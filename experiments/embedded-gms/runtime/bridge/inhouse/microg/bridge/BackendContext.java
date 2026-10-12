package inhouse.microg.bridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.view.LayoutInflater;

/** Keeps real installed package/UID while supplying the backend's code and resource table. */
public final class BackendContext extends ContextWrapper {
    private final Resources resources;
    private Resources.Theme theme;
    private LayoutInflater inflater;

    BackendContext(Context base) {
        super(base);
        resources = new BackendResources(BackendEnvironment.resources,
                base.getResources().getConfiguration(), base.getPackageName());
    }

    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return getResources().getAssets(); }
    @Override public ClassLoader getClassLoader() { return BackendEnvironment.classLoader; }

    @Override public Resources.Theme getTheme() {
        if (theme == null) theme = getResources().newTheme();
        return theme;
    }
    @Override public void setTheme(int resourceId) { getTheme().applyStyle(resourceId, true); }

    @Override public Object getSystemService(String name) {
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (inflater == null) inflater = ((LayoutInflater) super.getSystemService(name)).cloneInContext(this);
            return inflater;
        }
        return super.getSystemService(name);
    }

    @Override public Context createConfigurationContext(Configuration configuration) {
        // Avoid directly updating the runtime Resources; the facade still shares AssetManager.
        // Simultaneous windows with different configurations remain outside this probe scope.
        return new BackendContext(super.createConfigurationContext(configuration));
    }

    // Deliberately keep ContextWrapper's real Application identity: AndroidX lifecycle code
    // casts applicationContext to android.app.Application. The host harness Application must
    // expose these resources/classLoader in the backend process for application-wide clients.
    // Also leave getPackageName/getOpPackageName/PackageManager unchanged; there is no second
    // installed package and this research must not fake PackageManager entries or UID checks.
}
