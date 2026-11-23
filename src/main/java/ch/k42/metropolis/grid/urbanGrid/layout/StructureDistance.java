package ch.k42.metropolis.grid.urbanGrid.layout;

/**
 * How far a block column is from the nearest chunk the plan builds on (a street, a block or a highway lane), as a pure
 * function of the plan. The ocean beach uses it to end its ramp at the real street instead of at a fixed length.
 *
 * It is a true Euclidean distance to the chunk rectangles, so it changes by at most one block per block of movement:
 * a ramp whose length follows it can never develop a step. Nothing here needs Bukkit.
 */
public final class StructureDistance {
   private StructureDistance() {
   }

   public static boolean built(WorldLayout layout, int chunkX, int chunkZ) {
      int k = layout.kindAt(chunkX, chunkZ);
      return k == WorldLayout.STREET || k == WorldLayout.BLOCK || k == WorldLayout.LANE;
   }

   /** Distance in blocks to the nearest built chunk, or maxBlocks when none lies within maxBlocks. */
   public static double blocksToStructure(WorldLayout layout, int blockX, int blockZ, double maxBlocks) {
      int cx = Math.floorDiv(blockX, 16);
      int cz = Math.floorDiv(blockZ, 16);
      int reach = (int) Math.ceil(maxBlocks / 16.0) + 1;
      double best = maxBlocks;
      for (int dz = -reach; dz <= reach; dz++) {
         for (int dx = -reach; dx <= reach; dx++) {
            int x = cx + dx;
            int z = cz + dz;
            if (!built(layout, x, z)) {
               continue;
            }
            // the block column spans [blockX, blockX + 1); measure from its centre to the chunk's rectangle
            double px = blockX + 0.5;
            double pz = blockZ + 0.5;
            double ex = Math.max(Math.max(x * 16.0 - px, 0.0), px - (x * 16.0 + 16.0));
            double ez = Math.max(Math.max(z * 16.0 - pz, 0.0), pz - (z * 16.0 + 16.0));
            double d = Math.sqrt(ex * ex + ez * ez);
            if (d < best) {
               best = d;
            }
         }
      }
      return best;
   }
}
