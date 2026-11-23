package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * Dirt road surface and the pieces built from it: the 4-wide strips along the
 * farm grid lines, and the road-end openings that the paved road chunks cut.
 */
final class FarmRoads {
   /** Width of a dirt road strip, and so the width of the cutout it asks the paved road for. */
   static final int WIDTH = 4;
   /** How far into the paved road chunk the cutout reaches; the same depth buildings use for theirs. */
   static final int CUT_DEPTH = 8;

   private FarmRoads() {
   }

   /** One worn-dirt block: coarse dirt, plain dirt and gravel mixed, with the plants above it cleared. */
   static void surface(FarmPainter p, int x, int y, int z) {
      double r = FarmRandom.unit(p.seed, x, z, 601);
      if (r < 0.58) {
         p.set(x, y, z, FarmPainter.DIRT, 1);
      } else if (r < 0.84) {
         p.set(x, y, z, FarmPainter.DIRT, 0);
      } else {
         p.set(x, y, z, FarmPainter.GRAVEL, 0);
      }
      p.set(x, y + 1, z, FarmPainter.AIR, 0);
      p.set(x, y + 2, z, FarmPainter.AIR, 0);
   }

   /** Paints the dirt road strips of this chunk, if its grid lines carry a road that leads somewhere. */
   static void paintStrips(FarmContext ctx, FarmPainter p) {
      int cx = p.chunk.getX();
      int cz = p.chunk.getZ();
      int y = p.groundY;
      if (ctx.connected(true, cx, cz)) {
         for (int lx = 0; lx < WIDTH; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               stripBlock(p, p.minX + lx, y, p.minZ + lz, lx);
            }
         }
      }
      if (ctx.connected(false, cx, cz)) {
         for (int lz = 0; lz < WIDTH; lz++) {
            for (int lx = 0; lx < 16; lx++) {
               stripBlock(p, p.minX + lx, y, p.minZ + lz, lz);
            }
         }
      }
   }

   /** The two outer columns are ragged, so the road edge is not a ruler line. */
   private static void stripBlock(FarmPainter p, int x, int y, int z, int across) {
      boolean edge = across == 0 || across == WIDTH - 1;
      if (edge && FarmRandom.unit(p.seed, x, z, 602) < 0.30) {
         return;                                           // the verge has crept in
      }
      surface(p, x, y, z);
      if (edge && FarmRandom.unit(p.seed, x, z, 605) < 0.10) {
         p.set(x, y + 1, z, FarmPainter.TALL_GRASS, 1);    // weeds along the rutted edge
      }
   }

   /**
    * Where a paved road chunk must open up for the dirt road that meets it,
    * as {x, z, sizeX, sizeZ} in the cutout convention (a negative size runs
    * backwards from the start block), or null when no dirt road ends there.
    *
    * (fcx, fcz) is the farm chunk, (rcx, rcz) the paved road chunk next to it.
    * The strip of the farm chunk runs straight into the road chunk, so the
    * opening is 4 wide, lines up with the strip, and reaches CUT_DEPTH blocks
    * into the road from the shared edge.
    */
   static int[] opening(FarmContext ctx, int fcx, int fcz, int rcx, int rcz) {
      if (!ctx.isRoadChunk(rcx, rcz)) {
         return null;
      }
      int dx = rcx - fcx;
      int dz = rcz - fcz;
      if (dz != 0 && dx == 0) {                         // road north or south: a vertical strip meets it
         if (!ctx.stripChunkValid(true, fcx, fcz)) {
            return null;
         }
         int x = fcx << 4;
         return dz < 0 ? new int[]{x, (rcz << 4) + 15, WIDTH, -CUT_DEPTH}
                       : new int[]{x, rcz << 4, WIDTH, CUT_DEPTH};
      }
      if (dx != 0 && dz == 0) {                         // road east or west: a horizontal strip meets it
         if (!ctx.stripChunkValid(false, fcx, fcz)) {
            return null;
         }
         int z = fcz << 4;
         return dx < 0 ? new int[]{(rcx << 4) + 15, z, -CUT_DEPTH, WIDTH}
                       : new int[]{rcx << 4, z, CUT_DEPTH, WIDTH};
      }
      return null;
   }
}
