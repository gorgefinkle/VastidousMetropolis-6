package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworks;
import ch.k42.metropolis.plugin.PluginConfig;

import java.util.HashMap;
import java.util.Map;

/** One shared WorldLayout per world seed and settings, so every tile and every road piece reads the same one. */
public final class WorldLayouts {
   /**
    * Distance in chunks between the street lines that give open land its blocks. Deliberately unrelated to the tile size
    * (64) and shifted by a seed-derived offset, so no street line is tied to a tile edge or to the origin: the layout
    * would come out the same whatever the tile grid was.
    */
   public static final int PITCH = 99;

   private static long mix(long z) {
      z += 0x9E3779B97F4A7C15L;
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      return z ^ (z >>> 31);
   }

   private static final Map<String, WorldLayout> CACHE = new HashMap<String, WorldLayout>();

   private static final Map<WorldLayout, RoadShapes> SHAPES = new java.util.IdentityHashMap<WorldLayout, RoadShapes>();

   private WorldLayouts() {
   }

   /** The road pieces of this seed's layout (see RoadShapes), or null when there is no layout. One per layout. */
   public static synchronized RoadShapes roadShapesFor(long seed) {
      WorldLayout layout = forSeed(seed);
      if (layout == null) {
         return null;
      }
      RoadShapes shapes = SHAPES.get(layout);
      if (shapes == null) {
         shapes = new RoadShapes(layout, HighwayNetworks.forSeed(seed));
         SHAPES.put(layout, shapes);
      }
      return shapes;
   }

   /** The water of this seed (the zone the layout stays out of, and what is in it). */
   public static WaterField waterFor(long seed) {
      return WaterFields.forSeed(seed, PluginConfig.getBlockSizeMin() + 2);
   }

   /** The layout for this seed, or null when the city highway network is off or the block sizes cannot be cut exactly. */
   public static synchronized WorldLayout forSeed(long seed) {
      if (!PluginConfig.isHighwayNetworkEnabled()) {
         return null;
      }
      int minBlock = PluginConfig.getBlockSizeMin();
      int maxBlock = PluginConfig.getBlockSize();
      if (minBlock < 1 || maxBlock < 2 * minBlock) {
         return null;
      }
      String key = seed + ":" + minBlock + ":" + maxBlock + ":" + PluginConfig.getCityRingsSpacing() + ":"
              + PluginConfig.getCityRingsRadius() + ":" + PluginConfig.getCityRingsSizeVariation() + ":"
              + PluginConfig.getHighwayNetworkBoxScale() + ":" + PluginConfig.getBlockSizeSlack();
      WorldLayout layout = CACHE.get(key);
      if (layout == null) {
         int lattice = minBlock + 2;
         // The street lines are on the same lattice as the highway lanes (pitch and offsets are multiples of it), so a
         // lane either lies exactly along a street line or is at least a lattice step from it: no street ever runs
         // beside a lane, and no line has to be dropped (which is what used to chain cells into huge domains).
         int pitch = (PITCH / lattice) * lattice;
         int offX = (int) Math.floorMod(mix(seed ^ 0x57A11E5L), (long) (pitch / lattice)) * lattice;
         int offZ = (int) Math.floorMod(mix(seed ^ 0x2B1D0F3L), (long) (pitch / lattice)) * lattice;
         layout = new WorldLayout(HighwayNetworks.forSeed(seed), seed, minBlock, maxBlock, pitch, offX, offZ,
                 WaterFields.forSeed(seed, lattice));
         layout.setBlockSlack(PluginConfig.getBlockSizeSlack());
         CACHE.put(key, layout);
      }
      return layout;
   }
}
