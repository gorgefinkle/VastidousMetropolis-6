package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.util.noise.SimplexOctaveGenerator;

/**
 * Pure, seed-driven fields for forest generation: which forest type is where,
 * how open the canopy is, and how hilly the ground is. Everything here is a
 * function of world coordinates only, so every chunk of a region -- and every
 * chunk that has to know about a tree overhanging its edge -- gets the same
 * answer without reading the world.
 */
public class ForestTerrain {
   public static final long WORLD_SEED_SALT = 0x464F52455354L;
   private static final double SIGMA = 0.45;

   private final long seed;
   private final SimplexOctaveGenerator tempNoise;
   private final SimplexOctaveGenerator moistNoise;
   private final SimplexOctaveGenerator reliefLarge;
   private final SimplexOctaveGenerator reliefSmall;
   private final SimplexOctaveGenerator clearingNoise;
   private final double amplitude;
   private final double bias;

   public ForestTerrain(long worldSeed) {
      this.seed = worldSeed ^ WORLD_SEED_SALT;
      double typeScale = Math.max(32, PluginConfig.getForestTypeScale());
      this.amplitude = PluginConfig.getForestReliefAmplitude();
      this.bias = PluginConfig.getForestReliefBias();

      this.tempNoise = octave(this.seed ^ 0x1111L, 3, 1.0 / typeScale);
      this.moistNoise = octave(this.seed ^ 0x2222L, 3, 1.0 / typeScale);
      this.reliefLarge = octave(this.seed ^ 0x3333L, 3, 1.0 / 90.0);
      this.reliefSmall = octave(this.seed ^ 0x4444L, 2, 1.0 / 22.0);
      this.clearingNoise = octave(this.seed ^ 0x5555L, 2, 1.0 / 60.0);
   }

   private static SimplexOctaveGenerator octave(long seed, int octaves, double scale) {
      SimplexOctaveGenerator g = new SimplexOctaveGenerator(seed, octaves);
      g.setScale(scale);
      return g;
   }

   public long getSeed() {
      return this.seed;
   }

   /** How much of a column is rainforest (0..1). The rainforest is a wilderness biome, not a point on the climate plane. */
   public interface Rainfall {
      double at(int x, int z);
   }

   private volatile Rainfall rainfall;

   /** Coherent wilderness type preferences; called after rainfall normalization. */
   public interface TypeBias { void apply(int x, int z, double[] weights); }
   private volatile TypeBias typeBias;
   public void setTypeBias(TypeBias bias) { this.typeBias = bias; }

   /** Wires the wilderness biome of the same name in (null = no rainforest anywhere). */
   public void setRainfall(Rainfall rainfall) {
      this.rainfall = rainfall;
   }

   /** Fills out[] (length ForestType.ALL.length) with blend weights that sum to 1. */
   public void typeWeights(int x, int z, double[] out) {
      double t = clamp(this.tempNoise.noise(x, z, 0.5, 0.5, true) * 1.6, -1.0, 1.0);
      double m = clamp(this.moistNoise.noise(x, z, 0.5, 0.5, true) * 1.6, -1.0, 1.0);
      double sum = 0.0;
      for (int i = 0; i < ForestType.ALL.length; i++) {
         double dt = t - ForestType.ALL[i].temp;
         double dm = m - ForestType.ALL[i].moist;
         double w = Math.exp(-(dt * dt + dm * dm) / (2.0 * SIGMA * SIGMA));
         out[i] = w;
         sum += w;
      }
      if (sum <= 1e-9) {
         out[0] = 1.0;
         sum = 1.0;
         for (int i = 1; i < out.length; i++) {
            out[i] = 0.0;
         }
      }
      for (int i = 0; i < out.length; i++) {
         out[i] /= sum;
      }
      Rainfall rf = this.rainfall;
      if (rf != null) {
         // the rainforest takes its share of the column outright: relief, ground cover and trees all read these weights
         double r = clamp(rf.at(x, z), 0.0, 1.0);
         if (r > 0.0) {
            for (int i = 0; i < out.length; i++) {
               out[i] *= 1.0 - r;
            }
            out[ForestType.RAINFOREST.ordinal()] += r;
         }
      }
      TypeBias bias = this.typeBias;
      if (bias != null) bias.apply(x, z, out);
   }

   public static ForestType dominant(double[] w) {
      int best = 0;
      for (int i = 1; i < w.length; i++) {
         if (w[i] > w[best]) {
            best = i;
         }
      }
      return ForestType.ALL[best];
   }

   /** Picks a type with probability proportional to its weight; u is uniform in [0,1). */
   public static ForestType pick(double[] w, double u) {
      double acc = 0.0;
      for (int i = 0; i < w.length; i++) {
         acc += w[i];
         if (u < acc) {
            return ForestType.ALL[i];
         }
      }
      return ForestType.ALL[w.length - 1];
   }

   /** 0 = full canopy, 1 = open glade. Roughly a quarter of the area is partly open. */
   public double openness(int x, int z) {
      double n = this.clearingNoise.noise(x, z, 0.5, 0.5, true);
      double t = clamp((n - 0.30) / 0.30, 0.0, 1.0);
      return t * t * (3.0 - 2.0 * t);
   }

   /**
    * Ground relief in blocks relative to the baseline, before the edge fade is
    * applied. Biased upward so the forest reads as rolling hills with the odd
    * hollow rather than a pitted floor.
    */
   public double relief(int x, int z, double[] w) {
      double mul = 0.0;
      for (int i = 0; i < w.length; i++) {
         mul += w[i] * ForestType.ALL[i].reliefMul;
      }
      double large = this.reliefLarge.noise(x, z, 0.5, 0.5, true);
      double small = this.reliefSmall.noise(x, z, 0.5, 0.5, true);
      double n = 0.85 * large + 0.15 * small;
      return this.amplitude * mul * (this.bias + (1.0 - this.bias) * n);
   }

   public static double clamp(double v, double lo, double hi) {
      return v < lo ? lo : (v > hi ? hi : v);
   }

   public static double smooth(double t) {
      t = clamp(t, 0.0, 1.0);
      return t * t * (3.0 - 2.0 * t);
   }

   /** SplitMix64-style position hash, stable across runs and chunk order. */
   public static long hash(long seed, int x, int z, int salt) {
      long h = seed + 0x9E3779B97F4A7C15L * (x * 0x632BE5ABL + 1)
              + 0xC2B2AE3D27D4EB4FL * (z * 0x27D4EB2FL + 1) + salt * 0x165667B19E3779F9L;
      h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
      h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
      return h ^ (h >>> 31);
   }

   /** Uniform [0,1) from a hash. */
   public static double unit(long h) {
      return (h >>> 11) * (1.0 / 9007199254740992.0);
   }
}
