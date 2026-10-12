package inhouse.microg.bridge;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.Process;
import android.util.Log;
import org.microg.gms.checkin.CheckinPreferences;
import org.microg.gms.checkin.LastCheckinInfo;

/** Opt-in proposal only; no production pipeline references this class. */
public final class CheckinJobScheduler {
    static final long REGULAR_INTERVAL = 12L * 60 * 60 * 1000;
    static final long RETRY_DELAY = 3L * 60 * 60 * 1000;
    static final String EXTRA_FORCE = "force";
    private static final String NAMESPACE = "inhouse.embedded.checkin";
    private static final String TAG = "InhouseCheckin";
    private static final Object LOCK = new Object();

    private CheckinJobScheduler() {}

    /** Checkin TriggerReceiver only: also covers MCS's indirect CHECKIN after boot. */
    public static boolean handleReceiver(Context raw, Intent intent) {
        if (Build.VERSION.SDK_INT < 35) return false;
        // Match upstream's only force trigger. Never copy arbitrary incoming intent extras.
        boolean force = intent != null
                && "android.provider.Telephony.SECRET_CODE".equals(intent.getAction());
        schedule(raw, true, force);
        return true; // Failure is handled too: never fall back to forbidden boot dataSync FGS.
    }

    /** Replaces the old getService PendingIntent aimed at a BroadcastReceiver on API35+. */
    public static boolean handleSchedule(Context raw) {
        if (Build.VERSION.SDK_INT < 35) return false;
        schedule(raw, false, false);
        return true;
    }

    static void cancelIfDisabled(Context raw) {
        if (Build.VERSION.SDK_INT >= 35) schedule(raw, false, false);
    }

    private static void schedule(Context raw, boolean immediateIfDue, boolean force) {
        synchronized (LOCK) {
            try {
                Context context = BackendEnvironment.wrap(raw);
                JobScheduler root = context.getSystemService(JobScheduler.class);
                if (root == null) {
                    Log.w(TAG, "Registration scheduler unavailable");
                    return;
                }
                JobScheduler scheduler = root.forNamespace(NAMESPACE);
                ComponentName service = new ComponentName(context, CheckinJobService.class);
                if (!serviceIsValid(context, service)) {
                    Log.w(TAG, "Registration service configuration invalid");
                    return;
                }
                String owner = service.flattenToString();
                JobInfo trigger = scheduler.getPendingJob(JobOwnershipPolicy.TRIGGER_JOB_ID);
                JobInfo regular = scheduler.getPendingJob(JobOwnershipPolicy.REGULAR_JOB_ID);
                if (!JobOwnershipPolicy.maySchedule(JobOwnershipPolicy.TRIGGER_JOB_ID, owner, ownerOf(trigger))
                        || !JobOwnershipPolicy.maySchedule(JobOwnershipPolicy.REGULAR_JOB_ID, owner, ownerOf(regular))) {
                    Log.w(TAG, "Registration job ownership conflict");
                    return;
                }
                if (!CheckinPreferences.isEnabled(context)) {
                    cancelOwned(scheduler, JobOwnershipPolicy.TRIGGER_JOB_ID, owner, trigger);
                    cancelOwned(scheduler, JobOwnershipPolicy.REGULAR_JOB_ID, owner, regular);
                    return;
                }
                // Keep existing periodic timing when repeated callbacks arrive.
                if (regular == null && scheduler.schedule(base(JobOwnershipPolicy.REGULAR_JOB_ID, service)
                        .setPeriodic(REGULAR_INTERVAL).build()) != JobScheduler.RESULT_SUCCESS) {
                    Log.w(TAG, "Periodic registration deferred");
                }
                if (!immediateIfDue) return;
                long last = LastCheckinInfo.read(context).getLastCheckin();
                long now = System.currentTimeMillis();
                if (!force && last > 0 && last <= now && now - last < REGULAR_INTERVAL) return;
                if (trigger != null) return; // Do not cancel a running job or reset its retry backoff.
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(EXTRA_FORCE, force);
                if (scheduler.schedule(base(JobOwnershipPolicy.TRIGGER_JOB_ID, service)
                        .setExtras(extras).build()) != JobScheduler.RESULT_SUCCESS) {
                    Log.w(TAG, "Registration deferred");
                }
            } catch (RuntimeException failure) {
                Log.w(TAG, "Registration scheduling failed");
            }
        }
    }

    private static boolean serviceIsValid(Context context, ComponentName component) {
        try {
            ServiceInfo info = context.getPackageManager().getServiceInfo(component, 0);
            String hostPackage = context.getPackageName();
            return hostPackage.equals(info.packageName)
                    && CheckinJobService.class.getName().equals(info.name)
                    && (hostPackage + ":inhouse_gms").equals(info.processName)
                    && "android.permission.BIND_JOB_SERVICE".equals(info.permission)
                    && info.exported && info.enabled && info.applicationInfo != null
                    && info.applicationInfo.enabled && info.applicationInfo.uid == Process.myUid();
        } catch (PackageManager.NameNotFoundException failure) {
            return false;
        }
    }

    private static JobInfo.Builder base(int id, ComponentName service) {
        return new JobInfo.Builder(id, service)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setBackoffCriteria(RETRY_DELAY, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    }

    private static String ownerOf(JobInfo job) {
        return job == null ? null : job.getService().flattenToString();
    }

    private static void cancelOwned(JobScheduler scheduler, int id, String owner, JobInfo existing) {
        if (JobOwnershipPolicy.mayCancel(id, owner, ownerOf(existing))) scheduler.cancel(id);
    }
}
