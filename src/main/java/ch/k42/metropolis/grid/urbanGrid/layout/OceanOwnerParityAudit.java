package ch.k42.metropolis.grid.urbanGrid.layout;

/** Restored from the R30ea member/bytecode reference; not an October 31 source. */
public final class OceanOwnerParityAudit {
    private OceanOwnerParityAudit() {}

    public enum Result {
        NOT_OCEAN_PARCEL,
        ACTUAL_OCEAN,
        LATTICE_ONLY_SHORE,
        BORROWED_SHORE_ZONE,
        BORROWED_OVER_PLANNED_ROAD,
        BORROWED_OVER_PLANNED_BUILDING,
        BORROWED_OTHER,
        CROSS_WATER_OWNER
    }

    public static Result classify(boolean oceanParcel, WaterField.Kind local,
                                  WaterField.Kind lattice, int plannedOwner) {
        if (!oceanParcel) return Result.NOT_OCEAN_PARCEL;
        if (local == WaterField.Kind.OCEAN) return Result.ACTUAL_OCEAN;
        if (local == WaterField.Kind.RIVER || local == WaterField.Kind.CREEK)
            return Result.CROSS_WATER_OWNER;
        if (lattice == WaterField.Kind.OCEAN) return Result.LATTICE_ONLY_SHORE;
        if (plannedOwner == 2 || plannedOwner == 1) return Result.BORROWED_OVER_PLANNED_ROAD;
        if (plannedOwner == 3) return Result.BORROWED_OVER_PLANNED_BUILDING;
        if (plannedOwner == 4) return Result.BORROWED_SHORE_ZONE;
        return Result.BORROWED_OTHER;
    }

    public static boolean isUnexpected(Result result) {
        return result == Result.BORROWED_OVER_PLANNED_ROAD
            || result == Result.BORROWED_OVER_PLANNED_BUILDING
            || result == Result.CROSS_WATER_OWNER;
    }
}
