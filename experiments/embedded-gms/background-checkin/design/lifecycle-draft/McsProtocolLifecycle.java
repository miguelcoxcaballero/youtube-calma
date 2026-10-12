package inhouse.microg.bridge;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.CountDownLatch;
import org.microg.gms.gcm.McsService;

/**
 * Inactive lifecycle proposal. A successor never reuses statics while old protocol
 * threads/callbacks can still access them. No joins or socket closes run on main.
 */
public final class McsProtocolLifecycle {
    private static final String TAG = "InhouseMessaging";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ProtocolLifecyclePolicy<McsService> POLICY =
            new ProtocolLifecyclePolicy<McsService>();

    private McsProtocolLifecycle() {}

    /** Called from real onCreate after Service.onCreate, on the Android main thread. */
    public static void created(McsService service) {
        try {
            if (POLICY.created(service)) initialize(service);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Messaging protocol initialization deferred");
            service.stopSelf();
        }
    }

    /** Also gates ordinary command dispatch and every new handleMessage callback. */
    public static boolean isOwner(McsService service) { return POLICY.isOwner(service); }

    /** Called by real onDestroy; the wrapper calls Service.onDestroy immediately. */
    public static void destroyed(final McsService service) {
        McsSessionCoordinator.onDestroy(service);
        if (!POLICY.destroyed(service)) return; // Pending uninitialized instances own no resources.
        Thread worker = new Thread(new Runnable() {
            @Override public void run() { retire(service); }
        }, "InhouseMcsRetire");
        try { worker.start(); }
        catch (RuntimeException failure) {
            // Do not initialize a successor if retirement could not be proved.
            Log.w(TAG, "Messaging protocol retirement deferred");
        }
    }

    private static void initialize(McsService service) {
        try {
            service.inhouseInitializeProtocol();
            if (POLICY.initialized(service)) McsSessionCoordinator.onProtocolReady(service);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Messaging protocol initialization failed");
            service.stopSelf();
        } catch (LinkageError failure) {
            Log.w(TAG, "Messaging protocol bridge unavailable");
            service.stopSelf();
        }
    }

    private static void retire(final McsService service) {
        try {
            final Thread rootThread = service.inhouseProtocolThread();
            Handler found = service.inhouseProtocolHandler();
            // Immediate stop can beat the custom HandlerThread's Looper setup.
            // Wait only here, off-main. No replacement gets ownership during this wait.
            while (found == null && rootThread != null && rootThread.isAlive()) {
                Thread.sleep(20L);
                found = service.inhouseProtocolHandler();
            }
            final Handler rootHandler = found;
            final Retirement captured = new Retirement();
            ProtocolTask close = new ProtocolTask() {
                @Override void body() {
                    // This barrier runs AFTER a current root-handler connect callback.
                    // Capturing at onDestroy could miss streams published by that connect.
                    captured.input = service.inhouseInputThread();
                    captured.output = service.inhouseOutputThread();
                    service.inhouseCloseProtocol();
                }
            };
            if (!runAfterCurrentCallback(rootHandler, rootThread, close)) return;

            // Input and output invoke Handler.dispatchMessage synchronously. Their
            // current callbacks must return before the old database can be closed.
            join(captured.input);
            if (captured.output != null) {
                while (captured.output.isAlive()) {
                    if (service.inhouseOutputThread() != captured.output) {
                        Log.w(TAG, "Messaging output ownership changed during retirement");
                        return;
                    }
                    Handler outputHandler = service.inhouseOutputHandler();
                    if (outputHandler != null) {
                        // Upstream close() can precede myHandler initialization. Its
                        // closed=true flag alone does not then stop the later Looper.
                        outputHandler.getLooper().quit();
                        break;
                    }
                    Thread.sleep(20L);
                }
                join(captured.output);
            }

            ProtocolTask destroy = new ProtocolTask() {
                @Override void body() {
                    try { service.inhouseDestroyProtocol(); }
                    finally { if (rootHandler != null) rootHandler.getLooper().quit(); }
                }
            };
            boolean cleaned = runAfterCurrentCallback(rootHandler, rootThread, destroy);
            join(rootThread);
            if (!cleaned) return;
            if (service.inhouseInputThread() != captured.input
                    || service.inhouseOutputThread() != captured.output
                    || (captured.input != null && captured.input.isAlive())
                    || (captured.output != null && captured.output.isAlive())
                    || !service.inhouseResetProtocolAfter(rootThread)) {
                Log.w(TAG, "Messaging protocol retirement ownership mismatch");
                return;
            }
            MAIN.post(new Runnable() {
                @Override public void run() {
                    McsService pending = POLICY.retired(service, true);
                    if (pending != null) initialize(pending);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Messaging protocol retirement interrupted");
        } catch (RuntimeException failure) {
            Log.w(TAG, "Messaging protocol retirement incomplete");
        } catch (LinkageError failure) {
            Log.w(TAG, "Messaging protocol retirement bridge unavailable");
        }
        // An unbounded old connect/IO operation can delay this worker indefinitely.
        // A timeout must never erase live statics or grant a successor ownership.
    }

    private static boolean runAfterCurrentCallback(Handler handler, Thread thread,
            ProtocolTask task) throws InterruptedException {
        if (handler != null && thread != null && thread.isAlive() && handler.post(task)) {
            task.done.await();
        } else {
            // A dead/quitting Looper cannot execute cleanup. Prove its thread has
            // stopped before doing that same cleanup on this retirement worker.
            join(thread);
            task.run();
        }
        return task.succeeded;
    }

    private static void join(Thread thread) throws InterruptedException {
        if (thread == null) return;
        if (thread == Thread.currentThread()) throw new IllegalStateException("Cannot retire current thread");
        thread.join();
    }

    private static final class Retirement {
        Thread input;
        Thread output;
    }

    private abstract static class ProtocolTask implements Runnable {
        final CountDownLatch done = new CountDownLatch(1);
        volatile boolean succeeded;
        abstract void body();
        @Override public final void run() {
            try {
                body();
                succeeded = true;
            } catch (RuntimeException failure) {
                Log.w(TAG, "Messaging retirement callback failed");
            } catch (LinkageError failure) {
                Log.w(TAG, "Messaging retirement callback unavailable");
            } finally { done.countDown(); }
        }
    }
}
