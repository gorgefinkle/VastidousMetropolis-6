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
   static final double MIN_RARE_CELL_BLOCKS = 512.0;

   private final long seed;
   private final int n;
   private final double[] prior;
   private final int[][] rules;
   private final double cellFinal;
   private final int rareCellDivisor;
   private final double rareCellBlocks;
   private final double warpBlocks;
   private final Noise2D warpX;
   private final Noise2D warpZ;
   private final ConcurrentHashMap<Long, Byte> memo = new ConcurrentHashMap<Long, Byte>();
   // Bounded, world-local cache of neighborhood-consistent final region labels.
   private final ConcurrentHashMap<Long, Byte> regionMemo = new ConcurrentHashMap<Long, Byte>();
   private int puts;
   private final double[] realised;
   /** Target-normalized shares for region-scale rare-biome seeding. */
   private final double bloomlandsCellShare;
   private final double karstCellShare;
   /** Rare-province macro tile; a 16,384-block sector at the default scale. */
   private final int rareGroupsPerSide;
   private final ConcurrentHashMap<Long, byte[]> rareTileMemo = new ConcurrentHashMap<Long, byte[]>();
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
      this.cellFinal = commonFinalCellBlocks(scaleChunks, this.levels);
      // Preserve a physically useful scale for Bloomlands fungi and Karst
      // formations even when the common biome grid uses its finest settings.
      this.rareCellDivisor = rareCellDivisor(this.cellFinal);
      this.rareCellBlocks = this.cellFinal * this.rareCellDivisor;
      this.warpBlocks = Math.min(this.cellFinal * 0.75, 48.0) * (Math.max(0, Math.min(100, warpPercent)) / 100.0);
      this.warpX = new Noise2D(this.seed ^ 0x77A1L);
      this.warpZ = new Noise2D(this.seed ^ 0x77A2L);
      this.prior = new double[this.n];
      this.realised = new double[this.n];
      double targetTotal = 0.0;
      for (double share : target) targetTotal += Math.max(0.0, share);
      this.bloomlandsCellShare = targetTotal > 0.0
              ? Math.max(0.0, target[WildBiome.BLOOMLANDS.ordinal()]) / targetTotal : 0.0;
      this.karstCellShare = targetTotal > 0.0
              ? Math.max(0.0, target[WildBiome.VOLCANIC_BARRENS.ordinal()]) / targetTotal : 0.0;
      this.rareGroupsPerSide = Math.max(6, Math.min(32,
              (int)Math.round(16384.0 / (2.0 * this.rareCellBlocks))));
      this.calibrate(target);
   }

   static double commonFinalCellBlocks(double scaleChunks,int zoomLevels) {
      int zooms=Math.max(3,Math.min(6,zoomLevels));
      double cell0=Math.max(256.0,scaleChunks*16.0*1.3);
      return cell0/(1<<zooms);
   }
   static int rareCellDivisor(double commonCellBlocks) {
      return Math.max(1,(int)Math.ceil(MIN_RARE_CELL_BLOCKS/commonCellBlocks));
   }
   static double configuredRareCellBlocks(double scaleChunks,int zoomLevels) {
      double common=commonFinalCellBlocks(scaleChunks,zoomLevels);
      return common*rareCellDivisor(common);
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
      // Special habitats use a separate physical lattice. Each 2x2 rare
      // province is at least 1,024 blocks across (about 1,352 at defaults),
      // leaving room for giant fungi and the large Mother Willow footprint.
      int center = this.rareRegionLabel(x, z);
      int[] counts = new int[this.n];
      for (int dz = -1; dz <= 1; ++dz) {
         for (int dx = -1; dx <= 1; ++dx) {
            counts[this.rareRegionLabel(x + dx, z + dz)]++;
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

   /** The common map plus a world-seed-stable quota of rare provinces per macro tile. */
   private int rareRegionLabel(int x, int z) {
      int groups = this.rareGroupsPerSide;
      int rareX = Math.floorDiv(x, this.rareCellDivisor);
      int rareZ = Math.floorDiv(z, this.rareCellDivisor);
      // Centre the macro grid on world origin.
      int groupX = Math.floorDiv(rareX, 2);
      int groupZ = Math.floorDiv(rareZ, 2);
      int centeredX = groupX + groups / 2;
      int centeredZ = groupZ + groups / 2;
      int tileX = Math.floorDiv(centeredX, groups);
      int tileZ = Math.floorDiv(centeredZ, groups);
      int localX = Math.floorMod(centeredX, groups);
      int localZ = Math.floorMod(centeredZ, groups);
      long tileKey = ((long)tileX << 32) ^ (tileZ & 0xffffffffL);
      byte[] plan = this.rareTileMemo.get(tileKey);
      if (plan == null) {
         byte[] created = this.buildRareTile(tileX, tileZ);
         byte[] prior = this.rareTileMemo.putIfAbsent(tileKey, created);
         plan = prior == null ? created : prior;
         if (this.rareTileMemo.size() > 4096) this.rareTileMemo.clear();
      }
      int rare = plan[localZ * groups + localX];
      // Store ordinal+1 in the tile plan; zero means use the coarse common map.
      return rare == 0 ? this.value(this.levels, x, z) : rare - 1;
   }

   double rareCellBlocks() { return this.rareCellBlocks; }
   double rareProvinceBlocks() { return this.rareCellBlocks * 2.0; }
   int rareCellDivisor() { return this.rareCellDivisor; }

   /**
    * Select deterministic, non-overlapping 2x2 rare-cell provinces inside
    * each macro tile. The quota is rounded stochastically per tile, so the
    * long-run land share remains the configured target while every normal
    * 16k world sector receives its expected rare-habitat candidates.
    */
   private byte[] buildRareTile(int tileX, int tileZ) {
      int groups = this.rareGroupsPerSide;
      byte[] labels = new byte[groups * groups];
      int margin = 1;
      int side = Math.max(1, groups - margin * 2);
      int slots = side * side;
      boolean[] occupied = new boolean[labels.length];
      long seedHash = this.hash(0x52415245, tileX, tileZ);
      int bloomCount = stochasticQuota(this.bloomlandsCellShare * groups * groups,
              seedHash ^ 0x424c4f4f4dL, slots);
      int karstCount = stochasticQuota(this.karstCellShare * groups * groups,
              seedHash ^ 0x4b41525354L, Math.max(0, slots - bloomCount));
      placeRareGroups(labels, occupied, WildBiome.BLOOMLANDS.ordinal(),
              bloomCount, tileX, tileZ, side, margin, 0x424c4f4f);
      placeKarstGroupsWithMountainRing(labels, occupied, karstCount, tileX, tileZ, groups);
      return labels;
   }

   private static int stochasticQuota(double expected, long seed, int maximum) {
      if (maximum <= 0 || expected <= 0.0) return 0;
      int count = Math.min(maximum, (int)Math.floor(expected));
      double fraction = expected - Math.floor(expected);
      double roll = (seed >>> 11) * (1.0 / (1L << 53));
      if (roll < fraction && count < maximum) count++;
      return count;
   }

   /**
    * A Karstlands province is a basin inside a mountain range, not an exposed
    * biome that relies on a city-distance exclusion radius. Reserve its full
    * eight-neighbour ring as mountains on the same physical rare-province
    * lattice. The two-cell-wide Karst core is therefore enclosed on the north,
    * south, east, west and all four diagonal sides by a complete mountain belt.
    *
    * The ring is accepted atomically: if any of its eight mountain cells or
    * the central Karst cell is already reserved by another rare province, try
    * another deterministic slot rather than emitting a broken/partial range.
    */
   private void placeKarstGroupsWithMountainRing(byte[] labels, boolean[] occupied,
                                                  int count, int tileX, int tileZ, int groups) {
      final int margin = 2; // one empty macro-cell beyond the mountain ring at every sector edge
      final int side = Math.max(1, groups - margin * 2);
      final int slots = side * side;
      for (int i = 0; i < count; i++) {
         int start = (int) Math.floorMod(this.hash(0x4b415253 + i, tileX, tileZ), (long) slots);
         boolean placed = false;
         for (int attempt = 0; attempt < slots; attempt++) {
            int slot = (start + attempt) % slots;
            int localX = margin + slot % side;
            int localZ = margin + slot / side;
            int center = localZ * groups + localX;
            boolean free = !occupied[center];
            for (int dz = -1; dz <= 1 && free; dz++) {
               for (int dx = -1; dx <= 1; dx++) {
                  if (dx == 0 && dz == 0) continue;
                  int neighbour = (localZ + dz) * groups + localX + dx;
                  if (occupied[neighbour]) {
                     free = false;
                     break;
                  }
               }
            }
            if (!free) continue;

            occupied[center] = true;
            labels[center] = (byte) (WildBiome.VOLCANIC_BARRENS.ordinal() + 1);
            for (int dz = -1; dz <= 1; dz++) {
               for (int dx = -1; dx <= 1; dx++) {
                  if (dx == 0 && dz == 0) continue;
                  int neighbour = (localZ + dz) * groups + localX + dx;
                  occupied[neighbour] = true;
                  labels[neighbour] = (byte) (WildBiome.MOUNTAINS.ordinal() + 1);
               }
            }
            placed = true;
            break;
         }
         if (!placed) return; // no partial Karst range if the tile is saturated
      }
   }

   private void placeRareGroups(byte[] labels, boolean[] occupied, int biomeOrdinal,
                                int count, int tileX, int tileZ, int side, int margin, int salt) {
      if (count <= 0) return;
      int groups = this.rareGroupsPerSide;
      int slots = side * side;
      for (int i = 0; i < count; i++) {
         int slot = (int)Math.floorMod(this.hash(salt + i, tileX, tileZ), (long)slots);
         int attempts = 0;
         while (occupied[slot] && attempts++ < slots) slot = (slot + 1) % slots;
         if (occupied[slot]) return;
         occupied[slot] = true;
         int localX = margin + slot % side;
         int localZ = margin + slot / side;
         labels[localZ * groups + localX] = (byte)(biomeOrdinal + 1);
      }
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
      // Calibrate the exact final map, not level 2 of the zoom stack. The
      // old intermediate-level estimate could report the requested rare-biome
      // share even when the final zoom, domain warp and 3x3 region protection
      // produced a materially different distribution.
      final int samples = 4096;
      double[] real = new double[this.n];
      for (int it = 0; it < 16; it++) {
         this.memo.clear();
         this.regionMemo.clear();
         java.util.Arrays.fill(real, 0.0);
         for (int sample = 0; sample < samples; sample++) {
            // Use independent hash-distributed world coordinates. A regular
            // lattice can alias the zoom cells and domain warp, making a
            // rare habitat appear to occupy most of the calibration sample
            // even when a uniform random survey shows it is uncommon.
            int x = (int) Math.floorMod(this.hash(91 + it, sample, 311), 600001L) - 300000;
            int z = (int) Math.floorMod(this.hash(173 + it, sample, -187), 600001L) - 300000;
            real[this.label(x, z)] += 1.0;
         }
         double err = 0.0;
         for (int b = 0; b < this.n; b++) {
            real[b] /= (double) samples;
            err = Math.max(err, Math.abs(real[b] - goal[b]));
            this.realised[b] = real[b];
         }
         this.calibrationError = err;
         if (err < 0.006) {
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
      // Bloomlands and Karstlands are deliberately omitted from the coarse
      // source grid; rareRegionLabel() places them at the final region scale.
      // Renormalize common priors so rare shares are not redistributed to the
      // last ordinary biome when the coarse map is sampled.
      double commonTotal = 0.0;
      for (int k = 0; k < this.n; k++) {
         if (k != WildBiome.BLOOMLANDS.ordinal()
                 && k != WildBiome.VOLCANIC_BARRENS.ordinal()) {
            commonTotal += Math.max(0.0, this.prior[k]);
         }
      }
      if (commonTotal <= 0.0) return WildBiome.FOREST.ordinal();
      double u = (hash(0, x, z) >>> 11) * (1.0 / (1L << 53)) * commonTotal;
      double acc = 0.0;
      int last = WildBiome.FOREST.ordinal();
      for (int k = 0; k < this.n; k++) {
         if (k == WildBiome.BLOOMLANDS.ordinal()
                 || k == WildBiome.VOLCANIC_BARRENS.ordinal()) continue;
         if (this.prior[k] > 0.0) {
            last = k;
            acc += this.prior[k];
            if (u < acc) return k;
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
      // A rare biome cell is a coherent output of the zoom stack. Do not let
      // the later cellular majority smoother erase its core; narrow margins
      // still transition naturally in adjacent common-biome cells.
      if (cur == WildBiome.VOLCANIC_BARRENS.ordinal()
              || cur == WildBiome.BLOOMLANDS.ordinal()) return cur;
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
