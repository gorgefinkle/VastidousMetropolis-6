package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * Named wilderness habitats and their original R30ea identities.
 * The source is recovered from the October 31 original class together with
 * the November 18 classfile enum table.  Do not reorder: the ordinal is a
 * persistent input to generation and biome-weight vectors.
 */
public enum WildBiome {
   FOREST("forest", null, 56, 0, "forest", 1.0, 0.0),
   MOUNTAINS("mountains", null, 18, 0, "forest", 0.0, 0.0),
   DESERT("desert", null, 20, 28, "dryForest", 0.0, 0.0),
   MESA("mesa", null, 12, 28, "dryForest", 0.0, 0.0),
   DRY_FOREST("dryForest", "forest", 8, 8, "forest", 0.45, 1.0),
   RAINFOREST("rainforest", "forest", 6, 10, "forest", 1.0, 0.0),
   PRAIRIE("prairie", "forest", 10, 0, "forest", 0.29, 0.0),
   WETLANDS("wetlands", "forest", 0, 2, "forest", 0.34, 0.0),
   OLD_GROWTH("oldGrowth", "forest", 7, 5, "forest", 1.0, 0.0),
   DECIDUOUS("deciduous", "forest", 9, 0, "forest", 0.66, 0.0),
   COASTAL_DUNES("coastalDunes", "forest", 4, 0, "forest", 0.0, 0.65),
   VOLCANIC_BARRENS("karstlands", "forest", 3, 8, "forest", 0.0, 0.35),
   BLOOMLANDS("bloomlands", "forest", 2, 12, "forest", 0.12, 0.25);

   public static final WildBiome[] ALL = values();
   private final String key;
   private final String archetypeKey;
   private final int defaultShare;
   private final int defaultAway;
   private final String fallbackKey;
   private final double treeFactor;
   private final double dryness;
   private WildBiome archetype;
   private WildBiome fallback;

   WildBiome(String key, String archetypeKey, int share, int away, String fallbackKey,
             double treeFactor, double dryness) {
      this.key = key;
      this.archetypeKey = archetypeKey;
      this.defaultShare = share;
      this.defaultAway = away;
      this.fallbackKey = fallbackKey;
      this.treeFactor = treeFactor;
      this.dryness = dryness;
   }
   public String key() { return key; }
   public int defaultShare() { return defaultShare; }
   public int defaultAway() { return defaultAway; }
   public WildBiome archetype() { return archetype; }
   public boolean is(WildBiome other) { return archetype == other; }
   public WildBiome fallback() { return fallback; }
   public double treeFactor() { return treeFactor; }
   public double dryness() { return dryness; }

   public static WildBiome byKey(String key) {
      if (key == null) return null;
      String s = key.trim();
      for (WildBiome biome : ALL) {
         if (biome.key.equalsIgnoreCase(s) || biome.name().equalsIgnoreCase(s)) return biome;
      }
      return null;
   }
   public static double weight(double[] weights, WildBiome family) {
      double sum = 0.0;
      for (int i = 0; i < weights.length && i < ALL.length; ++i) {
         if (ALL[i].archetype == family) sum += weights[i];
      }
      return sum;
   }
   public static double treeWeight(double[] weights) {
      double sum = 0.0;
      for (int i = 0; i < weights.length && i < ALL.length; ++i) {
         sum += ALL[i].treeFactor * weights[i];
      }
      return sum;
   }
   static {
      for (WildBiome biome : ALL) {
         biome.archetype = biome.archetypeKey == null ? biome : byKey(biome.archetypeKey);
         biome.fallback = byKey(biome.fallbackKey);
         if (biome.archetype == null || biome.fallback == null) {
            throw new IllegalStateException("Missing archetype/fallback for " + biome.name());
         }
      }
      for (WildBiome biome : ALL) {
         if (biome.archetype.archetype != biome.archetype) {
            throw new IllegalStateException("Nested archetype for " + biome.name());
         }
      }
   }
}
