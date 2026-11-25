package ch.k42.metropolis.grid.urbanGrid.provider;

/**
 * Keeps delayed branch-growth work from being registered for a disabled
 * plugin or a build whose pending accumulator has already been retired.
 */
public final class BranchGrowthTimeoutLifecyclePolicy {
    private BranchGrowthTimeoutLifecyclePolicy() {}

    public static boolean shouldScheduleTimeout(boolean pluginEnabled, boolean pendingBuildExists) {
        return pluginEnabled && pendingBuildExists;
    }
}
