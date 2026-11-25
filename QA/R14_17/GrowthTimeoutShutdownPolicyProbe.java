package ch.k42.metropolis.grid.urbanGrid.provider;

/** Regression probe for delayed branch-growth callbacks during plugin shutdown. */
public final class GrowthTimeoutShutdownPolicyProbe {
    public static void main(String[] args) {
        if (!BranchGrowthTimeoutLifecyclePolicy.shouldScheduleTimeout(true, true)) {
            throw new AssertionError("active plugin with pending build must retain its watchdog");
        }
        if (BranchGrowthTimeoutLifecyclePolicy.shouldScheduleTimeout(false, true)) {
            throw new AssertionError("disabled plugin must not schedule another watchdog");
        }
        if (BranchGrowthTimeoutLifecyclePolicy.shouldScheduleTimeout(true, false)) {
            throw new AssertionError("retired build must not schedule another watchdog");
        }
        if (BranchGrowthTimeoutLifecyclePolicy.shouldScheduleTimeout(false, false)) {
            throw new AssertionError("disabled plugin and retired build must not schedule");
        }
        System.out.println("GrowthTimeoutShutdownPolicyProbe: PASS (enabled/pending retained; disabled/retired never rescheduled)");
    }
}
