package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import java.util.Random;

/**
 * Exercises the exact production WildField picker, including blur, habitat
 * bias, city/farm keep-away, sharpening, hard borders and the painter's hash.
 * This is deliberately beyond the discrete WildLayers label-map test.
 */
public final class WildBiomeProductionPickerProbe {
   private static final int SAMPLES_PER_SEED = 100_000;
   private static final int EXTENT = 8_000;

   public static void main(String[] args) {
      long[] seeds = {
         -1482741523194359869L,
         1750414970189483479L,
         1414199025253064524L
      };
      long totalBloom = 0;
      long totalKarst = 0;

      for (long seed : seeds) {
         ForestGenerator.WildField field = new ForestGenerator.WildField(seed, false);
         Random random = new Random(seed ^ 0x4250524f44554354L);
         int bloom = 0;
         int karst = 0;

         for (int i = 0; i < SAMPLES_PER_SEED; i++) {
            int x = random.nextInt(EXTENT * 2 + 1) - EXTENT;
            int z = random.nextInt(EXTENT * 2 + 1) - EXTENT;
            double roll = ForestTerrain.unit(ForestTerrain.hash(
                    seed ^ 0x464F52455354L, x, z, 41));
            WildBiome picked = field.pick(x, z, roll);
            if (picked == WildBiome.BLOOMLANDS) bloom++;
            if (picked == WildBiome.VOLCANIC_BARRENS) karst++;
         }

         double percent = 100.0 * bloom / SAMPLES_PER_SEED;
         System.out.printf(java.util.Locale.ROOT,
                 "seed=%d productionBloom=%d (%.3f%%) productionKarst=%d (%.3f%%)%n",
                 seed, bloom, percent, karst, 100.0 * karst / SAMPLES_PER_SEED);
         if (bloom < 200) {
            throw new AssertionError("production picker collapses Bloomlands for seed "
                    + seed + ": only " + bloom + "/" + SAMPLES_PER_SEED);
         }
         if (karst <= bloom) {
            throw new AssertionError("Bloomlands must remain rarer than Karst for seed " + seed);
         }
         totalBloom += bloom;
         totalKarst += karst;
      }

      System.out.printf(java.util.Locale.ROOT,
              "WildBiomeProductionPickerProbe: PASS totalBloom=%d totalKarst=%d samples=%d%n",
              totalBloom, totalKarst, (long) SAMPLES_PER_SEED * seeds.length);
   }
}
