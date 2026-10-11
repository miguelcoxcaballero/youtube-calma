"""Exercise the released 0.1.0 APK's checker with the current public release."""
import json,zipfile
from pathlib import Path
from playwright.sync_api import sync_playwright
R=Path(__file__).resolve().parents[1]
release=json.loads((R/'build/public-release.json').read_text())
current=json.loads((R/'android-update.json').read_text())
with zipfile.ZipFile(R/'build/dist/YouTube-Calma-0.1.0.apk') as z:
    assets={n.rsplit('/',1)[1]:z.read(n) for n in z.namelist() if n.startswith('assets/updates/')}
with sync_playwright() as p:
    browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
    for quiet in [False,True]:
        for offline in [False,True]:
            context=browser.new_context()
            context.add_init_script('window.InhouseNative={getAppVersion(){return "0.1.0"}};window.InhouseUpdateHost={offer(m){window.pending=JSON.parse(m)}};')
            def route(r):
                if 'android-update.json' in r.request.url:
                    if offline:r.fulfill(status=503,body='{}')
                    else:r.fulfill(content_type='application/json',headers={'Access-Control-Allow-Origin':'*'},body=json.dumps({**current,'version':'0.1.0'}))
                elif 'api.github.com' in r.request.url:r.fulfill(content_type='application/json',headers={'Access-Control-Allow-Origin':'*'},body=json.dumps(release))
                else:
                    name=r.request.url.split('/updates/')[-1].split('?')[0]
                    r.fulfill(content_type='application/javascript' if name.endswith('.js') else 'text/css' if name.endswith('.css') else 'text/html',body=assets[name])
            context.route('**/*',route);page=context.new_page()
            page.goto('https://appassets.androidplatform.net/updates/index.html?inhouse_app=1'+('&quiet=1' if quiet else ''))
            if quiet:
                page.wait_for_function('() => Boolean(window.pending)',timeout=5000);found=page.evaluate('window.pending')
                assert found['version']==current['version'] and found['apkSha256']==current['apkSha256'] and found['apkUrl']==current['apkUrl']
            else:
                page.wait_for_selector('#android-update-gate',timeout=5000)
                assert current['version'] in page.locator('[data-update-message]').inner_text()
            context.close()
    browser.close()
print('PASS: released 0.1.0 finds the published '+current['version']+' automatically and manually with a stale or unavailable raw manifest (4 cases)')
