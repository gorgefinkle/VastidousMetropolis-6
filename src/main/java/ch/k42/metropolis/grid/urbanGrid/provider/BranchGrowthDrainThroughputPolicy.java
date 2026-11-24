package ch.k42.metropolis.grid.urbanGrid.provider;

/** Pure scheduling limits for the main-thread branch-growth drain. */
public final class BranchGrowthDrainThroughputPolicy {
    public static final int MAX_BUILDS_PER_TICK = 64;
    public static final int MAX_SCANS_PER_TICK = 256;
    public static final int GENERATED_CHUNK_LOADS_PER_TICK = 8;
    public static final long SOFT_WORK_BUDGET_NS = 8_000_000L;

    private BranchGrowthDrainThroughputPolicy() {}

    /** Always allow one queued job to be inspected; then enforce the time and safety limits. */
    public static boolean shouldContinue(int queueDepth, int scanned, int progressed, long elapsedNanos) {
        return queueDepth > 0
                && scanned < MAX_SCANS_PER_TICK
                && progressed < MAX_BUILDS_PER_TICK
                && (scanned == 0 || elapsedNanos < SOFT_WORK_BUDGET_NS);
    }

    public static int remainingChunkLoadBudget(int attemptedThisTick) {
        return Math.max(0, GENERATED_CHUNK_LOADS_PER_TICK - attemptedThisTick);
    }
}
