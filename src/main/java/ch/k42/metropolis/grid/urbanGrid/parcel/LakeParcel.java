package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.wild.WildernessFeatures;
import ch.k42.metropolis.minions.Cartesian2D;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;

/**
 * One chunk of a seed-planned named wilderness lake. The shared LakeSite owns
 * the complete basin geometry; every chunk paints only its own sixteen by
 * sixteen columns, so the waterline and shelves are identical whichever
 * chunk populates first.
 */
public final class LakeParcel extends Parcel {
   private static final Cartesian2D SIZE = new Cartesian2D(1, 1);
   private final WildernessFeatures.LakeSite lake;

   public LakeParcel(UrbanGrid grid, Cartesian2D base, WildernessFeatures.LakeSite lake) {
      super(base, SIZE, ContextType.RIVER, SchematicType.RIVER, grid);
      if (lake == null) throw new IllegalArgumentException("Lake site is required");
      this.lake = lake;
      grid.fillParcels(this.chunkX, this.chunkZ, this);
   }

   public WildernessFeatures.LakeSite getLake() {
      return lake;
   }

   @Override
   public void populate(MetropolisGenerator generator, Chunk chunk) {
      if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) return;
      World world = generator.getWorld();
      if (world == null) return;
      int baseX = this.chunkX << 4;
      int baseZ = this.chunkZ << 4;
      int minY = Math.max(world.getMinHeight() + 1, 1);
      int maxY = world.getMaxHeight() - 1;
      int waterY = Math.min(maxY - 4, Math.max(minY + 4, lake.waterY));

      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            int x = baseX + lx;
            int z = baseZ + lz;
            double radius = lake.normalizedRadius(x + 0.5, z + 0.5);
            if (radius >= 1.0) continue;

            int oldTop = world.getHighestBlockYAt(x, z);
            if (radius < WildernessFeatures.LakeSite.WATER_EDGE) {
               int depth = lake.depthAt(x, z);
               int bedY = Math.max(minY + 2, waterY - depth);
               // Only the lake-owned column is excavated. Tree canopies from
               // adjacent forest roots are preserved at the chunk boundary.
               Parcel.clearAboveKeepingTrees(world, x, z, bedY + 1, Math.max(oldTop, waterY + 1));
               for (int y = Math.max(minY, bedY - 3); y < bedY; y++) {
                  world.getBlockAt(x, y, z).setType(Material.STONE, false);
               }
               Material bed = lakeBed(x, z, radius);
               world.getBlockAt(x, bedY, z).setType(bed, false);
               for (int y = bedY + 1; y <= waterY; y++) {
                  world.getBlockAt(x, y, z).setType(Material.WATER, false);
               }
               world.setBiome(x, z, Biome.RIVER);
               continue;
            }

            // A shallow, irregular natural shelf. The shoreline rises only
            // a few blocks above the water rather than making a sheer bowl.
            double shoreT = (radius - WildernessFeatures.LakeSite.WATER_EDGE)
                    / (1.0 - WildernessFeatures.LakeSite.WATER_EDGE);
            shoreT = smooth(shoreT);
            int targetY = Math.min(maxY - 3, waterY + 1 + (int)Math.round(4.0 * shoreT));
            if (targetY < oldTop) {
               Parcel.clearAboveKeepingTrees(world, x, z, targetY + 1, oldTop);
            } else if (targetY > oldTop) {
               for (int y = oldTop + 1; y < targetY; y++) {
                  world.getBlockAt(x, y, z).setType(Material.DIRT, false);
               }
            }
            for (int y = Math.max(minY, targetY - 3); y < targetY; y++) {
               world.getBlockAt(x, y, z).setType(Material.DIRT, false);
            }
            world.getBlockAt(x, targetY, z).setType(shoreTop(x, z, shoreT), false);
            world.setBiome(x, z, Biome.FOREST);
         }
      }
      this.markPopulated();
   }

   private Material lakeBed(int x, int z, double radius) {
      long hash = hash(x, z, 0x4C414B45424544L);
      double roll = unit(hash);
      if (radius > 0.67 && roll < 0.38) return Material.GRAVEL;
      if (roll < 0.16) return Material.CLAY;
      if (roll < 0.42) return Material.GRAVEL;
      return Material.SAND;
   }

   private Material shoreTop(int x, int z, double shoreT) {
      long hash = hash(x, z, 0x4C414B4553484F52L);
      double roll = unit(hash);
      if (shoreT > 0.75 && roll < 0.16) return Material.COARSE_DIRT;
      if (roll < 0.12) return Material.GRAVEL;
      if (roll < 0.20) return Material.SAND;
      return Material.GRASS_BLOCK;
   }

   private static double smooth(double value) {
      double t = Math.max(0.0, Math.min(1.0, value));
      return t * t * (3.0 - 2.0 * t);
   }

   private static long hash(int x, int z, long salt) {
      long h = salt ^ ((long)x * 0x9E3779B97F4A7C15L) ^ ((long)z * 0xD1B54A32D192ED03L);
      h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
      h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
      return h ^ (h >>> 31);
   }

   private static double unit(long value) {
      return (value >>> 11) * (1.0 / 9007199254740992.0);
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
   }
}
