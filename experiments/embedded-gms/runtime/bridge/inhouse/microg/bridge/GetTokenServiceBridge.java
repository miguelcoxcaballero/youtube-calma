package inhouse.microg.bridge;

import android.content.Context;

/** Actual upstream IAuthManagerService binder. No claim of successful Google login. */
public final class GetTokenServiceBridge extends com.google.android.gms.auth.GetToken {
    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(BackendEnvironment.wrap(context));
    }
}
