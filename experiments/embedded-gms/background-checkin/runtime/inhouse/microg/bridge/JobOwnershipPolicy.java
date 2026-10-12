package inhouse.microg.bridge;

/** Pure Java policy: never replace or cancel another component's job. */
public final class JobOwnershipPolicy {
    public static final int TRIGGER_JOB_ID = 0x49484301;
    public static final int REGULAR_JOB_ID = 0x49484302;

    private JobOwnershipPolicy() {}

    public static boolean isReserved(int jobId) {
        return jobId == TRIGGER_JOB_ID || jobId == REGULAR_JOB_ID;
    }

    public static boolean maySchedule(int jobId, String expectedOwner, String existingOwner) {
        return isReserved(jobId) && valid(expectedOwner)
                && (existingOwner == null || expectedOwner.equals(existingOwner));
    }

    public static boolean mayCancel(int jobId, String expectedOwner, String existingOwner) {
        return isReserved(jobId) && valid(expectedOwner) && expectedOwner.equals(existingOwner);
    }

    private static boolean valid(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
