package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;

import java.util.HashMap;
import java.util.Map;

/** One shared WaterField per world seed. Built exactly the way UrbanGrid builds its own providers, so they agree. */
public final class WaterFields {
   private static final Map<Long, WaterField> CACHE = new HashMap<Long, WaterField>();

   private WaterFields() {
   }

   public static synchronized WaterField forSeed(long seed, int lattice) {
      long key = seed * 31 + lattice;
      WaterField field = CACHE.get(key);
      if (field == null) {
         OceanProvider ocean = new OceanProvider(seed);
         RiverProvider main;
         RiverProvider z = null;
         boolean converge = OceanConfig.isOceanEnabled() && RiverConfig.isRiverEnabled() && RiverConfig.isRiverConvergeOnOcean();
         if (converge) {
            double cx = ocean.getCenterChunkX();
            double cz = ocean.getCenterChunkZ();
            main = new RiverProvider(seed, false, cx, cz);
            z = RiverConfig.isRiverZBoundEnabled() ? new RiverProvider(seed, true, cz, cx) : null;
            ocean.attachRivers(main, z);
         } else {
            main = new RiverProvider(seed);
         }
         field = new WaterField(main, z, ocean, lattice);
         CACHE.put(key, field);
      }
      return field;
   }
}
