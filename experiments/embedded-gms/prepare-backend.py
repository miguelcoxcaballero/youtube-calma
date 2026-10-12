#!/usr/bin/env python3
"""Adapt the pinned microG as an unsigned, uninstalled same-APK code/resource asset.

This does not install microG, sign/publish a release, bypass UID/signature checks,
or assert authenticated login/playback/push. Host runtime and manifest must provide
all bridge methods and renamed components recorded in the output report.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
HOST = 'app.revanced.android.youtube'
ORIGINAL = 'app.revanced.android.gms'
ACCOUNT = 'es.inhouse.youtube.google'
COMPONENT_CLASS_MAPPING = {name: 'inhouse.microg.module.' + name for name in (
    'androidx.room.MultiInstanceInvalidationService',
    'androidx.work.impl.background.systemalarm.RescheduleReceiver',
    'androidx.work.impl.background.systemjob.SystemJobService',
    'androidx.work.impl.diagnostics.DiagnosticsReceiver',
    'androidx.work.impl.foreground.SystemForegroundService',
    'androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver',
    'com.google.android.gms.common.api.GoogleApiActivity',
)}


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(*args):
    subprocess.run([str(x) for x in args], check=True)


def load_audit():
    spec = importlib.util.spec_from_file_location('embedded_boundary_audit', ROOT / 'scripts/audit-embedded-gms.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def provider_target(authority):
    if authority == 'app.revanced.android.gsf.gservices':
        return HOST + '.inhouse.gms.gservices'
    if authority.startswith(ORIGINAL + '.microg.'):
        return HOST + authority[len(ORIGINAL):]
    if authority.startswith(ORIGINAL + '.'):
        return HOST + '.inhouse.gms' + authority[len(ORIGINAL):]
    if authority in ('app.revanced.settings', 'app.revanced.subscribedfeeds'):
        return HOST + '.inhouse.gms.' + authority.rsplit('.', 1)[-1]
    raise ValueError('Unreviewed upstream provider authority: ' + authority)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--morphe', type=Path, default=ROOT / 'tools/morphe.jar')
    parser.add_argument('--aapt2', type=Path, default=ROOT / 'tools/android-15/aapt2')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    source, output = args.input.resolve(), args.output.resolve()
    if source == output:
        raise SystemExit('Input and output must differ')
    upstream = json.loads((HERE / 'upstream.lock.json').read_text())
    tool = json.loads((ROOT / 'tools.lock.json').read_text())['morphe']
    if digest(source) != upstream['apkSha256']:
        raise SystemExit('Refusing unknown backend APK hash')
    if digest(args.morphe) != tool['sha256']:
        raise SystemExit('Refusing unknown Morphe tool hash')
    audit = load_audit()
    original_info, original_dex = audit.inspect_apk(source, args.aapt2, ORIGINAL, 'app.revanced')
    if original_info['manifest']['versionCode'] != 250932004:
        raise SystemExit('Unexpected backend versionCode')
    components = original_info['manifest']['components']
    hooks, mapping = [], {}
    for item in components:
        kind = item['kind']
        if kind not in {'activity', 'service', 'provider', 'receiver'}:
            continue
        name = item['name']
        if name.startswith('.'):
            name = ORIGINAL + name
        elif '.' not in name:
            name = ORIGINAL + '.' + name
        hooks.append(('L' + name.replace('.', '/') + ';', kind))
        if kind == 'provider':
            for authority in item['authorities'].split(';'):
                mapping[authority] = provider_target(authority)
    if len(hooks) != 191 or len(mapping) != 13:
        raise SystemExit('Unreviewed upstream manifest component count')
    report_path = (args.report or output.with_suffix('.report.json')).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    output.unlink(missing_ok=True)
    report_path.unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='inhouse-backend-', dir=output.parent) as tmp:
        work = Path(tmp)
        generated = work / 'BackendPatchConfig.kt'
        q = lambda s: json.dumps(s).replace('$', '\\$')
        generated.write_text('package inhouse.microg.patches\n' +
            'internal val componentHooks = listOf(\n' + ',\n'.join('    ' + q(t) + ' to ' + q(k) for t, k in hooks) + '\n)\n' +
            'internal val endpointMappings = mapOf(\n' + ',\n'.join('    ' + q(k) + ' to ' + q(v) for k, v in sorted(mapping.items())) + '\n)\n')
        classes = work / 'classes'; classes.mkdir()
        patch = HERE / 'patches/BackendPatch.kt'
        patch_sha = digest(patch)
        script_sha = digest(Path(__file__))
        run('java', '-cp', str(ROOT / 'tools/kotlin/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
            '-Xskip-metadata-version-check', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
            '-classpath', args.morphe, '-d', classes, patch, generated)
        jar = work / 'backend-patches.jar'
        with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as z:
            for path in sorted(classes.rglob('*')):
                if path.is_file(): z.write(path, path.relative_to(classes).as_posix())
        mutations = work / 'mutations.json'
        pending = work / 'adapted-unsigned.apk'
        run('java', '-Xmx7g', '-Dinhouse.backend.report=' + str(mutations), '-jar', args.morphe,
            'patch', '--patches', jar, '--bytecode-mode', 'FULL', '--unsigned', '--out', pending,
            '--temporary-files-path', work / 'patch-work', source)
        if not mutations.is_file() or not pending.is_file():
            raise RuntimeError('Backend patch did not produce its required outputs')
        relocated = work / 'adapted-relocated.apk'
        run('java', '-Xmx7g', '-cp', str(jar) + ':' + str(args.morphe),
            'inhouse.microg.patches.BackendPatchKt', pending, relocated)
        pending = relocated
        rewritten = audit.apk_dex(pending)
        if ORIGINAL in rewritten['strings'] or 'app.revanced' in rewritten['strings']:
            raise RuntimeError('External package/account type remains in emitted backend DEX')
        before_binders = set(original_info['literalReferences']['binderDescriptorLiterals'])
        missing_binders = before_binders - rewritten['strings']
        if missing_binders:
            raise RuntimeError('Binder descriptors changed: ' + ', '.join(sorted(missing_binders)))
        type_mapping = {'L' + k.replace('.', '/') + ';': 'L' + v.replace('.', '/') + ';'
                        for k, v in COMPONENT_CLASS_MAPPING.items()}
        expected_classes = {type_mapping.get(name, name) for name in original_dex['classes']}
        if rewritten['classes'] != expected_classes or rewritten['duplicateDefinitions']:
            raise RuntimeError('Backend class identity changed or duplicate DEX classes emitted')
        with zipfile.ZipFile(source) as original_zip, zipfile.ZipFile(pending) as modified_zip:
            # Resource package/IDs and all non-DEX module bytes must retain their original meaning.
            resource_names = [n for n in original_zip.namelist() if n == 'resources.arsc' or n.startswith('res/')]
            for name in resource_names:
                if original_zip.read(name) != modified_zip.read(name):
                    raise RuntimeError('Unexpected resource modification: ' + name)
        changes = json.loads(mutations.read_text())
        required_methods = {
            'wrap': '(Landroid/content/Context;)Landroid/content/Context;',
            'prepareActivity': '(Landroid/app/Activity;)V',
            'startForeground': '(Landroid/app/Service;ILandroid/app/Notification;)V',
            'scheduleAlarm': '(Landroid/app/AlarmManager;IJLandroid/app/PendingIntent;)V',
            'notify(int)': '(Landroid/app/NotificationManager;ILandroid/app/Notification;)V',
            'notify(tag,int)': '(Landroid/app/NotificationManager;Ljava/lang/String;ILandroid/app/Notification;)V',
        }
        if digest(patch) != patch_sha or digest(Path(__file__)) != script_sha:
            raise RuntimeError('Build source changed during compilation; rebuild to preserve provenance')
        report = {
            'schemaVersion': 1, 'purpose': 'unsigned uninstalled embedded backend asset',
            'inputSha256': digest(source), 'outputSha256': digest(pending),
            'sourceCommit': upstream['sourceCommit'], 'morpheSha256': digest(args.morphe),
            'patchSourceSha256': patch_sha, 'preparerSourceSha256': script_sha,
            'generatedConfigSha256': digest(generated), 'patchBundleSha256': digest(jar),
            'hostPackage': HOST, 'accountType': ACCOUNT, 'endpointMappings': mapping,
            'componentClassMapping': COMPONENT_CLASS_MAPPING,
            'bridgeClass': 'inhouse.microg.bridge.BackendEnvironment', 'requiredBridgeMethods': required_methods,
            'componentHooks': [{'class': t[1:-1].replace('/', '.'), 'kind': k} for t, k in hooks],
            'mutations': changes, 'binderDescriptorsPreserved': len(before_binders),
            'originalResourceFilesPreserved': len(resource_names),
            'classCount': len(rewritten['classes']), 'separatePackageInstalled': False,
            'hostTargetSdkUnchanged': 35, 'backendArchiveTargetSdk': upstream['apkTargetSdk'],
            'authenticatedLoginTested': False, 'youtubePlaybackTested': False, 'pushDeliveryTested': False,
            'runtimeRequirements': [
                'Host manifest must use mapped authorities and signature permissions, accountType and backend process.',
                'Host manifest must provide original theme IDs in per-activity inhouse.gms.theme metadata.',
                'Host must grant FGS/dataSync/specialUse permissions, declare types, and use host-resolvable notification icons.',
                'Background/boot-start restrictions, dataSync timeout and reconnect lifecycle need device validation.',
                'Archive is not separately installed; its targetSdk29 does not lower the installed host targetSdk35.',
            ],
        }
        pending.replace(output)
        report_path.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'output': str(output), 'report': str(report_path), 'sha256': report['outputSha256'],
                          'hooks': len(changes['contextAndThemeHooks']), 'redactions': len(changes['redactedLogCalls'])}, indent=2))


if __name__ == '__main__':
    main()
