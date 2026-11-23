package ch.k42.metropolis.grid.urbanGrid.layout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The exact carve, used when the quick peel of a domain leaves chunks over. It cuts the free chunks of a domain by
 * depth-first search: a street goes only where both of its sides are free, and every piece it leaves must still be able
 * to hold a block. A piece's solution depends only on its own shape (and place), so it is solved once and remembered
 * (memo); the number of candidate cuts tried per piece grows with each retry. Pure Java, no state shared between runs.
 *
 * Some pieces have no exact tiling at all (a minBlock-wide square with a corner missing is too small to cut and is not
 * a rectangle). From the later attempts on, such a small piece is given its largest block and the rest of it is handed
 * back as unusable ("closed"): the caller makes terrain of it, like every other chunk no block can ever hold.
 */
final class LayoutSearch {

   /** The carve of a domain: blocks (world chunks), street and closed cells (domain-local, [x][z]). */
   static final class Result {
      final List<WorldLayout.Rect> blocks;
      final boolean[][] street;
      final boolean[][] closed;

      Result(List<WorldLayout.Rect> blocks, boolean[][] street, boolean[][] closed) {
         this.blocks = blocks;
         this.street = street;
         this.closed = closed;
      }
   }

   /** Whether a perimeter cell can be reserved as a road in the original
    * world plan, before any tentative subdivision commits. It may be a
    * committed road, a cut row from the free region, or explicitly dry
    * unassigned land. Water and protected natural ownership are never free.
    */
   interface RoadCapacity {
      boolean at(int x, int z);
      /** Already-owned roads are reused, never reclassified as streets. */
      default boolean existingRoadAt(int x, int z) { return false; }
   }

   static volatile String lastFail = "";

   /**
    * @param free    the free chunks of the domain, [x][z], already without the chunks no block can hold (not modified)
    * @param originX world chunk of free[0][..]; originZ likewise
    * @return the carve, or null if no attempt found one
    */
   static Result solve(boolean[][] free, int originX, int originZ, int minBlock, int maxBlock, long seed, int attempts) {
      return solve(free, originX, originZ, minBlock, maxBlock, seed, attempts, null, null);
   }

   /** Frame reservation is part of accepting the search's candidate, not an
    * after-the-fact terrain or road repair. If a candidate cannot reserve its
    * real four-sided road perimeter, search tries another deterministic cut.
    * Neither a failed search nor a rejected candidate mutates the domain.
    */
   static Result solve(boolean[][] free, int originX, int originZ, int minBlock, int maxBlock,
                       long seed, int attempts, java.util.function.UnaryOperator<Result> accept) {
      return solve(free, originX, originZ, minBlock, maxBlock, seed, attempts, accept, null);
   }

   static Result solve(boolean[][] free, int originX, int originZ, int minBlock, int maxBlock,
                       long seed, int attempts, java.util.function.UnaryOperator<Result> accept,
                       RoadCapacity capacity) {
      return solve(free, originX, originZ, minBlock, maxBlock, seed, attempts, accept, capacity, 0);
   }

   // R30ea's two guided search orders are alternatives of this same exact solver,
   // not separate planners. The unguided public overload retains mode zero.
   static Result solve(boolean[][] free, int originX, int originZ, int minBlock, int maxBlock,
                       long seed, int attempts, java.util.function.UnaryOperator<Result> accept,
                       RoadCapacity capacity, int guideMode) {
      // A rectangular component has an exact dimension-arithmetic solution;
      // use it before the general irregular-shape DFS.
      Result exactRectangle = RectangularTiling.solve(free, originX, originZ, minBlock, maxBlock);
      if (exactRectangle != null) {
         Result accepted = accept == null ? exactRectangle : accept.apply(exactRectangle);
         if (accepted != null) return accepted;
      }
      for (int attempt = 0; attempt < attempts; attempt++) {
         LayoutSearch s = new LayoutSearch(free, originX, originZ, minBlock, maxBlock, new Random(seed + attempt * 83492791L), capacity, guideMode);
         s.candLimit = 6 + attempt * 6;                // the first tries are quick; a hard piece gets a wider search each time
         s.allowAbsorb = attempt >= attempts / 2;       // exact tilings first; only then give a small stubborn piece up
         if (s.run()) {
            boolean[][] closed = new boolean[free.length][free[0].length];
            for (int[] e : s.extraClosed) {
               if (!s.street[e[0]][e[1]]) closed[e[0]][e[1]] = true;
            }
            Result candidate = new Result(s.blocks, s.street, closed);
            Result accepted = accept == null ? candidate : accept.apply(candidate);
            if (accepted != null) return accepted;
            lastFail = "candidate could not allocate all four street sides";
         }
         lastFail = s.failNote + "\n" + s.smallestFail;
      }
      return null;
   }

   private static final class Sol {
      static final Sol EMPTY = new Sol(), FAILED = new Sol();
      final List<int[]> streets = new ArrayList<int[]>();
      final List<WorldLayout.Rect> rects = new ArrayList<WorldLayout.Rect>();
      final List<int[]> closed = new ArrayList<int[]>();
      final List<Sol> children = new ArrayList<Sol>();

      static Sol block(WorldLayout.Rect r) {
         Sol s = new Sol();
         s.rects.add(r);
         return s;
      }

      void apply(LayoutSearch c) {
         for (int[] st : streets) {
            c.street[st[0]][st[1]] = true;
         }
         c.blocks.addAll(rects);
         c.extraClosed.addAll(closed);
         for (Sol ch : children) {
            ch.apply(c);
         }
      }
   }

   private final boolean[][] free;
   private final RoadCapacity capacity;
   private final int guideMode;
   private final int originX, originZ, minBlock, maxBlock;
   private final long salt;
   private final boolean[][] street;
   private final List<WorldLayout.Rect> blocks = new ArrayList<WorldLayout.Rect>();
   private final List<int[]> extraClosed = new ArrayList<int[]>();
   private final Map<Long, Sol> memo = new HashMap<Long, Sol>();
   private int budget = 20000;
   private int candLimit = 6;
   private boolean allowAbsorb = false;
   private int smallestFailArea = -1;
   private String smallestFail = "", failNote = "";

   private LayoutSearch(boolean[][] free, int originX, int originZ, int minBlock, int maxBlock,
                        Random r, RoadCapacity capacity, int guideMode) {
      this.capacity = capacity;
      this.guideMode = guideMode;
      this.free = free;
      this.originX = originX;
      this.originZ = originZ;
      this.minBlock = minBlock;
      this.maxBlock = maxBlock;
      this.salt = r.nextLong();
      this.street = new boolean[free.length][free[0].length];
   }

   private boolean run() {
      int W = free.length, H = free[0].length;
      boolean[][] seen = new boolean[W][H];
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (!free[x][z] || seen[x][z]) {
               continue;
            }
            boolean[][] comp = new boolean[W][H];
            flood(free, comp, seen, x, z, W, H);
            if (!coverable(comp)) {
               failNote = "free area at " + (originX + x) + "," + (originZ + z) + " has a part narrower than minBlock";
               return false;
            }
            Sol s = solvePiece(comp, 0, 0);
            if (s == null) {
               failNote = (budget < 0 ? "search budget exhausted" : "no cut found") + " in the piece at " + (originX + x) + "," + (originZ + z);
               return false;
            }
            s.apply(this);
         }
      }
      return true;
   }

   private static void flood(boolean[][] src, boolean[][] dst, boolean[][] seen, int sx, int sz, int W, int H) {
      int[] st = new int[W * H];
      int sp = 0;
      st[sp++] = sx * H + sz;
      seen[sx][sz] = true;
      while (sp > 0) {
         int c = st[--sp];
         int x = c / H, z = c % H;
         dst[x][z] = true;
         if (x > 0 && src[x - 1][z] && !seen[x - 1][z]) {
            seen[x - 1][z] = true;
            st[sp++] = (x - 1) * H + z;
         }
         if (x < W - 1 && src[x + 1][z] && !seen[x + 1][z]) {
            seen[x + 1][z] = true;
            st[sp++] = (x + 1) * H + z;
         }
         if (z > 0 && src[x][z - 1] && !seen[x][z - 1]) {
            seen[x][z - 1] = true;
            st[sp++] = x * H + z - 1;
         }
         if (z < H - 1 && src[x][z + 1] && !seen[x][z + 1]) {
            seen[x][z + 1] = true;
            st[sp++] = x * H + z + 1;
         }
      }
   }

   /** Crops a mask to its bounding box (plus a one-cell border of false) and solves that. */
   private Sol solvePiece(boolean[][] m, int offX, int offZ) {
      int W = m.length, H = m[0].length;
      int x0 = W, x1 = -1, z0 = H, z1 = -1;
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (m[x][z]) {
               if (x < x0) x0 = x;
               if (x > x1) x1 = x;
               if (z < z0) z0 = z;
               if (z > z1) z1 = z;
            }
         }
      }
      if (x1 < 0) {
         return Sol.EMPTY;
      }
      boolean[][] c = new boolean[x1 - x0 + 3][z1 - z0 + 3];
      for (int x = x0; x <= x1; x++) {
         System.arraycopy(m[x], z0, c[x - x0 + 1], 1, z1 - z0 + 1);
      }
      return solve(c, offX + x0 - 1, offZ + z0 - 1);
   }

   /** Carve one connected set of free chunks (a mask over the domain's grid, offset by offX/offZ). */
   private Sol solve(boolean[][] m, int offX, int offZ) {
      int W = m.length, H = m[0].length;
      int x0 = W, x1 = -1, z0 = H, z1 = -1, area = 0;
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (m[x][z]) {
               area++;
               if (x < x0) x0 = x;
               if (x > x1) x1 = x;
               if (z < z0) z0 = z;
               if (z > z1) z1 = z;
            }
         }
      }
      if (area == 0) {
         return Sol.EMPTY;
      }
      int w = x1 - x0 + 1, h = z1 - z0 + 1;
      boolean rect = area == w * h;
      // A piece is solved once: its answer depends only on its own shape and place, so it is remembered, and the
      // random stream it draws from is its own, not whatever the search happened to have drawn before it.
      long key = 0x9E3779B97F4A7C15L * (offX + x0 + 1) ^ 0xC2B2AE3D27D4EB4FL * (offZ + z0 + 1) ^ 0x165667B19E3779F9L * w ^ 0x27D4EB2F165667C5L * h;
      for (int x = x0; x <= x1; x++) {
         long row = 0;
         for (int z = z0; z <= z1; z++) {
            row = row * 31 + (m[x][z] ? 1 : 0) + 1;
         }
         key = (key ^ row) * 0x100000001B3L + x;
      }
      Sol known = memo.get(key);
      if (known != null) {
         return known == Sol.FAILED ? null : known;
      }
      if (--budget < 0) {
         return null;
      }
      final Random r = new Random(key ^ salt);
      if (rect && w >= minBlock && w <= maxBlock && h >= minBlock && h <= maxBlock) {
         Sol one = framedBlock(originX + offX + x0, originZ + offZ + z0,
                 originX + offX + x1, originZ + offZ + z1);
         if (one != null) {
            memo.put(key, one);
            return one;
         }
         // This shape fits a rectangle, but NOT a road frame. It is not a
         // subdivision leaf. Try an alternative cut (if room exists), rather
         // than publishing it and expecting a coastal painter to fix it.
      }
      if (w < minBlock || h < minBlock) {
         memo.put(key, Sol.FAILED);
         return null;
      }
      // A rectangular subcomponent need not consume thousands of recursive
      // trial cuts. Partition it exactly with one-cell roads, then require
      // every leaf to claim its external road frame before accepting it.
      // This is an actual construction, not a post-plan hole filler.
      if (rect) {
         Sol tiled = tileRectangularPiece(x0,z0,w,h,offX,offZ);
         if (tiled != null) {
            memo.put(key,tiled);
            return tiled;
         }
      }
      // A piece too small to take a street either way (it takes 2 * minBlock + 1 to cut) can only be one block, and
      // if it is not a rectangle no exact tiling exists: its largest block is kept, the rest of it is of no use.
      if (w <= 2 * minBlock && h <= 2 * minBlock && !rect) {
         Sol one = absorb(m, x0, z0, x1, z1, offX, offZ);
         if (one != null) {
            memo.put(key, one);
            return one;
         }
      }

      // candidate cuts: {axis (0 = a vertical street at column c, 1 = horizontal at row c), position}
      List<int[]> cands = new ArrayList<int[]>();
      // R30ea: prioritize a cut whose new road can connect to an existing
      // street at a free component's edge WITHOUT running parallel to an
      // existing street immediately to either side of that proposed cut.
      // The two modes inspect the immediate endpoint and (mode 2) one
      // further row, using the original candidate list and seeded shuffle.
      if (guideMode > 0 && capacity != null) {
         for (int c = x0 + minBlock; c <= x1 - minBlock; c++) {
            int worldX = originX + offX + c;
            int lo = originZ + offZ + z0 - 1;
            int hi = originZ + offZ + z1 + 1;
            boolean aRoad = false, bRoad = false;
            for (int distance = 0; distance <= (guideMode > 1 ? 1 : 0); distance++) {
               int nearZ = lo + distance, farZ = hi - distance;
               aRoad |= capacity.existingRoadAt(worldX, nearZ)
                       && !capacity.existingRoadAt(worldX - 1, nearZ)
                       && !capacity.existingRoadAt(worldX + 1, nearZ);
               bRoad |= capacity.existingRoadAt(worldX, farZ)
                       && !capacity.existingRoadAt(worldX - 1, farZ)
                       && !capacity.existingRoadAt(worldX + 1, farZ);
            }
            if (aRoad || bRoad) cands.add(new int[]{0, c});
         }
         for (int c = z0 + minBlock; c <= z1 - minBlock; c++) {
            int worldZ = originZ + offZ + c;
            int lo = originX + offX + x0 - 1;
            int hi = originX + offX + x1 + 1;
            boolean aRoad = false, bRoad = false;
            for (int distance = 0; distance <= (guideMode > 1 ? 1 : 0); distance++) {
               int nearX = lo + distance, farX = hi - distance;
               aRoad |= capacity.existingRoadAt(nearX, worldZ)
                       && !capacity.existingRoadAt(nearX, worldZ - 1)
                       && !capacity.existingRoadAt(nearX, worldZ + 1);
               bRoad |= capacity.existingRoadAt(farX, worldZ)
                       && !capacity.existingRoadAt(farX, worldZ - 1)
                       && !capacity.existingRoadAt(farX, worldZ + 1);
            }
            if (aRoad || bRoad) cands.add(new int[]{1, c});
         }
         Collections.shuffle(cands, r);
      }
      if (rect) {
         boolean cutX = w > maxBlock && (h <= maxBlock || w >= h);
         int n = cutX ? w : h;
         int mid = (int) Math.round(n / 2.0 + r.nextGaussian() * n / 6.0);
         mid = Math.max(minBlock, Math.min(n - 1 - minBlock, mid));
         for (int dlt = 0; dlt <= n; dlt++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
               int c = mid + sgn * dlt;
               if (c >= minBlock && c <= n - 1 - minBlock && !(dlt == 0 && sgn == 1)) {
                  cands.add(new int[]{cutX ? 0 : 1, (cutX ? x0 : z0) + c});
               }
            }
         }
      } else {
         // An irregular piece: first, cuts at the piece's own steps (where the outline steps in or out, a street just
         // beside the step separates a notch or ledge from the body in one move); then strips a block's width in from
         // an edge of the bounding box; then every other position, shuffled.
         boolean[] stepX = new boolean[W + 1];
         boolean[] stepZ = new boolean[H + 1];
         for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
               if (m[x][z] != m[x + 1][z]) {
                  stepX[x] = true;
               }
               if (m[x][z] != m[x][z + 1]) {
                  stepZ[z] = true;
               }
            }
         }
         List<int[]> steps = new ArrayList<int[]>();
         for (int e = x0; e <= x1; e++) {
            if (stepX[e]) {
               for (int dlt = -1; dlt <= 2; dlt++) {
                  int c = e + dlt;
                  if (c >= x0 + minBlock && c <= x1 - minBlock) {
                     steps.add(new int[]{0, c});
                  }
               }
            }
         }
         for (int e = z0; e <= z1; e++) {
            if (stepZ[e]) {
               for (int dlt = -1; dlt <= 2; dlt++) {
                  int c = e + dlt;
                  if (c >= z0 + minBlock && c <= z1 - minBlock) {
                     steps.add(new int[]{1, c});
                  }
               }
            }
         }
         Collections.shuffle(steps, r);
         cands.addAll(steps);
         List<int[]> peel = new ArrayList<int[]>();
         List<int[]> rest = new ArrayList<int[]>();
         for (int c = x0 + minBlock; c <= x1 - minBlock; c++) {
            boolean near = c - x0 <= maxBlock || x1 - c <= maxBlock;
            (near ? peel : rest).add(new int[]{0, c});
         }
         for (int c = z0 + minBlock; c <= z1 - minBlock; c++) {
            boolean near = c - z0 <= maxBlock || z1 - c <= maxBlock;
            (near ? peel : rest).add(new int[]{1, c});
         }
         Collections.shuffle(peel, r);
         Collections.shuffle(rest, r);
         cands.addAll(peel);
         cands.addAll(rest);
      }
      int tried = 0;
      for (int[] cand : cands) {
         if (tried++ > candLimit || budget < 0) {
            break;
         }
         boolean vertical = cand[0] == 0;
         int c = cand[1];
         // a street needs a free chunk on both sides of every chunk of it
         boolean ok = true;
         int cells = 0;
         if (vertical) {
            for (int z = z0; z <= z1 && ok; z++) {
               if (m[c][z]) {
                  cells++;
                  if (!(m[c - 1][z] && m[c + 1][z])) {
                     ok = false;
                  }
               }
            }
         } else {
            for (int x = x0; x <= x1 && ok; x++) {
               if (m[x][c]) {
                  cells++;
                  if (!(m[x][c - 1] && m[x][c + 1])) {
                     ok = false;
                  }
               }
            }
         }
         if (!ok || cells == 0) {
            continue;
         }
         boolean[][] cut = new boolean[W][H];
         for (int x = 0; x < W; x++) {
            System.arraycopy(m[x], 0, cut[x], 0, H);
         }
         if (vertical) {
            for (int z = z0; z <= z1; z++) {
               cut[c][z] = false;
            }
         } else {
            for (int x = x0; x <= x1; x++) {
               cut[x][c] = false;
            }
         }
         List<boolean[][]> parts = new ArrayList<boolean[][]>();
         boolean[][] seen = new boolean[W][H];
         boolean feasible = true;
         for (int x = 0; x < W && feasible; x++) {
            for (int z = 0; z < H && feasible; z++) {
               if (!cut[x][z] || seen[x][z]) {
                  continue;
               }
               boolean[][] part = new boolean[W][H];
               flood(cut, part, seen, x, z, W, H);
               if (!coverable(part)) {
                  feasible = false;
               } else {
                  parts.add(part);
               }
            }
         }
         if (!feasible) {
            continue;
         }
         Sol res = new Sol();
         if (vertical) {
            for (int z = z0; z <= z1; z++) {
               if (m[c][z]) {
                  res.streets.add(new int[]{offX + c, offZ + z});
               }
            }
         } else {
            for (int x = x0; x <= x1; x++) {
               if (m[x][c]) {
                  res.streets.add(new int[]{offX + x, offZ + c});
               }
            }
         }
         boolean all = true;
         for (boolean[][] p : parts) {
            Sol sub = solvePiece(p, offX, offZ);
            if (sub == null || !compatible(res,sub)) {
               all = false;
               break;
            }
            res.children.add(sub);
         }
         if (all) {
            memo.put(key, res);
            return res;
         }
      }
      // every cut failed: a small stubborn piece is given up, in the later attempts
      if (allowAbsorb && area <= 4 * maxBlock * maxBlock && budget >= 0) {
         Sol one = absorb(m, x0, z0, x1, z1, offX, offZ);
         if (one != null) {
            memo.put(key, one);
            return one;
         }
      }
      if (budget >= 0 && (smallestFailArea < 0 || area < smallestFailArea)) {
         smallestFailArea = area;
         StringBuilder sb = new StringBuilder("smallest exhausted piece: area=" + area + " bbox x[" + (originX + offX + x0) + ".." + (originX + offX + x1) + "] z[" + (originZ + offZ + z0) + ".." + (originZ + offZ + z1) + "]\n");
         for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
               sb.append(m[x][z] ? '.' : '#');
            }
            sb.append('\n');
         }
         smallestFail = sb.toString();
      }
      memo.put(key, Sol.FAILED);
      return null;
   }

   /** The exact tiler also applies to rectangular SUBPIECES of an irregular
    * coastal domain. Every leaf reserves its 4 road faces while tiling; an
    * unframeable tile returns null and normal cut search keeps looking.
    */
   /** The reference exact rectangular subpiece tiler. Split roads are part of
    * the candidate, and the enclosing candidate is subsequently checked by
    * CandidateRoadFrames; individual sub-rectangles do not pre-emptively
    * donate land or reject otherwise valid partitions here. */
   private Sol tileRectangularPiece(int x0, int z0, int w, int h, int offX, int offZ) {
      int[] widths = RectangularTiling.partition(w, minBlock, maxBlock);
      int[] depths = RectangularTiling.partition(h, minBlock, maxBlock);
      if (widths == null || depths == null) return null;
      Sol combined = new Sol();
      int cx = x0;
      for (int i = 0; i < widths.length; i++) {
         int cz = z0;
         for (int j = 0; j < depths.length; j++) {
            combined.rects.add(new WorldLayout.Rect(
                  originX + offX + cx, originZ + offZ + cz,
                  originX + offX + cx + widths[i] - 1,
                  originZ + offZ + cz + depths[j] - 1));
            cz += depths[j];
            if (j + 1 < depths.length) {
               for (int x = x0; x < x0 + w; x++)
                  combined.streets.add(new int[]{offX + x, offZ + cz});
               cz++;
            }
         }
         cx += widths[i];
         if (i + 1 < widths.length) {
            for (int z = z0; z < z0 + h; z++)
               combined.streets.add(new int[]{offX + cx, offZ + z});
            cx++;
         }
      }
      return combined;
   }

   /** A cut is legal only if its siblings can own their road frames at
    * the same time. This is a candidate-search veto, not a post-carve repair.
    * A common street may serve both lots; a street can never be a lot cell.
    */
   private boolean compatible(Sol accepted,Sol candidate) {
      List<WorldLayout.Rect> ab=new ArrayList<>(),bb=new ArrayList<>();
      java.util.Set<Long> ar=new java.util.HashSet<>(),br=new java.util.HashSet<>();
      flatten(accepted,ab,ar);
      flatten(candidate,bb,br);
      for(WorldLayout.Rect b:bb) for(long road:ar) {
         int x=originX+(int)(road>>32),z=originZ+(int)road;
         if(x>=b.minX&&x<=b.maxX&&z>=b.minZ&&z<=b.maxZ)return false;
      }
      for(WorldLayout.Rect b:ab) for(long road:br) {
         int x=originX+(int)(road>>32),z=originZ+(int)road;
         if(x>=b.minX&&x<=b.maxX&&z>=b.minZ&&z<=b.maxZ)return false;
      }
      for(WorldLayout.Rect a:ab)for(WorldLayout.Rect b:bb) {
         if(a.minX<=b.maxX&&a.maxX>=b.minX&&a.minZ<=b.maxZ&&a.maxZ>=b.minZ)return false;
      }
      return true;
   }
   private static void flatten(Sol s,List<WorldLayout.Rect> blocks,java.util.Set<Long> roads) {
      blocks.addAll(s.rects);
      for(int[] street:s.streets) roads.add(((long)street[0]<<32) ^ (street[1]&0xffffffffL));
      for(Sol ch:s.children)flatten(ch,blocks,roads);
   }

   /** Validate the rectangle's actual external road budget as a leaf of
    * recursive subdivision. If its boundary is genuinely blocked, donate a
    * strip of its OWN free land as a road before accepting the rectangle.
    * Minimum size is checked AFTER donation. Nothing mutates global plan.
    */
   /** As in R30ea, the rectangle is recorded without an early per-leaf
    * road-capacity veto. Road-frame validation takes place when the complete
    * candidate is accepted by WorldLayout/CandidateRoadFrames. */
   private Sol framedBlock(int x0, int z0, int x1, int z1) {
      return Sol.block(new WorldLayout.Rect(x0, z0, x1, z1));
   }

   /** The largest block that fits in the piece; whatever of the piece it leaves is handed back as unusable. Null if none fits. */
   private Sol absorb(boolean[][] m, int x0, int z0, int x1, int z1, int offX, int offZ) {
      int bx = -1, bz = -1, bw = 0, bh = 0;
      for (int ax = x0; ax <= x1; ax++) {
         for (int az = z0; az <= z1; az++) {
            for (int cw = minBlock; cw <= maxBlock && ax + cw - 1 <= x1; cw++) {
               for (int ch = minBlock; ch <= maxBlock && az + ch - 1 <= z1; ch++) {
                  boolean all = true;
                  for (int ix = ax; ix < ax + cw && all; ix++) {
                     for (int iz = az; iz < az + ch; iz++) {
                        if (!m[ix][iz]) {
                           all = false;
                           break;
                        }
                     }
                  }
                  if (!all) {
                     break;      // a taller one at this width cannot fit either
                  }
                  if (cw * ch > bw * bh) {
                     bx = ax;
                     bz = az;
                     bw = cw;
                     bh = ch;
                  }
               }
            }
         }
      }
      if (bw == 0) {
         return null;
      }
      Sol one = framedBlock(originX + offX + bx, originZ + offZ + bz,
              originX + offX + bx + bw - 1, originZ + offZ + bz + bh - 1);
      if (one == null) return null;
      java.util.Set<Long> keptStreet = new java.util.HashSet<>();
      for (int[] e : one.streets) keptStreet.add(((long)e[0]<<32) ^ (e[1]&0xffffffffL));
      WorldLayout.Rect kept = one.rects.get(0);
      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            int lx = offX + x, lz = offZ + z;
            int wx=originX+lx, wz=originZ+lz;
            if (m[x][z] && !(wx>=kept.minX&&wx<=kept.maxX&&wz>=kept.minZ&&wz<=kept.maxZ)
                && !keptStreet.contains(((long)lx<<32) ^ (lz&0xffffffffL))) {
               one.closed.add(new int[]{lx, lz});
            }
         }
      }
      return one;
   }

   /** Is every chunk of the part inside some minBlock x minBlock square that lies wholly inside it? */
   /**
    * Check a component in its own bounding box, never the entire parent domain.
    * Flood fills represent small fragments in masks as large as several hundred chunks
    * across. Building the square-coverage prefix/difference grids over all the empty
    * surrounding cells used to be the dominant backtracking allocation cost.
    * This is an exact spatial crop: the predicate and every candidate remain unchanged.
    */
   private boolean coverable(boolean[][] p) {
      int minX = p.length, maxX = -1, minZ = p[0].length, maxZ = -1;
      for (int x = 0; x < p.length; x++) {
         for (int z = 0; z < p[x].length; z++) {
            if (p[x][z]) {
               if (x < minX) minX = x;
               if (x > maxX) maxX = x;
               if (z < minZ) minZ = z;
               if (z > maxZ) maxZ = z;
            }
         }
      }
      if (maxX < 0) return true;
      int width = maxX - minX + 1, height = maxZ - minZ + 1;
      if (width < minBlock || height < minBlock) return false;
      boolean[][] crop = new boolean[width][height];
      for (int x = 0; x < width; x++) {
         System.arraycopy(p[minX + x], minZ, crop[x], 0, height);
      }
      boolean[][] cov = cover(crop, minBlock);
      for (int x = 0; x < width; x++) {
         for (int z = 0; z < height; z++) {
            if (crop[x][z] && !cov[x][z]) return false;
         }
      }
      return true;
   }

   /** Which chunks of the grid lie in some s x s square of true chunks. */
   static boolean[][] cover(boolean[][] f, int s) {
      int w = f.length, h = f[0].length;
      int[][] pre = new int[w + 1][h + 1];
      for (int x = 0; x < w; x++) {
         for (int z = 0; z < h; z++) {
            pre[x + 1][z + 1] = pre[x][z + 1] + pre[x + 1][z] - pre[x][z] + (f[x][z] ? 1 : 0);
         }
      }
      int[][] diff = new int[w + 2][h + 2];
      for (int x = 0; x + s <= w; x++) {
         for (int z = 0; z + s <= h; z++) {
            if (pre[x + s][z + s] - pre[x][z + s] - pre[x + s][z] + pre[x][z] == s * s) {
               diff[x][z]++;
               diff[x + s][z]--;
               diff[x][z + s]--;
               diff[x + s][z + s]++;
            }
         }
      }
      boolean[][] out = new boolean[w][h];
      int[][] cov = new int[w + 1][h + 1];
      for (int x = 0; x < w; x++) {
         for (int z = 0; z < h; z++) {
            cov[x + 1][z + 1] = diff[x][z] + cov[x][z + 1] + cov[x + 1][z] - cov[x][z];
            out[x][z] = cov[x + 1][z + 1] > 0;
         }
      }
      return out;
   }
}
