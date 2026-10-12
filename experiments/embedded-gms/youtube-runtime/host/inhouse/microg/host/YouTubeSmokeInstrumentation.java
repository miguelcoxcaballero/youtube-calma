package inhouse.microg.host;

import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Registered only by the explicit experimental --instrumentation build option. */
public final class YouTubeSmokeInstrumentation extends Instrumentation {
    private static final String HOST = "app.revanced.android.youtube";
    private static final String ACCOUNT = "es.inhouse.youtube.google";
    private final JSONArray checks = new JSONArray();
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        this.arguments = arguments == null ? new Bundle() : arguments;
        start();
    }

    private void require(String name, boolean condition) throws Exception {
        JSONObject check = new JSONObject();
        check.put("name", name); check.put("passed", condition); checks.put(check);
        if (!condition) throw new IllegalStateException(name);
    }

    private boolean installed(Context context, String packageName) {
        try { context.getPackageManager().getPackageInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException absent) { return false; }
    }

    @Override public void onStart() {
        JSONObject report = new JSONObject();
        Bundle result = new Bundle();
        boolean passed = false;
        try {
            Context context = getTargetContext();
            report.put("googleLoginTested", false);
            report.put("youtubePlaybackTested", false);
            report.put("pushDeliveryTested", false);
            report.put("accountsOrCredentialsSupplied", false);
            require("target is real YouTube package", HOST.equals(context.getPackageName()));
            require("main process uses original YouTube Application", context.getApplicationContext()
                    .getClass().getName().equals("com.google.android.apps.youtube.app.YouTubeTikTokRoot_Application"));
            require("external ReVanced microG absent", !installed(context, "app.revanced.android.gms"));
            require("external Vanced microG absent", !installed(context, "com.mgoogle.android.gms"));
            require("test account store is empty", AccountManager.get(context).getAccountsByType(ACCOUNT).length == 0);
            Context backend = YouTubeGmsBridge.backendContext(context);
            require("wrapped context keeps real installed identity", backend.getPackageName().equals(HOST)
                    && backend.getApplicationInfo().uid == Process.myUid());
            require("module resources resolve independently", backend.getResources()
                    .getIdentifier("gms_app_name", "string", HOST) != 0);
            require("normalized remote context retains module resources", backend.getApplicationContext().getResources()
                    .getIdentifier("gms_app_name", "string", HOST) != 0);
            require("main Application remains unchanged after module preparation", context.getApplicationContext()
                    .getClass().getName().equals("com.google.android.apps.youtube.app.YouTubeTikTokRoot_Application"));
            String preference = "inhouse-embedded-smoke";
            backend.getSharedPreferences(preference, Context.MODE_PRIVATE).edit().putBoolean("probe", true).commit();
            require("backend preferences do not collide with host", !context.getSharedPreferences(preference,
                    Context.MODE_PRIVATE).contains("probe"));
            backend.deleteSharedPreferences(preference);
            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<IBinder> binder = new AtomicReference<>();
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    binder.set(service); connected.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName name) { connected.countDown(); }
                @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
            };
            boolean bound = context.bindService(new Intent("com.google.android.gms.auth.GetToken")
                    .setComponent(new ComponentName(HOST, "com.google.android.gms.auth.GetToken")),
                    connection, Context.BIND_AUTO_CREATE);
            try {
                require("token service binds", bound && connected.await(15, TimeUnit.SECONDS)
                        && binder.get() != null && binder.get().isBinderAlive());
                require("token Binder exposes upstream protocol", "com.google.android.auth.IAuthManagerService"
                        .equals(binder.get().getInterfaceDescriptor()));
                for (String authority : new String[]{HOST + ".inhouse.gms.gservices", HOST + ".inhouse.gms.auth.accounts"}) {
                    ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
                    require("private owned provider: " + authority, provider != null
                            && HOST.equals(provider.packageName) && (HOST + ":inhouse_gms").equals(provider.processName)
                            && provider.applicationInfo.uid == Process.myUid() && !provider.exported);
                }
                Uri uri = Uri.parse("content://" + HOST + ".inhouse.gms.gservices/prefix");
                try (ContentProviderClient provider = context.getContentResolver().acquireUnstableContentProviderClient(uri)) {
                    require("real GServices provider starts", provider != null);
                    try (Cursor ignored = provider.query(uri, null, null, new String[]{"inhouse.smoke."}, null)) {}
                    require("real GServices query returns", true);
                }
                boolean authenticator = false;
                for (AuthenticatorDescription auth : AccountManager.get(context).getAuthenticatorTypes()) {
                    if (ACCOUNT.equals(auth.type) && HOST.equals(auth.packageName)) authenticator = true;
                }
                require("Android registers owned embedded authenticator", authenticator);
                try (ContentProviderClient provider = context.getContentResolver().acquireUnstableContentProviderClient(
                        Uri.parse("content://" + HOST + ".inhouse.gms.auth.accounts"))) {
                    require("real account provider starts", provider != null);
                    Bundle response = provider.call("get_accounts", ACCOUNT, null);
                    require("account provider accepts real host caller", response != null
                            && response.getParcelableArray("accounts") != null
                            && response.getParcelableArray("accounts").length == 0);
                }
            } finally { if (bound) context.unbindService(connection); }
            if ("true".equals(arguments.getString("openLogin"))) {
                context.startActivity(new Intent().setComponent(new ComponentName(HOST,
                        "org.microg.gms.auth.login.LoginActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("tmpl", "new_account"));
                require("private embedded login activity launch requested", true);
            }
            passed = true;
        } catch (Throwable error) {
            // Exception class only: upstream messages may carry account or request data.
            try { report.put("errorClass", error.getClass().getName()); }
            catch (Exception ignored) {}
        } finally {
            try { report.put("passed", passed); report.put("checks", checks); }
            catch (Exception ignored) {}
            result.putString("youtubeEmbeddedReport", report.toString());
            result.putString("stream", passed ? "YOUTUBE_EMBEDDED_COMPONENTS_OK\n" : "YOUTUBE_EMBEDDED_COMPONENTS_FAILED\n");
            if (passed && "true".equals(arguments.getString("openLogin"))) {
                // Keep instrumentation alive while the separate runner examines the
                // private login activity. It force-stops this disposable test afterward.
                sendStatus(0, result);
                try { Thread.sleep(180000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        }
    }
}
