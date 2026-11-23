package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.Random;

/** Checks that the two rare habitats survive the final discrete field. */
public final class WildBiomeAppearanceProbe {
   public static void main(String[] args) {
      double[] shares = new double[WildBiome.ALL.length];
      for (WildBiome biome : WildBiome.ALL) shares[biome.ordinal()] = biome.defaultShare();
      int[][] transitions = WildBiomes.parseTransitions(new String[] {
            "forest>desert=dryForest", "forest>mesa=dryForest",
            "dryForest>mesa=desert", "rainforest>desert=dryForest",
            "rainforest>mesa=dryForest"
      }, new java.util.ArrayList<>());

      long[] seeds = { -1482741523194359869L, 1750414970189483479L, 1414199025253064524L };
      long totalBloom = 0, totalKarst = 0;
      for (long seed : seeds) {
         WildBiomes field = new WildBiomes(seed, 520.0, 0.15, shares, transitions, 4, 1, 35);
         double[] realised = field.realisedShares();
         long bloom = 0, karst = 0, bloomHard = 0, karstHard = 0;
         final int samples = 60000;
         Random random = new Random(seed ^ 0x5241524542494f4dL);
         double[] raw = new double[WildBiome.ALL.length];
         double[] sharp = new double[WildBiome.ALL.length];
         for (int sample = 0; sample < samples; sample++) {
            // Uniform random coordinates avoid aliasing a rare biome's
            // surviving child cells with a regular sampling lattice.
            int x = random.nextInt(600001) - 300000;
            int z = random.nextInt(600001) - 300000;
            int label = field.label(x, z);
            if (label == WildBiome.BLOOMLANDS.ordinal()) bloom++;
            if (label == WildBiome.VOLCANIC_BARRENS.ordinal()) karst++;
            field.weights(x, z, raw);
            WildBiomes.sharpen(raw, sharp);
            int dominant = WildBiomes.dominant(sharp).ordinal();
            if (dominant == WildBiome.BLOOMLANDS.ordinal()) bloomHard++;
            if (dominant == WildBiome.VOLCANIC_BARRENS.ordinal()) karstHard++;
         }
         System.out.println("seed=" + seed + " calibratedBloom=" + realised[WildBiome.BLOOMLANDS.ordinal()]
                 + " calibratedKarst=" + realised[WildBiome.VOLCANIC_BARRENS.ordinal()]
                 + " labelBloom=" + bloom + " labelKarst=" + karst
                 + " dominantBloom=" + bloomHard + " dominantKarst=" + karstHard);
         if (bloom == 0 || bloomHard == 0)
            throw new AssertionError("Bloomlands absent from final sampled field for seed " + seed);
         if (karst == 0 || karstHard == 0)
            throw new AssertionError("Karst absent from final sampled field for seed " + seed);
         if (bloomHard >= karstHard)
            throw new AssertionError("Bloomlands must remain rarer than karst: seed=" + seed
                    + " Bloomlands=" + bloomHard + " karst=" + karstHard);
         totalBloom += bloomHard;
         totalKarst += karstHard;
      }
      System.out.println("WildBiomeAppearanceProbe: PASS (hard-border Bloomlands=" + totalBloom
              + " remains rarer than karst=" + totalKarst + ")");
   }
}
