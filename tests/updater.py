"""Exercise the exact shared popup bundled by this app in Chromium."""
import subprocess,sys
from pathlib import Path
R=Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,str(R/'vendor/inhouse-services-updater/tests/browser.py'),'--root',str(R)],check=True)
