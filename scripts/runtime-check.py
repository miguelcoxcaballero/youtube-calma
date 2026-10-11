#!/usr/bin/env python3
"""Run the exact signed APK and its native instrumentation on official Android."""
import argparse,hashlib,json,os,re,shutil,subprocess,time,urllib.request,traceback
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--api',type=int,required=True);a=p.parse_args()
OUT=ROOT/'build/evidence';OUT.mkdir(parents=True,exist_ok=True)
WORK=ROOT/'build/runtime';WORK.mkdir(exist_ok=True)
SDK=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
PKG='app.revanced.android.youtube'
def sha(p):return hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
def run(*args,timeout=60,**kwargs):return subprocess.run(list(map(str,args)),text=True,capture_output=True,timeout=timeout,**kwargs)
def required(result,name):
    (OUT/(name+'.txt')).write_text(result.stdout+'\n'+result.stderr)
    if result.returncode:raise RuntimeError(name+' failed: '+result.stdout+result.stderr)
    return result.stdout
def adb(*args,**kwargs):return run(SDK/'platform-tools/adb','-s','emulator-5554',*args,**kwargs)
report={'api':a.api,'signedInAccountTested':False};emulator=None
try:
    transport=json.loads((ROOT/'runtime-input/transport.json').read_text())
    apk=WORK/'candidate.apk'
    with apk.open('wb') as out:
        for part in transport['parts']:
            path=ROOT/'runtime-input'/part['name'];assert sha(path)==part['sha256'];out.write(path.read_bytes())
    assert sha(apk)==transport['apkSha256'];report['apkSha256']=sha(apk)
    image=f'system-images;android-{a.api};google_apis;x86_64'
    required(run(SDK/'cmdline-tools/latest/bin/sdkmanager','--licenses',input='y\n'*100,timeout=180),'licenses')
    required(run(SDK/'cmdline-tools/latest/bin/sdkmanager','platform-tools','emulator','build-tools;36.0.0',image,timeout=400),'sdk')
    signature=required(run(SDK/'build-tools/36.0.0/apksigner','verify','--print-certs',apk),'signature')
    assert '7133a4b3e4b9f9c4d2fbbd38b7dd2d44c7b8d0d7d16568bfe3da74a09e7887b6' in signature
    avdhome=WORK/'avds';avdhome.mkdir(exist_ok=True);os.environ['ANDROID_AVD_HOME']=str(avdhome)
    required(run(SDK/'cmdline-tools/latest/bin/avdmanager','create','avd','--force','--name','calma','--package',image,'--device','pixel_2','--path',avdhome/'calma.avd',input='no\n'),'avd')
    log=(OUT/'emulator.txt').open('w')
    emulator=subprocess.Popen([str(SDK/'emulator/emulator'),'-avd','calma','-port','5554','-accel','on','-no-window','-no-audio','-no-boot-anim','-no-snapshot','-no-metrics','-gpu','swiftshader','-cores','2','-memory','3072'],stdout=log,stderr=subprocess.STDOUT)
    deadline=time.monotonic()+300
    while time.monotonic()<deadline:
        if emulator.poll() is not None:raise RuntimeError('Emulator exited')
        if adb('shell','getprop','sys.boot_completed').stdout.strip()=='1':break
        time.sleep(2)
    else:raise RuntimeError('Emulator boot timed out')
    microg=WORK/'microg.apk'
    with urllib.request.urlopen('https://github.com/ReVanced/GmsCore/releases/download/v0.3.13.2.250932/app.revanced.android.gms-250932004-signed.apk') as response,microg.open('wb') as out:shutil.copyfileobj(response,out)
    assert sha(microg)=='62119f040d629a9cd3225f280649a464936730c6466cafb5b9dd5ded14269a65'
    required(adb('install','--no-incremental',microg,timeout=120),'install-microg')
    adb('shell','dumpsys','deviceidle','whitelist','+app.revanced.android.gms')
    adb('shell','pm','grant','app.revanced.android.gms','android.permission.POST_NOTIFICATIONS')
    # Reproduce the user's installed 0.1.5, then require a STRICTLY newer Android code.
    baseline=WORK/'baseline-0.1.5.apk'
    with urllib.request.urlopen('https://github.com/miguelcoxcaballero/youtube-calma/releases/download/v0.1.5/YouTube-Calma-0.1.5.apk') as response,baseline.open('wb') as out:shutil.copyfileobj(response,out)
    assert sha(baseline)=='2d6b2acd7b7c01ae5f23f0d3c80057d828f282b3ca3331a52d12378f9877c704'
    def apk_code(path):
        output=required(run(SDK/'build-tools/36.0.0/aapt2','dump','badging',path),'metadata-'+path.stem)
        return int(re.search(r"versionCode='(\d+)'",output)[1])
    baseline_code,candidate_code=apk_code(baseline),apk_code(apk)
    # The public catalog intentionally remains on the previous APK until publication.
    # Verify the staged candidate against its source release identity, not that older catalog.
    from release_metadata import release_version
    source_version,source_code=release_version()
    assert transport['version']==source_version and candidate_code==source_code and candidate_code>baseline_code
    required(adb('install','--no-incremental',baseline,timeout=120),'install-baseline')
    required(adb('install','-r','--no-incremental',apk,timeout=120),'install')
    report['upgrade']={'from':'0.1.5','installedCode':baseline_code,'candidateCode':candidate_code,'strictlyNewer':True,'replacedExistingInstall':True}

    adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
    required(adb('shell','appops','set',PKG,'REQUEST_INSTALL_PACKAGES','allow'),'installer-permission')
    adb('logcat','-b','all','-c')
    resolved=required(adb('shell','cmd','package','resolve-activity','--brief','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER',PKG),'launcher')
    component=[line.strip() for line in resolved.splitlines() if '/' in line and PKG in line][-1]
    required(adb('shell','am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',component),'launch')
    samples=[]
    for n in range(6):
        time.sleep(5);samples.append(adb('shell','pidof',PKG).stdout.strip())
    report['pids']=samples
    assert all(samples) and len(set(samples))==1,'App exited or restarted during launch'
    logs=required(adb('logcat','-b','all','-d'),'startup-logcat')
    report['startupActivityEvents']=[line for line in logs.splitlines() if ('ActivityTaskManager' in line or 'ActivityManager' in line) and (PKG in line or 'START u0' in line)]
    assert not re.search(r'Process: '+re.escape(PKG)+r'[,\s]|ANR in '+re.escape(PKG),logs),'Crash/ANR in app'
    for mode in ['no','yes']:
        adb('shell','cmd','uimode','night',mode);time.sleep(3)
        snap=subprocess.run([str(SDK/'platform-tools/adb'),'-s','emulator-5554','exec-out','screencap','-p'],capture_output=True,timeout=30)
        (OUT/('night-'+mode+'.png')).write_bytes(snap.stdout)
        adb('shell','uiautomator','dump','/sdcard/ui.xml',timeout=30);adb('pull','/sdcard/ui.xml',OUT/('night-'+mode+'.xml'))
        ui=OUT/('night-'+mode+'.xml')
        if ui.exists():
            report['ui-'+mode]=list(dict.fromkeys(node.get('text') or node.get('content-desc') for node in ET.parse(ui).iter('node') if node.get('text') or node.get('content-desc')))
    smoke=ROOT/'runtime-input/YouTube-Calma-Smoke.apk';assert sha(smoke)==transport['smokeSha256']
    required(adb('install','--no-incremental',smoke),'install-tests')
    report['profiles']=[]
    for phase in ['disable','restore']:
        profile=required(adb('shell','am','instrument','-w','-e','profile',phase,'es.calma.youtube.tests/es.calma.youtube.tests.NativeSmoke',timeout=120),'profile-'+phase)
        assert 'CALMA_PROFILE_OK '+phase in profile,profile
        report['profiles'].append(profile)
    result=required(adb('shell','am','instrument','-w','es.calma.youtube.tests/es.calma.youtube.tests.NativeSmoke',timeout=120),'native-tests')
    assert 'CALMA_NATIVE_TESTS_OK' in result,result
    report['nativeTests']=result
    # Capture confirmation before finishInstrumentation force-stops the target
    # process and provider; the instrumented APK must remain alive during handoff.
    installer=json.loads(re.search(r'CALMA_INSTALLER_UI (.+)',result)[1])
    texts=installer['texts'];report['installerUi']=texts
    report['installerPackages']=installer['packages']
    assert any('packageinstaller' in value for value in report['installerPackages']),report['installerPackages']
    assert any('update' in value.lower() for value in texts) and any('YouTube' in value for value in texts),texts
    report['installerHandoffPassed']=True
    report['passed']=True
except Exception as error:
    report['passed']=False;report['error']=str(error);(OUT/'error.txt').write_text(traceback.format_exc())
finally:
    try:
        final_logs=adb('logcat','-b','all','-d').stdout
        (OUT/'final-logcat.txt').write_text(final_logs)
        report['installerLogs']=[line for line in final_logs.splitlines() if any(word in line for word in ['PackageInstaller','InstallStart','InstallStaging','InstallInstalling','FileProvider','Permission Denial','CALMA_INSTALLER'])][-80:]
    except Exception:pass
    (OUT/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)
    if emulator is not None:emulator.terminate()
raise SystemExit(0 if report['passed'] else 1)
