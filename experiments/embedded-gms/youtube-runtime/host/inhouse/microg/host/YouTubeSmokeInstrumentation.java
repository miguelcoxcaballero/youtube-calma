package inhouse.microg.host;

import android.accounts.AccountManager;
import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorDescription;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.os.Parcel;
import android.os.Parcelable;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
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
        phase("on_create");
        start();
    }

    private void phase(String name) {
        Bundle progress = new Bundle();
        progress.putString("youtubeEmbeddedPhase", name);
        sendStatus(0, progress);
    }

    private void require(String name, boolean condition) throws Exception {
        JSONObject check = new JSONObject();
        check.put("name", name); check.put("passed", condition); checks.put(check);
        Bundle progress = new Bundle();
        progress.putString("youtubeEmbeddedProgress", checks.toString());
        sendStatus(0, progress);
        if (!condition) throw new IllegalStateException(name);
    }

    private boolean installed(Context context, String packageName) {
        try { context.getPackageManager().getPackageInfo(packageName, 0); return true; }
        catch (PackageManager.NameNotFoundException absent) { return false; }
    }

    private void parcelableIsolation(Context context, Context backend) throws Exception {
        ClassLoader isolated = backend.getClassLoader();
        Class<?> responseType = isolated.loadClass("com.google.android.gms.auth.AccountChangeEventsResponse");
        Parcelable original = (Parcelable) responseType.getDeclaredConstructor().newInstance();
        Bundle state = new Bundle();
        state.putParcelable("module", original);
        state.putParcelable("framework", new android.graphics.Point(7, 11));
        Bundle nested = new Bundle();
        nested.putParcelable("module", original);
        state.putBundle("nested", nested);
        byte[] bytes;
        Parcel written = Parcel.obtain();
        try {
            written.writeBundle(state);
            bytes = written.marshall();
        } finally { written.recycle(); }
        Class<?> environment = isolated.loadClass("inhouse.microg.bridge.BackendEnvironment");
        Parcel restored = Parcel.obtain();
        try {
            restored.unmarshall(bytes, 0, bytes.length);
            restored.setDataPosition(0);
            Bundle recreation = restored.readBundle(context.getClassLoader());
            environment.getMethod("prepareState", Bundle.class).invoke(null, recreation);
            Object moduleValue = recreation.getParcelable("module");
            require("saved-state recreation restores isolated module Parcelable", responseType.isInstance(moduleValue)
                    && moduleValue.getClass().getClassLoader() == isolated);
            Object nestedValue = recreation.getBundle("nested").getParcelable("module");
            require("nested saved-state recreation preserves module Parcelable loader", responseType.isInstance(nestedValue)
                    && nestedValue.getClass().getClassLoader() == isolated);
            android.graphics.Point point = recreation.getParcelable("framework");
            require("saved-state isolation preserves framework Parcelable", point != null && point.x == 7 && point.y == 11);
        } finally { restored.recycle(); }
        Parcel intentParcel = Parcel.obtain();
        try {
            new Intent().putExtras(state).writeToParcel(intentParcel, 0);
            byte[] intentBytes = intentParcel.marshall();
            intentParcel.unmarshall(intentBytes, 0, intentBytes.length);
            intentParcel.setDataPosition(0);
            Intent intent = Intent.CREATOR.createFromParcel(intentParcel);
            intent.setExtrasClassLoader(context.getClassLoader());
            environment.getMethod("prepareIntent", Intent.class).invoke(null, intent);
            Object intentValue = intent.getParcelableExtra("module");
            require("intent recreation restores isolated module Parcelable", responseType.isInstance(intentValue)
                    && intentValue.getClass().getClassLoader() == isolated);
            android.graphics.Point point = intent.getParcelableExtra("framework");
            require("intent isolation preserves framework Parcelable", point != null && point.x == 7 && point.y == 11);
        } finally { intentParcel.recycle(); }
    }

    private void manifestReceiverRoundTrip(Context context, Context backend, IBinder token) throws Exception {
        // Read-only upstream status request: no accounts, registration, boot event
        // or push connection is created. A reply proves Android delivered the real
        // manifest receiver, including its Application/ContextImpl framework path.
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<Boolean> moduleReply = new AtomicReference<>(false);
        BroadcastReceiver response = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                try {
                    intent.setExtrasClassLoader(backend.getClassLoader());
                    Object status = intent.getSerializableExtra("org.microg.gms.gcm.SERVICE_INFO");
                    moduleReply.set(status != null && status.getClass().getName().equals("org.microg.gms.gcm.ServiceInfo")
                            && status.getClass().getClassLoader() == backend.getClassLoader());
                } catch (Throwable invalidReply) { moduleReply.set(false); }
                finally { received.countDown(); }
            }
        };
        context.registerReceiver(response, new IntentFilter("org.microg.gms.gcm.SERVICE_INFO_RESPONSE"),
                Context.RECEIVER_NOT_EXPORTED);
        try {
            context.sendOrderedBroadcast(new Intent("org.microg.gms.gcm.SERVICE_INFO_REQUEST")
                    .setComponent(new ComponentName(HOST, "org.microg.gms.gcm.ServiceInfoReceiver")), null);
            require("actual manifest receiver delivers isolated backend status", received.await(15, TimeUnit.SECONDS)
                    && moduleReply.get());
            require("backend survives framework manifest receiver delivery", token.isBinderAlive());
        } finally { context.unregisterReceiver(response); }
    }

    @Override public void onStart() {
        phase("on_start");
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
            phase("prepare_backend_context");
            Context backend = YouTubeGmsBridge.backendContext(context);
            require("wrapped context keeps real installed identity", backend.getPackageName().equals(HOST)
                    && backend.getApplicationInfo().uid == Process.myUid());
            require("module resources resolve independently", backend.getResources()
                    .getIdentifier("gms_app_name", "string", HOST) != 0);
            require("normalized remote context retains module resources", backend.getApplicationContext().getResources()
                    .getIdentifier("gms_app_name", "string", HOST) != 0);
            require("main Application remains unchanged after module preparation", context.getApplicationContext()
                    .getClass().getName().equals("com.google.android.apps.youtube.app.YouTubeTikTokRoot_Application"));
            phase("parcelable_recreation");
            parcelableIsolation(context, backend);
            if (Build.VERSION.SDK_INT >= 34) {
                String originalNamespace = context.getSystemService(JobScheduler.class).getNamespace();
                require("backend jobs have their own Android scheduler namespace", "inhouse.microg".equals(
                        backend.getSystemService(JobScheduler.class).getNamespace()));
                require("host scheduler namespace stays unchanged", java.util.Objects.equals(originalNamespace,
                        context.getSystemService(JobScheduler.class).getNamespace()));
            }
            String preference = "inhouse-embedded-smoke";
            backend.getSharedPreferences(preference, Context.MODE_PRIVATE).edit().putBoolean("probe", true).commit();
            require("backend preferences do not collide with host", !context.getSharedPreferences(preference,
                    Context.MODE_PRIVATE).contains("probe"));
            backend.deleteSharedPreferences(preference);
            File privateDatabase = new File(backend.getNoBackupFilesDir(), "inhouse-smoke.db");
            require("Room absolute database path stays in private backend storage", backend.getDatabasePath(
                    privateDatabase.getAbsolutePath()).getCanonicalFile().equals(privateDatabase.getCanonicalFile()));
            try {
                try (SQLiteDatabase database = backend.openOrCreateDatabase(privateDatabase.getAbsolutePath(),
                        Context.MODE_PRIVATE, null)) {
                    database.execSQL("CREATE TABLE IF NOT EXISTS smoke (value INTEGER)");
                    database.execSQL("INSERT INTO smoke VALUES (17)");
                    try (Cursor row = database.rawQuery("SELECT value FROM smoke", null)) {
                        require("Room-style absolute private database opens and round-trips", row.moveToFirst()
                                && row.getInt(0) == 17);
                    }
                }
            } finally { backend.deleteDatabase(privateDatabase.getAbsolutePath()); }
            require("private absolute test database deletes cleanly", !privateDatabase.exists());
            boolean rejectedHostDatabase = false;
            try { backend.getDatabasePath(context.getDatabasePath("inhouse-smoke-host.db").getAbsolutePath()); }
            catch (SecurityException expected) { rejectedHostDatabase = true; }
            require("absolute host database path remains inaccessible to backend wrapper", rejectedHostDatabase);
            CountDownLatch connected = new CountDownLatch(1);
            AtomicReference<IBinder> binder = new AtomicReference<>();
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    binder.set(service); connected.countDown();
                }
                @Override public void onServiceDisconnected(ComponentName name) { connected.countDown(); }
                @Override public void onNullBinding(ComponentName name) { connected.countDown(); }
            };
            phase("bind_token_service");
            boolean bound = context.bindService(new Intent("com.google.android.gms.auth.GetToken")
                    .setComponent(new ComponentName(HOST, "com.google.android.gms.auth.GetToken")),
                    connection, Context.BIND_AUTO_CREATE);
            try {
                require("token service binds", bound && connected.await(15, TimeUnit.SECONDS)
                        && binder.get() != null && binder.get().isBinderAlive());
                require("token Binder exposes upstream protocol", "com.google.android.auth.IAuthManagerService"
                        .equals(binder.get().getInterfaceDescriptor()));
                phase("manifest_receiver_round_trip");
                manifestReceiverRoundTrip(context, backend, binder.get());
                phase("provider_checks");
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
                phase("main_webview");
                AtomicReference<Throwable> webViewFailure = new AtomicReference<>();
                runOnMainSync(() -> {
                    try {
                        // Acquire the main process's real WebView data-directory lock
                        // without loading any URL. The backend's later login WebView
                        // must work concurrently using its stable directory suffix.
                        WebView probe = new WebView(context);
                        probe.destroy();
                    } catch (Throwable error) { webViewFailure.set(error); }
                });
                if (webViewFailure.get() != null) {
                    report.put("mainWebViewFailureClass", webViewFailure.get().getClass().getName());
                }
                require("main process initializes WebView before backend login", webViewFailure.get() == null);
                // Exercise Android's real account-add contract. Passing no Activity
                // asks AccountManager to return the authenticator's intended UI; it
                // does not add an account or supply any credentials on its own.
                phase("account_manager_add");
                AccountManagerFuture<Bundle> addAccount = AccountManager.get(context).addAccount(
                        ACCOUNT, null, null, new Bundle(), null, null, null);
                Bundle accountEntry = addAccount.getResult(30, TimeUnit.SECONDS);
                Intent login = accountEntry.getParcelable(AccountManager.KEY_INTENT);
                require("AccountManager returns owned embedded login intent", login != null
                        && login.getComponent() != null && HOST.equals(login.getComponent().getPackageName())
                        && "org.microg.gms.auth.login.LoginActivity".equals(login.getComponent().getClassName()));
                Parcelable authenticatorResponse = login.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
                require("account-add intent carries Android authenticator response", authenticatorResponse
                        instanceof AccountAuthenticatorResponse);
                report.put("accountManagerAddAccountEntryExercised", true);
                phase("launch_login");
                context.startActivity(login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                require("real account-add login activity launch requested", true);
            }
            phase("completed");
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
