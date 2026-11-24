package ch.k42.metropolis.generator;

/**
 * Pure policy for the Chunky heap valve. Kept separate from Bukkit so the
 * important 80%-baseline recovery behavior can be exercised deterministically.
 */
public final class ChunkyBackpressurePolicy {
    public static final double SOFT_PAUSE = 0.74;
    public static final double NORMAL_RESUME = 0.68;
    public static final double STABLE_RESUME_MAX = 0.82;
    public static final double HARD_PAUSE = 0.86;
    public static final int QUEUE_PAUSE = 1024;
    public static final int QUEUE_RESUME = 128;
    public static final long MIN_FREE_PROBE = 512L * 1024L * 1024L;
    public static final double MIN_FREE_FRACTION = 0.08;
    public static final long STABILITY_NS = 30_000_000_000L;
    public static final long PROBE_NS = 30_000_000_000L;

    private ChunkyBackpressurePolicy() {}

    public static int combinedQueueSize(int deferredPopulateJobs, int branchGrowthBuilds) {
        long total = (long) Math.max(0, deferredPopulateJobs) + Math.max(0, branchGrowthBuilds);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public static boolean hasStableHeadroom(double usage, long remaining, long max) {
        return max > 0L && usage <= STABLE_RESUME_MAX
                && remaining >= MIN_FREE_PROBE
                && remaining >= (long) (max * MIN_FREE_FRACTION);
    }

    public static boolean stableRecoveryReady(double usage, long remaining, long max,
                                              long stableSince, long now) {
        return hasStableHeadroom(usage, remaining, max)
                && stableSince > 0L && now - stableSince >= STABILITY_NS;
    }

    public static boolean canResume(int pending, double usage, long remaining, long max,
                                     long stableSince, long now) {
        return pending <= QUEUE_RESUME
                && (usage <= NORMAL_RESUME
                    || stableRecoveryReady(usage, remaining, max, stableSince, now));
    }

    /**
     * Once a stable high-heap recovery has succeeded, remain in that bounded
     * operating band while heap stays <=82%. Do not immediately pause again
     * merely because usage remains above the ordinary 74% soft trigger.
     */
    public static boolean shouldKeepStableMode(double usage, long remaining, long max) {
        return hasStableHeadroom(usage, remaining, max);
    }

    public static boolean shouldPause(double usage, int pending, boolean stableMode,
                                      boolean probeActive) {
        return usage >= HARD_PAUSE || pending >= QUEUE_PAUSE
                || (!stableMode && !probeActive && usage >= SOFT_PAUSE);
    }
}
