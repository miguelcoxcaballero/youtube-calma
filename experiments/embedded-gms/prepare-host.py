#!/usr/bin/env python3
"""Generate reviewed host registrations for the opt-in, same-APK backend.

This does not change the release catalog. Module resources keep their private IDs;
only Android-parsed descriptors are recreated in the installed host resources.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
HOST = 'app.revanced.android.youtube'
ORIGINAL = 'app.revanced.android.gms'
NS = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', NS)
def attr(key): return '{' + NS + '}' + key
def add(parent, tag, **attrs): return ET.SubElement(parent, tag, {attr(k): str(v) for k, v in attrs.items()})
def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()

# Providers are instantiated together before Application.onCreate. Register only
# those needed for account/token, settings and work scheduling; never activate
# location, exposure notifications, contact sync or a second work authenticator.
PROVIDERS = {
    'org.microg.gms.gservices.GServicesProvider',
    'org.microg.gms.settings.GoogleSettingsProvider',
    'org.microg.gms.settings.GmsFileProvider',
    'org.microg.gms.auth.AccountContentProvider',
    'org.microg.gms.settings.SettingsProvider',
    'androidx.startup.InitializationProvider',
}
OMITTED = {
    'org.microg.gms.people.ContactSyncService',
    'com.google.android.gms.auth.account.authenticator.WorkAccountAuthenticatorService',
}
NORMAL_PERMISSIONS = (
    'INTERNET', 'ACCESS_NETWORK_STATE', 'WAKE_LOCK', 'RECEIVE_BOOT_COMPLETED',
    'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_DATA_SYNC', 'FOREGROUND_SERVICE_SPECIAL_USE',
    'POST_NOTIFICATIONS', 'AUTHENTICATE_ACCOUNTS', 'MANAGE_ACCOUNTS', 'USE_CREDENTIALS',
    'GET_ACCOUNTS', 'READ_SYNC_SETTINGS', 'WRITE_SYNC_SETTINGS', 'READ_SYNC_STATS',
)

def permission(value):
    for before, after in (
        (ORIGINAL + '.permission.', HOST + '.inhouse.gms.permission.'),
        ('app.revanced.android.c2dm.permission.', HOST + '.inhouse.gms.c2dm.permission.'),
        ('app.revanced.android.providers.gsf.permission.', HOST + '.inhouse.gms.gsf.permission.'),
    ):
        if value.startswith(before): return after + value[len(before):]
    return value

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--backend-apk', type=Path, required=True)
    p.add_argument('--backend-report', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--aapt2', type=Path, default=ROOT / 'tools/android-15/aapt2')
    p.add_argument('--host-apk', type=Path, default=ROOT / 'base/youtube-original.apk')
    p.add_argument('--instrumentation', action='store_true')
    args = p.parse_args()
    report = json.loads(args.backend_report.read_text())
    assert digest(args.backend_apk) == report['outputSha256'], 'Backend report mismatch'
    assert digest(args.host_apk) == json.loads((ROOT / 'base.lock.json').read_text())['sha256']
    assert report['hostPackage'] == HOST and report['accountType'] == 'es.inhouse.youtube.google'
    spec = importlib.util.spec_from_file_location('audit', ROOT / 'scripts/audit-embedded-gms.py')
    audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)
    def manifest(apk):
        raw = subprocess.check_output([str(args.aapt2), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
        return next(x for x in audit.parse_xmltree(raw)['children'] if x['name'] == 'manifest')
    original = manifest(args.backend_apk)
    original_app = next(x for x in original['children'] if x['name'] == 'application')
    host_app = next(x for x in manifest(args.host_apk)['children'] if x['name'] == 'application')
    host_names = {x['attributes'].get('name') for x in host_app['children'] if x['name'] in ('activity', 'service', 'provider', 'receiver')}
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    xml_dir = output / 'res/xml'; xml_dir.mkdir(parents=True, exist_ok=True)
    fragment = ET.Element('manifest')
    for name in NORMAL_PERMISSIONS: add(fragment, 'uses-permission', name='android.permission.' + name)
    # These endpoints belong only to this signed app. Same-UID access and Android
    # account/job binding remain authenticated; no global signature spoofing.
    for suffix in ('gsf.permission.READ_GSERVICES', 'c2dm.permission.SEND', 'c2dm.permission.RECEIVE',
                   'permission.READ_SETTINGS', 'permission.WRITE_SETTINGS'):
        name = HOST + '.inhouse.gms.' + suffix
        add(fragment, 'permission', name=name, protectionLevel='signature')
        add(fragment, 'uses-permission', name=name)
    app = add(fragment, 'application', appComponentFactory='inhouse.microg.host.YouTubeComponentFactory')
    for name in ('checkin_enable_service', 'gcm_enable_mcs_service', 'droidguard_enabled', 'safetynet_enabled'):
        add(app, 'meta-data', name='org.microg.gms.settings.' + name, value='true')
    add(app, 'meta-data', name='firebase_messaging_notification_delegation_enabled', value='false')
    # Preserve upstream authentication/challenge defaults. Location/contact
    # providers and unrelated account types remain outside this integration.
    components, skipped = [], []
    class_mapping = report['componentClassMapping']
    endpoint_mapping = report['endpointMappings']
    def copy(node):
        element = ET.Element(node['name'])
        for key, value in node['attributes'].items():
            if value.startswith('@0x7f'):
                raise ValueError('Untranslated module resource in manifest: ' + str(node))
            element.set(attr(key), permission(value))
        for child in node['children']: element.append(copy(child))
        return element
    for item in original_app['children']:
        kind, attrs = item['name'], dict(item['attributes'])
        if kind not in ('activity', 'service', 'provider', 'receiver'): continue
        name = attrs['name']
        if name in OMITTED or (kind == 'provider' and name not in PROVIDERS):
            skipped.append(name); continue
        mapped = class_mapping.get(name, name)
        assert mapped not in host_names and mapped not in components, 'Component collision: ' + mapped
        components.append(mapped)
        theme = attrs.pop('theme', original_app['attributes']['theme'])
        for key in ('label', 'icon', 'roundIcon', 'description', 'logo'): attrs.pop(key, None)
        attrs.update(name=mapped, process=':inhouse_gms', exported='false')
        # Avoid sharing a task or global class alias with standalone microG.
        if 'taskAffinity' in attrs: attrs['taskAffinity'] = HOST + '.inhouse.gms'
        if attrs.get('enabled', '').startswith('@'):
            attrs['enabled'] = str('systemalarm' not in name).lower()
        if kind == 'activity': attrs['theme'] = '@android:style/Theme.Material.Light.NoActionBar'
        if kind == 'provider':
            attrs['authorities'] = ';'.join(endpoint_mapping[a] for a in attrs['authorities'].split(';'))
        # Only Android itself must enter these services from another UID.
        if name == 'org.microg.gms.auth.loginservice.GoogleLoginService':
            attrs.update(exported='true', permission='android.permission.BIND_ACCOUNT_AUTHENTICATOR')
        if name == 'androidx.work.impl.background.systemjob.SystemJobService':
            attrs.update(exported='true', permission='android.permission.BIND_JOB_SERVICE')
        if name == 'org.microg.gms.checkin.CheckinService': attrs['foregroundServiceType'] = 'dataSync'
        if name == 'org.microg.gms.gcm.McsService': attrs['foregroundServiceType'] = 'specialUse'
        children = []
        for child in item['children']:
            child = json.loads(json.dumps(child))
            ca = child['attributes']
            if child['name'] == 'intent-filter':
                # Module activities are opened explicitly; no extra launcher icons.
                if any(c['attributes'].get('name') == 'android.intent.category.LAUNCHER' for c in child['children']): continue
            if child['name'] == 'meta-data':
                if ca.get('name') == 'android.accounts.AccountAuthenticator': ca['resource'] = '@xml/inhouse_gms_authenticator'
                elif ca.get('name') == 'android.support.FILE_PROVIDER_PATHS': ca['resource'] = '@xml/inhouse_gms_file_paths'
                elif ca.get('name') == 'androidx.profileinstaller.ProfileInstallerInitializer': continue
            children.append(child)
        element = copy({'name': kind, 'attributes': attrs, 'children': children})
        if kind == 'activity': add(element, 'meta-data', name='inhouse.gms.theme', value=theme.removeprefix('@'))
        if name == 'org.microg.gms.gcm.McsService':
            add(element, 'property', name='android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE', value='Google account push-message delivery for YouTube')
        app.append(element)
    if args.instrumentation:
        add(fragment, 'instrumentation', name='inhouse.microg.host.YouTubeSmokeInstrumentation', targetPackage=HOST)
    authenticator = ET.Element('account-authenticator', {
        attr('accountType'): report['accountType'], attr('customTokens'): 'true',
        attr('icon'): '@drawable/inhouse_settings_icon', attr('smallIcon'): '@drawable/inhouse_settings_icon',
        attr('label'): '@string/inhouse_gms_account_label',
    })
    ET.ElementTree(authenticator).write(xml_dir / 'inhouse_gms_authenticator.xml', encoding='utf-8', xml_declaration=True)
    values_dir = output / 'res/values'; values_dir.mkdir(parents=True, exist_ok=True)
    values = ET.Element('resources')
    ET.SubElement(values, 'string', {'name': 'inhouse_gms_account_label', 'translatable': 'false'}).text = 'YouTube'
    ET.ElementTree(values).write(values_dir / 'inhouse_gms_strings.xml', encoding='utf-8', xml_declaration=True)
    # Restrict grants to the module's namespaced files, never the host's updater,
    # account databases, or unrelated cache. The provider itself is not exported.
    paths = ET.Element('paths')
    # GmsFileProvider receives BackendContext, whose directories are already scoped.
    ET.SubElement(paths, 'cache-path', {'name': 'backend-cache', 'path': '.'})
    ET.SubElement(paths, 'files-path', {'name': 'backend-files', 'path': '.'})
    ET.ElementTree(paths).write(xml_dir / 'inhouse_gms_file_paths.xml', encoding='utf-8', xml_declaration=True)
    ET.ElementTree(fragment).write(output / 'manifest-fragment.xml', encoding='utf-8', xml_declaration=True)
    (output / 'components.json').write_text(json.dumps(components, indent=2) + '\n')
    (output / 'host-registration-report.json').write_text(json.dumps({
        'componentCount': len(components), 'omittedComponents': skipped,
        'allComponentsPrivateExceptAndroidBoundServices': True, 'instrumentation': args.instrumentation,
        'backendSha256': report['outputSha256'], 'authenticatedLoginTested': False,
    }, indent=2) + '\n')
    print('Prepared', len(components), 'embedded components;', len(skipped), 'unneeded components omitted')

if __name__ == '__main__': main()
