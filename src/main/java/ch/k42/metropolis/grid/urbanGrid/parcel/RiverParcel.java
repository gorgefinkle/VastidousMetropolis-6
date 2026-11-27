package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.generator.LodCompletionSync;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaWilderness;
import ch.k42.metropolis.grid.urbanGrid.forest.ForestGenerator;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.wild.HydrologySample;
import ch.k42.metropolis.grid.urbanGrid.wild.WildernessStreamCourse;
import ch.k42.metropolis.grid.urbanGrid.river.WaterCourseCarver;
import ch.k42.metropolis.grid.urbanGrid.river.ConfluenceBed;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Wall;
import org.bukkit.util.noise.SimplexOctaveGenerator;

/**
 * One chunk of open river: water, a carved bed beneath it, and a blended
 * sand/gravel/grass bank fading out to dry land. Placed directly by
 * UrbanGrid (see fillRiverStrip()) the same way RoadParcel/HighwayParcel
 * are -- it is never a member of a District, and never goes through
 * ClipboardProvider/schematic placement. Procedural rather than
 * schematic-based deliberately: it works immediately for any width the
 * river's noise produces, with no new schematic assets to author, and
 * leaves the block-level look easy to keep tuning without touching the
 * carving logic in UrbanGrid at all.
 *
 * EXTENSION POINT: context-dependent bank dressing (concrete embankments
 * for an INDUSTRIAL-bordering bank, a boardwalk for PARK, etc. -- see the
 * chat discussion this was designed in) is not wired in yet. The natural
 * hook is right where paintBank() below picks a palette: look up the
 * ContextType of the nearest non-river land parcel (grid.getParcel() a few
 * columns further out from the bank) and branch the block palette --or,
 * for a schematic-based version, call the district's own findRoad()-style
 * edge check and reach into ClipboardProvider.getFit() for that context --
 * instead of the single natural palette used here.
 */
public class RiverParcel extends Parcel {
   private static final Cartesian2D size = new Cartesian2D(1, 1);

   // Stone brick retaining wall, and the round drain pipe through it -- see
   // buildDrainWall()'s own javadoc for what these are for and why they
   // live here rather than in HighwayParcel.
   private static final Material WALL_MATERIAL = Material.STONE_BRICKS;
   // Dark gray concrete (the old data value 7: "gray", the dark one; 8 was
   // light gray). No fallback to hardened clay needed on a modern server.
   private static final Material PIPE_MATERIAL = Material.GRAY_CONCRETE;
   // Largest offset from the pipe's centre that is still part of the pipe
   // (the outer rim), one per width step of the river it carries -- narrow,
   // medium, wide -- see pipeRadius(). About a quarter of the river's width.
   // See pipeCell() for the exact shape.
   private static final int[] PIPE_RADII = {2, 4, 6};
   private static final int MIN_PIPE_RADIUS = 2;
   // How far, in BLOCKS, a drain pipe bores from the wall: the highway ring
   // at a tile boundary is always TWO chunks wide (one ring chunk belonging
   // to each of the two tiles that meet there), so a pipe that only bores
   // through the FIRST chunk (what this used to do) stops dead in the middle
   // of the road bed, in solid ground belonging to the second chunk -- the
   // water has nowhere to go. This bores through both ring chunks in one
   // pass rather than relying on the chunk on the far side to bore its own
   // matching half (that chunk isn't always a river chunk itself, e.g. at an
   // ocean-tile handoff, so nothing would ever open the second chunk).
   private static final int HIGHWAY_RING_SPAN_BLOCKS = 32;
   // ...plus this many blocks of overshoot PAST the ring's far edge, so the
   // pipe visibly pokes out the other side into whatever's beyond it, rather
   // than ending flush with the ring the instant it clears the road.
   private static final int PIPE_OVERSHOOT_BLOCKS = 2;
   private static final int PIPE_BORE_BLOCKS = HIGHWAY_RING_SPAN_BLOCKS - 1 + PIPE_OVERSHOOT_BLOCKS;
   // Depth a plain retaining wall (no pipe) is cut to, as if for this radius.
   private static final int PLAIN_WALL_RADIUS = 3;

   // Set by UrbanGrid.carveRiverCorridor() at grid-carve time -- true only
   // for the corridor's own outermost edge row, and only where that edge
   // coincides with the tile's own Z boundary (nearIsBoundary/
   // farIsBoundary there), meaning the highway ring sits directly against
   // this chunk with no frontage road between them. highwayNorth is which
   // face: true if the highway is at smaller Z (this chunk's own local
   // z=0), false if larger Z (local z=15). Both default false/unset for
   // every ordinary RiverParcel, which is the overwhelming majority.
   private boolean highwayBoundary = false;
   private boolean highwayBoundaryNorth = false;

   // The X-axis counterpart of the pair above, for the OTHER highway
   // edge: the N-S ring columns at a tile's west/east edge, which is
   // where the (east-west) river actually CROSSES a highway. Set by
   // UrbanGrid.carveRiverCorridor() on EVERY chunk of the corridor's
   // outermost column on each side (all of them are exposed to the ring,
   // whether or not the water reaches them). highwayBoundaryWest
   // is which face: true if the highway is at smaller X (this chunk's
   // own local x=0), false if larger X (local x=15). Can be set together
   // with highwayBoundary on a corner chunk -- the two walls just meet.
   private boolean highwayBoundaryX = false;
   private boolean highwayBoundaryWest = false;

   // The Z-bound (north-south) river's counterpart of highwayBoundaryX: this
   // chunk sits on a tile's north/south interior edge, where that river crosses
   // the E-W highway ring. Set by UrbanGrid when it reserves the river's chunks.
   private boolean highwayCrossingZ = false;
   private boolean highwayCrossingNorth = false;

   // The north-south river's counterpart of highwayBoundary: this chunk is in
   // the corridor's outermost column on a side that coincides with the tile's
   // own west/east boundary, so the highway ring sits directly against it.
   private boolean highwayFrontageColumn = false;
   private boolean highwayFrontageWest = false;

   // The RiverProvider this cell is carved from, when it is not simply whichever main river
   // UrbanGrid.getRiverProviderAt() would answer for this chunk: a creek is a RiverProvider too
   // (RiverProvider.forCreek()), and every cell of it holds a reference to that ONE instance so
   // spine, width and bank maths can never drift between neighbouring chunks. null for the main
   // rivers, which keeps their behaviour exactly what it always was.
   private final RiverProvider carrier;

   public RiverParcel(UrbanGrid grid, Cartesian2D base) {
      this(grid, base, null);
   }

   public RiverParcel(UrbanGrid grid, Cartesian2D base, RiverProvider carrier) {
      super(base, size, ContextType.RIVER, SchematicType.RIVER, grid);
      this.carrier = carrier;
      grid.fillParcels(this.chunkX, this.chunkZ, this);
   }

   /** The creek this cell belongs to, or null for a main-river cell. */
   public RiverProvider getCarrier() {
      return this.carrier;
   }

   /** See the highwayBoundary field's own javadoc. */
   public void markHighwayBoundary(boolean north) {
      this.highwayBoundary = true;
      this.highwayBoundaryNorth = north;
   }

   /** See the highwayFrontageColumn field's own javadoc. */
   public void markHighwayFrontageColumn(boolean west) {
      this.highwayFrontageColumn = true;
      this.highwayFrontageWest = west;
   }

   /** See the highwayCrossingZ field's own javadoc. */
   public void markHighwayCrossingZ(boolean north) {
      this.highwayCrossingZ = true;
      this.highwayCrossingNorth = north;
   }

   /** See the highwayBoundaryX field's own javadoc. */
   public void markHighwayBoundaryX(boolean west) {
      this.highwayBoundaryX = true;
      this.highwayBoundaryWest = west;
   }

   // Plain getters for the two "crossing" flags/directions (highwayBoundaryX,
   // highwayCrossingZ) -- NOT the "boundary"/"frontage" pair (highwayBoundary,
   // highwayFrontageColumn) above, since those are only ever set by
   // carveRiverCorridor() for the main river's own wide corridor bank
   // touching the ring, a case CreekParcel never hits (a creek only ever
   // reaches the ring by actually crossing it, never by a bank brushing it
   // sideways -- see UrbanGrid.commitCreekPathSegment()). Fields stay
   // private; CreekParcel (same package, but a subclass needs its own
   // accessor for a superclass's private state) reads them through these
   // to decide whether to carve its own pipe through the ring.
   public boolean isHighwayBoundaryX() {
      return this.highwayBoundaryX;
   }

   public boolean isHighwayBoundaryWest() {
      return this.highwayBoundaryWest;
   }

   public boolean isHighwayCrossingZ() {
      return this.highwayCrossingZ;
   }

   public boolean isHighwayCrossingNorth() {
      return this.highwayCrossingNorth;
   }

   @Override
   public void populate(MetropolisGenerator generator, Chunk chunk) {
      if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) {
         return;
      }
      World world = generator.getWorld();
      if (world == null) {
         return;
      }

      RiverProvider river = this.carrier != null ? this.carrier : this.grid.getRiverProviderAt(this.chunkX, this.chunkZ);
      GridRandom rand = this.grid.getRandom();
      WaterField hydrology=this.grid.planWater();
      int baseX = this.chunkX << 4;
      int baseZ = this.chunkZ << 4;

      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            int x=baseX+lx,z=baseZ+lz;
            RiverProvider columnRiver=river;
            if(river instanceof WildernessStreamCourse) {
               HydrologySample sample=hydrology.hydrologySampleAt(x,z);
               // Wilderness tributaries are rendered only where the shared
               // city-aware biome layer grants STREAM ownership. At a keyed
               // confluence the receiving ocean/river/creek keeps its parcel.
               if(sample.feature()!=HydrologySample.Feature.STREAM
                       ||!(sample.carrier() instanceof WildernessStreamCourse))continue;
               columnRiver=sample.carrier();
            }
            carveColumnWithCreeks(world,columnRiver,this.grid,rand,x,z);
         }
      }

      // buildHighwayBoundaryWall()/buildHighwayCrossingWall()/
      // buildHighwayFrontageWallColumn()/buildHighwayCrossingWallZ() all
      // reach past this chunk's own edge into the NEIGHBOURING highway
      // ring chunk(s) via world.getBlockAt() (see buildHighwayCrossingWall()'s
      // own javadoc). Calling them inline, right here, means that reach
      // happens from INSIDE this chunk's own populate() -- exactly the
      // reentrant-population hazard StreetParcel.notifyNeighbour() already
      // avoids for grid/tile construction (see its own javadoc: touching
      // a chunk mid-populate can force it to generate WITHOUT its own
      // populators ever running). If the ring chunk hasn't been populated
      // yet, HighwayParcel's own paste can end up running AFTER this
      // reach and refilling right over the pipe -- the pipe silently
      // stops punching through, exactly as long as that ring chunk simply
      // hadn't been touched by anything else first. Deferring to the next
      // tick moves this call OUTSIDE the populate() call entirely, so the
      // forced load behaves like an ordinary chunk load (generate AND
      // populate together) instead of a reentrant one -- by the time this
      // runs, the ring chunk's own HighwayParcel has already pasted, and
      // the bore carves through it instead of racing it.
      if (this.highwayBoundary || this.highwayBoundaryX
              || (this.highwayFrontageColumn && river.isZBound())
              || (this.highwayCrossingZ && river.isZBound())) {
         final RiverProvider finalRiver = river;
         Bukkit.getScheduler().runTask(generator.getPlugin(), () -> {
            if (this.highwayBoundary) {
               buildHighwayBoundaryWall(this.grid, world, finalRiver, this.chunkX, this.chunkZ, this.highwayBoundaryNorth);
            }
            if (this.highwayBoundaryX) {
               buildHighwayCrossingWallForRiver(this.grid, world, finalRiver, this.chunkX, this.chunkZ, this.highwayBoundaryWest);
            }
            if (this.highwayFrontageColumn && finalRiver.isZBound()) {
               buildHighwayFrontageWallColumn(this.grid, world, finalRiver, this.chunkX, this.chunkZ, this.highwayFrontageWest);
            }
            if (this.highwayCrossingZ && finalRiver.isZBound()) {
               buildHighwayCrossingWallForRiverZ(this.grid, world, finalRiver, this.chunkX, this.chunkZ, this.highwayCrossingNorth);
            }
            // The bridge/retaining-wall correction runs a tick AFTER this parcel
            // populated, and can edit neighbouring loaded highway chunks.
            LodCompletionSync.changedLoadedAround(world, this.chunkX, this.chunkZ, 2);
         });
      }

      if (RiverConfig.isRiverTreesEnabled() || RiverConfig.isRiverVegetationEnabled() || VanillaWilderness.isActive(generator.getWorld())
              || ForestGenerator.isShoreReliefActive() || PluginConfig.isForestGenerationEnabled()) {
         ForestGenerator.populateShore(generator, this.grid, chunk, new BankSite(this.grid),
                 RiverConfig.isRiverTreesEnabled(), RiverConfig.getRiverTreesChancePercent(),
                 RiverConfig.isRiverVegetationEnabled(), RiverConfig.getRiverVegetationChancePercent());
      }

      // R30ea registers the highway/river wall completion after this parcel's
      // vegetation pass; the task resolves adjacent loaded walls at the tick
      // boundary rather than assuming neighbouring chunks populated already.
      HighwayRiverBankWalls.schedule(this, generator);
      // No post-paint vegetation cleanup: shore plants are guarded before placement.
      this.markPopulated();
   }

   /**
    * Does a highway retaining wall / drain pipe get built on this chunk (see markHighwayBoundary() and its
    * relatives)? Trees keep clear of those chunks: the wall takes the chunk's outer block row.
    */
   private boolean hasHighwayWall() {
      return this.highwayBoundary || this.highwayBoundaryX || this.highwayFrontageColumn || this.highwayCrossingZ;
   }

   /**
    * Blocks from the water's edge beyond which the bank's top block is always grass: bankTopMaterial() turns to
    * sand/gravel only inside a wet band at most SHORE_BAND_MAX wide, which its fine noise can stretch by a quarter.
    */
   private static final double GRASS_SAFE_BLOCKS = RiverParcel.SHORE_BAND_MAX * 1.25 + 1.0;

   /**
    * The grass of the main river's banks as a tree site, for ForestGenerator.populateShore(): the forest's own
    * trees, ground cover and biome tint, so a bank blends into a forest that runs down to it.
    *
    * A column qualifies only where it is certain to be grass -- in a river chunk, clear of creeks and highway
    * walls, and past the sand and gravel band -- because every chunk a tree reaches into has to reach the same
    * verdict about the same column without looking at its neighbour's blocks. groundAt() repeats carveColumn()'s
    * own height rules for the same reason.
    */
   /** Pure acceptance policy for trees and undergrowth on a bounded
    * tributary's surviving NATURAL bank parcel. A dry bank is deliberately
    * absent from the wet WaterField mask; never extrapolate from mere
    * proximity into a planned street or building. */
   public static boolean boundedNaturalBankVegetationOwner(boolean boundedMode,
         int plannedKind, RiverProvider actualParcelCarrier, RiverProvider nearestBankCarrier) {
      return boundedMode && plannedKind == WorldLayout.ZONE
            && actualParcelCarrier instanceof ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSegment
            && actualParcelCarrier == nearestBankCarrier;
   }

   /** R30ea pure shoreline site used by cross-owner vegetation roots. */
   public static ForestGenerator.TreeSite vegetationSite(UrbanGrid grid) {
       return new BankSite(grid);
   }

   private static final class BankSite implements ForestGenerator.TreeSite {
      private final UrbanGrid grid;

      BankSite(UrbanGrid grid) {
         this.grid = grid;
      }

      private RiverProvider riverAt(int x, int z) {
         // Pick the painter that actually owns the planned shore at this WORLD column.
         // Previously this method excluded every creek (`river.isCreek() ? null`),
         // leaving the entire creek corridor without river-bank trees or undergrowth.
         // The plan-based ownership check also prevents river trees on public streets,
         // fixed lots, creek outlet bridges and highway lanes.
         WaterField water = this.grid.planWater();
         WorldLayout layout = this.grid.cityLayout();
         if (water == null || layout == null) {
            return null;
         }
         int cx = Math.floorDiv(x, 16);
         int cz = Math.floorDiv(z, 16);
         int land = layout.kindAt(cx, cz);
         if (land == WorldLayout.LANE || land == WorldLayout.STREET || land == WorldLayout.BLOCK) {
            return null;
         }
         WaterField.Kind kind = water.kindAt(cx, cz);
         if (kind == WaterField.Kind.NONE && land == WorldLayout.ZONE) {
            kind = water.zoneKind(cx, cz);
         }
         if (kind == WaterField.Kind.CREEK) {
            // The accepted creek owns dry shoreline well beyond its nominal
            // bank margin; use the actual RiverParcel/carrier handoff first.
            // Otherwise the trees stop after the first water-facing layer.
            RiverProvider owned = this.grid.creekVegetationShoreAt(cx, cz, x, z);
            if (owned != null) return owned;
            // A waterline straddling a planned cell boundary can still own
            // the exact block's bank profile, even if the dry site did not
            // receive a RiverParcel. Existing near-water treatment remains.
            RiverProvider creek = this.grid.creekBankAt(x, z);
            return creek != null && (creek.isCreek()
                    || creek instanceof ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSegment) ? creek : null;
         }
         if (kind == WaterField.Kind.STREAM) {
            // The polyline course also owns its dry bank; the allows() and
            // groundAt() checks below keep vegetation out of open water.
            return this.grid.creekBankAt(x, z);
         }
         // Bounded tributaries classify only their WET chunks as creek water;
         // their dry banks are separately reserved in the planning zone.
         // Earlier builds made the shore painter reject
         // ALL their trees/undergrowth, even when a real RiverParcel had
         // survived as the owner. Only a surviving natural RiverParcel may
         // supply a carrier: never plant over accepted city lots or streets.
         // Ordinary receiving rivers are unaffected by this branch.
         if (kind == WaterField.Kind.NONE && land == WorldLayout.ZONE
                 ) {
            Parcel cell = this.grid.peekParcel(cx, cz);
            if (cell instanceof RiverParcel) {
               RiverProvider bounded = ((RiverParcel) cell).getCarrier();
               if (boundedNaturalBankVegetationOwner(true, land, bounded, this.grid.creekBankAt(x, z))
                     || bounded == this.grid.creekVegetationShoreAt(cx, cz, x, z))
                  return bounded;
            }
         }
         if (kind != WaterField.Kind.RIVER) {
            return null;
         }
         RiverProvider river = this.grid.getRiverProviderAt(cx, cz);
         return river == null || river.isCreek() ? null : river;
      }

      @Override
      public boolean allows(int x, int z) {
         RiverProvider river = riverAt(x, z);
         if (river == null || this.grid.creekWaterAt(x, z) != null) {
            return false;
         }
         double signed = river.distanceFromCenterBlock(x, z);
         if (Double.isNaN(signed)) {
            return false;
         }
         double halfWidth = river.widthAtBlock(river.isZBound() ? z : x) / 2.0;
         return Math.abs(signed) > halfWidth + GRASS_SAFE_BLOCKS;
      }

      @Override
      public double fade(int x, int z) {
         return 1.0;
      }

      @Override
      public double edgeDistance(int x, int z, int reachChunks) {
         return ForestGenerator.probeEdge(this, x, z, reachChunks);
      }

      @Override
      public int groundAt(int x, int z) {
         int buildHeight = RiverConfig.getRiverBuildHeight();
         RiverProvider river = riverAt(x, z);
         if (river == null) {
            return buildHeight;
         }
         int waterY = buildHeight - RiverConfig.getRiverDepth();
         double halfWidth = river.widthAtBlock(river.isZBound() ? z : x) / 2.0;
         double signed = river.distanceFromCenterBlock(x, z);
         double rampEnd = bankRampEnd(river, this.grid, x, z, signed < 0, halfWidth);
         if (Math.abs(signed) <= rampEnd) {
            int targetY = (int) Math.round(smoothedBankTargetY(river, this.grid, x, z, waterY, buildHeight));
            return Math.min(buildHeight, Math.max(waterY, targetY));
         }
         return buildHeight;
      }
   }

   /**
    * Where the river corridor directly meets the highway ring (see
    * markHighwayBoundary()) -- a stone brick retaining wall along this
    * chunk's own outward-facing block row, with ONE round drain pipe
    * through it where the river's open channel reaches the wall (see
    * buildDrainWall() for the pipe itself), running straight through BOTH
    * ring chunks the highway is made of (see HIGHWAY_RING_SPAN_BLOCKS) and
    * poking out PIPE_OVERSHOOT_BLOCKS past the far one, so the water has
    * somewhere to go instead of stopping dead against the highway.
    *
    * This is one of only two places in the whole codebase that reach into
    * a HighwayParcel's own block-space (the other is
    * buildHighwayCrossingWall() below) -- HighwayParcel.java itself is
    * never touched for this, and nothing here fills or otherwise modifies
    * anything else inside that chunk; the highway's own schematic is left
    * completely alone apart from the pipe bored through the ground under
    * it.
    */
   private static void buildHighwayBoundaryWall(UrbanGrid grid, World world, RiverProvider river, int chunkX, int chunkZ, boolean north) {
      int baseX = chunkX << 4;
      int wallZ = north ? (chunkZ << 4) : (chunkZ << 4) + 15;
      int highwayNearZ = north ? wallZ - 1 : wallZ + 1;
      int highwayFarZ = north ? highwayNearZ - PIPE_BORE_BLOCKS : highwayNearZ + PIPE_BORE_BLOCKS;
      int waterY = RiverConfig.getRiverBuildHeight() - RiverConfig.getRiverDepth();

      // Where along this chunk's edge to put the pipe, and how big: the size
      // for the river's width step here (see pipeRadius()), at the position
      // whose whole pipe sits inside the open channel with the most room to
      // spare. If the channel only grazes this wall, or is too narrow here to
      // hold even the smallest pipe, there is no pipe -- just the plain
      // retaining wall.
      int[] found = findEdgePipe(river, baseX, wallZ, false);
      boolean pipe = found != null;
      int radius = pipe ? found[0] : PLAIN_WALL_RADIUS;
      int centerX = pipe ? found[1] : baseX + 8;

      buildDrainWall(grid, world, false, baseX, wallZ, highwayNearZ, highwayFarZ, pipe, centerX, waterY, radius);
   }

   /**
    * Where a channel running roughly east-west (the main river, OR a creek
    * whose alongXAxis is true -- see CreekParcel) runs into a highway it
    * has to cross -- the N-S ring columns at a tile's west/east edge (see
    * markHighwayBoundaryX()). Same wall-and-drain as
    * buildHighwayBoundaryWall() above, rotated onto the X axis: a one
    * block thick stone brick wall along this chunk's own edge column
    * facing the highway, with a round drain pipe through it at the
    * channel's own centreline, bored into the ADJACENT highway chunk.
    *
    * The highway ring between two tiles is always TWO chunks wide (one
    * ring chunk from each tile) -- this bores through BOTH of them in one
    * pass (HIGHWAY_RING_SPAN_BLOCKS) rather than stopping at the first and
    * relying on whatever is on the far side of that second chunk to bore
    * its own matching hole; that far side is not always another river
    * chunk (e.g. an ocean-tile handoff), so nothing would ever open it.
    * The bore also runs PIPE_OVERSHOOT_BLOCKS past the ring's far edge so
    * it visibly pokes out the other side.
    *
    * GENERIC ON PURPOSE: centerZ/radius/pipe are already resolved by the
    * caller -- from RiverProvider's spine/width for the main river (see
    * buildHighwayCrossingWallForRiver() below), from the creek's own fixed
    * centerBlock/halfWidthBlocks for a creek (see CreekParcel.populate()) --
    * this method only knows how to carve the wall and the pipe once told
    * where each goes, same as buildDrainWall() itself.
    */
   static void buildHighwayCrossingWall(UrbanGrid grid, World world, int chunkX, int chunkZ, boolean west, int centerZ, int radius, boolean pipe) {
      int wallX = west ? (chunkX << 4) : (chunkX << 4) + 15;
      int highwayNearX = west ? wallX - 1 : wallX + 1;
      int highwayFarX = west ? highwayNearX - PIPE_BORE_BLOCKS : highwayNearX + PIPE_BORE_BLOCKS;
      int waterY = RiverConfig.getRiverBuildHeight() - RiverConfig.getRiverDepth();

      buildDrainWall(grid, world, true, chunkZ << 4, wallX, highwayNearX, highwayFarX, pipe, centerZ, waterY, pipe ? radius : PLAIN_WALL_RADIUS);
   }

   /**
    * Resolves the main river's own centreline/radius/fit for
    * buildHighwayCrossingWall() above -- exactly what that method used to
    * compute internally before it was generalised to take them as
    * parameters. Centreline is the river's spine at the exact middle of
    * the (two-chunk-wide) ring, so a wall built from the OTHER side of
    * this same crossing -- if one exists -- computes the identical
    * centreline and the two pipes still agree. Pipe size comes from the
    * river's width step there too; the whole pipe (rim included) also has
    * to fit inside the open channel at THIS wall specifically, not just at
    * the ring's middle, or it's skipped in favour of a plain wall.
    */
   static void buildHighwayCrossingWallForRiver(UrbanGrid grid, World world, RiverProvider river, int chunkX, int chunkZ, boolean west) {
      int wallX = west ? (chunkX << 4) : (chunkX << 4) + 15;
      int ringMidX = west ? (chunkX - 1) << 4 : (chunkX + 2) << 4;
      int centerZ = (int) Math.round(river.spineZBlock(ringMidX));
      int radius = crossingPipeRadius(river, ringMidX);
      boolean pipe = radius > 0
              && Math.abs(centerZ - river.spineZBlock(wallX)) + radius <= river.widthAtBlock(wallX) / 2.0;
      buildHighwayCrossingWall(grid, world, chunkX, chunkZ, west, centerZ, radius, pipe);
   }

   /**
    * The north-south river's version of buildHighwayBoundaryWall(): the
    * corridor's outermost column runs directly against the highway ring on
    * this chunk's west or east edge, so the same retaining wall goes up along
    * that edge, with a drain pipe only where the open channel actually
    * reaches it.
    */
   private static void buildHighwayFrontageWallColumn(UrbanGrid grid, World world, RiverProvider river, int chunkX, int chunkZ, boolean west) {
      int baseZ = chunkZ << 4;
      int wallX = west ? (chunkX << 4) : (chunkX << 4) + 15;
      int highwayNearX = west ? wallX - 1 : wallX + 1;
      int highwayFarX = west ? highwayNearX - PIPE_BORE_BLOCKS : highwayNearX + PIPE_BORE_BLOCKS;
      int waterY = RiverConfig.getRiverBuildHeight() - RiverConfig.getRiverDepth();

      int[] found = findEdgePipe(river, baseZ, wallX, true);
      boolean pipe = found != null;
      int radius = pipe ? found[0] : PLAIN_WALL_RADIUS;
      int centerZ = pipe ? found[1] : baseZ + 8;

      buildDrainWall(grid, world, true, baseZ, wallX, highwayNearX, highwayFarX, pipe, centerZ, waterY, radius);
   }

   /**
    * The north-south counterpart of buildHighwayCrossingWall(): the same
    * retaining wall and drain pipe, rotated onto the other axis, for a
    * channel (main river, or a creek whose alongXAxis is false) crossing
    * the E-W highway ring at a tile's north/south edge. Generic on purpose,
    * same reasoning as buildHighwayCrossingWall() -- see that method's own
    * javadoc.
    */
   static void buildHighwayCrossingWallZ(UrbanGrid grid, World world, int chunkX, int chunkZ, boolean north, int centerX, int radius, boolean pipe) {
      int wallZ = north ? (chunkZ << 4) : (chunkZ << 4) + 15;
      int highwayNearZ = north ? wallZ - 1 : wallZ + 1;
      int highwayFarZ = north ? highwayNearZ - PIPE_BORE_BLOCKS : highwayNearZ + PIPE_BORE_BLOCKS;
      int waterY = RiverConfig.getRiverBuildHeight() - RiverConfig.getRiverDepth();

      buildDrainWall(grid, world, false, chunkX << 4, wallZ, highwayNearZ, highwayFarZ, pipe, centerX, waterY, pipe ? radius : PLAIN_WALL_RADIUS);
   }

   /**
    * Resolves the main river's own centreline/radius/fit for
    * buildHighwayCrossingWallZ() above -- the Z-bound (north-south)
    * river's version of buildHighwayCrossingWallForRiver(). Both sides of
    * the ring agree on the pipe's centreline by taking the river's spine
    * at the exact middle of the ring, same reasoning as the X-bound
    * version.
    */
   static void buildHighwayCrossingWallForRiverZ(UrbanGrid grid, World world, RiverProvider river, int chunkX, int chunkZ, boolean north) {
      int wallZ = north ? (chunkZ << 4) : (chunkZ << 4) + 15;
      int ringMidZ = north ? (chunkZ - 1) << 4 : (chunkZ + 2) << 4;
      int centerX = (int) Math.round(river.spineXBlock(ringMidZ));
      int radius = crossingPipeRadius(river, ringMidZ);
      boolean pipe = radius > 0
              && Math.abs(centerX - river.spineXBlock(wallZ)) + radius <= river.widthAtBlock(wallZ) / 2.0;
      buildHighwayCrossingWallZ(grid, world, chunkX, chunkZ, north, centerX, radius, pipe);
   }

   /**
    * Outer radius of the drain pipe for this river at this point along its
    * own axis: one size per width step -- narrow, medium, wide -- so the pipe
    * is in proportion to the channel it carries (roughly a quarter of its
    * width). alongBlock is the block coordinate along the river's own axis
    * (X for the east-west river, Z for the north-south one).
    */
   private static int pipeRadius(RiverProvider river, double alongBlock) {
      int widthChunks = river.widthAt((int) Math.floor(alongBlock / 16.0));
      int step = widthChunks >= RiverConfig.getRiverWidthWide() ? 2
              : (widthChunks >= RiverConfig.getRiverWidthMedium() ? 1 : 0);
      return PIPE_RADII[step];
   }

   /**
    * Pipe radius for a crossing whose centreline is fixed by the middle of the
    * highway ring: the size for the width step there, shrunk only if the
    * channel is too narrow to carry it (mid-transition between two steps).
    * Measured entirely AT the ring's middle, so the two halves bored from
    * either side agree on it. 0 = no pipe.
    */
   private static int crossingPipeRadius(RiverProvider river, double ringMidAlongBlock) {
      double halfWidth = river.widthAtBlock(ringMidAlongBlock) / 2.0;
      if (Double.isNaN(halfWidth)) {
         return 0;
      }
      int radius = pipeRadius(river, ringMidAlongBlock);
      // the flared lip is one wider than the pipe; keep it well inside the water
      while (radius >= MIN_PIPE_RADIUS && radius + 1 > halfWidth * 0.75) {
         radius--;
      }
      return radius >= MIN_PIPE_RADIUS ? radius : 0;
   }

   /**
    * R30ea frontage-wall rule: river/creek banks next to highways do not
    * gain opportunistic storm-drain holes. A true perpendicular highway
    * crossing has its own explicitly planned pipe opening, separately from
    * this no-drain frontage selector.
    */
   private static int[] findEdgePipe(RiverProvider river, int alongBase, int perpPos, boolean alongIsZ) {
      // R30ea: frontage walls do not acquire opportunistic storm drains.
      // Highway crossing pipes have a separate, explicit construction path.
      return null;
   }

   /** Squared-distance limit of the open bore of a pipe of this outer radius. */
   private static int boreLimit(int radius) {
      return (int) Math.floor((radius - 0.5) * (radius - 0.5) - 1.0);
   }

   /** Squared-distance limit of the outer rim of a ring of this outer radius. */
   private static int rimLimit(int radius) {
      return (int) Math.floor((radius + 0.5) * (radius + 0.5) - 0.25);
   }

   /**
    * The round drain pipe's cross-section, as offsets from its centre:
    * (along = position along the wall, up = height above the pipe's
    * centre line). Returns 2 for the open bore, 1 for the one-block rim
    * around it, 0 for outside the pipe. Blocky circles -- for the medium
    * pipe (radius 4), rim 9 wide, bore 7 wide; radius 2 gives 5 / 3 and
    * radius 6 gives 13 / 11. (The limits keep the rim at least one block
    * thick all the way round, so the bore never touches the wall.)
    *
    *      rim (r=3)     bore (r=3)
    *      .###.         .....
    *      #####         .###.
    *      #######  ->   #####
    *      #######       #####
    *      #######       .###.
    *      #####         .....
    *      .###.
    */
   private static int pipeCell(int along, int up, int radius) {
      int d2 = along * along + up * up;
      if (d2 <= boreLimit(radius)) {
         return 2;
      }
      if (d2 <= rimLimit(radius)) {
         return 1;
      }
      return 0;
   }

   /**
    * The flared lip on the water end of a pipe -- the "Mario pipe" collar: the
    * same bore, but a rim one block wider all the way round, on the layer just
    * outside the retaining wall (in the river's own chunk). Same return
    * values as pipeCell(); 0 = leave whatever is there alone.
    */
   private static int lipCell(int along, int up, int radius) {
      int d2 = along * along + up * up;
      if (d2 <= boreLimit(radius)) {
         return 2;
      }
      if (d2 <= rimLimit(radius + 1)) {
         return 1;
      }
      return 0;
   }

   /**
    * Builds one drain wall: a one block thick stone brick wall on this
    * chunk's own outward-facing edge (16 blocks long, from just below the
    * water up to one block above the road surface), and, if pipe is true,
    * a round pipe -- a dark gray concrete rim around an open bore
    * (pipeCell()) of the given outer radius -- centred on alongCenter and on
    * the water surface, cut through that wall and continued straight through
    * the ADJACENT highway chunk's 16 blocks so the water has somewhere to
    * go. On the water side of the wall, the pipe runs straight for one more
    * block (still pipeCell(), no flare) before it ends, two blocks out from
    * the wall, in a flared lip (lipCell()). The bore is filled with water up to the
    * river's own water level and left as air above it, so it reads as a
    * half-full pipe, and nothing blocks it: no grate.
    *
    * Everything written inside the highway chunk is the pipe itself,
    * below the road: the pipe is kept at least two blocks under the road
    * surface (pipeY clamp below), so the highway's surface and
    * everything above it is untouched.
    *
    * alongIsZ says which way the wall runs. true: the wall is a column of
    * constant X (a west/east edge), running along Z. false: the wall is a
    * row of constant Z (a north/south edge), running along X. alongBase
    * is the chunk's own first block along that axis; wallPos is the
    * wall's coordinate on the OTHER axis, and nearPos/farPos are the first
    * and last block of the adjacent highway chunk on that same axis.
    */
   private static void buildDrainWall(UrbanGrid grid, World world, boolean alongIsZ, int alongBase, int wallPos,
                                      int nearPos, int farPos, boolean pipe, int alongCenter, int waterY, int radius) {
      // Pipe centre height: the water surface, but never so high that the
      // rim gets within two blocks of the road surface.
      int pipeY = Math.min(waterY, StreetParcel.ROAD_SURFACE_Y - 2 - radius);
      int wallBottom = Math.max(1, Math.min(waterY - 1, pipeY - radius));
      int wallTop = StreetParcel.ROAD_SURFACE_Y + 1;
      int step = farPos >= nearPos ? 1 : -1;
      // one block out from the wall, on the river's side (away from the
      // highway): a plain straight run of pipe (no flare yet), so the pipe
      // clears the wall before it flares out.
      int extPos = wallPos - step;
      // two blocks out from the wall: where the flared lip actually sits.
      int lipPos = wallPos - 2 * step;

      for (int i = 0; i < 16; i++) {
         int along = alongBase + i;
         int a = along - alongCenter;

         for (int y = wallBottom; y <= wallTop; y++) {
            int cell = pipe ? pipeCell(a, y - pipeY, radius) : 0;
            setDrainBlock(world, alongIsZ, along, wallPos, y, cell, waterY);
         }

         if (!pipe) {
            continue;
         }

         if (Math.abs(a) <= radius) {
            for (int b = -radius; b <= radius; b++) {
               int cell = pipeCell(a, b, radius);
               if (cell != 0) {
                  setDrainBlock(world, alongIsZ, along, extPos, pipeY + b, cell, waterY);
               }
            }
         }

         if (Math.abs(a) <= radius + 1) {
            for (int b = -(radius + 1); b <= radius + 1; b++) {
               int cell = lipCell(a, b, radius);
               if (cell != 0) {
                  setDrainBlock(world, alongIsZ, along, lipPos, pipeY + b, cell, waterY);
               }
            }
         }

         if (Math.abs(a) > radius) {
            continue;
         }

         for (int d = nearPos; d != farPos + step; d += step) {
            for (int b = -radius; b <= radius; b++) {
               int cell = pipeCell(a, b, radius);
               if (cell != 0) {
                  setDrainBlock(world, alongIsZ, along, d, pipeY + b, cell, waterY);
               }
            }
         }
      }

      // Cap the *continuous* retaining-wall run, not each 16-block parcel.
      // A cap block exists exactly when there is retaining-wall support to
      // either side.  Thus a lone face is inset one block at both ends; two
      // adjoining faces share a gapless cap and only the true outer ends
      // are inset.  Revisit the two cells across each join so the result is
      // independent of which chunk populated first.
      refreshDrainWallCap(grid, world, alongIsZ, alongBase, wallPos, wallTop + 1);
   }

   /** A retaining-wall post is determined by the *planned* river/highway edge,
    * not by whether an adjacent chunk happened to populate already. The
    * orientation and facing must match: a perpendicular wall at a corner is
    * not a continuation of the same straight run.
    *
    * peekParcel() never generates a neighbouring tile. A missing plan is
    * conservatively treated as an endpoint rather than loading new terrain.
    */
   private static boolean plannedDrainWallAt(UrbanGrid grid, boolean alongIsZ,
                                              int along, int wallPos) {
      int x = alongIsZ ? wallPos : along;
      int z = alongIsZ ? along : wallPos;
      Parcel parcel = grid.peekParcel(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
      if (!(parcel instanceof RiverParcel))
         return HighwayRiverBankWalls.capContinuation(false, grid, alongIsZ, along, wallPos);
      RiverParcel river = (RiverParcel) parcel;
      if (alongIsZ) {
         boolean west = (wallPos & 15) == 0;
         return (river.highwayBoundaryX && river.highwayBoundaryWest == west)
                 || (river.highwayFrontageColumn && river.highwayFrontageWest == west)
                 || HighwayRiverBankWalls.capContinuation(false, grid, alongIsZ, along, wallPos);
      }
      boolean north = (wallPos & 15) == 0;
      return (river.highwayBoundary && river.highwayBoundaryNorth == north)
              || (river.highwayCrossingZ && river.highwayCrossingNorth == north)
              || HighwayRiverBankWalls.capContinuation(false, grid, alongIsZ, along, wallPos);
   }

   /** The only intentional holes are the first and last positions of a
    * planned, continuous wall. Never infer endpoints from live block-state:
    * that caused apparently random missing posts during chunk-order changes.
    * Revisit the immediate loaded join when the neighbouring segment arrives.
    */
   private static void refreshDrainWallCap(UrbanGrid grid, World world, boolean alongIsZ,
                                            int alongBase, int wallPos, int capY) {
      for (int along = alongBase - 1; along <= alongBase + 16; along++) {
         int x = alongIsZ ? wallPos : along;
         int z = alongIsZ ? along : wallPos;
         if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
         if (!plannedDrainWallAt(grid, alongIsZ, along, wallPos)) continue;
         // Do not place cap above a neighbour still awaiting its wall build.
         Block support = world.getBlockAt(x, capY - 1, z);
         if (support.getType() != WALL_MATERIAL) continue;
         boolean left = plannedDrainWallAt(grid, alongIsZ, along - 1, wallPos);
         boolean right = plannedDrainWallAt(grid, alongIsZ, along + 1, wallPos);
         Block top = world.getBlockAt(x, capY, z);
         if (!left || !right) {
            if (top.getType() == Material.STONE_BRICK_WALL)
               top.setType(Material.AIR, false);
            continue;
         }
         // Every interior cap exists; low side-connections follow the plan,
         // including across the 16-block boundary.
         Wall cap = (Wall) Material.STONE_BRICK_WALL.createBlockData();
         if (alongIsZ) {
            if (plannedDrainWallAt(grid, true, along - 2, wallPos))
               cap.setHeight(BlockFace.NORTH, Wall.Height.LOW);
            if (plannedDrainWallAt(grid, true, along + 2, wallPos))
               cap.setHeight(BlockFace.SOUTH, Wall.Height.LOW);
         } else {
            if (plannedDrainWallAt(grid, false, along - 2, wallPos))
               cap.setHeight(BlockFace.WEST, Wall.Height.LOW);
            if (plannedDrainWallAt(grid, false, along + 2, wallPos))
               cap.setHeight(BlockFace.EAST, Wall.Height.LOW);
         }
         // A straight wall with opposite arms must be flush, not one forced
         // centre pillar per block. The planned-cap neighbour predicates
         // above exclude the intentional one-block gaps at genuine ends.
         // Use planned ownership rather than previously painted neighbour
         // data, which changes with chunk load/population order.
         boolean negativeCap = plannedDrainWallAt(grid, alongIsZ, along - 2, wallPos);
         boolean positiveCap = plannedDrainWallAt(grid, alongIsZ, along + 2, wallPos);
         cap.setUp(!straightCapHasBothConnections(negativeCap, positiveCap));
         top.setBlockData(cap, false);
      }
   }

   /** No raised centre post where a planned cap connects in both directions. */
   static boolean straightCapHasBothConnections(boolean negativeCap, boolean positiveCap) {
      return negativeCap && positiveCap;
   }

   /** cell: 0 = retaining wall block, 1 = pipe rim (dark gray concrete), 2 = pipe bore (water up to waterY, air above). */
   private static void setDrainBlock(World world, boolean alongIsZ, int along, int depth, int y, int cell, int waterY) {
      Block block = alongIsZ ? world.getBlockAt(depth, y, along) : world.getBlockAt(along, y, depth);
      if (cell == 0) {
         block.setType(WALL_MATERIAL, false);
      } else if (cell == 1) {
         block.setType(PIPE_MATERIAL, false);
      } else {
         block.setType(y <= waterY ? Material.WATER : Material.AIR, false);
      }
   }

   /**
    * Carves a single column: open water within the river's width, a
    * blended bank out to bankMargin beyond that, or flat dry land beyond
    * the bank. Shared with BridgeParcel, which calls this for every
    * column under its own deck -- a bridge crossing can land on a road
    * cell that ordinary road-strip placement claimed BEFORE this river
    * chunk's own reservation ever ran (see UrbanGrid.convertRoadsToBridges()'s
    * javadoc), so BridgeParcel can't assume a neighbouring RiverParcel
    * already did this for it.
    *
    * The "beyond the bank" case used to be left untouched, on the theory
    * that whichever separately-typed parcel UrbanGrid decided to place
    * there (formerly RiverLandParcel) would handle it. That decision was
    * made per CHUNK (RiverProvider.isRiverOrBank()), while the bank's own
    * blend above is a smooth function of per-BLOCK distance from the
    * centerline that has no relationship to chunk boundaries -- so
    * whichever chunk a given boundary happened to fall in either got the
    * flat land treatment a few blocks too early (visibly notching into
    * what should have been a continuous bank slope) or too late. Painting
    * flat land here too, in the exact same per-block distance check as
    * water/bank, means every column in the corridor -- regardless of
    * which chunk it's in -- resolves consistently off the same continuous
    * math, with no chunk-granularity seam possible.
    */
   public static void carveColumn(World world, WaterCourseCarver river, GridRandom rand, int blockX, int blockZ) {
      carveColumn(world, river, null, rand, blockX, blockZ);
   }

   /**
    * Reclamation-aware carve. Identical to the two-argument-shorter
    * overload above except that the bank's elevation ramp is stretched (or
    * compressed) to finish exactly at the water-facing edge of whatever
    * reclaimed district sits on this side of this column, instead of
    * always finishing a fixed bankMargin from the water.
    *
    * WHY THE RAMP LENGTH HAS TO BE PER-COLUMN: the district edge is a
    * straight chunk boundary; the waterline is a smooth curve
    * (spineZBlock() x widthAtBlock()). The gap between them therefore
    * changes continuously along X -- often by tens of blocks across a
    * single district. A fixed-length ramp in that gap has to end
    * somewhere, and wherever it ends is either short of the buildings
    * (leaving a flat shelf of grass that makes the bank look like it
    * stopped for no reason) or past them (putting a slope under a
    * foundation). Solving for the ramp's END instead of its LENGTH --
    * reading the district edge for this exact column and interpolating
    * waterY -> buildHeight across whatever distance that turns out to be
    * -- makes the bank meet the district's edge at full build height
    * everywhere along it, at every X, by construction.
    *
    * grid may be null (BridgeParcel's columns, any tile with no corridor,
    * reclamation disabled), in which case this falls back to the original
    * fixed bankMargin ramp followed by flat land -- exactly the behaviour
    * that existed before reclamation.
    */
   public static void carveColumn(World world, WaterCourseCarver river, UrbanGrid grid, GridRandom rand, int blockX, int blockZ) {
      // Everything in the corridor -- water, bank ramp, AND the flat land
      // beyond it -- now follows the river's OWN height (riverBuildHeight)
      // uniformly, all the way out to the corridor's edge road. This means
      // there IS now a real, accepted step where this land meets the
      // riverside road (which stays at StreetParcel.ROAD_SURFACE_Y /
      // the general buildHeight) if riverBuildHeight and buildHeight
      // differ -- that tradeoff was chosen deliberately over having this
      // land match the road, so the whole river district reads as one
      // consistent elevation instead of correctly matching the water on
      // one side and correctly matching the road on the other, at
      // different heights.
      int buildHeight = RiverConfig.getRiverBuildHeight();
      int waterY = buildHeight - RiverConfig.getRiverDepth();
      double halfWidth = river.widthAtBlock(river.isZBound() ? blockZ : blockX) / 2.0;
      double signed = river.distanceFromCenterBlock(blockX, blockZ);
      // A bounded creek has a finite along-axis span. Road-approach chunks
      // often straddle its final sample; a non-finite distance/width is NOT
      // open water and must not be rounded to waterY by the bank smoother.
      // Paint ordinary dry ground and let this chunk's road schematic cover
      // it, rather than excavating a sudden low terrace at the bridge end.
      if (!hasWaterProfile(signed, halfWidth)) {
         paintLand(world, blockX, blockZ, buildHeight);
         return;
      }
      double distance = Math.abs(signed);
      double rampEnd = bankRampEnd(river, grid, blockX, blockZ, signed < 0, halfWidth);

      if (distance <= halfWidth) {
         // 0 right at the water's edge (the shore), ramping up to 1 a few
         // blocks in -- how far into the river this column is, used to
         // taper the bed from a shallow, walkable shore up to full depth
         // instead of an abrupt uniform-depth trench with vertical walls
         // at the waterline.
         double shoreRamp = Math.min(4.0, halfWidth);
         double shoreT = shoreRamp <= 0 ? 1.0 : Math.min(1.0, (halfWidth - distance) / shoreRamp);
         paintWater(world, rand, blockX, blockZ, waterY, buildHeight, shoreT);
      } else if (distance <= rampEnd) {
         double bankT = (distance - halfWidth) / (rampEnd - halfWidth); // 0 at water's edge, 1 where the ramp tops out -- still drives material selection only, see paintBank()
         double blendedTargetY = smoothedBankTargetY(river, grid, blockX, blockZ, waterY, buildHeight);
         paintBank(world, rand, blockX, blockZ, waterY, buildHeight, bankT, blendedTargetY, distance - halfWidth);
      } else {
         // R30ea continues the same smoothed bank elevation through the
         // adjoining dry corridor, clamped between water and road heights.
         double target = smoothedBankTargetY(river, grid, blockX, blockZ, waterY, buildHeight);
         int dryY = Math.min(buildHeight, Math.max(waterY, (int) Math.round(target)));
         paintLand(world, blockX, blockZ, dryY);
      }
   }

   /** Whether a river profile genuinely exists at a column. Non-finite
    * bounded-creek tails are dry terrain, never the bottom of a bank. */
   static boolean hasWaterProfile(double signedDistance, double width) {
      return Double.isFinite(signedDistance) && Double.isFinite(width) && width > 0.0;
   }

   /**
    * carveColumn(), except that where a CREEK's water runs through this column and this carrier's own
    * water does not, the column is the creek's water. A creek joins a river by flowing through the
    * river's own cells, which keep their own shoreline and bank; painting the creek's channel in
    * per column -- outside shared open water the owner still wins. In the
    * shared opening the deeper of the two smoothly ramped beds wins, so the
    * receiving river's shallow edge cannot become a transverse underwater
    * wall across the creek mouth. No carrier gives up its chunk ownership.
    *
    * A creek's own cells (carrier is itself a creek) and grids with no creeks fall straight through to
    * carveColumn(), so a main river's behaviour with no creek near it is exactly what it always was.
    */
   public static void carveColumnWithCreeks(World world, WaterCourseCarver river, UrbanGrid grid, GridRandom rand, int blockX, int blockZ) {
      // A confluence is not two independent flat-bottomed channels that happen
      // to meet. The receiver's edge depth begins at one block, while a creek
      // approaching that edge may already be at full depth. Letting the main
      // river win outright in that overlap leaves a transverse underwater
      // ledge at the mouth. The same combined profile must be used from both
      // river-owned and creek-owned chunks, independent of population order.
      // Only actual OPEN water shared by two accepted carriers participates;
      // the main river everywhere else and all dry banks are unchanged.
      if (grid != null) {
         RiverProvider creekWater = grid.creekWaterAt(blockX, blockZ);
         if (creekWater != null) {
            RiverProvider receiver = river == creekWater
                    ? grid.getRiverProviderAt(Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16))
                    : river instanceof RiverProvider ? (RiverProvider) river : null;
            if (receiver != null && receiver != creekWater && !receiver.isCreek()
                    && !(receiver instanceof ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverSegment)) {
               double receiverShore = ConfluenceBed.shoreFraction(receiver, blockX, blockZ);
               double creekShore = ConfluenceBed.shoreFraction(creekWater, blockX, blockZ);
               if (Double.isFinite(receiverShore) && Double.isFinite(creekShore)) {
                  int buildHeight = RiverConfig.getRiverBuildHeight();
                  int waterY = buildHeight - RiverConfig.getRiverDepth();
                  // Union of the two smoothly sloped beds, never the shallow
                  // receiver shelf cutting across the deeper creek mouth.
                  paintWater(world, rand, blockX, blockZ, waterY, buildHeight,
                          ConfluenceBed.deeperFraction(receiverShore, creekShore));
                  return;
               }
            }
         }
      }
      // Do not change the main river when no accepted creek approaches it.
      if (grid != null && !(river instanceof RiverProvider && ((RiverProvider) river).isCreek())) {
         RiverProvider creek = grid.creekBankAt(blockX, blockZ);
         if (creek != null) {
            // An open creek channel must remain open even when the main river's
            // bank elevation happens to equal the creek's water elevation.
            // Comparing only bank target heights lets equal-valued bank columns
            // win and leaves a transverse wall across the creek mouth.
            // The creek must own these columns unless the main river already
            // owns them as open water (in which case its deeper bed wins).
            RiverProvider creekWater = grid.creekWaterAt(blockX, blockZ);
            double signedMain = river.distanceFromCenterBlock(blockX, blockZ);
            double halfMain = river.widthAtBlock(river.isZBound() ? blockZ : blockX) / 2.0;
            // Main-river water outside the shared creek/river channel keeps
            // its original profile. The shared opening was resolved above.
            if (Double.isNaN(signedMain) || Math.abs(signedMain) > halfMain) {
               if (creekWater != null) {
                  carveColumn(world, creek, grid, rand, blockX, blockZ);
                  return;
               }
               int buildHeight = RiverConfig.getRiverBuildHeight();
               int waterY = buildHeight - RiverConfig.getRiverDepth();
               double mainY = Double.isNaN(signedMain) ? buildHeight
                       : rawBankTargetY(river, grid, blockX, blockZ, waterY, buildHeight);
               double creekY = rawBankTargetY(creek, grid, blockX, blockZ, waterY, buildHeight);
               // The lower of the two bank profiles wins. Both profiles
               // converge continuously to full ground elevation at their
               // edges, so the confluence does not require a hard carve
               // boundary or a second writer/overlay pass.
               if (creekY < mainY - 0.001) {
                  carveColumn(world, creek, grid, rand, blockX, blockZ);
                  return;
               }
            }
         }
      }
      carveColumn(world, river, grid, rand, blockX, blockZ);
   }

   /** Radius, in blocks, of the 2D neighbourhood smoothedBankTargetY() averages over -- see its own javadoc. */
   private static final int BANK_TARGET_BLEND_RADIUS = 2;

   /**
    * The elevation this exact block's ramp would reach if computed on its
    * own, from ITS OWN column's rampEnd/bankT -- i.e. exactly what
    * carveColumn() used to compute inline before this change. Extracted
    * so it can be evaluated at arbitrary neighbouring (blockX, blockZ)
    * pairs, not just the one actually being painted -- that's what makes
    * smoothedBankTargetY() below a real per-block sample of each
    * neighbour's own geometry rather than a single already-collapsed
    * number.
    *
    * Deliberately continuous across ALL three regions (water/bank/land),
    * not just the bank: a neighbouring column can legitimately be over
    * open water or already-flat land at the same Z the current column is
    * still ramping through, and returning waterY/buildHeight for those
    * cases (rather than only ever being called for "bank" columns) is
    * exactly what lets the blend below pull a boundary column's height
    * toward what's really next to it.
    */
   private static double rawBankTargetY(WaterCourseCarver river, UrbanGrid grid, int blockX, int blockZ, int waterY, int buildHeight) {
      double halfWidth = river.widthAtBlock(river.isZBound() ? blockZ : blockX) / 2.0;
      double signed = river.distanceFromCenterBlock(blockX, blockZ);
      double distance = Math.abs(signed);
      double rampEnd = bankRampEnd(river, grid, blockX, blockZ, signed < 0, halfWidth);

      if (distance <= halfWidth) {
         return waterY;
      }
      if (distance <= rampEnd) {
         double bankT = (distance - halfWidth) / (rampEnd - halfWidth);
         return waterY + smoothstep(bankT) * (buildHeight - waterY);
      }
      return buildHeight;
   }

   /**
    * The height paintBank() actually builds to: a box average of
    * rawBankTargetY() over a small (BANK_TARGET_BLEND_RADIUS) window in
    * BOTH blockX and blockZ, instead of each column computing its own
    * height in isolation from a single pre-smoothed edge scalar.
    *
    * WHY THIS IS DIFFERENT FROM UrbanGrid.computeBankEdges()'s smoothing:
    * that method smooths northBankEdgeBlockZ/southBankEdgeBlockZ, which
    * is one number PER X COLUMN -- the Z the ramp is targeting -- and
    * every block within that column then still computes its own Y
    * independently via a per-column-only bankT -> smoothstep, with zero
    * awareness of what any neighbouring column (in X) is doing at the
    * same Z. Two adjacent columns whose (still-somewhat-different, even
    * after that smoothing) rampEnd values put a given Z on opposite
    * sides of their own ramp -- one column already flat, the next still
    * rising -- produced a visible sideways step at that Z no amount of
    * smoothing the target SCALAR could fix, because nothing ever blended
    * the resulting HEIGHTS together.
    *
    * Averaging rawBankTargetY() over neighbouring blocks fixes that
    * directly: each sample is evaluated against that neighbour's own
    * real rampEnd (itself already reading the actual placed parcel grid
    * via getBankEdgeBlockZ()), so the blend is genuinely aware of what's
    * actually next to it on both axes, not just a single upstream number.
    * A plain average (not an extremum) is deliberate here, unlike
    * computeBankEdges()'s max/min: that method is protecting a
    * buildable-clearance invariant (never reach past a real edge), while
    * this is purely cosmetic terrain shape -- blending toward the middle
    * is what actually produces a smooth slope instead of a stepped one.
    */
   private static double smoothedBankTargetY(WaterCourseCarver river, UrbanGrid grid, int blockX, int blockZ, int waterY, int buildHeight) {
      double sum = 0;
      int count = 0;
      for (int dx = -BANK_TARGET_BLEND_RADIUS; dx <= BANK_TARGET_BLEND_RADIUS; dx++) {
         for (int dz = -BANK_TARGET_BLEND_RADIUS; dz <= BANK_TARGET_BLEND_RADIUS; dz++) {
            sum += rawBankTargetY(river, grid, blockX + dx, blockZ + dz, waterY, buildHeight);
            count++;
         }
      }
      return sum / count;
   }

   /**
    * Distance from the centerline, in blocks, at which this column's bank
    * must have reached full build height -- see UrbanGrid.getBankEdgeBlockZ()'s
    * own javadoc for how that target is found (a scan of the FINAL parcel
    * grid, after every district/road is placed, not a geometric estimate).
    * NaN from that call means nothing but open river/bank sits between
    * the corridor edge and the water on this side of this column, in
    * which case this falls back to the plain bankMargin ramp.
    *
    * getRiverBankMaxRunChunks(), when set, still caps how long the ramp is
    * allowed to get -- see that config field's own javadoc.
    */
   static double bankRampEnd(WaterCourseCarver river, UrbanGrid grid, int blockX, int blockZ, boolean negativeSide, double halfWidth) {
      double fallback = halfWidth + river.getFallbackBankMarginBlocks();
      if (grid == null) {
         return fallback;
      }

      // Real edge of whatever the corridor ended up holding on this side,
      // ALREADY a block distance from the centerline (not a world
      // coordinate) -- see WaterCourseCarver.getBankEdgeBlockAt()'s own
      // javadoc. Each carrier converts its own internal representation
      // (RiverProvider: a world X/Z coordinate off UrbanGrid's fixed
      // corridor-wide arrays; CreekSpine: already a distance, off its own
      // along-axis-indexed scan) into that one shared distance contract,
      // so this needs no per-carrier branching at all.
      double toEdge = river.getBankEdgeBlockAt(grid, blockX, blockZ, negativeSide);
      if (Double.isNaN(toEdge) || toEdge <= halfWidth) {
         return fallback;
      }

      double maxRunBlocks = river.getRampMaxRunBlocks();
      if (maxRunBlocks > 0) {
         toEdge = Math.min(toEdge, halfWidth + maxRunBlocks);
      }
      // NOT Math.max(toEdge, fallback): toEdge is a real, validated distance to
      // the actual district/road edge; if it's closer than the generic
      // bankMargin fallback, the ramp should be that much steeper to reach it.
      return toEdge;
   }

   /**
    * Package-private (not private) so CreekParcel can reuse the exact same
    * water/bank block palette and blend logic for its own, much smaller
    * footprint -- both just need SOME distance-from-centerline and a
    * halfWidth/waterY to work from; how that distance gets computed (a
    * smooth RiverProvider function here, a fixed per-chunk value for a
    * creek) is the only thing that differs between the two.
    */
   static void paintWater(World world, GridRandom rand, int x, int z, int waterY, int buildHeight, double shoreT) {
      // Clear anything the raw world generator left poking up through the
      // water down to a couple blocks above buildHeight, same reasoning as
      // TerrainFallbackParcel's clear-above-target pass.
      int currentTop = world.getHighestBlockYAt(x, z);
      for (int y = waterY + 1; y <= Math.max(currentTop, buildHeight + 2); y++) {
         world.getBlockAt(x, y, z).setType(Material.AIR, false);
      }
      for (int y = 1; y <= waterY; y++) {
         world.getBlockAt(x, y, z).setType(Material.WATER, false);
      }

      // Depth ramps from a single shallow block right at the shoreline
      // (shoreT=0) up to the full configured column depth a few blocks in
      // (shoreT=1), instead of every column -- shoreline included --
      // jumping straight to the same fixed depth. A real riverbed slopes
      // down from the bank; the old version had no shore at all, just a
      // uniform-depth trench with vertical walls starting right at the
      // waterline.
      int fullDepth = Math.max(1, RiverConfig.getRiverWaterColumnDepth());
      int depth = Math.max(1, (int) Math.round(1 + shoreT * (fullDepth - 1)));
      int bedY = Math.max(1, waterY - depth);

      // Bed material comes from coherent noise (see bedMaterial()), not a
      // per-block dice roll: patches of sand, gravel bars and the odd clay
      // pocket that run for several blocks and blend into each other,
      // instead of a salt-and-pepper speckle. Still sandy in the shallows
      // near the bank, gravelier toward the channel.
      Material bed = bedMaterial(world, x, z, shoreT);
      world.getBlockAt(x, bedY, z).setType(bed, false);

      // Solid stone from the bed floor all the way down to bedrock. This
      // used to only place 2 layers of stone under the bed material and
      // leave everything below THAT as open water (the first loop above
      // fills the entire column with water up to waterY) -- so most of
      // every river column was actually open water reaching straight down
      // to y=1, not riverbed.
      for (int y = bedY - 1; y >= 1; y--) {
         world.getBlockAt(x, y, z).setType(Material.STONE, false);
      }
   }

   /**
    * Simple, unblended overload -- kept for CreekParcel, which has no
    * continuous river geometry to sample neighbours from (see its own
    * class javadoc: a fixed per-chunk centerline/width, not a smooth
    * function of X) and wasn't part of what was reported here. Computes
    * targetY the same way carveColumn() used to before 2D blending was
    * added, then delegates to the shared painter below.
    */
   static void paintBank(World world, GridRandom rand, int x, int z, int waterY, int buildHeight, double bankT) {
      double easedT = smoothstep(bankT);
      double targetY = waterY + easedT * (buildHeight - waterY);
      paintBank(world, rand, x, z, waterY, buildHeight, bankT, targetY);
   }

   /**
    * Package-private -- see paintWater()'s javadoc, same reasoning.
    *
    * blendedTargetY (from smoothedBankTargetY(), or the single-column
    * overload above) drives the ELEVATION. Material is chosen separately,
    * from position alone (see bankTopMaterial()): sand near the water,
    * grass near dry land, with coherent noise shaping the boundaries.
    * Unlike height, that doesn't need blending across neighbours -- the
    * noise is already continuous in world position.
    */
   static void paintBank(World world, GridRandom rand, int x, int z, int waterY, int buildHeight, double bankT, double blendedTargetY) {
      paintBank(world, rand, x, z, waterY, buildHeight, bankT, blendedTargetY, Double.NaN);
   }

   /**
    * shoreDist is this column's real distance, in blocks, from the edge of
    * the open water (carveColumn() knows it exactly); NaN means "not
    * known" (CreekParcel), and the material choice falls back to
    * estimating it from bankT.
    */
   static void paintBank(World world, GridRandom rand, int x, int z, int waterY, int buildHeight, double bankT, double blendedTargetY, double shoreDist) {
      int targetY = (int) Math.round(blendedTargetY);
      targetY = Math.min(buildHeight, Math.max(waterY, targetY));

      int currentTop = world.getHighestBlockYAt(x, z);
      for (int y = targetY + 1; y <= Math.max(currentTop, buildHeight + 1); y++) {
         world.getBlockAt(x, y, z).setType(Material.AIR, false);
      }

      Material top = bankTopMaterial(world, x, z, bankT, shoreDist);
      world.getBlockAt(x, targetY, z).setType(top, false);

      // What's under the surface matches it, so a slope cut or a wall
      // face doesn't show a sand stripe under a gravel bar or grass:
      // dirt under grass, gravel then sand under gravel, sand under sand.
      for (int y = targetY - 1; y >= Math.max(1, targetY - 3); y--) {
         Material under;
         if (top == Material.GRASS_BLOCK) {
            under = Material.DIRT;
         } else if (top == Material.GRAVEL && y == targetY - 1) {
            under = Material.GRAVEL;
         } else {
            under = Material.SAND;
         }
         world.getBlockAt(x, y, z).setType(under, false);
      }
   }

   // ---- natural material shifts ------------------------------------------
   //
   // Every "which material goes here" decision on the bank and the bed is a
   // pure function of world position -- coherent simplex noise plus the
   // block's real distance from the water -- rather than a per-block random
   // roll. Two things that buys: neighbouring blocks agree with each other
   // (sand and gravel come in patches and bands with soft, ragged edges,
   // not a checkerboard), and the result is the same whichever order chunks
   // happen to be generated in.

   /** How wide, in blocks, the wet sand/gravel shore band runs -- wanders between these along the river. */
   private static final double SHORE_BAND_MIN = 3.0;
   private static final double SHORE_BAND_MAX = 7.0;

   private static final class ShoreNoise {
      final long seed;
      final SimplexOctaveGenerator gen;

      ShoreNoise(long seed) {
         this.seed = seed;
         this.gen = new SimplexOctaveGenerator(seed ^ 0x53484F524553414EL, 2);
         this.gen.setScale(1.0);
      }
   }

   private static volatile ShoreNoise shoreNoise;

   /**
    * Coherent noise in [-1, 1] whose features are roughly featureSize
    * blocks across, with a finer second octave for a slightly ragged
    * edge. layer just decorrelates independent uses of the same noise
    * (band width vs. sand/grass mottling vs. gravel patches...).
    */
   private static double patch(World world, int x, int z, double featureSize, int layer) {
      long seed = world.getSeed();
      ShoreNoise n = shoreNoise;
      if (n == null || n.seed != seed) {
         n = new ShoreNoise(seed);
         shoreNoise = n;
      }
      return n.gen.noise(x / featureSize + layer * 137.31, z / featureSize - layer * 91.73, 2.0, 0.5, true);
   }

   /**
    * Top block of a bank column. A wet band hugs the waterline, 3-7 blocks
    * wide and slowly wandering in width along the river; inside it the
    * surface is sand with gravel bars in patches, at its inner edge the
    * sand breaks up into mottled sand/grass (patches a few blocks across,
    * not single blocks), and beyond it is grass. A little fine noise on the
    * band's edge keeps it from following the water in a straight line.
    */
   private static Material bankTopMaterial(World world, int x, int z, double bankT, double shoreDist) {
      double dist = Double.isNaN(shoreDist) ? bankT * RiverConfig.getRiverBankMargin() * 16.0 : shoreDist;
      double bandWidth = SHORE_BAND_MIN + (SHORE_BAND_MAX - SHORE_BAND_MIN) * (patch(world, x, z, 28.0, 1) + 1.0) * 0.5;
      double wetness = 1.0 - dist / bandWidth + 0.25 * patch(world, x, z, 3.0, 2);

      if (wetness > 0.55) {
         return patch(world, x, z, 9.0, 3) > 0.3 ? Material.GRAVEL : Material.SAND;
      }
      if (wetness > 0.2) {
         return patch(world, x, z, 2.5, 4) > 0.0 ? Material.SAND : Material.GRASS_BLOCK;
      }
      return Material.GRASS_BLOCK;
   }

   /**
    * Riverbed surface. shoreT is 0 at the waterline and 1 a few blocks
    * out: shallows are mostly sand, and the chance of gravel rises
    * smoothly with depth (so bars of gravel appear in the channel and
    * fade into sand toward the banks), with occasional clay pockets in the
    * deeper, calmer water. All patches are several blocks across.
    */
   private static Material bedMaterial(World world, int x, int z, double shoreT) {
      if (shoreT > 0.6 && patch(world, x, z, 10.0, 6) > 0.55) {
         return Material.CLAY;
      }
      double gravelness = 0.8 * patch(world, x, z, 7.0, 5) + 0.6 * shoreT - 0.3;
      return gravelness > 0.3 ? Material.GRAVEL : Material.SAND;
   }

   /**
    * Classic Hermite smoothstep -- same curve RiverProvider uses for its
    * own width-bucket blending, duplicated here (rather than made
    * package-visible there) since this is the only other place in the
    * river code that needs an eased 0..1 ramp and it's a two-line
    * function. Flat (zero slope) at both t=0 and t=1, rising fastest
    * through the middle.
    */
   private static double smoothstep(double t) {
      double c = Math.max(0.0, Math.min(1.0, t));
      return c * c * (3.0 - 2.0 * c);
   }

   /**
    * Flat, deterministic dry land pinned to buildHeight -- the "beyond the
    * bank" case in carveColumn(), and the direct replacement for the old
    * separately-typed RiverLandParcel (see carveColumn()'s javadoc for why
    * that per-chunk approach produced visible notches). No noise, no
    * per-chunk sampling: every column that reaches here resolves off the
    * same continuous per-block distance check water/bank use, so it always
    * meets the bank's own dry-land endpoint (also pinned to buildHeight)
    * and the corridor's edge road at exactly the same height, seamlessly.
    */
   static void paintLand(World world, int x, int z, int buildHeight) {
      int currentTop = world.getHighestBlockYAt(x, z);
      clearAboveKeepingTrees(world, x, z, buildHeight + 1, Math.max(currentTop, buildHeight + 1));
      world.getBlockAt(x, buildHeight, z).setType(Material.GRASS_BLOCK, false);
      for (int y = buildHeight - 1; y >= Math.max(1, buildHeight - 3); y--) {
         world.getBlockAt(x, y, z).setType(Material.DIRT, false);
      }
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
      // No schematic, no decay pass, no cutout notifications, no growth
      // to cache -- mirrors TerrainFallbackParcel's postPopulate()
      // reasoning exactly. populate() already marked this parcel
      // populated, which is all any neighbour's growth-readiness check
      // needs from it.
   }
}