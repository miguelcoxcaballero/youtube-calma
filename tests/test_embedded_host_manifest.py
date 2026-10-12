"""Boundary tests for the single-APK backend's Android-visible registrations.

Use a tiny compiled-manifest tree fixture; no binary download or Android device is
needed to catch private resource leaks, provider grants and exported endpoints.
"""
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'experiments/embedded-gms/prepare-host.py'
NS = '{http://schemas.android.com/apk/res/android}'
HOST = 'app.revanced.android.youtube'

MODULE_TREE = '''E: manifest
  A: package="app.revanced.android.gms"
  E: application
    A: android:name="androidx.multidex.MultiDexApplication"
    A: android:theme=@0x7f130240
    E: activity
      A: android:name="org.microg.gms.auth.login.LoginActivity"
      A: android:theme=@0x7f130275
      A: android:label=@0x7f120001
      A: android:icon=@0x7f080001
      A: android:exported="true"
    E: provider
      A: android:name="org.microg.gms.gservices.GServicesProvider"
      A: android:authorities="app.revanced.android.gsf.gservices"
      A: android:exported="true"
      A: android:readPermission="app.revanced.android.providers.gsf.permission.READ_GSERVICES"
    E: provider
      A: android:name="org.microg.gms.settings.GmsFileProvider"
      A: android:authorities="app.revanced.android.gms.fileprovider"
      A: android:grantUriPermissions="true"
      E: meta-data
        A: android:name="android.support.FILE_PROVIDER_PATHS"
        A: android:resource=@0x7f160001
    E: service
      A: android:name="org.microg.gms.auth.loginservice.GoogleLoginService"
      E: meta-data
        A: android:name="android.accounts.AccountAuthenticator"
        A: android:resource=@0x7f160002
    E: service
      A: android:name="androidx.work.impl.background.systemjob.SystemJobService"
      A: android:permission="android.permission.BIND_JOB_SERVICE"
    E: service
      A: android:name="org.microg.gms.checkin.CheckinService"
    E: service
      A: android:name="org.microg.gms.gcm.McsService"
    E: receiver
      A: android:name="org.microg.gms.checkin.TriggerReceiver"
      A: android:exported="true"
    E: provider
      A: android:name="org.microg.gms.phenotype.ConfigurationProvider"
      A: android:authorities="app.revanced.android.gms.phenotype"
'''
HOST_TREE = '''E: manifest
  A: package="app.revanced.android.youtube"
  E: application
    E: service
      A: android:name="androidx.work.impl.background.systemjob.SystemJobService"
'''


class EmbeddedHostManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        (self.root / 'scripts').mkdir()
        shutil.copyfile(ROOT / 'scripts/audit-embedded-gms.py', self.root / 'scripts/audit-embedded-gms.py')
        self.host = self.root / 'host.apk'; self.host.write_bytes(b'pinned host fixture')
        self.backend = self.root / 'module.apk'; self.backend.write_bytes(b'pinned module fixture')
        sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
        (self.root / 'base.lock.json').write_text(json.dumps({'sha256': sha(self.host)}))
        self.report = self.root / 'backend-report.json'
        self.report.write_text(json.dumps({
            'outputSha256': sha(self.backend), 'hostPackage': HOST,
            'accountType': 'es.inhouse.youtube.google',
            'componentClassMapping': {'androidx.work.impl.background.systemjob.SystemJobService':
                                      'inhouse.microg.module.androidx.work.impl.background.systemjob.SystemJobService'},
            'endpointMappings': {
                'app.revanced.android.gsf.gservices': HOST + '.inhouse.gms.gservices',
                'app.revanced.android.gms.fileprovider': HOST + '.inhouse.gms.fileprovider',
            },
        }))
        self.output = self.root / 'generated'
        spec = importlib.util.spec_from_file_location('embedded_host_under_test', SCRIPT)
        self.module = importlib.util.module_from_spec(spec); spec.loader.exec_module(self.module)
        self.module.ROOT = self.root

    def tearDown(self):
        self.tmp.cleanup()

    def generate(self, tree=MODULE_TREE):
        def xmltree(command, **kwargs):
            return HOST_TREE if str(self.host) in command else tree
        args = ['prepare-host.py', '--backend-apk', str(self.backend), '--backend-report', str(self.report),
                '--host-apk', str(self.host), '--output', str(self.output), '--aapt2', 'aapt2']
        with patch.object(sys, 'argv', args), patch.object(self.module.subprocess, 'check_output', side_effect=xmltree), redirect_stdout(io.StringIO()):
            self.module.main()
        return ET.parse(self.output / 'manifest-fragment.xml').getroot()

    def test_private_module_resources_never_escape_into_android_descriptors(self):
        root = self.generate()
        for item in root.iter():
            self.assertFalse(any(value.startswith('@0x7f') for value in item.attrib.values()), item.attrib)
        login = next(x for x in root.iter('activity') if x.get(NS + 'name').endswith('LoginActivity'))
        theme = next(x for x in login if x.get(NS + 'name') == 'inhouse.gms.theme')
        self.assertEqual(theme.get(NS + 'value'), '0x7f130275')
        self.assertEqual(login.get(NS + 'theme'), '@android:style/Theme.Material.Light.NoActionBar')
        self.assertNotIn(NS + 'icon', login.attrib)
        self.assertNotIn(NS + 'label', login.attrib)

    def test_only_android_bound_services_are_exported_and_require_system_permissions(self):
        root = self.generate()
        exported = {item.get(NS + 'name'): item.get(NS + 'permission') for item in root.find('application')
                    if item.get(NS + 'exported') == 'true'}
        self.assertEqual(exported, {
            'org.microg.gms.auth.loginservice.GoogleLoginService': 'android.permission.BIND_ACCOUNT_AUTHENTICATOR',
            'inhouse.microg.module.androidx.work.impl.background.systemjob.SystemJobService': 'android.permission.BIND_JOB_SERVICE',
        })
        for item in root.find('application'):
            if item.tag in {'activity', 'provider', 'receiver', 'service'}:
                self.assertEqual(item.get(NS + 'process'), ':inhouse_gms')

    def test_android_parsed_authenticator_has_host_string_and_new_account_type(self):
        self.generate()
        auth = ET.parse(self.output / 'res/xml/inhouse_gms_authenticator.xml').getroot()
        self.assertEqual(auth.get(NS + 'accountType'), 'es.inhouse.youtube.google')
        # AccountAuthenticator reads a resource ID, so a literal label silently becomes0.
        label = auth.get(NS + 'label')
        self.assertTrue(label.startswith('@string/'), label)
        label_name = label.removeprefix('@string/')
        strings = [item for p in (self.output / 'res/values').glob('*.xml')
                   for item in ET.parse(p).getroot() if item.tag == 'string']
        self.assertTrue(any(item.get('name') == label_name for item in strings))

    def test_provider_authorities_permissions_and_file_roots_stay_inside_module(self):
        root = self.generate()
        providers = list(root.iter('provider'))
        self.assertTrue(all(x.get(NS + 'authorities').startswith(HOST + '.inhouse.gms.') for x in providers))
        gservices = next(x for x in providers if x.get(NS + 'name').endswith('GServicesProvider'))
        self.assertEqual(gservices.get(NS + 'readPermission'), HOST + '.inhouse.gms.gsf.permission.READ_GSERVICES')
        paths = ET.parse(self.output / 'res/xml/inhouse_gms_file_paths.xml').getroot()
        self.assertEqual({x.tag for x in paths}, {'cache-path', 'files-path'})
        self.assertFalse(any(x.tag in {'root-path', 'external-path'} for x in paths))
        self.assertFalse(any(x.get(NS + 'name').endswith('ConfigurationProvider') for x in providers))

    def test_resource_leak_in_unreviewed_metadata_fails_closed(self):
        extra = '''    E: service
      A: android:name="org.microg.gms.example.UnknownService"
      E: meta-data
        A: android:name="unreviewed.metadata"
        A: android:resource=@0x7f190001
'''
        with self.assertRaisesRegex(ValueError, 'Untranslated module resource'):
            self.generate(MODULE_TREE + extra)

    def test_wrong_backend_hash_is_rejected_before_generation(self):
        self.backend.write_bytes(b'different module')
        with self.assertRaisesRegex(AssertionError, 'Backend report mismatch'):
            self.generate()
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
