package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.grid.urbanGrid.context.CityRingField;
import ch.k42.metropolis.grid.urbanGrid.forest.WildernessTerrainProfile;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterFields;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworks;
import ch.k42.metropolis.plugin.PluginConfig;
import ch.k42.metropolis.minions.Minions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seed-stable wilderness landmarks. A rare alpine massif and named lakes are
 * selected only where their exact footprints fit outside the outer city/farm
 * rings. Lake outlets are routed through wilderness; intercity highways may
 * be crossed perpendicularly, but are never used as stream corridors.
 *
 * This is a plan, not a chunk-population pass. Every answer is a pure
 * world-coordinate lookup and is shared by all UrbanGrid tiles.
 */
public final class WildernessFeatures implements WaterField.HydrologyLayer {
   private static final int MOUNTAIN_CLEARANCE = 18;
   private static final int MAX_STREAM_RANGE = 300;
   private static final int MAX_ROUTE_EXPANSIONS = 500000;
   private static final int MAX_HIGHWAY_CROSSING_CHUNKS = 2;
   private static final int MOUNTAIN_THRESHOLD = 2;
   // Keep lakes frequent enough to encounter in ordinary wilderness exploration.
   // With 58% of feature cells assigned to lakes, empty cells remain common
   // while inland water is no longer a once-per-large-region rarity.
   private static final int LAKE_THRESHOLD = 60;

   private static final Map<String, WildernessFeatures> CACHE = new HashMap<>();
   private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();
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
   private final int streamRange;
   private int lastRouteExpansionsForProbe;
   private int lastRouteFrontierForProbe;
   private int lastRouteCityBlocksForProbe;
   private int lastRouteWaterBlocksForProbe;
   private int lastRouteCreekBlocksForProbe;
   private int lastRouteHighwayParallelBlocksForProbe;
   private int lastRouteHighwayWidthBlocksForProbe;
   private int lastRouteHighwayTurnBlocksForProbe;
   private final WaterField water;
   private final HighwayNetwork highways;
   /** Installed once, after bounded creek reservations and before layout solving.
    * This direct source avoids the recursive composed WaterField -> stream planner
    * -> WaterField creek lookup edge. */
   private volatile WaterField.CreekSource routeCreeks;
   private final CityRingField cities;
   private final WildernessTerrainProfile terrainProfile;
   private final HydrologyConvergenceRegistry convergenceRegistry=new HydrologyConvergenceRegistry();
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
      // WaterFields may have installed this feature plan while creating the
      // shared field. Reuse it rather than replacing the provider after roads
      // have already retained its lake/stream masks.
      found = CACHE.get(key);
      if (found != null) return found;
      HighwayNetwork highways = HighwayNetworks.forSeed(seed);
      found = new WildernessFeatures(seed, spacing, water, highways);
      CACHE.put(key, found);
      return found;
   }

   /** Install the feature masks before the first WorldLayout domain is solved. */
   public static void install(long seed) {
      if (!PluginConfig.isCityRingsEnabled() || !PluginConfig.isForestGenerationEnabled()) return;
      WildernessFeatures features = forSeed(seed);
      // Capture the already-installed bounded creek source exactly once.
      // Outlet routing is allowed to consult this base reservation directly,
      // but never to call back through WaterField.isCreek()/kindAt().
      features.routeCreeks = features.water.creekSource();
      features.water.setHydrologyLayer(features);
   }

   /** Resolve the hydrology cells relevant to a 64x64-chunk tile, plus a
    * one-tile layout halo, as a single planning phase before WorldLayout asks
    * WaterField any zoning questions. Later WaterField lookups are read-only. */
   public static void prepareForTile(long seed, int tileMinChunkX, int tileMinChunkZ) {
      if (!PluginConfig.isCityRingsEnabled() || !PluginConfig.isForestGenerationEnabled()) return;
      forSeed(seed).prepareForTile(tileMinChunkX, tileMinChunkZ);
   }

   private synchronized void prepareForTile(int tileMinChunkX, int tileMinChunkZ) {
      WaterField.CreekSource creekSource=this.routeCreeks;
      if(creekSource!=null) {
         int creekReach=Math.min(this.streamRange,192);
         creekSource.prepareForConvergenceArea(tileMinChunkX-creekReach-64,tileMinChunkZ-creekReach-64,
                 tileMinChunkX+63+creekReach+64,tileMinChunkZ+63+creekReach+64);
      }
      int reach = Math.max(1, (int)Math.ceil((this.streamRange + this.cellSize / 2.0) / this.cellSize));
      int minGX = Math.floorDiv(tileMinChunkX - 128, this.cellSize) - reach;
      int maxGX = Math.floorDiv(tileMinChunkX + 63 + 128, this.cellSize) + reach;
      int minGZ = Math.floorDiv(tileMinChunkZ - 128, this.cellSize) - reach;
      int maxGZ = Math.floorDiv(tileMinChunkZ + 63 + 128, this.cellSize) + reach;
      // FeatureCell construction may route an outlet, but all its inputs are
      // now base water, highways, city rings and the captured creek source.
      // It cannot invoke the composed WaterField or start another plan phase.
      for (int gx = minGX; gx <= maxGX; gx++) {
         for (int gz = minGZ; gz <= maxGZ; gz++) {
            this.cell(gx, gz);
         }
      }
      // A prior tile may have cached a negative lookup before these cells
      // became part of the resolved window. Re-read against the new snapshot.
      this.streamChunkCache.clear();
   }

   private WildernessFeatures(long seed, int spacing, WaterField water, HighwayNetwork highways) {
      this.seed = seed;
      this.spacing = Math.max(16, spacing);
      // A 192-chunk cell is 3,072 blocks wide. Candidate lakes stay at least
      // 40 chunks apart at their closest possible opposing offsets, leaving a
      // real gap between their maximum 17-chunk radii while making them much
      // more discoverable than the former 416-chunk lattice.
      this.cellSize = 192;
      // An unanchored main river's cross-axis baseline may be anywhere inside
      // OceanConfig.maxDistanceFromSpawn. A lake can sit near the far edge of
      // the adjacent two landmark cells, so the route window must account for
      // both that baseline range and the actual feature-cell placement span.
      this.streamRange = Math.max(MAX_STREAM_RANGE,
              OceanConfig.getOceanMaxDistanceFromSpawn() + this.cellSize * 2 + 64);
      this.water = water;
      this.highways = highways;
      this.cities = new CityRingField(seed);
      this.terrainProfile = new WildernessTerrainProfile(seed);
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

   /** Read only a feature cell already resolved by prepareForTile() or by an
    * explicit nearest-landmark/QA request. WaterField callbacks must not build it. */
   private synchronized FeatureCell resolvedCell(int gx, int gz) {
      return this.featureCells.get(cellKey(gx, gz));
   }

   int resolvedFeatureCellCountForProbe() {
      synchronized (this) { return this.featureCells.size(); }
   }
   int registeredConvergenceEndpointCountForProbe(){return this.convergenceRegistry.size();}
   String basinSearchDiagnosticForProbe(){return this.terrainProfile.lastSearchDiagnostic();}

   private FeatureCell buildCell(int gx, int gz) {
      long h = cellHash(gx, gz);
      int kind = (int)Math.floor(unit(h) * 100.0);
      if (kind >= LAKE_THRESHOLD) return FeatureCell.EMPTY;

      int idealX = gx * this.cellSize + this.cellSize / 2;
      int idealZ = gz * this.cellSize + this.cellSize / 2;
      int mountainAttempts = 4;
      // Inland seas have a larger but bounded envelope, leaving open wilderness between basins.
      int lakeReach = Math.max(24, this.cellSize / 2 - 44);
      int maskReach = lakeReach + 24;
      HighwayNetwork.Snapshot highwayMask = this.highways.snapshot(
              idealX - maskReach, idealZ - maskReach, idealX + maskReach, idealZ + maskReach);

      if (kind < MOUNTAIN_THRESHOLD) {
         int radius = 34 + (int)(mix(h ^ 0x4D4F554E5441494EL) >>> 60);
         for (int attempt = 0; attempt < mountainAttempts; attempt++) {
            int[] offset = candidateOffset(h, attempt, 22);
            int x = idealX + offset[0], z = idealZ + offset[1];
            if (!footprintClear(x, z, radius, radius, highwayMask, 3)) continue;
            if (!footprintClear(x, z, radius, radius, highwayMask, 1)) continue;
            int peakDrop = 2 + (int)(mix(h ^ 0x5045414B4C494E45L) >>> 61);
            MountainSite site = new MountainSite(this.seed, gx, gz, x * 16.0 + 8.0, z * 16.0 + 8.0,
                    radius * 16.0, radius * 16.0, unit(mix(h ^ 0x524944474553L)) * Math.PI,
                    MOUNTAIN_CLEARANCE + peakDrop);
            return new FeatureCell(site, null);
         }
         return FeatureCell.EMPTY;
      }

      // 4-8 chunk major-axis radii fit compact natural hollows without forcing their scale.
      // Placement reserves the noisy coast and its full finite natural bank.
      int radius = 4 + (int)(mix(h ^ 0x4C414B4553495A45L) >>> 61);
      double ratio = 0.70 + unit(mix(h ^ 0x454C4C49505345L)) * 0.20;
      double angle = unit(mix(h ^ 0x4C414B45414E474CL)) * Math.PI;
      int footprint = (int)Math.ceil(radius * 1.13 + LakeSite.SHORE_WIDTH_BLOCKS / 16.0 + 3.0);
      double radiusX=radius*16.0,radiusZ=radius*ratio*16.0;
      // Search the existing wilderness surface for closed depressions rather
      // than stamping a predetermined ellipse at a random offset. Candidate
      // basins are returned in deterministic suitability order; city, water,
      // creek and highway reservations still veto the final footprint.
      List<WildernessTerrainProfile.BasinCandidate> basins=this.terrainProfile.findNearbyBasins(
              idealX*16.0+8.0,idealZ*16.0+8.0,lakeReach*16.0,
              radiusX,radiusZ,angle,24);
      for(WildernessTerrainProfile.BasinCandidate candidate:basins) {
         int x=(int)Math.floor(candidate.centerX/16.0),z=(int)Math.floor(candidate.centerZ/16.0);
         if(!footprintClear(x,z,footprint,footprint,highwayMask,2))continue;
         LakeSite lake=new LakeSite(this.seed,gx,gz,candidate.centerX,candidate.centerZ,
                 candidate.radiusX,candidate.radiusZ,candidate.angle,candidate.fit.waterY,this.terrainProfile,candidate.fit);
         // A basin remains a freshwater lake even when it has no legal outlet.
         // Accepted outlets converge through an exact keyed ocean, river or creek endpoint.
         if(RiverConfig.isRiverEnabled()) {
            List<WaterTarget> targets=findTargets(x,z);
            if(!targets.isEmpty())lake.stream=this.buildOutlet(lake,h,targets);
         }
         return new FeatureCell(null,lake);
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
                    || this.plannedCreekAt(x, z) || highwayMask.at(x, z)) return false;
         }
      }
      return true;
   }

   /** Package-visible deterministic seam for lake/stream coverage QA. */
   int featureCellSizeForProbe() { return this.cellSize; }

   /** Package-visible deterministic seam for lake/stream coverage QA. */
   LakeSite lakeForCellProbe(int gx, int gz) { return cell(gx, gz).lake; }

   /**
    * Diagnostic tuple for a planned lake: [raw receiving-water chunks,
    * city-safe receiving-water targets, route points]. This is only used by
    * the deterministic regression and does not mutate the plan.
    */
   int[] outletDiagnosticsForProbe(LakeSite lake) {
      int cx=(int)Math.floor(lake.centerX/16.0),cz=(int)Math.floor(lake.centerZ/16.0);
      List<WaterTarget> targets=findTargets(cx,cz);
      if(targets.isEmpty())return new int[]{0,0,0};
      double dx=targets.get(0).x-cx,dz=targets.get(0).z-cz;
      RouteResult result=route((int)Math.round(cx+dx*.62),(int)Math.round(cz+dz*.62),targets);
      return new int[]{targets.size(),result==null?0:result.path.size(),
              result==null?-1:result.target.key.receiver().ordinal(),
              this.lastRouteExpansionsForProbe,this.lastRouteFrontierForProbe,
              this.lastRouteCityBlocksForProbe,this.lastRouteWaterBlocksForProbe,
              this.lastRouteCreekBlocksForProbe,this.lastRouteHighwayParallelBlocksForProbe,
              this.lastRouteHighwayWidthBlocksForProbe,this.lastRouteHighwayTurnBlocksForProbe};
   }

   private int countReceivingRiverChunksForProbe(int startX, int startZ) {
      int radius = this.streamRange;
      int minX = startX - radius, maxX = startX + radius;
      int minZ = startZ - radius, maxZ = startZ + radius;
      int count = 0;
      RiverProvider[] rivers = {this.water.mainRiver(), this.water.zBoundRiver()};
      for (RiverProvider river : rivers) {
         if (river == null || !river.isEnabled()) continue;
         boolean zBound = river.isZBound();
         int alongMin = zBound ? minZ : minX;
         int alongMax = zBound ? maxZ : maxX;
         int acrossReach = (int)Math.ceil(river.getEnvelopeHalfWidth()) + 2;
         for (int along = alongMin; along <= alongMax; along++) {
            double alongBlock = along * 16.0 + 8.0;
            double acrossBlock = zBound ? river.spineXBlock(alongBlock) : river.spineZBlock(alongBlock);
            if (!Double.isFinite(acrossBlock)) continue;
            int acrossCenter = (int)Math.floor(acrossBlock / 16.0);
            for (int offset = -acrossReach; offset <= acrossReach; offset++) {
               int x = zBound ? acrossCenter + offset : along;
               int z = zBound ? along : acrossCenter + offset;
               if (x >= minX && x <= maxX && z >= minZ && z <= maxZ
                       && this.water.isReceivingWaterAt(x, z)) count++;
            }
         }
      }
      return count;
   }

   private boolean inOuterCity(int chunkX, int chunkZ) {
      CityRingField.Site nearest = this.cities.nearestSite(chunkX, chunkZ);
      return this.cities.withinOuterCity(chunkX, chunkZ, nearest);
   }

   /**
    * Find the nearest planned named lake without scanning or generating terrain.
    * Feature cells are widely spaced, so search outward by cell rings and inspect
    * one ring beyond the first hit before choosing the closest lake centre.
    */
   /**
    * Find the nearest planned named lake with no arbitrary search-radius cap.
    * Search complete feature-cell rings and one extra ring after the first hit
    * so a nearer site at the edge cannot be overlooked. Run asynchronously:
    * candidate planning may calculate a connected outlet route.
    */
   public LakeSite nearestLake(int blockX, int blockZ) {
      int chunkX = Math.floorDiv(blockX, 16);
      int chunkZ = Math.floorDiv(blockZ, 16);
      int originGX = Math.floorDiv(chunkX, this.cellSize);
      int originGZ = Math.floorDiv(chunkZ, this.cellSize);
      int firstHitRing = -1;
      LakeSite best = null;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int ring = 0; ; ring++) {
         for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
               if (ring > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
               LakeSite lake = cell(originGX + dx, originGZ + dz).lake;
               if (lake == null) continue;
               double ox = lake.centerX - (blockX + 0.5), oz = lake.centerZ - (blockZ + 0.5);
               double distance = ox * ox + oz * oz;
               if (distance < bestDistance) { bestDistance = distance; best = lake; }
            }
         }
         if (best != null) {
            if (firstHitRing < 0) firstHitRing = ring;
            else if (ring > firstHitRing) return best;
         }
         if (ring == Integer.MAX_VALUE) return best;
      }
   }

   /**
    * Find a separately planned named massif. This search is independent of the
    * broad /metrobiome mountains field.
    */
   public MountainSite nearestMountain(int blockX, int blockZ) {
      int chunkX = Math.floorDiv(blockX, 16);
      int chunkZ = Math.floorDiv(blockZ, 16);
      int originGX = Math.floorDiv(chunkX, this.cellSize);
      int originGZ = Math.floorDiv(chunkZ, this.cellSize);
      int firstHitRing = -1;
      MountainSite best = null;
      double bestDistance = Double.POSITIVE_INFINITY;
      for (int ring = 0; ; ring++) {
         for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
               if (ring > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
               MountainSite mountain = cell(originGX + dx, originGZ + dz).mountain;
               if (mountain == null) continue;
               double ox = mountain.centerX - (blockX + 0.5), oz = mountain.centerZ - (blockZ + 0.5);
               double distance = ox * ox + oz * oz;
               if (distance < bestDistance) { bestDistance = distance; best = mountain; }
            }
         }
         if (best != null) {
            if (firstHitRing < 0) firstHitRing = ring;
            else if (ring > firstHitRing) return best;
         }
         if (ring == Integer.MAX_VALUE) return best;
      }
   }

   public MountainSite mountainAt(int blockX, int blockZ) {
      int gx = Math.floorDiv(Math.floorDiv(blockX, 16), this.cellSize);
      int gz = Math.floorDiv(Math.floorDiv(blockZ, 16), this.cellSize);
      FeatureCell resolved = resolvedCell(gx, gz);
      MountainSite site = resolved == null ? null : resolved.mountain;
      return site != null && site.contains(blockX, blockZ) ? site : null;
   }

   public LakeSite lakeAt(int chunkX, int chunkZ) {
      int gx = Math.floorDiv(chunkX, this.cellSize);
      int gz = Math.floorDiv(chunkZ, this.cellSize);
      FeatureCell resolved = resolvedCell(gx, gz);
      LakeSite lake = resolved == null ? null : resolved.lake;
      return lake != null && lake.intersectsChunk(chunkX, chunkZ) ? lake : null;
   }

   @Override
   public HydrologySample sampleAt(WaterField water,int blockX,int blockZ) {
      int chunkX=Math.floorDiv(blockX,16),chunkZ=Math.floorDiv(blockZ,16);
      WaterField.Kind base=water.baseKindAt(chunkX,chunkZ);
      double px=blockX+0.5,pz=blockZ+0.5;
      if(base==WaterField.Kind.OCEAN) {
         double signed=water.ocean()==null?Double.NaN:
                 water.ocean().oceanDistanceInsideShoreBlock(px,pz);
         HydrologyConvergenceKey key=this.convergenceRegistry.findAt(
                 HydrologyConvergenceKey.Receiver.OCEAN,chunkX,chunkZ);
         return new HydrologySample(blockX,blockZ,base,HydrologySample.Feature.OCEAN,
                 Double.isFinite(signed)&&signed>0.0,signed,OceanConfig.getOceanBuildHeight(),0,null,key);
      }
      if(base==WaterField.Kind.RIVER) {
         RiverProvider carrier=water.riverAt(chunkX,chunkZ);
         double signed=carrier==null?Double.NaN:carrier.distanceFromCenterBlock(px,pz);
         double width=carrier==null?Double.NaN:
                 carrier.widthAtBlock(carrier.isZBound()?pz:px);
         HydrologyConvergenceKey.Receiver receiver=HydrologyConvergenceKey.Receiver.RIVER;
         HydrologyConvergenceKey key=this.convergenceRegistry.findAt(receiver,chunkX,chunkZ);
         boolean open=Double.isFinite(signed)&&Double.isFinite(width)&&Math.abs(signed)<=width*.5;
         return new HydrologySample(blockX,blockZ,base,HydrologySample.Feature.RIVER,
                 open,signed,RiverConfig.getRiverBuildHeight(),0,carrier,key);
      }
      WaterField.CreekSource creekSource=water.creekSource();
      if(creekSource!=null&&creekSource.waterOrBank(chunkX,chunkZ)) {
         RiverProvider carrier=creekSource.carrierAt(chunkX,chunkZ);
         double signed=carrier==null?Double.NaN:carrier.distanceFromCenterBlock(px,pz);
         double width=carrier==null?Double.NaN:
                 carrier.widthAtBlock(carrier.isZBound()?pz:px);
         boolean open=Double.isFinite(signed)&&Double.isFinite(width)&&Math.abs(signed)<=width*.5;
         HydrologyConvergenceKey key=this.convergenceRegistry.findAt(
                 HydrologyConvergenceKey.Receiver.CREEK,chunkX,chunkZ);
         return new HydrologySample(blockX,blockZ,WaterField.Kind.CREEK,
                 HydrologySample.Feature.CREEK,open,signed,RiverConfig.getRiverBuildHeight(),0,carrier,key);
      }
      LakeSite lake=this.lakeAt(chunkX,chunkZ);
      if(lake!=null) {
         double signed=lake.distanceInsideShoreBlock(px,pz);
         boolean open=signed>0.0;
         int depth=open?lake.depthAt(blockX,blockZ):0;
         return new HydrologySample(blockX,blockZ,WaterField.Kind.LAKE,
                 HydrologySample.Feature.LAKE,open,signed,lake.waterY,depth,null,null);
      }
      RiverProvider stream=this.streamCarrierAt(chunkX,chunkZ);
      if(stream!=null) {
         double signed=stream.distanceFromCenterBlock(px,pz);
         HydrologyConvergenceKey key=stream instanceof WildernessStreamCourse course
                 ?course.convergenceKey():null;
         boolean open=Double.isFinite(signed)&&Math.abs(signed)<=stream.getHalfWidthBlocks();
         return new HydrologySample(blockX,blockZ,WaterField.Kind.STREAM,
                 HydrologySample.Feature.STREAM,open,signed,RiverConfig.getRiverBuildHeight(),0,stream,key);
      }
      return HydrologySample.none(blockX,blockZ);
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
      int cellReach = Math.max(1, (int)Math.ceil((this.streamRange + this.cellSize / 2.0) / this.cellSize));
      for (int dx = -cellReach; dx <= cellReach; dx++) {
         for (int dz = -cellReach; dz <= cellReach; dz++) {
            FeatureCell resolved = this.featureCells.get(cellKey(gx + dx, gz + dz));
            LakeSite lake = resolved == null ? null : resolved.lake;
            if (lake == null || lake.stream == null) continue;
            WildernessStreamCourse carrier = lake.stream;
            // The terminal cell belongs to the ocean/river/creek receiver.
            // Keeping stream parcel ownership out of that cell prevents the
            // tributary from repainting the receiving network at the junction.
            if (carrier.terminatesAt(chunkX,chunkZ)) continue;
            if (!carrier.isRiverOrBank(chunkX, chunkZ)) continue;
            double signed = carrier.distanceFromCenterBlock(chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0);
            double distance = Math.max(0.0, Math.abs(signed) - carrier.getHalfWidthBlocks());
            if (distance < bestDistance) {
               bestDistance = distance;
               best = carrier;
            }
         }
      }
      this.streamChunkCache.put(key, best);
      return best;
   }

   private WildernessStreamCourse buildOutlet(LakeSite lake,long featureHash,List<WaterTarget> targets) {
      if(targets.isEmpty())return null;
      WaterTarget nearest=targets.get(0);
      double dx=nearest.x-lake.centerX/16.0,dz=nearest.z-lake.centerZ/16.0;
      double length=Math.max(1.0,Math.hypot(dx,dz)),inset=Math.min(lake.radiusX,lake.radiusZ)/16.0*.62;
      int startX=(int)Math.floor(lake.centerX/16.0+dx/length*inset);
      int startZ=(int)Math.floor(lake.centerZ/16.0+dz/length*inset);
      if(lake.distanceInsideShoreBlock(startX*16.0+8.0,startZ*16.0+8.0)<12.0) {
         for(double factor:new double[]{.52,.42,.32,.22}) {
            int sx=(int)Math.floor(lake.centerX/16.0+dx/length*Math.min(lake.radiusX,lake.radiusZ)/16.0*factor);
            int sz=(int)Math.floor(lake.centerZ/16.0+dz/length*Math.min(lake.radiusX,lake.radiusZ)/16.0*factor);
            if(lake.distanceInsideShoreBlock(sx*16.0+8.0,sz*16.0+8.0)>=12.0){startX=sx;startZ=sz;break;}
         }
      }
      RouteResult routed=route(startX,startZ,targets);
      if(routed==null||routed.path.size()<2)return null;
      double[] blockX=new double[routed.path.size()],blockZ=new double[routed.path.size()];
      for(int i=0;i<routed.path.size();i++){blockX[i]=routed.path.get(i)[0]*16.0+8.0;blockZ[i]=routed.path.get(i)[1]*16.0+8.0;}
      int last=blockX.length-1;
      blockX[last]=routed.target.key.blockX();blockZ[last]=routed.target.key.blockZ();
      long streamSeed=mix(featureHash^0x53545245414D4C4BL);
      double width=10.0+unit(mix(streamSeed))*2.0,bank=7.0+unit(mix(streamSeed^0x42414E4BL))*3.0;
      return new WildernessStreamCourse(streamSeed,blockX,blockZ,width,bank,routed.target.key);
   }

   private List<WaterTarget> findTargets(int startX,int startZ) {
      int radius=this.streamRange;
      int minX=startX-radius,maxX=startX+radius,minZ=startZ-radius,maxZ=startZ+radius;
      Map<HydrologyConvergenceKey,WaterTarget> unique=new LinkedHashMap<>();
      addRiverTargets(this.water.mainRiver(),false,startX,startZ,minX,maxX,minZ,maxZ,unique);
      addRiverTargets(this.water.zBoundRiver(),true,startX,startZ,minX,maxX,minZ,maxZ,unique);
      addOceanTargets(startX,startZ,Math.max(minX,startX-256),Math.max(minZ,startZ-256),
              Math.min(maxX,startX+256),Math.min(maxZ,startZ+256),unique);
      WaterField.CreekSource source=this.routeCreeks;
      if(source!=null) {
         int creekRadius=Math.min(radius,192);
         List<HydrologyConvergenceKey> endpoints=source.convergenceEndpoints(
                 Math.max(minX,startX-creekRadius),Math.max(minZ,startZ-creekRadius),
                 Math.min(maxX,startX+creekRadius),Math.min(maxZ,startZ+creekRadius));
         for(HydrologyConvergenceKey raw:endpoints) {
            HydrologyConvergenceKey key=this.convergenceRegistry.register(raw);
            int x=key.chunkX(),z=key.chunkZ();
            if(!this.inOuterCity(x,z))unique.putIfAbsent(key,new WaterTarget(key,startX,startZ));
         }
      }
      List<WaterTarget> all=new ArrayList<>(unique.values());
      all.sort(Comparator.comparingInt((WaterTarget t)->t.distance).thenComparing(t->t.key));
      List<WaterTarget> result=new ArrayList<>();
      for(HydrologyConvergenceKey.Receiver receiver:HydrologyConvergenceKey.Receiver.values()) {
         int count=0;
         for(WaterTarget t:all)if(t.key.receiver()==receiver&&count++<24)result.add(t);
      }
      result.sort(Comparator.comparingInt((WaterTarget t)->t.distance).thenComparing(t->t.key));
      if(result.size()>64)result.subList(64,result.size()).clear();
      return result;
   }

   private void addOceanTargets(int startX,int startZ,int minX,int minZ,int maxX,int maxZ,
                                Map<HydrologyConvergenceKey,WaterTarget> targets) {
      if(this.water.ocean()==null||minX>maxX||minZ>maxZ)return;
      int lattice=Math.max(1,this.water.lattice()),stride=Math.max(1,lattice/3);
      int firstX=Math.floorDiv(minX,lattice),lastX=Math.floorDiv(maxX,lattice);
      int firstZ=Math.floorDiv(minZ,lattice),lastZ=Math.floorDiv(maxZ,lattice);
      long network=HydrologyConvergenceKey.stableId("ocean:"+this.seed);
      for(int gx=firstX;gx<=lastX;gx++)for(int gz=firstZ;gz<=lastZ;gz++) {
         int x0=gx*lattice,z0=gz*lattice;
         if(this.water.baseZoneKind(x0,z0)!=WaterField.Kind.OCEAN)continue;
         for(int dx=stride/2;dx<lattice;dx+=stride)for(int dz=stride/2;dz<lattice;dz+=stride) {
            int x=x0+dx,z=z0+dz;
            if(x<minX||x>maxX||z<minZ||z>maxZ||this.inOuterCity(x,z))continue;
            double bx=x*16.0+8.0,bz=z*16.0+8.0;
            double inside=this.water.ocean().distanceInsideShoreBlock(bx,bz);
            if(!Double.isFinite(inside)||inside<=0.0)continue;
            HydrologyConvergenceKey key=this.convergenceRegistry.register(
                    new HydrologyConvergenceKey(HydrologyConvergenceKey.Receiver.OCEAN,network,
                            HydrologyConvergenceKey.chunkEndpointId(x,z),bx,bz));
            targets.putIfAbsent(key,new WaterTarget(key,startX,startZ));
         }
      }
   }

   private void addRiverTargets(RiverProvider river,boolean zBound,int startX,int startZ,
                                int minX,int maxX,int minZ,int maxZ,
                                Map<HydrologyConvergenceKey,WaterTarget> targets) {
      if(river==null||!river.isEnabled())return;
      int alongMin=zBound?minZ:minX,alongMax=zBound?maxZ:maxX;
      long network=HydrologyConvergenceKey.stableId("river:"+this.seed+":"+(zBound?"z":"x"));
      for(int along=alongMin;along<=alongMax;along++) {
         double alongBlock=along*16.0+8.0;
         double across=zBound?river.spineXBlock(alongBlock):river.spineZBlock(alongBlock);
         if(!Double.isFinite(across))continue;
         double bx=zBound?across:alongBlock,bz=zBound?alongBlock:across;
         int cx=(int)Math.floor(bx/16.0),cz=(int)Math.floor(bz/16.0);
         if(cx<minX||cx>maxX||cz<minZ||cz>maxZ||this.inOuterCity(cx,cz))continue;
         if(!river.isRiver(cx,cz)||this.water.baseKindAt(cx,cz)==WaterField.Kind.OCEAN)continue;
         double distance=river.distanceFromCenterBlock(bx,bz),width=river.widthAtBlock(zBound?bz:bx);
         if(!Double.isFinite(distance)||!Double.isFinite(width)||Math.abs(distance)>width*.5)continue;
         HydrologyConvergenceKey key=this.convergenceRegistry.register(
                 new HydrologyConvergenceKey(HydrologyConvergenceKey.Receiver.RIVER,network,along,bx,bz));
         targets.putIfAbsent(key,new WaterTarget(key,startX,startZ));
      }
   }

   private boolean plannedCreekAt(int chunkX, int chunkZ) {
      WaterField.CreekSource source = this.routeCreeks;
      return source != null && source.waterOrBank(chunkX, chunkZ);
   }

   private RouteResult route(int startX,int startZ,List<WaterTarget> targets) {
      int radius=this.streamRange,minX=startX-radius,maxX=startX+radius,minZ=startZ-radius,maxZ=startZ+radius;
      Map<Long,WaterTarget> goals=new HashMap<>();
      for(WaterTarget t:targets)goals.putIfAbsent(cellKey(t.x,t.z),t);
      if(goals.isEmpty())return null;
      Map<Long,Byte> highwayAxes=highwayAxes(minX,minZ,maxX,maxZ);
      PriorityQueue<RouteNode> open=new PriorityQueue<>(Comparator.comparingInt((RouteNode n)->n.score)
              .thenComparingInt(n->n.cost).thenComparingLong(n->n.key));
      Map<Long,Integer> best=new HashMap<>();Map<Long,Long> previous=new HashMap<>();
      long startKey=routeKey(startX,startZ,4,0);
      open.add(new RouteNode(startKey,startX,startZ,4,0,0,heuristic(startX,startZ,targets)));
      best.put(startKey,0);
      long goalKey=Long.MIN_VALUE;WaterTarget reached=null;
      int expansions=0,cityBlocks=0,waterBlocks=0,creekBlocks=0;
      int highwayParallelBlocks=0,highwayWidthBlocks=0,highwayTurnBlocks=0;
      final int[] dx={1,-1,0,0},dz={0,0,1,-1};
      while(!open.isEmpty()&&expansions++<MAX_ROUTE_EXPANSIONS) {
         RouteNode node=open.poll();Integer currentBest=best.get(node.key);
         if(currentBest==null||currentBest!=node.cost)continue;
         WaterTarget endpoint=goals.get(cellKey(node.x,node.z));
         if(endpoint!=null){goalKey=node.key;reached=endpoint;break;}
         for(int direction=0;direction<4;direction++) {
            int nx=node.x+dx[direction],nz=node.z+dz[direction];
            if(nx<minX||nx>maxX||nz<minZ||nz>maxZ)continue;
            if(this.inOuterCity(nx,nz)){cityBlocks++;continue;}
            WaterTarget proposed=goals.get(cellKey(nx,nz));
            boolean exactCreekGoal=proposed!=null&&proposed.key.receiver()==HydrologyConvergenceKey.Receiver.CREEK;
            WaterField.Kind base=this.water.baseKindAt(nx,nz);
            if(base!=WaterField.Kind.NONE&&base!=WaterField.Kind.RIVER&&base!=WaterField.Kind.OCEAN
                    &&!this.water.isReceivingWaterAt(nx,nz)){waterBlocks++;continue;}
            if(this.plannedCreekAt(nx,nz)&&!exactCreekGoal){creekBlocks++;continue;}
            byte axes=highwayAxes.getOrDefault(cellKey(nx,nz),(byte)0);
            int moveAxis=dx[direction]!=0?1:2;
            if(axes!=0&&(axes&moveAxis)!=0){highwayParallelBlocks++;continue;}
            if(node.highwayRun>0&&direction!=node.direction){highwayTurnBlocks++;continue;}
            int run=axes==0?0:node.highwayRun+1;if(run>MAX_HIGHWAY_CROSSING_CHUNKS){highwayWidthBlocks++;continue;}
            int cost=node.cost+10+(axes==0?0:9)+(node.direction!=4&&node.direction!=direction?2:0);
            long next=routeKey(nx,nz,direction,run);Integer old=best.get(next);if(old!=null&&old<=cost)continue;
            best.put(next,cost);previous.put(next,node.key);
            open.add(new RouteNode(next,nx,nz,direction,run,cost,cost+heuristic(nx,nz,targets)*10));
         }
      }
      this.lastRouteExpansionsForProbe=expansions;this.lastRouteFrontierForProbe=open.size();
      this.lastRouteCityBlocksForProbe=cityBlocks;this.lastRouteWaterBlocksForProbe=waterBlocks;
      this.lastRouteCreekBlocksForProbe=creekBlocks;this.lastRouteHighwayParallelBlocksForProbe=highwayParallelBlocks;
      this.lastRouteHighwayWidthBlocksForProbe=highwayWidthBlocks;this.lastRouteHighwayTurnBlocksForProbe=highwayTurnBlocks;
      if(goalKey==Long.MIN_VALUE||reached==null)return null;
      List<int[]> reversed=new ArrayList<>();long cursor=goalKey;
      while(cursor!=startKey){reversed.add(decodeCell(cursor));Long parent=previous.get(cursor);if(parent==null)return null;cursor=parent;}
      reversed.add(new int[]{startX,startZ});java.util.Collections.reverse(reversed);
      return new RouteResult(reversed,reached);
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

   public static final class MountainSite {
      public final String name;
      public final double centerX, centerZ, radiusX, radiusZ, angle;
      public final int peakClearance;
      private final Noise2D broad, ridge;
      private final int summitHeight;

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
         // Cap the massif itself, rather than treating the dimension ceiling
         // as its target summit.
         this.summitHeight = 80 + (int)(mix(h ^ 0x5045414B48454947L) >>> 62) * 3;
      }

      public boolean contains(int x, int z) {
         return normalizedRadius(x, z) < 1.0;
      }

        public int heightAt(int x, int z, int baseY, int maxHeight) {
         double r = normalizedRadius(x, z);
         if (r >= 1.0) return baseY;

         double dx = (x - this.centerX) / this.radiusX;
         double dz = (z - this.centerZ) / this.radiusZ;
         double c = Math.cos(this.angle), s = Math.sin(this.angle);
         double flank = dx * c - dz * s;
         // The opposite flank starts eroding near the foot of the mountain;
         // the steep flank holds a high shoulder before breaking into cliffs.
         double steepSide = smoothStep(-0.20, 0.55, flank);
         double falloffStart = 0.16 + 0.62 * steepSide;
         double t = Math.max(0.0, Math.min(1.0, (r - falloffStart) / (1.0 - falloffStart)));
         double fade = 1.0 - smoothStep(0.0, 1.0, t);

         double broadNoise = this.broad.fbm(x / 470.0, z / 470.0, 3, 0.52);
         double ridged = this.ridge.ridged(x / 165.0, z / 165.0, 4);
         // A broader, low-frequency offset keeps the escarpments from reading
         // as perfect concentric rings. Gullies cut irregularly into their face.
         double cliffWarp = this.ridge.fbm(x / 145.0, z / 145.0, 2, 0.5) * 0.045;
         double crown = 0.90 + broadNoise * 0.08 + (ridged - 0.5) * 0.08;
         // The declared summit cap must be attained, not merely used as a
         // ceiling. A rounded core blends into the asymmetric shoulder.
         double summitCore = 1.0 - smoothStep(0.025, 0.18, r);
         double innerCliff = 0.12 * smoothStep(0.59 + cliffWarp, 0.64 + cliffWarp, r);
         double outerCliff = 0.15 * smoothStep(0.77 + cliffWarp, 0.81 + cliffWarp, r);
         double cliffDrop = steepSide * (innerCliff + outerCliff);
         double gullies = steepSide * 0.055 * ridged * ridged * Math.max(0.0, 1.0 - r);
         double rough = (broadNoise * 7.0 + (ridged - 0.48) * 10.0) * Math.max(0.0, 1.0 - r);

         int top = Math.min(baseY + this.summitHeight, maxHeight - this.peakClearance);
         double available = Math.max(0.0, top - baseY);
         double body = Math.max(0.0, fade * crown - cliffDrop - gullies);
         double relief = available * Math.max(body, summitCore) + rough * (1.0 - summitCore);
         return (int)Math.max(baseY, Math.min(top, Math.round(baseY + relief)));
      }

      private static double smoothStep(double a, double b, double value) {
         double t = Math.max(0.0, Math.min(1.0, (value - a) / (b - a)));
         return t * t * (3.0 - 2.0 * t);
      }

        public boolean snowy(int y, int baseY, int maxHeight) {
         int top = Math.min(baseY + this.summitHeight, maxHeight - this.peakClearance);
         int span = Math.max(0, top - baseY);
         return y >= baseY + Math.round(span * 0.78);
      }

        public boolean treeless(int y, int baseY, int maxHeight) {
         int top = Math.min(baseY + this.summitHeight, maxHeight - this.peakClearance);
         int span = Math.max(0, top - baseY);
         int treeLine = baseY + Math.max(34, (int)Math.round(span * 0.44));
         return y >= Math.min(top, treeLine);
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
      /** Natural bank width beyond the topographic freshwater contour. */
      public static final double SHORE_WIDTH_BLOCKS = 32.0;
      private static final double GRAD_STEP=6.0, GRAD_MIN=0.55, GRAD_MAX=1.8;
      public final String name;
      public final int gridX,gridZ;
      public final double centerX,centerZ,radiusX,radiusZ,angle;
      public final int waterY;
      public WildernessStreamCourse stream;
      private final double cosAngle,sinAngle,majorRadius;
      private final WildernessTerrainProfile terrainProfile;
      private final WildernessTerrainProfile.BasinFit basinFit;
      private final Noise2D warpNoise,bayNoise,detailNoise,coastNoise,bedNoise,shoreNoise;
      private final Map<Long,Boolean> chunkCoverage=new LinkedHashMap<Long,Boolean>(256,0.75f,true){
         @Override protected boolean removeEldestEntry(Map.Entry<Long,Boolean> eldest){return size()>8192;}
      };
      LakeSite(long seed,int gx,int gz,double centerX,double centerZ,double radiusX,double radiusZ,double angle,int waterY){
         this(seed,gx,gz,centerX,centerZ,radiusX,radiusZ,angle,waterY,null,null);
      }
      LakeSite(long seed,int gx,int gz,double centerX,double centerZ,double radiusX,double radiusZ,
               double angle,int waterY,WildernessTerrainProfile terrainProfile,
               WildernessTerrainProfile.BasinFit basinFit) {
         long h=mix(seed ^ cellKey(gx,gz) ^ 0x4C414B454F434541L);
         this.name="Lake "+generatedName(seed ^ 0x4C414B454E414D45L,gx,gz);
         this.gridX=gx;this.gridZ=gz;this.centerX=centerX;this.centerZ=centerZ;
         this.radiusX=radiusX;this.radiusZ=radiusZ;this.angle=angle;this.waterY=waterY;
         this.cosAngle=Math.cos(angle);this.sinAngle=Math.sin(angle);
         this.majorRadius=Math.max(radiusX,radiusZ);
         this.terrainProfile=terrainProfile;this.basinFit=basinFit;
         this.warpNoise=new Noise2D(h ^ 0x574152504C414B45L);
         this.bayNoise=new Noise2D(h ^ 0x424159534C414B45L);
         this.detailNoise=new Noise2D(h ^ 0x44455441494C4C4BL);
         this.coastNoise=new Noise2D(h ^ 0x434F4153544C414BL);
         this.bedNoise=new Noise2D(h ^ 0x424544464F524D4CL);
         this.shoreNoise=new Noise2D(h ^ 0x53484F52454C414BL);
      }
      public boolean terrainDerived(){return this.terrainProfile!=null&&this.basinFit!=null;}
      public WildernessTerrainProfile.BasinFit basinFit(){return this.basinFit;}
      public double normalizedRadius(double x,double z){
         double dx=x-centerX,dz=z-centerZ;
         double u=dx*cosAngle+dz*sinAngle,v=-dx*sinAngle+dz*cosAngle;
         return Math.sqrt(u*u/(radiusX*radiusX)+v*v/(radiusZ*radiusZ));
      }
      /** Positive inside open freshwater, negative outside the irregular coast. */
      public double distanceInsideShoreBlock(double x,double z){
         double f=field(x,z);
         double gx=(field(x+GRAD_STEP,z)-field(x-GRAD_STEP,z))/(2.0*GRAD_STEP);
         double gz=(field(x,z+GRAD_STEP)-field(x,z-GRAD_STEP))/(2.0*GRAD_STEP);
         if(this.terrainDerived()) {
            // The contour is defined by water level minus untouched land height.
            // Convert that elevation difference to horizontal signed distance.
            double gradient=Math.max(0.025,Math.min(0.45,Math.hypot(gx,gz)));
            return f/gradient;
         }
         double outsideExact=(SHORE_WIDTH_BLOCKS+24.0)*GRAD_MAX;
         if(f>=112.0||f<=-(outsideExact+48.0))return f;
         double gradient=Math.max(GRAD_MIN,Math.min(GRAD_MAX,Math.hypot(gx,gz)));
         double distance=f/gradient;
         if(f>40.0){double t=smoothStep(40.0,112.0,f);distance+=(f-distance)*t;}
         if(f< -outsideExact){double t=smoothStep(0.0,48.0,-outsideExact-f);distance+=(f-distance)*t;}
         return distance;
      }
      public boolean waterAt(int x,int z){return distanceInsideShoreBlock(x+0.5,z+0.5)>0.0;}
      public synchronized boolean intersectsChunk(int chunkX,int chunkZ){
         long key=cellKey(chunkX,chunkZ);Boolean cached=chunkCoverage.get(key);
         if(cached!=null)return cached;
         double bx=chunkX*16.0,bz=chunkZ*16.0;double[] offsets={0.0,8.0,16.0};
         boolean intersects=false;
         outer:for(double ox:offsets)for(double oz:offsets){
            if(distanceInsideShoreBlock(bx+ox,bz+oz)>=-SHORE_WIDTH_BLOCKS){intersects=true;break outer;}
         }
         chunkCoverage.put(key,intersects);return intersects;
      }
      /**
       * Shelf-free bathymetry: one continuous slope from the shallow margin
       * to the deep centre. No underwater shelf bands or depth plateaus.
       */
      public int depthAt(int x,int z){
         if(this.terrainDerived()) {
            // Follow the naturally submerged terrain floor. The bed therefore
            // deepens with the existing basin and never cuts a preset 25-block
            // cylindrical hole beneath an otherwise shallow landscape.
            double px=x+0.5,pz=z+0.5;
            double natural=this.terrainProfile.surfaceHeight(x,z);
            double depth=this.waterY-natural+1.0
                    +0.22*bedNoise.fbm(px/210.0,pz/210.0,2,0.52);
            return Math.max(1,Math.min(25,(int)Math.round(depth)));
         }
         double px=x+0.5,pz=z+0.5,distance=distanceInsideShoreBlock(px,pz);
         if(distance<=0.0)return 0;
         double basinSpan=Math.max(180.0,Math.min(radiusX,radiusZ)*0.92);
         double t=Math.max(0.0,Math.min(1.0,distance/basinSpan));
         double continuous=1.0+23.0*Math.pow(t,1.28);
         double reliefFade=smoothStep(18.0,basinSpan*0.72,distance);
         double relief=bedNoise.fbm(px/260.0,pz/260.0,2,0.52)*0.85*reliefFade;
         return Math.max(1,Math.min(25,(int)Math.round(continuous+relief)));
      }
      public double bedMaterialNoiseAt(double x,double z){return bedNoise.fbm(x/210.0,z/210.0,2,0.52);}
      public double shoreMaterialNoiseAt(double x,double z){return shoreNoise.fbm(x/52.0,z/52.0,2,0.5);}
      public double envelopeRadiusChunks(){return majorRadius*1.13/16.0+SHORE_WIDTH_BLOCKS/16.0;}
      private double field(double x,double z){
         double radius=majorRadius,rawRho=normalizedRadius(x,z);
         if(this.terrainDerived()) {
            // The lake's coastline is a topographic contour, not an ellipse
            // pasted into the map. Gentle shoreline noise only adds local
            // erosion; the existing wilderness elevation determines the bays.
            double wavelength=radius*1.30,warp=Math.min(18.0,radius*0.035);
            double px=x+warp*warpNoise.fbm(x/wavelength,z/wavelength,2,0.52);
            double pz=z+warp*warpNoise.fbm(x/wavelength+37.1,z/wavelength-61.7,2,0.52);
            int terrainY=this.terrainProfile.surfaceHeight((int)Math.floor(px),(int)Math.floor(pz));
            double contour=this.waterY-terrainY+1.15*coastNoise.noise(x/92.0,z/92.0);
            double envelope=radius*(1.13-rawRho);
            return smoothMin(contour,envelope,2.5);
         }
         double wavelength=radius*1.30,warp=radius*0.055;
         double px=x+warp*warpNoise.fbm(x/wavelength,z/wavelength,2,0.52);
         double pz=z+warp*warpNoise.fbm(x/wavelength+37.1,z/wavelength-61.7,2,0.52);
         double dx=px-centerX,dz=pz-centerZ;
         double u=dx*cosAngle+dz*sinAngle,v=-dx*sinAngle+dz*cosAngle;
         double warpedRho=Math.sqrt(u*u/(radiusX*radiusX)+v*v/(radiusZ*radiusZ));
         double value=radius*(1.0-warpedRho);
         value+=radius*0.135*bayNoise.fbm(x/(radius*0.82),z/(radius*0.82),3,0.52);
         value+=radius*0.030*detailNoise.fbm(x/(radius*0.22),z/(radius*0.22),2,0.5);
         value+=2.5*coastNoise.noise(x/84.0,z/84.0);
         double cap=radius*(1.13-rawRho);
         return smoothMin(value,cap,radius*0.055);
      }
      private static double smoothMin(double a,double b,double k){
         double h=Math.max(k-Math.abs(a-b),0.0)/k;
         return Math.min(a,b)-h*h*k*0.25;
      }
      private static double smoothStep(double a,double b,double value){
         if(b<=a)return value<a?0.0:1.0;
         double t=Math.max(0.0,Math.min(1.0,(value-a)/(b-a)));
         return t*t*(3.0-2.0*t);
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
      final HydrologyConvergenceKey key;final int x,z,distance;
      WaterTarget(HydrologyConvergenceKey key,int sx,int sz) {
         this.key=key;this.x=key.chunkX();this.z=key.chunkZ();this.distance=Math.abs(x-sx)+Math.abs(z-sz);
      }
   }
   private static final class RouteResult {
      final List<int[]> path;final WaterTarget target;
      RouteResult(List<int[]> path,WaterTarget target){this.path=path;this.target=target;}
   }

   private static final class RouteNode {
      final long key;
      final int x, z, direction, highwayRun, cost, score;
      RouteNode(long key, int x, int z, int direction, int highwayRun, int cost, int score) {
         this.key=key; this.x=x; this.z=z; this.direction=direction;
         this.highwayRun=highwayRun; this.cost=cost; this.score=score;
      }
   }

   /** Log each named location once per server process, including coordinates for test visits. */
   public static void announceMountain(MountainSite site, String worldName) {
      if (site == null) return;
      String key = "M:" + worldName + ":" + Math.round(site.centerX) + ":" + Math.round(site.centerZ);
      if (ANNOUNCED.add(key)) {
         Minions.i("Wilderness landmark in '%s': %s at block (%d,%d); summit target is near the world build ceiling",
                 worldName, site.name, Math.round(site.centerX), Math.round(site.centerZ));
      }
   }

   public static void announceLake(LakeSite site, String worldName) {
      if (site == null) return;
      String key = "L:" + worldName + ":" + Math.round(site.centerX) + ":" + Math.round(site.centerZ);
      if (ANNOUNCED.add(key)) {
         Minions.i("Wilderness landmark in '%s': %s at block (%d,%d); outlet course points=%d",
                 worldName, site.name, Math.round(site.centerX), Math.round(site.centerZ),
                 site.stream == null ? 0 : site.stream.pointCount());
      }
   }

   private static String generatedName(long seed, int x, int z) {
      long h = mix(seed ^ ((long)x * 7146057691288625177L) ^ ((long)z * -7046029254386353131L));
      return ROOTS[Math.floorMod(h, ROOTS.length)] + ENDS[Math.floorMod(mix(h), ENDS.length)];
   }
}
