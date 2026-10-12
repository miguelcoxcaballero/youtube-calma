package inhouse.microg.bridge;

import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import org.microg.gms.checkin.CheckinPreferences;
import org.microg.gms.checkin.LastCheckinInfo;
import org.microg.gms.gcm.GcmPrefs;
import org.microg.gms.gcm.McsService;

/**
 * Inactive API35+ proposal. Android must actually permit each foreground launch.
 * A registration job can signal a running messaging service, never create an FGS exemption.
 */
public final class McsSessionCoordinator {
    public static final String WAIT_ACTION = "inhouse.microg.action.WAIT_FOR_REGISTRATION";
    private static final String TAG = "InhouseMessaging";
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long SETTINGS_CHECK_MILLIS = 60000L;
    private static final ForegroundSessionPolicy<McsService> SESSION =
            new ForegroundSessionPolicy<McsService>();
    private static boolean waiting;

    private McsSessionCoordinator() {}

    /** Insert only at the actual GCM boot receiver; an action string grants no privilege. */
    public static boolean prepareBootReceiver(Context raw, Intent intent) {
        if (Build.VERSION.SDK_INT < 35 || intent == null
                || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return false;
        if (!startWaitingService(raw)) CheckinJobScheduler.handleReceiver(raw, null);
        // Consume failures too. Never fall back to a boot-launched dataSync FGS.
        return true;
    }

    /** Optional entry for a genuinely visible app Activity; not wired by this proposal. */
    public static boolean startFromVisibleActivity(Activity activity) {
        if (Build.VERSION.SDK_INT < 35 || activity == null || activity.isFinishing()
                || activity.isDestroyed() || !activity.hasWindowFocus()) return false;
        return startWaitingService(activity);
    }

    private static boolean startWaitingService(Context raw) {
        try {
            Context context = BackendEnvironment.wrap(raw);
            if (!GcmPrefs.get(context).isEnabled()) return false;
            if (LastCheckinInfo.read(context).getAndroidId() == 0
                    && !CheckinPreferences.isEnabled(context)) return false;
            ComponentName component = new ComponentName(context, McsService.class);
            if (!validService(context, component)) {
                Log.w(TAG, "Messaging service configuration invalid");
                return false;
            }
            // Always request a real FGS and its notification. Do not use upstream's
            // battery-exemption path, which may omit the foreground marker.
            context.startForegroundService(new Intent(WAIT_ACTION).setComponent(component)
                    .putExtra("foreground", true));
            return true;
        } catch (RuntimeException denied) {
            Log.i(TAG, "Messaging awaits an eligible foreground launch");
            // Registration remains useful even if the OS denies messaging promotion.
            CheckinJobScheduler.handleReceiver(raw, null);
            return false;
        }
    }

    /** Replaces only the prepared ForegroundServiceContext promotion call. */
    public static void startForeground(Service service, int id, Notification notification) {
        BackendEnvironment.startForeground(service, id, notification);
        // completeForegroundService catches promotion errors upstream. Record success
        // here, after the platform call, rather than trusting that method's return.
        if (Build.VERSION.SDK_INT >= 35 && service instanceof McsService) {
            synchronized (LOCK) {
                McsService previous = SESSION.current();
                if (previous != service) {
                    SESSION.promoted((McsService) service);
                    waiting = false;
                    MAIN.removeCallbacks(CHECK_SETTINGS);
                }
            }
        }
    }

    /** Runs after foreground promotion and before upstream's pending-connect path. */
    public static boolean handleStart(McsService service, Intent intent, int startId) {
        if (Build.VERSION.SDK_INT < 35 || intent == null
                || !WAIT_ACTION.equals(intent.getAction())) return false;
        if (!isActive(service)) {
            service.stopSelf(startId);
            CheckinJobScheduler.handleReceiver(service, null);
            return true;
        }
        try {
            if (!GcmPrefs.get(service).isEnabled()
                    || (LastCheckinInfo.read(service).getAndroidId() == 0
                    && !CheckinPreferences.isEnabled(service))) {
                onDestroy(service);
                service.stopSelf(startId);
                return true;
            }
            if (LastCheckinInfo.read(service).getAndroidId() != 0) {
                clearWaiting(service);
                service.inhouseRequestReconnect();
            } else {
                synchronized (LOCK) {
                    if (SESSION.isActive(service)) {
                        waiting = true;
                        MAIN.removeCallbacks(CHECK_SETTINGS);
                        MAIN.postDelayed(CHECK_SETTINGS, SETTINGS_CHECK_MILLIS);
                    }
                }
            }
            // Schedule only after actual promotion. WAIT never enters the upstream
            // branch that acquires a wake lock or retains an implicit connect Intent.
            CheckinJobScheduler.handleReceiver(service, null);
        } catch (RuntimeException failure) {
            onDestroy(service);
            service.stopSelf(startId);
            Log.w(TAG, "Messaging registration settings unavailable");
        }
        return true;
    }

    public static boolean isActive(McsService service) {
        synchronized (LOCK) { return SESSION.isActive(service); }
    }

    public static void onDestroy(McsService service) {
        synchronized (LOCK) {
            if (!SESSION.destroyed(service)) return;
            waiting = false;
            MAIN.removeCallbacks(CHECK_SETTINGS);
        }
    }

    /** Called by successful bounded registration; never starts/promotes a service. */
    public static void onCheckinComplete(Context context) {
        CheckinJobNetwork.checkpoint();
        McsService service;
        synchronized (LOCK) { service = SESSION.current(); }
        if (service == null || !GcmPrefs.get(context).isEnabled()
                || LastCheckinInfo.read(context).getAndroidId() == 0) return;
        CheckinJobNetwork.checkpoint();
        // Do not hold LOCK: the original protocol bridge locks McsService.class and
        // rechecks isActive plus Android ID before queuing MSG_CONNECT.
        if (service.inhouseRequestReconnect()) clearWaiting(service);
    }

    private static void clearWaiting(McsService service) {
        synchronized (LOCK) {
            if (!SESSION.isActive(service)) return;
            waiting = false;
            MAIN.removeCallbacks(CHECK_SETTINGS);
        }
    }

    private static final Runnable CHECK_SETTINGS = new Runnable() {
        @Override public void run() {
            McsService service;
            synchronized (LOCK) {
                if (!waiting) return;
                service = SESSION.current();
            }
            if (service == null) return;
            try {
                long androidId = LastCheckinInfo.read(service).getAndroidId();
                if (!GcmPrefs.get(service).isEnabled()
                        || (androidId == 0 && !CheckinPreferences.isEnabled(service))) {
                    onDestroy(service);
                    service.stopSelf();
                    return;
                }
                if (androidId != 0 && service.inhouseRequestReconnect()) {
                    clearWaiting(service);
                    return;
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "Messaging registration settings unavailable");
            }
            synchronized (LOCK) {
                if (waiting && SESSION.isActive(service))
                    MAIN.postDelayed(this, SETTINGS_CHECK_MILLIS);
            }
            // No wake lock, alarm or deadline: offline registration may await network
            // restoration/Doze for longer than a job attempt's HTTP budget.
        }
    };

    private static boolean validService(Context context, ComponentName component) {
        try {
            ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
            String host = context.getPackageName();
            return host.equals(info.packageName) && McsService.class.getName().equals(info.name)
                    && (host + ":inhouse_gms").equals(info.processName)
                    && !info.exported && info.enabled && info.applicationInfo != null
                    && info.applicationInfo.enabled && info.applicationInfo.uid == Process.myUid()
                    && (info.getForegroundServiceType() & ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) != 0
                    && context.checkSelfPermission("android.permission.FOREGROUND_SERVICE")
                        == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
                        == PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }
}
