"""Check provenance, preset, ABI preservation, native hooks and release metadata."""
import sys,hashlib,importlib.util,json,re,subprocess,zipfile
from pathlib import Path
R=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(R/'scripts'))
from release_metadata import verify_apk
m=json.loads((R/'android-update.json').read_text());apk=R/'build/dist'/('YouTube-Calma-'+m['version']+'.apk')
assert hashlib.file_digest(apk.open('rb'),'sha256').hexdigest()==m['apkSha256']
verify_apk(apk,R/'tools/android-15',m['version'],m['versionCode'])
assert apk.stat().st_size==m['apkSizeBytes'] and m['required'] is True
spec=importlib.util.spec_from_file_location('compiler',R/'scripts/compile-extension.py');compiler=importlib.util.module_from_spec(spec);spec.loader.exec_module(compiler)
with zipfile.ZipFile(apk) as z,zipfile.ZipFile(R/'base/youtube-original.apk') as base:
    classes=set();old=set();strings=b''
    for n in z.namelist():
        if re.fullmatch('classes[0-9]*.dex',n):
            data=z.read(n);defined=compiler.dex_classes(data);assert not classes&defined;classes.update(defined);strings+=data
    for n in base.namelist():
        if n.startswith('lib/'):assert base.read(n)==z.read(n),n
        if re.fullmatch('classes[0-9]*.dex',n):old.update(compiler.dex_classes(base.read(n)))
    assert old<=classes,'Original classes lost'
    assert 'Les/calma/youtube/nativeapp/CalmaBootstrap;' in classes
    for name in ('InstallActivity', 'InstallState', 'InstallResultPolicy'):
        assert f'Lcom/inhouse/services/updater/{name};' in classes, f'Missing installer result component: {name}'
    assert b'CalmaBuild' in strings and m['version'].encode() in strings
    for n in ['updater.js','index.html','updater.css','host.js','host.css','config.json']:assert z.read('assets/updates/'+n)==(R/'app/assets/updates'/n).read_bytes()
    assert json.loads(z.read('assets/updates/config.json'))['installedVersion']==m['version'],'Updater displays a stale installed version'
    assert z.read('assets/calma-preferences.json')==(R/'resources/assets/calma-preferences.json').read_bytes()
    supplied=json.loads((R/'settings.json').read_text());supplied.update(json.loads((R/'navigation-settings.json').read_text()));preset=json.loads(z.read('assets/calma-preferences.json'))
    for k,v in supplied.items():
        stored=k if k.startswith('sb_') else 'revanced_'+k
        expected=v if isinstance(v,bool) else str(v)
        if k in {'custom_branding_icon','spoof_video_streams_client_type','change_start_page'}:expected=expected.upper()
        assert preset[stored]==expected,k
photos=(R/'vendor/inhouse-photos/UpdateInstaller.kt').read_text();adapted=(R/'vendor/inhouse-services-updater/android/PhotosUpdateInstaller.kt').read_text()
assert photos[photos.index('  private fun download('):photos.index('  private fun publishDownloadProgress(')] in adapted
# Verification is unchanged; the verified PackageInfo is now returned so the
# result Activity can compare the installed version with that exact package.
verification=adapted.replace('private fun verifyApk(apkFile: File, expectedSha256: String?): PackageInfo {',
    'private fun verifyApk(apkFile: File, expectedSha256: String?) {').replace('    return archive\n  }', '  }')
assert photos[photos.index('  private fun validateUrl('):] in verification
assert 'setClassName(activity.packageName, "com.inhouse.services.updater.InstallActivity")' in adapted
assert 'putExtra("versionCode", getVersionCode(verifiedPackage))' in adapted
patch=(R/'patches/YouTubeCalma.kt').read_text()
assert '"name" to "com.inhouse.services.updater.InstallActivity", "exported" to "false"' in patch
subprocess.run(['python3',str(R/'vendor/inhouse-services-updater/verify.py'),str(R)],check=True)
source=(R/'src/es/calma/youtube/nativeapp/CalmaBuild.java').read_text()
assert re.search(r'VERSION = "([^"]+)"',source)[1]==m['version']
assert int(re.search(r'VERSION_CODE = (\d+)',source)[1])==m['versionCode']
print('PASS: exact preset, original native libraries/classes retained, updater assets, literal Photos installer, shared popup, release hash/size/version')
