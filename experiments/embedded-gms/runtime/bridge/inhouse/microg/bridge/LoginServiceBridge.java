package inhouse.microg.bridge;

import android.content.Context;

/** Actual upstream Android account authenticator. Host must supply authenticator XML. */
public final class LoginServiceBridge extends org.microg.gms.auth.loginservice.GoogleLoginService {
    @Override protected void attachBaseContext(Context context) {
        super.attachBaseContext(BackendEnvironment.wrap(context));
    }
}
