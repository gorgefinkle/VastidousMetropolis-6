package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * Ground relief of the non-forest biomes, in blocks above the baseline before any edge fade. Each is a pure function of
 * (seed, x, z). The forest keeps its own relief (ForestTerrain); the generator blends all four by the biome weights.
 */
public final class WildRelief {
   private final Noise2D mountain, mountainBase, dune, swell, mesa, mesaTop;
   // mountain kinds (see MountainKind)
   private final Noise2D kindNoise, rangeCrest, rangeWarpA, rangeWarpB, rangeShoulder, domeNoise, domeBump, plateauNoise, plateauUndulation, plateauGully, coneFlank;
   private final double[] kindEdges;
   private final double[] kindSample;
   private final double rangeCos, rangeSin;
   private final long coneSeed;
   /** The highest any mountain may rise above the baseline: the world is 256 high and the baseline is about 63. */
   private static final double HARD_CAP = 165.0;
   private final double mountainHeight;
   private final int mesaStep;
   private final int mesaLevels;
   private final double duneScale;

   /**
    * @param mountainHeight peak height of a mountain in blocks above the baseline (forest.mountains.height)
    * @param mesaStep       height of one mesa terrace in blocks
    * @param mesaLevels     how many terraces a mesa can climb
    * @param duneScale      horizontal size of the desert's dunes and swells, 1 = the original size, 2 = twice as wide and long
    */
   public WildRelief(long worldSeed, double mountainHeight, int mesaStep, int mesaLevels, double duneScale) {
      this(worldSeed, mountainHeight, mesaStep, mesaLevels, duneScale, null);
   }

   /** @param kindShares the share of each MountainKind (percent, in MountainKind order, normalised here); null = the defaults */
   public WildRelief(long worldSeed, double mountainHeight, int mesaStep, int mesaLevels, double duneScale, double[] kindShares) {
      long s = worldSeed ^ 0x57494C4452454CL;
      this.kindNoise = new Noise2D(s ^ 0x77L);
      this.rangeCrest = new Noise2D(s ^ 0x78L);
      this.rangeWarpA = new Noise2D(s ^ 0x79L);
      this.rangeWarpB = new Noise2D(s ^ 0x7AL);
      this.rangeShoulder = new Noise2D(s ^ 0x7BL);
      this.domeNoise = new Noise2D(s ^ 0x7CL);
      this.domeBump = new Noise2D(s ^ 0x7DL);
      this.plateauNoise = new Noise2D(s ^ 0x7EL);
      this.plateauUndulation = new Noise2D(s ^ 0x7FL);
      this.plateauGully = new Noise2D(s ^ 0x80L);
      this.coneFlank = new Noise2D(s ^ 0x81L);
      this.coneSeed = s ^ 0x434F4E45L;
      double angle = (Noise2D.mix(s ^ 0x82L) >>> 11) * (1.0 / (1L << 53)) * Math.PI;
      this.rangeCos = Math.cos(angle);
      this.rangeSin = Math.sin(angle);
      int kinds = MountainKind.ALL.length;
      double[] share = new double[kinds];
      double total = 0.0;
      for (int i = 0; i < kinds; i++) {
         share[i] = kindShares != null && i < kindShares.length ? Math.max(0.0, kindShares[i]) : MountainKind.ALL[i].defaultShare();
         total += share[i];
      }
      if (total <= 0.0) {
         share[MountainKind.PEAKS.ordinal()] = 1.0;
         total = 1.0;
      }
      this.kindEdges = new double[kinds];
      double acc = 0.0;
      for (int i = 0; i < kinds; i++) {
         acc += share[i] / total;
         this.kindEdges[i] = acc;
      }
      // the kind noise is not evenly spread, so its values are ranked against a sample: a kind with 20% share covers 20% of the ground
      this.kindSample = new double[6000];
      java.util.Random rnd = new java.util.Random(worldSeed ^ 0x4B494E44L);
      for (int i = 0; i < this.kindSample.length; i++) {
         this.kindSample[i] = this.kindNoise.fbm((rnd.nextDouble() - 0.5) * 120000.0 / KIND_SCALE, (rnd.nextDouble() - 0.5) * 120000.0 / KIND_SCALE, 2, 0.5);
      }
      java.util.Arrays.sort(this.kindSample);
      this.mountain = new Noise2D(s ^ 0x11L);
      this.mountainBase = new Noise2D(s ^ 0x22L);
      this.dune = new Noise2D(s ^ 0x33L);
      this.swell = new Noise2D(s ^ 0x44L);
      this.mesa = new Noise2D(s ^ 0x55L);
      this.mesaTop = new Noise2D(s ^ 0x66L);
      this.mountainHeight = Math.max(8.0, mountainHeight);
      this.mesaStep = Math.max(2, mesaStep);
      this.mesaLevels = Math.max(1, mesaLevels);
      this.duneScale = Math.max(0.1, duneScale);
   }

   /** Horizontal size of the slow noise that decides which kind of mountain a place is, in blocks. */
   private static final double KIND_SCALE = 2200.0;
   /** How wide, in rank (0..1) of the kind noise, the blend between two kinds is. */
   private static final double KIND_BLEND = 0.07;

   /**
    * Mountains, in blocks above the baseline (never more than HARD_CAP): a mix of the kinds of mountain, by how the slow kind noise
    * rates this place. Peaks are the original small sharp mountains; ranges are the big ones, their best crests about 1.5 times mountainHeight.
    */
   public double mountains(int x, int z) {
      double u = this.rank(this.kindNoise.fbm(x / KIND_SCALE, z / KIND_SCALE, 2, 0.5));
      double h = 0.0;
      double sum = 0.0;
      int kinds = this.kindEdges.length;
      for (int k = 0; k < kinds; k++) {
         double lo = k == 0 ? 1.0 : smooth01((u - this.kindEdges[k - 1]) / KIND_BLEND);
         double hi = k == kinds - 1 ? 0.0 : smooth01((u - this.kindEdges[k]) / KIND_BLEND);
         double w = lo - hi;
         if (w > 0.002) {
            h += w * this.profile(MountainKind.ALL[k], x, z);
            sum += w;
         }
      }
      return Math.min(HARD_CAP, sum > 0.0 ? h / sum : 0.0);
   }

   /** Where an actual volcanic landform has influence (0..1); uses the SAME mountain-kind noise and volcano profile as mountains(). */
   public double volcanicInfluence(int x, int z) {
      int k = MountainKind.VOLCANO.ordinal();
      double u = this.rank(this.kindNoise.fbm(x / KIND_SCALE, z / KIND_SCALE, 2, 0.5));
      double lower = k == 0 ? 1.0 : smooth01((u - this.kindEdges[k - 1]) / KIND_BLEND);
      double upper = k == this.kindEdges.length - 1 ? 0.0 : smooth01((u - this.kindEdges[k]) / KIND_BLEND);
      double kind = Math.max(0.0, lower - upper);
      double cone = Math.max(0.0, this.profile(MountainKind.VOLCANO, x, z));
      return kind * smooth01(cone / Math.max(10.0, this.mountainHeight * 0.35));
   }

   /** Open prairie has broad rolling ground, not dense woodland's broken short hills. */
   public double prairie(int x, int z) {
      return 2.5 + 2.5 * this.swell.fbm(x / 380.0,z / 380.0,3,0.5);
   }

   /** Wetland relief stays nearly level, with broad shallow depressions. */
   public double wetlands(int x, int z) {
      return Math.max(0.0, 1.5 + 1.4 * this.swell.fbm(x / 230.0,z / 230.0,2,0.5));
   }

   /** Natural low rolling foredunes and backdunes, with short-scale noise suppressed. */
   public double coastalDunes(int x, int z) {
      return 2.0 + 4.2 * Math.pow(0.5 + 0.5 * this.dune.fbm(x / 145.0,z / 220.0,3,0.5), 1.5);
   }

   /** The height of one kind of mountain at a column, in blocks above the baseline. */
   public double profile(MountainKind kind, int x, int z) {
      double mh = this.mountainHeight;
      switch (kind) {
         case MASSIF: {
            // rounded domes that stay under the tree line, so they are forested to the top
            double d = 0.5 + 0.5 * this.domeNoise.fbm(x / 620.0, z / 620.0, 3, 0.45);
            double dome = step(0.30, 0.75, d);
            return Math.max(0.0, mh * 0.48 * Math.pow(dome, 1.2) + 0.03 * mh * this.domeBump.fbm(x / 70.0, z / 70.0, 2, 0.5) * dome);
         }
         case RANGE: {
            // a long chain: ridged noise stretched along a crest direction and bent by a slow warp, on broad shoulders
            double top = Math.min(2.0 * mh, 160.0);
            double wa = this.rangeWarpA.fbm(x / 520.0, z / 520.0, 2, 0.5);
            double wb = this.rangeWarpB.fbm(x / 520.0 + 7.0, z / 520.0 - 3.0, 2, 0.5);
            double px = x + 160.0 * wa;
            double pz = z + 160.0 * wb;
            double along = (px * this.rangeCos + pz * this.rangeSin) / 760.0;
            double across = (-px * this.rangeSin + pz * this.rangeCos) / 210.0;
            double crest = this.rangeCrest.ridged(along, across, 4);
            double shoulder = 0.5 + 0.5 * this.rangeShoulder.fbm(x / 950.0, z / 950.0, 3, 0.5);
            return top * (0.14 * shoulder + 0.86 * Math.pow(crest, 1.15) * (0.5 + 0.5 * shoulder));
         }
         case PLATEAU: {
            // tableland: a high flat that ends in an escarpment, gullied and gently rolling on top
            double n = 0.5 + 0.5 * this.plateauNoise.fbm(x / 820.0, z / 820.0, 3, 0.5);
            double lift = step(0.44, 0.48, n);
            double gully = Math.pow(this.plateauGully.ridged(x / 150.0, z / 150.0, 2), 3.0);
            double top = 4.0 * this.plateauUndulation.fbm(x / 130.0, z / 130.0, 3, 0.5) - 8.0 * gully;
            return Math.max(0.0, mh * 0.62 * lift + lift * top + mh * 0.08 * n);
         }
         case VOLCANO: {
            // cones on a jittered grid: concave flanks, a crater, ridged lava flows down the sides
            final double cell = 1150.0;
            int ci = (int) Math.floor(x / cell);
            int cj = (int) Math.floor(z / cell);
            double best = 0.0;
            for (int di = -1; di <= 1; di++) {
               for (int dj = -1; dj <= 1; dj++) {
                  long h = Noise2D.mix(this.coneSeed + (ci + di) * 0x9E3779B97F4A7C15L + (cj + dj) * 0xC2B2AE3D27D4EB4FL);
                  if (unit(h) >= 0.75) {
                     continue;
                  }
                  long h2 = Noise2D.mix(h);
                  long h3 = Noise2D.mix(h2);
                  long h4 = Noise2D.mix(h3);
                  double cx = (ci + di + 0.2 + 0.6 * unit(h2)) * cell;
                  double cz = (cj + dj + 0.2 + 0.6 * unit(h3)) * cell;
                  double radius = cell * (0.34 + 0.2 * unit(h4));
                  double t = Math.hypot(x - cx, z - cz) / radius;
                  if (t >= 1.0) {
                     continue;
                  }
                  double height = Math.min(150.0, mh * (0.8 + 0.45 * unit(Noise2D.mix(h4))));
                  double cone = height * Math.pow(1.0 - t, 1.55);
                  double crater = 1.0 - step(0.03, 0.13, t);
                  double flow = 1.0 + 0.10 * (this.coneFlank.ridged(x / 55.0, z / 55.0, 2) - 0.4);
                  best = Math.max(best, Math.max(0.0, cone * flow - 0.34 * height * crater));
               }
            }
            return best;
         }
         case PEAKS:
         default: {
            double base = 0.5 + 0.5 * this.mountainBase.fbm(x / 420.0, z / 420.0, 3, 0.5);
            double ridge = this.mountain.ridged(x / 190.0, z / 190.0, 4);
            return mh * (0.18 * base + 0.82 * ridge * (0.45 + 0.55 * base));
         }
      }
   }

   /** Where a noise value falls among the sampled ones, 0..1: equalises the kind noise so shares mean what they say. */
   private double rank(double v) {
      int lo = 0;
      int hi = this.kindSample.length;
      while (lo < hi) {
         int mid = (lo + hi) >>> 1;
         if (this.kindSample[mid] < v) {
            lo = mid + 1;
         } else {
            hi = mid;
         }
      }
      return (lo + 0.5) / this.kindSample.length;
   }

   /** 0 below -1, 1 above +1, smooth between: the step used to blend two kinds across KIND_BLEND of rank. */
   private static double smooth01(double t) {
      double c = Math.max(0.0, Math.min(1.0, (t + 1.0) / 2.0));
      return c * c * (3.0 - 2.0 * c);
   }

   /** The usual smoothstep: 0 at or below e0, 1 at or above e1, smooth between. */
   private static double step(double e0, double e1, double v) {
      double c = Math.max(0.0, Math.min(1.0, (v - e0) / (e1 - e0)));
      return c * c * (3.0 - 2.0 * c);
   }

   private static double unit(long h) {
      return (h >>> 11) * (1.0 / (1L << 53));
   }

   /** Dunes with sharp crests on broad swells, roughly -2 .. 12. */
   public double desert(int x, int z) {
      double crest = 1.0 - Math.abs(this.dune.fbm(x / (46.0 * this.duneScale), z / (62.0 * this.duneScale), 2, 0.5));
      double broad = this.swell.fbm(x / (230.0 * this.duneScale), z / (230.0 * this.duneScale), 2, 0.5);
      return 5.5 + 3.8 * broad + 6.0 * crest * crest - 4.0;
   }

   /** Flat-topped terraces with steep sides: buttes and plateaus climbing up to mesaLevels * mesaStep blocks. */
   public double mesa(int x, int z) {
      double n = 0.5 + 0.5 * this.mesa.fbm(x / 210.0, z / 210.0, 3, 0.5);
      double t = Math.max(0.0, Math.min(1.0, (n - 0.30) / 0.45)) * this.mesaLevels;
      double fl = Math.floor(t);
      double frac = t - fl;
      double k = Math.max(0.0, Math.min(1.0, (frac - 0.5) * 7.0 + 0.5));
      double s = k * k * (3.0 - 2.0 * k);
      double top = 0.8 * this.mesaTop.noise(x / 14.0, z / 14.0);
      return (fl + s) * this.mesaStep + top;
   }

   /**
    * The mesa height with the terrace steps worn off: the smooth envelope the terraces climb along. The desert beside a mesa
    * carries this (faded out with distance, see ForestGenerator.wildReliefAt), so the high ground comes down as an eroded
    * slope instead of ending in a cliff at the border.
    */
   public double mesaSlope(int x, int z) {
      double n = 0.5 + 0.5 * this.mesa.fbm(x / 210.0, z / 210.0, 3, 0.5);
      return Math.max(0.0, Math.min(1.0, (n - 0.30) / 0.45)) * this.mesaLevels * this.mesaStep;
   }

   public double mountainHeight() {
      return this.mountainHeight;
   }
}
