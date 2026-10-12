package inhouse.microg.bridge;

import android.content.Context;
import android.content.pm.ProviderInfo;

/** Actual upstream provider implementation, not an empty mock or external-provider relay. */
public final class GServicesProviderBridge extends org.microg.gms.gservices.GServicesProvider {
    @Override public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(BackendEnvironment.wrap(context), info);
    }
}
