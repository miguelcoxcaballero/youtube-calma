"""Credential-page evidence must reject loading screens and Google challenges."""
import importlib.util
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / 'experiments/embedded-gms/run-youtube-smoke.py'
SPEC = importlib.util.spec_from_file_location('youtube_smoke', SCRIPT)
SMOKE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SMOKE)


def sign_in_tree(label='', *, password=False):
    root = ET.Element('hierarchy')
    web = ET.SubElement(root, 'node', {'class': 'android.webkit.WebView'})
    for text in ('Sign in', 'Forgot email?', 'Next'):
        ET.SubElement(web, 'node', {'class': 'android.view.View', 'text': text})
    field = ET.SubElement(web, 'node', {
        'class': 'android.widget.EditText', 'text': '', 'content-desc': label,
        'enabled': 'true', 'focusable': 'true', 'password': str(password).lower(),
        'bounds': '[30,350][450,410]',
    })
    return root, web, field


def classify(root):
    return SMOKE.login_ui_structure(list(root.iter('node')))


class CredentialPageTest(unittest.TestCase):
    def test_unlabelled_complete_google_identifier_schema(self):
        root, _, _ = sign_in_tree()
        structure = classify(root)
        self.assertFalse(structure['credentialLabelOrIdRecognized'])
        self.assertTrue(structure['credentialEntryLayoutRecognized'])
        self.assertTrue(SMOKE.credential_page_visible(structure))

    def test_explicit_email_label_still_recognized(self):
        root, _, _ = sign_in_tree('Email or phone number')
        structure = classify(root)
        self.assertTrue(structure['credentialLabelOrIdRecognized'])
        self.assertTrue(SMOKE.credential_page_visible(structure))

    def test_password_is_not_identifier_entry(self):
        root, _, _ = sign_in_tree(password=True)
        self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_captcha_is_not_success_even_with_email_label(self):
        root, web, _ = sign_in_tree('Email or phone')
        ET.SubElement(web, 'node', {'text': 'Type the text you hear or see'})
        self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_network_error_is_not_success(self):
        root, web, _ = sign_in_tree('Email or phone')
        ET.SubElement(web, 'node', {'text': 'Check your network connection'})
        self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_arbitrary_editable_loading_screen_is_not_success(self):
        root, web, field = sign_in_tree()
        for child in list(web):
            if child is not field:
                web.remove(child)
        ET.SubElement(web, 'node', {'text': 'Just a sec...', 'class': 'android.widget.ProgressBar'})
        self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_each_identifier_marker_is_required_for_unlabelled_field(self):
        for marker in ('Sign in', 'Forgot email?', 'Next'):
            with self.subTest(marker=marker):
                root, web, _ = sign_in_tree()
                web.remove(next(node for node in web if node.get('text') == marker))
                self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_native_markers_outside_webview_do_not_qualify(self):
        root, web, field = sign_in_tree()
        for child in list(web):
            if child is not field:
                web.remove(child)
                root.append(child)
        self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_disabled_hidden_and_nonfocusable_fields_do_not_qualify(self):
        for attribute, value in (('enabled', 'false'), ('focusable', 'false'),
                                 ('bounds', '[0,0][0,0]'), ('bounds', '')):
            with self.subTest(attribute=attribute, value=value):
                root, _, field = sign_in_tree()
                field.set(attribute, value)
                self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_extra_editable_does_not_qualify_for_semantic_fallback(self):
        root, web, field = sign_in_tree()
        ET.SubElement(web, 'node', dict(field.attrib))
        self.assertFalse(SMOKE.credential_page_visible(classify(root)))

    def test_native_progress_behind_real_identifier_form_is_not_rejected(self):
        # API 34/36 pass evidence includes the native connecting view underneath
        # Google's rendered WebView; its existence alone must not reject it.
        root, _, _ = sign_in_tree()
        ET.SubElement(root, 'node', {'class': 'android.widget.ProgressBar'})
        ET.SubElement(root, 'node', {'text': 'Establishing a connection to Google'})
        self.assertTrue(SMOKE.credential_page_visible(classify(root)))


class SignedUpgradeIdentityTest(unittest.TestCase):
    def test_parses_android_package_metadata_without_retaining_unrelated_text(self):
        for uid_label in ('userId', 'appId'):
            with self.subTest(uid_label=uid_label):
                result = SMOKE.installed_identity('''Packages:
  Package [app.revanced.android.youtube]:
    %s=10123
    versionCode=1557278158 minSdk=34 targetSdk=35
    arbitraryDiagnostic=not retained
    User 0:
      firstInstallTime=2026-10-12 21:30:00
''' % uid_label)
                self.assertEqual(result, {'versionCode': 1557278158, 'uid': 10123,
                                          'firstInstallTime': '2026-10-12 21:30:00'})

    def test_missing_identity_fails_closed(self):
        with self.assertRaises(RuntimeError):
            SMOKE.installed_identity('versionCode=1557278158 minSdk=34\nuserId=10123\n')

    def test_strictly_newer_replacement_preserves_identity(self):
        baseline = {'versionCode': 10, 'uid': 10123, 'firstInstallTime': '2026-10-12 21:30:00'}
        SMOKE.verify_upgrade_identity(baseline, {**baseline, 'versionCode': 11})

    def test_reinstall_same_code_and_downgrade_fail(self):
        baseline = {'versionCode': 10, 'uid': 10123, 'firstInstallTime': '2026-10-12 21:30:00'}
        for code in (9, 10):
            with self.subTest(versionCode=code), self.assertRaises(RuntimeError):
                SMOKE.verify_upgrade_identity(baseline, {**baseline, 'versionCode': code})

    def test_fresh_install_is_not_mistaken_for_upgrade(self):
        baseline = {'versionCode': 10, 'uid': 10123, 'firstInstallTime': '2026-10-12 21:30:00'}
        for change in ({'uid': 10124}, {'firstInstallTime': '2026-10-12 21:31:00'}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                SMOKE.verify_upgrade_identity(baseline, {**baseline, 'versionCode': 11, **change})


if __name__ == '__main__':
    unittest.main()
