package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.plugin.PluginConfig;

/**
 * What the layout planner needs to know about the world: the region mask and
 * the ground height of every chunk. A plot or building is only placed when
 * every chunk it touches (plus a margin) belongs to the region AND sits at
 * the same ground height, so nothing is ever cut off by a road, a building
 * or a river, and nothing straddles a height step. The region is the plan's,
 * so it runs on across tile edges and the mask has no tile edge in it.
 */
final class FarmContext {
   final UrbanGrid grid;
   final MorphomosRegion region;
   final long seed;

   FarmContext(UrbanGrid grid, MorphomosRegion region, long seed) {
      this.grid = grid;
      this.region = region;
      this.seed = seed;
   }

   /** Surface height (y of the top block) for a chunk. Same baseline rule as the forest. */
   int groundY(int chunkX, int chunkZ) {
      int base = this.grid.isRiverReclaimedChunk(chunkX, chunkZ)
              ? RiverConfig.getRiverReclaimBuildHeight()
              : PluginConfig.getBuildHeight();
      return base + PluginConfig.getFarmSurfaceOffset();
   }

   /**
    * Ground height shared by every chunk touched by the box grown by margin
    * blocks, or -1 when any of those chunks is outside the region or at a
    * different height.
    */
   int validHeight(int minX, int minZ, int maxX, int maxZ, int margin) {
      int cx0 = (minX - margin) >> 4;
      int cx1 = (maxX + margin) >> 4;
      int cz0 = (minZ - margin) >> 4;
      int cz1 = (maxZ + margin) >> 4;
      int h = Integer.MIN_VALUE;
      for (int cx = cx0; cx <= cx1; cx++) {
         for (int cz = cz0; cz <= cz1; cz++) {
            if (this.grid.getMorphomosRegionAt(cx, cz) != this.region) {
               return -1;
            }
            int g = this.groundY(cx, cz);
            if (h == Integer.MIN_VALUE) {
               h = g;
            } else if (h != g) {
               return -1;
            }
         }
      }
      return h;
   }

   // ---------------------------------------------------------------- dirt roads
   //
   // Dirt roads run along some of the lines of the farm grid (a cell is
   // CELL_CHUNKS x CELL_CHUNKS chunks). Each cell owns a 4-wide strip along its west edge
   // (local x 0..3) and one along its north edge (local z 0..3), so a strip
   // always sits inside a single chunk column / row. A strip chunk is only
   // painted when following the line from it, through chunks that are also
   // farm, reaches an ordinary paved road chunk. That keeps every dirt road
   // a real connection and avoids pointless dead ends.

   boolean inRegion(int cx, int cz) {
      return this.grid.getMorphomosRegionAt(cx, cz) == this.region;
   }

   /**
    * An ordinary paved street chunk, as the world plan has it (a street that did not dissolve into the countryside).
    * Asked of the plan, not of a neighbouring tile, so it is the same whichever tile is built first.
    */
   boolean isRoadChunk(int cx, int cz) {
      return !this.inRegion(cx, cz) && this.grid.isPlanStreet(cx, cz);
   }

   /** Whether grid line number idx (x = 64 * idx for vertical lines, z = 64 * idx for horizontal ones) carries a dirt road. */
   boolean lineExists(boolean vertical, int idx) {
      double roll = vertical ? FarmRandom.unit(this.seed, idx, 0, 801) : FarmRandom.unit(this.seed, 0, idx, 802);
      return roll * 100.0 < PluginConfig.getFarmRoadLinePercent();
   }

   /**
    * True when chunk (cx, cz) lies on a grid line that carries a road and may
    * hold a piece of it: it is farm, and so is the chunk on the line's outer
    * side (so a road never runs squeezed alongside a paved road).
    */
   boolean stripChunkValid(boolean vertical, int cx, int cz) {
      if (Math.floorMod(vertical ? cx : cz, FarmLayout.CELL_CHUNKS) != 0) {
         return false;
      }
      if (!this.inRegion(cx, cz)) {
         return false;
      }
      if (!this.lineExists(vertical, FarmRandom.floorDiv(vertical ? cx : cz, FarmLayout.CELL_CHUNKS))) {
         return false;
      }
      return vertical ? this.inRegion(cx - 1, cz) : this.inRegion(cx, cz - 1);
   }

   /** Follows the line both ways from a strip chunk; true if either end runs into a paved road chunk. */
   boolean connected(boolean vertical, int cx, int cz) {
      if (!this.stripChunkValid(vertical, cx, cz)) {
         return false;
      }
      for (int dir = -1; dir <= 1; dir += 2) {
         int x = cx;
         int z = cz;
         for (int step = 0; step < 66; step++) {
            if (vertical) {
               z += dir;
            } else {
               x += dir;
            }
            if (this.stripChunkValid(vertical, x, z)) {
               continue;
            }
            if (this.isRoadChunk(x, z)) {
               return true;
            }
            break;
         }
      }
      return false;
   }
}
