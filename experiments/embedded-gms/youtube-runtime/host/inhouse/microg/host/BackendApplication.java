package inhouse.microg.host;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.view.Display;
import android.webkit.WebView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;

/** Only the :inhouse_gms process uses this application; YouTube keeps its original one. */
public final class BackendApplication extends Application {
    private Context backend;

    @Override protected void attachBaseContext(Context base) {
        try {
            // Android permits one WebView data-directory owner per process. Keep
            // this stable suffix before any module initializer or login activity.
            WebView.setDataDirectorySuffix("inhouse_gms");
            // ActivityThread casts Application.getBaseContext() to ContextImpl
            // when delivering manifest broadcasts. Preserve Android's actual base;
            // delegate only module-facing operations to a separate scoped context.
            super.attachBaseContext(base);
            EmbeddedRuntime runtime = EmbeddedRuntime.open(base);
            runtime.setApplication(this);
            backend = runtime.wrap(base);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot initialize built-in Google services", failure);
        }
    }

    private Context scoped() { return backend == null ? super.getBaseContext() : backend; }

    // Keep the real Application identity for libraries which require Application,
    // while resources, storage and scheduler access remain consistently isolated.
    @Override public Context getApplicationContext() { return this; }
    @Override public Resources getResources() { return scoped().getResources(); }
    @Override public AssetManager getAssets() { return scoped().getAssets(); }
    @Override public ClassLoader getClassLoader() { return scoped().getClassLoader(); }
    @Override public Resources.Theme getTheme() { return scoped().getTheme(); }
    @Override public void setTheme(int id) { scoped().setTheme(id); }
    @Override public Object getSystemService(String name) { return scoped().getSystemService(name); }
    @Override public Context createConfigurationContext(Configuration config) {
        return scoped().createConfigurationContext(config);
    }
    @Override public Context createDisplayContext(Display display) { return scoped().createDisplayContext(display); }
    @Override public Context createDeviceProtectedStorageContext() { return scoped().createDeviceProtectedStorageContext(); }
    @Override public Context createPackageContext(String name, int flags) throws PackageManager.NameNotFoundException {
        return scoped().createPackageContext(name, flags);
    }
    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
        return scoped().getSharedPreferences(name, mode);
    }
    @Override public boolean deleteSharedPreferences(String name) { return scoped().deleteSharedPreferences(name); }
    @Override public boolean moveSharedPreferencesFrom(Context source, String name) {
        return scoped().moveSharedPreferencesFrom(source, name);
    }
    @Override public File getDatabasePath(String name) { return scoped().getDatabasePath(name); }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
        return scoped().openOrCreateDatabase(name, mode, factory);
    }
    @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
            DatabaseErrorHandler handler) { return scoped().openOrCreateDatabase(name, mode, factory, handler); }
    @Override public boolean deleteDatabase(String name) { return scoped().deleteDatabase(name); }
    @Override public boolean moveDatabaseFrom(Context source, String name) { return scoped().moveDatabaseFrom(source, name); }
    @Override public String[] databaseList() { return scoped().databaseList(); }
    @Override public File getFilesDir() { return scoped().getFilesDir(); }
    @Override public File getCacheDir() { return scoped().getCacheDir(); }
    @Override public File getCodeCacheDir() { return scoped().getCodeCacheDir(); }
    @Override public File getNoBackupFilesDir() { return scoped().getNoBackupFilesDir(); }
    @Override public File getDir(String name, int mode) { return scoped().getDir(name, mode); }
    @Override public File getFileStreamPath(String name) { return scoped().getFileStreamPath(name); }
    @Override public FileInputStream openFileInput(String name) throws FileNotFoundException {
        return scoped().openFileInput(name);
    }
    @Override public FileOutputStream openFileOutput(String name, int mode) throws FileNotFoundException {
        return scoped().openFileOutput(name, mode);
    }
    @Override public boolean deleteFile(String name) { return scoped().deleteFile(name); }
    @Override public String[] fileList() { return scoped().fileList(); }
    @Override public File getExternalFilesDir(String type) { return scoped().getExternalFilesDir(type); }
    @Override public File[] getExternalFilesDirs(String type) { return scoped().getExternalFilesDirs(type); }
    @Override public File getExternalCacheDir() { return scoped().getExternalCacheDir(); }
    @Override public File[] getExternalCacheDirs() { return scoped().getExternalCacheDirs(); }
    @Override public File[] getExternalMediaDirs() { return scoped().getExternalMediaDirs(); }
}
