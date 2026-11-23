package ch.k42.metropolis.grid.urbanGrid.layout;

/**
 * One-way parcel ownership rule for already accepted public roads.  This rule
 * only arbitrates late writes to a road, not what is a road in the first place.
 * WorldLayout/PlanRegions remains authoritative for same-context Morphomos
 * dissolution, so a street between two farms may still be reclaimed.
 */
public final class RoadParcelOwnership {
    private RoadParcelOwnership() { }

    /**
     * Return true if a later parcel writer must not replace the current road.
     * An accepted city/creek road and a highway are public infrastructure;
     * a painter may convert them to another street/bridge/highway parcel, but
     * never to a forest, river, ocean, building or fallback parcel.  A road
     * that the planner deliberately dissolved is not protected by this rule.
     */
    public static boolean rejectReplacement(boolean existingStreet,
                                            boolean newStreet,
                                            boolean plannedStreet,
                                            boolean acceptedCreekRoad,
                                            boolean plannedHighway) {
        return existingStreet && !newStreet
                && (plannedStreet || acceptedCreekRoad || plannedHighway);
    }
}
