package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * Seeded 2-D simplex noise in about [-1, 1], pure Java (no Bukkit), so the wilderness fields are the same on every version
 * and can be run and tested outside the server. A pure function of (seed, x, z): any chunk, in any order, gets the same value.
 */
public final class Noise2D {
   private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
   private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
   private static final int[][] GRAD = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};

   private final byte[] perm = new byte[512];

   public Noise2D(long seed) {
      int[] p = new int[256];
      for (int i = 0; i < 256; i++) {
         p[i] = i;
      }
      long s = seed;
      for (int i = 255; i > 0; i--) {
         s = mix(s + i);
         int j = (int) Math.floorMod(s, (long) (i + 1));
         int t = p[i];
         p[i] = p[j];
         p[j] = t;
      }
      for (int i = 0; i < 512; i++) {
         this.perm[i] = (byte) p[i & 255];
      }
   }

   public static long mix(long z) {
      z += 0x9E3779B97F4A7C15L;
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      return z ^ (z >>> 31);
   }

   private int p(int i) {
      return this.perm[i & 511] & 255;
   }

   /** Simplex noise at (x, z), about [-1, 1]. */
   public double noise(double x, double z) {
      double s = (x + z) * F2;
      int i = (int) Math.floor(x + s);
      int j = (int) Math.floor(z + s);
      double t = (i + j) * G2;
      double x0 = x - (i - t);
      double z0 = z - (j - t);
      int i1 = x0 > z0 ? 1 : 0;
      int j1 = x0 > z0 ? 0 : 1;
      double x1 = x0 - i1 + G2;
      double z1 = z0 - j1 + G2;
      double x2 = x0 - 1.0 + 2.0 * G2;
      double z2 = z0 - 1.0 + 2.0 * G2;
      double n = corner(i, j, x0, z0) + corner(i + i1, j + j1, x1, z1) + corner(i + 1, j + 1, x2, z2);
      return Math.max(-1.0, Math.min(1.0, 70.0 * n));
   }

   private double corner(int i, int j, double x, double z) {
      double t = 0.5 - x * x - z * z;
      if (t < 0.0) {
         return 0.0;
      }
      int[] g = GRAD[p(i + p(j)) & 7];
      t *= t;
      return t * t * (g[0] * x + g[1] * z);
   }

   /** Fractal sum of octaves, normalised to about [-1, 1]. */
   public double fbm(double x, double z, int octaves, double gain) {
      double sum = 0.0;
      double amp = 1.0;
      double norm = 0.0;
      double f = 1.0;
      for (int o = 0; o < octaves; o++) {
         sum += amp * this.noise(x * f + o * 31.7, z * f - o * 17.3);
         norm += amp;
         amp *= gain;
         f *= 2.0;
      }
      return sum / norm;
   }

   /** Ridged noise in [0, 1]: sharp crests where the underlying noise crosses zero. */
   public double ridged(double x, double z, int octaves) {
      double sum = 0.0;
      double amp = 1.0;
      double norm = 0.0;
      double f = 1.0;
      double weight = 1.0;
      for (int o = 0; o < octaves; o++) {
         double r = 1.0 - Math.abs(this.noise(x * f + o * 19.1, z * f + o * 43.9));
         r *= r;
         r *= weight;
         weight = Math.max(0.0, Math.min(1.0, r * 1.6));
         sum += r * amp;
         norm += amp;
         amp *= 0.5;
         f *= 2.0;
      }
      return sum / norm;
   }
}
