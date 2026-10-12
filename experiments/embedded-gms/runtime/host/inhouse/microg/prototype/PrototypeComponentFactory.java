package inhouse.microg.prototype;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Intent;

/** Framework components returned from the isolated loader remain normal Android components. */
public final class PrototypeComponentFactory extends AppComponentFactory {
    private static String bridge(String name) {
        if (name.equals("org.microg.gms.auth.login.LoginActivity")) return "inhouse.microg.bridge.LoginActivityBridge";
        if (name.equals("org.microg.gms.auth.loginservice.GoogleLoginService")) return "inhouse.microg.bridge.LoginServiceBridge";
        if (name.equals("com.google.android.gms.auth.GetToken")) return "inhouse.microg.bridge.GetTokenServiceBridge";
        if (name.equals("org.microg.gms.gservices.GServicesProvider")) return "inhouse.microg.bridge.GServicesProviderBridge";
        // Final Kotlin provider: it only uses real host preferences/metadata and no
        // backend resource IDs. Let Android attach its real host Context unchanged.
        if (name.equals("org.microg.gms.settings.SettingsProvider")) return name;
        return name.startsWith("inhouse.microg.bridge.") ? name : null;
    }

    @Override public ContentProvider instantiateProvider(ClassLoader cl, String name)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return bridge(name) != null ? (ContentProvider) EmbeddedRuntime.current().newComponent(bridge(name))
                : super.instantiateProvider(cl, name);
    }

    @Override public Service instantiateService(ClassLoader cl, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return bridge(name) != null ? (Service) EmbeddedRuntime.current().newComponent(bridge(name))
                : super.instantiateService(cl, name, intent);
    }

    @Override public Activity instantiateActivity(ClassLoader cl, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return bridge(name) != null ? (Activity) EmbeddedRuntime.current().newComponent(bridge(name))
                : super.instantiateActivity(cl, name, intent);
    }

    @Override public BroadcastReceiver instantiateReceiver(ClassLoader cl, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return bridge(name) != null ? (BroadcastReceiver) EmbeddedRuntime.current().newComponent(bridge(name))
                : super.instantiateReceiver(cl, name, intent);
    }
}
