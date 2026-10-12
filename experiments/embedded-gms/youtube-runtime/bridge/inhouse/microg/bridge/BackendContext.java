package inhouse.microg.bridge;

import android.content.Context;
import android.app.job.JobScheduler;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.view.Display;
import android.view.LayoutInflater;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Real installed identity, private backend storage, isolated code and resources. */
public final class BackendContext extends ContextWrapper {
    private static final String PREFIX = "inhouse.microg.";
    private static final String DIRECTORY = "inhouse-microg";
    private final Resources resources;
    private Resources.Theme theme;
    private LayoutInflater inflater;
    private JobScheduler jobScheduler;
    private Context applicationContext;

    BackendContext(Context base) {
        super(base);
        resources = new BackendResources(BackendEnvironment.resources,
                base.getResources().getDisplayMetrics(), base.getResources().getConfiguration(),
                base.getPackageName());
    }

    @Override public Resources getResources() { return resources; }
    @Override public AssetManager getAssets() { return resources.getAssets(); }
    @Override public ClassLoader getClassLoader() { return BackendEnvironment.classLoader; }
    @Override public Context getApplicationContext() {
        if (BackendEnvironment.application != null) return BackendEnvironment.application;
        // A main-process remote-library context has no separate Application instance.
        // Preserve its module resources when a client normalizes its Context, without
        // changing Android's actual YouTube Application or impersonating another app.
        if (applicationContext == null) {
            Context original = super.getApplicationContext();
            applicationContext = original == null || original == getBaseContext()
                    ? this : BackendEnvironment.wrap(original);
        }
        return applicationContext;
    }
    @Override public Resources.Theme getTheme() {
        if (theme == null) theme = resources.newTheme();
        return theme;
    }
    @Override public void setTheme(int id) { getTheme().applyStyle(id, true); }
    @Override public Object getSystemService(String name) {
        if (JOB_SCHEDULER_SERVICE.equals(name) && Build.VERSION.SDK_INT >= 34) {
            if (jobScheduler == null) {
                JobScheduler original = (JobScheduler) super.getSystemService(name);
                jobScheduler = original.forNamespace("inhouse.microg");
            }
            return jobScheduler;
        }
        if (LAYOUT_INFLATER_SERVICE.equals(name)) {
            if (inflater == null) {
                LayoutInflater original = (LayoutInflater) super.getSystemService(name);
                inflater = original.cloneInContext(this);
            }
            return inflater;
        }
        return super.getSystemService(name);
    }
    @Override public Context createConfigurationContext(Configuration configuration) {
        return BackendEnvironment.wrap(super.createConfigurationContext(configuration));
    }
    @Override public Context createDisplayContext(Display display) {
        return BackendEnvironment.wrap(super.createDisplayContext(display));
    }
    @Override public Context createDeviceProtectedStorageContext() {
        return BackendEnvironment.wrap(super.createDeviceProtectedStorageContext());
    }
    @Override public Context createPackageContext(String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        Context context = super.createPackageContext(packageName, flags);
        return getPackageName().equals(packageName) ? BackendEnvironment.wrap(context) : context;
    }

    private static String name(String name) {
        if (name == null || name.length() == 0 || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.equals(".") || name.equals("..")) {
            throw new IllegalArgumentException("Invalid backend storage name");
        }
        return name;
    }
    private static String scoped(String name) { return PREFIX + name(name); }
    private static File directory(File parent) {
        if (parent == null) return null;
        File child = new File(parent, DIRECTORY);
        if (!child.isDirectory() && !child.mkdirs()) throw new IllegalStateException("Cannot create backend storage");
        return child;
    }
    private static File[] directories(File[] parents) {
        File[] result = new File[parents.length];
        for (int i = 0; i < parents.length; i++) result[i] = directory(parents[i]);
        return result;
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(scoped(name), mode);
    }
    @Override public boolean deleteSharedPreferences(String name) {
        return super.deleteSharedPreferences(scoped(name));
    }
    private static Context storageSource(Context source) {
        if (source == BackendEnvironment.application) return BackendEnvironment.application.getBaseContext();
        return source instanceof BackendContext ? ((BackendContext) source).getBaseContext() : source;
    }
    @Override public boolean moveSharedPreferencesFrom(Context source, String name) {
        return super.moveSharedPreferencesFrom(storageSource(source), scoped(name));
    }
    private String databaseName(String supplied) {
        if (supplied == null || !new File(supplied).isAbsolute()) return scoped(supplied);
        // Room/WorkManager legitimately pass an absolute database path under the
        // context's no-backup directory. Preserve that already namespaced path;
        // never accept an absolute path into the host's other databases/files.
        try {
            File path = new File(supplied).getCanonicalFile();
            for (File root : new File[]{getNoBackupFilesDir(), getFilesDir(), getCacheDir(), getCodeCacheDir()}) {
                if (path.getPath().startsWith(root.getCanonicalPath() + File.separator)) return path.getPath();
            }
            File databases = super.getDatabasePath(PREFIX + "namespace-check").getParentFile().getCanonicalFile();
            if (databases.equals(path.getParentFile()) && path.getName().startsWith(PREFIX)) return path.getPath();
            throw new SecurityException("Absolute database path leaves backend storage");
        } catch (IOException error) { throw new IllegalArgumentException("Cannot resolve backend database path", error); }
    }
    @Override public File getDatabasePath(String name) { return super.getDatabasePath(databaseName(name)); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
            SQLiteDatabase.CursorFactory factory) {
        return super.openOrCreateDatabase(databaseName(name), mode, factory);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
            SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler errorHandler) {
        return super.openOrCreateDatabase(databaseName(name), mode, factory, errorHandler);
    }
    @Override public boolean deleteDatabase(String name) { return super.deleteDatabase(databaseName(name)); }
    @Override public boolean moveDatabaseFrom(Context source, String name) {
        return super.moveDatabaseFrom(storageSource(source), scoped(name));
    }
    @Override public String[] databaseList() {
        List<String> names = new ArrayList<>();
        for (String name : super.databaseList()) {
            if (name.startsWith(PREFIX)) names.add(name.substring(PREFIX.length()));
        }
        return names.toArray(new String[0]);
    }
    @Override public File getFilesDir() { return directory(super.getFilesDir()); }
    @Override public File getCacheDir() { return directory(super.getCacheDir()); }
    @Override public File getCodeCacheDir() { return directory(super.getCodeCacheDir()); }
    @Override public File getNoBackupFilesDir() { return directory(super.getNoBackupFilesDir()); }
    @Override public File getDir(String name, int mode) { return super.getDir(scoped(name), mode); }
    @Override public File getFileStreamPath(String name) { return new File(getFilesDir(), name(name)); }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return new FileInputStream(getFileStreamPath(name));
    }
    @Override public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        if ((mode & ~MODE_APPEND) != 0) throw new IllegalArgumentException("Backend files must remain private");
        return new FileOutputStream(getFileStreamPath(name), (mode & MODE_APPEND) != 0);
    }
    @Override public boolean deleteFile(String name) { return getFileStreamPath(name).delete(); }
    @Override public String[] fileList() {
        String[] names = getFilesDir().list();
        return names == null ? new String[0] : names;
    }
    @Override public File getExternalFilesDir(String type) { return directory(super.getExternalFilesDir(type)); }
    @Override public File[] getExternalFilesDirs(String type) { return directories(super.getExternalFilesDirs(type)); }
    @Override public File getExternalCacheDir() { return directory(super.getExternalCacheDir()); }
    @Override public File[] getExternalCacheDirs() { return directories(super.getExternalCacheDirs()); }
    @Override public File[] getExternalMediaDirs() { return directories(super.getExternalMediaDirs()); }
}
