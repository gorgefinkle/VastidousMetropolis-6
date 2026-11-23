package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayPieces;

/**
 * The piece (straight, T, X, corner, dead end, highway side, ...) of every road and highway chunk, worked out from the
 * world layout and the highway network alone. No parcel, no tile, no generation order: the answer for a chunk is the
 * same whoever asks and whichever tile was built first, so nothing has to look at a neighbouring parcel (peekParcel)
 * to decide how a road joins another. Pure Java, no Bukkit.
 *
 * A street joins a street always, and a highway lane when the lane's own piece is open toward it. A lane's piece
 * is HighwayPieces.resolve() over the network piece, its bend and the streets that join it.
 */
public final class RoadShapes {
   private static final int[] DX = {0, 1, 0, -1};
   private static final int[] DZ = {-1, 0, 1, 0};      // N, E, S, W: side bit = 1 << index (PieceOpenness.N/E/S/W)

   private final WorldLayout layout;
   private final HighwayNetwork network;
   private final PlanRegions regions;

   public RoadShapes(WorldLayout layout, HighwayNetwork network) {
      this.layout = layout;
      this.network = network;
      this.regions = new PlanRegions(layout);
   }

   /** Which streets dissolve into farm or forest, and the regions of countryside (see PlanRegions). */
   public PlanRegions regions() {
      return this.regions;
   }

   /** A street of the layout that is still a street: not dissolved into the countryside around it. */
   public boolean isRoadStreet(int x, int z) {
      return this.layout.kindAt(x, z) == WorldLayout.STREET && !this.regions.dissolvedAt(x, z);
   }

   private boolean isRoadCell(int x, int z) {
      int k = this.layout.kindAt(x, z);
      return k == WorldLayout.LANE || (k == WorldLayout.STREET && !this.regions.dissolvedAt(x, z));
   }

   /** The piece of the street or lane at (x, z); null for a chunk that is no road, and for a street with no road beside it. */
   public RoadType roadTypeAt(int x, int z) {
      int k = layout.kindAt(x, z);
      if (k == WorldLayout.STREET) {
         return this.regions.dissolvedAt(x, z) ? null : streetType(x, z);
      }
      if (k == WorldLayout.LANE) {
         return laneType(x, z);
      }
      return null;
   }

   /** Is chunk (x, z) a street or lane of the layout? Only then does the layout decide its piece. */
   public boolean covers(int x, int z) {
      int k = layout.kindAt(x, z);
      return k == WorldLayout.STREET || k == WorldLayout.LANE;
   }

   /** Which sides (PieceOpenness bit mask) the road at (x, z) is open on; 0 for no road. */
   public int opennessAt(int x, int z) {
      return PieceOpenness.sides(roadTypeAt(x, z));
   }

   // ------------------------------------------------------------------- streets

   private RoadType streetType(int x, int z) {
      boolean n = joins(x, z, 0), e = joins(x, z, 1), s = joins(x, z, 2), w = joins(x, z, 3);
      if (n) {
         if (s) {
            return e ? (w ? RoadType.STREET_X : RoadType.STREET_T_E) : (w ? RoadType.STREET_T_W : RoadType.STREET_I_NS);
         }
         return e ? (w ? RoadType.STREET_T_N : RoadType.STREET_C_NE) : (w ? RoadType.STREET_C_NW : RoadType.STREET_D_N);
      }
      if (s) {
         return e ? (w ? RoadType.STREET_T_S : RoadType.STREET_C_SE) : (w ? RoadType.STREET_C_SW : RoadType.STREET_D_S);
      }
      if (e) {
         return w ? RoadType.STREET_I_EW : RoadType.STREET_D_E;
      }
      return w ? RoadType.STREET_D_W : null;
   }

   /** Does the street at (x, z) carry on into its neighbour on side dir (0 N, 1 E, 2 S, 3 W)? */
   private boolean joins(int x, int z, int dir) {
      int nx = x + DX[dir], nz = z + DZ[dir];
      int k = layout.kindAt(nx, nz);
      if (k == WorldLayout.STREET) {
         return !this.regions.dissolvedAt(nx, nz);
      }
      if (k == WorldLayout.LANE) {
         int facing = 1 << ((dir + 2) & 3);            // the lane's side that faces this street
         return (PieceOpenness.sides(laneType(nx, nz)) & facing) != 0;
      }
      return false;
   }

   // ------------------------------------------------------------------- highway lanes

   private RoadType laneType(final int x, final int z) {
      final HighwayNetwork.Snapshot around = network.snapshot(x - 3, z - 3, x + 3, z + 3);
      if (!around.at(x, z)) {
         return null;
      }
      RoadType base = HighwayNetwork.pieceOrFallback(around, x, z);
      int[] bend = HighwayNetwork.bendInfo(around, x, z);
      return HighwayPieces.resolve(new HighwayPieces.Surroundings() {
         @Override
         public RoadType baseAt(int bx, int bz) {
            HighwayNetwork.Snapshot s = network.snapshot(bx - 3, bz - 3, bx + 3, bz + 3);
            return s.at(bx, bz) ? HighwayNetwork.pieceOrFallback(s, bx, bz) : null;
         }

         @Override
         public boolean streetJoins(int bx, int bz, int dir) {
            return streetJoinsAt(bx, bz, DX[dir], DZ[dir]);
         }
      }, x, z, base, bend);
   }

   /**
    * Does a street JOIN the lane at (x, z) from the neighbour at (dx, dz), rather than run alongside it? A street whose
    * own neighbours on both sides along the lane are roads too is running alongside, which is no junction.
    */
   private boolean streetJoinsAt(int x, int z, int dx, int dz) {
      if (!isRoadStreet(x + dx, z + dz)) {
         return false;
      }
      boolean before = isRoadCell(x + dx + dz, z + dz + dx);
      boolean after = isRoadCell(x + dx - dz, z + dz - dx);
      return !(before && after);
   }
}
