package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * Stateless, seed-driven randomness for farm generation. Everything the farm
 * generator decides is a pure function of (world seed, coordinates, salt), so
 * every chunk of a region reaches the same answer without reading the world.
 */
final class FarmRandom {
   private FarmRandom() {
   }

   /** SplitMix64-style position hash, stable across runs and chunk order. */
   static long hash(long seed, int x, int z, int salt) {
      long h = seed + 0x9E3779B97F4A7C15L * (x * 0x632BE5ABL + 1)
              + 0xC2B2AE3D27D4EB4FL * (z * 0x27D4EB2FL + 1) + salt * 0x165667B19E3779F9L;
      h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
      h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
      return h ^ (h >>> 31);
   }

   /** Uniform [0,1) from a hash. */
   static double unit(long h) {
      return (h >>> 11) * (1.0 / 9007199254740992.0);
   }

   static double unit(long seed, int x, int z, int salt) {
      return unit(hash(seed, x, z, salt));
   }

   static int floorDiv(int a, int b) {
      int q = a / b;
      return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
   }
}
