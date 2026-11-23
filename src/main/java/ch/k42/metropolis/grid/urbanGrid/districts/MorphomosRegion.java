package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One connected morphomos area (forest or farm) of the world plan: every block of that context that touches another
 * through a dissolved street, plus the dissolved street chunks themselves (see PlanRegions, which builds them). This is
 * the "full usable area" a generator should work on as a whole -- it is just the map, nothing is generated here.
 *
 * A region belongs to the plan, not to a tile: it is the same object whichever tile asks for it, so generators can
 * compare regions by identity across tile edges.
 *
 * Cells are chunk coordinates. Only world-chunk coordinates are stored, so the region can be queried without knowing
 * any tile root.
 */
public class MorphomosRegion {
   private final int id;
   private final ContextType context;
   private final List<int[]> rects = new ArrayList<int[]>();          // {minX, minZ, maxX, maxZ}, inclusive
   private final List<int[]> bridgeCells = new ArrayList<int[]>();
   private final Set<Long> bridgeSet = new HashSet<Long>();
   private int minX = Integer.MAX_VALUE;
   private int minZ = Integer.MAX_VALUE;
   private int maxX = Integer.MIN_VALUE;
   private int maxZ = Integer.MIN_VALUE;
   private int cellCount = 0;

   public MorphomosRegion(int id, ContextType context) {
      this.id = id;
      this.context = context;
   }

   /** A block of the plan (inclusive chunk bounds). */
   public void addRect(int x0, int z0, int x1, int z1) {
      this.rects.add(new int[]{x0, z0, x1, z1});
      this.include(x0, z0);
      this.include(x1, z1);
      this.cellCount += (x1 - x0 + 1) * (z1 - z0 + 1);
   }

   /** A street chunk dissolved to join blocks of this region. */
   public void addBridgeCell(int chunkX, int chunkZ) {
      if (this.bridgeSet.add(cell(chunkX, chunkZ))) {
         this.bridgeCells.add(new int[]{chunkX, chunkZ});
         this.include(chunkX, chunkZ);
         this.cellCount++;
      }
   }

   private static long cell(int x, int z) {
      return ((long) x << 32) ^ (z & 0xffffffffL);
   }

   private void include(int x, int z) {
      this.minX = Math.min(this.minX, x);
      this.minZ = Math.min(this.minZ, z);
      this.maxX = Math.max(this.maxX, x);
      this.maxZ = Math.max(this.maxZ, z);
   }

   public int getId() { return this.id; }
   public ContextType getContext() { return this.context; }
   /** The blocks as {minX, minZ, maxX, maxZ} chunk rectangles. */
   public List<int[]> getRects() { return Collections.unmodifiableList(this.rects); }
   /** Dissolved street cells as {chunkX, chunkZ}. */
   public List<int[]> getBridgeCells() { return Collections.unmodifiableList(this.bridgeCells); }
   public int getCellCount() { return this.cellCount; }
   public int getMinX() { return this.minX; }
   public int getMinZ() { return this.minZ; }
   public int getMaxX() { return this.maxX; }
   public int getMaxZ() { return this.maxZ; }

   /** True when chunk (x,z) belongs to this region (a block cell or a dissolved street cell). */
   public boolean contains(int x, int z) {
      if (x < this.minX || x > this.maxX || z < this.minZ || z > this.maxZ) {
         return false;
      }
      for (int[] r : this.rects) {
         if (x >= r[0] && x <= r[2] && z >= r[1] && z <= r[3]) {
            return true;
         }
      }
      return this.bridgeSet.contains(cell(x, z));
   }
}
