package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.Random;

/** Simple trees for windbreaks, pasture shade and scrub reclaiming old fields. Painted through the clipping painter, so a tree may span chunks. */
final class FarmTrees {
   static final int OAK = 0;
   static final int SPRUCE = 1;
   static final int DEAD = 2;
   /** A fruit tree kept low and wide, gone a little wild. */
   static final int FRUIT = 3;
   /** An old, overgrown fruit tree: bigger, ragged, trailing vines. */
   static final int FRUIT_OLD = 4;

   private FarmTrees() {
   }

   static void tree(FarmPainter p, int x, int z, int h, long seed, int kind) {
      Random r = new Random(FarmRandom.hash(seed, x, z, 901));
      if (kind == SPRUCE) {
         int th = 6 + r.nextInt(3);
         for (int dy = 2; dy <= th + 1; dy++) {
            int t = th + 1 - dy;
            int rad = t == 0 ? 0 : (t <= 2 ? 1 : 2);
            for (int dx = -rad; dx <= rad; dx++) {
               for (int dz = -rad; dz <= rad; dz++) {
                  if (rad >= 1 && Math.abs(dx) == rad && Math.abs(dz) == rad) {
                     continue;
                  }
                  p.set(x + dx, h + dy, z + dz, FarmPainter.LEAVES, 5);
               }
            }
         }
         p.fill(x, h + 1, z, x, h + th, z, FarmPainter.LOG, 1);
      } else if (kind == OAK) {
         int th = 4 + r.nextInt(3);
         for (int dy = th - 2; dy <= th + 1; dy++) {
            int rad = dy <= th - 1 ? 2 : 1;
            for (int dx = -rad; dx <= rad; dx++) {
               for (int dz = -rad; dz <= rad; dz++) {
                  if (Math.abs(dx) == 2 && Math.abs(dz) == 2) {
                     continue;
                  }
                  if (dy == th + 1 && Math.abs(dx) + Math.abs(dz) > 1) {
                     continue;
                  }
                  p.set(x + dx, h + dy, z + dz, FarmPainter.LEAVES, 4);
               }
            }
         }
         p.fill(x, h + 1, z, x, h + th, z, FarmPainter.LOG, 0);
      } else if (kind == FRUIT || kind == FRUIT_OLD) {
         fruitTree(p, x, z, h, seed, kind == FRUIT_OLD, r);
      } else {                                   // dead tree: bare trunk, a snapped branch or two
         int th = 4 + r.nextInt(4);
         p.fill(x, h + 1, z, x, h + th, z, FarmPainter.LOG, 0);
         int bx = r.nextBoolean() ? 1 : -1;
         p.set(x + bx, h + th - 1, z, FarmPainter.LOG, 4);
         p.set(x, h + th - 2, z + (r.nextBoolean() ? 1 : -1), FarmPainter.LOG, 8);
         if (r.nextInt(100) < 35) {
            p.set(x + bx, h + th, z, FarmPainter.LEAVES, 4);
         }
      }
   }

   /**
    * An orchard tree: a short trunk with one crooked branch and a broad, flat-topped crown. The old kind is taller and wider, with
    * a ragged crown (leaves missing) and vines trailing from its edge. The crown is worked out first in a small grid so the vines
    * know where there is a leaf to hang from.
    */
   private static void fruitTree(FarmPainter p, int x, int z, int h, long seed, boolean old, Random r) {
      int th = (old ? 4 : 3) + r.nextInt(2);                      // trunk, bare for its lower two or three blocks
      int rad = old ? 4 : 3;
      int[] radii = old ? new int[]{3, 4, 3, 1} : new int[]{2, 3, 1};   // the crown, bottom up: a rounded dome
      int top = th + radii.length - 1;
      int size = 2 * rad + 1;
      boolean[][][] leaf = new boolean[size][top + 2][size];
      for (int dy = th; dy <= top; dy++) {
         int rr = radii[dy - th];
         for (int dx = -rr; dx <= rr; dx++) {
            for (int dz = -rr; dz <= rr; dz++) {
               int d2 = dx * dx + dz * dz;
               if (d2 > rr * rr + 1) {
                  continue;                                     // round the crown off
               }
               if (dx == 0 && dz == 0 && dy <= th) {
                  continue;                                     // the trunk
               }
               if (old && dy <= th + 2 && d2 >= (rr - 1) * (rr - 1) && r.nextInt(100) < 22) {
                  continue;                                     // ragged edge
               }
               leaf[dx + rad][dy][dz + rad] = true;
            }
         }
      }
      for (int dy = th; dy <= top; dy++) {
         for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
               if (leaf[dx + rad][dy][dz + rad]) {
                  p.set(x + dx, h + dy, z + dz, FarmPainter.LEAVES, 4);
               }
            }
         }
      }
      p.fill(x, h + 1, z, x, h + th, z, FarmPainter.LOG, 0);
      int bx = r.nextBoolean() ? 1 : -1;
      p.set(x + bx, h + th, z, FarmPainter.LOG, 4);                // a crooked limb
      if (!old) {
         return;
      }
      // vines trailing from the edge of the crown, on the side that has a leaf to hold them
      int[][] dirs = {{0, 1, 1}, {-1, 0, 2}, {0, -1, 4}, {1, 0, 8}};      // dx, dz, vine data (the face it is attached to)
      for (int dy = th; dy <= th + 1; dy++) {
         for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
               if (!leaf[dx + rad][dy][dz + rad] || r.nextInt(100) >= 28) {
                  continue;
               }
               for (int[] d : dirs) {
                  int ax = dx + d[0] + rad;
                  int az = dz + d[1] + rad;
                  boolean open = ax < 0 || az < 0 || ax >= size || az >= size || !leaf[ax][dy][az];
                  if (!open) {
                     continue;
                  }
                  // the vine hangs in the open cell beside the leaf, attached to the leaf on the side facing back at it
                  int back = d[2] == 1 ? 4 : d[2] == 4 ? 1 : d[2] == 2 ? 8 : 2;
                  int len = 1 + r.nextInt(3);
                  for (int k = 0; k < len; k++) {
                     p.set(x + dx + d[0], h + dy - k, z + dz + d[1], FarmPainter.VINE, back);
                  }
                  break;
               }
            }
         }
      }
   }
}
