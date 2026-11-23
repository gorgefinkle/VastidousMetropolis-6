package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.WaterEdgeProvider;

/**
 * Adapts OceanProvider's "distance inside the shoreline" function into the
 * same per-column north/south water-edge measurement RiverProvider gives
 * RiverReclaim directly -- so RiverReclaim.compute() can carve the ocean's
 * own shoreline exactly the way it carves the river's, splitting each chunk
 * column into a "north" and a "south" side, instead of a river's spine.
 *
 * WHY A SCAN, NOT A BISECTION FROM THE CENTER: RiverProvider can answer
 * "where's the water edge" in closed form because its geometry is a 1D line
 * (spineZ(x) +/- width/2). The ocean has no such closed form along a fixed-X
 * line, but distanceInsideShoreBlock(x, z) is exact and cheap for any single
 * point, so this searches for the crossing directly, using the very function
 * OceanParcel paints from -- no separate geometric approximation to keep in
 * sync with it.
 *
 * The first version bisected outward from the ocean's center row, which is
 * only right if the water is ONE unbroken span along every column that
 * contains the center row -- true of a round body, false of what the ocean
 * now is: stretched, bent, with bays, inlets and islands. There a column
 * can miss the center row entirely while still holding water further
 * north or south (a bent sea), and can hold several separate spans of water
 * with land between (an island, a peninsula reaching across an inlet). A
 * bisection would quietly land on whichever crossing it happened to converge
 * to -- for an island, its shore rather than the real outer shore -- and
 * RiverReclaim would then reclaim land over open water.
 *
 * So each side is now measured as the OUTERMOST water in the column: walk in
 * from the far end of the ocean's guaranteed envelope (land, by
 * construction) toward the other end, and stop at the first sample that is
 * water; then bisect between that sample and the land sample just before it
 * for the exact crossing. Everything between the two outermost edges is
 * treated as water (an island inside it is simply not claimable, which is
 * the safe direction to be wrong in). The step size grows with distance from
 * the water, so the long approach across open land costs a handful of
 * samples, and shrinks to MIN_STEP near the coast so a narrow inlet is not
 * stepped over -- and anything a coarse step could still clip is smaller than
 * the reclaim berth kept clear of the water anyway.
 *
 * Sampled at every one of the chunk's 16 block columns PLUS the first
 * column of the next chunk, taking the worst case (min for north, max for
 * south) across them -- same conservative-across-the-whole-column reasoning
 * as RiverProvider.waterEdgeNorthBlock()/waterEdgeSouthBlock() themselves,
 * and for the same reason: the shoreline moves continuously within a chunk,
 * so its extreme point is rarely dead center.
 */
public class OceanWaterEdgeProvider implements WaterEdgeProvider {

   /** Smallest scan step, in blocks -- used whenever the sample is within reach of water. */
   private static final double MIN_STEP = 8.0;
   /** Largest scan step, in blocks -- far out over open land. */
   private static final double MAX_STEP = 64.0;
   /**
    * Fraction of the distance-to-shore estimate the scan is willing to skip
    * in one step. The estimate is only first-order and the field's slope can
    * exceed 1, so this stays well under 1 rather than stepping right up to
    * where the shore is claimed to be.
    */
   private static final double STEP_FRACTION = 0.15;
   /** Bisection halvings between the last land sample and the first water sample (at most MAX_STEP apart): well under a block. */
   private static final int BISECT_ITERATIONS = 20;

   private final OceanProvider ocean;

   public OceanWaterEdgeProvider(OceanProvider ocean) {
      this.ocean = ocean;
   }

   @Override
   public double waterEdgeNorthBlock(int worldChunkX) {
      double min = Double.MAX_VALUE;
      boolean any = false;
      int baseX = worldChunkX << 4;
      for (int lx = 0; lx <= 16; lx++) {
         double edge = outermostEdgeAtBlockX(baseX + lx, true);
         if (!Double.isNaN(edge)) {
            min = Math.min(min, edge);
            any = true;
         }
      }
      return any ? min : Double.NaN;
   }

   @Override
   public double waterEdgeSouthBlock(int worldChunkX) {
      double max = -Double.MAX_VALUE;
      boolean any = false;
      int baseX = worldChunkX << 4;
      for (int lx = 0; lx <= 16; lx++) {
         double edge = outermostEdgeAtBlockX(baseX + lx, false);
         if (!Double.isNaN(edge)) {
            max = Math.max(max, edge);
            any = true;
         }
      }
      return any ? max : Double.NaN;
   }

   /**
    * The outermost water crossing at this exact block X: the smallest Z that
    * is water if north is true, the largest if false. NaN if there is no
    * water anywhere in this column -- the common case near the corners of
    * the square UrbanGrid carves (see UrbanGrid.reclaimOceanDistricts() for
    * how that is handled).
    */
   private double outermostEdgeAtBlockX(double blockX, boolean north) {
      double envelopeBlocks = this.ocean.getEnvelopeRadiusChunks() * 16.0;
      if (envelopeBlocks <= 0) {
         return Double.NaN;
      }
      double centerZ = this.ocean.getCenterBlockZ();
      double dir = north ? 1.0 : -1.0;               // scan direction along Z
      double start = north ? centerZ - envelopeBlocks : centerZ + envelopeBlocks; // guaranteed land
      double end = north ? centerZ + envelopeBlocks : centerZ - envelopeBlocks;

      double land = start;
      double z = start;
      while ((z - end) * dir <= 0) {
         double d = this.ocean.distanceInsideShoreBlock(blockX, z);
         if (d > 0) {
            return bisect(blockX, land, z);
         }
         land = z;
         z += dir * stepFor(d);
      }
      return Double.NaN;
   }

   /** Step for the next sample, given this one was land at signed distance d (<= 0) from the shore. */
   private static double stepFor(double d) {
      double step = -d * STEP_FRACTION;
      return Math.max(MIN_STEP, Math.min(MAX_STEP, step));
   }

   /** Exact crossing between a known land sample and a known water sample: returns the water-side Z. */
   private double bisect(double blockX, double landZ, double waterZ) {
      double land = landZ;
      double water = waterZ;
      for (int i = 0; i < BISECT_ITERATIONS; i++) {
         double mid = (land + water) / 2.0;
         if (this.ocean.distanceInsideShoreBlock(blockX, mid) > 0) {
            water = mid;
         } else {
            land = mid;
         }
      }
      return water;
   }
}
