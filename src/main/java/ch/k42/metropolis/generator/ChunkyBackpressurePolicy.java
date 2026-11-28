package ch.k42.metropolis.generator;

/**
 * Population-only back-pressure policy for Chunky.
 *
 * Branch growth has its own event-driven queue and bounded per-tick drain.
 * It is deliberately not charged against Chunky's population queue: waiting
 * branch work must never consume population slots or hold population paused.
 *
 * Heap pause/recovery thresholds use hysteresis. Chunky pauses at 74% and may
 * only resume after heap use has fallen to 68% or lower. This prevents the
 * previous 74%-pause / 86%-resume inversion from immediately re-pausing a
 * worker that was resumed while still above the pause threshold.
 */
public final class ChunkyBackpressurePolicy {
    public static final double SOFT_PAUSE = 0.74;
    public static final double RESUME_HEAP = 0.68;
    public static final double HARD_PAUSE = 0.86;

    public static final int QUEUE_PAUSE = 1024;
    public static final int QUEUE_RESUME = 64;

    /** Diagnostic compatibility constants; branch growth no longer gates Chunky. */
    public static final int BRANCH_GROWTH_PAUSE = 128;
    public static final int BRANCH_GROWTH_RESUME = 16;
    public static final int BRANCH_GROWTH_WEIGHT = 2;

    public static final long MIN_FREE_RESUME_BYTES = 512L * 1024L * 1024L;
    public static final long QUEUE_STABILITY_NS = 10_000_000_000L;
    public static final int MIN_QUEUE_STABLE_SAMPLES = 10;
    public static final long RESUME_CONFIRM_NS = 30_000_000_000L;
    public static final long RAPID_REPAUSE_WINDOW_NS = 15_000_000_000L;
    // The stable 68% recovery band already provides strong hysteresis. Keep
    // only a brief 5-second guard after a genuine rapid re-pause, not 60s.
    public static final long RAPID_REPAUSE_COOLDOWN_NS = 5_000_000_000L;

    private ChunkyBackpressurePolicy() {}

    /** Active population work only; branch-growth jobs are never included. */
    public static int populationQueueSize(int deferredPopulateJobs) {
        return Math.max(0, deferredPopulateJobs);
    }

    /**
     * Compatibility alias for older diagnostics. The second argument is
     * intentionally ignored because branch growth owns a separate queue.
     */
    public static int weightedActiveQueueSize(int deferredPopulateJobs, int readyBranchGrowthBuilds) {
        return populationQueueSize(deferredPopulateJobs);
    }

    /** Branch growth is independent and therefore does not gate population recovery. */
    public static boolean queuesLowEnough(int deferredPopulateJobs, int readyBranchGrowthBuilds) {
        return populationQueueSize(deferredPopulateJobs) <= QUEUE_RESUME;
    }

    public static boolean queueRecoveryReady(int deferredPopulateJobs, int readyBranchGrowthBuilds,
                                             long stableSince, int stableSamples, long now) {
        return queuesLowEnough(deferredPopulateJobs, readyBranchGrowthBuilds)
                && stableSamples >= MIN_QUEUE_STABLE_SAMPLES
                && now - stableSince >= QUEUE_STABILITY_NS;
    }

    /** Resume only inside the lower hysteresis band and with an absolute safety reserve. */
    public static boolean hasEmergencyHeadroom(double currentUsage, long remaining) {
        return currentUsage <= RESUME_HEAP && remaining >= MIN_FREE_RESUME_BYTES;
    }

    public static boolean canResume(int deferredPopulateJobs, int readyBranchGrowthBuilds,
                                    double currentUsage, long remaining,
                                    long stableSince, int stableSamples,
                                    boolean cooldownActive, long recoveryNotBefore, long now) {
        return queuesLowEnough(deferredPopulateJobs, readyBranchGrowthBuilds)
                && hasEmergencyHeadroom(currentUsage, remaining)
                && (!cooldownActive || now - recoveryNotBefore >= 0L)
                && queueRecoveryReady(deferredPopulateJobs, readyBranchGrowthBuilds,
                        stableSince, stableSamples, now);
    }

    public static boolean shouldApplyRapidRePauseCooldown(boolean hasResumed,
                                                          long lastResumeAt, long pauseAt) {
        if (!hasResumed) return false;
        long elapsed = pauseAt - lastResumeAt;
        return elapsed >= 0L && elapsed <= RAPID_REPAUSE_WINDOW_NS;
    }

    /** Chunky is gated only by ordinary population backlog and heap pressure. */
    public static boolean shouldPause(double currentUsage, int deferredPopulateJobs,
                                      int readyBranchGrowthBuilds) {
        if (populationQueueSize(deferredPopulateJobs) >= QUEUE_PAUSE) return true;
        return currentUsage >= SOFT_PAUSE || currentUsage >= HARD_PAUSE;
    }
}
