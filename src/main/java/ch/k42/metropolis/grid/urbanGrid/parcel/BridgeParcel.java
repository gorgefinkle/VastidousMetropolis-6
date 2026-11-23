package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Chunk;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Wall;
import org.bukkit.block.data.type.Slab;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Replaces a single RoadParcel or HighwayParcel cell where UrbanGrid's
 * post-placement pass (convertRoadsToBridges()) found the river flanking it
 * on both opposite sides.
 *
 * SCHEMATIC-FIRST, PROCEDURAL FALLBACK: populate() looks for a clipboard
 * tagged SchematicType.BRIDGE that fits this cell's context/direction via
 * the normal ClipboardProvider.getFit() path (same lookup RoadParcel/
 * ClipboardParcel use for their own schematics) before falling back to the
 * hand-built deck/curb/parapet/sidewalk/pier geometry below. No BRIDGE-tagged
 * schematics exist for this context/direction (the common case, since
 * authoring one is optional) -> the procedural path runs exactly as
 * before, unchanged. Water/bed/bank carving always runs procedurally
 * either way (see below) since it has to follow the river's noise-driven
 * width and position, which a fixed authored schematic can't.
 *
 * IMPORTANT: this can NOT assume a neighbouring RiverParcel already carved
 * the water beneath it. convertRoadsToBridges() runs as a pass over
 * whatever Road/HighwayParcel cells already exist -- including ones an
 * ordinary ancestor road-strip cut placed BEFORE UrbanGrid ever reserved
 * this column as river (see that method's javadoc in UrbanGrid). So this
 * parcel carves its own water/bed via RiverParcel.carveColumn() for every
 * column in its footprint, independent of whatever else is or isn't there,
 * and independent of whether the deck itself ends up schematic or
 * procedural.
 *
 * EXTENSION POINT NOT YET IMPLEMENTED: the procedural fallback's flat deck
 * is used everywhere. A real bridge should ramp down to street level at
 * the first/last chunk of a multi-chunk span rather than stepping
 * abruptly -- that requires UrbanGrid to know this chunk's position within
 * the span (not just "is a river neighbour"), which convertRoadsToBridges()
 * doesn't currently track. The natural extension is an isSpanEnd flag
 * threaded through the constructor from that pass, used here to linearly
 * taper deckY down to buildHeight across the chunk instead of holding it
 * constant. A schematic-based span end piece is an equally valid way to
 * close this gap and wouldn't need that flag at all -- just a second
 * SchematicType.BRIDGE variant the author tags distinctly (e.g. by
 * pre-filtering on Clipboard.getSize() or a dedicated context) and a
 * second getFit() call from convertRoadsToBridges() for cells it already
 * knows are span ends.
 */
public class BridgeParcel extends StreetParcel {
   private static final Cartesian2D size = new Cartesian2D(1, 1);
   private final boolean deckAxisX;
   private final boolean wasHighway;
   // Set by populate() only when a BRIDGE-tagged schematic was found and
   // pasted this pass; null means the procedural fallback ran instead.
   // postPopulate() checks this to decide which decay path applies to
   // whatever actually got built.
   private Clipboard schematic;

   // Legacy STEP data value for a stone brick slab (0 stone, 1 sandstone,
   // 2 wood, 3 cobblestone, 4 brick, 5 stone brick, 6 nether brick,
   // 7 quartz) -- same STEP-with-data convention StreetParcel already uses
   // for the cobblestone-slab decay/sidewalk look, just a different data
   // value.
   private static final byte STONE_BRICK_SLAB_DATA = 5;
   // The travel lane's default surface -- plain stone, distinct from the
   // stone BRICK used for supports/curbs/parapet so the two read as
   // different materials up close, and so StreetParcel.decayRoadChunk()
   // (already reused in postPopulate()) has somewhere to visibly age FROM.
   private static final Material ROAD_LANE_MATERIAL = Material.STONE;
   // Stone brick for every structural/edge surface: the slab underneath
   // the deck, the curb strip at the deck's outer edge, the parapet wall
   // above it, and the support piers. STONE_BRICKS is the stone brick BLOCK -- not to be confused
   // with Material.BRICKS, which is clay brick.
   private static final Material STONE_BRICK = Material.STONE_BRICKS;

   /**
    * @param deckAxisX true if the road/highway this replaces runs
    *                  east-west (the river flanks it to the north and
    *                  south); false if it runs north-south (river flanks
    *                  it east and west).
    * @param wasHighway which SIDE of the road network this cell belonged
    *                    to -- preserves ContextType.HIGHWAY vs
    *                    ContextType.STREET so Parcel.isStreet() still
    *                    treats this cell as road network for neighbouring
    *                    RoadParcel/HighwayParcel fit lookups, exactly as
    *                    it did before being converted.
    */
   // The creek this deck spans, when it spans a creek rather than one of the main rivers (which
   // getRiverProviderAt() answers for). A creek is a RiverProvider, so the deck carves its water
   // from that exact instance. null for the main rivers: their behaviour is unchanged.
   private final RiverProvider carrier;

   public BridgeParcel(UrbanGrid grid, Cartesian2D base, boolean deckAxisX, boolean wasHighway) {
      this(grid, base, deckAxisX, wasHighway, null);
   }

   public BridgeParcel(UrbanGrid grid, Cartesian2D base, boolean deckAxisX, boolean wasHighway, RiverProvider carrier) {
      super(base, size, wasHighway ? ContextType.HIGHWAY : ContextType.STREET, SchematicType.BRIDGE, grid);
      this.deckAxisX = deckAxisX;
      this.wasHighway = wasHighway;
      this.carrier = carrier;
      grid.fillParcels(this.chunkX, this.chunkZ, this);
   }

   /**
    * Whether this bridge cell is a real street connection on the given
    * side. ContextType.STREET/HIGHWAY is kept on all four faces (see the
    * constructor javadoc) so unrelated lookups -- decay, growth, the
    * plain isStreet(Parcel) check -- still see this cell as road network
    * no matter which side they approach from. But the bridge only
    * actually CONTINUES the road at its two span ends, along
    * deckAxisX's own axis; the other two sides are the parapet running
    * down the length of the deck, not a junction.
    * Parcel.isStreet(Parcel, Direction) is the direction-aware entry
    * point that calls this -- RoadParcel/HighwayParcel use it instead of
    * the plain check specifically so a road sitting alongside a bridge
    * doesn't grow a T-junction stub into its side.
    *
    * direction is the axis being probed, either as seen from the
    * neighbour looking toward this bridge or the reverse -- EAST and
    * WEST both mean "the X axis" and NORTH/SOUTH both mean "the Z axis",
    * so which of the pair is passed doesn't matter, only which pair.
    */
   /** The creek explicitly owning this span; null denotes a main-river crossing. */
   public RiverProvider getCarrier() {
      return this.carrier;
   }

   public boolean connectsOnSide(Direction direction) {
      if (this.deckAxisX) {
         return direction == Direction.EAST || direction == Direction.WEST;
      }
      return direction == Direction.NORTH || direction == Direction.SOUTH;
   }

   /** Preserve the R30ea creek-carrier carve choice even when an ocean tile overlaps. */
   static boolean useOceanCarver(boolean oceanTile, RiverProvider carrier) {
      return oceanTile && carrier == null;
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
      OceanProvider ocean = this.grid.getOceanProvider();
      boolean oceanTile = OceanConfig.isOceanEnabled()
              && ocean.tileReached(OceanProvider.tileRootOf(this.chunkX), OceanProvider.tileRootOf(this.chunkZ));
      GridRandom rand = this.grid.getRandom();
      int baseX = this.chunkX << 4;
      int baseZ = this.chunkZ << 4;
      int waterY = RiverConfig.getRiverBuildHeight() - RiverConfig.getRiverDepth();
      // The deck sits at the road's ACTUAL rendered surface height, not at
      // a value re-derived from buildHeight/riverDepth/bridgeClearance --
      // this chunk is replacing a real RoadParcel/HighwayParcel cell (see
      // UrbanGrid.convertRoadsToBridges()), so the deck has to line up
      // with whatever that road actually built at, which is
      // StreetParcel.ROAD_SURFACE_Y by construction. Deriving deckY from
      // config instead risked exactly the failure this replaced: the two
      // numbers currently agree only because their defaults happen to
      // coincide, and silently step apart the moment either config value
      // changes without the other -- see ROAD_SURFACE_Y's own javadoc.
      int deckY = StreetParcel.ROAD_SURFACE_Y;
      this.warnIfClearanceBelowConfigured(deckY, waterY);

      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            // Same reclamation-aware ramp RiverParcel itself uses -- a
            // bridge approach can run over reclaimed bank, and its
            // columns have to agree with the neighbouring RiverParcel
            // columns about where that bank tops out.
            if (useOceanCarver(oceanTile, this.carrier)) {
               // An ocean tile's terrain -- rivers included -- is painted by the
               // ocean's own painter, so the columns under the deck have to be
               // too, or the bank would notch at every bridge.
               // Use the same grid-aware river-mouth bank smoothing as an
               // adjacent OceanParcel, not the legacy gridless beach ramp.
               // An ocean-owned TILE remains ocean-owned: selecting an
               // ordinary-river carver by raw per-column coast distance
               // would disagree with its neighbour's planned painter.
               OceanParcel.carveColumn(world, ocean, this.grid, rand, baseX + lx, baseZ + lz);
            } else {
               RiverParcel.carveColumnWithCreeks(world, river, this.grid, rand, baseX + lx, baseZ + lz);
            }
         }
      }

      // Schematic-first: look for a BRIDGE-tagged clip fitting this cell's
      // preserved road context (HIGHWAY/STREET) before falling back to the
      // procedural build below. Queried under BOTH directions along the
      // deck's own travel axis (EAST+WEST for an east-west deck, NORTH+
      // SOUTH for a north-south one) rather than just one -- a straight
      // span has no real "facing" the way a building's entrance does, so
      // an author shouldn't have to duplicate the identical schematic
      // under two direction tags just to cover both; either tag along the
      // span's own axis is treated as equivalent here.
      ContextType context = this.wasHighway ? ContextType.HIGHWAY : ContextType.STREET;
      Direction primaryDirection = this.deckAxisX ? Direction.EAST : Direction.NORTH;
      Direction secondaryDirection = this.deckAxisX ? Direction.WEST : Direction.SOUTH;
      ClipboardProvider cprovider = generator.getClipboardProvider();
      List<Clipboard> clips = new ArrayList<>(cprovider.getFit(size, context, SchematicType.BRIDGE, primaryDirection));
      clips.addAll(cprovider.getFit(size, context, SchematicType.BRIDGE, secondaryDirection));

      if (!clips.isEmpty()) {
         this.schematic = clips.get(rand.getRandomInt(clips.size()));
         this.schematic.paste(generator, new Cartesian2D(this.chunkX, this.chunkZ), deckY);
         Minions.dRiver("BridgeParcel chunk=(%d,%d): pasted schematic bridge groupId=%s",
                 this.chunkX, this.chunkZ, this.schematic.getGroupId());
      } else {
         this.schematic = null;
         this.buildProceduralDeck(world, baseX, baseZ, deckY);
      }

      this.markPopulated();
   }

   // How far in from each outer edge (baseZ/baseZ+15 for an east-west deck,
   // baseX/baseX+15 for a north-south one) the support piers sit, and how
   // wide (in both footprint directions) each individual pier is. Piers
   // used to be a single 1-block column 4 blocks in from each edge --
   // moved out to sit right behind the curb, and widened to a small
   // square, for a sturdier-looking support closer to where the deck's
   // actual outer edge overhangs it.
   private static final int SIDEWALK_WIDTH = 2;
   private static final int PIER_EDGE_OFFSET = 2;
   private static final int PIER_THICKNESS = 2;
   // How far apart (in blocks along the span) piers are dropped. Was 4 --
   // 4 pairs per 16-block chunk -- which read as too tightly spaced for
   // how tall/substantial the piers themselves are; doubled to 8 (2 pairs
   // per chunk) to space them out.
   private static final int PIER_SPACING = 8;

   /**
    * The original hand-built deck/curb/parapet/sidewalk/pier geometry recovered from R30ea,
    * just extracted out of populate() so it only runs when no BRIDGE
    * schematic was found for this cell's context/direction.
    */
   private void buildProceduralDeck(World world, int baseX, int baseZ, int deckY) {
      // Stone brick directly under the deck across the full
      // footprint -- placed BEFORE the deck surface itself so a curb
      // override at deckY never has to also reach down and re-place what's
      // beneath it.
      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            world.getBlockAt(baseX + lx, deckY - 1, baseZ + lz)
                    .setType(STONE_BRICK, false);
         }
      }

      // Default road fill across the full footprint first -- the plain
      // stone travel lane. Curb/parapet columns below deliberately
      // override part of this afterward rather than being skipped here,
      // so the fill logic never has to know where the edges are.
      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            world.getBlockAt(baseX + lx, deckY, baseZ + lz).setType(ROAD_LANE_MATERIAL, false);
            // Deck must be clear of anything RiverParcel's own AIR-clear
            // pass didn't reach because this column was outside its
            // width+bankMargin band (e.g. bare ancestor road strip).
            world.getBlockAt(baseX + lx, deckY + 1, baseZ + lz).setType(Material.AIR, false);
            world.getBlockAt(baseX + lx, deckY + 2, baseZ + lz).setType(Material.AIR, false);
         }
      }

      // Curb, parapet wall caps and two smooth-stone sidewalk strips along the two edges parallel to travel direction
      // (the edges actually facing the water) -- stone brick overriding
      // the default road fill at deckY, plus a one-block parapet wall
      // above it. Support piers, also stone brick, drop down to the bed
      // every few blocks along the span.
      if (deckAxisX) {
         for (int lx = 0; lx < 16; lx++) {
            placeBridgeEdge(world, baseX + lx, deckY, baseZ, true);
            placeBridgeEdge(world, baseX + lx, deckY, baseZ + 15, true);
            for (int sideOffset = 1; sideOffset <= SIDEWALK_WIDTH; sideOffset++) {
               placeSmoothSidewalk(world, baseX + lx, deckY, baseZ + sideOffset);
               placeSmoothSidewalk(world, baseX + lx, deckY, baseZ + 15 - sideOffset);
            }
            if (lx % PIER_SPACING == 0) {
               dropPier(world, baseX + lx, deckY - 2, baseZ + PIER_EDGE_OFFSET);
               dropPier(world, baseX + lx, deckY - 2, baseZ + 15 - PIER_EDGE_OFFSET - (PIER_THICKNESS - 1));
            }
         }
      } else {
         for (int lz = 0; lz < 16; lz++) {
            placeBridgeEdge(world, baseX, deckY, baseZ + lz, false);
            placeBridgeEdge(world, baseX + 15, deckY, baseZ + lz, false);
            for (int sideOffset = 1; sideOffset <= SIDEWALK_WIDTH; sideOffset++) {
               placeSmoothSidewalk(world, baseX + sideOffset, deckY, baseZ + lz);
               placeSmoothSidewalk(world, baseX + 15 - sideOffset, deckY, baseZ + lz);
            }
            if (lz % PIER_SPACING == 0) {
               dropPier(world, baseX + PIER_EDGE_OFFSET, deckY - 2, baseZ + lz);
               dropPier(world, baseX + 15 - PIER_EDGE_OFFSET - (PIER_THICKNESS - 1), deckY - 2, baseZ + lz);
            }
         }
      }
   }

   /** R30ea bridge edges and two-wide sidewalks, built after lane fill. */
   private void placeBridgeEdge(World world, int x, int deckY, int z, boolean axisX) {
      world.getBlockAt(x, deckY, z).setType(STONE_BRICK, false);
      world.getBlockAt(x, deckY + 1, z).setType(STONE_BRICK, false);
      Wall wall = (Wall) Material.STONE_BRICK_WALL.createBlockData();
      // Both sides of a straight bridge parapet are uninterrupted wall runs.
      // With up=true every segment raises a central post (crenellations), even
      // though the EAST/WEST or NORTH/SOUTH arms already connect. Match the
      // smooth, flat-top wall profile used on continuous culvert caps instead.
      // Keep the existing low arms and structural brick courses unchanged.
      wall.setUp(false);
      if (axisX) {
         wall.setHeight(BlockFace.EAST, Wall.Height.LOW);
         wall.setHeight(BlockFace.WEST, Wall.Height.LOW);
      } else {
         wall.setHeight(BlockFace.NORTH, Wall.Height.LOW);
         wall.setHeight(BlockFace.SOUTH, Wall.Height.LOW);
      }
      world.getBlockAt(x, deckY + 2, z).setBlockData(wall, false);
   }

   private void placeSmoothSidewalk(World world, int x, int deckY, int z) {
      Slab slab = (Slab) Material.SMOOTH_STONE_SLAB.createBlockData();
      slab.setType(Slab.Type.BOTTOM);
      world.getBlockAt(x, deckY + 1, z).setBlockData(slab, false);
   }

   /**
    * deckY is now pinned to the real road height (StreetParcel.
    * ROAD_SURFACE_Y) rather than derived from riverBridgeClearance -- see
    * that constant's javadoc. That's the correct fix for making the deck
    * actually line up with the road it replaces, but it does mean
    * riverBridgeClearance is no longer the thing that DECIDES the gap
    * between deck and water; it's just what the admin configured
    * expecting it to be. If the road's real height and the
    * river.buildHeight/depth-derived water level don't leave at least that
    * much room, that combination of config values doesn't describe this
    * server's actual road height -- surfaced here once per bridge chunk
    * rather than silently rendering a bridge with less clearance than
    * configured.
    */
   private void warnIfClearanceBelowConfigured(int deckY, int waterY) {
      int actualClearance = deckY - waterY;
      int configuredClearance = RiverConfig.getRiverBridgeClearance();
      if (actualClearance < configuredClearance) {
         Minions.dRiver("BridgeParcel chunk=(%d,%d): actual deck-to-water clearance is %d block(s) (deckY=%d, waterY=%d), below the configured riverBridgeClearance of %d -- generator.river.buildHeight/depth don't currently agree with the road system's real height (StreetParcel.ROAD_SURFACE_Y=%d).",
                 this.chunkX, this.chunkZ, actualClearance, deckY, waterY, configuredClearance, StreetParcel.ROAD_SURFACE_Y);
      }
   }

   /**
    * Drops a small square (PIER_THICKNESS x PIER_THICKNESS) stone brick
    * pier straight down from just under the slab to the riverbed (or
    * whatever solid ground it first hits) -- (x,z) is the pier's corner,
    * extending PIER_THICKNESS-1 blocks further in both +X and +Z.
    *
    * fromY is deckY - 2, not deckY - 1: deckY - 1 is now occupied by the
    * stone brick slab added above, which is solid rather than the open
    * air/water this loop expects to walk through. Starting one block
    * lower than before means the pier is planted flush against the
    * underside of that slab instead of starting a block short of it or
    * immediately breaking out of the loop because the slab itself reads
    * as "not air/water".
    */
   private void dropPier(World world, int x, int fromY, int z) {
      for (int dx = 0; dx < PIER_THICKNESS; dx++) {
         for (int dz = 0; dz < PIER_THICKNESS; dz++) {
            dropPierColumn(world, x + dx, fromY, z + dz);
         }
      }
   }

   private void dropPierColumn(World world, int x, int fromY, int z) {
      for (int y = fromY; y >= 1; y--) {
         Material existing = world.getBlockAt(x, y, z).getType();
         if (!existing.isAir() && existing != Material.WATER) {
            break;
         }
         world.getBlockAt(x, y, z).setType(STONE_BRICK, false);
      }
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
      if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) {
         return;
      }
      int deckY = StreetParcel.ROAD_SURFACE_Y;
      if (this.schematic != null) {
         // Same decay path RoadParcel uses for its own pasted clips -- the
         // schematic's own authored DecayOption applies, not the generic
         // stone-brick decay decayRoadChunk() below assumes.
         generator.getDecayProvider()
                 .destroyChunks(
                         this.chunkX,
                         this.chunkZ,
                         this.chunkSizeX,
                         this.chunkSizeZ,
                         this.schematic.getBottom(deckY),
                         this.schematic.getSize().Y,
                         this.schematic.getConfig().getDecayOption(),
                         this.grid
                 );
      } else {
         // Reuses StreetParcel's own decay -- same treatment ordinary road
         // surfaces get, just applied at the deck's elevation instead of
         // the hardcoded street level RoadParcel uses.
         this.decayRoadChunk(generator, chunk, deckY);
      }

      if (generator.getWorld() != null) {
         generator.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
      }

      // Same reasoning as RoadParcel/HighwayParcel -- tell adjacent
      // buildings (if any manage to border a bridge chunk) to recut toward
      // it now that paste + decay are done.
      this.notifyNeighboursToRecut(generator);
   }
}