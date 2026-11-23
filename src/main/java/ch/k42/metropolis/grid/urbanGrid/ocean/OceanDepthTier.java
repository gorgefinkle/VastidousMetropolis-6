package ch.k42.metropolis.grid.urbanGrid.ocean;

/**
 * Depth brackets for ocean parcel water columns, used to gate
 * depth-dependent boat/ship schematics.
 *
 * The brackets follow the three depth zones of the sea floor (see
 * OceanConfig): SHORE is the shallow shelf, MID the ordinary open sea, DEEP
 * the trench in the middle. The cut-offs sit halfway between neighbouring zone
 * depths, so they follow whatever depths are configured:
 * SHORE up to OceanConfig.getShallowTierMaxDepth(), DEEP from
 * OceanConfig.getDeepTierMinDepth(), MID in between.
 */
public enum OceanDepthTier {
    SHORE("shore"),
    MID("mid"),
    DEEP("deep");

    public final String string;

    OceanDepthTier(String string) {
        this.string = string;
    }

    /** Map a water column depth in blocks to its tier. */
    public static OceanDepthTier getTier(int waterDepth) {
        if (waterDepth <= OceanConfig.getShallowTierMaxDepth()) return SHORE;
        if (waterDepth >= OceanConfig.getDeepTierMinDepth()) return DEEP;
        return MID;
    }

    /**
     * Reverse lookup: get enum by string (e.g. "shore" -> SHORE). "shallow" is
     * accepted as an alias of "shore"; matching ignores case.
     */
    public static OceanDepthTier getByString(String string) {
        if (string == null) {
            return null;
        }
        String s = string.trim();
        if (s.equalsIgnoreCase("shallow")) {
            return SHORE;
        }
        for (OceanDepthTier tier : values()) {
            if (tier.string.equalsIgnoreCase(s)) {
                return tier;
            }
        }
        return null;
    }

    /** Check if the given depth falls in this tier. */
    public boolean includes(int waterDepth) {
        return getTier(waterDepth) == this;
    }
}