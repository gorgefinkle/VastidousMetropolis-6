package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

/**
 * A derelict gabled barn, in two sizes:
 *
 *  - standard: 15 x 9, walls 4 high, a 3-wide archway at each end;
 *  - large:    25 x 13, walls 6 high, 5-wide archways, stalls down both sides,
 *              a hayloft door in the gable and a cupola on the ridge.
 *
 * Both have the roof overhanging the walls on all four sides (eaves along the
 * sides, a projecting gable at each end), a hayloft over the back half reached
 * by a ladder, and loot chests (named for the residential collection).
 *
 * The ruin level (generator.farm.ruinPercent, varied per barn) takes holes out
 * of the roof, chunks out of the walls, most of the glass, patches of the
 * loft and floor, and grows vines and cobwebs. Chest cells and the floor
 * under them are never removed, so the loot stays reachable.
 *
 * Built in local coordinates: u along the length, v across the width, dy up
 * from the ground (dy 0 is the ground surface, which becomes the floor). The
 * barn can lie along x or along z; the helpers turn u/v into world coordinates
 * and pick the right stair / ladder / chest / vine orientation.
 */
final class FarmBarn extends FarmFeature {
   static int length(boolean big) { return big ? 25 : 15; }
   static int width(boolean big) { return big ? 13 : 9; }
   static int doorWidth(boolean big) { return big ? 5 : 3; }

   private final int ox, oz, h;
   private final boolean axisX, mirror, big;
   private final int L, W, T, half, dw, dh, loftY, ladderU;
   private final long seed;
   private final double ruin;
   private final int wall, wallData, alt, altData, trim, trimData, stairs, ridge, ridgeData;

   FarmBarn(int ox, int oz, boolean axisX, boolean big, int h, long seed) {
      this(ox, oz, axisX, false, big, h, seed);
   }

   FarmBarn(int ox, int oz, boolean axisX, boolean mirror, boolean big, int h, long seed) {
      super(ox - 1, oz - 1, ox + (axisX ? length(big) : width(big)), oz + (axisX ? width(big) : length(big)));
      this.ox = ox;
      this.oz = oz;
      this.axisX = axisX;
      this.mirror = mirror;
      this.big = big;
      this.h = h;
      this.seed = seed;
      this.L = length(big);
      this.W = width(big);
      this.T = big ? 6 : 4;
      this.half = (this.W - 1) / 2;
      this.dw = doorWidth(big);
      this.dh = big ? 4 : 3;
      this.loftY = big ? 5 : 4;
      this.ladderU = big ? 11 : 9;
      double base = PluginConfig.getFarmRuinPercent() / 100.0;
      this.ruin = Math.max(0.0, Math.min(0.95, base * (0.55 + 0.9 * FarmRandom.unit(seed, ox, oz, 1300))));

      double r = FarmRandom.unit(seed, ox, oz, 401);
      if (r < 0.34) {            // faded red barn, white trim, dark roof
         this.wall = FarmPainter.TERRACOTTA; this.wallData = 14;
         this.alt = FarmPainter.TERRACOTTA; this.altData = 12;       // sun-bleached to brown
         this.trim = FarmPainter.TERRACOTTA; this.trimData = 0;
         this.stairs = 164;
         this.ridge = FarmPainter.PLANKS; this.ridgeData = 5;
      } else if (r < 0.50) {     // dark timber barn
         this.wall = FarmPainter.PLANKS; this.wallData = 1;
         this.alt = FarmPainter.PLANKS; this.altData = 5;
         this.trim = FarmPainter.LOG; this.trimData = 1;
         this.stairs = 134;
         this.ridge = FarmPainter.PLANKS; this.ridgeData = 1;
      } else if (r < 0.62) {     // grey weathered oak
         this.wall = FarmPainter.PLANKS; this.wallData = 0;
         this.alt = FarmPainter.PLANKS; this.altData = 5;
         this.trim = FarmPainter.LOG; this.trimData = 0;
         this.stairs = 53;
         this.ridge = FarmPainter.PLANKS; this.ridgeData = 0;
      } else if (r < 0.76) {     // whitewashed barn, gone grey in streaks, slate-grey trim and a stone-brick roof
         this.wall = FarmPainter.TERRACOTTA; this.wallData = 0;
         this.alt = FarmPainter.TERRACOTTA; this.altData = 8;
         this.trim = FarmPainter.TERRACOTTA; this.trimData = 7;
         this.stairs = 109;
         this.ridge = FarmPainter.PLANKS; this.ridgeData = 1;
      } else if (r < 0.88) {     // brick barn: mossy where the damp sits, stone-brick quoins, a brick-tile roof
         this.wall = 45; this.wallData = 0;
         this.alt = FarmPainter.STONE_BRICK; this.altData = 1;
         this.trim = FarmPainter.STONE_BRICK; this.trimData = 0;
         this.stairs = 108;
         this.ridge = FarmPainter.PLANKS; this.ridgeData = 5;
      } else {                   // a green barn, faded toward brown, white trim, dark roof
         this.wall = FarmPainter.TERRACOTTA; this.wallData = 13;
         this.alt = FarmPainter.TERRACOTTA; this.altData = 12;
         this.trim = FarmPainter.TERRACOTTA; this.trimData = 0;
         this.stairs = 164;
         this.ridge = FarmPainter.PLANKS; this.ridgeData = 5;
      }
   }

   private int wx(int u, int v) {
      return this.axisX ? this.ox + (this.mirror ? this.L - 1 - u : u) : this.ox + v;
   }

   private int wz(int u, int v) {
      return this.axisX ? this.oz + v : this.oz + (this.mirror ? this.L - 1 - u : u);
   }

   private void put(FarmPainter p, int u, int v, int dy, int id, int data) {
      p.set(this.wx(u, v), this.h + dy, this.wz(u, v), id, data);
   }

   private double rnd(int u, int v, int dy, int salt) {
      return FarmRandom.unit(this.seed, this.wx(u, v) * 7 + dy * 1013, this.wz(u, v), salt);
   }

   /** Clusters of damage, so the roof loses whole patches rather than single blocks. */
   private boolean hole(int u, int v) {
      int x = this.wx(u, v);
      int z = this.wz(u, v);
      return FarmRandom.unit(this.seed, FarmRandom.floorDiv(x, 4), FarmRandom.floorDiv(z, 4), 1307) < this.ruin * 0.55;
   }

   private int stairToward(boolean plusV) { return this.axisX ? (plusV ? 2 : 3) : (plusV ? 0 : 1); }
   private int facePlusV()  { return this.axisX ? 3 : 5; }
   private int faceMinusV() { return this.axisX ? 2 : 4; }
   private int faceMinusU() {
      if (this.axisX) return this.mirror ? 5 : 4;
      return this.mirror ? 3 : 2;
   }

   private int[] chestCellsLoftU() { return this.big ? new int[]{this.L - 2, this.L - 2} : new int[]{this.L - 2}; }
   private int[] chestCellsLoftV() { return this.big ? new int[]{3, this.W - 4} : new int[]{this.half}; }

   private boolean loftProtected(int u, int v) {
      int[] cu = this.chestCellsLoftU();
      int[] cv = this.chestCellsLoftV();
      for (int i = 0; i < cu.length; i++) {
         if (Math.abs(u - cu[i]) <= 1 && Math.abs(v - cv[i]) <= 1) {
            return true;
         }
      }
      return false;
   }

   private boolean chestCellUnder(int u, int v) {
      return (u == this.L - 2 && (v == 1 || (this.big && v == this.W - 2))) || (u == 1 && v == this.W - 2);
   }

   /** A wall block, with the weathering that makes a barn look old: some boards sun-bleached to another colour. */
   private void wallBlock(FarmPainter p, int u, int v, int dy) {
      boolean faded = this.rnd(u, v, dy, 1302) < 0.12 + 0.2 * this.ruin;
      this.put(p, u, v, dy, faded ? this.alt : this.wall, faded ? this.altData : this.wallData);
   }

   @Override
   void paint(FarmPainter p) {
      final int L = this.L;
      final int W = this.W;
      final int T = this.T;
      final int half = this.half;
      final int halfDoor = this.dw / 2;

      // ---- floor and foundation
      for (int u = 0; u < L; u++) {
         for (int v = 0; v < W; v++) {
            boolean endWall = u == 0 || u == L - 1;
            boolean opening = endWall && Math.abs(v - half) <= halfDoor;
            boolean edge = endWall || v == 0 || v == W - 1;
            if (edge && !opening) {
               this.put(p, u, v, 0, this.rnd(u, v, 0, 1303) < 0.3 ? FarmPainter.MOSSY_COBBLE : FarmPainter.COBBLE, 0);
            } else if (this.rnd(u, v, 0, 1304) < this.ruin * 0.25 && !this.chestCellUnder(u, v)) {
               this.put(p, u, v, 0, FarmPainter.DIRT, this.rnd(u, v, 0, 1305) < 0.5 ? 1 : 0);   // floor rotted through
            } else {
               this.put(p, u, v, 0, FarmPainter.PLANKS, 1);
            }
         }
      }

      // ---- walls
      for (int u = 0; u < L; u++) {
         for (int v = 0; v < W; v++) {
            boolean endWall = u == 0 || u == L - 1;
            boolean sideWall = v == 0 || v == W - 1;
            if (!endWall && !sideWall) {
               continue;
            }
            for (int dy = 1; dy <= T; dy++) {
               if (endWall && Math.abs(v - half) <= halfDoor && dy <= this.dh) {
                  continue;                                         // the archway
               }
               boolean corner = endWall && sideWall;
               boolean post = corner || (sideWall && !endWall && u % 4 == 0)
                       || (endWall && Math.abs(v - half) == halfDoor + 1);
               boolean lintel = endWall && Math.abs(v - half) <= halfDoor + 1 && dy == this.dh + 1;
               boolean window = sideWall && !endWall && u % 4 == 2 && dy == this.dh;
               double miss;
               if (post || lintel) {
                  miss = this.ruin * 0.04 * dy;
               } else {
                  miss = this.ruin * (0.05 + 0.06 * dy);
               }
               if (dy >= 2 && this.hole(u, v)) {
                  miss = Math.max(miss, 0.75);
               }
               if (this.rnd(u, v, dy, 1306) < miss) {
                  continue;                                         // gone
               }
               if (window) {
                  if (this.rnd(u, v, dy, 1308) > 0.7) {             // most of the glass is broken
                     this.put(p, u, v, dy, FarmPainter.GLASS_PANE, 0);
                  }
               } else if (post || lintel) {
                  this.put(p, u, v, dy, this.trim, this.trimData);
               } else {
                  this.wallBlock(p, u, v, dy);
               }
            }
         }
      }

      // ---- hayloft over the back half
      for (int u = this.ladderU; u <= L - 2; u++) {
         for (int v = 1; v <= W - 2; v++) {
            if (u == this.ladderU && v == W - 2) {
               continue;                                            // the hatch the ladder climbs through
            }
            if (this.rnd(u, v, this.loftY, 1309) < this.ruin * 0.3 && !this.loftProtected(u, v)) {
               continue;                                            // fallen through
            }
            this.put(p, u, v, this.loftY, FarmPainter.PLANKS, 1);
         }
      }
      for (int dy = 1; dy <= this.loftY + 1; dy++) {
         this.put(p, this.ladderU, W - 2, dy, FarmPainter.LADDER, this.faceMinusV());
      }
      // a full block behind the top rung so the ladder has something to hold on to
      this.put(p, this.ladderU, W - 1, this.loftY + 1, this.trim, this.trimData);

      // ---- roof: stair rows step one block in per block up, starting one block OUT
      //      from the wall (the eave) and running one block past each gable (the overhang)
      for (int k = 0; k <= half; k++) {
         int dy = T + k;
         for (int u = -1; u <= L; u++) {
            boolean skipLeft = (this.hole(u, -1 + k) && this.rnd(u, -1 + k, dy, 1310) < 0.85) || this.rnd(u, -1 + k, dy, 1311) < this.ruin * 0.12;
            if (!skipLeft) {
               this.put(p, u, -1 + k, dy, this.stairs, this.stairToward(true));
            }
            boolean anchor = u == this.ladderU && W - k == W - 1 && dy == this.loftY + 1;
            boolean skipRight = (this.hole(u, W - k) && this.rnd(u, W - k, dy, 1312) < 0.85) || this.rnd(u, W - k, dy, 1313) < this.ruin * 0.12;
            if (!skipRight && !anchor) {
               this.put(p, u, W - k, dy, this.stairs, this.stairToward(false));
            }
         }
      }
      int ridgeY = T + half + 1;
      for (int u = -1; u <= L; u++) {
         if ((this.hole(u, half) && this.rnd(u, half, ridgeY, 1314) < 0.9) || this.rnd(u, half, ridgeY, 1315) < this.ruin * 0.3) {
            continue;
         }
         this.put(p, u, half, ridgeY, this.ridge, this.ridgeData);
      }

      // ---- gables
      for (int j = 1; j <= half; j++) {
         int dy = T + j;
         for (int v = j; v <= W - 1 - j; v++) {
            for (int e = 0; e < 2; e++) {
               int u = e == 0 ? 0 : L - 1;
               boolean hayDoor = this.big && e == 1 && j <= 2 && Math.abs(v - half) <= 1;
               if (hayDoor) {
                  continue;                                         // the hayloft door
               }
               if ((this.hole(u, v) && this.rnd(u, v, dy, 1316) < 0.7) || this.rnd(u, v, dy, 1317) < this.ruin * 0.35) {
                  continue;
               }
               boolean pane = (!this.big && j == 2 && v == half) || (this.big && j == 4 && v == half);
               if (pane) {
                  if (this.rnd(u, v, dy, 1318) > 0.7) {
                     this.put(p, u, v, dy, FarmPainter.GLASS_PANE, 0);
                  }
               } else {
                  this.wallBlock(p, u, v, dy);
               }
            }
         }
      }

      if (this.big) {
         this.paintCupola(p, ridgeY);
         this.paintStalls(p);
      } else {
         this.paintStdProps(p);
      }

      // ---- loft hay along the low sides
      for (int u = this.ladderU + 1; u <= L - 2; u++) {
         if (this.rnd(u, 1, this.loftY + 1, 1320) < 0.75 && !this.loftProtected(u, 1)) {
            this.put(p, u, 1, this.loftY + 1, FarmPainter.HAY, 0);
         }
         if (this.rnd(u, W - 2, this.loftY + 1, 1321) < 0.75 && !this.loftProtected(u, W - 2)) {
            this.put(p, u, W - 2, this.loftY + 1, FarmPainter.HAY, 0);
         }
      }

      // ---- cobwebs in the dark corners
      int[][] webs = {{1, 1}, {1, W - 2}, {this.ladderU - 2, 1}, {this.ladderU - 2, W - 2}};
      for (int i = 0; i < webs.length; i++) {
         if (this.rnd(webs[i][0], webs[i][1], T, 1322) < 0.4 + 0.5 * this.ruin) {
            this.put(p, webs[i][0], webs[i][1], T, FarmPainter.COBWEB, 0);
         }
      }

      // ---- vines on the outside of the side walls
      for (int u = 2; u < L - 2; u++) {
         for (int dy = 1; dy <= 3; dy++) {
            if (this.rnd(u, 0, dy, 1323) < 0.10 + 0.14 * this.ruin) {
               this.put(p, u, -1, dy, FarmPainter.VINE, this.axisX ? 1 : 8);
            }
            if (this.rnd(u, W - 1, dy, 1324) < 0.10 + 0.14 * this.ruin) {
               this.put(p, u, W, dy, FarmPainter.VINE, this.axisX ? 4 : 2);
            }
         }
      }

      // ---- loot chests (last, so nothing above overwrites them)
      p.chest(this.wx(L - 2, 1), this.h + 1, this.wz(L - 2, 1), this.facePlusV(), 1);
      p.chest(this.wx(1, W - 2), this.h + 1, this.wz(1, W - 2), this.faceMinusV(), 2);
      int[] cu = this.chestCellsLoftU();
      int[] cv = this.chestCellsLoftV();
      for (int i = 0; i < cu.length; i++) {
         p.chest(this.wx(cu[i], cv[i]), this.h + this.loftY + 1, this.wz(cu[i], cv[i]), this.faceMinusU(), 3 + i);
      }
      if (this.big) {
         p.chest(this.wx(L - 2, W - 2), this.h + 1, this.wz(L - 2, W - 2), this.faceMinusV(), 6);
      }
   }

   private void paintStdProps(FarmPainter p) {
      int W = this.W;
      int[][] hay = {{2, 1, 1}, {3, 1, 1}, {2, 1, 2}, {5, 1, 1}, {6, 1, 1}, {7, W - 2, 1}};
      for (int i = 0; i < hay.length; i++) {
         if (this.rnd(hay[i][0], hay[i][1], hay[i][2], 1330) < 0.75) {
            this.put(p, hay[i][0], hay[i][1], hay[i][2], FarmPainter.HAY, 0);
         }
      }
      this.put(p, 5, W - 2, 1, FarmPainter.CAULDRON, 0);          // dry troughs
      this.put(p, 6, W - 2, 1, FarmPainter.CAULDRON, 0);
   }

   /** Stall fences down both sides, hay in the stalls, dry troughs. */
   private void paintStalls(FarmPainter p) {
      int W = this.W;
      int[] rows = {3, W - 4};
      for (int ri = 0; ri < rows.length; ri++) {
         int v = rows[ri];
         for (int u = 3; u <= this.ladderU - 2; u++) {
            if ((u - 3) % 4 != 3 && this.rnd(u, v, 1, 1331) >= 0.1 + this.ruin * 0.35) {
               this.put(p, u, v, 1, FarmPainter.FENCE, 0);
            }
         }
         int hv = ri == 0 ? 2 : W - 3;
         for (int u = 4; u <= this.ladderU - 3; u += 4) {
            if (this.rnd(u, hv, 1, 1332) < 0.75) {
               this.put(p, u, hv, 1, FarmPainter.HAY, 0);
            }
         }
         int tv = ri == 0 ? 1 : W - 2;
         for (int u = 5; u <= this.ladderU - 2; u += 4) {
            this.put(p, u, tv, 1, FarmPainter.CAULDRON, 0);
         }
      }
   }

   /** A small vented tower on the ridge of the large barn. */
   private void paintCupola(FarmPainter p, int ridgeY) {
      int cu = this.L / 2;
      int cv = this.half;
      for (int du = -1; du <= 1; du++) {
         for (int dv = -1; dv <= 1; dv++) {
            this.put(p, cu + du, cv + dv, ridgeY, this.ridge, this.ridgeData);        // seat on the ridge
            boolean ring = du != 0 || dv != 0;
            if (ring) {
               for (int dy = 1; dy <= 2; dy++) {
                  boolean mid = (du == 0 || dv == 0) && dy == 2;
                  if (this.rnd(cu + du, cv + dv, ridgeY + dy, 1340) < this.ruin * 0.25) {
                     continue;
                  }
                  if (mid) {
                     if (this.rnd(cu + du, cv + dv, ridgeY + dy, 1341) > 0.65) {
                        this.put(p, cu + du, cv + dv, ridgeY + dy, FarmPainter.GLASS_PANE, 0);
                     }
                  } else {
                     this.put(p, cu + du, cv + dv, ridgeY + dy, this.trim, this.trimData);
                  }
               }
            }
            if (this.rnd(cu + du, cv + dv, ridgeY + 3, 1342) >= 0.15 + 0.45 * this.ruin) {
               this.put(p, cu + du, cv + dv, ridgeY + 3, FarmPainter.SLAB_WOOD, 5);
            }
         }
      }
   }
}
