package inhouse.microg.bridge;

import android.content.Context;
import android.os.Bundle;
import android.webkit.WebView;

/** Optional online login-page probe, not an authenticated-login or production integration proof. */
public final class LoginActivityBridge extends org.microg.gms.auth.login.LoginActivity {
    private static boolean webViewConfigured;

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
    }
}
