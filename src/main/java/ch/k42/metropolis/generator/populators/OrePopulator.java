package ch.k42.metropolis.generator.populators;

import java.util.Random;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;

public class OrePopulator extends BlockPopulator {
   private OreVein[] ores = new OreVein[]{
      new OreVein("GRAVEL", 32, 10, 1.0, 0.0),
      new OreVein("COAL_ORE", 16, 21, 1.0, 0.0),
      new OreVein("IRON_ORE", 8, 21, 0.8, 0.0),
      new OreVein("GOLD_ORE", 8, 8, 0.75, 0.0),
      new OreVein("REDSTONE_ORE", 7, 10, 0.7, 0.0),
      new OreVein("DIAMOND_ORE", 7, 1, 0.4, 0.0),
      new OreVein("LAPIS_ORE", 6, 3, 0.5, 0.0)
   };
   private final Random random;
   public static final int oreLevel = 62;

   public OrePopulator(World world) {
      this.random = new Random(world.getSeed());
   }

   public OrePopulator(World world, OreVein[] ores) {
      this(world);
      this.ores = ores;
   }

   /**
    * Places one vein, entirely inside {@code chunk}.
    *
    * The original (vanilla 1.8) algorithm centred the vein 8 blocks toward +x/+z
    * and let it spill into the neighbouring chunks, which was fine when those
    * chunks were already generated. On a modern server, reading a block in an
    * unloaded chunk loads it right there -- and a loaded chunk runs ITS
    * populators, whose veins read THEIR +x/+z neighbour, and so on: a runaway
    * diagonal chain of chunk loads that froze the server during spawn
    * preparation (the main thread's stack was this method, repeated, with
    * syncLoad in between).
    *
    * So: the vein is centred on the random point inside the chunk (no +8
    * offset), and every block outside the chunk is simply not placed. Veins
    * that touch a chunk edge are slightly truncated; nothing else changes.
    * Blocks are read and written through the Chunk, which cannot reach
    * anything outside it.
    */
   private void createClump(Chunk chunk, Material oreType, int size, int x, int y, int z) {
      int minX = chunk.getX() * 16;
      int minZ = chunk.getZ() * 16;
      // Chunk.getBlock() REFUSES a Y outside the world (World.getBlockAt used
      // to hand back air). A vein centred near the bottom reaches 2 below its
      // start, which is Y=-1 in the Nether (its floor is Y=0), and that threw
      // and killed the server while it was loading the Nether.
      int minY = chunk.getWorld().getMinHeight();
      int maxY = chunk.getWorld().getMaxHeight() - 1;
      float f = this.random.nextFloat() * 3.141593F;
      double d1 = x + Math.sin(f) * size / 8.0;
      double d2 = x - Math.sin(f) * size / 8.0;
      double d3 = z + Math.cos(f) * size / 8.0;
      double d4 = z - Math.cos(f) * size / 8.0;
      double d5 = y + this.random.nextInt(3) - 2;
      double d6 = y + this.random.nextInt(3) - 2;

      for (int i = 0; i <= size; i++) {
         double d7 = d1 + (d2 - d1) * i / size;
         double d8 = d5 + (d6 - d5) * i / size;
         double d9 = d3 + (d4 - d3) * i / size;
         double d10 = this.random.nextDouble() * size / 16.0;
         double d11 = (Math.sin(i * 3.141593F / size) + 1.0) * d10 + 1.0;
         double d12 = (Math.sin(i * 3.141593F / size) + 1.0) * d10 + 1.0;
         int j = (int)Math.floor(d7 - d11 / 2.0);
         int k = (int)Math.floor(d8 - d12 / 2.0);
         int m = (int)Math.floor(d9 - d11 / 2.0);
         int n = (int)Math.floor(d7 + d11 / 2.0);
         int i1 = (int)Math.floor(d8 + d12 / 2.0);
         int i2 = (int)Math.floor(d9 + d11 / 2.0);

         for (int i3 = Math.max(j, minX); i3 <= Math.min(n, minX + 15); i3++) {
            double d13 = (i3 + 0.5 - d7) / (d11 / 2.0);
            if (d13 * d13 < 1.0) {
               for (int i4 = Math.max(k, minY); i4 <= Math.min(i1, maxY); i4++) {
                  double d14 = (i4 + 0.5 - d8) / (d12 / 2.0);
                  if (d13 * d13 + d14 * d14 < 1.0) {
                     for (int i5 = Math.max(m, minZ); i5 <= Math.min(i2, minZ + 15); i5++) {
                        double d15 = (i5 + 0.5 - d9) / (d11 / 2.0);
                        if (!(d13 * d13 + d14 * d14 + d15 * d15 >= 1.0) && i4 <= oreLevel) {
                           Block block = chunk.getBlock(i3 & 15, i4, i5 & 15);
                           if (block.getType() == Material.STONE) {
                              block.setType(oreType, false);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   public void populate(World world, Random random, Chunk chunk) {
      int worldChunkX = chunk.getX() * 16;
      int worldChunkZ = chunk.getZ() * 16;

      for (OreVein ore : this.ores) {
         for (int i = 0; i < ore.getNum(); i++) {
            int x = worldChunkX + this.random.nextInt(16);
            int z = worldChunkZ + this.random.nextInt(16);
            int y = this.random.nextInt(ore.getRange()) + ore.getOffset();
            this.createClump(chunk, ore.getOre(), ore.getAmount(), x, y, z);
         }
      }
   }
}
