#!/usr/bin/env python3
"""Reassemble the exact signed candidate from pinned, individually verified parts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile
from urllib.parse import urlsplit


def simple_name(value):
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', value):
        raise ValueError('Transport filenames must be simple basenames')
    return value


def digest(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9a-f]{64}', value):
        raise ValueError('Transport digest must be a lowercase SHA-256')
    return value


def size(value):
    if type(value) is not int or value <= 0:
        raise ValueError('Transport size must be a positive integer')
    return value


def reconstruct(transport_path, candidate_path, output_dir):
    transport_path = Path(transport_path)
    transport = json.loads(transport_path.read_text())
    candidate = json.loads(Path(candidate_path).read_text())
    filename = simple_name(transport['fileName'])
    expected_digest = digest(transport['sha256'])
    expected_size = size(transport['sizeBytes'])
    if (filename != Path(urlsplit(candidate['apkUrl']).path).name
            or expected_digest != candidate['apkSha256'] or expected_size != candidate['apkSizeBytes']):
        raise ValueError('Transport differs from the signed candidate manifest')
    for key in ('version', 'versionCode'):
        if key in transport and transport[key] != candidate[key]:
            raise ValueError('Transport release identity differs from candidate')
    parts = transport['parts']
    if not isinstance(parts, list) or not parts:
        raise ValueError('Transport requires at least one part')
    names = [simple_name(part['fileName']) for part in parts]
    if len(set(names)) != len(names):
        raise ValueError('Transport contains duplicate parts')
    if sum(size(part['sizeBytes']) for part in parts) != expected_size:
        raise ValueError('Transport part sizes do not sum to candidate size')
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        whole = hashlib.sha256()
        total = 0
        with tempfile.NamedTemporaryFile(dir=output_dir, prefix='.candidate-', delete=False) as output:
            temporary = Path(output.name)
            for part in parts:
                path = transport_path.parent / simple_name(part['fileName'])
                if path.is_symlink() or not path.is_file():
                    raise ValueError('Transport part must be a regular non-symlink file')
                part_hash = hashlib.sha256()
                part_size = 0
                with path.open('rb') as source:
                    for chunk in iter(lambda: source.read(1024 * 1024), b''):
                        part_hash.update(chunk)
                        whole.update(chunk)
                        output.write(chunk)
                        part_size += len(chunk)
                if part_size != size(part['sizeBytes']) or part_hash.hexdigest() != digest(part['sha256']):
                    raise ValueError('Transport part failed size or SHA-256 verification')
                total += part_size
        if total != expected_size or whole.hexdigest() != expected_digest:
            raise ValueError('Reconstructed APK failed final size or SHA-256 verification')
        target = output_dir / filename
        temporary.replace(target)
        temporary = None
        return target
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', type=Path, required=True)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    print(reconstruct(args.transport, args.candidate, args.output_dir))
