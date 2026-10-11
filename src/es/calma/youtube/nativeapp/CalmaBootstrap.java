package es.calma.youtube.nativeapp;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Iterator;
import org.json.JSONObject;

/** Runs before ReVanced constructs/caches any Setting instances. */
public final class CalmaBootstrap {
    private static boolean seeded;
    private static boolean registered;

    public static synchronized void initialize(Context context) {
        if (context == null) return;
        if (!seeded) {
            SharedPreferences preferences = context.getSharedPreferences("revanced_prefs", 0);
            if (!preferences.getBoolean("calma_preset_v1", false)) {
                try (InputStream stream = context.getAssets().open("calma-preferences.json")) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    for (int count; (count = stream.read(buffer)) != -1;) bytes.write(buffer, 0, count);
                    JSONObject values = new JSONObject(bytes.toString("UTF-8"));
                    SharedPreferences.Editor editor = preferences.edit();
                    Iterator<String> keys = values.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = values.get(key);
                        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                        else editor.putString(key, value.toString());
                    }
                    // Atomic preset + marker: upgrades keep changes and SponsorBlock totals.
                    if (!editor.putBoolean("calma_preset_v1", true).commit()) {
                        Log.e("Calma", "Could not persist initial preferences");
                        return;
                    }
                } catch (Exception error) {
                    Log.e("Calma", "Could not apply bundled preferences", error);
                    return;
                }
            }
            seeded = true;
        }
        Context app = context.getApplicationContext();
        if (!registered && app instanceof Application) {
            registered = true;
            NativeUpdates updates = new NativeUpdates(app);
            ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                public void onActivityCreated(Activity activity, Bundle state) {}
                public void onActivityStarted(Activity activity) {}
                public void onActivityResumed(Activity activity) { updates.onResumed(activity); }
                public void onActivityPaused(Activity activity) { updates.onPaused(activity); }
                public void onActivityStopped(Activity activity) {}
                public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
                public void onActivityDestroyed(Activity activity) { updates.onDestroyed(activity); }
            });
        }
    }

    public static String homeBrowseId(String original) {
        return "FEwhat_to_watch".equals(original) ? "FEsubscriptions" : original;
    }

    public static void toolbarItem(Enum<?> icon, android.view.MenuItem item) {
        if (icon == null || item == null) return;
        String name = icon.name();
        if (name.equals("TAB_ACTIVITY") || name.equals("TAB_ACTIVITY_CAIRO")
                || name.startsWith("CREATION_ENTRY") || name.equals("FAB_CAMERA")) {
            item.setVisible(false);
        }
    }
}
