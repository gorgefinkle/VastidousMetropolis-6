package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The blocks and streets of the land OUTSIDE each city's highway ring: the residential remainder (when the ring is
 * smaller than the residential edge) and the farm band, out to the start of the forest. Worked out from the highways
 * alone, by slicing the space that is left once the highways are down: there is no fixed grid, so nothing in it
 * depends on a tile and a block that runs over a tile edge is the same block for both tiles. Pure Java, no Bukkit.
 *
 * <pre>
 *   territory   each city's box grown by its band width on every side. Overlapping territories (two cities close
 *               together) are one cluster and are carved together, so the gap between two rings is carved as one space.
 *   wrapper     the outer edge of the territory is a one-chunk street, the road that wraps the farm band where the
 *               forest starts. Where the territory ends against a highway there is no wrapper: the blocks touch it.
 *   free space  the territory minus the boxes, the highways and the wrapper.
 *   slicing     every connected piece of free space is cut by one-chunk streets, longer side first, at a gaussian cut.
 *               A street only goes where both sides of every chunk of it are free space (so it never runs along a
 *               highway or another street, only arrives at one head-on), and only where every piece it leaves can
 *               still hold a block. A piece that is one rectangle with both sides in [minBlock, maxBlock] is a block.
 *               Cuts that continue the edge of a highway are tried first, because a street one chunk off that edge
 *               leaves a notch no block can fill.
 *   retry       if a cluster has a piece that cannot be carved (a shape such as an offset staircase of two squares),
 *               its band widths are nudged by up to a few chunks, in a fixed pseudo-random order seeded from the
 *               cluster, until it carves. Same cluster, same answer, whoever asks first.
 * </pre>
 */
public final class OuterLayout {

   public static final int NONE = 0, LANE = 1, STREET = 2, BLOCK = 3, BOX = 4, UNCLAIMED = 5;

   public static final class Rect {
      public final int minX, minZ, maxX, maxZ;
      public Rect(int minX, int minZ, int maxX, int maxZ) { this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ; }
      public int width() { return maxX - minX + 1; }
      public int depth() { return maxZ - minZ + 1; }
      public boolean intersects(int x0, int z0, int x1, int z1) { return maxX >= x0 && minX <= x1 && maxZ >= z0 && minZ <= z1; }
      @Override public String toString() { return "[" + minX + ".." + maxX + "," + minZ + ".." + maxZ + "]"; }
   }

   /** A group of cities whose territories touch, and the finished carve of the land around them. */
   public static final class Cluster {
      public final List<HighwayNetwork.City> cities = new ArrayList<HighwayNetwork.City>();
      public int minX, minZ, maxX, maxZ;
      byte[][] kind;
      public final List<Rect> blocks = new ArrayList<Rect>();
      public boolean solved;
      public int attempts;
      public int kindAt(int x, int z) {
         return x < minX || x > maxX || z < minZ || z > maxZ ? NONE : kind[x - minX][z - minZ];
      }
   }

   private static final int MAX_JITTER = 3;
   private static final int MAX_ATTEMPTS = 1500;

   private final HighwayNetwork net;
   private final long seed;
   private final int spacing, minBlock, maxBlock;
   private final double bandFraction;
   private final Map<Long, Cluster> byCity = new HashMap<Long, Cluster>();
   public int clusters = 0, failedClusters = 0, retries = 0, largestCluster = 0;
   public final List<Cluster> clusterList = new ArrayList<Cluster>();
   public boolean[][] debugInfeasible; public int debugArea = Integer.MAX_VALUE, debugOx, debugOz;

   /**
    * @param spacing      the city grid spacing the network was built with
    * @param bandFraction the band width outside the ring as a fraction of the ring's half size (farm band plus any
    *                     residential left outside the ring, over the ring's own size)
    */
   public OuterLayout(HighwayNetwork net, long seed, int spacing, int minBlock, int maxBlock, double bandFraction) {
      if (maxBlock < 2 * minBlock) {
         throw new IllegalArgumentException("maxBlock must be at least 2 * minBlock");
      }
      this.net = net;
      this.seed = seed;
      this.spacing = spacing;
      this.minBlock = minBlock;
      this.maxBlock = maxBlock;
      this.bandFraction = bandFraction;
   }

   private static int fd(int a, int b) { return Math.floorDiv(a, b); }
   private static long key(int a, int b) { return ((long) a << 32) ^ (b & 0xffffffffL); }

   private int baseExtent(HighwayNetwork.City c) {
      return Math.max(0, (int) Math.round(c.h * bandFraction));
   }

   // ---------------------------------------------------------------- queries

   /** What this layout puts at chunk (x, z): NONE (outside every territory and no highway), LANE, BOX, STREET, BLOCK or UNCLAIMED. */
   public synchronized int kindAt(int x, int z) {
      int a0 = fd(x, spacing), b0 = fd(z, spacing);
      for (int a = a0 - 1; a <= a0 + 1; a++) {
         for (int b = b0 - 1; b <= b0 + 1; b++) {
            HighwayNetwork.City c = net.city(a, b);
            if (!c.present) continue;
            int e = baseExtent(c) + MAX_JITTER;
            if (x >= c.x0 - e && x <= c.x1 + e && z >= c.z0 - e && z <= c.z1 + e) {
               return clusterOf(c).kindAt(x, z);
            }
         }
      }
      return NONE;
   }

   /** The blocks of every cluster that touches the window. */
   public synchronized List<Rect> blocksIn(int minX, int minZ, int maxX, int maxZ) {
      List<Rect> out = new ArrayList<Rect>();
      java.util.IdentityHashMap<Cluster, Boolean> seen = new java.util.IdentityHashMap<Cluster, Boolean>();
      for (int a = fd(minX, spacing) - 1; a <= fd(maxX, spacing) + 1; a++) {
         for (int b = fd(minZ, spacing) - 1; b <= fd(maxZ, spacing) + 1; b++) {
            HighwayNetwork.City c = net.city(a, b);
            if (!c.present) continue;
            Cluster cl = clusterOf(c);
            if (seen.put(cl, Boolean.TRUE) != null) continue;
            for (Rect r : cl.blocks) if (r.intersects(minX, minZ, maxX, maxZ)) out.add(r);
         }
      }
      return out;
   }

   // --------------------------------------------------------------- clusters

   private Cluster clusterOf(HighwayNetwork.City start) {
      Cluster cl = byCity.get(key(start.a, start.b));
      if (cl != null) return cl;
      cl = new Cluster();
      List<HighwayNetwork.City> queue = new ArrayList<HighwayNetwork.City>();
      queue.add(start);
      for (int qi = 0; qi < queue.size(); qi++) {
         HighwayNetwork.City c = queue.get(qi);
         for (int da = -1; da <= 1; da++) {
            for (int db = -1; db <= 1; db++) {
               HighwayNetwork.City n = net.city(c.a + da, c.b + db);
               if (!n.present || queue.contains(n) || !reachTouches(c, n)) continue;
               queue.add(n);
            }
         }
      }
      // order the cities so the result never depends on which one was asked about
      java.util.Collections.sort(queue, new java.util.Comparator<HighwayNetwork.City>() {
         @Override public int compare(HighwayNetwork.City p, HighwayNetwork.City q) { return p.a != q.a ? Integer.compare(p.a, q.a) : Integer.compare(p.b, q.b); }
      });
      cl.cities.addAll(queue);
      clusters++;
      clusterList.add(cl);
      largestCluster = Math.max(largestCluster, queue.size());
      carve(cl);
      for (HighwayNetwork.City c : queue) byCity.put(key(c.a, c.b), cl);
      return cl;
   }

   private boolean reachTouches(HighwayNetwork.City p, HighwayNetwork.City q) {
      int ep = baseExtent(p) + MAX_JITTER + 1, eq = baseExtent(q) + MAX_JITTER + 1;
      return p.x0 - ep <= q.x1 + eq && p.x1 + ep >= q.x0 - eq && p.z0 - ep <= q.z1 + eq && p.z1 + ep >= q.z0 - eq;
   }

   private void carve(Cluster cl) {
      int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
      for (HighwayNetwork.City c : cl.cities) {
         int e = baseExtent(c) + MAX_JITTER + 1;
         minX = Math.min(minX, c.x0 - e); minZ = Math.min(minZ, c.z0 - e);
         maxX = Math.max(maxX, c.x1 + e); maxZ = Math.max(maxZ, c.z1 + e);
      }
      cl.minX = minX; cl.minZ = minZ; cl.maxX = maxX; cl.maxZ = maxZ;
      final int W = maxX - minX + 1, H = maxZ - minZ + 1;
      HighwayNetwork.Snapshot snap = net.snapshot(minX, minZ, maxX, maxZ);
      boolean[][] lane = new boolean[W][H];
      boolean[][] box = new boolean[W][H];
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            lane[x][z] = snap.at(minX + x, minZ + z);
            for (HighwayNetwork.City c : cl.cities) if (c.boxContains(minX + x, minZ + z)) { box[x][z] = true; break; }
         }
      }
      long clusterSeed = seed * 0x9E3779B97F4A7C15L + cl.cities.get(0).a * 73856093L + cl.cities.get(0).b * 19349663L;
      Random rnd = new Random(clusterSeed);
      int[][] ext = new int[cl.cities.size()][4];
      for (int i = 0; i < ext.length; i++) Arrays.fill(ext[i], baseExtent(cl.cities.get(i)));
      Map<String, Solved> memo = new HashMap<String, Solved>();
      for (int iter = 0; iter < MAX_ATTEMPTS; iter++) {
         cl.attempts = iter + 1;
         int[] failing = tryExtents(cl, ext, lane, box, W, H, memo);
         if (failing == null) {
            cl.solved = true;
            retries += iter;
            return;
         }
         // nudge only a city whose territory reaches the piece that would not carve, by one or two chunks on one side
         List<Integer> near = new ArrayList<Integer>();
         for (int ci = 0; ci < ext.length; ci++) {
            HighwayNetwork.City c = cl.cities.get(ci);
            int x0 = c.x0 - ext[ci][3] - 3 - cl.minX, x1 = c.x1 + ext[ci][1] + 3 - cl.minX;
            int z0 = c.z0 - ext[ci][0] - 3 - cl.minZ, z1 = c.z1 + ext[ci][2] + 3 - cl.minZ;
            if (x0 <= failing[2] && x1 >= failing[0] && z0 <= failing[3] && z1 >= failing[1]) near.add(ci);
         }
         if (near.isEmpty()) for (int ci = 0; ci < ext.length; ci++) near.add(ci);
         int ci = near.get(rnd.nextInt(near.size()));
         int side = rnd.nextInt(4);
         int base = baseExtent(cl.cities.get(ci));
         int d = (rnd.nextBoolean() ? 1 : -1) * (1 + rnd.nextInt(2));
         ext[ci][side] = Math.max(0, Math.min(base + MAX_JITTER, Math.max(base - MAX_JITTER, ext[ci][side] + d)));
      }
      failedClusters++;
      // leave the territory marked as unclaimed so the checker reports it
      boolean[][] inU = territory(cl, new int[cl.cities.size()][4], W, H, true);
      cl.kind = new byte[W][H];
      for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
         cl.kind[x][z] = (byte) (lane[x][z] ? LANE : box[x][z] ? BOX : inU[x][z] ? UNCLAIMED : NONE);
      }
   }

   /** The cells inside any city's territory; with useBase the base extents are used. */
   private boolean[][] territory(Cluster cl, int[][] ext, int W, int H, boolean useBase) {
      boolean[][] u = new boolean[W][H];
      for (int i = 0; i < cl.cities.size(); i++) {
         HighwayNetwork.City c = cl.cities.get(i);
         int eN, eE, eS, eW;
         if (useBase) { eN = eE = eS = eW = baseExtent(c); } else { eN = ext[i][0]; eE = ext[i][1]; eS = ext[i][2]; eW = ext[i][3]; }
         int x0 = c.x0 - eW - cl.minX, x1 = c.x1 + eE - cl.minX, z0 = c.z0 - eN - cl.minZ, z1 = c.z1 + eS - cl.minZ;
         for (int x = Math.max(0, x0); x <= Math.min(W - 1, x1); x++) for (int z = Math.max(0, z0); z <= Math.min(H - 1, z1); z++) u[x][z] = true;
      }
      return u;
   }

   /** One carved free-space piece: its blocks and the chunks of its streets, in cluster-grid coordinates. */
   private static final class Solved {
      final boolean ok; final List<Rect> blocks; final List<int[]> streets;
      Solved(boolean ok, List<Rect> blocks, List<int[]> streets) { this.ok = ok; this.blocks = blocks; this.streets = streets; }
   }

   /** Carve the cluster with these extents. Returns null on success, else {minX, minZ, maxX, maxZ} of a piece that would not carve. */
   private int[] tryExtents(Cluster cl, int[][] ext, boolean[][] lane, boolean[][] box, int W, int H, Map<String, Solved> memo) {
      boolean[][] u = territory(cl, ext, W, H, false);
      boolean[][] street = new boolean[W][H];
      boolean[][] free = new boolean[W][H];
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            if (!u[x][z] || lane[x][z] || box[x][z]) continue;
            // the wrapper: a territory edge that is open to the forest (not one that ends against a highway or a box)
            boolean edge = false;
            if (x == 0 || !u[x - 1][z] && !lane[x - 1][z]) edge = true;
            if (x == W - 1 || !u[x + 1][z] && !lane[x + 1][z]) edge = true;
            if (z == 0 || !u[x][z - 1] && !lane[x][z - 1]) edge = true;
            if (z == H - 1 || !u[x][z + 1] && !lane[x][z + 1]) edge = true;
            if (edge) { street[x][z] = true; } else { free[x][z] = true; }
         }
      }
      Carver splitter = new Carver(cl.minX, cl.minZ, free, new Random(0));
      List<Mask> comps = splitter.components(free, 0, 0);
      int[] failing = null;
      List<Solved> results = new ArrayList<Solved>();
      for (Mask comp : comps) {
         String key = comp.ox + "," + comp.oz + "," + comp.m.length + "x" + comp.m[0].length + "#" + Arrays.deepHashCode(comp.m);
         Solved res = memo.get(key);
         if (res == null) {
            Carver c = new Carver(cl.minX + comp.ox, cl.minZ + comp.oz, comp.m, new Random(seedFor(cl, comp.ox, comp.oz, comp.m)));
            boolean ok = c.carve(new Mask(comp.m, 0, 0));
            List<int[]> sts = new ArrayList<int[]>();
            if (ok) {
               for (int x = 0; x < comp.m.length; x++) for (int z = 0; z < comp.m[0].length; z++) if (c.street[x][z]) sts.add(new int[]{comp.ox + x, comp.oz + z});
            }
            res = new Solved(ok, ok ? c.blocks : null, sts);
            memo.put(key, res);
         }
         if (!res.ok) {
            if (failing == null) failing = new int[]{comp.ox, comp.oz, comp.ox + comp.m.length - 1, comp.oz + comp.m[0].length - 1};
         } else {
            results.add(res);
         }
      }
      if (failing != null) return failing;
      byte[][] kind = new byte[W][H];
      for (int x = 0; x < W; x++) {
         for (int z = 0; z < H; z++) {
            kind[x][z] = (byte) (lane[x][z] ? LANE : box[x][z] ? BOX : street[x][z] ? STREET : NONE);
         }
      }
      List<Rect> all = new ArrayList<Rect>();
      for (Solved res : results) {
         for (int[] st : res.streets) kind[st[0]][st[1]] = STREET;
         for (Rect r : res.blocks) {
            all.add(r);
            for (int x = r.minX; x <= r.maxX; x++) for (int z = r.minZ; z <= r.maxZ; z++) kind[x - cl.minX][z - cl.minZ] = BLOCK;
         }
      }
      cl.kind = kind;
      cl.blocks.clear();
      cl.blocks.addAll(all);
      return null;
   }

   private long seedFor(Cluster cl, int ox, int oz, boolean[][] m) {
      long h = seed ^ 0x51ED270B5F3A9C17L;
      h = h * 31 + (cl.minX + ox);
      h = h * 31 + (cl.minZ + oz);
      h = h * 31 + Arrays.deepHashCode(m);
      return h * 0x9E3779B97F4A7C15L;
   }

   // ----------------------------------------------------------------- carving

   /** A connected set of free chunks, cropped to its own bounding box; (ox, oz) is where it sits in the cluster grid. */
   private static final class Mask {
      final boolean[][] m; final int ox, oz;
      Mask(boolean[][] m, int ox, int oz) { this.m = m; this.ox = ox; this.oz = oz; }
   }

   /** A cropped shape as a hash key. */
   private static final class ShapeKey {
      final boolean[][] m; final int hash;
      ShapeKey(boolean[][] m) { this.m = m; this.hash = Arrays.deepHashCode(m); }
      @Override public int hashCode() { return hash; }
      @Override public boolean equals(Object o) { return o instanceof ShapeKey && Arrays.deepEquals(m, ((ShapeKey) o).m); }
   }

   private final class Carver {
      final int worldX, worldZ; final boolean[][] free; final Random r;
      final boolean[][] street; final List<Rect> blocks = new ArrayList<Rect>();
      int budget = 40000;
      final Map<ShapeKey, Boolean> impossible = new HashMap<ShapeKey, Boolean>();

      Carver(int worldX, int worldZ, boolean[][] free, Random r) {
         this.worldX = worldX; this.worldZ = worldZ; this.free = free; this.r = r;
         this.street = new boolean[free.length][free[0].length];
      }

      boolean run() {
         for (Mask comp : components(free, 0, 0)) {
            if (!carve(comp)) return false;
         }
         return true;
      }

      private List<Mask> components(boolean[][] src, int ox, int oz) {
         int W = src.length, H = src[0].length;
         boolean[][] seen = new boolean[W][H];
         List<Mask> out = new ArrayList<Mask>();
         int[] st = new int[W * H];
         for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
            if (!src[x][z] || seen[x][z]) continue;
            int sp = 0, cnt = 0, minx = x, maxx = x, minz = z, maxz = z;
            int[] cells = new int[16];
            st[sp++] = x * H + z; seen[x][z] = true;
            while (sp > 0) {
               int c = st[--sp]; int cx = c / H, cz = c % H;
               if (cnt == cells.length) cells = Arrays.copyOf(cells, cnt * 2);
               cells[cnt++] = c;
               if (cx < minx) minx = cx; if (cx > maxx) maxx = cx; if (cz < minz) minz = cz; if (cz > maxz) maxz = cz;
               if (cx > 0 && src[cx - 1][cz] && !seen[cx - 1][cz]) { seen[cx - 1][cz] = true; st[sp++] = (cx - 1) * H + cz; }
               if (cx < W - 1 && src[cx + 1][cz] && !seen[cx + 1][cz]) { seen[cx + 1][cz] = true; st[sp++] = (cx + 1) * H + cz; }
               if (cz > 0 && src[cx][cz - 1] && !seen[cx][cz - 1]) { seen[cx][cz - 1] = true; st[sp++] = cx * H + cz - 1; }
               if (cz < H - 1 && src[cx][cz + 1] && !seen[cx][cz + 1]) { seen[cx][cz + 1] = true; st[sp++] = cx * H + cz + 1; }
            }
            boolean[][] part = new boolean[maxx - minx + 1][maxz - minz + 1];
            for (int i = 0; i < cnt; i++) part[cells[i] / H - minx][cells[i] % H - minz] = true;
            out.add(new Mask(part, ox + minx, oz + minz));
         }
         return out;
      }

      boolean carve(Mask mk) {
         if (--budget < 0) return false;
         boolean[][] m = mk.m;
         int w = m.length, h = m[0].length, area = 0;
         for (int x = 0; x < w; x++) for (int z = 0; z < h; z++) if (m[x][z]) area++;
         if (area == 0) return true;
         ShapeKey shape = new ShapeKey(m);
         if (impossible.containsKey(shape)) return false;
         boolean rect = area == w * h;
         if (rect && w >= minBlock && w <= maxBlock && h >= minBlock && h <= maxBlock) {
            blocks.add(new Rect(worldX + mk.ox, worldZ + mk.oz, worldX + mk.ox + w - 1, worldZ + mk.oz + h - 1));
            return true;
         }
         if (w < minBlock || h < minBlock) return false;

         List<int[]> cands = new ArrayList<int[]>();
         if (rect) {
            boolean cutX = w > maxBlock && (h <= maxBlock || w >= h);
            int n = cutX ? w : h;
            int mid = (int) Math.round(n / 2.0 + r.nextGaussian() * n / 6.0);
            mid = Math.max(minBlock, Math.min(n - 1 - minBlock, mid));
            for (int dlt = 0; dlt <= n; dlt++) {
               for (int sgn = -1; sgn <= 1; sgn += 2) {
                  int c = mid + sgn * dlt;
                  if (c >= minBlock && c <= n - 1 - minBlock && !(dlt == 0 && sgn == 1)) cands.add(new int[]{cutX ? 0 : 1, c});
               }
            }
         } else {
            int[][] colExt = new int[w][3];
            for (int x = 0; x < w; x++) {
               int lo = h, hi = -1, cnt = 0;
               for (int z = 0; z < h; z++) if (m[x][z]) { cnt++; if (z < lo) lo = z; if (z > hi) hi = z; }
               colExt[x][0] = lo; colExt[x][1] = hi; colExt[x][2] = cnt;
            }
            int[][] rowExt = new int[h][3];
            for (int z = 0; z < h; z++) {
               int lo = w, hi = -1, cnt = 0;
               for (int x = 0; x < w; x++) if (m[x][z]) { cnt++; if (x < lo) lo = x; if (x > hi) hi = x; }
               rowExt[z][0] = lo; rowExt[z][1] = hi; rowExt[z][2] = cnt;
            }
            List<int[]> aligned = new ArrayList<int[]>(), rest = new ArrayList<int[]>();
            for (int c = minBlock; c <= w - 1 - minBlock; c++) {
               boolean step = !Arrays.equals(colExt[c], colExt[c - 1]) || !Arrays.equals(colExt[c], colExt[c + 1]);
               (step ? aligned : rest).add(new int[]{0, c});
            }
            for (int c = minBlock; c <= h - 1 - minBlock; c++) {
               boolean step = !Arrays.equals(rowExt[c], rowExt[c - 1]) || !Arrays.equals(rowExt[c], rowExt[c + 1]);
               (step ? aligned : rest).add(new int[]{1, c});
            }
            java.util.Collections.shuffle(aligned, r);
            java.util.Collections.shuffle(rest, r);
            cands.addAll(aligned);
            cands.addAll(rest);
         }
         int tried = 0;
         for (int[] cand : cands) {
            if (tried++ > 200 || budget < 0) break;
            boolean vertical = cand[0] == 0; int c = cand[1];
            boolean ok = true; int cells = 0;
            if (vertical) {
               for (int z = 0; z < h && ok; z++) if (m[c][z]) { cells++; if (!(m[c - 1][z] && m[c + 1][z])) ok = false; }
            } else {
               for (int x = 0; x < w && ok; x++) if (m[x][c]) { cells++; if (!(m[x][c - 1] && m[x][c + 1])) ok = false; }
            }
            if (!ok || cells == 0) continue;
            boolean[][] cut = new boolean[w][h];
            for (int x = 0; x < w; x++) System.arraycopy(m[x], 0, cut[x], 0, h);
            if (vertical) for (int z = 0; z < h; z++) cut[c][z] = false; else for (int x = 0; x < w; x++) cut[x][c] = false;
            List<Mask> parts = components(cut, mk.ox, mk.oz);
            boolean feasible = true;
            for (Mask p : parts) if (!coverable(p.m)) { feasible = false; break; }
            if (!feasible) continue;
            int mark = blocks.size();
            if (vertical) for (int z = 0; z < h; z++) { if (m[c][z]) street[mk.ox + c][mk.oz + z] = true; } else for (int x = 0; x < w; x++) { if (m[x][c]) street[mk.ox + x][mk.oz + c] = true; }
            boolean all = true;
            for (Mask p : parts) if (!carve(p)) { all = false; break; }
            if (all) return true;
            while (blocks.size() > mark) blocks.remove(blocks.size() - 1);
            if (vertical) for (int z = 0; z < h; z++) { if (m[c][z]) street[mk.ox + c][mk.oz + z] = false; } else for (int x = 0; x < w; x++) { if (m[x][c]) street[mk.ox + x][mk.oz + c] = false; }
         }
         if (budget >= 0) { impossible.put(shape, Boolean.TRUE); if (area < debugArea) { debugArea = area; debugInfeasible = m; debugOx = worldX + mk.ox; debugOz = worldZ + mk.oz; } }
         return false;
      }

      boolean coverable(boolean[][] p) {
         int W = p.length, H = p[0].length, s = minBlock;
         if (W < s || H < s) return false;
         int[][] pre = new int[W + 1][H + 1];
         for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) pre[x + 1][z + 1] = pre[x][z + 1] + pre[x + 1][z] - pre[x][z] + (p[x][z] ? 1 : 0);
         int[][] diff = new int[W + 2][H + 2];
         for (int x = 0; x + s <= W; x++) for (int z = 0; z + s <= H; z++) {
            if (pre[x + s][z + s] - pre[x][z + s] - pre[x + s][z] + pre[x][z] == s * s) {
               diff[x][z]++; diff[x + s][z]--; diff[x][z + s]--; diff[x + s][z + s]++;
            }
         }
         int[][] cov = new int[W + 1][H + 1];
         for (int x = 0; x < W; x++) for (int z = 0; z < H; z++) {
            cov[x + 1][z + 1] = diff[x][z] + cov[x][z + 1] + cov[x + 1][z] - cov[x][z];
            if (p[x][z] && cov[x + 1][z + 1] <= 0) return false;
         }
         return true;
      }
   }
}
