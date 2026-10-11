"""Exercise the original Inhouse Read update UI, with native installer callbacks mocked."""
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

root=Path(__file__).resolve().parents[1]
assets=root/'app/assets/updates'
release=None;failure=False;requests=[]
manifest={'version':'0.3.3','versionCode':7,'required':True,'apkUrl':'https://raw.githubusercontent.com/miguelcoxcaballero/youtube-calma/main/downloads/YouTube-Calma-0.3.3.apk','apkSha256':'a'*64,'apkSizeBytes':500000}
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    context=browser.new_context()
    context.add_init_script('window.installs=[];window.offers=0;window.InhouseNative={getAppVersion(){return "0.3.2"},installAppUpdate(url,sha){window.installs.push({url,sha});window.handleInhouseUpdateResult({status:"permission_required"})}};window.InhouseUpdateHost={offer(){window.offers++}}')
    def route(r):
        requests.append(r.request.url)
        if 'android-update.json' in r.request.url:
            if failure:r.fulfill(status=503,body='{}');return
            r.fulfill(content_type='application/json',headers={'Access-Control-Allow-Origin':'*'},body=json.dumps(manifest));return
        if 'api.github.com' in r.request.url:
            if release:r.fulfill(content_type='application/json',headers={'Access-Control-Allow-Origin':'*'},body=json.dumps(release))
            else:r.fulfill(status=404,body='{}')
            return
        name=r.request.url.split('/updates/')[-1].split('?')[0]
        path=assets/name
        r.fulfill(content_type='application/javascript' if name.endswith('.js') else 'text/css' if name.endswith('.css') else 'text/html',body=path.read_text())
    context.route('**/*',route)
    page=context.new_page();page.goto('https://appassets.androidplatform.net/updates/index.html?inhouse_app=1')
    page.wait_for_selector('#android-update-gate')
    assert 'Versión 0.3.2' in page.locator('[data-update-message]').inner_text()
    page.locator('[data-update-install]').click()
    assert page.evaluate('window.installs[0]')=={'url':manifest['apkUrl'],'sha':manifest['apkSha256']}
    assert 'Permitir desde esta fuente' in page.locator('[data-update-status]').inner_text()
    assert page.locator('[data-update-install]').is_enabled()
    page.evaluate('window.handleInhouseUpdateResult({status:"downloading",downloadedBytes:250000,totalBytes:500000})')
    assert page.locator('[data-update-percent]').inner_text()=='50%'
    assert page.locator('[data-update-install]').is_disabled()
    assert page.locator('[data-update-progressbar]').get_attribute('aria-valuenow')=='50'
    page.evaluate('window.handleInhouseUpdateResult({status:"ready",downloadedBytes:500000,totalBytes:500000})')
    assert 'Confirma la instalación' in page.locator('[data-update-status]').inner_text()
    page.evaluate('window.handleInhouseUpdateResult({status:"error",message:"Checksum inválido"})')
    assert page.locator('[data-update-install]').inner_text()=='Reintentar'
    assert 'Checksum inválido' in page.locator('[data-update-status]').inner_text()
    assert page.evaluate('InhouseUpdater.compareSemanticVersions("0.10.0","0.9.9")')>0
    assert page.evaluate('(()=>{try{InhouseUpdater.validateManifest({version:"1.0.0",apkUrl:"https://evil.example/app.apk"});return false}catch(e){return true}})()')
    context.add_init_script('window.requestAnimationFrame=()=>0;window.requestIdleCallback=()=>0')
    page.goto('https://appassets.androidplatform.net/updates/index.html?inhouse_app=1&quiet=1')
    # The fixture deliberately disables frames. Playwright's default RAF polling
    # would then evaluate only once and hang whenever a mocked fetch finishes later.
    page.wait_for_function('() => window.offers>0',timeout=2000,polling=50)
    manifest['version']='0.3.2'
    page.goto('https://appassets.androidplatform.net/updates/index.html?inhouse_app=1')
    page.wait_for_function('() => document.querySelector("#check-status").textContent.includes("última versión")',polling=50)
    assert page.locator('#android-update-gate').count()==0
    # Foregrounding checks again without waiting for YouTube or a fifteen-minute timer.
    manifest['version']='0.3.4'
    page.evaluate('window.dispatchEvent(new Event("focus"))')
    page.wait_for_selector('#android-update-gate')
    assert '0.3.4' in page.locator('[data-update-message]').inner_text()
    # The original Read Release recovery finds a newer APK despite a stale raw manifest.
    manifest['version']='0.3.2'
    release={'tag_name':'v0.3.4','assets':[{'name':'YouTube-Calma-0.3.4.apk','browser_download_url':'https://github.com/miguelcoxcaballero/youtube-calma/releases/download/v0.3.4/YouTube-Calma-0.3.4.apk','digest':'sha256:'+'b'*64,'size':500000}]}
    page.goto('https://appassets.androidplatform.net/updates/index.html?inhouse_app=1')
    page.wait_for_selector('#android-update-gate')
    assert '0.3.4' in page.locator('[data-update-message]').inner_text()
    release=None;failure=True
    page.goto('https://appassets.androidplatform.net/updates/index.html?inhouse_app=1')
    page.wait_for_function('() => document.querySelector("#check-status").textContent.includes("No se pudo comprobar")',polling=50)
    assert page.locator('#android-update-gate').count()==0
    # Popup receives the already checked manifest; it must never query GitHub a second time.
    popup_context=browser.new_context()
    popup_context.add_init_script('window.InhouseNative={getAppVersion(){return "0.3.2"},getPendingUpdateManifest(){return '+json.dumps(json.dumps({**manifest,'version':'0.3.4'}))+'}}')
    popup_requests=[]
    def popup_route(r):
        if 'appassets.androidplatform.net' not in r.request.url:popup_requests.append(r.request.url);r.abort();return
        name=r.request.url.split('/updates/')[-1].split('?')[0]
        r.fulfill(content_type='application/javascript' if name.endswith('.js') else 'text/css' if name.endswith('.css') else 'text/html',body=(assets/name).read_text())
    popup_context.route('**/*',popup_route)
    popup=popup_context.new_page();popup.goto('https://appassets.androidplatform.net/updates/index.html?inhouse_app=1')
    popup.wait_for_selector('#android-update-gate',timeout=2000)
    assert popup.locator('main').is_hidden() and popup_requests==[]
    assert '0.3.4' in popup.locator('[data-update-message]').inner_text()

    assert (assets/'android-update.css').read_bytes()==(root/'vendor/inhouse-read/android-update.css').read_bytes()
    print('PASS: original Read popup/progress, immediate startup with frame/idle suppressed, focus checks, GitHub Release recovery, offline error, and prechecked-manifest popup without another network request; original CSS unchanged.')
    browser.close()
