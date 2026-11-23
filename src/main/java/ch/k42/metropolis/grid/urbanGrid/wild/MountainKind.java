package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * The kinds of mountain the mountain biome can be. Which kind a patch of mountains is comes from a slow noise (see WildRelief), so
 * a patch has a character of its own and kinds grade into one another where patches meet. The enum order is the order they sit in
 * along that noise, so neighbouring kinds in this list are the ones that border each other: put a new kind next to the ones it
 * should look like.
 *
 * Adding a kind: add a constant here, add its profile to WildRelief.profile() (a function of x and z returning blocks above the
 * baseline), and its share is read from generator.forest.mountains.kinds.[key] (the default below applies when the key is missing).
 */
public enum MountainKind {
   //        key        share
   MASSIF  ("massif",   20),   // rounded old mountains, forested to the top
   PEAKS   ("peaks",    35),   // small sharp peaks: the original mountains
   RANGE   ("range",    25),   // long high chains with broad shoulders, the biggest of them
   PLATEAU ("plateau",  12),   // high tableland: flat-topped, gullied, ending in escarpments
   VOLCANO ("volcano",   8);   // isolated cones with a crater

   public static final MountainKind[] ALL = values();

   private final String key;
   private final int defaultShare;

   MountainKind(String key, int defaultShare) {
      this.key = key;
      this.defaultShare = defaultShare;
   }

   /** The name used in the config (generator.forest.mountains.kinds.[key]). */
   public String key() {
      return this.key;
   }

   /** Percent of the mountain biome this kind covers when the config does not say. */
   public int defaultShare() {
      return this.defaultShare;
   }
}
