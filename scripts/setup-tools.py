#!/usr/bin/env python3
"""Install pinned compilers and link an existing official Android SDK."""
import argparse,hashlib,json,subprocess,urllib.request,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];T=ROOT/'tools'
p=argparse.ArgumentParser();p.add_argument('--android-sdk',type=Path,required=True);a=p.parse_args();sdk=a.android_sdk.resolve()
T.mkdir(exist_ok=True)
def download(url,path):
    if not path.exists():
        with urllib.request.urlopen(url) as response:path.write_bytes(response.read())
download('https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar',T/'ecj.jar')
download('https://dl.google.com/dl/android/maven2/androidx/core/core/1.13.1/core-1.13.1.aar',T/'androidx-core.aar')
with zipfile.ZipFile(T/'androidx-core.aar') as z:(T/'androidx-core.jar').write_bytes(z.read('classes.jar'))
for name,target in [('android-35',sdk/'platforms/android-35'),('android-15',sdk/'build-tools/36.0.0')]:
    if not target.is_dir():raise SystemExit('Install Android SDK dependency: '+str(target))
    if not (T/name).exists():(T/name).symlink_to(target,target_is_directory=True)
lock=json.loads((ROOT/'tools.lock.json').read_text())['morphe'];download(lock['url'],T/'morphe.jar')
assert hashlib.sha256((T/'morphe.jar').read_bytes()).hexdigest()==lock['sha256']
subprocess.run(['python3',str(ROOT/'scripts/setup-kotlin.py')],check=True)
print('Build tools ready')
