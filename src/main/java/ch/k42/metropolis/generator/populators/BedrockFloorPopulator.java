package ch.k42.metropolis.generator.populators;

import java.util.Random;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;

public class BedrockFloorPopulator extends BlockPopulator {
   private static final int[][] easteregg1 = new int[][]{
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0, 1, 1, 0, 1, 1},
      {0, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1},
      {0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1},
      {0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1},
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 1, 0, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 1, 1, 0},
      {0, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 0},
      {0, 0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 1, 0, 0},
      {0, 1, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 1, 1, 0},
      {0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}
   };
   private static final int[][] easteregg2 = new int[][]{
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 0, 1, 1, 0, 0, 0},
      {0, 1, 0, 1, 0, 1, 0, 0, 0, 0, 0, 1, 0, 1, 0, 0},
      {0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0},
      {0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 0},
      {0, 0, 0, 0, 0, 1, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0},
      {0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0},
      {1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 1, 0, 1},
      {1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0, 1, 0, 1, 0, 0},
      {1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 1, 0, 0, 0, 1, 0},
      {1, 0, 0, 1, 1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 0, 1},
      {1, 1, 0, 1, 0, 1, 0, 1, 0, 1, 1, 0, 0, 1, 0, 1},
      {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0}
   };

   /**
    * True when the underworld is vanilla-generated (MetropolisGenerator does
    * its own vanilla-style bedrock then, at the world's true bottom), so this
    * populator must not seal the world at Y=0..3 and only stamps the two
    * easter-egg chunks, on the bottom layer.
    */
   private final boolean vanillaUnderworld;

   /** The original behaviour: a bedrock floor at Y=0..3. */
   public BedrockFloorPopulator() {
      this(false);
   }

   public BedrockFloorPopulator(boolean vanillaUnderworld) {
      this.vanillaUnderworld = vanillaUnderworld;
   }

   public void populate(World world, Random random, Chunk chunk) {
      // The floor layer: Y=0 in the original layout, the true bottom of the
      // world when the underworld is vanilla.
      int floor = this.vanillaUnderworld ? world.getMinHeight() : 0;

      if (chunk.getX() % 128 == 0 && chunk.getZ() % 128 == 0) {
         for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
               chunk.getBlock(x, floor, z).setType(Material.BEDROCK, false);
               if (easteregg1[z][x] != 0) {
                  chunk.getBlock(x, floor + 1, z).setType(Material.BEDROCK, false);
               }
            }
         }
      } else if (chunk.getX() % 128 == 1 && chunk.getZ() % 128 == 0) {
         for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
               chunk.getBlock(x, floor, z).setType(Material.BEDROCK, false);
               if (easteregg2[z][x] != 0) {
                  chunk.getBlock(x, floor + 1, z).setType(Material.BEDROCK, false);
               }
            }
         }
      } else if (!this.vanillaUnderworld) {
         for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
               chunk.getBlock(x, 0, z).setType(Material.BEDROCK, false);

               for (int bed = random.nextInt(3) + 1; bed > 0; bed--) {
                  chunk.getBlock(x, bed, z).setType(Material.BEDROCK, false);
               }
            }
         }
      }
   }
}
