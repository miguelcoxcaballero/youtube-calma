package inhouse.microg.host;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Process;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Same UID, same installed APK. Assets are executed, never sent to an installer. */
public final class EmbeddedRuntime {
    private static volatile EmbeddedRuntime current;
    private final Resources resources;
    private final ClassLoader loader;
    private final Class<?> environment;

    private EmbeddedRuntime(Context host) throws Exception {
        long started = android.os.SystemClock.elapsedRealtime();
        stage("start", started);
        if (Build.VERSION.SDK_INT < 34) throw new UnsupportedOperationException("This embedded experiment requires Android 14");
        validateDigest(EmbeddedGmsBuild.GMS_SHA256);
        validateDigest(EmbeddedGmsBuild.BRIDGE_SHA256);
        File root = directory(new File(host.getCodeCacheDir(), "inhouse-embedded-gms"));
        // Immutable, content-addressed locations also avoid overwriting a library loaded
        // by another process while a newly installed application version starts.
        File payload = directory(new File(root, EmbeddedGmsBuild.GMS_SHA256
                + "-" + EmbeddedGmsBuild.BRIDGE_SHA256));
        File archive;
        File bridge;
        File libraries;
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(root, "extract.lock"), "rw");
                FileLock ignored = lockFile.getChannel().lock()) {
            archive = extractAsset(host, payload, "gmscore.apk", EmbeddedGmsBuild.GMS_SHA256);
            bridge = extractAsset(host, payload, "bridge.dex", EmbeddedGmsBuild.BRIDGE_SHA256);
            stage("assets_verified", started);
            libraries = extractLibraries(archive, payload);
            stage("libraries_verified", started);
        }
        ClassLoader platform = ClassLoader.getSystemClassLoader().getParent();
        loader = new DexClassLoader(bridge.getAbsolutePath() + File.pathSeparator
                + archive.getAbsolutePath(), host.getCodeCacheDir().getAbsolutePath(),
                libraries == null ? null : libraries.getAbsolutePath(), platform);
        stage("loader_ready", started);
        resources = ArchiveResources.load(host, archive);
        stage("resources_ready", started);
        environment = Class.forName("inhouse.microg.bridge.BackendEnvironment", true, loader);
        environment.getMethod("configure", Resources.class, ClassLoader.class)
                .invoke(null, resources, loader);
        stage("environment_ready", started);
    }

    private static void stage(String stage, long started) {
        android.util.Log.i("InhouseGmsRuntime", stage + " elapsed_ms="
                + (android.os.SystemClock.elapsedRealtime() - started));
    }

    public static synchronized EmbeddedRuntime open(Context host) throws Exception {
        if (current == null) current = new EmbeddedRuntime(host);
        return current;
    }

    public static EmbeddedRuntime current() {
        EmbeddedRuntime value = current;
        if (value == null) throw new IllegalStateException("Built-in services have not been initialized");
        return value;
    }

    public Resources resources() { return resources; }
    public ClassLoader classLoader() { return loader; }

    public Context wrap(Context context) throws Exception {
        return (Context) environment.getMethod("wrap", Context.class).invoke(null, context);
    }

    void setApplication(Application application) throws Exception {
        environment.getMethod("setApplication", Application.class).invoke(null, application);
    }

    static boolean isComponent(String name) {
        for (String component : EmbeddedGmsBuild.COMPONENTS) if (component.equals(name)) return true;
        return false;
    }

    Object newComponent(String name) throws ClassNotFoundException, InstantiationException,
            IllegalAccessException {
        if (!isComponent(name)) throw new ClassNotFoundException("Unregistered built-in component: " + name);
        try {
            return Class.forName(name, true, loader).getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException | NoSuchMethodException error) {
            InstantiationException failure = new InstantiationException(name);
            failure.initCause(error instanceof InvocationTargetException
                    ? ((InvocationTargetException) error).getCause() : error);
            throw failure;
        }
    }

    private static File directory(File path) throws IOException {
        if (!path.isDirectory() && !path.mkdirs()) throw new IOException("Cannot create built-in services directory");
        return path;
    }

    private static void validateDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}")) {
            throw new SecurityException("Missing built-in services build hash");
        }
    }

    private static File extractAsset(Context host, File directory, String name, String expected)
            throws Exception {
        File target = new File(directory, name);
        if (target.isFile() && expected.equals(sha256(target))) {
            protect(target);
            return target;
        }
        try (InputStream input = host.getAssets().open("embedded/" + name)) {
            return publish(input, target, expected);
        }
    }

    private static File publish(InputStream input, File target, String expected) throws Exception {
        File temporary = File.createTempFile(target.getName(), ".partial", target.getParentFile());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                // Android 14 safer DCL: remove path write access before writing through
                // the already-open descriptor; only publish after complete verification.
                protect(temporary);
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                }
                output.getFD().sync();
            }
            if (!expected.equals(hex(digest.digest()))) throw new SecurityException("Built-in payload hash mismatch");
            if (target.exists() && !target.delete()) throw new IOException("Cannot replace stale built-in code");
            if (!temporary.renameTo(target)) throw new IOException("Cannot publish built-in code");
            protect(target);
            return target;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static File extractLibraries(File archive, File payload) throws Exception {
        String[] candidates = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        try (ZipFile zip = new ZipFile(archive)) {
            return extractLibraries(zip, payload, candidates, EmbeddedGmsBuild.NATIVE_LIBRARIES);
        }
    }

    private static File extractLibraries(ZipFile zip, File payload, String[] candidates,
            String[][] fingerprints) throws Exception {
        // The complete archive has already passed GMS_SHA256. Its native inventory
        // must also match the build-generated pins, including ABIs not used here.
        Map<String, String> expected = new LinkedHashMap<>();
        for (String[] fingerprint : fingerprints) {
            if (fingerprint == null || fingerprint.length != 2) {
                throw new SecurityException("Invalid embedded native fingerprint");
            }
            validateLibraryPath(fingerprint[0]);
            validateDigest(fingerprint[1]);
            if (expected.put(fingerprint[0], fingerprint[1]) != null) {
                throw new SecurityException("Duplicate embedded native fingerprint");
            }
        }
        Map<String, ZipEntry> archiveEntries = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith("lib/") || !name.endsWith(".so")) continue;
            validateLibraryPath(name);
            if (archiveEntries.put(name, entry) != null) {
                throw new SecurityException("Duplicate embedded native archive entry");
            }
        }
        if (!expected.keySet().equals(archiveEntries.keySet())) {
            throw new SecurityException("Embedded native inventory differs from build fingerprints");
        }
        for (String abi : candidates) {
            String prefix = "lib/" + abi + "/";
            List<String> entries = new ArrayList<>();
            for (String name : expected.keySet()) if (name.startsWith(prefix)) entries.add(name);
            if (entries.isEmpty()) continue;
            File destination = directory(new File(payload, "lib-" + abi));
            for (String path : entries) {
                String hash = expected.get(path);
                File target = new File(destination, path.substring(prefix.length()));
                if (target.isFile() && hash.equals(sha256(target))) {
                    protect(target);
                } else {
                    // Never inflate solely to calculate an expected hash. A cold or
                    // corrupt cache needs one verified inflate/write; warm caches
                    // need only their file hash and readonly permission check.
                    try (InputStream input = zip.getInputStream(archiveEntries.get(path))) {
                        publish(input, target, hash);
                    }
                }
            }
            return destination;
        }
        // Components using a native library will report their real unsupported-ABI error.
        // Java-only service registration is not proof that authentication or Cronet works.
        return null;
    }

    private static void validateLibraryPath(String name) {
        if (name == null || !name.matches("lib/[A-Za-z0-9_-]+/[A-Za-z0-9_+.\\-]+\\.so")
                || name.contains("..")) {
            throw new SecurityException("Invalid embedded native library path");
        }
    }

    private static void protect(File path) throws Exception {
        if (!path.setReadOnly() || (android.system.Os.stat(path.getAbsolutePath()).st_mode & 0222) != 0) {
            throw new SecurityException("Built-in executable payload is writable");
        }
    }

    private static String sha256(File path) throws Exception {
        try (InputStream input = new FileInputStream(path)) { return sha256(input); }
    }

    private static String sha256(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = digits[(bytes[i] >>> 4) & 15];
            result[i * 2 + 1] = digits[bytes[i] & 15];
        }
        return new String(result);
    }
}
