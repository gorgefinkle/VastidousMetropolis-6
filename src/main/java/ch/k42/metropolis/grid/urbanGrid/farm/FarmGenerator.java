package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.minions.LegacyBlocks;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.Material;

/**
 * Generates the ground, fields, fences, barns, silos and loot of one chunk of
 * a farm region (see MorphomosRegion / MorphomosParcel). The counterpart of
 * ForestGenerator, built the same way: a chunk only ever writes into itself,
 * and everything it draws is a pure function of world coordinates plus the
 * region mask, so chunks agree on shared structures without any dependence on
 * generation order.
 *
 * The farm surface is flat and sits at the build height (plus
 * generator.farm.surfaceOffset), the same level the forest uses, so it meets
 * streets and neighbouring regions cleanly. See FarmLayout for what is placed.
 */
public final class FarmGenerator {
   /** Column clearing stops here; the base terrain tops out at 64. */
   private static final int CLEAR_TOP = 72;

   private FarmGenerator() {
   }

   public static void populateChunk(MetropolisGenerator generator, Chunk chunk, UrbanGrid grid, MorphomosRegion region) {
      if (!PluginConfig.isFarmGenerationEnabled() || region == null || generator.getWorld() == null) {
         return;
      }
      long seed = generator.getWorldSeed() ^ 0x4641524D4C414E44L;
      FarmContext ctx = new FarmContext(grid, region, seed);
      World world = generator.getWorld();
      int cx = chunk.getX();
      int cz = chunk.getZ();
      int h = ctx.groundY(cx, cz);
      int baseX = cx << 4;
      int baseZ = cz << 4;

      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            writeColumn(chunk, lx, lz, h);
            try {
               world.setBiome(baseX + lx, baseZ + lz, Biome.PLAINS);
            } catch (Throwable ignored) {
               // biome tinting is cosmetic; never let it stop terrain
            }
         }
      }

      FarmPainter painter = new FarmPainter(chunk, seed, h);
      FarmLayout layout = FarmLayout.get(ctx, FarmRandom.floorDiv(cx, FarmLayout.CELL_CHUNKS), FarmRandom.floorDiv(cz, FarmLayout.CELL_CHUNKS));
      layout.paint(painter);
      varyUnusedMeadow(chunk, layout, ctx, seed, h);
      FarmRoads.paintStrips(ctx, painter);
      cover(chunk, seed, h);
      painter.finishPaneAndBarConnections();
      Minions.dDistrict("FARM chunk=(%d,%d) region=%d", cx, cz, region.getId());
   }

   private static void writeColumn(Chunk chunk, int lx, int lz, int h) {
      for (int y = h + 1; y <= CLEAR_TOP; y++) {
         LegacyBlocks.set(chunk.getBlock(lx, y, lz), FarmPainter.AIR, 0);
      }
      for (int y = h - 4; y >= 65; y--) {
         LegacyBlocks.set(chunk.getBlock(lx, y, lz), FarmPainter.STONE, 0);
      }
      for (int y = h - 1; y >= h - 3 && y >= 1; y--) {
         LegacyBlocks.set(chunk.getBlock(lx, y, lz), FarmPainter.DIRT, 0);
      }
      LegacyBlocks.set(chunk.getBlock(lx, h, lz), FarmPainter.GRASS, 0);
   }

   /** Restored R30cg farm meadow variance at the reference paint stage.
    * Reserved field/structure locations are left untouched. */
   private static void varyUnusedMeadow(Chunk chunk, FarmLayout layout, FarmContext ctx, long seed, int h) {
      int bx = chunk.getX() << 4;
      int bz = chunk.getZ() << 4;
      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            int x = bx + lx, z = bz + lz;
            boolean reserved = false;
            for (int dx = -6; dx <= 6 && !reserved; dx += 3) {
               for (int dz = -6; dz <= 6; dz += 3) {
                  if (layout.reserved(x + dx, z + dz)) { reserved = true; break; }
               }
            }
            if (reserved) continue;
            int gx = Math.floorDiv(x, 26), gz = Math.floorDiv(z, 26);
            double fx = (x - gx * 26) / 26.0, fz = (z - gz * 26) / 26.0;
            fx = fx * fx * (3.0 - 2.0 * fx);
            fz = fz * fz * (3.0 - 2.0 * fz);
            double a = FarmRandom.unit(seed, gx, gz, 741);
            double b = FarmRandom.unit(seed, gx + 1, gz, 741);
            double c = FarmRandom.unit(seed, gx, gz + 1, 741);
            double d = FarmRandom.unit(seed, gx + 1, gz + 1, 741);
            double v = (a * (1.0 - fx) + b * fx) * (1.0 - fz)
                     + (c * (1.0 - fx) + d * fx) * fz;
            Block block = chunk.getBlock(lx, h, lz);
            if (block.getType() != Material.GRASS_BLOCK) continue;
            if (v > 0.72) {
               block.setType(Material.DIRT, false);
               chunk.getBlock(lx, h + 1, lz).setType(Material.GRASS_BLOCK, false);
            } else if (v < 0.30) {
               block.setType(Material.COARSE_DIRT, false);
            } else if (v < 0.37 && FarmRandom.unit(seed, x, z, 742) < 0.12) {
               block.setType(Material.GRAVEL, false);
            }
         }
      }
   }

   /**
    * Tall grass and wildflowers, on any column that is still bare grass after
    * everything else has been painted (pastures, meadow, the verges), so it
    * never lands on paths, crops, floors or under fences.
    */
   private static void cover(Chunk chunk, long seed, int h) {
      int baseX = chunk.getX() << 4;
      int baseZ = chunk.getZ() << 4;
      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            Block ground = chunk.getBlock(lx, h, lz);
            Block above = chunk.getBlock(lx, h + 1, lz);
            if (!LegacyBlocks.is(ground, FarmPainter.GRASS) || !LegacyBlocks.is(above, FarmPainter.AIR)) {
               continue;
            }
            int x = baseX + lx;
            int z = baseZ + lz;
            double r = FarmRandom.unit(seed, x, z, 701);
            if (r < 0.003) {
               long patch = FarmRandom.hash(seed, x >> 2, z >> 2, 702);
               int kind = (int) ((patch >>> 40) % 10);
               if (kind == 9) {
                  LegacyBlocks.set(above, FarmPainter.DANDELION, 0);
               } else {
                  LegacyBlocks.set(above, FarmPainter.RED_ROSE, kind);
               }
            } else if (r < 0.003 + 0.12) {
               LegacyBlocks.set(above, FarmPainter.TALL_GRASS, 1);
            }
         }
      }
   }

   /**
    * Called when a paved road chunk next to a farm chunk has finished
    * generating (see StreetParcel.notifyNeighboursToRecut). If a dirt road runs
    * into that road chunk, returns where the road must be opened up for it as
    * {x, z, sizeX, sizeZ} (the cutout convention buildings use: a negative
    * size runs backwards from the start block); otherwise null.
    * Pure geometry; the parcel performs the cut with the shared cutout code.
    */
   public static int[] roadOpening(MetropolisGenerator generator, UrbanGrid grid, MorphomosRegion region,
                                   int farmChunkX, int farmChunkZ, int roadChunkX, int roadChunkZ) {
      if (!PluginConfig.isFarmGenerationEnabled() || region == null || generator.getWorld() == null) {
         return null;
      }
      long seed = generator.getWorldSeed() ^ 0x4641524D4C414E44L;
      FarmContext ctx = new FarmContext(grid, region, seed);
      return FarmRoads.opening(ctx, farmChunkX, farmChunkZ, roadChunkX, roadChunkZ);
   }

   /** Forget cached farm plans (call after the config is reloaded). */
   public static void clearCache() {
      FarmLayout.clearCache();
      FarmRoads.clearCache();
   }
}
