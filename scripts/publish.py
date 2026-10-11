#!/usr/bin/env python3
"""Publish the already signed APK; never rebuild or expose the signing key."""
import hashlib,json,os,subprocess
from pathlib import Path
R=Path(__file__).resolve().parents[1];source=R/'release-input';out=R/'build/publish';out.mkdir(parents=True,exist_ok=True)
assert os.environ.get('GITHUB_ACTIONS')=='true'
def sha(p):return hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
def run(*args):subprocess.run(list(map(str,args)),check=True)
m=json.loads((R/'android-update.json').read_text());t=json.loads((source/'transport.json').read_text());repo=os.environ['GH_REPO']
name='YouTube-Calma-'+m['version']+'.apk';apk=out/name;tag='v'+m['version']
assert repo=='miguelcoxcaballero/youtube-calma'
assert m['apkUrl']==f'https://github.com/{repo}/releases/download/{tag}/{name}'
with apk.open('wb') as dest:
    for part in t['parts']:
        chunk=source/part['name'];assert sha(chunk)==part['sha256'];dest.write(chunk.read_bytes())
assert sha(apk)==m['apkSha256'] and apk.stat().st_size==m['apkSizeBytes']
sums=out/'SHA256SUMS.txt';sums.write_text(sha(apk)+'  '+name+'\n')
run('gh','release','create',tag,'--target',t['sourceCommit'],'--title','YouTube Calma '+m['version']+' · A','--notes-file',source/'release-notes.txt','--draft')
run('gh','release','upload',tag,apk,sums,R/'android-update.json',source/'build-info.json')
run('gh','release','edit',tag,'--draft=false','--prerelease','--latest=false')
print('Published '+m['apkUrl'])
