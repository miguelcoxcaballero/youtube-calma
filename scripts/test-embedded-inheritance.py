#!/usr/bin/env python3
"""Test embedded host inheritance against the actual pinned YouTube DEX.

Requires local Morphe, ECJ and an already compiled experimental patch bundle.
No downloads, credentials, APK changes, signing or CI calls are performed.

Example:
  python3 scripts/test-embedded-inheritance.py --morphe /path/to/morphe.jar \
      --patch-bundle build/embedded-youtube/work/embedded-patches.jar
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PINNED_BASE_SHA256 = "220c4eb6d315a8887ef404d6530b16196701b84848e568360b0b58bdbe3103f2"
# ECJ 3.39.0, the existing local dependency selected by scripts/setup-tools.py.
ECJ_SHA256 = "01f5a92ac19bb2b3bf85e295a68f2c73c264369109158b566ce9b490af982948"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require_hash(path: Path, expected: str, label: str) -> None:
    if not path.is_file() or digest(path) != expected:
        raise SystemExit(f"Missing or mismatched pinned {label}: {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--morphe", type=Path, required=True, help="Existing pinned Morphe JAR")
    parser.add_argument("--ecj", type=Path, default=ROOT / "tools/ecj.jar")
    parser.add_argument("--stock", type=Path, default=ROOT / "base/youtube-original.apk")
    parser.add_argument(
        "--patch-bundle", type=Path,
        default=ROOT / "build/embedded-youtube/work/embedded-patches.jar",
        help="Exact compiled experimental patch bundle to test")
    args = parser.parse_args()
    args.morphe = args.morphe.resolve()
    args.ecj = args.ecj.resolve()
    args.stock = args.stock.resolve()
    args.patch_bundle = args.patch_bundle.resolve()

    base_lock = json.loads((ROOT / "base.lock.json").read_text())
    if base_lock["sha256"] != PINNED_BASE_SHA256:
        raise SystemExit("Pinned base changed; review the inheritance regression fixture first")
    require_hash(args.stock, PINNED_BASE_SHA256, "YouTube APK")
    require_hash(args.ecj, ECJ_SHA256, "ECJ 3.39.0")
    tools_lock = json.loads((ROOT / "tools.lock.json").read_text())
    require_hash(args.morphe, tools_lock["morphe"]["sha256"], "Morphe")
    if not args.patch_bundle.is_file():
        raise SystemExit(f"Build the experimental patch bundle first: {args.patch_bundle}")
    with zipfile.ZipFile(args.patch_bundle) as bundle:
        if "es/calma/youtube/patches/EmbeddedGmsKt.class" not in bundle.namelist():
            raise SystemExit("The selected bundle has no compiled EmbeddedGms client patch")

    print(f"Patch bundle: {args.patch_bundle}", flush=True)
    print(f"Patch SHA-256: {digest(args.patch_bundle)}", flush=True)
    with tempfile.TemporaryDirectory(prefix="embedded-inheritance-") as temporary:
        classes = Path(temporary) / "classes"
        classes.mkdir()
        subprocess.run([
            "java", "-jar", str(args.ecj), "-source", "17", "-target", "17", "-proc:none",
            "-classpath", str(args.morphe), "-d", str(classes),
            str(ROOT / "tests/EmbeddedInheritanceRegression.java"),
        ], check=True, cwd=ROOT)
        classpath = os.pathsep.join(map(str, (classes, args.patch_bundle, args.morphe)))
        subprocess.run([
            "java", "-cp", classpath, "EmbeddedInheritanceRegression", str(args.stock),
        ], check=True, cwd=ROOT)


if __name__ == "__main__":
    main()
