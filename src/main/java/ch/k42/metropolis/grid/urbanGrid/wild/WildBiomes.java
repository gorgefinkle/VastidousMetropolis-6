package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Original R30ea layer-based wilderness selection, backed by the existing
 * WildLayers zoom, transitions and bounded region-label memoization.
 * No alternate noise-based biome selector is retained alongside it.
 * The R30ea JAR is a test comparator only; no runtime bytecode dependency.
 */
public final class WildBiomes {
   private final WildLayers layers;
   private final double ringBlocks;

   public WildBiomes(long worldSeed, double scaleChunks, double blend, double[] shares) {
      this(worldSeed, scaleChunks, blend, shares, null);
   }

   public WildBiomes(long worldSeed, double scaleChunks, double blend, double[] shares,
                     int[][] transitions) {
      this(worldSeed, scaleChunks, blend, shares, transitions, 5, 2, 100);
   }

   public WildBiomes(long worldSeed, double scaleChunks, double blend, double[] shares,
                     int[][] transitions, int zoomLevels, int transitionPasses, int warpPercent) {
      double[] weights = new double[WildBiome.ALL.length];
      for (int i = 0; i < weights.length; ++i) {
         weights[i] = shares == null || i >= shares.length ? 0 : Math.max(0.0, shares[i]);
      }
      this.layers = new WildLayers(worldSeed, scaleChunks, weights, transitions,
                                   zoomLevels, transitionPasses, warpPercent);
      this.ringBlocks = 30.0 + 200.0 * Math.max(0.0, Math.min(1.0, blend));
   }

   public static int[][] parseTransitions(String[] rules, List<String> warnings) {
      ArrayList<int[]> parsed = new ArrayList<>();
      if (rules != null) for (String input : rules) {
         if (input == null || input.trim().isEmpty()) continue;
         String value = input.trim();
         int gt = value.indexOf('>');
         int eq = value.indexOf('=');
         WildBiome cell = gt > 0 ? WildBiome.byKey(value.substring(0, gt)) : null;
         WildBiome neighbor = gt > 0 && eq > gt ? WildBiome.byKey(value.substring(gt + 1, eq)) : null;
         WildBiome result = eq > 0 ? WildBiome.byKey(value.substring(eq + 1)) : null;
         if (cell == null || neighbor == null || result == null) {
            if (warnings != null) {
               warnings.add("cannot read \"" + value + "\" (expected cell>neighbour=becomes, for example forest>desert=dryForest; the keys are " + keys() + ")");
            }
            continue;
         }
         parsed.add(new int[] {cell.ordinal(), neighbor.ordinal(), result.ordinal()});
      }
      return parsed.toArray(new int[0][]);
   }

   private static String keys() {
      StringBuilder out = new StringBuilder();
      for (WildBiome b : WildBiome.ALL) out.append(out.length() == 0 ? "" : ", ").append(b.key());
      return out.toString();
   }

   public double[] realisedShares() { return layers.realisedShares(); }
   public double calibrationError() { return layers.calibrationError(); }
   public int label(int x, int z) { return layers.label(x, z); }

   /** R30ea center plus two eight-direction rings, normalization 3+8+8. */
   public void weights(int x, int z, double[] out) {
      Arrays.fill(out, 0.0);
      out[layers.label(x, z)] += 3.0;
      double total = 3.0;
      for (int ring = 1; ring <= 2; ++ring) {
         double radius = ringBlocks * ring / 2.0;
         for (int direction = 0; direction < 8; ++direction) {
            double a = direction * (Math.PI / 4.0);
            out[layers.label(x + Math.cos(a) * radius, z + Math.sin(a) * radius)] += 1.0;
            total += 1.0;
         }
      }
      for (int i = 0; i < out.length; ++i) out[i] /= total;
   }

   public static void keepAway(double[] weights, double distanceBlocks, double[] fullBlocks) {
      keepAway(weights, distanceBlocks, fullBlocks, 0.0);
   }

   /** R30ea: process longer exclusion zones first, handing suppressed weight
    * to each biome's declared fallback instead of renormalizing all habitats.
    */
   public static void keepAway(double[] weights, double distanceBlocks, double[] fullBlocks, double mergeBlocks) {
      boolean[] processed = new boolean[weights.length];
      for (int i = 0; i < weights.length; ++i) {
         int selected = -1;
         double length = 0;
         for (int candidate = 0; candidate < weights.length; ++candidate) {
            double d = candidate < fullBlocks.length ? fullBlocks[candidate] : 0;
            if (!processed[candidate] && d > length) {
               length = d;
               selected = candidate;
            }
         }
         if (selected < 0) return;
         processed[selected] = true;
         double ramp = mergeBlocks > 0 ? Math.min(mergeBlocks, length) : 0.6 * length;
         double t = Math.max(0.0, Math.min(1.0, (distanceBlocks - (length - ramp)) / ramp));
         double kept = weights[selected] * t * t * (3.0 - 2.0 * t);
         int fallback = WildBiome.ALL[selected].fallback().ordinal();
         if (fallback != selected) {
            weights[fallback] += weights[selected] - kept;
            weights[selected] = kept;
         }
      }
   }

   public static void sharpen(double[] weights, double[] output) {
      double total = 0.0;
      for (int i = 0; i < weights.length; ++i) {
         double t = Math.max(0.0, Math.min(1.0, (weights[i] - .10) / .80));
         output[i] = t * t * (3.0 - 2.0 * t);
         total += output[i];
      }
      if (total <= 1e-9) {
         for (int i = 0; i < output.length; ++i) output[i] = i == 0 ? 1.0 : 0.0;
         return;
      }
      for (int i = 0; i < output.length; ++i) output[i] /= total;
   }

   public static WildBiome pick(double[] weights, double u) {
      double sum = 0;
      for (int i = 0; i < weights.length; ++i) {
         sum += weights[i];
         if (u < sum) return WildBiome.ALL[i];
      }
      return WildBiome.ALL[weights.length - 1];
   }

   public static WildBiome dominant(double[] weights) {
      int best = 0;
      for (int i = 1; i < weights.length; ++i) if (weights[i] > weights[best]) best = i;
      return WildBiome.ALL[best];
   }
}
