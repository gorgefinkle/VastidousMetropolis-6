package ch.k42.metropolis.grid.urbanGrid.layout;

/**
 * Where a water body's bank ramp has to end, as a pure function of the plan: no tile, no parcel grid, no order.
 *
 * Replaces UrbanGrid.computeBankEdges(), which read the final parcel grid of one tile. Here the same question
 * ("where does the land the water owns stop, on this column") is asked of the plan through {@link Terrain}, and the
 * slope limit is applied with a fixed window, so a column's answer is the same whichever tile (or window) asks.
 *
 * Coordinates: chunks. A water body runs along one axis (u) and the bank ramps across the other (v). With
 * alongX = true, u is chunk X and v is chunk Z (a river running east-west, or the north/south shore of the ocean);
 * with alongX = false it is the other way round. Edges are returned as BLOCK coordinates of the boundary on the v axis,
 * like UrbanGrid.getBankEdgeBlockZ()/X().
 *
 * Nothing in this class needs Bukkit.
 */
public final class BankSlope {
   private BankSlope() {
   }

   /** What the plan says about a chunk. */
   public interface Terrain {
      /** Water (river or ocean) itself. */
      boolean water(int x, int z);

      /** Dry land the water body owns: ZONE ground beside water, painted by the water body's own provider. */
      boolean shore(int x, int z);

      /**
       * Land or water that belongs to a different water body (the ocean beside a river's mouth). A bank that runs into it has no
       * street to meet, so its column has no edge (NaN) and that body's own painter decides the ground there.
       */
      default boolean other(int x, int z) {
         return false;
      }

      /** A chunk across-coordinate that is water in column u (e.g. the river's centre line), or Integer.MIN_VALUE if none. */
      int waterAt(boolean alongX, int u);
   }

   /** Furthest a bank may reach from the water, in chunks. Past this the column is treated as open (NaN). */
   public static final int MAX_SCAN = 64;

   private static boolean isWater(Terrain t, boolean alongX, int u, int v) {
      return alongX ? t.water(u, v) : t.water(v, u);
   }

   private static boolean isOther(Terrain t, boolean alongX, int u, int v) {
      return alongX ? t.other(u, v) : t.other(v, u);
   }

   private static boolean isShore(Terrain t, boolean alongX, int u, int v) {
      return alongX ? t.shore(u, v) : t.shore(v, u);
   }

   /**
    * The block coordinate where the bank on this side of column u must end: the boundary between the last chunk of
    * water-owned land and the first street, block or lane. NaN when the column has no water, or nothing but open shore
    * lies out to MAX_SCAN chunks (no structure to meet).
    *
    * @param positive true for the side with larger v (south for alongX, east otherwise)
    */
   public static double rawEdge(Terrain t, boolean alongX, int u, boolean positive) {
      int v = t.waterAt(alongX, u);
      if (v == Integer.MIN_VALUE || !isWater(t, alongX, u, v)) {
         return Double.NaN;
      }
      int step = positive ? 1 : -1;
      int guard = 0;
      while (isWater(t, alongX, u, v) && guard++ < 4 * MAX_SCAN) {
         v += step;
      }
      if (guard >= 4 * MAX_SCAN) {
         return Double.NaN;
      }
      int reach = 0;
      while (isShore(t, alongX, u, v)) {
         if (++reach > MAX_SCAN) {
            return Double.NaN;
         }
         v += step;
      }
      if (isOther(t, alongX, u, v)) {
         return Double.NaN;
      }
      // v is the first chunk that is neither water nor shore. The ramp ends at its near boundary.
      return positive ? v * 16.0 : (v + 1) * 16.0;
   }

   /** Window radius (chunks) that makes the slope limit exact for any edges within MAX_SCAN chunks of each other. */
   public static int radiusFor(double maxSlopeBlocksPerChunk) {
      double s = Math.max(maxSlopeBlocksPerChunk, 0.01);
      return (int) Math.min(512, Math.ceil(MAX_SCAN * 16.0 / s));
   }

   /**
    * Slope-limited edge for columns u0..u1: the smallest curve that is on the water side of every raw edge within the
    * window and never changes by more than maxSlope blocks between neighbouring columns (the same closing computeBankEdges
    * does with two sweeps, written as a cone so each column depends only on raw edges within `radius` of it).
    * "Water side" means larger v for the negative bank (max), smaller for the positive bank (min), so the ramp never
    * reaches past a real street or block. NaN where no raw edge is within reach.
    */
   public static double[] smoothed(Terrain t, boolean alongX, int u0, int u1, boolean positive, double maxSlope, int radius) {
      int n = u1 - u0 + 1;
      int m = n + 2 * radius;
      double[] raw = new double[m];
      for (int i = 0; i < m; i++) {
         raw[i] = rawEdge(t, alongX, u0 - radius + i, positive);
      }
      double[] out = new double[n];
      for (int i = 0; i < n; i++) {
         double best = Double.NaN;
         for (int d = -radius; d <= radius; d++) {
            double r = raw[i + radius + d];
            if (Double.isNaN(r)) {
               continue;
            }
            double cone = positive ? r + maxSlope * Math.abs(d) : r - maxSlope * Math.abs(d);
            // negative bank: edge grows toward the water, so keep the largest cone; positive: the smallest
            // (a positive-side raw edge is a v coordinate, smaller = nearer the water)
            if (Double.isNaN(best) || (positive ? cone < best : cone > best)) {
               best = cone;
            }
         }
         out[i] = best;
      }
      return out;
   }
}
