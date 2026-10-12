#!/usr/bin/env python3
"""Build a debug-only, single-APK embedded microG component probe.

This produces no YouTube release and changes no updater/catalog. The included
GmsCore remains an asset loaded by the same app; it is never installed separately.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
UPSTREAM = json.loads((HERE / 'upstream.lock.json').read_text())
GMS_URL = UPSTREAM['apkUrl']
GMS_SHA = UPSTREAM['apkSha256']
ECJ_URL = 'https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar'
ECJ_SHA = '01f5a92ac19bb2b3bf85e295a68f2c73c264369109158b566ce9b490af982948'


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def pinned(path: Path, url: str, expected: str) -> Path:
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        partial = path.with_suffix(path.suffix + '.partial')
        try:
            with urllib.request.urlopen(url, timeout=120) as response, partial.open('wb') as output:
                shutil.copyfileobj(response, output)
            if digest(partial) != expected:
                raise RuntimeError('Downloaded dependency SHA-256 mismatch: ' + path.name)
            partial.replace(path)
        finally:
            partial.unlink(missing_ok=True)
    if digest(path) != expected:
        raise RuntimeError('Dependency SHA-256 mismatch: ' + str(path))
    return path


def run(*args: str | Path, **kwargs) -> None:
    subprocess.run(list(map(str, args)), check=True, **kwargs)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime-source', type=Path, default=HERE / 'runtime')
    parser.add_argument('--gms-apk', type=Path)
    parser.add_argument('--build-tools', type=Path)
    parser.add_argument('--android-jar', type=Path)
    parser.add_argument('--ecj', type=Path)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/embedded-gms')
    parser.add_argument('--login-entry', action='store_true', help='Add optional unauthenticated login entry components')
    args = parser.parse_args()
    output = args.output_dir.resolve(); output.mkdir(parents=True, exist_ok=True)
    sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    build_tools = args.build_tools or (Path(sdk) / 'build-tools/36.0.0' if sdk else ROOT / 'tools/android-15')
    android = args.android_jar or (Path(sdk) / 'platforms/android-35/android.jar' if sdk else ROOT / 'tools/android-35/android.jar')
    if not android.is_file() or not (build_tools / 'aapt2').is_file():
        raise SystemExit('Android API 35 stubs/build tools missing. Pass --android-jar and --build-tools.')
    ecj_candidate = args.ecj or ROOT / 'tools/ecj.jar'
    ecj = pinned(ecj_candidate if ecj_candidate.is_file() else output / 'dependencies/ecj.jar', ECJ_URL, ECJ_SHA)
    gms = pinned(args.gms_apk or output / 'dependencies/gmscore.apk', GMS_URL, GMS_SHA)
    runtime = args.runtime_source.resolve()
    groups = {part: sorted((runtime / part).rglob('*.java')) for part in ('host', 'bridge', 'stubs')}
    if not all(groups.values()):
        raise SystemExit('Runtime source must contain host/, bridge/ and compile-only stubs/: ' + str(runtime))
    work = output / 'work'
    if work.exists(): shutil.rmtree(work)
    work.mkdir()
    classes = work / 'bridge-classes'; classes.mkdir()
    run('java', '-jar', ecj, '-source', '1.8', '-target', '1.8', '-proc:none', '-classpath', android,
        '-d', classes, *groups['bridge'], *groups['stubs'])
    bridge_jar = work / 'bridge.jar'
    with zipfile.ZipFile(bridge_jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        selected = list((classes / 'inhouse/microg/bridge').rglob('*.class'))
        if not selected: raise RuntimeError('No isolated bridge classes compiled')
        for path in selected:
            archive.write(path, path.relative_to(classes).as_posix())
    bridge_dex = work / 'bridge-dex'; bridge_dex.mkdir()
    run(build_tools / 'd8', '--min-api', '30', '--lib', android, '--output', bridge_dex, bridge_jar)
    if len(list(bridge_dex.glob('*.dex'))) != 1: raise RuntimeError('Expected exactly one bridge DEX')
    bridge = bridge_dex / 'classes.dex'
    generated = work / 'generated/es/inhouse/gmsprobe'; generated.mkdir(parents=True)
    (generated / 'ProbeBuild.java').write_text(
        'package es.inhouse.gmsprobe; final class ProbeBuild {\n'
        f' static final String GMS_SHA256 = "{GMS_SHA}";\n'
        f' static final String BRIDGE_SHA256 = "{digest(bridge)}";\n'
        f' static final boolean LOGIN_ENTRY = {str(args.login_entry).lower()};\n'
        '}\n')
    host_classes = work / 'host-classes'; host_classes.mkdir()
    host_sources = sorted((HERE / 'harness/src').rglob('*.java')) + groups['host'] + list(generated.glob('*.java'))
    run('java', '-jar', ecj, '-source', '1.8', '-target', '1.8', '-proc:none', '-classpath', android,
        '-d', host_classes, *host_sources)
    host_jar = work / 'host.jar'
    with zipfile.ZipFile(host_jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(host_classes.rglob('*.class')):
            name = path.relative_to(host_classes).as_posix()
            if not name.startswith(('es/inhouse/gmsprobe/', 'inhouse/microg/prototype/')):
                raise RuntimeError('Unexpected host class: ' + name)
            archive.write(path, name)
    host_dex = work / 'host-dex'; host_dex.mkdir()
    run(build_tools / 'd8', '--min-api', '30', '--lib', android, '--output', host_dex, host_jar)
    if len(list(host_dex.glob('*.dex'))) != 1: raise RuntimeError('Expected exactly one host DEX')
    compiled_resources = work / 'resources.zip'
    run(build_tools / 'aapt2', 'compile', '--dir', HERE / 'harness/res', '-o', compiled_resources)
    unsigned = work / 'unsigned.apk'
    manifest = HERE / 'harness/AndroidManifest.xml'
    if args.login_entry:
        android_ns = 'http://schemas.android.com/apk/res/android'
        ET.register_namespace('android', android_ns)
        document = ET.parse(manifest); application = document.getroot().find('application')
        def component(tag, **attributes):
            return ET.SubElement(application, tag, {'{' + android_ns + '}' + key: value for key, value in attributes.items()})
        component('activity', name='org.microg.gms.auth.login.LoginActivity', process=':microg', exported='true')
        component('provider', name='org.microg.gms.settings.SettingsProvider',
                  authorities='es.inhouse.gmsprobe.microg.settings', process=':microg', exported='false')
        component('meta-data', name='org.microg.gms.settings.checkin_enable_service', value='true')
        manifest = work / 'AndroidManifest.xml'; document.write(manifest, encoding='utf-8', xml_declaration=True)
    run(build_tools / 'aapt2', 'link', '-I', android, '--manifest', manifest,
        '-o', unsigned, compiled_resources)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as archive:
        archive.write(host_dex / 'classes.dex', 'classes.dex')
        archive.write(bridge, 'assets/embedded/bridge.dex')
        # Stored once as an immutable input asset; no external download occurs at runtime.
        archive.write(gms, 'assets/embedded/gmscore.apk', compress_type=zipfile.ZIP_STORED)
        archive.write(HERE / 'upstream.lock.json', 'assets/licenses/upstream.lock.json')
        for path in sorted((HERE / 'harness/assets').rglob('*')):
            if path.is_file(): archive.write(path, 'assets/' + path.relative_to(HERE / 'harness/assets').as_posix())
    aligned = work / 'aligned.apk'
    run(build_tools / 'zipalign', '-f', '-p', '4', unsigned, aligned)
    # This disposable probe certificate is intentionally unrelated to the permanent
    # Calma release key. It cannot upgrade or replace any user application.
    key = output / 'probe-debug.jks'
    if not key.exists():
        run('keytool', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
            '-alias', 'androiddebugkey', '-dname', 'CN=Embedded Gms Probe, O=Inhouse Experiment, C=ES',
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-noprompt')
    apk = output / 'Embedded-Gms-Probe.apk'
    run(build_tools / 'apksigner', 'sign', '--ks', key, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--ks-key-alias', 'androiddebugkey', '--out', apk, aligned)
    run(build_tools / 'apksigner', 'verify', '--verbose', apk)
    report = {'apk': apk.name, 'apkSha256': digest(apk), 'gmsCoreSha256': GMS_SHA,
              'bridgeDexSha256': digest(bridge), 'package': 'es.inhouse.gmsprobe',
              'productionRelease': False, 'externalPackageInstallation': False,
              'googleLoginTested': False, 'youtubePlaybackTested': False, 'pushDeliveryTested': False,
              'loginEntryComponentsIncluded': args.login_entry,
              'sourceHashes': {str(path.relative_to(runtime)): digest(path) for files in groups.values() for path in files}}
    (output / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
