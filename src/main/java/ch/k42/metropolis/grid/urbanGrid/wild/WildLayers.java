package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Which biome is where, as discrete labels, built the way vanilla Minecraft builds its biome map: a coarse grid of random biomes
 * is zoomed in step by step, and between zooms the labels are cleaned up. A pure function of (seed, x, z): nothing here reads the
 * world, so every chunk, in any order, gets the same answer.
 *
 *  - level 0 is a coarse grid (one cell is a biome patch or so) holding a random biome per cell, drawn by the configured shares;
 *  - every level after it zooms the one before in half (a cell becomes four): the new cells copy a parent, or the majority of the
 *    four parents, and a coin flip (a hash of the cell) decides where that is a tie;
 *  - from level 2 on, after each zoom, a smoothing pass removes single-cell spots and one-cell lines (a cell whose opposite
 *    neighbours agree with each other and not with it is overruled), and then the transition rules run: a cell of one biome that
 *    touches another listed biome turns into the buffer biome between them (forest beside desert becomes dry forest, say). That
 *    puts a buffer a whole cell thick, at a coarse scale, instead of letting incompatible biomes meet;
 *  - the last level's cells are small (about 65 blocks at the default scale), read through a slight domain warp so the borders are
 *    not straight grid lines.
 *
 * Nothing in here knows a biome by name. The biomes, the shares and the transition rules all come in as data, so a new biome is a
 * new WildBiome constant and, if it needs a buffer, a line in generator.wilderness.transitions.
 */
final class WildLayers {
   /** Zoom steps between the coarse start grid and the final cells (each halves the cell size). */
   static final int DEFAULT_LEVELS = 5;
   /** The level from which smoothing and the transition rules run (they are applied to coarse levels, so a buffer is a thick belt). */
   private static final int CLEAN_FROM = 2;
   /** How many times the transition rules run after each zoom (a rule can make a cell that needs another rule). */
   private final int levels;
   private final int edgePasses;
   private static final int MAX_CACHED = 600000;

   private final long seed;
   private final int n;
   private final double[] prior;
   private final int[][] rules;
   private final double cellFinal;
   private final double warpBlocks;
   private final Noise2D warpX;
   private final Noise2D warpZ;
   private final ConcurrentHashMap<Long, Byte> memo = new ConcurrentHashMap<Long, Byte>();
   // Bounded, world-local cache of neighborhood-consistent final region labels.
   private final ConcurrentHashMap<Long, Byte> regionMemo = new ConcurrentHashMap<Long, Byte>();
   private int puts;
   private final double[] realised;
   private double calibrationError;

   /**
    * @param target the share of the land each biome should cover, in WildBiome order (any scale, normalised here)
    * @param rules  transition rules, each {cell biome, neighbour biome, becomes}: a cell that touches the neighbour turns into "becomes"
    */
   WildLayers(long worldSeed, double scaleChunks, double[] target, int[][] rules) {
      this(worldSeed, scaleChunks, target, rules, 5, 2, 100);
   }

   WildLayers(long worldSeed, double scaleChunks, double[] target, int[][] rules,
              int zoomLevels, int transitionPasses, int warpPercent) {
      this.levels = Math.max(3, Math.min(6, zoomLevels));
      this.edgePasses = Math.max(0, Math.min(2, transitionPasses));
      this.seed = worldSeed ^ 0x4C41594552534CL;
      this.n = target.length;
      this.rules = rules == null ? new int[0][] : rules;
      double cell0 = Math.max(256.0, scaleChunks * 16.0 * 1.3);
      this.cellFinal = cell0 / (1 << this.levels);
      this.warpBlocks = Math.min(this.cellFinal * 0.75, 48.0) * (Math.max(0, Math.min(100, warpPercent)) / 100.0);
      this.warpX = new Noise2D(this.seed ^ 0x77A1L);
      this.warpZ = new Noise2D(this.seed ^ 0x77A2L);
      this.prior = new double[this.n];
      this.realised = new double[this.n];
      this.calibrate(target);
   }

   /** The biome (a WildBiome ordinal) of the final cell holding the block (x, z). */
   int label(double x, double z) {
      double qx = x + this.warpBlocks * clamp1(1.6 * this.warpX.fbm(x / 700.0, z / 700.0, 2, 0.5));
      double qz = z + this.warpBlocks * clamp1(1.6 * this.warpZ.fbm(x / 700.0 + 9.0, z / 700.0 - 4.0, 2, 0.5));
      return this.regionLabel((int) Math.floor(qx / this.cellFinal), (int) Math.floor(qz / this.cellFinal));
   }

   /** R30ea majority protection across a 3x3 neighborhood; do not erase broad core cells. */
   private int regionLabel(int x, int z) {
      long key = ((long) x << 32) ^ (z & 0xffffffffL);
      Byte prior = regionMemo.get(key);
      if (prior != null) return prior & 0xff;
      int center = this.value(this.levels, x, z);
      int[] counts = new int[this.n];
      for (int dz = -1; dz <= 1; ++dz) {
         for (int dx = -1; dx <= 1; ++dx) {
            counts[this.value(this.levels, x + dx, z + dz)]++;
         }
      }
      int best = center;
      for (int b = 0; b < this.n; ++b) {
         if (counts[b] > counts[best]) best = b;
      }
      // The two deliberately rare special habitats have broad, coherent
      // source cells but can be outvoted by ordinary forest around their
      // narrow/irregular margins. Never let the final 3x3 majority pass erase
      // every surviving karst or Bloomlands core cell.
      boolean preserveRareCore = center == WildBiome.VOLCANIC_BARRENS.ordinal()
              || center == WildBiome.BLOOMLANDS.ordinal();
      int label = preserveRareCore || counts[center] >= 4 || counts[best] < 3 ? center : best;
      if (regionMemo.size() > 250000) regionMemo.clear();
      regionMemo.put(key, (byte) label);
      return label;
   }

   double[] realisedShares() {
      return this.realised.clone();
   }

   double calibrationError() {
      return this.calibrationError;
   }

   // ------------------------------------------------------------------ calibration

   /**
    * The start grid draws biomes by prior, but what ends up on the map has been through majority zooms, smoothing and the
    * transition rules, which favour some biomes and eat others. So the priors are nudged until the finished labels, counted over a
    * large window, match the target shares (to a point or so: the buffer a rule makes cannot be less than the rule makes).
    */
   private void calibrate(double[] target) {
      double[] goal = new double[this.n];
      double total = 0.0;
      for (int i = 0; i < this.n; i++) {
         goal[i] = Math.max(0.0, target[i]);
         total += goal[i];
      }
      if (total <= 0.0) {
         goal[0] = 1.0;
         total = 1.0;
      }
      for (int i = 0; i < this.n; i++) {
         goal[i] /= total;
         this.prior[i] = goal[i];
      }
      final int level = 2;
      final int window = 256;
      double[] real = new double[this.n];
      for (int it = 0; it < 30; it++) {
         this.memo.clear();
         java.util.Arrays.fill(real, 0.0);
         for (int i = 0; i < window; i++) {
            for (int j = 0; j < window; j++) {
               real[this.value(level, i - window / 2 + 311, j - window / 2 - 187)] += 1.0;
            }
         }
         double err = 0.0;
         for (int b = 0; b < this.n; b++) {
            real[b] /= (double) window * window;
            err = Math.max(err, Math.abs(real[b] - goal[b]));
            this.realised[b] = real[b];
         }
         this.calibrationError = err;
         if (err < 0.008) {
            break;
         }
         double sum = 0.0;
         for (int b = 0; b < this.n; b++) {
            this.prior[b] = goal[b] <= 0.0 ? 0.0 : this.prior[b] * Math.pow((goal[b] + 1e-4) / (real[b] + 1e-4), 0.7);
            sum += this.prior[b];
         }
         if (sum <= 0.0) {
            this.prior[0] = 1.0;
            sum = 1.0;
         }
         for (int b = 0; b < this.n; b++) {
            this.prior[b] /= sum;
         }
      }
      this.memo.clear();
   }

   // ------------------------------------------------------------------ the layer stack

   private int lastStep(int level) {
      return level < CLEAN_FROM ? 0 : 1 + this.edgePasses;
   }

   /** The finished label of a cell at a level (its zoom, then its smoothing and transitions). */
   private int value(int level, int x, int z) {
      return this.stage(level, lastStep(level), x, z);
   }

   /** step 0 = the zoom of the level above, 1 = smoothed, 2.. = the transition passes. Memoised: the answer is a pure function of its arguments. */
   private int stage(int level, int step, int x, int z) {
      if (level == 0) {
         return this.start(x, z);
      }
      long key = key(level, step, x, z);
      Byte cached = this.memo.get(key);
      if (cached != null) {
         return cached;
      }
      int v;
      if (step == 0) {
         v = this.zoom(level, x, z);
      } else {
         int cur = this.stage(level, step - 1, x, z);
         if (level < CLEAN_FROM) {
            v = cur;
         } else if (step == 1) {
            v = this.smooth(level, x, z, cur);
         } else {
            v = this.edge(level, step, x, z, cur);
         }
      }
      this.memo.put(key, (byte) v);
      if ((++this.puts & 0x3FFF) == 0 && this.memo.size() > MAX_CACHED) {
         this.memo.clear();
      }
      return v;
   }

   private static long key(int level, int step, int x, int z) {
      return ((long) (level * 8 + step) << 56) ^ ((long) (x & 0xFFFFFFF) << 28) ^ (long) (z & 0xFFFFFFF);
   }

   /** Level 0: a random biome per coarse cell, by the (calibrated) priors. */
   private int start(int x, int z) {
      double u = (hash(0, x, z) >>> 11) * (1.0 / (1L << 53));
      double acc = 0.0;
      int last = 0;
      for (int k = 0; k < this.n; k++) {
         if (this.prior[k] > 0.0) {
            last = k;
            acc += this.prior[k];
            if (u < acc) {
               return k;
            }
         }
      }
      return last;
   }

   /** One cell of the zoom: the four cells of a parent block copy their parent, or the parents' majority, or one of them by coin flip. */
   private int zoom(int level, int cx, int cz) {
      int i = Math.floorDiv(cx, 2);
      int j = Math.floorDiv(cz, 2);
      int a = cx - 2 * i;
      int b = cz - 2 * j;
      int p00 = this.value(level - 1, i, j);
      int p10 = this.value(level - 1, i + 1, j);
      int p01 = this.value(level - 1, i, j + 1);
      int p11 = this.value(level - 1, i + 1, j + 1);
      if (a == 0 && b == 0) {
         return p00;
      }
      if (a == 1 && b == 0) {
         return (hash(level, cx, cz) & 1) == 0 ? p00 : p10;
      }
      if (a == 0 && b == 1) {
         return (hash(level, cx, cz) & 1) == 0 ? p00 : p01;
      }
      int[] v = {p00, p10, p01, p11};
      int best = -1;
      int bestCount = 0;
      int tied = 0;
      for (int k = 0; k < 4; k++) {
         int c = 0;
         for (int m = 0; m < 4; m++) {
            if (v[m] == v[k]) {
               c++;
            }
         }
         if (c > bestCount) {
            bestCount = c;
            best = v[k];
            tied = 1;
         } else if (c == bestCount && v[k] != best) {
            tied++;
         }
      }
      if (bestCount >= 2 && tied == 1) {
         return best;
      }
      return v[(int) (hash(level, cx, cz) >>> 7 & 3)];
   }

   /** Vanilla's smoothing: a cell whose opposite neighbours agree with each other and not with it takes their value. */
   private int smooth(int level, int x, int z, int cur) {
      int l = this.stage(level, 0, x - 1, z);
      int r = this.stage(level, 0, x + 1, z);
      int u = this.stage(level, 0, x, z - 1);
      int d = this.stage(level, 0, x, z + 1);
      if (l == r && u == d) {
         return (hash(level * 7, x, z) & 1) == 0 ? l : u;
      }
      if (l == r) {
         return l;
      }
      if (u == d) {
         return u;
      }
      return cur;
   }

   /** The transition rules: a cell that touches a listed biome becomes the buffer biome. */
   private int edge(int level, int step, int x, int z, int cur) {
      if (this.rules.length == 0) {
         return cur;
      }
      int[] nb = {
            this.stage(level, step - 1, x - 1, z), this.stage(level, step - 1, x + 1, z),
            this.stage(level, step - 1, x, z - 1), this.stage(level, step - 1, x, z + 1)};
      for (int m : nb) {
         for (int[] r : this.rules) {
            if (cur == r[0] && m == r[1]) {
               return r[2];
            }
         }
      }
      return cur;
   }

   private long hash(int level, int a, int b) {
      long x = this.seed * 0x9E3779B97F4A7C15L ^ (long) level * 0xC2B2AE3D27D4EB4FL ^ (long) a * 0x165667B19E3779F9L ^ (long) b * 0x27D4EB2F165667C5L;
      x ^= x >>> 33;
      x *= 0xFF51AFD7ED558CCDL;
      x ^= x >>> 33;
      x *= 0xC4CEB9FE1A85EC53L;
      x ^= x >>> 33;
      return x;
   }

   private static double clamp1(double v) {
      return v < -1.0 ? -1.0 : (v > 1.0 ? 1.0 : v);
   }
}
