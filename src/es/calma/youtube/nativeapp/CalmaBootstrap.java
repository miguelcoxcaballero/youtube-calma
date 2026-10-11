package es.calma.youtube.nativeapp;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.Intent;
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
        CalmaSwitch.initialize(context);
        if (context instanceof Activity) launchSubscriptions(((Activity) context).getIntent());
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
            // 0.1.0 users already have the original preset. Apply only the newly
            // requested navigation/watch changes, preserving all other values.
            if (!preferences.getBoolean("calma_watch_v2", false)) {
                boolean saved=preferences.edit()
                    .putBoolean("revanced_hide_home_button", true)
                    .putBoolean("revanced_hide_related_videos", true)
                    .putBoolean("revanced_hide_related_videos_overlay", true)
                    .putBoolean("revanced_hide_filter_bar_feed_in_related_videos", true)
                    .putBoolean("revanced_hide_comments_section", false)
                    .putBoolean("revanced_hide_comments_preview_comment", false)
                    .putBoolean("revanced_hide_comments_button", false)
                    .putBoolean("calma_watch_v2", true).commit();
                if (!saved) return;
            }
            // This APK is intentionally distributed as a signed Calma release,
            // not built/installed on this phone by ReVanced Manager. Its original
            // patch-time/installer-origin warning does not describe this build.
            // The Photos installer's package, version, SHA and signer checks stay intact.
            if (!preferences.getBoolean("calma_distribution_v3", false)) {
                if (!preferences.edit()
                    .putString("revanced_check_environment_warnings_issued", "2147483647")
                    .putBoolean("calma_distribution_v3", true).commit()) return;
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
        if (!CalmaSwitch.enabled()) {
            try {
                return (String)Class.forName("app.revanced.extension.youtube.patches.ChangeStartPagePatch")
                    .getMethod("overrideBrowseId",String.class).invoke(null,original);
            } catch (ReflectiveOperationException error) { return original; }
        }
        return "FEwhat_to_watch".equals(original) ? "FEsubscriptions" : original;
    }

    public static void launchSubscriptions(Intent intent) {
        if (CalmaSwitch.enabled() && intent != null && Intent.ACTION_MAIN.equals(intent.getAction())) {
            intent.setAction("com.google.android.youtube.action.open.subscriptions");
        }
    }

    public static void toolbarItem(Enum<?> icon, android.view.MenuItem item) {
        if (!CalmaSwitch.enabled()) return;
        if (icon == null || item == null) return;
        String name = icon.name();
        if (name.equals("TAB_ACTIVITY") || name.equals("TAB_ACTIVITY_CAIRO")
                || name.startsWith("CREATION_ENTRY") || name.equals("FAB_CAMERA")) {
            item.setVisible(false);
        }
    }
}
