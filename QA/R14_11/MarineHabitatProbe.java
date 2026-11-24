import ch.k42.metropolis.grid.urbanGrid.ocean.MarineHabitatModel;

/** Standalone deterministic coverage and patch-coherence regression probe. */
public final class MarineHabitatProbe {
    private static final long[] SEEDS = {
            -1482741523194359869L,
            1750414970189483479L,
            1414199025253064524L,
            -4265128899667023160L
    };
    private static final int[] DEPTHS = {6, 12, 21, 32, 40, 45};
    private static final double[] SHORE = {34.0, 80.0, 135.0, 220.0, 320.0, 380.0};

    public static void main(String[] args) {
        long[] counts = new long[6];
        for (long seed : SEEDS) {
            for (int x = -256; x < 256; x += 2) {
                for (int z = -256; z < 256; z += 2) {
                    for (int i = 0; i < DEPTHS.length; i++) {
                        int habitat = MarineHabitatModel.habitat(seed, x, z, DEPTHS[i], SHORE[i]);
                        if (habitat < MarineHabitatModel.BARREN
                                || habitat > MarineHabitatModel.HYDROTHERMAL_VENTS) {
                            throw new AssertionError("Unknown habitat " + habitat);
                        }
                        if (habitat != MarineHabitatModel.habitat(seed, x, z, DEPTHS[i], SHORE[i])) {
                            throw new AssertionError("Habitat is not deterministic");
                        }
                        counts[habitat]++;
                    }
                }
            }
        }

        if (counts[MarineHabitatModel.BARREN] < 200_000L) {
            throw new AssertionError("Too little barren seabed remains");
        }
        requireCoverage(counts, MarineHabitatModel.SEAGRASS_MEADOW, 20_000L, "seagrass meadows");
        requireCoverage(counts, MarineHabitatModel.KELP_FOREST, 50_000L, "kelp forests");
        requireCoverage(counts, MarineHabitatModel.CORAL_REEF, 15_000L, "coral reefs");
        requireCoverage(counts, MarineHabitatModel.SPONGE_GARDEN, 15_000L, "sponge gardens");
        requireCoverage(counts, MarineHabitatModel.HYDROTHERMAL_VENTS, 100L, "hydrothermal vents");

        for (long seed : SEEDS) {
            if (MarineHabitatModel.habitat(seed, 0, 0, 3, 200.0) != MarineHabitatModel.BARREN
                    || MarineHabitatModel.habitat(seed, 0, 0, 49, 200.0) != MarineHabitatModel.BARREN
                    || MarineHabitatModel.habitat(seed, 0, 0, 20, 6.9) != MarineHabitatModel.BARREN) {
                throw new AssertionError("Habitat escaped its depth or shore safety gate");
            }
        }

        long adjacentFeaturePairs = 0L;
        long sameHabitatPairs = 0L;
        long seed = SEEDS[0];
        for (int x = -512; x < 512; x += 2) {
            for (int z = -512; z < 512; z += 2) {
                int a = MarineHabitatModel.habitat(seed, x, z, 20, 120.0);
                int b = MarineHabitatModel.habitat(seed, x + 2, z, 20, 120.0);
                if (a != MarineHabitatModel.BARREN && b != MarineHabitatModel.BARREN) {
                    adjacentFeaturePairs++;
                    if (a == b) sameHabitatPairs++;
                }
            }
        }
        double patchAgreement = adjacentFeaturePairs == 0L ? 0.0
                : (double) sameHabitatPairs / adjacentFeaturePairs;
        if (patchAgreement < 0.85) {
            throw new AssertionError("Habitat patches are too noisy: " + patchAgreement);
        }

        for (int depth = 1; depth <= 48; depth++) {
            int height = MarineHabitatModel.maximumFeatureHeight(depth);
            if (height < 0 || height > 6) {
                throw new AssertionError("Unsafe feature height " + height + " at depth " + depth);
            }
        }
        if (MarineHabitatModel.maximumFeatureHeight(3) != 0
                || MarineHabitatModel.maximumFeatureHeight(8) != 1
                || MarineHabitatModel.maximumFeatureHeight(10) != 3
                || MarineHabitatModel.maximumFeatureHeight(13) != 6) {
            throw new AssertionError("Unexpected ship-clearance feature-height envelope");
        }

        System.out.printf(
                "MarineHabitatProbe: PASS (barren=%d seagrass=%d kelp=%d coral=%d sponge=%d vents=%d, adjacentPatchAgreement=%.4f)%n",
                counts[0], counts[1], counts[2], counts[3], counts[4], counts[5], patchAgreement);
    }

    private static void requireCoverage(long[] counts, int habitat, long minimum, String name) {
        if (counts[habitat] < minimum) {
            throw new AssertionError(name + " coverage too low: " + counts[habitat]);
        }
    }
}
