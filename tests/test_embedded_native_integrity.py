"""Native integrity and no-repeat-inflation regressions using the real Java loader."""
import hashlib
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'experiments/embedded-gms/youtube-runtime'
SPEC = importlib.util.spec_from_file_location('embedded_builder', ROOT / 'experiments/embedded-gms/build-youtube.py')
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


def make_zip(path, entries):
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, content in entries:
                archive.writestr(name, content)


class NativeIntegrityTest(unittest.TestCase):
    def test_buildtime_fingerprints_and_ambiguous_paths(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / 'module.apk'
            content = b'fixture native payload'
            make_zip(archive, [('lib/x86_64/libc++_shared.so', content)])
            self.assertEqual(BUILDER.native_library_hashes(archive),
                             {'lib/x86_64/libc++_shared.so': hashlib.sha256(content).hexdigest()})
            for entries in [
                [('lib/x86_64/liba.so', b'a'), ('lib/x86_64/liba.so', b'b')],
                [('lib/x86_64/../liba.so', b'a')],
                [('lib/x86_64/nested/liba.so', b'a')],
            ]:
                with self.subTest(entries=entries):
                    make_zip(archive, entries)
                    with self.assertRaises(RuntimeError):
                        BUILDER.native_library_hashes(archive)

    def test_runtime_cold_warm_corrupt_and_rejected_payloads(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            sources = work / 'src'
            stubs = {
                'android/system/StructStat.java': 'package android.system; public final class StructStat { public int st_mode; }',
                'android/system/Os.java': '''package android.system;
                    public final class Os {
                      public static StructStat stat(String name) throws Exception {
                        StructStat result = new StructStat();
                        java.util.Set<java.nio.file.attribute.PosixFilePermission> p =
                          java.nio.file.Files.getPosixFilePermissions(java.nio.file.Path.of(name));
                        if (p.contains(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) result.st_mode |= 0200;
                        if (p.contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)) result.st_mode |= 0020;
                        if (p.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)) result.st_mode |= 0002;
                        return result;
                      }
                    }''',
                'inhouse/microg/host/EmbeddedGmsBuild.java': '''package inhouse.microg.host;
                    public final class EmbeddedGmsBuild {
                      public static final String GMS_SHA256 = ""; public static final String BRIDGE_SHA256 = "";
                      public static final String[] COMPONENTS = {}; public static final String[][] NATIVE_LIBRARIES = {};
                    }''',
                'inhouse/microg/host/ArchiveResources.java': '''package inhouse.microg.host;
                    final class ArchiveResources {
                      static android.content.res.Resources load(android.content.Context c, java.io.File f) {
                        throw new UnsupportedOperationException("Unused in extraction fixture");
                      }
                    }''',
            }
            for name, content in stubs.items():
                path = sources / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            classes = work / 'classes'
            # Execute against the JDK's real ZIP/filesystem classes. The SDK also
            # bundles java.* stubs, which conflict with named Java 17 modules.
            android = work / 'android-framework-only.jar'
            with zipfile.ZipFile(ROOT / 'tools/android-35/android.jar') as sdk, \
                    zipfile.ZipFile(android, 'w', zipfile.ZIP_DEFLATED) as framework:
                for entry in sdk.infolist():
                    if not entry.filename.startswith(('java/', 'javax/', 'org/w3c/', 'org/xml/', 'org/ietf/')):
                        framework.writestr(entry, sdk.read(entry))
            command = ['java', '-jar', str(ROOT / 'tools/ecj.jar'), '-source', '17', '-target', '17',
                       '-proc:none', '-classpath', str(android), '-d', str(classes),
                       str(RUNTIME / 'host/inhouse/microg/host/EmbeddedRuntime.java'),
                       str(ROOT / 'tests/EmbeddedNativeIntegrityRegression.java'),
                       *map(str, sources.rglob('*.java'))]
            compiled = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            entries = [('lib/arm64-v8a/libother.so', b'other ABI'),
                       ('lib/x86_64/liba.so', b'alpha-native' * 1024),
                       ('lib/x86_64/libb.so', b'beta-native' * 1024)]
            make_zip(work / 'valid.zip', entries)
            pins = BUILDER.native_library_hashes(work / 'valid.zip')
            (work / 'pins.tsv').write_text(''.join(name + '\t' + digest + '\n' for name, digest in pins.items()))
            (work / 'expected-liba.bin').write_bytes(entries[1][1])
            make_zip(work / 'unexpected.zip', entries + [('lib/x86_64/libextra.so', b'extra')])
            make_zip(work / 'missing.zip', entries[1:])  # Missing unused ABI must also fail.
            make_zip(work / 'duplicate.zip', entries + [entries[1]])
            make_zip(work / 'traversal.zip', entries + [('lib/x86_64/../libbad.so', b'bad')])
            make_zip(work / 'tampered.zip', [entries[0], (entries[1][0], b'tampered'), entries[2]])
            tested = subprocess.run(['java', '-cp', str(classes) + ':' + str(android),
                                     'inhouse.microg.host.EmbeddedNativeIntegrityRegression', str(work)],
                                    capture_output=True, text=True)
            self.assertEqual(tested.returncode, 0, tested.stdout + tested.stderr)
            self.assertIn('NATIVE_INTEGRITY_OK cold=2 warm=0 repaired=1 rejected=7', tested.stdout)


if __name__ == '__main__':
    unittest.main()
