package inhouse.microg.bridge;

/** Pure identity/state gate. Only a proved, completed retirement permits replacement. */
public final class ProtocolLifecyclePolicy<T> {
    private T owner;
    private T pending;
    private boolean initialized;
    private boolean retiring;

    /** True means this caller owns the right to initialize this instance now. */
    public synchronized boolean created(T instance) {
        if (instance == null) throw new IllegalArgumentException("Missing protocol instance");
        if (owner == instance || pending == instance) return false;
        if (owner == null) {
            owner = instance;
            initialized = false;
            return true;
        }
        if (pending != null) throw new IllegalStateException("Pending protocol instance exists");
        pending = instance;
        return false;
    }

    public synchronized boolean initialized(T instance) {
        if (owner != instance || retiring) return false;
        initialized = true;
        return true;
    }

    public synchronized boolean isOwner(T instance) {
        return instance != null && owner == instance && initialized && !retiring;
    }

    /** True means exactly one retirement worker must be started for this owner. */
    public synchronized boolean destroyed(T instance) {
        if (pending == instance && instance != null) pending = null;
        if (owner != instance || instance == null || retiring) return false;
        initialized = false;
        retiring = true;
        return true;
    }

    /** Caller must first prove all old threads stopped and matching statics reset. */
    public synchronized T retired(T instance, boolean terminationAndResetProved) {
        if (!terminationAndResetProved || owner != instance || !retiring) return null;
        owner = pending;
        pending = null;
        initialized = false;
        retiring = false;
        return owner;
    }
}
