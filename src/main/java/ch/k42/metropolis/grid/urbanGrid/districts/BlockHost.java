package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProvider;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One whole city block as its own little ground, so its buildings are decided from the block alone: not from where a tile
 * edge happens to cut it, not from what a tile has already built, and not from the order tiles are generated in.
 *
 * The dice are seeded from the world seed and the block's rectangle. The ground is the block's own chunks (nothing outside
 * it is ever consulted). "Neighbours" for clone avoidance are the block's own buildings plus those of the lower blocks near it
 * (see BlockPlans), which are handed in.
 * Whatever tile fills the block gets the same buildings in the same places.
 */
public final class BlockHost implements DistrictHost {
   private final UrbanGrid tile;
   private final int minX, minZ, width, height;
   private final Parcel[][] cells;
   private final GridRandom random;
   private final List<ClipboardParcel> external;      // buildings of other blocks to keep clear of; never written to

   public BlockHost(UrbanGrid tile, long worldSeed, int minX, int minZ, int maxX, int maxZ, List<ClipboardParcel> external) {
      this.tile = tile;
      this.external = external;
      this.minX = minX;
      this.minZ = minZ;
      this.width = maxX - minX + 1;
      this.height = maxZ - minZ + 1;
      this.cells = new Parcel[this.width][this.height];
      long seed = GridRandom.generateLocalSeed(GridRandom.generateLocalSeed(worldSeed ^ 0x626C6F636B21L, minX, minZ), maxX, maxZ);
      this.random = new GridRandom(seed);
   }

   private boolean inside(int x, int z) {
      return x >= this.minX && z >= this.minZ && x < this.minX + this.width && z < this.minZ + this.height;
   }

   @Override
   public Parcel getParcel(int chunkX, int chunkZ) {
      return this.inside(chunkX, chunkZ) ? this.cells[chunkX - this.minX][chunkZ - this.minZ] : null;
   }

   @Override
   public void fillParcels(int chunkX, int chunkZ, Parcel parcel) {
      for (int x = chunkX; x < chunkX + parcel.getChunkSizeX(); x++) {
         for (int z = chunkZ; z < chunkZ + parcel.getChunkSizeZ(); z++) {
            if (this.inside(x, z)) {
               this.cells[x - this.minX][z - this.minZ] = parcel;
            }
         }
      }
   }

   @Override
   public void recutAndRefill(IDistrict scrapped, Cartesian2D base, Cartesian2D size) {
      // extraCutMode re-cuts a district with fresh road splits. A block's buildings are never re-cut here (UrbanGrid keeps the
      // tile path for that mode), so there is nothing to do.
   }

   @Override
   public Set<ClipboardParcel> getNeighbours(Cartesian2D center, int radius) {
      Set<ClipboardParcel> found = new HashSet<ClipboardParcel>();
      for (int x = center.X - radius; x <= center.X + radius; x++) {
         for (int z = center.Y - radius; z <= center.Y + radius; z++) {
            Parcel p = this.getParcel(x, z);
            if (p instanceof ClipboardParcel) {
               found.add((ClipboardParcel) p);
            }
         }
      }
      // The buildings of lower blocks near enough to count: any whose footprint touches the square the radius draws around the centre.
      for (ClipboardParcel p : this.external) {
         if (p.getChunkX() <= center.X + radius && p.getChunkX() + p.getChunkSizeX() - 1 >= center.X - radius
                 && p.getChunkZ() <= center.Y + radius && p.getChunkZ() + p.getChunkSizeZ() - 1 >= center.Y - radius) {
            found.add(p);
         }
      }
      return found;
   }

   @Override
   public List<Clipboard> filterRiverFacing(List<Clipboard> clips, int chunkX, int chunkZ, Direction direction) {
      return this.tile.filterRiverFacing(clips, chunkX, chunkZ, direction);
   }

   @Override
   public ClipboardProvider getClipboardProvider() {
      return this.tile.getClipboardProvider();
   }

   @Override
   public ContextProvider getContextProvider() {
      return this.tile.getContextProvider();
   }

   @Override
   public GridRandom getRandom() {
      return this.random;
   }

   /** Every distinct parcel the fill produced, in a fixed order (top-left to bottom-right). */
   /**
    * The block's parcels, each once, in the order their first chunk is met. Collected by IDENTITY: ClipboardParcel.equals() calls
    * two parcels equal when they hold the same schematic group (that is what the clone checks want), so a set by equality would
    * silently drop the second of two buildings that happen to use the same schematic (or the second of two identical fillers) in
    * one block, and its chunks would be left with no parcel at all: bare, unpopulated ground.
    */
   public List<Parcel> parcels() {
      Set<Parcel> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Parcel, Boolean>());
      List<Parcel> out = new ArrayList<Parcel>();
      for (int z = 0; z < this.height; z++) {
         for (int x = 0; x < this.width; x++) {
            Parcel p = this.cells[x][z];
            if (p != null && seen.add(p)) {
               out.add(p);
            }
         }
      }
      return out;
   }
}
