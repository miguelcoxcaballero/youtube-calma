package es.inhouse.gmsprobe;

import android.app.Application;
import android.content.Context;
import inhouse.microg.prototype.EmbeddedRuntime;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Same-APK runtime probe: no package installer and no external service package. */
public final class ProbeApplication extends Application {
    private EmbeddedRuntime backendRuntime() {
        if (!ProbeBuild.LOGIN_ENTRY || !Application.getProcessName().endsWith(":microg")) return null;
        try { return EmbeddedRuntime.current(); }
        catch (IllegalStateException notInitialized) { return null; }
    }
    @Override public android.content.res.Resources getResources() {
        EmbeddedRuntime runtime = backendRuntime();
        return runtime == null ? super.getResources() : runtime.resources();
    }
    @Override public android.content.res.AssetManager getAssets() {
        EmbeddedRuntime runtime = backendRuntime();
        return runtime == null ? super.getAssets() : runtime.resources().getAssets();
    }
    @Override public ClassLoader getClassLoader() {
        EmbeddedRuntime runtime = backendRuntime();
        return runtime == null ? super.getClassLoader() : runtime.classLoader();
    }
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            File directory = new File(base.getCodeCacheDir(), "embedded-microg");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new Exception("Cannot create private code directory");
            File backend = extract(base, directory, "gmscore.apk", ProbeBuild.GMS_SHA256);
            File bridge = extract(base, directory, "bridge.dex", ProbeBuild.BRIDGE_SHA256);
            EmbeddedRuntime.open(base, backend, bridge);
        } catch (Exception error) {
            throw new IllegalStateException("Embedded microG probe initialization failed", error);
        }
    }

    private static File extract(Context context, File directory, String name, String expected) throws Exception {
        File target = new File(directory, name);
        // Each process can start concurrently. Lock extraction so the published file is
        // complete, verified and read-only before Android 14+ is allowed to execute it.
        File lock = new File(directory, "extract.lock");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(lock, "rw");
                java.nio.channels.FileLock ignored = file.getChannel().lock()) {
            if (target.isFile() && expected.equals(sha256(target))) {
                if (!target.setReadOnly()) throw new Exception("Cannot protect embedded file");
                return target;
            }
            if (target.exists() && !target.delete()) throw new Exception("Cannot remove stale embedded file");
            File temporary = File.createTempFile(name, ".partial", directory);
            try {
                try (InputStream input = context.getAssets().open("embedded/" + name);
                        FileOutputStream output = new FileOutputStream(temporary)) {
                    // Keep the open descriptor writable, but remove path write access
                    // before bytes are written, as required by Android's safer DCL guidance.
                    if (!temporary.setReadOnly()) throw new Exception("Cannot protect temporary code");
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.getFD().sync();
                }
                if (!expected.equals(sha256(temporary))) throw new SecurityException("Embedded asset hash mismatch: " + name);
                if (!temporary.renameTo(target)) throw new Exception("Cannot publish embedded code");
                return target;
            } finally { if (temporary.exists()) temporary.delete(); }
        }
    }

    private static String sha256(File path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(path)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
