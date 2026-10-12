package inhouse.microg.bridge;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Optional online login-page probe, not an authenticated-login or production integration proof. */
public final class LoginActivityBridge extends org.microg.gms.auth.login.LoginActivity {
    private static boolean webViewConfigured;
    private final Handler diagnostics = new Handler(Looper.getMainLooper());
    private int diagnosticTicks;
    private static final String DOM_STRUCTURE = "(function(){return JSON.stringify({"
        + "ready:['loading','interactive','complete'].indexOf(document.readyState),"
        + "emailInputs:document.querySelectorAll('input[type=email],input[name=identifier],input[id=identifierId]').length,"
        + "visibleEmailInputs:Array.prototype.filter.call(document.querySelectorAll('input[type=email],input[name=identifier],input[id=identifierId]'),function(e){return e.getClientRects().length>0;}).length,"
        + "passwordInputs:document.querySelectorAll('input[type=password]').length,"
        + "nonHiddenInputs:document.querySelectorAll('input:not([type=hidden])').length,"
        + "captchaInputs:document.querySelectorAll('input[name=ca],input[name=captcha]').length,"
        + "visibleCaptchaInputs:Array.prototype.filter.call(document.querySelectorAll('input[name=ca],input[name=captcha]'),function(e){return e.getClientRects().length>0;}).length,"
        + "recaptchaFrames:document.querySelectorAll('iframe[src*=recaptcha]').length"
        + "});})()";
    private final Runnable inspect = new Runnable() {
        @Override public void run() {
            try {
                java.lang.reflect.Field field = org.microg.gms.auth.login.LoginActivity.class.getDeclaredField("webView");
                field.setAccessible(true);
                WebView web = (WebView) field.get(LoginActivityBridge.this);
                final JSONObject structure = new JSONObject()
                    .put("progress", web.getProgress()).put("visible", web.getVisibility() == android.view.View.VISIBLE)
                    .put("route", route(web.getUrl()));
                // Probe only: inspect DOM shape, never text, input values, cookies,
                // accounts, request bodies, or full URLs. No credentials are entered.
                web.evaluateJavascript(DOM_STRUCTURE, value -> {
                    try {
                        Object decoded = new JSONTokener(value).nextValue();
                        JSONObject dom = new JSONObject((String) decoded);
                        for (String key : new String[]{"ready", "emailInputs", "visibleEmailInputs", "passwordInputs", "nonHiddenInputs", "captchaInputs", "visibleCaptchaInputs", "recaptchaFrames"}) {
                            structure.put(key, dom.getInt(key));
                        }
                        android.util.Log.i("EmbeddedGmsLoginProbe", structure.toString());
                    } catch (Exception ignored) {
                        android.util.Log.i("EmbeddedGmsLoginProbe", "{\"diagnosticFailure\":true}");
                    }
                });
            } catch (Exception ignored) {
                android.util.Log.i("EmbeddedGmsLoginProbe", "{\"diagnosticFailure\":true}");
            }
            if (++diagnosticTicks < 24) diagnostics.postDelayed(this, 5000);
        }
    };

    private static String route(String raw) {
        if (raw == null || raw.isEmpty()) return "empty";
        android.net.Uri uri = android.net.Uri.parse(raw);
        if (!"accounts.google.com".equals(uri.getHost())) return "other";
        String path = uri.getPath();
        if (path == null) return "google_other";
        if (path.endsWith("/identifier") || "identifier".equals(uri.getFragment())) return "google_identifier";
        if (path.contains("/challenge/")) return "google_challenge";
        if (path.equals("/EmbeddedSetup")) return "google_setup";
        return "google_other";
    }

    @Override protected void attachBaseContext(Context context) {
        synchronized (LoginActivityBridge.class) {
            if (!webViewConfigured) {
                // Must happen before any WebView use in the isolated backend process.
                WebView.setDataDirectorySuffix("inhouse_gms_probe");
                webViewConfigured = true;
            }
        }
        super.attachBaseContext(BackendEnvironment.wrap(context));
    }

    @Override protected void onCreate(Bundle state) {
        int theme = getResources().getIdentifier("Theme.LoginBlue", "style", "com.google.android.gms");
        if (theme == 0) theme = getResources().getIdentifier("Theme.LoginBlue", "style", "app.revanced.android.gms");
        if (theme == 0) throw new IllegalStateException("Backend login theme not found");
        setTheme(theme);
        super.onCreate(state);
        diagnostics.postDelayed(inspect, 1000);
    }

    @Override protected void onDestroy() {
        diagnostics.removeCallbacks(inspect);
        super.onDestroy();
    }
}
