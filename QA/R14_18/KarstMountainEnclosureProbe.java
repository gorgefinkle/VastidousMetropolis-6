package ch.k42.metropolis.grid.urbanGrid.wild;

/** Verifies every rare Karstlands province is surrounded by actual mountain provinces. */
public final class KarstMountainEnclosureProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static double[] defaultShares() {
        double[] shares = new double[WildBiome.ALL.length];
        for (WildBiome biome : WildBiome.ALL) shares[biome.ordinal()] = biome.defaultShare();
        return shares;
    }

    private static int labelAtGroupCenter(WildLayers layers, double groupBlocks,
                                          int groupX, int groupZ) {
        int x = (int) Math.floor((groupX + 0.5) * groupBlocks);
        int z = (int) Math.floor((groupZ + 0.5) * groupBlocks);
        return layers.label(x, z);
    }

    public static void main(String[] args) {
        long[] seeds = {
            -1482741523194359869L,
            1750414970189483479L,
            1414199025253064524L,
            -4265128899667023160L,
            0x51A7E5L
        };
        int totalKarst = 0;
        for (long seed : seeds) {
            WildLayers layers = new WildLayers(seed, 520.0, defaultShares(), null, 4, 1, 35);
            double cellFinal = WildLayers.commonFinalCellBlocks(520.0, 4);
            int rareDivisor = WildLayers.rareCellDivisor(cellFinal);
            double groupBlocks = cellFinal * rareDivisor * 2.0;
            int groups = Math.max(6, Math.min(32, (int) Math.round(16384.0 / groupBlocks)));
            int karst = 0;
            for (int tileX = -2; tileX <= 2; tileX++) {
                for (int tileZ = -2; tileZ <= 2; tileZ++) {
                    for (int localZ = 0; localZ < groups; localZ++) {
                        for (int localX = 0; localX < groups; localX++) {
                            int groupX = tileX * groups + localX - groups / 2;
                            int groupZ = tileZ * groups + localZ - groups / 2;
                            int label = labelAtGroupCenter(layers, groupBlocks, groupX, groupZ);
                            if (label != WildBiome.VOLCANIC_BARRENS.ordinal()) continue;
                            karst++;
                            // Each neighbouring macro province must be mountain,
                            // including the four diagonal directions.
                            for (int dz = -1; dz <= 1; dz++) {
                                for (int dx = -1; dx <= 1; dx++) {
                                    if (dx == 0 && dz == 0) continue;
                                    int neighbour = labelAtGroupCenter(layers, groupBlocks,
                                            groupX + dx, groupZ + dz);
                                    check(neighbour == WildBiome.MOUNTAINS.ordinal(),
                                            "Karst province is not mountain-enclosed on seed=" + seed
                                            + " group=" + groupX + "," + groupZ
                                            + " direction=" + dx + "," + dz
                                            + " found=" + WildBiome.ALL[neighbour]);
                                }
                            }
                        }
                    }
                }
            }
            check(karst > 0, "no Karstlands provinces found in macro-tile survey for seed " + seed);
            totalKarst += karst;
            System.out.println("seed=" + seed + " enclosedKarstProvinces=" + karst
                    + " mountainRingWidth=" + Math.round(groupBlocks) + " blocks");
        }
        check(totalKarst >= 10, "too few Karstlands provinces exercised: " + totalKarst);
        System.out.println("KarstMountainEnclosureProbe: PASS (all Karstlands provinces have mountains on all eight sides)");
    }
}
