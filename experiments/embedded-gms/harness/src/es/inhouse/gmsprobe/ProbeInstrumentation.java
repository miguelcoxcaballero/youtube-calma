package es.inhouse.gmsprobe;

import android.accounts.AccountManager;
import android.accounts.AuthenticatorDescription;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;
import inhouse.microg.prototype.EmbeddedRuntime;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real framework/component checks; deliberately makes no Google-account claims. */
public final class ProbeInstrumentation extends Instrumentation {
    private final JSONArray checks = new JSONArray();
    private Context context;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private void check(String name, boolean value) throws Exception {
        checks.put(new JSONObject().put("name", name).put("passed", value));
        if (!value) throw new AssertionError(name);
    }
    @Override public void onStart() {
        JSONObject report = new JSONObject(); Bundle result = new Bundle(); int status = -1;
        context = getTargetContext();
        try {
            report.put("googleLoginTested", false).put("youtubePlaybackTested", false).put("pushDeliveryTested", false)
                .put("accountsOrCredentialsSupplied", false)
                .put("scope", "same-APK class/resources/provider/authenticator/Binder plumbing");
            for (String name : new String[]{"app.revanced.android.gms", "com.mgoogle.android.gms"}) {
                boolean present = true;
                try { context.getPackageManager().getPackageInfo(name, 0); }
                catch (PackageManager.NameNotFoundException expected) { present = false; }
                check("external package absent: " + name, !present);
            }
            EmbeddedRuntime runtime = EmbeddedRuntime.current();
            ClassLoader isolated = runtime.classLoader();
            Class<?> backend = isolated.loadClass("org.microg.gms.gservices.GServicesProvider");
            check("backend classes use isolated loader", backend.getClassLoader() == isolated);
            boolean leaked = false;
            try { context.getClassLoader().loadClass(backend.getName()); leaked = true; }
            catch (ClassNotFoundException expected) { }
            check("backend classes absent from host loader", !leaked);
            check("backend Kotlin library isolated", isolated.loadClass("kotlin.Unit").getClassLoader() == isolated);
            int backendLabelId = runtime.resources().getIdentifier("gms_app_name", "string", "app.revanced.android.gms");
            check("original backend resource table resolves by name", backendLabelId != 0);
            String label = runtime.resources().getString(backendLabelId);
            check("original backend resources readable", "microG Services".equals(label));
            report.put("backendLabel", label);
            int hostLabelId = context.getResources().getIdentifier("probe_label", "string", context.getPackageName());
            check("host resources remain isolated", hostLabelId != 0 && "Embedded microG probe".equals(context.getResources().getString(hostLabelId)));
            for (String resourcePackage : new String[]{"app.revanced.android.gms", "com.google.android.gms"}) {
                check("system resources unmodified: " + resourcePackage,
                    android.content.res.Resources.getSystem().getIdentifier("google_account_label", "string", resourcePackage) == 0);
                check("host resources exclude backend: " + resourcePackage,
                    context.getResources().getIdentifier("google_account_label", "string", resourcePackage) == 0);
            }
            ProviderInfo provider = context.getPackageManager().resolveContentProvider("app.revanced.android.gsf.gservices", 0);
            check("provider belongs to only installed probe APK", provider != null && provider.packageName.equals(context.getPackageName()));
            check("provider uses separate backend process", provider.processName.equals(context.getPackageName() + ":microg"));
            Uri base = Uri.parse("content://app.revanced.android.gsf.gservices/");
            ContentValues values = new ContentValues(); values.put("name", "inhouse_probe_value"); values.put("value", "embedded");
            check("real Gservices provider stores value", context.getContentResolver().update(Uri.withAppendedPath(base, "main"), values, null, null) == 1);
            try (Cursor cursor = context.getContentResolver().query(Uri.withAppendedPath(base, "prefix"), null, null, new String[]{"inhouse_probe_"}, null)) {
                check("real Gservices provider reads stored value", cursor != null && cursor.moveToFirst() && "embedded".equals(cursor.getString(cursor.getColumnIndexOrThrow("value"))));
            }
            try (Binding auth = bind("inhouse.microg.bridge.GetTokenServiceBridge", "com.google.android.gms.auth.GetToken")) {
                check("auth Binder descriptor is real upstream interface", "com.google.android.auth.IAuthManagerService".equals(auth.binder.getInterfaceDescriptor()));
                Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken("com.google.android.auth.IAuthManagerService");
                    data.writeInt(0); // Nullable AccountChangeEventsRequest; no credentials needed.
                    check("auth Binder getChangeEvents transaction accepted", auth.binder.transact(IBinder.FIRST_CALL_TRANSACTION + 2, data, reply, 0));
                    reply.readException();
                    check("auth Binder returns parcelable response", reply.readInt() == 1 && reply.dataAvail() > 0);
                    Class<?> responseType = isolated.loadClass("com.google.android.gms.auth.AccountChangeEventsResponse");
                    android.os.Parcelable.Creator<?> creator = (android.os.Parcelable.Creator<?>) responseType.getField("CREATOR").get(null);
                    Object response = creator.createFromParcel(reply);
                    check("auth response deserializes through upstream CREATOR", responseType.isInstance(response));
                    check("auth response Parcel fully consumed", reply.dataAvail() == 0);
                    java.lang.reflect.Field events = responseType.getDeclaredField("events"); events.setAccessible(true);
                    check("auth response contains expected empty event list", ((java.util.List<?>) events.get(response)).isEmpty());
                } finally { data.recycle(); reply.recycle(); }
            }
            boolean registered = false;
            for (AuthenticatorDescription authenticator : AccountManager.get(context).getAuthenticatorTypes()) {
                if ("app.revanced".equals(authenticator.type) && context.getPackageName().equals(authenticator.packageName)) registered = true;
            }
            check("Android AccountManager registers embedded authenticator", registered);
            try (Binding login = bind("inhouse.microg.bridge.LoginServiceBridge", AccountManager.ACTION_AUTHENTICATOR_INTENT)) {
                check("login Binder is real Android authenticator", "android.accounts.IAccountAuthenticator".equals(login.binder.getInterfaceDescriptor()));
            }
            report.put("passed", true); status = 0;
        } catch (Throwable error) {
            try { report.put("passed", false).put("error", Log.getStackTraceString(error)); } catch (Exception ignored) { }
            Log.e("EmbeddedGmsProbe", "Component probe failed", error);
        } finally {
            try {
                report.put("checks", checks);
                String json = report.toString(2);
                try (FileOutputStream output = new FileOutputStream(new File(context.getFilesDir(), "probe-report.json"))) {
                    output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                result.putString("probeReport", report.toString());
                result.putString("result", status == 0 ? "EMBEDDED_GMS_COMPONENTS_OK" : "EMBEDDED_GMS_COMPONENTS_FAILED");
            } catch (Exception error) { result.putString("reportError", error.toString()); status = -1; }
            finish(status, result);
        }
    }
    private Binding bind(String name, String action) throws Exception {
        Binding result = new Binding();
        Intent intent = new Intent(action).setComponent(new ComponentName(context.getPackageName(), name));
        if (!context.bindService(intent, result, Context.BIND_AUTO_CREATE)) throw new Exception("Service binding rejected: " + name);
        result.bound = true;
        if (!result.ready.await(20, TimeUnit.SECONDS) || result.binder == null) { result.close(); throw new Exception("Service binding timed out: " + name); }
        return result;
    }
    private final class Binding implements ServiceConnection, AutoCloseable {
        final CountDownLatch ready = new CountDownLatch(1); volatile IBinder binder; boolean bound;
        @Override public void onServiceConnected(ComponentName name, IBinder service) { binder = service; ready.countDown(); }
        @Override public void onServiceDisconnected(ComponentName name) { binder = null; }
        @Override public void onNullBinding(ComponentName name) { ready.countDown(); }
        @Override public void close() { if (bound) { context.unbindService(this); bound = false; } }
    }
}
