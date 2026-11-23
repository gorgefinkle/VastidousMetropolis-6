package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

/** Small farmyard pieces: the trodden yard around a barn, a roofed well, hay stacks. */
final class FarmProps {
   private FarmProps() {
   }

   /** Bare, trampled ground around the buildings, going back to weeds at the edges. */
   static final class Yard extends FarmFeature {
      private final int h;
      private final long seed;

      Yard(int minX, int minZ, int maxX, int maxZ, int h, long seed) {
         super(minX, minZ, maxX, maxZ);
         this.h = h;
         this.seed = seed;
      }

      @Override
      void paint(FarmPainter p) {
         int x0 = Math.max(this.minX, p.minX);
         int x1 = Math.min(this.maxX, p.maxX);
         int z0 = Math.max(this.minZ, p.minZ);
         int z1 = Math.min(this.maxZ, p.maxZ);
         for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
               int edge = Math.min(Math.min(x - this.minX, this.maxX - x), Math.min(z - this.minZ, this.maxZ - z));
               double keep = Math.min(1.0, 0.20 + edge / 5.0);
               double roll = FarmRandom.unit(this.seed, x, z, 501);
               if (roll >= keep * 0.70) {
                  continue;
               }
               double kind = FarmRandom.unit(this.seed, x, z, 502);
               if (kind < 0.55) {
                  p.set(x, this.h, z, FarmPainter.DIRT, 1);
               } else if (kind < 0.80) {
                  p.set(x, this.h, z, FarmPainter.DIRT, 0);
               } else {
                  p.set(x, this.h, z, FarmPainter.GRAVEL, 0);
               }
               p.set(x, this.h + 1, z, FarmRandom.unit(this.seed, x, z, 503) < 0.07 ? FarmPainter.DEAD_BUSH : FarmPainter.AIR, 0);
            }
         }
      }
   }

   /** A 3 x 3 cobblestone well with a roof on four posts, half fallen in. */
   static final class Well extends FarmFeature {
      private final int cx, cz, h;
      private final long seed;

      Well(int cx, int cz, int h, long seed) {
         super(cx - 1, cz - 1, cx + 1, cz + 1);
         this.cx = cx;
         this.cz = cz;
         this.h = h;
         this.seed = seed;
      }

      @Override
      void paint(FarmPainter p) {
         double ruin = PluginConfig.getFarmRuinPercent() / 100.0;
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (dx == 0 && dz == 0) {
                  p.set(this.cx, this.h - 1, this.cz, FarmPainter.WATER, 0);
                  p.set(this.cx, this.h, this.cz, FarmPainter.WATER, 0);
                  p.set(this.cx, this.h + 1, this.cz, FarmPainter.AIR, 0);
               } else {
                  boolean mossy = FarmRandom.unit(this.seed, this.cx + dx, this.cz + dz, 521) < 0.4;
                  p.set(this.cx + dx, this.h, this.cz + dz, mossy ? FarmPainter.MOSSY_COBBLE : FarmPainter.COBBLE, 0);
                  p.set(this.cx + dx, this.h + 1, this.cz + dz, FarmPainter.AIR, 0);
               }
            }
         }
         int[][] corners = {{-1, -1}, {1, -1}, {-1, 1}, {1, 1}};
         for (int i = 0; i < corners.length; i++) {
            int x = this.cx + corners[i][0];
            int z = this.cz + corners[i][1];
            p.set(x, this.h + 1, z, FarmPainter.COBBLE, 0);
            if (FarmRandom.unit(this.seed, x, z, 522) >= ruin * 0.5) {
               p.set(x, this.h + 2, z, FarmPainter.FENCE, 0);
               p.set(x, this.h + 3, z, FarmPainter.FENCE, 0);
            }
         }
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (FarmRandom.unit(this.seed, this.cx + dx, this.cz + dz, 523) >= 0.2 + ruin * 0.5) {
                  p.set(this.cx + dx, this.h + 4, this.cz + dz, FarmPainter.SLAB_WOOD, 0);
               }
            }
         }
      }
   }

   /** A stack of old hay bales: 2 x 2 two high with a bale on top, collapsed in places. */
   static final class HayStack extends FarmFeature {
      private final int x, z, h;
      private final long seed;

      HayStack(int x, int z, int h, long seed) {
         super(x, z, x + 1, z + 1);
         this.x = x;
         this.z = z;
         this.h = h;
         this.seed = seed;
      }

      @Override
      void paint(FarmPainter p) {
         double ruin = PluginConfig.getFarmRuinPercent() / 100.0;
         for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
               if (FarmRandom.unit(this.seed, this.x + dx, this.z + dz, 531) >= 0.1 + ruin * 0.3) {
                  p.set(this.x + dx, this.h + 1, this.z + dz, FarmPainter.HAY, 0);
                  if (FarmRandom.unit(this.seed, this.x + dx, this.z + dz, 532) >= 0.2 + ruin * 0.4) {
                     p.set(this.x + dx, this.h + 2, this.z + dz, FarmPainter.HAY, 0);
                  }
               }
            }
         }
         if (FarmRandom.unit(this.seed, this.x, this.z, 533) >= 0.4 + ruin * 0.3) {
            p.set(this.x + (FarmRandom.unit(this.seed, this.x, this.z, 534) < 0.5 ? 0 : 1), this.h + 3,
                    this.z + (FarmRandom.unit(this.seed, this.x, this.z, 535) < 0.5 ? 0 : 1), FarmPainter.HAY, 0);
         }
      }
   }

   /** A dirt driveway from a barn doorway out to a dirt road, rutted and going to weed. */
   static final class Driveway extends FarmFeature {
      private final int h;
      private final boolean alongX;

      Driveway(int minX, int minZ, int maxX, int maxZ, int h, boolean alongX) {
         super(minX, minZ, maxX, maxZ);
         this.h = h;
         this.alongX = alongX;
      }

      @Override
      void paint(FarmPainter p) {
         int x0 = Math.max(this.minX, p.minX);
         int x1 = Math.min(this.maxX, p.maxX);
         int z0 = Math.max(this.minZ, p.minZ);
         int z1 = Math.min(this.maxZ, p.maxZ);
         int width = this.alongX ? this.maxZ - this.minZ + 1 : this.maxX - this.minX + 1;
         for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
               int across = this.alongX ? z - this.minZ : x - this.minX;
               boolean edge = across == 0 || across == width - 1;
               if (edge && FarmRandom.unit(p.seed, x, z, 603) < 0.35) {
                  continue;
               }
               FarmRoads.surface(p, x, this.h, z);
               if (edge && FarmRandom.unit(p.seed, x, z, 604) < 0.15) {
                  p.set(x, this.h + 1, z, FarmPainter.TALL_GRASS, 1);
               }
            }
         }
      }
   }

   /** A row of trees (a farm windbreak), some of them long dead. */
   static final class Windbreak extends FarmFeature {
      private final int[][] spots;      // x, z
      private final int h;
      private final long seed;
      private final boolean spruce;

      Windbreak(int[][] spots, int h, long seed, boolean spruce) {
         super(minOf(spots, 0) - 3, minOf(spots, 1) - 3, maxOf(spots, 0) + 3, maxOf(spots, 1) + 3);
         this.spots = spots;
         this.h = h;
         this.seed = seed;
         this.spruce = spruce;
      }

      private static int minOf(int[][] a, int i) {
         int m = Integer.MAX_VALUE;
         for (int[] s : a) { m = Math.min(m, s[i]); }
         return m;
      }

      private static int maxOf(int[][] a, int i) {
         int m = Integer.MIN_VALUE;
         for (int[] s : a) { m = Math.max(m, s[i]); }
         return m;
      }

      @Override
      void paint(FarmPainter p) {
         double ruin = PluginConfig.getFarmRuinPercent() / 100.0;
         for (int[] s : this.spots) {
            boolean dead = FarmRandom.unit(this.seed, s[0], s[1], 541) < 0.15 + ruin * 0.35;
            FarmTrees.tree(p, s[0], s[1], this.h, this.seed, dead ? FarmTrees.DEAD : (this.spruce ? FarmTrees.SPRUCE : FarmTrees.OAK));
         }
      }
   }

   /** A rusting, abandoned tractor: a wheeled body, a hood, a stack and a cab frame, partly stripped. */
   static final class Tractor extends FarmFeature {
      private final int ox, oz, h;
      private final boolean axisX;
      private final long seed;
      private final int body;

      Tractor(int ox, int oz, boolean axisX, int h, long seed) {
         super(ox, oz, ox + (axisX ? 4 : 2), oz + (axisX ? 2 : 4));
         this.ox = ox;
         this.oz = oz;
         this.axisX = axisX;
         this.h = h;
         this.seed = seed;
         double r = FarmRandom.unit(seed, ox, oz, 551);
         this.body = r < 0.4 ? 14 : (r < 0.75 ? 12 : 1);     // rust red, rust brown, faded orange
      }

      private void put(FarmPainter p, int u, int v, int dy, int id, int data) {
         int x = this.axisX ? this.ox + u : this.ox + v;
         int z = this.axisX ? this.oz + v : this.oz + u;
         double ruin = PluginConfig.getFarmRuinPercent() / 100.0;
         if (FarmRandom.unit(this.seed, x + dy * 31, z, 552) < ruin * 0.3 && id != FarmPainter.COAL_BLOCK) {
            return;                                           // stripped
         }
         p.set(x, this.h + dy, z, id, data);
      }

      @Override
      void paint(FarmPainter p) {
         // big rear wheels (u 0..1), small front wheels (u 3..4)
         for (int v = 0; v <= 2; v += 2) {
            this.put(p, 0, v, 1, FarmPainter.COAL_BLOCK, 0);
            this.put(p, 1, v, 1, FarmPainter.COAL_BLOCK, 0);
            this.put(p, 0, v, 2, FarmPainter.COAL_BLOCK, 0);
            this.put(p, 1, v, 2, FarmPainter.COAL_BLOCK, 0);
            this.put(p, 3, v, 1, FarmPainter.COAL_BLOCK, 0);
         }
         // chassis and hood down the middle
         for (int u = 0; u <= 4; u++) {
            this.put(p, u, 1, 1, FarmPainter.TERRACOTTA, this.body);
         }
         this.put(p, 2, 1, 2, FarmPainter.TERRACOTTA, this.body);
         this.put(p, 3, 1, 2, FarmPainter.TERRACOTTA, this.body);
         this.put(p, 4, 1, 2, FarmPainter.TERRACOTTA, this.body);
         this.put(p, 3, 1, 3, FarmPainter.IRON_BARS, 0);          // exhaust stack
         // open cab frame
         this.put(p, 0, 1, 3, FarmPainter.IRON_BARS, 0);
         this.put(p, 1, 1, 3, FarmPainter.IRON_BARS, 0);
         this.put(p, 0, 0, 3, FarmPainter.IRON_BARS, 0);
         this.put(p, 0, 2, 3, FarmPainter.IRON_BARS, 0);
         this.put(p, 1, 0, 3, FarmPainter.IRON_BARS, 0);
         this.put(p, 1, 2, 3, FarmPainter.IRON_BARS, 0);
         for (int u = 0; u <= 1; u++) {
            for (int v = 0; v <= 2; v++) {
               this.put(p, u, v, 4, FarmPainter.SLAB_STONE, 0);
            }
         }
      }
   }
}
