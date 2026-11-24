package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Purpose-built farm access spines.
 *
 * A grid line is only painted when a farm site on that line actually requests
 * access and the line reaches a real planned street. For each site, the route
 * is the interval from its access gate to the nearest paved-road endpoint.
 * This prevents empty parallel grid strips, dead ends, and the long unused
 * continuations that the former probabilistic road lattice produced.
 *
 * Farm sites remain responsible for their own short driveway from the building
 * to the shared spine. Every method is deterministic in world seed, region and
 * coordinates, and painting remains chunk-local.
 */
final class FarmRoads {
   static final int WIDTH = 4;
   static final int CUT_DEPTH = 8;

   private static final int MAX_CONNECT_CHUNKS = 66;
   // The cached plan is centred on the first queried chunk in a 16-chunk cell.
   // The extra margin covers the cell's far edge plus the full access search.
   private static final int PLAN_SCAN = MAX_CONNECT_CHUNKS + FarmLayout.CELL_CHUNKS + 16;
   private static final int CACHE_LIMIT = 2048;

   private static final ConcurrentHashMap<LineKey, LinePlan> LINE_CACHE =
           new ConcurrentHashMap<LineKey, LinePlan>();

   private FarmRoads() {
   }

   private static final class LineKey {
      final Object region;
      final long seed;
      final boolean vertical;
      final int line;
      final int segment;

      LineKey(FarmContext ctx, boolean vertical, int line, int segment) {
         this.region = ctx.region;
         this.seed = ctx.seed;
         this.vertical = vertical;
         this.line = line;
         this.segment = segment;
      }

      @Override
      public boolean equals(Object other) {
         if (!(other instanceof LineKey)) return false;
         LineKey k = (LineKey) other;
         return this.region == k.region && this.seed == k.seed
                 && this.vertical == k.vertical && this.line == k.line
                 && this.segment == k.segment;
      }

      @Override
      public int hashCode() {
         int h = System.identityHashCode(this.region);
         h = 31 * h + (int)(this.seed ^ (this.seed >>> 32));
         h = 31 * h + (this.vertical ? 1 : 0);
         h = 31 * h + this.line;
         return 31 * h + this.segment;
      }
   }

   private static final class LinePlan {
      final List<int[]> intervals;

      LinePlan(List<int[]> intervals) {
         this.intervals = intervals;
      }

      boolean contains(int along) {
         for (int[] interval : this.intervals) {
            if (along >= interval[0] && along <= interval[1]) return true;
         }
         return false;
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

   /** Paint only the portions of a farm-grid spine needed by actual farm sites. */
   static void paintStrips(FarmContext ctx, FarmPainter p) {
      int cx = p.chunk.getX();
      int cz = p.chunk.getZ();
      int y = p.groundY;

      if (ctx.stripChunkValid(true, cx, cz) && activeLine(ctx, true, Math.floorDiv(cx, FarmLayout.CELL_CHUNKS), cz)) {
         for (int lx = 0; lx < WIDTH; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               stripBlock(p, p.minX + lx, y, p.minZ + lz, lx);
            }
         }
      }
      if (ctx.stripChunkValid(false, cx, cz) && activeLine(ctx, false, Math.floorDiv(cz, FarmLayout.CELL_CHUNKS), cx)) {
         for (int lz = 0; lz < WIDTH; lz++) {
            for (int lx = 0; lx < 16; lx++) {
               stripBlock(p, p.minX + lx, y, p.minZ + lz, lz);
            }
         }
      }
   }

   /** The two outer columns are ragged; the wheel track remains continuous. */
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
    * Whether this chunk of a possible grid spine belongs to at least one
    * site-to-street route. Routes are calculated from actual site access gates,
    * not from a random line percentage.
    */
   private static boolean activeLine(FarmContext ctx, boolean vertical, int line, int along) {
      int segment = Math.floorDiv(along, FarmLayout.CELL_CHUNKS);
      LineKey key = new LineKey(ctx, vertical, line, segment);
      LinePlan plan = LINE_CACHE.get(key);
      if (plan == null) {
         int anchor = segment * FarmLayout.CELL_CHUNKS + FarmLayout.CELL_CHUNKS / 2;
         LinePlan made = buildLinePlan(ctx, vertical, line, anchor);
         LinePlan prior = LINE_CACHE.putIfAbsent(key, made);
         plan = prior == null ? made : prior;
         if (LINE_CACHE.size() > CACHE_LIMIT) LINE_CACHE.clear();
      }
      return plan.contains(along);
   }

   private static LinePlan buildLinePlan(FarmContext ctx, boolean vertical, int line, int anchor) {
      Set<Integer> farmChunks = new HashSet<Integer>();
      Set<Integer> streetEnds = new HashSet<Integer>();

      for (int dir = -1; dir <= 1; dir += 2) {
         for (int step = 0; step <= PLAN_SCAN; step++) {
            int along = anchor + dir * step;
            int cx = vertical ? line * FarmLayout.CELL_CHUNKS : along;
            int cz = vertical ? along : line * FarmLayout.CELL_CHUNKS;
            if (!ctx.stripChunkValid(vertical, cx, cz)) {
               if (step > 0 && ctx.isRoadChunk(cx, cz)) {
                  streetEnds.add(along);
               }
               break;
            }
            farmChunks.add(along);
         }
      }

      if (farmChunks.isEmpty() || streetEnds.isEmpty()) {
         return new LinePlan(new ArrayList<int[]>());
      }

      // Inspect the two cells touching the line once per 256-block segment.
      Set<Integer> visitedSegments = new HashSet<Integer>();
      Set<Integer> accessPoints = new HashSet<Integer>();
      for (int along : farmChunks) {
         int segment = Math.floorDiv(along, FarmLayout.CELL_CHUNKS);
         if (!visitedSegments.add(segment)) continue;
         if (vertical) {
            FarmLayout east = FarmLayout.get(ctx, line, segment);
            FarmLayout west = FarmLayout.get(ctx, line - 1, segment);
            east.collectAccessPoints(true, line, accessPoints);
            west.collectAccessPoints(true, line, accessPoints);
         } else {
            FarmLayout south = FarmLayout.get(ctx, segment, line);
            FarmLayout north = FarmLayout.get(ctx, segment, line - 1);
            south.collectAccessPoints(false, line, accessPoints);
            north.collectAccessPoints(false, line, accessPoints);
         }
      }

      List<int[]> intervals = new ArrayList<int[]>();
      for (int site : accessPoints) {
         if (!farmChunks.contains(site)) continue;
         int nearestStreet = Integer.MIN_VALUE;
         int nearestDistance = Integer.MAX_VALUE;
         for (int street : streetEnds) {
            int distance = Math.abs(site - street);
            if (distance < nearestDistance || (distance == nearestDistance && street < nearestStreet)) {
               nearestDistance = distance;
               nearestStreet = street;
            }
         }
         if (nearestStreet == Integer.MIN_VALUE || nearestDistance > MAX_CONNECT_CHUNKS) continue;

         int lo = Math.min(site, nearestStreet);
         int hi = Math.max(site, nearestStreet);
         // The paved chunk itself is not part of the farm strip; clipping is
         // implicit because paintStrips also requires stripChunkValid().
         intervals.add(new int[]{lo, hi});
      }
      return new LinePlan(intervals);
   }

   static void clearCache() {
      LINE_CACHE.clear();
   }

   /**
    * The paved-road opening is emitted only when the neighbouring farm chunk
    * is part of a real site-to-street access route.
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
