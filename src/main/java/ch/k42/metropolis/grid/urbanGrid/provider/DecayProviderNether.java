package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.minions.DecayOption;
import ch.k42.metropolis.minions.Mats;
import com.google.common.collect.ImmutableSet;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.noise.SimplexOctaveGenerator;

public class DecayProviderNether extends DecayProvider {
   private static Set<Material> removableBlocks = ImmutableSet.<Material>builder()
           .add(Material.GLASS_PANE).add(Material.GLASS)
           .addAll(Mats.STAINED_GLASS).addAll(Mats.STAINED_GLASS_PANES)
           .addAll(Mats.WOODEN_DOORS)
           .build();
   private static Set<Material> deletedBlocks = ImmutableSet.<Material>builder()
           .addAll(Mats.LEAVES)
           .addAll(Mats.TALL_PLANTS)
           .addAll(Mats.SMALL_FLOWERS)
           .add(Material.GRASS_BLOCK)
           .addAll(Mats.SHORT_PLANTS)
           .add(Material.VINE)
           .addAll(Mats.LOGS)
           .add(Material.WATER)
           .build();

   public DecayProviderNether(MetropolisGenerator generator, Random random) {
      super(generator, random);
   }

   @Override
   public void destroyWithin(int x1, int x2, int y1, int y2, int z1, int z2, DecayOption options) {
      if (y1 < 0) {
         y1 = 0;
      }

      if (y2 < 0) {
         y2 = 0;
      }

      int MAX = 254;
      int groundHeight = 65;
      if (y1 > MAX) {
         y1 = MAX;
      }

      if (y2 > MAX) {
         y2 = MAX;
      }

      double holeScale = options.getHoleScale();
      double fulldecay = options.getFullDecay() - 0.4;
      double partialdecay = options.getPartialDecay() - 0.4;
      World world = this.generator.getWorld();
      long seed = this.generator.getWorldSeed();
      SimplexOctaveGenerator noiseGen = new SimplexOctaveGenerator(seed, 2);

      for (int z = z1; z < z2; z++) {
         for (int x = x1; x < x2; x++) {
            // Even with postPopulate() now restricted to firing once (see
            // ClipboardParcel), the anchor chunk's single call still spans
            // the structure's ENTIRE footprint -- for a multi-chunk city
            // that's coordinates in sibling chunks Bukkit has no guarantee
            // are generated yet at this point. world.getBlockAt() on an
            // ungenerated chunk synchronously generates+populates it,
            // which re-enters this same populator and can reach into yet
            // another ungenerated sibling -- the recursion that overflowed
            // the stack. Skipping columns in a not-yet-loaded chunk avoids
            // ever triggering that; it does mean decay/lava-sea overlay
            // for the parts of a very large structure that land in a
            // not-yet-generated chunk gets skipped rather than applied,
            // which is a visual tradeoff (a hard edge instead of a
            // gradient) but not a crash.
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
               continue;
            }

            for (int y = y1; y < y2; y++) {
               double holeNoise = noiseGen.noise(x * holeScale, y * holeScale, z * holeScale, 0.3, 0.6, true);
               Block block = world.getBlockAt(x, y, z);
               if (!options.getExceptions().contains(block.getType())) {
                  if (Mats.WOODEN_DOORS.contains(block.getType()) && block.getRelative(0, 1, 0).getType() == block.getType() && this.random.nextInt(100) < 80) {
                     Mats.toggleDoor(block);
                  }

                  if (block.getType() == Material.WATER) {
                     // applyPhysics=false: setType(Material) alone fires
                     // vanilla's fluid-placement hook (BlockFlowing.onPlace
                     // -> BlockFluids -> World.getType()), which reads the
                     // TYPE OF EVERY NEIGHBOURING BLOCK to work out fluid
                     // spread -- entirely inside the game engine, bypassing
                     // getSafeRelative() below since that only guards our
                     // own lookups. If a neighbour is in an unloaded chunk,
                     // that internal read synchronously generates it,
                     // re-entering this populator -- this is what actually
                     // showed up in the crash log as BlockFluids.e/
                     // BlockFlowing.onPlace frames sitting right below a
                     // setType() call here. DecayProviderNormal never hits
                     // this because it never places a fluid block at all;
                     // this is the actual nether-specific trigger, and it
                     // doesn't depend on structure size -- destroyChunks()
                     // always aligns its range to chunk boundaries, so
                     // decay routinely lands lava right on a chunk edge.
                     // applyPhysics=false skips that hook entirely.
                     block.setType(Material.LAVA, false);
                  }

                  // (No separate STATIONARY_WATER -> STATIONARY_LAVA step any
                  // more: still and flowing water are one material, so the
                  // WATER -> LAVA conversion above covers both.)

                  if (block.getType() == Material.GRASS_BLOCK || Mats.DIRT_FAMILY.contains(block.getType())) {
                     // Grass/dirt reads as a leftover overworld texture
                     // sitting in the nether -- netherrack fits the biome
                     // and matches how the rest of this pass reworks
                     // out-of-place materials (stone/brick -> netherrack a
                     // few lines below). Grass used to become DIRT here
                     // first, requiring a second pass to finish the job;
                     // going straight to NETHERRACK for both does it in one.
                     block.setType(Material.NETHERRACK, false);
                  }

                  // (The old "yellow flower AND red rose -> long grass" step
                  // that sat here required one block to be two different
                  // materials at once, so it never fired; flowers are deleted
                  // by the isDeleted() step below regardless.)

                  if (isDeleted(block)) {
                     block.setType(Material.AIR, false);
                  }

                  // Only the 4 horizontal neighbours can cross a chunk
                  // boundary (chunks span full world height, so up/down
                  // never does). Calling getRelative()/getBlockAt() on a
                  // coordinate in a chunk that isn't loaded yet doesn't
                  // just read air -- Bukkit synchronously GENERATES that
                  // chunk right there, which runs this same populator
                  // again, which can reach into another unloaded neighbour,
                  // and so on. Recursing deep enough overflows the stack --
                  // that's the actual StackOverflowError this used to throw
                  // during pregen. getSafeRelative returns null instead of
                  // triggering generation when the target chunk isn't
                  // already loaded; a null neighbour is treated as
                  // "unknown, assume not empty" below, same conservative
                  // default as any other non-empty block.
                  Block[] neighbors = new Block[]{
                          block.getRelative(0, 1, 0),
                          this.getSafeRelative(world, block, 0, 0, -1),
                          this.getSafeRelative(world, block, 0, 0, 1),
                          this.getSafeRelative(world, block, 1, 0, 0),
                          this.getSafeRelative(world, block, -1, 0, 0),
                          block.getRelative(0, -1, 0)
                  };
                  if (y < groundHeight - 1 && holeNoise > fulldecay) {
                     // Same applyPhysics=false reasoning as the WATER->LAVA
                     // conversion above.
                     block.setType(Material.LAVA, false);
                  } else if (!block.isEmpty() && holeNoise > fulldecay) {
                     block.setType(Material.AIR, false);
                  } else if (this.isValid(block) && holeNoise > partialdecay) {
                     Material partialType = block.getType();
                     if (Mats.STONE_FAMILY.contains(partialType) || Mats.SANDSTONE_FAMILY.contains(partialType)
                             || partialType == Material.COBBLESTONE || partialType == Material.BRICKS
                             || Mats.STONE_BRICK_FAMILY.contains(partialType)) {
                        if (this.random.nextInt(100) >= 40) {
                           block.setType(Material.NETHERRACK, false);
                        }
                     } else {
                        block.setType(Material.AIR, false);
                     }

                     if (block.isEmpty() && !neighbors[5].isEmpty()) {
                        int prob = 0;

                        for (int i = 0; i < neighbors.length; i++) {
                           // Treat an unfetched (chunk-boundary, not yet
                           // loaded) neighbour as "not empty" -- the same
                           // conservative default as any other solid block,
                           // rather than skipping it or crashing on a null.
                           prob += (neighbors[i] != null && neighbors[i].isEmpty()) ? 1 : 0;
                        }

                        if (this.random.nextInt(500) < prob) {
                           block.setType(Material.LAVA, false);
                        }
                     }
                  }
               }
            }
         }
      }
   }

   /**
    * Like block.getRelative(dx, dy, dz), but for the horizontal directions
    * that can cross a chunk boundary: returns null instead of fetching a
    * block in a chunk that isn't already loaded, so it can never trigger
    * synchronous chunk generation (and the re-entrant populator recursion
    * that caused the StackOverflowError this replaces).
    */
   private Block getSafeRelative(World world, Block block, int dx, int dy, int dz) {
      int targetX = block.getX() + dx;
      int targetZ = block.getZ() + dz;
      int targetChunkX = targetX >> 4;
      int targetChunkZ = targetZ >> 4;

      if ((targetChunkX != block.getX() >> 4 || targetChunkZ != block.getZ() >> 4)
              && !world.isChunkLoaded(targetChunkX, targetChunkZ)) {
         return null;
      }

      return block.getRelative(dx, dy, dz);
   }

   protected static boolean isOnFire(Block block, Block above) {
      return block.getType().isBurnable() && above.isEmpty();
   }

   protected static boolean isRemovable(Block block) {
      return removableBlocks.contains(block.getType());
   }

   protected static boolean isDeleted(Block block) {
      return deletedBlocks.contains(block.getType());
   }
}