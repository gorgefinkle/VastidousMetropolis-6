package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.LodCompletionSync;
import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import org.bukkit.Bukkit;
import org.bukkit.World;

/** Recovered R30ea highway-to-river wall placement. */
public final class HighwayRiverBankWalls {
   private static final int NORTH = 1, EAST = 2, SOUTH = 4, WEST = 8;
   private HighwayRiverBankWalls() {}

   private static boolean hasHighway(UrbanGrid grid, WorldLayout layout, int x, int z) {
      Parcel parcel = grid.peekParcel(x, z);
      if (parcel != null) return parcel instanceof HighwayParcel;
      return layout != null && layout.kindAt(x, z) == 1;
   }
   static int facingMask(UrbanGrid grid, int chunkX, int chunkZ) {
      WorldLayout layout = grid.cityLayout();
      int mask = 0;
      if (hasHighway(grid, layout, chunkX, chunkZ - 1)) mask |= NORTH;
      if (hasHighway(grid, layout, chunkX + 1, chunkZ)) mask |= EAST;
      if (hasHighway(grid, layout, chunkX, chunkZ + 1)) mask |= SOUTH;
      if (hasHighway(grid, layout, chunkX - 1, chunkZ)) mask |= WEST;
      return mask;
   }
   static int removeCrossingFaces(int mask, boolean zBound, boolean boundaryX,
                                  boolean boundaryWest, boolean crossingZ, boolean crossingNorth) {
      if (zBound && crossingZ) mask &= ~(crossingNorth ? NORTH : SOUTH);
      if (!zBound && boundaryX) mask &= ~(boundaryWest ? WEST : EAST);
      return mask;
   }
   public static void schedule(RiverParcel parcel, MetropolisGenerator generator) {
      UrbanGrid grid = parcel.grid;
      if (grid == null || generator == null) return;
      World world = generator.getWorld();
      if (world == null) return;
      int x = parcel.getChunkX(), z = parcel.getChunkZ();
      int faces = facingMask(grid, x, z);
      if (faces == 0) return;
      RiverProvider river = parcel.getCarrier() != null ? parcel.getCarrier() : grid.getRiverProviderAt(x, z);
      if (river == null) return;
      faces = removeCrossingFaces(faces, river.isZBound(), parcel.isHighwayBoundaryX(),
               parcel.isHighwayBoundaryWest(), parcel.isHighwayCrossingZ(), parcel.isHighwayCrossingNorth());
      if (faces == 0) return;
      final int finalFaces = faces;
      Bukkit.getScheduler().runTask(generator.getPlugin(), () -> {
         if (grid.peekParcel(x,z) != parcel) return;
         if ((finalFaces & NORTH) != 0) RiverParcel.buildHighwayCrossingWallZ(grid, world, x, z, true, (x << 4) + 8, 3, false);
         if ((finalFaces & SOUTH) != 0) RiverParcel.buildHighwayCrossingWallZ(grid, world, x, z, false, (x << 4) + 8, 3, false);
         if ((finalFaces & WEST) != 0) RiverParcel.buildHighwayCrossingWall(grid, world, x, z, true, (z << 4) + 8, 3, false);
         if ((finalFaces & EAST) != 0) RiverParcel.buildHighwayCrossingWall(grid, world, x, z, false, (z << 4) + 8, 3, false);
         LodCompletionSync.changedLoadedAround(world, x, z, 2);
      });
   }
   public static boolean capContinuation(boolean immediate, UrbanGrid grid, boolean xAxis, int x, int z) {
      if (immediate) return true;
      if (grid == null) return false;
      int mod = Math.floorMod(z, 16);
      if (mod != 0 && mod != 15) return false;
      int cx = Math.floorDiv(xAxis ? z : x, 16);
      int cz = Math.floorDiv(xAxis ? x : z, 16);
      if (!(grid.peekParcel(cx, cz) instanceof RiverParcel)) return false;
      WorldLayout layout = grid.cityLayout();
      if (xAxis) return hasHighway(grid, layout, cx + (mod == 0 ? -1 : 1), cz);
      return hasHighway(grid, layout, cx, cz + (mod == 0 ? -1 : 1));
   }
}
