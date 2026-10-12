#!/usr/bin/env python3
"""Build an unpublished, current-version YouTube APK with embedded microG.

This opt-in experiment never changes a release version, catalog, updater source,
or production build output. A successful build is not a successful Google login.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from release_metadata import release_version, verify_apk

HOST_PACKAGE = 'app.revanced.android.youtube'
SIGNER_SHA256 = '7133a4b3e4b9f9c4d2fbbd38b7dd2d44c7b8d0d7d16568bfe3da74a09e7887b6'


def run(*args: str | Path, **kwargs):
    return subprocess.run(list(map(str, args)), cwd=ROOT, check=True, **kwargs)


def digest(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def require_pinned(path: Path, expected: str, label: str) -> None:
    if not path.is_file() or digest(path) != expected:
        raise RuntimeError(label + ' is missing or its pinned SHA-256 differs: ' + str(path))


def dex_helpers():
    spec = importlib.util.spec_from_file_location('calma_compile_extension', ROOT / 'scripts/compile-extension.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def jar_classes(classes: Path, target: Path, prefix: str) -> None:
    selected = sorted((classes / prefix).rglob('*.class'))
    if not selected:
        raise RuntimeError('Compiled class namespace is empty: ' + prefix)
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for source in selected:
            archive.write(source, source.relative_to(classes).as_posix())


def native_library_hashes(backend: Path) -> dict[str, str]:
    """Fingerprint native payloads once at build time, after module verification."""
    hashes = {}
    with zipfile.ZipFile(backend) as archive:
        for entry in sorted(archive.infolist(), key=lambda item: item.filename):
            name = entry.filename
            if entry.is_dir() or not name.startswith('lib/') or not name.endswith('.so'):
                continue
            if not re.fullmatch(r'lib/[A-Za-z0-9_-]+/[A-Za-z0-9_+.\-]+\.so', name) or '..' in name:
                raise RuntimeError('Invalid embedded native library path: ' + name)
            if name in hashes:
                raise RuntimeError('Duplicate embedded native library: ' + name)
            with archive.open(entry) as stream:
                hashes[name] = hashlib.file_digest(stream, 'sha256').hexdigest()
    return hashes


def compile_runtime(args, work: Path, backend: Path, resources: Path) -> tuple[Path, Path]:
    runtime = HERE / 'youtube-runtime'
    groups = {name: sorted((runtime / name).rglob('*.java')) for name in ('host', 'bridge', 'stubs')}
    if not all(groups.values()):
        raise RuntimeError('Incomplete YouTube host runtime; host, bridge and stubs are required')
    helpers = dex_helpers()
    bridge_classes = work / 'bridge-classes'; bridge_classes.mkdir()
    run('java', '-jar', args.ecj, '-source', '1.8', '-target', '1.8', '-proc:none',
        '-classpath', args.android_jar, '-d', bridge_classes, *groups['bridge'])
    bridge_jar = work / 'bridge.jar'
    jar_classes(bridge_classes, bridge_jar, 'inhouse/microg/bridge')
    bridge_dir = work / 'bridge-dex'; bridge_dir.mkdir()
    run(args.build_tools / 'd8', '--min-api', '30', '--lib', args.android_jar,
        '--output', bridge_dir, bridge_jar)
    if len(list(bridge_dir.glob('*.dex'))) != 1:
        raise RuntimeError('Expected exactly one isolated bridge DEX')
    bridge = bridge_dir / 'classes.dex'
    if any(not name.startswith('Linhouse/microg/bridge/')
           for name in helpers.dex_classes(bridge.read_bytes())):
        raise RuntimeError('Unexpected classes in isolated bridge DEX')

    components = json.loads((resources / 'components.json').read_text())
    if (not isinstance(components, list) or not components
            or any(not isinstance(name, str) or not re.fullmatch(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)+', name)
                   for name in components) or len(set(components)) != len(components)):
        raise RuntimeError('components.json must contain unique, fully qualified module class names')
    generated = work / 'generated/inhouse/microg/host/EmbeddedGmsBuild.java'
    generated.parent.mkdir(parents=True)
    backend_sha256 = digest(backend)
    native_hashes = native_library_hashes(backend)
    require_pinned(backend, backend_sha256, 'Module changed while fingerprinting native libraries')
    (work / 'native-library-hashes.json').write_text(json.dumps({
        'moduleSha256': backend_sha256, 'libraries': native_hashes,
    }, indent=2) + '\n')
    generated.write_text(
        'package inhouse.microg.host;\n'
        'public final class EmbeddedGmsBuild {\n'
        f' public static final String GMS_SHA256 = "{backend_sha256}";\n'
        f' public static final String BRIDGE_SHA256 = "{digest(bridge)}";\n'
        ' public static final int BACKEND_VERSION = 250932004;\n'
        ' public static final String[] COMPONENTS = new String[] {\n'
        + ',\n'.join('  ' + json.dumps(name) for name in components)
        + '\n };\n public static final String[][] NATIVE_LIBRARIES = new String[][] {\n'
        + ',\n'.join('  {' + json.dumps(name) + ', ' + json.dumps(value) + '}'
                      for name, value in native_hashes.items())
        + '\n };\n private EmbeddedGmsBuild() {}\n}\n')
    host_classes = work / 'host-classes'; host_classes.mkdir()
    run('java', '-jar', args.ecj, '-source', '1.8', '-target', '1.8', '-proc:none',
        '-classpath', args.android_jar, '-d', host_classes, *groups['host'], *groups['stubs'], generated)
    host_jar = work / 'embedded-host.jar'
    jar_classes(host_classes, host_jar, 'inhouse/microg/host')
    stubs_jar = work / 'compile-only-stubs.jar'
    jar_classes(host_classes, stubs_jar, 'androidx')
    host_dir = work / 'host-dex'; host_dir.mkdir()
    run(args.build_tools / 'd8', '--min-api', '30', '--lib', args.android_jar,
        '--classpath', stubs_jar, '--output', host_dir, host_jar)
    if len(list(host_dir.glob('*.dex'))) != 1:
        raise RuntimeError('Expected exactly one embedded host DEX')
    host = host_dir / 'classes.dex'
    host_classes_set = helpers.dex_classes(host.read_bytes())
    if any(not name.startswith('Linhouse/microg/host/') for name in host_classes_set):
        raise RuntimeError('Compile-only stubs leaked into embedded host DEX')
    original = set()
    with zipfile.ZipFile(args.stock) as archive:
        for name in archive.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                original.update(helpers.dex_classes(archive.read(name)))
    collisions = original & host_classes_set
    if collisions:
        raise RuntimeError('Embedded host classes collide with YouTube: ' + ', '.join(sorted(collisions)))
    payload = resources / 'assets/embedded'; payload.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(backend, payload / 'gmscore.apk')
    shutil.copyfile(bridge, payload / 'bridge.dex')
    licenses = resources / 'assets/licenses/embedded-gms'; licenses.mkdir(parents=True, exist_ok=True)
    for source in (HERE / 'LICENSE.microg', HERE / 'upstream.lock.json'):
        shutil.copyfile(source, licenses / source.name)
    # Third-party notices supplied with the probe also accompany the real host.
    for source in sorted((HERE / 'harness/assets/licenses').rglob('*')):
        if source.is_file():
            destination = licenses / source.relative_to(HERE / 'harness/assets/licenses')
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
    paths = sorted(source.relative_to(resources).as_posix()
                   for part in ('assets', 'res') for source in (resources / part).rglob('*') if source.is_file())
    (resources / 'files.txt').write_text('\n'.join(paths) + '\n')
    return host, bridge


def preference_bytes() -> bytes:
    """Generate the usual preset in memory, without rewriting tracked resources."""
    values = json.loads((ROOT / 'settings.json').read_text())
    values.update(json.loads((ROOT / 'navigation-settings.json').read_text()))
    enums = {'custom_branding_icon', 'spoof_video_streams_client_type', 'change_start_page'}
    encoded = {}
    for key, value in values.items():
        if key in enums:
            value = value.upper()
        encoded[key if key.startswith('sb_') else 'revanced_' + key] = value if isinstance(value, bool) else str(value)
    return (json.dumps(encoded, indent=2, ensure_ascii=False) + '\n').encode()


def compile_patches(args, work: Path, host: Path, resources: Path, extension: Path) -> Path:
    classes = work / 'patch-classes'; classes.mkdir()
    version, code = release_version()
    generated = work / 'BuildRelease.kt'
    generated.write_text('package es.calma.youtube.patches\ninternal const val RELEASE_VERSION = '
                         + json.dumps(version) + '\ninternal const val RELEASE_VERSION_CODE = ' + str(code) + '\n')
    patches = sorted((ROOT / 'patches').glob('*.kt'))
    for name in ('EmbeddedGms.kt', 'EmbeddedGmsResources.kt'):
        if not any(path.name == name for path in patches):
            raise RuntimeError('Required opt-in patch is missing: ' + name)
    run('java', '-cp', ROOT / 'tools/kotlin/*', 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-Xskip-metadata-version-check', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
        '-classpath', args.morphe, '-d', classes, *patches, generated)
    bundle = work / 'embedded-patches.jar'
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED) as archive:
        entries = set()
        def add(source: Path, name: str):
            if name in entries:
                raise RuntimeError('Duplicate patch resource: ' + name)
            entries.add(name)
            archive.write(source, name, compress_type=zipfile.ZIP_STORED if name.endswith('gmscore.apk') else zipfile.ZIP_DEFLATED)
        for source in sorted(classes.rglob('*')):
            if source.is_file(): add(source, source.relative_to(classes).as_posix())
        add(extension, 'calma-extension.dex')
        add(host, 'embedded-host.dex')
        for source_root, prefix in ((ROOT / 'resources', 'calma-res/'),
                                    (ROOT / 'app/assets/updates', 'calma-res/assets/updates/'),
                                    (resources, 'embedded-res/')):
            for source in sorted(source_root.rglob('*')):
                if source.is_file():
                    name = prefix + source.relative_to(source_root).as_posix()
                    if name != 'calma-res/assets/calma-preferences.json': add(source, name)
        archive.writestr('calma-res/assets/calma-preferences.json', preference_bytes())
    return bundle


def protected_state() -> dict[str, str]:
    """Detect accidental changes to the release identity and published catalogs."""
    files = [ROOT / 'android-update.json', ROOT / 'inhouse-updater.lock.json',
             ROOT / 'src/es/calma/youtube/nativeapp/CalmaBuild.java']
    for directory in ('updates', 'app/assets/updates', 'vendor/inhouse-services-updater'):
        files.extend(path for path in (ROOT / directory).rglob('*') if path.is_file())
    return {str(path.relative_to(ROOT)): digest(path) for path in files if path.is_file()}


def integration_sources() -> dict[str, str]:
    files = [HERE / name for name in ('build-youtube.py', 'prepare-host.py', 'prepare-backend.py', 'upstream.lock.json')]
    files.extend(ROOT / 'scripts' / name for name in ('compile-extension.py', 'release_metadata.py'))
    for directory in (HERE / 'youtube-runtime', HERE / 'patches', ROOT / 'patches', ROOT / 'src'):
        files.extend(path for path in directory.rglob('*') if path.is_file())
    return {str(path.relative_to(ROOT)): digest(path) for path in sorted(files)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gms-apk', type=Path, required=True, help='Original hash-pinned upstream GmsCore APK')
    parser.add_argument('--morphe', type=Path, required=True)
    parser.add_argument('--build-tools', type=Path, default=ROOT / 'tools/android-15')
    parser.add_argument('--android-jar', type=Path, default=ROOT / 'tools/android-35/android.jar')
    parser.add_argument('--ecj', type=Path, default=ROOT / 'tools/ecj.jar')
    signing = parser.add_mutually_exclusive_group(required=True)
    signing.add_argument('--keystore', type=Path, help='Existing permanent Calma signing key')
    signing.add_argument('--debug-signing', action='store_true',
                         help='Use a disposable certificate; only allowed together with --instrumentation')
    parser.add_argument('--stock', type=Path, default=ROOT / 'base/youtube-original.apk')
    parser.add_argument('--prepared-backend', '--backend-apk', dest='prepared_backend', type=Path,
                        help='Reuse a previously adapted module (requires its report)')
    parser.add_argument('--backend-report', type=Path, help='Report matching --prepared-backend')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'build/embedded-youtube')
    parser.add_argument('--prepare-only', action='store_true', help='Compile inputs and patch bundle without patching/signing YouTube')
    parser.add_argument('--instrumentation', action='store_true', help='Register the opt-in same-APK smoke instrumentation')
    args = parser.parse_args()
    for name, value in vars(args).items():
        if isinstance(value, Path): setattr(args, name, value.resolve())
    if bool(args.prepared_backend) != bool(args.backend_report):
        parser.error('--prepared-backend and --backend-report must be used together')
    if args.debug_signing and not args.instrumentation:
        parser.error('--debug-signing requires --instrumentation; it cannot produce an installable release update')
    if json.loads((ROOT / 'app/assets/updates/config.json').read_text())['installedVersion'] != release_version()[0]:
        raise RuntimeError('Updater installedVersion must match CalmaBuild.VERSION before building')
    output = args.output_dir
    required_root = (ROOT / 'build/embedded-youtube').resolve()
    if output != required_root and required_root not in output.parents:
        parser.error('--output-dir must be build/embedded-youtube or a directory inside it')
    java = run('java', '-version', capture_output=True, text=True)
    java_version = re.search(r'version\s+"(\d+)', java.stdout + java.stderr)
    if not java_version or int(java_version[1]) < 21:
        raise RuntimeError('The pinned Morphe patcher requires Java 21 or newer; select that JDK before building')
    require_pinned(args.stock, json.loads((ROOT / 'base.lock.json').read_text())['sha256'], 'YouTube input')
    require_pinned(args.gms_apk, json.loads((HERE / 'upstream.lock.json').read_text())['apkSha256'], 'Upstream GmsCore')
    require_pinned(args.morphe, json.loads((ROOT / 'tools.lock.json').read_text())['morphe']['sha256'], 'Morphe tool')
    for path in (args.android_jar, args.ecj, args.build_tools / 'd8', args.build_tools / 'aapt2',
                 args.build_tools / 'zipalign', args.build_tools / 'apksigner'):
        if not path.is_file(): raise RuntimeError('Missing build input: ' + str(path))
    if not args.debug_signing and not args.keystore.is_file():
        raise RuntimeError('The existing permanent Calma key is required')
    # Serialize only this wrapper. Other production build outputs are never removed.
    output.mkdir(parents=True, exist_ok=True)
    import fcntl
    with (output / '.build.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before = protected_state()
        try:
            build(args, output)
        finally:
            if protected_state() != before:
                raise RuntimeError('Release identity, updater or catalog changed during the experimental build; do not publish')


def build(args, output: Path) -> None:
    sources_before = integration_sources()
    work = output / 'work'
    if work.exists(): shutil.rmtree(work)
    work.mkdir()
    backend = args.prepared_backend or output / 'adapted-gmscore.apk'
    backend_report = args.backend_report or output / 'adapted-gmscore.report.json'
    if not args.prepared_backend:
        run(sys.executable, HERE / 'prepare-backend.py', '--input', args.gms_apk, '--output', backend,
            '--report', backend_report, '--morphe', args.morphe, '--aapt2', args.build_tools / 'aapt2')
    report = json.loads(backend_report.read_text())
    require_pinned(backend, report['outputSha256'], 'Adapted embedded module')
    if report.get('hostPackage') != HOST_PACKAGE or report.get('accountType') != 'es.inhouse.youtube.google':
        raise RuntimeError('Adapted backend is for a different host or account type')
    for key, path in (('inputSha256', args.gms_apk), ('morpheSha256', args.morphe),
                      ('patchSourceSha256', HERE / 'patches/BackendPatch.kt'),
                      ('preparerSourceSha256', HERE / 'prepare-backend.py')):
        require_pinned(path, report[key], 'Adapted module provenance: ' + key)
    resources = work / 'embedded-res'
    run(sys.executable, HERE / 'prepare-host.py', '--backend-apk', backend, '--backend-report', backend_report,
        '--output', resources, '--aapt2', args.build_tools / 'aapt2', '--host-apk', args.stock,
        *(['--instrumentation'] if args.instrumentation else []))
    host, bridge = compile_runtime(args, work, backend, resources)
    extension = work / 'calma-extension.dex'
    run(sys.executable, ROOT / 'scripts/compile-extension.py', '--stock', args.stock,
        '--android-jar', args.android_jar, '--output', extension, '--build-dir', work / 'calma-extension')
    bundle = compile_patches(args, work, host, resources, extension)
    version, code = release_version()
    evidence = {
        'productionRelease': False, 'experimental': True, 'published': False,
        'version': version, 'versionCode': code, 'package': HOST_PACKAGE,
        'upstreamSha256': digest(args.gms_apk), 'adaptedBackendSha256': digest(backend),
        'backendReportSha256': digest(backend_report), 'bridgeDexSha256': digest(bridge),
        'hostDexSha256': digest(host), 'patchBundleSha256': digest(bundle),
        'extensionSha256': digest(extension), 'externalPackageInstallation': False,
        'googleLoginTested': False, 'youtubePlaybackTested': False, 'pushDeliveryTested': False,
        'signedInAccountTested': False, 'releaseCatalogChanged': False,
        'instrumentationIncluded': args.instrumentation,
        'debugSigning': args.debug_signing,
        'sourceHashes': sources_before,
    }
    if integration_sources() != sources_before:
        raise RuntimeError('Integration sources changed during compilation; rebuild from a stable snapshot')
    if args.prepare_only:
        evidence['prepareOnly'] = True
        (output / 'build-report.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print('Prepared experimental patch bundle:', bundle)
        return
    patched = work / 'patched.apk'
    run('java', '-Xmx7g', '-Dcalma.embeddedGms=true', '-jar', args.morphe, 'patch', '--patches', bundle,
        '--bytecode-mode', 'FULL', '--unsigned', '--out', patched, '--temporary-files-path', work / 'morphe', args.stock)
    clean = work / 'clean.apk'
    with zipfile.ZipFile(patched) as source, zipfile.ZipFile(clean, 'w') as destination:
        for info in source.infolist():
            if info.filename.startswith('META-INF/') and re.search(r'\.(SF|RSA|DSA|EC|MF)$', info.filename): continue
            if info.filename in ('assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm'): continue
            # The backend is already a compressed APK. Deflating that archive
            # again saves about 1% but adds work during cold provider startup.
            # Keep both verified executable assets directly readable by Android.
            compression = zipfile.ZIP_STORED if info.filename in (
                'assets/embedded/gmscore.apk', 'assets/embedded/bridge.dex') else info.compress_type
            destination.writestr(info, source.read(info), compress_type=compression)
    aligned = work / 'aligned.apk'
    run(args.build_tools / 'zipalign', '-f', '-P', '16', '4', clean, aligned)
    apk = output / f'YouTube-Calma-{version}-embedded-EXPERIMENTAL.apk'
    signed = work / 'signed-candidate.apk'
    environment = dict(os.environ)
    alias = 'calma'
    expected_signer = SIGNER_SHA256
    if args.debug_signing:
        args.keystore = output / 'youtube-embedded-debug.jks'
        environment['CALMA_KEYSTORE_PASSWORD'] = 'android'
        alias = 'androiddebugkey'
        if not args.keystore.exists():
            run('keytool', '-genkeypair', '-keystore', args.keystore, '-storepass', 'android', '-keypass', 'android',
                '-alias', alias, '-dname', 'CN=Embedded YouTube Experiment, O=Inhouse, C=ES',
                '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650', '-noprompt')
        certificate = run('keytool', '-exportcert', '-keystore', args.keystore, '-alias', alias,
                          '-storepass:env', 'CALMA_KEYSTORE_PASSWORD', env=environment, capture_output=True).stdout
        expected_signer = hashlib.sha256(certificate).hexdigest()
        if expected_signer == SIGNER_SHA256:
            raise RuntimeError('A disposable experiment must not use the permanent release certificate')
    else:
        environment.setdefault('CALMA_KEYSTORE_PASSWORD', 'localbuild')
    run(args.build_tools / 'apksigner', 'sign', '--ks', args.keystore, '--ks-key-alias', alias,
        '--ks-pass', 'env:CALMA_KEYSTORE_PASSWORD', '--key-pass', 'env:CALMA_KEYSTORE_PASSWORD',
        '--out', signed, aligned, env=environment)
    verification = run(args.build_tools / 'apksigner', 'verify', '--verbose', '--print-certs', signed,
                       capture_output=True, text=True).stdout
    signatures = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', verification)
    if signatures != [expected_signer]:
        raise RuntimeError('Experimental APK does not have its required signing certificate')
    run(args.build_tools / 'zipalign', '-c', '-P', '16', '4', signed)
    verify_apk(signed, args.build_tools, version, code)
    with zipfile.ZipFile(signed) as archive:
        for name, expected in (('assets/embedded/gmscore.apk', digest(backend)),
                               ('assets/embedded/bridge.dex', digest(bridge))):
            if hashlib.sha256(archive.read(name)).hexdigest() != expected:
                raise RuntimeError('Packaged embedded asset differs: ' + name)
            if archive.getinfo(name).compress_type != zipfile.ZIP_STORED:
                raise RuntimeError('Embedded executable asset must not be double-compressed: ' + name)
    if integration_sources() != sources_before:
        raise RuntimeError('Integration sources changed during APK assembly; rebuild from a stable snapshot')
    # Publish this local candidate path only after every structural check passes.
    signed.replace(apk)
    evidence.update({'apk': apk.name, 'apkSha256': digest(apk), 'apkSizeBytes': apk.stat().st_size,
                     'signerSha256': expected_signer, 'actualManifestVerified': True})
    (output / 'build-report.json').write_text(json.dumps(evidence, indent=2) + '\n')
    (output / 'SHA256SUMS.txt').write_text(digest(apk) + '  ' + apk.name + '\n')
    print('Unpublished experimental APK:', apk)


if __name__ == '__main__':
    main()
