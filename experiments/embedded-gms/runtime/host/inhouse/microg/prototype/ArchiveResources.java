package inhouse.microg.prototype;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import java.io.File;
import java.io.IOException;

/** Reads an uninstalled, trusted APK's resource table through public PackageManager APIs. */
public final class ArchiveResources {
    private ArchiveResources() {}

    public static Resources load(Context host, File backendApk)
            throws IOException, PackageManager.NameNotFoundException {
        String path = backendApk.getAbsolutePath();
        PackageManager pm = host.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(path, 0);
        if (archive == null || archive.applicationInfo == null) {
            throw new IOException("Bundled resource archive cannot be parsed");
        }
        // This is an archive descriptor, never returned as the host's ApplicationInfo and
        // never supplied to authentication/UID checks. No installed package is fabricated.
        ApplicationInfo info = new ApplicationInfo(archive.applicationInfo);
        info.sourceDir = path;
        info.publicSourceDir = path;
        return pm.getResourcesForApplication(info);
    }
}
