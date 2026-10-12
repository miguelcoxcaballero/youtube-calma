package inhouse.microg.bridge;

import java.lang.ref.WeakReference;

/** Pure identity gate; callers may mark an instance only after real platform promotion. */
public final class ForegroundSessionPolicy<T> {
    private WeakReference<T> active = new WeakReference<T>(null);

    public synchronized void promoted(T instance) {
        if (instance == null) throw new IllegalArgumentException("Missing foreground instance");
        active = new WeakReference<T>(instance);
    }

    public synchronized T current() { return active.get(); }

    public synchronized boolean isActive(T instance) {
        return instance != null && active.get() == instance;
    }

    public synchronized boolean destroyed(T instance) {
        if (!isActive(instance)) return false;
        active.clear();
        return true;
    }
}
