package inhouse.microg.bridge;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/** Each wrapped context owns its Resources/Theme facade over the isolated archive assets. */
final class BackendResources extends Resources {
    private final String hostPackage;

    BackendResources(Resources backend, DisplayMetrics metrics,
            Configuration configuration, String hostPackage) {
        super(backend.getAssets(), metrics, new Configuration(configuration));
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
        return id != 0 ? id : super.getIdentifier(adapted, defType, "com.google.android.gms");
    }
}
