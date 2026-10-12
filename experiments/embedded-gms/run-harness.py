#!/usr/bin/env python3
"""Exercise the standalone probe on a clean API 35/36 emulator without external microG."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import traceback

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'es.inhouse.gmsprobe'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', type=int, choices=(35, 36), required=True)
    parser.add_argument('--apk', type=Path, default=ROOT / 'build/embedded-gms/Embedded-Gms-Probe.apk')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/embedded-gms/evidence')
    args = parser.parse_args()
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
    adb_binary = sdk / 'platform-tools/adb'
    report = {'api': args.api, 'googleLoginTested': False, 'youtubePlaybackTested': False,
              'pushDeliveryTested': False, 'accountsOrCredentialsSupplied': False, 'productionRelease': False}
    emulator = None

    def run(*command, timeout=60, **kwargs):
        return subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=timeout, **kwargs)

    def require(result, name):
        (output / (name + '.txt')).write_text(result.stdout + '\n' + result.stderr)
        if result.returncode: raise RuntimeError(name + ' failed: ' + result.stdout + result.stderr)
        return result.stdout

    def adb(*command, **kwargs):
        return run(adb_binary, '-s', 'emulator-5554', *command, **kwargs)

    try:
        with args.apk.open('rb') as stream: report['apkSha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        image = f'system-images;android-{args.api};google_apis;x86_64'
        require(run(sdk / 'cmdline-tools/latest/bin/sdkmanager', '--licenses', input='y\n' * 100, timeout=180), 'licenses')
        require(run(sdk / 'cmdline-tools/latest/bin/sdkmanager', 'platform-tools', 'emulator', image, timeout=600), 'sdk')
        avdhome = output.parent / ('avd-' + str(args.api)); avdhome.mkdir(exist_ok=True)
        os.environ['ANDROID_AVD_HOME'] = str(avdhome)
        require(run(sdk / 'cmdline-tools/latest/bin/avdmanager', 'create', 'avd', '--force', '--name', 'gmsprobe',
                    '--package', image, '--device', 'pixel_2', '--path', avdhome / 'gmsprobe.avd', input='no\n'), 'avd')
        with (output / 'emulator.txt').open('w') as emulator_log:
            emulator = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', 'gmsprobe', '-port', '5554',
                '-accel', 'on', '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot', '-no-metrics',
                '-gpu', 'swiftshader', '-cores', '2', '-memory', '3072'], stdout=emulator_log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if emulator.poll() is not None: raise RuntimeError('Emulator exited')
            if adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() == '1': break
            time.sleep(2)
        else: raise RuntimeError('Emulator boot timeout')
        packages = require(adb('shell', 'pm', 'list', 'packages'), 'packages-before')
        installed = set(re.findall(r'^package:(\S+)', packages, re.MULTILINE))
        forbidden = {'app.revanced.android.gms', 'com.mgoogle.android.gms'}
        if installed & forbidden: raise RuntimeError('Test device contains separately installed microG')
        report['externalMicrogAbsentBefore'] = True
        report['officialGooglePlayServicesPresent'] = 'com.google.android.gms' in installed
        require(adb('install', '--no-incremental', args.apk, timeout=180), 'install-single-probe-apk')
        adb('logcat', '-b', 'all', '-c')
        require(adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.ProbeActivity'), 'launch')
        time.sleep(3)
        result = require(adb('shell', 'am', 'instrument', '-w', PACKAGE + '/.ProbeInstrumentation', timeout=120), 'instrumentation')
        match = re.search(r'INSTRUMENTATION_RESULT: probeReport=(\{[^\r\n]+\})', result)
        if not match:
            fallback = adb('shell', 'run-as', PACKAGE, 'cat', 'files/probe-report.json')
            if fallback.returncode: raise RuntimeError('No component probe report: ' + result)
            component = json.loads(fallback.stdout)
        else: component = json.loads(match[1])
        report['components'] = component
        if 'EMBEDDED_GMS_COMPONENTS_OK' not in result or not component.get('passed'):
            raise RuntimeError('Embedded component probe failed: ' + component.get('error', result))
        final_packages = require(adb('shell', 'pm', 'list', 'packages'), 'packages-after')
        after = set(re.findall(r'^package:(\S+)', final_packages, re.MULTILINE))
        if after & forbidden: raise RuntimeError('A separate microG package appeared during test')
        added = after - installed
        if added != {PACKAGE}: raise RuntimeError('Unexpected packages installed during probe: ' + repr(added))
        report['onlyProbeApkInstalled'] = True
        require(adb('shell', 'dumpsys', 'package', PACKAGE), 'package-components')
        require(adb('shell', 'dumpsys', 'account'), 'registered-authenticators')
        logs = require(adb('logcat', '-b', 'all', '-d'), 'logcat')
        if re.search(r'Process: ' + re.escape(PACKAGE) + r'[:,\s]|ANR in ' + re.escape(PACKAGE), logs):
            raise RuntimeError('Probe app or backend process crashed/ANR')
        report['passed'] = True
    except Exception as error:
        report['passed'] = False; report['error'] = str(error)
        (output / 'error.txt').write_text(traceback.format_exc())
    finally:
        try: (output / 'final-logcat.txt').write_text(adb('logcat', '-b', 'all', '-d').stdout)
        except Exception: pass
        if emulator is not None:
            emulator.terminate()
            try: emulator.wait(timeout=15)
            except subprocess.TimeoutExpired: emulator.kill()
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    raise SystemExit(0 if report['passed'] else 1)


if __name__ == '__main__':
    main()
