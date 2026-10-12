package inhouse.microg.bridge;

/** No Android or network needed: verifies replacement cannot overtake retirement. */
public final class ProtocolLifecyclePolicyTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        ProtocolLifecyclePolicy<Object> policy = new ProtocolLifecyclePolicy<Object>();
        Object old = new Object(), cancelled = new Object(), next = new Object();
        require(policy.created(old), "First instance may initialize");
        require(!policy.isOwner(old), "Partial initialization cannot dispatch protocol");
        require(policy.initialized(old) && policy.isOwner(old), "Completed init owns protocol");
        require(policy.destroyed(old) && !policy.isOwner(old), "Destroy closes the dispatch gate");
        require(!policy.destroyed(old), "Only one retirement worker may start");
        require(!policy.created(cancelled) && !policy.isOwner(cancelled), "Replacement must wait");
        require(!policy.destroyed(cancelled), "Uninitialized replacement needs no old cleanup");
        require(!policy.created(next), "A live successor waits behind the same retirement");
        require(policy.retired(old, false) == null && !policy.isOwner(next),
                "Timeout or live old threads never grants replacement ownership");
        require(policy.retired(new Object(), true) == null, "Stale worker cannot reset ownership");
        require(policy.retired(old, true) == next && !policy.isOwner(next),
                "Only proved retirement hands out the next initialization right");
        require(!policy.initialized(old), "Old completion cannot revive retired instance");
        require(policy.initialized(next) && policy.isOwner(next), "Successor owns fresh state");
        require(!policy.destroyed(old) && policy.isOwner(next), "Late old destroy is harmless");
        System.out.println("Protocol lifecycle invariants passed");
    }
}
