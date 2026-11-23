package ch.k42.metropolis.grid.urbanGrid.ocean;

/**
 * Seeded underwater habitat zoning. These are coherent ecological patches,
 * not per-column random rolls: neighbouring columns in the same patch share
 * a habitat and its visual palette.
 */
public final class MarineHabitatModel {
    public static final int BARREN = 0;
    public static final int SEAGRASS_MEADOW = 1;
    public static final int KELP_FOREST = 2;
    public static final int CORAL_REEF = 3;
    public static final int SPONGE_GARDEN = 4;
    public static final int HYDROTHERMAL_VENTS = 5;

    private static final long SALT_MACRO = 0x4D4152494E450101L;
    private static final long SALT_MESO = 0x4D4152494E450202L;
    private static final long SALT_MICRO = 0x4D4152494E450303L;
    private static final long SALT_ROLL = 0x4D4152494E450404L;

    private MarineHabitatModel() { }

    private static long mix(long h) {
        h ^= h >>> 30;
        h *= -4658895280553007687L;
        h ^= h >>> 27;
        h *= -7723592293110705685L;
        return h ^ (h >>> 31);
    }

    private static double unit(long seed, long x, long z, long salt) {
        return (mix(seed ^ mix(x * -7046029254386353131L
                + z * -4417276706812531889L + salt)) >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) {
        t = Math.min(1.0, Math.max(0.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double patch(long seed, int x, int z, double scale, long salt) {
        double fx = x / scale;
        double fz = z / scale;
        long ix = (long) Math.floor(fx);
        long iz = (long) Math.floor(fz);
        double tx = smooth(fx - ix);
        double tz = smooth(fz - iz);
        double a = unit(seed, ix, iz, salt) * (1.0 - tx)
                + unit(seed, ix + 1, iz, salt) * tx;
        double b = unit(seed, ix, iz + 1, salt) * (1.0 - tx)
                + unit(seed, ix + 1, iz + 1, salt) * tx;
        return a * (1.0 - tz) + b * tz;
    }

    /**
     * Returns one of the five populated habitats or BARREN.
     *
     * Depth and shore distance deliberately overlap at the transitions so
     * the seabed does not form concentric, sharply bounded rings.
     */
    public static int habitat(long seed, int x, int z, int depth, double shoreDistance) {
        if (depth < 4 || depth > 48 || shoreDistance < 7.0) {
            return BARREN;
        }

        double macro = patch(seed, x, z, 224.0, SALT_MACRO);
        double meso = patch(seed, x, z, 88.0, SALT_MESO);
        double micro = patch(seed, x, z, 34.0, SALT_MICRO);
        double roll = patch(seed, x, z, 18.0, SALT_ROLL);

        // Patchy reefs live on the brighter, shallower shelf and outer shelf.
        if (depth >= 5 && depth <= 18 && shoreDistance >= 20.0
                && macro > 0.35 && macro < 0.97
                && meso > 0.34 && meso < 0.92 && micro > 0.28
                && roll < 0.84) {
            return CORAL_REEF;
        }

        // Dense kelp belts occupy mid-depth water, with large barren gaps
        // retained between forest patches.
        if (depth >= 9 && depth <= 36 && shoreDistance >= 25.0
                && macro > 0.10 && macro < 0.82
                && meso > 0.28 && micro > 0.29
                && roll < 0.91) {
            return KELP_FOREST;
        }

        // Deeper sponge fields add a different silhouette without turning the
        // whole abyss into vegetation.
        if (depth >= 16 && depth <= 42 && shoreDistance >= 42.0
                && macro > 0.14 && macro < 0.95
                && meso < 0.77 && micro > 0.36
                && roll < 0.78) {
            return SPONGE_GARDEN;
        }

        // Hydrothermal fields are deliberately rare and restricted to deep,
        // far-offshore water.
        if (depth >= 30 && depth <= 48 && shoreDistance >= 90.0
                && macro > 0.66 && meso < 0.58 && micro > 0.35
                && roll < 0.18) {
            return HYDROTHERMAL_VENTS;
        }

        // Broad meadows fill some of the nearshore and shelf gaps.
        if (depth >= 3 && depth <= 23 && shoreDistance >= 8.0
                && meso < 0.82 && micro > 0.25
                && roll < 0.86) {
            return SEAGRASS_MEADOW;
        }
        return BARREN;
    }

    /** Conservative upper bound used by the ship-hull/seabed clearance check. */
    public static int maximumFeatureHeight(int depth) {
        if (depth < 4) return 0;
        if (depth < 9) return 1;
        if (depth < 13) return 3;
        return 6;
    }
}
