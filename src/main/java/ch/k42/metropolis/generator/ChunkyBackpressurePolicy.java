package ch.k42.metropolis.generator;

/**
 * Queue-first recovery policy for the Chunky back-pressure valve.
 *
 * Ready branch-growth work is weighted more heavily than deferred chunk jobs.
 * Growth still waiting for future parcel placement or missing support chunks
 * is deliberately excluded from the ready count: Chunky may need to generate
 * those chunks before that work can become runnable.
 */
public final class ChunkyBackpressurePolicy {
    public static final double SOFT_PAUSE = 0.74;
    public static final double HARD_PAUSE = 0.86;

    public static final int QUEUE_PAUSE = 1024;
    public static final int BRANCH_GROWTH_PAUSE = 128;
    public static final int QUEUE_RESUME = 64;
    public static final int BRANCH_GROWTH_RESUME = 16;
    public static final int BRANCH_GROWTH_WEIGHT = 2;

    /** Absolute emergency floor; no percentage-of-max-heap recovery gate. */
    public static final long MIN_FREE_RESUME_BYTES = 512L * 1024L * 1024L;

    /** The queue must stay below its low-water marks for ten monitor samples. */
    public static final long QUEUE_STABILITY_NS = 10_000_000_000L;
    public static final int MIN_QUEUE_STABLE_SAMPLES = 10;

    public static final long RESUME_CONFIRM_NS = 30_000_000_000L;
    public static final long RAPID_REPAUSE_WINDOW_NS = 15_000_000_000L;
    public static final long RAPID_REPAUSE_COOLDOWN_NS = 60_000_000_000L;

    private ChunkyBackpressurePolicy() {}

    /**
     * Weighted backlog of work that can actually be drained now.
     * One ready branch-growth build counts as two deferred chunk jobs.
     */
    public static int weightedActiveQueueSize(int deferredPopulateJobs, int readyBranchGrowthBuilds) {
        long total = (long) Math.max(0, deferredPopulateJobs)
                + (long) Math.max(0, readyBranchGrowthBuilds) * BRANCH_GROWTH_WEIGHT;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Recovery is intentionally asymmetric: branch growth has its own tighter
     * low-water mark, while the combined weighted active queue must also be low.
     */
    public static boolean queuesLowEnough(int deferredPopulateJobs, int readyBranchGrowthBuilds) {
        return Math.max(0, deferredPopulateJobs) <= QUEUE_RESUME
                && Math.max(0, readyBranchGrowthBuilds) <= BRANCH_GROWTH_RESUME
                && weightedActiveQueueSize(deferredPopulateJobs, readyBranchGrowthBuilds) <= QUEUE_RESUME;
    }

    public static boolean queueRecoveryReady(int deferredPopulateJobs, int readyBranchGrowthBuilds,
                                             long stableSince, int stableSamples, long now) {
        return queuesLowEnough(deferredPopulateJobs, readyBranchGrowthBuilds)
                && stableSamples >= MIN_QUEUE_STABLE_SAMPLES
                && now - stableSince >= QUEUE_STABILITY_NS;
    }

    /**
     * Queue depth is the primary recovery signal. Heap use is only an emergency
     * boundary: require the absolute free-memory floor and stay below the hard
     * pause ceiling. Post-GC retained-heap percentage is not a resume gate.
     */
    public static boolean hasEmergencyHeadroom(double currentUsage, long remaining) {
        return currentUsage < HARD_PAUSE && remaining >= MIN_FREE_RESUME_BYTES;
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

    /**
     * Stop feeding new chunks when a meaningful ready branch backlog has built
     * up, or when the weighted active queue becomes large. The ordinary heap
     * thresholds remain protective pause triggers, not recovery targets.
     */
    public static boolean shouldPause(double currentUsage, int deferredPopulateJobs,
                                      int readyBranchGrowthBuilds) {
        if (readyBranchGrowthBuilds >= BRANCH_GROWTH_PAUSE) return true;
        if (weightedActiveQueueSize(deferredPopulateJobs, readyBranchGrowthBuilds) >= QUEUE_PAUSE) {
            return true;
        }
        return currentUsage >= SOFT_PAUSE || currentUsage >= HARD_PAUSE;
    }
}
