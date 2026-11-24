package ch.k42.metropolis.grid.urbanGrid.wild;

/** Restored from the R30ea member/bytecode reference; not an October 31 source. */
public final class WildHabitatBias {
    private WildHabitatBias() {}

    private static double smooth(double lo, double hi, double value) {
        double t = Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
        return t * t * (3.0 - 2.0 * t);
    }

    public static void apply(double[] weights, WildGeography geography, int x, int z,
                             double[] additions, int unused, int dunesPct, int barrensPct) {
        int forest = WildBiome.FOREST.ordinal();
        WildBiome[] habitats = {
            WildBiome.PRAIRIE, WildBiome.WETLANDS, WildBiome.OLD_GROWTH,
            WildBiome.DECIDUOUS, WildBiome.COASTAL_DUNES, WildBiome.VOLCANIC_BARRENS
        };
        for (WildBiome biome : habitats) {
            int at = biome.ordinal();
            double suitability = geography.regionSuitability(biome, x, z);
            double before = weights[at];
            weights[at] = before * suitability;
            weights[forest] += before - weights[at];
        }
        double requestedTotal = 0.0;
        for (double addition : additions) requestedTotal += Math.max(0.0, addition);
        int[] favored = {WildBiome.COASTAL_DUNES.ordinal(), WildBiome.VOLCANIC_BARRENS.ordinal()};
        int[] percentages = {dunesPct, barrensPct};
        for (int i = 0; i < favored.length; i++) {
            int at = favored[i];
            if (at >= additions.length || additions[at] <= 0 || percentages[i] <= 0
                    || requestedTotal <= 0.0) continue;
            double suitability = geography.regionSuitability(WildBiome.ALL[at], x, z);
            // The configured percentage is the strength of THIS habitat's
            // requested share, not a replacement fraction of the entire
            // biome mixture. Treating 88% core strength as 88% of all land
            // turned almost every inland column into Karst and erased rare
            // Bloomlands in the final production picker.
            double core = i == 1 ? smooth(0.12, 0.58, suitability)
                    : smooth(0.32, 0.78, suitability);
            double requestedFraction = Math.max(0.0, additions[at]) / requestedTotal;
            double share = Math.max(0, Math.min(0.92, percentages[i] / 100.0))
                    * requestedFraction * core;
            if (share <= 0) continue;
            for (int index = 0; index < weights.length; index++) weights[index] *= 1.0 - share;
            weights[at] += share;
        }
        int wetlands = WildBiome.WETLANDS.ordinal();
        weights[forest] += weights[wetlands];
        weights[wetlands] = 0;
    }
}
