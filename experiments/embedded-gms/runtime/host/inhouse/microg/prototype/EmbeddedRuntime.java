package inhouse.microg.prototype;

import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/** Research harness only: loads bundled code, never installs or contacts another APK. */
public final class EmbeddedRuntime {
    private static volatile EmbeddedRuntime current;
    private final Resources resources;
    private final ClassLoader classLoader;
    private final Class<?> environment;

    private EmbeddedRuntime(Context host, File backendApk, File bridgeDex) throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new UnsupportedOperationException("API 30 required");
        if (!backendApk.isFile() || !bridgeDex.isFile()) throw new IOException("Bundled payload missing");
        // Files must already be copied from trusted assets, hash-checked and made read-only
        // by the harness before loading. Read-only code is mandatory when targeting API 34+.
        if ((android.system.Os.stat(backendApk.getAbsolutePath()).st_mode & 0222) != 0
                || (android.system.Os.stat(bridgeDex.getAbsolutePath()).st_mode & 0222) != 0) {
            throw new SecurityException("Embedded executable payload must be read-only");
        }
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        classLoader = new DexClassLoader(bridgeDex.getAbsolutePath() + File.pathSeparator
                + backendApk.getAbsolutePath(), host.getCodeCacheDir().getAbsolutePath(), null, boot);
        // The ApplicationInfo overload reads a trusted uninstalled archive directly.
        // Its resource table is distinct from the host and system tables. Keep the
        // real Context/PackageManager identity for all subsequent Android operations.
        resources = ArchiveResources.load(host, backendApk);
        environment = Class.forName("inhouse.microg.bridge.BackendEnvironment", true, classLoader);
        environment.getMethod("configure", Resources.class, ClassLoader.class)
                .invoke(null, resources, classLoader);
    }

    public static synchronized EmbeddedRuntime open(Context host, File backendApk, File bridgeDex)
            throws Exception {
        if (current == null) current = new EmbeddedRuntime(host, backendApk, bridgeDex);
        return current;
    }

    public static EmbeddedRuntime current() {
        EmbeddedRuntime value = current;
        if (value == null) throw new IllegalStateException("Initialize EmbeddedRuntime in Application.attachBaseContext");
        return value;
    }

    public static EmbeddedRuntime currentOrNull() { return current; }

    public Resources resources() { return resources; }
    public ClassLoader classLoader() { return classLoader; }

    public Context wrap(Context host) throws Exception {
        return (Context) environment.getMethod("wrap", Context.class).invoke(null, host);
    }

    public Object newComponent(String className) throws ClassNotFoundException,
            IllegalAccessException, InstantiationException {
        if (!className.startsWith("inhouse.microg.bridge.")
                && !className.equals("org.microg.gms.settings.SettingsProvider")) {
            throw new ClassNotFoundException("Unapproved prototype component: " + className);
        }
        try {
            return Class.forName(className, true, classLoader).getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException | NoSuchMethodException error) {
            InstantiationException failure = new InstantiationException(className);
            failure.initCause(error);
            throw failure;
        }
    }
}
