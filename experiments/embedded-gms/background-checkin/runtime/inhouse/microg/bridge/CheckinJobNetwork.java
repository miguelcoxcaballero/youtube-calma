package inhouse.microg.bridge;

import android.os.SystemClock;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;

/**
 * Inactive experiment: connection limits exist only in a registration job's worker.
 * Original synchronous login calls have no ThreadLocal budget and pass through unchanged.
 * No TLS provider, verifier, trust store, request content or signature checks change.
 */
public final class CheckinJobNetwork {
    private static final ThreadLocal<Budget> ACTIVE = new ThreadLocal<Budget>();

    private CheckinJobNetwork() {}

    public static void checkpoint() {
        Budget budget = ACTIVE.get();
        if (budget != null) budget.check();
    }

    /** Replace the three audited URL.openConnection invokes, never a global network API. */
    public static URLConnection openConnection(URL url) throws IOException {
        Budget budget = ACTIVE.get();
        if (budget == null) return url.openConnection();
        budget.check();
        URLConnection connection = url.openConnection();
        if (!(connection instanceof HttpURLConnection)) {
            throw new IOException("Unsupported registration connection");
        }
        budget.track((HttpURLConnection) connection);
        return connection;
    }

    static Budget begin(long durationMillis) {
        if (ACTIVE.get() != null) throw new IllegalStateException("Registration budget already active");
        Budget budget = new Budget(durationMillis);
        ACTIVE.set(budget);
        return budget;
    }

    static void end(Budget budget) {
        if (ACTIVE.get() == budget) ACTIVE.remove();
        budget.cancel();
    }

    static final class Budget {
        private final long deadline;
        private final ArrayList<HttpURLConnection> connections = new ArrayList<HttpURLConnection>();
        private volatile boolean cancelled;

        Budget(long durationMillis) {
            deadline = SystemClock.elapsedRealtime() + durationMillis;
        }

        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()
                    || SystemClock.elapsedRealtime() >= deadline) {
                throw new CancellationException("Registration cancelled");
            }
        }

        synchronized void track(HttpURLConnection connection) {
            try {
                check();
                int remaining = (int) Math.max(1, Math.min(20000L,
                        deadline - SystemClock.elapsedRealtime()));
                connection.setConnectTimeout(remaining);
                connection.setReadTimeout(remaining);
                connections.add(connection);
            } catch (RuntimeException failure) {
                connection.disconnect();
                throw failure;
            }
        }

        void cancel() {
            final ArrayList<HttpURLConnection> closing;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                closing = new ArrayList<HttpURLConnection>(connections);
                connections.clear();
            }
            // A blocked disconnect must not block JobService's main-thread stop callback.
            if (!closing.isEmpty()) {
                Thread cleanup = new Thread(new Runnable() {
                    @Override public void run() {
                        for (HttpURLConnection connection : closing) {
                            try { connection.disconnect(); } catch (RuntimeException ignored) {}
                        }
                    }
                }, "InhouseCheckinCleanup");
                cleanup.setDaemon(true);
                cleanup.start();
            }
        }
    }
}
