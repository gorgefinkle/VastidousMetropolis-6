package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

/**
 * A round barn, the kind built in the early twentieth century so that one man could feed a whole herd from the middle: a ring wall
 * twenty-one blocks across, five high, a low conical roof of weathered shingles a block of rise to two of run, a glazed lantern on
 * top to light and air the loft, and a single wide door in the ring.
 *
 * Inside, a stack of old hay in the middle, a ring of stall partitions round it, and a tack cabinet on the far wall. Derelict, not
 * collapsed: the shingles come off in patches, the odd board is gone, creeper has taken the shady side.
 *
 * It takes the same box in the farmstead as a gabled barn (SIZE x SIZE footprint, one block of eave round it) and the door looks
 * toward the road, so the driveway code treats it like any other barn. Everything is a pure function of the seed and the position.
 */
final class FarmRoundBarn extends FarmFeature {
   /** Footprint (the ring wall's diameter). */
   static final int SIZE = 21;
   /** Width of the door in the ring. */
   static final int DOOR_W = 5;

   private static final int R = (SIZE - 1) / 2;       // radius of the wall ring
   private static final int T = 5;                    // wall height

   private final int cx;
   private final int cz;
   private final int facing;                          // the side the door is on: 0 west, 1 east, 2 north, 3 south
   private final int groundY;
   private final long seed;
   private final double ruin;
   private final int wallId;
   private final int wallData;
   private final int altData;

   FarmRoundBarn(int minX, int minZ, int facing, int groundY, long seed) {
      super(minX - 2, minZ - 2, minX + SIZE + 1, minZ + SIZE + 1);
      this.cx = minX + R;
      this.cz = minZ + R;
      this.facing = facing;
      this.groundY = groundY;
      this.seed = seed;
      double base = PluginConfig.getFarmRuinPercent() / 100.0;
      this.ruin = Math.max(0.0, Math.min(0.95, base * (0.6 + 0.8 * FarmRandom.unit(seed, minX, minZ, 1800))));
      double r = FarmRandom.unit(seed, minX, minZ, 1801);
      if (r < 0.55) {
         this.wallId = FarmPainter.TERRACOTTA;       // barn red, faded
         this.wallData = 14;
         this.altData = 12;
      } else if (r < 0.80) {
         this.wallId = FarmPainter.TERRACOTTA;       // whitewash gone grey
         this.wallData = 0;
         this.altData = 8;
      } else {
         this.wallId = FarmPainter.PLANKS;           // bare weathered boards
         this.wallData = 1;
         this.altData = 5;
      }
   }

   private double rnd(int x, int y, int z, int salt) {
      return FarmRandom.unit(this.seed, x * 31 + y * 1013, z, salt);
   }

   private boolean hole(int x, int z, double share, int salt) {
      return FarmRandom.unit(this.seed, Math.floorDiv(x, 4), Math.floorDiv(z, 4), salt) < this.ruin * share;
   }

   /** The roof piece at k rings in from the eave: full blocks on even rings at wallTop + k/2, bottom slabs on odd ones one cell higher. */
   private static int roofDy(int k) {
      return k % 2 == 0 ? T + k / 2 : T + (k + 1) / 2;
   }

   /** Cardinal direction of a cell on the ring: 0 west, 1 east, 2 north, 3 south, by the larger offset. */
   private static int dirOf(int du, int dv) {
      return Math.abs(du) >= Math.abs(dv) ? (du < 0 ? 0 : 1) : (dv < 0 ? 2 : 3);
   }

   /** Distance along the wall from the middle of the door, for a ring cell on the door's side. */
   private int doorOffset(int du, int dv) {
      return this.facing < 2 ? Math.abs(dv) : Math.abs(du);
   }

   @Override
   void paint(FarmPainter p) {
      int h = this.groundY;
      for (int dz = -R - 1; dz <= R + 1; dz++) {
         for (int dx = -R - 1; dx <= R + 1; dx++) {
            int x = this.cx + dx;
            int z = this.cz + dz;
            if (!p.in(x, z)) {
               continue;
            }
            int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
            if (dist > R + 1) {
               continue;
            }
            if (dist <= R) {
               this.floor(p, x, z, dx, dz, dist, h);
            }
            if (dist == R) {
               this.wall(p, x, z, dx, dz, h);
            }
            this.roof(p, x, z, dist, dx, dz, h);
         }
      }
      this.fittings(p, h);
   }

   private void floor(FarmPainter p, int x, int z, int dx, int dz, int dist, int h) {
      double f = this.rnd(x, 0, z, 1810);
      p.set(x, h, z, f < 0.55 ? FarmPainter.PLANKS : FarmPainter.DIRT, f < 0.55 ? 1 : 1);
      if (f > 0.93 && !this.nearChest(x, z)) {
         p.set(x, h + 1, z, FarmPainter.HAY, 0);                      // loose hay, never in front of a chest
      }
      // a ring of stall partitions round the middle, a gap here and there
      if (dist == R - 3 && (Math.abs(dx) + 2 * Math.abs(dz)) % 3 == 0 && this.rnd(x, 1, z, 1811) >= 0.15 && !this.inDoorLane(dx, dz)) {
         p.set(x, h + 1, z, FarmPainter.FENCE, 0);
      }
      // the hay in the middle
      if (dist <= 2) {
         int heap = 3 - dist;
         for (int dy = 1; dy <= heap; dy++) {
            if (this.rnd(x, dy, z, 1812) < 0.9) {
               p.set(x, h + dy, z, FarmPainter.HAY, (dx + dz + dy) % 3 == 0 ? 4 : 0);
            }
         }
      }
   }

   /** The two chest cells (see fittings()), for keeping their fronts clear. */
   private int[][] chestCells() {
      int[] d = this.doorVec();
      return new int[][]{
         {this.cx - d[0] * (R - 1), this.cz - d[1] * (R - 1)},
         {this.cx + (d[0] == 0 ? 4 : 0), this.cz + (d[1] == 0 ? 4 : 0)}};
   }

   private boolean nearChest(int x, int z) {
      for (int[] c : this.chestCells()) {
         if (Math.abs(x - c[0]) <= 1 && Math.abs(z - c[1]) <= 1) {
            return true;
         }
      }
      return false;
   }

   private boolean inDoorLane(int dx, int dz) {
      int[] d = this.doorVec();
      boolean onSide = d[0] != 0 ? (dx * d[0] > 0) : (dz * d[1] > 0);
      return onSide && this.doorOffset(dx, dz) <= 2;
   }

   private int[] doorVec() {
      switch (this.facing) {
         case 0:  return new int[]{-1, 0};
         case 1:  return new int[]{1, 0};
         case 2:  return new int[]{0, -1};
         default: return new int[]{0, 1};
      }
   }

   private void wall(FarmPainter p, int x, int z, int dx, int dz, int h) {
      boolean[] holds = new boolean[T + 1];            // where a full wall block stands, which is all a vine can cling to
      boolean doorSide = dirOf(dx, dz) == this.facing;
      boolean door = doorSide && this.doorOffset(dx, dz) <= DOOR_W / 2;
      boolean jamb = doorSide && this.doorOffset(dx, dz) == DOOR_W / 2 + 1;
      for (int dy = 1; dy <= T; dy++) {
         if (door && dy <= 4) {
            continue;                                                    // the doorway, four high, with its door long gone
         }
         if (this.rnd(x, dy, z, 1820) < 0.035 && dy >= 2 && !jamb) {
            continue;                                                    // a board kicked out
         }
         if (dy == 3 && (dx + dz + 64) % 4 == 0 && !door) {
            if (this.rnd(x, dy, z, 1821) < 0.35) {
               continue;                                                 // a window with the glass gone
            }
            p.set(x, h + dy, z, FarmPainter.GLASS_PANE, 0);
            continue;
         }
         boolean trim = jamb || dy == 1 || (door && dy == 5);
         int data = trim ? (this.wallId == FarmPainter.PLANKS ? 5 : 7) : (this.rnd(x, dy, z, 1822) < 0.25 ? this.altData : this.wallData);
         p.set(x, h + dy, z, trim && this.wallId != FarmPainter.PLANKS ? FarmPainter.TERRACOTTA : this.wallId, data);
         holds[dy] = true;
      }
      // creeper on the outside, thickest on the north side where the sun is least
      if (this.rnd(x, 7, z, 1823) < (dz < 0 ? 0.35 : 0.14)) {
         int ox = x + (dx == 0 ? 0 : (dx > 0 ? 1 : -1));
         int oz = z + (dx == 0 ? (dz > 0 ? 1 : -1) : 0);
         int len = 2 + (int) (this.rnd(x, 8, z, 1824) * 3);
         int data = dx != 0 ? (dx > 0 ? 2 : 8) : (dz > 0 ? 4 : 1);       // the face the vine clings to is the wall's, back at it
         for (int dy = 1; dy <= len; dy++) {
            if (dy <= T && holds[dy]) {
               p.set(ox, h + dy, oz, FarmPainter.VINE, data);
            }
         }
      }
   }

   private void roof(FarmPainter p, int x, int z, int dist, int dx, int dz, int h) {
      int k = R + 1 - dist;                        // 0 at the eave ring, rising toward the middle
      if (k < 0) {
         return;
      }
      boolean lanternCol = dist <= 1;
      int dy = roofDy(k);
      boolean slab = k % 2 == 1;
      if (lanternCol) {
         // the lantern: a glazed turret three blocks high on the apex, a slab cap
         for (int ly = dy; ly <= dy + 2; ly++) {
            boolean edge = dist == 1;
            if (edge) {
               p.set(x, h + ly, z, ly == dy + 1 && this.rnd(x, ly, z, 1830) > 0.3 ? FarmPainter.GLASS_PANE : FarmPainter.PLANKS, 1);
            }
         }
         p.set(x, h + dy + 3, z, FarmPainter.SLAB_WOOD, 5);
         return;
      }
      if (this.hole(x, z, 0.5, 1831) || this.rnd(x, 0, z, 1832) < 0.02) {
         return;                                    // shingles gone in a patch
      }
      p.set(x, h + dy, z, slab ? FarmPainter.SLAB_WOOD : FarmPainter.PLANKS, 5);
   }

   /** The tack cabinet on the far wall, and a chest of feed in the hay. */
   private void fittings(FarmPainter p, int h) {
      int[] d = this.doorVec();
      int tx = this.cx - d[0] * (R - 1);
      int tz = this.cz - d[1] * (R - 1);
      int toward = d[0] != 0 ? (d[0] > 0 ? 5 : 4) : (d[1] > 0 ? 3 : 2);        // the lid looks toward the door
      p.chest(tx, h + 1, tz, toward, 61, FarmPainter.TIER_MID);                  // harness and tack: something worth keeping
      // spilt feed: on the floor beside the hay, to the side of the door axis, its lid toward the middle
      int sx = this.cx + (d[0] == 0 ? 4 : 0);
      int sz = this.cz + (d[1] == 0 ? 4 : 0);
      p.chest(sx, h + 1, sz, d[0] == 0 ? 4 : 2, 62, FarmPainter.TIER_LOW);
   }
}
