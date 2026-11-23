package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mountain ranges for forest regions that are big enough to sit between cities
 * (generator.forest.mountains.*, PluginConfig.isForestMountainsEnabled()).
 *
 * A region qualifies when its area is at least minCells chunks AND its deepest
 * point is at least minDepth chunks from every edge, so a long thin strip of
 * forest never becomes a range. A qualifying region gets a Field: the exact
 * distance from every block to the region's edge, computed once per region.
 * ForestGenerator uses that for the edge fade too (the ordinary edge scan only
 * sees a few chunks, a range needs the distance to the edge from 100+ blocks in).
 *
 * The relief is ridged, domain-warped noise raised by how deep inside the region
 * a block is, so the range climbs from the edges to a core of crests and valleys
 * instead of one hill. Everything is a pure function of world coordinates and
 * the region's mask, like the rest of the forest generator.
 */
public final class ForestMountains {
   /** Blocks of "outside" kept around the region's bounding box so every inside block has an edge to measure to. */
   private static final int PAD = 16;
   private static final double INF = 1e12;

   private final SimplexOctaveGenerator broad;
   private final SimplexOctaveGenerator mid;
   private final SimplexOctaveGenerator fine;
   private final SimplexOctaveGenerator warpA;
   private final SimplexOctaveGenerator warpB;
   private final SimplexOctaveGenerator massif;
   private final SimplexOctaveGenerator lowland;
   /** Mask noise value below which ground stays lowland; derived from generator.forest.mountains.lowlands. */
   private final double lowlandThreshold;
   private final boolean lowlandsOn;
   /** Rough spread of a normalized 2-octave noise value (the clearing noise's "a quarter is open" cut implies about this). */
   private static final double NOISE_SPREAD = 0.42;

   /**
    * Regions belong to the world plan and live as long as it does, so their fields are kept in a small LRU instead:
    * a field is a distance map of the region's whole bounding box. An evicted field is rebuilt (same result) on demand.
    */
   private static final int MAX_FIELDS = 4;
   private final Map<MorphomosRegion, Field> fields = new LinkedHashMap<MorphomosRegion, Field>(16, 0.75f, true) {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(Map.Entry<MorphomosRegion, Field> eldest) {
         return this.size() > MAX_FIELDS;
      }
   };
   /** Stored for regions that don't qualify, so the test runs once. */
   private static final Field NONE = new Field(0, 0, 1, 1, new float[1], 0.0, 1.0, 0);

   public ForestMountains(long worldSeed) {
      long seed = worldSeed ^ 0x4D4F554E5441L;
      this.broad = octave(seed ^ 0x1L, 2, 1.0 / 320.0);
      this.mid = octave(seed ^ 0x2L, 3, 1.0 / 120.0);
      this.fine = octave(seed ^ 0x3L, 2, 1.0 / 42.0);
      this.warpA = octave(seed ^ 0x4L, 2, 1.0 / 210.0);
      this.warpB = octave(seed ^ 0x5L, 2, 1.0 / 210.0);
      this.massif = octave(seed ^ 0x6L, 2, 1.0 / 460.0);
      this.lowland = octave(seed ^ 0x7L, 2, 1.0 / 260.0);
      int pct = PluginConfig.getForestMountainLowlands();
      this.lowlandsOn = pct > 0;
      this.lowlandThreshold = this.lowlandsOn ? NOISE_SPREAD * inverseNormal(Math.min(0.95, pct / 100.0)) : 0.0;
   }

   /** Inverse of the standard normal CDF (Abramowitz & Stegun 26.2.23), good to about 4e-4. */
   private static double inverseNormal(double p) {
      if (p < 0.5) {
         return -inverseNormal(1.0 - p);
      }
      double t = Math.sqrt(-2.0 * Math.log(1.0 - p));
      return t - (2.515517 + 0.802853 * t + 0.010328 * t * t)
              / (1.0 + 1.432788 * t + 0.189269 * t * t + 0.001308 * t * t * t);
   }

   private static SimplexOctaveGenerator octave(long seed, int octaves, double scale) {
      SimplexOctaveGenerator g = new SimplexOctaveGenerator(seed, octaves);
      g.setScale(scale);
      return g;
   }

   /** The field for this region, or null when it is too small / too thin to become a range. */
   public synchronized Field fieldFor(MorphomosRegion region) {
      Field field = this.fields.get(region);
      if (field == null) {
         field = build(region);
         this.fields.put(region, field);
      }
      return field == NONE ? null : field;
   }

   private static Field build(MorphomosRegion region) {
      if (region.getCellCount() < PluginConfig.getForestMountainMinCells()) {
         return NONE;
      }
      final int minCx = region.getMinX();
      final int minCz = region.getMinZ();
      final int w = (region.getMaxX() - minCx + 1) * 16 + 2 * PAD;
      final int h = (region.getMaxZ() - minCz + 1) * 16 + 2 * PAD;
      final int originX = minCx * 16 - PAD;
      final int originZ = minCz * 16 - PAD;

      // 0 = outside the region, INF = inside; the transform turns INF into squared distance to the nearest 0.
      double[] grid = new double[w * h];
      for (int[] r : region.getRects()) {
         fill(grid, w, h, originX, originZ, r[0], r[1], r[2] - r[0] + 1, r[3] - r[1] + 1);
      }
      for (int[] cell : region.getBridgeCells()) {
         fill(grid, w, h, originX, originZ, cell[0], cell[1], 1, 1);
      }
      squaredDistance(grid, w, h);

      float[] dist = new float[w * h];
      double deepest = 0.0;
      for (int i = 0; i < dist.length; i++) {
         // block centre to the nearest outside block centre, less one block: 0 on the outermost ring of the region
         double d = Math.max(0.0, Math.sqrt(grid[i]) - 1.0);
         dist[i] = (float) d;
         if (d > deepest) {
            deepest = d;
         }
      }

      int deepestChunks = (int) (deepest / 16.0);
      int minDepth = PluginConfig.getForestMountainMinDepth();
      if (deepestChunks < minDepth) {
         return NONE;
      }

      // Larger regions get taller peaks: the smallest that qualify get half the configured height.
      double growth = ForestTerrain.clamp((deepestChunks - minDepth) / (double) minDepth, 0.0, 1.0);
      double peak = PluginConfig.getForestMountainHeight() * (0.5 + 0.5 * growth);
      // A region too small for the configured ramp still reaches its peak before its core.
      double ramp = Math.min(PluginConfig.getForestMountainRamp(), Math.max(32.0, 0.85 * deepest));
      return new Field(originX, originZ, w, h, dist, peak, ramp, (int) deepest);
   }

   /** Marks the block rectangle of a chunk-cell rectangle as inside. */
   private static void fill(double[] grid, int w, int h, int originX, int originZ,
                            int chunkX, int chunkZ, int chunksX, int chunksZ) {
      int x0 = chunkX * 16 - originX;
      int z0 = chunkZ * 16 - originZ;
      int x1 = x0 + chunksX * 16;
      int z1 = z0 + chunksZ * 16;
      for (int z = Math.max(0, z0); z < Math.min(h, z1); z++) {
         int row = z * w;
         for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
            grid[row + x] = INF;
         }
      }
   }

   /**
    * Exact squared Euclidean distance transform (Felzenszwalb & Huttenlocher):
    * in place, grid[i] becomes the squared distance to the nearest cell that held 0.
    */
   private static void squaredDistance(double[] grid, int w, int h) {
      int n = Math.max(w, h);
      double[] f = new double[n];
      double[] out = new double[n];
      int[] v = new int[n];
      double[] z = new double[n + 1];
      for (int x = 0; x < w; x++) {
         for (int y = 0; y < h; y++) {
            f[y] = grid[y * w + x];
         }
         transform1d(f, out, h, v, z);
         for (int y = 0; y < h; y++) {
            grid[y * w + x] = out[y];
         }
      }
      for (int y = 0; y < h; y++) {
         System.arraycopy(grid, y * w, f, 0, w);
         transform1d(f, out, w, v, z);
         System.arraycopy(out, 0, grid, y * w, w);
      }
   }

   private static void transform1d(double[] f, double[] out, int n, int[] v, double[] z) {
      int k = 0;
      v[0] = 0;
      z[0] = -INF;
      z[1] = INF;
      for (int q = 1; q < n; q++) {
         double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
         while (s <= z[k]) {
            k--;
            s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
         }
         k++;
         v[k] = q;
         z[k] = s;
         z[k + 1] = INF;
      }
      k = 0;
      for (int q = 0; q < n; q++) {
         while (z[k + 1] < q) {
            k++;
         }
         double dq = q - v[k];
         out[q] = dq * dq + f[v[k]];
      }
   }

   /**
    * Relief of a range region at a world column, before the edge fade: the
    * ordinary hilly-forest relief where the mask says lowland, the mountain
    * relief where it says range, and a smooth blend between, so the range rises
    * out of hills and meadows instead of covering the whole region.
    */
   public double reliefBlend(Field field, int x, int z, double ordinary) {
      double env = ForestTerrain.smooth(field.depth(x, z) / field.ramp);
      double m = 1.0;
      if (this.lowlandsOn) {
         // Mountains are favoured toward the core, lowlands toward the edges.
         double n = this.lowland.noise(x, z, 0.5, 0.5, true) + 0.35 * (env - 0.5);
         m = ForestTerrain.smooth((n - this.lowlandThreshold) / 0.30);
      }
      if (m <= 0.0) {
         return ordinary;
      }
      double mountain = relief(field, x, z);
      return ordinary + m * (mountain - ordinary);
   }

   /**
    * Height in blocks above the baseline at a world column, before the edge fade
    * is applied: 0 at the region edge, rising along the ramp to a core of ridges.
    */
   public double relief(Field field, int x, int z) {
      double env = ForestTerrain.smooth(field.depth(x, z) / field.ramp);
      if (env <= 0.0) {
         return 0.0;
      }
      // Warp the domain so the ridgelines wind instead of running straight.
      double wx = x + 60.0 * this.warpA.noise(x, z, 0.5, 0.5, true);
      double wz = z + 60.0 * this.warpB.noise(x, z, 0.5, 0.5, true);

      double ridges = 0.55 * ridge(this.broad, wx, wz) + 0.30 * ridge(this.mid, wx, wz) + 0.15 * ridge(this.fine, x, z);
      // Broad swells make some stretches of the range taller than others.
      double swell = 0.75 + 0.25 * this.massif.noise(x, z, 0.5, 0.5, true);
      double shape = (0.28 + 0.72 * ridges) * swell;
      return field.peak * env * ForestTerrain.clamp(shape, 0.0, 1.0);
   }

   /** 1 along a crest, falling away on both sides; cubed so crests stay narrow and valleys wide. */
   private static double ridge(SimplexOctaveGenerator g, double x, double z) {
      double r = 1.0 - Math.abs(g.noise(x, z, 0.5, 0.5, true));
      r = ForestTerrain.clamp(r, 0.0, 1.0);
      return r * r * r;
   }

   /** One qualifying region: its distance-to-edge field and the range's size. */
   public static final class Field {
      private final int originX;
      private final int originZ;
      private final int w;
      private final int h;
      private final float[] dist;
      final double peak;
      final double ramp;
      /** Blocks from the edge to the deepest point of the region. */
      public final int deepest;

      Field(int originX, int originZ, int w, int h, float[] dist, double peak, double ramp, int deepest) {
         this.originX = originX;
         this.originZ = originZ;
         this.w = w;
         this.h = h;
         this.dist = dist;
         this.peak = peak;
         this.ramp = ramp;
         this.deepest = deepest;
      }

      /** Blocks from this column to the region's edge; 0 on the outermost ring, and outside the region. */
      public double depth(int x, int z) {
         int ix = x - this.originX;
         int iz = z - this.originZ;
         if (ix < 0 || iz < 0 || ix >= this.w || iz >= this.h) {
            return 0.0;
         }
         return this.dist[iz * this.w + ix];
      }
   }
}
