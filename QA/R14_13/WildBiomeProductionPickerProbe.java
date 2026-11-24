package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import java.util.Random;

/**
 * Exercises the exact production WildField picker, including blur, habitat
 * bias, city/farm keep-away, sharpening, hard borders and the painter's hash.
 * This is deliberately beyond the discrete WildLayers label-map test.
 */
public final class WildBiomeProductionPickerProbe {
   private static final int SAMPLES_PER_SEED = 20_000;
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

         // Mirror the command's square-ring scan from a player near the
         // default-world spawn, using its 32-block search lattice and border.
         int originX = -56, originZ = 23, firstRingHit = -1;
         long firstHitDistance = Long.MAX_VALUE;
         long latticeMatches = 0;
         long checked = 0;
         int searchStep = 128; // deliberately coarser than the production command's 32-block lattice
         int maxRing = (int)Math.ceil(EXTENT / (double)searchStep) + 1;
         search:
         for (int ring = 0; ring <= maxRing; ring++) {
            long points = ring == 0 ? 1L : 8L * ring;
            for (long index = 0; index < points; index++) {
               int[] point = pointAt(originX, originZ, ring, index, searchStep);
               if (point[0] < 54 - EXTENT || point[0] > 54 + EXTENT
                       || point[1] < -16 - EXTENT || point[1] > -16 + EXTENT) continue;
               checked++;
               double roll = ForestTerrain.unit(ForestTerrain.hash(
                       seed ^ 0x464F52455354L, point[0], point[1], 41));
               if (field.pick(point[0], point[1], roll) == WildBiome.BLOOMLANDS) {
                  latticeMatches++;
                  long dx = (long)point[0] - originX, dz = (long)point[1] - originZ;
                  firstHitDistance = Math.min(firstHitDistance, dx * dx + dz * dz);
                  if (firstRingHit < 0) firstRingHit = ring;
               }
            }
            if (firstRingHit >= 0) break;
         }

         double percent = 100.0 * bloom / SAMPLES_PER_SEED;
         System.out.printf(java.util.Locale.ROOT,
                 "seed=%d productionBloom=%d (%.3f%%) productionKarst=%d (%.3f%%) firstBloomRing=%d scanSamples=%d%n",
                 seed, bloom, percent, karst, 100.0 * karst / SAMPLES_PER_SEED, firstRingHit, checked);
         if (bloom < 20) {
            throw new AssertionError("production picker collapses Bloomlands for seed "
                    + seed + ": only " + bloom + "/" + SAMPLES_PER_SEED);
         }
         if (karst <= bloom) {
            throw new AssertionError("Bloomlands must remain rarer than Karst for seed " + seed);
         }
         if (karst > SAMPLES_PER_SEED * 0.20) {
            throw new AssertionError("Karst expanded beyond its intended rare habitat for seed "
                    + seed + ": " + karst + "/" + SAMPLES_PER_SEED);
         }
         if (firstRingHit < 0 || latticeMatches == 0) {
            throw new AssertionError("32-block command scan did not encounter Bloomlands inside the world border for seed " + seed);
         }
         totalBloom += bloom;
         totalKarst += karst;
      }

      System.out.printf(java.util.Locale.ROOT,
              "WildBiomeProductionPickerProbe: PASS totalBloom=%d totalKarst=%d samples=%d%n",
              totalBloom, totalKarst, (long) SAMPLES_PER_SEED * seeds.length);
   }

   private static int[] pointAt(int originX,int originZ,int ring,long index,int step) {
      if (ring == 0) return new int[] {originX, originZ};
      long r = ring;
      long edge = 2L * r + 1L;
      long dx, dz;
      if (index < edge) { dx = -r + index; dz = -r; }
      else if ((index -= edge) < edge) { dx = -r + index; dz = r; }
      else if ((index -= edge) < 2L * r - 1L) { dx = -r; dz = -r + 1L + index; }
      else { index -= 2L * r - 1L; dx = r; dz = -r + 1L + index; }
      return new int[] {originX + (int)(dx * step), originZ + (int)(dz * step)};
   }

}
