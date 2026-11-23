package ch.k42.metropolis.generator.populators;

import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanFloorSafety;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.minions.XYZ;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.generator.BlockPopulator;

public class CavePopulator extends BlockPopulator {
   private static final int MAX_LEVEL = 45;

   // Null when the ocean is disabled -- every check below treats that as
   // "nothing to protect", so this is the only place that needs to know
   // OceanConfig.isOceanEnabled() at all. Built ONCE, eagerly, from the
   // constructor rather than lazily inside populate(): this single
   // CavePopulator instance is shared across the whole world (see
   // MetropolisGenerator.getDefaultPopulators()) and Bukkit can call
   // populate() for different chunks from different threads, so a lazy
   // "build it the first time it's needed" field would race. OceanProvider
   // itself, like everywhere else it's used, is a stateless pure function
   // of worldSeed and safe to share and query concurrently once built.
   private final OceanProvider oceanProvider;
   // The original R30ea uses a 4096-entry synchronized LRU for coastal
   // sampling. It is a performance cache only; sample locations are integral
   // block coordinates so a cache hit cannot change the planned shore.
   private final Map<Long, OceanProvider.Sample> shoreSamples = new LinkedHashMap<>(4096, 0.75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Long, OceanProvider.Sample> eldest) {
         return size() > 4096;
      }
   };

   public CavePopulator(long worldSeed) {
      if (!OceanConfig.isOceanEnabled()) {
         this.oceanProvider = null;
         return;
      }
      OceanProvider ocean = new OceanProvider(worldSeed);
      // R30ea uses the same receiving rivers when querying ocean-floor safety
      // as when carving the actual shore. Without this handoff the cave guard
      // can sample a different mouth/shoreline geometry near confluences.
      if (RiverConfig.isRiverEnabled() && RiverConfig.isRiverConvergeOnOcean()) {
         double centerX = ocean.getCenterChunkX();
         double centerZ = ocean.getCenterChunkZ();
         RiverProvider xBound = new RiverProvider(worldSeed, false, centerX, centerZ);
         RiverProvider zBound = RiverConfig.isRiverZBoundEnabled()
                 ? new RiverProvider(worldSeed, true, centerZ, centerX) : null;
         ocean.attachRivers(xBound, zBound);
      }
      this.oceanProvider = ocean;
   }

   private OceanProvider.Sample shorelineSample(int x, int z) {
      long key = ((long)x << 32) ^ (z & 0xffffffffL);
      synchronized (shoreSamples) {
         OceanProvider.Sample result = shoreSamples.get(key);
         if (result != null) return result;
         result = oceanProvider.sample((double)x, (double)z);
         shoreSamples.put(key, result);
         return result;
      }
   }

   public void populate(World world, Random random, Chunk source) {
      if (random.nextInt(100) < PluginConfig.getCaveChance()) {
         int x = 4 + random.nextInt(8) + source.getX() * 16;
         int z = 4 + random.nextInt(8) + source.getZ() * 16;
         int maxY = world.getHighestBlockYAt(x, z);
         if (maxY < 16) {
            maxY = 32;
         }

         int y = random.nextInt(maxY);
         Set<XYZ> snake = selectBlocksForCave(world, random, x, y, z);
         CavePopulator.CaveType caveType = CavePopulator.CaveType.getRandom(random);
         this.buildCave(world, snake.toArray(new XYZ[0]), caveType);

         for (XYZ block : snake) {
            world.unloadChunkRequest(block.x / 16, block.z / 16);
         }
      }
   }

   /**
    * True if it's safe to call getBlockAt()/getBlockTypeIdAt() for this
    * column without risking a synchronous chunk generation. The starting
    * point of a cave snake is always inside the currently-populating
    * (already loaded) chunk, but the drift/carve logic below can wander or
    * reach many blocks away over up to 1200 iterations -- easily crossing
    * into a neighbouring chunk that isn't generated yet. Reading a block
    * there doesn't just return air, it makes Bukkit generate that chunk
    * right there, re-entering this same populator and risking the same
    * kind of recursive stack overflow the nether decay/lava code hit.
    */
   private static boolean isSafe(World world, int x, int z) {
      return world.isChunkLoaded(x >> 4, z >> 4);
   }

   static Set<XYZ> selectBlocksForCave(World world, Random random, int blockX, int blockY, int blockZ) {
      Set<XYZ> snakeBlocks = new HashSet<>();
      int airHits = 0;
      XYZ block = new XYZ();

      while (airHits <= 1200 && blockY <= 45) {
         if (random.nextInt(20) == 0) {
            blockY++;
         } else if (isSafe(world, blockX, blockZ) && world.getBlockAt(blockX, blockY + 2, blockZ).getType().isAir()) {
            blockY += 2;
         } else if (isSafe(world, blockX + 2, blockZ) && world.getBlockAt(blockX + 2, blockY, blockZ).getType().isAir()) {
            blockX++;
         } else if (isSafe(world, blockX - 2, blockZ) && world.getBlockAt(blockX - 2, blockY, blockZ).getType().isAir()) {
            blockX--;
         } else if (isSafe(world, blockX, blockZ + 2) && world.getBlockAt(blockX, blockY, blockZ + 2).getType().isAir()) {
            blockZ++;
         } else if (isSafe(world, blockX, blockZ - 2) && world.getBlockAt(blockX, blockY, blockZ - 2).getType().isAir()) {
            blockZ--;
         } else if (isSafe(world, blockX + 1, blockZ) && world.getBlockAt(blockX + 1, blockY, blockZ).getType().isAir()) {
            blockX++;
         } else if (isSafe(world, blockX - 1, blockZ) && world.getBlockAt(blockX - 1, blockY, blockZ).getType().isAir()) {
            blockX--;
         } else if (isSafe(world, blockX, blockZ + 1) && world.getBlockAt(blockX, blockY, blockZ + 1).getType().isAir()) {
            blockZ++;
         } else if (isSafe(world, blockX, blockZ - 1) && world.getBlockAt(blockX, blockY, blockZ - 1).getType().isAir()) {
            blockZ--;
         } else if (random.nextBoolean()) {
            if (random.nextBoolean()) {
               blockX++;
            } else {
               blockZ++;
            }
         } else if (random.nextBoolean()) {
            blockX--;
         } else {
            blockZ--;
         }

         if (!isSafe(world, blockX, blockZ)) {
            // The walk stepped into an ungenerated chunk despite every
            // move above being guarded -- can happen via the unguarded
            // random-drift fallback branches (the three "else" cases with
            // no isSafe check, kept unguarded on purpose since they're the
            // snake's only way to escape a dead end). Stop rather than
            // read/carve there.
            break;
         }

         if (world.getBlockAt(blockX, blockY, blockZ).getType().isAir()) {
            airHits++;
         } else {
            int radius = 2 + random.nextInt(4);
            int radius2 = radius * radius + 1;

            for (int x = -radius; x <= radius; x++) {
               for (int y = -radius; y <= radius; y++) {
                  for (int z = -radius; z <= radius; z++) {
                     if (x * x + y * y + z * z <= radius2 && y >= 0 && y < 128) {
                        if (!isSafe(world, blockX + x, blockZ + z)) {
                           continue;
                        }

                        if (world.getBlockAt(blockX + x, blockY + y, blockZ + z).getType().isAir()) {
                           airHits++;
                        } else {
                           block.x = blockX + x;
                           block.y = blockY + y;
                           block.z = blockZ + z;
                           if (snakeBlocks.add(block)) {
                              block = new XYZ();
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      return snakeBlocks;
   }

   /**
    * R30ea protects the actual sculpted ocean FLOOR and nearby shoreline,
    * not merely the open water above it. The guard also checks four adjacent
    * columns within the -2..0 edge band so caves cannot undercut the shore.
    */
   private boolean isProtectedOceanFloor(int x, int y, int z) {
      if (oceanProvider == null) return false;
      double margin = OceanConfig.getOceanBankMargin() * 16.0;
      OceanProvider.Sample sample = shorelineSample(x, z);
      if (OceanFloorSafety.protectsCave(y, sample.distanceInside, sample.surfaceY, sample.columnDepth, margin))
         return true;
      if (sample.distanceInside <= -2.0 || sample.distanceInside > 0.0) return false;
      for (int[] neighbor : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
         sample = shorelineSample(x + neighbor[0], z + neighbor[1]);
         if (OceanFloorSafety.protectsCave(y, sample.distanceInside, sample.surfaceY, sample.columnDepth, margin))
            return true;
      }
      return false;
   }

   void buildCave(World world, XYZ[] snakeBlocks, CavePopulator.CaveType caveType) {
      if (snakeBlocks.length > 0) {
         int midpoint = Math.abs(snakeBlocks[0].y + snakeBlocks[snakeBlocks.length - 1].y) / 2;
         int lavapoint = Math.min(midpoint, 24);

         for (XYZ loc : snakeBlocks) {
            if (!isSafe(world, loc.x, loc.z)) continue;
            if (this.isProtectedOceanFloor(loc.x, loc.y, loc.z)) {
               continue; // leave the ocean's own water alone -- see this method's own guard javadoc
            }
            Block block = world.getBlockAt(loc.x, loc.y, loc.z);
            if (!block.isEmpty() && block.getType() != Material.BEDROCK) {
               switch (caveType) {
                  case AIR:
                     block.setType(Material.AIR, false);
                     break;
                  case LAVA:
                     if (loc.y < lavapoint) {
                        // applyPhysics=false: same reasoning as
                        // DecayProviderNether -- setType(Material) alone
                        // fires vanilla's fluid-placement hook, which reads
                        // every neighbouring block's type directly through
                        // the engine, bypassing any chunk-loaded checks of
                        // ours. A cave carved near a chunk edge can place
                        // lava right on that edge, and the neighbour one
                        // step further out was never itself visited/
                        // verified loaded by the walk above.
                        block.setType(Material.LAVA, false);
                     } else {
                        block.setType(Material.AIR, false);
                     }
                     break;
                  case WATER:
                     if (loc.y < midpoint) {
                        block.setType(Material.WATER, false);
                     } else {
                        block.setType(Material.AIR, false);
                     }
               }
            }
         }
      }
   }

   private enum CaveType {
      AIR,
      LAVA,
      WATER;

      public static CavePopulator.CaveType getRandom(Random random) {
         return values()[random.nextInt(values().length)];
      }
   }
}