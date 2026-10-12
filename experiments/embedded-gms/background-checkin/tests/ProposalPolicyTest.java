package inhouse.microg.bridge;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.CancellationException;

/** JVM invariants. Uses a local fake connection; never connects to Google or reads credentials. */
public final class ProposalPolicyTest {
    private static int checks;
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static final class FakeConnection extends HttpURLConnection {
        FakeConnection(URL url) { super(url); }
        @Override public void connect() { throw new AssertionError("No network allowed in test"); }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
    }
    private static URL fakeUrl() throws Exception {
        return new URL(null, "https://example.invalid/registration", new URLStreamHandler() {
            @Override protected URLConnection openConnection(URL url) { return new FakeConnection(url); }
        });
    }
    public static void main(String[] args) throws Exception {
        String owner = "app.revanced.android.youtube/inhouse.microg.bridge.CheckinJobService";
        String other = "app.revanced.android.youtube/androidx.work.impl.background.systemjob.SystemJobService";
        int[] reserved = {JobOwnershipPolicy.TRIGGER_JOB_ID, JobOwnershipPolicy.REGULAR_JOB_ID};
        require(reserved[0] != reserved[1], "Trigger and periodic IDs must differ");
        for (int id : reserved) {
            require(JobOwnershipPolicy.maySchedule(id, owner, null), "New owned job allowed");
            require(JobOwnershipPolicy.maySchedule(id, owner, owner), "Existing owned job allowed");
            require(!JobOwnershipPolicy.maySchedule(id, owner, other), "Never replace another service");
            require(!JobOwnershipPolicy.mayCancel(id, owner, other), "Never cancel another service");
            require(!JobOwnershipPolicy.mayCancel(id, owner, null), "Never cancel unknown ownership");
            require(JobOwnershipPolicy.mayCancel(id, owner, owner), "Owned cleanup allowed");
            require(!JobOwnershipPolicy.maySchedule(id, null, null), "No missing expected service");
            require(!JobOwnershipPolicy.maySchedule(id, " ", null), "No empty expected service");
        }
        require(!JobOwnershipPolicy.maySchedule(1, owner, null), "No unreserved ID scheduling");
        require(!JobOwnershipPolicy.mayCancel(1, owner, owner), "No unreserved ID cancellation");
        ForegroundSessionPolicy<Object> sessions = new ForegroundSessionPolicy<Object>();
        Object first = new String("same-service-class"), second = new String("same-service-class");
        require(sessions.current() == null && !sessions.isActive(first),
                "No foreground session exists before successful promotion");
        require(!sessions.destroyed(first), "Unknown destruction does not change ownership");
        sessions.promoted(first);
        require(sessions.isActive(first) && !sessions.isActive(second),
                "Session identity is the actual instance, not equal values or class name");
        sessions.promoted(second);
        require(!sessions.destroyed(first) && sessions.isActive(second),
                "A delayed old onDestroy cannot unregister the new foreground instance");
        require(sessions.destroyed(second) && !sessions.isActive(second) && sessions.current() == null,
                "Stopped session cannot receive job reconnect messages");
        boolean noSyntheticSession = false;
        try { sessions.promoted(null); } catch (IllegalArgumentException expected) { noSyntheticSession = true; }
        require(noSyntheticSession, "Missing service cannot become an active foreground session");
        URL url = fakeUrl();
        URLConnection foreground = CheckinJobNetwork.openConnection(url);
        require(foreground.getConnectTimeout() == 0 && foreground.getReadTimeout() == 0,
                "Synchronous foreground network defaults must remain unchanged");
        CheckinJobNetwork.checkpoint();
        CheckinJobNetwork.Budget job = CheckinJobNetwork.begin(120000);
        try {
            URLConnection background = CheckinJobNetwork.openConnection(url);
            require(background.getConnectTimeout() > 0 && background.getConnectTimeout() <= 20000,
                    "Job connection has bounded connect timeout");
            require(background.getReadTimeout() > 0 && background.getReadTimeout() <= 20000,
                    "Job connection has bounded read timeout");
            job.cancel();
            boolean stopped = false;
            try { CheckinJobNetwork.checkpoint(); } catch (CancellationException expected) { stopped = true; }
            require(stopped, "Cancelled job cannot cross a state-write checkpoint");
            stopped = false;
            try { CheckinJobNetwork.openConnection(url); } catch (CancellationException expected) { stopped = true; }
            require(stopped, "Cancelled job cannot start new HTTP work");
        } finally { CheckinJobNetwork.end(job); }
        URLConnection after = CheckinJobNetwork.openConnection(url);
        require(after.getConnectTimeout() == 0 && after.getReadTimeout() == 0,
                "Finishing a job restores ordinary foreground behavior on the worker");
        System.out.println(checks + " registration proposal invariants passed");
    }
}
