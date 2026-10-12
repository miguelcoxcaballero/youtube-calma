package inhouse.microg.bridge;
/** Compilation only. Never package stubs into the bridge DEX. */
public final class BackendEnvironment {
    public static android.content.Context wrap(android.content.Context context) { throw new AssertionError(); }
    public static void startForeground(android.app.Service service, int id,
            android.app.Notification notification) { throw new AssertionError(); }
}
