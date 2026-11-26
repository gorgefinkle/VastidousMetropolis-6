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
    public static final int MIN_STABLE_SAMPLES = 10;
    public static final double STABILITY_DRIFT = 0.03;
    public static final long RAPID_REPAUSE_WINDOW_NS = 15_000_000_000L;
    public static final long RAPID_REPAUSE_COOLDOWN_NS = 60_000_000_000L;

    private ChunkyBackpressurePolicy() {}

    public static int combinedQueueSize(int deferredPopulateJobs, int branchGrowthBuilds) {
        long total = (long) Math.max(0, deferredPopulateJobs) + Math.max(0, branchGrowthBuilds);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Recovery is based on the post-GC live set, while free headroom is checked
     * against the instantaneous heap. This separates retained memory from
     * temporary allocation spikes without permitting a resume near exhaustion.
     */
    public static boolean hasStableHeadroom(double retainedUsage, long remaining, long max) {
        return max > 0L && retainedUsage >= 0.0 && retainedUsage <= STABLE_RESUME_MAX
                && remaining >= MIN_FREE_PROBE
                && remaining >= (long) (max * MIN_FREE_FRACTION);
    }

    /**
     * A full dwell period and repeated healthy samples are required, even when
     * the instantaneous heap briefly falls below the former 68% fast-resume
     * threshold. stableSamples also makes a zero or negative nanoTime origin
     * safe; sample count, rather than a timestamp sentinel, marks tracking.
     */
    public static boolean stableRecoveryReady(double retainedUsage, long remaining, long max,
                                              long stableSince, int stableSamples, long now) {
        return hasStableHeadroom(retainedUsage, remaining, max)
                && stableSamples >= MIN_STABLE_SAMPLES
                && now - stableSince >= STABILITY_NS;
    }

    /**
     * Every automatic resume requires stable retained heap, actual free-memory
     * headroom, a low combined deferred/growth queue, and any active cooldown
     * to have expired.
     */
    public static boolean canResume(int pending, double retainedUsage, long remaining, long max,
                                    long stableSince, int stableSamples,
                                    boolean cooldownActive, long recoveryNotBefore, long now) {
        return pending <= QUEUE_RESUME
                && (!cooldownActive || now - recoveryNotBefore >= 0L)
                && stableRecoveryReady(retainedUsage, remaining, max, stableSince, stableSamples, now);
    }

    /** Compatibility overload for the older retained-heap probe. */
    public static boolean canResume(int pending, double currentUsage, double retainedUsage,
                                    long remaining, long max, long stableSince, long now) {
        return canResume(pending, retainedUsage, remaining, max, stableSince,
                MIN_STABLE_SAMPLES, false, 0L, now);
    }

    /** Compatibility overload for callers without an explicit sample counter. */
    public static boolean stableRecoveryReady(double retainedUsage, long remaining, long max,
                                              long stableSince, long now) {
        return stableRecoveryReady(retainedUsage, remaining, max, stableSince,
                MIN_STABLE_SAMPLES, now);
    }

    public static boolean shouldApplyRapidRePauseCooldown(boolean hasResumed,
                                                          long lastResumeAt, long pauseAt) {
        if (!hasResumed) return false;
        long elapsed = pauseAt - lastResumeAt;
        return elapsed >= 0L && elapsed <= RAPID_REPAUSE_WINDOW_NS;
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
