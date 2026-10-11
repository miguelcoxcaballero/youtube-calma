"""One release identity for Java, manifest, patch compiler and update catalog."""
import re
import subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

def release_version():
    source = (ROOT / 'src/es/calma/youtube/nativeapp/CalmaBuild.java').read_text()
    return re.search(r'VERSION = "(\d+\.\d+\.\d+)"', source)[1], int(re.search(r'VERSION_CODE = (\d+)', source)[1])

def verify_apk(apk, build_tools, version, code):
    badging = subprocess.check_output([str(build_tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not package or package[1] != 'app.revanced.android.youtube' or int(package[2]) != code or package[3] != '20.40.45':
        raise ValueError('Signed APK identity differs from release: ' + badging.splitlines()[0])
    xml = subprocess.check_output([str(build_tools / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
    entries = [block for block in xml.split('E: meta-data') if '"es.calma.version"' in block]
    if len(entries) != 1 or not re.search(r'android:value[^\n]*="' + re.escape(version) + '"', entries[0]):
        raise ValueError('Signed APK release metadata differs from ' + version)
    return code
