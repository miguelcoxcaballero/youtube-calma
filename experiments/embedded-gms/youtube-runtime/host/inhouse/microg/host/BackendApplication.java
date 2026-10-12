package inhouse.microg.host;

import android.app.Application;
import android.content.Context;

/** Only the :inhouse_gms process uses this application; YouTube keeps its original one. */
public final class BackendApplication extends Application {
    @Override protected void attachBaseContext(Context base) {
        try {
            EmbeddedRuntime runtime = EmbeddedRuntime.open(base);
            runtime.setApplication(this);
            super.attachBaseContext(runtime.wrap(base));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize built-in Google services", failure);
        }
    }
}
