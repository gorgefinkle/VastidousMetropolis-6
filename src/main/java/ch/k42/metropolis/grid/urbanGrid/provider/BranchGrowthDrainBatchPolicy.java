package ch.k42.metropolis.grid.urbanGrid.provider;

/** Pure policy for processing support chunks in bounded ticket windows. */
public final class BranchGrowthDrainBatchPolicy {
    public static final int MAX_CHUNKS_PER_BATCH = 128;

    private BranchGrowthDrainBatchPolicy() {}

    public static boolean shouldFlushBatch(boolean allWaitingChunksLoaded,
                                           int loadedWaitingChunks, int retainedTickets,
                                           boolean stagingBlockedByRetry) {
        return allWaitingChunksLoaded
                || (loadedWaitingChunks > 0
                    && (retainedTickets >= MAX_CHUNKS_PER_BATCH || stagingBlockedByRetry));
    }

    public static int batchSize(int loadedWaitingChunks) {
        return Math.max(0, Math.min(MAX_CHUNKS_PER_BATCH, loadedWaitingChunks));
    }
}
