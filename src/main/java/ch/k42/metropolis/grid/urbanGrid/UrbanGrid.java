package ch.k42.metropolis.grid.urbanGrid;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.common.Grid;
import ch.k42.metropolis.grid.common.GridProvider;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardDAO;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProvider;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProviderSimplex;
import ch.k42.metropolis.grid.urbanGrid.context.DistrictContextAssigner;
import ch.k42.metropolis.grid.urbanGrid.context.LandmarkZoneRule;
import ch.k42.metropolis.grid.urbanGrid.districts.BlockPlans;
import ch.k42.metropolis.grid.urbanGrid.districts.BoundedRiverLots;
import ch.k42.metropolis.grid.urbanGrid.layout.FixedParkRingRoads;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedHighwayAxis;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedParkAlignment;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSegment;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSites;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverOwnershipPlan;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverDryHandoff;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedCreekFailureTrace;
import ch.k42.metropolis.grid.urbanGrid.districts.LandmarkPlans;
import ch.k42.metropolis.grid.urbanGrid.districts.District;
import ch.k42.metropolis.grid.urbanGrid.provider.BranchDamageWatch;
import ch.k42.metropolis.grid.urbanGrid.districts.DistrictHost;
import ch.k42.metropolis.grid.urbanGrid.districts.IDistrict;
import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.PieceNames;
import ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.BridgeParcel;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.layout.BankSlope;
import ch.k42.metropolis.grid.urbanGrid.layout.PlanBankTerrain;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.PlanRegions;
import ch.k42.metropolis.grid.urbanGrid.layout.OceanOwnerParityAudit;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworks;
import ch.k42.metropolis.grid.urbanGrid.parcel.HighwayParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.RiverParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.RoadParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.StreetParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.MorphomosParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.EmptyParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanBoatParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanBoatTailParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.TerrainFallbackParcel;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanReclaim;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanBoatPlacer;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanPortPlanner;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanShipRegistry;
import ch.k42.metropolis.grid.urbanGrid.river.CreekRoadBankOwner;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverReclaim;
import ch.k42.metropolis.grid.urbanGrid.statistics.AthmosStat;
import ch.k42.metropolis.grid.urbanGrid.statistics.GridStatistics;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.util.noise.SimplexOctaveGenerator;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;

public class UrbanGrid extends Grid implements DistrictHost {
   private Parcel[][] parcels = new Parcel[64][64];
   private final ContextProvider contextProvider;
   private final GridStatistics statistics = new AthmosStat();
   private final ClipboardProvider clipboardProvider;
   private Set<IDistrict> districtSet = new HashSet<>();

   // The world layout is immutable, but ports can reclaim OceanParcel cells after
   // it is planned. Wilderness/shore relief must know those cells are now HARD
   // urban ground, or it forms a vertical cut along the new district boundary.
   // Chunk coordinates, stored on this grid only: never alter the global coast.
   private final Set<Long> portGradedCells = new HashSet<>();

   // Exact port-owned chunk cells: keep trees out of construction cells,
   // but do NOT create a blanket canopy-radius clearance around the port.
   // Ordinary terrain/tree edge rules apply right up to these cells.
   private final Set<Long> portConstructionCells = new HashSet<>();

   public void markPortConstructionCell(int chunkX, int chunkZ) {
      this.portConstructionCells.add(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
   }

   public boolean isPortConstructionCell(int chunkX, int chunkZ) {
      return this.portConstructionCells.contains(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
   }

   public void markPortGradedCell(int chunkX, int chunkZ) {
      this.portGradedCells.add(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
      markPortConstructionCell(chunkX, chunkZ);
   }

   public boolean isPortGradedCell(int chunkX, int chunkZ) {
      return this.portGradedCells.contains(((long) chunkX << 32) ^ (chunkZ & 0xffffffffL));
   }


   /** Register an industrial block reclaimed beside a selected port.
    * The normal world-layout district fill pass has already completed when
    * the port planner runs; the caller fills this district explicitly. */
   public void registerPortReclaimedDistrict(IDistrict district) {
      if (district != null) this.districtSet.add(district);
   }

   // Bounded sites and their parks are reserved by the shared world plan before
   // any domain is solved. No tile-local legacy creek-site provider is retained.
   private final List<CreekBand> creekBands = new ArrayList<>();
   private final List<RiverProvider> creekCarriers = new ArrayList<>();
   // Exact accepted bounded-site ownership, never derived from nearby parcels.
   // Per-tile carrier copies retain each site's bank geometry independently.
   private static final java.util.Map<MetropolisGenerator, BoundedRiverSites> BOUNDED_SITE_PROVIDERS = new java.util.WeakHashMap<>();
   private final java.util.Map<String, RiverProvider> boundedCarrierOf = new java.util.HashMap<>();
   private final java.util.Map<String, BoundedRiverSites.Site> boundedSitesOf = new java.util.HashMap<>();
   // Final city/road ownership must survive the later natural-bank fallback pass.
   private final java.util.Map<Long, BoundedRiverOwnershipPlan.Owner> boundedCommittedDry = new java.util.HashMap<>();
   private BoundedCreekFailureTrace boundedFailureTrace;
   // Optional physical paint audit, separate from the dry-ownership enforcement.
   private ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit boundedPaintAudit;
   private int boundedShoreWithoutCarrier;
   // Every cell a creek's own reservation painted as road: its corridor edge rows, the roads its
   // reclaim lays around returned land, and the seams around its band. They are roads, but they
   // belong to the creek, so convertRoadsToBridges() must not read them as "a street that reaches
   // the river" -- see isCrossingStreet().
   private final Set<Long> creekRoadCells = new HashSet<>();
   private final Set<Long> creekApproachCells = new HashSet<>();
   // Explicit edges of an accepted creek crossing. Unlike parcel-neighbour
   // inference, these requirements survive when an approach neighbours
   // another parcel kind or its neighbouring tile is not yet populated.
   private final java.util.Map<Long, Integer> creekRouteSides = new java.util.HashMap<Long, Integer>();
   // Every cell the creek's water or bank passes over -- INCLUDING cells that stay the main river's
   // own (a creek flows into a river through the river's cells; it does not replace them). The main
   // river's reclaim hands dry corridor land back as districts and roads, and must treat these as
   // taken, or the creek's last stretch before the river is paved over and the water stops short.
   private final Set<Long> creekProtectedCells = new HashSet<>();
   private boolean creekPhaseDone = false;

   private final int blockSize = PluginConfig.getBlockSize();
   private final int blockSizeMin = PluginConfig.getBlockSizeMin();
   // Landmark district budget for this grid tile -- see tryPlaceLandmark().
   // Starts at PluginConfig.getLandmarkMaxPerGrid() (0 if the feature is
   // disabled) and counts down by 1 each time a landmark is successfully
   // carved, so this tile stops attempting more once its cap is reached.
   private int landmarksRemaining = PluginConfig.isLandmarkDistrictsEnabled()
           ? PluginConfig.getLandmarkMaxPerGrid() : 0;

   /**
    * generator.landmarkZoneMatch: the zone a landmark (or creek-lot) district
    * has to be given, handed to DistrictContextAssigner when districts are
    * zoned. demotable = a landmark district that may fall back to an ordinary
    * district if the zone can't be honoured; a creek lot may not, since the
    * creek is already carved from its building.
    */
   private static final class ZonePin {
      final ContextType zone;
      final boolean demotable;

      ZonePin(ContextType zone, boolean demotable) {
         this.zone = zone;
         this.demotable = demotable;
      }
   }

   private final java.util.Map<IDistrict, ZonePin> zonePins = new java.util.IdentityHashMap<IDistrict, ZonePin>();

   /**
    * The zones around a district of this footprint, as it is expected to be zoned:
    * the zone at its centre, and for each of its four sides the zones of the
    * neighbours just beyond it (one road past the edge, sampled every few chunks
    * along the side). The footprint itself is not sampled -- a landmark is bigger
    * than most bands are wide, so what matters is what it is bordered by.
    * See LandmarkZoneRule.allowsFootprint().
    *
    * With neighbour rules on, each zone is the assigner's own preference at that
    * chunk (what assignDistrictContexts() starts from). Otherwise the centre is the
    * plain lookup at the district's base, exactly what District.fillDistrict()
    * samples, and the neighbours use the same plain lookup at their sample point.
    * Static and a pure function of the seed and the footprint, so
    * BoundedRiverSites can use it before any tile exists.
    */
   public static LandmarkZoneRule.Footprint zonesAround(ContextProvider provider, Cartesian2D base, Cartesian2D size) {
      DistrictContextAssigner assigner = null;
      if (provider instanceof ContextProviderSimplex) {
         DistrictContextAssigner candidate = ((ContextProviderSimplex) provider).getDistrictAssigner();
         if (candidate != null && candidate.isUsable()) {
            assigner = candidate;
         }
      }

      ContextType centre = assigner != null
              ? assigner.preferredContext(base.X + size.X / 2, base.Y + size.Y / 2) : null;
      if (centre == null) {
         centre = provider.getContext(base);
      }

      final int gap = LandmarkZoneRule.NEIGHBOUR_OFFSET;
      final int lowX = base.X - gap;
      final int highX = base.X + size.X - 1 + gap;
      final int lowZ = base.Y - gap;
      final int highZ = base.Y + size.Y - 1 + gap;

      List<List<ContextType>> sides = new ArrayList<List<ContextType>>();
      // order matches LandmarkZoneRule.Footprint: low-Z, high-X, high-Z, low-X
      sides.add(sampleSide(provider, assigner, base.X, base.X + size.X - 1, lowZ, true));
      sides.add(sampleSide(provider, assigner, base.Y, base.Y + size.Y - 1, highX, false));
      sides.add(sampleSide(provider, assigner, base.X, base.X + size.X - 1, highZ, true));
      sides.add(sampleSide(provider, assigner, base.Y, base.Y + size.Y - 1, lowX, false));
      return new LandmarkZoneRule.Footprint(centre, sides);
   }

   /** Zones along one side: from..to every SAMPLE_STEP chunks (and the far end), at the fixed coordinate {@code fixed}. */
   private static List<ContextType> sampleSide(ContextProvider provider, DistrictContextAssigner assigner,
                                               int from, int to, int fixed, boolean alongX) {
      List<ContextType> out = new ArrayList<ContextType>();
      for (int at = from; ; at += LandmarkZoneRule.SAMPLE_STEP) {
         int along = Math.min(at, to);
         int cx = alongX ? along : fixed;
         int cz = alongX ? fixed : along;
         ContextType zone = assigner != null ? assigner.preferredContext(cx, cz) : null;
         if (zone == null) {
            zone = provider.getContext(new Cartesian2D(cx, cz));
         }
         out.add(zone);
         if (along >= to) {
            break;
         }
      }
      return out;
   }

   // Stateless, seed-derived river geometry -- see RiverProvider's own
   // javadoc for why this has to be a pure function rather than anything
   // tile-specific. Constructed unconditionally; RiverConfig.isRiverEnabled()
   // just makes every query return false/NaN when the feature is off, so
   // nothing below needs its own separate "is the river on" branching.
   private final RiverProvider riverProvider;
   // The north-south river, only when rivers converge on the ocean and it is enabled; null otherwise.
   private final RiverProvider zRiverProvider;

   // Stateless, seed-derived ocean geometry -- same reasoning as
   // riverProvider above, see OceanProvider's own javadoc. Constructed
   // unconditionally; OceanConfig.isOceanEnabled() just makes every query
   // return false/0/NaN when the feature is off.
   private final OceanProvider oceanProvider;

   // Salted independently from RiverProvider's own meander/width noise so
   // this doesn't correlate with the river's own bends -- see
   // computeBankEdges()'s use of it.
   private final SimplexOctaveGenerator bankEdgeJitter;

   // Set by placeCityDistricts() only when this tile's river corridor was
   // carved directly (see carveRiverCorridor()) -- i.e. the river actually
   // crosses this tile and is east-west oriented here. Consumed by
   // convertRoadsToBridges(), which needs the corridor's exact Z band to
   // find genuine street crossings -- see that method's javadoc for why the
   // corridor's own bounds have to be tracked explicitly rather than
   // rediscovered by scanning for RiverParcel neighbours.
   private boolean riverCorridorPresent = false;
   private int riverCorridorTileMinX;
   private int riverCorridorTileWidth;
   private int riverCorridorMinZ;
   private int riverCorridorMaxZ;

   // Set by placeOceanIfPresent() only when this tile's own ocean square
   // was actually carved. Since river.convergeOnOcean anchors the river
   // straight through the ocean's own center, the river corridor ALWAYS
   // crosses this exact box on any tile where both are present -- these
   // bounds let carveRiverCorridor()/reclaimRiverDistricts() step around
   // cells the ocean already owns (isInsideOceanCarve()/
   // rectOverlapsOceanCarve() below) instead of overwriting them, or --
   // for reclaimed river districts -- being silently overwritten by them.
   // This is what lets the river keep meandering on both sides of a
   // confluence instead of just stopping dead at the ocean's own carve
   // box the moment it's carved first (see placeCityDistricts()'s own
   // javadoc for how this used to be a KNOWN v1 LIMITATION).
   private boolean oceanCarvePresent = false;
   // Which sides of THIS tile's highway ring are not drawn (see OceanProvider.droppedRingSides()); null unless this is an ocean tile.
   private boolean[] ringDropped = null;

   // Set when this tile uses the city highway network instead of the old ring around the tile: the
   // highway lanes inside (and one chunk around) the tile, and the bands they are made of, which
   // placeLeftoverLand() carves districts around.
   private HighwayNetwork.Snapshot networkMask = null;
   // True on an ocean tile of a world with the highway network: the ring on its edge is a street, not a highway.
   private boolean streetRing = false;
   private List<HighwayNetwork.Rect> networkBands = null;

   // The north-south (Z-bound) river's corridor: the transpose of the
   // east-west one above. The corridor runs the tile's whole Z range and spans
   // columns zCorridorMinX..zCorridorMaxX. Only ever set when this tile has no
   // east-west corridor. See placeZRiverCorridor().
   private boolean zRiverCorridorPresent = false;
   private int zCorridorTileMinZ;
   private int zCorridorTileHeight;
   private int zCorridorMinX;
   private int zCorridorMaxX;
   // Bank-ramp targets per ROW, the transpose of north/southBankEdgeBlockZ.
   private double[] westBankEdgeBlockX;
   private double[] eastBankEdgeBlockX;
   private int zBankEdgeTileMinZ;
   private int zBankEdgeTileHeight;
   // {baseX, baseZ, sizeX, sizeZ} of every district reclaimed beside the north-south river.
   private final List<int[]> zReclaimRects = new ArrayList<int[]>();
   private int oceanCarveMinX;
   private int oceanCarveMaxX;
   private int oceanCarveMinZ;
   private int oceanCarveMaxZ;

   private boolean isInsideOceanCarve(int x, int z) {
      return this.oceanCarvePresent
              && x >= this.oceanCarveMinX && x <= this.oceanCarveMaxX
              && z >= this.oceanCarveMinZ && z <= this.oceanCarveMaxZ;
   }

   private boolean rectOverlapsOceanCarve(int baseX, int baseZ, int sizeX, int sizeZ) {
      if (!this.oceanCarvePresent) {
         return false;
      }
      int maxX = baseX + sizeX - 1;
      int maxZ = baseZ + sizeZ - 1;
      return maxX >= this.oceanCarveMinX && baseX <= this.oceanCarveMaxX
              && maxZ >= this.oceanCarveMinZ && baseZ <= this.oceanCarveMaxZ;
   }

   // This tile's reclaimed riverside districts, and -- the part RiverParcel
   // actually reads back -- the per-column block Z at which the bank's
   // elevation ramp has to top out so it lands on a reclaimed district's
   // edge instead of running flat underneath it. Null until
   // reclaimRiverDistricts() runs (and stays null on any tile that never
   // carves a corridor), which RiverParcel.carveColumn() treats as "no
   // reclamation here, use the plain bankMargin ramp".
   private RiverReclaim riverReclaim;

   // Actual bank-ramp target per column, read straight off the FINAL
   // parcel grid after every district/road/separator/fallback-road is
   // placed -- not derived from RiverReclaim's own geometry math, which
   // never recorded an edge at all for fallback roads (see
   // RiverReclaim.emit()) and so left those columns falling back to the
   // generic short bankMargin ramp regardless of what was actually placed
   // right next to them. computeBankEdges() below scans outward from the
   // water on every column and just asks "where does RiverParcel actually
   // stop", so it's correct no matter which reclaim mechanism (or none)
   // put something there.
   private double[] northBankEdgeBlockZ;
   private double[] southBankEdgeBlockZ;
   private int bankEdgeTileMinX;
   private int bankEdgeTileWidth;

   // This tile's reclaimed ocean-shore districts -- ocean counterpart of
   // riverReclaim above, populated by reclaimOceanDistricts() instead of
   // reclaimRiverDistricts(). Null on any tile the ocean's bounding square
   // never reaches. Deliberately its own field rather than reusing
   // riverReclaim: a single tile CAN carve both now (see this.oceanCarve*
   // and placeCityDistricts()'s own javadoc), so this and riverReclaim
   // need to keep meaning two different things on such a tile rather than
   // one overwriting the other. OceanReclaim, not RiverReclaim -- see that
   // class's own javadoc for why the river's linear north/south algorithm
   // doesn't fit a closed shoreline.
   private OceanReclaim oceanReclaim;

   // Non-null only while reclaimOceanDistricts() runs the ORDINARY recSetDistricts()
   // over an ocean tile to learn where it would put roads and districts: the
   // partition*WithRoads() and addDistrict() below then record into this instead of
   // painting parcels / creating districts. The cuts themselves are made by the
   // same code, with the same random stream, as on any other tile -- OceanReclaim
   // only decides which of the resulting blocks are dry enough to build.
   private OceanReclaim.Layout layoutRecorder = null;

   public OceanReclaim getOceanReclaim() {
      return this.oceanReclaim;
   }

   /** True if this chunk lies in a district reclaimed beside either river. */
   public boolean isRiverReclaimedChunk(int chunkX, int chunkZ) {
      if (this.riverReclaim != null && this.riverReclaim.containsChunk(chunkX, chunkZ)) {
         return true;
      }
      for (int[] r : this.zReclaimRects) {
         if (chunkX >= r[0] && chunkX < r[0] + r[2] && chunkZ >= r[1] && chunkZ < r[1] + r[3]) {
            return true;
         }
      }
      return false;
   }

   public RiverReclaim getRiverReclaim() {
      return this.riverReclaim;
   }

   public RiverProvider getRiverProvider() {
      return this.riverProvider;
   }

   /** The north-south river, or null. */
   public RiverProvider getZRiverProvider() {
      return this.zRiverProvider;
   }

   /**
    * The river whose water/bank footprint covers this chunk: the east-west
    * river if it does, else the north-south one if it does, else the
    * east-west river (the default everything has always used).
    */
   public RiverProvider getRiverProviderAt(int chunkX, int chunkZ) {
      // Inside a north-south corridor every chunk belongs to that river, bank
      // ramp and dry slack included, not just the chunks its footprint test hits.
      if (this.zRiverProvider != null && this.zRiverCorridorPresent
              && chunkX >= this.zCorridorMinX && chunkX <= this.zCorridorMaxX
              && chunkZ >= this.zCorridorTileMinZ && chunkZ < this.zCorridorTileMinZ + this.zCorridorTileHeight) {
         return this.zRiverProvider;
      }
      if (this.zRiverProvider != null && !this.riverProvider.isRiverOrBank(chunkX, chunkZ)
              && this.zRiverProvider.isRiverOrBank(chunkX, chunkZ)) {
         return this.zRiverProvider;
      }
      return this.riverProvider;
   }

   /**
    * True if this chunk is water or bank of either river -- OR this
    * tile's own construction has already painted a RiverParcel here by
    * some other means (a creek cell is a RiverParcel, so one
    * instanceof check covers a creek too). That second half is what lets
    * every caller of this method -- tryPlaceRiverBand()'s "prove the
    * whole rectangle before committing" bisection, riverOverlapsFootprint()'s
    * landmark-avoidance check, and both of them -- treat an
    * already-painted creek exactly like the main river: something
    * ordinary carving proves a rectangle clear of before ever building
    * over it, rather than something it can walk straight through because
    * neither RiverProvider knows anything about it.
    *
    * Currently a no-op for creeks under this tile's own ordering (creeks
    * are still painted by placeCreeks(), strictly AFTER every ordinary
    * district/road in this tile is already carved -- see this
    * constructor's own call order), so no creek can exist yet at the
    * point any of this method's callers run. It becomes load-bearing the
    * moment a creek is ever painted BEFORE placeCityDistricts() runs
    * instead (the reordering this is being prepared for) -- until then
    * this is inert, deliberately added ahead of that change rather than
    * alongside it.
    */
   private boolean isAnyRiverOrBank(int chunkX, int chunkZ) {
      return this.riverProvider.isRiverOrBank(chunkX, chunkZ)
              || (this.zRiverProvider != null && this.zRiverProvider.isRiverOrBank(chunkX, chunkZ))
              || this.getParcel(chunkX, chunkZ) instanceof RiverParcel;
   }

   /**
    * Creates the RiverParcel for one river/bank chunk. Where the north-south
    * river reaches the tile's first/last interior row, the parcel is marked so
    * it builds the retaining wall and drain pipe through the E-W highway ring
    * -- the same treatment carveRiverCorridor() gives the east-west river at
    * the ring's N-S columns.
    */
   private RiverParcel placeRiverParcel(int chunkX, int chunkZ) {
      // Nothing here depends on where the tile edge is: a river chunk is a river chunk. (The old tile-edge highway wall on the
      // north-south river's rows 1 and 62 belonged to the tile ring, which the plan no longer has.)
      RiverParcel parcel = new RiverParcel(this, new Cartesian2D(chunkX, chunkZ));
      // Where the river runs under a highway the lanes keep their road bed, and this side builds the retaining wall and the drain
      // pipe (RiverParcel): a river chunk with a lane, over the river, right ahead of it along the river's own course.
      RiverProvider river = this.getRiverProviderAt(chunkX, chunkZ);
      if (river != null) {
         if (river.isZBound()) {
            if (this.isLaneOverRiver(chunkX, chunkZ - 1)) {
               parcel.markHighwayCrossingZ(true);
            } else if (this.isLaneOverRiver(chunkX, chunkZ + 1)) {
               parcel.markHighwayCrossingZ(false);
            }
         } else if (this.isLaneOverRiver(chunkX - 1, chunkZ)) {
            parcel.markHighwayBoundaryX(true);
         } else if (this.isLaneOverRiver(chunkX + 1, chunkZ)) {
            parcel.markHighwayBoundaryX(false);
         }
      }
      return parcel;
   }

   /** Is chunk (x, z) a highway lane of the network that the river or its bank runs under? */
   private boolean isLaneOverRiver(int x, int z) {
      return this.networkPieceAt(x, z) != null && this.isAnyRiverOrBank(x, z);
   }

   public OceanProvider getOceanProvider() {
      return this.oceanProvider;
   }

   // Ships/boats placed on THIS tile (see OceanBoatPlacer). Neighbouring tiles
   // read it, read-only, to keep big builds from repeating within their
   // (large) clone radius across a tile border.
   private OceanShipRegistry oceanShipRegistry;

   public synchronized OceanShipRegistry getOceanShipRegistry() {
      if (this.oceanShipRegistry == null) {
         this.oceanShipRegistry = new OceanShipRegistry(OceanConfig.getOceanBoatsSpacingBlocks());
      }
      return this.oceanShipRegistry;
   }

   /**
    * Every ship already placed on this tile or on an ALREADY-BUILT neighbouring
    * tile that could lie within `radius` chunks of (chunkX, chunkZ). Read-only:
    * never builds a tile that doesn't exist yet (same rule as peekParcel()).
    */
   public List<OceanShipRegistry.PlacedShip> peekOceanShipsNear(int chunkX, int chunkZ, int radius) {
      List<OceanShipRegistry.PlacedShip> out = new ArrayList<OceanShipRegistry.PlacedShip>(this.getOceanShipRegistry().getShips());
      int minTile = Math.floorDiv(chunkX - radius, 64);
      int maxTile = Math.floorDiv(chunkX + radius, 64);
      int minTileZ = Math.floorDiv(chunkZ - radius, 64);
      int maxTileZ = Math.floorDiv(chunkZ + radius, 64);
      for (int tx = minTile; tx <= maxTile; tx++) {
         for (int tz = minTileZ; tz <= maxTileZ; tz++) {
            if (tx * 64 == this.root.X && tz * 64 == this.root.Y) {
               continue;
            }
            Grid neighbour = this.gridProvider.peekGrid(tx * 64, tz * 64);
            if (neighbour instanceof UrbanGrid && neighbour != this) {
               out.addAll(((UrbanGrid) neighbour).getOceanShipRegistry().getShips());
            }
         }
      }
      return out;
   }

   public UrbanGrid(GridProvider provider, GridRandom random, MetropolisGenerator generator, Cartesian2D root) {
      super(random, provider, generator, root);
      this.contextProvider = generator.getContextProvider();
      this.clipboardProvider = generator.getClipboardProvider();
      if (this.clipboardProvider == null) {
         Minions.w("GENERATOR CP IS NULL");
      }
      this.oceanProvider = new OceanProvider(generator.getWorldSeed());
      // The creeks are part of the world plan (their water in the water field, their buildings as fixed blocks of the layout), so
      // they are installed before this tile, or any other, asks the plan anything.
      prepareWorldPlan(generator);

      // RiverProvider's anchored constructor and OceanProvider.attachRivers()
      // have existed on both sides for a while -- this is the one line that
      // was actually missing, and it's the entire reason
      // river.convergeOnOcean never did anything: nothing ever called it, so
      // the "main" river was always built fully independently (the
      // no-args constructor below) and the ocean's xRiver/zRiver stayed
      // permanently null. See RiverConfig.isRiverConvergeOnOcean()'s own
      // javadoc for what this is supposed to look like once it's live.
      boolean convergeRiverOnOcean = OceanConfig.isOceanEnabled() && RiverConfig.isRiverEnabled() && RiverConfig.isRiverConvergeOnOcean();
      if (convergeRiverOnOcean) {
         double oceanCenterX = this.oceanProvider.getCenterChunkX();
         double oceanCenterZ = this.oceanProvider.getCenterChunkZ();
         // The X-bound river IS the world's one main river -- everything
         // else that reads this.riverProvider elsewhere (reclaim, bridges,
         // city district avoidance) sees this same, now ocean-anchored,
         // instance. The Z-bound river (if enabled) exists ONLY to be
         // attached below -- see RiverConfig.isRiverZBoundEnabled()'s own
         // "only drawn inside the ocean's own carve box" note for why
         // nothing outside the ocean's own rendering ever needs a
         // reference to it.
         this.riverProvider = new RiverProvider(generator.getWorldSeed(), false, oceanCenterX, oceanCenterZ);
         this.zRiverProvider = RiverConfig.isRiverZBoundEnabled()
                 ? new RiverProvider(generator.getWorldSeed(), true, oceanCenterZ, oceanCenterX)
                 : null;
         this.oceanProvider.attachRivers(this.riverProvider, this.zRiverProvider);
         Minions.dOcean("river convergence active -- X-bound river anchored at chunk (%d,%d), %s",
                 (int) Math.round(oceanCenterX), (int) Math.round(oceanCenterZ),
                 this.zRiverProvider != null ? "Z-bound river also anchored there" : "Z-bound river disabled (riverZBoundEnabled=false)");
      } else {
         this.riverProvider = new RiverProvider(generator.getWorldSeed());
         this.zRiverProvider = null;
      }
      this.bankEdgeJitter = new SimplexOctaveGenerator(generator.getWorldSeed() ^ 0x42414E4B45444745L, 2);

      // One line per tile, always cheap (a couple of spineZ() samples),
      // gated on isRiverDebugEnabled() -- shows whether THIS tile's chunk
      // range comes anywhere near the river's centerline even when no
      // district in it ever actually rolls a hit, which is the single
      // most useful signal for "why don't I see a river": either this
      // tile is nowhere near baseZ, or it is and something further down
      // the carving/rendering pipeline is the real problem.
      if (RiverConfig.isRiverEnabled()) {
         int tileMinX = root.X + 1;
         int tileMaxX = root.X + 62;
         double spineAtStart = this.riverProvider.spineZ(tileMinX);
         double spineAtEnd = this.riverProvider.spineZ(tileMaxX);
         double tileMinZ = root.Y + 1;
         double tileMaxZ = root.Y + 62;
         boolean nearTile = (spineAtStart >= tileMinZ - 8 && spineAtStart <= tileMaxZ + 8)
                 || (spineAtEnd >= tileMinZ - 8 && spineAtEnd <= tileMaxZ + 8);
         Minions.dRiver("tile root=(%d,%d) chunkRangeX=[%d,%d] chunkRangeZ=[%.0f,%.0f] -- river spineZ at this tile's X range: %.1f .. %.1f -- %s",
                 root.X, root.Y, tileMinX, tileMaxX, tileMinZ, tileMaxZ, spineAtStart, spineAtEnd,
                 nearTile ? "PASSES THROUGH (or very near) this tile" : "does not come near this tile");
      }

      // Same per-tile relevance trace as the river's own above, cheap
      // bounding check gated on isOceanDebugEnabled().
      if (OceanConfig.isOceanEnabled()) {
         int tileMinX = root.X + 1;
         int tileMaxX = root.X + 62;
         int tileMinZ = root.Y + 1;
         int tileMaxZ = root.Y + 62;
         // tileReached() itself, not a separate approximation of it -- a
         // log line disagreeing with the actual decision is exactly the
         // kind of mismatch that made this whole area hard to diagnose.
         int centerX = this.oceanProvider.getCenterChunkX();
         int centerZ = this.oceanProvider.getCenterChunkZ();
         boolean nearTile = this.oceanProvider.tileReached(root.X, root.Y);
         Minions.dOcean("tile root=(%d,%d) chunkRangeX=[%d,%d] chunkRangeZ=[%d,%d] -- ocean center=(%d,%d) envelope=%.1fx%.1f chunks -- %s",
                 root.X, root.Y, tileMinX, tileMaxX, tileMinZ, tileMaxZ, centerX, centerZ,
                 this.oceanProvider.getEnvelopeRadiusChunksX(), this.oceanProvider.getEnvelopeRadiusChunksZ(),
                 nearTile ? "REACHES this tile" : "does not reach this tile");
      }

      // Everything on the tile comes from the world plan (highways, water, streets, blocks); nothing is carved here.
      this.placeFromPlan();

      // fillDistrict() can now mutate this.districtSet mid-iteration when
      // extraCutMode is on (a district scraps and re-cuts itself via
      // recutAndRefill(), which removes itself and adds its replacements).
      // Iterate a snapshot so that doesn't throw ConcurrentModificationException;
      // the replacement districts are filled directly inside recutAndRefill(),
      // not by this loop, so they don't need to appear in the snapshot.
      // (zoning is done by the plan: each block arrives with its context; and the plan, not a setting of the tile, decides
      // which streets dissolve, so the chunks it dissolved must be placed whatever the neighbour rules setting says)
      this.dissolveMorphomosRoads();
      for (IDistrict district : new HashSet<>(this.districtSet)) {
         // Morphomos (forest / farm) districts hold no schematics: they are mapped
         // as regions by dissolveMorphomosRoads() and left to the region generators.
         if (this.morphomosDistricts.contains(district)) {
            continue;
         }
         // The block is filled whole, from the block alone, and this tile keeps its chunks of it (see fillBlockWhole).
         if (district instanceof District && ((District) district).isCreekReclaimSchematicFill()) {
            district.fillDistrict();
         } else if (!this.fillBlockWhole(district)) {
            district.fillDistrict();
         }
      }

      // Creek ownership closure: the world plan has already accepted its
      // districts and filled their real schematics. Any cells it could not
      // assign near an actual creek must now belong to natural wilderness,
      // NEVER to a filler schematic and never remain null. This is strictly
      // limited to unresolved cells; it cannot replace a building, road,
      // bridge, creek or river parcel. A single region covers the tile's
      // natural remainder so forest vegetation has a coherent mask.
      this.commitBoundedDryHandoff();

      // Bank-ramp targets, read off the FINAL parcel grid now that every
      // reclaimed district's actual building has replaced the RiverParcel
      // placeholder carveRiverCorridor() put there -- see
      // computeBankEdges()'s own javadoc for why this can't run any
      // earlier (initDistrict() only registers a district; fillDistrict()
      // just above is what actually writes its parcels) or be derived
      // from RiverReclaim's own geometry instead.
      if (this.riverCorridorPresent) {
         this.computeBankEdges(this.riverCorridorTileMinX, this.riverCorridorTileWidth,
                 this.riverCorridorMinZ, this.riverCorridorMaxZ);
      }
      if (this.zRiverCorridorPresent) {
         this.computeBankEdgesZ(this.zCorridorTileMinZ, this.zCorridorTileHeight,
                 this.zCorridorMinX, this.zCorridorMaxX);
      }

      // Boats: only now, after fillDistrict() above has actually written
      // every reclaimed ocean district's real building, is it safe to ask
      // an OceanParcel cell "are you still open water" and trust the
      // answer -- see placeOceanIfPresent()'s own comment on why this
      // can't run any earlier. depth (isOpenWater()/getDepthTier()) itself
      // has been valid since each OceanParcel was constructed -- see that
      // class's own constructor comment -- only the SURROUNDING reclaimed
      // land needed to wait for fillDistrict().
      // With the world plan the ocean is not carved per tile (oceanCarvePresent is never set), so a tile with sea in it scans its own
      // chunks: the placer only acts on OceanParcels, whatever else it meets.
      boolean planOcean = WorldLayouts.forSeed(this.generator.getWorldSeed()) != null
              && OceanConfig.isOceanEnabled() && this.oceanProvider.tileReached(this.root.X, this.root.Y);
      if ((this.oceanCarvePresent || planOcean) && OceanConfig.isOceanBoatsEnabled()) {
         int boatMinX = this.oceanCarvePresent ? this.oceanCarveMinX : this.root.X;
         int boatMaxX = this.oceanCarvePresent ? this.oceanCarveMaxX : this.root.X + 63;
         int boatMinZ = this.oceanCarvePresent ? this.oceanCarveMinZ : this.root.Y;
         int boatMaxZ = this.oceanCarvePresent ? this.oceanCarveMaxZ : this.root.Y + 63;
         int boatsPlaced = new OceanBoatPlacer(this, this.generator.getWorldSeed(), this.root.X, this.root.Y,
                 PluginConfig.getCloneRadius(), PluginConfig.getCloneRadiusSoftLimit())
                 .populateOceanBoats(boatMinX, boatMaxX, boatMinZ, boatMaxZ);
         if (boatsPlaced > 0) {
            Minions.dOcean("tile root=(%d,%d) -- placed %d boat(s)", this.root.X, this.root.Y, boatsPlaced);
         }
      }
      // Optional, rare, street-connected ports are chosen only after boats and city
      // parcels are final. No continuous waterfront is generated or reinstated.
      if (planOcean && OceanConfig.isOceanPortsEnabled()) {
         OceanPortPlanner.place(this, this.generator, this.root.X, this.root.Y);
      }
      // Roads/highways are placed on a fixed rectilinear lattice with zero
      // awareness of the river (see recSetDistricts()' river-strip check
      // for why that's fine, not a bug) -- so whichever of them ended up
      // with the river flanking both opposite sides gets swapped for a
      // BridgeParcel now, after every district AND every road/highway
      // cell in this tile has been placed.
      if (RiverConfig.isRiverEnabled()) {
         this.convertRoadsToBridges();
      }
      // Bridge conversion can leave two planned approaches along a creek
      // which lead to water but cannot support a valid district. Retire the
      // WHOLE empty branch, then re-resolve the adjacent street geometry.
      this.retireCreekRoadBranches();
      // Finalize bank geometry after the pre-paint road ownership handoff.
      // Rejected creek-side roads must not become competing forest terrain.
      for (RiverProvider creek : this.creekCarriers) {
         this.computeCreekBankEdges(creek, this.root.X + 1, this.root.X + 62, this.root.Y + 1, this.root.Y + 62);
      }
      // The original bounded debug-ledgers close after bridges, terrain and
      // schematics have finished parcel assignment, not after the initial
      // water plan. Ownership guards remain enabled with debug toggles OFF.
      if (this.boundedPaintAudit != null) {
         WorldLayout finalLayout=WorldLayouts.forSeed(this.generator.getWorldSeed());
         for (BoundedRiverSites.Site site : this.boundedSitesOf.values()) {
            this.boundedPaintAudit.register(site.id(),site.resolvedAgainst(finalLayout),
                    site.dryCandidatesBeforeResolution);
         }
         this.boundedPaintAudit.finishPlan((x,z)->parcelName(this.getParcel(x,z)));
         this.boundedPaintAudit.summary();
      }
      if (this.boundedFailureTrace != null) {
         this.boundedFailureTrace.phase("PLAN_CLOSED");
         this.boundedFailureTrace.finish();
      }
      Minions.i("Set new grid.");
      this.auditActiveCreekPlan();
      this.auditBoundedParkOwnership();
      this.auditUnresolvedCreekOwnership();
      this.auditOceanFinalOwnerParity();
      if (PluginConfig.isAsciiMapDebugEnabled()) {
         this.auditFinalPlannedRoadOwnership();
         this.dumpAsciiMap();
      }
      if (PluginConfig.isDebugEnabled()) {
         Bukkit.getScheduler()
                 .runTaskAsynchronously(
                         generator.getPlugin(),
                         new Runnable() {
                            @Override
                            public void run() {
                               String filename = UrbanGrid.this.generator.getPlugin().getDataFolder().getAbsolutePath()
                                       + File.separator
                                       + "gridlog "
                                       + UrbanGrid.this.root.X
                                       + "_"
                                       + UrbanGrid.this.root.Y
                                       + ".txt";

                               try {
                                  BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filename)));
                                  writer.write(UrbanGrid.this.statistics.printStatistics());
                                  writer.close();
                               } catch (FileNotFoundException e) {
                                  Minions.e(e);
                               } catch (IOException e) {
                                  Minions.e(e);
                               }
                            }
                         }
                 );
      }
   }

   /**
    * Called by Parcel.markPopulated() the moment ANY parcel in this tile
    * gets its schematic pasted -- forwarded straight to the DecayProvider
    * so it can re-check whether that new placement is what some OTHER,
    * already-decayed build in this tile was waiting on before flushing
    * its own cached growth (see DecayProviderNormal.onNeighbourPlaced()/
    * neighbourhoodReady()). Replaces the old tile-wide "wait for literally
    * every parcel in the whole 64x64 tile" gate -- growth now only ever
    * waits on the handful of builds actually around it.
    */
   public void notifyParcelPopulated(Parcel parcel) {
      this.generator.getDecayProvider().onNeighbourPlaced(this, parcel);
   }

   /**
    * The city highway network's share of this tile: every highway lane inside it becomes a HighwayParcel
    * whose piece comes from its neighbours (HighwayNetwork.pieceFor()). The tile edge keeps a street
    * wherever no highway runs there, so districts of neighbouring tiles never touch without a road
    * between them -- the job the old ring did. Interior lanes are carved around by placeLeftoverLand().
    */
   /** True once placeNetworkHighways() ran for this tile: the edge cells not covered by a city layout get a street. */
   private boolean networkEdgeRing = false;

   /**
    * The piece the highway network gives the lane at (x, z), or null if there is no lane there. Worked out from the
    * network itself, not from whichever tile happens to be built, so a lane beside a tile edge reads the same
    * whichever side asks. Without a network (the old tile ring) it is the lane parcel's own piece.
    */
   public RoadType networkPieceAt(int x, int z) {
      if (this.networkMask == null) {
         Parcel p = this.peekParcel(x, z);
         return p instanceof HighwayParcel ? ((HighwayParcel) p).baseRoadType() : null;
      }
      if (x >= this.root.X && x <= this.root.X + 63 && z >= this.root.Y && z <= this.root.Y + 63) {
         return this.networkMask.at(x, z) ? HighwayNetwork.pieceOrFallback(this.networkMask, x, z) : null;
      }
      HighwayNetwork.Snapshot around = HighwayNetworks.forSeed(this.generator.getWorldSeed()).snapshot(x - 1, z - 1, x + 1, z + 1);
      return around.at(x, z) ? HighwayNetwork.pieceOrFallback(around, x, z) : null;
   }

   private void placeNetworkHighways() {
      HighwayNetwork network = HighwayNetworks.forSeed(this.generator.getWorldSeed());
      int minX = this.root.X;
      int minZ = this.root.Y;
      int maxX = this.root.X + 63;
      int maxZ = this.root.Y + 63;
      // With a world layout there is no tile edge street to paint: the layout puts the one street a tile edge ever has
      // (see WorldLayouts.PITCH), and drops it where a highway runs beside it.
      boolean worldLayout = WorldLayouts.forSeed(this.generator.getWorldSeed()) != null;
      this.networkEdgeRing = true;
      this.networkMask = network.snapshot(minX, minZ, maxX, maxZ);
      this.networkBands = network.rectsIn(minX, minZ, maxX, maxZ);
      this.ringDropped = null;
      int lanes = 0;
      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            Cartesian2D pos = new Cartesian2D(x, z);
            if (this.networkMask.at(x, z)) {
               HighwayParcel lane = new HighwayParcel(this, pos, HighwayNetwork.pieceOrFallback(this.networkMask, x, z));
               lane.setBend(HighwayNetwork.bendInfo(this.networkMask, x, z));
               this.setParcel(x, z, lane);
               lanes++;
            } else if (!worldLayout && (x == minX || x == maxX || z == minZ || z == maxZ)) {
               this.setParcel(x, z, new RoadParcel(this, pos));
            }
         }
      }
      Minions.d("tile root=(" + this.root.X + "," + this.root.Y + ") highway network: " + lanes + " lane chunk(s) in "
              + this.networkBands.size() + " band(s)");
   }

   /** The first highway band that overlaps this rectangle, or null (also null on a tile that uses the old ring). */
   private HighwayNetwork.Rect firstNetworkBandOverlapping(int minX, int minZ, int maxX, int maxZ) {
      if (this.networkBands == null) {
         return null;
      }
      for (HighwayNetwork.Rect band : this.networkBands) {
         if (band.intersects(minX, minZ, maxX, maxZ)) {
            return band;
         }
      }
      return null;
   }

   /**
    * Puts the whole tile together from the world plan, cell by cell: highway lanes, water, the ground around the water,
    * then the streets and blocks of the street layout. The plan is a pure function of the seed, so nothing here depends
    * on the tile: the same chunk comes out the same whichever tile asks for it.
    */
   /** Must equal WorldLayout.SHORE_REACH: the layout leaves to the shore every leftover chunk within this reach of water. */
   private static final int SHORE_REACH = WorldLayout.SHORE_REACH;

   /**
    * The kind of the nearest water zone to chunk (x, z), looking outwards ring by ring, or NONE if there is none within
    * SHORE_REACH. A function of the world layout alone, so every tile reads the same.
    */
   public static WaterField.Kind nearestShoreKind(WaterField water, int x, int z) {
      for (int r = 1; r <= SHORE_REACH; r++) {
         WaterField.Kind best = WaterField.Kind.NONE;
         for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
               if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                  continue;
               }
               WaterField.Kind k = water.zoneKind(x + dx, z + dz);
               if (k == WaterField.Kind.OCEAN) {
                  return k;                      // OCEAN wins over RIVER, as in zoneKind itself
               }
               if (k != WaterField.Kind.NONE) {
                  best = k;
               }
            }
         }
         if (best != WaterField.Kind.NONE) {
            return best;
         }
      }
      return WaterField.Kind.NONE;
   }

   private void placeFromPlan() {
      long seed = this.generator.getWorldSeed();
      WorldLayout layout = WorldLayouts.forSeed(seed);
      if (layout == null) {
         Minions.e(new IllegalStateException("world plan unavailable (highwayNetwork disabled, or blockSize < 2 * blockSizeMin): tile "
                 + this.root.X + "," + this.root.Y + " left empty"));
         return;
      }
      WaterField water = WorldLayouts.waterFor(seed);
      this.networkEdgeRing = true;

      // Registration was moved to prepareWorldPlan(), before any domain lookup.
      prepareWorldPlan(this.generator);
      this.registerCreeks();

      this.placeNetworkHighways();   // the lanes
      // Bounded creek shore shoulders must resolve against the same
      // committed road/region plan as their water carriers.
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes creekShoreShapes = this.roadShapes();
      PlanRegions creekShoreRegions = creekShoreShapes == null ? null : creekShoreShapes.regions();

      for (int z = this.root.Y; z <= this.root.Y + 63; z++) {
         for (int x = this.root.X; x <= this.root.X + 63; x++) {
            if (this.getParcel(x, z) != null) {
               continue;   // a highway lane
            }
            WaterField.Kind kind = water.kindAt(x, z);
            // The park owns its mandatory street wrapper, but the one outlet
            // crossing must use its actual creek carrier and paint a bridge.
            int outletAxis=layout.creekOutletRingAxisAt(x,z);
            if(outletAxis!=0) {
               if(kind==WaterField.Kind.CREEK) {
                  RiverProvider carrier=this.activeCreekCarrierAt(x,z);
                  if(carrier==null) {
                     Minions.w("CREEK PARK BRIDGE CARRIER MISSING chunk=(%d,%d)",x,z);
                     this.placeCreekAt(x,z);
                  } else new BridgeParcel(this,new Cartesian2D(x,z),outletAxis==1,false,carrier);
               } else new RoadParcel(this,new Cartesian2D(x,z));
            } else if (kind == WaterField.Kind.OCEAN) {
               new OceanParcel(this, new Cartesian2D(x, z));
            } else if (kind == WaterField.Kind.RIVER) {
               this.placeRiverParcel(x, z);
            } else if (kind == WaterField.Kind.CREEK) {
               this.placeCreekAt(x, z);
            } else if (layout.kindAt(x, z) == WorldLayout.NATURAL) {
               this.assignPlannedInterfluveGround(x,z);
            } else if (layout.kindAt(x, z) == WorldLayout.ZONE) {
               // Preserve the narrow planned dry shoulder joining this exact
               // creek to its real road, before generic nearest-shore painting.
               RiverProvider bankCarrier = this.plannedCreekRoadBankCarrier(x, z, layout, water, creekShoreRegions);
               if (bankCarrier != null) {
                  new RiverParcel(this, new Cartesian2D(x, z), bankCarrier);
                  continue;
               }
               // The shore: the land between the water and the nearest street belongs to the water body and is painted by its
               // own provider (bank, beach, flat shore land, sloped to the height of what borders it), exactly as the river
               // corridor and the ocean's beach always were. There is no separate ground category beside water.
               WaterField.Kind shore = water.zoneKind(x, z);
               if (shore == WaterField.Kind.NONE) {
                  // Ground the carve left over beside the water (a chunk or fifty: no block size comes into it) is its shore:
                  // it takes the nearest water's shore painting, which paints any chunk of dry land, never bare terrain.
                  shore = this.nearestShoreKind(water, x, z);
               }
               if (shore == WaterField.Kind.OCEAN) {
                  new OceanParcel(this, new Cartesian2D(x, z));
               } else if (shore == WaterField.Kind.RIVER) {
                  this.placeRiverParcel(x, z);
               } else if (shore == WaterField.Kind.CREEK) {
                  boolean hasCarrierSite=boundedSitesFor(this.generator).siteAt(x,z)!=null;
                  if(BoundedRiverDryHandoff.naturalShoreInsteadOfCarrier(true,hasCarrierSite)) {
                     this.assignCreekNaturalGround(x,z);
                     this.boundedShoreWithoutCarrier++;
                  } else this.placeCreekAt(x,z);
               } else {
                  // not beside any water: a pocket between highway lanes too narrow for a block (see the notes)
                  this.placeGroundFallback(x, z, "zone-pocket");
               }
            }
         }
      }

      if(this.boundedShoreWithoutCarrier>0 && PluginConfig.isCreekPaintLifecycleDebugEnabled())
         Minions.w("[R14-CREEK-LIFECYCLE] BOUNDED_NATURAL_SHORE_NO_CARRIER tile=(%d,%d) cells=%d",
                 this.root.X,this.root.Y,this.boundedShoreWithoutCarrier);
      this.placeCityBlocks(this.root.X + 1, this.root.Y + 1, this.root.X + 62, this.root.Y + 62);

      // Bank-ramp targets: where the land each river owns stops, from the plan (see BankSlope), not from this tile's parcels.
      this.computeBankEdgesFromPlan(layout, water);
   }

   /**
    * The plan version of computeBankEdges()/computeBankEdgesZ(): for each column (or row) of this tile, how far the river's
    * bank ramp may reach before it meets a street, block or lane, slope-limited and lightly jittered exactly as before.
    * A pure function of the plan and the column, so the tile next door computes the same value for the same column; the
    * window each column looks at is a fixed number of chunks (BANK_WINDOW), so no tile edge shows. Leaves the arrays null
    * (every query NaN, the generic margin ramp) for a river with no bank in this tile.
    */
   private static final int BANK_WINDOW = 96;

   private void computeBankEdgesFromPlan(WorldLayout layout, WaterField water) {
      double maxSlope = RiverConfig.getRiverBankEdgeMaxSlopePerChunk();
      int radius = Math.min(BankSlope.radiusFor(maxSlope), BANK_WINDOW);

      RiverProvider main = water.mainRiver();
      if (main != null) {
         BankSlope.Terrain t = new PlanBankTerrain(layout, water, main);
         int u0 = this.root.X;
         int u1 = this.root.X + 63;
         double[] north = BankSlope.smoothed(t, true, u0, u1, false, maxSlope, radius);
         double[] south = BankSlope.smoothed(t, true, u0, u1, true, maxSlope, radius);
         boolean any = false;
         for (int i = 0; i < north.length; i++) {
            double jitterBlocks = (this.bankEdgeJitter.noise((u0 + i) * 0.05, 0, 0.5, 0.5, true) + 1.0) * 0.15 * 16.0;
            if (!Double.isNaN(north[i])) {
               north[i] -= jitterBlocks;
               any = true;
            }
            if (!Double.isNaN(south[i])) {
               south[i] += jitterBlocks;
               any = true;
            }
         }
         if (any) {
            this.bankEdgeTileMinX = u0;
            this.bankEdgeTileWidth = north.length;
            this.northBankEdgeBlockZ = north;
            this.southBankEdgeBlockZ = south;
         }
      }

      RiverProvider zRiver = water.zBoundRiver();
      if (zRiver != null) {
         BankSlope.Terrain t = new PlanBankTerrain(layout, water, zRiver);
         int u0 = this.root.Y;
         int u1 = this.root.Y + 63;
         double[] west = BankSlope.smoothed(t, false, u0, u1, false, maxSlope, radius);
         double[] east = BankSlope.smoothed(t, false, u0, u1, true, maxSlope, radius);
         boolean any = false;
         for (int i = 0; i < west.length; i++) {
            double jitterBlocks = (this.bankEdgeJitter.noise((u0 + i) * 0.05, 0, 0.5, 0.5, true) + 1.0) * 0.15 * 16.0;
            if (!Double.isNaN(west[i])) {
               west[i] -= jitterBlocks;
               any = true;
            }
            if (!Double.isNaN(east[i])) {
               east[i] += jitterBlocks;
               any = true;
            }
         }
         if (any) {
            this.zBankEdgeTileMinZ = u0;
            this.zBankEdgeTileHeight = west.length;
            this.westBankEdgeBlockX = west;
            this.eastBankEdgeBlockX = east;
         }
      }
   }

   /**
    * Lays out the part of a city that falls inside this rectangle of leftover land, from the city's own layout
    * (CityLayout) instead of cutting the rectangle up per tile: the streets are painted where the layout says,
    * and every block becomes a district. The layout is a function of the seed and the city alone, so a block
    * that runs across a tile edge is the same block in both tiles; each tile takes its own part of it.
    *
    * Returns false, having touched nothing, when no city's street or block is in the rectangle. When the
    * rectangle is only partly city the rest (land outside every city box) is handed back to
    * placeLeftoverLand() as rectangles of its own, which carve it the way they always did.
    *
    * A rectangle that reaches the tile's interior edge is extended one cell to the tile edge, because inside
    * a city box there is no tile edge street any more: the layout runs right up to the tile edge instead.
    */
   // Audited independently from the legacy RiverReclaim registry.
   private final java.util.List<WorldLayout.Rect> acceptedActiveCreekBlocks = new java.util.ArrayList<WorldLayout.Rect>();
   private final java.util.List<WorldLayout.Rect> rejectedActiveCreekBlocks = new java.util.ArrayList<WorldLayout.Rect>();

   private boolean placeCityBlocks(int minX, int minZ, int maxX, int maxZ) {
      WorldLayout layout = WorldLayouts.forSeed(this.generator.getWorldSeed());
      if (layout == null || !this.networkEdgeRing) {
         return false;
      }
      int tileMinX = this.root.X;
      int tileMinZ = this.root.Y;
      int tileMaxX = this.root.X + 63;
      int tileMaxZ = this.root.Y + 63;
      int exMinX = minX == tileMinX + 1 ? tileMinX : minX;
      int exMinZ = minZ == tileMinZ + 1 ? tileMinZ : minZ;
      int exMaxX = maxX == tileMaxX - 1 ? tileMaxX : maxX;
      int exMaxZ = maxZ == tileMaxZ - 1 ? tileMaxZ : maxZ;

      List<WorldLayout.Rect> blocks = layout.blocksIn(exMinX, exMinZ, exMaxX, exMaxZ);
      for (BoundedRiverSites.Site site : this.boundedSitesOf.values()) {
         if (site.hostTileX * 64 != this.root.X || site.hostTileZ * 64 != this.root.Y) continue;
         WorldLayout.Rect origin = layout.blockAt(site.lotBaseX, site.lotBaseZ);
         WorldLayout.Landmark mark = origin == null ? null : layout.landmarkAt(origin);
         Minions.dRiver("%s", "[METROPOLIS-CREEK-ORIGIN-PROBE-05] tile=("+this.root.X+","+this.root.Y+") lot=("+site.lotBaseX+","+site.lotBaseZ+","+site.lotSizeX+","+site.lotSizeZ+") planKind="+layout.kindAt(site.lotBaseX,site.lotBaseZ)
               +" block="+(origin==null?"null":("("+origin.minX+","+origin.minZ+".."+origin.maxX+","+origin.maxZ+")"))
               +" fixedLandmark="+(mark!=null));
      }
      WaterField water = WorldLayouts.waterFor(this.generator.getWorldSeed());
      int w = exMaxX - exMinX + 1;
      int h = exMaxZ - exMinZ + 1;
      boolean[] claimed = new boolean[w * h];
      boolean any = !blocks.isEmpty();

      // Active plan path: only accept creek-neighboring blocks with a genuinely
      // continuous public street ring. Validate globally from the plan so a
      // block straddling two 64-chunk tiles has the same answer in both tiles.
      java.util.Set<WorldLayout.Rect> rejectedCreekBlocks = new java.util.HashSet<WorldLayout.Rect>();
      int rejectedCreekBlockCount = 0;
      for (WorldLayout.Rect block : blocks) {
         if (creekSensitivePlanBlock(water, block) && !creekOriginPlanBlock(block)
                 && !creekPlanBlockFullyWrapped(layout, water, block)) {
            Minions.dRiver("%s", "[METROPOLIS-CREEK-REJECT-BOUNDS-07] tile=("+this.root.X+","+this.root.Y+") rect=("+block.minX+","+block.minZ+".."+block.maxX+","+block.maxZ+") area="+((block.maxX-block.minX+1)*(block.maxZ-block.minZ+1)));
            // Audit only: preserve the original buildable district rather than
            // destroying it and painting a flat forest rectangle.  A missing
            // road side remains a reported defect, never a license to erase
            // the entire block and keep its streets.
            this.rejectedActiveCreekBlocks.add(block);
            rejectedCreekBlockCount++;
         }
      }
      if (rejectedCreekBlockCount > 0) {
         Minions.w("CREEK ROAD WRAPPER UNRESOLVED tile=(%d,%d) districts=%d (not a successful wrapped release)",
                 this.root.X, this.root.Y, rejectedCreekBlockCount);
         Minions.dRiver("%s", "[METROPOLIS-CREEK-ACTIVE-PLAN-03] tile=("+this.root.X+","+this.root.Y+") rejectedUnwrappedBlocks="+rejectedCreekBlockCount);
      }

      // Repartition a blocked creek-side district INWARD. Its original
      // shoreline-side parcel(s) become ordinary streets, never forest or
      // a solid artificial plateau. Validate the entire candidate ring before
      // reserving anything, and never pave a wet channel or another district.
      java.util.Map<WorldLayout.Rect,WorldLayout.Rect> adjustedCreekBlocks =
              new java.util.HashMap<WorldLayout.Rect,WorldLayout.Rect>();
      java.util.Set<Long> ringReservations = new java.util.LinkedHashSet<Long>();
      int completedRings=0, blockedRings=0;
      for (WorldLayout.Rect block : blocks) {
         if (!creekSensitivePlanBlock(water,block) || creekOriginPlanBlock(block)) continue;
         if (creekPlanBlockFullyWrapped(layout,water,block)) continue;
         WorldLayout.Rect chosen=null;
         java.util.Set<Long> chosenRing=null;
         String blocker="none";
         // Test smaller rectangles, minimum 2x2, with bounded, deterministic
         // offsets. Try least land loss first; no random or tile-local choice.
         for (int loss=0;loss<=6 && chosen==null;loss++) {
            for(int n=0;n<=Math.min(3,loss) && chosen==null;n++)
             for(int so=0;so<=Math.min(3,loss-n) && chosen==null;so++)
              for(int wInset=0;wInset<=Math.min(3,loss-n-so) && chosen==null;wInset++) {
               int eInset=loss-n-so-wInset;
               if(eInset<0 || eInset>3)continue;
               // A district remains a RECTANGLE. Every chunk trimmed from its
               // old rectangle must be accounted for by the ONE-CHUNK road
               // ring of the replacement. A 2- or 3-chunk inset left an
               // unexplained dry strip which the creek wilderness clean-up
               // then painted as forest, making an otherwise ordinary city
               // block look jagged / partially erased. Reject that solution;
               // retain the original district if no complete ring fits.
               if(n>1 || so>1 || wInset>1 || eInset>1)continue;
               WorldLayout.Rect b=new WorldLayout.Rect(block.minX+wInset,block.minZ+n,
                                    block.maxX-eInset,block.maxZ-so);
               if(b.maxX-b.minX<1 || b.maxZ-b.minZ<1)continue;
               java.util.Set<Long> candidate=new java.util.LinkedHashSet<Long>();
               for(int x=b.minX-1;x<=b.maxX+1;x++) {
                  candidate.add(cellKey(x,b.minZ-1));candidate.add(cellKey(x,b.maxZ+1));
               }
               for(int z=b.minZ;z<=b.maxZ;z++) {
                  candidate.add(cellKey(b.minX-1,z));candidate.add(cellKey(b.maxX+1,z));
               }
               boolean valid=true;
               for(long key:candidate) {
                  int x=(int)(key>>32),z=(int)key;
                  WaterField.Kind kind=water.kindAt(x,z);
                  if(kind==WaterField.Kind.CREEK || kind==WaterField.Kind.RIVER || kind==WaterField.Kind.OCEAN) {
                     blocker="water("+x+","+z+")"; valid=false;break;
                  }
                  WorldLayout.Rect other=layout.blockAt(x,z);
                  // Use the WORLD's committed ownership rather than
                  // this tile's preliminary parcels. The latter can differ
                  // depending on which tile generated first and left ring
                  // gaps on cross-tile districts. Any planned creek bank,
                  // NATURAL forest, or an unrelated city block is immutable.
                  int planned=layout.kindAt(x,z);
                  if(!CreekFrameCapacity.allows(planned,other==block)) {
                     blocker="planOwner="+planned+"("+x+","+z+")";valid=false;break;
                  }
                  if(ringReservations.contains(key))continue;
               }
               if(valid){chosen=b;chosenRing=candidate;}
              }
         }
         if(chosen==null) {
            blockedRings++;
            Minions.dRiver("%s", "[METROPOLIS-CREEK-REPARTITION-10] FAILED rect=("+block.minX+","+block.minZ+".."+block.maxX+","+block.maxZ+") lastBlocker="+blocker);
         } else {
            adjustedCreekBlocks.put(block,chosen);
            ringReservations.addAll(chosenRing);
            completedRings++;
            Minions.dRiver("%s", "[METROPOLIS-CREEK-REPARTITION-10] rect=("+block.minX+","+block.minZ+".."+block.maxX+","+block.maxZ+") -> ("+chosen.minX+","+chosen.minZ+".."+chosen.maxX+","+chosen.maxZ+") ring="+chosenRing.size());
         }
      }
      for(long key:ringReservations) {
         int x=(int)(key>>32),z=(int)key;
         if(x<tileMinX || x>tileMaxX || z<tileMinZ || z>tileMaxZ)continue;
         if(this.peekParcel(x,z)==null) {
            this.setParcel(x,z,new RoadParcel(this,new Cartesian2D(x,z)));
         }
         // Existing RoadParcels can be part of the very same accepted ring.
         // They need the same ownership and reciprocal shape handoff as the
         // newly created pieces, particularly when Morphomos had dissolved
         // their original plan street.
         if(this.peekParcel(x,z) instanceof RoadParcel)
            this.creekPerimeterCells.add(key);
      }
      // The ring is one accepted, deterministic geometric transaction; its
      // road edges must be published together, on both ends, BEFORE painting.
      // This also updates a previously planned T/X street at the ring join.
      // Never infer unrelated parallel road connections from mere adjacency.
      for (WorldLayout.Rect acceptedRing : adjustedCreekBlocks.values()) {
         CreekFrameEdges.trace(acceptedRing.minX, acceptedRing.minZ,
                 acceptedRing.maxX, acceptedRing.maxZ, (x,z,nx,nz) -> {
                    // A road-ring segment may share a side with a real
                    // highway. Do NOT invent new highway junctions by
                    // treating highway lanes as ordinary street segments.
                    // Their independent lane piece already owns those joins.
                    if (layout.kindAt(x,z) == WorldLayout.LANE
                            || layout.kindAt(nx,nz) == WorldLayout.LANE) return;
                    this.commitCreekRouteEdge(x,z,nx,nz);
                 });
      }
      Minions.dRiver("%s", "[METROPOLIS-CREEK-RING-BUILD-11] tile=("+tileMinX+","+tileMinZ+") completed="+completedRings+" blocked="+blockedRings+" locallyOwned="+this.creekPerimeterCells.size());

      // Streets: every chunk the layout says is a street and nothing has claimed. No tile edge street is painted
      // anywhere else; the layout's own lines are the only streets there are.
      for (int z = exMinZ; z <= exMaxZ; z++) {
         for (int x = exMinX; x <= exMaxX; x++) {
            int kind = layout.kindAt(x, z);
            if (kind == WorldLayout.STREET) {
               any = true;
               claimed[(z - exMinZ) * w + (x - exMinX)] = true;
               if (this.getParcel(x, z) == null) {
                  // A planned street with no surviving block on either
                  // side is not a reclaim perimeter. Preserve crossings
                  // near wet creek cells and creek-origin streets.
                  // Do not remove individual cells of an already planned public street.
                  // Pointwise creekOrphanPlanStreet() broke continuous approaches,
                  // causing the observed holes and road-connection mismatches.
                  this.setParcel(x, z, new RoadParcel(this, new Cartesian2D(x, z)));
               }
            }
         }
      }
      if (!any) {
         return false;
      }

      for (WorldLayout.Rect originalBlock : blocks) {
         // IMPORTANT: A failed creek road-ring repair must NEVER erase the
         // entire planned BUILD district. The old `continue` here silently
         // omitted the rectangle; its cells later became unresolved / forest
         // although the world plan still classified them as city BLOCK.
         // Preserve the full block and report the frontage defect instead.
         if (rejectedCreekBlocks.contains(originalBlock) && !adjustedCreekBlocks.containsKey(originalBlock)) {
            Minions.dDistrict("CREEK BLOCK RETAINED (perimeter incomplete) rect=(%d,%d..%d,%d)",
                    originalBlock.minX, originalBlock.minZ, originalBlock.maxX, originalBlock.maxZ);
         }
         WorldLayout.Rect block=adjustedCreekBlocks.containsKey(originalBlock)
               ? adjustedCreekBlocks.get(originalBlock) : originalBlock;
         if (creekSensitivePlanBlock(water, originalBlock)) acceptedActiveCreekBlocks.add(block);
         int x0 = Math.max(block.minX, exMinX);
         int x1 = Math.min(block.maxX, exMaxX);
         int z0 = Math.max(block.minZ, exMinZ);
         int z1 = Math.min(block.maxZ, exMaxZ);
         for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
               claimed[(z - exMinZ) * w + (x - exMinX)] = true;
            }
         }
         // Road-facing flags must come from the ACTUAL world street plan.
         // In particular, a coast / reclaim / tile edge is never a road
         // merely because a district happens to end there.
         // A repaired creek ring is a real committed RoadParcel but is not
         // represented in the immutable WorldLayout. Evaluate actual streets
         // for its frontage flags, or the schematics will incorrectly face
         // away from the new perimeter.
         boolean adjusted=adjustedCreekBlocks.containsKey(originalBlock);
         boolean north = adjusted ? creekRoadOnEdge(x0,x1,z0-1,true) : plannedRoadOnEdge(layout,x0,x1,z0-1,true);
         boolean south = adjusted ? creekRoadOnEdge(x0,x1,z1+1,true) : plannedRoadOnEdge(layout,x0,x1,z1+1,true);
         boolean west = adjusted ? creekRoadOnEdge(z0,z1,x0-1,false) : plannedRoadOnEdge(layout,z0,z1,x0-1,false);
         boolean east = adjusted ? creekRoadOnEdge(z0,z1,x1+1,false) : plannedRoadOnEdge(layout,z0,z1,x1+1,false);
         IDistrict district = new District();
         district.initDistrict(new Cartesian2D(x0, z0), new Cartesian2D(x1 - x0 + 1, z1 - z0 + 1), this);
         if (district instanceof District) {
            ((District) district).setRoadSides(north, south, east, west);
            // Valid creek-side city districts require the ordinary schematic
            // filler pass, not the BUILD-only shortcut that leaves Pn lots.
            if (creekSensitivePlanBlock(water, originalBlock) && !creekOriginPlanBlock(originalBlock)) {
               ((District) district).setCreekReclaimSchematicFill(true);
               Minions.dRiver("%s", "[METROPOLIS-CREEK-FILL-REACH-09] rect=("+block.minX+","+block.minZ+".."+block.maxX+","+block.maxZ+") schematicFillEnabled=true");
            }
         }
         ContextType planned = layout.contextOf(originalBlock);
         if (planned != null) {
            district.assignContext(planned);
         }
         this.districtSet.add(district);
      }

      // Whatever the plan did not claim (it claims every chunk, so this is a safety net only) is plain ground.
      for (int z = minZ; z <= maxZ; z++) {
         for (int x = minX; x <= maxX; x++) {
            if (!claimed[(z - exMinZ) * w + (x - exMinX)] && this.getParcel(x, z) == null) {
               this.placeGroundFallback(x, z, "unclaimed-by-plan");
            }
         }
      }
      // Do NOT turn failed road wraps into enormous cleared forest districts.
      // The acceptance audit retains their rejection markers so the missing
      // roads remain visible while we preserve existing building placement.
      // No blanket parcel replacement: only explicitly creek-owned cells
      // may be naturalized by the ordinary shoreline ownership pass.
      return true;
   }

   /** Independent factual check of the ACTIVE WorldLayout districts, not RiverReclaim's unused accepted list.
    * This deliberately reports zero-accepted layouts as zero, not as a clean pass. */
   private void auditActiveCreekPlan() {
      if (this.creekCarriers.isEmpty()) return;
      int missingRing = 0, deadEnds = 0, creekNull = 0, neverFronted = 0, developed = 0;
      java.util.List<String> examples = new java.util.ArrayList<String>();
      java.util.Set<Long> seen = new java.util.HashSet<Long>();
      for (WorldLayout.Rect b : acceptedActiveCreekBlocks) {
         int missing = 0, occupied = 0, edgeWithoutRoad = 0;
         for (int x=b.minX-1; x<=b.maxX+1; x++) {
            int[] zs={b.minZ-1,b.maxZ+1};
            for (int z:zs) {
               if (x<this.root.X || x>this.root.X+63 || z<this.root.Y || z>this.root.Y+63) continue;
               Parcel p=this.peekParcel(x,z);
               if (!(p instanceof RoadParcel || p instanceof HighwayParcel || p instanceof BridgeParcel)) missing++;
            }
         }
         for (int z=b.minZ; z<=b.maxZ; z++) {
            int[] xs={b.minX-1,b.maxX+1};
            for (int x:xs) {
               if (x<this.root.X || x>this.root.X+63 || z<this.root.Y || z>this.root.Y+63) continue;
               Parcel p=this.peekParcel(x,z);
               if (!(p instanceof RoadParcel || p instanceof HighwayParcel || p instanceof BridgeParcel)) missing++;
            }
         }
         for (int z=Math.max(b.minZ,this.root.Y);z<=Math.min(b.maxZ,this.root.Y+63);z++)
            for (int x=Math.max(b.minX,this.root.X);x<=Math.min(b.maxX,this.root.X+63);x++) {
               Parcel p=this.peekParcel(x,z);
               if (p instanceof ClipboardParcel) occupied++;
               if (p instanceof ClipboardParcel && (x==b.minX || x==b.maxX || z==b.minZ || z==b.maxZ)) {
                  // A perimeter building is road-facing only when a road actually
                  // touches at least one exterior side; do not count interior lot walls.
                  boolean touches=(x==b.minX && creekRoadNeighbor(x-1,z)) || (x==b.maxX && creekRoadNeighbor(x+1,z))
                       || (z==b.minZ && creekRoadNeighbor(x,z-1)) || (z==b.maxZ && creekRoadNeighbor(x,z+1));
                  if (!touches) edgeWithoutRoad++;
               }
            }
         missingRing+=missing;
         neverFronted+=edgeWithoutRoad;
         developed+=occupied;
         if ((missing>0 || occupied==0 || edgeWithoutRoad>0) && examples.size()<16)
            examples.add("activeRect=("+b.minX+","+b.minZ+".."+b.maxX+","+b.maxZ+") missingRing="+missing+" buildCells="+occupied+" edgeUnfronted="+edgeWithoutRoad);
      }
      for (int z=this.root.Y; z<this.root.Y+64;z++) for(int x=this.root.X;x<this.root.X+64;x++) {
         Parcel p=this.peekParcel(x,z);
         boolean near=false;
         for(int dz=-10;dz<=10 && !near;dz++) for(int dx=-10;dx<=10;dx++) {
            if (Math.abs(dx)+Math.abs(dz)>10) continue;
            Parcel q=this.peekParcel(x+dx,z+dz);
            if (q instanceof RiverParcel && this.creekCarriers.contains(((RiverParcel)q).getCarrier())
                    && ((RiverParcel)q).getCarrier().isRiver(x+dx,z+dz)) { near=true;break; }
         }
         if (!near) continue;
         if (p==null) { creekNull++; if(examples.size()<16) examples.add("creekNull=("+x+","+z+")"); }
         if (p instanceof RoadParcel && this.roadProblem(p,x,z)!=null && this.roadProblem(p,x,z).startsWith("dead end")) {
            deadEnds++; if(examples.size()<16) examples.add("creekRoadDeadEnd=("+x+","+z+")");
         }
      }
      Minions.dRiver("%s", "[METROPOLIS-CREEK-ACTIVE-AUDIT-04] tile=("+this.root.X+","+this.root.Y+") accepted="+acceptedActiveCreekBlocks.size()
        +" rejected="+rejectedActiveCreekBlocks.size()+" missingRingCells="+missingRing+" acceptedBuildCells="+developed
        +" edgeUnfronted="+neverFronted+" nearCreekNull="+creekNull+" nearCreekDeadEnds="+deadEnds);
      for(String item:examples) Minions.dRiver("%s", "[METROPOLIS-CREEK-ACTIVE-AUDIT-04] "+item);
   }

   /** Suppress only unmistakable orphan street pieces in the creek strip.
    * Preserve creek bridges and their banks, and never change city streets
    * outside creek-sensitive land. The decision depends on the world plan. */
   private boolean creekOrphanPlanStreet(WorldLayout layout, WaterField water, int x, int z) {
      boolean nearCreek = false, nearWet = false;
      for (int dz=-6;dz<=6;dz++) for (int dx=-6;dx<=6;dx++) {
         WaterField.Kind k=water.kindAt(x+dx,z+dz);
         if (k==WaterField.Kind.CREEK || water.zoneKind(x+dx,z+dz)==WaterField.Kind.CREEK) nearCreek=true;
         if (Math.abs(dx)<=2 && Math.abs(dz)<=2 && k==WaterField.Kind.CREEK) nearWet=true;
      }
      if (!nearCreek || nearWet) return false;
      for (BoundedRiverSites.Site site : this.boundedSitesOf.values())
         if (x>=site.lotBaseX-5 && x<=site.lotBaseX+site.lotSizeX+4
                 && z>=site.lotBaseZ-5 && z<=site.lotBaseZ+site.lotSizeZ+4) return false;
      int[][] offsets={{-1,0},{1,0},{0,-1},{0,1}};
      for (int[] d : offsets) {
         WorldLayout.Rect b=layout.blockAt(x+d[0],z+d[1]);
         if (b!=null && (!creekSensitivePlanBlock(water,b)
                 || creekOriginPlanBlock(b)
                 || creekPlanBlockFullyWrapped(layout,water,b))) return false;
      }
      return true;
   }

   /** A block that actually approaches creek water or its reserved bank. */
   private boolean creekSensitivePlanBlock(WaterField water, WorldLayout.Rect b) {
      for (int z=b.minZ-2; z<=b.maxZ+2;z++)
         for (int x=b.minX-2; x<=b.maxX+2;x++)
            if (water.kindAt(x,z)==WaterField.Kind.CREEK || water.zoneKind(x,z)==WaterField.Kind.CREEK) return true;
      return false;
   }

   /** Never reject the explicitly authored creek-source schematic. */
   private boolean creekOriginPlanBlock(WorldLayout.Rect b) {
      for (BoundedRiverSites.Site site : this.boundedSitesOf.values()) {
         int endX = site.lotBaseX + site.lotSizeX - 1;
         int endZ = site.lotBaseZ + site.lotSizeZ - 1;
         if (b.minX <= site.lotBaseX && b.maxX >= endX
                 && b.minZ <= site.lotBaseZ && b.maxZ >= endZ) return true;
      }
      return false;
   }

   /** All four sides must be a complete road ring, not one road per side.
    * No creeks, shoreline, or null plan cells are accepted as road frontage. */
   private boolean creekPlanBlockFullyWrapped(WorldLayout layout, WaterField water, WorldLayout.Rect b) {
      for (int x=b.minX-1;x<=b.maxX+1;x++) {
         if (!creekPlannedDryRoad(layout,water,x,b.minZ-1)
                 || !creekPlannedDryRoad(layout,water,x,b.maxZ+1)) return false;
      }
      for (int z=b.minZ;z<=b.maxZ;z++) {
         if (!creekPlannedDryRoad(layout,water,b.minX-1,z)
                 || !creekPlannedDryRoad(layout,water,b.maxX+1,z)) return false;
      }
      return true;
   }

   /** A district wrapper may reuse an existing highway culvert or its
    * explicitly reserved park-outlet bridge. Both are real road structures,
    * even though water runs underneath them. Ordinary wet STREET cells are
    * still rejected until a complete crossing has been proven; we must not
    * pretend every creek/road overlap is a valid bridge. */
   public static boolean legitimateCreekFrontageRoad(int plannedKind,
           WaterField.Kind waterKind, int originBridgeAxis) {
      if (plannedKind != WorldLayout.STREET && plannedKind != WorldLayout.LANE) return false;
      if (waterKind == WaterField.Kind.OCEAN) return false;
      if (plannedKind == WorldLayout.LANE) return true; // highway culvert keeps its lanes
      if (waterKind == WaterField.Kind.NONE) return true;
      return waterKind == WaterField.Kind.CREEK && originBridgeAxis != 0;
   }

   private boolean creekPlannedDryRoad(WorldLayout layout, WaterField water, int x, int z) {
      return legitimateCreekFrontageRoad(layout.kindAt(x,z), water.kindAt(x,z),
              layout.creekOutletRingAxisAt(x,z));
   }

   /**
    * Fills one city block with buildings, deciding them from the whole block and nothing else: its dice are seeded from the
    * world seed and the block's rectangle, its ground is the block's own chunks, and the block is never cut where a tile happens
    * to end. This tile then keeps the chunks of each building that fall inside it. A building that straddles the tile edge is
    * therefore the same building in both tiles; only the tile holding its base chunk pastes it (populate() acts on the base
    * chunk alone), and the other tile's copy is marked as already handled.
    *
    * Not used (the district fills itself, as it always has) when extraCutMode is on, which re-cuts districts with fresh road
    * splits, or when the plan has no block at the district's base.
    *
    * @return false when this path does not apply
    */
   /** Observe committed streets after new creek-road reservations. */
   private boolean creekRoadOnEdge(int from,int through,int fixed,boolean alongX) {
      for(int i=from;i<=through;i++) {
         int x=alongX?i:fixed,z=alongX?fixed:i;
         Parcel p=this.peekParcel(x,z);
         if(p instanceof RoadParcel || p instanceof HighwayParcel || p instanceof BridgeParcel) return true;
      }
      return false;
   }

   /** Coarse district-edge hint; individual schematics still require all
    * frontage chunks to face real streets in District.hasRealRoadFrontage(). */
   private static boolean plannedRoadOnEdge(WorldLayout layout, int from, int through, int fixed, boolean alongX) {
      for (int i = from; i <= through; i++) {
         if (alongX ? layout.isCityRoadCell(i, fixed) : layout.isCityRoadCell(fixed, i)) return true;
      }
      return false;
   }

   private boolean fillBlockWhole(IDistrict d) {
      if (!(d instanceof District) || PluginConfig.isExtraCutModeEnabled()) {
         return false;
      }
      long seed = this.generator.getWorldSeed();
      WorldLayout layout = WorldLayouts.forSeed(seed);
      if (layout == null) {
         return false;
      }
      WorldLayout.Rect block = layout.blockAt(d.getBase().X, d.getBase().Y);
      if (block == null) {
         return false;
      }
      int tx0 = this.root.X;
      int tz0 = this.root.Y;
      if (creekOriginPlanBlock(block))
         Minions.dRiver("%s", "[METROPOLIS-CREEK-ORIGIN-PROBE-05] fill originBlock=("+block.minX+","+block.minZ+".."+block.maxX+","+block.maxZ+") landmark="+(layout.landmarkAt(block)!=null)+" placements="+BlockPlans.planFor(this,seed,layout,block).size());
      for (BlockPlans.Placement plan : BlockPlans.planFor(this, seed, layout, block)) {
         ClipboardParcel p = plan.toParcel(this);
         int placed = 0;
         for (int x = p.getChunkX(); x < p.getChunkX() + p.getChunkSizeX(); x++) {
            for (int z = p.getChunkZ(); z < p.getChunkZ() + p.getChunkSizeZ(); z++) {
               if (x >= tx0 && x < tx0 + 64 && z >= tz0 && z < tz0 + 64 && this.getParcel(x, z) == null) {
                  this.setParcel(x, z, p);
                  placed++;
               }
            }
         }
         boolean owned = p.getChunkX() >= tx0 && p.getChunkX() < tx0 + 64 && p.getChunkZ() >= tz0 && p.getChunkZ() < tz0 + 64;
         if (placed > 0 && !owned) {
            p.markHandledByOwner();
         }
      }
      // An empty chunk is now a valid outcome when no road-facing BUILD fits;
      // it retains underlying terrain instead of receiving a forced filler.
      int bare = 0;
      for (int x = Math.max(block.minX, tx0); x <= Math.min(block.maxX, tx0 + 63); x++) {
         for (int z = Math.max(block.minZ, tz0); z <= Math.min(block.maxZ, tz0 + 63); z++) {
            if (this.getParcel(x, z) == null) {
               bare++;
            }
         }
      }
      if (bare > 0) {
         Minions.dDistrict("block [%d,%d..%d,%d]: %d chunk(s) deliberately left as natural ground (tile %d,%d)",
                 block.minX, block.minZ, block.maxX, block.maxZ, bare, tx0, tz0);
      }
      return true;
   }

   /**
    * Carves a rectangle of leftover land around a highway band that runs through it. The band's own cells are
    * already highway lanes. The line through the band's first row (or, for a band running north-south, first
    * column) is cut across the whole rectangle: the cells of it that are not lanes become a street -- the highway
    * carries on as a street to the edge of the rectangle, which is a highway, the tile edge street or another
    * street, so it never dead-ends -- and the land on either side of the cut goes back to placeLeftoverLand(),
    * which cuts again for any further band. Splitting the rectangle in four around the band instead leaves the
    * districts either side of a band's end touching with no road between them.
    */
   private void placeLeftoverAroundBand(HighwayNetwork.Rect band, int minX, int minZ, int maxX, int maxZ) {
      int bx0 = Math.max(minX, band.minX);
      int bx1 = Math.min(maxX, band.maxX);
      int bz0 = Math.max(minZ, band.minZ);
      int bz1 = Math.min(maxZ, band.maxZ);
      if (bx1 - bx0 >= bz1 - bz0) {
         for (int x = minX; x <= maxX; x++) {
            this.paintBandSeam(x, bz0);
         }
         this.placeBandPiece(minX, minZ, maxX, bz0 - 1);
         this.placeBandPiece(minX, bz0 + 1, maxX, maxZ);
      } else {
         for (int z = minZ; z <= maxZ; z++) {
            this.paintBandSeam(bx0, z);
         }
         this.placeBandPiece(minX, minZ, bx0 - 1, maxZ);
         this.placeBandPiece(bx0 + 1, minZ, maxX, maxZ);
      }
   }

   /** A street on a free cell of the cut line; lanes, and anything already placed, stay as they are. */
   private void paintBandSeam(int x, int z) {
      if (this.getParcel(x, z) == null) {
         this.setParcel(x, z, new RoadParcel(this, new Cartesian2D(x, z)));
      }
   }

   /** Chunks that wanted plain ground but had nowhere to go because generator.enableFillerTerrainFallback is off. */
   private int groundFallbackSuppressed = 0;
   // Shared natural owner for any creek-side ground that cannot be reclaimed.
   private ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion creekNaturalGround;

   private void assignCreekNaturalGround(int x, int z) {
      // Never disguise an unresolved BUILD district as wilderness. The
      // world plan owns these chunks regardless of whether the schematic
      // packing pass succeeds. Naturalization is for genuinely non-city
      // creek/water/shore ground only, not failed residential infill.
      WorldLayout layout = WorldLayouts.forSeed(this.generator.getWorldSeed());
      if (layout != null && layout.kindAt(x, z) == WorldLayout.BLOCK) return;
      Parcel existing = this.peekParcel(x, z);
      if (existing != null) {
         // A rejected reclaim candidate can still retain its preliminary creek
         // RiverParcel. This is *dry* land, not part of the working bank or water.
         // Null-only cleanup missed it, resulting in bare rectangular strips.
         if (!(existing instanceof RiverParcel)) return;
         RiverProvider owner = ((RiverParcel) existing).getCarrier();
         if (owner == null || owner.isRiverOrBank(x, z)) return;
         // R30ea: a dry preliminary river parcel can still be the planned
         // *same creek* road-bank handoff. Do not naturalize that owner as
         // wilderness just because its center column isn't wet. The carrier
         // lookup uses the accepted bounded sites; never the retired creek path.
         WaterField water = WorldLayouts.waterFor(this.generator.getWorldSeed());
         ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
         if (layout != null && water != null && shapes != null
                 && this.plannedCreekRoadBankCarrier(x, z, layout, water, shapes.regions()) == owner) {
            return;
         }
      }
      if (this.inLotFootprint(x, z) || this.isInsideOceanCarve(x, z)) return;
      if (this.creekNaturalGround == null) {
         this.creekNaturalGround = new ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion(
                 (this.root.X * 31) ^ this.root.Y ^ 0x6c37, ContextType.FOREST);
      }
      this.creekNaturalGround.addRect(x, z, x, z);
      this.setParcel(x, z, new MorphomosParcel(new Cartesian2D(x,z),
              ContextType.FOREST, this, this.creekNaturalGround));
   }


   /**
    * The single gate for every "nothing else can take this chunk" case that used to build a TerrainFallbackParcel
    * unconditionally. generator.enableFillerTerrainFallback is honoured here exactly as District.fillDistrict() honours it:
    * when it is off, NO TerrainFallbackParcel is created and the chunk is left as the plan / earlier passes left it (it is
    * not overwritten either, which the morphomos pass used to do). Each suppressed chunk is reported with the site that
    * wanted it, so a leak in the layout shows up as a warning instead of being quietly painted over as grass.
    */
   /** One-way ownership transfer after real districts and roads have been painted.
    * It records the existing WorldLayout's decisions: it never creates a new
    * parcel or turns an unsuccessful city parcel into a forest parcel. */
   private void commitBoundedDryHandoff() {
      if (this.boundedSitesOf.isEmpty()) return;
      WorldLayout layout=WorldLayouts.forSeed(this.generator.getWorldSeed());
      if(layout==null) {
         Minions.w("[R14-BOUNDED-DRY-HANDOFF] missing layout tile=(%d,%d)",this.root.X,this.root.Y);
         return;
      }
      PlanRegions regions=new PlanRegions(layout);
      int city=0,road=0,natural=0,missingCity=0,missingRoad=0;
      for(BoundedRiverSites.Site site:this.boundedSitesOf.values()) {
         for(int z=this.root.Y;z<this.root.Y+64;z++) for(int x=this.root.X;x<this.root.X+64;x++) {
            if(site.ownership.at(x,z)!=BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE)continue;
            int kind=layout.kindAt(x,z);
            boolean dissolved=kind==WorldLayout.STREET && regions.dissolvedAt(x,z);
            BoundedRiverOwnershipPlan.Owner owner=BoundedRiverDryHandoff.resolveCell(
                    BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE,kind,layout.contextAt(x,z),dissolved);
            if(owner==BoundedRiverOwnershipPlan.Owner.CITY || owner==BoundedRiverOwnershipPlan.Owner.CITY_ROAD)
               this.boundedCommittedDry.put(cellKey(x,z),owner);
            Parcel existing=this.peekParcel(x,z);
            if(owner==BoundedRiverOwnershipPlan.Owner.CITY) {
               city++;
               if(!(existing instanceof ClipboardParcel))missingCity++;
            } else if(owner==BoundedRiverOwnershipPlan.Owner.CITY_ROAD) {
               road++;
               if(!(existing instanceof StreetParcel))missingRoad++;
            } else natural++;
            if(this.boundedFailureTrace!=null) {
               this.boundedFailureTrace.commit(x,z,owner,"layoutKind="+kind+" dissolved="+dissolved,
                       existing==null?"null":existing.getClass().getSimpleName());
            }
         }
      }
      if(missingCity>0 || missingRoad>0)
         Minions.w("[R14-BOUNDED-DRY-HANDOFF] PAINT_PLAN_PARITY_FAIL tile=(%d,%d) cityPlanned=%d missing=%d roadPlanned=%d missing=%d -- not substituting wilderness",
                 this.root.X,this.root.Y,city,missingCity,road,missingRoad);
      if(PluginConfig.isCreekPaintLifecycleDebugEnabled())
         Minions.w("[R14-BOUNDED-DRY-HANDOFF] tile=(%d,%d) city=%d roads=%d natural=%d unfilledCity=%d unpaintedRoad=%d",
                 this.root.X,this.root.Y,city,road,natural,missingCity,missingRoad);
   }

   // One contiguous local owner for the explicitly natural, between-river terrain.
   // This is not the creek bank and never masks a planned street or city block.
   private MorphomosRegion interfluveNaturalGround;

   private void assignPlannedInterfluveGround(int x,int z) {
      WorldLayout layout=WorldLayouts.forSeed(this.generator.getWorldSeed());
      if(layout==null || layout.kindAt(x,z)!=WorldLayout.NATURAL ||
              this.peekParcel(x,z)!=null || this.inLotFootprint(x,z))return;
      if(this.interfluveNaturalGround==null)
         this.interfluveNaturalGround=new MorphomosRegion(
                 (this.root.X*31)^this.root.Y^1231975526,ContextType.FOREST);
      this.interfluveNaturalGround.addRect(x,z,x,z);
      this.setParcel(x,z,new MorphomosParcel(new Cartesian2D(x,z),ContextType.FOREST,
              this,this.interfluveNaturalGround));
   }

   /** True for exactly the two natural-region owners allocated by this grid. */
   public boolean isCreekNaturalForestRegion(MorphomosRegion region) {
      return region != null && (region == this.creekNaturalGround || region == this.interfluveNaturalGround);
   }

   /** Is this an actually reserved dry-bank cell in a natural (not city) region? */
   public boolean isPlannedCreekNaturalForestAt(int x,int z) {
      BoundedRiverSites.Site site=boundedSitesFor(this.generator).siteAt(x,z);
      if(site==null || site.ownership.at(x,z)!=BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE)
         return false;
      WorldLayout layout=WorldLayouts.forSeed(this.generator.getWorldSeed());
      if(layout==null)return false;
      int kind=layout.kindAt(x,z);
      return BoundedRiverDryHandoff.plannedUse(kind,layout.contextAt(x,z),
             kind==WorldLayout.STREET && new PlanRegions(layout).dissolvedAt(x,z))
             ==BoundedRiverOwnershipPlan.DryUse.NATURAL_BANK;
   }



   /**
    * The single gate for every "nothing else can take this chunk" case that used to build a TerrainFallbackParcel
    * unconditionally. generator.enableFillerTerrainFallback is honoured here exactly as District.fillDistrict() honours it:
    * when it is off, NO TerrainFallbackParcel is created and the chunk is left as the plan / earlier passes left it (it is
    * not overwritten either, which the morphomos pass used to do). Each suppressed chunk is reported with the site that
    * wanted it, so a leak in the layout shows up as a warning instead of being quietly painted over as grass.
    */
   private void auditUnresolvedCreekOwnership() {
      if (this.creekBands.isEmpty()) return;
      WorldLayout layout = WorldLayouts.forSeed(this.generator.getWorldSeed());
      int nullCities = 0, nullWater = 0;
      for (int z = this.root.Y; z < this.root.Y + 64; z++) {
         for (int x = this.root.X; x < this.root.X + 64; x++) {
            if (this.peekParcel(x, z) != null) continue;
            int kind = layout.kindAt(x, z);
            if (kind == WorldLayout.BLOCK || kind == WorldLayout.STREET) nullCities++;
            if (WorldLayouts.waterFor(this.generator.getWorldSeed()).kindAt(x, z) == WaterField.Kind.CREEK) nullWater++;
         }
      }
      if (nullCities > 0 || nullWater > 0)
         Minions.w("CREEK PLAN UNFILLED tile=("+this.root.X+","+this.root.Y+") cityBlockOrStreet="+nullCities+" creekCells="+nullWater+"; no post-plan forest substitution");
   }

   private void placeGroundFallback(int x, int z, String site) {
      // Creek-side ground is nature, not a filler schematic, regardless of
      // enableFillerTerrainFallback. It must never be left unassigned.
      if (!this.creekBands.isEmpty()) {
         for (CreekBand band : this.creekBands) {
            int along = band.alongZ ? z : x;
            int across = band.alongZ ? x : z;
            if (along >= band.alongLo - 2 && along <= band.alongHi + 2
                    && across >= band.acrossLo - 3 && across <= band.acrossHi + 3
                    && !this.inLotFootprint(x, z)) {
               Parcel old = this.peekParcel(x, z);
               if (old == null || old instanceof RiverParcel
                       && ((RiverParcel)old).getCarrier() == band.creek
                       && !band.creek.isRiverOrBank(x, z)) {
                  this.assignCreekNaturalGround(x, z);
                  return;
               }
            }
         }
      }
      if (PluginConfig.isFillerTerrainFallbackEnabled()) {
         this.setParcel(x, z, new TerrainFallbackParcel(new Cartesian2D(x, z), ContextType.UNDEFINED, this));
         return;
      }
      // This site belongs to neither a planned block nor a street; reclaim
      // it as wilderness when the creek influences this tile. This is NOT
      // TerrainFallbackParcel and preserves the user's fallback=false policy.
      if (!this.creekBands.isEmpty() && this.peekParcel(x, z) == null && !this.inLotFootprint(x,z)) {
         this.assignCreekNaturalGround(x,z);
         return;
      }
      this.groundFallbackSuppressed++;
      Minions.w("TERRAIN FALLBACK SUPPRESSED site=%s chunk=(%d,%d) existingParcel=%s -- generator.enableFillerTerrainFallback is off",
              site, x, z, this.peekParcel(x, z) == null ? "none" : this.peekParcel(x, z).getClass().getSimpleName());
   }

   /** Is a highway lane directly beside this cell? */
   private boolean touchesLane(int x, int z) {
      return this.peekParcel(x + 1, z) instanceof HighwayParcel || this.peekParcel(x - 1, z) instanceof HighwayParcel
              || this.peekParcel(x, z + 1) instanceof HighwayParcel || this.peekParcel(x, z - 1) instanceof HighwayParcel;
   }

   private void placeBandPiece(int minX, int minZ, int maxX, int maxZ) {
      if (minX > maxX || minZ > maxZ) {
         return;
      }
      int sizeX = maxX - minX + 1;
      int sizeZ = maxZ - minZ + 1;
      if (sizeX < 3 || sizeZ < 3) {
         // Too thin for a district. Filling it with street would run one or two chunks of road alongside the lane
         // next to it -- a doubled highway -- so a cell that touches a lane is left as plain ground, and only a cell
         // that does not (so a strip that carries traffic between two streets) is a street.
         for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
               if (this.getParcel(x, z) != null) {
                  continue;
               }
               if (this.touchesLane(x, z)) {
                  this.placeGroundFallback(x, z, "thin-strip-beside-lane");
               } else {
                  this.setParcel(x, z, new RoadParcel(this, new Cartesian2D(x, z)));
               }
            }
         }
         return;
      }
      this.placeLeftoverLand(new Cartesian2D(minX, minZ), new Cartesian2D(sizeX, sizeZ));
   }

   @Override
   public void populate(Chunk chunk) {
      // Match R30ea: observe the same parcel before and after actual painting.
      // With debug disabled, the hook exits without reading a single block.
      this.probeBoundedChunk(chunk, ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit.Stage.POPULATE_BEFORE);
      Parcel p = this.getParcel(chunk.getX(), chunk.getZ());
      if (p != null) {
         p.populate(this.generator, chunk);
         this.probeBoundedChunk(chunk, ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit.Stage.POPULATE_AFTER);
         BranchDamageWatch.scanNearby(chunk.getWorld(), chunk.getX(), chunk.getZ(),
                 "AFTER populate " + p.getClass().getSimpleName() + " parcel@"
                 + p.getChunkX() + "," + p.getChunkZ());
      } else {
         Minions.w("Parcel not found");
      }
   }

   @Override
   public void postPopulate(Chunk chunk) {
      this.probeBoundedChunk(chunk, ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit.Stage.POST_BEFORE);
      Parcel p = this.getParcel(chunk.getX(), chunk.getZ());
      if (p != null) {
         p.postPopulate(this.generator, chunk);
         this.probeBoundedChunk(chunk, ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit.Stage.POST_AFTER);
         // Check only already-loaded neighbours which have a post-populated
         // fingerprint; do not generate new chunks to perform the audit.
         this.probeBoundedSurvivingNeighbours(chunk);
         BranchDamageWatch.scanNearby(chunk.getWorld(), chunk.getX(), chunk.getZ(),
                 "AFTER postPopulate " + p.getClass().getSimpleName() + " parcel@"
                 + p.getChunkX() + "," + p.getChunkZ());
      }
   }

   /** R30ea's fixed diagnostic sampling. Debug-only, not a painting rule. */
   private static long boundedSurfaceHash(Chunk chunk) {
      long hash = -3750763034362895579L;
      int minY = Math.max(chunk.getWorld().getMinHeight(), 54);
      int maxY = Math.min(chunk.getWorld().getMaxHeight() - 1, 70);
      int[] samples = {1, 4, 8, 12, 15};
      for (int x : samples) {
         for (int z : samples) {
            for (int y = minY; y <= maxY; y += 2) {
               hash ^= (long) chunk.getBlock(x, y, z).getType().ordinal() + 1L;
               hash *= 1099511628211L;
            }
         }
      }
      return hash;
   }

   /** Exact 225-element sampled material signature used by the R30ea audit. */
   private static int[] boundedSurfaceMaterials(Chunk chunk) {
      int minY = Math.max(chunk.getWorld().getMinHeight(), 54);
      int[] result = new int[225];
      int i = 0;
      int[] samples = {1, 4, 8, 12, 15};
      for (int x : samples) {
         for (int z : samples) {
            for (int level = 0; level < 9; level++) {
               int y = minY + 2 * level;
               result[i++] = y >= chunk.getWorld().getMaxHeight()
                       ? -1 : chunk.getBlock(x, y, z).getType().ordinal();
            }
         }
      }
      return result;
   }

   /** Physical lifecycle evidence is separate from the one-way dry-owner guard. */
   private void probeBoundedChunk(Chunk chunk,
          ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit.Stage stage) {
      final int x = chunk.getX(), z = chunk.getZ();
      boolean trace = this.boundedFailureTrace != null && this.boundedFailureTrace.tracks(x, z);
      boolean physical = this.boundedPaintAudit != null && this.boundedPaintAudit.tracks(x, z);
      if (!trace && !physical) return;
      long fingerprint = this.boundedPaintAudit == null ? 0L : boundedSurfaceHash(chunk);
      if (trace) {
         this.boundedFailureTrace.population(x, z, stage.name(), parcelName(this.peekParcel(x, z)), 0L);
      }
      if (physical) this.boundedPaintAudit.stage(x, z, stage, fingerprint);
      if (stage != ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit.Stage.POST_AFTER) return;
      // Read-only terrain evidence; do not rewrite soil or vegetation while diagnosing.
      int water = 0, soil = 0, plants = 0, structure = 0;
      int minY = Math.max(chunk.getWorld().getMinHeight(), 52);
      int maxY = Math.min(chunk.getWorld().getMaxHeight() - 1, 84);
      int[] samples = {3, 8, 12};
      for (int sx : samples) for (int sz : samples) for (int y = minY; y <= maxY; y++) {
         String type = chunk.getBlock(sx, y, sz).getType().name();
         if (type.equals("WATER")) water++;
         else if (type.equals("GRASS_BLOCK") || type.equals("DIRT") || type.equals("SAND")
                  || type.equals("GRAVEL") || type.equals("COARSE_DIRT") || type.equals("ROOTED_DIRT")) soil++;
         else if (type.contains("LEAVES") || type.endsWith("_LOG") || type.contains("FERN")
                  || type.endsWith("_GRASS") || type.equals("VINE") || type.contains("FLOWER")
                  || type.equals("DANDELION") || type.equals("POPPY") || type.contains("SAPLING")) plants++;
         else if (type.contains("CONCRETE") || type.contains("BRICKS") || type.contains("PLANKS")) structure++;
      }
      if (this.boundedPaintAudit != null) {
         this.boundedPaintAudit.terrainEvidence(x, z, water, soil, plants, structure);
      }
   }

   /** Recheck final samples within this tile without synchronously generating neighbours. */
   private void probeBoundedSurvivingNeighbours(Chunk chunk) {
      if (this.boundedPaintAudit == null) return;
      for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
         int x = chunk.getX() + dx, z = chunk.getZ() + dz;
         if (!chunk.getWorld().isChunkLoaded(x, z)) continue;
         if (!this.boundedPaintAudit.hasFinalSample(x, z)) continue;
         Chunk neighbour = chunk.getWorld().getChunkAt(x, z);
         this.boundedPaintAudit.verifySurvival(x, z, boundedSurfaceHash(neighbour), chunk.getX(), chunk.getZ());
      }
   }

   /**
    * Read-only final-owner audit. Runs after district population, ports and the
    * bridge pass, so it reports actual parcel ownership rather than the initial
    * placement decision. It does not solve a second layout or mutate parcels.
    */
   private void auditOceanFinalOwnerParity() {
      if (!PluginConfig.isOceanDebugEnabled()) return;
      WorldLayout layout=WorldLayouts.forSeed(this.generator.getWorldSeed());
      WaterField water=WorldLayouts.waterFor(this.generator.getWorldSeed());
      if (layout==null || water==null) return;
      int[] totals=new int[OceanOwnerParityAudit.Result.values().length];
      int unusual=0;
      int printed=0;
      for (int z=this.root.Y;z<this.root.Y+64;z++) {
         for (int x=this.root.X;x<this.root.X+64;x++) {
            Parcel parcel=this.getParcel(x,z);
            OceanOwnerParityAudit.Result result=OceanOwnerParityAudit.classify(
                  parcel instanceof OceanParcel,water.kindAt(x,z),
                  water.zoneKind(x,z),layout.kindAt(x,z));
            totals[result.ordinal()]++;
            if (!OceanOwnerParityAudit.isUnexpected(result)) continue;
            unusual++;
            if (printed++<32)
               Minions.dOcean("[R14-OCEAN-FINAL] UNEXPECTED chunk=(%d,%d) result=%s parcel=%s planned=%d water=%s lattice=%s context=%s",
                     x,z,result,parcel==null?"null":parcel.getClass().getSimpleName(),
                     layout.kindAt(x,z),water.kindAt(x,z),water.zoneKind(x,z),layout.contextAt(x,z));
         }
      }
      Minions.dOcean("[R14-OCEAN-FINAL] tile=(%d,%d) actualOcean=%d latticeOnly=%d borrowedZone=%d borrowedRoad=%d borrowedBuilding=%d borrowedOther=%d crossWater=%d unexpected=%d%s",
            this.root.X,this.root.Y,
            totals[OceanOwnerParityAudit.Result.ACTUAL_OCEAN.ordinal()],
            totals[OceanOwnerParityAudit.Result.LATTICE_ONLY_SHORE.ordinal()],
            totals[OceanOwnerParityAudit.Result.BORROWED_SHORE_ZONE.ordinal()],
            totals[OceanOwnerParityAudit.Result.BORROWED_OVER_PLANNED_ROAD.ordinal()],
            totals[OceanOwnerParityAudit.Result.BORROWED_OVER_PLANNED_BUILDING.ordinal()],
            totals[OceanOwnerParityAudit.Result.BORROWED_OTHER.ordinal()],
            totals[OceanOwnerParityAudit.Result.CROSS_WATER_OWNER.ordinal()],
            unusual,printed>32?" (first 32 examples shown)":"");
   }

   public Parcel getParcel(int chunkX, int chunkZ) {
      int x = getChunkOffset(chunkX);
      int z = getChunkOffset(chunkZ);
      return this.parcels[x][z];
   }

   /**
    * True only when a karst span's complete safety footprint is backed by
    * populated forest Morphomos parcels belonging to the exact same world
    * region. A span must not bridge over EmptyParcel, fallback, road, water,
    * city or another region cells; it is refused rather than carving into
    * terrain owned by another parcel.
    */
   public boolean isKarstBridgeFootprintOwned(MorphomosRegion owner,int centerChunkX,int centerChunkZ,int radiusChunks) {
      if(owner==null || owner.getContext()!=ContextType.FOREST || radiusChunks<0) return false;
      int minX=this.root.X, minZ=this.root.Y, maxX=minX+64, maxZ=minZ+64;
      for(int dx=-radiusChunks;dx<=radiusChunks;dx++) {
         for(int dz=-radiusChunks;dz<=radiusChunks;dz++) {
            int cx=centerChunkX+dx, cz=centerChunkZ+dz;
            if(cx<minX || cx>=maxX || cz<minZ || cz>=maxZ) return false;
            Parcel parcel=this.getParcel(cx,cz);
            if(!(parcel instanceof MorphomosParcel morph)
                    || morph.getContextType()!=ContextType.FOREST
                    || morph.getRegion()!=owner) return false;
         }
      }
      return true;
   }

   /** R30ea trace label for a parcel writer; read only for debug diagnostics. */
   private static String parcelName(Parcel parcel) {
      return parcel == null ? "null" : parcel.getClass().getSimpleName();
   }

   private static String parcelWriter() {
      for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
         String method = frame.getMethodName();
         if ("getStackTrace".equals(method) || "parcelWriter".equals(method) || "setParcel".equals(method)) continue;
         String owner = frame.getClassName();
         int dot = owner.lastIndexOf('.');
         if (dot >= 0) owner = owner.substring(dot + 1);
         return owner + "." + method + ":" + frame.getLineNumber();
      }
      return "unknown";
   }

   /** Once planning has handed a dry creek site to city/road ownership, no
    * later natural/water/fallback pass may overwrite that committed parcel.
    * This guard matches R30ea's existing setter rather than adding a second
    * competing ownership registry. */
   public void setParcel(int chunkX, int chunkZ, Parcel parcel) {
      // Central ownership gate: once a street/highway/accepted creek ring has
      // an actual road parcel, later forest, creek, ocean or building painters
      // cannot take it away.  A Morphomos street intentionally dissolved by
      // PlanRegions does NOT meet the plannedStreet test and remains eligible
      // for the normal same-context farmland/forest handoff.
      Parcel previous = this.peekParcel(chunkX, chunkZ);
      if (previous instanceof StreetParcel && !(parcel instanceof StreetParcel)) {
         boolean creekOwned = this.creekPerimeterCells.contains(cellKey(chunkX,chunkZ))
                 || this.creekApproachCells.contains(cellKey(chunkX,chunkZ));
         boolean highwayOwned = previous instanceof HighwayParcel;
         ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes planned = this.roadShapes();
         boolean layoutOwned = planned != null && planned.isRoadStreet(chunkX,chunkZ);
         if (ch.k42.metropolis.grid.urbanGrid.layout.RoadParcelOwnership.rejectReplacement(
                 true, false, layoutOwned, creekOwned, highwayOwned)) {
            Minions.w("ROAD OWNERSHIP VIOLATION chunk=(%d,%d) planStreet=%s creekRoad=%s highway=%s from=%s to=%s writer=%s -- rejected",
                    chunkX,chunkZ,layoutOwned,creekOwned,highwayOwned,
                    parcelName(previous),parcelName(parcel),parcelWriter());
            return;
         }
      }
      BoundedRiverOwnershipPlan.Owner finalOwner = this.boundedCommittedDry.get(cellKey(chunkX,chunkZ));
      if (finalOwner == BoundedRiverOwnershipPlan.Owner.CITY ||
              finalOwner == BoundedRiverOwnershipPlan.Owner.CITY_ROAD) {
         if (parcel instanceof RiverParcel || parcel instanceof OceanParcel ||
                 parcel instanceof MorphomosParcel || parcel instanceof TerrainFallbackParcel) {
            Minions.w("[R14-BOUNDED-DRY-HANDOFF] REJECT_LATE_TERRAIN chunk=(%d,%d) final=%s attempted=%s",
                    chunkX, chunkZ, finalOwner, parcel.getClass().getSimpleName());
            if (this.boundedFailureTrace != null) {
               this.boundedFailureTrace.refused(chunkX,chunkZ,parcelName(this.getParcel(chunkX,chunkZ)),
                       parcelName(parcel),parcelWriter());
               if (parcel instanceof MorphomosParcel && finalOwner == BoundedRiverOwnershipPlan.Owner.CITY_ROAD) {
                  ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
                  if (shapes != null && shapes.regions().dissolvedAt(chunkX,chunkZ)) {
                     this.boundedFailureTrace.morphomosBlockedByRoadGuard(chunkX,chunkZ,
                             parcelName(this.getParcel(chunkX,chunkZ)),parcelWriter());
                  }
               }
            }
            return;
         }
      }
      int x = getChunkOffset(chunkX);
      int z = getChunkOffset(chunkZ);
      if (this.boundedFailureTrace != null && this.boundedFailureTrace.tracks(chunkX,chunkZ)) {
         Parcel existing = this.parcels[x][z];
         this.boundedFailureTrace.assignment(chunkX,chunkZ,parcelName(existing),parcelName(parcel),
                 parcelWriter(),existing != null && existing != parcel);
      }
      this.parcels[x][z] = parcel;
      if (this.boundedPaintAudit != null) {
         this.boundedPaintAudit.assignment(chunkX,chunkZ,parcelName(parcel));
      }
   }

   public void fillParcels(int chunkX, int chunkZ, Parcel parcel) {
      for (int x = 0; x < parcel.getChunkSizeX(); x++) {
         for (int z = 0; z < parcel.getChunkSizeZ(); z++) {
            this.setParcel(chunkX + x, chunkZ + z, parcel);
         }
      }
   }

   private static int getChunkOffset(int chunk) {
      int ret = chunk % 64;
      if (ret < 0) {
         ret += 64;
      }

      return ret;
   }

   public GridStatistics getStatistics() {
      return this.statistics;
   }

   public ContextProvider getContextProvider() {
      return this.contextProvider;
   }

   /**
    * Same job as recSetDistricts() -- hand a rectangle of this tile's
    * leftover land to ordinary district carving -- but first carves
    * AROUND this tile's own ocean square (this.oceanCarve*, set by
    * placeOceanIfPresent()) if the rectangle happens to overlap it. Every
    * recSetDistricts() call placeCityDistricts() used to make directly
    * now goes through here instead, which is what stops those calls from
    * blindly painting ordinary buildings straight over ocean cells on a
    * tile where the river's leftover land and the ocean's own square
    * overlap.
    *
    * Splits into the same up-to-four-rectangle shape placeOceanIfPresent()
    * itself uses to carve around its own square, then recurses -- each
    * piece produced by one split is, by construction, already clear of
    * the ocean square, so the recursion is always exactly one level deep
    * in practice (the top-of-method overlap check just makes that
    * explicit and gives the plain no-ocean case, which is by far the
    * common one, a single direct recSetDistricts() call with none of
    * this splitting overhead).
    */
   private void placeLeftoverLand(Cartesian2D areaBase, Cartesian2D areaSize) {
      if (areaSize.X <= 0 || areaSize.Y <= 0) {
         return;
      }
      int minX = areaBase.X;
      int maxX = areaBase.X + areaSize.X - 1;
      int minZ = areaBase.Y;
      int maxZ = areaBase.Y + areaSize.Y - 1;

      if (!this.rectOverlapsOceanCarve(minX, minZ, areaSize.X, areaSize.Y) && this.placeCityBlocks(minX, minZ, maxX, maxZ)) {
         return;
      }

      HighwayNetwork.Rect networkBand = this.firstNetworkBandOverlapping(minX, minZ, maxX, maxZ);
      if (networkBand != null) {
         this.placeLeftoverAroundBand(networkBand, minX, minZ, maxX, maxZ);
         return;
      }

      if (!this.rectOverlapsOceanCarve(minX, minZ, areaSize.X, areaSize.Y)) {
         this.recSetDistricts(areaBase, areaSize);
         return;
      }

      int carveMinX = Math.max(minX, this.oceanCarveMinX);
      int carveMaxX = Math.min(maxX, this.oceanCarveMaxX);
      int carveMinZ = Math.max(minZ, this.oceanCarveMinZ);
      int carveMaxZ = Math.min(maxZ, this.oceanCarveMaxZ);

      if (carveMinZ > minZ) {
         this.placeLeftoverLand(new Cartesian2D(minX, minZ), new Cartesian2D(areaSize.X, carveMinZ - minZ));
      }
      if (carveMaxZ < maxZ) {
         this.placeLeftoverLand(new Cartesian2D(minX, carveMaxZ + 1), new Cartesian2D(areaSize.X, maxZ - carveMaxZ));
      }
      // The west and east pieces sit between the north and south pieces, and
      // recSetDistricts() puts no road on the outer edge of what it's given, so
      // without a street here they'd sit flush against those two. The box's own
      // outer street (OceanReclaim's outermost lattice line) already runs along
      // carveMinZ/carveMaxZ inside the box; continue that same row out through
      // each side piece and hand the rest of the piece to district carving.
      // (carveMinZ > minZ can only be true when carveMinZ IS the box's own edge --
      // a tile-clipped edge would equal minZ -- so that row really is a street.)
      int sideMinZ = carveMinZ;
      int sideMaxZ = carveMaxZ;
      boolean streetAbove = carveMinZ > minZ;
      boolean streetBelow = carveMaxZ < maxZ;
      if (streetAbove) {
         sideMinZ++;
      }
      if (streetBelow) {
         sideMaxZ--;
      }
      if (carveMinX > minX) {
         this.paintOceanSeamRows(minX, carveMinX - 1, carveMinZ, carveMaxZ, streetAbove, streetBelow);
         this.placeLeftoverLand(new Cartesian2D(minX, sideMinZ), new Cartesian2D(carveMinX - minX, sideMaxZ - sideMinZ + 1));
      }
      if (carveMaxX < maxX) {
         this.paintOceanSeamRows(carveMaxX + 1, maxX, carveMinZ, carveMaxZ, streetAbove, streetBelow);
         this.placeLeftoverLand(new Cartesian2D(carveMaxX + 1, sideMinZ), new Cartesian2D(maxX - carveMaxX, sideMaxZ - sideMinZ + 1));
      }
   }

   /** Paints the one-chunk street rows that continue the ocean box's north/south edge streets across a side piece -- see placeLeftoverLand(). */
   private void paintOceanSeamRows(int fromX, int toX, int northZ, int southZ, boolean north, boolean south) {
      for (int x = fromX; x <= toX; x++) {
         if (north) {
            this.setParcel(x, northZ, new RoadParcel(this, new Cartesian2D(x, northZ)));
         }
         if (south && (!north || southZ != northZ)) {
            this.setParcel(x, southZ, new RoadParcel(this, new Cartesian2D(x, southZ)));
         }
      }
   }

   /** Does the rectangle (inclusive chunk bounds) contain a highway lane of this tile's network? Always false on a tile with the old ring. */
   private boolean rectHasHighwayCell(int minX, int minZ, int maxX, int maxZ) {
      if (this.networkMask == null) {
         return false;
      }
      for (int x = minX; x <= maxX; x++) {
         for (int z = minZ; z <= maxZ; z++) {
            if (this.networkMask.at(x, z)) {
               return true;
            }
         }
      }
      return false;
   }

   /** computeBankEdges() turned a quarter turn: per ROW, scanning across from each side of the corridor. */
   private void computeBankEdgesZ(int tileMinZ, int tileHeight, int corridorMinX, int corridorMaxX) {
      this.zBankEdgeTileMinZ = tileMinZ;
      this.zBankEdgeTileHeight = tileHeight;
      double[] rawWest = new double[tileHeight];
      double[] rawEast = new double[tileHeight];

      for (int i = 0; i < tileHeight; i++) {
         int z = tileMinZ + i;

         double westEdge = Double.NaN;
         for (int x = corridorMinX; x <= corridorMaxX; x++) {
            if (!(this.getParcel(x, z) instanceof RiverParcel)) {
               westEdge = (x + 1) * 16.0; // boundary between this non-river chunk and the river beyond it
            } else if (!Double.isNaN(westEdge)) {
               break;
            }
         }
         rawWest[i] = westEdge;

         double eastEdge = Double.NaN;
         for (int x = corridorMaxX; x >= corridorMinX; x--) {
            if (!(this.getParcel(x, z) instanceof RiverParcel)) {
               eastEdge = x * 16.0;
            } else if (!Double.isNaN(eastEdge)) {
               break;
            }
         }
         rawEast[i] = eastEdge;
      }

      // Same slope-limited majorant + gentle jitter as the east-west version
      // (see computeBankEdges()'s own javadoc for why).
      double maxSlope = RiverConfig.getRiverBankEdgeMaxSlopePerChunk();
      double[] smoothWest = slopeLimitedMajorant(rawWest, maxSlope, true);
      double[] smoothEast = slopeLimitedMajorant(rawEast, maxSlope, false);
      for (int i = 0; i < tileHeight; i++) {
         int z = tileMinZ + i;
         double jitterBlocks = (this.bankEdgeJitter.noise(z * 0.05, 0, 0.5, 0.5, true) + 1.0) * 0.15 * 16.0;
         if (!Double.isNaN(smoothWest[i])) {
            smoothWest[i] -= jitterBlocks;
         }
         if (!Double.isNaN(smoothEast[i])) {
            smoothEast[i] += jitterBlocks;
         }
      }
      this.westBankEdgeBlockX = smoothWest;
      this.eastBankEdgeBlockX = smoothEast;
   }

   /**
    * Block X the bank ramp on this side of this ROW must reach -- the
    * north-south river's counterpart of getBankEdgeBlockZ(). NaN if this row is
    * outside the corridor this tile carved, or nothing but open river/bank
    * lies between the corridor edge and the water.
    */
   public double getBankEdgeBlockX(int worldBlockZ, boolean west) {
      if (this.westBankEdgeBlockX == null) {
         return Double.NaN;
      }
      int idx = (worldBlockZ >> 4) - this.zBankEdgeTileMinZ;
      if (idx < 0 || idx >= this.zBankEdgeTileHeight) {
         return Double.NaN;
      }
      return west ? this.westBankEdgeBlockX[idx] : this.eastBankEdgeBlockX[idx];
   }

   /**
    * For every column, scans outward from the water on each bank and
    * records the block Z of the LAST cell that's still RiverParcel before
    * hitting something else -- i.e. wherever the bank ramp actually needs
    * to reach, on the FINAL parcel grid, after every reclaimed district,
    * frontage road, separator and fallback road has replaced whatever
    * RiverParcel placeholder carveRiverCorridor() originally put there.
    *
    * Replaces relying on RiverReclaim's own precomputed edge (northEdge/
    * southEdge, set only by emit() for accepted districts): that's pure
    * geometry, decided before a single parcel was actually written, and
    * it never records anything at all for a fallback road (see
    * RiverReclaim.emit()) -- those columns fell back to the generic short
    * bankMargin ramp regardless of the real (non-river) road sitting
    * right next to them, leaving a visible cliff between the ramp's end
    * and that road. Reading the actual grid instead is correct no matter
    * which mechanism -- or none -- ended up occupying a given cell.
    */
   private void computeBankEdges(int tileMinX, int tileWidth, int corridorMinZ, int corridorMaxZ) {
      this.bankEdgeTileMinX = tileMinX;
      this.bankEdgeTileWidth = tileWidth;
      double[] rawNorth = new double[tileWidth];
      double[] rawSouth = new double[tileWidth];

      for (int i = 0; i < tileWidth; i++) {
         int x = tileMinX + i;

         double northEdge = Double.NaN;
         for (int z = corridorMinZ; z <= corridorMaxZ; z++) {
            if (!(this.getParcel(x, z) instanceof RiverParcel)) {
               northEdge = (z + 1) * 16.0; // boundary between this non-river chunk and the river beyond it
            } else if (!Double.isNaN(northEdge)) {
               break; // first river cell after a non-river run -- that run's far edge is the target
            }
         }
         rawNorth[i] = northEdge;

         double southEdge = Double.NaN;
         for (int z = corridorMaxZ; z >= corridorMinZ; z--) {
            if (!(this.getParcel(x, z) instanceof RiverParcel)) {
               southEdge = z * 16.0;
            } else if (!Double.isNaN(southEdge)) {
               break;
            }
         }
         rawSouth[i] = southEdge;
      }

      // Raw edges are correct per column, but two neighbouring columns can
      // legitimately reach very different depths (a district hugging the
      // water right next to a wide-open gap reaching all the way to the
      // corridor's own edge) -- feeding that straight into bankRampEnd()
      // jumps the ramp's target across a single chunk boundary. The old
      // fix here was a windowed max/min (safe on the north bank, min on
      // the south) -- but an extremum filter is a DILATION, not a blur:
      // it copies whichever value is most extreme across its whole
      // window, so it turns one sharp jump into a flat SHELF bounded by
      // two sharp corners -- the shelf's edges, not the original jump,
      // which is exactly the kinked/faceted look screenshots of this kept
      // showing. RiverParcel's own small-radius (2-block) blend can only
      // soften the sharpest few blocks right at a chunk boundary -- nowhere
      // near enough to smooth a difference that plays out over many
      // chunks, which is the actual scale these jumps happen at.
      //
      // This instead computes the SMALLEST slope-limited majorant of the
      // raw values: a curve that (a) never sits below the safe direction
      // of any real raw value (max-side for north, min-side for south --
      // same safety argument as the old filter: it only ever SHORTENS a
      // column's reach to something a real nearby column's own
      // measurement already justified, never invents a longer one) and
      // (b) never changes by more than getRiverBankEdgeMaxSlopePerChunk()
      // blocks between adjacent columns. That second property is what a
      // fixed-radius window can't give you: instead of flattening to a
      // shelf, it tapers gradually, at a bounded rate, all the way from
      // one real value to the next -- "leaning into" the slope rather
      // than snapping to whichever extreme is nearest.
      //
      // Standard two-pass construction (equivalent to 1D grayscale
      // morphological closing with a cone-shaped structuring element):
      // sweep left-to-right propagating each real value forward, capped
      // to change by at most maxSlope per step; sweep right-to-left doing
      // the mirror; combine with the same extremum the sweeps themselves
      // use. A gap of NaN columns (nothing at all on that side -- no
      // structure to protect) just lets the cone taper through it from
      // either side; if it decays far enough that the ramp it implies
      // would end up shorter than the water's own half-width,
      // bankRampEnd()'s existing guard already falls back to the plain
      // margin, so no separate clamping is needed here for that case.
      double maxSlope = RiverConfig.getRiverBankEdgeMaxSlopePerChunk();
      double[] smoothNorth = slopeLimitedMajorant(rawNorth, maxSlope, true);
      double[] smoothSouth = slopeLimitedMajorant(rawSouth, maxSlope, false);

      // A pure slope-limited majorant is still a deterministic, exact
      // curve -- fine for closing the seam, but a little too clean/plastic
      // on its own. A gentle, low-frequency jitter breaks that up without
      // reopening it: it only ever shortens the already-safe value
      // further (same "never extend past a real edge" reasoning as
      // above), so it can't undo the fix, just texture it.
      //
      // Magnitude was previously (noise+1)*0.9 chunks -- 0..1.8 chunks,
      // i.e. up to 28.8 blocks, averaging ~14.4 -- which wasn't gentle at
      // all: pulling the target back by up to two whole chunks on every
      // column reproduced the exact flat-shelf-short-of-the-buildings
      // look the majorant construction above exists to prevent, just from
      // a different cause (the ramp visibly stopping short of the real
      // district/road edge instead of reaching it). Cut to (noise+1)*0.15
      // chunks -- 0..0.3 chunks, up to 4.8 blocks, averaging ~2.4 -- small
      // enough to still break up the curve's plastic look without eating
      // into the ramp's actual reach.
      for (int i = 0; i < tileWidth; i++) {
         int x = tileMinX + i;
         double jitterChunks = (this.bankEdgeJitter.noise(x * 0.05, 0, 0.5, 0.5, true) + 1.0) * 0.15; // ~0..0.3 chunks
         double jitterBlocks = jitterChunks * 16.0;
         if (!Double.isNaN(smoothNorth[i])) {
            smoothNorth[i] -= jitterBlocks; // shorter reach = larger edgeZ for north; subtract to pull it back toward water
         }
         if (!Double.isNaN(smoothSouth[i])) {
            smoothSouth[i] += jitterBlocks; // shorter reach = smaller edgeZ for south; add to pull it back toward water
         }
      }

      this.northBankEdgeBlockZ = smoothNorth;
      this.southBankEdgeBlockZ = smoothSouth;

      Minions.dRiver("tile root=(%d,%d) -- bank edges computed from final parcel grid for x=[%d,%d), slope-limited (max %.1f blocks/chunk) + jitter -- RiverParcel's own per-block blend still runs on top",
              this.root.X, this.root.Y, tileMinX, tileMinX + tileWidth, maxSlope);
   }

   /**
    * Smallest curve that is (a) always on the `takeMax` side of every
    * defined entry in raw (>= for north/takeMax=true, <= for south/false)
    * and (b) never changes by more than maxSlopePerChunk between adjacent
    * indices -- see computeBankEdges()'s own javadoc for why. NaN entries
    * carry no constraint of their own; the result there is whatever the
    * nearest real entry's cone tapers to (still NaN if no real entry is
    * within reach on either side, or the array is empty of any).
    */
   private static double[] slopeLimitedMajorant(double[] raw, double maxSlopePerChunk, boolean takeMax) {
      int n = raw.length;
      double[] forward = new double[n];
      double running = Double.NaN;
      for (int i = 0; i < n; i++) {
         if (!Double.isNaN(raw[i])) {
            running = Double.isNaN(running) ? raw[i]
                    : (takeMax ? Math.max(raw[i], running - maxSlopePerChunk) : Math.min(raw[i], running + maxSlopePerChunk));
         } else if (!Double.isNaN(running)) {
            running = takeMax ? running - maxSlopePerChunk : running + maxSlopePerChunk;
         }
         forward[i] = running;
      }

      double[] result = new double[n];
      running = Double.NaN;
      for (int i = n - 1; i >= 0; i--) {
         if (!Double.isNaN(raw[i])) {
            running = Double.isNaN(running) ? raw[i]
                    : (takeMax ? Math.max(raw[i], running - maxSlopePerChunk) : Math.min(raw[i], running + maxSlopePerChunk));
         } else if (!Double.isNaN(running)) {
            running = takeMax ? running - maxSlopePerChunk : running + maxSlopePerChunk;
         }
         double f = forward[i];
         if (Double.isNaN(f)) {
            result[i] = running;
         } else if (Double.isNaN(running)) {
            result[i] = f;
         } else {
            result[i] = takeMax ? Math.max(f, running) : Math.min(f, running);
         }
      }
      return result;
   }

   /**
    * Block Z the bank ramp on this side of this column must reach --
    * smoothed and lightly jittered, see computeBankEdges()'s own javadoc.
    * NaN if this column is outside the corridor this tile carved, or the
    * whole strip from the corridor edge to the water is still open
    * river/bank (nothing else was ever placed there).
    */
   public double getBankEdgeBlockZ(int worldBlockX, boolean north) {
      if (this.northBankEdgeBlockZ == null) {
         return Double.NaN;
      }
      int idx = (worldBlockX >> 4) - this.bankEdgeTileMinX;
      if (idx < 0 || idx >= this.bankEdgeTileWidth) {
         return Double.NaN;
      }
      return north ? this.northBankEdgeBlockZ[idx] : this.southBankEdgeBlockZ[idx];
   }

   public void recSetDistricts(Cartesian2D base, Cartesian2D size) {
      if (this.layoutRecorder == null && this.landmarksRemaining > 0 && this.tryPlaceLandmark(base, size)) {
         return;
      }

      // River (open water + full bank footprint) is carved before anything
      // else gets a chance to claim this rectangle -- checked ahead of the
      // DENSERESIDENTIAL cap below so a dense-residential block never gets
      // to straddle it. Unlike the DENSERESIDENTIAL cap's simple "shrink
      // the oversized dimension" loop, this is closer in spirit to
      // tryPlaceLandmark(): fully resolve whether the river touches this
      // rectangle AT ALL (every cell, not just base's corner) before ever
      // deciding to hand it to ordinary land carving, and only ever commit
      // a piece as river or as land once that's been proven true for every
      // cell in it. See tryPlaceRiverBand()'s own javadoc for why the old
      // corner-only sample produced visible gaps, and why this checks
      // isRiverOrBank() (the water+bank footprint) rather than just
      // isRiver() (open water only).
      if (this.layoutRecorder == null && this.tryPlaceRiverBand(base, size)) {
         return;
      }

      // DENSERESIDENTIAL uses the same city-wide road carving as every other
      // district, but it can be given a deliberately smaller maximum district
      // footprint.  This is a carving-time restriction: it prevents a large
      // ordinary block from ever becoming one giant DENSERESIDENTIAL district.
      // The recursive children are then normal districts and each is evaluated
      // independently, so the cap is enforced on every DENSERESIDENTIAL leaf.
      int denseResidentialMax = PluginConfig.getDenseResidentialBlockSize();
      if (denseResidentialMax > 0
              && this.contextProvider.getContext(base) == ContextType.DENSERESIDENTIAL
              && (size.X > denseResidentialMax || size.Y > denseResidentialMax)) {
         if (size.X >= size.Y && size.X > denseResidentialMax) {
            if (size.X > 1) {
               int cut = Minions.makeCut(this.random, size.X, 1);
               if (size.X <= 2 * denseResidentialMax + 1) {
                  cut = Math.max(size.X - denseResidentialMax - 1, Math.min(denseResidentialMax, cut));
               }
               this.partitionXwithRoads(base, size, cut);
               return;
            }
         } else if (size.Y > denseResidentialMax) {
            if (size.Y > 1) {
               int cut = Minions.makeCut(this.random, size.Y, 1);
               if (size.Y <= 2 * denseResidentialMax + 1) {
                  cut = Math.max(size.Y - denseResidentialMax - 1, Math.min(denseResidentialMax, cut));
               }
               this.partitionZwithRoads(base, size, cut);
               return;
            }
         }
      }

      if (size.X > size.Y) {
         if (size.X < this.blockSize || !this.canCut(size.X)) {
            this.addDistrict(base, size);
         } else {
            int cut = Minions.makeCut(this.random, size.X, this.blockSizeMin);
            this.partitionXwithRoads(base, size, cut);
         }
      } else if (size.Y < this.blockSize || !this.canCut(size.Y)) {
         this.addDistrict(base, size);
      } else {
         int cut = Minions.makeCut(this.random, size.Y, this.blockSizeMin);
         this.partitionZwithRoads(base, size, cut);
      }
   }

   /**
    * Whether a dimension of this size can be split at all without leaving
    * either side smaller than blockSizeMin. The road strip between the two
    * pieces costs 1 unit on top of both minimums, so a valid cut only
    * exists when dimension >= 2*blockSizeMin + 1. When this is false,
    * recSetDistricts() falls back to addDistrict() even if the dimension
    * is still >= blockSize -- an oversized leaf district being the only
    * alternative to violating blockSizeMin.
    */
   private boolean canCut(int dimension) {
      return dimension >= 2 * this.blockSizeMin + 1;
   }

   /**
    * Attempts to carve a landmark district -- a schematic whose own chunk
    * footprint exceeds blockSize -- out of the region currently being
    * carved. Returns true if it succeeded and this recursion node is fully
    * handled (a forced district plus 0-2 remainder pieces already
    * recursed back into recSetDistricts()); false means "did nothing,
    * proceed with normal blockSize-based carving" -- the caller falls
    * straight through to its existing logic in that case.
    *
    * Unlike extraCutMode (District.checkExtraCut()), which fills a
    * district normally first and only afterwards detects and re-cuts it
    * if too much of it turned out to be filler, this commits nothing
    * speculative: a landmark is only carved once a real schematic is
    * confirmed to both exist and fit, so there's no iteration, no
    * generate-then-discard, and no risk of the same area being cut more
    * than once.
    */
   private boolean tryPlaceLandmark(Cartesian2D base, Cartesian2D size) {
      // Cheap checks first, before touching the context provider or DB.
      if (size.X < this.blockSize && size.Y < this.blockSize) {
         // Region's already at or below normal city block size -- by
         // definition too small to contain anything that needed a
         // landmark district in the first place.
         return false;
      }

      if (!this.random.getChance(PluginConfig.getLandmarkAttemptChancePercent())) {
         Minions.dLandmark("LANDMARK ATTEMPT SKIPPED (chance %d%% roll missed) tile=(%d,%d) base=%s size=%s",
                 PluginConfig.getLandmarkAttemptChancePercent(), this.root.X, this.root.Y, base, size);
         return false;
      }

      ContextType context = this.contextProvider.getContext(base);
      Minions.dLandmark("LANDMARK ATTEMPT tile=(%d,%d) base=%s size=%s context='%s' budgetRemaining=%d",
              this.root.X, this.root.Y, base, size, context, this.landmarksRemaining);

      // The explicit landmark pool (schematics tagged "landmark", and every
      // schematic with a directional cutout -- ClipboardDAO routes those to
      // ContextType.LANDMARK ONLY) is tried FIRST, regardless of the
      // district's own zoning context, exactly as ContextType.LANDMARK's
      // javadoc says. Only if it has nothing that fits does this fall back to
      // whatever happens to be tagged with the district's own context and is
      // large enough. Without this step the LANDMARK pool was never queried at
      // all, so only zoning-tagged schematics that merely happened to be big
      // could ever be picked.
      List<Cartesian2D> candidateSizes = this.clipboardProvider.getLandmarkSizes(
              this.blockSize, ContextType.LANDMARK, SchematicType.BUILD, size);
      if (!candidateSizes.isEmpty()) {
         context = ContextType.LANDMARK;
         Minions.dLandmark("LANDMARK POOL explicit landmark pool has %d candidate size(s) for base=%s size=%s", candidateSizes.size(), base, size);
      } else {
         candidateSizes = this.clipboardProvider.getLandmarkSizes(
                 this.blockSize, context, SchematicType.BUILD, size);
      }

      if (candidateSizes.isEmpty()) {
         Minions.dLandmark("LANDMARK NONE FOUND context='%s' base=%s size=%s", context, base, size);
         return false;
      }

      // generator.landmarkZoneMatch: a size can now fail for a reason the plain
      // size list can't see (no schematic of that size names this district's
      // zone), so every size is carved in turn until one succeeds. A failed
      // carveLandmark() writes nothing, so trying the next one is safe.
      if (PluginConfig.isLandmarkZoneMatchEnabled()) {
         return this.carveZoneMatchedLandmark(base, size, candidateSizes, context);
      }

      // Try every candidate size before giving up, starting from a random
      // offset so the same size isn't always favoured when several fit --
      // same intent as the plain random pick this replaces, just skipping
      // any candidate whose OWN footprint would overlap the river. A
      // landmark is a single large hand-authored schematic pasted as one
      // unit; unlike an ordinary BUILD parcel there's no mechanism to
      // carve a gap out of one for open water to run through, so a
      // footprint overlapping the river at all has to be rejected
      // outright here rather than ever reaching carveLandmark() -- see
      // riverOverlapsFootprint()'s own javadoc.
      Cartesian2D landmarkSize = null;
      int startIdx = this.random.getRandomInt(candidateSizes.size());
      for (int offset = 0; offset < candidateSizes.size(); offset++) {
         Cartesian2D candidate = candidateSizes.get((startIdx + offset) % candidateSizes.size());
         if (!this.riverOverlapsFootprint(base, candidate)) {
            landmarkSize = candidate;
            break;
         }
      }
      if (landmarkSize == null) {
         Minions.dLandmark("LANDMARK ALL CANDIDATES OVERLAP RIVER context='%s' base=%s size=%s (%d candidates) -- abandoning, falling back to normal carving",
                 context, base, size, candidateSizes.size());
         return false;
      }

      Minions.dLandmark("LANDMARK CANDIDATE SIZE context='%s' base=%s regionSize=%s landmarkSize=%s (of %d candidates)",
              context, base, size, landmarkSize, candidateSizes.size());

      return this.carveLandmark(base, size, landmarkSize, context);
   }

   /** tryPlaceLandmark()'s size loop for generator.landmarkZoneMatch: the first size that carves wins. */
   private boolean carveZoneMatchedLandmark(Cartesian2D base, Cartesian2D size, List<Cartesian2D> candidateSizes, ContextType context) {
      int startIdx = this.random.getRandomInt(candidateSizes.size());
      for (int offset = 0; offset < candidateSizes.size(); offset++) {
         Cartesian2D candidate = candidateSizes.get((startIdx + offset) % candidateSizes.size());
         if (this.riverOverlapsFootprint(base, candidate)) {
            continue;
         }
         if (this.carveLandmark(base, size, candidate, context)) {
            return true;
         }
      }
      Minions.dLandmark("LANDMARK NO SIZE CARVED (zone match on) context='%s' base=%s size=%s (%d candidate size(s)) -- falling back to normal carving",
              context, base, size, candidateSizes.size());
      return false;
   }

   /**
    * True if any chunk within [base, base+footprint) is river or bank
    * (see RiverProvider.isRiverOrBank()) -- used by tryPlaceLandmark() to
    * reject a candidate size outright rather than ever attempting to
    * carve it. Deliberately checks the landmark's own small footprint,
    * not the whole surrounding region tryPlaceLandmark() was called
    * with -- a landmark can still be placed in a region that touches the
    * river elsewhere, as long as its own final footprint doesn't.
    */
   private boolean riverOverlapsFootprint(Cartesian2D base, Cartesian2D footprint) {
      if (!RiverConfig.isRiverEnabled()) {
         return false;
      }
      for (int dx = 0; dx < footprint.X; dx++) {
         for (int dz = 0; dz < footprint.Y; dz++) {
            if (this.isAnyRiverOrBank(base.X + dx, base.Y + dz)) {
               return true;
            }
         }
      }
      return false;
   }

   /**
    * Carves landmarkSize out of the base/size region via two guillotine
    * cuts, reusing the exact same road-strip-insertion pattern as
    * partitionXwithRoads()/partitionZwithRoads() so every resulting piece
    * stays a clean, road-separated rectangle:
    *
    *   1. Vertical cut at landmarkSize.X: left column (landmark's own
    *      width, full region height) | road | right column (remainder).
    *   2. Within the left column, horizontal cut at landmarkSize.Y:
    *      landmark footprint (bottom) | road | leftover strip (top).
    *
    * The right column and leftover strip are fed back into
    * recSetDistricts() as ordinary regions -- ordinary blockSize-based
    * carving picks up from there. If step 2's own leftover strip would be
    * empty (landmarkSize.Y == size.Y exactly), no road or strip is added
    * for that side; same for the right column when landmarkSize.X ==
    * size.X. This can leave the landmark district road-adjacent on fewer
    * than 4 sides, same as any ordinary district at the edge of its
    * parent split -- District.findRoad() already handles that generically.
    *
    * Actual clip selection happens last, via the normal getFit() +
    * weighted pick, using the real direction computed from the final
    * carved footprint -- if no clip matches that specific direction
    * (rotation not available), this bails out and returns false having
    * changed nothing, so the caller falls through to normal carving of
    * the ORIGINAL, uncarved region. No parcels or districts are written
    * until a clip is confirmed to fit and match.
    */
   /**
    * Which world sides of a lot at this chunk face a river: the east-west
    * river lies to the NORTH of a lot south of it and to the SOUTH of one
    * north of it; the north-south river lies to the WEST of a lot east of it
    * and to the EAST of one west of it. Two sides per lot -- one per river --
    * or just one where the north-south river isn't enabled, or none at all if
    * rivers are off.
    */
   public Set<Direction> riverDirectionsAt(int chunkX, int chunkZ) {
      Set<Direction> toward = java.util.EnumSet.noneOf(Direction.class);
      if (!RiverConfig.isRiverEnabled()) {
         return toward;
      }
      if (this.riverProvider != null && this.riverProvider.isEnabled()) {
         double d = this.riverProvider.distanceFromCenter(chunkX, chunkZ);
         if (!Double.isNaN(d)) {
            toward.add(d >= 0 ? Direction.NORTH : Direction.SOUTH);
         }
      }
      if (this.zRiverProvider != null && this.zRiverProvider.isEnabled()) {
         double d = this.zRiverProvider.distanceFromCenter(chunkX, chunkZ);
         if (!Double.isNaN(d)) {
            toward.add(d >= 0 ? Direction.WEST : Direction.EAST);
         }
      }
      return toward;
   }

   /**
    * Applies SchematicConfig.isRiverFacing(): drops every river-facing clip
    * whose river side, once rotated into this placement direction, would not
    * face one of the (up to) two rivers this lot has -- see
    * riverDirectionsAt(). Clips that aren't river-facing pass through
    * untouched, and so does everything when there is no river to face or no
    * direction (an interior lot with no road side has no orientation to test).
    */
   public List<Clipboard> filterRiverFacing(List<Clipboard> clips, int chunkX, int chunkZ, Direction direction) {
      if (clips.isEmpty() || direction == Direction.NONE) {
         return clips;
      }
      boolean any = false;
      for (Clipboard clip : clips) {
         if (clip.getConfig() != null && clip.getConfig().isRiverFacing()) {
            any = true;
            break;
         }
      }
      if (!any) {
         return clips;
      }
      Set<Direction> toward = this.riverDirectionsAt(chunkX, chunkZ);
      if (toward.isEmpty()) {
         return clips;
      }
      List<Clipboard> result = new ArrayList<>(clips.size());
      for (Clipboard clip : clips) {
         SchematicConfig config = clip.getConfig();
         if (config == null || !config.isRiverFacing()) {
            result.add(clip);
            continue;
         }
         boolean faces = false;
         for (Direction local : config.getRiverFacingSides()) {
            if (toward.contains(ClipboardParcel.rotateSide(local, direction))) {
               faces = true;
               break;
            }
         }
         if (faces) {
            result.add(clip);
         } else {
            Minions.dDistrict("RIVER-FACING CANDIDATE REJECTED clip=%s direction=%s riverSides=%s lot=(%d,%d) towardRivers=%s",
                    clip.getGroupId(), direction, config.getRiverFacingSides(), chunkX, chunkZ, toward);
         }
      }
      return result;
   }

   private boolean carveLandmark(Cartesian2D base, Cartesian2D size, Cartesian2D landmarkSize, ContextType context) {
      // A margin on either axis must be either 0 (landmark exactly fills
      // that dimension, no split needed) or >= 2 (room for a 1-chunk road
      // strip plus at least 1 chunk of actual remainder). A margin of
      // exactly 1 can't hold both -- the road strip alone would consume
      // it and leave a 0-size remainder region, which recSetDistricts()
      // isn't equipped to handle. Bail rather than risk that; the caller
      // falls back to carving the original, uncarved region normally.
      int marginX = size.X - landmarkSize.X;
      int marginY = size.Y - landmarkSize.Y;
      if (marginX == 1 || marginY == 1) {
         Minions.dLandmark("LANDMARK MARGIN TOO NARROW base=%s size=%s landmarkSize=%s marginX=%d marginY=%d -- abandoning",
                 base, size, landmarkSize, marginX, marginY);
         return false;
      }

      // Compute the two remainder pieces (and the roads that would
      // separate them from the landmark) without writing anything yet --
      // clip selection can still fail below, and nothing should be
      // mutated until it succeeds.
      boolean hasRightColumn = marginX >= 2;
      boolean hasTopStrip = marginY >= 2;

      Cartesian2D landmarkBase = base;
      Direction direction = this.findRoadFor(base, size, landmarkBase, landmarkSize);

      int lmCenterX = landmarkBase.X + landmarkSize.X / 2;
      int lmCenterZ = landmarkBase.Y + landmarkSize.Y / 2;
      List<Clipboard> clips = this.filterRiverFacing(
              this.clipboardProvider.getFit(landmarkSize, context, SchematicType.BUILD, direction),
              lmCenterX, lmCenterZ, direction);

      // A landmark has a road on all four sides (the outer region's edges plus
      // the strips carved beside it), so a RIVER-FACING one isn't limited to
      // the randomly drawn road side above: it may be turned to whichever
      // side makes its river side face a river. Those extra rotations are
      // offered alongside the ordinary candidates; each remembers the
      // direction it needs.
      java.util.Map<Clipboard, Direction> directionFor = new IdentityHashMap<>();
      for (Clipboard c : clips) {
         directionFor.put(c, direction);
      }
      if (RiverConfig.isRiverEnabled()) {
         Set<String> haveGroups = new HashSet<>();
         for (Clipboard c : clips) {
            haveGroups.add(c.getGroupId());
         }
         for (Direction alt : Direction.getDirections()) {
            if (alt == direction) {
               continue;
            }
            List<Clipboard> altClips = new ArrayList<>();
            for (Clipboard c : this.clipboardProvider.getFit(landmarkSize, context, SchematicType.BUILD, alt)) {
               if (c.getConfig() != null && c.getConfig().isRiverFacing() && !haveGroups.contains(c.getGroupId())) {
                  altClips.add(c);
               }
            }
            for (Clipboard c : this.filterRiverFacing(altClips, lmCenterX, lmCenterZ, alt)) {
               clips.add(c);
               directionFor.put(c, alt);
               haveGroups.add(c.getGroupId());
            }
         }
      }
      // A building that declares a creek origin is only ever valid where a creek provably leaves it;
      // those lots are decided up front by BoundedRiverSites, never by random landmark carving.
      clips.removeIf(ClipboardParcel::hasCreekOrigin);

      // generator.landmarkZoneMatch: only a schematic whose own config names every
      // zone this district's footprint reaches may stand here. (The landmark pool
      // is otherwise tried regardless of the district's zone.)
      // A landmark is bigger than most bands are wide, so it isn't asked to fit inside one:
      // its own centre zone must be one the schematic names, and at least
      // LandmarkZoneRule.MIN_SIDES of its four sides must be bordered by zones it names.
      final boolean zoneMatch = PluginConfig.isLandmarkZoneMatchEnabled();
      final LandmarkZoneRule.Footprint footprint = zonesAround(this.contextProvider, landmarkBase, landmarkSize);
      if (zoneMatch) {
         int beforeZoneFilter = clips.size();
         clips.removeIf(c -> !LandmarkZoneRule.allowsFootprint(c, footprint));
         Minions.dLandmark("LANDMARK ZONE FILTER tile=(%d,%d) base=%s landmarkSize=%s %s, candidates %d -> %d",
                 this.root.X, this.root.Y, base, landmarkSize, footprint, beforeZoneFilter, clips.size());
      } else {
         Minions.dLandmark("LANDMARK ZONE MATCH OFF (generator.landmarkZoneMatch is false in the loaded config) tile=(%d,%d) base=%s %s",
                 this.root.X, this.root.Y, base, footprint);
      }
      if (clips.isEmpty()) {
         Minions.dLandmark("LANDMARK NO DIRECTION MATCH context='%s' base=%s landmarkSize=%s direction=%s -- abandoning, falling back to normal carving",
                 context, base, landmarkSize, direction);
         return false;
      }

      // Landmark selection used to pick purely off raw getOddsOfAppearance()
      // with no idea what was already placed nearby -- neither the hard
      // clone-radius block nor the variant penalty (District.getWeights(),
      // now Minions.getPlacementWeights()) were ever consulted here, since
      // landmarks are carved through this entirely separate path rather
      // than District.placeRandomBuild(). That's why turning variantPenalty
      // up had no effect on landmarks sitting right next to each other --
      // this method never applied it in the first place.
      Cartesian2D center = new Cartesian2D(
              landmarkBase.X + landmarkSize.X / 2, landmarkBase.Y + landmarkSize.Y / 2);
      Set<ClipboardParcel> neighbours = this.getNeighbours(center, PluginConfig.getCloneRadius());
      Set<ClipboardParcel> variantNeighbours = this.getNeighbours(center, PluginConfig.getVariantRadius());

      // cloneRadius is always a hard limit here too -- strip any candidate
      // whose groupId already exists among the clone-radius neighbours,
      // exactly like District.placeRandomRec(). This always runs,
      // regardless of cloneRadiusSoftLimit -- the soft term below only ever
      // adds extra discouragement in the ring BEYOND this hard radius.
      Set<String> nearbyGroupIds = new HashSet<>();
      for (ClipboardParcel neighbour : neighbours) {
         if (neighbour.getClipboard() != null) {
            nearbyGroupIds.add(neighbour.getClipboard().getGroupId());
         }
      }
      int beforeDuplicateFilter = clips.size();
      clips.removeIf(c -> nearbyGroupIds.contains(c.getGroupId()));
      if (clips.isEmpty()) {
         Minions.dLandmark("LANDMARK ALL CANDIDATES DUPLICATE context='%s' base=%s landmarkSize=%s (%d -> 0) -- abandoning, falling back to normal carving",
                 context, base, landmarkSize, beforeDuplicateFilter);
         return false;
      }

      // Landmark-specific hard block, distinct from (and in addition to)
      // the ordinary cloneRadius block just above -- see
      // PluginConfig.getLandmarkCloneRadius() for why landmarks need their
      // own, much wider, tile-granularity radius rather than reusing the
      // chunk-scale cloneRadius. Backed by a small persistent placement
      // log rather than live neighbour parcels (see
      // ClipboardDAO.findNearbyLandmarkGroupIds()), so it reaches
      // neighbouring grid tiles reliably regardless of tile-cache eviction
      // or generation order.
      int landmarkCloneRadius = PluginConfig.getLandmarkCloneRadius();
      Set<String> nearbyLandmarkGroupIds = this.clipboardProvider.findNearbyLandmarkGroupIds(
              this.generator.worldName, this.root.X, this.root.Y, landmarkCloneRadius);
      int beforeLandmarkDuplicateFilter = clips.size();
      clips.removeIf(c -> nearbyLandmarkGroupIds.contains(c.getGroupId()));
      Minions.dLandmark("LANDMARK CLONE FILTER tile=(%d,%d) landmarkCloneRadius=%d tiles, groupIds already placed nearby=%s, candidates %d -> %d",
              this.root.X, this.root.Y, landmarkCloneRadius, nearbyLandmarkGroupIds, beforeLandmarkDuplicateFilter, clips.size());
      if (clips.isEmpty()) {
         Minions.dLandmark("LANDMARK ALL CANDIDATES DUPLICATE (landmarkCloneRadius=%d tiles) context='%s' base=%s landmarkSize=%s (%d -> 0) -- abandoning, falling back to normal carving",
                 landmarkCloneRadius, context, base, landmarkSize, beforeLandmarkDuplicateFilter);
         return false;
      }

      int cloneRadiusSoftLimit = PluginConfig.getCloneRadiusSoftLimit();
      Set<ClipboardParcel> cloneSoftNeighbours = cloneRadiusSoftLimit > PluginConfig.getCloneRadius()
              ? this.getNeighbours(center, cloneRadiusSoftLimit)
              : Collections.emptySet();
      List<Integer> weights = Minions.getPlacementWeights(
              clips, center,
              variantNeighbours, PluginConfig.getVariantRadius(), PluginConfig.getVariantPenalty(),
              cloneSoftNeighbours, cloneRadiusSoftLimit, PluginConfig.getClonePenalty(),
              PluginConfig.isNormalizeOddsOfAppearance()
      );
      Clipboard chosen = clips.get(Minions.getRandomWeighted(weights, this.random));
      // The rotation this candidate was offered under (a river-facing one may
      // differ from the drawn road side).
      direction = directionFor.get(chosen);

      Minions.dLandmark("LANDMARK PLACING context='%s' base=%s landmarkSize=%s direction=%s clip=%s",
              context, base, landmarkSize, direction, chosen.getGroupId());

      // Confirmed -- now actually write the roads and register districts.
      if (hasRightColumn) {
         int cut = landmarkSize.X;
         for (int i = base.Y; i < base.Y + size.Y; i++) {
            this.setParcel(base.X + cut, i, new RoadParcel(this, new Cartesian2D(base.X + cut, i)));
         }
      }

      if (hasTopStrip) {
         int cut = landmarkSize.Y;
         for (int i = base.X; i < base.X + landmarkSize.X; i++) {
            this.setParcel(i, base.Y + cut, new RoadParcel(this, new Cartesian2D(i, base.Y + cut)));
         }
      }

      Minions.dLandmark("LANDMARK ZONE RESULT group='%s' declaredZones=%s %s sidesMatching=%d/4 (need %d) zoneMatch=%s",
              chosen.getGroupId(), LandmarkZoneRule.declaredZones(chosen), footprint,
              LandmarkZoneRule.sidesMatching(chosen, footprint), LandmarkZoneRule.sidesRequired(chosen), zoneMatch);
      IDistrict landmarkDistrict = this.addForcedDistrict(landmarkBase, landmarkSize, chosen, direction);
      if (zoneMatch) {
         ContextType pin = LandmarkZoneRule.pinFor(chosen, footprint.centre);
         if (pin != null) {
            this.zonePins.put(landmarkDistrict, new ZonePin(pin, true));
         }
      }
      this.landmarksRemaining--;
      Minions.dLandmark("LANDMARK PLACED tile=(%d,%d) group='%s' base=%s landmarkSize=%s direction=%s budgetRemaining=%d",
              this.root.X, this.root.Y, chosen.getGroupId(), landmarkBase, landmarkSize, direction, this.landmarksRemaining);
      this.clipboardProvider.storeLandmarkPlacement(this.generator.worldName, chosen.getGroupId(), this.root.X, this.root.Y);

      if (hasTopStrip) {
         Cartesian2D topBase = new Cartesian2D(base.X, base.Y + landmarkSize.Y + 1);
         Cartesian2D topSize = new Cartesian2D(landmarkSize.X, size.Y - landmarkSize.Y - 1);
         this.recSetDistricts(topBase, topSize);
      }

      if (hasRightColumn) {
         Cartesian2D rightBase = new Cartesian2D(base.X + landmarkSize.X + 1, base.Y);
         Cartesian2D rightSize = new Cartesian2D(size.X - landmarkSize.X - 1, size.Y);
         this.recSetDistricts(rightBase, rightSize);
      }

      return true;
   }

   /**
    * Direction candidates for a landmark are computed exactly like
    * District.findRoad() does for any other parcel -- comparing the
    * landmark's own footprint against the OUTER region's edges (base/size
    * as passed into recSetDistricts for this recursion node, not the
    * final district's own base/size, since roads are only guaranteed on
    * whichever sides were already road-adjacent coming into this carve).
    * A side is road-facing if the landmark's edge lines up with the outer
    * region's edge on that side.
    */
   private Direction findRoadFor(Cartesian2D outerBase, Cartesian2D outerSize, Cartesian2D landmarkBase, Cartesian2D landmarkSize) {
      boolean north = outerBase.Y == landmarkBase.Y;
      boolean south = outerBase.Y + outerSize.Y == landmarkBase.Y + landmarkSize.Y;
      boolean west = outerBase.X == landmarkBase.X;
      boolean east = outerBase.X + outerSize.X == landmarkBase.X + landmarkSize.X;
      return Direction.getRandomDirection(this.random, north, south, east, west);
   }

   private IDistrict addForcedDistrict(Cartesian2D base, Cartesian2D size, Clipboard clip, Direction direction) {
      IDistrict district = new District();
      district.initForcedDistrict(base, size, this, clip, direction);
      this.districtSet.add(district);
      return district;
   }

   private void addDistrict(Cartesian2D base, Cartesian2D size) {
      if (this.layoutRecorder != null) {
         this.layoutRecorder.block(base.X, base.Y, size.X, size.Y);
         return;
      }
      IDistrict district = new District();
      district.initDistrict(base, size, this);
      this.districtSet.add(district);
   }

   // Rungs (DistrictContextAssigner ladder index) this tile recorded for the
   // chunk cells within DistrictContextAssigner.SEAM_REACH of its edge, keyed by
   // packed chunk coordinates. Published once, fully built and immutable, at
   // the end of assignDistrictContexts(); until then (and in a tile that never
   // ran the assigner) it is empty. A neighbouring tile that is generated
   // later reads it through peekGrid() so its own border districts stay
   // within one rung of ours. Read-only by design, like the rest of this
   // class's cross-tile lookups: it never builds a tile that doesn't exist.
   private volatile java.util.Map<Long, Integer> districtTierSeam = Collections.emptyMap();

   private static long seamKey(int chunkX, int chunkZ) {
      return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
   }

   /** -1 when this tile recorded nothing for that chunk cell. */
   public int getPublishedDistrictTier(int chunkX, int chunkZ) {
      Integer tier = this.districtTierSeam.get(seamKey(chunkX, chunkZ));
      return tier == null ? -1 : tier;
   }

   // The farm and forest regions this tile holds a piece of, from dissolveMorphomosRoads(). The regions themselves belong
   // to the world plan (PlanRegions): one object per region, whichever tile asks, so a region runs across tile edges.
   private final List<MorphomosRegion> morphomosRegions = new ArrayList<MorphomosRegion>();
   private final Set<IDistrict> morphomosDistricts = new HashSet<IDistrict>();

   /** The regions of farm and forest that reach into this tile (each is the plan's whole region, not a clipped copy). */
   public List<MorphomosRegion> getMorphomosRegions() {
      return Collections.unmodifiableList(this.morphomosRegions);
   }

   /** The region of farm or forest covering this chunk, anywhere in the world, or null. Asked of the plan. */
   /** The actual forest parcel on a bounded creek's dry natural ground.
    * Both creekNaturalGround (unclaimed shore) and interfluveNaturalGround
    * (committed NATURAL between creek arms) are local synthetic regions,
    * invisible to PlanRegions. Never construct a neighbour tile here.
    */
   /** Creek woodland ownership is resolved for ANY world chunk, including
    * cells across an unbuilt tile boundary. This follows the same ZONE shore
    * handoff used by placeFromPlan(): when the nearest water body is a creek
    * but no accepted bounded river site owns the cell, the cell is dry
    * interfluve woodland rather than river water or an empty tree mask.
    * A real accepted creek site remains a RIVER shore owner; we never turn
    * its wet/bank parcel into forest just to increase tree density.
    * No parcel read, neighbour construction or post-paint modification.
    */
   public boolean isWorldPlannedCreekForestAt(int chunkX, int chunkZ) {
      WorldLayout layout = this.cityLayout();
      WaterField water = this.planWater();
      if (layout == null || water == null) return false;
      int kind = layout.kindAt(chunkX, chunkZ);
      WaterField.Kind wet = water.kindAt(chunkX, chunkZ);
      if (kind != WorldLayout.ZONE || wet != WaterField.Kind.NONE
              || this.isPlanStreet(chunkX, chunkZ)) return false;
      if (boundedSitesFor(this.generator).siteAt(chunkX, chunkZ) != null) return false;
      WaterField.Kind shore = water.zoneKind(chunkX, chunkZ);
      if (shore == WaterField.Kind.NONE)
         shore = nearestShoreKind(water, chunkX, chunkZ);
      return plannedCreekForestCell(kind, wet, false, false, shore);
   }

   /** Pure plan-ownership predicate used by the shared river/forest root selector.
    * No tile-local parcel state. The caller supplies actual world-plan labels. */
   public static boolean plannedCreekForestCell(int kind, WaterField.Kind wet,
                                                boolean plannedStreet, boolean acceptedCreekSite,
                                                WaterField.Kind shore) {
      return kind == WorldLayout.ZONE && wet == WaterField.Kind.NONE
              && !plannedStreet && !acceptedCreekSite
              && shore == WaterField.Kind.CREEK;
   }

   public MorphomosRegion localCreekNaturalForestAt(int chunkX, int chunkZ) {
      if (chunkX < this.root.X || chunkX >= this.root.X + 64
              || chunkZ < this.root.Y || chunkZ >= this.root.Y + 64) return null;
      Parcel parcel = this.getParcel(chunkX, chunkZ);
      if (parcel instanceof MorphomosParcel forest && forest.getContextType() == ContextType.FOREST) {
         MorphomosRegion owner = forest.getRegion();
         if (owner != null && (owner == this.creekNaturalGround || owner == this.interfluveNaturalGround))
            return owner;
      }
      return null;
   }

   public MorphomosRegion getMorphomosRegionAt(int chunkX, int chunkZ) {
      // A committed creek ring street must not also present itself as
      // forest/farm simply because the pre-ring plan dissolved this cell.
      if (this.isAcceptedCreekLocalRoad(chunkX, chunkZ)) return null;
      if (!PluginConfig.isMorphomosEnabled()) {
         return null;
      }
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
      return shapes == null ? null : shapes.regions().regionFor(chunkX, chunkZ);
   }

   /** Is this chunk a paved street of the plan (a street that did not dissolve into farm or forest)? Any chunk, any tile. */
   public boolean isPlanStreet(int chunkX, int chunkZ) {
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
      return this.isAcceptedCreekLocalRoad(chunkX, chunkZ)
              || (shapes != null && shapes.isRoadStreet(chunkX, chunkZ));
   }

   /**
    * Morphomos pass. Puts a MorphomosParcel on every chunk of this tile that the world plan says is farm or forest: the
    * blocks of countryside and the streets between them that dissolved into it (PlanRegions). Nothing is decided here:
    * which streets dissolve, and which blocks make one region, is a function of the chunk and the seed alone, so the same
    * region is found from every tile it reaches into, in any order, and no tile edge is in it. The region object is the
    * plan's own (see PlanRegions.regionFor()), never a copy clipped to this tile.
    * Generates nothing: the district of such a block is not filled (see the fill loop in the constructor).
    *
    * A street the plan dissolved that no block of its own domain touches is in no region and is no street either; it
    * becomes ground, like every other chunk the plan leaves to terrain.
    */
   private void dissolveMorphomosRoads() {
      if (this.layoutRecorder != null) {
         return;
      }
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
      WorldLayout layout = WorldLayouts.forSeed(this.generator.getWorldSeed());
      if (shapes == null || layout == null) {
         return;
      }
      ch.k42.metropolis.grid.urbanGrid.layout.PlanRegions plan = shapes.regions();
      boolean enabled = PluginConfig.isMorphomosEnabled();
      Set<MorphomosRegion> seen = new java.util.LinkedHashSet<MorphomosRegion>();
      int dissolved = 0;
      for (int z = this.root.Y; z <= this.root.Y + 63; z++) {
         for (int x = this.root.X; x <= this.root.X + 63; x++) {
            int kind = layout.kindAt(x, z);
            boolean street = kind == WorldLayout.STREET && plan.dissolvedAt(x, z);
            // These streets were explicitly accepted into a complete creek-side
            // road ring after the immutable countryside plan was computed.
            // The original Morphomos decision does not own them any more:
            // replacing them here silently erased their already-reserved
            // RoadParcel (and its schematic), causing ring gaps/dead ends.
            // This is an ownership handoff during the existing zoning pass,
            // never a post-generation rebuild or blanket road-retention rule.
            if (CreekRoadOwnership.preservesStreet(kind, street,
                    this.isAcceptedCreekLocalRoad(x, z))) {
               continue;
            }
            if (kind != WorldLayout.BLOCK && !street) {
               continue;
            }
            MorphomosRegion region = enabled ? plan.regionFor(x, z) : null;
            if (region != null) {
               this.setParcel(x, z, new MorphomosParcel(new Cartesian2D(x, z), region.getContext(), this, region));
               seen.add(region);
               dissolved += street ? 1 : 0;
            } else if (street) {
               this.placeGroundFallback(x, z, "dissolved-street-no-region");
            }
         }
      }
      this.morphomosRegions.addAll(seen);
      int missingAcceptedCreekRoads = 0;
      for (long key : this.creekPerimeterCells) {
         int x=(int)(key>>32),z=(int)key;
         if (this.peekParcel(x,z) instanceof RoadParcel) continue;
         missingAcceptedCreekRoads++;
         if (missingAcceptedCreekRoads <= 8)
            Minions.w("ACCEPTED CREEK RING LOST ROAD chunk=(%d,%d) actual=%s",
                    x,z,parcelName(this.peekParcel(x,z)));
      }
      if (missingAcceptedCreekRoads > 0)
         Minions.w("ACCEPTED CREEK RING LOST ROAD tile=(%d,%d) count=%d",
                 this.root.X,this.root.Y,missingAcceptedCreekRoads);
      for (IDistrict d : this.districtSet) {
         if (d.getContext() != null && d.getContext().isMorphomos() && this.getMorphomosRegionAt(d.getBase().X, d.getBase().Y) != null) {
            this.morphomosDistricts.add(d);
         }
      }
      Minions.dDistrict("MORPHOMOS tile root=(%d,%d) regions=%d countrysideDistricts=%d dissolvedRoadCells=%d",
              this.root.X, this.root.Y, this.morphomosRegions.size(), this.morphomosDistricts.size(), dissolved);
   }

   /**
    * Scraps a district that came back too filler-heavy (see
    * District.checkExtraCut()) and re-cuts its exact base/size area with a
    * fresh road split via forceCut(), then fills whatever new districts
    * come out of that.
    *
    * Deliberately does NOT call recSetDistricts() -- that method's leaf
    * decision is gated on blockSize, which would refuse to split anything
    * already below normal city block size (exactly the case here, since
    * extraCutMode's whole point is to keep going past that). forceCut()
    * performs exactly one split with no blockSize involved at all; the
    * floor for the recursion is extraCutBlockSize, enforced by the caller
    * (District.checkExtraCut()) before this is ever invoked. Every chunk in
    * base/size gets a fresh parcel written by the districts this produces,
    * so there's no need to clear the old ones first.
    *
    * Each newly created district is filled here directly rather than
    * relying on the constructor's original districtSet loop, since that
    * loop has already finished running by the time a district is old
    * enough to have been checked and found wanting. If one of these new,
    * smaller districts is STILL too filler-heavy, its own fillDistrict()
    * call ends up back here again -- naturally bounded, since each cut
    * strictly shrinks the area and checkExtraCut() stops once both
    * dimensions are below extraCutBlockSize.
    */
   public void recutAndRefill(IDistrict scrapped, Cartesian2D base, Cartesian2D size) {
      this.districtSet.remove(scrapped);

      Set<IDistrict> before = new HashSet<>(this.districtSet);
      this.forceCut(base, size);
      Set<IDistrict> added = new HashSet<>(this.districtSet);
      added.removeAll(before);

      // The re-cut halves keep the scrapped district's zoning: sampling the
      // noise again at each half's own base could land on a context two
      // rungs away from the neighbours the ladder was already solved
      // against. (Only under district neighbor rules; otherwise each half
      // re-samples, as before.)
      ContextType inherited = scrapped.getContext();
      if (PluginConfig.isDistrictNeighborRulesEnabled() && inherited != null) {
         for (IDistrict newDistrict : added) {
            newDistrict.assignContext(inherited);
         }
      }

      for (IDistrict newDistrict : added) {
         newDistrict.fillDistrict();
      }
   }

   /**
    * Splits base/size into two districts with a road strip between them,
    * exactly like partitionXwithRoads()/partitionZwithRoads() do -- but
    * registers both halves directly as leaf districts via addDistrict()
    * instead of recursing back into recSetDistricts(), so no blockSize
    * check is ever applied. Used only by extraCutMode's re-cut path (see
    * recutAndRefill()); ordinary district carving continues to go through
    * recSetDistricts() and respects blockSize as always.
    *
    * Only called when at least one dimension is >= 2 -- District.checkExtraCut()
    * guarantees that before calling in. Falls back to re-adding base/size
    * unchanged if that's somehow not true, rather than risking a zero-size
    * or negative-size district.
    */
   private void forceCut(Cartesian2D base, Cartesian2D size) {
      if (size.X > 1 && size.X >= size.Y) {
         int cut = Minions.makeCut(this.random, size.X);
         for (int i = base.Y; i < base.Y + size.Y; i++) {
            this.setParcel(base.X + cut, i, new RoadParcel(this, new Cartesian2D(base.X + cut, i)));
         }

         this.addDistrict(base, new Cartesian2D(cut, size.Y));
         this.addDistrict(new Cartesian2D(base.X + cut + 1, base.Y), new Cartesian2D(size.X - cut - 1, size.Y));
      } else if (size.Y > 1) {
         int cut = Minions.makeCut(this.random, size.Y);
         for (int i = base.X; i < base.X + size.X; i++) {
            this.setParcel(i, base.Y + cut, new RoadParcel(this, new Cartesian2D(i, base.Y + cut)));
         }

         this.addDistrict(base, new Cartesian2D(size.X, cut));
         this.addDistrict(new Cartesian2D(base.X, base.Y + cut + 1), new Cartesian2D(size.X, size.Y - cut - 1));
      } else {
         Minions.dDistrict("FORCECUT CALLED ON INDIVISIBLE AREA base=%s size=%s -- re-adding unchanged", base, size);
         this.addDistrict(base, size);
      }
   }

   private void partitionXwithRoads(Cartesian2D base, Cartesian2D initSize, int cut) {
      if (this.layoutRecorder != null) {
         this.layoutRecorder.road(base.X + cut, base.Y, base.X + cut, base.Y + initSize.Y - 1);
      } else {
         for (int i = base.Y; i < base.Y + initSize.Y; i++) {
            this.setParcel(base.X + cut, i, new RoadParcel(this, new Cartesian2D(base.X + cut, i)));
         }
      }

      this.recSetDistricts(base, new Cartesian2D(cut, initSize.Y));
      this.recSetDistricts(new Cartesian2D(base.X + cut + 1, base.Y), new Cartesian2D(initSize.X - cut - 1, initSize.Y));
   }

   private void partitionZwithRoads(Cartesian2D base, Cartesian2D initSize, int cut) {
      if (this.layoutRecorder != null) {
         this.layoutRecorder.road(base.X, base.Y + cut, base.X + initSize.X - 1, base.Y + cut);
      } else {
         for (int i = base.X; i < base.X + initSize.X; i++) {
            this.setParcel(i, base.Y + cut, new RoadParcel(this, new Cartesian2D(i, base.Y + cut)));
         }
      }

      this.recSetDistricts(base, new Cartesian2D(initSize.X, cut));
      this.recSetDistricts(new Cartesian2D(base.X, base.Y + cut + 1), new Cartesian2D(initSize.X, initSize.Y - cut - 1));
   }

   /**
    * Attempts to carve the river's true water+bank footprint out of this
    * rectangle, in the same "prove it before committing" spirit as
    * tryPlaceLandmark(): resolves whether isRiverOrBank() touches the
    * ENTIRE rectangle (every cell, not just base's corner) before deciding
    * anything, and only ever commits a piece as pure river or hands a
    * piece to ordinary land carving once that's been proven true for
    * every cell in it.
    *
    * The old approach sampled only base's corner, then shrank the
    * rectangle's cross-flow dimension by guesswork until it was "thin
    * enough." A rectangle whose corner missed the river but whose middle
    * didn't would sail straight through untouched, becoming an ordinary
    * land district on top of what should have been open water or bank --
    * that's the isolated, disconnected-looking river fragments the map
    * screenshot showed. Scanning the whole rectangle up front (cheap: at
    * most 62x62 isRiverOrBank() calls, done once per rectangle during
    * world generation, not per tick) makes that impossible: nothing here
    * is ever ordinary land unless every one of its cells was checked and
    * found clean.
    *
    * Returns true if this rectangle was fully handled (committed as
    * river, or split into two pieces that were each already recursed);
    * false means "no river or bank anywhere in this rectangle, proceed
    * with normal carving" -- the same contract tryPlaceLandmark() has.
    */
   private boolean tryPlaceRiverBand(Cartesian2D base, Cartesian2D size) {
      if (!RiverConfig.isRiverEnabled() || RiverConfig.getRiverBlockSize() <= 0) {
         return false;
      }

      boolean[][] river = new boolean[size.X][size.Y];
      boolean anyRiver = false;
      boolean allRiver = true;
      for (int dx = 0; dx < size.X; dx++) {
         for (int dz = 0; dz < size.Y; dz++) {
            boolean hit = this.isAnyRiverOrBank(base.X + dx, base.Y + dz);
            river[dx][dz] = hit;
            if (hit) {
               anyRiver = true;
            } else {
               allRiver = false;
            }
         }
      }

      if (!anyRiver) {
         return false;
      }

      if (allRiver) {
         Minions.dRiver("RIVER BAND fully covers base=%s size=%s", base, size);
         return this.commitRiverRect(base, size);
      }

      // Mixed: this rectangle contains both river/bank and ordinary land.
      // Once it's down to RiverConfig.getRiverBlockSize() or smaller on
      // BOTH axes, further splitting isn't worth it -- reserve whatever
      // river/bank cells are here and hand the rest to ordinary carving,
      // accepting this one small "swiss cheese" leaf as a floor case
      // rather than chasing a perfectly clean boundary forever.
      int floor = Math.max(1, RiverConfig.getRiverBlockSize());
      if (size.X <= floor && size.Y <= floor) {
         this.reserveRiverCells(base, size);
         return false;
      }

      // Otherwise: bisect this rectangle in half along whichever axis is
      // currently longer, and hand each half back to recSetDistricts()
      // -- which will re-invoke this method on each, continuing to
      // halve until every resulting piece is provably pure-river,
      // provably pure-land, or hits the floor above. Deliberately PURE
      // GEOMETRY: no road is placed at the seam here, unlike the old
      // peel-based approach this replaced (see below) -- whatever road
      // ends up at or near this seam is left entirely to whichever
      // ordinary, whole-rectangle-spanning ...WithRoads() call eventually
      // handles each half, exactly like every other road in the city.
      //
      // This replaced an earlier approach that tried to carve a clean,
      // river-free CUT (with a real road already placed at the seam)
      // before ever letting ordinary carving see this rectangle. That
      // guarantee sounded safer than it was: the river's edge runs
      // diagonally through most of these rectangles, so a fully clean
      // margin was rarely more than 1 row/column deep, and each peel's
      // road only ever spanned that CURRENT FRAGMENT's own width --
      // never the tile, or even the original region before the cascade
      // started. Two fragments sitting right next to each other, each
      // peeling their own 1-wide margin, produced two independent,
      // disconnected road stubs instead of one continuous street: roads
      // dead-ending for no reason specifically near the river.
      //
      // That got replaced with reserving the ENTIRE mixed rectangle's
      // river cells in one pass and handing the whole thing straight to
      // ordinary carving with no splitting at all -- which fixed the
      // dead-ending, but traded it for a different problem: a large
      // rectangle riddled with scattered reserved holes is exactly the
      // shape BUILD footprint-fitting was never designed to reason
      // about, so placement attempts kept failing and falling through to
      // FILLER at ever-smaller sizes, producing dense clusters of tiny
      // mismatched lots hugging the river -- and, separately, an ordinary
      // road strip crossing such a rectangle could land squarely on a
      // reserved cell without being flanked by river on BOTH opposite
      // sides (only clipping the edge of it), so it never qualified for
      // convertRoadsToBridges() and rendered as a plain, un-bridged road
      // sitting on top of what should have been open water.
      //
      // Bisecting down to a small, clean floor before ever reserving
      // anything is the middle ground: ordinary carving only ever sees
      // EITHER a fully river-free rectangle (no holes to trip over) OR a
      // small floor-sized leaf that gets reserved whole -- never a large
      // rectangle half-punched full of holes -- while still never placing
      // a road itself, so the dead-ending this whole redesign started
      // from can't come back either.
      //
      // The one thing this does NOT protect against: a multi-chunk BUILD
      // schematic placed by ordinary carving on a PURE-LAND rectangle
      // still landing partly on a reserved cell from an adjacent tile's
      // independently-computed river geometry, or any other edge this
      // per-rectangle bisection can't see across (RoadParcel/FILLER are
      // both disposable 1x1s with no such risk, and tryPlaceLandmark()
      // already checks this itself -- see riverOverlapsFootprint()).
      // That's a real, currently-open gap -- just a far narrower one
      // than either problem this replaces.
      if (size.X >= size.Y) {
         int cut = Math.max(1, size.X / 2);
         this.recSetDistricts(base, new Cartesian2D(cut, size.Y));
         this.recSetDistricts(new Cartesian2D(base.X + cut, base.Y), new Cartesian2D(size.X - cut, size.Y));
      } else {
         int cut = Math.max(1, size.Y / 2);
         this.recSetDistricts(base, new Cartesian2D(size.X, cut));
         this.recSetDistricts(new Cartesian2D(base.X, base.Y + cut), new Cartesian2D(size.X, size.Y - cut));
      }
      return true;
   }

   /**
    * Places a RiverParcel for every cell in [base, base+size) that's
    * actually river/bank, without touching anything else in the
    * rectangle or attempting any cut -- see tryPlaceRiverBand()'s
    * "mixed" branch. Idempotent: recSetDistricts() naturally recurses
    * into smaller sub-rectangles of whatever this was called on as
    * ordinary carving proceeds, so tryPlaceRiverBand() (and therefore
    * this) gets invoked again on pieces already fully reserved here --
    * skipping cells that are already a RiverParcel keeps that from doing
    * any redundant work beyond the cheap isRiverOrBank() re-check.
    */
   private void reserveRiverCells(Cartesian2D base, Cartesian2D size) {
      int placed = 0;
      for (int dx = 0; dx < size.X; dx++) {
         for (int dz = 0; dz < size.Y; dz++) {
            int x = base.X + dx;
            int z = base.Y + dz;
            if (this.isAnyRiverOrBank(x, z) && !(this.getParcel(x, z) instanceof RiverParcel)) {
               this.placeRiverParcel(x, z);
               placed++;
            }
         }
      }
      if (placed > 0) {
         Minions.dRiver("RIVER MIXED reserved %d/%d river/bank cell(s), handing rest of base=%s size=%s to ordinary carving",
                 placed, size.X * size.Y, base, size);
      }
   }

   /**
    * Commits a rectangle already PROVEN (by tryPlaceRiverBand()) to be
    * entirely river/bank, subject to riverBlockSize -- same role the old
    * corner-check's "thin enough yet?" loop had, just working from an
    * exact confirmed footprint instead of a guess. Both pieces of any
    * further split here are still guaranteed all-river/bank (the whole
    * rectangle already was), so reusing the ordinary uniform-road-strip
    * partition helpers is safe.
    */
   private boolean commitRiverRect(Cartesian2D base, Cartesian2D size) {
      int riverBlockSize = RiverConfig.getRiverBlockSize();
      // Orientation of whichever river covers this (proven all-river) rectangle.
      RiverProvider here = this.getRiverProviderAt(base.X + size.X / 2, base.Y + size.Y / 2);
      boolean eastWest = here.isZBound()
              ? !here.isEastWestOriented(base.Y + size.Y / 2)
              : here.isEastWestOriented(base.X + size.X / 2);
      int crossAxis = eastWest ? size.Y : size.X;
      if (crossAxis > riverBlockSize && crossAxis > 1) {
         int cut = Minions.makeCut(this.random, crossAxis, 1);
         if (eastWest) {
            this.partitionZwithRoads(base, size, cut);
         } else {
            this.partitionXwithRoads(base, size, cut);
         }
         return true;
      }

      Minions.dRiver("RIVER STRIP carved at base=(%d,%d) size=(%d,%d) eastWest=%s",
              base.X, base.Y, size.X, size.Y, eastWest);
      this.fillRiverStrip(base, size);
      return true;
   }

   /**
    * Commits a rectangle that tryPlaceRiverBand() has already PROVEN is
    * entirely river/bank, rather than land that still needs further
    * carving. Deliberately does NOT create an IDistrict -- a river reach
    * is placed directly, the same way roads/highways are, not routed
    * through District.fillDistrict()'s random building selection.
    *
    * Uses isRiverOrBank() (the full water+bank footprint), not the
    * narrower isRiver() (open water only) -- every cell in this rectangle
    * already passed that same check in tryPlaceRiverBand(), so this loop
    * always places a RiverParcel for every single cell here; the
    * per-cell re-check is just reusing the existing predicate rather than
    * assuming a shared, potentially-stale intermediate result.
    *
    * Guarded by "not already a RiverParcel", same as reserveRiverCells():
    * without it, a cell isAnyRiverOrBank() now also recognizes as river
    * ONLY because it's already a creek's own RiverParcel (see that
    * method's own javadoc) would get overwritten here with a brand-new,
    * plain RiverParcel -- erasing the creek's own geometry the one place
    * this whole extension exists to protect.
    */
   private void fillRiverStrip(Cartesian2D base, Cartesian2D size) {
      int placed = 0;
      for (int x = base.X; x < base.X + size.X; x++) {
         for (int z = base.Y; z < base.Y + size.Y; z++) {
            if (this.isAnyRiverOrBank(x, z) && !(this.getParcel(x, z) instanceof RiverParcel)) {
               this.placeRiverParcel(x, z);
               placed++;
            }
         }
      }
      Minions.dRiver("fillRiverStrip base=(%d,%d) size=(%d,%d) -- placed %d/%d chunks as RiverParcel",
              base.X, base.Y, size.X, size.Y, placed, size.X * size.Y);
   }

   // ========================================================================
   // BOUNDED TRIBUTARY CARRIERS (world-plan owned, never tile-carved)
   // ========================================================================

   // ========================================================================
   // Bounded creek sites supply park, channel, and bank ownership atomically.
   // The ordinary main RiverProvider remains the receiving river.
   // ========================================================================

   /** The axis-aligned corridor rectangle one creek owns in this tile, in its own (along, across) frame. */
   private static final class CreekBand {
      final RiverProvider creek;
      final boolean alongZ;
      final int alongLo;
      final int alongHi;
      final int acrossLo;
      final int acrossHi;

      CreekBand(RiverProvider creek, boolean alongZ, int alongLo, int alongHi, int acrossLo, int acrossHi) {
         this.creek = creek;
         this.alongZ = alongZ;
         this.alongLo = alongLo;
         this.alongHi = alongHi;
         this.acrossLo = acrossLo;
         this.acrossHi = acrossHi;
      }

      int minX() { return this.alongZ ? this.acrossLo : this.alongLo; }
      int maxX() { return this.alongZ ? this.acrossHi : this.alongHi; }
      int minZ() { return this.alongZ ? this.alongLo : this.acrossLo; }
      int maxZ() { return this.alongZ ? this.alongHi : this.acrossHi; }
   }

   /**
    * The lot of a creek site's building plus its road ring: a rectangle ordinary carving treats as
    * an obstacle. The ring exists only on sides with room for one (the highway ring is the road
    * on a side where the footprint already touches the tile interior's edge).
    */
   /**
    * Is the parcel at (x, z) a street a crossing could connect to: a real road or highway, and not one a
    * creek's own reservation laid down along itself. A creek's corridor ends against the river corridor,
    * and its edge roads, reclaim roads (including whole slivers paved as road) and seams run straight up to
    * the river's flanking row -- from there they look exactly like a street arriving from both sides, and
    * the river's crossing scan used to answer by bridging the entire river in front of every one of them.
    */
   private boolean isCrossingStreet(int x, int z) {
      Parcel p = this.peekParcel(x, z);
      return (p instanceof RoadParcel || p instanceof HighwayParcel) && !this.creekRoadCells.contains(cellKey(x, z));
   }

   private static boolean isCreekCell(Parcel p) {
      return p instanceof RiverParcel && ((RiverParcel) p).getCarrier() != null;
   }

   private RiverProvider carrierOf(Parcel p) {
      return p instanceof RiverParcel ? ((RiverParcel) p).getCarrier() : null;
   }

   /** The creek carrier of whichever flank is a creek cell, or null if neither is (the deck then spans a main river). */
   private RiverProvider firstCarrier(Parcel a, Parcel b) {
      RiverProvider c = this.carrierOf(a);
      return c != null ? c : this.carrierOf(b);
   }

   private boolean rectHasMainRiverCell(int baseX, int baseZ, int sizeX, int sizeZ) {
      for (int x = baseX; x < baseX + sizeX; x++) {
         for (int z = baseZ; z < baseZ + sizeZ; z++) {
            Parcel p = this.getParcel(x, z);
            if (p instanceof RiverParcel && ((RiverParcel) p).getCarrier() == null) {
               return true;
            }
         }
      }
      return false;
   }



   /**
    * Carves a leftover-land rectangle around a creek band, road-wrapped on
    * every side: edge roads continue past the band's ends, cap rows close
    * its ends, and each remaining piece goes back through placeLeftoverLand()
    * (which recurses for any other band). Works in (along, across) space so
    * one method serves both orientations.
    */



   /**
    * The creek-site provider for this world, built on first use from the generator alone -- no tile has to exist. It is
    * shared by every tile, by the plan (the water field and the layout) and by /creek, so all of them see the same creeks.
    * It reads the plan's own water (the rivers and the ocean of WaterField), the highway network and the layout's
    * super-cell lines, so a site is only drawn where the plan can hold both its building and its water.
    */



   /** R30ea bounded-site planner: share one deterministically accepted site set per generator. */
   public static BoundedRiverSites boundedSitesFor(final MetropolisGenerator generator) {
      synchronized (BOUNDED_SITE_PROVIDERS) {
         BoundedRiverSites current = BOUNDED_SITE_PROVIDERS.get(generator);
         if (current != null) return current;
         final long seed = generator.getWorldSeed();
         final WaterField water = WorldLayouts.waterFor(seed);
         final WorldLayout layout = WorldLayouts.forSeed(seed);
         final HighwayNetwork highway = HighwayNetworks.forSeed(seed);
         final ContextProvider contexts = generator.getContextProvider();
         if (layout == null || highway == null || water == null) {
            throw new IllegalStateException("Bounded sites require a world layout and highway plan before domain solving");
         }
         BoundedRiverSites sites = new BoundedRiverSites(seed, water.mainRiver(), water.zBoundRiver(),
                 generator.getClipboardProvider(), new BoundedRiverSites.Rules() {
            private boolean roadAt(HighwayNetwork.Snapshot lanes, int x, int z, int minX, int minZ) {
               if (lanes.at(x,z)) return true;
               int pitch = layout.pitch();
               return (Math.floorMod(x-layout.offX(),pitch)==0 &&
                       Math.floorDiv(z-layout.offZ(),pitch)==Math.floorDiv(minZ-layout.offZ(),pitch)) ||
                      (Math.floorMod(z-layout.offZ(),pitch)==0 &&
                       Math.floorDiv(x-layout.offX(),pitch)==Math.floorDiv(minX-layout.offX(),pitch));
            }
            @Override public int[] alignedParkBase(int x, int z, int w, int h) {
               int[] a = BoundedParkAlignment.align(x,z,w,h,layout.pitch(),layout.offX(),layout.offZ(),
                       PluginConfig.getBlockSizeMin());
               final int bx=a[0], bz=a[1];
               HighwayNetwork.Snapshot lanes = highway.snapshot(bx-3,bz-3,bx+w+3,bz+h+3);
               return FixedParkRingRoads.alignToExistingRoad(bx,bz,w,h,
                       (px,pz) -> roadAt(lanes,px,pz,bx,bz));
            }
            @Override public boolean parkAndRingAvailable(int bx,int bz,int w,int h,
                      Clipboard clipboard,Direction direction) {
               int maxX=bx+w-1,maxZ=bz+h-1;
               int tileX=Math.floorDiv(bx,64)*64,tileZ=Math.floorDiv(bz,64)*64;
               if(bx-1<=tileX || bz-1<=tileZ || maxX+1>=tileX+63 || maxZ+1>=tileZ+63)
                  return false;
               int pitch=layout.pitch(),offX=layout.offX(),offZ=layout.offZ();
               if(Math.floorDiv(bx-offX,pitch)!=Math.floorDiv(maxX-offX,pitch) ||
                  Math.floorDiv(bz-offZ,pitch)!=Math.floorDiv(maxZ-offZ,pitch) ||
                  Math.floorMod(bx-offX,pitch)==0 || Math.floorMod(bz-offZ,pitch)==0)
                  return false;
               if(PluginConfig.isLandmarkZoneMatchEnabled() &&
                  !LandmarkZoneRule.allowsFootprint(clipboard,
                      UrbanGrid.zonesAround(contexts,new Cartesian2D(bx,bz),new Cartesian2D(w,h))))
                  return false;
               HighwayNetwork.Snapshot lanes=highway.snapshot(bx-2,bz-2,maxX+2,maxZ+2);
               if(FixedParkRingRoads.parallelsNewRing(bx,bz,w,h,
                       (px,pz)->roadAt(lanes,px,pz,bx,bz))) return false;
               for(int px=bx-1;px<=maxX+1;px++) for(int pz=bz-1;pz<=maxZ+1;pz++) {
                  boolean inPark=px>=bx&&px<=maxX&&pz>=bz&&pz<=maxZ;
                  if(inPark&&lanes.at(px,pz)) return false;
                  if(water.baseZoneKind(px,pz)!=WaterField.Kind.NONE) return false;
               }
               return true;
            }
            @Override public boolean northSouthHighwayAt(int x,int z) {
               return (BoundedHighwayAxis.at(highway,x,z)&BoundedHighwayAxis.NORTH_SOUTH)!=0;
            }
            @Override public boolean eastWestHighwayAt(int x,int z) {
               return (BoundedHighwayAxis.at(highway,x,z)&BoundedHighwayAxis.EAST_WEST)!=0;
            }
            @Override public boolean bridgeableHighwayCrossingAt(int x,int z,BoundedRiverSegment river) {
               return BoundedHighwayAxis.completeCrossing(highway,river,x,z);
            }
            @Override public boolean existingWaterAt(int x,int z) {
               return water.riverAt(x,z)!=null || water.baseZoneKind(x,z)==WaterField.Kind.OCEAN;
            }
            @Override public boolean unrelatedWaterAt(int x,int z) { return existingWaterAt(x,z); }
            @Override public boolean unrelatedWaterAt(int x,int z,RiverProvider receiver) {
               RiverProvider xRiver=water.mainRiver(), zRiver=water.zBoundRiver();
               return (xRiver!=null && xRiver!=receiver && xRiver.isRiverOrBank(x,z)) ||
                      (zRiver!=null && zRiver!=receiver && zRiver.isRiverOrBank(x,z)) ||
                      water.baseZoneKind(x,z)==WaterField.Kind.OCEAN;
            }
         });
         BOUNDED_SITE_PROVIDERS.put(generator,sites);
         return sites;
      }
   }

   private static final java.util.Set<MetropolisGenerator> CREEKS_INSTALLED =
           java.util.Collections.newSetFromMap(new java.util.WeakHashMap<MetropolisGenerator, Boolean>());

   /**
    * Puts the creeks into the world plan, once per world and before the plan is first asked anything: the creek's water becomes
    * water of the plan's water field (so the layout stays out of it and the tiles paint it), and the building each creek leaves
    * becomes a fixed block of the layout (so it is carved around and filled like any other block). With no layout (no highway
    * network) there is no plan to put them in, and no creek.
    */
   /**
    * World-scope plan initialization, before any grid requests a domain. R30ea
    * registers zone contexts, landmarks and creeks in that order; repeating this
    * here during tile population is too late when earlier site probes solved a
    * domain already. This continues to use WorldLayouts' shared planner.
    */
   public static void prepareWorldPlan(MetropolisGenerator generator) {
      if (generator == null || generator.getWorldSeed() == null) return;
      long seed = generator.getWorldSeed();
      WorldLayout layout = WorldLayouts.forSeed(seed);
      if (layout == null) return;
      ContextProvider contexts = generator.getContextProvider();
      if (contexts instanceof ContextProviderSimplex) {
         final DistrictContextAssigner assigner = ((ContextProviderSimplex) contexts).getDistrictAssigner();
         if (assigner != null && assigner.isUsable()) {
            layout.setZoner((bx, bz, sx, sz, minX, minZ, maxX, maxZ) -> {
               DistrictContextAssigner.TierLookup none = (x, z) -> -1;
               return assigner.assign(bx, bz, sx, sz, minX, minZ, maxX, maxZ, none, null).contexts;
            });
         }
      }
      if (PluginConfig.isLandmarkDistrictsEnabled()) {
         layout.setLandmarkPlanner(new LandmarkPlans(seed, generator.getClipboardProvider(),
                 contexts, PluginConfig.getBlockSize()));
      }
      installCreeks(generator);
   }

   private static void installCreeks(MetropolisGenerator generator) {
      synchronized (CREEKS_INSTALLED) {
         if (!CREEKS_INSTALLED.add(generator)) return;
         long seed=generator.getWorldSeed();
         WorldLayout layout=WorldLayouts.forSeed(seed);
         if(layout==null || !RiverConfig.isRiverEnabled()) return;
         // Site, origin park, and channel are installed together before domain solving.
         if(layout.hasSolvedDomains()) {
            Minions.w("[R14-CREEK-LIFECYCLE] PLAN_SOLVED_BEFORE_ORIGIN seed=%d mode=BOUNDED", seed);
            return;
         }
         final BoundedRiverSites sites=boundedSitesFor(generator);
         layout.setFixedPlanner(new BoundedRiverLots(sites,generator.getContextProvider()));
         WorldLayouts.waterFor(seed).setCreeks(new WaterField.CreekSource() {
            @Override public boolean waterOrBank(int x,int z) { return sites.wetAt(x,z); }
            @Override public boolean reservedBankAt(int x,int z) { return sites.reservedBankAt(x,z); }
            @Override public boolean hasExactBankReservation() { return true; }
            @Override public boolean useRiverPlanningLattice() { return true; }
            @Override public boolean protectedDryParkAt(int x,int z) { return sites.protectedDryParkAt(x,z); }
            @Override public double nearestBankDistanceBlocks(double x,double z,double maxDistance) {
               return sites.nearestBankDistanceBlocks(x,z,maxDistance);
            }
         });
      }
   }



   // The creek each creek chunk of this tile belongs to: this tile's own carrier of the site (a carrier stores this tile's
   // bank-edge samples, so every tile owns its copy).


   /**
    * Gives this tile its own carrier of every creek that can touch it (water, bank or the shore beside it), and records each
    * creek's band and protected cells for the bridge and reclaim code. Nothing is carved here: the plan decides where the water,
    * the street and the building lot are, and placeFromPlan paints them. Idempotent.
    */
   /** Final-plan integrity probe: a river must never be counted as accepted
    * while its mandatory origin park was rejected. The park's perimeter is
    * its OWN district road ring. The river has no extra road ring. This is a
    * read-only diagnostic, not an attempt to repair/repaint a failed world. */
   private void auditBoundedParkOwnership() {
      if (boundedSitesOf.isEmpty()) return;
      WorldLayout layout = WorldLayouts.forSeed(generator.getWorldSeed());
      if (layout==null) return;
      for (BoundedRiverSites.Site site:boundedSitesOf.values()) {
         if (site.hostTileX*64!=root.X || site.hostTileZ*64!=root.Y) continue;
         int x0=site.lotBaseX,z0=site.lotBaseZ;
         int x1=x0+site.lotSizeX-1,z1=z0+site.lotSizeZ-1;
         WorldLayout.Rect block=layout.blockAt(x0,z0);
         WorldLayout.Landmark fixed=block==null?null:layout.landmarkAt(block);
         boolean matching=block!=null && block.minX==x0 && block.maxX==x1
                 && block.minZ==z0 && block.maxZ==z1 && fixed!=null
                 && fixed.choice instanceof LandmarkPlans.Choice
                 && ((LandmarkPlans.Choice)fixed.choice).clip==site.clipboard;
         if (!matching) {
            Minions.w("[R14-CREEK-LIFECYCLE] ISSUE=BOUNDED_WATER_WITHOUT_ORIGIN_PARK site=%s tile=(%d,%d) park=(%d,%d..%d,%d) block=%s fixed=%s",
                    site.id(),root.X,root.Y,x0,z0,x1,z1,block,fixed);
            continue;
         }
         int gaps=0,wetWithoutBridge=0;
         for(int x=x0-1;x<=x1+1;x++)for(int z=z0-1;z<=z1+1;z++){
            if(x>=x0&&x<=x1&&z>=z0&&z<=z1) continue;
            int kind=layout.kindAt(x,z);
            if(kind!=WorldLayout.STREET && kind!=WorldLayout.LANE)gaps++;
            if(site.ownership.at(x,z)==BoundedRiverOwnershipPlan.Owner.PARK_BRIDGE
                    && layout.creekOutletRingAxisAt(x,z)==0)wetWithoutBridge++;
         }
         if(gaps!=0 || wetWithoutBridge!=0)
            Minions.w("[R14-CREEK-LIFECYCLE] ISSUE=BOUNDED_ORIGIN_RING_INCOMPLETE site=%s roadGaps=%d missingOutletBridges=%d",
                    site.id(),gaps,wetWithoutBridge);
         else if(PluginConfig.isCreekPaintLifecycleDebugEnabled())
            Minions.w("[R14-CREEK-LIFECYCLE] PARK_ORIGIN_AND_RING_VALIDATED site=%s tile=(%d,%d)",site.id(),root.X,root.Y);
      }
   }

   private void registerCreeks() {
      if (this.creekPhaseDone) return;
      this.creekPhaseDone = true;
      if (RiverConfig.isRiverEnabled()) this.registerBoundedRivers();
   }

   private RiverProvider activeCreekCarrierAt(int x,int z) {
      BoundedRiverSites.Site site=boundedSitesFor(this.generator).siteAt(x,z);
      return site==null?null:this.boundedCarrierOf.get(site.id());
   }

   /** Register site-local river carriers only for sites accepted by the world plan. */
   private void registerBoundedRivers() {
      int tx=Math.floorDiv(this.root.X,64),tz=Math.floorDiv(this.root.Y,64);
      List<BoundedRiverSites.Site> sites=boundedSitesFor(this.generator).sitesCovering(tx,tz);
      int minX=this.root.X,maxX=minX+63,minZ=this.root.Y,maxZ=minZ+63;
      if(!sites.isEmpty() && PluginConfig.isCreekPhysicalPaintDebugEnabled())
         this.boundedPaintAudit=new ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverLifecycleAudit(minX,minZ,Minions::w);
      if(!sites.isEmpty() && PluginConfig.isCreekPaintLifecycleDebugEnabled())
         this.boundedFailureTrace=new BoundedCreekFailureTrace(minX,minZ,Minions::w);
      for(BoundedRiverSites.Site site:sites) {
         if (this.boundedFailureTrace != null) {
            for (int z=minZ;z<=maxZ;z++) for (int x=minX;x<=maxX;x++) {
               this.boundedFailureTrace.proposal(site.id(),x,z,site.ownership.at(x,z));
            }
         }
         BoundedRiverSegment river=site.newCarrier();
         this.boundedCarrierOf.put(site.id(),river);
         this.boundedSitesOf.put(site.id(),site);
         this.creekCarriers.add(river);
         boolean alongZ=river.isZBound();
         int alongLo=Math.max(alongZ?minZ:minX,river.getAlongLoChunk());
         int alongHi=Math.min(alongZ?maxZ:maxX,river.getAlongHiChunk());
         if(alongLo>alongHi) continue;
         double left=Double.POSITIVE_INFINITY,right=Double.NEGATIVE_INFINITY;
         for(int along=alongLo;along<=alongHi;along++) {
            double at=river.spineAcross(along);
            if(Double.isFinite(at)){left=Math.min(left,at);right=Math.max(right,at);}
         }
         if(left>right)continue;
         double envelope=river.getEnvelopeHalfWidth();
         int acrossLo=Math.max(alongZ?minX:minZ,(int)Math.floor(left-envelope));
         int acrossHi=Math.min(alongZ?maxX:maxZ,(int)Math.ceil(right+envelope));
         if(acrossLo>acrossHi)continue;
         this.creekBands.add(new CreekBand(river,alongZ,alongLo,alongHi,acrossLo,acrossHi));
         for(int along=alongLo;along<=alongHi;along++)
            for(int across=acrossLo;across<=acrossHi;across++) {
               int px=alongZ?across:along,pz=alongZ?along:across;
               if(site.ownsWetCell(px,pz))this.creekProtectedCells.add(cellKey(px,pz));
            }
      }
   }

   /**
    * One chunk of a creek's water, bank or shore: a river parcel carried by this tile's copy of the creek. Where a highway lane
    * runs over the creek right ahead of it along its own course, the lane keeps its road bed and this side builds the retaining
    * wall and the drain pipe, exactly as for the main river (placeRiverParcel).
    */
   private boolean creekImplementationProbeLogged = false;

   private void placeCreekAt(int chunkX, int chunkZ) {
      if (!this.creekImplementationProbeLogged) {
         this.creekImplementationProbeLogged = true;
         Minions.dRiver("%s", "[METROPOLIS-CREEK-PROBE V2026-10-30-BANK-ACTIVE-01] ACTIVE CREEK IMPLEMENTATION CALLED: placeCreekAt chunk=(" + chunkX + "," + chunkZ + ") tile=(" + this.root.X + "," + this.root.Y + ")");
      }
      BoundedRiverSites.Site site=boundedSitesFor(this.generator).siteAt(chunkX,chunkZ);
      if(site!=null && !site.ownsWetCell(chunkX,chunkZ)) {
         WorldLayout layout=WorldLayouts.forSeed(this.generator.getWorldSeed());
         BoundedRiverOwnershipPlan.Owner owner=BoundedRiverDryHandoff.resolveCell(
                 site.ownership.at(chunkX,chunkZ),layout.kindAt(chunkX,chunkZ),
                 layout.contextAt(chunkX,chunkZ));
         if(!BoundedRiverDryHandoff.mayPlaceRiver(owner)) return;
      }
      RiverProvider creek=this.activeCreekCarrierAt(chunkX,chunkZ);
      if(creek==null) {
         Minions.w("BOUNDED CREEK CARRIER MISSING chunk=(%d,%d) tile=(%d,%d)",
                 chunkX,chunkZ,this.root.X,this.root.Y);
         return;
      }
      RiverParcel parcel = new RiverParcel(this, new Cartesian2D(chunkX, chunkZ), creek);
      if (creek.isZBound()) {
         if (this.isLaneOverCreek(creek, chunkX, chunkZ - 1)) {
            parcel.markHighwayCrossingZ(true);
         } else if (this.isLaneOverCreek(creek, chunkX, chunkZ + 1)) {
            parcel.markHighwayCrossingZ(false);
         }
      } else if (this.isLaneOverCreek(creek, chunkX - 1, chunkZ)) {
         parcel.markHighwayBoundaryX(true);
      } else if (this.isLaneOverCreek(creek, chunkX + 1, chunkZ)) {
         parcel.markHighwayBoundaryX(false);
      }
   }

   private boolean isLaneOverCreek(RiverProvider creek, int x, int z) {
      return this.networkPieceAt(x, z) != null && creek.isRiverOrBank(x, z);
   }

   /**
    * Reserves every creek site that touches this tile. Runs once, right after the river corridor is
    * carved and BEFORE that corridor's reclaim and every ordinary build -- the same slot the main
    * river's own reservation occupies, so ordinary carving simply works around what is already here.
    * Idempotent; safe to call from every branch of the corridor logic.
    */



   /**
    * The creek, if any, whose WATER covers this exact block column. Used by every river-carving
    * parcel so that a creek's channel exists wherever the creek's own maths says it does, no matter
    * which kind of cell -- a creek's own, the main river's, a bridge deck -- happens to hold the chunk.
    */
   /** Signed distance from a column to the nearest accepted creek waterline.
    * Negative values lie within water; +Infinity means no accepted creek is nearby.
    * Shares the exact creek geometry used by RiverParcel, rather than a parcel-edge approximation.
    */
   public double nearestCreekShoreDistance(int blockX, int blockZ) {
      double nearest = Double.POSITIVE_INFINITY;
      for (RiverProvider creek : this.creekCarriers) {
         double signed = creek.distanceFromCenterBlock(blockX, blockZ);
         if (Double.isNaN(signed)) continue;
         double d = Math.abs(signed) - creek.getHalfWidthBlocks();
         if (d < nearest) nearest = d;
      }
      return nearest;
   }

   /**
    * During initial parcel assignment, honor a short dry bank extending from
    * this exact bounded creek to a genuine planned road. No arbitrary nearby
    * creek is borrowed: the interior end must belong to the SAME accepted
    * site's immutable water/bank reservation. This is not a terrain cleanup.
    */
   private RiverProvider plannedCreekRoadBankCarrier(int x, int z,
           WorldLayout layout, WaterField water,
           ch.k42.metropolis.grid.urbanGrid.layout.PlanRegions regions) {
      if (!RiverConfig.isRiverEnabled() || boundedCarrierOf.isEmpty()
              || !creekRoadBankDryCell(layout, water, x, z)) return null;
      for (java.util.Map.Entry<String, RiverProvider> entry : boundedCarrierOf.entrySet()) {
         BoundedRiverSites.Site site = boundedSitesOf.get(entry.getKey());
         if (site == null) continue;
         RiverProvider creek = entry.getValue();
         if (CreekRoadBankOwner.belongs(x, z, creek.isZBound(), new CreekRoadBankOwner.Cells() {
            @Override public boolean dryBankCandidate(int bx, int bz) {
               return creekRoadBankDryCell(layout, water, bx, bz);
            }
            @Override public boolean realRoad(int bx, int bz) {
               int kind = layout.kindAt(bx, bz);
               return kind == WorldLayout.LANE || (kind == WorldLayout.STREET
                       && regions != null && !regions.dissolvedAt(bx, bz));
            }
            @Override public boolean sameCreekBank(int bx, int bz) {
               return site.ownsRiverCell(bx, bz) && creek.isRiverOrBank(bx, bz);
            }
         })) return creek;
      }
      return null;
   }

   private static boolean creekRoadBankDryCell(WorldLayout layout, WaterField water,
           int x, int z) {
      return layout.kindAt(x, z) == WorldLayout.ZONE
              && water.kindAt(x, z) == WaterField.Kind.NONE
              && water.baseZoneKind(x, z) == WaterField.Kind.NONE
              && !water.plannedNaturalInterfluveAt(x, z);
   }

   public RiverProvider creekWaterAt(int blockX, int blockZ) {
      for (RiverProvider creek : this.creekCarriers) {
         double d = creek.distanceFromCenterBlock(blockX, blockZ);
         if (!Double.isNaN(d) && Math.abs(d) <= creek.getHalfWidthBlocks()) {
            return creek;
         }
      }
      return null;
   }

   /** The accepted bounded creek that owns a dry, plantable river parcel.
    * Unlike creekBankAt(), this deliberately does not stop at the fixed
    * geometric bank margin: the river painter also owns its dry outer
    * shoulder, which should have woodland instead of being bare grass.
    * Read-only and backed by the accepted site and existing parcel, never
    * by a nearby unrelated creek or a new tile construction.
    */
   /** Compare the accepted bounded tributary's immutable identity rather than
    * its TILE-LOCAL Java object identity. Each UrbanGrid creates its own
    * carrier copy for bank-height samples; the global site carries a third
    * geometrically identical instance. Requiring == here made river roots
    * disappear as soon as a neighbouring tile had been populated, so tree
    * placement could depend on chunk loading order.
    *
    * This is NOT a proximity test: only the identical endpoints, orientation,
    * width and protected bank margin for the accepted site may match.
    */
   public static boolean sameBoundedCreekSite(RiverProvider a, RiverProvider b) {
      if (a == b) return a instanceof BoundedRiverSegment;
      if (!(a instanceof BoundedRiverSegment c) || !(b instanceof BoundedRiverSegment d)) return false;
      return c.sameImmutableCourse(d);
   }

   public RiverProvider creekVegetationShoreAt(int chunkX, int chunkZ, int blockX, int blockZ) {
      BoundedRiverSites.Site site = boundedSitesFor(this.generator).siteAt(chunkX, chunkZ);
      if (site == null || !site.ownsRiverCell(chunkX, chunkZ)) return null;
      RiverProvider carrier = this.boundedCarrierOf.get(site.id());
      // A tree crown from an edge chunk can probe the accepted creek just
      // outside this tile. That creek is not registered in this tile's
      // boundedCarrierOf map, yet its global accepted Site is authoritative.
      // Use the site's immutable geometry for this read-only foreign-column
      // lookup; never create a neighbour grid or depend on population order.
      if (carrier == null) carrier = site.river;
      if (carrier == null || !Double.isFinite(carrier.distanceFromCenterBlock(blockX, blockZ))
              || !Double.isFinite(carrier.widthAtBlock(carrier.isZBound() ? blockZ : blockX))) return null;
      Parcel actual = this.peekParcel(chunkX, chunkZ);
      if (actual != null) {
         if (!(actual instanceof RiverParcel)) return null;
         // Foreign UrbanGrid uses a fresh RiverProvider object for the SAME
         // site. It is a valid river owner and has the real bank samples for
         // its own tile; accept it instead of failing carrier reference ==.
         RiverProvider actualCarrier = ((RiverParcel) actual).getCarrier();
         if (!sameBoundedCreekSite(actualCarrier, site.river)) return null;
         return actualCarrier;
      }
      return carrier;
   }

   /** The creek whose actual bank PROFILE can affect a block column. The
    * main river must recognize the creek's banks as well as its open water
    * or a confluence becomes a water-filled trench through a vertical wall.
    * This query is local and read-only: it never changes the main river's
    * geometry or creates a second terrain writer. */
   public RiverProvider creekBankAt(int blockX, int blockZ) {
      RiverProvider best = null;
      double smallest = Double.POSITIVE_INFINITY;
      for (RiverProvider creek : this.creekCarriers) {
         double signed = creek.distanceFromCenterBlock(blockX, blockZ);
         if (Double.isNaN(signed)) continue;
         double fromWater = Math.abs(signed) - creek.getHalfWidthBlocks();
         if (fromWater > creek.getFallbackBankMarginBlocks()) continue;
         if (fromWater < smallest) {
            smallest = fromWater;
            best = creek;
         }
      }
      return best;
   }

   private boolean inLotFootprint(int x, int z) {
      for (BoundedRiverSites.Site site : this.boundedSitesOf.values()) {
         if (x>=site.lotBaseX && x<site.lotBaseX+site.lotSizeX
                 && z>=site.lotBaseZ && z<site.lotBaseZ+site.lotSizeZ) return true;
      }
      return false;
   }

   /**
    * Creek counterpart of computeBankEdges()/computeBankEdgesZ(): scans, per tile, the real parcel-grid
    * edge on both sides of the creek along its own perpendicular, applies the same slope-limited
    * majorant, and records the result on the creek's own carrier (a creek's geometry, unlike the one
    * shared main river, is per-site). Must run after every district is filled -- see the call site.
    * The recorded values are DISTANCES from the centerline, which is meaningful whichever way the
    * creek runs, so both sides use the same majorant direction.
    */
   /**
    * Determine creek bank limits from the FINAL immutable water/street plan,
    * instead of giving every creek a fixed-width bank regardless of the road.
    * Each sample stops at the near face of the first foreign chunk (street,
    * highway, building or land not owned by this creek). The shared plan
    * makes the result identical across tiles and independent of population
    * order. No terrain or road block is modified here.
    */
   private void computeCreekBankEdges(RiverProvider creek, int tileMinX, int tileMaxX, int tileMinZ, int tileMaxZ) {
      final int count = creek.getBankEdgeSampleCount();
      if (count <= 0) return;
      WorldLayout layout = this.cityLayout();
      WaterField water = this.planWater();
      if (layout == null || water == null) return;
      final double half = creek.getHalfWidthBlocks();
      final double margin = Math.max(1.0, creek.getFallbackBankMarginBlocks());
      final double maxRun = creek.getRampMaxRunBlocks();
      final double cap = half + Math.max(1.0, maxRun > 0 ? Math.min(maxRun, margin) : margin);
      double[] negative = new double[count];
      double[] positive = new double[count];
      int constrained = 0;
      int squeezed = 0;
      for (int i = 0; i < count; i++) {
         negative[i] = creekBankLimitAt(creek, layout, water, i, -1, half, cap);
         positive[i] = creekBankLimitAt(creek, layout, water, i, 1, half, cap);
         if (negative[i] < cap - 0.01) constrained++;
         if (positive[i] < cap - 0.01) constrained++;
         if (negative[i] < half + 10.0) squeezed++;
         if (positive[i] < half + 10.0) squeezed++;
      }
      // A 1-sample slope-limited closing never extends a ramp under a
      // nearby road: a narrow available bank shortens its neighbours, not
      // the other way around. This removes per-chunk sawtooth cliffs.
      double stepLimit = Math.max(1.0, RiverConfig.getRiverBankEdgeMaxSlopePerChunk());
      double[] smoothNegative = ch.k42.metropolis.grid.urbanGrid.river.CreekBankGeometry.slopeLimited(negative, half, stepLimit);
      double[] smoothPositive = ch.k42.metropolis.grid.urbanGrid.river.CreekBankGeometry.slopeLimited(positive, half, stepLimit);
      creek.recordBankEdges(smoothNegative, smoothPositive);
      Minions.dRiver("CREEK PLAN BANK tile=(%d,%d) samples=%d constrainedSides=%d squeezedBankSides=%d cap=%.1f sampleEdge[-]=%.1f sampleEdge[+]=%.1f",
              this.root.X, this.root.Y, count, constrained, squeezed, cap,
              smoothNegative[count / 2], smoothPositive[count / 2]);
   }

   private boolean plannedCreekBank(RiverProvider creek, WorldLayout layout, WaterField water,
                                    int chunkX, int chunkZ) {
      int plan = layout.kindAt(chunkX, chunkZ);
      if (creekChannelBeneathStreet(plan, water.kindAt(chunkX, chunkZ),
              creek.isRiver(chunkX, chunkZ))) return true;
      if (plan == WorldLayout.STREET || plan == WorldLayout.LANE || plan == WorldLayout.BLOCK) return false;
      if (plan == WorldLayout.ZONE && water.zoneKind(chunkX, chunkZ) == WaterField.Kind.CREEK)
         return true;
      // Bank smoothing must see the same additional short dry shoulder that
      // the parcel allocator assigns to RiverParcel, else its ramp ends at
      // the old forest seam rather than the actual road's inner edge.
      if (!creekRoadBankDryCell(layout, water, chunkX, chunkZ)) return false;
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
      return shapes != null && plannedCreekRoadBankCarrier(chunkX, chunkZ, layout,
              water, shapes.regions()) == creek;
   }

   /** Water under a real street crossing remains water in the bank profile.
    * Never give a highway lane or building this exemption: those have
    * their own culvert/foundation treatment, not an open street bridge. */
   static boolean creekChannelBeneathStreet(int plan, WaterField.Kind wet,
                                            boolean actualCreekChannel) {
      return plan != WorldLayout.LANE && plan != WorldLayout.BLOCK
              && wet == WaterField.Kind.CREEK && actualCreekChannel;
   }

   /** At a fixed along-axis site, read the first foreign plan chunk on this bank. */
   private double creekBankLimitAt(RiverProvider creek, WorldLayout layout, WaterField water,
                                          int sample, int side, double half, double cap) {
      double[] center = creek.bankEdgeSamplePoint(sample);
      return ch.k42.metropolis.grid.urbanGrid.river.CreekBankGeometry.rampEnd(
              center[0], center[1], creek.isZBound(), side, half, cap,
              (chunkX, chunkZ) -> plannedCreekBank(creek, layout, water, chunkX, chunkZ));
   }

   /** Reversible (x,z) -> long key for clearedCells/footprint dedup above -- concatenation, not XOR, since decoding has to recover both. */
   private static long cellKey(int x, int z) {
      return (((long) x) << 32) | (z & 0xFFFFFFFFL);
   }

   /**
    * Runs once, after every district AND every road/highway cell in this
    * tile has been placed. Roads and highways are laid down by
    * placeHighways()/partitionXwithRoads()/partitionZwithRoads() with zero
    * awareness of the river -- some of those cells may predate
    * fillRiverStrip() ever reserving that location (an ancestor cut can
    * insert a road strip before recursion reaches a small enough rectangle
    * for the river check to fire), so this can't assume every collision
    * was already caught upstream. Instead it just looks at the FINAL state
    * of the parcel grid: any Road/HighwayParcel cell with the river
    * flanking it on both opposite sides is, definitionally, a crossing,
    * regardless of which mechanism put the road there or in what order.
    *
    * Roads a forest reclaimed (MorphomosParcel, see dissolveMorphomosRoads())
    * never become bridges: a dissolved cell is not a Road/HighwayParcel, so
    * it is neither a flanking street nor swapped itself, and a crossing is
    * skipped when either corridor edge row it would land on was dissolved.
    */
   private void convertRoadsToBridges() {
      int bridgesPlaced = 0;
      List<int[]> placed = new ArrayList<int[]>();
      java.util.Map<Long, Parcel> replaced = new java.util.HashMap<Long, Parcel>();

      // PRIMARY: carveRiverCorridor() carves the river's ENTIRE cross-tile
      // band directly -- the corridor's interior is always RiverParcel
      // (open water, bank, or flat land -- see RiverParcel.carveColumn()),
      // and its two outer edge rows (corridorMinZ/corridorMaxZ) are ALWAYS
      // RoadParcel, whether or not a real street from either flanking land
      // strip actually lands there (see that method's javadoc). That means
      // a road cell sitting INSIDE the river, flanked by RiverParcel on
      // both opposite sides, can never exist under this scheme -- so the
      // FALLBACK scan below (the original, only check this method used to
      // have) can never fire for a direct-carve tile, which is why
      // convertRoadsToBridges() always logged "placed 0 bridge cell(s)" on
      // every ordinary tile.
      //
      // A genuine crossing instead has to be found by asking "does a real
      // street reach the corridor from BOTH flanking land strips at the
      // same X" -- i.e. is there a Road/HighwayParcel cell one row OUTSIDE
      // the corridor on the north side, and another one row outside on the
      // south side, at the same X. Only recSetDistricts()'s own district/
      // road grid places cells there (see placeCityDistricts()), so this
      // only matches actual streets, not the corridor's own boundary road.
      // Where it matches, the corridor's INTERIOR at that X -- river and
      // bank, but NOT the corridor's own two edge rows -- becomes one
      // continuous bridge deck. The edge rows (corridorMinZ/corridorMaxZ)
      // are the riverside frontage road carveRiverCorridor() lays down the
      // whole tile width regardless of whether a crossing lands there --
      // that's exactly where a crossing street actually T's/X's into it,
      // so converting those two rows too was swallowing that intersection
      // whole into the bridge deck instead of letting the crossing lead
      // off of it, reaching one extra row into each flanking land strip.
      if (this.riverCorridorPresent) {
         for (int x = this.riverCorridorTileMinX; x < this.riverCorridorTileMinX + this.riverCorridorTileWidth; x++) {
            Parcel north = this.peekParcel(x, this.riverCorridorMinZ - 1);
            Parcel south = this.peekParcel(x, this.riverCorridorMaxZ + 1);
            boolean northStreet = this.isCrossingStreet(x, this.riverCorridorMinZ - 1);
            boolean southStreet = this.isCrossingStreet(x, this.riverCorridorMaxZ + 1);
            if (!northStreet || !southStreet) {
               continue;
            }
            // A forest that reclaimed the riverside edge road on either side leaves
            // nothing for the deck to land on: no bridge into a dissolved road.
            if (this.peekParcel(x, this.riverCorridorMinZ) instanceof MorphomosParcel
                    || this.peekParcel(x, this.riverCorridorMaxZ) instanceof MorphomosParcel) {
               continue;
            }

            boolean wasHighway = north instanceof HighwayParcel || south instanceof HighwayParcel;

            // Where the flanking street is the HIGHWAY RING, the corridor
            // edge row on that side is NOT a riverside road:
            // carveRiverCorridor() deliberately skips it (nearIsBoundary/
            // farIsBoundary) because the ring already is that frontage
            // road, so the row is left as ordinary RiverParcel. The loop
            // below excludes both edge rows (to keep a real riverside
            // road as the T where a street leads off it), which is
            // exactly right for a RoadParcel edge row -- but here it
            // strands that one RiverParcel row between the end of the
            // deck and the highway: every such bridge stops one chunk
            // short. Include the edge row on any side the highway is
            // directly against. The RiverParcel guard inside the loop
            // still skips anything that isn't open river/land anymore
            // (reclaimed districts, road cells), so nothing else changes.
            int zStart = this.riverCorridorMinZ + 1;
            int zEnd = this.riverCorridorMaxZ - 1;
            if (north instanceof HighwayParcel) {
               zStart = this.riverCorridorMinZ;
            }
            if (south instanceof HighwayParcel) {
               zEnd = this.riverCorridorMaxZ;
            }
            // deckAxisX=false: this corridor runs east-west (see
            // placeCityDistricts()'s isEastWestOriented() guard), so a
            // street crossing it runs north-south -- same convention
            // documented on the fallback scan below.
            for (int z = zStart; z <= zEnd; z++) {
               // reclaimRiverDistricts() runs before this method (see
               // placeCityDistricts()) and overwrites whichever corridor
               // cells measurement proved clear of water with ordinary
               // district parcels -- buildings, and the road
               // separators/water-frontage roads that go with them (see
               // that method's own javadoc). Those cells are no longer
               // RiverParcel by the time this loop runs, even though
               // they're still inside [corridorMinZ+1, corridorMaxZ-1] at
               // an X that happens to have a street on both flanking
               // edges. Converting them to BridgeParcel anyway would
               // bulldoze a reclaimed building (or its access road) under
               // a bridge deck it has nothing to do with -- only cells
               // that are STILL open river at this point actually need a
               // bridge.
               if (!(this.getParcel(x, z) instanceof RiverParcel)) {
                  continue;
               }
               this.placeBridge(x, z, false, wasHighway, null, placed, replaced);
               bridgesPlaced++;
            }
         }
      }

      // The north-south corridor: the same crossing rule turned a quarter
      // turn. A street that reaches the corridor from BOTH flanking land
      // strips at the same Z (a road/highway cell one column outside the
      // corridor on the west and another on the east) becomes a deck over the
      // corridor's interior columns, running east-west.
      if (this.zRiverCorridorPresent) {
         for (int z = this.zCorridorTileMinZ; z < this.zCorridorTileMinZ + this.zCorridorTileHeight; z++) {
            Parcel west = this.peekParcel(this.zCorridorMinX - 1, z);
            Parcel east = this.peekParcel(this.zCorridorMaxX + 1, z);
            boolean westStreet = this.isCrossingStreet(this.zCorridorMinX - 1, z);
            boolean eastStreet = this.isCrossingStreet(this.zCorridorMaxX + 1, z);
            if (!westStreet || !eastStreet) {
               continue;
            }
            // Same as the east-west corridor: no deck onto an edge road a forest dissolved.
            if (this.peekParcel(this.zCorridorMinX, z) instanceof MorphomosParcel
                    || this.peekParcel(this.zCorridorMaxX, z) instanceof MorphomosParcel) {
               continue;
            }
            boolean wasHighway = west instanceof HighwayParcel || east instanceof HighwayParcel;
            int xStart = this.zCorridorMinX + 1;
            int xEnd = this.zCorridorMaxX - 1;
            if (west instanceof HighwayParcel) {
               xStart = this.zCorridorMinX;
            }
            if (east instanceof HighwayParcel) {
               xEnd = this.zCorridorMaxX;
            }
            for (int x = xStart; x <= xEnd; x++) {
               if (!(this.getParcel(x, z) instanceof RiverParcel)) {
                  continue;
               }
               // deckAxisX=true: the street runs east-west across a river
               // running north-south.
               this.placeBridge(x, z, true, wasHighway, null, placed, replaced);
               bridgesPlaced++;
            }
         }
      }

      // Active world-plan creek crossing repair. The old scan only bridged when
      // both road ends already existed in exactly the same row. In the plan a
      // creek bank is ZONE, so a legal short approach is usually RiverParcel,
      // not RoadParcel. Construct both approaches from one validated route.
      for (CreekBand band : this.creekBands) {
         // Plan the creek's whole crossing route, not just a deck against
         // whatever existing street happens to be found first. Start at
         // the actual creek-origin structure so its T-junction is evaluated
         // before optional downstream crossings. Keep the same spacing rule.
         final int originAlong = this.creekOriginAlong(band);
         java.util.List<Integer> candidateAxes = new java.util.ArrayList<Integer>();
         for (int axis = band.alongLo; axis <= band.alongHi; axis++) candidateAxes.add(axis);
         java.util.Collections.sort(candidateAxes, new java.util.Comparator<Integer>() {
            @Override public int compare(Integer lhs, Integer rhs) {
               long dl = Math.abs((long) lhs - originAlong);
               long dr = Math.abs((long) rhs - originAlong);
               if (dl != dr) return dl < dr ? -1 : 1;
               return lhs.compareTo(rhs);
            }
         });
         java.util.List<Integer> builtCrossings = new java.util.ArrayList<Integer>();
         for (int a : candidateAxes) {
            boolean spaced = true;
            for (int existingAxis : builtCrossings) {
               if (Math.abs(a - existingAxis) < 8) { spaced = false; break; }
            }
            if (!spaced) continue;
            int firstWet = Integer.MAX_VALUE, lastWet = Integer.MIN_VALUE;
            for (int c = band.acrossLo; c <= band.acrossHi; c++) {
               int x = band.alongZ ? c : a;
               int z = band.alongZ ? a : c;
               if (band.creek.isRiver(x, z)) {
                  firstWet = Math.min(firstWet, c);
                  lastWet = Math.max(lastWet, c);
               }
            }
            if (firstWet == Integer.MAX_VALUE) continue;
            // Roads are resolved across the whole creek crossing first. Only
            // chunks contacting OPEN WATER become bridge decks. Dry bank chunks
            // are ordinary approaches (even where a creek RiverParcel paints
            // the sloped bank), so the bridge never consumes land-side roads.
            java.util.List<int[]> negative = this.creekApproachPath(band, a, firstWet - 1);
            java.util.List<int[]> positive = this.creekApproachPath(band, a, lastWet + 1);
            if (negative == null || positive == null) continue;
            boolean clear = true;
            for (int c = firstWet; c <= lastWet; c++) {
               int x = band.alongZ ? c : a, z = band.alongZ ? a : c;
               Parcel current = this.peekParcel(x, z);
               if (!(current instanceof RiverParcel)
                       || ((RiverParcel) current).getCarrier() != band.creek
                       || this.inLotFootprint(x, z)
                       || this.isInsideOceanCarve(x, z)) {
                  clear = false;
                  break;
               }
            }
            if (!clear) continue;
            // A crossing too close to an established deck or the fixed
            // outlet is rejected before adding any new road or bridge.
            if (this.hasNearbyParallelCreekCrossing(band, a, firstWet, lastWet)) continue;
            this.commitCreekRouteEdges(negative);
            this.commitCreekRouteEdges(positive);
            // Two shore-to-deck connections are part of the accepted route.
            int lowBankX = band.alongZ ? firstWet - 1 : a;
            int lowBankZ = band.alongZ ? a : firstWet - 1;
            int lowDeckX = band.alongZ ? firstWet : a;
            int lowDeckZ = band.alongZ ? a : firstWet;
            int highBankX = band.alongZ ? lastWet + 1 : a;
            int highBankZ = band.alongZ ? a : lastWet + 1;
            int highDeckX = band.alongZ ? lastWet : a;
            int highDeckZ = band.alongZ ? a : lastWet;
            this.commitCreekRouteEdge(lowBankX, lowBankZ, lowDeckX, lowDeckZ);
            this.commitCreekRouteEdge(highBankX, highBankZ, highDeckX, highDeckZ);
            // Both approaches are checked before any mutation. Reserve the
            // complete connected route, retaining ordinary road on dry banks.
            for (java.util.List<int[]> path : new java.util.List[]{negative, positive}) {
               for (int[] point : path) {
                  Parcel existing = this.peekParcel(point[0], point[1]);
                  if (existing == null || existing instanceof RiverParcel || existing instanceof MorphomosParcel) {
                     this.creekApproachCells.add(cellKey(point[0], point[1]));
                     this.setParcel(point[0], point[1],
                             new RoadParcel(this, new Cartesian2D(point[0], point[1])));
                  }
               }
            }
            for (int c = firstWet; c <= lastWet; c++) {
               int x = band.alongZ ? c : a, z = band.alongZ ? a : c;
               // Even when open water narrows between bends, its dry chunk
               // remains street rather than getting an unnecessary deck.
               if (band.creek.isRiver(x, z)) {
                  this.placeBridge(x, z, band.alongZ, false, band.creek, placed, replaced);
                  bridgesPlaced++;
               } else if (this.peekParcel(x, z) instanceof RiverParcel) {
                  this.creekApproachCells.add(cellKey(x, z));
                  this.setParcel(x, z, new RoadParcel(this, new Cartesian2D(x, z)));
               }
            }
            builtCrossings.add(a);
            Minions.dRiver("CREEK CONNECTED CROSSING tile=(%d,%d) along=%d deck=[%d,%d] approaches=%d,%d",
                    this.root.X, this.root.Y, a, firstWet, lastWet, negative.size(), positive.size());
         }
      }

      // FALLBACK: the original single-cell check, kept for any road cell
      // that ends up flanked by river on both opposite sides through some
      // other path -- e.g. tryPlaceRiverBand()'s recursive bisection
      // fallback (used when isEastWestOriented() says no, so the corridor
      // scan above never ran this tile at all), or a creek. Harmless no-op
      // everywhere the corridor scan above already replaced the cell,
      // since replaced cells are BridgeParcel, not RoadParcel/HighwayParcel,
      // and so no longer match the instanceof check below.
      for (int x = 0; x < 64; x++) {
         for (int z = 0; z < 64; z++) {
            Parcel p = this.parcels[x][z];
            // A highway lane is never turned into a bridge: where a river meets one it keeps its road bed, and the
            // river side builds the retaining walls and the drain pipe (RiverParcel).
            if (p instanceof HighwayParcel) {
               continue;
            }
            boolean wasHighway = false;
            if (!(p instanceof RoadParcel)) {
               continue;
            }

            int worldX = this.root.X + x;
            int worldZ = this.root.Y + z;
            // peekParcel(), not getParcel() -- a crossing right at this
            // tile's edge needs to look at the ALREADY-BUILT neighbouring
            // tile without triggering construction of one that doesn't
            // exist yet (same read-only-neighbour reasoning as
            // StreetParcel.notifyNeighbour()). A neighbour tile that
            // simply hasn't been generated yet correctly reads as "no
            // river there" -- there's nothing to bridge over until that
            // tile actually exists.
            boolean riverEW = this.peekParcel(worldX - 1, worldZ) instanceof RiverParcel
                    && this.peekParcel(worldX + 1, worldZ) instanceof RiverParcel;
            boolean riverNS = this.peekParcel(worldX, worldZ - 1) instanceof RiverParcel
                    && this.peekParcel(worldX, worldZ + 1) instanceof RiverParcel;
            if (!riverEW && !riverNS) {
               continue;
            }

            // deckAxisX must match BridgeParcel's own documented contract:
            // true means the road runs EAST-WEST, i.e. the river flanks it
            // to the NORTH and SOUTH -- that's riverNS, not riverEW.
            //
            // riverEW (water to the east AND west of THIS road cell) means
            // the neighbouring columns at X-1/X+1 are still inside the
            // river's band while the cells at Z-1/Z+1 are still part of
            // the SAME road strip (not yet river) -- i.e. the road itself
            // is running NORTH-SOUTH through the crossing. riverNS is the
            // mirror: water north/south, road cells east/west along the
            // strip -- the road runs EAST-WEST. Assigning deckAxisX =
            // riverEW (as this used to) fed BridgeParcel exactly the
            // opposite of what its own javadoc promises, building every
            // ordinary crossing of the river's usual west-to-east flow
            // with its deck running EAST-WEST -- parallel to the river
            // instead of across it.
            //
            // If both happen to be true (a lone road cell sitting on a
            // local width/orientation wobble) default to the north-south
            // deck (deckAxisX = false), since the river's primary axis is
            // west-to-east and a north-south crossing is the far more
            // common case.
            boolean deckAxisX = riverNS && !riverEW;
            // A deck over a creek carves that creek's water, not the main river's: take the carrier
            // from the flanking river cell (null for the main rivers, whose behaviour is unchanged).
            RiverProvider spanned = deckAxisX
                    ? this.firstCarrier(this.peekParcel(worldX, worldZ - 1), this.peekParcel(worldX, worldZ + 1))
                    : this.firstCarrier(this.peekParcel(worldX - 1, worldZ), this.peekParcel(worldX + 1, worldZ));
            this.placeBridge(worldX, worldZ, deckAxisX, wasHighway, spanned, placed, replaced);
            bridgesPlaced++;
         }
      }
      int pruned = this.pruneDanglingBridges(placed, replaced);
      // R30ea validates actual final decks after all placement and pruning.
      this.auditParallelCreekBridges();
      Minions.dRiver("convertRoadsToBridges tile root=(%d,%d) -- placed %d bridge cell(s), took back %d that led nowhere",
              this.root.X, this.root.Y, bridgesPlaced - pruned, pruned);
   }

   /** R30ea read-only double-bridge audit. Parallel ordinary decks of one
    * carrier cannot occupy adjacent axes; the two lanes of a highway are
    * deliberately exempt. Report rather than silently removing infrastructure.
    */
   private void auditParallelCreekBridges() {
      int parallelPairs = 0;
      for (int z = this.root.Y; z < this.root.Y + 64; z++) {
         for (int x = this.root.X; x < this.root.X + 64; x++) {
            Parcel current = this.peekParcel(x,z);
            if (!(current instanceof BridgeParcel)) continue;
            BridgeParcel bridge = (BridgeParcel) current;
            if (bridge.getCarrier() == null) continue;
            boolean eastWest = bridge.connectsOnSide(Direction.EAST);
            int otherX = x + (eastWest ? 0 : 1);
            int otherZ = z + (eastWest ? 1 : 0);
            Parcel other = this.peekParcel(otherX,otherZ);
            if (!(other instanceof BridgeParcel)) continue;
            BridgeParcel next = (BridgeParcel) other;
            if (next.getCarrier() != bridge.getCarrier()
                    || next.connectsOnSide(Direction.EAST) != eastWest) continue;
            if (bridge.getContextType() == ContextType.HIGHWAY
                    && next.getContextType() == ContextType.HIGHWAY) continue;
            parallelPairs++;
            Minions.w("CREEK DOUBLE BRIDGE DEFECT tile=(%d,%d) adjacent parallel decks at (%d,%d) and (%d,%d); geometry must be corrected at planning time",
                    this.root.X,this.root.Y,x,z,otherX,otherZ);
         }
      }
      if (parallelPairs != 0) {
         Minions.w("CREEK DOUBLE BRIDGE AUDIT FAILED tile=(%d,%d) parallelPairs=%d",
                 this.root.X,this.root.Y,parallelPairs);
      } else if (!this.creekBands.isEmpty()) {
         Minions.dRiver("CREEK DOUBLE BRIDGE AUDIT PASSED tile=(%d,%d) parallelPairs=0",
                 this.root.X,this.root.Y);
      }
   }

   /**
    * Remove a dead creek-bank side road only if EVERY cell from its tip to
    * the first real intersection is undeveloped and dry. Road cells are
    * removed as a unit, so a straight approach cannot be punctured midway.
    * Ordinary city streets, river roads and origin bridges are protected.
    */
   /** Immutable planned roads are not temporary creek-side approach stubs. */
   public static boolean plannedRoadMustSurviveCreekPrune(int kind, boolean cityRoad) {
      return cityRoad || kind == WorldLayout.STREET || kind == WorldLayout.LANE;
   }

   private void retireCreekRoadBranches() {
      if (this.creekBands.isEmpty()) return;
      // A creek-only provisional road can be retired, but a road that the
      // immutable world plan still owns must NEVER be replaced with river
      // or forest merely because its present-day parcel neighbours make it
      // look like a dead end. That erases mandatory district frontage and
      // can shorten the approach to a real bridge.
      final WorldLayout committed = WorldLayouts.forSeed(this.generator.getWorldSeed());
      int removed = 0, branches = 0;
      java.util.Set<Long> visited = new java.util.HashSet<Long>();
      int[] dx = {1,-1,0,0}, dz = {0,0,1,-1};
      for (int z=this.root.Y;z<this.root.Y+64;z++) for (int x=this.root.X;x<this.root.X+64;x++) {
         Parcel tip=this.peekParcel(x,z);
         if (!(tip instanceof RoadParcel) || visited.contains(cellKey(x,z))) continue;
         String problem=this.roadProblem(tip,x,z);
         if (problem == null || !problem.startsWith("dead end")) continue;
         boolean creekNearby=false;
         for (int zz=-8;zz<=8 && !creekNearby;zz++) for (int xx=-8;xx<=8;xx++) {
            if (Math.abs(xx)+Math.abs(zz)>8) continue;
            Parcel nearby=this.peekParcel(x+xx,z+zz);
            if (nearby instanceof RiverParcel && this.creekCarriers.contains(((RiverParcel)nearby).getCarrier())
                    && ((RiverParcel) nearby).getCarrier().isRiver(x+xx,z+zz)) creekNearby=true;
         }
         if (!creekNearby) continue;
         java.util.List<int[]> branch=new java.util.ArrayList<int[]>();
         int px=Integer.MIN_VALUE, pz=Integer.MIN_VALUE, cx=x, cz=z;
         boolean eligible=true, anchored=false;
         for (int i=0;i<24;i++) {
            Parcel current=this.peekParcel(cx,cz);
            // The old planner cannot see a late accepted creek-local ring:
            // its former cells may still read as BLOCK or dissolved STREET.
            // Preserve that committed ring/approach just like any original
            // planned road, before retiring any alleged dead-end branch.
            if (!(current instanceof RoadParcel) || this.inLotFootprint(cx,cz)
                    || CreekRoadOwnership.protectsFromPrune(
                        committed == null ? WorldLayout.UNCLAIMED : committed.kindAt(cx,cz),
                        committed != null && committed.isCityRoadCell(cx,cz),
                        this.isAcceptedCreekLocalRoad(cx,cz))) {
               eligible=false;break;
            }
            int neighbours=0, nextX=Integer.MIN_VALUE,nextZ=Integer.MIN_VALUE;
            boolean nearBuilding=false;
            for (int j=0;j<4;j++) {
               int nx=cx+dx[j],nz=cz+dz[j];
               Parcel other=this.peekParcel(nx,nz);
               if (other instanceof ClipboardParcel) nearBuilding=true;
               if (other instanceof RoadParcel || other instanceof HighwayParcel || other instanceof BridgeParcel) {
                  neighbours++;
                  if (nx!=px || nz!=pz) {nextX=nx;nextZ=nz;}
               }
            }
            if (neighbours>=3 || (neighbours>=2 && px==Integer.MIN_VALUE)) {
               anchored=true; break; // keep the developed junction intact
            }
            if (nearBuilding) {eligible=false;break;}
            branch.add(new int[]{cx,cz});
            if (nextX==Integer.MIN_VALUE || nextX<this.root.X || nextX>=this.root.X+64
                    || nextZ<this.root.Y || nextZ>=this.root.Y+64) {eligible=false;break;}
            px=cx;pz=cz;cx=nextX;cz=nextZ;
         }
         if (!eligible || !anchored || branch.isEmpty()) continue;
         for (int[] v:branch) {
            int bx=v[0],bz=v[1];
            // Finalize parcel ownership before any chunk terrain is painted.
            // Retired road cells falling anywhere within the creek's water/bank
            // footprint MUST use the same RiverParcel painter as adjacent
            // creek chunks. ForestGenerator must not repaint those columns.
            RiverProvider creekOwner = null;
            for (RiverProvider candidate : this.creekCarriers) {
               if (creekOwner != null) break;
               for (int lz = 0; lz < 16 && creekOwner == null; lz++) {
                  for (int lx = 0; lx < 16; lx++) {
                     if (this.creekBankAt((bx << 4) + lx, (bz << 4) + lz) == candidate) {
                        creekOwner = candidate;
                        break;
                     }
                  }
               }
            }
            if (creekOwner != null) {
               this.setParcel(bx,bz,new RiverParcel(this,new Cartesian2D(bx,bz),creekOwner));
               Minions.dRiver("%s", "[METROPOLIS-CREEK-PREPAINT-HANDOFF-12] tile=("+this.root.X+","+this.root.Y+") chunk=("+bx+","+bz+") owner=CREEK");
            } else {
               if (this.creekNaturalGround == null) {
                  this.creekNaturalGround=new ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion(
                      (this.root.X*31)^this.root.Y^0x6c37,ContextType.FOREST);
               }
               this.creekNaturalGround.addRect(bx,bz,bx,bz);
               this.setParcel(bx,bz,new MorphomosParcel(new Cartesian2D(bx,bz),ContextType.FOREST,this,this.creekNaturalGround));
               Minions.dRiver("%s", "[METROPOLIS-CREEK-PREPAINT-HANDOFF-12] tile=("+this.root.X+","+this.root.Y+") chunk=("+bx+","+bz+") owner=DRY_FOREST");
            }
            this.creekApproachCells.add(cellKey(bx,bz));
            visited.add(cellKey(bx,bz));removed++;
         }
         // Overridden road types must read the final neighboring parcels,
         // not assume the original city street line still exists here.
         this.creekApproachCells.add(cellKey(cx,cz));
         branches++;
      }
      if (branches>0) Minions.dRiver("%s", "[METROPOLIS-CREEK-ROAD-PRUNE-06] tile=("+this.root.X+","+this.root.Y+") branches="+branches+" retiredCells="+removed);
   }

   /** Stable source-end crossing priority from the accepted creek site.
    * Site coordinates come from the shared world plan, never from the
    * order chunks happened to generate. */
   private int creekOriginAlong(CreekBand band) {
      for (BoundedRiverSites.Site site : this.boundedSitesOf.values()) {
         if (this.boundedCarrierOf.get(site.id()) == band.creek)
            return band.alongZ ? site.origin.chunkZ : site.origin.chunkX;
      }
      return band.alongLo;
   }

   private boolean creekOriginJunctionRoad(CreekBand band, int x, int z) {
      Parcel here = this.peekParcel(x, z);
      if (!(here instanceof RoadParcel)) return false;
      for (BoundedRiverSites.Site site : this.boundedSitesOf.values()) {
         if (this.boundedCarrierOf.get(site.id()) != band.creek) continue;
         if (x < site.lotBaseX - 1 || x > site.lotBaseX + site.lotSizeX
                 || z < site.lotBaseZ - 1 || z > site.lotBaseZ + site.lotSizeZ) continue;
         boolean onRing = x == site.lotBaseX - 1 || x == site.lotBaseX + site.lotSizeX
                 || z == site.lotBaseZ - 1 || z == site.lotBaseZ + site.lotSizeZ;
         if (!onRing) continue;
         int neighbours = 0;
         if (creekRoadNeighbor(x - 1, z)) neighbours++;
         if (creekRoadNeighbor(x + 1, z)) neighbours++;
         if (creekRoadNeighbor(x, z - 1)) neighbours++;
         if (creekRoadNeighbor(x, z + 1)) neighbours++;
         if (neighbours >= 2) return true;
      }
      return false;
   }

   /** Overrides immutable road shapes only where an actual creek crossing has
    * added an approach. Also updates the existing planned T junction where the
    * approach joins a street. Other roads retain their original shape rules.
    */
   private final java.util.Set<Long> creekPerimeterCells = new java.util.HashSet<Long>();

   /** Reject an ordinary creek bridge alongside a previously committed
    * crossing, or beside the protected origin park outlet axis. This is a
    * read-only veto; it must run before either shore is changed.
    */
   private boolean hasNearbyParallelCreekCrossing(CreekBand band, int axis,
                                                    int wetStart, int wetEnd) {
      WorldLayout layout = WorldLayouts.forSeed(this.generator.getWorldSeed());
      final int wantedAxis = band.alongZ ? 1 : 2;
      return CreekBridgeSpacing.overlapsExistingCrossing(band.alongZ, axis, wetStart, wetEnd,
              (x, z) -> {
                 Parcel existing = this.peekParcel(x, z);
                 if (existing instanceof BridgeParcel) {
                    BridgeParcel bridge = (BridgeParcel) existing;
                    if (bridge.getCarrier() == band.creek
                            && bridge.connectsOnSide(Direction.EAST) == band.alongZ) {
                       Minions.dRiver("CREEK PARALLEL BRIDGE VETO tile=(%d,%d) candidateAxis=%d existing=(%d,%d)",
                               this.root.X, this.root.Y, axis, x, z);
                       return true;
                    }
                 }
                 if (layout != null && band.creek.isRiver(x, z)
                         && layout.creekOutletRingAxisAt(x, z) == wantedAxis) {
                    Minions.dRiver("CREEK PARALLEL PARK OUTLET VETO tile=(%d,%d) candidateAxis=%d planned=(%d,%d)",
                            this.root.X, this.root.Y, axis, x, z);
                    return true;
                 }
                 return false;
              });
   }

   /** Keep accepted creek-route edges distinct from ordinary road adjacency.
    * N/E/S/W bits agree with PieceOpenness and the established RoadShapes.
    */
   private void commitCreekRouteEdges(java.util.List<int[]> route) {
      for (int i = 1; i < route.size(); i++) {
         int[] from = route.get(i - 1);
         int[] to = route.get(i);
         commitCreekRouteEdge(from[0], from[1], to[0], to[1]);
      }
   }

   private void commitCreekRouteEdge(int x, int z, int nx, int nz) {
      int dx = nx - x;
      int dz = nz - z;
      int side = dx == 1 && dz == 0 ? ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.E
              : dx == -1 && dz == 0 ? ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.W
              : dz == 1 && dx == 0 ? ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.S
              : dz == -1 && dx == 0 ? ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.N : 0;
      if (side == 0) throw new IllegalArgumentException("Non-adjacent creek road cells");
      creekRouteSides.merge(cellKey(x, z), side, (a, b) -> a | b);
      creekRouteSides.merge(cellKey(nx, nz),
              ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.opposite(side),
              (a, b) -> a | b);
   }

   /** Necessary connections committed by a complete, accepted crossing. */
   public int creekRequiredSides(int x, int z) {
      return creekRouteSides.getOrDefault(cellKey(x, z), 0);
   }

   /** The normal plan owns all ordinary roads. Only an explicitly accepted
    * creek route can extend one of its road pieces into a new junction.
    */
   public RoadType creekConnectedRoadType(int x, int z) {
      int required = creekRequiredSides(x, z);
      if (required == 0 || !(this.peekParcel(x, z) instanceof RoadParcel)) return null;
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
      int original = shapes == null ? 0
              : ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.sides(shapes.roadTypeAt(x, z));
      return creekRoadTypeFromSides(original | required);
   }

   private static RoadType creekRoadTypeFromSides(int sides) {
      boolean n = (sides & ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.N) != 0;
      boolean e = (sides & ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.E) != 0;
      boolean south = (sides & ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.S) != 0;
      boolean w = (sides & ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness.W) != 0;
      if (n) {
         if (south) return e ? (w ? RoadType.STREET_X : RoadType.STREET_T_E)
                 : (w ? RoadType.STREET_T_W : RoadType.STREET_I_NS);
         return e ? (w ? RoadType.STREET_T_N : RoadType.STREET_C_NE)
                 : (w ? RoadType.STREET_C_NW : RoadType.STREET_D_N);
      }
      if (south) return e ? (w ? RoadType.STREET_T_S : RoadType.STREET_C_SE)
              : (w ? RoadType.STREET_C_SW : RoadType.STREET_D_S);
      if (e) return w ? RoadType.STREET_I_EW : RoadType.STREET_D_E;
      return w ? RoadType.STREET_D_W : null;
   }

   /** True only for creek-side road cells published into the parcel grid by
    * the accepted creek ring or complete creek crossing route. These roads
    * may not occur in the older immutable RoadShapes snapshot: unlike
    * arbitrary unplanned STREET cells, they have a validated local owner.
    * Keep this query read-only so parcel-shape resolution cannot construct
    * districts, modify water or trigger a population repair pass. */
   public boolean isAcceptedCreekLocalRoad(int x, int z) {
      long key = cellKey(x,z);
      return (this.creekPerimeterCells.contains(key) || this.creekApproachCells.contains(key))
              && this.peekParcel(x,z) instanceof RoadParcel;
   }

   private boolean touchesCreekPerimeter(int x, int z) {
      return this.creekPerimeterCells.contains(cellKey(x-1,z))
              || this.creekPerimeterCells.contains(cellKey(x+1,z))
              || this.creekPerimeterCells.contains(cellKey(x,z-1))
              || this.creekPerimeterCells.contains(cellKey(x,z+1));
   }

   private boolean touchesCreekApproach(int x, int z) {
      return this.creekApproachCells.contains(cellKey(x + 1, z))
              || this.creekApproachCells.contains(cellKey(x - 1, z))
              || this.creekApproachCells.contains(cellKey(x, z + 1))
              || this.creekApproachCells.contains(cellKey(x, z - 1));
   }

   private boolean creekRoadNeighbor(int x, int z) {
      Parcel p = this.peekParcel(x, z);
      if (p instanceof RoadParcel || p instanceof BridgeParcel) return true;
      if (p instanceof HighwayParcel) {
         RoadType kind = ((HighwayParcel)p).resolveRoadType();
         return kind != null;
      }
      return false;
   }

   /** Find a real public road within a short, fully dry creek-shore approach.
    * The search is bounded and never traverses buildings, normal wilderness,
    * highways under water, or another carrier's river. Each path cell remains
    * in this tile, so a partially generated neighbour cannot create a gap.
    */
   private java.util.List<int[]> creekApproachPath(CreekBand band, int along, int across) {
      int sx = band.alongZ ? across : along;
      int sz = band.alongZ ? along : across;
      if (sx < this.root.X || sx >= this.root.X + 64 || sz < this.root.Y || sz >= this.root.Y + 64) return null;
      java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<int[]>();
      java.util.Map<Long, Long> prev = new java.util.HashMap<Long, Long>();
      long start = cellKey(sx, sz);
      prev.put(start, start);
      q.add(new int[]{sx, sz, 0});
      final int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
      while (!q.isEmpty()) {
         int[] at = q.removeFirst();
         int x = at[0], z = at[1], steps = at[2];
         Parcel p = this.peekParcel(x, z);
         // Origin and creek-frontage roads are valid landings IF they genuinely
         // lead somewhere. They were formerly excluded just for being in
         // creekRoadCells, making the origin T-junction impossible to attach.
         if ((p instanceof RoadParcel || p instanceof HighwayParcel)
                 && (this.roadLeadsSomewhere(x, z, java.util.Collections.<Long>emptySet())
                     || this.creekOriginJunctionRoad(band, x, z))) {
            java.util.List<int[]> path = new java.util.ArrayList<int[]>();
            long current = cellKey(x, z);
            while (current != start) {
               int px = (int)(current >> 32), pz = (int)current;
               path.add(new int[]{px, pz});
               current = prev.get(current);
            }
            path.add(new int[]{sx, sz});
            return path;
         }
         if (steps >= 12) continue;
         // The shoreline can be followed through a few naturally-owned cells,
         // but ONLY when a complete path to an existing connected street is found.
         // Never consume a building lot, water, or another river carrier.
         if (!creekDryApproachCell(band, x, z)) continue;
         for (int i = 0; i < 4; i++) {
            int nx = x + dx[i], nz = z + dz[i];
            if (nx < this.root.X || nx >= this.root.X + 64
                    || nz < this.root.Y || nz >= this.root.Y + 64) continue;
            long key = cellKey(nx, nz);
            if (prev.containsKey(key)) continue;
            Parcel next = this.peekParcel(nx, nz);
            if (creekDryApproachCell(band, nx, nz)
                    || next instanceof RoadParcel || next instanceof HighwayParcel) {
               prev.put(key, cellKey(x, z));
               q.add(new int[]{nx, nz, steps + 1});
            }
         }
      }
      return null;
   }

   /** One terrain ownership rule for creek bridge approaches. Natural ground
    * can carry a short complete connection, but cannot be used as a bridge
    * landing unless the search reaches an existing through-road. */
   private boolean creekDryApproachCell(CreekBand band, int x, int z) {
      if (this.inLotFootprint(x, z) || this.isInsideOceanCarve(x, z)
              || band.creek.isRiver(x, z)) return false;
      Parcel p = this.peekParcel(x, z);
      // A failed creek reclaim can leave a genuinely unassigned (null) dry
      // cell. It is safe as an approach only inside this accepted creek band:
      // the lot, ocean and water guards above still apply, and a complete
      // connection to a real road is required before we mutate any parcel.
      int along = band.alongZ ? z : x;
      int across = band.alongZ ? x : z;
      if (along < band.alongLo || along > band.alongHi
              || across < band.acrossLo || across > band.acrossHi) return false;
      return p == null
              || (p instanceof RiverParcel && ((RiverParcel)p).getCarrier() == band.creek)
              || p instanceof MorphomosParcel;
   }

   /** Find the first genuinely connected public road on one side of a creek.
    * Only existing road cells or dry creek-carrier slack can be traversed.
    * This never clears lots or forest to force an artificial connection.
    * Return {across coordinate, highway flag} or null.
    */
   private int[] findCreekCrossingAnchor(CreekBand band, int along, int side) {
      int edge = side < 0 ? band.acrossLo : band.acrossHi;
      for (int distance = 1; distance <= 8; distance++) {
         int across = edge + side * distance;
         int x = band.alongZ ? across : along;
         int z = band.alongZ ? along : across;
         Parcel p = this.peekParcel(x, z);
         if (p instanceof HighwayParcel || (p instanceof RoadParcel
                 && !this.creekRoadCells.contains(cellKey(x, z)))) {
            if (this.roadLeadsSomewhere(x, z, java.util.Collections.<Long>emptySet())) {
               return new int[]{across, p instanceof HighwayParcel ? 1 : 0};
            }
            return null;
         }
         if (p instanceof RoadParcel && this.creekRoadCells.contains(cellKey(x, z))) continue;
         if (p instanceof RiverParcel && ((RiverParcel) p).getCarrier() == band.creek
                 && !band.creek.isRiverOrBank(x, z)) continue;
         return null;
      }
      return null;
   }

   /** Swaps one cell for a bridge deck, remembering what it replaced so pruneDanglingBridges() can undo it. */
   private void placeBridge(int x, int z, boolean deckAxisX, boolean wasHighway, RiverProvider carrier,
                            List<int[]> placed, java.util.Map<Long, Parcel> replaced) {
      replaced.put(cellKey(x, z), this.getParcel(x, z));
      this.setParcel(x, z, new BridgeParcel(this, new Cartesian2D(x, z), deckAxisX, wasHighway, carrier));
      placed.add(new int[]{x, z});
   }

   /**
    * A bridge must join two roads. Roads a forest dissolved are MorphomosParcels by now, and a street whose
    * neighbours were all dissolved can survive as a few lonely cells; a deck onto either would span the river
    * between nothing and nothing. Each connected run of deck cells placed by convertRoadsToBridges() is therefore
    * checked: it needs a real road (RoadParcel, HighwayParcel or another bridge) on both ends, and each of those
    * landings has to lead somewhere -- onto a highway, onto a road that carries on past this deck for a few cells,
    * or out of the tile (a tile that is not built yet is not held against it). A run that fails is put back
    * exactly as it was, which is the river or road cell it replaced. Returns the number of deck cells undone.
    */
   private int pruneDanglingBridges(List<int[]> placed, java.util.Map<Long, Parcel> replaced) {
      if (placed.isEmpty()) {
         return 0;
      }
      Set<Long> deck = new HashSet<Long>();
      for (int[] c : placed) {
         deck.add(cellKey(c[0], c[1]));
      }
      Set<Long> visited = new HashSet<Long>();
      int[] dx = {1, -1, 0, 0};
      int[] dz = {0, 0, 1, -1};
      int undone = 0;
      for (int[] start : placed) {
         if (!visited.add(cellKey(start[0], start[1]))) {
            continue;
         }
         List<int[]> run = new ArrayList<int[]>();
         java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<int[]>();
         queue.add(start);
         while (!queue.isEmpty()) {
            int[] c = queue.poll();
            run.add(c);
            for (int d = 0; d < 4; d++) {
               int nx = c[0] + dx[d];
               int nz = c[1] + dz[d];
               if (deck.contains(cellKey(nx, nz)) && visited.add(cellKey(nx, nz))) {
                  queue.add(new int[]{nx, nz});
               }
            }
         }
         int runMinX = Integer.MAX_VALUE;
         int runMaxX = Integer.MIN_VALUE;
         int runMinZ = Integer.MAX_VALUE;
         int runMaxZ = Integer.MIN_VALUE;
         for (int[] c : run) {
            runMinX = Math.min(runMinX, c[0]);
            runMaxX = Math.max(runMaxX, c[0]);
            runMinZ = Math.min(runMinZ, c[1]);
            runMaxZ = Math.max(runMaxZ, c[1]);
         }
         boolean west = false;
         boolean east = false;
         boolean north = false;
         boolean south = false;
         for (int[] c : run) {
            for (int d = 0; d < 4; d++) {
               int nx = c[0] + dx[d];
               int nz = c[1] + dz[d];
               if (deck.contains(cellKey(nx, nz))) {
                  continue;
               }
               Parcel q = this.peekParcel(nx, nz);
               if (!(q instanceof RoadParcel || q instanceof HighwayParcel || q instanceof BridgeParcel)) {
                  continue;
               }
               if (!this.roadLeadsSomewhere(nx, nz, deck)) {
                  continue;
               }
               west |= nx < runMinX;
               east |= nx > runMaxX;
               north |= nz < runMinZ;
               south |= nz > runMaxZ;
            }
         }
         if ((west && east) || (north && south)) {
            continue;
         }
         for (int[] c : run) {
            Parcel original = replaced.get(cellKey(c[0], c[1]));
            if (original != null) {
               this.setParcel(c[0], c[1], original);
               undone++;
            }
         }
         Minions.dRiver("tile root=(%d,%d) -- bridge run x=[%d,%d] z=[%d,%d] has no road on both ends, taken back",
                 this.root.X, this.root.Y, runMinX, runMaxX, runMinZ, runMaxZ);
      }
      return undone;
   }

   /**
    * Does the road at (x, z) go anywhere other than into the deck being tested? True when it reaches a highway, a
    * cluster of at least six road cells, or the edge of this tile onto a road or onto a neighbour that is not built
    * yet. A road reduced to a few cells by a forest reclaiming the rest is false.
    */
   private boolean roadLeadsSomewhere(int x, int z, Set<Long> deck) {
      Set<Long> seen = new HashSet<Long>();
      java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<int[]>();
      queue.add(new int[]{x, z});
      seen.add(cellKey(x, z));
      int[] dx = {1, -1, 0, 0};
      int[] dz = {0, 0, 1, -1};
      while (!queue.isEmpty()) {
         int[] c = queue.poll();
         Parcel p = this.peekParcel(c[0], c[1]);
         if (p instanceof HighwayParcel) {
            return true;
         }
         if (seen.size() >= 6) {
            return true;
         }
         for (int d = 0; d < 4; d++) {
            int nx = c[0] + dx[d];
            int nz = c[1] + dz[d];
            long key = cellKey(nx, nz);
            if (deck.contains(key) || seen.contains(key)) {
               continue;
            }
            boolean inTile = nx >= this.root.X && nx <= this.root.X + 63 && nz >= this.root.Y && nz <= this.root.Y + 63;
            Parcel q = this.peekParcel(nx, nz);
            if (!inTile) {
               if (q == null || q instanceof RoadParcel || q instanceof HighwayParcel || q instanceof BridgeParcel) {
                  return true;
               }
               continue;
            }
            if (q instanceof RoadParcel || q instanceof HighwayParcel || q instanceof BridgeParcel) {
               seen.add(key);
               queue.add(new int[]{nx, nz});
            }
         }
      }
      return false;
   }

   /**
    * Cheap tile identifier for routine log lines -- just the root
    * coordinate, NOT the full parcel grid. toString() below dumps an
    * ASCII map of the entire 64x64 tile (one row of text per chunk row);
    * that's fine for a one-off manual dump while investigating a specific
    * tile, but several [TILE]* log call sites used to pass the grid
    * itself as a %s argument, which silently ran that full dump on EVERY
    * firing -- including [TILE][ACCUMULATE], documented as firing per
    * PARCEL. On a real pregen run that turned a single tile's routine
    * logging into hundreds of thousands of lines of ASCII art. Use this
    * for any log call that fires more than once per tile.
    */
   public String tileLabel() {
      return "tile(root=(" + this.root.X + "," + this.root.Y + "))";
   }

   @Override
   public String toString() {
      return this.asciiMap();
   }

   /** Final plan-to-parcel audit, not a repair. A road can have perfectly
    * reciprocal shapes in the ASCII map yet have vanished BEFORE that shape
    * resolver sees it. Compare every committed (non-dissolved) street/lane
    * against its actual final parcel, including creek and bridge cells.
    * Gated by the existing ASCII debug setting to avoid extra layout reads
    * in ordinary generation. The planner is always the authority here.
    */
   private void auditFinalPlannedRoadOwnership() {
      WorldLayout layout = this.cityLayout();
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.roadShapes();
      if (layout == null || shapes == null) return;
      int planned = 0, missing = 0, creekRoads = 0, lostCreekRoads = 0;
      int examples = 0;
      for (int z=this.root.Y;z<this.root.Y+64;z++) for (int x=this.root.X;x<this.root.X+64;x++) {
         int kind=layout.kindAt(x,z);
         boolean required=kind==WorldLayout.LANE || (kind==WorldLayout.STREET && shapes.isRoadStreet(x,z));
         Parcel actual=this.peekParcel(x,z);
         if (required) {
            planned++;
            if (!(actual instanceof StreetParcel)) {
               missing++;
               if (examples++<24) Minions.w("ROAD PLAN/PAINTER DISAGREEMENT chunk=(%d,%d) kind=%d plannedType=%s actual=%s",
                       x,z,kind,shapes.roadTypeAt(x,z),parcelName(actual));
            }
         }
         if (this.isAcceptedCreekLocalRoad(x,z)) {
            creekRoads++;
            if (!(actual instanceof StreetParcel)) {
               lostCreekRoads++;
               if (examples++<24) Minions.w("ACCEPTED CREEK ROAD LOST chunk=(%d,%d) parcel=%s",x,z,parcelName(actual));
            }
         }
      }
      if (missing>0 || lostCreekRoads>0)
         Minions.w("ROAD PLAN/PAINTER FAILURE tile=(%d,%d) committed=%d missing=%d creekLocal=%d creekLocalMissing=%d",
                 this.root.X,this.root.Y,planned,missing,creekRoads,lostCreekRoads);
      else Minions.i("ROAD PLAN/PAINTER AUDIT tile=("+this.root.X+","+this.root.Y+") committed="+planned
                   +" missing=0 creekLocal="+creekRoads+" creekLocalMissing=0");
   }

   /** Width of one map cell: a marker column, then the longest name, "[Hc ne]". */
   private static final int ASCII_CELL = 8;

   /**
    * The tile as text, one row per chunk row (north at the top, west on the left, like the map in F3), every
    * cell a name in brackets padded to the same width so the columns line up. Reads the parcels as they are at the
    * moment of the call, so call it after the tile has been built. The road and highway shapes are not guessed
    * here: they are what RoadParcel / HighwayParcel resolveRoadType() work out from the neighbours, the very code
    * that picks the schematic generation pastes, so the map shows what will be built.
    *
    * A marker sits in front of a cell: "!" where the piece and a neighbour disagree about whether they connect (one
    * is open toward the other and the other is not open back, or is no road at all), "?" for a dead end or an
    * isolated road cell. Under the map the road audit lists every one of them. Directions are n/e/s/w.
    *
    * <pre>
    *   [Hs n]   straight highway lane, n = the side its outer edge faces     [Hb ne]  plain highway bend, the two sides it connects
    *   [Ht n]   highway tee, n = the turn-off (the back is closed)          [Hc ne]  highway cell open on all four sides,
    *                                                                                 crosswalks on the sides named
    *   [Ri ns]  street straight (ns or ew)   [Rt n] street tee, n = turn-off   [Rc ne] street corner   [Rx] crossing   [Rd n] dead end
    *   [Pf n]   building parcel, n = the placement direction of its schematic (no tag if it has none)
    *   [Pn]     nothing placed there  [Em]  empty parcel      [Tf]  terrain fallback
    *   [Fo]     forest    [Fa] farm    [Fm] any other morphomos context
    *   [Br ew]  bridge (ew or ns)   [Bh ew] bridge that was a highway landing   [Hw ew] highway causeway
    *   [Wr]     river     [Wc] creek   [Wo] ocean    [Wb] boat    [Wt] boat tail
    * </pre>
    */
   public String asciiMap() {
      StringBuilder sb = new StringBuilder();
      sb.append("================ UrbanGrid root=(").append(this.root.X).append(',').append(this.root.Y)
              .append(") -- x across, z down, north at the top ================\n");
      sb.append("Hs Hb Ho Ht Hn Hm Hc: highway lane, bend, bend outer corner, tee, tee with sidewalk back, its mirror image, open cell | Ri Rt Rc Rx Rd: street straight, tee, corner, crossing, dead end\n");
      sb.append("Pf building parcel, Pn nothing, Fo forest, Fa farm, Br/Bh bridge, Wr river, Wc creek, Wo ocean, Wb boat, Hw causeway\n");
      sb.append("n/e/s/w after a name: Hs outer side, Hb/Ho/Hc/Rc the two sides joined, Ht/Hn/Hm/Rt/Rd the turn-off / open side, Ri/Br/Hw the axis (ns/ew), Pf placement direction\n");
      sb.append("marker ! = piece and neighbour disagree about connecting, ? = dead end or isolated road cell; patterns rotate, never mirror\n\n");
      List<String> findings = new ArrayList<String>();
      int roadCells = 0;
      sb.append("     ");
      for (int lx = 0; lx < 64; lx++) {
         sb.append(padCell(Integer.toString(lx)));
      }
      sb.append('\n');
      for (int lz = 0; lz < 64; lz++) {
         sb.append(String.format("%3d  ", lz));
         for (int lx = 0; lx < 64; lx++) {
            Parcel p = this.parcels[lx][lz];
            int wx = this.root.X + lx;
            int wz = this.root.Y + lz;
            char marker = ' ';
            if (this.openSides(p) >= 0) {
               roadCells++;
               String problem = this.roadProblem(p, wx, wz);
               if (problem != null) {
                  findings.add("(" + lx + "," + lz + ") " + this.asciiName(p) + ": " + problem);
                  marker = problem.startsWith("dead end") || problem.startsWith("isolated") ? '?' : '!';
               }
            }
            sb.append(marker).append(PieceNames.cell(this.asciiName(p), ASCII_CELL - 1));
         }
         sb.append('\n');
      }
      int mismatches = 0;
      for (String f : findings) {
         if (!f.contains(": dead end") && !f.contains(": isolated")) {
            mismatches++;
         }
      }
      sb.append("\n---- road audit: ").append(roadCells).append(" road/highway/bridge cells, ").append(mismatches)
              .append(" mismatch(es), ").append(findings.size() - mismatches).append(" dead end(s) or isolated ----\n");
      for (String f : findings) {
         sb.append(f).append('\n');
      }
      return sb.toString();
   }

   private static String padCell(String text) {
      StringBuilder p = new StringBuilder(text);
      while (p.length() < ASCII_CELL) {
         p.append(' ');
      }
      return p.toString();
   }

   /**
    * Which sides of this parcel carry the road on, as a PieceOpenness mask; -1 if it is not a road cell at all and
    * -2 if it is a road cell whose openness is not known.
    */
   private int openSides(Parcel p) {
      if (p instanceof RoadParcel) {
         return PieceOpenness.sides(((RoadParcel) p).resolveRoadType());
      }
      if (p instanceof HighwayParcel) {
         return PieceOpenness.sides(((HighwayParcel) p).resolveRoadType());
      }
      if (p instanceof BridgeParcel) {
         BridgeParcel b = (BridgeParcel) p;
         return b.connectsOnSide(Direction.EAST) ? PieceOpenness.E | PieceOpenness.W : PieceOpenness.N | PieceOpenness.S;
      }
      return -1;
   }

   /**
    * What is wrong with the road cell at (wx, wz), or null. Two neighbouring road cells are connected exactly when
    * each is open toward the other. A cell open toward a neighbour that is not open back (or is no road at all, as a
    * street dissolved by a forest is not), or closed toward a neighbour that is open toward it, is a mismatch. A
    * neighbour in a tile that is not built yet is not held against anything.
    */
   private String roadProblem(Parcel p, int wx, int wz) {
      int mine = this.openSides(p);
      if (mine == 0) {
         return "isolated road cell, open on no side";
      }
      int[] dx = {0, 1, 0, -1};
      int[] dz = {-1, 0, 1, 0};
      int[] bit = {PieceOpenness.N, PieceOpenness.E, PieceOpenness.S, PieceOpenness.W};
      String[] name = {"north", "east", "south", "west"};
      StringBuilder problems = new StringBuilder();
      if (p instanceof RoadParcel) {
         // a street one chunk wide is the only kind there is: a 2x2 block of street chunks is two streets side by
         // side (most often the street round the edge of one tile against the one round the edge of the next)
         boolean wide = false;
         for (int ax = -1; ax <= 0 && !wide; ax++) {
            for (int az = -1; az <= 0 && !wide; az++) {
               wide = this.peekParcel(wx + ax, wz + az) instanceof RoadParcel
                       && this.peekParcel(wx + ax + 1, wz + az) instanceof RoadParcel
                       && this.peekParcel(wx + ax, wz + az + 1) instanceof RoadParcel
                       && this.peekParcel(wx + ax + 1, wz + az + 1) instanceof RoadParcel;
            }
         }
         if (wide) {
            return "doubled: part of a street two or more chunks wide";
         }
         // a street must never run alongside a highway: a lane beside it that is not open toward it, with street
         // on both sides of this cell along the lane
         for (int d = 0; d < 4; d++) {
            if (!(this.peekParcel(wx + dx[d], wz + dz[d]) instanceof HighwayParcel)) {
               continue;
            }
            int sx = dz[d] != 0 ? 1 : 0;
            int sz = dx[d] != 0 ? 1 : 0;
            if (this.openSides(this.peekParcel(wx + sx, wz + sz)) >= 0 && this.openSides(this.peekParcel(wx - sx, wz - sz)) >= 0
                    && ((((HighwayParcel) this.peekParcel(wx + dx[d], wz + dz[d])).resolveRoadType() == null)
                    || !PieceOpenness.opens(((HighwayParcel) this.peekParcel(wx + dx[d], wz + dz[d])).resolveRoadType(), PieceOpenness.opposite(bit[d])))) {
               problems.append("runs alongside the highway lane to the ").append(name[d]).append("; ");
            }
         }
      }
      int connected = 0;
      for (int d = 0; d < 4; d++) {
         Parcel q = this.peekParcel(wx + dx[d], wz + dz[d]);
         int theirs = this.openSides(q);
         boolean iOpen = (mine & bit[d]) != 0;
         if (q == null) {
            if (iOpen) {
               connected++;
            }
            continue;
         }
         if (theirs < 0) {
            if (iOpen) {
               problems.append("open to ").append(name[d]).append(", which is no road (").append(this.asciiName(q)).append("); ");
            }
            continue;
         }
         boolean theyOpen = (theirs & PieceOpenness.opposite(bit[d])) != 0;
         if (iOpen && theyOpen) {
            connected++;
         } else if (iOpen) {
            problems.append("open to ").append(name[d]).append(" but ").append(this.asciiName(q)).append(" is closed toward it; ");
         } else if (theyOpen) {
            problems.append("closed to ").append(name[d]).append(" but ").append(this.asciiName(q)).append(" is open toward it; ");
         }
      }
      if (problems.length() > 0) {
         return problems.toString().trim();
      }
      if (connected <= 1 && !(p instanceof HighwayParcel)) {
         return connected == 0 ? "isolated road cell, nothing connects to it" : "dead end";
      }
      return null;
   }

   /** The name of one parcel in the debug map (see asciiMap()). */
   private String asciiName(Parcel p) {
      if (p == null) {
         return "Pn";
      }
      if (p instanceof RoadParcel) {
         String n = PieceNames.of(((RoadParcel) p).resolveRoadType());
         return n != null ? n : "R?";
      }
      if (p instanceof HighwayParcel) {
         String n = PieceNames.of(((HighwayParcel) p).resolveRoadType());
         return n != null ? n : "H?";
      }
      if (p instanceof BridgeParcel) {
         boolean ew = ((BridgeParcel) p).connectsOnSide(Direction.EAST);
         return (p.getContextType() == ContextType.HIGHWAY ? "Bh " : "Br ") + (ew ? "ew" : "ns");
      }
      if (p instanceof MorphomosParcel) {
         ContextType c = p.getContextType();
         return c == ContextType.FOREST ? "Fo" : c == ContextType.FARM ? "Fa" : "Fm";
      }
      if (p instanceof OceanBoatTailParcel) {
         return "Wt";
      }
      if (p instanceof OceanBoatParcel) {
         return "Wb";
      }
      if (p instanceof OceanParcel) {
         return "Wo";
      }
      if (p instanceof RiverParcel) {
         return ((RiverParcel) p).getCarrier() != null ? "Wc" : "Wr";
      }
      if (p instanceof TerrainFallbackParcel) {
         return "Tf";
      }
      if (p instanceof EmptyParcel) {
         return "Em";
      }
      if (p instanceof ClipboardParcel) {
         Direction facing = ((ClipboardParcel) p).getDirection();
         if (facing != null && facing != Direction.NONE) {
            return "Pf " + facing.name().substring(0, 1).toLowerCase();
         }
      }
      return "Pf";
   }

   /**
    * consoleOutput.debugAsciiMap: write this tile's map to <plugin>/ascii/tile_X_Z.txt. The text is built here and
    * now, on the generation thread, because the parcels are only valid at this moment; only the file write is
    * handed to another thread.
    */
   private void dumpAsciiMap() {
      final String map = this.asciiMap();
      final java.io.File dir = new java.io.File(this.generator.getPlugin().getDataFolder(), "ascii");
      final java.io.File file = new java.io.File(dir, "tile_" + this.root.X + "_" + this.root.Y + ".txt");
      int audit = map.indexOf("---- road audit:");
      String summary = audit >= 0 ? map.substring(audit, map.indexOf('\n', audit)).replace("----", "").trim() : "";
      Minions.i("ASCII map of tile root=(" + this.root.X + "," + this.root.Y + ") -> " + file.getPath() + " -- " + summary);
      // The original logger emitted only the summary, hiding the coordinate
      // of an actual mismatch in the attachment unless the full ASCII file
      // was sent separately. Surface just the failed locations, not the map.
      if (audit >= 0) {
         int details = map.indexOf('\n', audit);
         if (details >= 0) {
            int end = 0, reported = 0;
            int start = details + 1;
            while (start < map.length() && reported < 12) {
               end = map.indexOf('\n', start);
               if (end < 0) end = map.length();
               String finding = map.substring(start, end).trim();
               if (finding.startsWith("(") && finding.indexOf(')') > 1 && finding.indexOf(':') > finding.indexOf(')')) {
                  String suffix = finding.substring(1, finding.indexOf(')'));
                  String[] local = suffix.split(",");
                  if (local.length == 2) {
                     try {
                        int cx = this.root.X + Integer.parseInt(local[0].trim());
                        int cz = this.root.Y + Integer.parseInt(local[1].trim());
                        WorldLayout plan = WorldLayouts.forSeed(this.generator.getWorldSeed());
                        int kind = plan == null ? -1 : plan.kindAt(cx, cz);
                        Minions.w("ROAD AUDIT FAILURE chunk=(%d,%d) plannedKind=%d parcel=%s", cx, cz, kind, finding);
                        reported++;
                     } catch (NumberFormatException ignored) { }
                  }
               }
               start = end + 1;
            }
         }
      }
      Bukkit.getScheduler().runTaskAsynchronously(this.generator.getPlugin(), new Runnable() {
         @Override
         public void run() {
            try {
               dir.mkdirs();
               BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file)));
               writer.write(map);
               writer.close();
            } catch (IOException e) {
               Minions.e(e);
            }
         }
      });
   }

   public Set<ClipboardParcel> getNeighbours(Cartesian2D center, int radius) {
      Set<ClipboardParcel> neighbours = new HashSet<>();

      for (int x = center.X - radius; x <= center.X + radius; x++) {
         for (int z = center.Y - radius; z <= center.Y + radius; z++) {
            Parcel p = this.getParcelAcrossTiles(x, z);
            if (p != null && p instanceof ClipboardParcel) {
               neighbours.add((ClipboardParcel)p);
            }
         }
      }

      return neighbours;
   }

   /**
    * Looks up a parcel by absolute world chunk coordinates, transparently
    * reaching into a neighbouring grid tile if the coordinate falls outside
    * this tile's own 64x64 chunk bounds.
    *
    * Cross-tile lookups are READ-ONLY (via GridProvider.peekGrid, never
    * getGrid) -- they must never trigger construction of a not-yet-built
    * neighbour. If they did, a tile still mid-construction (and therefore
    * not yet registered in GridProvider's grid table) could end up
    * re-entrantly triggering construction of a second, conflicting instance
    * of itself if that neighbour's own edge districts happened to query
    * back across the same boundary while being built. Read-only lookups
    * mean a tile can only ever be protected against a NEIGHBOUR that
    * already existed before it started generating -- never the reverse,
    * since a not-yet-built neighbour has no buildings to see yet anyway.
    */
   private Parcel getParcelAcrossTiles(int worldChunkX, int worldChunkZ) {
      if (worldChunkX >= this.root.X && worldChunkX < this.root.X + 64
              && worldChunkZ >= this.root.Y && worldChunkZ < this.root.Y + 64) {
         return this.getParcel(worldChunkX, worldChunkZ);
      }

      Grid neighbour = this.gridProvider.peekGrid(worldChunkX, worldChunkZ);
      if (neighbour instanceof UrbanGrid) {
         return ((UrbanGrid)neighbour).getParcel(worldChunkX, worldChunkZ);
      }

      return null;
   }

   /**
    * Public entry point for code outside this class (decay/growth) that
    * needs to peek at a neighbouring parcel -- e.g. to check whether it's
    * already been pasted before letting growth reach into it. Just
    * getParcelAcrossTiles() under a name that makes sense from outside
    * this file; see that method's own javadoc for why this must stay
    * read-only (never construct a not-yet-built neighbour tile).
    */
   public Parcel peekParcel(int worldChunkX, int worldChunkZ) {
      return this.getParcelAcrossTiles(worldChunkX, worldChunkZ);
   }

   /** The city layout for this world (null when the city highway network is off), for road pieces to read. */
   /** Where the water is, from the plan alone (see WaterField). Null when there is no layout. */
   /** World-planned woodland is distinct from water/shore and urban parcels.
    * Query the established world plan, not an already-allocated tile's local parcel array. */
   public boolean isPlannedNaturalWildernessAt(int chunkX, int chunkZ) {
      long seed = this.generator.getWorldSeed();
      WorldLayout layout = WorldLayouts.forSeed(seed);
      if (layout == null || layout.kindAt(chunkX, chunkZ) != WorldLayout.NATURAL) return false;
      WaterField water = WorldLayouts.waterFor(seed);
      return water != null && water.kindAt(chunkX, chunkZ) == WaterField.Kind.NONE;
   }

   public WaterField planWater() {
      return WorldLayouts.waterFor(this.generator.getWorldSeed());
   }

   public WorldLayout cityLayout() {
      return WorldLayouts.forSeed(this.generator.getWorldSeed());
   }

   /**
    * The piece of every street and highway lane of the city layout, from the layout and the highway network alone (no
    * parcel, no tile, no generation order). Null when there is no layout. See RoadShapes.
    */
   public ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes roadShapes() {
      return WorldLayouts.roadShapesFor(this.generator.getWorldSeed());
   }

   public ClipboardProvider getClipboardProvider() {
      return this.clipboardProvider;
   }
}