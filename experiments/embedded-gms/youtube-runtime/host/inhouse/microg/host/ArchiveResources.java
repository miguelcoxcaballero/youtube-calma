package inhouse.microg.host;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import java.io.File;
import java.io.IOException;

/** Public archive API: this descriptor is never used as an installed identity. */
final class ArchiveResources {
    private ArchiveResources() {}

    static Resources load(Context host, File archive) throws IOException,
            PackageManager.NameNotFoundException {
        PackageManager manager = host.getPackageManager();
        PackageInfo parsed = manager.getPackageArchiveInfo(archive.getAbsolutePath(), 0);
        if (parsed == null || parsed.applicationInfo == null) {
            throw new IOException("Cannot read the built-in services resource archive");
        }
        ApplicationInfo resources = new ApplicationInfo(parsed.applicationInfo);
        resources.sourceDir = archive.getAbsolutePath();
        resources.publicSourceDir = archive.getAbsolutePath();
        return manager.getResourcesForApplication(resources);
    }
}
