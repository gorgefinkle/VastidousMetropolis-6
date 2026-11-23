package ch.k42.metropolis.grid.urbanGrid.highway;

import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterFields;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.plugin.PluginConfig;

import java.util.HashMap;
import java.util.Map;

/** One shared HighwayNetwork per world seed and settings, so every tile and the zoning field see the same one. */
public final class HighwayNetworks {
   private static final Map<String, HighwayNetwork> CACHE = new HashMap<String, HighwayNetwork>();

   private HighwayNetworks() {
   }

   public static synchronized HighwayNetwork forSeed(long seed) {
      int spacing = PluginConfig.getCityRingsSpacing();
      int radius = PluginConfig.getCityRingsRadius();
      int variation = PluginConfig.getCityRingsSizeVariation();
      int boxScale = PluginConfig.getHighwayNetworkBoxScale();
      int extra = PluginConfig.getHighwayNetworkExtraLinkPercent();
      boolean ocean = OceanConfig.isOceanEnabled();
      // lane edges on a lattice of blockSizeMin + 2 chunks (see HighwayNetwork): no gap or ledge narrower than a block
      int minBlock = PluginConfig.getBlockSizeMin();
      int lattice = minBlock >= 1 && PluginConfig.getBlockSize() >= 2 * minBlock ? minBlock + 2 : 1;
      String key = seed + ":" + spacing + ":" + radius + ":" + variation + ":" + boxScale + ":" + extra
              + ":coast-v1:river-channel-v1:" + ocean + ":" + lattice
              + ":" + RiverConfig.isRiverEnabled() + ":" + RiverConfig.isRiverZBoundEnabled();
      HighwayNetwork network = CACHE.get(key);
      if (network == null) {
         WaterField water = (ocean || RiverConfig.isRiverEnabled()) ? WaterFields.forSeed(seed, lattice) : null;
         HighwayNetwork.Mask waterExclusion = ocean ? new OceanHighwaySafety(water.ocean()) : null;
         HighwayNetwork.LinkWaterSafety freshwater = RiverConfig.isRiverEnabled()
                 ? new RiverHighwaySafety(water.mainRiver(), water.zBoundRiver()) : null;
         network = new HighwayNetwork(seed, spacing, radius, variation / 100.0, boxScale / 100.0, extra / 100.0, 20,
                 waterExclusion, lattice, freshwater);
         CACHE.put(key, network);
      }
      return network;
   }

}
