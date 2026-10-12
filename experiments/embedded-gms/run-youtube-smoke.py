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


def installed_identity(package_dump: str) -> dict:
    """Extract fixed installation metadata, never package/account diagnostic text."""
    code = re.search(r'^\s*versionCode=(\d+)\b', package_dump, re.MULTILINE)
    uid = re.search(r'^\s*(?:userId|appId)=(\d+)\b', package_dump, re.MULTILINE)
    first = re.search(r'^\s*firstInstallTime=(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})\s*$',
                      package_dump, re.MULTILINE)
    if code is None or uid is None or first is None:
        raise RuntimeError('Cannot establish installed package version, UID and first install time')
    return {'versionCode': int(code[1]), 'uid': int(uid[1]), 'firstInstallTime': first[1]}


def verify_upgrade_identity(baseline: dict, candidate: dict) -> None:
    if candidate['versionCode'] <= baseline['versionCode']:
        raise RuntimeError('Candidate Android versionCode is not strictly newer than baseline')
    if candidate['uid'] != baseline['uid'] or candidate['firstInstallTime'] != baseline['firstInstallTime']:
        raise RuntimeError('Upgrade did not preserve the original installed package identity')


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
    web_nodes = {child for node in nodes if node.get('class') == 'android.webkit.WebView'
                 for child in node.iter('node')}
    web_texts = {re.sub(r'\s+', ' ', (node.get(attribute) or '').casefold()).strip()
                 for node in web_nodes for attribute in ('text', 'content-desc', 'hint')}

    def usable_field(node):
        bounds = re.fullmatch(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', node.get('bounds', ''))
        if bounds is None:
            return False
        left, top, right, bottom = map(int, bounds.groups())
        return (right > left and bottom > top and node.get('enabled') == 'true'
                and node.get('focusable') == 'true' and node.get('password') != 'true')

    structure = {
        'nodeCount': len(nodes),
        'webViewVisible': any(node.get('class') == 'android.webkit.WebView' for node in nodes),
        'editableFieldCount': len(editable),
        'passwordFieldCount': sum(node.get('password') == 'true' for node in editable),
        'usableIdentifierFieldCount': sum(usable_field(node) and node in web_nodes for node in editable),
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
    # Some Google identifier pages expose an empty editable node without its
    # HTML label. Require the complete sign-in schema inside the same WebView;
    # a generic input, a password/challenge or the native loading screen is not
    # sufficient. Report this separately from actual label/identifier evidence.
    structure['credentialEntryLayoutRecognized'] = (
        len(editable) == 1 and structure['usableIdentifierFieldCount'] == 1
        and {'sign in', 'next'} <= web_texts and bool({'forgot email', 'forgot email?'} & web_texts)
        and not structure['captchaPromptVisible'] and not structure['networkErrorVisible'])
    return structure


def credential_page_visible(structure: dict) -> bool:
    return (structure['webViewVisible'] and structure['usableIdentifierFieldCount'] > 0
            and structure['passwordFieldCount'] == 0
            and (structure['credentialLabelOrIdRecognized'] or structure['credentialEntryLayoutRecognized'])
            and not structure['captchaPromptVisible'] and not structure['networkErrorVisible'])


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
            suffix = (':inhouse_gms_unstable' if PACKAGE + ':inhouse_gms_unstable' in message else
                      ':inhouse_gms' if PACKAGE + ':inhouse_gms' in message else '')
            result.append('Process: ' + PACKAGE + suffix)
        elif re.fullmatch(r'\s*at [A-Za-z0-9_.$<>]+\([A-Za-z0-9_.$ :\-]+\)', message):
            result.append(message.strip())
        else:
            error = re.match(r'(?:Caused by: )?([A-Za-z0-9_.$]+(?:Exception|Error))(?::|$)', message)
            if error:
                result.append(error[1] + ': [message omitted]')
    return '\n'.join(result) + '\n'


def structural_anr(raw: str) -> str:
    """Keep Java call stacks and fixed thread roles, never ANR subjects or messages."""
    frames = []
    for line in raw.splitlines():
        stripped = line.strip()
        if stripped.startswith('Cmd line: '):
            process = stripped.removeprefix('Cmd line: ').strip()
            role = ('YouTube main' if process == PACKAGE else
                    'embedded backend' if process == PACKAGE + ':inhouse_gms' else
                    'DroidGuard backend' if process == PACKAGE + ':inhouse_gms_unstable' else
                    'official Google Play services' if process == 'com.google.android.gms'
                    or process.startswith('com.google.android.gms:') else 'other')
            frames.append('[' + role + ' process]')
        elif stripped.startswith('"main"'):
            frames.append('[main thread]')
        elif stripped.startswith('"Instr:'):
            frames.append('[instrumentation thread]')
        elif re.match(r'^".*" (?:daemon )?prio=\d+ tid=\d+', stripped):
            frames.append('[other thread]')
        elif re.fullmatch(r'at [A-Za-z0-9_.$<>]+\([A-Za-z0-9_.$ :\-]+\)', stripped):
            frames.append(stripped)
        elif re.fullmatch(r'- (?:waiting to lock|locked|waiting on|waiting to re-lock in wait\(\)) <0x[0-9a-f]+> \(a [A-Za-z0-9_.$<>]+\)(?: held by thread \d+)?', stripped):
            frames.append(re.sub(r'<0x[0-9a-f]+>', '<object>', stripped))
        if len(frames) >= 600:
            break
    return '\n'.join(frames) + '\n'


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', type=int, choices=(34, 35, 36), required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/youtube-embedded-smoke/evidence')
    parser.add_argument('--existing-emulator', action='store_true', help='Use an already running disposable emulator-5554')
    parser.add_argument('--diagnostic-stacks', action='store_true',
                        help='After failure only, use disposable emulator su to collect sanitized Java stacks')
    parser.add_argument('--disable-official-gms', action='store_true',
                        help='Disable system Google Play Services on this disposable emulator to expose missed routing')
    parser.add_argument('--components-only', action='store_true', help='Stop before opening the sign-in entry UI')
    parser.add_argument('--main-only', action='store_true',
                        help='Check normal launch of a signed APK without test instrumentation')
    parser.add_argument('--upgrade-from', type=Path,
                        help='Install this pinned baseline first, then replace it with the candidate using -r')
    parser.add_argument('--upgrade-sha256', help='Required exact SHA-256 of --upgrade-from')
    parser.add_argument('--expected-version-code', type=int,
                        help='Require this actual Android versionCode after candidate installation')
    args = parser.parse_args()
    if bool(args.upgrade_from) != bool(args.upgrade_sha256):
        parser.error('--upgrade-from and --upgrade-sha256 must be supplied together')
    if args.upgrade_sha256 and not re.fullmatch(r'[0-9a-fA-F]{64}', args.upgrade_sha256):
        parser.error('--upgrade-sha256 must contain exactly 64 hexadecimal characters')
    if args.main_only and args.components_only:
        parser.error('--main-only and --components-only describe different test scopes')
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
    if args.main_only:
        report['scope'] = 'signed YouTube APK installation and native main-process launch only'
        report['instrumentationTested'] = False

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
        if args.upgrade_from:
            with args.upgrade_from.open('rb') as stream:
                baseline_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
            if baseline_hash != args.upgrade_sha256.lower():
                raise RuntimeError('Baseline APK does not match its pinned SHA-256')
            report['upgrade'] = {'baselineSha256': baseline_hash}
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
        baseline_identity = None
        if args.upgrade_from:
            require(adb('install', '--no-incremental', args.upgrade_from, timeout=240),
                    'Pinned baseline APK installation failed')
            if package_set() - before != {PACKAGE}:
                raise RuntimeError('Baseline installation changed unexpected packages')
            baseline_identity = installed_identity(require(adb('shell', 'dumpsys', 'package', PACKAGE),
                                                            'Baseline package inspection failed'))
            report['upgrade']['baseline'] = baseline_identity
        install_args = ['install', '--no-incremental']
        if args.upgrade_from:
            install_args.append('-r')
        require(adb(*install_args, args.apk, timeout=240), 'Candidate APK installation failed')
        candidate_identity = installed_identity(require(adb('shell', 'dumpsys', 'package', PACKAGE),
                                                         'Candidate package inspection failed'))
        report['installedCandidate'] = candidate_identity
        if args.expected_version_code is not None and candidate_identity['versionCode'] != args.expected_version_code:
            raise RuntimeError('Installed Android versionCode differs from the expected candidate')
        if baseline_identity is not None:
            verify_upgrade_identity(baseline_identity, candidate_identity)
            report['upgrade'].update({'candidate': candidate_identity, 'strictlyNewer': True,
                                      'uidPreserved': True, 'firstInstallTimePreserved': True,
                                      'replacedExistingInstall': True})
        after = package_set()
        if after - before != {PACKAGE} or after & FORBIDDEN:
            raise RuntimeError('Candidate installation changed unexpected packages')
        report['onlyCandidateApkInstalled'] = not bool(args.upgrade_from)
        report['onlyYouTubePackageInstalled'] = True
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
        if not args.main_only:
            adb('logcat', '-b', 'all', '-c')
            # Instrumentation validates the actual Application instance, real provider
            # calls, account registration and token Binder. It belongs to this APK and
            # adds no second installed package. Its output contains fixed structural data.
            with tempfile.TemporaryFile(mode='w+t') as instrumentation_output:
                # Raw mode preserves every Bundle key even when Android's special
                # "stream" result is present; pretty mode suppresses the JSON report.
                command = [str(adb_binary), '-s', 'emulator-5554', 'shell', 'am', 'instrument', '-w', '-r']
                if not args.components_only:
                    command += ['-e', 'openLogin', 'true']
                command += [INSTRUMENTATION]
                instrument = subprocess.Popen(command, stdout=instrumentation_output, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 90
                component = None
                while time.monotonic() < deadline:
                    instrumentation_output.seek(0)
                    raw = instrumentation_output.read()
                    phases = re.findall(r'^INSTRUMENTATION_STATUS: youtubeEmbeddedPhase=([a-z_]+)$', raw, re.MULTILINE)
                    if phases:
                        report['instrumentationPhases'] = phases
                    progress = re.findall(r'^INSTRUMENTATION_STATUS: youtubeEmbeddedProgress=(\[[^\r\n]+\])$', raw, re.MULTILINE)
                    if progress:
                        report['incompleteComponentChecks'] = json.loads(progress[-1])
                    report['instrumentationProcessCrashed'] = 'shortMsg=Process crashed.' in raw
                    report['instrumentationFinalCodeSeen'] = bool(re.search(r'^INSTRUMENTATION_CODE:', raw, re.MULTILINE))
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
                        if credential_page_visible(structure):
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
            if not report.get('passed'):
                (output / 'anr-frames.txt').write_text(structural_anr(
                    adb('shell', 'dumpsys', 'activity', 'lastanr-traces', timeout=20).stdout))
                if args.diagnostic_stacks:
                    # Userdebug-only shell privilege begins after the test failed.
                    # Never restart adbd: doing so drops am instrument's watcher,
                    # which can force-stop the very process we need to inspect.
                    # The candidate UID and granted permissions remain unchanged.
                    privileged = adb('shell', 'su', '0', 'id', '-u', timeout=10)
                    available = privileged.returncode == 0 and privileged.stdout.strip() == '0'
                    report['postFailureDiagnosticSuAvailable'] = available
                    report['postFailureStackCaptures'] = {}
                    for label, process in (('main', PACKAGE), ('backend', PACKAGE + ':inhouse_gms'),
                                           ('droidguard', PACKAGE + ':inhouse_gms_unstable')):
                        process_ids = adb('shell', 'pidof', process).stdout.strip().split()
                        present = len(process_ids) == 1 and process_ids[0].isdigit()
                        capture = {'processPresent': present}
                        if available and present:
                            traces = adb('shell', 'su', '0', 'debuggerd', '-j', process_ids[0], timeout=30)
                            sanitized = structural_anr(traces.stdout)
                            (output / (label + '-java-frames.txt')).write_text(sanitized)
                            capture.update({'exitCode': traces.returncode,
                                            'javaFramesPresent': bool(sanitized.strip())})
                        report['postFailureStackCaptures'][label] = capture
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
