package inhouse.microg.host;

import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.widget.Toast;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client adapters for a registered, functioning same-APK backend, not an install-check bypass. */
public final class YouTubeGmsBridge {
    private static final String HOST = "app.revanced.android.youtube";
    private static final String PROCESS = HOST + ":inhouse_gms";
    private static final String ACCOUNT_TYPE = "es.inhouse.youtube.google";
    private static final String GET_TOKEN = "com.google.android.gms.auth.GetToken";
    private static final AtomicBoolean running = new AtomicBoolean();
    private static final AtomicBoolean errorShown = new AtomicBoolean();
    private static volatile String state = "unchecked";

    private YouTubeGmsBridge() {}

    public static int backendVersion(Context context) { return EmbeddedGmsBuild.BACKEND_VERSION; }

    public static Context backendContext(Context context) {
        try { return EmbeddedRuntime.open(context).wrap(context); }
        catch (Exception error) { throw new IllegalStateException("Built-in Google services resources are unavailable", error); }
    }

    public static String backendString(Context context, String name) {
        if (name == null || name.length() == 0) return null;
        Context backend = backendContext(context);
        Resources resources = backend.getResources();
        int id = resources.getIdentifier(name, "string", context.getPackageName());
        if (id == 0) return null;
        try {
            String value = resources.getString(id);
            return value.length() == 0 ? null : value;
        } catch (Resources.NotFoundException missing) { return null; }
    }

    /** Does not block the first YouTube frame or silently treat missing services as ready. */
    public static void check(Activity activity) {
        if ("ready".equals(state) || !running.compareAndSet(false, true)) return;
        final Context application = activity.getApplicationContext();
        final WeakReference<Activity> screen = new WeakReference<>(activity);
        state = "checking";
        Thread worker = new Thread(() -> {
            try {
                clearExternalNotificationDelegate(application);
                // Prepare the immutable payload off the main thread before asking
                // Android to publish any backend provider under its startup deadline.
                EmbeddedRuntime.open(application);
                verifyRegistration(application);
                verifyService(application);
                state = "ready";
            } catch (Exception failure) {
                // No account names, credentials, token responses or backend URLs enter
                // diagnostics. A real failing service remains failed and can be retried.
                state = "failed:" + failure.getClass().getSimpleName();
                new Handler(Looper.getMainLooper()).post(() -> {
                    Activity current = screen.get();
                    if (current != null && !current.isFinishing() && !current.isDestroyed()
                            && errorShown.compareAndSet(false, true)) {
                        Toast.makeText(current, "Built-in Google services could not start. Reopen the app to retry.",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } finally { running.set(false); }
        }, "InhouseGoogleServicesCheck");
        worker.setDaemon(true);
        worker.start();
    }

    /** Exposes only component health to opt-in instrumentation, never login/account data. */
    public static String healthState() { return state; }

    private static void clearExternalNotificationDelegate(Context context) {
        if (!HOST.equals(context.getPackageName())) throw new SecurityException("Unexpected notification host");
        if (Build.VERSION.SDK_INT >= 29) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null && "app.revanced.android.gms".equals(manager.getNotificationDelegate())) {
                // Upgrade cleanup only: normal host notification delivery stays active.
                manager.setNotificationDelegate(null);
            }
        }
    }

    private static void verifyRegistration(Context context) throws Exception {
        if (!HOST.equals(context.getPackageName())) throw new SecurityException("Unexpected backend host");
        PackageManager manager = context.getPackageManager();
        verifyProviderOwner(manager.resolveContentProvider(HOST + ".inhouse.gms.gservices", 0));
        verifyProviderOwner(manager.resolveContentProvider(HOST + ".inhouse.gms.auth.accounts", 0));
        ServiceInfo token = manager.getServiceInfo(new ComponentName(HOST, GET_TOKEN), 0);
        if (!HOST.equals(token.packageName) || !PROCESS.equals(token.processName)
                || token.applicationInfo.uid != Process.myUid() || !token.enabled || token.exported) {
            throw new SecurityException("Invalid embedded token service");
        }
        ServiceInfo login = manager.getServiceInfo(new ComponentName(HOST,
                "org.microg.gms.auth.loginservice.GoogleLoginService"), 0);
        if (!HOST.equals(login.packageName) || !PROCESS.equals(login.processName)
                || login.applicationInfo.uid != Process.myUid() || !login.enabled
                || !"android.permission.BIND_ACCOUNT_AUTHENTICATOR".equals(login.permission)) {
            throw new SecurityException("Invalid embedded account authenticator");
        }
        for (AuthenticatorDescription authenticator : AccountManager.get(context).getAuthenticatorTypes()) {
            if (ACCOUNT_TYPE.equals(authenticator.type) && HOST.equals(authenticator.packageName)) return;
        }
        throw new IllegalStateException("The embedded account authenticator is unavailable");
    }

    private static void verifyProviderOwner(ProviderInfo info) {
        if (info == null || !HOST.equals(info.packageName) || !PROCESS.equals(info.processName)
                || info.applicationInfo.uid != Process.myUid() || !info.enabled || info.exported) {
            throw new SecurityException("Invalid embedded provider");
        }
    }

    private static void verifyProvider(Context context) throws Exception {
        Uri uri = Uri.parse("content://" + HOST + ".inhouse.gms.gservices/prefix");
        try (ContentProviderClient provider = context.getContentResolver().acquireUnstableContentProviderClient(uri)) {
            if (provider == null) throw new IllegalStateException("Embedded provider failed to start");
            // A fresh GServices database legitimately returns a null/empty result.
            // Calling its query through Binder proves registration alone was insufficient.
            try (Cursor ignored = provider.query(uri, null, null, new String[]{"inhouse.health."}, null)) {}
        }
    }

    private static void verifyService(Context context) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean alive = new AtomicBoolean();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) {
                alive.set(service != null && service.isBinderAlive());
                completed.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                alive.set(false);
                completed.countDown();
            }
            @Override public void onNullBinding(ComponentName name) { completed.countDown(); }
            @Override public void onBindingDied(ComponentName name) { completed.countDown(); }
        };
        Intent intent = new Intent(GET_TOKEN).setComponent(new ComponentName(HOST, GET_TOKEN));
        boolean bound = false;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!bound || !completed.await(12, TimeUnit.SECONDS) || !alive.get()) {
                throw new IllegalStateException("Embedded token service did not respond");
            }
            // A provider-first cold start has a shorter Android publication deadline.
            // Keep a real service binding alive while querying the same backend process;
            // this retains all hash checks and does not change platform timeout limits.
            verifyProvider(context);
        } finally {
            if (bound) context.unbindService(connection);
        }
    }
}
