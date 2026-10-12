package inhouse.microg.bridge;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;

/** Isolated with microG. Never references host-private or vendor AndroidX classes. */
public final class BackendEnvironment {
    static Resources resources;
    static ClassLoader classLoader;
    static Application application;

    private BackendEnvironment() {}

    public static void configure(Resources value, ClassLoader code) {
        if (value == null || code == null) throw new NullPointerException("Backend environment");
        resources = value;
        classLoader = code;
    }

    public static void setApplication(Application value) { application = value; }

    public static Context wrap(Context context) {
        if (context == null) throw new NullPointerException("Backend context");
        if (resources == null || classLoader == null) throw new IllegalStateException("Backend environment not configured");
        // Android can attach providers using our Application, whose base is already
        // wrapped. Keep the original outer identity (Application/Activity/window),
        // otherwise preference names and file paths acquire a second namespace.
        IdentityHashMap<Context, Boolean> visited = new IdentityHashMap<>();
        Context current = context;
        while (current != null) {
            if (current instanceof BackendContext) return context;
            if (visited.put(current, Boolean.TRUE) != null) throw new IllegalStateException("Cyclic backend context chain");
            if (!(current instanceof ContextWrapper)) break;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return new BackendContext(context);
    }

    /** The build stores the module's resource ID as data, not a host resource reference. */
    public static void prepareActivity(Activity activity) {
        try {
            ActivityInfo info = activity.getPackageManager().getActivityInfo(
                    activity.getComponentName(), PackageManager.GET_META_DATA);
            if (info.metaData != null) {
                int theme = info.metaData.getInt("inhouse.gms.theme", 0);
                if (theme != 0) activity.setTheme(theme);
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Unregistered built-in activity", error);
        }
    }

    /** Preserve Android's foreground/background enforcement while supplying declared types. */
    public static void startForeground(Service service, int notificationId, Notification notification) {
        notification = notificationForHost(service, notification);
        if (Build.VERSION.SDK_INT < 29) {
            service.startForeground(notificationId, notification);
            return;
        }
        int type;
        try {
            ServiceInfo info = service.getPackageManager().getServiceInfo(
                    new ComponentName(service, service.getClass().getName()), 0);
            type = info.getForegroundServiceType();
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException("Unregistered built-in foreground service", error);
        }
        String name = service.getClass().getName();
        if (name.equals("org.microg.gms.checkin.CheckinService")) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        } else if (name.equals("org.microg.gms.gcm.McsService") && Build.VERSION.SDK_INT >= 34) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        } else if (Build.VERSION.SDK_INT < 34) {
            // specialUse was introduced in Android 14. Older systems still use the
            // registered older types, or the original overload when none applies.
            type &= ~0x40000000;
        }
        if (type == 0) service.startForeground(notificationId, notification);
        else service.startForeground(notificationId, notification, type);
    }

    private static Notification notificationForHost(Context context, Notification notification) {
        // SystemUI runs outside the isolated module and can only resolve resources
        // from installed packages. Never send its private archive resource IDs there.
        Icon icon;
        try {
            Resources host = context.getPackageManager().getResourcesForApplication(context.getPackageName());
            int id = host.getIdentifier("inhouse_settings_icon", "drawable", context.getPackageName());
            icon = id != 0 ? Icon.createWithResource(context.getPackageName(), id)
                    : Icon.createWithResource("android", android.R.drawable.ic_dialog_info);
        } catch (PackageManager.NameNotFoundException missingHost) {
            throw new IllegalStateException("Installed services host is missing", missingHost);
        }
        return Notification.Builder.recoverBuilder(context, notification).setSmallIcon(icon).build();
    }

    /** Auth notifications also cross into SystemUI and cannot carry a module-only icon. */
    public static void notify(NotificationManager manager, int id, Notification notification) {
        if (application == null) throw new IllegalStateException("Backend application is unavailable");
        manager.notify(id, notificationForHost(application, notification));
    }

    public static void notify(NotificationManager manager, String tag, int id, Notification notification) {
        if (application == null) throw new IllegalStateException("Backend application is unavailable");
        manager.notify(tag, id, notificationForHost(application, notification));
    }

    /** Inexact wakeup is allowed when the user has not granted exact alarm access. */
    public static void scheduleAlarm(AlarmManager manager, int type, long time, PendingIntent operation) {
        if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
            manager.setAndAllowWhileIdle(type, time, operation);
        } else {
            manager.setExactAndAllowWhileIdle(type, time, operation);
        }
    }

    private static File privateVmPath(File candidate) throws IOException {
        if (application == null) throw new IllegalStateException("Backend application is unavailable");
        File root = application.getDir("cache_dg", Context.MODE_PRIVATE).getCanonicalFile();
        File canonical = candidate.getCanonicalFile();
        if (!canonical.getPath().startsWith(root.getPath() + File.separator)) {
            throw new SecurityException("Challenge executable must remain in the private backend cache");
        }
        if (candidate.exists() && !candidate.getAbsoluteFile().equals(canonical)) {
            // Do not follow a file alias into executable code, even within the cache.
            try {
                if (OsConstants.S_ISLNK(Os.lstat(candidate.getAbsolutePath()).st_mode)) {
                    throw new SecurityException("Challenge executable cannot be a symbolic link");
                }
            } catch (ErrnoException error) { throw new IOException("Cannot inspect challenge cache", error); }
        }
        return canonical;
    }

    /** Android 14 DCL requires making the new file read-only before writing its bytes. */
    public static void writeReadOnlyVm(File destination, byte[] payload) throws IOException {
        if (payload == null) throw new NullPointerException("Challenge executable payload");
        File target = privateVmPath(destination);
        FileDescriptor descriptor = null;
        boolean created = false;
        boolean complete = false;
        try {
            descriptor = Os.open(target.getAbsolutePath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                    | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW, 0600);
            created = true;
            Os.fchmod(descriptor, 0400);
            try (FileOutputStream output = new FileOutputStream(descriptor)) {
                descriptor = null; // The stream now owns and closes the descriptor.
                output.write(payload);
                output.getFD().sync();
            }
            validateVmFile(target);
            complete = true;
        } catch (ErrnoException error) {
            throw new IOException("Cannot create private read-only challenge executable", error);
        } finally {
            if (descriptor != null) try { Os.close(descriptor); } catch (ErrnoException ignored) {}
            if (created && !complete) target.delete();
        }
        // This is storage hardening only. The existing signed-response/checksum
        // validation and challenge result flow remain in the original module.
    }

    public static String prepareVmForLoading(String path) throws IOException {
        File target = privateVmPath(new File(path));
        try {
            StructStat info = Os.lstat(target.getAbsolutePath());
            if (!OsConstants.S_ISREG(info.st_mode) || info.st_uid != Process.myUid()) {
                throw new SecurityException("Challenge executable is not a private regular file");
            }
            if (!target.setReadOnly()) throw new SecurityException("Cannot protect challenge executable");
            validateVmFile(target);
            return target.getAbsolutePath();
        } catch (ErrnoException error) { throw new IOException("Cannot inspect challenge executable", error); }
    }

    private static void validateVmFile(File target) throws ErrnoException {
        StructStat info = Os.lstat(target.getAbsolutePath());
        if (!OsConstants.S_ISREG(info.st_mode) || info.st_uid != Process.myUid()
                || (info.st_mode & 0222) != 0 || info.st_size == 0) {
            throw new SecurityException("Challenge executable is not a private read-only regular file");
        }
    }
}
