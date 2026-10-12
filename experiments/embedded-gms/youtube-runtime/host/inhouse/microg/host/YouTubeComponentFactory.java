package inhouse.microg.host;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;

/** Preserve the host's CoreComponentFactory everywhere except the private backend process. */
public final class YouTubeComponentFactory extends androidx.core.app.CoreComponentFactory {
    private static boolean backendProcess() {
        return "app.revanced.android.youtube:inhouse_gms".equals(Application.getProcessName());
    }

    private static boolean backendComponent(String name) {
        return backendProcess() && EmbeddedRuntime.isComponent(name);
    }

    @Override public Application instantiateApplication(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendProcess() ? new BackendApplication() : super.instantiateApplication(loader, name);
    }

    @Override public Activity instantiateActivity(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (Activity) EmbeddedRuntime.current().newComponent(name)
                : super.instantiateActivity(loader, name, intent);
    }

    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (Service) EmbeddedRuntime.current().newComponent(name)
                : super.instantiateService(loader, name, intent);
    }

    @Override public ContentProvider instantiateProvider(ClassLoader loader, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (ContentProvider) EmbeddedRuntime.current().newComponent(name)
                : super.instantiateProvider(loader, name);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return backendComponent(name) ? (BroadcastReceiver) EmbeddedRuntime.current().newComponent(name)
                : super.instantiateReceiver(loader, name, intent);
    }
}
