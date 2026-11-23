package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayPieces;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.List;
import org.bukkit.Chunk;

public class HighwayParcel extends StreetParcel {
   private RoadType roadType = RoadType.NONE;
   private static final Cartesian2D size = new Cartesian2D(1, 1);
   private Clipboard road;
   /** {role, ox, oz, a, b} if this lane is part of a bend (see HighwayNetwork.bendInfo()), else null. */
   private int[] bend;

   /** The street level every highway piece is pasted at. */
   private static final int HIGHWAY_PASTE_LEVEL = 65;
   /**
    * How many blocks lower the H-Tin* schematics and the bend's outer corner (HIGHWAY_TURNOUT_*, which lines up with them)
    * are pasted than every other highway piece. They are authored one block higher than the rest, so with no offset their
    * surface sits one block above the road beside them. Change this one number to nudge them (1 = one block lower, 0 = same
    * as the others, -1 = one higher).
    */
   private static final int TIN_Y_OFFSET = 1;

   /** Set by getFits(): true when a missing Tin or outer-corner schematic was replaced by the plain T or turn, which needs no offset. */
   private boolean tinFellBack;

   private static final int[] DIR_X = {0, 1, 0, -1};
   private static final int[] DIR_Z = {-1, 0, 1, 0};
   private static final Direction[] DIRS = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

   public HighwayParcel(UrbanGrid grid, Cartesian2D base, RoadType roadType) {
      super(base, size, ContextType.HIGHWAY, SchematicType.HIGHWAY, grid);
      this.roadType = roadType;
   }

   /** Called by UrbanGrid when it places the lane: this chunk's place in the 2x2 block of a bend, from the network. */
   public void setBend(int[] bend) {
      this.bend = bend;
   }

   @Override
   public void populate(MetropolisGenerator generator, Chunk chunk) {
      this.findAndPlaceClip(generator, chunk, this.resolveRoadType());
   }

   /**
    * The piece this lane places, by the written rules in HighwayPieces: a street joining a straight lane makes it a
    * T, a lane beside a C is a T toward it, and a bend is decided cell by cell (inner turn always an open cell, the
    * straights beside the outer turn Ts toward it, the outer turn a plain bend until streets join it).
    * populate() pastes the schematic of this type, and UrbanGrid.asciiMap() reports it, so the debug map shows
    * exactly what generation will build. Always worked out from the type the network gave this lane and from the
    * parcels around it, never from what a neighbouring lane resolved to, so there is no order to depend on.
    */
   public RoadType resolveRoadType() {
      ch.k42.metropolis.grid.urbanGrid.layout.RoadShapes shapes = this.grid.roadShapes();
      if (shapes != null && shapes.covers(this.chunkX, this.chunkZ)) {
         return shapes.roadTypeAt(this.chunkX, this.chunkZ);     // from the layout alone, no neighbouring parcel read
      }
      return this.roadType;     // not a lane of the plan: the piece the network gave it

   }

   /** The piece the network gave this lane, before any street or neighbour changed it. */
   public RoadType baseRoadType() {
      return this.roadType;
   }

   /** Does a street join this lane, so that it is a junction with a street rather than the plain piece the network gave it? */
   public boolean streetJoins() {
      return this.resolveRoadType() != this.roadType;
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
      if (generator.getWorld() != null) {
         generator.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
      }

      // See RoadParcel.postPopulate() for the full explanation.
      this.notifyNeighboursToRecut(generator);
   }

   private void findAndPlaceClip(MetropolisGenerator generator, Chunk chunk, RoadType roadType) {
      List<Clipboard> clips = this.getFits(generator.getClipboardProvider(), roadType);
      Clipboard clip = null;
      if (clips != null && clips.size() > 0) {
         clip = clips.get(this.grid.getRandom().getRandomInt(clips.size()));
      }

      if (clip != null) {
         boolean tin = roadType != null && (roadType.name().startsWith("HIGHWAY_TIN") || roadType.name().startsWith("HIGHWAY_TURNOUT_")) && !this.tinFellBack;
         clip.paste(generator, new Cartesian2D(this.chunkX, this.chunkZ), HIGHWAY_PASTE_LEVEL - (tin ? TIN_Y_OFFSET : 0));
         this.road = clip;
         this.decayRoadChunk(generator, chunk, StreetParcel.ROAD_SURFACE_Y);
         this.decaySidewalk(generator, chunk, 64);
      } else {
         Minions.d("Haven't found any HIGHWAY schem for: " + roadType.toString());
      }
   }

   private List<Clipboard> getFits(ClipboardProvider cprovider, RoadType type) {
      List<Clipboard> fits = cprovider.getRoadFit(type);
      this.tinFellBack = false;
      if ((fits == null || fits.isEmpty()) && type != null && type.name().startsWith("HIGHWAY_TINM_")) {
         // No mirrored Tin schematic: the Tin as drawn, of the same turn-off.
         type = RoadType.valueOf("HIGHWAY_TIN_" + type.name().substring(type.name().length() - 1));
         fits = cprovider.getRoadFit(type);
      }
      if ((fits == null || fits.isEmpty()) && type != null && type.name().startsWith("HIGHWAY_TIN_")) {
         // A server without the HIGHWAY_TIN_* schematics still gets its junction: the plain tee of the same turn-off.
         RoadType plain = RoadType.valueOf("HIGHWAY_T_" + type.name().substring(type.name().length() - 1));
         fits = cprovider.getRoadFit(plain);
         this.tinFellBack = true;
      }
      if ((fits == null || fits.isEmpty()) && type != null && type.name().startsWith("HIGHWAY_TURNOUT_")) {
         // A server without the HIGHWAY_TURNOUT_* schematics still gets its bend: the plain turn of the same corner.
         fits = cprovider.getRoadFit(RoadType.valueOf("HIGHWAY_TURN_" + type.name().substring("HIGHWAY_TURNOUT_".length())));
         this.tinFellBack = true;
      }
      return fits;
   }

   @Override
   public String toString() {
      String info = "HighWay +[" + this.chunkX + "][" + this.chunkZ + "] ";
      if (this.road != null) {
         info = info + "Schemname: " + this.road;
      } else {
         info = info + " No schem found. ";
      }

      return info;
   }
}