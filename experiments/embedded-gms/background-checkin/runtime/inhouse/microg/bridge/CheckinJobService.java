package inhouse.microg.bridge;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.microg.gms.auth.AuthConstants;
import org.microg.gms.checkin.CheckinManager;
import org.microg.gms.checkin.CheckinPreferences;
import org.microg.gms.checkin.LastCheckinInfo;
import org.microg.gms.people.PeopleManager;

/**
 * Inactive API35 job template, created by the isolated backend AppComponentFactory.
 * Account permission checks and all checkin payload/state logic remain upstream's.
 * No JobService or alarm creates an exemption from Android background FGS restrictions.
 */
public final class CheckinJobService extends JobService {
    private static final String TAG = "InhouseCheckin";
    private static final long MAX_RUN_MILLIS = 120000L;
    // Retain exclusion until the worker really exits, even after Android stops its job.
    private static final AtomicBoolean WORKER_ACTIVE = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Run active;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(BackendEnvironment.wrap(base));
    }

    @Override public boolean onStartJob(final JobParameters parameters) {
        if (!JobOwnershipPolicy.isReserved(parameters.getJobId())) {
            Log.w(TAG, "Unexpected registration job");
            return false;
        }
        try {
            if (!CheckinPreferences.isEnabled(this)) {
                CheckinJobScheduler.cancelIfDisabled(this);
                return false;
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Registration settings unavailable");
            return false;
        }
        if (!WORKER_ACTIVE.compareAndSet(false, true)) {
            jobFinished(parameters, true);
            return true;
        }
        final Run run = new Run(parameters);
        active = run;
        run.timeout = new Runnable() {
            @Override public void run() {
                run.cancel();
                finish(run, true);
            }
        };
        run.worker = new Thread(new Runnable() {
            @Override public void run() {
                boolean retry = false;
                boolean registrationSaved = false;
                CheckinJobNetwork.Budget budget = null;
                try {
                    if (run.cancelled.get()) throw new CancellationException();
                    budget = CheckinJobNetwork.begin(MAX_RUN_MILLIS);
                    run.budget = budget;
                    if (run.cancelled.get()) budget.cancel();
                    CheckinJobNetwork.checkpoint();
                    if (CheckinPreferences.isEnabled(CheckinJobService.this)) {
                        boolean force = parameters.getJobId() == JobOwnershipPolicy.TRIGGER_JOB_ID
                                && parameters.getExtras().getBoolean(CheckinJobScheduler.EXTRA_FORCE, false);
                        LastCheckinInfo info = CheckinManager.checkin(CheckinJobService.this, force);
                        CheckinJobNetwork.checkpoint();
                        registrationSaved = info != null;
                        // Signal an already-promoted MCS session immediately; profile HTTP must
                        // not hold up messaging. The coordinator never starts a service here.
                        McsSessionCoordinator.onCheckinComplete(CheckinJobService.this);
                        if (registrationSaved) finishSuccessfulCheckin();
                    }
                } catch (CancellationException cancelled) {
                    retry = !registrationSaved;
                } catch (IOException failure) {
                    retry = !registrationSaved;
                    Log.w(TAG, "Registration network attempt deferred");
                } catch (RuntimeException failure) {
                    Log.w(TAG, "Registration configuration error");
                } catch (LinkageError failure) {
                    Log.w(TAG, "Registration component unavailable");
                } finally {
                    if (budget != null) CheckinJobNetwork.end(budget);
                    run.budget = null;
                    WORKER_ACTIVE.set(false);
                    final boolean needsRetry = retry;
                    main.post(new Runnable() {
                        @Override public void run() { finish(run, needsRetry); }
                    });
                }
            }
        }, "InhouseCheckin");
        main.postDelayed(run.timeout, MAX_RUN_MILLIS);
        try {
            run.worker.start();
        } catch (RuntimeException failure) {
            main.removeCallbacks(run.timeout);
            active = null;
            WORKER_ACTIVE.set(false);
            Log.w(TAG, "Registration worker unavailable");
            return false;
        }
        return true;
    }

    private void finishSuccessfulCheckin() {
        // Same account type/permission path as CheckinService; the prepared backend owns it.
        CheckinJobNetwork.checkpoint();
        Account[] accounts = AccountManager.get(this).getAccountsByType(AuthConstants.DEFAULT_ACCOUNT_TYPE);
        for (Account account : accounts) {
            CheckinJobNetwork.checkpoint();
            PeopleManager.loadUserInfo(this, account);
            CheckinJobNetwork.checkpoint();
        }
        CheckinJobNetwork.checkpoint();
    }

    private void finish(Run run, boolean retry) {
        if (!run.finished.compareAndSet(false, true)) return;
        main.removeCallbacks(run.timeout);
        if (active == run) active = null;
        jobFinished(run.parameters, retry);
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Run run = active;
        if (run != null && run.parameters.getJobId() == parameters.getJobId()) {
            run.finished.set(true);
            main.removeCallbacks(run.timeout);
            run.cancel();
            active = null;
        }
        try { return CheckinPreferences.isEnabled(this); }
        catch (RuntimeException failure) { return false; }
    }

    @Override public void onDestroy() {
        Run run = active;
        if (run != null) {
            run.finished.set(true);
            main.removeCallbacks(run.timeout);
            run.cancel();
            active = null;
        }
        super.onDestroy();
    }

    private static final class Run {
        final JobParameters parameters;
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile CheckinJobNetwork.Budget budget;
        Thread worker;
        Runnable timeout;
        Run(JobParameters parameters) { this.parameters = parameters; }
        void cancel() {
            cancelled.set(true);
            CheckinJobNetwork.Budget current = budget;
            if (current != null) current.cancel();
            if (worker != null) worker.interrupt();
        }
    }
}
