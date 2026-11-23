package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

/**
 * A derelict round grain silo, 7 wide, with an arched door, a ladder to an
 * inner platform, a domed top with a lightning rod, and two loot chests (one
 * on the floor, one on the platform). Cracked and mossy stonework, a broken
 * top and a holed dome; the ladder, platform around the chest and chest cells
 * are always kept so the loot stays reachable.
 *
 * The ladder and chests sit on the west and east walls, so the door (on a
 * random side) never blocks them.
 */
final class FarmSilo extends FarmFeature {
   private final int cx, cz, h, height, doorDir;
   private final long seed;
   private final double ruin;
   private final boolean stone;
   private final int wall, band, slabId, slabData;

   FarmSilo(int cx, int cz, int h, long seed) {
      super(cx - 3, cz - 3, cx + 3, cz + 3);
      this.cx = cx;
      this.cz = cz;
      this.h = h;
      this.seed = seed;
      this.height = 12 + (int) (FarmRandom.unit(seed, cx, cz, 411) * 4);
      this.doorDir = (int) (FarmRandom.unit(seed, cx, cz, 412) * 4);
      this.ruin = Math.max(0.0, Math.min(0.95, PluginConfig.getFarmRuinPercent() / 100.0 * (0.55 + 0.9 * FarmRandom.unit(seed, cx, cz, 414))));
      this.stone = FarmRandom.unit(seed, cx, cz, 413) < 0.6;
      if (this.stone) {
         this.wall = FarmPainter.STONE_BRICK;
         this.band = FarmPainter.STONE_BRICK;
         this.slabId = FarmPainter.SLAB_STONE; this.slabData = 5;
      } else {
         this.wall = FarmPainter.TERRACOTTA;
         this.band = FarmPainter.TERRACOTTA;
         this.slabId = FarmPainter.SLAB_STONE; this.slabData = 7;
      }
   }

   private void put(FarmPainter p, int dx, int dz, int dy, int id, int data) {
      p.set(this.cx + dx, this.h + dy, this.cz + dz, id, data);
   }

   private double rnd(int dx, int dz, int dy, int salt) {
      return FarmRandom.unit(this.seed, (this.cx + dx) * 7 + dy * 1013, this.cz + dz, salt);
   }

   /** Weathered wall material: stone brick goes mossy and cracked, painted clay fades and streaks. */
   private void wallBlock(FarmPainter p, int dx, int dz, int dy, boolean banded) {
      double r = this.rnd(dx, dz, dy, 1401);
      int data;
      if (this.stone) {
         data = banded ? 1 : (r < 0.62 ? 0 : (r < 0.62 + 0.18 + 0.1 * this.ruin ? 1 : 2));
      } else {
         data = banded ? 7 : (r < 0.55 ? 0 : (r < 0.8 ? 8 : 12));
      }
      this.put(p, dx, dz, dy, banded ? this.band : this.wall, data);
   }

   @Override
   void paint(FarmPainter p) {
      int top = this.height;
      for (int dx = -3; dx <= 3; dx++) {
         for (int dz = -3; dz <= 3; dz++) {
            int d2 = dx * dx + dz * dz;
            if (d2 > 12) {
               continue;
            }
            boolean ring = d2 > 6;
            this.put(p, dx, dz, 0, ring ? FarmPainter.MOSSY_COBBLE : FarmPainter.PLANKS, ring ? 0 : 1);
            if (ring) {
               for (int dy = 1; dy <= top; dy++) {
                  boolean banded = dy % 4 == 0;
                  boolean upper = dy >= top - 4;
                  double miss = (upper ? this.ruin * 0.45 : this.ruin * 0.06) * (upper ? 1 + (dy - (top - 4)) * 0.15 : 1);
                  if (dy > 2 && this.rnd(dx, dz, dy, 1402) < miss) {
                     continue;                                          // a hole in the stonework
                  }
                  this.wallBlock(p, dx, dz, dy, banded);
               }
            } else {
               for (int dy = 1; dy <= top; dy++) {
                  this.put(p, dx, dz, dy, FarmPainter.AIR, 0);
               }
            }
            // dome, with holes in it
            if (this.rnd(dx, dz, top + 1, 1403) >= this.ruin * 0.4) {
               this.put(p, dx, dz, top + 1, this.slabId, this.slabData);
            }
            if (d2 <= 6 && this.rnd(dx, dz, top + 2, 1404) >= this.ruin * 0.35) {
               this.put(p, dx, dz, top + 1, this.wall, this.stone ? 0 : 0);
               this.put(p, dx, dz, top + 2, this.wall, this.stone ? 0 : 0);
            }
            if (d2 <= 2 && this.rnd(dx, dz, top + 3, 1405) >= this.ruin * 0.3) {
               this.put(p, dx, dz, top + 3, this.wall, 0);
            }
         }
      }
      if (this.rnd(0, 0, top + 4, 1406) >= this.ruin * 0.6) {
         this.put(p, 0, 0, top + 4, FarmPainter.IRON_BARS, 0);
         this.put(p, 0, 0, top + 5, FarmPainter.IRON_BARS, 0);
      }

      // door: a 1 x 2 archway on one cardinal side
      int[][] dirs = {{0, -3}, {3, 0}, {0, 3}, {-3, 0}};
      int ddx = dirs[this.doorDir][0];
      int ddz = dirs[this.doorDir][1];
      this.put(p, ddx, ddz, 1, FarmPainter.AIR, 0);
      this.put(p, ddx, ddz, 2, FarmPainter.AIR, 0);
      this.put(p, ddx, ddz, 0, FarmPainter.PLANKS, 1);

      // slit windows on the other three sides, low and high; most of the glass is gone
      for (int i = 0; i < 4; i++) {
         if (i == this.doorDir) {
            continue;
         }
         if (this.rnd(dirs[i][0], dirs[i][1], 5, 1407) > 0.7) {
            this.put(p, dirs[i][0], dirs[i][1], 5, FarmPainter.GLASS_PANE, 0);
         } else {
            this.put(p, dirs[i][0], dirs[i][1], 5, FarmPainter.AIR, 0);
         }
         if (this.rnd(dirs[i][0], dirs[i][1], top - 2, 1408) > 0.7) {
            this.put(p, dirs[i][0], dirs[i][1], top - 2, FarmPainter.GLASS_PANE, 0);
         } else {
            this.put(p, dirs[i][0], dirs[i][1], top - 2, FarmPainter.AIR, 0);
         }
      }

      // platform with a hatch, ladder up the west wall; planks around the chest are never lost
      for (int dx = -2; dx <= 2; dx++) {
         for (int dz = -2; dz <= 2; dz++) {
            if (dx * dx + dz * dz > 6) {
               continue;
            }
            if (dx == -2 && dz == -1) {
               continue;
            }
            boolean nearChest = Math.abs(dx - 2) <= 1 && Math.abs(dz + 1) <= 1;
            if (!nearChest && this.rnd(dx, dz, 7, 1409) < this.ruin * 0.25) {
               continue;
            }
            this.put(p, dx, dz, 7, FarmPainter.PLANKS, 1);
         }
      }
      for (int dy = 1; dy <= 8; dy++) {
         this.put(p, -2, -1, dy, FarmPainter.LADDER, 5);   // faces east, attached to the west wall
      }

      // old grain left on the floor
      int[][] hay = {{-2, 1, 1}, {-1, 2, 1}, {0, 2, 1}, {-1, 2, 8}};
      for (int i = 0; i < hay.length; i++) {
         if (this.rnd(hay[i][0], hay[i][1], hay[i][2], 1410) < 0.7) {
            this.put(p, hay[i][0], hay[i][1], hay[i][2], FarmPainter.HAY, 0);
         }
      }

      // loot chests on the east wall, lids facing west
      p.chest(this.cx + 2, this.h + 1, this.cz + 1, 4, 4);
      p.chest(this.cx + 2, this.h + 8, this.cz - 1, 4, 5);
   }
}
