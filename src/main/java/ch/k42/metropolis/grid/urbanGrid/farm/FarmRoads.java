package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demand-driven farm access roads.
 *
 * The farm grid still supplies shared, four-block-wide access spines, but a
 * spine is painted only on the intervals between real farm-site access gates
 * and a real paved street. Empty grid lines and unused continuations stay
 * grassland. Multiple sites on one boundary share the same route interval.
 *
 * The short driveway inside a farm plot is planned by FarmLayout; this class
 * paints only its shared boundary spine and the matching opening in the paved
 * street. All decisions are deterministic and chunk-order independent.
 */
final class FarmRoads {
   static final int WIDTH = 4;
   static final int CUT_DEPTH = 8;
   private static final int MAX_CONNECT_CHUNKS = 66;
   private static final int CACHE_LIMIT = 8192;
   private static final ConcurrentHashMap<RouteKey, Boolean> ROUTE_CACHE =
           new ConcurrentHashMap<RouteKey, Boolean>();

   private FarmRoads() {
   }

   private static final class RouteKey {
      final Object region;
      final long seed;
      final boolean vertical;
      final int line;
      final int along;

      RouteKey(FarmContext ctx, boolean vertical, int line, int along) {
         this.region = ctx.region;
         this.seed = ctx.seed;
         this.vertical = vertical;
         this.line = line;
         this.along = along;
      }

      @Override
      public boolean equals(Object other) {
         if (!(other instanceof RouteKey)) return false;
         RouteKey k = (RouteKey) other;
         return this.region == k.region && this.seed == k.seed
                 && this.vertical == k.vertical && this.line == k.line
                 && this.along == k.along;
      }

      @Override
      public int hashCode() {
         int h = System.identityHashCode(this.region);
         h = 31 * h + (int)(this.seed ^ (this.seed >>> 32));
         h = 31 * h + (this.vertical ? 1 : 0);
         h = 31 * h + this.line;
         return 31 * h + this.along;
      }
   }

   /** One worn-dirt block: coarse dirt, plain dirt and gravel mixed. */
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

   /** Paint only route intervals requested by actual farm sites. */
   static void paintStrips(FarmContext ctx, FarmPainter p) {
      int cx = p.chunk.getX();
      int cz = p.chunk.getZ();
      int y = p.groundY;

      if (ctx.stripChunkValid(true, cx, cz)
              && activeLine(ctx, true, Math.floorDiv(cx, FarmLayout.CELL_CHUNKS), cz)) {
         for (int lx = 0; lx < WIDTH; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               stripBlock(p, p.minX + lx, y, p.minZ + lz, lx);
            }
         }
      }
      if (ctx.stripChunkValid(false, cx, cz)
              && activeLine(ctx, false, Math.floorDiv(cz, FarmLayout.CELL_CHUNKS), cx)) {
         for (int lz = 0; lz < WIDTH; lz++) {
            for (int lx = 0; lx < 16; lx++) {
               stripBlock(p, p.minX + lx, y, p.minZ + lz, lz);
            }
         }
      }
   }

   /** The verge may reclaim the two outer columns; the wheel-track centre stays continuous. */
   private static void stripBlock(FarmPainter p, int x, int y, int z, int across) {
      boolean edge = across == 0 || across == WIDTH - 1;
      if (edge && FarmRandom.unit(p.seed, x, z, 602) < 0.30) {
         return;
      }
      surface(p, x, y, z);
      if (edge && FarmRandom.unit(p.seed, x, z, 605) < 0.10) {
         p.set(x, y + 1, z, FarmPainter.TALL_GRASS, 1);
      }
   }

   /**
    * Checks whether this chunk belongs to a minimal access interval. The
    * interval begins at a recorded site gate and ends at the nearest paved
    * street in the same continuous farm boundary run.
    */
   private static boolean activeLine(FarmContext ctx, boolean vertical, int line, int along) {
      RouteKey key = new RouteKey(ctx, vertical, line, along);
      Boolean known = ROUTE_CACHE.get(key);
      if (known != null) return known.booleanValue();

      boolean active = buildRouteContains(ctx, vertical, line, along);
      ROUTE_CACHE.put(key, Boolean.valueOf(active));
      if (ROUTE_CACHE.size() > CACHE_LIMIT) ROUTE_CACHE.clear();
      return active;
   }

   private static boolean buildRouteContains(FarmContext ctx, boolean vertical, int line, int queryAlong) {
      int lineChunk = line * FarmLayout.CELL_CHUNKS;
      int currentX = vertical ? lineChunk : queryAlong;
      int currentZ = vertical ? queryAlong : lineChunk;
      if (!ctx.stripChunkValid(vertical, currentX, currentZ)) return false;

      Set<Integer> farmAlong = new HashSet<Integer>();
      Set<Integer> streetEnds = new HashSet<Integer>();
      farmAlong.add(queryAlong);

      for (int dir : new int[]{-1, 1}) {
         for (int step = 1; step <= MAX_CONNECT_CHUNKS; step++) {
            int along = queryAlong + dir * step;
            int cx = vertical ? lineChunk : along;
            int cz = vertical ? along : lineChunk;
            if (ctx.stripChunkValid(vertical, cx, cz)) {
               farmAlong.add(along);
               continue;
            }
            if (ctx.isRoadChunk(cx, cz)) {
               streetEnds.add(along);
            }
            break;
         }
      }
      if (streetEnds.isEmpty()) return false;

      // Each 256-block farm cell touching this spine contributes its own
      // site gates. Looking up the plans is safe: site planning uses only
      // FarmContext.connected(), never this demand-aware paint query.
      Set<Integer> visitedSegments = new HashSet<Integer>();
      Set<Integer> accessPoints = new HashSet<Integer>();
      for (int along : farmAlong) {
         int segment = Math.floorDiv(along, FarmLayout.CELL_CHUNKS);
         if (!visitedSegments.add(segment)) continue;
         if (vertical) {
            FarmLayout.get(ctx, line, segment).collectAccessPoints(true, line, accessPoints);
            FarmLayout.get(ctx, line - 1, segment).collectAccessPoints(true, line, accessPoints);
         } else {
            FarmLayout.get(ctx, segment, line).collectAccessPoints(false, line, accessPoints);
            FarmLayout.get(ctx, segment, line - 1).collectAccessPoints(false, line, accessPoints);
         }
      }

      for (int site : accessPoints) {
         if (!farmAlong.contains(site)) continue;
         int nearestStreet = Integer.MIN_VALUE;
         int nearestDistance = Integer.MAX_VALUE;
         for (int street : streetEnds) {
            int distance = Math.abs(site - street);
            if (distance < nearestDistance
                    || (distance == nearestDistance && street < nearestStreet)) {
               nearestDistance = distance;
               nearestStreet = street;
            }
         }
         if (nearestStreet == Integer.MIN_VALUE || nearestDistance > MAX_CONNECT_CHUNKS) continue;
         int lo = Math.min(site, nearestStreet);
         int hi = Math.max(site, nearestStreet);
         if (queryAlong >= lo && queryAlong <= hi) return true;
      }
      return false;
   }

   static void clearCache() {
      ROUTE_CACHE.clear();
   }

   /**
    * The paved street opens only where an active farm access spine reaches it.
    * Return the cutout rectangle in the shared building-cutout convention.
    */
   static int[] opening(FarmContext ctx, int fcx, int fcz, int rcx, int rcz) {
      if (!ctx.isRoadChunk(rcx, rcz)) return null;
      int dx = rcx - fcx;
      int dz = rcz - fcz;
      if (dz != 0 && dx == 0) {
         if (!ctx.stripChunkValid(true, fcx, fcz)
                 || !activeLine(ctx, true, Math.floorDiv(fcx, FarmLayout.CELL_CHUNKS), fcz)) {
            return null;
         }
         int x = fcx << 4;
         return dz < 0 ? new int[]{x, (rcz << 4) + 15, WIDTH, -CUT_DEPTH}
                       : new int[]{x, rcz << 4, WIDTH, CUT_DEPTH};
      }
      if (dx != 0 && dz == 0) {
         if (!ctx.stripChunkValid(false, fcx, fcz)
                 || !activeLine(ctx, false, Math.floorDiv(fcz, FarmLayout.CELL_CHUNKS), fcx)) {
            return null;
         }
         int z = fcz << 4;
         return dx < 0 ? new int[]{(rcx << 4) + 15, z, -CUT_DEPTH, WIDTH}
                       : new int[]{rcx << 4, z, CUT_DEPTH, WIDTH};
      }
      return null;
   }
}
