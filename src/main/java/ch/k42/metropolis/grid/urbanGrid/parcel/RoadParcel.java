package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import org.bukkit.Material;
import org.bukkit.Chunk;
import org.bukkit.World;

public class RoadParcel extends StreetParcel {
   private static final Cartesian2D size = new Cartesian2D(1, 1);
   private Clipboard road;

   public RoadParcel(UrbanGrid grid, Cartesian2D base) {
      super(base, size, ContextType.STREET, SchematicType.ROAD, grid);
      grid.fillParcels(this.chunkX, this.chunkZ, this);
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
      if (this.road != null) {
         generator.getDecayProvider()
                 .destroyChunks(
                         this.chunkX,
                         this.chunkZ,
                         this.chunkSizeX,
                         this.chunkSizeZ,
                         this.road.getBottom(65),
                         this.road.getSize().Y,
                         this.road.getConfig().getDecayOption(),
                         this.grid
                 );
      }

      if (generator.getWorld() != null) {
         generator.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
      }

      // Paste + decay + sidewalk are all done. Tell any adjacent building
      // parcels to re-cut their faces toward this road chunk now.
      // notifyNeighboursToRecut() looks up neighbours via GridProvider so
      // it crosses grid boundaries correctly -- this.grid.getParcel() would
      // only work within the same 64x64 grid, silently returning the wrong
      // parcel (or null) for buildings that sit in an adjacent grid.
      this.notifyNeighboursToRecut(generator);
   }

   /**
    * The shape of road this cell is, from which of its four neighbours are streets or highways: a straight, a
    * T, an X, a corner or a dead end (see RoadType), or null for an isolated cell with no road next to it.
    * populate() places the schematic of this type, and UrbanGrid.asciiMap() reports it, so the debug map shows
    * exactly what generation will build.
    */
   public RoadType resolveRoadType() {
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.grid.roadShapes();
      if (shapes != null && shapes.covers(this.chunkX, this.chunkZ)) {
         RoadType joined = this.grid.creekConnectedRoadType(this.chunkX, this.chunkZ);
         if (joined != null) return joined;
         RoadType planned = shapes.roadTypeAt(this.chunkX, this.chunkZ);
         if (planned != null) return planned;
         // An accepted creek-side district ring can be reserved after the
         // original WorldLayout shapes were built. Its RoadParcel is real,
         // but roadTypeAt() returns null because that immutable plan did not
         // include the late accepted ring. Do not silently skip its paste.
         // Only accepted creek-local roads may use parcel-neighbour shape
         // resolution; arbitrary planner holes must remain visible failures.
         if (this.grid.isAcceptedCreekLocalRoad(this.chunkX, this.chunkZ))
            return siteRoadPiece();
         return null;
      }
      // Not every valid road is in the immutable city road plan. An
      // accepted creek-site approach can be inserted after that plan was
      // constructed. Infer only its existing connected neighbours, plus
      // the route edges explicitly reserved by UrbanGrid.
      return siteRoadPiece();
   }

   private RoadType siteRoadPiece() {
      int mask = 0;
      int[] dx = {0, 1, 0, -1};
      int[] dz = {-1, 0, 1, 0};
      int[] bit = {PieceOpenness.N, PieceOpenness.E, PieceOpenness.S, PieceOpenness.W};
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.grid.roadShapes();
      for (int direction = 0; direction < 4; direction++) {
         int x = this.chunkX + dx[direction];
         int z = this.chunkZ + dz[direction];
         Parcel other = this.grid.peekParcel(x, z);
         int facing = PieceOpenness.opposite(bit[direction]);
         if (other instanceof RoadParcel) {
            if (shapes == null || !shapes.covers(x, z)
                    || this.grid.isAcceptedCreekLocalRoad(x, z)
                    || (this.grid.creekRequiredSides(x, z) & facing) != 0
                    || PieceOpenness.opens(shapes.roadTypeAt(x, z), facing)) {
               mask |= bit[direction];
            }
         } else if (other instanceof HighwayParcel) {
            if (PieceOpenness.opens(((HighwayParcel) other).resolveRoadType(), facing)) {
               mask |= bit[direction];
            }
         } else if (other instanceof BridgeParcel) {
            // BridgeParcel.connectsOnSide(EAST) reports a deck on the X axis.
            boolean eastWest = ((BridgeParcel) other).connectsOnSide(Direction.EAST);
            if (eastWest ? (bit[direction] == PieceOpenness.E || bit[direction] == PieceOpenness.W)
                    : (bit[direction] == PieceOpenness.N || bit[direction] == PieceOpenness.S)) {
               mask |= bit[direction];
            }
         }
      }
      mask |= this.grid.creekRequiredSides(this.chunkX, this.chunkZ);
      switch (mask) {
         case PieceOpenness.N: return RoadType.STREET_D_N;
         case PieceOpenness.E: return RoadType.STREET_D_E;
         case PieceOpenness.S: return RoadType.STREET_D_S;
         case PieceOpenness.W: return RoadType.STREET_D_W;
         case PieceOpenness.N | PieceOpenness.E: return RoadType.STREET_C_NE;
         case PieceOpenness.E | PieceOpenness.S: return RoadType.STREET_C_SE;
         case PieceOpenness.S | PieceOpenness.W: return RoadType.STREET_C_SW;
         case PieceOpenness.N | PieceOpenness.W: return RoadType.STREET_C_NW;
         case PieceOpenness.N | PieceOpenness.S: return RoadType.STREET_I_NS;
         case PieceOpenness.E | PieceOpenness.W: return RoadType.STREET_I_EW;
         case PieceOpenness.N | PieceOpenness.E | PieceOpenness.S: return RoadType.STREET_T_E;
         case PieceOpenness.E | PieceOpenness.S | PieceOpenness.W: return RoadType.STREET_T_S;
         case PieceOpenness.N | PieceOpenness.S | PieceOpenness.W: return RoadType.STREET_T_W;
         case PieceOpenness.N | PieceOpenness.E | PieceOpenness.W: return RoadType.STREET_T_N;
         case PieceOpenness.ALL: return RoadType.STREET_X;
         default: return null;
      }
   }

   @Override
   public void populate(MetropolisGenerator generator, Chunk chunk) {
      if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) {
         Minions.d("Wanted to place road where it should not belong...");
         return;
      }
      RoadType type = this.resolveRoadType();
      ClipboardProvider cprovider = generator.getClipboardProvider();
      GridRandom random = this.grid.getRandom();
      List<Clipboard> clips = null;
      if (type == null) {
         Minions.dReclaimRoads("populate() -- %s -- isolated roadcell, no neighbouring street/highway on any side, nothing placed", this);
      } else {
         clips = cprovider.getRoadFit(type);
      }
      // Preserve genuine author-supplied dead-end assets whenever present.
      // Only if absent use a matching CLEAR straight road and close its tip.
      boolean makeCap = (clips == null || clips.isEmpty()) && isEndCap(type);
      if (makeCap) {
         RoadType straight = type == RoadType.STREET_D_N || type == RoadType.STREET_D_S
                 ? RoadType.STREET_I_NS : RoadType.STREET_I_EW;
         List<Clipboard> clear = cprovider.getRoadFit(straight);
         List<Clipboard> choices = new ArrayList<Clipboard>();
         if (clear != null) {
            for (Clipboard candidate : clear) {
               if (candidate != null && candidate.getConfig() != null
                       && candidate.getConfig().getBuildName() != null
                       && candidate.getConfig().getBuildName().toUpperCase(Locale.ROOT).contains("CLEAR")) {
                  choices.add(candidate);
               }
            }
         }
         clips = choices;
      }
      if (clips != null && !clips.isEmpty()) {
         // Road approaches are owned by RoadParcel, not BridgeParcel. Give
         // their underlying soil the same per-column river/creek/ocean-bank
         // slope that the bridge paints before adding the street schematic.
         // Each chunk paints ONLY itself in its normal populate lifecycle.
         this.carveBridgeApproachGround(generator, chunk);
         if (type != null && type.name().startsWith("STREET_D_")) {
            Minions.dReclaimRoads("ROAD PAINTER PROBE chunk=(%d,%d) piece=%s candidateSchematics=%d beforeCenter64=%s beforeCenter65=%s",
                    this.chunkX,this.chunkZ,type,clips.size(),chunk.getBlock(8,64,8).getType(),chunk.getBlock(8,65,8).getType());
         }
         this.road = clips.get(random.getRandomInt(clips.size()));
         this.road.paste(generator, new Cartesian2D(this.chunkX, this.chunkZ), 65);
         if (makeCap) {
            paintClosedRoadEnd(chunk, type, this.road.getBottom(65));
            Minions.i("ROAD END CAP GENERATED chunk=(%d,%d) type=%s from clear straight (author-supplied cap missing)",
                    this.chunkX, this.chunkZ, type);
         }
         if (type != null && type.name().startsWith("STREET_D_")) {
            Minions.dReclaimRoads("ROAD PAINTER PROBE chunk=(%d,%d) piece=%s selectedGroup=%s afterCenter64=%s afterCenter65=%s",
                    this.chunkX,this.chunkZ,type,this.road.getGroupId(),chunk.getBlock(8,64,8).getType(),chunk.getBlock(8,65,8).getType());
         }
         this.decayRoadChunk(generator, chunk, StreetParcel.ROAD_SURFACE_Y);
         this.decaySidewalk(generator, chunk, 64);
      } else {
         if (type != null) Minions.w("ROAD PAINTER MISSING SCHEMATIC chunk=(%d,%d) type=%s; this is a painter/library failure, NOT a road-plan disconnection",
                 this.chunkX, this.chunkZ, type);
         Minions.dReclaimRoads("populate() -- %s -- road type %s -- getRoadFit() returned no candidates for this combination",
                 this, type);
      }
   }

   /**
    * Adjacent bridge deck is a continuation only along its travel axis;
    * never borrow one at the side of a bridge or across a tile boundary
    * that has not yet been planned. Forcing a new tile to generate here
    * would reintroduce the nested planner stalls this checkpoint removes.
    */
   private BridgeParcel connectedBridgeApproach() {
      int[][] dirs = {{1,0}, {-1,0}, {0,1}, {0,-1}};
      for (int[] offset : dirs) {
         Parcel neighbour = this.grid.peekParcel(this.chunkX + offset[0], this.chunkZ + offset[1]);
         if (!(neighbour instanceof BridgeParcel bridge)
               || bridge.getContextType() != ContextType.STREET) continue;
         if (isBridgeAxisApproach(bridge.connectsOnSide(Direction.EAST), offset[0], offset[1]))
            return bridge;
      }
      return null;
   }

   /** Exact axis rule, tested separately so a bridge flank can never be
    * mistaken for an approach and painted as a road-bank ramp. */
   static boolean isBridgeAxisApproach(boolean deckAxisX, int dx, int dz) {
      return (Math.abs(dx) + Math.abs(dz) == 1)
              && (deckAxisX ? dz == 0 : dx == 0);
   }

   private void carveBridgeApproachGround(MetropolisGenerator generator, Chunk chunk) {
      BridgeParcel bridge = connectedBridgeApproach();
      if (bridge == null) return;
      RiverProvider river = bridge.getCarrier() != null ? bridge.getCarrier()
              : this.grid.getRiverProviderAt(bridge.getChunkX(), bridge.getChunkZ());
      OceanProvider ocean = this.grid.getOceanProvider();
      boolean oceanTile = OceanConfig.isOceanEnabled()
              && ocean.tileReached(OceanProvider.tileRootOf(this.chunkX), OceanProvider.tileRootOf(this.chunkZ));
      // A main-river bridge in an ocean tile uses the ocean painter.
      // An actual bounded creek bridge ALWAYS uses its own creek carrier.
      if (!BridgeParcel.useOceanCarver(oceanTile, bridge.getCarrier()) && river == null)
         return;
      World world = generator.getWorld();
      if (world == null) return;
      int bx = this.chunkX << 4, bz = this.chunkZ << 4;
      // Do not consume the shared district-selection RNG while carving an
      // optional approach. The same world/chunk generates the same palette
      // irrespective of whether neighbouring chunks populated first.
      GridRandom random = new GridRandom(generator.getWorldSeed(), this.chunkX, this.chunkZ);
      for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
         if (BridgeParcel.useOceanCarver(oceanTile, bridge.getCarrier()))
            OceanParcel.carveColumn(world, ocean, this.grid, random, bx + x, bz + z);
         else
            RiverParcel.carveColumnWithCreeks(world, river, this.grid, random, bx + x, bz + z);
      }
   }

   private static boolean isEndCap(RoadType type) {
      return type == RoadType.STREET_D_N || type == RoadType.STREET_D_E
              || type == RoadType.STREET_D_S || type == RoadType.STREET_D_W;
   }

   /** Three-cell deep pavement closure for a missing one-sided road asset.
    * Surface levels follow the selected clear straight road schematic.
    */
   private static void paintClosedRoadEnd(Chunk chunk, RoadType type, int bottom) {
      boolean northSouth = type == RoadType.STREET_D_N || type == RoadType.STREET_D_S;
      boolean highSide = type == RoadType.STREET_D_N || type == RoadType.STREET_D_W;
      for (int end = 0; end < 3; end++) {
         int boundary = highSide ? 15 - end : end;
         for (int across = 3; across <= 12; across++) {
            int x = northSouth ? across : boundary;
            int z = northSouth ? boundary : across;
            for (int layer = 0; layer < 12; layer++) {
               chunk.getBlock(x, bottom + layer, z).setType(Material.STONE, false);
            }
            chunk.getBlock(x, bottom + 12, z).setType(Material.STONE_BRICKS, false);
            chunk.getBlock(x, bottom + 13, z).setType(Material.SMOOTH_STONE_SLAB, false);
            chunk.getBlock(x, bottom + 14, z).setType(Material.AIR, false);
         }
      }
   }

   @Override
   public String toString() {
      String info = "RoadParcel +[" + this.chunkX + "][" + this.chunkZ + "] ";
      if (this.road != null) {
         info = info + "Schemname: " + this.road;
      } else {
         info = info + " No schem found. ";
      }

      return info;
   }
}