package inhouse.microg.host;

import android.app.Activity;
import android.app.Application;
import android.app.AppComponentFactory;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.pm.ApplicationInfo;

/** Preserve the host's CoreComponentFactory everywhere except the two private backend processes. */
public final class YouTubeComponentFactory extends AppComponentFactory {
    // The pinned YouTube APK marks CoreComponentFactory's instantiate methods final.
    // Delegate to preserve its compatibility-wrapper behavior; do not subclass it.
    private final AppComponentFactory host = new androidx.core.app.CoreComponentFactory();
    static final String BACKEND_PROCESS = "app.revanced.android.youtube:inhouse_gms";
    static final String DROIDGUARD_PROCESS = "app.revanced.android.youtube:inhouse_gms_unstable";

    private static boolean backendProcess() {
        String process = Application.getProcessName();
        return BACKEND_PROCESS.equals(process) || DROIDGUARD_PROCESS.equals(process);
    }

    private static boolean backendComponent(String name) {
        return backendProcess() && EmbeddedRuntime.isComponent(name);
    }

    @Override public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendProcess() ? new BackendApplication() : host.instantiateApplication(loader, name);
    }

    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (Activity) EmbeddedRuntime.current().newComponent(name)
                : host.instantiateActivity(loader, name, intent);
    }

    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (Service) EmbeddedRuntime.current().newComponent(name)
                : host.instantiateService(loader, name, intent);
    }

    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (ContentProvider) EmbeddedRuntime.current().newComponent(name)
                : host.instantiateProvider(loader, name);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (BroadcastReceiver) EmbeddedRuntime.current().newComponent(name)
                : host.instantiateReceiver(loader, name, intent);
    }

    @Override public ClassLoader instantiateClassLoader(ClassLoader loader, ApplicationInfo info) {
        return host.instantiateClassLoader(loader, info);
    }
}
