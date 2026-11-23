package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworks;
import ch.k42.metropolis.plugin.PluginConfig;

import java.util.HashMap;
import java.util.Map;

/** One shared CityLayout per world seed and settings, so every tile and every road piece reads the same one. */
public final class CityLayouts {
   private static final Map<String, CityLayout> CACHE = new HashMap<String, CityLayout>();

   private CityLayouts() {
   }

   /** The layout for this seed, or null when the city highway network (which defines the city boxes) is off. */
   public static synchronized CityLayout forSeed(long seed) {
      if (!PluginConfig.isHighwayNetworkEnabled()) {
         return null;
      }
      int spacing = PluginConfig.getCityRingsSpacing();
      int blockSize = PluginConfig.getBlockSize();
      int blockSizeMin = PluginConfig.getBlockSizeMin();
      int sigma = PluginConfig.getSigmaCut();
      String key = seed + ":" + spacing + ":" + blockSize + ":" + blockSizeMin + ":" + sigma + ":"
              + PluginConfig.getCityRingsRadius() + ":" + PluginConfig.getCityRingsSizeVariation() + ":"
              + PluginConfig.getHighwayNetworkBoxScale();
      CityLayout layout = CACHE.get(key);
      if (layout == null) {
         layout = new CityLayout(HighwayNetworks.forSeed(seed), seed, spacing, blockSize, blockSizeMin, sigma);
         CACHE.put(key, layout);
      }
      return layout;
   }
}