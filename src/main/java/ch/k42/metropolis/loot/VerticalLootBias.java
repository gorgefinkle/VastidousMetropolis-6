package ch.k42.metropolis.loot;

import ch.k42.metropolis.minions.GridRandom;

/**
 * VerticalLootBias
 *
 * Biases chest loot level selection by a schematic's placement Y relative
 * to buildHeight (the street baseline): low floors skew toward
 * lootMinLevel, high floors skew toward lootMaxLevel, with a smooth blend
 * as height moves from 0 to maxDistance blocks away from street level.
 *
 * Blend factor t = clamp( |schematicBaseY - buildHeight| / maxDistance, 0, 1 )
 * Target level    = lo + t * (hi - lo)   -- the "ideal" continuous level for this height
 *
 * Each discrete level's weight is a triangular kernel centered on that
 * target (width controlled by verticalLootDelineation), multiplied by the
 * schematic's own configured chestLevelWeights as a secondary shaping
 * factor -- so a level a config explicitly zeroed out stays at zero
 * regardless of height, but height is what decides which level dominates.
 *
 * Earlier versions blended (1-t)*originalWeight + t*allWeightOnMaxLevel:
 * at t=0 that reduces to exactly the raw, unbiased originalWeight (often
 * flat across all levels), so a max-level chest had the SAME odds at
 * street level as anywhere else -- the bias only ever pushed weight
 * TOWARD lootMaxLevel as height increased, it never pushed away from it
 * near street level. That's why high-level loot could still turn up on
 * ground floor even with the bias enabled. This version is symmetric:
 * t=0 concentrates on lootMinLevel exactly as strongly as t=1 concentrates
 * on lootMaxLevel.
 *
 * When disabled, falls back to the standard weighted draw using the
 * original (unblended) weights.
 *
 * Uses GridRandom.getRandomInt(int bound) (returns a value in [0, bound))
 * as its only randomness source, since that's the method GridRandom is
 * confirmed to expose (see GlobalSchematicConfig#getRandomChestLevel).
 * Blended weights are floating point, so they're scaled up to integers
 * before the roll to preserve precision.
 */
public class VerticalLootBias {

    /** Scale factor used to convert blended double weights to integers. */
    private static final int WEIGHT_SCALE = 10_000;

    private final boolean enabled;
    private final int     baselineY;
    private final int     maxDistanceBlocks;
    private final double  delineation;

    public VerticalLootBias(boolean enabled, int baselineY, int maxDistanceBlocks, double delineation) {
        this.enabled           = enabled;
        this.baselineY         = baselineY;
        this.maxDistanceBlocks = Math.max(1, maxDistanceBlocks);
        this.delineation       = Math.max(0.01, delineation);
    }

    /**
     * Select a loot level for a chest placed inside a schematic.
     *
     * @param schematicBaseY  World Y of the schematic paste origin.
     * @param lootMinLevel    From SchematicConfig, inclusive (1-based).
     * @param lootMaxLevel    From SchematicConfig, inclusive (1-based).
     * @param globalWeights   chestLevelWeights, index 0 = level 1.
     * @param random          GridRandom provided by the caller.
     * @return                A loot level in [lootMinLevel, lootMaxLevel].
     */
    public int selectLootLevel(int schematicBaseY,
                               int lootMinLevel,
                               int lootMaxLevel,
                               int[] globalWeights,
                               GridRandom random) {

        if (!enabled) {
            return standardWeightedDraw(lootMinLevel, lootMaxLevel, globalWeights, random);
        }

        int lo = Math.max(1, lootMinLevel);
        int hi = Math.min(globalWeights.length, lootMaxLevel);
        if (lo > hi) hi = lo;

        double dist = Math.abs(schematicBaseY - baselineY);
        double t    = Math.min(1.0, dist / maxDistanceBlocks);

        // The "ideal" continuous level for this height -- lo at street
        // level, hi at maxDistanceBlocks away, linear in between. Each
        // discrete level is then weighted by how close it sits to this
        // target (triangular kernel, half-width = delineation), so only
        // the target level and its immediate neighbours get meaningful
        // probability instead of every level in [lo, hi] being live at
        // every height.
        double targetLevel = lo + t * (hi - lo);

        int   range   = hi - lo + 1;
        int[] blended = new int[range];
        int   total   = 0;

        for (int i = 0; i < range; i++) {
            int    level    = lo + i;
            double origW    = (level - 1 < globalWeights.length) ? globalWeights[level - 1] : 1.0;
            double kernel   = Math.max(0.0, 1.0 - Math.abs(level - targetLevel) / delineation);
            double weight   = kernel * origW;

            // Scale to an int and guarantee at least 1 so a level with
            // nonzero probability can never round down to an impossible 0.
            int scaled = (int) Math.round(weight * WEIGHT_SCALE);
            blended[i] = Math.max(scaled, weight > 0.0 ? 1 : 0);
            total      += blended[i];
        }

        if (total <= 0) {
            // Only reachable if every level the kernel favoured at this
            // height happens to have origW=0 in this schematic's config
            // (deliberately disabled). Fall back to whichever end of the
            // range the target height was actually closest to, rather
            // than always defaulting to hi -- that silently reintroduces
            // exactly the "high-level loot near street level" problem
            // this rework exists to fix.
            return (int) Math.round(Math.min(hi, Math.max(lo, targetLevel)));
        }

        int roll  = random.getRandomInt(total);
        int cumul = 0;
        for (int i = 0; i < range; i++) {
            cumul += blended[i];
            if (roll < cumul) return lo + i;
        }
        return hi;
    }

    // ------------------------------------------------------------------ //

    private int standardWeightedDraw(int lootMinLevel,
                                     int lootMaxLevel,
                                     int[] globalWeights,
                                     GridRandom random) {
        int lo = Math.max(1, lootMinLevel);
        int hi = Math.min(globalWeights.length, lootMaxLevel);
        if (lo > hi) hi = lo;

        int total = 0;
        for (int level = lo; level <= hi; level++) total += globalWeights[level - 1];
        if (total <= 0) return lo;

        int roll  = random.getRandomInt(total);
        int cumul = 0;
        for (int level = lo; level <= hi; level++) {
            cumul += globalWeights[level - 1];
            if (roll < cumul) return level;
        }
        return hi;
    }

    public boolean isEnabled()      { return enabled; }
    public int     getBaselineY()   { return baselineY; }
    public int     getMaxDistance() { return maxDistanceBlocks; }
    public double  getDelineation() { return delineation; }

    /** Returns blend factor t for a given Y — useful for debug logging. */
    public double blendFactor(int schematicBaseY) {
        return Math.min(1.0, Math.abs(schematicBaseY - baselineY) / (double) maxDistanceBlocks);
    }
}