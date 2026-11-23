package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.minions.VoronoiGenerator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.World;

public class NetherEnvironmentProvider extends EnvironmentProvider {
   VoronoiGenerator lava;

   // checkBlock()'s noise result depends only on (x, z) -- posY never
   // factors into maxHeight/innerHeight/overlay below -- but it's called
   // once per (x, y, z), including from FileClipboard/ClipboardWE's
   // placement loop, which calls it once per block of a schematic. For a
   // 30-block-tall building that's the same (x, z) noise recomputed 30
   // times in a row. A small bounded cache collapses that back down to
   // one computation per column; it's an LRU (eldest-entry eviction) with
   // a fixed cap rather than an unbounded map, since this same provider
   // instance also lives for the entire world's lifetime and gets called
   // during ordinary terrain generation as new chunks are explored --
   // without a cap that would grow forever.
   private static final int CACHE_CAP = 8192;
   private final Map<Long, Double> overlayCache = new LinkedHashMap<Long, Double>(CACHE_CAP, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, Double> eldest) {
         return size() > CACHE_CAP;
      }
   };

   public NetherEnvironmentProvider(Long seed) {
      super(seed);
      this.lava = new VoronoiGenerator(seed, (short)0);
   }

   private double getOverlay(int posX, int posZ) {
      long key = ((long)posX << 32) ^ (posZ & 0xFFFFFFFFL);
      // synchronized: this NetherEnvironmentProvider instance is created
      // once per world and shared between generateBlockSections() (raw
      // terrain generation) and FileClipboard/ClipboardWE's place()
      // (structure placement during populate()). Whether those can ever
      // run concurrently on different threads isn't something I could
      // confirm, and LinkedHashMap isn't thread-safe -- concurrent
      // get()/put() on it can corrupt its internal structure or, worse,
      // infinite-loop during a resize. The cache lookup itself is cheap,
      // so paying for a lock here costs effectively nothing next to the
      // noise computation it's avoiding.
      synchronized (this.overlayCache) {
         Double cached = this.overlayCache.get(key);
         if (cached != null) {
            return cached;
         }

         double frequency = 0.01;
         double frequency2 = 0.1;
         int size = 1;
         double maxHeight = this.lava.noise(posX / size, posZ / size, frequency);
         double innerHeight = this.lava.noise(posX / size, posZ / size, frequency2);
         double overlay = maxHeight + innerHeight / 10.0;
         this.overlayCache.put(key, overlay);
         return overlay;
      }
   }

   @Override
   public Material checkBlock(World world, int posX, int posY, int posZ) {
      double overlay = this.getOverlay(posX, posZ);
      if (overlay < 0.06) {
         if (posY > 64) {
            return Material.AIR;
         }

         int cut = (int)((0.06 - overlay) / 0.06 * 46.0);
         if (posY > 64 - cut) {
            if (posY > 56) {
               return Material.AIR;
            }

            // STATIONARY_LAVA, not LAVA: this runs once per block for every
            // block in a pasted schematic's footprint (see FileClipboard
            // .place() / ClipboardWE.place()). LAVA is the active/flowing
            // fluid variant that keeps scheduling physics/fluid-tick
            // updates on itself until it settles into STATIONARY_LAVA --
            // vanilla fluids do that settling gradually over many ticks,
            // but a schematic paste sets every one of these blocks
            // synchronously in a single tick. For a small building that's
            // a handful of blocks and goes unnoticed; for a nether city
            // schematic spanning a large multi-chunk footprint sitting
            // over the lava sea, it's thousands of active-fluid blocks
            // getting flooded with tick updates in one go, which is enough
            // to stall the main thread and trip the watchdog. Returning
            // the already-settled variant (matches CavePopulator's lava
            // pools, which do this correctly) places the same visual lava
            // without the update storm.
            return Material.LAVA;
         }
      }

      return null;
   }
}