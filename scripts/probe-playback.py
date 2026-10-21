#!/usr/bin/env python3
"""Credential-free playback probe. A blocked/inconclusive probe is never a pass."""
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'app.revanced.android.youtube'
adb = str(Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME']) / 'platform-tools/adb')

def shell(*args):
    return subprocess.run([adb, '-s', 'emulator-5554', 'shell', *args], capture_output=True, text=True, timeout=25).stdout

def ui_status(dismiss=False):
    shell('uiautomator', 'dump', '/sdcard/playback-probe.xml')
    try:
        nodes = list(ET.fromstring(shell('cat', '/sdcard/playback-probe.xml')).iter('node'))
    except ET.ParseError:
        return {'uiParsed': False}
    labels = [(n.get('text', '') + ' ' + n.get('content-desc', '')).strip().lower() for n in nodes]
    combined = ' '.join(labels)
    status = {'uiParsed': True,
              'botVerification': "not a bot" in combined,
              'signInRequired': 'sign in to confirm' in combined,
              'networkError': any(t in combined for t in ("you're offline", 'no connection', 'network error')),
              'integrityDialog': 'checks failed' in combined,
              'playerError': any(t in combined for t in ('something went wrong', 'playback error', 'video unavailable'))}
    if dismiss:
        for n in nodes:
            if n.get('text', '').strip().lower() in ('ignore', 'not now', 'later', 'got it'):
                bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', n.get('bounds', ''))
                if bounds:
                    l, t, r, b = map(int, bounds.groups())
                    shell('input', 'tap', str((l+r)//2), str((t+b)//2))
                    status['dismissedNonessentialDialog'] = True
                    break
    return status


def probe(output):
    report = {'authenticated': False, 'passed': False, 'videoId': 'aqz-KE-bpKQ', 'samples': [],
              'scope': 'Public video, screen off before position 60 seconds; not an authenticated-device test'}
    report['initialUi'] = ui_status(dismiss=True)
    shell('am', 'start', '-W', '-a', 'android.intent.action.VIEW', '-d',
          'https://www.youtube.com/watch?v=aqz-KE-bpKQ', '-p', PACKAGE)
    started = time.monotonic()
    screen_off = False
    try:
        while time.monotonic() - started < 160:
            elapsed = round(time.monotonic() - started)
            raw = shell('dumpsys', 'media_session')
            # Fresh disposable emulator, only this third-party media app installed.
            matches = re.findall(r'PlaybackState\s*\{state=(\d+)(?:\([^)]*\))?,\s*position=(-?\d+)', raw) if PACKAGE in raw else []
            sample = {'elapsedSeconds': elapsed, 'states': [[int(a), int(b)] for a, b in matches]}
            if not screen_off and elapsed < 30:
                sample['ui'] = ui_status(dismiss=True)
            report['samples'].append(sample)
            if not screen_off and any(int(a) == 3 and 1000 < int(b) < 55000 for a, b in matches):
                shell('input', 'keyevent', 'KEYCODE_SLEEP')
                screen_off = True
                report['screenOffAt'] = sample
            time.sleep(5)
        report['screenTurnedOffBefore60Seconds'] = screen_off
        # A clock extrapolation is insufficient: require actual reported media position advances.
        report['reportedPositionPast120Seconds'] = any(a == 3 and b > 120000 for s in report['samples'] for a, b in s['states'])
        report['passed'] = screen_off and report['reportedPositionPast120Seconds']
        if not report['passed']:
            report['reason'] = 'Playback not demonstrated; network/login gating or stale media-session data may make this inconclusive.'
        shell('input', 'keyevent', 'KEYCODE_WAKEUP')
        shell('wm', 'dismiss-keyguard')
        report['finalUi'] = ui_status()
        # Disposable, unsigned-in device only: capture the actual screen for failure diagnosis.
        subprocess.run([adb, '-s', 'emulator-5554', 'exec-out', 'screencap', '-p'],
                       stdout=(output / 'playback-screen.png').open('wb'), timeout=25, check=True)
    finally:
        (output / 'playback-probe.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'playbackProbe': report}, indent=2), flush=True)
    if not report['passed']:
        raise RuntimeError('Public playback probe did not demonstrate uninterrupted playback beyond 120 seconds')
