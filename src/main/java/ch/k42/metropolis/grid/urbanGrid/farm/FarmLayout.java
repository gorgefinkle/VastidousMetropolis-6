package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * The plan for one cell of the farm grid: CELL_CHUNKS x CELL_CHUNKS chunks
 * (256 x 256 blocks), aligned to the chunk grid so a chunk belongs to exactly
 * one cell.
 *
 * A cell is a few very large abandoned fields that take up nearly all of it,
 * plus (in some cells) one compact FARMSTEAD: a 3 x 3 chunk block in a corner,
 * next to a road, holding a derelict barn (a gabled one in one of six
 * weatherings, standard or large, or sometimes a round barn), one or two silos,
 * a wrecked tractor, old hay, maybe a well, and rows of windbreak trees, and --
 * each by chance -- an open pole barn, a shed or two (chicken coop, corn crib,
 * tool shed), a water-pumping windmill and a rusted pickup. The barn's doorway
 * faces the road and a dirt driveway runs straight out of it to the dirt road.
 *
 * A few farmsteads are instead a MODERN COMPOUND: a 6 x 5 chunk plot with one
 * very big steel-framed barn (see FarmModernBarn), abandoned but intact, front
 * doors toward the road, with a silage bunker, silos, a machine shed and yard.
 * Some cells also get a NURSERY in a free corner (glasshouses, polytunnels, a
 * potting shed; see FarmGreenhouse), and some fields are overgrown ORCHARDS.
 * generator.farm.modernBarnPercent, greenhousePercent, orchardShare,
 * roundBarnPercent and outbuildingPercent set how often.
 *
 * Everything added after the first version draws its randomness from a second
 * stream (the field `extra`), kept apart from the one the original plan uses, so
 * with those options at 0 every cell is planned exactly as it was before.
 *
 * Farm boundaries can carry shared dirt access spines, but are not painted
 * automatically. A boundary segment is only built when a real farm site asks
 * for access and the spine reaches an ordinary paved street. Each farm site
 * has its own driveway into the shared spine; unused boundaries remain meadow.
 * Fields are laid out as separate plots and the access routes stay on their
 * edges rather than cutting across crop interiors.
 *
 * Fields are found by taking the largest rectangle of free, same-height farm
 * chunks, again and again, so they follow the shape of the region instead of
 * being cut off by it; a sliver too thin to be a field is left as wild meadow.
 *
 * The plan is a pure function of (world seed, cell, region mask), so every
 * chunk of the cell rebuilds the identical plan and paints its own share.
 */
final class FarmLayout {
   static final int CELL_CHUNKS = 16;
   static final int CELL = CELL_CHUNKS * 16;
   /** Chunks per side of the farmstead block. */
   private static final int BLOCK = 3;
   /** The modern compound's plot, in chunks: SIX along the barn, FIVE across it. */
   private static final int MODERN_LONG = 6;
   private static final int MODERN_SHORT = 5;
   /** In a tunnel field: the lane between two banks of tunnels, and the access lane across the open ends of all of them. */
   private static final int FIELD_LANE = 6;
   private static final int HEAD_LANE = 6;
   /** Offset of the first block fields and buildings may use next to a road strip. */
   private static final int INSET = FarmRoads.WIDTH + 1;

   private FarmContext ctx;
   private final int cellX;
   private final int cellZ;
   private final int x0;
   private final int z0;
   private final long seed;
   private final List<FarmFeature> features = new ArrayList<FarmFeature>();
   private final List<int[]> taken = new ArrayList<int[]>();
   /** Boundary-road requests made by real farm sites in this cell. */
   private final List<RoadAccess> roadAccesses = new ArrayList<RoadAccess>();

   private static final class RoadAccess {
      final boolean vertical;
      final int line;
      final int alongChunk;

      RoadAccess(boolean vertical, int line, int alongChunk) {
         this.vertical = vertical;
         this.line = line;
         this.alongChunk = alongChunk;
      }
   }

   private FarmLayout(FarmContext ctx, int cellX, int cellZ) {
      this.ctx = ctx;
      this.cellX = cellX;
      this.cellZ = cellZ;
      this.x0 = cellX * CELL;
      this.z0 = cellZ * CELL;
      this.seed = ctx.seed;
   }

   static FarmLayout build(FarmContext ctx, int cellX, int cellZ) {
      FarmLayout layout = new FarmLayout(ctx, cellX, cellZ);
      layout.plan();
      layout.ctx = null;                              // a finished plan keeps nothing of the world
      return layout;
   }

   // A cell is planned once and then reused by each of its 256 chunks. Plans are
   // immutable and a pure function of their key, so sharing them is safe.
   private static final int CACHE_SIZE = 32;
   private static final java.util.Map<Key, FarmLayout> CACHE = new java.util.LinkedHashMap<Key, FarmLayout>(64, 0.75f, true) {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(java.util.Map.Entry<Key, FarmLayout> eldest) {
         return this.size() > CACHE_SIZE;
      }
   };

   private static final class Key {
      private final Object region;
      private final long seed;
      private final int cellX;
      private final int cellZ;

      Key(Object region, long seed, int cellX, int cellZ) {
         this.region = region;
         this.seed = seed;
         this.cellX = cellX;
         this.cellZ = cellZ;
      }

      @Override
      public boolean equals(Object o) {
         if (!(o instanceof Key)) {
            return false;
         }
         Key k = (Key) o;
         return k.region == this.region && k.seed == this.seed && k.cellX == this.cellX && k.cellZ == this.cellZ;
      }

      @Override
      public int hashCode() {
         return ((System.identityHashCode(this.region) * 31 + (int) (this.seed ^ (this.seed >>> 32))) * 31 + this.cellX) * 31 + this.cellZ;
      }
   }

   /** The plan for a cell, built on first use and cached. */
   static synchronized FarmLayout get(FarmContext ctx, int cellX, int cellZ) {
      Key key = new Key(ctx.region, ctx.seed, cellX, cellZ);
      FarmLayout layout = CACHE.get(key);
      if (layout == null) {
         layout = build(ctx, cellX, cellZ);
         CACHE.put(key, layout);
      }
      return layout;
   }

   /** Forget all cached plans, e.g. after the farm settings are reloaded. */
   static synchronized void clearCache() {
      CACHE.clear();
   }

   /** Original field/structure footprint ownership query, used by the
    * reference's meadow painter before road strips and cover are painted. */
   boolean reserved(int x, int z) {
      for (FarmFeature f : this.features) {
         if (x >= f.minX && x <= f.maxX && z >= f.minZ && z <= f.maxZ) {
            return true;
         }
      }
      return false;
   }

   void paint(FarmPainter p) {
      for (FarmFeature f : this.features) {
         f.paintIfVisible(p);
      }
   }

   // -------------------------------------------------------------------- plan

   /** Second random stream for the features added after the original ones; see plan(). */
   private Random extra;

   private void plan() {
      int n = CELL_CHUNKS;
      int ccx = this.cellX * n;
      int ccz = this.cellZ * n;
      int[][] gy = new int[n][n];                    // ground height of each farm chunk, -1 outside the region
      for (int i = 0; i < n; i++) {
         for (int j = 0; j < n; j++) {
            gy[i][j] = this.ctx.inRegion(ccx + i, ccz + j) ? this.ctx.groundY(ccx + i, ccz + j) : -1;
         }
      }
      boolean[][] used = new boolean[n][n];
      Random r = new Random(FarmRandom.hash(this.seed, this.cellX, this.cellZ, 101));
      // A second stream for everything added later (modern compounds, nurseries, round barns, outbuildings). Kept apart from r so a
      // cell that gets none of them is planned exactly as it was before they existed.
      this.extra = new Random(FarmRandom.hash(this.seed, this.cellX, this.cellZ, 104));
      if (r.nextInt(100) < PluginConfig.getFarmFarmsteadPercent()) {
         boolean modern = this.extra.nextInt(100) < PluginConfig.getFarmModernBarn();
         if (!(modern && this.planModernFarmstead(gy, used))) {
            this.planFarmstead(r, gy, used);
         }
      }
      if (this.extra.nextInt(100) < PluginConfig.getFarmGreenhouse()) {
         this.planNursery(gy, used);
      }
      this.planFields(r, gy, used);
   }

   /** Block rectangle {minX, minZ, maxX, maxZ} for the chunk range [a..b] x [c..d] of this cell: where a field's fence (or a farmstead's usable ground) may go. */
   private int[] rectFor(int a, int b, int c, int d) {
      // West and north strips are owned by this cell. East and south strips
      // are owned by the neighbouring cell, so only inset the edges that
      // physically reserve road blocks inside this cell.
      int west = a == 0 && this.ctx.lineExists(true, this.cellX) ? INSET : 1;
      int north = c == 0 && this.ctx.lineExists(false, this.cellZ) ? INSET : 1;
      return new int[]{
              this.x0 + 16 * a + west, this.z0 + 16 * c + north,
              this.x0 + 16 * (b + 1) - 2, this.z0 + 16 * (d + 1) - 2};
   }

   /** Add a site request for the shared dirt spine on the selected cell edge. */
   private void recordRoadAccess(int side, int alongBlock) {
      if (side < 0 || side > 3) return;
      boolean vertical = side < 2;
      int line = side == 0 ? this.cellX
              : side == 1 ? this.cellX + 1
              : side == 2 ? this.cellZ : this.cellZ + 1;
      this.roadAccesses.add(new RoadAccess(vertical, line, alongBlock >> 4));
   }

   /**
    * Exposes only genuine site access gates on the queried grid boundary.
    * Called by FarmRoads while it builds the minimal shared route to a paved
    * street; fields and empty boundaries never create road demand.
    */
   void collectAccessPoints(boolean vertical, int line, Set<Integer> points) {
      for (RoadAccess access : this.roadAccesses) {
         if (access.vertical == vertical && access.line == line) {
            points.add(access.alongChunk);
         }
      }
   }

   // ------------------------------------------------------------------ fields

   private FarmField.Kind pickKind(Random r) {
      if (r.nextInt(100) < PluginConfig.getFarmPastureShare()) {
         return FarmField.Kind.PASTURE;
      }
      int roll = r.nextInt(100);
      return roll < 50 ? FarmField.Kind.CROP : (roll < 80 ? FarmField.Kind.FALLOW : FarmField.Kind.HAY);
   }

   /**
    * The largest all-free, same-height rectangle of chunks, each side between
    * 2 and maxDim chunks; {a, b, c, d} or null. One prefix-sum table per ground
    * height makes every rectangle test O(1).
    */
   private int[] bestRect(int[][] gy, boolean[][] used, int maxDim) {
      int n = CELL_CHUNKS;
      int bestArea = 0;
      int[] best = null;
      java.util.TreeSet<Integer> heights = new java.util.TreeSet<Integer>();
      for (int i = 0; i < n; i++) {
         for (int j = 0; j < n; j++) {
            if (gy[i][j] >= 0 && !used[i][j]) {
               heights.add(gy[i][j]);
            }
         }
      }
      for (int hv : heights) {
         int[][] pre = new int[n + 1][n + 1];
         for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
               pre[i + 1][j + 1] = pre[i][j + 1] + pre[i + 1][j] - pre[i][j] + (!used[i][j] && gy[i][j] == hv ? 1 : 0);
            }
         }
         for (int a = 0; a < n; a++) {
            for (int b = a + 1; b < n && b - a + 1 <= maxDim; b++) {
               for (int c = 0; c < n; c++) {
                  for (int d = c + 1; d < n && d - c + 1 <= maxDim; d++) {
                     int area = (b - a + 1) * (d - c + 1);
                     if (area <= bestArea) {
                        continue;
                     }
                     int count = pre[b + 1][d + 1] - pre[a][d + 1] - pre[b + 1][c] + pre[a][c];
                     if (count == area) {
                        bestArea = area;
                        best = new int[]{a, b, c, d};
                     }
                  }
               }
            }
         }
      }
      return best;
   }

   private boolean rectFree(int[][] gy, boolean[][] used, int a, int b, int c, int d) {
      int ref = gy[a][c];
      if (ref < 0) {
         return false;
      }
      for (int i = a; i <= b; i++) {
         for (int j = c; j <= d; j++) {
            if (used[i][j] || gy[i][j] != ref) {
               return false;
            }
         }
      }
      return true;
   }

   private void markUsed(boolean[][] used, int a, int b, int c, int d) {
      for (int i = a; i <= b; i++) {
         for (int j = c; j <= d; j++) {
            used[i][j] = true;
         }
      }
   }

   private void planFields(Random r, int[][] gy, boolean[][] used) {
      int maxDim = 10 + r.nextInt(7);                // fields of up to 10..16 chunks (160..256 blocks) across, so they differ in size
      for (int n = 0; n < 12; n++) {
         int[] rc = this.bestRect(gy, used, maxDim);
         if (rc == null) {
            break;
         }
         this.markUsed(used, rc[0], rc[1], rc[2], rc[3]);
         int[] b = this.rectFor(rc[0], rc[1], rc[2], rc[3]);
         FarmField.Kind kind = this.pickKind(r);
         // some fields (never pasture) are an overgrown orchard instead; rolled from the field's own position, not from r, so every
         // other field and farmstead in the cell comes out exactly as it would without orchards
         if (kind != FarmField.Kind.PASTURE && FarmRandom.unit(this.seed, b[0], b[1], 103) * 100.0 < PluginConfig.getFarmOrchardShare()) {
            kind = FarmField.Kind.ORCHARD;
         }
         boolean fenced = kind == FarmField.Kind.PASTURE || kind == FarmField.Kind.ORCHARD || PluginConfig.getFarmFenceCrops();
         this.features.add(new FarmField(b[0], b[1], b[2], b[3], gy[rc[0]][rc[2]], kind, this.seed, fenced));
      }
   }

   // --------------------------------------------------------------- farmstead

   /** Finds a spot for a w x d box at least gap blocks from everything placed so far, with its minimum corner in [loX..hiX] x [loZ..hiZ]; {x, z} or null. */
   private int[] findSpot(Random r, int w, int d, int gap, int loX, int loZ, int hiX, int hiZ) {
      int rangeX = hiX - loX + 1;
      int rangeZ = hiZ - loZ + 1;
      if (rangeX <= 0 || rangeZ <= 0) {
         return null;
      }
      for (int tries = 0; tries < 40; tries++) {
         int x = loX + r.nextInt(rangeX);
         int z = loZ + r.nextInt(rangeZ);
         int bx1 = x + w - 1;
         int bz1 = z + d - 1;
         boolean clash = false;
         for (int[] t : this.taken) {
            if (x - gap <= t[2] && bx1 + gap >= t[0] && z - gap <= t[3] && bz1 + gap >= t[1]) {
               clash = true;
               break;
            }
         }
         if (!clash) {
            this.taken.add(new int[]{x, z, bx1, bz1});
            return new int[]{x, z};
         }
      }
      return null;
   }

   private boolean blockFree(int[][] gy, boolean[][] used, int a0, int c0) {
      return this.rectFree(gy, used, a0, a0 + BLOCK - 1, c0, c0 + BLOCK - 1);
   }

   private boolean sideHasRoad(int side) {
      switch (side) {
         case 0:  return this.ctx.lineExists(true, this.cellX);
         case 1:  return this.ctx.lineExists(true, this.cellX + 1);
         case 2:  return this.ctx.lineExists(false, this.cellZ);
         default: return this.ctx.lineExists(false, this.cellZ + 1);
      }
   }

   /** True only when this exact gate lies on a continuous spine reaching a paved street. */
   private boolean sideConnected(int side, int alongBlock) {
      int alongChunk = alongBlock >> 4;
      int cx = this.cellX * CELL_CHUNKS;
      int cz = this.cellZ * CELL_CHUNKS;
      switch (side) {
         case 0: return this.ctx.connected(true, cx, alongChunk);
         case 1: return this.ctx.connected(true, cx + CELL_CHUNKS, alongChunk);
         case 2: return this.ctx.connected(false, alongChunk, cz);
         case 3: return this.ctx.connected(false, alongChunk, cz + CELL_CHUNKS);
         default: return false;
      }
   }



   private void planFarmstead(Random r, int[][] gy, boolean[][] used) {
      int n = CELL_CHUNKS;
      int[][] corners = {{0, 0}, {n - BLOCK, 0}, {0, n - BLOCK}, {n - BLOCK, n - BLOCK}};
      for (int i = corners.length - 1; i > 0; i--) {
         int j = r.nextInt(i + 1);
         int[] t = corners[i];
         corners[i] = corners[j];
         corners[j] = t;
      }
      // First choice: a corner whose barn can face a road it can reach.
      for (int[] c : corners) {
         if (!this.blockFree(gy, used, c[0], c[1])) {
            continue;
         }
         int horizontal = c[0] == 0 ? 0 : 1;
         int vertical = c[1] == 0 ? 2 : 3;
         int first = r.nextBoolean() ? horizontal : vertical;
         int second = first == horizontal ? vertical : horizontal;
         if (this.sideHasRoad(first) && this.tryFarmstead(r, gy, used, c[0], c[1], first)) {
            return;
         }
         if (this.sideHasRoad(second) && this.tryFarmstead(r, gy, used, c[0], c[1], second)) {
            return;
         }
      }
      // A farmstead is a road-served site. If neither candidate edge reaches
      // a paved street through a valid dirt strip, leave this cell as fields
      // rather than place an isolated compound with no usable access.
   }

   private boolean tryFarmstead(Random r, int[][] gy, boolean[][] used, int a0, int c0, int side) {
      int f0 = this.features.size();
      int t0 = this.taken.size();
      if (this.buildFarmstead(r, gy[a0][c0], a0, c0, side)) {
         this.markUsed(used, a0, a0 + BLOCK - 1, c0, c0 + BLOCK - 1);
         return true;
      }
      while (this.features.size() > f0) {
         this.features.remove(this.features.size() - 1);
      }
      while (this.taken.size() > t0) {
         this.taken.remove(this.taken.size() - 1);
      }
      return false;
   }

   private boolean buildFarmstead(Random r, int h, int a0, int c0, int side) {
      int[] u = this.rectFor(a0, a0 + BLOCK - 1, c0, c0 + BLOCK - 1);
      int ux0 = u[0];
      int uz0 = u[1];
      int ux1 = u[2];
      int uz1 = u[3];
      boolean axisX = side < 0 ? r.nextBoolean() : side < 2;
      boolean wantBig = r.nextInt(100) < 40;
      boolean round = this.extra.nextInt(100) < PluginConfig.getFarmRoundBarn();      // a round barn instead, door toward the road

      int bx = 0, bz = 0;
      boolean big = false;
      boolean placed = false;
      for (int attempt = 0; attempt < 2 && !placed; attempt++) {
         big = !round && wantBig && attempt == 0;
         int bw = axisX ? barnLength(big, round) : barnWidth(big, round);
         int bd = axisX ? barnWidth(big, round) : barnLength(big, round);
         int boxW = bw + 2;                          // footprint plus the one-block roof overhang each side
         int boxD = bd + 2;
         int loX = ux0 + 3;
         int hiX = ux1 - 3 - boxW + 1;
         int loZ = uz0 + 3;
         int hiZ = uz1 - 3 - boxD + 1;
         if (side == 0) {
            hiX = Math.min(hiX, loX + 5);
         } else if (side == 1) {
            loX = Math.max(loX, hiX - 5);
         } else if (side == 2) {
            hiZ = Math.min(hiZ, loZ + 5);
         } else if (side == 3) {
            loZ = Math.max(loZ, hiZ - 5);
         }
         int[] pos = this.findSpot(r, boxW, boxD, 0, loX, loZ, hiX, hiZ);
         if (pos != null) {
            bx = pos[0] + 1;
            bz = pos[1] + 1;
            placed = true;
         } else if (!big) {
            return false;
         }
      }
      if (!placed) {
         return false;
      }
      int bw = axisX ? barnLength(big, round) : barnWidth(big, round);
      int bd = axisX ? barnWidth(big, round) : barnLength(big, round);
      int half = (barnWidth(big, round) - 1) / 2;

      this.features.add(new FarmProps.Yard(
              Math.max(bx - 4, ux0), Math.max(bz - 4, uz0), Math.min(bx + bw + 3, ux1), Math.min(bz + bd + 3, uz1), h, this.seed));

      if (side >= 0) {
         // The driveway leaves the barn doorway (the middle of its end wall) and runs straight to the dirt road.
         int centre = axisX ? bz + half : bx + half;
         int hw = (round ? FarmRoundBarn.DOOR_W : FarmBarn.doorWidth(big)) / 2;
         int along = centre >> 4;
         int ccx = this.cellX * CELL_CHUNKS;
         int ccz = this.cellZ * CELL_CHUNKS;
         boolean ok;
         int sx0, sz0, sx1, sz1;
         if (side == 0) {
            ok = this.ctx.connected(true, ccx, along);
            sx0 = this.x0 + FarmRoads.WIDTH; sx1 = bx - 1; sz0 = centre - hw; sz1 = centre + hw;
         } else if (side == 1) {
            ok = this.ctx.connected(true, ccx + CELL_CHUNKS, along);
            sx0 = bx + bw; sx1 = this.x0 + CELL - 1; sz0 = centre - hw; sz1 = centre + hw;
         } else if (side == 2) {
            ok = this.ctx.connected(false, along, ccz);
            sz0 = this.z0 + FarmRoads.WIDTH; sz1 = bz - 1; sx0 = centre - hw; sx1 = centre + hw;
         } else {
            ok = this.ctx.connected(false, along, ccz + CELL_CHUNKS);
            sz0 = bz + bd; sz1 = this.z0 + CELL - 1; sx0 = centre - hw; sx1 = centre + hw;
         }
         if (!ok) {
            return false;
         }
         this.taken.add(new int[]{sx0, sz0, sx1, sz1});
         this.features.add(new FarmProps.Driveway(sx0, sz0, sx1, sz1, h, axisX));
         this.recordRoadAccess(side, centre);
      }

      if (round) {
         this.features.add(new FarmRoundBarn(bx, bz, side >= 0 ? side : this.extra.nextInt(4), h, this.seed));
      } else {
         this.features.add(new FarmBarn(bx, bz, axisX, big, h, this.seed));
      }

      int sLoX = ux0 + 3;
      int sLoZ = uz0 + 3;
      int silos = 1 + r.nextInt(2);
      for (int i = 0; i < silos; i++) {
         int[] s = this.findSpot(r, 7, 7, 3, sLoX, sLoZ, ux1 - 3 - 6, uz1 - 3 - 6);
         if (s != null) {
            this.features.add(new FarmSilo(s[0] + 3, s[1] + 3, h, this.seed));
         }
      }
      this.plantBigExtras(h, ux0, uz0, ux1, uz1, true);
      if (r.nextInt(100) < 55) {
         int[] w = this.findSpot(r, 3, 3, 4, sLoX, sLoZ, ux1 - 3 - 2, uz1 - 3 - 2);
         if (w != null) {
            this.features.add(new FarmProps.Well(w[0] + 1, w[1] + 1, h, this.seed));
         }
      }
      int stacks = 1 + r.nextInt(3);
      for (int i = 0; i < stacks; i++) {
         int[] s = this.findSpot(r, 2, 2, 3, sLoX, sLoZ, ux1 - 3 - 1, uz1 - 3 - 1);
         if (s != null) {
            this.features.add(new FarmProps.HayStack(s[0], s[1], h, this.seed));
         }
      }
      if (r.nextInt(100) < 60) {
         boolean tx = r.nextBoolean();
         int tw = tx ? 5 : 3;
         int td = tx ? 3 : 5;
         int[] t = this.findSpot(r, tw, td, 3, sLoX, sLoZ, ux1 - 3 - tw + 1, uz1 - 3 - td + 1);
         if (t != null) {
            this.features.add(new FarmProps.Tractor(t[0], t[1], tx, h, this.seed));
         }
      }
      this.plantSmallExtras(h, ux0, uz0, ux1, uz1);
      this.planWindbreaks(r, h, side, ux0, uz0, ux1, uz1);
      return true;
   }

   private static int barnLength(boolean big, boolean round) { return round ? FarmRoundBarn.SIZE : FarmBarn.length(big); }

   private static int barnWidth(boolean big, boolean round) { return round ? FarmRoundBarn.SIZE : FarmBarn.width(big); }

   /**
    * The two big extras an ordinary farmstead may have: an open pole barn and a water-pumping windmill (the windmill at half the
    * outbuilding chance, being the bigger fuss). Placed before the small props so they get first pick of the room, which is what
    * they need: they are the two that would otherwise never fit. The pole barn is skipped where the caller has already put one.
    */
   private void plantBigExtras(int h, int ux0, int uz0, int ux1, int uz1, boolean poleBarn) {
      Random x = this.extra;
      int pct = PluginConfig.getFarmOutbuildings();
      int loX = ux0 + 3;
      int loZ = uz0 + 3;
      if (poleBarn && x.nextInt(100) < pct) {
         boolean ax = x.nextBoolean();
         int variant = x.nextInt(2);
         int len = FarmOutbuildings.PoleBarn.lengthOf(variant);
         int bw = ax ? len + 2 : 13;
         int bd = ax ? 13 : len + 2;
         int[] s = this.findSpot(x, bw, bd, 3, loX, loZ, ux1 - 3 - bw + 1, uz1 - 3 - bd + 1);
         if (s != null) {
            this.features.add(new FarmOutbuildings.PoleBarn(s[0] + 1, s[1] + 1, ax, variant, h, this.seed));
         }
      }
      if (x.nextInt(100) < pct / 2) {
         int[] s = this.findSpot(x, 13, 13, 2, loX, loZ, ux1 - 3 - 12, uz1 - 3 - 12);
         if (s != null) {
            this.features.add(new FarmOutbuildings.Windmill(s[0] + 6, s[1] + 6, x.nextBoolean(), h, this.seed));
         }
      }
   }

   /** Up to two sheds (chicken coop, corn crib, tool shed) and a rusted pickup, each at generator.farm.outbuildingPercent. */
   private void plantSmallExtras(int h, int ux0, int uz0, int ux1, int uz1) {
      Random x = this.extra;
      int pct = PluginConfig.getFarmOutbuildings();
      int loX = ux0 + 3;
      int loZ = uz0 + 3;
      for (int i = 0; i < 2; i++) {
         if (x.nextInt(100) >= pct) {
            continue;
         }
         boolean ax = x.nextBoolean();
         int kind = x.nextInt(3);
         int len = FarmOutbuildings.Shed.lengthOf(kind);
         int wid = FarmOutbuildings.Shed.widthOf(kind);
         int bw = (ax ? len : wid) + 2;
         int bd = (ax ? wid : len) + 2;
         int[] s = this.findSpot(x, bw, bd, 2, loX, loZ, ux1 - 3 - bw + 1, uz1 - 3 - bd + 1);
         if (s != null) {
            this.features.add(new FarmOutbuildings.Shed(s[0] + 1, s[1] + 1, ax, kind, h, this.seed));
         }
      }
      if (x.nextInt(100) < pct) {
         boolean ax = x.nextBoolean();
         int bw = ax ? 6 : 3;
         int bd = ax ? 3 : 6;
         int[] s = this.findSpot(x, bw, bd, 2, loX, loZ, ux1 - 3 - bw + 1, uz1 - 3 - bd + 1);
         if (s != null) {
            this.features.add(new FarmOutbuildings.Pickup(s[0], s[1], ax, h, this.seed));
         }
      }
   }

   // ---------------------------------------------------------------- the modern compound

   /**
    * A modern compound instead of an ordinary farmstead: one very big, steel-framed barn with its front doors toward the road, on a
    * plot of six by five chunks. Tried in each corner and on each road side, as the ordinary farmstead is; false if none fits (the
    * caller then builds the ordinary one, so a cell is never left without when it was meant to have a farmstead).
    */
   private boolean planModernFarmstead(int[][] gy, boolean[][] used) {
      int[][] corners = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};            // {east?, south?}
      for (int i = corners.length - 1; i > 0; i--) {
         int j = this.extra.nextInt(i + 1);
         int[] t = corners[i];
         corners[i] = corners[j];
         corners[j] = t;
      }
      for (int[] cf : corners) {
         int horizontal = cf[0] == 0 ? 0 : 1;
         int vertical = cf[1] == 0 ? 2 : 3;
         int first = this.extra.nextBoolean() ? horizontal : vertical;
         int second = first == horizontal ? vertical : horizontal;
         if (this.sideHasRoad(first) && this.tryModern(gy, used, cf, first)) {
            return true;
         }
         if (this.sideHasRoad(second) && this.tryModern(gy, used, cf, second)) {
            return true;
         }
      }
      // Do not create an isolated modern compound as a fallback. Every
      // placed farm site must have a real road-facing entrance and driveway.
      return false;
   }

   private boolean tryModern(int[][] gy, boolean[][] used, int[] cf, int side) {
      int n = CELL_CHUNKS;
      boolean axisX = side < 0 ? this.extra.nextBoolean() : side < 2;
      int bwC = axisX ? MODERN_LONG : MODERN_SHORT;
      int bdC = axisX ? MODERN_SHORT : MODERN_LONG;
      int a0 = cf[0] == 0 ? 0 : n - bwC;
      int c0 = cf[1] == 0 ? 0 : n - bdC;
      if (!this.rectFree(gy, used, a0, a0 + bwC - 1, c0, c0 + bdC - 1)) {
         return false;
      }
      int f0 = this.features.size();
      int t0 = this.taken.size();
      if (this.buildModern(gy[a0][c0], a0, c0, bwC, bdC, side, axisX)) {
         this.markUsed(used, a0, a0 + bwC - 1, c0, c0 + bdC - 1);
         return true;
      }
      while (this.features.size() > f0) {
         this.features.remove(this.features.size() - 1);
      }
      while (this.taken.size() > t0) {
         this.taken.remove(this.taken.size() - 1);
      }
      return false;
   }

   private boolean buildModern(int h, int a0, int c0, int bwC, int bdC, int side, boolean axisX) {
      Random x = this.extra;
      int[] u = this.rectFor(a0, a0 + bwC - 1, c0, c0 + bdC - 1);
      int ux0 = u[0];
      int uz0 = u[1];
      int ux1 = u[2];
      int uz1 = u[3];
      int variant = x.nextInt(3);
      int len = FarmModernBarn.lengthOf(variant);
      int wid = FarmModernBarn.widthOf(variant);
      boolean mirror = side < 0 ? x.nextBoolean() : (side == 1 || side == 3);        // the front doors toward the road
      final int M = 8;                                                                   // the barn declares eaves, vines and apron out to here
      int bw = axisX ? len : wid;
      int bd = axisX ? wid : len;
      // across the barn: anywhere in the plot with its margins inside; along it: hard up against the road side, a short drive away
      int loAcross = (axisX ? uz0 : ux0) + 3 + M;
      int hiAcross = (axisX ? uz1 : ux1) - 3 - M - wid + 1;
      int loAlong = (axisX ? ux0 : uz0) + 3 + M;
      int hiAlong = (axisX ? ux1 : uz1) - 3 - M - len + 1;
      if (hiAcross < loAcross || hiAlong < loAlong) {
         return false;
      }
      int lead = x.nextInt(5);
      int along = side < 0 ? loAlong + x.nextInt(hiAlong - loAlong + 1) : (mirror ? hiAlong - lead : loAlong + lead);
      along = Math.max(loAlong, Math.min(hiAlong, along));
      int across = loAcross + x.nextInt(hiAcross - loAcross + 1);
      int bx = axisX ? along : across;
      int bz = axisX ? across : along;
      this.taken.add(new int[]{bx - M, bz - M, bx + bw - 1 + M, bz + bd - 1 + M});

      this.features.add(new FarmProps.Yard(Math.max(bx - 3, ux0), Math.max(bz - 3, uz0), Math.min(bx + bw + 2, ux1), Math.min(bz + bd + 2, uz1), h, this.seed));

      if (side >= 0) {
         // the drive: nine wide like the doors, from the road to the front apron
         int centre = axisX ? bz + (wid - 1) / 2 : bx + (wid - 1) / 2;
         int hw = 4;
         int alongC = centre >> 4;
         int ccx = this.cellX * CELL_CHUNKS;
         int ccz = this.cellZ * CELL_CHUNKS;
         boolean ok;
         int sx0, sz0, sx1, sz1;
         if (side == 0) {
            ok = this.ctx.connected(true, ccx, alongC);
            sx0 = this.x0 + FarmRoads.WIDTH; sx1 = bx - 1; sz0 = centre - hw; sz1 = centre + hw;
         } else if (side == 1) {
            ok = this.ctx.connected(true, ccx + CELL_CHUNKS, alongC);
            sx0 = bx + bw; sx1 = this.x0 + CELL - 1; sz0 = centre - hw; sz1 = centre + hw;
         } else if (side == 2) {
            ok = this.ctx.connected(false, alongC, ccz);
            sz0 = this.z0 + FarmRoads.WIDTH; sz1 = bz - 1; sx0 = centre - hw; sx1 = centre + hw;
         } else {
            ok = this.ctx.connected(false, alongC, ccz + CELL_CHUNKS);
            sz0 = bz + bd; sz1 = this.z0 + CELL - 1; sx0 = centre - hw; sx1 = centre + hw;
         }
         if (!ok) {
            return false;
         }
         this.taken.add(new int[]{sx0, sz0, sx1, sz1});
         this.features.add(new FarmProps.Driveway(sx0, sz0, sx1, sz1, h, axisX));
         this.recordRoadAccess(side, centre);
      }

      this.features.add(FarmModernBarn.make(bx, bz, axisX, mirror, variant, h, this.seed));
      // a wrecked tractor in the feed alley, painted after the barn so it stands on the barn's floor
      int c = (wid - 1) / 2;
      int u0 = len / 2 - 3;
      int tAlong = (axisX ? bx : bz) + (mirror ? len - 5 - u0 : u0);
      int tAcross = (axisX ? bz : bx) + c - 1;
      if (x.nextInt(100) < 70) {
         this.features.add(new FarmProps.Tractor(axisX ? tAlong : tAcross, axisX ? tAcross : tAlong, axisX, h, this.seed));
      }

      int sLoX = ux0 + 3;
      int sLoZ = uz0 + 3;
      // One operation specialization drives the rest of the compound mix.
      // It is chosen before ancillary structures so storage and handling
      // equipment can be appropriate to the actual operation.
      int operation = this.chooseIndustrialStyle(x);
      boolean grainOperation = operation == FarmIndustrial.ELEVATOR || operation == FarmIndustrial.GRAIN_DRYER;
      boolean feedOperation = grainOperation || operation == FarmIndustrial.POULTRY_HALL;
      boolean bAx = x.nextBoolean();
      int bunkerW = bAx ? 24 : 9;
      int bunkerD = bAx ? 9 : 24;
      int bunkerChance = operation == FarmIndustrial.PUMPWORKS
              || operation == FarmIndustrial.ORGANIC_RECOVERY
              || operation == FarmIndustrial.PACKHOUSE ? 30 : 72;
      if (x.nextInt(100) < bunkerChance) {
         int[] bs = this.findSpot(x, bunkerW, bunkerD, 3, sLoX, sLoZ,
                 ux1 - 3 - bunkerW + 1, uz1 - 3 - bunkerD + 1);
         if (bs != null) {
            this.features.add(new FarmModernBarn.Bunker(bs[0], bs[1], bAx, x.nextBoolean(), 24, 9, h, this.seed));
         }
      }
      int siloChance = grainOperation ? 88 : (feedOperation ? 48 : 20);
      if (x.nextInt(100) < siloChance) {
         int silos = grainOperation ? 1 + x.nextInt(2) : 1;
         for (int i = 0; i < silos; i++) {
            int[] s = this.findSpot(x, 7, 7, 3, sLoX, sLoZ, ux1 - 3 - 6, uz1 - 3 - 6);
            if (s != null) this.features.add(new FarmSilo(s[0] + 3, s[1] + 3, h, this.seed));
         }
      }
      // a machine shed (the long pole barn), a windmill perhaps, and the rest of the yard
      this.plantBigExtras(h, ux0, uz0, ux1, uz1, false);
      if (x.nextInt(100) < 28) {
         boolean ax = x.nextBoolean();
         int pl = FarmOutbuildings.PoleBarn.lengthOf(2);
         int pw = ax ? pl + 2 : 13;
         int pd = ax ? 13 : pl + 2;
         int[] s = this.findSpot(x, pw, pd, 3, sLoX, sLoZ, ux1 - 3 - pw + 1, uz1 - 3 - pd + 1);
         if (s != null) {
            this.features.add(new FarmOutbuildings.PoleBarn(s[0] + 1, s[1] + 1, ax, 2, h, this.seed));
         }
      }
      this.plantModernExtras(x, h, ux0, uz0, ux1, uz1);
      // One operation-specific facility per compound at most. Its unique
      // footprint is reserved after the barn, yard, feed handling and small
      // essentials so it cannot collide with the main farm or its driveway.
      if (x.nextInt(100) < 62) {
         boolean ax = x.nextBoolean();
         int bl = FarmIndustrial.length(operation), wd = FarmIndustrial.width(operation);
         int rw = (ax ? bl : wd) + 2, rd = (ax ? wd : bl) + 2;
         int[] site = this.findSpot(x, rw, rd, 2, sLoX, sLoZ,
                 ux1 - 3 - rw + 1, uz1 - 3 - rd + 1);
         if (site != null) {
            this.features.add(new FarmIndustrial(site[0] + 1, site[1] + 1, ax, operation, h, this.seed));
         }
      }
      int stacks = x.nextInt(3);
      for (int i = 0; i < stacks; i++) {
         int[] s = this.findSpot(x, 2, 2, 3, sLoX, sLoZ, ux1 - 3 - 1, uz1 - 3 - 1);
         if (s != null) {
            this.features.add(new FarmProps.HayStack(s[0], s[1], h, this.seed));
         }
      }
      this.planWindbreaks(x, h, side, ux0, uz0, ux1, uz1);
      return true;
   }

   /** Weighted operation mix. The old elevator/depot pair no longer dominates every modern site. */
   private int chooseIndustrialStyle(Random x) {
      int roll = x.nextInt(100);
      if (roll < 17) return FarmIndustrial.ELEVATOR;
      if (roll < 30) return FarmIndustrial.DEPOT;
      if (roll < 44) return FarmIndustrial.PACKHOUSE;
      if (roll < 58) return FarmIndustrial.GRAIN_DRYER;
      if (roll < 70) return FarmIndustrial.PUMPWORKS;
      if (roll < 80) return FarmIndustrial.POULTRY_HALL;
      if (roll < 92) return FarmIndustrial.LIVESTOCK_WORKS;
      return FarmIndustrial.ORGANIC_RECOVERY;
   }

   /** Keep modern compounds functional without filling every spare corner with sheds. */
   private void plantModernExtras(Random x, int h, int ux0, int uz0, int ux1, int uz1) {
      int loX = ux0 + 3, loZ = uz0 + 3;
      if (x.nextInt(100) < 32) {
         boolean ax = x.nextBoolean();
         int len = FarmOutbuildings.Shed.lengthOf(FarmOutbuildings.Shed.TOOL);
         int wid = FarmOutbuildings.Shed.widthOf(FarmOutbuildings.Shed.TOOL);
         int bw = (ax ? len : wid) + 2, bd = (ax ? wid : len) + 2;
         int[] s = this.findSpot(x, bw, bd, 2, loX, loZ, ux1 - 3 - bw + 1, uz1 - 3 - bd + 1);
         if (s != null) this.features.add(new FarmOutbuildings.Shed(
                 s[0] + 1, s[1] + 1, ax, FarmOutbuildings.Shed.TOOL, h, this.seed));
      }
      if (x.nextInt(100) < 48) {
         boolean ax = x.nextBoolean();
         int bw = ax ? 6 : 3, bd = ax ? 3 : 6;
         int[] s = this.findSpot(x, bw, bd, 2, loX, loZ, ux1 - 3 - bw + 1, uz1 - 3 - bd + 1);
         if (s != null) this.features.add(new FarmOutbuildings.Pickup(s[0], s[1], ax, h, this.seed));
      }
   }

   // ---------------------------------------------------------------- the nursery

   /**
    * A nursery in a free corner of the cell: a glasshouse or two, or a block of polytunnels, with a potting shed. Placed before the
    * fields so they leave room for it. Skipped if no corner is free.
    */
   private void planNursery(int[][] gy, boolean[][] used) {
      // Production layouts are attempted first, but only when their service
      // lane can meet a connected paved-road spine.
      double kind = this.extra.nextDouble();
      if (kind < 0.58 && this.planTunnelField(gy, used)) return;
      if (kind >= 0.58 && kind < 0.80 && this.planCommercialGlass(gy, used)) return;

      int n = CELL_CHUNKS;
      int[][] corners = {{0, 0}, {n - BLOCK, 0}, {0, n - BLOCK}, {n - BLOCK, n - BLOCK}};
      for (int i = corners.length - 1; i > 0; i--) {
         int j = this.extra.nextInt(i + 1);
         int[] t = corners[i];
         corners[i] = corners[j];
         corners[j] = t;
      }
      for (int[] corner : corners) {
         if (!this.blockFree(gy, used, corner[0], corner[1])) continue;
         int horizontal = corner[0] == 0 ? 0 : 1;
         int vertical = corner[1] == 0 ? 2 : 3;
         int first = this.extra.nextBoolean() ? horizontal : vertical;
         int second = first == horizontal ? vertical : horizontal;
         for (int side : new int[]{first, second}) {
            int f0 = this.features.size();
            int t0 = this.taken.size();
            if (this.buildNursery(gy[corner[0]][corner[1]], corner[0], corner[1], side)) {
               this.markUsed(used, corner[0], corner[0] + BLOCK - 1, corner[1], corner[1] + BLOCK - 1);
               return;
            }
            while (this.features.size() > f0) this.features.remove(this.features.size() - 1);
            while (this.taken.size() > t0) this.taken.remove(this.taken.size() - 1);
         }
      }
   }

   private boolean buildNursery(int h, int a0, int c0, int side) {
      Random x = this.extra;
      int[] u = this.rectFor(a0, a0 + BLOCK - 1, c0, c0 + BLOCK - 1);
      boolean axisX = side < 2;
      boolean mirror = side == 1 || side == 3;
      int variant = x.nextInt(3);
      int len = FarmGreenhouse.lengthOf(FarmGreenhouse.GABLED, variant);
      int wid = FarmGreenhouse.widthOf(FarmGreenhouse.GABLED, variant, 0);
      int acrossCentre = axisX ? (u[1] + u[3]) / 2 : (u[0] + u[2]) / 2;
      int acrossStart = acrossCentre - wid / 2;
      int doorAlong = side == 0 ? this.x0 + 10
              : side == 1 ? this.x0 + CELL - 11
              : side == 2 ? this.z0 + 10 : this.z0 + CELL - 11;
      if (!this.sideConnected(side, acrossCentre)) return false;

      int ox, oz;
      if (axisX) {
         ox = mirror ? doorAlong - len + 1 : doorAlong;
         oz = acrossStart;
      } else {
         ox = acrossStart;
         oz = mirror ? doorAlong - len + 1 : doorAlong;
      }

      // A four-wide driveway meets the actual open gable end, not just an
      // arbitrary point in the nursery yard.
      int minX, minZ, maxX, maxZ;
      if (side == 0) {
         minX = this.x0 + FarmRoads.WIDTH;
         maxX = doorAlong - 1;
         minZ = acrossCentre - 2;
         maxZ = acrossCentre + 1;
      } else if (side == 1) {
         minX = doorAlong + 1;
         maxX = this.x0 + CELL - 1;
         minZ = acrossCentre - 2;
         maxZ = acrossCentre + 1;
      } else if (side == 2) {
         minX = acrossCentre - 2;
         maxX = acrossCentre + 1;
         minZ = this.z0 + FarmRoads.WIDTH;
         maxZ = doorAlong - 1;
      } else {
         minX = acrossCentre - 2;
         maxX = acrossCentre + 1;
         minZ = doorAlong + 1;
         maxZ = this.z0 + CELL - 1;
      }
      if (minX > maxX || minZ > maxZ) return false;

      this.taken.add(new int[]{minX, minZ, maxX, maxZ});
      this.features.add(new FarmProps.Driveway(minX, minZ, maxX, maxZ, h, axisX));
      this.recordRoadAccess(side, acrossCentre);
      this.features.add(FarmGreenhouse.gabled(ox, oz, axisX, mirror, variant, h, this.seed));
      this.taken.add(new int[]{ox - 1, oz - 1,
              ox + (axisX ? len : wid), oz + (axisX ? wid : len)});

      // Keep the four-wide entrance lane clear of the greenhouse floor.

      if (x.nextInt(100) < 70) {
         boolean shedAxisX = x.nextBoolean();
         int shedLength = FarmOutbuildings.Shed.lengthOf(FarmOutbuildings.Shed.TOOL);
         int shedWidth = FarmOutbuildings.Shed.widthOf(FarmOutbuildings.Shed.TOOL);
         int bw = (shedAxisX ? shedLength : shedWidth) + 2;
         int bd = (shedAxisX ? shedWidth : shedLength) + 2;
         int[] s = this.findSpot(x, bw, bd, 2, u[0] + 2, u[1] + 2, u[2] - 2 - bw + 1, u[3] - 2 - bd + 1);
         if (s != null) {
            this.features.add(new FarmOutbuildings.Shed(s[0] + 1, s[1] + 1, shedAxisX, FarmOutbuildings.Shed.TOOL, h, this.seed));
         }
      }
      return true;
   }

   /**
    * A glasshouse with a bank of two polytunnels beside it, run the way a grower would: same axis, same end flush with the glasshouse's
    * door, a four-wide track between. If the whole group does not fit, just the glasshouse.
    */
   private boolean placeGlasshouseAndBank(Random x, int h, int[] u) {
      boolean ax = x.nextBoolean();
      int gl = FarmGreenhouse.lengthOf(FarmGreenhouse.GABLED, 1);
      int gw = FarmGreenhouse.widthOf(FarmGreenhouse.GABLED, 1, 0);
      int tl = FarmGreenhouse.lengthOf(FarmGreenhouse.HOOP, 0);
      int tw = FarmGreenhouse.widthOf(FarmGreenhouse.HOOP, 0, 2);
      int lane = 4;
      int along = Math.max(gl, tl);
      int across = gw + lane + tw;
      int bw = (ax ? along : across) + 2;
      int bd = (ax ? across : along) + 2;
      int[] s = this.findSpot(x, bw, bd, 3, u[0] + 2, u[1] + 2, u[2] - 2 - bw + 1, u[3] - 2 - bd + 1);
      if (s == null) {
         return this.placeGlasshouse(x, 1, h, u);
      }
      int ox = s[0] + 1;
      int oz = s[1] + 1;
      // the track first, so the buildings paint over its edges
      if (ax) {
         this.features.add(new FarmProps.Driveway(ox, oz + gw, ox + along - 1, oz + gw + lane - 1, h, true));
      } else {
         this.features.add(new FarmProps.Driveway(ox + gw, oz, ox + gw + lane - 1, oz + along - 1, h, false));
      }
      this.features.add(FarmGreenhouse.gabled(ox, oz, ax, 1, h, this.seed));
      this.features.add(FarmGreenhouse.hoops(ax ? ox : ox + gw + lane, ax ? oz + gw + lane : oz, ax, 0, 2, h, this.seed));
      return true;
   }

   // ---------------------------------------------------------------- the tunnel field

   /**
    * A polytunnel field: one or two banks of identical tunnels, every one the same length with its ends flush, all open at the same
    * end onto a single access lane that runs across the lot of them; with two banks, a lane between. A grower's layout, so everything
    * lines up. One bank of five or six tunnels, two of five, or three of four: eighteen at the most. Needs a plot of its own (up to
    * ten chunks by four), tried in each corner; false if there is none, and the nursery is built the ordinary way.
    */
   private boolean planTunnelField(int[][] gy, boolean[][] used) {
      Random x = this.extra;
      int banks = 1 + x.nextInt(4);
      int tunnels = banks == 1 ? 5 + x.nextInt(2) : (banks == 2 ? 5 + x.nextInt(2) : banks == 3 ? 4 + x.nextInt(2) : 5 + x.nextInt(2));
      int variant = 1 + x.nextInt(2);
      int len = FarmGreenhouse.lengthOf(FarmGreenhouse.HOOP, variant);
      int bankW = FarmGreenhouse.widthOf(FarmGreenhouse.HOOP, variant, tunnels);
      int fieldW = banks * bankW + (banks - 1) * FIELD_LANE;
      int fieldL = HEAD_LANE + len;
      int alongC = (fieldL + 10 + 15) / 16;
      int acrossC = (fieldW + 10 + 15) / 16;
      int n = CELL_CHUNKS;
      int[] sides = {0, 2}; // west or north: the tunnel mouths face the road-side head lane
      if (x.nextBoolean()) { int t = sides[0]; sides[0] = sides[1]; sides[1] = t; }
      for (int side : sides) {
         boolean ax = side == 0;
         int bwC = ax ? alongC : acrossC;
         int bdC = ax ? acrossC : alongC;
         if (bwC > n || bdC > n) continue;
         int farA = n - bwC;
         int farC = n - bdC;
         int[][] options = ax
                 ? new int[][]{{0, 0}, {0, farC}}
                 : new int[][]{{0, 0}, {farA, 0}};
         if (x.nextBoolean()) { int[] t = options[0]; options[0] = options[1]; options[1] = t; }
         for (int[] option : options) {
            int a0 = option[0], c0 = option[1];
            if (!this.rectFree(gy, used, a0, a0 + bwC - 1, c0, c0 + bdC - 1)) continue;
            int f0 = this.features.size();
            int t0 = this.taken.size();
            if (this.buildTunnelField(gy[a0][c0], a0, c0, bwC, bdC, ax, side,
                    banks, tunnels, variant, len, bankW, fieldW)) {
               this.markUsed(used, a0, a0 + bwC - 1, c0, c0 + bdC - 1);
               return true;
            }
            while (this.features.size() > f0) this.features.remove(this.features.size() - 1);
            while (this.taken.size() > t0) this.taken.remove(this.taken.size() - 1);
         }
      }
      return false;
   }

   private boolean buildTunnelField(int h, int a0, int c0, int bwC, int bdC, boolean ax, int side,
                                    int banks, int tunnels, int variant, int len, int bankW, int fieldW) {
      Random x = this.extra;
      int[] u = this.rectFor(a0, a0 + bwC - 1, c0, c0 + bdC - 1);
      int alongSpan = (ax ? u[2] - u[0] : u[3] - u[1]) + 1;
      int acrossSpan = (ax ? u[3] - u[1] : u[2] - u[0]) + 1;
      if (alongSpan < HEAD_LANE + len + 8 || acrossSpan < fieldW + 8) {
         return false;
      }
      // the access lane at the low-coordinate side of the plot, the tunnels' open ends facing it, the whole field flush to the corner
      int acrossStart = (ax ? u[1] : u[0]) + 4;
      int gate = acrossStart + fieldW / 2;
      if (!this.sideConnected(side, gate)) return false;
      int laneStart = side == 0 ? this.x0 + FarmRoads.WIDTH : this.z0 + FarmRoads.WIDTH;
      int tunnelStart = laneStart + HEAD_LANE;
      int ox = ax ? tunnelStart : acrossStart;
      int oz = ax ? acrossStart : tunnelStart;
      if (ax) {
         this.taken.add(new int[]{laneStart - 1, acrossStart - 2, tunnelStart + len, acrossStart + fieldW + 1});
         this.features.add(new FarmProps.Driveway(laneStart, acrossStart - 1, tunnelStart - 1, acrossStart + fieldW, h, true));
      } else {
         this.taken.add(new int[]{acrossStart - 2, laneStart - 1, acrossStart + fieldW + 1, tunnelStart + len});
         this.features.add(new FarmProps.Driveway(acrossStart - 1, laneStart, acrossStart + fieldW, tunnelStart - 1, h, false));
      }
      this.recordRoadAccess(side, gate);
      for (int lane = 1; lane < banks; lane++) {
         int off = lane * bankW + (lane - 1) * FIELD_LANE;
         if (ax) {
            this.features.add(new FarmProps.Driveway(ox, oz + off, ox + len - 1, oz + off + FIELD_LANE - 1, h, true));
         } else {
            this.features.add(new FarmProps.Driveway(ox + off, oz, ox + off + FIELD_LANE - 1, oz + len - 1, h, false));
         }
      }
      for (int i = 0; i < banks; i++) {
         int off = i * (bankW + FIELD_LANE);
         this.features.add(FarmGreenhouse.hoops(ax ? ox : ox + off, ax ? oz + off : oz, ax, variant, tunnels, h, this.seed));
      }
      // a pickup parked on the access lane, and a packing shed in what room is left on the plot
      if (x.nextInt(100) < 55) {
         int slide = x.nextInt(Math.max(1, fieldW - 8));
         if (ax) {
            this.features.add(new FarmOutbuildings.Pickup(laneStart + 1, acrossStart + 2 + slide, false, h, this.seed));
         } else {
            this.features.add(new FarmOutbuildings.Pickup(acrossStart + 2 + slide, laneStart + 1, true, h, this.seed));
         }
      }
      if (x.nextInt(100) < 70) {
         boolean sax = x.nextBoolean();
         int sl = FarmOutbuildings.Shed.lengthOf(FarmOutbuildings.Shed.TOOL);
         int sw = FarmOutbuildings.Shed.widthOf(FarmOutbuildings.Shed.TOOL);
         int bw = (sax ? sl : sw) + 2;
         int bd = (sax ? sw : sl) + 2;
         int[] s = this.findSpot(x, bw, bd, 2, u[0] + 2, u[1] + 2, u[2] - 2 - bw + 1, u[3] - 2 - bd + 1);
         if (s != null) {
            this.features.add(new FarmOutbuildings.Shed(s[0] + 1, s[1] + 1, sax, FarmOutbuildings.Shed.TOOL, h, this.seed));
         }
      }
      return true;
   }

   // ---------------------------------------------------------------- the commercial glasshouse

   /**
    * One large multi-bay glasshouse on a plot of its own (see FarmGreenhouse.BAYS), its doors facing an access lane that runs the
    * width of the building, a pickup on the lane, a shed beside. Tried in each corner; false if the plot is not there.
    */
   private boolean planCommercialGlass(int[][] gy, boolean[][] used) {
      Random x = this.extra;
      int bays = 3 + x.nextInt(2);
      int variant = x.nextInt(3);
      int len = FarmGreenhouse.lengthOf(FarmGreenhouse.BAYS, variant);
      int wid = FarmGreenhouse.widthOf(FarmGreenhouse.BAYS, variant, bays);
      int alongC = (HEAD_LANE + len + 10 + 15) / 16;
      int acrossC = (wid + 10 + 15) / 16;
      int n = CELL_CHUNKS;
      int[] sides = {0, 2};
      if (x.nextBoolean()) { int t = sides[0]; sides[0] = sides[1]; sides[1] = t; }
      for (int side : sides) {
         boolean ax = side == 0;
         int bwC = ax ? alongC : acrossC;
         int bdC = ax ? acrossC : alongC;
         if (bwC > n || bdC > n) continue;
         int farA = n - bwC;
         int farC = n - bdC;
         int[][] options = ax
                 ? new int[][]{{0, 0}, {0, farC}}
                 : new int[][]{{0, 0}, {farA, 0}};
         if (x.nextBoolean()) { int[] t = options[0]; options[0] = options[1]; options[1] = t; }
         for (int[] option : options) {
            int a0 = option[0], c0 = option[1];
            if (!this.rectFree(gy, used, a0, a0 + bwC - 1, c0, c0 + bdC - 1)) continue;
            int f0 = this.features.size();
            int t0 = this.taken.size();
            if (this.buildCommercialGlass(gy[a0][c0], a0, c0, bwC, bdC, ax, side, bays, variant, len, wid)) {
               this.markUsed(used, a0, a0 + bwC - 1, c0, c0 + bdC - 1);
               return true;
            }
            while (this.features.size() > f0) this.features.remove(this.features.size() - 1);
            while (this.taken.size() > t0) this.taken.remove(this.taken.size() - 1);
         }
      }
      return false;
   }

   private boolean buildCommercialGlass(int h, int a0, int c0, int bwC, int bdC, boolean ax, int side,
                                        int bays, int variant, int len, int wid) {
      Random x = this.extra;
      int[] u = this.rectFor(a0, a0 + bwC - 1, c0, c0 + bdC - 1);
      int alongSpan = (ax ? u[2] - u[0] : u[3] - u[1]) + 1;
      int acrossSpan = (ax ? u[3] - u[1] : u[2] - u[0]) + 1;
      if (alongSpan < HEAD_LANE + len + 8 || acrossSpan < wid + 8) {
         return false;
      }
      int acrossStart = (ax ? u[1] : u[0]) + 4;
      int gate = acrossStart + wid / 2;
      if (!this.sideConnected(side, gate)) return false;
      int laneStart = side == 0 ? this.x0 + FarmRoads.WIDTH : this.z0 + FarmRoads.WIDTH;
      int houseStart = laneStart + HEAD_LANE;
      int ox = ax ? houseStart : acrossStart;
      int oz = ax ? acrossStart : houseStart;
      if (ax) {
         this.taken.add(new int[]{laneStart - 1, acrossStart - 2, houseStart + len, acrossStart + wid + 1});
         this.features.add(new FarmProps.Driveway(laneStart, acrossStart - 1, houseStart - 1, acrossStart + wid, h, true));
      } else {
         this.taken.add(new int[]{acrossStart - 2, laneStart - 1, acrossStart + wid + 1, houseStart + len});
         this.features.add(new FarmProps.Driveway(acrossStart - 1, laneStart, acrossStart + wid, houseStart - 1, h, false));
      }
      this.recordRoadAccess(side, gate);
      this.features.add(FarmGreenhouse.bays(ox, oz, ax, variant, bays, h, this.seed));
      if (x.nextInt(100) < 55) {
         int slide = x.nextInt(Math.max(1, wid - 8));
         if (ax) {
            this.features.add(new FarmOutbuildings.Pickup(laneStart + 1, acrossStart + 2 + slide, false, h, this.seed));
         } else {
            this.features.add(new FarmOutbuildings.Pickup(acrossStart + 2 + slide, laneStart + 1, true, h, this.seed));
         }
      }
      if (x.nextInt(100) < 70) {
         boolean sax = x.nextBoolean();
         int sl = FarmOutbuildings.Shed.lengthOf(FarmOutbuildings.Shed.TOOL);
         int sw = FarmOutbuildings.Shed.widthOf(FarmOutbuildings.Shed.TOOL);
         int bw = (sax ? sl : sw) + 2;
         int bd = (sax ? sw : sl) + 2;
         int[] s = this.findSpot(x, bw, bd, 2, u[0] + 2, u[1] + 2, u[2] - 2 - bw + 1, u[3] - 2 - bd + 1);
         if (s != null) {
            this.features.add(new FarmOutbuildings.Shed(s[0] + 1, s[1] + 1, sax, FarmOutbuildings.Shed.TOOL, h, this.seed));
         }
      }
      return true;
   }

   private boolean placeGlasshouse(Random x, int variant, int h, int[] u) {
      boolean ax = x.nextBoolean();
      int len = FarmGreenhouse.lengthOf(FarmGreenhouse.GABLED, variant);
      int wid = FarmGreenhouse.widthOf(FarmGreenhouse.GABLED, variant, 0);
      int bw = (ax ? len : wid) + 2;
      int bd = (ax ? wid : len) + 2;
      int[] s = this.findSpot(x, bw, bd, 3, u[0] + 2, u[1] + 2, u[2] - 2 - bw + 1, u[3] - 2 - bd + 1);
      if (s == null) {
         return false;
      }
      this.features.add(FarmGreenhouse.gabled(s[0] + 1, s[1] + 1, ax, variant, h, this.seed));
      return true;
   }

   private boolean placeTunnels(Random x, int tunnels, int variant, int h, int[] u) {
      boolean ax = x.nextBoolean();
      int len = FarmGreenhouse.lengthOf(FarmGreenhouse.HOOP, variant);
      int wid = FarmGreenhouse.widthOf(FarmGreenhouse.HOOP, variant, tunnels);
      int bw = (ax ? len : wid) + 2;
      int bd = (ax ? wid : len) + 2;
      int[] s = this.findSpot(x, bw, bd, 3, u[0] + 2, u[1] + 2, u[2] - 2 - bw + 1, u[3] - 2 - bd + 1);
      if (s == null) {
         return false;
      }
      this.features.add(FarmGreenhouse.hoops(s[0] + 1, s[1] + 1, ax, variant, tunnels, h, this.seed));
      return true;
   }

   /** Tree rows 3 blocks in from the edges of the farmstead, on the sides away from the road. */
   private void planWindbreaks(Random r, int h, int side, int ux0, int uz0, int ux1, int uz1) {
      List<Integer> edges = new ArrayList<Integer>();      // 0 west, 1 east, 2 north, 3 south
      for (int e = 0; e < 4; e++) {
         if (e != side) {
            edges.add(e);
         }
      }
      int opposite = side == 0 ? 1 : side == 1 ? 0 : side == 2 ? 3 : side == 3 ? 2 : -1;
      List<Integer> chosen = new ArrayList<Integer>();
      if (opposite >= 0) {
         chosen.add(opposite);
         edges.remove(Integer.valueOf(opposite));
      } else {
         chosen.add(edges.remove(r.nextInt(edges.size())));
      }
      chosen.add(edges.get(r.nextInt(edges.size())));
      for (int e : chosen) {
         List<int[]> spots = new ArrayList<int[]>();
         boolean vertical = e < 2;                          // a west/east row runs along z
         int fixed = e == 0 ? ux0 + 3 : e == 1 ? ux1 - 3 : e == 2 ? uz0 + 3 : uz1 - 3;
         int from = (vertical ? uz0 : ux0) + 3;
         int to = (vertical ? uz1 : ux1) - 3;
         for (int t = from; t <= to; t += 5) {
            int a = fixed;
            int b = t + r.nextInt(2);
            int tx = vertical ? a : b;
            int tz = vertical ? b : a;
            if (this.nearTaken(tx, tz, 3)) {
               continue;
            }
            spots.add(new int[]{tx, tz});
         }
         if (spots.size() >= 2) {
            this.features.add(new FarmProps.Windbreak(spots.toArray(new int[0][]), h, this.seed, r.nextInt(100) < 65));
         }
      }
   }

   private boolean nearTaken(int x, int z, int pad) {
      for (int[] t : this.taken) {
         if (x >= t[0] - pad && x <= t[2] + pad && z >= t[1] - pad && z <= t[3] + pad) {
            return true;
         }
      }
      return false;
   }
}
