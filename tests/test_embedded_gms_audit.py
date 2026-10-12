"""Regression checks for the audit's evidence boundaries, not microG functionality."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("embedded_gms_audit", ROOT / "scripts/audit-embedded-gms.py")
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


def dex_fixture(defined, referenced=()):
    """Minimal standard DEX tables consumed by the existing class reader."""
    names = list(dict.fromkeys([*defined, *referenced]))
    strings_offset = 112
    types_offset = strings_offset + len(names) * 4
    classes_offset = types_offset + len(names) * 4
    data = bytearray(classes_offset + len(defined) * 32)
    data[:8] = b"dex\n035\0"
    struct.pack_into("<II", data, 0x38, len(names), strings_offset)
    struct.pack_into("<II", data, 0x40, len(names), types_offset)
    struct.pack_into("<II", data, 0x60, len(defined), classes_offset)
    for index, name in enumerate(names):
        encoded = name.encode("ascii")
        assert len(encoded) < 128
        struct.pack_into("<I", data, strings_offset + index * 4, len(data))
        struct.pack_into("<I", data, types_offset + index * 4, index)
        data.extend(bytes([len(encoded)]) + encoded + b"\0")
    for index, name in enumerate(defined):
        struct.pack_into("<I", data, classes_offset + index * 32, names.index(name))
    return bytes(data)


class EvidenceTests(unittest.TestCase):
    def test_shared_references_are_not_duplicate_definitions(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as out:
                out.writestr("classes.dex", dex_fixture(["Lhost/Main;"], ["Lshared/Reference;"]))
                out.writestr("classes2.dex", dex_fixture(["Lmodule/Service;"], ["Lshared/Reference;"]))
            result = audit.apk_dex(apk)
            self.assertEqual(result["classes"], {"Lhost/Main;", "Lmodule/Service;"})
            self.assertEqual(result["duplicateDefinitions"], [])

    def test_duplicate_classes_across_dex_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as out:
                out.writestr("classes.dex", dex_fixture(["Lshared/Conflicting;"]))
                out.writestr("classes2.dex", dex_fixture(["Lshared/Conflicting;"]))
            self.assertEqual(audit.apk_dex(apk)["duplicateDefinitions"], ["Lshared/Conflicting;"])

    def test_endpoint_identity_does_not_replace_binder_protocol(self):
        descriptor = "com.google.android.auth.IAuthManagerService"
        strings = {descriptor, "app.revanced.android.gms", "app.revanced", "app.revanced.android.youtube",
                   "content://app.revanced.android.gsf.gservices/prefix", "app.revanced.android.gms.auth.accounts",
                   "app.revanced.android.providers.gsf.permission.READ_GSERVICES", "app.revanced.android.c2dm.permission.SEND",
                   "com.google.android.gms.auth.GetToken", "com.google.android.gms.auth.service.START"}
        result = audit.endpoints(strings, "app.revanced.android.gms", "app.revanced")
        self.assertEqual(result["externalPackageLiterals"], ["app.revanced.android.gms"])
        self.assertEqual(result["externalAccountTypeLiterals"], ["app.revanced"])
        self.assertEqual(result["binderDescriptorLiterals"], [descriptor])
        self.assertNotIn("app.revanced.android.youtube", result["externalAuthorityAndPermissionLiterals"])
        self.assertIn("app.revanced.android.providers.gsf.permission.READ_GSERVICES", result["externalAuthorityAndPermissionLiterals"])
        self.assertIn("app.revanced.android.c2dm.permission.SEND", result["externalAuthorityAndPermissionLiterals"])
        self.assertEqual(result["externalProviderUris"], ["content://app.revanced.android.gsf.gservices/prefix"])
        wrong = audit.endpoints({"es.calma.microg.auth.IAuthManagerService"}, "app.revanced.android.gms", "app.revanced")
        comparison = audit.compare_binder_descriptors(result, wrong)
        self.assertEqual(comparison["missingOriginalLiterals"], [descriptor])
        self.assertFalse(comparison["runtimeCompatibilityVerified"])

    def test_manifest_components_do_not_inherit_sibling_service_type(self):
        xml = '''N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=1)
    E: application (line=2)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="HostApplication" (Raw: "HostApplication")
      E: service (line=3)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="org.microg.gms.gcm.McsService"
        E: intent-filter (line=4)
          E: action (line=5)
            A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.google.TEST"
      E: service (line=6)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="PlayerService"
        A: http://schemas.android.com/apk/res/android:foregroundServiceType(0x01010599)=0x00000002
'''
        summary = audit.manifest_summary("package: name='example.app' versionCode='12' versionName='1.0'\nminSdkVersion:'28'\ntargetSdkVersion:'35'", xml)
        self.assertEqual(summary["minSdk"], 28)
        self.assertEqual(summary["targetSdk"], 35)
        first, second = summary["components"]
        self.assertNotIn("foregroundServiceType", first)
        self.assertEqual(first["actions"], ["com.google.TEST"])
        self.assertEqual(second["actions"], [])
        self.assertEqual(second["foregroundServiceType"], "0x00000002")

    def test_dependency_install_is_distinguished_from_candidate_install(self):
        source = '''
with urllib.request.urlopen("https://github.com/ReVanced/GmsCore/releases/download/test/core.apk") as response, helper.open("wb") as out:
    shutil.copyfileobj(response, out)
required(adb("install", "--no-incremental", helper), "install-microg")
required(adb("install", "-r", candidate), "install-candidate")
'''
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "runner.py"
            runner.write_text(source)
            result = audit.ci_dependency_evidence(runner, "app.revanced.android.gms")
            installs = [e for e in result["evidence"] if e["kind"] == "externalDependencyInstall"]
            self.assertTrue(result["installsExternalGmsCore"])
            self.assertEqual(len(installs), 1)
            runner.write_text('adb("install", candidate)\n')
            result = audit.ci_dependency_evidence(runner, "app.revanced.android.gms")
            self.assertFalse(result["installsExternalGmsCore"])
            self.assertFalse(result["provesIndependence"])

    def test_malformed_dex_fails_instead_of_reporting_empty_evidence(self):
        with self.assertRaises(ValueError):
            audit.dex_strings(b"not a dex")
        data = bytearray(dex_fixture(["Lhost/Main;"]))
        struct.pack_into("<I", data, 0x3C, len(data) + 1)
        with self.assertRaises(ValueError):
            audit.dex_strings(data)


if __name__ == "__main__":
    unittest.main()
