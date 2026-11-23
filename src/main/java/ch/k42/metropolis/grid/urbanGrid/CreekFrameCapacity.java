package ch.k42.metropolis.grid.urbanGrid;

import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;

/**
 * World-plan-only reservation permission for a creek-side district replacement
 * ring. The same cell gets the same answer whichever 64x64 tile is generated
 * first. No shoreline / NATURAL / foreign district cell may be taken by the
 * renderer's later local repartitioning step.
 */
public final class CreekFrameCapacity {
    private CreekFrameCapacity() {}

    /**
     * A replacement ring can reuse an existing public road or move inward
     * over the SAME original city block. Existing ZONE/NATURAL reservations
     * are not an unclaimed road budget: they already belong to ecology.
     */
    public static boolean allows(int plannedKind, boolean belongsToOriginalBlock) {
        return plannedKind == WorldLayout.STREET || plannedKind == WorldLayout.LANE
                || (plannedKind == WorldLayout.BLOCK && belongsToOriginalBlock);
    }
}
