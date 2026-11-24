package ch.k42.metropolis.grid.urbanGrid.provider;

/** Guards against reintroducing the one-build/one-chunk-per-tick throttle. */
public final class BranchGrowthDrainThroughputProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(BranchGrowthDrainThroughputPolicy.MAX_BUILDS_PER_TICK > 1,
                "drain is capped at one build per tick");
        check(BranchGrowthDrainThroughputPolicy.MAX_SCANS_PER_TICK >= 64,
                "queue scan cap is too small to find ready work in a deep queue");
        check(BranchGrowthDrainThroughputPolicy.GENERATED_CHUNK_LOADS_PER_TICK > 1,
                "generated chunk loading is still capped at one attempt per tick");
        check(BranchGrowthDrainThroughputPolicy.shouldContinue(5, 0, 0, Long.MAX_VALUE),
                "first viable job must be allowed to make progress");
        check(!BranchGrowthDrainThroughputPolicy.shouldContinue(5, 1, 1,
                BranchGrowthDrainThroughputPolicy.SOFT_WORK_BUDGET_NS),
                "soft budget must stop scanning after the first visited job consumes the budget");
        check(BranchGrowthDrainThroughputPolicy.remainingChunkLoadBudget(0) > 1,
                "chunk-load work budget collapsed to one");
        check(BranchGrowthDrainThroughputPolicy.remainingChunkLoadBudget(
                BranchGrowthDrainThroughputPolicy.GENERATED_CHUNK_LOADS_PER_TICK) == 0,
                "chunk-load budget does not close at its bound");
        System.out.println("BranchGrowthDrainThroughputProbe: PASS (multi-build, multi-load tick budget)");
    }
}
