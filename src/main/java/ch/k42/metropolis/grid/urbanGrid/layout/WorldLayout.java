package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.minions.Minions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Every block and street of the world, worked out from the highways alone. Nothing here knows about tiles: a tile
 * (or anything else) just asks for the window it needs, so a block that crosses a tile edge is the same block in
 * both tiles and no seam street, edge street or band-continuation street ever exists. Pure Java, no Bukkit.
 *
 * How the space is cut up. The highways come first. The rest of the world is cut into blocks and one-chunk streets:
 * 1. A coarse grid of super-cells, PITCH chunks across. The line along the west and north edge of each is a street,
 *    which is what gives open land (farm, forest) its blocks.
 * 2. Where a highway runs parallel to such a line closer than a block could fit between them, that line segment would
 *    be a street running alongside the highway, or a sliver. So there is no line there: the two super-cells it
 *    separated are one DOMAIN instead.
 * 3. Each domain is the space it can use: the free chunks (not highway, not a boundary street). It is cut by an exact
 *    search: a street is only ever placed where both of its sides are free chunks (so it never touches a highway or
 *    another street sideways) and where every piece left over can still hold a block of at least minBlock x
 *    minBlock. A piece that is one rectangle with both sides in [minBlock, maxBlock] is a block.
 *
 * With maxBlock >= 2 * minBlock any free span of minBlock or more can be cut exactly. The search is seeded by the
 * domain, and retried with a new salt (deterministically) in the unlikely case a domain dead-ends, so the result never
 * depends on who asks first.
 */
public final class WorldLayout {

   public static final int UNCLAIMED = 0, LANE = 1, STREET = 2, BLOCK = 3, ZONE = 4, NATURAL = 5;

   /** Decides what each block of a domain is zoned as, from the blocks alone (no tile involved). */
   public interface Zoner {
      /** Contexts for the blocks (base and size arrays, in chunks) of one domain whose chunk range is given. */
      ContextType[] zone(int[] bx, int[] bz, int[] sx, int[] sz, int minX, int minZ, int maxX, int maxZ);
   }

   /**
    * The landmarks of the plan: schematics bigger than a city block, each reserved a block of its own (its footprint, with a
    * street all round it) before the rest of the domain is carved. Which landmark stands where is a function of the seed alone
    * (see LandmarkPlans), so every tile, in any order, finds the same ones.
    */
   public interface LandmarkPlanner {
      /** Side of the square cell of chunks that holds sitesPerCell() landmark sites. */
      int CELL = 64;

      /** How many landmark sites each cell holds (0 = no landmarks). */
      int sitesPerCell();

      /** Site k of cell (cellX, cellZ): where it would like to stand and how big it is, or null if it holds none. Pure. */
      Landmark siteOf(int cellX, int cellZ, int site);
      /** R30ea deterministic landmark alternatives; first entry is always siteOf. */
      default List<Landmark> alternativesOf(int cellX, int cellZ, int site) {
         Landmark found = siteOf(cellX,cellZ,site);
         return found == null ? java.util.Collections.emptyList() : java.util.Collections.singletonList(found);
      }

      /**
       * Called when a site the planner returned could not be given a block in the domain that owns its anchor (the reason says
       * why). A logging hook only: this class stays free of Bukkit (see PlanValidator), so the planner decides what to do with it.
       */
      default void unplaced(Landmark landmark, String reason) {
      }
   }

   /** Fixed creek-side building footprints: unlike landmarks, these cannot move away from their creek mouths. */
   public interface FixedPlanner {
      List<Landmark> fixedIn(int minX, int minZ, int maxX, int maxZ);
      default void unplaced(Landmark landmark, String reason) { }
      /** The exact outward face of a creek-origin schematic may cross its own creek.
       * Such a crossing is reserved with the park rather than repaired later. */
      default boolean outletRoadAt(Landmark landmark, int x, int z) { return false; }
   }

   /** One landmark: a footprint (width x depth chunks) wanted near an anchor chunk, and what the planner chose to stand on it. */
   public static final class Landmark {
      public final int anchorX, anchorZ, width, depth;
      public final ContextType context;
      public final Object choice;

      public Landmark(int anchorX, int anchorZ, int width, int depth, ContextType context, Object choice) {
         this.anchorX = anchorX;
         this.anchorZ = anchorZ;
         this.width = width;
         this.depth = depth;
         this.context = context;
         this.choice = choice;
      }
   }

   /** A yes/no answer per chunk, pure in position. */
   public interface Mask {
      boolean at(int x, int z);
   }

   public static final class Rect {
      public final int minX, minZ, maxX, maxZ;

      public Rect(int minX, int minZ, int maxX, int maxZ) {
         this.minX = minX;
         this.minZ = minZ;
         this.maxX = maxX;
         this.maxZ = maxZ;
      }

      public int width() {
         return maxX - minX + 1;
      }

      public int depth() {
         return maxZ - minZ + 1;
      }

      public boolean intersects(int x0, int z0, int x1, int z1) {
         return maxX >= x0 && minX <= x1 && maxZ >= z0 && minZ <= z1;
      }

      @Override
      public String toString() {
         return "[" + minX + ".." + maxX + "," + minZ + ".." + maxZ + "]";
      }
   }

   /** One domain: its cells, and the finished carve of them. */
   public static final class Domain {
      public final int id;
      public final int minX, minZ, maxX, maxZ;      // bounding box in chunks
      final byte[][] kind;                           // [x - minX][z - minZ]
      final byte[][] diagnosticZoneOrigin;
      boolean[][] shoreRoad;                         // the chunks the shore road took (see shoreStreets)
      boolean[][] noRing;                            // chunks beside the water where no shore road is laid (see handToShore)
      // Boundary, landmark and fixed-park streets reserved BEFORE normal subdivision.
      // Local retiling may move ordinary subdivision streets, never these fixed roads.
      boolean[][] fixedRoadBeforeCarve;
      int ringSkipped;                               // how many: a shortfall the flexible blocks try to remove
      final List<Rect> blocks = new ArrayList<Rect>();
      public boolean solved;
      // A boundary fringe can depend on the neighbour's completed zoning.
      // Domains are published before solve finishes, so defer this check
      // until a caller actually retrieves the completed world plan.
      boolean planBuildComplete;
      boolean bendFringeFinalized;
      boolean coverageAudited;
      boolean preCoverageSolved;
      boolean coverageFailureAdded;
      String preCoverageFailNote = "";
      /** Number of dry, unallocated cells left by a physically incomplete carve. Zero is mandatory for a valid plan. */
      // One highway mask per solved domain. The exact-search road-capacity
      // callbacks may ask the same cell thousands of times; computing a new
      // network.snapshot(point) each time rebuilds link-route candidates and
      // can starve Paper's population watchdog.
      HighwayNetwork.Snapshot preexistingHighways;
      public int unresolvedDryCells;
      /** Legitimate dry natural ground and painter-owned shore after carving. */
      public int naturalRemainderCells, shoreRemainderCells;
      /** Planning-time river-scaffold reclamation (never a paint cleanup). */
      public int creekReclaimBlocks, creekReclaimBuildings, creekReclaimRoads, creekReclaimUnassignedDry;

      public int attempts;
      public String failNote = "";
      final java.util.Map<Rect, ContextType> contexts = new java.util.IdentityHashMap<Rect, ContextType>();
      // Hard frontage roads of developed districts (and other previously reserved
      // boundaries). Rural-only seams are not permanently urban infrastructure.
      final java.util.Set<Long> urbanBoundaryRoads = new java.util.HashSet<Long>();
      // Exact, dry FARM / FOREST-or-waterfront frame cells donated during
      // zoning, before any tile or painter exists. Never dissolve these.
      final java.util.Set<Long> ruralBoundaryRoads = new java.util.HashSet<Long>();
      // Recovered-lot frames are provisional during subdivision, not
      // automatically immutable CITY frontage after zoning.
      final java.util.Set<Long> provisionalRecoveredFrameRoads = new java.util.HashSet<Long>();
      final java.util.Map<Rect, Landmark> landmarks = new java.util.IdentityHashMap<Rect, Landmark>();   // the blocks reserved for landmarks
      // Only fixed-creek-origin perimeter cells that require a planned creek crossing.
      final java.util.Map<Long, Integer> creekOutletRing = new java.util.HashMap<Long, Integer>();
      // Only these validated district-side road segments may override an overly broad water/shore mask.
      public final int superCells;

      Domain(int id, int minX, int minZ, int maxX, int maxZ, int superCells) {
         this.id = id;
         this.minX = minX;
         this.minZ = minZ;
         this.maxX = maxX;
         this.maxZ = maxZ;
         this.superCells = superCells;
         this.kind = new byte[maxX - minX + 1][maxZ - minZ + 1];
         this.diagnosticZoneOrigin = new byte[maxX - minX + 1][maxZ - minZ + 1];
      }

      public List<Rect> blocks() {
         return blocks;
      }

      public int kindAt(int x, int z) {
         return x < minX || x > maxX || z < minZ || z > maxZ ? UNCLAIMED : kind[x - minX][z - minZ];
      }
   }

   private final HighwayNetwork network;
   private final long seed;
   private final int pitch, offX, offZ;

   /** World-layout lattice pitch in chunks; shared with creek placement. */
   public int pitch() { return pitch; }
   /** World-layout X lattice offset in chunks. */
   public int offX() { return offX; }
   /** World-layout Z lattice offset in chunks. */
   public int offZ() { return offZ; }
   // The limits in force. Normally the configured ones; while a domain is solved with flexible blocks (setBlockSlack) they are
   // relaxed for that domain alone and put back afterwards. Solving is single-threaded (every public method is synchronized).
   private int minBlock, maxBlock;
   private int baseMin, baseMax;
   private int blockSlack = 0;
   private final Mask zone;
   private volatile Zoner zoner;
   private volatile LandmarkPlanner landmarkPlanner;
   private volatile FixedPlanner fixedPlanner;
   private final int lattice;
   private final Map<Long, Domain> byCell = new HashMap<Long, Domain>();
   private final Map<Long, Boolean> conflicts = new HashMap<Long, Boolean>();
   private final Map<Integer, Domain> byId = new LinkedHashMap<Integer, Domain>();
   private int nextId = 1;
   public int largestDomain = 0, failedDomains = 0, totalDomains = 0, retries = 0, shoreCells = 0, searched = 0;

   /**
    * Flexible blocks: 0 keeps every block between minBlock and maxBlock and closes what no such block can use. Above 0, a
    * domain that would close chunks is solved again with the limits relaxed by 1, 2, ... up to this many chunks, and the
    * smallest relaxation that closes nothing is kept: blocks vary by as little as it takes, so there are no pockets. Call
    * before the layout is first queried.
    */
   /** A creek may not be attached to a layout with already solved domains. */
   public synchronized boolean hasSolvedDomains() {
      return !byId.isEmpty();
   }

   public synchronized void setBlockSlack(int slack) {
      this.blockSlack = Math.max(0, slack);
   }

   /** Failed domains, for the checker and for logging. */
   public synchronized List<Domain> failures() {
      List<Domain> out = new ArrayList<Domain>();
      for (Domain d : byId.values()) {
         if (!d.solved) {
            out.add(d);
         }
      }
      return out;
   }

   public WorldLayout(HighwayNetwork network, long seed, int minBlock, int maxBlock, int pitch) {
      this(network, seed, minBlock, maxBlock, pitch, 0, 0);
   }

   /** offX / offZ shift the super-cell lines, so no line has to fall on any particular chunk (the origin, a tile edge). */
   public WorldLayout(HighwayNetwork network, long seed, int minBlock, int maxBlock, int pitch, int offX, int offZ) {
      this(network, seed, minBlock, maxBlock, pitch, offX, offZ, null);
   }

   /**
    * @param zone chunks that belong to something else (the water: river, creek, ocean and the ground around it). They
    *             are carved around exactly like a highway lane and reported as ZONE; what to put there is the caller's.
    */
   public WorldLayout(HighwayNetwork network, long seed, int minBlock, int maxBlock, int pitch, int offX, int offZ, Mask zone) {
      this.zone = zone;
      this.lattice = latticeOf(pitch, offX, offZ);
      if (maxBlock < 2 * minBlock) {
         throw new IllegalArgumentException("maxBlock must be at least 2 * minBlock for every span to be cuttable");
      }
      this.network = network;
      this.seed = seed;
      this.minBlock = minBlock;
      this.maxBlock = maxBlock;
      this.baseMin = minBlock;
      this.baseMax = maxBlock;
      this.pitch = pitch;
      this.offX = Math.floorMod(offX, pitch);
      this.offZ = Math.floorMod(offZ, pitch);
   }

   /** The lane lattice the street lines are aligned to: pitch and both offsets are multiples of it (else 1 = unaligned). */
   private static int latticeOf(int pitch, int offX, int offZ) {
      for (int l = 16; l >= 2; l--) {
         if (pitch % l == 0 && Math.floorMod(offX, l) == 0 && Math.floorMod(offZ, l) == 0) {
            return l;
         }
      }
      return 1;
   }

   /** Zoning is a function of the domain, decided once when it is solved; set before the first query (first one wins). */
   /** Sets the landmark planner, once, before any domain is solved: a domain solved without it holds no landmarks. */
   public synchronized void setLandmarkPlanner(LandmarkPlanner planner) {
      if (this.landmarkPlanner == null) {
         this.landmarkPlanner = planner;
      }
   }

   /** Register fixed creek lots before solving any domains. */
   public synchronized void setFixedPlanner(FixedPlanner planner) {
      if (this.fixedPlanner == null) this.fixedPlanner = planner;
   }

   /** Explicit plan-owned road crossing at a creek-origin park wall.
    * 1 = east/west span, 2 = north/south span, 0 = ordinary parcel.
    * This must be queried only after the domain was solved. */
   public synchronized int creekOutletRingAxisAt(int x, int z) {
      Domain d = this.domainAt(x, z);
      Integer axis = d.creekOutletRing.get(key(x,z));
      return axis == null ? 0 : axis;
   }

   /** Street reserved by the final urban/countryside boundary validator. */
   public synchronized boolean urbanBoundaryRoadAt(int x, int z) {
      return this.domainAt(x,z).urbanBoundaryRoads.contains(key(x,z));
   }

   /** Planning-time protected FARM/FOREST and FARM/waterfront road strips. */
   public synchronized boolean ruralBoundaryRoadAt(int x, int z) {
      return this.domainAt(x,z).ruralBoundaryRoads.contains(key(x,z));
   }

   /** The landmark this block was reserved for, or null for an ordinary block. */
   public synchronized Landmark landmarkAt(Rect block) {
      return this.domainAt(block.minX, block.minZ).landmarks.get(block);
   }

   public synchronized void setZoner(Zoner zoner) {
      if (this.zoner == null && zoner != null) {
         this.zoner = zoner;
         // Creek planning / coastline masks can ask for a domain before the
         // UrbanGrid constructor has registered this callback. Such domains
         // are already carved, so solve() won't run zoneBlocks() again.
         // Retroactively assign their contexts once, without re-solving
         // geometry or modifying any street, highway or reserved footprint.
         java.util.Set<Domain> seen = java.util.Collections.newSetFromMap(
                 new java.util.IdentityHashMap<Domain, Boolean>());
         for (Domain d : this.byCell.values()) {
            if (seen.add(d) && !d.blocks.isEmpty()) {
               zoneBlocks(d);
               // Early domain queries before the zoner was installed may have
               // conservatively kept boundary fringe streets. Re-evaluate
               // with their actual neighbour contexts once the zoner exists.
               d.bendFringeFinalized = false;
            }
         }
      }
   }

   private void zoneBlocks(Domain d) {
      Zoner z = this.zoner;
      if (z == null || d.blocks.isEmpty()) {
         return;
      }
      int n = d.blocks.size();
      int[] bx = new int[n], bz = new int[n], sx = new int[n], sz = new int[n];
      for (int i = 0; i < n; i++) {
         Rect r = d.blocks.get(i);
         bx[i] = r.minX;
         bz[i] = r.minZ;
         sx[i] = r.width();
         sz[i] = r.depth();
      }
      ContextType[] out = z.zone(bx, bz, sx, sz, d.minX, d.minZ, d.maxX, d.maxZ);
      for (int i = 0; i < n && out != null && i < out.length; i++) {
         if (out[i] != null) {
            d.contexts.put(d.blocks.get(i), out[i]);
         }
      }
      // A landmark block is zoned as the planner chose (a landmark is never countryside)
      for (java.util.Map.Entry<Rect, Landmark> e : d.landmarks.entrySet()) {
         if (e.getValue().context != null) {
            d.contexts.put(e.getKey(), e.getValue().context);
         }
      }
      // Discharge only reclaimed frames' temporary ownership now that
      // their actual contexts are known. The existing urban-frontage
      // registration below re-protects every genuine developed road.
      // No road geometry, generation phase, or paint timing is changed.
      d.urbanBoundaryRoads.removeAll(d.provisionalRecoveredFrameRoads);
      d.provisionalRecoveredFrameRoads.clear();
      // Zoning is now known: repair missing URBAN/COUTRYSIDE frontage
      // before Morphomos is allowed to dissolve rural-only streets.
      // This may trim only an exterior row/column of a countryside rectangle;
      // urban rectangles, the creek, parks, and highways are immutable.
      // Complete developed perimeters without widening existing roads: first
      // reuse a road beyond an unallocated dry strip by extending the urban
      // rectangle, then link only safe, two-ended gaps in existing street
      // lines. Remaining natural, water, and cross-domain conflicts stay
      // explicit instead of receiving speculative parallel streets.
      // experimental post-zoning repair deliberately disabled: UrbanPerimeterCompletion.repair(d, this.zone, this.baseMax + this.blockSlack);
      // Where the entire outer face borders non-road terrain, an eligible
      // district may donate one ROW OF ITS OWN footprint to a locally joined
      // perimeter street; no oceanband or foreign terrain is claimed.
      // experimental post-zoning repair deliberately disabled: UrbanPerimeterInset.repair(d, Math.max(2, this.baseMin - this.blockSlack));
      // experimental post-zoning repair deliberately disabled: UrbanPartialShoreRepartition.repair(d, this.zone, Math.max(2, this.baseMin - this.blockSlack));
      UrbanRuralBoundaryRoads.repair(d, this.baseMin);
      // R14.5: reserve already-accepted dry right-angle corners before
      // redundant-frontage optimization. A missing end corner previously
      // made DoubleFrontageRoads reject a perfectly legal single-width
      // frontage (seed 13: 80..81,235..238), then this corner was reserved
      // too late for the optimizer to reconsider the double road.
      // The later call remains because a rural-boundary donation can create
      // additional eligible corners after the other planning passes.
      reserveDryFrameCorners(d);
      // Commit every developed district's already-selected road wrapper
      // BEFORE any redundant-road or highway-frontage optimization can
      // inspect it. Existing frontage is a reservation, not a provisional
      // road that a later zone-specific pass may silently dissolve.
      UrbanRuralBoundaryRoads.protectExistingUrbanFrontage(d);
      // Prevent two ordinary street columns from being committed between an
      // urban block and farmland/forest. Only a full, legally expandable
      // rectangle face with cross-connected road ends can donate a column;
      // all reserved/landmark/shore streets are immutable. This is the same
      // zoning-time road/parcel contract on every seed, never paint cleanup.
      DoubleFrontageRoads.preventWithPlan(d, this.baseMax + this.blockSlack,
              (wx,wz) -> plannedFixedBoundaryRoadAt(wx,wz), this);
      // A full countryside/urban rectangle face must not retain an ordinary
      // frontage street parallel to a completed two-lane highway. Absorb the
      // single redundant street column ONLY when both end-cap streets and
      // highway junctions remain connected; the highway is immutable.
      HighwayFrontageRoads.prevent(d, this.network.snapshot(d.minX - 2, d.minZ - 2,
              d.maxX + 2, d.maxZ + 2), this.baseMax + this.blockSlack);
      // A valid urban frontage street must survive countryside dissolution
      // everywhere, not only where the zoning-time boundary repair ran.
      UrbanRuralBoundaryRoads.protectExistingUrbanFrontage(d);
      // Complete legal countryside/waterfront boundary roads NOW, while the
      // rectangles and their contexts are known. This is NOT a tile-local
      // road repair: only a FARM rectangle can donate its own dry edge, and
      // only where the entire face touches actual FOREST/shore and the road
      // can join an established street without spanning open water.
      RuralContextBoundaryRoads.repair(d, Math.max(2,this.baseMin),this.zone);
      // A completed rural road may border NATURAL forest, which is not
      // represented by a ContextType.FOREST block. Protect its already
      // committed mixed-boundary pieces and reciprocal street corners.
      RuralBoundaryLinks.protect(d,this.zone);
      // Commit omitted DRY corners only while the zoning plan still owns
      // rectangle/road layout. DryRemainderOwnership must never claim an
      // otherwise complete accepted wrapper corner as natural woodland.
      reserveDryFrameCorners(d);
      // Reserve dry FOREST-adjacent margins as natural woodland, not ZONE
      // holes. This is a restrictive ownership handoff before any painter,
      // never a general permission to erase unclaimed district frontage.
      // experimental post-zoning repair deliberately disabled: ForestMarginHandoff.reclaim(d, this.zone);

   }


   /** The four right-angle corners of an accepted rectangle are part of the
    * same shared road frame as its straight sides. The solver occasionally
    * left a dry corner as ZONE while both incident street sides were accepted;
    * the later woodland handoff then made that missing corner permanent.
    * Only complete, already-plan-owned right angles qualify. Water and
    * non-street arms are never overwritten, and a same-context internal
    * Morphomos seam alone cannot acquire a new road.
    */
   private void reserveDryFrameCorners(Domain d) {
      for(Rect r : d.blocks) {
         for(int xi=0;xi<2;xi++) {
            int x=xi==0?r.minX-1:r.maxX+1;
            for(int zi=0;zi<2;zi++) {
               int z=zi==0?r.minZ-1:r.maxZ+1;
               if(x<d.minX||x>d.maxX||z<d.minZ||z>d.maxZ)continue;
               if(d.kindAt(x,z)!=ZONE)continue;
               if(this.zone!=null && this.zone.at(x,z)
                      && !(this.zone instanceof WaterField wf && wf.isCreekPlanningOnlyDryAt(x,z)))continue;
               int sx=x==r.minX-1?1:-1, sz=z==r.minZ-1?1:-1;
               int hx=x+sx, hz=z, vx=x, vz=z+sz;
               if(d.kindAt(hx,hz)!=STREET||d.kindAt(vx,vz)!=STREET)continue;
               if(sameCountrysideSeam(d,hx,hz)&&sameCountrysideSeam(d,vx,vz))continue;
               d.kind[x-d.minX][z-d.minZ]=STREET;
               if(d.contexts.get(r)!=null && d.contexts.get(r).isMorphomos())
                  d.ruralBoundaryRoads.add(key(x,z));
               else d.urbanBoundaryRoads.add(key(x,z));
            }
         }
      }
   }

   private boolean sameCountrysideSeam(Domain d,int x,int z) {
      ContextType west=localContext(d,x-1,z),east=localContext(d,x+1,z);
      ContextType north=localContext(d,x,z-1),south=localContext(d,x,z+1);
      return west!=null&&west==east&&west.isMorphomos()
                ||north!=null&&north==south&&north.isMorphomos();
   }
   private ContextType localContext(Domain d,int x,int z) {
      if(d.kindAt(x,z)!=BLOCK)return null;
      for(Rect r:d.blocks)if(x>=r.minX&&x<=r.maxX&&z>=r.minZ&&z<=r.maxZ)
         return d.contexts.get(r);
      return null;
   }

   /** What the block holding chunk (x, z) is zoned as, or null if the chunk is not in a block (lane, street, water, ground). */
   public synchronized ContextType contextAt(int x, int z) {
      Domain d = this.domainAt(x, z);
      for (Rect r : d.blocks) {
         if (x >= r.minX && x <= r.maxX && z >= r.minZ && z <= r.maxZ) {
            return d.contexts.get(r);
         }
      }
      return null;
   }

   /** The block that holds chunk (x, z), or null if the chunk is not in a block. */
   public synchronized Rect blockAt(int x, int z) {
      Domain d = this.domainAt(x, z);
      for (Rect r : d.blocks) {
         if (x >= r.minX && x <= r.maxX && z >= r.minZ && z <= r.maxZ) {
            return r;
         }
      }
      return null;
   }

   /** Is chunk (x, z) water of the zone mask the layout stays out of (as opposed to the shore closed beside it)? */
   public boolean isWater(int x, int z) {
      if (this.zone == null) return false;
      // The river-style creek lattice is a TEMPORARY subdivision reservation,
      // not physical water. A completed CreekReclaim street or district has
      // already taken exclusive ownership of that dry cell. Treating it as
      // water after commitment can falsely license cut road ends in PlanRegions
      // and hide real frontage failures from PlanValidator. The original
      // river/ocean zones and the actual bounded-creek banks remain unchanged.
      if (this.zone instanceof WaterField) {
         WaterField water = (WaterField) this.zone;
         if (water.isCreekPlanningOnlyDryAt(x, z)) {
            int committed = this.domainAt(x, z).kindAt(x, z);
            if (committed == BLOCK || committed == STREET || committed == LANE)
               return false;
         }
      }
      return this.zone.at(x, z);
   }

   /** Physical water only: do not recurse into domainAt during zoning and frontage repair. */
   public boolean physicalWaterWithoutDomainLookup(int x, int z) {
      if (zone == null || !zone.at(x, z)) return false;
      return !(zone instanceof WaterField
              && ((WaterField) zone).isCreekPlanningOnlyDryAt(x, z));
   }

   /** Does this exact chunk belong to a domain, not just its rectangular bounding box?
    * Reading byCell directly avoids accidentally solving a foreign domain while
    * Morphomos constructs region objects for the current one. */
   synchronized boolean ownedByDomain(Domain d, int x, int z) {
      return byCell.get(key(fd(x - offX, pitch), fd(z - offZ, pitch))) == d;
   }

   /** The domain's blocks and the id of the domain, for region work (see PlanRegions). */
   synchronized Domain domainOf(int x, int z) {
      return this.domainAt(x, z);
   }

   /** What this block is zoned as. */
   public synchronized ContextType contextOf(Rect block) {
      return this.domainAt(block.minX, block.minZ).contexts.get(block);
   }

   private static long key(int a, int b) {
      return ((long) a << 32) ^ (b & 0xffffffffL);
   }

   private static int fd(int a, int b) {
      return Math.floorDiv(a, b);
   }

   // ------------------------------------------------------------------ domains

   /** The domain that holds chunk (x, z). */
   public synchronized Domain domainAt(int x, int z) {
      Domain d = domainOfCell(fd(x - offX, pitch), fd(z - offZ, pitch));
      finalizeBendFringe(d);
      return d;
   }

   private Domain domainOfCell(int ca, int cb) {
      Domain d = byCell.get(key(ca, cb));
      if (d != null) {
         return d;
      }
      // grow the set of super-cells across every boundary line that has no street (a highway runs beside it)
      Map<Long, int[]> members = new LinkedHashMap<Long, int[]>();
      List<int[]> queue = new ArrayList<int[]>();
      members.put(key(ca, cb), new int[]{ca, cb});
      queue.add(new int[]{ca, cb});
      for (int qi = 0; qi < queue.size(); qi++) {
         int a = queue.get(qi)[0], b = queue.get(qi)[1];
         int[][] nb = {{a - 1, b, 0}, {a + 1, b, 1}, {a, b - 1, 2}, {a, b + 1, 3}};
         for (int[] n : nb) {
            if (members.containsKey(key(n[0], n[1]))) {
               continue;
            }
            boolean vertical = n[2] < 2;                                   // the shared line runs north-south
            int line = n[2] == 0 ? a : n[2] == 1 ? a + 1 : n[2] == 2 ? b : b + 1;
            int seg = vertical ? b : a;
            if (lineConflicts(vertical, line, seg)) {
               members.put(key(n[0], n[1]), n);
               queue.add(n);
            }
         }
      }
      int id = nextId++;
      int amin = Integer.MAX_VALUE, bmin = Integer.MAX_VALUE, amax = Integer.MIN_VALUE, bmax = Integer.MIN_VALUE;
      for (int[] m : members.values()) {
         amin = Math.min(amin, m[0]);
         amax = Math.max(amax, m[0]);
         bmin = Math.min(bmin, m[1]);
         bmax = Math.max(bmax, m[1]);
      }
      d = new Domain(id, amin * pitch + offX, bmin * pitch + offZ, (amax + 1) * pitch + offX - 1, (bmax + 1) * pitch + offZ - 1, members.size());
      for (int[] m : members.values()) {
         byCell.put(key(m[0], m[1]), d);
      }
      byId.put(id, d);
      totalDomains++;
      largestDomain = Math.max(largestDomain, members.size());
      solve(d, members);
      d.planBuildComplete = true;
      return d;
   }

   /**
    * Would the street along this super-cell boundary line run alongside a highway or leave a sliver? True when a lane
    * runs parallel to the segment (three or more chunks in a row; a highway crossing it is only two) within
    * minBlock + 1 chunks of it, on either side, or on it.
    */
   private boolean lineConflicts(boolean vertical, int line, int seg) {
      long ck = ((vertical ? 1L : 0L) << 62) ^ (key(line, seg) & 0x3fffffffffffffffL);
      Boolean c = conflicts.get(ck);
      if (c != null) {
         return c;
      }
      int reach = minBlock;
      int pos = line * pitch + (vertical ? offX : offZ), s0 = seg * pitch + (vertical ? offZ : offX), s1 = s0 + pitch - 1;
      // A lane run that crosses the end of the segment counts too: judged on its own, the end of a long run would be
      // only a cell or two long here, and the street would end up touching the corner of a highway it runs along.
      int ext = 2;
      int x0 = vertical ? pos - reach : s0 - ext, x1 = vertical ? pos + reach : s1 + ext;
      int z0 = vertical ? s0 - ext : pos - reach, z1 = vertical ? s1 + ext : pos + reach;
      HighwayNetwork.Snapshot snap = network.snapshot(x0, z0, x1, z1);
      boolean conflict = false;
      for (int off = -reach; off <= reach && !conflict; off++) {
         if (this.lattice > 1 && (off == 0 || off == 1) && Math.floorMod(pos, this.lattice) == 0) {
            continue;   // a lane lying along the line itself (it is two wide): no street is painted over it, nothing runs beside it
         }
         int run = 0;
         for (int t = s0 - ext; t <= s1 + ext; t++) {
            boolean lane = vertical ? snap.at(pos + off, t) : snap.at(t, pos + off);
            run = lane ? run + 1 : 0;
            if (run >= 3) {
               conflict = true;
               break;
            }
         }
      }
      if (!conflict && zone != null) {
         conflict = zoneSliver(vertical, pos, s0, s1);
      }
      conflicts.put(ck, conflict);
      return conflict;
   }

   /**
    * Would the street along this line leave a gap of less than minBlock between itself and the water zone? From each
    * street chunk that is not itself zone, the nearest zone chunk on either side (within minBlock chunks) gives a gap;
    * a gap of 0..minBlock-1 is a sliver no block can fill. Three such chunks in a row make a conflict, exactly as for a
    * lane. A river that crosses the line is not one: beside the crossing there is no zone to the side, only along the line.
    */
   private boolean zoneSliver(boolean vertical, int pos, int s0, int s1) {
      int run = 0;
      for (int t = s0 - 2; t <= s1 + 2; t++) {
         boolean tight = false;
         if (!(vertical ? zone.at(pos, t) : zone.at(t, pos))) {
            for (int side = -1; side <= 1 && !tight; side += 2) {
               for (int g = 1; g <= minBlock; g++) {
                  if (vertical ? zone.at(pos + side * g, t) : zone.at(t, pos + side * g)) {
                     tight = true;
                     break;
                  }
               }
            }
         }
         run = tight ? run + 1 : 0;
         if (run >= 3) {
            return true;
         }
      }
      return false;
   }

   // ------------------------------------------------------------------- solving

   private static final int ATTEMPTS = 3, SEARCH_ATTEMPTS = 6;

   /**
    * How far from the water a leftover chunk still belongs to its shore. Must equal UrbanGrid.SHORE_REACH, which paints
    * such a chunk as that water's shore (no block size comes into it: one chunk or fifty, the shore takes it).
    */
   public static final int SHORE_REACH = 16;
   /** Dry ocean fringe can never borrow an ocean painter beyond this inland distance.
    * This is independent of the farther-reaching city/river planning reservation.
    * True water and river/creek banks keep their pre-existing ownership. */
   public static final double MAX_OCEAN_SHORE_INLAND_BLOCKS = 160.0;

   /** Is the world chunk (x, z) within SHORE_REACH of the water zone, so that its water's shore takes it? */
   private boolean inShoreReach(int x, int z) {
      if (this.zone == null) {
         return false;
      }
      // A single ocean-containing lattice cell can extend this 16-chunk
      // search far inland. Do not exempt a dry city pocket from normal
      // district absorption solely because there is remote ocean nearby.
      // Real river/creek shore reach is deliberately unchanged.
      WaterField water = this.zone instanceof WaterField ? (WaterField) this.zone : null;
      boolean distantFromCoast = water != null
              && water.kindAt(x,z) != WaterField.Kind.OCEAN
              && water.oceanSignedDistanceAt(x,z) < -MAX_OCEAN_SHORE_INLAND_BLOCKS;
      for (int dx = -SHORE_REACH; dx <= SHORE_REACH; dx += 3) {
         for (int dz = -SHORE_REACH; dz <= SHORE_REACH; dz += 3) {
            if (water != null) {
               WaterField.Kind kind = water.zoneKind(x + dx, z + dz);
               // Bounded-creek banks are already reserved one chunk at a time
               // by WaterField.CreekSource.reservedBankAt(). Do NOT promote
               // an additional 16-chunk ring of nearby dry city land to
               // unbuildable shore. That broad shore reach belongs to the
               // existing river/ocean planner, not the bounded tributary.
               if (kind != WaterField.Kind.NONE
                       && !(kind == WaterField.Kind.CREEK && water.hasExactCreekBankReservation())
                       && !(distantFromCoast && kind == WaterField.Kind.OCEAN)) {
                  return true;
               }
            } else if (this.zone.at(x + dx, z + dz)) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Chunks the domain closed as ground that nothing takes: not the zone mask itself (the water), and not within reach of
    * the water either, where the shore takes any leftover whatever its size. Only these are pockets the blocks must absorb.
    */
   private int pocketsOf(Domain d) {
      int n = 0;
      for (int x = d.minX; x <= d.maxX; x++) {
         for (int z = d.minZ; z <= d.maxZ; z++) {
            if (d.kind[x - d.minX][z - d.minZ] == ZONE && !(this.zone != null && this.zone.at(x, z))) {
               n++;
            }
         }
      }
      return n;
   }

   /** What a carve still lacks: ground that is not the water, and shore road left out beside the water. 0 = nothing wasted. */
   private int deficit(Domain d) {
      return pocketsOf(d) + d.ringSkipped;
   }

   /** A complete district plan must not call discarded dry ground a success.
    * Remaining ZONE cells must be owned by actual water/bank reservation
    * or by the SAME established shore reach used by the terrain painter.
    * A separately committed planning-time dry forest remainder is NATURAL,
    * never a ZONE that the audit quietly ignores. We do not enlarge the
    * river mask or convert urban frontage into woodland here.
    * This audit intentionally marks an incomplete domain as FAILED: callers
    * must not use the Peeler's "closed means solved" result as proof of a
    * valid city layout. It does not pretend to repair the failed geometry.
    */
   private void auditCompletedCoverage(Domain d, PlannedShoreOwnership.Prepared shore) {
      // Idempotent if the zoner was attached late. Earlier dry-deficit audit
      // messages must not survive a now-valid, neighbour-aware handoff.
      if (d.coverageAudited) {
         if (d.coverageFailureAdded) failedDomains--;
         d.solved = d.preCoverageSolved;
         d.failNote = d.preCoverageFailNote;
      } else {
         d.preCoverageSolved = d.solved;
         d.preCoverageFailNote = d.failNote;
         d.coverageAudited = true;
      }
      int missing = 0;
      // Dry cells reserved to the renderer's bank/shore are already owned:
      // they cannot be counted as unresolved merely because no city parcel
      // occupies them. Crucially, this does NOT relabel their ZONE status,
      // so the river/ocean/creek painter retains the entire bank profile.
      for (int ix=0;ix<d.kind.length;ix++)for(int iz=0;iz<d.kind[ix].length;iz++) {
         if(d.kind[ix][iz]!=ZONE)continue;
         int wx=d.minX+ix,wz=d.minZ+iz;
         if(this.zone!=null && this.zone.at(wx,wz))continue;
         if(!shore.owns(wx,wz))missing++;
      }
      d.unresolvedDryCells = missing;
      d.coverageFailureAdded = missing > 0 && d.solved;
      if (missing > 0) {
         if (d.coverageFailureAdded) failedDomains++;
         d.solved = false;
         String detail = "incomplete subdivision: " + missing
                 + " unallocated dry cells (not explicitly reserved water/bank)";
         d.failNote = d.failNote.isEmpty() ? detail : d.failNote + "; " + detail;
      }
   }

   private static void DEBUG_FRAMES(Domain d,String stage) {
      if (!Boolean.getBoolean("metropolis.debugframes")) return;
      int missing=0,block=0,zone=0,other=0,out=0;
      for (Rect r:d.blocks) {
         for(int side=0;side<4;side++) for(int t=0;t<((side&1)==0?r.width():r.depth());t++) {
            int x=(side&1)==0?r.minX+t:(side==1?r.maxX+1:r.minX-1);
            int z=(side&1)==1?r.minZ+t:(side==0?r.minZ-1:r.maxZ+1);
            int k=d.kindAt(x,z);
            if(k==STREET||k==LANE)continue;
            if(k==UNCLAIMED){out++;continue;}
            missing++;
            if(k==BLOCK)block++; else if(k==ZONE)zone++;else other++;
         }
      }
      System.err.println("FRAMESTAGE "+stage+" missing="+missing+" block="+block+" zone="+zone+" other="+other+" outside="+out+" rects="+d.blocks.size());
   }

   private void solve(Domain d, Map<Long, int[]> members) {
      if (this.blockSlack <= 0) {
         solveOnce(d, members);
         adoptLandmarks(d);
         if (zone instanceof WaterField)
            CreekReclaim.apply(d, (WaterField) zone, minBlock, maxBlock, this.seed);
         zoneBlocks(d);
         // Domain-edge classification requires the adjacent domain's solved
         // plan, so the final release and deficit audit occur at domainAt().
         return;
      }
      final int failed0 = failedDomains, shore0 = shoreCells, retries0 = retries, searched0 = searched;
      try {
         int bestS = 0, bestDeficit = Integer.MAX_VALUE, lastDeficit = 0;
         for (int s = 0; s <= this.blockSlack; s++) {
            solveAt(d, members, s, failed0, shore0, retries0, searched0);
            lastDeficit = deficit(d);
            if (lastDeficit < bestDeficit) {
               bestDeficit = lastDeficit;
               bestS = s;
            }
            if (lastDeficit == 0) {
               break;           // the smallest relaxation that wastes nothing
            }
         }
         if (lastDeficit > bestDeficit) {
            solveAt(d, members, bestS, failed0, shore0, retries0, searched0);
         }
         // Whatever even the widest blocks could not use is re-mathed into the blocks beside it (and the road): the domain is
         // used to the last chunk, so no ground is left over in the middle of the land.
         adoptLandmarks(d);
         absorbPockets(d);
         DEBUG_FRAMES(d,"absorb-1");
         // Planning-time, transactional rectangle/street edge exchanges.
         // This runs before zoning or any parcel/terrain painter, with all
         // fixed streets and water owners protected.
         LayoutBoundaryExchange.repair(d, this.zone, this.minBlock, this.maxBlock);
         DEBUG_FRAMES(d,"exchange");
         absorbPockets(d);
         DEBUG_FRAMES(d,"absorb-2");
         // Recover partial urban/rural perimeter gaps by moving a complete
         // existing street, rather than filling its neighboring ZONE with a
         // doubled road. Verified as one atomic rectangle/road exchange.
         LayoutBoundaryExchange.repairDryGapPerimeters(d, this.zone,
                 this.minBlock, this.maxBlock);
         if (zone instanceof WaterField)
            CreekReclaim.apply(d, (WaterField) zone, minBlock, maxBlock, this.seed);
         zoneBlocks(d);
         DEBUG_FRAMES(d,"zoned");
         // See finalizeBendFringe(): no local-only false rejection at borders.
      } finally {
         this.minBlock = this.baseMin;
         this.maxBlock = this.baseMax;
      }
   }

   /**
    * A complete highway bend can have an obsolete *street* tail from the
    * subdivision carve immediately beyond the bend's closed highway face.
    * The matching dry ZONE strip then has no parcel owner. This is NOT an
    * alternate highway route: the HighwayNetwork's committed mask already
    * excludes it. Release only the precise forest/forest dead-end pair in
    * the WORLD plan, before any road or wilderness parcel is instantiated.
    * City frontage, fixed streets, parks, water and live highway exits never
    * satisfy the guard; an incomplete city district is never naturalized.
    */
   private void finalizeBendFringe(Domain d) {
      if (!d.planBuildComplete || d.bendFringeFinalized) return;
      d.bendFringeFinalized = true;
      // Read neighbour domains *after* this domain has completed solving.
      // Calling domainAt() from inside solve() would see a partially built
      // neighbour and make the result depend on tile-query order.
      HighwayBendFringe.Outside exterior = new HighwayBendFringe.Outside() {
         private Domain neighbour(int x, int z) {
            return domainOfCell(fd(x - offX, pitch), fd(z - offZ, pitch));
         }
         @Override public int kindAt(int x, int z) {
            Domain n = neighbour(x,z);
            return n.planBuildComplete ? n.kindAt(x,z) : UNCLAIMED;
         }
         @Override public boolean forestAt(int x, int z) {
            Domain n = neighbour(x,z);
            if (!n.planBuildComplete) return false;
            int type = n.kindAt(x,z);
            if (type == NATURAL) return true;
            if (type != BLOCK) return false;
            for (Rect r : n.blocks)
               if (x>=r.minX && x<=r.maxX && z>=r.minZ && z<=r.maxZ)
                  return n.contexts.get(r) == ContextType.FOREST;
            return false;
         }
      };
      // A neighbouring street that Morphomos already dissolves into a real
      // forest region is NOT a live road. Check the completed original plan,
      // before changing any cells, to avoid mistaking a retired terminal for
      // a required road connection. This is read-only and cannot create land.
      PlanRegions originalRegions = new PlanRegions(this);
      java.util.Map<Long, Boolean> retiredMemo = new java.util.HashMap<>();
      HighwayBendFringe.RetiredStreet retired = (x, z) -> {
         long k = key(x,z);
         Boolean v = retiredMemo.get(k);
         if (v != null) return v;
         boolean safe = kindAt(x,z) == STREET && originalRegions.dissolvedAt(x,z);
         if (safe) {
            ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion mr = originalRegions.regionFor(x,z);
            safe = mr != null && mr.getContext() == ContextType.FOREST;
         }
         retiredMemo.put(k,safe);
         return safe;
      };
      // Compute the renderer-equivalent dry shore reach exactly once. The
      // forest fringe and remaining-terrain handoff must agree about the
      // same cells BEFORE they commit anything to the world plan.
      PlannedShoreOwnership.Prepared shore = new PlannedShoreOwnership.Prepared(
              this.zone,d.minX,d.minZ,d.maxX,d.maxZ);
      int released = HighwayBendFringe.release(d,
              this.network.snapshot(d.minX - 2,d.minZ - 2,d.maxX + 2,d.maxZ + 2),
              this.zone, exterior, retired, shore);
      if (released > 0)
         Minions.dDistrict("HIGHWAY BEND FRINGE released dry forest cells=%d domain=%d bounds=[%d..%d,%d..%d]",
                 released, d.id, d.minX, d.maxX, d.minZ, d.maxZ);
      // All district and road decisions are now committed. Ownership priority:
      // protected bank/shore > accepted road frame > accepted rectangle >
      // genuinely unused dry woodland. No parcel has been painted yet.
      DryRemainderOwnership.Result remainder = DryRemainderOwnership.commit(d, this.zone, shore);
      d.naturalRemainderCells += remainder.woodland;
      d.shoreRemainderCells = remainder.shore;
      auditCompletedCoverage(d, shore);
      auditCommittedStreetFrames(d);
   }

   /** A read-only check after ALL planning-time zoning and shoreline ownership
    * have completed, before any renderer receives this domain. Report missing
    * inward ring pieces and corners separately from roads in a *different*
    * domain, which must not be assumed absent based on this domain alone.
    * Only factual local gaps are warnings; no late street creation occurs. */
   private void auditCommittedStreetFrames(Domain d) {
      int gaps = 0, external = 0;
      java.util.List<String> examples = new java.util.ArrayList<String>();
      for (Rect r : d.blocks) {
         for (int z = r.minZ - 1; z <= r.maxZ + 1; z++) {
            for (int x = r.minX - 1; x <= r.maxX + 1; x++) {
               if (x != r.minX - 1 && x != r.maxX + 1
                       && z != r.minZ - 1 && z != r.maxZ + 1) continue;
               // Do not interpret the edge of this domain as a dead end.
               // Its adjacent domain can own a valid street independently.
               if (x < d.minX || x > d.maxX || z < d.minZ || z > d.maxZ
                       || byCell.get(key(fd(x - offX, pitch), fd(z - offZ, pitch))) != d) {
                  external++;
                  continue;
               }
               int kind = d.kindAt(x, z);
               if (kind == STREET || kind == LANE) continue;
               gaps++;
               if (examples.size() < 8) examples.add("(" + x + "," + z + " kind=" + kind
                       + " corner=" + ((x == r.minX-1 || x == r.maxX+1)
                       && (z == r.minZ-1 || z == r.maxZ+1)) + ")");
            }
         }
      }
      if (gaps > 0) {
         Minions.w("COMMITTED ROAD FRAME GAPS domain=(%d,%d..%d,%d) missing=%d uncheckedNeighborDomain=%d examples=%s",
                 d.minX, d.minZ, d.maxX, d.maxZ, gaps, external, examples);
      }
   }

   /** Solves the domain from scratch with the block limits relaxed by s chunks (s = 0: the configured ones). */
   private void solveAt(Domain d, Map<Long, int[]> members, int s, int failed0, int shore0, int retries0, int searched0) {
      this.minBlock = Math.max(2, this.baseMin - s);
      this.maxBlock = this.baseMax + s;
      for (byte[] row : d.kind) {
         java.util.Arrays.fill(row, (byte) UNCLAIMED);
      }
      d.blocks.clear();
      for(byte[] row:d.diagnosticZoneOrigin) java.util.Arrays.fill(row,(byte)0);
      d.contexts.clear();
      d.urbanBoundaryRoads.clear(); // trial-local protected frame ownership must not survive a retry
      d.provisionalRecoveredFrameRoads.clear();
      d.landmarks.clear();
      d.shoreRoad = null;
      d.noRing = null;
      d.fixedRoadBeforeCarve = null;
      d.ringSkipped = 0;
      d.solved = false;
      d.unresolvedDryCells = 0;
      d.failNote = "";
      failedDomains = failed0;
      shoreCells = shore0;
      retries = retries0;
      searched = searched0;
      solveOnce(d, members);
   }

   // -------------------------------------------------------------------- using every chunk

   /** Is the domain-local chunk ground left over by the carve that only the blocks can take: not water, not in the shore's reach? */
   private boolean isPocket(Domain d, int x, int z) {
      return x >= 0 && z >= 0 && x < d.kind.length && z < d.kind[0].length && d.kind[x][z] == ZONE
              && !(this.zone != null && this.zone.at(d.minX + x, d.minZ + z));
   }

   private boolean nearWater(Domain d, int x, int z) {
      if (this.zone == null) {
         return false;
      }
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if ((dx != 0 || dz != 0) && this.zone.at(d.minX + x + dx, d.minZ + z + dz)) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Flexible blocks leave nothing over. The carve with the widest limits tried may still close a few chunks as ground (a
    * corner of a coast too small for any block); here every one of them is re-mathed into what lies beside it. A block grows
    * by a whole row or column over a strip of such ground along its side (up to blockSize + slack, never against another
    * block and never up to the water, where the shore road runs), and whatever is still left beside a street (not a lane) joins
    * that road (a street between two pieces of countryside of one kind dissolves into them, see PlanRegions). The ground
    * beside the water that is the shore's own (the water mask) is not touched.
    */
   private void absorbPockets(Domain d) {
      final int W = d.kind.length, H = d.kind[0].length;
      final int cap = this.baseMax + this.blockSlack;
      boolean changed = true;
      while (changed) {
         changed = false;
         for (int i = 0; i < d.blocks.size(); i++) {
            for (int side = 0; side < 4; side++) {
               Rect g = grown(d, d.blocks.get(i), side, cap);
               if (g != null) {
                  for (int x = g.minX; x <= g.maxX; x++) {
                     for (int z = g.minZ; z <= g.maxZ; z++) {
                        d.kind[x - d.minX][z - d.minZ] = BLOCK;
                     }
                  }
                  d.blocks.set(i, g);
                  changed = true;
               }
            }
         }
         for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
               if (!isPocket(d, x, z)) {
                  continue;
               }
               boolean street = false, lane = false;
               for (int i = 0; i < 4; i++) {
                  int nx = x + DX4[i], nz = z + DZ4[i];
                  if (nx >= 0 && nz >= 0 && nx < W && nz < H) {
                     street |= d.kind[nx][nz] == STREET;
                     lane |= d.kind[nx][nz] == LANE;
                  }
               }
               // Do not turn an unusable parcel margin into a SECOND street alongside an
               // existing one. This used to make 2x2+ street strips beside landmark
               // perimeters: every pocket bordering a street became yet another street.
               // Keep the unresolved dry pocket visible to deficit accounting instead
               // of inventing a parallel roadway (never repair after blocks are painted).
               if (street && !lane && !makesParallelRoad(d, x, z)) {
                  d.kind[x][z] = STREET;
                  changed = true;
               }
            }
         }
      }
   }

   /**
    * Planning-time guard against creating a parallel road by absorbing a dry
    * leftover pocket. An existing run of two or more road chunks beside the
    * candidate is an existing road, not an invitation to widen that road into
    * an extra row/column of streets. The guard is independent of seed, landmark
    * choice and district zoning. A single isolated crossing remains possible.
    */
   private boolean makesParallelRoad(Domain d, int x, int z) {
      for (int side = 0; side < 4; side++) {
         int nx = x + DX4[side], nz = z + DZ4[side];
         if (!roadCell(d, nx, nz)) continue;
         int tx = DZ4[side], tz = DX4[side];
         if (roadCell(d, nx + tx, nz + tz) || roadCell(d, nx - tx, nz - tz)) {
            return true;
         }
      }
      return false;
   }

   private static boolean roadCell(Domain d, int x, int z) {
      return x >= 0 && z >= 0 && x < d.kind.length && z < d.kind[0].length
              && (d.kind[x][z] == STREET || d.kind[x][z] == LANE);
   }

   /** The block with its side (0 N, 1 E, 2 S, 3 W) moved out one chunk over a strip of pocket, or null if it cannot. */
   private Rect grown(Domain d, Rect b, int side, int cap) {
      if (d.landmarks.containsKey(b)) {
         return null;           // a landmark's footprint is the schematic's: it does not grow
      }
      int sx0 = b.minX, sx1 = b.maxX, sz0 = b.minZ, sz1 = b.maxZ;
      int ox = 0, oz = 0;
      switch (side) {
         case 0: sz0 = sz1 = b.minZ - 1; oz = -1; break;
         case 1: sx0 = sx1 = b.maxX + 1; ox = 1; break;
         case 2: sz0 = sz1 = b.maxZ + 1; oz = 1; break;
         default: sx0 = sx1 = b.minX - 1; ox = -1; break;
      }
      if ((side % 2 == 1 ? b.width() + 1 : b.width()) > cap || (side % 2 == 0 ? b.depth() + 1 : b.depth()) > cap) {
         return null;
      }
      for (int x = sx0; x <= sx1; x++) {
         for (int z = sz0; z <= sz1; z++) {
            int lx = x - d.minX, lz = z - d.minZ;
            if (!isPocket(d, lx, lz)) {
               return null;
            }
            int bx = lx + ox, bz = lz + oz;
            // Do not enlarge an accepted rectangle into a dry pocket unless
            // the NEW outer edge is already an owned road. The old test only
            // rejected BLOCK: it silently accepted bare ZONE/NATURAL as a
            // perimeter and manufactured incomplete districts downstream.
            if (bx < 0 || bz < 0 || bx >= d.kind.length || bz >= d.kind[0].length
                    || (d.kind[bx][bz] != STREET && d.kind[bx][bz] != LANE)) {
               return null;
            }
         }
      }
      return new Rect(Math.min(b.minX, sx0), Math.min(b.minZ, sz0), Math.max(b.maxX, sx1), Math.max(b.maxZ, sz1));
   }

   // -------------------------------------------------------------------- landmarks

   /** How far from its anchor a landmark may be moved to find room, in chunks. */
   private static final int LANDMARK_REACH = 16;

   /**
    * Reserves each landmark of the plan a block of its own, before anything else in the domain is carved: its footprint as a
    * block, and a one-chunk street on every side that has no road yet (the same kind of chunk as a boundary street, closed to
    * the carve). A side that already has a lane or street against it gets no ring: that road is its side street. A site
    * belongs to the domain that holds its anchor on free land, so no two domains ever answer for one landmark.
    *
    * A landmark must not disturb the layout around it. Only the highway lanes (and water) are fixed; the blocks beside a
    * landmark are carved afterwards and flex to fit it, so a landmark may stand hard against a lane. What it may NOT do is
    * leave a gap that no block can fill: free ground that is not zero chunks wide but too thin for a minBlock x minBlock
    * block would be closed as a ZONE strip. So a position is only taken when reserving it uncovers no free chunk that was
    * coverable before (see createsSliver()). It stands as near its anchor as such a position exists within LANDMARK_REACH;
    * if none does, the landmark is not placed at all (and the planner is told why through LandmarkPlanner.unplaced()),
    * rather than being squeezed in with a sliver beside it.
    */
   /**
    * Reserves the fixed blocks (see FixedPlanner) that belong to this domain, exactly where they are asked for: the footprint as a
    * block and a one-chunk street on every free chunk round it (a chunk of a lane, a street or the water already there is that
    * side's own edge). A fixed block belongs to the domain whose super-cell holds its first chunk, so no two domains answer for one.
    * There is no search and no sliver test: the block is where the water it serves needs it, and what the carve leaves beside it is
    * handed to the shore like any other leftover ground.
    */
   private void reserveFixed(Domain d, boolean[][] free, Map<Long, int[]> members, List<Landmark> fixed) {
      FixedPlanner planner = this.fixedPlanner;
      if (planner == null) {
         return;
      }
      final int W = free.length, H = free[0].length;
      for (Landmark lm : fixed) {
         int x0 = lm.anchorX - lm.width / 2 - d.minX, z0 = lm.anchorZ - lm.depth / 2 - d.minZ;
         if (x0 < 0 || z0 < 0 || x0 + lm.width > W || z0 + lm.depth > H) {
            continue;                                   // another domain's block
         }
         if (!members.containsKey(key(fd(d.minX + x0 - offX, pitch), fd(d.minZ + z0 - offZ, pitch)))) {
            continue;                                   // its first chunk is in a super-cell of another domain
         }
         // A fixed park is atomic: its footprint AND every perimeter cell
         // must have a committed road owner. The old placement skipped any
         // unavailable ring cell and still accepted the park, leaving gaps.
         boolean clear = true;
         String failure = "footprint not free";
         for (int x = x0; x < x0 + lm.width && clear; x++) {
            for (int z = z0; z < z0 + lm.depth; z++) {
               if (!free[x][z]) { clear = false; break; }
            }
         }
         if (clear) {
            for (int x = x0 - 1; x <= x0 + lm.width && clear; x++) {
               for (int z = z0 - 1; z <= z0 + lm.depth; z++) {
                  if (x >= x0 && x < x0 + lm.width && z >= z0 && z < z0 + lm.depth) continue;
                  if (x < 0 || z < 0 || x >= W || z >= H) {
                     // The fixed park's own block belongs to this domain, but
                     // its one-chunk perimeter can coincide with a preexisting
                     // road owned by the adjacent domain. Reuse that immutable
                     // highway/super-cell street rather than rejecting the
                     // park or attempting to paint across a domain boundary.
                     if (preexistingRoadAt(d,x,z)) continue;
                     clear = false; failure = "perimeter beyond domain without existing road"; break;
                  }
                  if (free[x][z] || d.kind[x][z] == STREET || d.kind[x][z] == LANE) continue;
                  int wx = d.minX + x, wz = d.minZ + z;
                  // The creek outlet can cross the park's perimeter. It must
                  // be reserved as a road with a bridge over the actual
                  // water, not silently surrendered to a coarse ZONE mask.
                  boolean outlet = d.kind[x][z] == ZONE
                          && zone instanceof WaterField
                          && ((WaterField)zone).zoneKind(wx, wz) == WaterField.Kind.CREEK
                          && planner.outletRoadAt(lm, wx, wz);
                  if (!outlet) {
                     clear = false; failure = "perimeter lacks a road at " + wx + "," + wz; break;
                  }
               }
            }
         }
         // Last-line invariant for every FixedPlanner (not just bounded rivers):
         // a free ring may not become a redundant parallel road. This uses the
         // same real, seed-stable road predicate as movable landmarks. Candidate
         // planners must reject such positions before committing water/park.
         if (clear && newLandmarkRingParallelsRoad(d, free, x0, z0, lm.width, lm.depth)) {
            clear = false;
            failure = "new fixed ring parallels existing road";
         }
         if (!clear) {
            planner.unplaced(lm, failure);
            continue;
         }
         for (int x = x0 - 1; x <= x0 + lm.width; x++) {
            for (int z = z0 - 1; z <= z0 + lm.depth; z++) {
               // Off-domain perimeter cells were individually validated to
               // be existing roads. Their owner will paint them; NEVER make
               // this domain write into the neighbor's array or replan it.
               if (x < 0 || z < 0 || x >= W || z >= H) continue;
               boolean inside = x >= x0 && x < x0 + lm.width && z >= z0 && z < z0 + lm.depth;
               if (!inside && !free[x][z] && (d.kind[x][z] == STREET || d.kind[x][z] == LANE)) {
                  continue; // existing road is the perimeter road
               }
               if (!inside && !free[x][z]) {
                  int wx = d.minX + x, wz = d.minZ + z;
                  // The only permitted non-road overlap was validated above
                  // as the OUTLET face; record its bridge axis before paint.
                  boolean eastWest = wz == lm.anchorZ - lm.depth / 2 - 1
                          || wz == lm.anchorZ - lm.depth / 2 + lm.depth;
                  d.creekOutletRing.put(key(wx,wz), eastWest ? 1 : 2);
               }
               free[x][z] = false;
               d.kind[x][z] = (byte)(inside ? BLOCK : STREET);
            }
         }
         d.landmarks.put(new Rect(d.minX + x0, d.minZ + z0, d.minX + x0 + lm.width - 1, d.minZ + z0 + lm.depth - 1), lm);
      }
   }

   /** Reserve the first fitting reference landmark candidate, preserving the original order. */
   private void reserveLandmarks(Domain d, boolean[][] free) {
      LandmarkPlanner planner = this.landmarkPlanner;
      if (planner == null || planner.sitesPerCell() <= 0) return;
      final int W = free.length, H = free[0].length, CELL = LandmarkPlanner.CELL;
      for (int cx = Math.floorDiv(d.minX, CELL); cx <= Math.floorDiv(d.maxX, CELL); cx++) {
         for (int cz = Math.floorDiv(d.minZ, CELL); cz <= Math.floorDiv(d.maxZ, CELL); cz++) {
            for (int k = 0; k < planner.sitesPerCell(); k++) {
               Landmark first = planner.siteOf(cx, cz, k);
               if (first == null) continue;
               int ax = first.anchorX - d.minX, az = first.anchorZ - d.minZ;
               if (ax < 0 || az < 0 || ax >= W || az >= H) continue;
               final boolean[][] before = tileable(free);
               boolean placed = false;
               int usableAnchors = 0;
               candidates:
               for (Landmark lm : planner.alternativesOf(cx, cz, k)) {
                  if (lm == null) continue;
                  int lx = lm.anchorX - d.minX, lz = lm.anchorZ - d.minZ;
                  if (lx < 0 || lz < 0 || lx >= W || lz >= H) continue;
                  if (!free[lx][lz]) continue;
                  usableAnchors++;
                  for (int r = 0; r <= LANDMARK_REACH; r++) {
                     for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                           if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                           int x0 = lm.anchorX - lm.width / 2 + dx - d.minX;
                           int z0 = lm.anchorZ - lm.depth / 2 + dz - d.minZ;
                           if (!landmarkFits(d,free,x0,z0,lm.width,lm.depth)
                                   || createsSliver(free,before,x0,z0,lm.width,lm.depth)) continue;
                           for (int x = x0 - 1; x <= x0 + lm.width; x++) {
                              for (int z = z0 - 1; z <= z0 + lm.depth; z++) {
                                 boolean inside = x >= x0 && x < x0 + lm.width && z >= z0 && z < z0 + lm.depth;
                                 if (!inside && !free[x][z]) continue;
                                 free[x][z] = false;
                                 d.kind[x][z] = (byte)(inside ? BLOCK : STREET);
                              }
                           }
                           d.landmarks.put(new Rect(d.minX+x0, d.minZ+z0,
                                   d.minX+x0+lm.width-1, d.minZ+z0+lm.depth-1),lm);
                           placed = true;
                           break candidates;
                        }
                     }
                  }
               }
               if (!placed) planner.unplaced(first, usableAnchors == 0
                       ? "no free anchor in the owning domain"
                       : "no fitting road-wrapped position after " + usableAnchors + " candidate anchor(s)");
            }
         }
      }
   }

   /**
    * The footprint is free land inside the domain, and every chunk of the one-chunk ring round it is either free land (it
    * becomes the landmark's street) or already a lane or street. A side that already has a lane or street against it needs no
    * ring: that road is the landmark's side street, so no second road is laid alongside it (a doubled highway).
    */
   private boolean landmarkFits(Domain d, boolean[][] free, int x0, int z0, int w, int dpt) {
      final int W = free.length, H = free[0].length;
      if (x0 - 1 < 0 || z0 - 1 < 0 || x0 + w >= W || z0 + dpt >= H) {
         return false;
      }
      for (int x = x0 - 1; x <= x0 + w; x++) {
         for (int z = z0 - 1; z <= z0 + dpt; z++) {
            boolean inside = x >= x0 && x < x0 + w && z >= z0 && z < z0 + dpt;
            if (free[x][z]) {
               continue;
            }
            if (inside) {
               return false;
            }
            byte k = d.kind[x][z];
            if (k != LANE && k != STREET) {
               return false;          // water, ground or anything else cannot be the landmark's road
            }
         }
      }
      // A new landmark ring may reuse an EXISTING street/lane on the ring itself,
      // but must never create another lane one chunk beside a sustained road.
      // Previously the check above stopped at the ring boundary. A park one
      // chunk too far from a highway was accepted with its own parallel street.
      // Reject this candidate before carving; the search can try the position
      // one chunk closer and share that existing road instead.
      if (newLandmarkRingParallelsRoad(d, free, x0, z0, w, dpt)) {
         return false;
      }
      return true;
   }

   /** Only newly reserved perimeter cells are checked: existing roads reused at the perimeter remain legal. */
   private boolean newLandmarkRingParallelsRoad(Domain d, boolean[][] free, int x0, int z0, int w, int depth) {
      // North/south: horizontal perimeter strip adjacent to an existing horizontal road.
      for (int side = 0; side < 2; side++) {
         int rz = side == 0 ? z0 - 1 : z0 + depth;
         int outsideZ = side == 0 ? rz - 1 : rz + 1;
         for (int x = x0; x < x0 + w - 1; x++) {
            if (freeParkCell(free,x,rz) && freeParkCell(free,x+1,rz)
                    && preexistingRoadAt(d, x, outsideZ) && preexistingRoadAt(d, x + 1, outsideZ)) {
               return true;
            }
         }
      }
      // West/east: vertical perimeter strip adjacent to an existing vertical road.
      for (int side = 0; side < 2; side++) {
         int rx = side == 0 ? x0 - 1 : x0 + w;
         int outsideX = side == 0 ? rx - 1 : rx + 1;
         for (int z = z0; z < z0 + depth - 1; z++) {
            if (freeParkCell(free,rx,z) && freeParkCell(free,rx,z+1)
                    && preexistingRoadAt(d, outsideX, z) && preexistingRoadAt(d, outsideX, z + 1)) {
               return true;
            }
         }
      }
      return false;
   }

   /** Bounds-safe: a road already owned by a neighbouring domain is NOT
    * free land that this fixed-park proposal can convert into a new street. */
   private static boolean freeParkCell(boolean[][] free,int x,int z) {
      return x>=0 && x<free.length && z>=0 && z<free[0].length && free[x][z];
   }

   /** The global lattice's mandatory road, without solving a foreign domain.
    * Used only when validating a redundant strip beside a fixed street. */
   private boolean plannedFixedBoundaryRoadAt(int wx, int wz) {
      if (this.zone != null && this.zone.at(wx,wz)) return false;
      int a=fd(wx-offX,pitch), b=fd(wz-offZ,pitch);
      return (wx == a*pitch+offX && !lineConflicts(true,a,b))
              || (wz == b*pitch+offZ && !lineConflicts(false,b,a));
   }

   /**
    * A highway or super-cell boundary street outside the current domain is
    * still a road. Consult the seed-stable boundary plan rather than querying
    * the foreign domain (which might not be solved yet).
    */
   private boolean preexistingRoadAt(Domain d, int lx, int lz) {
      if (roadCell(d, lx, lz)) return true;
      int wx=d.minX+lx, wz=d.minZ+lz;
      if (this.network != null) {
         // These point queries occur inside LayoutSearch's recursive solver.
         // A single immutable mask spanning the whole domain (+4 perimeter)
         // has the same lane values as a point snapshot, but avoids
         // rebuilding network candidate links on every query.
         HighwayNetwork.Snapshot lanes = d.preexistingHighways;
         if (lanes == null) {
            lanes = this.network.snapshot(d.minX - 4, d.minZ - 4, d.maxX + 4, d.maxZ + 4);
            d.preexistingHighways = lanes;
         }
         if (wx >= d.minX - 4 && wx <= d.maxX + 4 && wz >= d.minZ - 4 && wz <= d.maxZ + 4) {
            if (lanes.at(wx, wz)) return true;
         } else if (this.network.snapshot(wx, wz, wx, wz).at(wx, wz)) {
            return true;
         }
      }
      if (this.zone != null && this.zone.at(wx, wz)) return false;
      int a=fd(wx-offX,pitch), b=fd(wz-offZ,pitch);
      return (wx == a*pitch+offX && !lineConflicts(true, a, b))
              || (wz == b*pitch+offZ && !lineConflicts(false, b, a));
   }

   /**
    * The free chunks a block can still be cut from: those inside some minBlock x minBlock square of free chunks, repeated until
    * nothing more drops out (closing a chunk can uncover its neighbours) -- the same test closeFree() applies. Works on a copy.
    */
   private boolean[][] tileable(boolean[][] free) {
      final int W = free.length, H = free[0].length;
      boolean[][] f = new boolean[W][];
      for (int x = 0; x < W; x++) {
         f[x] = free[x].clone();
      }
      while (true) {
         boolean[][] cov = LayoutSearch.cover(f, minBlock);
         boolean changed = false;
         for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
               if (f[x][z] && !cov[x][z]) {
                  f[x][z] = false;
                  changed = true;
               }
            }
         }
         if (!changed) {
            return f;
         }
      }
   }

   /**
    * Would reserving the footprint and its street at (x0, z0) leave a sliver? True when some free chunk that a block could be
    * cut from before (the tileable() set, which already leaves out ledges the water or lanes made on their own) can no longer be,
    * because the landmark left it a strip too thin for a block. A gap of zero (landmark against a lane) or one wide enough for
    * a block uncovers nothing, so both pass.
    */
   private boolean createsSliver(boolean[][] free, boolean[][] before, int x0, int z0, int w, int dpt) {
      final int W = free.length, H = free[0].length;
      boolean[][] trial = new boolean[W][];
      for (int x = 0; x < W; x++) {
         trial[x] = free[x].clone();
      }
      for (int x = x0 - 1; x <= x0 + w; x++) {
         for (int z = z0 - 1; z <= z0 + dpt; z++) {
            trial[x][z] = false;
         }
      }
      boolean[][] after = tileable(trial);
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            boolean ring = x >= x0 - 1 && x <= x0 + w && z >= z0 - 1 && z <= z0 + dpt;
            if (before[x][z] && !after[x][z] && !ring) {
               return true;
            }
         }
      }
      return false;
   }

   /** The landmark blocks are blocks of the domain like any other, whatever the carve of the rest returned. */
   private void adoptLandmarks(Domain d) {
      for (Rect r : d.landmarks.keySet()) {
         if (!d.blocks.contains(r)) {
            d.blocks.add(r);
         }
      }
   }

   private void trackZoneOrigin(Domain d, int stage) {
      for (int x=0;x<d.kind.length;x++) for(int z=0;z<d.kind[x].length;z++)
         if(d.kind[x][z]==ZONE && d.diagnosticZoneOrigin[x][z]==0)
            d.diagnosticZoneOrigin[x][z]=(byte)stage;
   }

   /** Candidate acceptance, before any district is committed or rendered.
    * Every edge must be an owned lane/street, either fixed in the initial
    * plan or allocated by the same atomic exact-search result. An ocean/river
    * mask, unclaimed dry terrain, and another block are never a road wrapper.
    */
   static boolean exactPlanHasCompleteFrames(Domain d, LayoutSearch.Result result) {
      for (Rect r : result.blocks) {
         for (int x = r.minX; x <= r.maxX; x++) {
            if (!exactFrameRoad(d, result, x, r.minZ - 1)
                    || !exactFrameRoad(d, result, x, r.maxZ + 1)) return false;
         }
         for (int z = r.minZ; z <= r.maxZ; z++) {
            if (!exactFrameRoad(d, result, r.minX - 1, z)
                    || !exactFrameRoad(d, result, r.maxX + 1, z)) return false;
         }
         // A complete rectangle frame includes its four turning cells, not
         // only the cardinal side spans. PlanRegions applies this same rule.
         if (!exactFrameRoad(d,result,r.minX-1,r.minZ-1)
                 || !exactFrameRoad(d,result,r.maxX+1,r.minZ-1)
                 || !exactFrameRoad(d,result,r.minX-1,r.maxZ+1)
                 || !exactFrameRoad(d,result,r.maxX+1,r.maxZ+1)) return false;
      }
      return true;
   }

   private static boolean exactFrameRoad(Domain d, LayoutSearch.Result result, int x, int z) {
      int k = d.kindAt(x,z);
      if (k == STREET || k == LANE) return true;
      if (x < d.minX || x > d.maxX || z < d.minZ || z > d.maxZ) return false;
      return result.street[x - d.minX][z - d.minZ];
   }

   /** R30ea exact-search selection: retain the first candidate on equal
    * covered block area. Both guided modes use the same planner and seed. */
   private static LayoutSearch.Result chooseLargerAcceptedPlan(LayoutSearch.Result first,
                                                                LayoutSearch.Result second) {
      if (first == null) return second;
      if (second == null) return first;
      long a = 0, b = 0;
      for (Rect rect : first.blocks) a += (long) rect.width() * rect.depth();
      for (Rect rect : second.blocks) b += (long) rect.width() * rect.depth();
      return b > a ? second : first;
   }

   private void solveOnce(Domain d, Map<Long, int[]> members) {
      final int W = d.maxX - d.minX + 1, H = d.maxZ - d.minZ + 1;
      HighwayNetwork.Snapshot snap = network.snapshot(d.minX, d.minZ, d.maxX, d.maxZ);
      boolean[][] free = new boolean[W][H];
      // Fix the ordering of creek origins relative to the creek's own water mask.
      // The provider's accepted fixed origin is a prerequisite of the creek,
      // so it must be known BEFORE the creek zone masks the domain. Previously
      // the creek consumed its own origin's land and reserveFixed() rejected it.
      // This is a plan-time ownership decision, not a terrain cleanup pass.
      final List<Landmark> fixed = this.fixedPlanner == null
              ? java.util.Collections.emptyList()
              : this.fixedPlanner.fixedIn(d.minX, d.minZ, d.maxX, d.maxZ);
      for (int x = d.minX; x <= d.maxX; x++) {
         for (int z = d.minZ; z <= d.maxZ; z++) {
            int a = fd(x - offX, pitch), b = fd(z - offZ, pitch);
            if (!members.containsKey(key(a, b))) {
               continue;                                                    // not this domain's
            }
            if (snap.at(x, z)) {
               d.kind[x - d.minX][z - d.minZ] = LANE;
               continue;
            }
            // No creek/park mask override: a candidate is valid only if its
            // already-resolved creek exclusion leaves the park footprint free.
            if (this.zone instanceof WaterField && ((WaterField)this.zone).plannedNaturalInterfluveAt(x,z)) {
               // A deliberate pre-subdivision NATURAL owner, not a ZONE
               // created to excuse a failed rectangular subdivision.
               d.kind[x - d.minX][z - d.minZ] = NATURAL;
               continue;
            }
            if (this.zone != null && this.zone.at(x, z)) {
               d.kind[x - d.minX][z - d.minZ] = ZONE;
               continue;
            }
            boolean onWest = x == a * pitch + offX, onNorth = z == b * pitch + offZ;
            boolean street = (onWest && !members.containsKey(key(a - 1, b)))
                    || (onNorth && !members.containsKey(key(a, b - 1)))
                    || (onWest && onNorth && !members.containsKey(key(a - 1, b - 1)));
            if (street) {
               d.kind[x - d.minX][z - d.minZ] = STREET;
               continue;
            }
            free[x - d.minX][z - d.minZ] = true;
         }
      }
      reserveFixed(d, free, members, fixed);
      reserveLandmarks(d, free);
      trackZoneOrigin(d, 1);
      closeFree(d, free);
      trackZoneOrigin(d, 2);
      d.fixedRoadBeforeCarve = new boolean[W][H];
      for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
         d.fixedRoadBeforeCarve[x][z] = d.kind[x][z] == STREET || d.kind[x][z] == LANE;
      }
      // Each attempt is a deterministic function of (seed, domain, attempt), so the result never depends on who asks
      // first. An attempt that left pockets is retried with another salt; the one with the fewest pockets is kept.
      Peeler best = null;
      for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
         d.attempts = attempt + 1;
         Peeler peeler = new Peeler(d, free, new Random(seed * 0x9E3779B97F4A7C15L + d.minX * 73856093L + d.minZ * 19349663L + attempt * 83492791L));
         peeler.run();
         if (best == null || peeler.closed < best.closed) {
            best = peeler;
         }
         if (best.closed == 0) {
            retries += attempt;
            break;
         }
      }
      if (best.closed > 0) {
         // The quick peel left pockets. The exact search carves the domain with no leftovers (or gives a stubborn pocket
         // up as ZONE, ground like the rest beside the water), so the peel's result is only kept if it finds nothing.
         searched++;
         // The exact tiler proposes rectangles; it must also OWN a full
         // road perimeter for every accepted rectangle. Dry neighboring cells
         // are available to reserve here, before any ownership is published.
         // A failed frame reservation retries a different exact carve rather
         // than falling back to an unwrapped, already-committed district.
         java.util.Map<Long,Boolean> frameCapacity = new java.util.HashMap<>();
         LayoutSearch.RoadCapacity canReserveRoad = new LayoutSearch.RoadCapacity() {
            @Override public boolean at(int x,int z) {
               return frameCapacity.computeIfAbsent(key(x,z), k -> {
                  if (existingRoadAt(x,z)) return true;
                  if (x<d.minX || x>d.maxX || z<d.minZ || z>d.maxZ) return false;
                  int lx=x-d.minX,lz=z-d.minZ;
                  return (free[lx][lz] || d.kind[lx][lz]==ZONE)
                          && (zone==null || !zone.at(x,z));
               });
            }
            @Override public boolean existingRoadAt(int x,int z) {
               return preexistingRoadAt(d,x-d.minX,z-d.minZ);
            }
         };
         long searchSeed = seed * 0x9E3779B97F4A7C15L
                 + d.minX * 73856093L + d.minZ * 19349663L + 7919L;
         java.util.function.UnaryOperator<LayoutSearch.Result> accept = candidate ->
                 CandidateRoadFrames.reserve(d, candidate, this.zone, minBlock, maxBlock,
                         (x,z) -> preexistingRoadAt(d,x-d.minX,z-d.minZ));
         LayoutSearch.Result res;
         if (!Boolean.getBoolean("metropolis.disableGuidedPair")) {
            LayoutSearch.Result first = LayoutSearch.solve(free, d.minX, d.minZ, minBlock, maxBlock,
                    searchSeed, 6, accept, canReserveRoad, 1);
            LayoutSearch.Result second = LayoutSearch.solve(free, d.minX, d.minZ, minBlock, maxBlock,
                    searchSeed, 6, accept, canReserveRoad, 2);
            res = chooseLargerAcceptedPlan(first, second);
         } else {
            res = LayoutSearch.solve(free, d.minX, d.minZ, minBlock, maxBlock,
                    searchSeed, 6, accept, canReserveRoad);
         }
         if (res == null && Boolean.getBoolean("metropolis.debugsearch"))
            System.err.println("EXACT SEARCH FAILED domain="+d.minX+","+d.minZ+" min="+minBlock+" max="+maxBlock+" reason="+LayoutSearch.lastFail);
         if (res != null) {
            d.blocks.clear();
            d.blocks.addAll(res.blocks);
            for (int x = 0; x < W; x++) {
               for (int z = 0; z < H; z++) {
                  if (res.street[x][z]) {
                     d.kind[x][z] = STREET;
                  } else if (res.closed[x][z]) {
                     d.kind[x][z] = ZONE;
                     shoreCells++;
                  }
               }
            }
            for (Rect r : res.blocks) {
               for (int x = r.minX; x <= r.maxX; x++) {
                  for (int z = r.minZ; z <= r.maxZ; z++) {
                     d.kind[x - d.minX][z - d.minZ] = BLOCK;
                  }
               }
            }
            trackZoneOrigin(d, 3);
            retries++;
            d.solved = true;
            d.failNote = "";
            DEBUG_FRAMES(d,"pretrim-exact");
            trimShoreRoad(d);
            DEBUG_FRAMES(d,"posttrim-exact");
            trackZoneOrigin(d, 4);
            return;
         }
      }
      // R30ea's provisional widened-envelope retry is part of the existing
      // Peeler fallback. It never commits a partial alternative: an accepted
      // candidate first has to publish legal rectangles and complete frames.
      if (best.closed > 0 && !Boolean.getBoolean("metropolis.disableProvisionalEnvelope")) {
         int minEnvelope = Math.max(2, baseMin - blockSlack);
         int maxEnvelope = baseMax + blockSlack;
         int savedMax = maxBlock;
         maxBlock = savedMax + Integer.getInteger("metropolis.envelopeExtra", 4);
         try {
            Peeler selected = null;
            int selectedArea = -1;
            int envelopeAttempts = Integer.getInteger("metropolis.envelopeAttempts", Math.max(4, 3));
            for (int attempt = 0; attempt < envelopeAttempts; attempt++) {
               Peeler candidate = new Peeler(d, free,
                     new Random(seed * 0x9E3779B97F4A7C15L + d.minX * 73856093L
                           + d.minZ * 19349663L + attempt * 83492791L));
               candidate.run();
               int closed = candidate.closed;
               boolean publish = closed == 0
                       && publishProvisionalSubdivisions(candidate, minEnvelope, maxEnvelope);
               int area = 0;
               if (publish) for (Rect rect : candidate.blocks) area += rect.width() * rect.depth();
               if (Boolean.getBoolean("metropolis.debugEnvelope") && d.minX == -99 && d.minZ == -99)
                  System.err.println("ENVELOPE domain=" + d.minX + "," + d.minZ
                        + " min=" + minBlock + " max=" + maxBlock
                        + " attempt=" + attempt + " closed=" + closed
                        + " accepted=" + publish + " covered=" + area);
               if (publish && area > selectedArea) {
                  selected = candidate;
                  selectedArea = area;
               }
            }
            if (selected != null) {
               best = selected;
               if (Boolean.getBoolean("metropolis.debugEnvelope"))
                  System.err.println("ENVELOPE SELECT domain=" + d.minX + "," + d.minZ
                        + " min=" + minBlock + " max=" + maxBlock
                        + " range=" + minEnvelope + ".." + maxEnvelope
                        + " rectangles=" + best.blocks.size() + " covered=" + selectedArea);
            }
         } finally {
            maxBlock = savedMax;
         }
      }
      // The exact search found nothing: keep the quick peel, and close its leftover pockets as ground (ZONE), exactly as
      // the search closes the ones it gives up. There is no third kind of chunk: every chunk is lane, street, block or ground.
      d.blocks.clear();
      d.blocks.addAll(best.blocks);
      // Preserve admitted lots and their roads through the rest of the carve.
      // Their provisional status is discharged after contexts are assigned;
      // only genuine developed frontage remains protected from Morphomos.
      d.urbanBoundaryRoads.addAll(best.admittedFrameRoads);
      d.provisionalRecoveredFrameRoads.addAll(best.admittedFrameRoads);
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (best.c[x][z] == Peeler.STREET) {
               d.kind[x][z] = STREET;
            } else if (best.c[x][z] == Peeler.CLOSEDC) {
               d.kind[x][z] = ZONE;
               shoreCells++;
            }
         }
      }
      for (Rect r : best.blocks) {
         for (int x = r.minX; x <= r.maxX; x++) {
            for (int z = r.minZ; z <= r.maxZ; z++) {
               d.kind[x - d.minX][z - d.minZ] = BLOCK;
            }
         }
      }
      trackZoneOrigin(d, 3);
      d.solved = best.closed == 0;
      d.failNote = d.solved ? "" : best.closed + " chunks closed as ground (the exact search found no carve)";
      if (!d.solved) {
         failedDomains++;
      }
      DEBUG_FRAMES(d,"pretrim-peeler");
      trimShoreRoad(d);
      DEBUG_FRAMES(d,"posttrim-peeler");
      trackZoneOrigin(d, 4);
   }

   /** R30ea: convert a complete widened-envelope Peeler solution back into
    * legal block sizes, reserving split roads in the SAME candidate before
    * publication. Invalid splits or four-way road holes reject the candidate.
    */
   private boolean publishProvisionalSubdivisions(Peeler peeler, int minimum, int maximum) {
      if (peeler.closed != 0) return false;
      int W = peeler.W, H = peeler.H;
      List<Rect> subdivided = new ArrayList<>();
      for (Rect original : peeler.blocks) {
         if (original.width() <= maximum && original.depth() <= maximum
                 && original.width() >= minimum && original.depth() >= minimum) {
            subdivided.add(original);
            continue;
         }
         int[] widths = RectangularTiling.partition(original.width(), minimum, maximum);
         int[] depths = RectangularTiling.partition(original.depth(), minimum, maximum);
         if (widths == null || depths == null) return false;
         int ox = original.minX - peeler.d.minX, oz = original.minZ - peeler.d.minZ;
         int cx = ox;
         for (int i = 0; i < widths.length; i++) {
            int cz = oz;
            for (int j = 0; j < depths.length; j++) {
               subdivided.add(new Rect(peeler.d.minX + cx, peeler.d.minZ + cz,
                       peeler.d.minX + cx + widths[i] - 1,
                       peeler.d.minZ + cz + depths[j] - 1));
               cz += depths[j];
               if (j + 1 < depths.length) {
                  for (int x = ox; x < ox + original.width(); x++) {
                     byte previous = peeler.c[x][cz];
                     if (previous != Peeler.BLOCKC && previous != Peeler.STREET) return false;
                     peeler.c[x][cz] = Peeler.STREET;
                  }
                  cz++;
               }
            }
            cx += widths[i];
            if (i + 1 < widths.length) {
               for (int z = oz; z < oz + original.depth(); z++) {
                  byte previous = peeler.c[cx][z];
                  if (previous != Peeler.BLOCKC && previous != Peeler.STREET) return false;
                  peeler.c[cx][z] = Peeler.STREET;
               }
               cx++;
            }
         }
      }
      for (Rect rect : subdivided) {
         if (rect.width() < minimum || rect.width() > maximum
                 || rect.depth() < minimum || rect.depth() > maximum) return false;
         int x0 = rect.minX - peeler.d.minX, z0 = rect.minZ - peeler.d.minZ;
         for (int x = x0; x < x0 + rect.width(); x++)
            for (int z = z0; z < z0 + rect.depth(); z++)
               if (peeler.get(x,z) != Peeler.BLOCKC) return false;
         if (!peeler.completeFrame(x0,z0,rect.width(),rect.depth())) return false;
      }
      // Reject road-only 2x2 blocks and a non-road cell surrounded on all four
      // cardinal sides by roads. Neither is a valid street topology.
      for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
         byte value = peeler.c[x][z];
         if (peeler.road(value) && peeler.road(peeler.get(x+1,z))
                 && peeler.road(peeler.get(x,z+1)) && peeler.road(peeler.get(x+1,z+1))) return false;
         if (!peeler.road(value) && peeler.road(peeler.get(x-1,z))
                 && peeler.road(peeler.get(x+1,z)) && peeler.road(peeler.get(x,z-1))
                 && peeler.road(peeler.get(x,z+1))) return false;
      }
      peeler.blocks.clear();
      peeler.blocks.addAll(subdivided);
      return true;
   }

   /**
    * A shore road that ends in nothing is no road: the end of it leads nowhere, and is only there because what it was laid
    * to serve (a block, a street to join) went to the shore instead. Such an end is handed to the shore too, and the
    * next chunk of the road is looked at in turn. A chunk at the edge of the domain counts as joined (the road goes on in
    * the next domain).
    */
   /** True for any of the four sides AND four turns of an accepted block's
    * one-chunk road ring. Do not use this to claim an absent road: acceptance
    * is responsible for reserving it before any ownership is committed. */
   static boolean requiredAcceptedFrame(List<Rect> blocks, int x, int z) {
      for (Rect block : blocks) {
         if (x < block.minX - 1 || x > block.maxX + 1 ||
                 z < block.minZ - 1 || z > block.maxZ + 1) continue;
         if (x == block.minX - 1 || x == block.maxX + 1 ||
                 z == block.minZ - 1 || z == block.maxZ + 1) return true;
      }
      return false;
   }

   private void trimShoreRoad(Domain d) {
      if (d.shoreRoad == null) {
         return;
      }
      final int W = d.shoreRoad.length, H = d.shoreRoad[0].length;
      boolean changed = true;
      while (changed) {
         changed = false;
         for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
               if (!d.shoreRoad[x][z] || d.kind[x][z] != STREET) {
                  continue;
               }
               int roads = 0;
               for (int i = 0; i < 4; i++) {
                  int nx = x + DX4[i], nz = z + DZ4[i];
                  if (nx < 0 || nz < 0 || nx >= W || nz >= H || d.kind[nx][nz] == STREET || d.kind[nx][nz] == LANE) {
                     roads++;
                  }
               }
               // All eight perimeter sections of an accepted rectangle are
               // immutable, including the four corner turns. Earlier shore
               // trimming only protected the straight faces; if a neighbour
               // was handed to the shore, its corner could subsequently be
               // deleted, despite the rectangle already being published.
               // This is a planning-time ownership guard, not a repair pass.
               int worldX = d.minX + x, worldZ = d.minZ + z;
               boolean servesBlock = requiredAcceptedFrame(d.blocks, worldX, worldZ);
               if (roads <= 1 && !servesBlock) {
                  d.kind[x][z] = ZONE;
                  shoreCells++;
                  changed = true;
               }
            }
         }
      }
   }

   /**
    * Free chunks that no minBlock x minBlock square of free chunks can contain can never be part of any block, however the
    * domain is cut: a ledge between a lane and a bank, a notch beside a step in the water. They are the shore of whatever
    * borders them, so they become ZONE (ground, like the rest beside the water) instead of leftover. Each removal can uncover
    * more, so it is repeated until nothing is left uncovered.
    */
   private void closeFree(Domain d, boolean[][] free) {
      while (true) {
         boolean[][] cov = LayoutSearch.cover(free, minBlock);
         int removed = 0;
         for (int x = 0; x < free.length; x++) {
            for (int z = 0; z < free[0].length; z++) {
               if (free[x][z] && !cov[x][z]) {
                  free[x][z] = false;
                  d.kind[x][z] = ZONE;
                  shoreCells++;
                  removed++;
               }
            }
         }
         // Space beside the water that could not hold a block once the shore road took its row is the shore's: hand it over
         // before any road is laid, so the road is only ever laid in front of a block that is really there.
         removed += handToShore(d, free);
         if (removed == 0) {
            removed += shoreStreets(d, free);
         }
         if (removed == 0) {
            return;
         }
      }
   }

   /**
    * The shore road takes the row of free chunks next to the water, so a block needs its minBlock x minBlock square of
    * free chunks beyond that row. Free chunks that no such square reaches would be left as a thin strip between the road and
    * the next street, lane or block: a pocket, or a road bending round a nook. They belong to the water's shore, so they are
    * closed as ZONE here, exactly as closeFree closes the chunks no block can reach, and the shore road never has to go round
    * them. The row beside the water is not tested (it becomes the road); whatever it leaves with nothing beyond it is closed
    * on the next round.
    */
   private int handToShore(Domain d, boolean[][] free) {
      if (this.zone == null) {
         return 0;
      }
      final int W = free.length, H = free[0].length;
      boolean[][] beyond = new boolean[W][H];
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (!free[x][z]) {
               continue;
            }
            boolean beside = besideZone(d, free, x, z);
            beyond[x][z] = !beside;
         }
      }
      boolean[][] cov = LayoutSearch.cover(beyond, minBlock);
      boolean[][] seen = new boolean[W][H];
      int n = 0;
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (!beyond[x][z] || cov[x][z] || seen[x][z]) {
               continue;
            }
            List<int[]> piece = new ArrayList<int[]>();
            ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
            queue.add(new int[]{x, z});
            seen[x][z] = true;
            while (!queue.isEmpty()) {
               int[] c = queue.poll();
               piece.add(c);
               for (int i = 0; i < 4; i++) {
                  int nx = c[0] + DX4[i], nz = c[1] + DZ4[i];
                  if (nx >= 0 && nz >= 0 && nx < W && nz < H && beyond[nx][nz] && !cov[nx][nz] && !seen[nx][nz]) {
                     seen[nx][nz] = true;
                     queue.add(new int[]{nx, nz});
                  }
               }
            }
            // An undersized beyond-bank strip is evidence that the projected
            // shore street cannot be committed at this location. It does NOT
            // transfer dry land to an unrelated terrain painter. Remove the
            // optional shore-street reservation instead; its original free
            // chunks can then join a complete rectangular block.
               for (int[] p : piece) {
                  for (int dx = -2; dx <= 2; dx++) {
                     for (int dz = -2; dz <= 2; dz++) {
                        int cx = p[0] + dx, cz = p[1] + dz;
                        if (cx < 0 || cz < 0 || cx >= W || cz >= H || !free[cx][cz] || !besideZone(d, free, cx, cz)) {
                           continue;
                        }
                        if (d.noRing == null) {
                           d.noRing = new boolean[W][H];
                        }
                        if (!d.noRing[cx][cz]) {
                           d.noRing[cx][cz] = true;
                           d.ringSkipped++;
                           n++;
                        }
                     }
                  }
               }
         }
      }
      return n;
   }

   /** Is the free chunk (x, z) beside the water zone (a side or a corner), and so a chunk of the shore road? */
   private boolean besideZone(Domain d, boolean[][] free, int x, int z) {
      if (d.noRing != null && d.noRing[x][z]) {
         return false;
      }
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if ((dx != 0 || dz != 0) && zoneAt(d, free, x + dx, z + dz)) {
               // Bounded tributaries now expose the same planning frontage
               // reservation as ordinary rivers; shoreline streets must be
               // tested rather than bypassed because the provider is bounded.
               return true;
            }
         }
      }
      return false;
   }

   /**
    * The shore road. A block never touches the zone (the water, or the ground closed beside it): every free chunk that
    * borders the zone, on a side or a corner, becomes a street, so the zone is walled by one road and the blocks start
    * behind it. Without it a block's own street frame (left, right, bottom) never reaches the zone side, and a farm or a
    * city block ran straight into the shore. The road is the same kind of chunk as every street the layout places (it
    * is closed like a boundary street, so the carve treats it as an obstacle it ends against), joins the streets and
    * lanes that reach the zone, and is a function of the plan alone. It takes free chunks, so closeFree() runs again on
    * what it leaves: a ledge too thin for a block closes to ZONE, which the next round walls in turn.
    */
   private int shoreStreets(Domain d, boolean[][] free) {
      if (this.zone == null) {
         return 0;
      }
      final int W = free.length, H = free[0].length;
      List<int[]> ring = new ArrayList<int[]>();
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (!free[x][z]) {
               continue;
            }
            boolean beside = besideZone(d, free, x, z);
            if (beside) {
               ring.add(new int[]{x, z});
            }
         }
      }
      // A shore street must not run alongside a lane (two street chunks in a row against the same side of a lane, which also
      // makes a 2x2 of road at a lane's end). Of such a pair the chunk that joins nothing else gives way and stays free.
      boolean[][] ringed = new boolean[W][H];
      for (int[] p : ring) {
         ringed[p[0]][p[1]] = true;
      }
      List<int[]> given = new ArrayList<int[]>();
      for (int[] p : ring) {
         for (int i = 0; i < 4; i++) {
            if (!laneAt(d, p[0] + DX4[i], p[1] + DZ4[i])) {
               continue;
            }
            for (int q = 0; q < 4; q++) {
               if (q % 2 == i % 2) {
                  continue;                                          // along the lane's own side, not beside it
               }
               int nx = p[0] + DX4[q], nz = p[1] + DZ4[q];
               if (!laneAt(d, nx + DX4[i], nz + DZ4[i])) {
                  continue;
               }
               boolean nRing = nx >= 0 && nz >= 0 && nx < W && nz < H && ringed[nx][nz];
               boolean nStreet = nx >= 0 && nz >= 0 && nx < W && nz < H && d.kind[nx][nz] == STREET;
               if (nStreet) {
                  given.add(p);
               } else if (nRing) {
                  // keep the one whose far side carries on as road
                  boolean pCarries = roadish(d, ringed, p[0] - DX4[q], p[1] - DZ4[q]);
                  boolean nCarries = roadish(d, ringed, nx + DX4[q], nz + DZ4[q]);
                  if (!pCarries && (nCarries || p[0] > nx || (p[0] == nx && p[1] > nz))) {
                     given.add(p);
                  }
               }
            }
         }
      }
      for (int[] p : given) {
         ringed[p[0]][p[1]] = false;
      }
      // Feasibility before committing a shoreline street: a road only has
      // permission to consume the margin if it leaves every originally
      // tileable dry cell inside at least one minBlock square. Otherwise
      // the road would create the impossible one-chunk-to-six-chunk band
      // previously surrendered as ZONE by closeFree(). Keep such shoreline
      // stretches as developable land rather than inventing a forest gap.
      // This is a purely planning-time calculation, before any road is painted.
      boolean[][] tileableBefore = LayoutSearch.cover(free, minBlock);
      for (int pass = 0; pass < 3; pass++) {
         boolean[][] after = new boolean[W][H];
         for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
            after[x][z] = free[x][z] && !ringed[x][z];
         }
         boolean[][] tileableAfter = LayoutSearch.cover(after, minBlock);
         boolean[][] release = new boolean[W][H];
         int released = 0;
         for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
            if (!tileableBefore[x][z] || ringed[x][z] || tileableAfter[x][z]) continue;
            // Every potential minBlock square covering this lost cell lies
            // in the following closed radius, so any road in that radius
            // can be a cause of the failed tiling.
            for (int rx = Math.max(0, x - minBlock + 1); rx <= Math.min(W - 1, x + minBlock - 1); rx++) {
               for (int rz = Math.max(0, z - minBlock + 1); rz <= Math.min(H - 1, z + minBlock - 1); rz++) {
                  if (ringed[rx][rz]) release[rx][rz] = true;
               }
            }
         }
         for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
            if (release[x][z]) {ringed[x][z] = false;released++;}
         }
         if (released == 0) break;
      }
      int made = 0;
      for (int[] p : ring) {
         if (ringed[p[0]][p[1]]) {
            free[p[0]][p[1]] = false;
            d.kind[p[0]][p[1]] = STREET;
            if (d.shoreRoad == null) {
               d.shoreRoad = new boolean[W][H];
            }
            d.shoreRoad[p[0]][p[1]] = true;
            made++;
         }
      }
      return made;
   }

   private static final int[] DX4 = {0, 1, 0, -1}, DZ4 = {-1, 0, 1, 0};

   private boolean laneAt(Domain d, int x, int z) {
      return x >= 0 && z >= 0 && x < d.kind.length && z < d.kind[0].length && d.kind[x][z] == LANE;
   }

   private boolean roadish(Domain d, boolean[][] ringed, int x, int z) {
      return x >= 0 && z >= 0 && x < d.kind.length && z < d.kind[0].length && (ringed[x][z] || d.kind[x][z] == STREET || d.kind[x][z] == LANE);
   }

   /**
    * Is the chunk (domain-local x, z) zone: the domain's own ZONE chunk, or, where the domain has no chunk of its own (the
    * box outside its super-cells), the zone mask. A free chunk or a street of the domain is not zone.
    */
   private boolean zoneAt(Domain d, boolean[][] free, int x, int z) {
      if (x >= 0 && z >= 0 && x < free.length && z < free[0].length) {
         if (free[x][z]) {
            return false;
         }
         if (d.kind[x][z] != UNCLAIMED) {
            return d.kind[x][z] == ZONE && this.zone.at(d.minX + x, d.minZ + z);
         }
      }
      return this.zone.at(d.minX + x, d.minZ + z);
   }

   /**
    * Carves one domain without searching. The free chunks are cut into blocks one at a time, from the top-left corner
    * of what is left (first row, then first column): a block of minBlock..maxBlock on each side, closed by a street
    * frame on its left, right and bottom (its top is already closed, by an earlier street or by an obstacle). A frame is
    * only used if every street chunk in it has a free chunk, a block or closed ground on both sides (no street beside a lane,
    * the water or another street), is no part of a 2x2 of road, and is no dead end; and a block never touches another.
    *
    * Some pockets cannot be tiled by any means: a minBlock-wide square with one corner missing is too small to cut and
    * not a rectangle. So a placement leaves behind every free chunk that no minBlock x minBlock square of free chunks
    * can still reach, and those chunks are closed as ground (ZONE): one clean patch with no street through it. Of the blocks
    * that would fit, the one that strands the fewest chunks is taken, and the first that strands none is taken at once.
    * Blocks are therefore always minBlock..maxBlock, and nothing is ever left unclaimed.
    */
   private final class Peeler {
      static final byte FREE = 0, BLOCKC = 1, STREET = 2, LANEC = 3, ZONEC = 4, BSTREET = 5, OUT = 6, CLOSEDC = 7, ROAD_CAPACITY = 8;
      final Domain d;
      final int W, H;
      final byte[][] c;
      final Random r;
      final List<Rect> blocks = new ArrayList<Rect>();
      // Full, committed perimeter streets of newly admitted deferred lots.
      final java.util.Set<Long> admittedFrameRoads = new java.util.HashSet<>();
      int closed;
      private int rowPtr = 0;

      Peeler(Domain d, boolean[][] free, Random r) {
         this.d = d;
         this.r = r;
         this.W = free.length;
         this.H = free[0].length;
         this.c = new byte[W][H];
         for (int x = 0; x < W; x++) {
            for (int z = 0; z < H; z++) {
               if (free[x][z]) {
                  c[x][z] = FREE;
               } else {
                  int k = d.kind[x][z];
                  c[x][z] = k == LANE ? LANEC : k == STREET ? BSTREET
                         : k == ZONE && (WorldLayout.this.zone == null
                           || !WorldLayout.this.zone.at(d.minX+x,d.minZ+z)) ? ROAD_CAPACITY
                         : k == ZONE ? ZONEC : OUT;
               }
            }
         }
      }

      final java.util.Map<Long,Byte> outerRoads = new java.util.HashMap<>();
      byte get(int x, int z) {
         if (x < 0 || z < 0 || x >= W || z >= H) {
            return outerRoads.computeIfAbsent(key(x,z), key ->
                WorldLayout.this.preexistingRoadAt(d,x,z) ? BSTREET : OUT);
         }
         return c[x][z];
      }

      boolean road(byte t) {
         return t == STREET || t == LANEC || t == BSTREET;
      }

      void run() {
         // chunks no minBlock square of free chunks reaches can never be part of a block
         strand(0, 0, W - 1, H - 1);
         while (true) {
            int fx = -1, fz = -1;
            for (int z = rowPtr; z < H && fx < 0; z++) {
               for (int x = 0; x < W; x++) {
                  if (c[x][z] == FREE) {
                     fx = x;
                     fz = z;
                     rowPtr = z;
                     break;
                  }
               }
            }
            if (fx < 0) {
               admitDeferredFramedLots(); // candidate stage: no chunk ownership has committed yet
               return;
            }
            if (!peel(fx, fz)) {
               // no block closes here at all: this chunk is closed as ground, and the next corner is tried
               c[fx][fz] = CLOSEDC;
               closed++;
               strand(fx - minBlock, fz - minBlock, fx + minBlock, fz + minBlock);
            }
         }
      }

      /**
       * A failed northwest greedy anchor is not proof that an entire dry
       * component cannot fit a road-framed district. Before the domain plan
       * is ever committed, retry the still-unassigned former free cells as
       * independent rectangles together with their complete protected roads.
       * This only admits candidates with real street-network access, no new
       * 2x2 road square, and no overwrite of water or other reservations.
       */
      private void admitDeferredFramedLots() {
         int n=0;
         boolean changed=true;
         while(changed) {
            changed=false;
            for(int z=1;z+minBlock<H;z++) {
               for(int x=1;x+minBlock<W;x++) {
                  if(tryDeferredLot(x,z,minBlock,minBlock)){
                     n++;changed=true;
                  }
               }
            }
         }
         if(Boolean.getBoolean("metropolis.debugpeel"))
            System.err.println("PEEL-ADMITTED="+n+" remainingClosed="+closed);
      }

      private boolean tryDeferredLot(int x0,int z0,int w,int h) {
         for(int x=x0;x<x0+w;x++)for(int z=z0;z<z0+h;z++)
            if(c[x][z]!=CLOSEDC)return false;
         // The proposed street must fit within the already reserved domain.
         boolean adjacent=false;
         for(int x=x0-1;x<=x0+w;x++) for(int z=z0-1;z<=z0+h;z++) {
            if(x>=x0&&x<x0+w&&z>=z0&&z<z0+h)continue;
            byte t=c[x][z];
            if(!road(t)&&t!=CLOSEDC)return false;
            if(road(t))adjacent=true;
            // An outer street link may be preexisting one cell beyond the frame.
            for(int i=0;i<4;i++) {
               int nx=x+DX4[i],nz=z+DZ4[i];
               if(nx>=x0-1&&nx<=x0+w&&nz>=z0-1&&nz<=z0+h)continue;
               if(road(get(nx,nz)))adjacent=true;
            }
         }
         if(!adjacent)return false;
         List<int[]> made=new ArrayList<>();
         for(int x=x0-1;x<=x0+w;x++) for(int z=z0-1;z<=z0+h;z++) {
            if(x>=x0&&x<x0+w&&z>=z0&&z<z0+h)continue;
            byte old=c[x][z];
            if(old==CLOSEDC) {
               c[x][z]=STREET;
               made.add(new int[]{x,z,old});
            }
         }
         fill(x0,z0,w,h,BLOCKC);
         boolean valid=completeFrame(x0,z0,w,h)&&streetsOk(made)&&!doubledDeferredRoad(made);
         if(!valid) {
            fill(x0,z0,w,h,CLOSEDC);
            for(int[] a:made)c[a[0]][a[1]]=(byte)a[2];
            return false;
         }
         closed-=w*h;
         for(int[] a:made)if(a[2]==CLOSEDC)closed--;
         blocks.add(new Rect(d.minX+x0,d.minZ+z0,d.minX+x0+w-1,d.minZ+z0+h-1));
         for(int x=x0-1;x<=x0+w;x++)for(int z=z0-1;z<=z0+h;z++) {
            if(x>=x0&&x<x0+w&&z>=z0&&z<z0+h)continue;
            if(c[x][z]==STREET) admittedFrameRoads.add(key(d.minX+x,d.minZ+z));
         }
         return true;
      }

      /** Reject a new two-cell road run immediately alongside another
       * already-owned road run; such duplication may be missed by the 2x2
       * junction test when the two roads do not have a cross-connection. */
      private boolean doubledDeferredRoad(List<int[]> made) {
         for(int[] p:made){
            int x=p[0],z=p[1];
            if(get(x+1,z)==STREET){
               if((road(get(x,z-1)) && road(get(x+1,z-1))) ||
                   (road(get(x,z+1)) && road(get(x+1,z+1))))return true;
            }
            if(get(x,z+1)==STREET){
               if((road(get(x-1,z)) && road(get(x-1,z+1))) ||
                   (road(get(x+1,z)) && road(get(x+1,z+1))))return true;
            }
         }
         return false;
      }

      /** Closes every free chunk in the box that no minBlock x minBlock square of free chunks reaches. */
      private void strand(int bx0, int bz0, int bx1, int bz1) {
         for (int[] p : dead(bx0, bz0, bx1, bz1)) {
            c[p[0]][p[1]] = CLOSEDC;
            closed++;
         }
      }

      /** The free chunks of the box (clipped to the domain) that no minBlock square of free chunks reaches. */
      private List<int[]> dead(int bx0, int bz0, int bx1, int bz1) {
         int ex0 = bx0 - (minBlock - 1), ez0 = bz0 - (minBlock - 1), ex1 = bx1 + (minBlock - 1), ez1 = bz1 + (minBlock - 1);
         int gw = ex1 - ex0 + 1, gh = ez1 - ez0 + 1;
         boolean[][] f = new boolean[gw][gh];
         for (int x = 0; x < gw; x++) {
            for (int z = 0; z < gh; z++) {
               f[x][z] = get(ex0 + x, ez0 + z) == FREE;
            }
         }
         boolean[][] cov = cover(f, minBlock);
         List<int[]> out = new ArrayList<int[]>();
         for (int z = Math.max(bz0, 0); z <= Math.min(bz1, H - 1); z++) {
            for (int x = Math.max(bx0, 0); x <= Math.min(bx1, W - 1); x++) {
               if (c[x][z] == FREE && !cov[x - ex0][z - ez0]) {
                  out.add(new int[]{x, z});
               }
            }
         }
         return out;
      }

      /** Places the block at the corner (x0, z0) that strands the fewest chunks; false if no block closes there. */
      boolean peel(int x0, int z0) {
         int maxW = 0;
         while (maxW < maxBlock && get(x0 + maxW, z0) == FREE) {
            maxW++;
         }
         if (maxW < minBlock) {
            return false;
         }
         int[] down = new int[maxW];
         for (int i = 0; i < maxW; i++) {
            int n = 0;
            while (n < maxBlock && get(x0 + i, z0 + n) == FREE) {
               n++;
            }
            down[i] = n;
         }
         List<int[]> cands = new ArrayList<int[]>();
         int hMax = Integer.MAX_VALUE;
         for (int w = 1; w <= maxW; w++) {
            hMax = Math.min(hMax, down[w - 1]);
            for (int h = minBlock; w >= minBlock && h <= hMax; h++) {
               cands.add(new int[]{w, h});
            }
         }
         Collections.shuffle(cands, r);
         int bestN = Integer.MAX_VALUE;
         int[] best = null;
         for (int[] wh : cands) {
            int n = score(x0, z0, wh[0], wh[1]);
            if (n >= 0 && n < bestN) {
               bestN = n;
               best = wh;
               if (n == 0) {
                  break;
               }
            }
         }
         if (best == null) {
            return false;
         }
         place(x0, z0, best[0], best[1]);
         return true;
      }

      /** Chunks the block would strand, or -1 if its frame is not acceptable. Leaves the grid as it found it. */
      private int score(int x0, int z0, int w, int h) {
         for (int i = 0; i < w; i++) {
            if (get(x0 + i, z0 - 1) == BLOCKC || get(x0 + i, z0 + h) == BLOCKC) {
               return -1;
            }
         }
         for (int j = 0; j < h; j++) {
            if (get(x0 - 1, z0 + j) == BLOCKC || get(x0 + w, z0 + j) == BLOCKC) {
               return -1;
            }
         }
         List<int[]> made = new ArrayList<int[]>();
         frame(x0, z0, w, h, made);
         fill(x0, z0, w, h, BLOCKC);
         int n = -1;
         if (completeFrame(x0,z0,w,h) && streetsOk(made)) {
            n = dead(x0 - 1 - minBlock, z0 - 1 - minBlock, x0 + w + minBlock, z0 + h + minBlock).size();
         }
         fill(x0, z0, w, h, FREE);
         for (int[] m : made) {
            c[m[0]][m[1]] = (byte)m[2];
         }
         return n;
      }

      private void place(int x0, int z0, int w, int h) {
         List<int[]> made = new ArrayList<int[]>();
         frame(x0, z0, w, h, made);
         fill(x0, z0, w, h, BLOCKC);
         // CLOSEDC is dry land no longer large enough for a building, not
         // ineligible for a frontage/junction road. It was counted as closed
         // by strand(), so release that count when the accepted road reuses it.
         for (int[] changed : made) if (changed[2] == CLOSEDC) closed--;
         blocks.add(new Rect(d.minX + x0, d.minZ + z0, d.minX + x0 + w - 1, d.minZ + z0 + h - 1));
         strand(x0 - 1 - minBlock, z0 - 1 - minBlock, x0 + w + minBlock, z0 + h + minBlock);
      }

      /** Reserve all four road sides of a candidate at placement time. */
      private void frame(int x0, int z0, int w, int h, List<int[]> made) {
         for (int z = z0; z <= z0 + h; z++) {
            addStreet(x0 - 1, z, made);
            addStreet(x0 + w, z, made);
         }
         for (int x = x0 - 1; x <= x0 + w; x++) {
            addStreet(x, z0 + h, made);
            addStreet(x, z0 - 1, made);
         }
      }

      /** A rectangle cannot be accepted with a missing perimeter chunk. */
      private boolean completeFrame(int x0, int z0, int w, int h) {
         for (int x = x0; x < x0 + w; x++) {
            if (!road(get(x, z0 - 1)) || !road(get(x, z0 + h))) return false;
         }
         for (int z = z0; z < z0 + h; z++) {
            if (!road(get(x0 - 1, z)) || !road(get(x0 + w, z))) return false;
         }
         return road(get(x0-1,z0-1)) && road(get(x0+w,z0-1))
             && road(get(x0-1,z0+h)) && road(get(x0+w,z0+h));
      }

      private void fill(int x0, int z0, int w, int h, byte t) {
         for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + h; z++) {
               c[x][z] = t;
            }
         }
      }

      private void addStreet(int x, int z, List<int[]> made) {
         // The cell is still in this uncommitted candidate. Reuse stranded dry
         // ground here, rather than leaving a missing corner or adding a road
         // during painting. Water/OUT and previously owned blocks stay excluded.
         if (x >= 0 && z >= 0 && x < W && z < H
                && (c[x][z] == FREE || c[x][z] == ROAD_CAPACITY || c[x][z] == CLOSEDC)) {
            byte before = c[x][z];
            c[x][z] = STREET;
            made.add(new int[]{x, z, before});
         }
      }

      private boolean streetsOk(List<int[]> made) {
         for (int[] m : made) {
            if (!streetOk(m[0], m[1])) {
               return false;
            }
         }
         return true;
      }

      /** Is the street chunk (x, z) between two free chunks, blocks or closed ground, not a dead end, and not part of a 2x2 of road? */
      private boolean streetOk(int x, int z) {
         byte n = get(x, z - 1), s = get(x, z + 1), w = get(x - 1, z), e = get(x + 1, z);
         boolean h = w == STREET || e == STREET, v = n == STREET || s == STREET;
         if (h && !v) {
            if (!(sideOk(n) && sideOk(s))) {
               return false;
            }
         } else if (v && !h) {
            if (!(sideOk(w) && sideOk(e))) {
               return false;
            }
         }
         int roads = 0;
         boolean water = false;
         byte[] nb = {n, s, w, e};
         for (byte t : nb) {
            if (road(t) || t == OUT) {
               roads++;
            }
            if (t == ZONEC) {
               water = true;
            }
         }
         if (roads < 2 && !water) {
            return false;
         }
         for (int dx = -1; dx <= 0; dx++) {
            for (int dz = -1; dz <= 0; dz++) {
               if (road(get(x + dx, z + dz)) && road(get(x + dx + 1, z + dz)) && road(get(x + dx, z + dz + 1)) && road(get(x + dx + 1, z + dz + 1))) {
                  return false;
               }
            }
         }
         return true;
      }

      private boolean sideOk(byte t) {
         return t == FREE || t == BLOCKC || t == CLOSEDC || t == ROAD_CAPACITY;
      }

      /** Which chunks of the grid lie in some s x s square of true chunks. */
      private boolean[][] cover(boolean[][] f, int s) {
         int w = f.length, h = f[0].length;
         int[][] pre = new int[w + 1][h + 1];
         for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
               pre[x + 1][z + 1] = pre[x][z + 1] + pre[x + 1][z] - pre[x][z] + (f[x][z] ? 1 : 0);
            }
         }
         int[][] diff = new int[w + 2][h + 2];
         for (int x = 0; x + s <= w; x++) {
            for (int z = 0; z + s <= h; z++) {
               if (pre[x + s][z + s] - pre[x][z + s] - pre[x + s][z] + pre[x][z] == s * s) {
                  diff[x][z]++;
                  diff[x + s][z]--;
                  diff[x][z + s]--;
                  diff[x + s][z + s]++;
               }
            }
         }
         boolean[][] out = new boolean[w][h];
         int[][] cov = new int[w + 1][h + 1];
         for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
               cov[x + 1][z + 1] = diff[x][z] + cov[x][z + 1] + cov[x + 1][z] - cov[x][z];
               out[x][z] = cov[x + 1][z + 1] > 0;
            }
         }
         return out;
      }
   }

   // -------------------------------------------------------------------- queries

   /** What the layout puts at chunk (x, z): UNCLAIMED, LANE, STREET or BLOCK. */
   public synchronized int kindAt(int x, int z) {
      return domainAt(x, z).kindAt(x, z);
   }

   /** Is chunk (x, z) a street between blocks (not a highway lane)? */
   public synchronized boolean isStreetCell(int x, int z) {
      return kindAt(x, z) == STREET;
   }

   /** Is chunk (x, z) a street or a highway lane? Lets road pieces tell what lies beside them before that tile exists. */
   public synchronized boolean isCityRoadCell(int x, int z) {
      int k = kindAt(x, z);
      return k == STREET || k == LANE;
   }

   /** The layout covers the whole world, so every chunk is "inside" it. */
   public boolean insideCityBox(int x, int z) {
      return true;
   }

   /** The blocks that touch the window, each exactly once. */
   public synchronized List<Rect> blocksIn(int minX, int minZ, int maxX, int maxZ) {
      List<Rect> out = new ArrayList<Rect>();
      Map<Integer, Boolean> seen = new HashMap<Integer, Boolean>();
      for (int a = fd(minX - offX, pitch); a <= fd(maxX - offX, pitch); a++) {
         for (int b = fd(minZ - offZ, pitch); b <= fd(maxZ - offZ, pitch); b++) {
            Domain d = domainAt(a*pitch+offX, b*pitch+offZ);
            if (seen.put(d.id, true) != null) {
               continue;
            }
            for (Rect r : d.blocks) {
               if (r.intersects(minX, minZ, maxX, maxZ)) {
                  out.add(r);
               }
            }
         }
      }
      return out;
   }
}