package inhouse.microg.bridge;

import android.content.Context;
import android.content.res.Resources;

/** Loaded in the same isolated ClassLoader as microG; no host class dependencies. */
public final class BackendEnvironment {
    static Resources resources;
    static ClassLoader classLoader;

    public static void configure(Resources value, ClassLoader code) {
        if (value == null || code == null) throw new NullPointerException("Backend environment");
        resources = value;
        classLoader = code;
    }

    public static Context wrap(Context context) {
        if (context instanceof BackendContext) return context;
        if (resources == null) throw new IllegalStateException("Backend environment not configured");
        return new BackendContext(context);
    }
}
