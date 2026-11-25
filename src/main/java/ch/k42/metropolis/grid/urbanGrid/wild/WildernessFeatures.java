package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.grid.urbanGrid.context.CityRingField;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterFields;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSegment;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworks;
import ch.k42.metropolis.plugin.PluginConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Seed-stable wilderness landmarks. A rare alpine massif and named lakes are
 * selected only where their exact footprints fit outside the outer city/farm
 * rings. Lake outlets are routed through wilderness; intercity highways may
 * be crossed perpendicularly, but are never used as stream corridors.
 *
 * This is a plan, not a chunk-population pass. Every answer is a pure
 * world-coordinate lookup and is shared by all UrbanGrid tiles.
 */
public final class WildernessFeatures implements WaterField.LakeSource, WaterField.StreamSource {
   private static final int FEATURE_CELL_MULTIPLIER = 2;
   private static final int MOUNTAIN_CLEARANCE = 28;
   private static final int MAX_STREAM_RANGE = 220;
   private static final int MAX_ROUTE_EXPANSIONS = 40000;
   private static final int MAX_HIGHWAY_CROSSING_CHUNKS = 2;
   private static final int MOUNTAIN_THRESHOLD = 2;
   private static final int LAKE_THRESHOLD = 10;

   private static final Map<String, WildernessFeatures> CACHE = new HashMap<>();
   private static final String[] ROOTS = {
      "Ash", "Brindle", "Clear", "Dun", "Elder", "Fair", "Glen", "Hart", "Iron", "Juniper", "Kings", "Lark",
      "Marl", "North", "Oak", "Pine", "Quarry", "Raven", "Silver", "Stone", "Thorn", "Vale", "West", "Willow",
      "Amber", "Briar", "Cinder", "Dove", "Elm", "Fox", "Gold", "Heather"
   };
   private static final String[] ENDS = {
      "brook", "fall", "field", "ford", "mere", "mont", "reach", "ridge", "well", "crest", "hollow", "view",
      "water", "peak", "crown", "spire", "height", "glen", "vale", "stone", "wood", "haven", "mount", "frost"
   };

   private final long seed;
   private final int spacing;
   private final int cellSize;
   private final WaterField water;
   private final HighwayNetwork highways;
   private final CityRingField cities;
   private final Map<Long, FeatureCell> featureCells =
           new LinkedHashMap<Long, FeatureCell>(96, 0.75f, true) {
              @Override protected boolean removeEldestEntry(Map.Entry<Long, FeatureCell> eldest) {
                 return size() > 256;
              }
           };
   private final Map<Long, RiverProvider> streamChunkCache =
           new LinkedHashMap<Long, RiverProvider>(512, 0.75f, true) {
              @Override protected boolean removeEldestEntry(Map.Entry<Long, RiverProvider> eldest) {
                 return size() > 8192;
              }
           };

   public static synchronized WildernessFeatures forSeed(long seed) {
      int spacing = PluginConfig.getCityRingsSpacing();
      int lattice = Math.max(1, PluginConfig.getBlockSizeMin() + 2);
      String key = seed + ":" + spacing + ":" + lattice + ":" + PluginConfig.getCityRingsRadius()
              + ":" + PluginConfig.getCityRingsFarmBandWidth() + ":" + PluginConfig.getCityRingsFarmForestOverlap();
      WildernessFeatures found = CACHE.get(key);
      if (found != null) return found;
      WaterField water = WaterFields.forSeed(seed, lattice);
      HighwayNetwork highways = HighwayNetworks.forSeed(seed);
      found = new WildernessFeatures(seed, spacing, water, highways);
      CACHE.put(key, found);
      return found;
   }

   /** Install the feature masks before the first WorldLayout domain is solved. */
   public static void install(long seed) {
      if (!PluginConfig.isCityRingsEnabled() || !PluginConfig.isForestGenerationEnabled()) return;
      WildernessFeatures features = forSeed(seed);
      features.water.setLakes(features);
      features.water.setStreams(features);
   }

   private WildernessFeatures(long seed, int spacing, WaterField water, HighwayNetwork highways) {
      this.seed = seed;
      this.spacing = Math.max(16, spacing);
      this.cellSize = this.spacing * FEATURE_CELL_MULTIPLIER;
      this.water = water;
      this.highways = highways;
      this.cities = new CityRingField(seed);
   }

   private static long mix(long z) {
      z += 0x9E3779B97F4A7C15L;
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      return z ^ (z >>> 31);
   }

   private static double unit(long value) {
      return (value >>> 11) * (1.0 / 9007199254740992.0);
   }

   private static long cellKey(int x, int z) {
      return ((long)x << 32) ^ (z & 0xffffffffL);
   }

   private long cellHash(int gx, int gz) {
      return mix(this.seed ^ ((long)gx * 0x632BE59BD9B4E019L) ^ ((long)gz * 0x9E3779B97F4A7C15L));
   }

   private synchronized FeatureCell cell(int gx, int gz) {
      long key = cellKey(gx, gz);
      FeatureCell saved = this.featureCells.get(key);
      if (saved != null) return saved;
      FeatureCell made = this.buildCell(gx, gz);
      this.featureCells.put(key, made);
      return made;
   }

   private FeatureCell buildCell(int gx, int gz) {
      long h = cellHash(gx, gz);
      int kind = (int)Math.floor(unit(h) * 100.0);
      if (kind >= LAKE_THRESHOLD) return FeatureCell.EMPTY;

      int idealX = gx * this.cellSize + this.spacing;
      int idealZ = gz * this.cellSize + this.spacing;
      int attempts = 4;
      HighwayNetwork.Snapshot highwayMask = this.highways.snapshot(
              idealX - 72, idealZ - 72, idealX + 72, idealZ + 72);

      if (kind < MOUNTAIN_THRESHOLD) {
         int radius = 34 + (int)(mix(h ^ 0x4D4F554E5441494EL) >>> 60);
         for (int attempt = 0; attempt < attempts; attempt++) {
            int[] offset = candidateOffset(h, attempt, 22);
            int x = idealX + offset[0], z = idealZ + offset[1];
            if (!footprintClear(x, z, radius, radius, highwayMask, 3)) continue;
            if (!footprintClear(x, z, radius, radius, highwayMask, 1)) continue;
            int peakDrop = 8 + (int)(mix(h ^ 0x5045414B4C494E45L) >>> 61);
            MountainSite site = new MountainSite(this.seed, gx, gz, x * 16.0 + 8.0, z * 16.0 + 8.0,
                    radius * 16.0, radius * 16.0, unit(mix(h ^ 0x524944474553L)) * Math.PI,
                    MOUNTAIN_CLEARANCE + peakDrop);
            return new FeatureCell(site, null);
         }
         return FeatureCell.EMPTY;
      }

      // BoundedRiverSegment deliberately follows the server's river enable
      // switch; without a receiving river system there can be no connected lake outlet.
      if (!RiverConfig.isRiverEnabled()) return FeatureCell.EMPTY;
      int radius = 14 + (int)(mix(h ^ 0x4C414B4553495A45L) >>> 61);
      double ratio = 0.76 + unit(mix(h ^ 0x454C4C49505345L)) * 0.20;
      double angle = unit(mix(h ^ 0x4C414B45414E474CL)) * Math.PI;
      List<WaterTarget> targets = findTargets(idealX, idealZ);
      if (targets.isEmpty()) return FeatureCell.EMPTY;
      for (int attempt = 0; attempt < attempts; attempt++) {
         int[] offset = candidateOffset(h, attempt, 20);
         int x = idealX + offset[0], z = idealZ + offset[1];
         if (!footprintClear(x, z, radius, (int)Math.ceil(radius * ratio), highwayMask, 3)) continue;
         if (!footprintClear(x, z, radius, (int)Math.ceil(radius * ratio), highwayMask, 1)) continue;
         LakeSite lake = new LakeSite(this.seed, gx, gz, x * 16.0 + 8.0, z * 16.0 + 8.0,
                 radius * 16.0, radius * ratio * 16.0, angle,
                 RiverConfig.getRiverBuildHeight());
         List<StreamSegment> stream = this.buildOutlet(lake, h, targets);
         if (stream != null && !stream.isEmpty()) {
            lake.streams = stream;
            return new FeatureCell(null, lake);
         }
      }
      return FeatureCell.EMPTY;
   }

   private static int[] candidateOffset(long h, int attempt, int reach) {
      long a = mix(h ^ ((long)attempt * 0xD1B54A32D192ED03L));
      long b = mix(a ^ 0x94D049BB133111EBL);
      return new int[] {
         (int)Math.floorMod(a, reach * 2L + 1L) - reach,
         (int)Math.floorMod(b, reach * 2L + 1L) - reach
      };
   }

   /** The exclusion is exactly the feature footprint; no extra empty buffer is reserved. */
   private boolean footprintClear(int centerX, int centerZ, int radiusX, int radiusZ,
                                  HighwayNetwork.Snapshot highwayMask, int step) {
      int extentX = radiusX + 1, extentZ = radiusZ + 1;
      for (int dx = -extentX; dx <= extentX; dx += step) {
         for (int dz = -extentZ; dz <= extentZ; dz += step) {
            double r = (double)dx * dx / ((double)radiusX * radiusX)
                    + (double)dz * dz / ((double)radiusZ * radiusZ);
            if (r > 1.0) continue;
            int x = centerX + dx, z = centerZ + dz;
            if (this.inOuterCity(x, z) || this.water.baseKindAt(x, z) != WaterField.Kind.NONE
                    || this.water.isCreek(x, z) || highwayMask.at(x, z)) return false;
         }
      }
      return true;
   }

   private boolean inOuterCity(int chunkX, int chunkZ) {
      CityRingField.Site nearest = this.cities.nearestSite(chunkX, chunkZ);
      return this.cities.withinOuterCity(chunkX, chunkZ, nearest);
   }

   public MountainSite mountainAt(int blockX, int blockZ) {
      int gx = Math.floorDiv(Math.floorDiv(blockX, 16), this.cellSize);
      int gz = Math.floorDiv(Math.floorDiv(blockZ, 16), this.cellSize);
      MountainSite site = cell(gx, gz).mountain;
      return site != null && site.contains(blockX, blockZ) ? site : null;
   }

   public LakeSite lakeAt(int chunkX, int chunkZ) {
      int gx = Math.floorDiv(chunkX, this.cellSize);
      int gz = Math.floorDiv(chunkZ, this.cellSize);
      LakeSite lake = cell(gx, gz).lake;
      return lake != null && lake.intersectsChunk(chunkX, chunkZ) ? lake : null;
   }

   @Override
   public boolean lakeOrBankAt(int chunkX, int chunkZ) {
      return lakeAt(chunkX, chunkZ) != null;
   }

   @Override
   public boolean streamOrBankAt(int chunkX, int chunkZ) {
      return streamCarrierAt(chunkX, chunkZ) != null;
   }

   @Override
   public RiverProvider carrierAt(int chunkX, int chunkZ) {
      return streamCarrierAt(chunkX, chunkZ);
   }

   /** The nearest stream segment whose water/bank intersects this chunk. */
   public synchronized RiverProvider streamCarrierAt(int chunkX, int chunkZ) {
      long key = cellKey(chunkX, chunkZ);
      if (this.streamChunkCache.containsKey(key)) return this.streamChunkCache.get(key);
      int gx = Math.floorDiv(chunkX, this.cellSize);
      int gz = Math.floorDiv(chunkZ, this.cellSize);
      RiverProvider best = null;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            LakeSite lake = cell(gx + dx, gz + dz).lake;
            if (lake == null || lake.streams == null) continue;
            for (StreamSegment segment : lake.streams) {
               BoundedRiverSegment carrier = segment.carrier;
               if (!carrier.isRiverOrBank(chunkX, chunkZ)) continue;
               double signed = carrier.distanceFromCenterBlock(chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0);
               double distance = Math.max(0.0, Math.abs(signed) - carrier.getHalfWidthBlocks());
               if (distance < bestDistance) {
                  bestDistance = distance;
                  best = carrier;
               }
            }
         }
      }
      this.streamChunkCache.put(key, best);
      return best;
   }

   private List<StreamSegment> buildOutlet(LakeSite lake, long featureHash, List<WaterTarget> targets) {
      if (targets.isEmpty()) return null;
      WaterTarget nearest = targets.get(0);
      double dx = nearest.x - lake.centerX / 16.0;
      double dz = nearest.z - lake.centerZ / 16.0;
      double length = Math.max(1.0, Math.hypot(dx, dz));
      double inset = Math.min(lake.radiusX, lake.radiusZ) / 16.0 * 0.68;
      int startX = (int)Math.round(lake.centerX / 16.0 + dx / length * inset);
      int startZ = (int)Math.round(lake.centerZ / 16.0 + dz / length * inset);
      List<int[]> path = route(startX, startZ, targets);
      if (path == null || path.size() < 2) return null;

      List<StreamSegment> result = new ArrayList<>();
      int index = 0;
      int runStart = 0;
      int directionX = path.get(1)[0] - path.get(0)[0];
      int directionZ = path.get(1)[1] - path.get(0)[1];
      for (int i = 2; i <= path.size(); i++) {
         boolean same = i < path.size()
                 && path.get(i)[0] - path.get(i - 1)[0] == directionX
                 && path.get(i)[1] - path.get(i - 1)[1] == directionZ;
         if (same) continue;
         int[] from = path.get(runStart);
         int[] to = path.get(i - 1);
         if (from[0] != to[0] || from[1] != to[1]) {
            boolean axisZ = directionX == 0;
            double x0 = from[0] * 16.0 + 8.0, z0 = from[1] * 16.0 + 8.0;
            double x1 = to[0] * 16.0 + 8.0, z1 = to[1] * 16.0 + 8.0;
            long segmentSeed = mix(featureHash ^ ((long)index * 0x9E3779B97F4A7C15L));
            double width = 6.0 + unit(mix(segmentSeed)) * 2.0;
            double bank = 7.0 + unit(mix(segmentSeed ^ 0x42414E4BL)) * 3.0;
            BoundedRiverSegment carrier = new BoundedRiverSegment(segmentSeed, axisZ, x0, z0, x1, z1, width, bank);
            result.add(new StreamSegment(segmentSeed, carrier));
            index++;
         }
         if (i < path.size()) {
            runStart = i - 1;
            directionX = path.get(i)[0] - path.get(i - 1)[0];
            directionZ = path.get(i)[1] - path.get(i - 1)[1];
         }
      }
      return result.isEmpty() ? null : result;
   }

   private List<WaterTarget> findTargets(int startX, int startZ) {
      int radius = Math.min(MAX_STREAM_RANGE, Math.max(128, this.spacing));
      int lattice = Math.max(1, this.water.lattice());
      List<WaterTarget> targets = new ArrayList<>();
      for (int x = startX - radius; x <= startX + radius; x += lattice) {
         for (int z = startZ - radius; z <= startZ + radius; z += lattice) {
            WaterField.Kind zone = this.water.baseZoneKind(x, z);
            if (zone == WaterField.Kind.NONE) continue;
            for (int dx = 0; dx < lattice; dx++) {
               for (int dz = 0; dz < lattice; dz++) {
                  int tx = x + dx, tz = z + dz;
                  if (!this.water.isReceivingWaterAt(tx, tz) || this.inOuterCity(tx, tz)) continue;
                  targets.add(new WaterTarget(tx, tz, Math.abs(tx - startX) + Math.abs(tz - startZ)));
               }
            }
         }
      }
      targets.sort(Comparator.comparingInt(t -> t.distance));
      if (targets.size() > 24) targets.subList(24, targets.size()).clear();
      return targets;
   }

   private List<int[]> route(int startX, int startZ, List<WaterTarget> targets) {
      int radius = Math.min(MAX_STREAM_RANGE, Math.max(128, this.spacing));
      int minX = startX - radius, maxX = startX + radius;
      int minZ = startZ - radius, maxZ = startZ + radius;
      Map<Long, Byte> highwayAxes = highwayAxes(minX, minZ, maxX, maxZ);
      PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator
              .comparingInt((RouteNode n) -> n.score)
              .thenComparingInt(n -> n.cost)
              .thenComparingLong(n -> n.key));
      Map<Long, Integer> best = new HashMap<>();
      Map<Long, Long> previous = new HashMap<>();
      long startKey = routeKey(startX, startZ, 4, 0);
      int startH = heuristic(startX, startZ, targets);
      open.add(new RouteNode(startKey, startX, startZ, 4, 0, 0, startH));
      best.put(startKey, 0);
      long goalKey = Long.MIN_VALUE;
      int expansions = 0;
      final int[] dx = {1, -1, 0, 0};
      final int[] dz = {0, 0, 1, -1};
      while (!open.isEmpty() && expansions++ < MAX_ROUTE_EXPANSIONS) {
         RouteNode node = open.poll();
         Integer currentBest = best.get(node.key);
         if (currentBest == null || currentBest != node.cost) continue;
         if (this.water.isReceivingWaterAt(node.x, node.z)) {
            goalKey = node.key;
            break;
         }
         for (int direction = 0; direction < 4; direction++) {
            int nx = node.x + dx[direction], nz = node.z + dz[direction];
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
            if (this.inOuterCity(nx, nz)) continue;
            WaterField.Kind base = this.water.baseKindAt(nx, nz);
            if (base != WaterField.Kind.NONE && !this.water.isReceivingWaterAt(nx, nz)) continue;
            if (this.water.isCreek(nx, nz)) continue;

            byte axes = highwayAxes.getOrDefault(cellKey(nx, nz), (byte)0);
            int moveAxis = dx[direction] != 0 ? 1 : 2;
            if (axes != 0 && (axes & moveAxis) != 0) continue; // never travel along a highway
            int run = axes == 0 ? 0 : node.highwayRun + 1;
            if (run > MAX_HIGHWAY_CROSSING_CHUNKS) continue;

            int cost = node.cost + 10 + (axes == 0 ? 0 : 9)
                    + (node.direction != 4 && node.direction != direction ? 2 : 0);
            long nextKey = routeKey(nx, nz, direction, run);
            Integer old = best.get(nextKey);
            if (old != null && old <= cost) continue;
            best.put(nextKey, cost);
            previous.put(nextKey, node.key);
            int score = cost + heuristic(nx, nz, targets) * 10;
            open.add(new RouteNode(nextKey, nx, nz, direction, run, cost, score));
         }
      }
      if (goalKey == Long.MIN_VALUE) return null;
      List<int[]> reversed = new ArrayList<>();
      long key = goalKey;
      while (key != startKey) {
         int[] point = decodeCell(key);
         reversed.add(point);
         Long parent = previous.get(key);
         if (parent == null) return null;
         key = parent;
      }
      reversed.add(new int[]{startX, startZ});
      java.util.Collections.reverse(reversed);
      return reversed;
   }

   private static int heuristic(int x, int z, List<WaterTarget> targets) {
      int best = Integer.MAX_VALUE;
      for (WaterTarget target : targets) {
         int distance = Math.abs(x - target.x) + Math.abs(z - target.z);
         if (distance < best) best = distance;
      }
      return best == Integer.MAX_VALUE ? 0 : best;
   }

   private Map<Long, Byte> highwayAxes(int minX, int minZ, int maxX, int maxZ) {
      Map<Long, Byte> out = new HashMap<>();
      for (HighwayNetwork.Rect rect : this.highways.rectsIn(minX, minZ, maxX, maxZ)) {
         byte axis = (byte)((rect.maxX - rect.minX) >= (rect.maxZ - rect.minZ) ? 1 : 2);
         int x0 = Math.max(minX, rect.minX), x1 = Math.min(maxX, rect.maxX);
         int z0 = Math.max(minZ, rect.minZ), z1 = Math.min(maxZ, rect.maxZ);
         for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
               long key = cellKey(x, z);
               out.put(key, (byte)(out.getOrDefault(key, (byte)0) | axis));
            }
         }
      }
      return out;
   }

   private static long routeKey(int x, int z, int direction, int run) {
      long cell = ((long)(x & 0x03FFFFFF) << 26) | (z & 0x03FFFFFFL);
      return (cell << 5) | ((long)(direction & 7) << 2) | (run & 3);
   }

   private static int[] decodeCell(long key) {
      long packed = key >>> 5;
      int x = (int)(packed >>> 26);
      int z = (int)(packed & 0x03FFFFFFL);
      if ((x & 0x02000000) != 0) x |= 0xFC000000;
      if ((z & 0x02000000) != 0) z |= 0xFC000000;
      return new int[]{x, z};
   }

   @Override
   public boolean waterOrBank(int chunkX, int chunkZ, boolean ignored) {
      return streamCarrierAt(chunkX, chunkZ) != null;
   }

   @Override
   public boolean waterOrBank(int chunkX, int chunkZ) {
      return streamCarrierAt(chunkX, chunkZ) != null;
   }

   @Override
   public RiverProvider carrierAt(int chunkX, int chunkZ) {
      return streamCarrierAt(chunkX, chunkZ);
   }

   public static final class MountainSite {
      public final String name;
      public final double centerX, centerZ, radiusX, radiusZ, angle;
      public final int peakClearance;
      private final Noise2D broad, ridge;

      MountainSite(long seed, int gx, int gz, double centerX, double centerZ,
                   double radiusX, double radiusZ, double angle, int peakClearance) {
         long h = mix(seed ^ cellKey(gx, gz) ^ 0x4D4F554E5441494EL);
         this.name = "Mount " + generatedName(seed ^ 0x4D4F554E544E414DL, gx, gz);
         this.centerX = centerX;
         this.centerZ = centerZ;
         this.radiusX = radiusX;
         this.radiusZ = radiusZ;
         this.angle = angle;
         this.peakClearance = peakClearance;
         this.broad = new Noise2D(h ^ 0x42524F41444E4F49L);
         this.ridge = new Noise2D(h ^ 0x52494447454E4F49L);
      }

      public boolean contains(int x, int z) {
         return normalizedRadius(x, z) < 1.0;
      }

      public int heightAt(int x, int z, int baseY, int maxHeight) {
         double r = normalizedRadius(x, z);
         if (r >= 1.0) return baseY;
         double fade = Math.pow(Math.max(0.0, 1.0 - r), 1.28);
         double nx = x / 420.0, nz = z / 420.0;
         double broadNoise = this.broad.fbm(nx, nz, 4, 0.52);
         double ridged = this.ridge.ridged(x / 300.0, z / 300.0, 4);
         double relief = Math.max(0.0, maxHeight - this.peakClearance - baseY);
         double rough = (broadNoise * 12.0 + (ridged - 0.42) * 17.0) * fade;
         // One dominant summit with broken shoulders, rather than a smooth cone or a flat plateau.
         double summit = 12.0 * Math.pow(Math.max(0.0, 1.0 - r * 1.45), 2.0);
         double value = baseY + relief * fade + rough + summit;
         return (int)Math.max(baseY, Math.min(maxHeight - this.peakClearance, Math.round(value)));
      }

      public boolean snowy(int y, int baseY, int maxHeight) {
         int top = maxHeight - this.peakClearance;
         return y >= baseY + (top - baseY) * 0.73;
      }

      public boolean treeless(int y, int baseY, int maxHeight) {
         int top = maxHeight - this.peakClearance;
         int treeLine = baseY + Math.max(48, (int)Math.round((top - baseY) * 0.30));
         return y >= treeLine;
      }

      private double normalizedRadius(double x, double z) {
         double dx = x - this.centerX, dz = z - this.centerZ;
         double c = Math.cos(this.angle), s = Math.sin(this.angle);
         double rx = (dx * c - dz * s) / this.radiusX;
         double rz = (dx * s + dz * c) / this.radiusZ;
         return Math.sqrt(rx * rx + rz * rz);
      }
   }

   public static final class LakeSite {
      public static final double WATER_EDGE = 0.79;
      public final String name;
      public final int gridX, gridZ;
      public final double centerX, centerZ, radiusX, radiusZ, angle;
      public final int waterY;
      public List<StreamSegment> streams;

      LakeSite(long seed, int gx, int gz, double centerX, double centerZ,
               double radiusX, double radiusZ, double angle, int waterY) {
         this.name = "Lake " + generatedName(seed ^ 0x4C414B454E414D45L, gx, gz);
         this.gridX = gx;
         this.gridZ = gz;
         this.centerX = centerX;
         this.centerZ = centerZ;
         this.radiusX = radiusX;
         this.radiusZ = radiusZ;
         this.angle = angle;
         this.waterY = waterY;
      }

      public double normalizedRadius(double x, double z) {
         double dx = x - this.centerX, dz = z - this.centerZ;
         double c = Math.cos(this.angle), s = Math.sin(this.angle);
         double rx = (dx * c - dz * s) / this.radiusX;
         double rz = (dx * s + dz * c) / this.radiusZ;
         double wave = Math.sin((x + this.gridX * 71.0) * 0.006)
                 * Math.cos((z - this.gridZ * 53.0) * 0.005) * 0.018;
         return Math.sqrt(rx * rx + rz * rz) + wave;
      }

      public boolean intersectsChunk(int chunkX, int chunkZ) {
         double bx = chunkX * 16.0 + 8.0, bz = chunkZ * 16.0 + 8.0;
         if (normalizedRadius(bx, bz) <= 1.04) return true;
         double[] ox = {-8.0, 8.0, -8.0, 8.0};
         double[] oz = {-8.0, -8.0, 8.0, 8.0};
         for (int i = 0; i < 4; i++) {
            if (normalizedRadius(bx + ox[i], bz + oz[i]) <= 1.0) return true;
         }
         return false;
      }

      public boolean waterAt(int x, int z) {
         return normalizedRadius(x + 0.5, z + 0.5) < WATER_EDGE;
      }

      public int depthAt(int x, int z) {
         double r = normalizedRadius(x + 0.5, z + 0.5);
         if (r >= WATER_EDGE) return 0;
         double t = (WATER_EDGE - r) / WATER_EDGE;
         return 3 + (int)Math.round(10.0 * Math.pow(t, 0.72));
      }
   }

   public static final class StreamSegment {
      public final long id;
      public final BoundedRiverSegment carrier;
      StreamSegment(long id, BoundedRiverSegment carrier) {
         this.id = id;
         this.carrier = carrier;
      }
   }

   private static final class FeatureCell {
      static final FeatureCell EMPTY = new FeatureCell(null, null);
      final MountainSite mountain;
      final LakeSite lake;
      FeatureCell(MountainSite mountain, LakeSite lake) {
         this.mountain = mountain;
         this.lake = lake;
      }
   }

   private static final class WaterTarget {
      final int x, z, distance;
      WaterTarget(int x, int z, int distance) { this.x=x; this.z=z; this.distance=distance; }
   }

   private static final class RouteNode {
      final long key;
      final int x, z, direction, highwayRun, cost, score;
      RouteNode(long key, int x, int z, int direction, int highwayRun, int cost, int score) {
         this.key=key; this.x=x; this.z=z; this.direction=direction;
         this.highwayRun=highwayRun; this.cost=cost; this.score=score;
      }
   }

   private static String generatedName(long seed, int x, int z) {
      long h = mix(seed ^ ((long)x * 7146057691288625177L) ^ ((long)z * -7046029254386353131L));
      return ROOTS[Math.floorMod(h, ROOTS.length)] + ENDS[Math.floorMod(mix(h), ENDS.length)];
   }
}
