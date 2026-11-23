package ch.k42.metropolis.grid.urbanGrid;

import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;

/**
 * During the normal parcel-ownership handoff, a later creek-frame commitment
 * supersedes an earlier countryside plan to dissolve that exact street.
 * No unaccepted planned street is protected by this rule.
 */
public final class CreekRoadOwnership {
    private CreekRoadOwnership() {}
    public static boolean preservesStreet(int plannedKind, boolean dissolved,
                                          boolean committedCreekRoad) {
        return committedCreekRoad && (plannedKind == WorldLayout.BLOCK
                || (plannedKind == WorldLayout.STREET && dissolved));
    }

    /** A creek-local road supersedes the obsolete branch classification.
     * Its ownership was accepted before population and must not be retired.
     */
    public static boolean protectsFromPrune(int plannedKind, boolean cityRoad,
                                            boolean committedCreekRoad) {
        return committedCreekRoad || cityRoad || plannedKind == WorldLayout.STREET
                || plannedKind == WorldLayout.LANE;
    }
}
