#!/usr/bin/env python3
"""Build the native Calma extension and mechanically reuse the Inhouse updater.

Kotlin and AndroidX bytecode is relocated into es.calma.vendor to coexist with
Instagram's obfuscated libraries. The original installer source is not rewritten.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT


def run(*args: str | Path) -> None:
    subprocess.run(list(map(str, args)), check=True, cwd=ROOT)


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def dex_classes(data: bytes) -> set[str]:
    if not data.startswith(b'dex\n'):
        raise ValueError('Expected standard DEX bytecode')
    _, strings_offset = struct.unpack_from('<II', data, 0x38)
    _, types_offset = struct.unpack_from('<II', data, 0x40)
    class_count, classes_offset = struct.unpack_from('<II', data, 0x60)
    names = set()
    for i in range(class_count):
        type_idx = struct.unpack_from('<I', data, classes_offset + i * 32)[0]
        string_idx = struct.unpack_from('<I', data, types_offset + type_idx * 4)[0]
        offset = struct.unpack_from('<I', data, strings_offset + string_idx * 4)[0]
        while data[offset] & 128:
            offset += 1
        offset += 1
        names.add(data[offset:data.index(0, offset)].decode('utf-8'))
    return names


def dex_types(data: bytes) -> set[str]:
    _, strings_offset = struct.unpack_from('<II', data, 0x38)
    type_count, types_offset = struct.unpack_from('<II', data, 0x40)
    names = set()
    for i in range(type_count):
        string_idx = struct.unpack_from('<I', data, types_offset + i * 4)[0]
        offset = struct.unpack_from('<I', data, strings_offset + string_idx * 4)[0]
        while data[offset] & 128:
            offset += 1
        offset += 1
        names.add(data[offset:data.index(0, offset)].decode('utf-8'))
    return names


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=NATIVE / 'src')
    parser.add_argument('--build-dir', type=Path, default=NATIVE / 'build' / 'extension')
    parser.add_argument('--android-jar', type=Path, default=ROOT / 'tools/android-35/android.jar')
    parser.add_argument('--stock', type=Path, required=True, help='Verified stock APK to check for class collisions')
    parser.add_argument('--output', type=Path, default=NATIVE / 'build' / 'classes.dex')
    args = parser.parse_args()
    sources = sorted(args.source.rglob('*.java'))
    if not sources:
        raise SystemExit(f'No original Java extension sources found in {args.source}')
    build = args.build_dir.resolve()
    build.mkdir(parents=True, exist_ok=True)
    compiler = ROOT / 'tools/kotlin/compiler.jar'
    for required in [compiler, ROOT / 'tools/ecj.jar', ROOT / 'tools/androidx-core.jar', args.android_jar]:
        if not required.is_file():
            raise SystemExit(f'Missing existing pinned build dependency: {required}')
    with tempfile.TemporaryDirectory(prefix='calma-extension-', dir=build) as tmp:
        work = Path(tmp)
        classes = work / 'classes'
        classes.mkdir()
        generated = work / 'updater'
        generated.mkdir()
        app_source = ROOT / 'app/src/es/calma/youtube'
        # Shared updater source is locked; edits belong in inhouse-services-updater.
        run('python3', ROOT / 'vendor/inhouse-services-updater/verify.py', ROOT)
        shared = ROOT / 'vendor/inhouse-services-updater/android'
        for file in shared.iterdir():
            if file.suffix in ('.java','.kt'): shutil.copyfile(file, generated / file.name)
        alias = work / 'alias'; alias.mkdir()
        shutil.copyfile(app_source / 'UpdateActivity.java', alias / 'UpdateActivity.java')
        kotlin_cp = ':'.join(map(str, [args.android_jar, ROOT / 'tools/androidx-core.jar', ROOT / 'tools/kotlin/stdlib.jar', ROOT / 'tools/kotlin/annotations.jar']))
        run('java', '-cp', ROOT / 'tools/kotlin/*', 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-no-stdlib', '-no-reflect', '-jvm-target', '1.8', '-classpath', kotlin_cp,
            '-d', classes, generated / 'PhotosUpdateInstaller.kt')
        java_sources = sources + sorted(generated.glob('*.java')) + sorted(alias.glob('*.java'))
        run('java', '-jar', ROOT / 'tools/ecj.jar', '-source', '1.8', '-target', '1.8', '-proc:none',
            '-classpath', kotlin_cp + ':' + str(classes), '-d', classes, *java_sources)
        own_jar = work / 'own.jar'
        with zipfile.ZipFile(own_jar, 'w', zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(classes.rglob('*.class')):
                archive.write(path, path.relative_to(classes).as_posix())
        tool_classes = work / 'tools'
        run('java', '-jar', ROOT / 'tools/ecj.jar', '-source', '17', '-target', '17', '-proc:none',
            '-classpath', compiler, '-d', tool_classes, NATIVE / 'scripts/RelocateRuntime.java')
        shaded = work / 'extension-isolated.jar'
        run('java', '-cp', str(tool_classes) + ':' + str(compiler), 'es.calma.tools.RelocateRuntime',
            shaded, own_jar, ROOT / 'tools/androidx-core.jar', ROOT / 'tools/kotlin/stdlib.jar', ROOT / 'tools/kotlin/annotations.jar')
        dex_dir = work / 'dex'
        dex_dir.mkdir()
        run(ROOT / 'tools/android-15/d8', '--min-api', '28', '--lib', args.android_jar,
            '--output', dex_dir, shaded)
        dex_files = list(dex_dir.glob('*.dex'))
        if len(dex_files) != 1:
            raise SystemExit('Extension unexpectedly needs multiple DEX files; update bundle integration first')
        dex_bytes = dex_files[0].read_bytes()
        own_classes = dex_classes(dex_bytes)
        own_types = dex_types(dex_bytes)
        original_namespaces = ('Lkotlin/', 'Landroidx/', 'Landroid/support/', 'Lorg/jetbrains/annotations/', 'Lorg/intellij/lang/annotations/')
        unisolated_references = sorted(name for name in own_types if name.lstrip('[').startswith(original_namespaces))
        if unisolated_references:
            raise SystemExit('Unisolated runtime references: ' + ', '.join(unisolated_references[:20]))
        if b'android.support.FILE_PROVIDER_PATHS\x00' not in dex_bytes:
            raise SystemExit('Original FileProvider manifest metadata key was not preserved')
        if 'Les/calma/vendor/androidx/core/content/FileProvider;' not in own_classes:
            raise SystemExit('Isolated AndroidX FileProvider is missing')
        unexpected = sorted(name for name in own_classes if not name.startswith(('Les/calma/', 'Lcom/inhouse/services/updater/')))
        if unexpected:
            raise SystemExit('Unisolated extension classes: ' + ', '.join(unexpected[:20]))
        stock_classes = set()
        with zipfile.ZipFile(args.stock) as archive:
            for name in archive.namelist():
                if re.fullmatch(r'classes\d*\.dex', name):
                    stock_classes.update(dex_classes(archive.read(name)))
        collisions = sorted(own_classes & stock_classes)
        if collisions:
            raise SystemExit('Extension would overwrite stock classes: ' + ', '.join(collisions[:20]))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(dex_files[0], args.output)
        shutil.copyfile(shaded, build / 'extension-isolated.jar')
        for source in generated.iterdir():
            shutil.copyfile(source, build / source.name)
        report = {
            'stockSha256': sha(args.stock),
            'outputSha256': sha(args.output),
            'stockClassCount': len(stock_classes),
            'extensionClassCount': len(own_classes),
            'classCollisions': collisions,
            'unisolatedRuntimeReferences': unisolated_references,
            'fileProviderMetadataPreserved': True,
            'updaterSources': {path.name: sha(path) for path in shared.iterdir() if path.is_file()},
            'hostAdaptations': ['App identity/version supplied by local updates/config.json; lifecycle and Activity aliases only'],
            'runtimeRelocation': {'kotlin': 'es.calma.vendor.kotlin', 'androidx': 'es.calma.vendor.androidx', 'android.support': 'es.calma.vendor.support', 'org.jetbrains.annotations': 'es.calma.vendor.annotations', 'org.intellij.lang.annotations': 'es.calma.vendor.intellij.annotations'},
            'photosInstallerSourceUnchanged': (shared / 'PhotosUpdateInstaller.kt').read_bytes() == (generated / 'PhotosUpdateInstaller.kt').read_bytes(),
        }
        (build / 'build-report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
        print('Extension DEX:', args.output)


if __name__ == '__main__':
    main()
