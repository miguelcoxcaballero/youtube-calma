package inhouse.microg.prototype;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/** Research harness only: loads bundled code, never installs or contacts another APK. */
public final class EmbeddedRuntime {
    private static volatile EmbeddedRuntime current;
    private final Resources resources;
    private final ClassLoader classLoader;
    // Keep both alive for the lifetime of Resources. Closing either unloads its assets.
    private final ResourcesProvider provider;
    private final ResourcesLoader loader;
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
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(backendApk,
                ParcelFileDescriptor.MODE_READ_ONLY)) {
            provider = ResourcesProvider.loadFromApk(descriptor);
        }
        loader = new ResourcesLoader();
        loader.addProvider(provider);
        // Obtain a ResourcesManager-managed, framework-only resource context through public
        // APIs. AssetManager() is not public, and borrowing Resources.getSystem().getAssets()
        // would mutate the shared system AssetManager when addLoaders() is called.
        Context framework = host.createPackageContext("android", 0);
        resources = framework.createConfigurationContext(
                new Configuration(host.getResources().getConfiguration())).getResources();
        resources.addLoaders(loader);
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

    public Resources resources() { return resources; }
    public ClassLoader classLoader() { return classLoader; }

    public Context wrap(Context host) throws Exception {
        return (Context) environment.getMethod("wrap", Context.class).invoke(null, host);
    }

    public Object newComponent(String className) throws ClassNotFoundException,
            IllegalAccessException, InstantiationException {
        if (!className.startsWith("inhouse.microg.bridge.")) {
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
