package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import ch.k42.metropolis.grid.urbanGrid.wild.WildRelief;

/**
 * Regression for the startup crash caused by BiomeEcology reflecting a removed
 * WildRelief.coneSeed field. Also verifies the real world seed reaches Karst
 * relief unchanged for positive, negative and production test-world seeds.
 */
public final class BiomeEcologySeedProbe {
    private BiomeEcologySeedProbe() {}

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        long[] seeds = {
            0L,
            -1482741523194359869L,
            1750414970189483479L,
            1414199025253064524L,
            -4265128899667023160L
        };

        for (long seed : seeds) {
            WildRelief prior = new WildRelief(seed, 58.0, 8, 5, 1.0, null);
            require(prior.getWorldSeed() == seed,
                    "WildRelief did not retain the constructor world seed: " + seed);

            long forestSeed = seed ^ ForestTerrain.WORLD_SEED_SALT;
            require(BiomeEcology.worldSeedFromForest(forestSeed) == seed,
                    "Forest terrain seed did not decode to the same world seed: " + seed);

            double[] weights = new double[WildBiome.ALL.length];
            weights[WildBiome.VOLCANIC_BARRENS.ordinal()] = 1.0;
            int x = -12345;
            int z = 6789;
            double original = prior.mountains(x, z);
            double actual = BiomeEcology.relief(original, weights, x, z, prior, false);
            double expected = KarstLandforms.relief(seed, x, z, false, false);
            require(Math.abs(actual - expected) < 1.0e-9,
                    "Karst relief seed mismatch for world seed " + seed
                    + ": expected " + expected + ", got " + actual);

            double unchanged = BiomeEcology.relief(
                    7.25, new double[WildBiome.ALL.length], x, z, prior, false);
            require(Math.abs(unchanged - 7.25) < 1.0e-12,
                    "Zero-weight biome altered the incoming relief for seed " + seed);
        }

        System.out.println("BiomeEcologySeedProbe PASS: class initialization, explicit world-seed handoff, "
                + "forest-seed decoding and Karst relief agree across " + seeds.length + " seeds");
    }
}
