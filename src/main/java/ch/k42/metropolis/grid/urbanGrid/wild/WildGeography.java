package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.forest.KarstLandforms;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic habitat suitability field (R30ea reconstruction).
 * Derived from the R30ea field/method bytecode; not a new biome-distribution design.
 */
public final class WildGeography {
   private final WaterField water;
   private final OceanProvider ocean;
   private final Noise2D rainfall;
   private final Noise2D temperature;
   private final Noise2D shoreExposure;
   private final WildRelief relief;
   private final int riverReach;
   private final int duneReach;
   private final int minimumRegionBlocks;
   private final long reliefSeed;
   private final ConcurrentHashMap<Long, double[]> habitatNodes = new ConcurrentHashMap<>();
   private static final int HABITAT_NODE = 128;
   /** Coarse cached exact distance samples used to keep Karst massifs inland of all watercourses. */
   private final ConcurrentHashMap<Long, Float> karstWaterNodes = new ConcurrentHashMap<>();
   private static final int KARST_WATER_NODE = 256;
   private static final double KARST_WATER_SCAN_RADIUS = 3200.0;
   private static final double KARST_WATER_START = 1600.0;
   private static final double KARST_WATER_FULL = 2400.0;
   private final Map<Long, Float> riverNodes = new LinkedHashMap<>(1024, .75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Long, Float> entry) { return size() > 4096; }
   };

   public WildGeography(long seed, WildRelief relief, int riverReachChunks, int duneReach) {
      this(seed, relief, riverReachChunks, duneReach, 480);
   }
   public WildGeography(long seed, WildRelief relief, int riverReachChunks, int duneReach, int minimumRegionBlocks) {
      this.reliefSeed = seed;
      this.water = WorldLayouts.waterFor(seed);
      this.ocean = water.ocean();
      this.relief = relief;
      this.rainfall = new Noise2D(seed ^ 6289642210670168397L);
      this.temperature = new Noise2D(seed ^ 6072344679851055429L);
      this.shoreExposure = new Noise2D(seed ^ 5999162233728221523L);
      this.riverReach = Math.max(1, Math.min(24, riverReachChunks)) * 16;
      this.duneReach = Math.max(32, Math.min(1024, duneReach));
      this.minimumRegionBlocks = Math.max(0, Math.min(2048, minimumRegionBlocks));
   }
   private static double ramp(double from, double to, double value) {
      double t = Math.max(0, Math.min(1, (value - from) / (to - from)));
      return t * t * (3.0 - 2.0 * t);
   }
   public double riverProximity(int x, int z) {
      int nx = Math.floorDiv(x, 128), nz = Math.floorDiv(z, 128);
      double tx = (x - nx * 128) / 128.0, tz = (z - nz * 128) / 128.0;
      double a = node(nx, nz), b = node(nx + 1, nz), c = node(nx, nz + 1), d = node(nx + 1, nz + 1);
      return ((a * (1 - tx) + b * tx) * (1 - tz)) + ((c * (1 - tx) + d * tx) * tz);
   }
   private double node(int ix, int iz) {
      long key = ((long) ix << 32) ^ ((long) iz & 0xffffffffL);
      synchronized (riverNodes) {
         Float prev = riverNodes.get(key);
         if (prev != null) return (double) prev;
      }
      int gx = ix * 8, gz = iz * 8;
      int radius = (int) Math.ceil((double) riverReach / 16.0) + 2;
      double nearest = Double.POSITIVE_INFINITY;
      for (int dx = -radius; dx <= radius; dx += 2) {
         for (int dz = -radius; dz <= radius; dz += 2) {
            double distance = Math.hypot((double)dx * 16.0, (double)dz * 16.0);
            if (distance >= nearest || distance > riverReach + 32.0) continue;
            if (water.riverAt(gx + dx, gz + dz) != null) nearest = distance;
         }
      }
      float v = (float)(1.0 - ramp(riverReach * .18, riverReach, nearest));
      synchronized (riverNodes) { riverNodes.put(key, v); }
      return (double) v;
   }
   public double coastalProximity(int x, int z) {
      if (ocean == null) return 0.0;
      double signed = ocean.oceanDistanceInsideShoreBlock((double)x, (double)z);
      if (!Double.isFinite(signed) || signed >= 0.0) return 0.0;
      double dryDistance = -signed;
      double exposure = 0.5 + .5 * shoreExposure.fbm(x / 2300.0, z / 2300.0, 3, .55);
      return ramp(12.0, 65.0, dryDistance)
            * (1.0 - ramp(duneReach * .62, (double)duneReach, dryDistance))
            * ramp(.32, .65, exposure);
   }
   /** Distance to the nearest ocean, main-river or accepted-creek bank, in world blocks. */
   private double karstWaterDistanceAtNode(int ix,int iz) {
      long key=((long)ix<<32)^(iz&0xffffffffL);
      Float cached=karstWaterNodes.get(key);
      if(cached!=null)return cached.doubleValue();
      double x=ix*(double)KARST_WATER_NODE,z=iz*(double)KARST_WATER_NODE;
      double nearest=Double.POSITIVE_INFINITY;
      if(ocean!=null) {
         double signed=ocean.oceanDistanceInsideShoreBlock(x,z);
         if(Double.isFinite(signed)) nearest=Math.min(nearest,Math.max(0.0,-signed));
      }
      nearest=Math.min(nearest,riverBankDistance(water.mainRiver(),x,z));
      nearest=Math.min(nearest,riverBankDistance(water.zBoundRiver(),x,z));
      double creek=water.nearestCreekBankDistanceBlocks(x,z,KARST_WATER_SCAN_RADIUS);
      if(Double.isFinite(creek)&&creek>=0.0)nearest=Math.min(nearest,creek);
      float stored=(float)Math.min(KARST_WATER_SCAN_RADIUS*2.0,nearest);
      if(karstWaterNodes.size()>16384)karstWaterNodes.clear();
      Float prior=karstWaterNodes.putIfAbsent(key,stored);
      return prior==null?(double)stored:prior.doubleValue();
   }

   private static double riverBankDistance(RiverProvider river,double x,double z) {
      if(river==null||!river.isEnabled())return Double.POSITIVE_INFINITY;
      double across=river.distanceFromCenterBlock(x,z);
      double along=river.isZBound()?z:x;
      double width=river.widthAtBlock(along);
      if(!Double.isFinite(across)||!Double.isFinite(width))return Double.POSITIVE_INFINITY;
      return Math.max(0.0,Math.abs(across)-width*.5-river.getFallbackBankMarginBlocks());
   }

   /** Interpolated water separation keeps an entire broad massif inland, not merely its center point. */
   private double karstWaterDistance(int x,int z) {
      int nx=Math.floorDiv(x,KARST_WATER_NODE),nz=Math.floorDiv(z,KARST_WATER_NODE);
      double tx=(x-nx*KARST_WATER_NODE)/(double)KARST_WATER_NODE;
      double tz=(z-nz*KARST_WATER_NODE)/(double)KARST_WATER_NODE;
      double a=karstWaterDistanceAtNode(nx,nz),b=karstWaterDistanceAtNode(nx+1,nz);
      double c=karstWaterDistanceAtNode(nx,nz+1),d=karstWaterDistanceAtNode(nx+1,nz+1);
      return (a*(1-tx)+b*tx)*(1-tz)+(c*(1-tx)+d*tx)*tz;
   }

   /** Smoothly excludes the whole Karst landform from coasts and river/creek corridors. */
   private double karstInlandSuitability(int x,int z) {
      return ramp(KARST_WATER_START,KARST_WATER_FULL,karstWaterDistance(x,z));
   }

   public double regionSuitability(WildBiome biome, int x, int z) {
      if (minimumRegionBlocks == 0 || (biome != WildBiome.PRAIRIE && biome != WildBiome.WETLANDS &&
            biome != WildBiome.OLD_GROWTH && biome != WildBiome.DECIDUOUS &&
            biome != WildBiome.COASTAL_DUNES)) {
         return suitability(biome, x, z);
      }
      int nx = Math.floorDiv(x, 128), nz = Math.floorDiv(z, 128);
      double tx = (x - nx * 128) / 128.0, tz = (z - nz * 128) / 128.0;
      int i = biome.ordinal();
      double a = habitatNode(nx, nz)[i], b = habitatNode(nx + 1, nz)[i];
      double c = habitatNode(nx, nz + 1)[i], d = habitatNode(nx + 1, nz + 1)[i];
      return ((a * (1 - tx) + b * tx) * (1 - tz)) + ((c * (1 - tx) + d * tx) * tz);
   }
   private double[] habitatNode(int ix, int iz) {
      long key = ((long) ix << 32) ^ ((long) iz & 0xffffffffL);
      if (habitatNodes.size() > 32768) habitatNodes.clear();
      return habitatNodes.computeIfAbsent(key, unused -> {
      double[] result = new double[WildBiome.ALL.length];
      int x = ix * 128, z = iz * 128;
      int distance = Math.max(64, minimumRegionBlocks / 2);
      for (WildBiome biome : new WildBiome[] { WildBiome.PRAIRIE, WildBiome.WETLANDS, WildBiome.OLD_GROWTH,
            WildBiome.DECIDUOUS, WildBiome.COASTAL_DUNES, WildBiome.VOLCANIC_BARRENS }) {
         double center = suitability(biome, x, z);
         if (center <= .00001) continue;
         double average = (center + suitability(biome, x + distance, z) + suitability(biome, x - distance, z)
               + suitability(biome, x, z + distance) + suitability(biome, x, z - distance)) / 5.0;
         result[biome.ordinal()] = center * ramp(.36, .64, average);
      }
      return result;
      });
   }
   public double suitability(WildBiome biome, int x, int z) {
      if (biome == WildBiome.COASTAL_DUNES) return coastalProximity(x, z);
      if (biome == WildBiome.VOLCANIC_BARRENS) {
         // Preserve the original fracture-habitat footprint: inland distance
         // is an exclusion mask, not the habitat itself. Replacing the
         // landform mask with this distance alone made nearly every inland
         // column Karst and starved Bloomlands in the production picker.
         return KarstLandforms.habitat(this.reliefSeed,x,z)
                 * karstInlandSuitability(x,z);
      }
      double rain = .5 + .5 * rainfall.fbm(x / 1450.0, z / 1450.0, 3, .55);
      double temp = .5 + .5 * temperature.fbm(x / 2100.0, z / 2100.0, 3, .5);
      if (biome == WildBiome.WETLANDS) return Math.max(riverProximity(x,z), .72 * coastalProximity(x,z)) * ramp(.31, .56, rain);
      if (biome == WildBiome.PRAIRIE) return (1.0 - ramp(.38, .78, rain)) * ramp(.2, .42, temp);
      if (biome == WildBiome.OLD_GROWTH) return ramp(.44, .76, rain) * (1.0 - ramp(.78, .96, temp));
      if (biome == WildBiome.DECIDUOUS) return ramp(.2, .43, rain) * (1.0 - ramp(.79, .98, rain))
            * ramp(.2, .38, temp) * (1.0 - ramp(.79, .98, temp));
      return 1.0;
   }
}
