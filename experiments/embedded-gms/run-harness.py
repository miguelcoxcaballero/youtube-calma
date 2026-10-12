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
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'es.inhouse.gmsprobe'


def login_ui_structure(nodes) -> dict:
    """Classify a fresh login UI without retaining remote or user-entered text."""
    texts = {(node.get(attribute) or '').casefold().strip() for node in nodes
             for attribute in ('text', 'content-desc', 'hint')}
    credential = bool(texts & {'email or phone', 'email address', 'enter your email', 'email'})
    credential = credential or any((node.get('resource-id') or '').split('/')[-1] == 'identifierId'
                                   for node in nodes)
    return {
        'nodeCount': len(nodes),
        'webViewVisible': any(node.get('class') == 'android.webkit.WebView' for node in nodes),
        'editableFieldCount': sum('EditText' in node.get('class', '') for node in nodes),
        'credentialLabelOrIdRecognized': credential,
        'progressIndicatorVisible': any('ProgressBar' in node.get('class', '') for node in nodes),
        'connectingMessageVisible': any('establishing a connection to google' in text for text in texts),
        'justASecVisible': any(text in {'just a sec', 'just a sec…', 'just a sec...'} for text in texts),
        'sorryVisible': 'sorry' in texts,
        'nextButtonVisible': 'next' in texts,
        'networkErrorVisible': any('problem communicating with google servers' in text
                                  or 'check your network connection' in text for text in texts),
    }


def structural_crash_log(raw: str) -> str:
    """Retain stack structure only, never upstream request/response bodies or URLs."""
    result = []
    for line in raw.splitlines():
        entry = re.search(r'\s(?:AndroidRuntime|GmsAuthLoginBrowser|GmsCheckin(?:Client|Svc|Trigger)?)\s*:\s*(.*)', line)
        if entry is None: continue
        message = entry[1]
        if message.startswith('pageFinished:'):
            result.append('login page-finished callback [URL omitted]')
        elif message.startswith('shouldOverrideUrlLoading:'):
            result.append('login navigation callback [URL omitted]')
        elif message.startswith('-- Request --'):
            result.append('check-in request started [request omitted]')
        elif message == 'Checkin failed':
            result.append('check-in failed')
        elif message == 'JSBridge: showView':
            result.append('login show-view callback')
        elif message.startswith('FATAL EXCEPTION:'):
            result.append('FATAL EXCEPTION')
        elif message.startswith('Process:') and PACKAGE in message:
            result.append('Process: ' + (PACKAGE + ':microg' if PACKAGE + ':microg' in message else PACKAGE))
        elif re.fullmatch(r'\s*at [A-Za-z0-9_.$<>]+\([A-Za-z0-9_.$ :\-]+\)', message):
            result.append(message.strip())
        else:
            error = re.match(r'(?:Caused by: )?([A-Za-z0-9_.$]+(?:Exception|Error))(?::|$)', message)
            if error: result.append(error[1] + ': [message omitted]')
    return '\n'.join(result) + '\n'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', type=int, choices=(35, 36), required=True)
    parser.add_argument('--apk', type=Path, default=ROOT / 'build/embedded-gms/Embedded-Gms-Probe.apk')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/embedded-gms/evidence')
    parser.add_argument('--login-entry', action='store_true', help='Probe sign-in entry only; never enter credentials')
    args = parser.parse_args()
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
    adb_binary = sdk / 'platform-tools/adb'
    report = {'api': args.api, 'googleLoginTested': False, 'youtubePlaybackTested': False,
              'pushDeliveryTested': False, 'accountsOrCredentialsSupplied': False, 'productionRelease': False,
              'onlineEntryStarted': False}
    emulator = None
    online_started = False

    def run(*command, timeout=60, **kwargs):
        return subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=timeout, **kwargs)

    def require(result, name):
        (output / (name + '.txt')).write_text(result.stdout + '\n' + result.stderr)
        if result.returncode: raise RuntimeError(name + ' failed: ' + result.stdout + result.stderr)
        return result.stdout

    def adb(*command, **kwargs):
        return run(adb_binary, '-s', 'emulator-5554', *command, **kwargs)

    def ui_nodes():
        # Accessibility XML is held only in memory. Do not upload raw dynamic text,
        # page URLs, cookies, check-in IDs, or any other remote response content.
        dumped = adb('shell', 'uiautomator', 'dump', '/sdcard/probe-ui.xml', timeout=25)
        if dumped.returncode: return []
        raw = adb('shell', 'cat', '/sdcard/probe-ui.xml').stdout
        try: return list(ET.fromstring(raw).iter('node'))
        except ET.ParseError: return []

    def login_entry():
        nonlocal online_started
        state = {'credentialsEntered': False, 'googleLoginTested': False,
                 'introVisible': False, 'googleCredentialEntryVisible': False,
                 'deviceCheckinMayContactGoogle': True}
        report['loginEntry'] = state
        # Core component checks cannot contact Google or receive credentials. Keep
        # their useful diagnostics separate before clearing all logs for online UI.
        (output / 'component-logcat.txt').write_text(adb('logcat', '-b', 'all', '-d').stdout)
        adb('logcat', '-b', 'all', '-c')
        online_started = True
        report['onlineEntryStarted'] = True
        launch = adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/org.microg.gms.auth.login.LoginActivity',
                     '--es', 'tmpl', 'new_account')
        if launch.returncode: raise RuntimeError('Embedded login entry activity failed to start')
        deadline = time.monotonic() + 30; button = None
        while time.monotonic() < deadline:
            nodes = ui_nodes()
            button = next((node for node in nodes if any((node.get(attribute) or '').casefold().strip() == 'sign in'
                           for attribute in ('text', 'content-desc', 'hint'))
                           and 'Button' in node.get('class', '')), None)
            if button is not None: break
            if not adb('shell', 'pidof', PACKAGE + ':microg').stdout.strip(): break
            time.sleep(1)
        if button is None: raise RuntimeError('Embedded login intro did not render a Sign in button')
        state['introVisible'] = True
        bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', button.get('bounds', ''))
        if not bounds: raise RuntimeError('Sign in button has invalid bounds')
        left, top, right, bottom = map(int, bounds.groups())
        tap = adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))
        if tap.returncode: raise RuntimeError('Cannot activate sign-in entry button')
        state['signInButtonActivated'] = True
        state['uiStates'] = []
        deadline = time.monotonic() + 75
        while time.monotonic() < deadline:
            structure = login_ui_structure(ui_nodes())
            if not state['uiStates'] or state['uiStates'][-1] != structure:
                state['uiStates'].append(structure)
            if structure['webViewVisible'] and structure['credentialLabelOrIdRecognized']:
                state['googleCredentialEntryVisible'] = True
                break
            if structure['networkErrorVisible']:
                state['networkErrorVisible'] = True
                break
            if not adb('shell', 'pidof', PACKAGE + ':microg').stdout.strip():
                state['backendExited'] = True
                break
            time.sleep(2)
        # Stop before any account name/password entry. This checks a landing page,
        # never authentication, token refresh, push, or YouTube account access.
        adb('shell', 'am', 'force-stop', PACKAGE)
        if not state['googleCredentialEntryVisible']:
            raise RuntimeError('Google credential entry did not appear in the embedded login probe')

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
        if args.login_entry: login_entry()
        logs = adb('logcat', '-b', 'all', '-d').stdout
        (output / 'logcat.txt').write_text(structural_crash_log(logs) if online_started else logs)
        if re.search(r'Process: ' + re.escape(PACKAGE) + r'[:,\s]|ANR in ' + re.escape(PACKAGE), logs):
            raise RuntimeError('Probe app or backend process crashed/ANR')
        report['passed'] = True
    except Exception as error:
        report['passed'] = False; report['error'] = str(error)
        (output / 'error.txt').write_text(traceback.format_exc())
    finally:
        try:
            logs = adb('logcat', '-b', 'all', '-d').stdout
            (output / 'final-logcat.txt').write_text(structural_crash_log(logs) if online_started else logs)
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
