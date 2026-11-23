package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworks;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Arrays;
import java.util.Random;

/**
 * The "city rings" field the district assigner uses in place of the plain
 * zone noise (generator.cityRings.*).
 *
 * Cities sit on a jittered grid, one per spacing x spacing cell. For any chunk,
 * distance() is the distance to the nearest city centre measured in units of
 * that city's own radius (each city draws its own radius from the world seed,
 * so sizes vary), then wobbled by noise so the outlines aren't circles.
 * Distance 1.0 is the residential edge; the city rungs (highrise ... residential)
 * are laid out inside it, and everything beyond is the outer rung
 * (farm and forest).
 *
 * Within the outer rung, farm is the part within farmBandWidth of the edge and
 * forest is the rest; the edge between them is blurred by noise
 * (farmForestOverlap) so the two interleave. Other two-member rungs
 * (lowrise/industrial, residential/dense) split by an unbiased noise quantile
 * using their configured weights.
 *
 * Everything is a pure function of the world seed and chunk coordinates.
 */
public class CityRingField {
   private static final int OUTER_TIER = 4;
   private static final int CALIBRATION_SAMPLES = 4000;

   private final long seed;
   private final SimplexOctaveGenerator warp;
   private final SimplexOctaveGenerator member;
   private final double radius;
   private final double spacing;
   private final double sizeVariation;
   private final double wobble;
   private final double farmBandWidth;
   private final double overlap;
   private final double[] memberSorted;
   /** The same accepted city boxes used by road planning. */
   private final HighwayNetwork acceptedHighways;

   public boolean cityAccepted(int a, int b) {
      return acceptedHighways == null || acceptedHighways.city(a, b).present;
   }

   public CityRingField(long worldSeed) {
      this(worldSeed, PluginConfig.isHighwayNetworkEnabled() ? HighwayNetworks.forSeed(worldSeed) : null);
   }

   CityRingField(long worldSeed, HighwayNetwork highways) {
      this.acceptedHighways = highways;
      this.seed = worldSeed;
      this.warp = new SimplexOctaveGenerator(worldSeed + 9L, 2);
      this.member = new SimplexOctaveGenerator(worldSeed + 77L, 2);
      this.radius = PluginConfig.getCityRingsRadius();
      this.spacing = PluginConfig.getCityRingsSpacing();
      this.sizeVariation = PluginConfig.getCityRingsSizeVariation() / 100.0;
      this.wobble = PluginConfig.getCityRingsEdgeWobble() / 100.0;
      this.farmBandWidth = PluginConfig.getCityRingsFarmBandWidth() / 100.0;
      this.overlap = PluginConfig.getCityRingsFarmForestOverlap() / 100.0;

      Random r = new Random(mix(worldSeed + 11L));
      this.memberSorted = new double[CALIBRATION_SAMPLES];
      for (int i = 0; i < CALIBRATION_SAMPLES; i++) {
         double x = (r.nextDouble() - 0.5) * 5000.0;
         double z = (r.nextDouble() - 0.5) * 5000.0;
         this.memberSorted[i] = memberNoise(x, z);
      }
      Arrays.sort(this.memberSorted);
   }

   private static long mix(long z) {
      z += 0x9E3779B97F4A7C15L;
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      return z ^ (z >>> 31);
   }

   private static double unit(long h) {
      return (h >>> 11) * (1.0 / 9007199254740992.0);
   }

   private double memberNoise(double x, double z) {
      return this.member.noise(x * 0.1, z * 0.1, 0.3, 0.6, true);
   }

   /** The authoritative city site selected by the SAME raw normalized-distance rule as distance().
    * Its identity is stable across tile boundaries, generation order, and restarts. */
   public static final class Site {
      public final int a, b;
      public final double centerX, centerZ, radius;
      Site(int a, int b, double centerX, double centerZ, double radius) {
         this.a = a; this.b = b; this.centerX = centerX; this.centerZ = centerZ; this.radius = radius;
      }
   }

   public Site site(int a, int b) {
      long h = mix(((long) a * 73856093L) ^ ((long) b * 19349663L) ^ this.seed);
      long h2 = mix(h);
      long h3 = mix(h2);
      double cx = (a + 0.15 + 0.7 * unit(h)) * this.spacing;
      double cz = (b + 0.15 + 0.7 * unit(h2)) * this.spacing;
      double factor = 1.0 - this.sizeVariation + 2.0 * this.sizeVariation * unit(h3);
      return new Site(a, b, cx, cz, this.radius * factor);
   }

   public Site nearestSite(int cx, int cz) {
      int ax = (int) Math.floor(cx / this.spacing);
      int az = (int) Math.floor(cz / this.spacing);
      Site nearest = null;
      double best = Double.MAX_VALUE;
      for (int a = ax - 1; a <= ax + 1; a++) {
         for (int b = az - 1; b <= az + 1; b++) {
            if (!cityAccepted(a, b)) continue;
            Site site = site(a, b);
            double d = Math.hypot(cx - site.centerX, cz - site.centerZ) / site.radius;
            if (d < best) { best = d; nearest = site; }
         }
      }
      return nearest;
   }

   /** Inner city: urban ring, extending through the residential boundary. */
   public boolean withinCity(int cx, int cz, Site site) {
      if (site == null) return false;
      Site nearest = nearestSite(cx, cz);
      return nearest != null && nearest.a == site.a && nearest.b == site.b && distance(cx, cz) <= 1.0;
   }

   /** Outer city: the agricultural belt, stopping where farm yields to forest. */
   public boolean withinOuterCity(int cx, int cz, Site site) {
      if (site == null) return false;
      Site nearest = nearestSite(cx, cz);
      return nearest != null && nearest.a == site.a && nearest.b == site.b
              && (distance(cx, cz) <= 1.0 || isFarm(cx, cz));
   }

   /** Distance to the nearest city, in units of that city's radius, wobbled. 1.0 = residential edge. */
   public double distance(int cx, int cz) {
      int ax = (int) Math.floor(cx / this.spacing);
      int az = (int) Math.floor(cz / this.spacing);
      double best = Double.MAX_VALUE;
      for (int a = ax - 1; a <= ax + 1; a++) {
         for (int b = az - 1; b <= az + 1; b++) {
            if (!cityAccepted(a, b)) continue;
            long h = mix(((long) a * 73856093L) ^ ((long) b * 19349663L) ^ this.seed);
            long h2 = mix(h);
            long h3 = mix(h2);
            double centreX = (a + 0.15 + 0.7 * unit(h)) * this.spacing;
            double centreZ = (b + 0.15 + 0.7 * unit(h2)) * this.spacing;
            double factor = 1.0 - this.sizeVariation + 2.0 * this.sizeVariation * unit(h3);
            double d = Math.hypot(cx - centreX, cz - centreZ) / (this.radius * factor);
            if (d < best) {
               best = d;
            }
         }
      }
      return best * (1.0 + this.wobble * this.warp.noise(cx * 0.02, cz * 0.02, 0.3, 0.6, true));
   }

   /** True where the outer rung is farm rather than forest. */
   public boolean isFarm(int cx, int cz) {
      return distance(cx, cz) + this.overlap * memberNoise(cx, cz) <= 1.0 + this.farmBandWidth;
   }

   private double rank(double v) {
      int lo = 0;
      int hi = this.memberSorted.length;
      while (lo < hi) {
         int mid = (lo + hi) >>> 1;
         if (this.memberSorted[mid] < v) {
            lo = mid + 1;
         } else {
            hi = mid;
         }
      }
      return (double) lo / this.memberSorted.length;
   }

   /** Picks which member of a multi-member rung a district becomes; never null for a non-empty rung. */
   public ContextType pickMember(ContextZone[] zones, int tier, int cx, int cz) {
      if (zones == null || zones.length == 0) {
         return null;
      }
      if (tier == OUTER_TIER) {
         ContextType farm = null;
         ContextType forest = null;
         for (ContextZone z : zones) {
            if (z.getContextType() == ContextType.FARM) {
               farm = ContextType.FARM;
            } else if (z.getContextType() == ContextType.FOREST) {
               forest = ContextType.FOREST;
            }
         }
         if (farm != null && forest != null) {
            return isFarm(cx, cz) ? farm : forest;
         }
      }
      double total = 0.0;
      for (ContextZone z : zones) {
         total += z.getWeight();
      }
      double r = rank(memberNoise(cx, cz));
      double acc = 0.0;
      for (ContextZone z : zones) {
         acc += total > 0.0 ? z.getWeight() / total : 0.0;
         if (r <= acc) {
            return z.getContextType();
         }
      }
      return zones[zones.length - 1].getContextType();
   }
}
