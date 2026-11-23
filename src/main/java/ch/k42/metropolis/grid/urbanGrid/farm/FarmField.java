package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * An abandoned field on the edge of a dead city. Nothing here is tidy:
 *
 *  - CROP: the ghost of planted rows. Furrows show as strips of bare dirt in
 *    the weeds, with dead crops, dead bushes and the odd uncut stalk still
 *    standing where the rows were.
 *  - FALLOW: gone to weeds and scrub, bare patches, saplings, a few trees
 *    already taking it back.
 *  - HAY: a rank meadow with old round bales left to rot where they dropped.
 *  - PASTURE: a sagging fence, empty troughs, a half-collapsed shelter, a
 *    stagnant pond and shade trees. No animals.
 *  - ORCHARD: rows of fruit trees nobody has pruned in years. The grid is still
 *    there but ragged: trees missing, trees dead, the old ones huge and hung with
 *    vines, saplings and ferns between the rows, a fallen trunk or two.
 *
 * Scarecrows stand over the fields. The fence (when there is one) is broken
 * up: whole sections gone, posts missing, the gate missing or hanging open.
 * There is no path around a field; it just ends at its fence.
 *
 * Everything about the field is decided in the constructor from its position,
 * so every chunk that draws a piece of it agrees.
 */
final class FarmField extends FarmFeature {
   enum Kind { CROP, FALLOW, HAY, PASTURE, ORCHARD }

   private static final int[][] FENCES = {
           {FarmPainter.FENCE, FarmPainter.FENCE_GATE},   // oak
           {188, 183},                                     // spruce
           {191, 186}                                      // dark oak
   };

   private final int fx0, fz0, fx1, fz1;   // the rectangle (fence outline), inclusive
   private final int h;
   private final Kind kind;
   private final long seed;
   private final boolean fenced;
   private final double decay;              // 0 barely touched .. 1 wrecked
   private final int fence, gate;
   private final boolean rowsAlongX;
   private final int cropId;
   private final List<int[]> gates = new ArrayList<int[]>();        // x, z, data, present (1/0)
   private final List<int[]> scarecrows = new ArrayList<int[]>();   // x, z, variant
   private final List<int[]> trees = new ArrayList<int[]>();        // x, z, kind
   private final List<int[]> bales = new ArrayList<int[]>();        // x, z, stack (1 or 2)
   private final List<int[]> shelters = new ArrayList<int[]>();     // x, z (min corner of a 5 x 4 shelter)
   private final List<int[]> ponds = new ArrayList<int[]>();        // x, z (centre), scale (1..3)
   private final List<int[]> logs = new ArrayList<int[]>();         // x, z, length, 1 = lies along x (a fallen orchard tree)

   FarmField(int fx0, int fz0, int fx1, int fz1, int h, Kind kind, long seed, boolean fenced) {
      super(fx0 - 1, fz0 - 1, fx1 + 1, fz1 + 1);
      this.fx0 = fx0;
      this.fz0 = fz0;
      this.fx1 = fx1;
      this.fz1 = fz1;
      this.h = h;
      this.kind = kind;
      this.seed = seed;
      this.fenced = fenced || kind == Kind.PASTURE;
      Random r = new Random(FarmRandom.hash(seed, fx0, fz0, 301));
      int w = fx1 - fx0 + 1;
      int d = fz1 - fz0 + 1;

      double ruin = PluginConfig.getFarmRuinPercent() / 100.0;
      this.decay = Math.max(0.0, Math.min(1.0, ruin * (0.65 + 0.7 * r.nextDouble())));

      int[] style = FENCES[r.nextInt(FENCES.length)];
      this.fence = style[0];
      this.gate = style[1];
      // gates: one per ~220 blocks of fence, on random sides, away from the corners
      int gateCount = Math.max(1, Math.min(4, (2 * (w + d)) / 220));
      for (int i = 0; i < gateCount; i++) {
         int side = r.nextInt(4);
         int gx, gz, gd;
         if (side == 0 || side == 1) {
            gx = fx0 + 4 + r.nextInt(Math.max(1, w - 8));
            gz = side == 0 ? fz0 : fz1;
            gd = side == 0 ? 2 : 0;
         } else {
            gx = side == 2 ? fx0 : fx1;
            gz = fz0 + 4 + r.nextInt(Math.max(1, d - 8));
            gd = side == 2 ? 1 : 3;
         }
         boolean present = r.nextDouble() > 0.25 + 0.4 * this.decay;
         boolean open = r.nextDouble() < 0.6;
         this.gates.add(new int[]{gx, gz, gd + (open ? 4 : 0), present ? 1 : 0});
      }

      this.rowsAlongX = r.nextBoolean();
      int[] crops = {FarmPainter.WHEAT, FarmPainter.WHEAT, FarmPainter.POTATOES, FarmPainter.CARROTS};
      this.cropId = crops[r.nextInt(crops.length)];

      int area = w * d;

      // ponds and shelters first, so trees and bales can keep out of them
      if (kind == Kind.PASTURE) {
         int pondCount = Math.min(4, 1 + area / 14000);
         int scale = 1 + Math.min(2, (int) (Math.sqrt(area) / 80));          // bigger pastures, bigger ponds
         for (int i = 0; i < pondCount && w >= 24 && d >= 24; i++) {
            int margin = 4 * scale + 5;
            for (int tries = 0; tries < 12; tries++) {
               int px = fx0 + margin + r.nextInt(Math.max(1, w - 2 * margin));
               int pz = fz0 + margin + r.nextInt(Math.max(1, d - 2 * margin));
               if (this.free(px, pz, 8 * scale)) {
                  this.ponds.add(new int[]{px, pz, scale});
                  break;
               }
            }
         }
         int shelterCount = Math.min(3, 1 + area / 18000);
         for (int i = 0; i < shelterCount && w >= 24 && d >= 24; i++) {
            for (int tries = 0; tries < 12; tries++) {
               int sx = fx0 + 6 + r.nextInt(Math.max(1, w - 12 - 5));
               int sz = fz0 + 6 + r.nextInt(Math.max(1, d - 12 - 4));
               if (this.free(sx, sz, 6) && this.free(sx + 4, sz + 3, 6)) {
                  this.shelters.add(new int[]{sx, sz});
                  break;
               }
            }
         }
      }

      // scarecrows: one per ~5000 blocks of field, up to fourteen (an orchard has trees, not rows to guard)
      if (kind != Kind.PASTURE && kind != Kind.ORCHARD) {
         int n = Math.max(1, Math.min(14, area / 5000));
         for (int i = 0; i < n; i++) {
            int sx = fx0 + 6 + r.nextInt(Math.max(1, w - 12));
            int sz = fz0 + 6 + r.nextInt(Math.max(1, d - 12));
            this.scarecrows.add(new int[]{sx, sz, r.nextInt(8)});
         }
      }

      // trees taking the field back
      int treeCount;
      if (kind == Kind.PASTURE) {
         treeCount = 3 + area / 2200;
      } else if (kind == Kind.FALLOW) {
         treeCount = area / 1800;
      } else if (kind == Kind.HAY) {
         treeCount = area / 5000;
      } else {
         treeCount = 0;
      }
      treeCount = Math.min(treeCount, 28);
      for (int i = 0; i < treeCount; i++) {
         int tx = fx0 + 6 + r.nextInt(Math.max(1, w - 12));
         int tz = fz0 + 6 + r.nextInt(Math.max(1, d - 12));
         int tk = r.nextInt(100) < 35 ? FarmTrees.DEAD : FarmTrees.OAK;
         if (this.free(tx, tz, 3)) {
            this.trees.add(new int[]{tx, tz, tk});
         }
      }

      if (kind == Kind.ORCHARD) {
         this.planOrchard();
      }

      // round bales left where they dropped
      if (kind == Kind.HAY) {
         for (int gx = fx0 + 5; gx < fx1 - 5; gx += 20) {
            for (int gz = fz0 + 5; gz < fz1 - 5; gz += 20) {
               if (r.nextInt(100) < 45) {
                  int bx = gx + r.nextInt(14);
                  int bz = gz + r.nextInt(14);
                  if (bx + 1 < fx1 - 1 && bz + 1 < fz1 - 1 && this.free(bx, bz, 2)) {
                     this.bales.add(new int[]{bx, bz, r.nextInt(100) < 25 ? 2 : 1});
                  }
               }
            }
         }
      }
   }

   /**
    * The old planting grid, kept but ragged. Rows run along the field's row axis, nine to eleven blocks apart, with a tree every 7 or 8
    * blocks of row, each knocked a block or so off its mark. About a sixth of the places are empty, a few trees are dead and a fifth
    * are old giants. Its own random stream, so it never disturbs what the rest of the plan draws.
    */
   private void planOrchard() {
      Random o = new Random(FarmRandom.hash(this.seed, this.fx0, this.fz0, 311));
      int rowGap = 9 + o.nextInt(3);                  // wide enough that the lanes between rows still show through the crowns
      int treeGap = 7 + o.nextInt(2);
      int margin = 5;
      boolean alongX = this.rowsAlongX;
      int aFrom = (alongX ? this.fz0 : this.fx0) + margin;
      int aTo = (alongX ? this.fz1 : this.fx1) - margin;
      int bFrom = (alongX ? this.fx0 : this.fz0) + margin;
      int bTo = (alongX ? this.fx1 : this.fz1) - margin;
      for (int a = aFrom; a <= aTo; a += rowGap) {
         for (int b = bFrom; b <= bTo; b += treeGap) {
            int tx = (alongX ? b : a) + o.nextInt(3) - 1;
            int tz = (alongX ? a : b) + o.nextInt(3) - 1;
            double roll = o.nextDouble();
            if (roll < 0.17) {
               continue;                                          // a tree that died and rotted away
            }
            int tk = roll < 0.30 ? FarmTrees.DEAD : (roll < 0.52 ? FarmTrees.FRUIT_OLD : FarmTrees.FRUIT);
            this.trees.add(new int[]{tx, tz, tk});
         }
      }
      int fallen = 1 + (this.fx1 - this.fx0 + 1) * (this.fz1 - this.fz0 + 1) / 9000;
      for (int i = 0; i < Math.min(fallen, 6); i++) {
         int lx = this.fx0 + 8 + o.nextInt(Math.max(1, this.fx1 - this.fx0 - 16));
         int lz = this.fz0 + 8 + o.nextInt(Math.max(1, this.fz1 - this.fz0 - 16));
         this.logs.add(new int[]{lx, lz, 4 + o.nextInt(3), o.nextBoolean() ? 1 : 0});
      }
   }

   /** True when (x, z) is clear of every pond and shelter placed so far by at least pad blocks. */
   private boolean free(int x, int z, int pad) {
      for (int[] sh : this.shelters) {
         if (x >= sh[0] - pad && x <= sh[0] + 4 + pad && z >= sh[1] - pad && z <= sh[1] + 3 + pad) {
            return false;
         }
      }
      for (int[] pd : this.ponds) {
         int dx = x - pd[0];
         int dz = z - pd[1];
         double m2 = pd[2] * pd[2];
         if ((dx * dx) / (12.0 * m2) + (dz * dz) / (6.0 * m2) <= 1.0 + pad * 0.1) {
            return false;
         }
      }
      return true;
   }

   @Override
   void paint(FarmPainter p) {
      this.paintGround(p);
      if (this.fenced) {
         this.paintFence(p);
      }
      if (this.kind == Kind.PASTURE) {
         this.paintPastureProps(p);
      }
      for (int[] b : this.bales) {
         if (p.intersects(b[0], b[1], b[0] + 1, b[1] + 1)) {
            this.paintBale(p, b[0], b[1], b[2]);
         }
      }
      for (int[] l : this.logs) {
         boolean ax = l[3] == 1;
         if (p.intersects(l[0], l[1], l[0] + (ax ? l[2] : 0), l[1] + (ax ? 0 : l[2]))) {
            for (int k = 0; k < l[2]; k++) {
               p.set(l[0] + (ax ? k : 0), this.h + 1, l[1] + (ax ? 0 : k), FarmPainter.LOG, ax ? 4 : 8);   // a trunk lying on its side
            }
         }
      }
      for (int[] t : this.trees) {
         if (p.intersects(t[0] - 5, t[1] - 5, t[0] + 5, t[1] + 5)) {
            FarmTrees.tree(p, t[0], t[1], this.h, this.seed, t[2]);
         }
      }
      for (int[] s : this.scarecrows) {
         if (p.intersects(s[0] - 1, s[1] - 1, s[0] + 1, s[1] + 1)) {
            this.paintScarecrow(p, s[0], s[1], s[2]);
         }
      }
   }

   // ----------------------------------------------------------------- ground

   private void paintGround(FarmPainter p) {
      int x0 = Math.max(this.fx0 + 1, p.minX);
      int x1 = Math.min(this.fx1 - 1, p.maxX);
      int z0 = Math.max(this.fz0 + 1, p.minZ);
      int z1 = Math.min(this.fz1 - 1, p.maxZ);
      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            switch (this.kind) {
               case CROP:    this.cropColumn(p, x, z); break;
               case FALLOW:  this.fallowColumn(p, x, z); break;
               case HAY:     this.meadowColumn(p, x, z, 0.34); break;
               case ORCHARD: this.orchardColumn(p, x, z); break;
               default:      this.meadowColumn(p, x, z, 0.16); break;
            }
         }
      }
   }

   /** Furrows still show as bare strips; between them the weeds have taken over. */
   private void cropColumn(FarmPainter p, int x, int z) {
      int idx = this.rowsAlongX ? z - this.fz0 : x - this.fx0;
      boolean furrow = (idx & 1) == 0;
      double n = FarmRandom.unit(this.seed, x, z, 1202);
      double c = FarmRandom.unit(this.seed, x, z, 1204);
      double patch = FarmRandom.unit(this.seed, x >> 2, z >> 2, 1203);
      int h = this.h;
      if (furrow) {
         boolean standing = patch > this.decay * 0.9;
         if (standing && c < 0.40) {
            int max = 7;
            int age = 2 + (int) (FarmRandom.unit(this.seed, x, z, 1205) * (max - 1));
            p.set(x, h, z, FarmPainter.FARMLAND, (int) (n * 8));
            p.set(x, h + 1, z, this.cropId, Math.min(max, age));
         } else if (n < 0.78) {
            p.set(x, h, z, FarmPainter.DIRT, 0);
            if (c < 0.12) {
               p.set(x, h + 1, z, FarmPainter.DEAD_BUSH, 0);
            } else if (c < 0.22) {
               p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 1);
            }
         } else if (c < 0.30) {
            p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 1);
         }
      } else {
         if (n < 0.14) {
            p.set(x, h, z, FarmPainter.DIRT, 1);
         } else if (c < 0.34) {
            p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 1);
         } else if (c < 0.40) {
            p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 2);
         }
      }
   }

   private void fallowColumn(FarmPainter p, int x, int z) {
      double patch = FarmRandom.unit(this.seed, x / 5, z / 5, 1210);
      double n = FarmRandom.unit(this.seed, x, z, 1211);
      double c = FarmRandom.unit(this.seed, x, z, 1212);
      int h = this.h;
      if (patch < 0.20 + 0.15 * this.decay) {                 // bare, eroded patch
         if (n < 0.55) {
            p.set(x, h, z, FarmPainter.DIRT, n < 0.30 ? 0 : 1);
         } else if (n < 0.62) {
            p.set(x, h, z, FarmPainter.GRAVEL, 0);
         }
         if (c < 0.06) {
            p.set(x, h + 1, z, FarmPainter.DEAD_BUSH, 0);
         }
      } else {
         if (c < 0.30) {
            p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 1);
         } else if (c < 0.37) {
            p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 2);
         } else if (c < 0.375) {
            p.set(x, h + 1, z, FarmPainter.SAPLING, 0);
         } else if (c < 0.385) {
            p.set(x, h + 1, z, FarmPainter.LEAVES, 4);          // a bush of scrub
            if (n < 0.4) {
               p.set(x, h + 2, z, FarmPainter.LEAVES, 4);
            }
         }
      }
   }

   /** Rank grass and fern under the trees, seedlings coming up where the fruit fell, the odd flower, bare patches of coarse dirt. */
   private void orchardColumn(FarmPainter p, int x, int z) {
      double c = FarmRandom.unit(this.seed, x, z, 1250);
      double n = FarmRandom.unit(this.seed, x, z, 1251);
      int h = this.h;
      if (c < 0.36) {
         p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 1);
      } else if (c < 0.46) {
         p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 2);
      } else if (c < 0.47) {
         p.set(x, h + 1, z, FarmPainter.SAPLING, 0);
      } else if (c < 0.48) {
         p.set(x, h + 1, z, n < 0.5 ? FarmPainter.DANDELION : FarmPainter.RED_ROSE, n < 0.5 ? 0 : (int) (n * 16) % 9);
      } else if (c < 0.49) {
         p.set(x, h + 1, z, FarmPainter.LEAVES, 4);          // a bramble
      } else if (n < 0.03) {
         p.set(x, h, z, FarmPainter.DIRT, 1);
      }
   }

   private void meadowColumn(FarmPainter p, int x, int z, double tall) {
      double c = FarmRandom.unit(this.seed, x, z, 1220);
      double n = FarmRandom.unit(this.seed, x, z, 1221);
      int h = this.h;
      if (c < tall) {
         p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 1);
      } else if (c < tall + 0.05) {
         p.set(x, h + 1, z, FarmPainter.TALL_GRASS, 2);
      } else if (n < 0.012) {
         p.set(x, h, z, FarmPainter.DIRT, 1);
      }
   }

   // ------------------------------------------------------------------ fence

   private boolean postStands(int x, int z, boolean alongX) {
      int seg = (alongX ? x : z) >> 3;
      double segRoll = FarmRandom.unit(this.seed, seg, alongX ? z : x, 1401);
      if (segRoll < 0.08 + 0.34 * this.decay) {
         return false;                                         // a whole stretch has fallen
      }
      return FarmRandom.unit(this.seed, x, z, 1402) >= 0.04 + 0.22 * this.decay;
   }

   /** R30cg reference: fence blocks and gates are written first; their faces
    * are authored from the planned field perimeter before this paint returns.
    * No chunk-load fence repair and no dependency on neighbour chunk order. */
   private void paintFence(FarmPainter p) {
      int h = this.h;
      for (int x = Math.max(this.fx0, p.minX); x <= Math.min(this.fx1, p.maxX); x++) {
         if (this.postStands(x, this.fz0, true)) p.set(x, h + 1, this.fz0, this.fence, 0);
         if (this.postStands(x, this.fz1, true)) p.set(x, h + 1, this.fz1, this.fence, 0);
      }
      for (int z = Math.max(this.fz0, p.minZ); z <= Math.min(this.fz1, p.maxZ); z++) {
         if (this.postStands(this.fx0, z, false)) p.set(this.fx0, h + 1, z, this.fence, 0);
         if (this.postStands(this.fx1, z, false)) p.set(this.fx1, h + 1, z, this.fence, 0);
      }
      for (int[] g : this.gates) {
         if (g[3] == 1) p.set(g[0], h + 1, g[1], this.gate, g[2]);
         else p.set(g[0], h + 1, g[1], FarmPainter.AIR, 0);
      }
      // Exact original ownership: fence faces finalized here, not by FarmPainter
      // chunk-load or full-world reconciliation. Both sides independently know
      // the same field plan, even when a fence crosses a chunk boundary.
      for (int x = Math.max(this.fx0, p.minX); x <= Math.min(this.fx1, p.maxX); x++) {
         this.connectFence(p, x, this.fz0);
         this.connectFence(p, x, this.fz1);
      }
      for (int z = Math.max(this.fz0, p.minZ); z <= Math.min(this.fz1, p.maxZ); z++) {
         this.connectFence(p, this.fx0, z);
         this.connectFence(p, this.fx1, z);
      }
   }

   /** The R30cg reference checks whether a fence or surviving gate is planned
    * here; its stored gate facing does not decide this topology query. */
   private boolean edgeExists(int x, int z) {
      if (x < fx0 || x > fx1 || z < fz0 || z > fz1) return false;
      if (x != fx0 && x != fx1 && z != fz0 && z != fz1) return false;
      for (int[] g : this.gates) {
         if (g[0] == x && g[1] == z) return g[3] == 1;
      }
      boolean alongX = (z == fz0 || z == fz1) && postStands(x, z, true);
      boolean alongZ = (x == fx0 || x == fx1) && postStands(x, z, false);
      return alongX || alongZ;
   }

   private void connectFence(FarmPainter p, int x, int z) {
      if (!p.in(x, z) || !edgeExists(x, z)) return;
      Block block = p.chunk.getBlock(x & 15, this.h + 1, z & 15);
      BlockData state = block.getBlockData();
      if (!(state instanceof MultipleFacing facing)) return; // gates are directional, not fences
      for (BlockFace side : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
         if (facing.getAllowedFaces().contains(side))
            facing.setFace(side, edgeExists(x + side.getModX(), z + side.getModZ()));
      }
      block.setBlockData(facing, false);
   }

   // ------------------------------------------------------------ pasture props

   private void paintPastureProps(FarmPainter p) {
      int h = this.h;
      for (int[] pd : this.ponds) {
         int m = pd[2];
         if (!p.intersects(pd[0] - 4 * m, pd[1] - 3 * m, pd[0] + 4 * m, pd[1] + 3 * m)) {
            continue;
         }
         for (int dx = -4 * m; dx <= 4 * m; dx++) {
            for (int dz = -3 * m; dz <= 3 * m; dz++) {
               double e = (dx * dx) / (12.0 * m * m) + (dz * dz) / (6.0 * m * m);
               if (e <= 1.0) {
                  int x = pd[0] + dx;
                  int z = pd[1] + dz;
                  p.set(x, h, z, FarmPainter.WATER, 0);
                  p.set(x, h - 1, z, FarmPainter.DIRT, 0);
                  p.set(x, h + 1, z, FarmRandom.unit(this.seed, x, z, 1231) < 0.30 ? FarmPainter.LILY_PAD : FarmPainter.AIR, 0);
               }
            }
         }
      }
      for (int[] sh : this.shelters) {
         if (!p.intersects(sh[0], sh[1], sh[0] + 4, sh[1] + 3)) {
            continue;
         }
         int sx = sh[0];
         int sz = sh[1];
         int[][] posts = {{0, 0}, {4, 0}, {0, 3}, {4, 3}};
         for (int i = 0; i < posts.length; i++) {
            int x = sx + posts[i][0];
            int z = sz + posts[i][1];
            if (FarmRandom.unit(this.seed, x, z, 1232) >= 0.20 + 0.25 * this.decay) {
               int top = 3 - (FarmRandom.unit(this.seed, x, z, 1233) < 0.4 * this.decay ? 1 : 0);
               p.fill(x, h + 1, z, x, h + top, z, this.fence, 0);
            }
         }
         for (int x = sx; x <= sx + 4; x++) {
            for (int z = sz; z <= sz + 3; z++) {
               if (FarmRandom.unit(this.seed, x, z, 1234) >= 0.15 + 0.5 * this.decay) {
                  p.set(x, h + 4, z, FarmPainter.SLAB_WOOD, 0);
               }
            }
         }
         for (int x = sx + 1; x <= sx + 3; x++) {
            if (FarmRandom.unit(this.seed, x, sz, 1235) < 0.6) {
               p.set(x, h + 1, sz, FarmPainter.HAY, 0);
            }
         }
         p.set(sx + 1, h + 1, sz + 3, FarmPainter.CAULDRON, 0);    // empty trough
         p.set(sx + 3, h + 1, sz + 3, FarmPainter.CAULDRON, FarmRandom.unit(this.seed, sx, sz, 1236) < 0.3 ? 1 : 0);
      }
   }

   private void paintBale(FarmPainter p, int x, int z, int stack) {
      int h = this.h;
      for (int dx = 0; dx <= 1; dx++) {
         for (int dz = 0; dz <= 1; dz++) {
            if (FarmRandom.unit(this.seed, x + dx, z + dz, 1241) < 0.15 + 0.25 * this.decay) {
               continue;                                          // rotted away
            }
            p.set(x + dx, h + 1, z + dz, FarmPainter.HAY, 0);
            if (stack == 2 && FarmRandom.unit(this.seed, x + dx, z + dz, 1242) < 0.7) {
               p.set(x + dx, h + 2, z + dz, FarmPainter.HAY, 0);
            }
         }
      }
   }

   // -------------------------------------------------------------- scarecrows

   /** A scarecrow on a post. Variants lose an arm or the head; some are leaning on their last support. */
   private void paintScarecrow(FarmPainter p, int x, int z, int variant) {
      int h = this.h;
      p.set(x, h + 1, z, FarmPainter.FENCE, 0);
      p.set(x, h + 2, z, FarmPainter.HAY, 0);
      if ((variant & 1) == 0) {
         p.set(x - 1, h + 2, z, FarmPainter.FENCE, 0);
      }
      if ((variant & 2) == 0) {
         p.set(x + 1, h + 2, z, FarmPainter.FENCE, 0);
      }
      if ((variant & 4) == 0) {
         p.set(x, h + 3, z, FarmPainter.PUMPKIN, (variant + x) & 3);
      }
   }
}
