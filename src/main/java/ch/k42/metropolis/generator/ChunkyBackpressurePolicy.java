package ch.k42.metropolis.generator;

/**
 * Pure policy for the Chunky heap valve. Kept separate from Bukkit so the
 * recovery behavior can be exercised deterministically.
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

    /**
     * Recovery is based on the post-GC live set, while free headroom is checked
     * against the instantaneous heap. This separates retained memory from
     * temporary allocation spikes without allowing recovery near exhaustion.
     */
    public static boolean hasStableHeadroom(double retainedUsage, long remaining, long max) {
        return max > 0L && retainedUsage >= 0.0 && retainedUsage <= STABLE_RESUME_MAX
                && remaining >= MIN_FREE_PROBE
                && remaining >= (long) (max * MIN_FREE_FRACTION);
    }

    public static boolean stableRecoveryReady(double retainedUsage, long remaining, long max,
                                              long stableSince, long now) {
        return hasStableHeadroom(retainedUsage, remaining, max)
                && stableSince != 0L && now - stableSince >= STABILITY_NS;
    }

    public static boolean canResume(int pending, double currentUsage, double retainedUsage,
                                    long remaining, long max, long stableSince, long now) {
        return pending <= QUEUE_RESUME
                && (currentUsage <= NORMAL_RESUME
                    || stableRecoveryReady(retainedUsage, remaining, max, stableSince, now));
    }

    /**
     * After a guarded high-retention recovery, stay in that band while the
     * post-GC live set and instantaneous free headroom remain safe.
     */
    public static boolean shouldKeepStableMode(double retainedUsage, long remaining, long max) {
        return hasStableHeadroom(retainedUsage, remaining, max);
    }

    public static boolean shouldPause(double currentUsage, double retainedUsage,
                                      long remaining, long max, int pending,
                                      boolean stableMode, boolean probeActive) {
        if (pending >= QUEUE_PAUSE) return true;
        if (stableMode) {
            return !hasStableHeadroom(retainedUsage, remaining, max);
        }
        return currentUsage >= HARD_PAUSE || (!probeActive && currentUsage >= SOFT_PAUSE);
    }
}
