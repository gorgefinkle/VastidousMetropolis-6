package ch.k42.metropolis.grid.urbanGrid.provider;

/** Verifies the ticket-window drain cannot deadlock at 128 chunks. */
public final class BranchGrowthDrainBatchProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(!BranchGrowthDrainBatchPolicy.shouldFlushBatch(false, 0, 128, false),
                "a full ticket window with no loaded work must wait, not spin");
        check(BranchGrowthDrainBatchPolicy.shouldFlushBatch(false, 1, 128, false),
                "a full ticket window with loaded work must release a partial batch");
        check(BranchGrowthDrainBatchPolicy.shouldFlushBatch(false, 1, 16, true),
                "loaded chunks must not sit idle through a failed-load retry cooldown");
        check(!BranchGrowthDrainBatchPolicy.shouldFlushBatch(false, 1, 16, false),
                "a partial batch must continue staging while more loads are being attempted");
        check(BranchGrowthDrainBatchPolicy.shouldFlushBatch(true, 128, 128, false),
                "a fully resident build may flush without requiring more tickets");
        int remaining = 300, batches = 0, drained = 0;
        while (remaining > 0) {
            int next = BranchGrowthDrainBatchPolicy.batchSize(remaining);
            check(next > 0 && next <= 128, "batch exceeded the ticket window");
            remaining -= next;
            drained += next;
            batches++;
        }
        check(drained == 300 && batches == 3, "300 chunks should drain as 128 + 128 + 44");
        System.out.println("BranchGrowthDrainBatchProbe: PASS (300 chunks drain through 128-ticket windows)");
    }
}
