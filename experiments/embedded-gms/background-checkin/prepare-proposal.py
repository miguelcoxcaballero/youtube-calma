#!/usr/bin/env python3
"""Build the inactive background-checkin proposal against a provenanced backend asset.

No host APK, signing, release or current build pipeline is modified. The output is
an uninstalled module requiring the companion runtime templates and manifest.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--backend', required=True, type=Path)
    p.add_argument('--backend-report', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--morphe', default=ROOT / 'tools/morphe.jar', type=Path)
    p.add_argument('--enable-background-checkin', action='store_true')
    args = p.parse_args()
    if not args.enable_background_checkin:
        raise SystemExit('Inactive proposal: explicit --enable-background-checkin is required')
    base = json.loads(args.backend_report.read_text())
    lock = json.loads((HERE.parent / 'upstream.lock.json').read_text())
    tool = json.loads((ROOT / 'tools.lock.json').read_text())['morphe']
    if base['inputSha256'] != lock['apkSha256'] or base['sourceCommit'] != lock['sourceCommit']:
        raise SystemExit('Unreviewed backend provenance')
    if digest(args.backend) != base['outputSha256'] or digest(args.morphe) != tool['sha256']:
        raise SystemExit('Backend or tool hash mismatch')
    if args.backend.resolve() == args.output.resolve():
        raise SystemExit('Do not overwrite the input backend')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    source = HERE / 'patches/BackgroundCheckinPatch.kt'
    source_hash = digest(source)
    script_hash = digest(__file__)
    with tempfile.TemporaryDirectory(prefix='background-checkin-', dir=args.output.parent) as temporary:
        work = Path(temporary)
        classes = work / 'classes'; classes.mkdir()
        subprocess.run(['java', '-cp', str(ROOT / 'tools/kotlin/*'),
            'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-Xskip-metadata-version-check',
            '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', str(args.morphe),
            '-d', str(classes), str(source)], check=True)
        jar = work / 'proposal.jar'
        with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
            for file in sorted(classes.rglob('*.class')):
                archive.write(file, file.relative_to(classes).as_posix())
        mutations = work / 'mutations.json'
        pending = work / 'module.apk'
        subprocess.run(['java', '-Xmx6g', '-Dinhouse.backgroundCheckin=true',
            '-Dinhouse.backgroundCheckin.report=' + str(mutations), '-jar', str(args.morphe),
            'patch', '--patches', str(jar), '--bytecode-mode', 'FULL', '--unsigned',
            '--out', str(pending), '--temporary-files-path', str(work / 'patch-work'),
            str(args.backend)], check=True)
        with zipfile.ZipFile(args.backend) as before, zipfile.ZipFile(pending) as after:
            resources = [name for name in before.namelist() if name == 'resources.arsc' or name.startswith('res/')]
            for name in resources:
                if before.read(name) != after.read(name):
                    raise RuntimeError('Unexpected resource change: ' + name)
        if digest(source) != source_hash or digest(__file__) != script_hash:
            raise RuntimeError('Proposal sources changed during build')
        report = {'schemaVersion': 1, 'proposalOnly': True, 'hostPipelineModified': False,
                  'baseBackendSha256': digest(args.backend), 'outputSha256': digest(pending),
                  'upstreamSourceCommit': lock['sourceCommit'], 'patchSourceSha256': source_hash,
                  'preparerSourceSha256': script_hash, 'morpheSha256': digest(args.morphe),
                  'mutations': json.loads(mutations.read_text()),
                  'resourcesPreserved': len(resources), 'runtimeTested': False,
                  'authenticatedLoginTested': False, 'pushAfterBootTested': False}
        pending.replace(args.output)
        report_path = args.output.with_suffix('.report.json')
        report_path.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'output': str(args.output), 'sha256': report['outputSha256'],
                          'report': str(report_path)}, indent=2))


if __name__ == '__main__':
    main()
