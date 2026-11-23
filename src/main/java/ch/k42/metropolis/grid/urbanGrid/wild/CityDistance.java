package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How far a world column is from the nearest farm or city block of the plan. A pure function of the plan (so of the seed and the
 * position): every chunk, in any order, gets the same answer. It is what keeps the harsh wilderness biomes away from the cities
 * (see WildBiomes.keepAway): a farm beside forest looks natural, a farm beside desert does not.
 *
 * The distance is measured to the plan's block rectangles, once per chunk centre, and read between the centres by bilinear
 * interpolation, so it varies smoothly across the land and costs four cached lookups per column.
 */
public final class CityDistance {
   private static final int CACHE = 16384;

   private final WorldLayout layout;
   private final int capChunks;
   private final Map<Long, Float> cache = new LinkedHashMap<Long, Float>(1024, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, Float> eldest) {
         return size() > CACHE;
      }
   };

   /** @param capChunks distances beyond this are all "far": nothing needs to know more than the largest keep-away distance */
   public CityDistance(WorldLayout layout, int capChunks) {
      this.layout = layout;
      this.capChunks = Math.max(1, capChunks);
   }

   /** Does a block of this context count as a city or a farm: anything built or worked, not wilderness, water or street. */
   static boolean civilised(ContextType t) {
      return t != null && t != ContextType.FOREST && t != ContextType.UNDEFINED && t != ContextType.STREET
              && t != ContextType.HIGHWAY && t != ContextType.RIVER && t != ContextType.OCEAN;
   }

   /** Blocks from the column (x, z) to the nearest farm or city block, at most capChunks * 16. */
   public double at(int x, int z) {
      double u = (x - 8) / 16.0;
      double v = (z - 8) / 16.0;
      int cx = (int) Math.floor(u);
      int cz = (int) Math.floor(v);
      double fx = u - cx;
      double fz = v - cz;
      double a = this.sample(cx, cz);
      double b = this.sample(cx + 1, cz);
      double c = this.sample(cx, cz + 1);
      double d = this.sample(cx + 1, cz + 1);
      return (a * (1.0 - fx) + b * fx) * (1.0 - fz) + (c * (1.0 - fx) + d * fx) * fz;
   }

   private double sample(int cx, int cz) {
      long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
      synchronized (this.cache) {
         Float known = this.cache.get(key);
         if (known != null) {
            return known;
         }
      }
      float value = this.compute(cx, cz);       // outside the lock: it may make the layout solve a domain
      synchronized (this.cache) {
         this.cache.put(key, value);
      }
      return value;
   }

   private float compute(int cx, int cz) {
      double px = cx * 16.0 + 8.0;
      double pz = cz * 16.0 + 8.0;
      double best = this.capChunks * 16.0;
      int reach = this.capChunks + 1;
      for (WorldLayout.Rect r : this.layout.blocksIn(cx - reach, cz - reach, cx + reach, cz + reach)) {
         if (!civilised(this.layout.contextOf(r))) {
            continue;
         }
         double dx = Math.max(Math.max(r.minX * 16.0 - px, 0.0), px - (r.maxX + 1) * 16.0);
         double dz = Math.max(Math.max(r.minZ * 16.0 - pz, 0.0), pz - (r.maxZ + 1) * 16.0);
         double d = Math.sqrt(dx * dx + dz * dz);
         if (d < best) {
            best = d;
         }
      }
      return (float) best;
   }
}
