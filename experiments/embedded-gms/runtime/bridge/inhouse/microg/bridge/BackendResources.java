package inhouse.microg.bridge;

import android.content.res.Configuration;
import android.content.res.Resources;

/** Per-context resource facade and name adaptation, without changing installed identity. */
public final class BackendResources extends Resources {
    private final String hostPackage;

    BackendResources(Resources backend, Configuration configuration, String hostPackage) {
        // No addLoaders() on this facade: its AssetManager already belongs only to the
        // managed backend Resources object. The facade owns theme caches, but AssetManager
        // configuration remains shared: this single-window probe is not production isolation.
        super(backend.getAssets(), backend.getDisplayMetrics(), new Configuration(configuration));
        this.hostPackage = hostPackage;
    }

    @Override public int getIdentifier(String name, String defType, String defPackage) {
        int id = super.getIdentifier(name, defType, defPackage);
        if (id != 0 || name == null) return id;
        String adapted = name;
        boolean hostReference = hostPackage.equals(defPackage);
        String prefix = hostPackage + ":";
        if (name.startsWith(prefix)) {
            adapted = name.substring(prefix.length());
            hostReference = true;
        }
        if (!hostReference) return 0;
        id = super.getIdentifier(adapted, defType, "app.revanced.android.gms");
        if (id == 0) id = super.getIdentifier(adapted, defType, "com.google.android.gms");
        return id;
    }
}
