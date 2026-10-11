#!/usr/bin/env python3
"""Patch only the hash-pinned, user-supplied ReVanced APK; reuse its native UI."""
import argparse,hashlib,json,os,re,subprocess,zipfile
from pathlib import Path
from release_metadata import release_version, verify_apk
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--morphe',type=Path,required=True);p.add_argument('--build-tools',type=Path,required=True);p.add_argument('--keystore',type=Path,required=True);p.add_argument('--skip-patch',action='store_true');a=p.parse_args()
for key,value in vars(a).items():
    if isinstance(value,Path):setattr(a,key,value.resolve())
def run(*args,**kwargs):return subprocess.run(list(map(str,args)),cwd=ROOT,check=True,**kwargs)
def sha(path):return hashlib.file_digest(path.open('rb'),'sha256').hexdigest()
stock=ROOT/'base/youtube-original.apk';lock=json.loads((ROOT/'base.lock.json').read_text());assert sha(stock)==lock['sha256']
build=ROOT/'build';build.mkdir(exist_ok=True);dist=build/'dist';dist.mkdir(exist_ok=True)
if not a.skip_patch:
    run('python3','scripts/generate-preferences.py')
    run('python3','scripts/compile-extension.py','--stock',stock)
    run('python3','scripts/compile-patches.py','--morphe',a.morphe)
    (build/'patched.apk').unlink(missing_ok=True)
    run('java','-Xmx7g','-jar',a.morphe,'patch','--patches',build/'patches.jar','--bytecode-mode','FULL','--unsigned','--out',build/'patched.apk','--temporary-files-path',build/'work',stock)
with zipfile.ZipFile(build/'patched.apk') as z,zipfile.ZipFile(build/'clean.apk','w') as out:
    for info in z.infolist():
        if info.filename.startswith('META-INF/') and re.search(r'\.(SF|RSA|DSA|EC|MF)$',info.filename):continue
        if info.filename in ['assets/dexopt/baseline.prof','assets/dexopt/baseline.profm']:continue
        out.writestr(info,z.read(info))
run(a.build_tools/'zipalign','-f','-P','16','4',build/'clean.apk',build/'aligned.apk')
version,version_code=release_version()
apk=dist/f'YouTube-Calma-{version}.apk'
env=dict(os.environ);env.setdefault('CALMA_KEYSTORE_PASSWORD','localbuild')
run(a.build_tools/'apksigner','sign','--ks',a.keystore,'--ks-key-alias','calma','--ks-pass','env:CALMA_KEYSTORE_PASSWORD','--key-pass','env:CALMA_KEYSTORE_PASSWORD','--out',apk,build/'aligned.apk',env=env)
run(a.build_tools/'apksigner','verify','--verbose','--print-certs',apk)
run(a.build_tools/'zipalign','-c','-P','16','4',apk)
# Validate the actual signed manifest before announcing a downloadable update.
verify_apk(apk,a.build_tools,version,version_code)
manifest={'version':version,'versionCode':version_code,'required':True,'apkUrl':f'https://github.com/miguelcoxcaballero/youtube-calma/releases/download/v{version}/'+apk.name,'apkSha256':sha(apk),'apkSizeBytes':apk.stat().st_size,'releaseNotes':'Neutral updater colors.'}
(ROOT/'android-update.json').write_text(json.dumps(manifest,indent=2,ensure_ascii=False)+'\n')
(dist/'SHA256SUMS.txt').write_text(sha(apk)+'  '+apk.name+'\n')
(dist/'build-info.json').write_text(json.dumps({'version':version,'base':lock,'apkSha256':sha(apk),'apkSizeBytes':apk.stat().st_size,'patchBundleSha256':sha(build/'patches.jar'),'extensionSha256':sha(build/'classes.dex'),'signedInAccountTested':False},indent=2)+'\n')
print(apk)
