#!/usr/bin/env python3
import argparse, hashlib, json, subprocess, tempfile, zipfile
from pathlib import Path
from release_metadata import release_version
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--morphe',type=Path,required=True);args=p.parse_args()
assert hashlib.sha256(args.morphe.read_bytes()).hexdigest()==json.loads((ROOT/'tools.lock.json').read_text())['morphe']['sha256']
with tempfile.TemporaryDirectory() as tmp:
    classes=Path(tmp)/'classes';classes.mkdir()
    version, code = release_version()
    generated = Path(tmp)/'BuildRelease.kt'
    generated.write_text('package es.calma.youtube.patches\ninternal const val RELEASE_VERSION = '+json.dumps(version)+'\ninternal const val RELEASE_VERSION_CODE = '+str(code)+'\n')
    subprocess.run(['java','-cp',str(ROOT/'tools/kotlin/*'),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-Xskip-metadata-version-check','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',str(args.morphe),'-d',str(classes),*map(str,(ROOT/'patches').glob('*.kt')),str(generated)],check=True)
    with zipfile.ZipFile(ROOT/'build/patches.jar','w',zipfile.ZIP_DEFLATED) as z:
        for f in classes.rglob('*'):
            if f.is_file():z.write(f,f.relative_to(classes).as_posix())
        z.write(ROOT/'build/classes.dex','calma-extension.dex')
        for source,prefix in [(ROOT/'resources','calma-res/'),(ROOT/'app/assets/updates','calma-res/assets/updates/')]:
            for f in source.rglob('*'):
                if f.is_file():z.write(f,prefix+f.relative_to(source).as_posix())
