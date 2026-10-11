#!/usr/bin/env python3
"""Build a same-signer instrumentation APK for CI only; never shipped in a release."""
import argparse, os, pathlib, subprocess, zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--build-tools',type=pathlib.Path,required=True)
SDK=parser.parse_args().build_tools.resolve()
BUILD=ROOT/'build/model-smoke'; BUILD.mkdir(parents=True,exist_ok=True)
ANDROID=ROOT/'tools/android-35/android.jar'
def run(*args,**kwargs):subprocess.run(list(map(str,args)),check=True,**kwargs)
run('java','-jar',ROOT/'tools/ecj.jar','-source','1.8','-target','1.8','-proc:none','-classpath',ANDROID,'-d',BUILD/'classes',ROOT/'tests/android/NativeSmoke.java')
with zipfile.ZipFile(BUILD/'classes.jar','w') as archive:
 for path in (BUILD/'classes').rglob('*.class'):archive.write(path,path.relative_to(BUILD/'classes').as_posix())
run(SDK/'d8','--min-api','28','--lib',ANDROID,'--output',BUILD,BUILD/'classes.jar')
run(SDK/'aapt2','link','-I',ANDROID,'--manifest',ROOT/'tests/android/AndroidManifest.xml','-o',BUILD/'unsigned.apk')
with zipfile.ZipFile(BUILD/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as archive:archive.write(BUILD/'classes.dex','classes.dex')
run(SDK/'zipalign','-f','4',BUILD/'unsigned.apk',BUILD/'aligned.apk')
env=dict(os.environ);env['CALMA_KEYSTORE_PASSWORD']=env.get('CALMA_KEYSTORE_PASSWORD','localbuild')
run(SDK/'apksigner','sign','--ks',pathlib.Path(os.environ['CALMA_KEYSTORE']),'--ks-key-alias','calma','--ks-pass','env:CALMA_KEYSTORE_PASSWORD','--key-pass','env:CALMA_KEYSTORE_PASSWORD','--out',BUILD/'YouTube-Calma-Smoke.apk',BUILD/'aligned.apk',env=env)
print(BUILD/'YouTube-Calma-Smoke.apk')
