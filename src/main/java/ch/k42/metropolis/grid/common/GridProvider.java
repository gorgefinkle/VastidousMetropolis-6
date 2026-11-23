package ch.k42.metropolis.grid.common;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.WatchdogKeepAlive;
import ch.k42.metropolis.plugin.PluginConfig;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.concurrent.ExecutionException;

public class GridProvider {
   public static final int GRID_SIZE = 64;
   // Was an unbounded HashBasedTable -- every 64x64-chunk tile a player
   // ever loaded a chunk in stayed cached (a full Parcel[64][64] plus
   // every District object carved for it) for the entire server lifetime,
   // with nothing ever evicted. On a long-running server with a
   // meaningfully explored map that's a genuine, permanently-growing
   // memory leak. Bounded to PluginConfig.getMaxCachedGridTiles() tiles,
   // evicting least-recently-used ones once the limit is exceeded -- an
   // evicted tile just gets recomputed deterministically (same world
   // seed) if a player wanders back into that area later, at the cost of
   // one grid regeneration rather than an ever-growing permanent hold.
   // R30ea reference contract: bound the tile cache by BOTH the configured
   // maximum and the JVM heap budget. Do not add reload/repaint/repair passes.
   // Heap budget: 16..256 tiles at 192 MiB per tile-budget step.
   private static long budgetedCacheLimit() {
      long maxHeap = Runtime.getRuntime().maxMemory();
      long heapBudget = Math.max(16L, Math.min(256L, maxHeap / 201326592L));
      return Math.min(Math.max(1L, PluginConfig.getMaxCachedGridTiles()), heapBudget);
   }

   private final Cache<Long, Grid> grids = CacheBuilder.newBuilder()
           .maximumSize(budgetedCacheLimit())
           .build();
   private MetropolisGenerator generator;

   public GridProvider(MetropolisGenerator generator) {
      this.generator = generator;
   }

   public Grid getGrid(int chunkX, int chunkZ) {
      final int originX = getGridOrigin(chunkX);
      final int originZ = getGridOrigin(chunkZ);
      try {
         return this.grids.get(packKey(originX, originZ), () -> this.getNewGrid(originX, originZ));
      } catch (ExecutionException e) {
         // getNewGrid() doesn't declare any checked exceptions itself, so
         // this only wraps something already unchecked (e.g. a bug in the
         // UrbanGrid constructor) -- unwrap and rethrow rather than force
         // every one of getGrid()'s six call sites (several inside
         // Bukkit's populate()/postPopulate() callbacks, where a checked
         // throws clause would be awkward at best) to handle a checked
         // exception that was never actually part of this method's
         // original contract.
         Throwable cause = e.getCause();
         if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
         }
         throw new RuntimeException("Failed to build grid tile at (" + originX + "," + originZ + ")", cause);
      }
   }

   private Grid getNewGrid(int chunkX, int chunkZ) {
      int originX = getGridOrigin(chunkX);
      int originZ = getGridOrigin(chunkZ);
      long seed = this.generator.getWorldSeed();
      // Planning a tile runs inside populate() on the server thread and can take close to a minute for a city tile;
      // Paper's watchdog stops the server at timeout-time (60 s), so say that the thread is busy, not dead.
      try (WatchdogKeepAlive.Token plan = WatchdogKeepAlive.begin("Planning world tile (" + originX + "," + originZ + ")")) {
         return new UrbanGrid(this, new GridRandom(seed, originX, originZ), this.generator, new Cartesian2D(originX, originZ));
      }
   }

   public Grid peekGrid(int chunkX, int chunkZ) {
      chunkX = getGridOrigin(chunkX);
      chunkZ = getGridOrigin(chunkZ);
      return this.grids.getIfPresent(packKey(chunkX, chunkZ));
   }

   private static long packKey(int chunkX, int chunkZ) {
      return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
   }

   private static int getGridOrigin(int chunk) {
      int ret;
      if (chunk < 0) {
         chunk++;
         ret = chunk - chunk % 64;
         ret -= 64;
      } else {
         ret = chunk - chunk % 64;
      }

      return ret;
   }
}