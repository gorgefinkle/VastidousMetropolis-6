package ch.k42.metropolis.grid.urbanGrid.wild;

/** Checks that the two rare habitats survive the zoom/smoothing/warp field. */
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
         long bloom = 0, karst = 0;
         int samples = 0;
         // 192x192 probes over a 98,000-block square, covering hundreds
         // of final field cells rather than repeatedly sampling one patch.
         for (int gx = -96; gx < 96; gx++) {
            for (int gz = -96; gz < 96; gz++) {
               int label = field.label(gx * 512 + 113, gz * 512 - 197);
               if (label == WildBiome.BLOOMLANDS.ordinal()) bloom++;
               if (label == WildBiome.VOLCANIC_BARRENS.ordinal()) karst++;
               samples++;
            }
         }
         if (bloom == 0) throw new AssertionError("Bloomlands never appeared for seed " + seed);
         if (karst == 0) throw new AssertionError("Karst never appeared for seed " + seed);
         if (bloom >= karst) throw new AssertionError("Bloomlands must remain the rarer of the two: seed="
                 + seed + " Bloomlands=" + bloom + " karst=" + karst);
         totalBloom += bloom;
         totalKarst += karst;
         System.out.println("seed=" + seed + " samples=" + samples + " Bloomlands=" + bloom
                 + " karst=" + karst + " PASS");
      }
      System.out.println("WildBiomeAppearanceProbe: PASS (Bloomlands=" + totalBloom
              + " remains rarer than karst=" + totalKarst + ")");
   }
}
