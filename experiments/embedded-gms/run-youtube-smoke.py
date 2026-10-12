#!/usr/bin/env python3
"""Credential-free smoke test of the actual experimental YouTube single-APK build.

This never installs external microG and never enters account credentials. A pass
means component/bootstrap/login-entry evidence only, not authenticated playback,
account migration, token refresh or notification delivery.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = 'app.revanced.android.youtube'
INSTRUMENTATION = PACKAGE + '/inhouse.microg.host.YouTubeSmokeInstrumentation'
FORBIDDEN = {'app.revanced.android.gms', 'com.mgoogle.android.gms'}


def login_ui_structure(nodes) -> dict:
    """Classify a fresh login UI without retaining remote or user-entered text."""
    texts = {re.sub(r'\s+', ' ', (node.get(attribute) or '').casefold()).strip() for node in nodes
             for attribute in ('text', 'content-desc', 'hint')}
    # WebView accessibility may combine a label and helper text, or add "number".
    # Recognize only fixed sign-in phrases and explicit editable-field semantics.
    credential = any(any(phrase in text for phrase in
                         ('email or phone', 'phone or email', 'enter your email', 'enter an email'))
                     for text in texts)
    editable = [node for node in nodes if 'EditText' in node.get('class', '')]
    editable_texts = {(node.get(attribute) or '').casefold() for node in editable
                      for attribute in ('text', 'content-desc', 'hint')}
    credential = credential or any(re.search(r'\be-?mail(?: address)?\b', text) for text in editable_texts)
    credential = credential or any((node.get('resource-id') or '').split('/')[-1] == 'identifierId'
                                   for node in nodes)
    return {
        'nodeCount': len(nodes),
        'webViewVisible': any(node.get('class') == 'android.webkit.WebView' for node in nodes),
        'editableFieldCount': len(editable),
        'credentialLabelOrIdRecognized': credential,
        'emailWordVisible': any(re.search(r'\be-?mail\b', text) for text in texts),
        'phoneWordVisible': any(re.search(r'\bphone\b', text) for text in texts),
        'signInHeadingVisible': 'sign in' in texts,
        'forgotEmailVisible': any('forgot email' in text for text in texts),
        'captchaPromptVisible': any('type the text you hear or see' in text
                                   or 'enter the text you see' in text
                                   or 'not a robot' in text for text in texts),
        'progressIndicatorVisible': any('ProgressBar' in node.get('class', '') for node in nodes),
        'connectingMessageVisible': any('establishing a connection to google' in text for text in texts),
        'justASecVisible': any(text in {'just a sec', 'just a sec…', 'just a sec...'} for text in texts),
        'sorryVisible': 'sorry' in texts,
        'nextButtonVisible': 'next' in texts,
        'networkErrorVisible': any('problem communicating with google servers' in text
                                  or 'check your network connection' in text for text in texts),
    }


def structural_log(raw: str) -> str:
    """Allowlisted crash structure/timings only; omit upstream messages and payloads."""
    result = []
    for line in raw.splitlines():
        if 'InhouseGmsRuntime' in line:
            timing = re.search(r'\b(start|assets_verified|libraries_verified|loader_ready|resources_ready|environment_ready) elapsed_ms=(\d+)\b', line)
            if timing:
                result.append('bootstrap ' + timing[1] + ' elapsed_ms=' + timing[2])
        if PACKAGE in line and 'timeout publishing content providers' in line:
            result.append('backend startup failed: timeout publishing content providers')
        if 'ANR in ' + PACKAGE in line:
            result.append('ANR in YouTube')
        entry = re.search(r'\s(?:AndroidRuntime|GmsAuthLoginBrowser|GmsCheckin)\s*:\s*(.*)', line)
        if entry is None:
            continue
        message = entry[1]
        if message.startswith('FATAL EXCEPTION:'):
            result.append('FATAL EXCEPTION')
        elif message.startswith('Process:') and PACKAGE in message:
            result.append('Process: ' + (PACKAGE + ':inhouse_gms' if PACKAGE + ':inhouse_gms' in message else PACKAGE))
        elif re.fullmatch(r'\s*at [A-Za-z0-9_.$<>]+\([A-Za-z0-9_.$ :\-]+\)', message):
            result.append(message.strip())
        else:
            error = re.match(r'(?:Caused by: )?([A-Za-z0-9_.$]+(?:Exception|Error))(?::|$)', message)
            if error:
                result.append(error[1] + ': [message omitted]')
    return '\n'.join(result) + '\n'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', type=int, choices=(34, 35, 36), required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/youtube-embedded-smoke/evidence')
    parser.add_argument('--existing-emulator', action='store_true', help='Use an already running disposable emulator-5554')
    parser.add_argument('--disable-official-gms', action='store_true',
                        help='Disable system Google Play Services on this disposable emulator to expose missed routing')
    parser.add_argument('--components-only', action='store_true', help='Stop before opening the sign-in entry UI')
    args = parser.parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
    adb_binary = sdk / 'platform-tools/adb'
    emulator = None
    instrument = None
    report = {
        'api': args.api, 'package': PACKAGE, 'productionRelease': False,
        'googleLoginTested': False, 'youtubePlaybackTested': False, 'pushDeliveryTested': False,
        'accountsOrCredentialsSupplied': False, 'googleCredentialEntryVisible': False,
        'scope': 'actual YouTube APK, embedded component bootstrap, credential-free sign-in entry',
    }

    def run(*command, timeout=60, **kwargs):
        return subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=timeout, **kwargs)

    def adb(*command, **kwargs):
        return run(adb_binary, '-s', 'emulator-5554', *command, **kwargs)

    def require(result, label):
        # Never save raw shell/UI/logcat output. A fresh YouTube process can contact
        # upstream services immediately, before an explicit sign-in screen opens.
        if result.returncode:
            raise RuntimeError(label)
        return result.stdout

    def package_set():
        return set(re.findall(r'^package:(\S+)', require(adb('shell', 'pm', 'list', 'packages'),
                                                       'Package listing failed'), re.MULTILINE))

    def nodes():
        dumped = adb('shell', 'uiautomator', 'dump', '/sdcard/inhouse-smoke-ui.xml', timeout=20)
        if dumped.returncode:
            return []
        raw = adb('shell', 'cat', '/sdcard/inhouse-smoke-ui.xml').stdout
        try:
            return list(ET.fromstring(raw).iter('node'))
        except ET.ParseError:
            return []

    try:
        with args.apk.open('rb') as stream:
            report['apkSha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        if not args.existing_emulator:
            image = f'system-images;android-{args.api};google_apis;x86_64'
            require(run(sdk / 'cmdline-tools/latest/bin/sdkmanager', '--licenses', input='y\n' * 100,
                        timeout=180), 'SDK license setup failed')
            require(run(sdk / 'cmdline-tools/latest/bin/sdkmanager', 'platform-tools', 'emulator', image,
                        timeout=600), 'SDK image setup failed')
            avdhome = output.parent / ('avd-youtube-' + str(args.api))
            avdhome.mkdir(exist_ok=True)
            os.environ['ANDROID_AVD_HOME'] = str(avdhome)
            require(run(sdk / 'cmdline-tools/latest/bin/avdmanager', 'create', 'avd', '--force', '--name',
                        'youtubeembedded', '--package', image, '--device', 'pixel_2', '--path',
                        avdhome / 'youtubeembedded.avd', input='no\n'), 'AVD creation failed')
            emulator = subprocess.Popen([str(sdk / 'emulator/emulator'), '-avd', 'youtubeembedded',
                '-port', '5554', '-accel', 'on', '-no-window', '-no-audio', '-no-boot-anim', '-no-snapshot',
                '-no-metrics', '-gpu', 'swiftshader', '-cores', '2', '-memory', '4096'],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        deadline = time.monotonic() + 300
        while time.monotonic() < deadline:
            if emulator is not None and emulator.poll() is not None:
                raise RuntimeError('Emulator exited during boot')
            if adb('shell', 'getprop', 'sys.boot_completed').stdout.strip() == '1':
                break
            time.sleep(2)
        else:
            raise RuntimeError('Emulator boot timeout')
        if adb('shell', 'getprop', 'ro.build.version.sdk').stdout.strip() != str(args.api):
            raise RuntimeError('Requested Android version does not match emulator')
        before = package_set()
        if before & FORBIDDEN or PACKAGE in before:
            raise RuntimeError('Test requires a clean device without YouTube Calma or external microG')
        report['externalMicrogAbsentBefore'] = True
        report['officialGooglePlayServicesPresent'] = 'com.google.android.gms' in before
        report['officialGooglePlayServicesDisabled'] = False
        if args.disable_official_gms:
            if not report['officialGooglePlayServicesPresent']:
                raise RuntimeError('Requested official-services isolation requires the Google APIs test image')
            require(adb('shell', 'pm', 'disable-user', '--user', '0', 'com.google.android.gms'),
                    'Cannot disable system Google Play Services on disposable emulator')
            disabled = set(re.findall(r'^package:(\S+)', require(adb('shell', 'pm', 'list', 'packages', '-d'),
                                                                'Disabled package listing failed'), re.MULTILINE))
            if 'com.google.android.gms' not in disabled:
                raise RuntimeError('System Google Play Services remained enabled')
            report['officialGooglePlayServicesDisabled'] = True
        require(adb('install', '--no-incremental', args.apk, timeout=240), 'Candidate APK installation failed')
        after = package_set()
        if after - before != {PACKAGE} or after & FORBIDDEN:
            raise RuntimeError('Candidate installation changed unexpected packages')
        report['onlyCandidateApkInstalled'] = True
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        adb('shell', 'appops', 'set', PACKAGE, 'POST_NOTIFICATION', 'allow')
        adb('logcat', '-b', 'all', '-c')
        launcher = require(adb('shell', 'cmd', 'package', 'resolve-activity', '--brief',
                               '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER',
                               PACKAGE), 'Launcher resolution failed').strip().splitlines()[-1]
        if not launcher.startswith(PACKAGE + '/'):
            raise RuntimeError('Candidate launcher does not belong to YouTube')
        require(adb('shell', 'am', 'start', '-W', '-n', launcher, timeout=90), 'YouTube main launch failed')
        time.sleep(12)
        if not adb('shell', 'pidof', PACKAGE).stdout.strip():
            raise RuntimeError('YouTube main process exited after launch')
        main_logs = adb('logcat', '-b', 'all', '-d').stdout
        (output / 'main-launch-log.txt').write_text(structural_log(main_logs))
        report['mainProcessStayedAliveAfterLaunch'] = True
        if re.search(r'Process: ' + re.escape(PACKAGE) + r'[:,\s]|ANR in ' + re.escape(PACKAGE), main_logs):
            raise RuntimeError('YouTube or embedded backend crashed during normal main launch')
        adb('logcat', '-b', 'all', '-c')
        # Instrumentation validates the actual Application instance, real provider
        # calls, account registration and token Binder. It belongs to this APK and
        # adds no second installed package. Its output contains fixed structural data.
        with tempfile.TemporaryFile(mode='w+t') as instrumentation_output:
            command = [str(adb_binary), '-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w']
            if not args.components_only:
                command += ['-e', 'openLogin', 'true']
            command += [INSTRUMENTATION]
            instrument = subprocess.Popen(command, stdout=instrumentation_output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 90
            component = None
            while time.monotonic() < deadline:
                instrumentation_output.seek(0)
                raw = instrumentation_output.read()
                match = re.search(r'INSTRUMENTATION_(?:STATUS|RESULT): youtubeEmbeddedReport=(\{[^\r\n]+\})', raw)
                if match:
                    component = json.loads(match[1])
                    break
                if instrument.poll() is not None:
                    break
                time.sleep(1)
            if component is None:
                raise RuntimeError('No structural report from experimental YouTube instrumentation')
            report['components'] = component
            if not component.get('passed'):
                raise RuntimeError('Actual YouTube embedded component checks failed')
            if not args.components_only:
                report['signInActivityLaunchRequested'] = True
                deadline = time.monotonic() + 30
                button = None
                while time.monotonic() < deadline:
                    button = next((node for node in nodes() if any((node.get(attribute) or '').casefold().strip() == 'sign in'
                                   for attribute in ('text', 'content-desc', 'hint'))
                                   and 'Button' in node.get('class', '')), None)
                    if button is not None:
                        break
                    time.sleep(1)
                if button is None:
                    raise RuntimeError('Embedded sign-in intro did not render')
                report['embeddedSignInIntroVisible'] = True
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', button.get('bounds', ''))
                if bounds is None:
                    raise RuntimeError('Embedded Sign in button has invalid bounds')
                left, top, right, bottom = map(int, bounds.groups())
                require(adb('shell', 'input', 'tap', (left + right) // 2, (top + bottom) // 2),
                        'Cannot activate sign-in entry')
                deadline = time.monotonic() + 90
                while time.monotonic() < deadline:
                    current = nodes()
                    structure = login_ui_structure(current)
                    report['loginUiStructure'] = structure
                    if (structure['webViewVisible'] and structure['editableFieldCount'] > 0
                            and structure['credentialLabelOrIdRecognized'] and not structure['captchaPromptVisible']):
                        report['googleCredentialEntryVisible'] = True
                        break
                    if structure['networkErrorVisible']:
                        report['networkErrorVisible'] = True
                        break
                    if not adb('shell', 'pidof', PACKAGE + ':inhouse_gms').stdout.strip():
                        report['backendExited'] = True
                        break
                    time.sleep(2)
                if not report['googleCredentialEntryVisible']:
                    raise RuntimeError('Google credential entry did not appear; authenticated operation remains untested')
        final = package_set()
        if final - before != {PACKAGE} or final & FORBIDDEN:
            raise RuntimeError('An unexpected external package appeared during the smoke test')
        report['externalMicrogAbsentAfter'] = True
        final_logs = adb('logcat', '-b', 'all', '-d').stdout
        if re.search(r'Process: ' + re.escape(PACKAGE) + r'[:,\s]|ANR in ' + re.escape(PACKAGE), final_logs):
            raise RuntimeError('YouTube or embedded backend crashed during the smoke test')
        report['passed'] = True
    except Exception as error:
        report['passed'] = False
        # This runner raises only fixed messages; dependency/process errors are
        # reduced to their type so arbitrary tool output never enters artifacts.
        report['error'] = str(error) if type(error) is RuntimeError else type(error).__name__
    finally:
        try:
            (output / 'structural-log.txt').write_text(structural_log(adb('logcat', '-b', 'all', '-d').stdout))
            adb('shell', 'rm', '-f', '/sdcard/inhouse-smoke-ui.xml')
            adb('shell', 'am', 'force-stop', PACKAGE)
        except Exception:
            pass
        if instrument is not None and instrument.poll() is None:
            instrument.terminate()
            try:
                instrument.wait(timeout=10)
            except subprocess.TimeoutExpired:
                instrument.kill()
        if emulator is not None:
            emulator.terminate()
            try:
                emulator.wait(timeout=15)
            except subprocess.TimeoutExpired:
                emulator.kill()
        (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    raise SystemExit(0 if report.get('passed') else 1)


if __name__ == '__main__':
    main()
