package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import ch.k42.metropolis.grid.urbanGrid.wild.HydrologyConvergenceKey;

/**
 * Where the water is, from the seed alone: the river(s) and the ocean, each a pure function of position. No tile is
 * involved anywhere, so asking about a chunk gives the same answer from any tile, in any order.
 *
 * As a WorldLayout.Mask it answers the ZONE the street layout must stay out of: the chunks within one lattice cell of
 * any water or bank chunk. Snapping to the lattice (the same lattice the highway lanes sit on) keeps the edge of the
 * zone rectilinear, so the free land beside the water is made of rectangles the carver can cut into whole blocks
 * instead of a ragged staircase that would leave slivers. The chunks of the zone that are not themselves water or bank
 * are open ground.
 */
public final class WaterField implements WorldLayout.Mask {

   public enum Kind {
      NONE, RIVER, OCEAN, CREEK, LAKE, STREAM
   }

   /** How far from the open ocean, in blocks, a chunk still counts as the ocean's (the beach and shallows). */
   private static final double SHORE_MARGIN_BLOCKS = 32.0;

   private final RiverProvider main;
   private final RiverProvider zBound;
   private final OceanProvider ocean;
   private final boolean riverOn;
   private final boolean oceanOn;
   private final int lattice;
   /** Seed-stable creek coverage supplied before layout planning begins. */
   public interface CreekSource {
      /** Actual carrier water or bank, not merely a proposed planning envelope. */
      boolean waterOrBank(int chunkX, int chunkZ);
      default boolean reservedBankAt(int chunkX, int chunkZ) { return waterOrBank(chunkX, chunkZ); }
      default boolean hasExactBankReservation() { return false; }
      default boolean useRiverPlanningLattice() { return false; }
      /** Negative means the entire containing planning cell, as in legacy planning. */
      default int riverLatticePaddingChunks() { return -1; }
      default boolean protectedDryParkAt(int chunkX, int chunkZ) { return false; }
      /** Distance from a world-block point to the nearest accepted creek bank, or NaN when none is within the requested radius. */
      default double nearestBankDistanceBlocks(double blockX, double blockZ, double maxDistanceBlocks) { return Double.NaN; }
      /** Carrier from the already-prepared city-aware creek plan. */
      default ch.k42.metropolis.grid.urbanGrid.river.RiverProvider carrierAt(int chunkX,int chunkZ) { return null; }
      default void prepareForConvergenceArea(int minX,int minZ,int maxX,int maxZ) { }
      default List<HydrologyConvergenceKey> convergenceEndpoints(int minX,int minZ,int maxX,int maxZ) {
         return java.util.Collections.emptyList();
      }
   }
   private volatile CreekSource creeks;

   /** One accepted wilderness hydrology layer owns lake and tributary-stream geography. */
   public interface HydrologyLayer {
      boolean lakeOrBankAt(int chunkX,int chunkZ);
      boolean streamOrBankAt(int chunkX,int chunkZ);
      ch.k42.metropolis.grid.urbanGrid.river.RiverProvider carrierAt(int chunkX,int chunkZ);
      HydrologySample sampleAt(WaterField water,int blockX,int blockZ);
   }
   private volatile HydrologyLayer hydrology;

   public synchronized void setHydrologyLayer(HydrologyLayer source) {
      this.hydrology=source;
      this.zoneCache.clear();
   }

   public HydrologyLayer hydrologyLayer(){return this.hydrology;}
   /** Authoritative per-column sample from the same hydrology owner used for chunk zoning. */
   public HydrologySample hydrologySampleAt(int blockX,int blockZ) {
      HydrologyLayer source=this.hydrology;
      return source==null?HydrologySample.none(blockX,blockZ):
              source.sampleAt(this,blockX,blockZ);
   }

   public ch.k42.metropolis.grid.urbanGrid.river.RiverProvider streamCarrierAt(int x,int z) {
      HydrologyLayer source=hydrology;
      return source==null?null:source.carrierAt(x,z);
   }

   public boolean isLake(int x,int z) {
      HydrologyLayer source=hydrology;
      return source!=null&&source.lakeOrBankAt(x,z);
   }

   public boolean isStream(int x,int z) {
      HydrologyLayer source=hydrology;
      return source!=null&&source.streamOrBankAt(x,z);
   }

   /** Exact open receiving water (not its bank): the outlet a wilderness stream must reach. */
   public boolean isReceivingWaterAt(int x, int z) {
      if (oceanOn) {
         OceanProvider.Sample sample = ocean.sample(x * 16.0 + 8.0, z * 16.0 + 8.0);
         if (sample != null && sample.distanceInside > 0.0) return true;
         if (ocean.hasRivers() && ocean.hasSeaInfluence(x * 16.0 + 8.0, z * 16.0 + 8.0)) {
            double merged = ocean.distanceInsideShoreBlock(x * 16.0 + 8.0, z * 16.0 + 8.0);
            if (Double.isFinite(merged) && merged > 0.0) return true;
         }
      }
      return riverOn && (main.isRiver(x, z) || (zBound != null && zBound.isRiver(x, z)));
   }

   public int lattice() { return lattice; }

   /** These additional reservations are part of the original river-style creek handoff. */
   public boolean hasRiverStyleCreekPlanning() {
      CreekSource src = creeks;
      return src != null && src.useRiverPlanningLattice();
   }

   public boolean hasExactCreekBankReservation() {
      CreekSource src = creeks;
      return src != null && src.hasExactBankReservation();
   }

   public synchronized void setCreeks(CreekSource source) {
      this.creeks = source;
      this.zoneCache.clear();
      this.interfluveCache.clear();
      this.creekLatticeCache.clear();
   }

   /** The base creek reservation provider, captured by later hydrology phases.
    * Planners must query this source directly; they must not call kindAt()/isCreek()
    * while WaterField is composing the final creek/lake/stream result. */
   public synchronized CreekSource creekSource() {
      return this.creeks;
   }

   /** True only for a dry chunk temporarily held for river-style creek layout. */
   public boolean isCreekPlanningOnlyDryAt(int x, int z) {
      CreekSource src = creeks;
      return src != null && src.useRiverPlanningLattice()
              && !src.reservedBankAt(x, z) && !src.protectedDryParkAt(x, z)
              && baseZoneKind(x, z) == Kind.NONE && kindAt(x, z) == Kind.NONE
              && !plannedNaturalInterfluveAt(x, z) && hasCreekInLattice(x, z, src);
   }

   public OceanProvider ocean() { return oceanOn ? ocean : null; }

   /** Bounded cache of creek-independent lattice water. Never invalidated by setCreeks():
    * candidate footprint probes must not ask the creek planner to plan itself. */
   private final Map<Long, Kind> baseZoneCache = new java.util.LinkedHashMap<Long, Kind>(8192, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Long, Kind> eldest) { return size() > 32768; }
   };

   /** Excludes creek reservations to avoid recursion when checking candidate creek sites. */
   public synchronized Kind baseZoneKind(int x, int z) {
      int cx = Math.floorDiv(x, lattice), cz = Math.floorDiv(z, lattice);
      long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
      Kind cached = baseZoneCache.get(key);
      if (cached != null) return cached;
      int x0 = cx * lattice, z0 = cz * lattice;
      Kind result = Kind.NONE;
      outer:
      for (int a = 0; a < lattice; a++) for (int b = 0; b < lattice; b++) {
         Kind k = baseKindAt(x0 + a, z0 + b);
         if (k == Kind.OCEAN) { result = k; break outer; }
         if (k != Kind.NONE) result = k;
      }
      baseZoneCache.put(key, result);
      return result;
   }

   private final Map<Long, Kind> zoneCache = new HashMap<Long, Kind>();
   private final Map<Long, int[]> creekLatticeCache = new java.util.LinkedHashMap<Long, int[]>(4096, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) { return size() > 8192; }
   };
   private final Map<Long, Boolean> interfluveCache = new java.util.LinkedHashMap<Long, Boolean>(8192, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) { return size() > 32768; }
   };

   /** Matches the R30ea reference: reserve a bounded box around actual bank cells within each lattice cell. */
   private boolean hasCreekInLattice(int x, int z, CreekSource src) {
      int cx = Math.floorDiv(x, lattice), cz = Math.floorDiv(z, lattice);
      long key = ((long)cx << 32) ^ (cz & 0xffffffffL);
      int[] box;
      synchronized (this) {
         box = creekLatticeCache.get(key);
      }
      if (box == null) {
         int loX = Integer.MAX_VALUE, hiX = Integer.MIN_VALUE;
         int loZ = Integer.MAX_VALUE, hiZ = Integer.MIN_VALUE;
         int x0 = cx * lattice, z0 = cz * lattice;
         for (int i = 0; i < lattice; i++) {
            for (int j = 0; j < lattice; j++) {
               int bx = x0 + i, bz = z0 + j;
               if (src.reservedBankAt(bx, bz)) {
                  loX = Math.min(loX, bx); hiX = Math.max(hiX, bx);
                  loZ = Math.min(loZ, bz); hiZ = Math.max(hiZ, bz);
               }
            }
         }
         int[] made = new int[]{loX, hiX, loZ, hiZ};
         synchronized (this) {
            box = creekLatticeCache.get(key);
            if (box == null) {
               creekLatticeCache.put(key, made);
               box = made;
            }
         }
      }
      if (box[0] == Integer.MAX_VALUE) return false;
      int pad = src.riverLatticePaddingChunks();
      if (pad < 0) return true;
      int x0 = cx * lattice, z0 = cz * lattice;
      return x >= Math.max(x0, box[0] - pad)
              && x <= Math.min(x0 + lattice - 1, box[1] + pad)
              && z >= Math.max(z0, box[2] - pad)
              && z <= Math.min(z0 + lattice - 1, box[3] + pad);
   }

   public WaterField(RiverProvider main, RiverProvider zBound, OceanProvider ocean, int lattice) {
      this.main = main;
      this.zBound = zBound;
      this.ocean = ocean;
      this.riverOn = RiverConfig.isRiverEnabled();
      this.oceanOn = OceanConfig.isOceanEnabled();
      this.lattice = Math.max(1, lattice);
   }

   /** The west-east river (the one UrbanGrid.getBankEdgeBlockZ() serves). */
   public RiverProvider mainRiver() {
      return this.riverOn ? this.main : null;
   }

   /** The north-south river (the one UrbanGrid.getBankEdgeBlockX() serves), or null. */
   public RiverProvider zBoundRiver() {
      return this.riverOn ? this.zBound : null;
   }

   /** What this chunk is, as far as water goes. */
   public Kind kindAt(int x, int z) {
      Kind base = baseKindAt(x, z);
      if (base != Kind.NONE) return base;
      CreekSource src = creeks;
      if (src != null && src.waterOrBank(x, z)) return Kind.CREEK;
      HydrologyLayer source=hydrology;
      if(source!=null&&source.lakeOrBankAt(x,z))return Kind.LAKE;
      return source!=null&&source.streamOrBankAt(x,z)?Kind.STREAM:Kind.NONE;
   }

   public Kind baseKindAt(int x, int z) {
      if (this.oceanOn) {
         double bx = x * 16.0 + 8.0, bz = z * 16.0 + 8.0;
         double d = this.ocean.oceanDistanceInsideShoreBlock(bx, bz);
         if (!Double.isNaN(d) && d > -SHORE_MARGIN_BLOCKS) {
            return Kind.OCEAN;
         }
         // The river mouth: the sea's merged water (its coast united with the attached rivers' flared channels) reaches
         // beyond the ocean alone, and it is what OceanParcel paints. Inside the sea's reach it is the ocean's, so the river
         // runs into the sea through the same smooth funnel the painter draws instead of stopping against the coast.
         if (this.ocean.hasRivers() && this.ocean.hasSeaInfluence(bx, bz)) {
            double merged = this.ocean.distanceInsideShoreBlock(bx, bz);
            if (!Double.isNaN(merged) && merged > -SHORE_MARGIN_BLOCKS) {
               return Kind.OCEAN;
            }
         }
      }
      if (this.riverOn) {
         if (this.main.isRiverOrBank(x, z) || (this.zBound != null && this.zBound.isRiverOrBank(x, z))) {
            return Kind.RIVER;
         }
      }
      return Kind.NONE;
   }

   public boolean isWater(int x, int z) {
      return this.kindAt(x, z) != Kind.NONE;
   }

   /** Only the lattice-expanded ocean zone, excluding actual ocean water or bank. */
   public boolean isOceanLatticeOnly(int x, int z) {
      return zoneKind(x, z) == Kind.OCEAN && kindAt(x, z) != Kind.OCEAN;
   }

   public double oceanSignedDistanceAt(int x, int z) {
      return oceanOn ? ocean.oceanDistanceInsideShoreBlock(x * 16.0 + 8.0, z * 16.0 + 8.0)
              : Double.NaN;
   }

   /** The chunk's creek carrier source answered yes: it is a creek's water or bank. */
   public boolean isCreek(int x, int z) {
      CreekSource c = this.creeks;
      return c != null && c.waterOrBank(x, z);
   }

   /** Nearest exact accepted creek-bank distance in world blocks; NaN means no creek in range or no creek plan installed. */
   public double nearestCreekBankDistanceBlocks(double blockX, double blockZ, double maxDistanceBlocks) {
      CreekSource c = this.creeks;
      return c == null ? Double.NaN : c.nearestBankDistanceBlocks(blockX, blockZ, maxDistanceBlocks);
   }

   /** The river provider whose water or bank covers this chunk, or null. */
   public RiverProvider riverAt(int x, int z) {
      if (!this.riverOn) {
         return null;
      }
      if (this.main.isRiverOrBank(x, z)) {
         return this.main;
      }
      if (this.zBound != null && this.zBound.isRiverOrBank(x, z)) {
         return this.zBound;
      }
      return null;
   }

   public synchronized boolean plannedNaturalInterfluveAt(int x, int z) {
      if (!riverOn || creeks == null) return false;
      long key = ((long)x << 32) ^ (z & 0xffffffffL);
      Boolean cached = interfluveCache.get(key);
      if (cached != null) return cached;
      boolean natural = computeNaturalInterfluve(x, z);
      interfluveCache.put(key, natural);
      return natural;
   }

   private int interfluveOwnerAt(int x, int z) {
      CreekSource src = creeks;
      if (src != null && src.reservedBankAt(x, z)) return 1;
      Kind kind = baseZoneKind(x, z);
      if (kind == Kind.RIVER) return 2;
      if (kind == Kind.OCEAN) return 3;
      return -1;
   }

   /** Preserve short dry natural channels between genuine river and creek banks. */
   private boolean computeNaturalInterfluve(int x, int z) {
      if (creeks.reservedBankAt(x, z) || baseZoneKind(x, z) != Kind.NONE) return false;
      for (int axis = 0; axis < 2; axis++) {
         int dx = axis == 0 ? 1 : 0, dz = axis == 0 ? 0 : 1;
         int left = -1, right = -1, distLeft = 0, distRight = 0;
         for (int dist = 1; dist <= 7; dist++) {
            if (left < 0) {
               int owner = interfluveOwnerAt(x - dist * dx, z - dist * dz);
               if (owner >= 0) { left = owner; distLeft = dist; }
            }
            if (right < 0) {
               int owner = interfluveOwnerAt(x + dist * dx, z + dist * dz);
               if (owner >= 0) { right = owner; distRight = dist; }
            }
            if (left >= 0 && right >= 0) break;
         }
         if (distLeft > 0 && distRight > 0 && distLeft + distRight - 1 <= 6
                 && ((left == 1 && right == 2) || (left == 2 && right == 1))) return true;
      }
      return false;
   }

   /** The zone: every chunk of any lattice cell that holds water. */
   @Override
   public boolean at(int x, int z) {
      return this.zoneKind(x, z) != Kind.NONE || plannedNaturalInterfluveAt(x, z);
   }

   /** R30ea zoning separates true banks, temporary creek planning envelopes and natural interfluves. */
   public Kind zoneKind(int x, int z) {
      Kind base = baseZoneKind(x, z);
      if (base != Kind.NONE) return base;
      CreekSource src = creeks;
      if (src != null) {
         if (src.reservedBankAt(x, z)) return Kind.CREEK;
         if (src.useRiverPlanningLattice() && hasCreekInLattice(x, z, src)
                 && !src.protectedDryParkAt(x, z)) return Kind.CREEK;
      }
      HydrologyLayer source=hydrology;
      if(source!=null&&source.lakeOrBankAt(x,z))return Kind.LAKE;
      return source!=null&&source.streamOrBankAt(x,z)?Kind.STREAM:Kind.NONE;
   }
}
