package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProvider;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;

import java.util.List;
import java.util.Set;

/**
 * Everything a District needs to know about the ground it fills and the dice it rolls. A tile is one host (UrbanGrid); a
 * single city block filled on its own, whole, is another (BlockHost): the same code, the same decisions, whichever tile asks.
 */
public interface DistrictHost {
   Parcel getParcel(int chunkX, int chunkZ);

   void fillParcels(int chunkX, int chunkZ, Parcel parcel);

   void recutAndRefill(IDistrict scrapped, Cartesian2D base, Cartesian2D size);

   Set<ClipboardParcel> getNeighbours(Cartesian2D center, int radius);

   List<Clipboard> filterRiverFacing(List<Clipboard> clips, int chunkX, int chunkZ, Direction direction);

   ClipboardProvider getClipboardProvider();

   ContextProvider getContextProvider();

   GridRandom getRandom();
}
