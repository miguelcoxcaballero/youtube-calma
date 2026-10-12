"""Signed release transport must preserve exact bytes and fail closed on tampering."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/reconstruct-embedded-candidate.py'
SPEC = importlib.util.spec_from_file_location('embedded_transport', SCRIPT)
TRANSPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TRANSPORT)


class EmbeddedTransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.payload = b'exact signed APK fixture'
        blocks = (self.payload[:10], self.payload[10:])
        self.transport = {'fileName': 'candidate.apk', 'sha256': hashlib.sha256(self.payload).hexdigest(),
                          'sizeBytes': len(self.payload), 'parts': []}
        for i, block in enumerate(blocks):
            name = 'part-' + str(i) + '.bin'
            (self.root / name).write_bytes(block)
            self.transport['parts'].append({'fileName': name, 'sha256': hashlib.sha256(block).hexdigest(),
                                           'sizeBytes': len(block)})
        self.candidate = {'apkUrl': 'https://example.invalid/candidate.apk',
                          'apkSha256': self.transport['sha256'], 'apkSizeBytes': len(self.payload)}

    def reconstruct(self):
        (self.root / 'transport.json').write_text(json.dumps(self.transport))
        (self.root / 'candidate.json').write_text(json.dumps(self.candidate))
        return TRANSPORT.reconstruct(self.root / 'transport.json', self.root / 'candidate.json',
                                     self.root / 'output')

    def test_reconstructs_exact_bytes(self):
        self.assertEqual(self.reconstruct().read_bytes(), self.payload)

    def test_changed_part_is_rejected_and_not_published(self):
        (self.root / 'part-0.bin').write_bytes(b'changed!!!')
        with self.assertRaises(ValueError):
            self.reconstruct()
        self.assertEqual(list((self.root / 'output').iterdir()), [])

    def test_changed_manifest_hash_is_rejected(self):
        self.candidate['apkSha256'] = '0' * 64
        with self.assertRaises(ValueError):
            self.reconstruct()

    def test_reordered_parts_fail_final_digest(self):
        self.transport['parts'].reverse()
        with self.assertRaises(ValueError):
            self.reconstruct()

    def test_traversal_names_and_duplicate_parts_rejected(self):
        for name in ('../part-0.bin', '/part-0.bin', 'part-0.bin'):
            with self.subTest(name=name):
                self.transport['parts'][1]['fileName'] = name
                with self.assertRaises(ValueError):
                    self.reconstruct()

    def test_symlink_parts_rejected(self):
        first = self.root / 'part-0.bin'
        first.rename(self.root / 'source.bin')
        first.symlink_to(self.root / 'source.bin')
        with self.assertRaises(ValueError):
            self.reconstruct()


if __name__ == '__main__':
    unittest.main()
