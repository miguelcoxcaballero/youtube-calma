package inhouse.microg.host;

import android.app.Application;
import android.content.Context;
import android.webkit.WebView;

/** Only the :inhouse_gms process uses this application; YouTube keeps its original one. */
public final class BackendApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        try {
            // Android permits one WebView data-directory owner per process. The
            // native YouTube process retains its existing directory; keep this
            // stable suffix for every future embedded sign-in session/upgrade.
            // Run before loading module providers, initializers or login activities.
            WebView.setDataDirectorySuffix("inhouse_gms");
            EmbeddedRuntime runtime = EmbeddedRuntime.open(base);
            runtime.setApplication(this);
            super.attachBaseContext(runtime.wrap(base));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize built-in Google services", failure);
        }
    }
}
