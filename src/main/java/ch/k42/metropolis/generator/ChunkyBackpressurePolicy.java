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
    public static final int MIN_STABLE_SAMPLES = 10;
    public static final double STABILITY_DRIFT = 0.03;
    public static final long RAPID_REPAUSE_WINDOW_NS = 15_000_000_000L;
    public static final long RAPID_REPAUSE_COOLDOWN_NS = 60_000_000_000L;

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
                                              long stableSince, int stableSamples, long now) {
        return hasStableHeadroom(usage, remaining, max)
                && stableSince > 0L
                && stableSamples >= MIN_STABLE_SAMPLES
                && now - stableSince >= STABILITY_NS;
    }

    /**
     * Every recovery, including a heap reading below the ordinary 68% target,
     * must first demonstrate a complete stable window. A single post-GC dip
     * is not sufficient evidence that generation can safely resume.
     */
    public static boolean canResume(int pending, double usage, long remaining, long max,
                                     long stableSince, int stableSamples,
                                     long recoveryNotBefore, long now) {
        return pending <= QUEUE_RESUME
                && now >= recoveryNotBefore
                && stableRecoveryReady(usage, remaining, max, stableSince, stableSamples, now);
    }

    /** Compatibility overload: callers still have to satisfy the full window. */
    public static boolean stableRecoveryReady(double usage, long remaining, long max,
                                              long stableSince, long now) {
        return stableRecoveryReady(usage, remaining, max, stableSince, MIN_STABLE_SAMPLES, now);
    }

    /** Compatibility overload: the former <=68% shortcut is intentionally removed. */
    public static boolean canResume(int pending, double usage, long remaining, long max,
                                     long stableSince, long now) {
        return canResume(pending, usage, remaining, max, stableSince,
                MIN_STABLE_SAMPLES, 0L, now);
    }

    /** Returns a 60-second cooldown only when a protective pause quickly follows a resume. */
    public static long rapidRePauseCooldownUntil(long lastResumeAt, long pauseAt) {
        if (lastResumeAt <= 0L || pauseAt < lastResumeAt
                || pauseAt - lastResumeAt > RAPID_REPAUSE_WINDOW_NS) {
            return 0L;
        }
        return pauseAt + RAPID_REPAUSE_COOLDOWN_NS;
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
