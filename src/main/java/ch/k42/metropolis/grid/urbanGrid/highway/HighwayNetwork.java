package ch.k42.metropolis.grid.urbanGrid.highway;

import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Comparator;
import java.util.function.Predicate;

/**
 * The city highway network: a pure function of the world seed and the city-ring settings.
 *
 * Every city (one per jittered grid cell -- the same sites CityRingField uses) gets a square
 * box. The box outline is a two-lane highway ring, and a two-lane highway crosses the city
 * east-west and north-south through its centre. Neighbouring cities form a sparse,
 * cycle-pruned highway backbone. When a city has fewer than two exits, it can retain
 * additional complete, legal routes on unused sides, preferring the opposite side.
 * Each physical side accepts at most one intercity connection. Routes are straight
 * whenever the two rings face each other, and use a single jog otherwise.
 *
 * The ocean has NO artificial highway wrapper or coarse keep-out rectangle. Each prospective city
 * ring quarter and its two central spokes must clear the actual ocean/river-mouth coast.
 * The largest useful dry set of quarters is retained as an atomic planner footprint, including the
 * spokes that return its endpoints to the central junction. Intercity links
 * is planned atomically, with alternate dry routes considered instead of ending a highway at water.
 *
 * A highway is always two lanes side by side, each lane one chunk wide ("a band"). Nothing in
 * this class knows about schematics: it only says which chunks are highway lanes (rectsIn) and
 * pieceFor() derives the piece for one lane chunk from which of its neighbours are lanes, the
 * same way the old tile ring did by position.
 *
 * Nothing here touches Bukkit, so it runs (and is tested) standalone. All results are cached;
 * the class is thread safe.
 */
public final class HighwayNetwork {

   /** Chunks per lane band: a highway is two lanes wide. */
   public static final int BAND = 2;
   /** A link may not attach closer than this to a ring corner or to the through-road it does not join. */
   private static final int ATTACH_MARGIN = 4;
   /** Cities whose boxes would come closer than this are shrunk apart. */
   private static final int DEFAULT_GAP = 20;
   private static final int MIN_HALF = 14;
   /** Shortest clear stretch between two boxes that a link will be routed through. */
   private static final int MIN_SEPARATION = 4;

   /** Four L-shaped quarter arcs, split at each mid-side cross-road intersection. */
   public static final int Q_NW = 1, Q_NE = 2, Q_SE = 4, Q_SW = 8;
   public static final int ALL_QUARTERS = Q_NW | Q_NE | Q_SE | Q_SW;
   private static final int[] QUARTERS = {Q_NW, Q_NE, Q_SE, Q_SW};

   // Physical attachment sides (not grid-neighbour directions): each side may accept one link.
   public static final int SIDE_N = 0, SIDE_E = 1, SIDE_S = 2, SIDE_W = 3;

   // ------------------------------------------------------------------ value types

   /** An axis-aligned block of chunks, both ends inclusive. */
   public static final class Rect {
      public final int minX;
      public final int minZ;
      public final int maxX;
      public final int maxZ;

      public Rect(int minX, int minZ, int maxX, int maxZ) {
         this.minX = minX;
         this.minZ = minZ;
         this.maxX = maxX;
         this.maxZ = maxZ;
      }

      public boolean intersects(int ominX, int ominZ, int omaxX, int omaxZ) {
         return this.minX <= omaxX && this.maxX >= ominX && this.minZ <= omaxZ && this.maxZ >= ominZ;
      }

      public boolean contains(int x, int z) {
         return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
      }

      @Override
      public String toString() {
         return "[" + this.minX + "," + this.minZ + " .. " + this.maxX + "," + this.maxZ + "]";
      }
   }

   /**
    * One city. cx/cz is the integer chunk the centre site falls in; the box is symmetric around
    * the corner shared by chunks (cx, cz) and (cx + 1, cz + 1), so the through-roads (chunks cx
    * and cx + 1, cz and cz + 1) sit exactly in the middle and the box is 2 * (h + 1) chunks wide.
    */
   public static final class City {
      public final int a;
      public final int b;
      public final int cx;
      public final int cz;
      public final int h;
      public final int x0;
      public final int x1;
      public final int z0;
      public final int z1;
      /** False only when not even one connected, dry quarter with both central spokes fits. */
      public final boolean present;
      /** The retained highway quarters; every retained quarter has both its spokes to the centre. */
      public final int quarters;

      City(int a, int b, int cx, int cz, int h) {
         this(a, b, cx, cz, h, ALL_QUARTERS);
      }

      City(int a, int b, int cx, int cz, int h, int quarters) {
         this.present = quarters != 0;
         this.quarters = quarters;
         this.a = a;
         this.b = b;
         this.cx = cx;
         this.cz = cz;
         this.h = h;
         this.x0 = cx - h;
         this.x1 = cx + 1 + h;
         this.z0 = cz - h;
         this.z1 = cz + 1 + h;
      }

      public boolean hasQuarter(int mask) {
         return (this.quarters & mask) != 0;
      }

      public boolean boxContains(int x, int z) {
         return this.present && x >= this.x0 && x <= this.x1 && z >= this.z0 && z <= this.z1;
      }

      @Override
      public String toString() {
         return "City(" + this.a + "," + this.b + ") centre=(" + this.cx + "," + this.cz
                 + ") h=" + this.h + " quarters=" + this.quarters;
      }
   }

   /** A link between two grid-neighbour cities and the bands that draw it. */
   public static final class Link {
      public final City from;
      public final City to;
      public final List<Rect> rects;
      public final double length;
      /** Real attachment face at each endpoint, after the route axis is selected. */
      public final int fromSide;
      public final int toSide;

      Link(City from, City to, List<Rect> rects, int fromSide, int toSide) {
         this.from = from;
         this.to = to;
         this.rects = rects;
         this.fromSide = fromSide;
         this.toSide = toSide;
         this.length = Math.hypot(from.cx - to.cx, from.cz - to.cz);
      }
   }

   /** Read access to a set of highway lane chunks. */
   public interface Mask {
      boolean at(int x, int z);
   }

   /** Optional link-only validator; city rings and their original ocean guard are unchanged. */
   public interface LinkWaterSafety {
      boolean permits(List<Rect> linkBands);
   }

   // ------------------------------------------------------------------ state

   private final long seed;
   private final double spacing;
   private final double radius;
   private final double sizeVariation;
   private final double boxScale;
   private final int gap;
   private final double extraLinkChance;
   /** Chunk-level exclusion of the actual ocean / mouth shoreline, not an ocean-tile rectangle. */
   private final Mask oceanBlocked;
   private final LinkWaterSafety riverLinkSafety;

   private final Map<Long, City> cities = new HashMap<Long, City>();
   private final Map<Long, Link> candidates = new HashMap<Long, Link>();
   private final Map<Long, Boolean> candidateKnown = new HashMap<Long, Boolean>();
   private final Map<Long, Boolean> survives = new HashMap<Long, Boolean>();
   /** The original cycle-pruned selections, independent of the new degree/side policy. */
   private final Map<Long, Boolean> baseSurvives = new HashMap<Long, Boolean>();

   /**
    * @param spacing       chunks between city sites (cityRings.spacing)
    * @param radius        base city radius in chunks (cityRings.radius)
    * @param sizeVariation fraction each city's radius varies either way (cityRings.sizeVariation / 100)
    * @param boxScale      box half-extent as a multiple of the city's own radius
    * @param extraLinkChance chance (0..1) that a link pruning would drop is kept anyway, for loops
    */
   public HighwayNetwork(long seed, double spacing, double radius, double sizeVariation, double boxScale, double extraLinkChance) {
      this(seed, spacing, radius, sizeVariation, boxScale, extraLinkChance, DEFAULT_GAP);
   }

   public HighwayNetwork(long seed, double spacing, double radius, double sizeVariation, double boxScale, double extraLinkChance, int gap) {
      this(seed, spacing, radius, sizeVariation, boxScale, extraLinkChance, gap, null, 1);
   }

   /**
    * @param oceanBlocked true for a chunk that must not contain a highway lane: actual ocean water,
    *                     coastal bank or ocean-converged river mouth. Null permits all terrain.
    * @param lattice every lane's near edge (ring, cross-road, link or jog) aligns to this many
    *                chunks, preserving the existing legal block-width gap invariant.
    */
   public HighwayNetwork(long seed, double spacing, double radius, double sizeVariation, double boxScale,
                         double extraLinkChance, int gap, Mask oceanBlocked, int lattice) {
      this(seed,spacing,radius,sizeVariation,boxScale,extraLinkChance,gap,oceanBlocked,lattice,null);
   }

   /** Link-only freshwater occupancy check, leaving every historical constructor compatible. */
   public HighwayNetwork(long seed, double spacing, double radius, double sizeVariation, double boxScale,
                         double extraLinkChance, int gap, Mask oceanBlocked, int lattice,
                         LinkWaterSafety riverLinkSafety) {
      this.riverLinkSafety = riverLinkSafety;
      this.lattice = Math.max(1, lattice);
      this.seed = seed;
      this.oceanBlocked = oceanBlocked;
      this.spacing = spacing;
      this.radius = radius;
      this.sizeVariation = sizeVariation;
      this.boxScale = boxScale;
      this.extraLinkChance = extraLinkChance;
      this.gap = gap;
   }

   private final int lattice;

   // ------------------------------------------------------------------ city sites (same maths as CityRingField)

   private static long mix(long z) {
      z += 0x9E3779B97F4A7C15L;
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      return z ^ (z >>> 31);
   }

   private int snap(double v) {
      return this.lattice <= 1 ? (int) Math.floor(v) : (int) Math.round(v / this.lattice) * this.lattice;
   }

   private static double unit(long h) {
      return (h >>> 11) * (1.0 / 9007199254740992.0);
   }

   private double rawCentreX(int a, int b, long h) {
      return (a + 0.15 + 0.7 * unit(h)) * this.spacing;
   }

   private double rawCentreZ(int a, int b, long h2) {
      return (b + 0.15 + 0.7 * unit(h2)) * this.spacing;
   }

   private static long cellKey(int a, int b) {
      return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
   }

   private long siteHash(int a, int b) {
      return mix(((long) a * 73856093L) ^ ((long) b * 19349663L) ^ this.seed);
   }

   /** The city of grid cell (a, b); cities never depend on anything but their own cell and the cells around it. */
   public synchronized City city(int a, int b) {
      Long key = cellKey(a, b);
      City c = this.cities.get(key);
      if (c != null) {
         return c;
      }
      long h = this.siteHash(a, b);
      long h2 = mix(h);
      long h3 = mix(h2);
      int cx = this.snap(this.rawCentreX(a, b, h));
      int cz = this.snap(this.rawCentreZ(a, b, h2));
      double factor = 1.0 - this.sizeVariation + 2.0 * this.sizeVariation * unit(h3);
      int half = (int) Math.round(this.radius * factor * this.boxScale);

      // Shrink until the box clears every neighbour's box by the gap along at least one axis: each of
      // two cities takes at most half of the room, so the pair always fits whatever the other one does.
      for (int na = a - 2; na <= a + 2; na++) {
         for (int nb = b - 2; nb <= b + 2; nb++) {
            if (na == a && nb == b) {
               continue;
            }
            long nh = this.siteHash(na, nb);
            long nh2 = mix(nh);
            int ncx = this.snap(this.rawCentreX(na, nb, nh));
            int ncz = this.snap(this.rawCentreZ(na, nb, nh2));
            int d = Math.max(Math.abs(cx - ncx), Math.abs(cz - ncz));
            half = Math.min(half, (d - this.gap) / 2);
         }
      }
      if (this.lattice > 1) {
         // a whole number of lattice steps, so the box's ring lanes land on the lattice too
         int minHalf = (MIN_HALF + this.lattice - 1) / this.lattice * this.lattice;
         half = Math.max(minHalf, half / this.lattice * this.lattice);
      }
      half = Math.max(MIN_HALF, half);
      // Coast-aware selection. Never cut a painted highway: each L-shaped quarter
      // and BOTH of its spokes to the centre must fit entirely on safe dry ground.
      // A large, partially coastal city often retains more useful roads than a tiny
      // full square. Score usable quarter area across lattice-safe radii, with full
      // original geometry preferred whenever it fits.
      c = null;
      if (this.oceanBlocked == null) {
         c = new City(a, b, cx, cz, half);
      } else {
         long bestScore = -1;
         for (int fit = half; fit >= MIN_HALF; fit -= this.lattice) {
            City trial = new City(a, b, cx, cz, fit);
            // Do not allow an island of perimeter highway disconnected from the hub.
            if (!this.allDry(new Rect(trial.cx, trial.cz, trial.cx + 1, trial.cz + 1))) {
               continue;
            }
            int mask = 0;
            for (int quarter : QUARTERS) {
               if (this.allDry(quarterAndSpokes(trial, quarter))) mask |= quarter;
            }
            if (mask == 0) continue;
            int count = Integer.bitCount(mask);
            long score = (long)count * (fit + 1L) * (fit + 1L);
            if (score > bestScore) {
               bestScore = score;
               c = new City(a, b, cx, cz, fit, mask);
            }
            if (fit == half && mask == ALL_QUARTERS) break;
         }
      }
      if (c == null) c = new City(a, b, cx, cz, 0, 0);
      this.cities.put(key, c);
      return c;
   }

   /**
    * The unique generated city box containing a world chunk, or null when the
    * chunk is outside every present city. Search the jittered site cells around
    * the chunk's coarse spacing cell; the city boxes are disjoint by construction.
    */
   public synchronized City cityContaining(int chunkX,int chunkZ) {
      if (!(this.spacing > 0.0) || !Double.isFinite(this.spacing)) return null;
      int a0=(int)Math.floor(chunkX/this.spacing);
      int b0=(int)Math.floor(chunkZ/this.spacing);
      for(int a=a0-2;a<=a0+2;a++) {
         for(int b=b0-2;b<=b0+2;b++) {
            City candidate=this.city(a,b);
            if(candidate.boxContains(chunkX,chunkZ)) return candidate;
         }
      }
      return null;
   }

   /** A proposed band either exists completely on dry ocean-safe land or does not exist. */
   private boolean allDry(Rect band) {
      if (this.oceanBlocked == null) return true;
      for (int z = band.minZ; z <= band.maxZ; z++) {
         for (int x = band.minX; x <= band.maxX; x++) {
            if (this.oceanBlocked.at(x, z)) return false;
         }
      }
      return true;
   }

   private boolean allDry(List<Rect> bands) {
      if (this.oceanBlocked == null) return true;
      for (Rect band : bands) {
         if (!this.allDry(band)) return false;
      }
      return true;
   }

   private int cellOf(int chunk) {
      return (int) Math.floor(chunk / this.spacing);
   }

   /**
    * Distance to the nearest city box, measured in half-box units: below 1.0 is inside the box
    * (the ring included), above 1.0 is outside it. This is the field the city zoning rungs read.
    */
   public double distance(int chunkX, int chunkZ) {
      int ax = this.cellOf(chunkX);
      int az = this.cellOf(chunkZ);
      double best = Double.MAX_VALUE;
      for (int a = ax - 1; a <= ax + 1; a++) {
         for (int b = az - 1; b <= az + 1; b++) {
            City c = this.city(a, b);
            if (!c.present) {
               continue;
            }
            double dx = Math.abs(chunkX - c.cx - 0.5);
            double dz = Math.abs(chunkZ - c.cz - 0.5);
            double d = Math.max(dx, dz) / (c.h + 1.0);
            if (d < best) {
               best = d;
            }
         }
      }
      return best;
   }

   // ------------------------------------------------------------------ city bands

   /** The two L-shaped arms of one perimeter quarter, and the two spokes to the central junction. */
   private static List<Rect> quarterAndSpokes(City c, int quarter) {
      List<Rect> out = new ArrayList<Rect>(4);
      switch (quarter) {
         case Q_NW:
            out.add(new Rect(c.x0, c.z0, c.cx + 1, c.z0 + 1));
            out.add(new Rect(c.x0, c.z0, c.x0 + 1, c.cz + 1));
            out.add(new Rect(c.cx, c.z0, c.cx + 1, c.cz + 1));
            out.add(new Rect(c.x0, c.cz, c.cx + 1, c.cz + 1));
            break;
         case Q_NE:
            out.add(new Rect(c.cx, c.z0, c.x1, c.z0 + 1));
            out.add(new Rect(c.x1 - 1, c.z0, c.x1, c.cz + 1));
            out.add(new Rect(c.cx, c.z0, c.cx + 1, c.cz + 1));
            out.add(new Rect(c.cx, c.cz, c.x1, c.cz + 1));
            break;
         case Q_SE:
            out.add(new Rect(c.cx, c.z1 - 1, c.x1, c.z1));
            out.add(new Rect(c.x1 - 1, c.cz, c.x1, c.z1));
            out.add(new Rect(c.cx, c.cz, c.cx + 1, c.z1));
            out.add(new Rect(c.cx, c.cz, c.x1, c.cz + 1));
            break;
         case Q_SW:
            out.add(new Rect(c.x0, c.z1 - 1, c.cx + 1, c.z1));
            out.add(new Rect(c.x0, c.cz, c.x0 + 1, c.z1));
            out.add(new Rect(c.cx, c.cz, c.cx + 1, c.z1));
            out.add(new Rect(c.x0, c.cz, c.cx + 1, c.cz + 1));
            break;
         default: throw new IllegalArgumentException("Unknown city highway quarter " + quarter);
      }
      return out;
   }

   /** An intact city returns the EXACT original six highway rectangles, preserving inland parity. */
   public static List<Rect> cityRects(City c) {
      List<Rect> r = new ArrayList<Rect>(12);
      if (!c.present) return r;
      if (c.quarters == ALL_QUARTERS) {
         r.add(new Rect(c.x0, c.z0, c.x0 + 1, c.z1));
         r.add(new Rect(c.x1 - 1, c.z0, c.x1, c.z1));
         r.add(new Rect(c.x0, c.z0, c.x1, c.z0 + 1));
         r.add(new Rect(c.x0, c.z1 - 1, c.x1, c.z1));
         r.add(new Rect(c.cx, c.z0, c.cx + 1, c.z1));
         r.add(new Rect(c.x0, c.cz, c.x1, c.cz + 1));
         return r;
      }
      // Preserve only complete dry arcs and exactly their dry centre connections;
      // no isolated ring endpoints or coastal partial lanes can enter the plan.
      for (int q : QUARTERS) {
         if (c.hasQuarter(q)) r.addAll(quarterAndSpokes(c, q));
      }
      return r;
   }

   // ------------------------------------------------------------------ links

   /** One city box seen along an axis: u runs along the link, v across it. */
   private static final class Box {
      final int u0;
      final int u1;
      final int vc;
      final int vLo;
      final int vHi;
      final int lattice;
      final City city;
      final boolean alongX;
      final boolean outwardPositive;

      Box(City c, boolean alongX, int lattice, boolean outwardPositive) {
         this.lattice = lattice;
         this.city = c;
         this.alongX = alongX;
         this.outwardPositive = outwardPositive;
         int uc = alongX ? c.cx : c.cz;
         this.vc = alongX ? c.cz : c.cx;
         this.u0 = uc - c.h;
         this.u1 = uc + 1 + c.h;
         // a link's band is rows (t, t + 1); keep both clear of the ring corners
         this.vLo = this.vc - c.h + ATTACH_MARGIN;
         this.vHi = this.vc + 1 + c.h - ATTACH_MARGIN - 1;
      }

      /** A row is allowed if the link either joins the through-road exactly or stays clear of it. */
      boolean allows(int t) {
         if (t < this.vLo || t > this.vHi) {
            return false;
         }
         if (this.lattice > 1 && Math.floorMod(t, this.lattice) != 0) {
            return false;
         }
         int off = Math.abs(t - this.vc);
         if (!(off == 0 || off >= ATTACH_MARGIN)) return false;
         // This must attach to an EXISTING quarter or its retained centre spoke.
         // Otherwise a whole dry link can still terminate at a removed coastal side.
         if (this.city.quarters == ALL_QUARTERS) return true;
         int quarter;
         if (this.alongX) {
            if (t == this.vc) {
               return this.city.hasQuarter(this.outwardPositive ? Q_NE | Q_SE : Q_NW | Q_SW);
            }
            quarter = this.outwardPositive ? (t < this.vc ? Q_NE : Q_SE)
                                            : (t < this.vc ? Q_NW : Q_SW);
         } else {
            if (t == this.vc) {
               return this.city.hasQuarter(this.outwardPositive ? Q_SW | Q_SE : Q_NW | Q_NE);
            }
            quarter = this.outwardPositive ? (t < this.vc ? Q_SW : Q_SE)
                                            : (t < this.vc ? Q_NW : Q_NE);
         }
         return this.city.hasQuarter(quarter);
      }
   }

   private static long linkKey(City p, City q) {
      // p is always the lower cell in (a, then b) order
      return ((long) (p.a & 0xFFFF) << 48) | ((long) (p.b & 0xFFFF) << 32) | ((long) (q.a & 0xFFFF) << 16) | (q.b & 0xFFFFL);
   }

   private static boolean lowerFirst(City p, City q) {
      return p.a < q.a || (p.a == q.a && p.b < q.b);
   }

   /** The route between two grid neighbours, or null when no clean route exists. Cached. */
   private synchronized Link candidate(City p, City q) {
      if (!lowerFirst(p, q)) {
         City t = p;
         p = q;
         q = t;
      }
      Long key = linkKey(p, q);
      Boolean known = this.candidateKnown.get(key);
      if (known != null) {
         return known ? this.candidates.get(key) : null;
      }
      Link link = this.buildRoute(p, q);
      this.candidateKnown.put(key, link != null);
      if (link != null) {
         this.candidates.put(key, link);
      }
      return link;
   }

   private Link buildRoute(City p, City q) {
      if (!p.present || !q.present) {
         return null;
      }
      // free columns / rows between the two boxes along each axis
      int sepX;
      int sepZ;
      if (p.cx <= q.cx) {
         sepX = (q.cx - q.h) - (p.cx + 1 + p.h) - 1;
      } else {
         sepX = (p.cx - p.h) - (q.cx + 1 + q.h) - 1;
      }
      if (p.cz <= q.cz) {
         sepZ = (q.cz - q.h) - (p.cz + 1 + p.h) - 1;
      } else {
         sepZ = (p.cz - p.h) - (q.cz + 1 + q.h) - 1;
      }
      boolean alongX = sepX >= sepZ;
      int sep = alongX ? sepX : sepZ;
      if (sep < MIN_SEPARATION) {
         return null;
      }
      City lo;
      City hi;
      if (alongX) {
         lo = p.cx <= q.cx ? p : q;
         hi = lo == p ? q : p;
      } else {
         lo = p.cz <= q.cz ? p : q;
         hi = lo == p ? q : p;
      }
      Box l = new Box(lo, alongX, this.lattice, true);
      Box r = new Box(hi, alongX, this.lattice, false);

      // These are the only possible neighbouring cities a short local route might hit.
      // Reject each COMPLETE candidate (both lanes, all jog segments) before accepting it;
      // a rejected link contributes zero highway chunks, never a stub at the shoreline.
      List<City> thirdCities = new ArrayList<City>();
      for (int a = Math.min(p.a, q.a) - 1; a <= Math.max(p.a, q.a) + 1; a++) {
         for (int b = Math.min(p.b, q.b) - 1; b <= Math.max(p.b, q.b) + 1; b++) {
            if ((a == p.a && b == p.b) || (a == q.a && b == q.b)) continue;
            City other = this.city(a, b);
            if (other.present) thirdCities.add(other);
         }
      }
      final boolean useX = alongX;
      Predicate<List<int[]>> acceptable = uv -> {
         List<Rect> rects = this.toRects(uv, useX);
         if (!this.allDry(rects)) return false;
         // The old ocean mask does not cover freshwater. Test the entire
         // proposed intercity link BEFORE reserving any of its lane chunks.
         // Dry river shores remain legal; short perpendicular wet culverts do too.
         if (this.riverLinkSafety != null && !this.riverLinkSafety.permits(rects)) return false;
         for (City other : thirdCities) {
            for (Rect rc : rects) {
               if (rc.intersects(other.x0 - 2, other.z0 - 2, other.x1 + 2, other.z1 + 2)) return false;
            }
         }
         return true;
      };
      List<int[]> uv = this.routeUV(l, r, acceptable);
      if (uv == null) return null;
      int outward = alongX ? SIDE_E : SIDE_S;
      int inward = alongX ? SIDE_W : SIDE_N;
      return new Link(p, q, toRects(uv, alongX), lo == p ? outward : inward,
              hi == q ? inward : outward);
   }

   private List<Rect> toRects(List<int[]> uv, boolean alongX) {
      List<Rect> rects = new ArrayList<Rect>(uv.size());
      for (int[] e : uv) {
         rects.add(alongX ? new Rect(e[0], e[1], e[2], e[3])
                          : new Rect(e[1], e[0], e[3], e[2]));
      }
      return rects;
   }

   /**
    * Prefer the same short, aligned connections as before. If the first route encounters the real
    * coastline, try other shared straight rows before attempting one-jog routes through dry land.
    * This is seed-deterministic and does not reserve or paint even one chunk of a failed proposal.
    */
   private List<int[]> routeUV(Box l, Box r, Predicate<List<int[]>> acceptable) {
      // Preserve the pre-removal highway geometry wherever it is already legal.
      // Only seek alternative rows/jogs if that exact old route conflicts with
      // the real coast or another city. No ordinary inland road is rerouted.
      List<int[]> original = this.originalRouteUV(l, r);
      if (original != null && acceptable.test(original)) return original;
      int lo = Math.max(l.vLo, r.vLo);
      int hi = Math.min(l.vHi, r.vHi);
      int mid = (l.vc + r.vc) / 2;
      List<Integer> straight = new ArrayList<Integer>();
      for (int t = lo; t <= hi; t++) {
         if (l.allows(t) && r.allows(t)) straight.add(t);
      }
      Collections.sort(straight, Comparator.comparingInt((Integer t) ->
              Math.abs(t - mid) - (t == l.vc ? 3 : 0) - (t == r.vc ? 3 : 0)).thenComparingInt(t -> t));
      for (int t : straight) {
         List<int[]> uv = new ArrayList<int[]>(1);
         uv.add(new int[]{l.u1 + 1, t, r.u0 - 1, t + 1});
         if (acceptable.test(uv)) return uv;
      }

      // A jog is needed when the rings' safe rows do not overlap or all straight
      // routes strike the coast. Consider multiple attachment rows and jog columns,
      // rather than prematurely rejecting a potentially navigable inland link.
      List<Integer> left = new ArrayList<Integer>(), right = new ArrayList<Integer>();
      for (int t = l.vLo; t <= l.vHi; t++) if (l.allows(t)) left.add(t);
      for (int t = r.vLo; t <= r.vHi; t++) if (r.allows(t)) right.add(t);
      Collections.sort(left, Comparator.comparingInt((Integer t) -> Math.abs(t - r.vc)).thenComparingInt(t -> t));
      Collections.sort(right, Comparator.comparingInt((Integer t) -> Math.abs(t - l.vc)).thenComparingInt(t -> t));
      int first = l.u1 + 1, last = r.u0 - 2;
      if (first > last) return null;
      List<Integer> jogColumns = new ArrayList<Integer>();
      int step = this.lattice;
      for (int uj = first; uj <= last; uj++) {
         if (step == 1 || Math.floorMod(uj, step) == 0) jogColumns.add(uj);
      }
      final int midpoint = (first + last) / 2;
      Collections.sort(jogColumns, Comparator.comparingInt((Integer uj) -> Math.abs(uj - midpoint)).thenComparingInt(uj -> uj));
      // Limit expensive coastal detour search; plenty of alternative positions remain, but
      // a huge grid cannot spin indefinitely trying to drive across an actual ocean.
      // Limit the expensive alternative search only after ALL direct rows have failed.
      // Attachment candidates and jog columns are ranked above, closest first.
      for (int li = 0; li < Math.min(left.size(), 18); li++) {
         int tl = left.get(li);
         for (int ri = 0; ri < Math.min(right.size(), 18); ri++) {
            int tr = right.get(ri);
            if (Math.abs(tl - tr) < 2) continue;
            int vMin = Math.min(tl, tr), vMax = Math.max(tl, tr);
            for (int ji = 0; ji < Math.min(jogColumns.size(), 13); ji++) {
               int uj = jogColumns.get(ji);
               List<int[]> uv = new ArrayList<int[]>(3);
               uv.add(new int[]{first, tl, uj + 1, tl + 1});
               uv.add(new int[]{uj, vMin, uj + 1, vMax + 1});
               uv.add(new int[]{uj, tr, r.u0 - 1, tr + 1});
               if (acceptable.test(uv)) return uv;
            }
         }
      }
      // A single jog cannot bypass a river occupying the full shared band
      // between the two endpoint rings. Only after the historic 1-jog search
      // fails, attempt an atomic TWO-jog detour out to a dry parallel route.
      // Each of its FIVE rectangles must satisfy the same ocean, river and
      // foreign-city checks as a straight link. No unsuccessful candidate
      // commits even a single highway cell.
      List<int[]> around = twoJogDetourUV(l, r, left, right, first, last, acceptable);
      if (around != null) return around;
      return null;
   }

   /** Dry-shore avoidance, only used after the old 1- and 3-band paths failed.
    * Keep a strict budget so an impenetrable ocean cannot force world-gen
    * into an unbounded pathfinder. All ordering is independent of query order. */
   private List<int[]> twoJogDetourUV(Box l, Box r, List<Integer> left,
           List<Integer> right, int first, int last, Predicate<List<int[]>> acceptable) {
      if (last - first < 6) return null;
      final int grid = Math.max(1, this.lattice);
      final int mid = (first + last) / 2;
      // Both short alternatives and a more substantial detour past an entire
      // meandering river band. Never extend more than 64 chunks beyond the
      // attachment rectangles, avoiding extravagant, badly-placed highways.
      List<Integer> outside = new ArrayList<Integer>();
      final int vMin = Math.min(l.vLo, r.vLo), vMax = Math.max(l.vHi, r.vHi);
      for (int delta = 4; delta <= 64; delta += Math.min(8, grid)) {
         // A two-jog connector is still a highway road, not an exception to
         // the block-width lane lattice. Snap the far-side row in the OUTWARD
         // direction; a raw +/-4 offset would produce misaligned street seams.
         int below = Math.floorDiv(vMin - delta, grid) * grid;
         int above = -Math.floorDiv(-(vMax + delta), grid) * grid;
         if (!outside.contains(below)) outside.add(below);
         if (!outside.contains(above)) outside.add(above);
      }
      List<Integer> early = new ArrayList<Integer>(), late = new ArrayList<Integer>();
      for (int u = first + 4; u < mid - 2; u++)
         if (grid == 1 || Math.floorMod(u,grid) == 0) early.add(u);
      for (int u = last - 4; u > mid + 2; u--)
         if (grid == 1 || Math.floorMod(u,grid) == 0) late.add(u);
      if (early.isEmpty() || late.isEmpty()) return null;
      // Prefer a modest dogleg and do not disturb dry historical geometry.
      final int eLimit = Math.min(early.size(), 6), tLimit = Math.min(late.size(), 6);
      for (int li = 0; li < Math.min(left.size(), 5); li++) {
         int tl = left.get(li);
         for (int ri = 0; ri < Math.min(right.size(), 5); ri++) {
            int tr = right.get(ri);
            for (int tm : outside) {
               if (Math.abs(tm-tl) < 4 || Math.abs(tm-tr) < 4) continue;
               for (int ei = 0; ei < eLimit; ei++) {
                  int u1 = early.get(ei);
                  for (int ti = 0; ti < tLimit; ti++) {
                     int u2 = late.get(ti);
                     if (u2 - u1 < 4) continue;
                     List<int[]> uv = new ArrayList<int[]>(5);
                     uv.add(new int[]{first, tl, u1+1, tl+1});
                     uv.add(new int[]{u1, Math.min(tl,tm), u1+1, Math.max(tl,tm)+1});
                     uv.add(new int[]{u1, tm, u2+1, tm+1});
                     uv.add(new int[]{u2, Math.min(tr,tm), u2+1, Math.max(tr,tm)+1});
                     uv.add(new int[]{u2, tr, r.u0-1, tr+1});
                     if (acceptable.test(uv)) return uv;
                  }
               }
            }
         }
      }
      return null;
   }

   private List<int[]> originalRouteUV(Box l, Box r) {
      int lo = Math.max(l.vLo, r.vLo);
      int hi = Math.min(l.vHi, r.vHi);
      int mid = (l.vc + r.vc) / 2;

      // straight: one row pair both rings accept
      int bestT = Integer.MIN_VALUE;
      int bestScore = Integer.MAX_VALUE;
      for (int t = lo; t <= hi; t++) {
         if (!l.allows(t) || !r.allows(t)) {
            continue;
         }
         int score = Math.abs(t - mid);
         if (t == l.vc) {
            score -= 3;
         }
         if (t == r.vc) {
            score -= 3;
         }
         if (score < bestScore) {
            bestScore = score;
            bestT = t;
         }
      }
      List<int[]> out = new ArrayList<int[]>(3);
      if (bestT != Integer.MIN_VALUE) {
         out.add(new int[]{l.u1 + 1, bestT, r.u0 - 1, bestT + 1});
         return out;
      }

      // one jog: leave the left ring on a row it accepts, arrive on a row the right ring accepts
      int tl = nearestAllowed(l, r.vc, Integer.MIN_VALUE, 0);
      if (tl == Integer.MIN_VALUE) {
         return null;
      }
      int tr = nearestAllowed(r, tl, tl, 2);
      if (tr == Integer.MIN_VALUE) {
         return null;
      }
      int uj = (l.u1 + 1 + r.u0 - 1) / 2;
      if (this.lattice > 1) {
         int snapped = Math.round(uj / (float) this.lattice) * this.lattice;
         // the nearest lattice column that still fits between the two rings
         if (snapped < l.u1 + 1 || snapped + 1 > r.u0 - 1) {
            snapped = (l.u1 + 1 + this.lattice - 1) / this.lattice * this.lattice;
         }
         uj = snapped;
      }
      if (uj < l.u1 + 1 || uj + 1 > r.u0 - 1) {
         return null;
      }
      int vMin = Math.min(tl, tr);
      int vMax = Math.max(tl, tr);
      out.add(new int[]{l.u1 + 1, tl, uj + 1, tl + 1});
      out.add(new int[]{uj, vMin, uj + 1, vMax + 1});
      out.add(new int[]{uj, tr, r.u0 - 1, tr + 1});
      return out;
   }

   /** The row nearest to target that the box accepts and that is at least minApart from avoid (ignored when avoid is MIN_VALUE). */
   private static int nearestAllowed(Box box, int target, int avoid, int minApart) {
      int best = Integer.MIN_VALUE;
      int bestDist = Integer.MAX_VALUE;
      for (int t = box.vLo; t <= box.vHi; t++) {
         if (!box.allows(t)) {
            continue;
         }
         if (avoid != Integer.MIN_VALUE && Math.abs(t - avoid) < minApart) {
            continue;
         }
         int d = Math.abs(t - target);
         if (d < bestDist) {
            bestDist = d;
            best = t;
         }
      }
      return best;
   }

   // ------------------------------------------------------------------ pruning

   /** Four grid neighbours, bounded and seed-independent of query order. */
   private List<City> neighbours(City city) {
      List<City> around = new ArrayList<City>(4);
      around.add(this.city(city.a - 1, city.b));
      around.add(this.city(city.a + 1, city.b));
      around.add(this.city(city.a, city.b - 1));
      around.add(this.city(city.a, city.b + 1));
      return around;
   }

   private static int sideAt(Link link, City city) {
      if (link.from == city) return link.fromSide;
      if (link.to == city) return link.toSide;
      throw new IllegalArgumentException("Link does not touch city " + city);
   }

   /** Original cycle pruning and optional configured random loops, without new link promotion. */
   private boolean baseKeeps(Link link) {
      Long key = linkKey(link.from, link.to);
      Boolean cached = this.baseSurvives.get(key);
      if (cached != null) return cached;
      boolean keep = !this.longestOfShortCycle(link.from, link.to);
      if (!keep && this.extraLinkChance > 0.0) {
         long hh = mix(key ^ this.seed ^ 0x51ED270B9A3C41L);
         keep = unit(hh) < this.extraLinkChance;
      }
      this.baseSurvives.put(key, keep);
      return keep;
   }

   /** Prefer existing kept edges, then the shorter edge, then a stable seed-independent tie. */
   private boolean betterAtSide(Link contender, Link incumbent) {
      boolean cBase = this.baseKeeps(contender), iBase = this.baseKeeps(incumbent);
      if (cBase != iBase) return cBase;
      if (contender.length != incumbent.length) return contender.length < incumbent.length;
      return linkKey(contender.from, contender.to) < linkKey(incumbent.from, incumbent.to);
   }

   /** Mutual arbitration prevents two highways from attaching on one physical side. */
   private boolean sideWinner(Link link, City city) {
      int side = sideAt(link, city);
      for (City other : this.neighbours(city)) {
         Link rival = this.candidate(city, other);
         if (rival != null && rival != link && sideAt(rival, city) == side
                 && this.betterAtSide(rival, link)) {
            return false;
         }
      }
      return true;
   }

   private boolean eligible(Link link) {
      return this.sideWinner(link, link.from) && this.sideWinner(link, link.to);
   }

   /**
    * Choose the shortest complete unused-side routes needed to bring a city to two
    * intercity exits, when that many valid exits exist. A neighbour can also nominate
    * this route; unioning nominations creates natural loops without random mass linking.
    * All proposals are whole routes already verified dry, and side arbitration is mutual.
    */
   private boolean nominatedBy(City city, Link requested) {
      List<Link> extras = new ArrayList<Link>(4);
      int retained = 0;
      int retainedSide = -1;
      for (City other : this.neighbours(city)) {
         Link link = this.candidate(city, other);
         if (link == null || !this.eligible(link)) continue;
         if (this.baseKeeps(link)) {
            retained++;
            retainedSide = sideAt(link, city);
         } else extras.add(link);
      }
      int needed = Math.max(0, 2 - retained);
      if (needed == 0 || extras.isEmpty()) return false;
      // If a city currently has one exit, prioritize the opposite side to avoid
      // a lollipop spur. When it has none, choose the closest two valid exits.
      final int farSide = retained == 1 ? (retainedSide + 2) % 4 : -1;
      extras.sort((one, two) -> {
         if (farSide >= 0) {
            boolean aOpposite = sideAt(one, city) == farSide;
            boolean bOpposite = sideAt(two, city) == farSide;
            if (aOpposite != bOpposite) return aOpposite ? -1 : 1;
         }
         int byDistance = Double.compare(one.length, two.length);
         return byDistance != 0 ? byDistance
                 : Long.compare(linkKey(one.from, one.to), linkKey(two.from, two.to));
      });
      for (int i = 0; i < Math.min(needed, extras.size()); i++) {
         if (extras.get(i) == requested) return true;
      }
      return false;
   }

   private synchronized boolean keepsLink(City p, City q) {
      if (!lowerFirst(p, q)) {
         City t = p;
         p = q;
         q = t;
      }
      Long key = linkKey(p, q);
      Boolean cached = this.survives.get(key);
      if (cached != null) return cached;
      Link link = this.candidate(p, q);
      boolean keep = link != null && this.eligible(link) &&
              (this.baseKeeps(link) || this.nominatedBy(p, link) || this.nominatedBy(q, link));
      this.survives.put(key, keep);
      return keep;
   }

   private static double weight(Link l) {
      return l.length;
   }

   /**
    * True if this link is the longest candidate of some rectangle of cities (1x1, 1x2, 2x1 or 2x2
    * cells) whose whole outline consists of candidate links: dropping the longest edge of a cycle
    * never disconnects anything, whatever else is dropped.
    */
   private boolean longestOfShortCycle(City p, City q) {
      Link self = this.candidate(p, q);
      long selfKey = linkKey(p, q);
      for (int w = 1; w <= 2; w++) {
         for (int h = 1; h <= 2; h++) {
            for (int a0 = Math.min(p.a, q.a) - 2; a0 <= Math.max(p.a, q.a) + 0; a0++) {
               for (int b0 = Math.min(p.b, q.b) - 2; b0 <= Math.max(p.b, q.b) + 0; b0++) {
                  List<Link> outline = this.outline(a0, b0, w, h);
                  if (outline == null) {
                     continue;
                  }
                  boolean contains = false;
                  for (Link l : outline) {
                     if (linkKey(l.from, l.to) == selfKey) {
                        contains = true;
                        break;
                     }
                  }
                  if (!contains) {
                     continue;
                  }
                  Link longest = null;
                  for (Link l : outline) {
                     if (longest == null || longer(l, longest)) {
                        longest = l;
                     }
                  }
                  if (longest == self || linkKey(longest.from, longest.to) == selfKey) {
                     return true;
                  }
               }
            }
         }
      }
      return false;
   }

   private static boolean longer(Link x, Link y) {
      double wx = weight(x);
      double wy = weight(y);
      if (wx != wy) {
         return wx > wy;
      }
      return linkKey(x.from, x.to) > linkKey(y.from, y.to);
   }

   /** The candidate links around a w x h block of cells starting at (a0, b0), or null if any one is missing. */
   private List<Link> outline(int a0, int b0, int w, int h) {
      List<Link> out = new ArrayList<Link>(2 * (w + h));
      for (int i = 0; i < w; i++) {
         Link top = this.candidate(this.city(a0 + i, b0), this.city(a0 + i + 1, b0));
         Link bottom = this.candidate(this.city(a0 + i, b0 + h), this.city(a0 + i + 1, b0 + h));
         if (top == null || bottom == null) {
            return null;
         }
         out.add(top);
         out.add(bottom);
      }
      for (int j = 0; j < h; j++) {
         Link left = this.candidate(this.city(a0, b0 + j), this.city(a0, b0 + j + 1));
         Link right = this.candidate(this.city(a0 + w, b0 + j), this.city(a0 + w, b0 + j + 1));
         if (left == null || right == null) {
            return null;
         }
         out.add(left);
         out.add(right);
      }
      return out;
   }

   /** Every link that is kept, among the cities of the given cell range. */
   public List<Link> linksAmong(int aMin, int bMin, int aMax, int bMax) {
      List<Link> out = new ArrayList<Link>();
      for (int a = aMin; a <= aMax; a++) {
         for (int b = bMin; b <= bMax; b++) {
            City c = this.city(a, b);
            if (a + 1 <= aMax) {
               City e = this.city(a + 1, b);
               if (this.keepsLink(c, e)) {
                  out.add(this.candidate(c, e));
               }
            }
            if (b + 1 <= bMax) {
               City s = this.city(a, b + 1);
               if (this.keepsLink(c, s)) {
                  out.add(this.candidate(c, s));
               }
            }
         }
      }
      return out;
   }

   // ------------------------------------------------------------------ queries

   /** The route between two grid-neighbour cities before pruning, or null when no clean route exists. */
   public Link route(City p, City q) {
      return this.candidate(p, q);
   }

   /** Whether the link between two grid-neighbour cities survives pruning (and so is drawn). */
   public boolean keeps(City p, City q) {
      return this.keepsLink(p, q);
   }

   /** Every highway band that touches the area (both ends inclusive): rings, through-roads and links. */
   public List<Rect> rectsIn(int minX, int minZ, int maxX, int maxZ) {
      int aMin = this.cellOf(minX) - 2;
      int aMax = this.cellOf(maxX) + 2;
      int bMin = this.cellOf(minZ) - 2;
      int bMax = this.cellOf(maxZ) + 2;
      List<Rect> out = new ArrayList<Rect>();
      for (int a = aMin; a <= aMax; a++) {
         for (int b = bMin; b <= bMax; b++) {
            City c = this.city(a, b);
            if (!c.present) {
               continue;
            }
            if (c.x1 + 1 < minX || c.x0 - 1 > maxX || c.z1 + 1 < minZ || c.z0 - 1 > maxZ) {
               continue;
            }
            for (Rect r : cityRects(c)) {
               if (r.intersects(minX, minZ, maxX, maxZ)) {
                  out.add(r);
               }
            }
         }
      }
      for (Link l : this.linksAmong(aMin, bMin, aMax, bMax)) {
         for (Rect r : l.rects) {
            if (r.intersects(minX, minZ, maxX, maxZ)) {
               out.add(r);
            }
         }
      }
      return out;
   }

   /**
    * A snapshot mask of the highway lanes inside an area, with a two-chunk border so edge cells can be
    * classified. pieceFor() reads the eight neighbours, and bendInfo() reads two chunks out, so a lane on the
    * edge of a tile needs that much of the next tile's road to reach the same answer the tile next door
    * reaches for its half of the same corner.
    */
   public Snapshot snapshot(int minX, int minZ, int maxX, int maxZ) {
      return new Snapshot(this.rectsIn(minX - 3, minZ - 3, maxX + 3, maxZ + 3), minX - 2, minZ - 2, maxX + 2, maxZ + 2);
   }

   /** Painted boolean grid over an area; implements Mask. Cells outside the area read as no highway. */
   public static final class Snapshot implements Mask {
      private final int minX;
      private final int minZ;
      private final int w;
      private final int h;
      private final boolean[] cells;

      Snapshot(List<Rect> rects, int minX, int minZ, int maxX, int maxZ) {
         this.minX = minX;
         this.minZ = minZ;
         this.w = maxX - minX + 1;
         this.h = maxZ - minZ + 1;
         this.cells = new boolean[this.w * this.h];
         for (Rect r : rects) {
            int x0 = Math.max(r.minX, minX);
            int x1 = Math.min(r.maxX, maxX);
            int z0 = Math.max(r.minZ, minZ);
            int z1 = Math.min(r.maxZ, maxZ);
            for (int z = z0; z <= z1; z++) {
               for (int x = x0; x <= x1; x++) {
                  this.cells[(z - minZ) * this.w + (x - minX)] = true;
               }
            }
         }
      }

      @Override
      public boolean at(int x, int z) {
         int ix = x - this.minX;
         int iz = z - this.minZ;
         if (ix < 0 || iz < 0 || ix >= this.w || iz >= this.h) {
            return false;
         }
         return this.cells[iz * this.w + ix];
      }
   }

   // ------------------------------------------------------------------ piece selection

   /**
    * The piece for the highway lane chunk at (x, z), from which of its eight neighbours are lanes.
    * Returns null for a shape that should never occur (a lone lane, a dead end, a filled blob);
    * check isWellFormed() when testing.
    *
    *  - three neighbours: a straight lane. The side with no neighbour is the side its buddy is NOT on,
    *    so a lane missing its west neighbour is the west lane: HIGHWAY_SIDE_W (buddy to the east).
    *  - four neighbours with exactly one diagonal missing: a corner of a crossing or a T, named for the
    *    missing diagonal: HIGHWAY_C_<that diagonal>. This reproduces the old tile ring, where the four
    *    tiles' corner pieces met in the middle of a crossing. With no road two chunks out on the sides
    *    opposite the missing diagonal it is the inner lane of a bend instead: the plain turn, same as its
    *    partner (see bendInfo() for when a street joining the bend turns both into intersection pieces).
    *  - two perpendicular neighbours: the outer lane of a bend, HIGHWAY_TURN_<the two sides>.
    */
   public static RoadType pieceFor(Mask m, int x, int z) {
      boolean n = m.at(x, z - 1);
      boolean e = m.at(x + 1, z);
      boolean s = m.at(x, z + 1);
      boolean w = m.at(x - 1, z);
      int count = (n ? 1 : 0) + (e ? 1 : 0) + (s ? 1 : 0) + (w ? 1 : 0);
      if (count == 3) {
         if (!n) {
            return RoadType.HIGHWAY_SIDE_N;
         }
         if (!e) {
            return RoadType.HIGHWAY_SIDE_E;
         }
         if (!s) {
            return RoadType.HIGHWAY_SIDE_S;
         }
         return RoadType.HIGHWAY_SIDE_W;
      }
      if (count == 4) {
         boolean nw = m.at(x - 1, z - 1);
         boolean ne = m.at(x + 1, z - 1);
         boolean sw = m.at(x - 1, z + 1);
         boolean se = m.at(x + 1, z + 1);
         int missing = (nw ? 0 : 1) + (ne ? 0 : 1) + (sw ? 0 : 1) + (se ? 0 : 1);
         if (missing != 1) {
            return null;
         }
         // The inner lane of a bend has exactly the same eight neighbours as a quadrant of a crossing or a T
         // (open on all four sides, one diagonal missing). What tells them apart is road two chunks out on the
         // sides OPPOSITE the missing diagonal: a crossing or T has an arm there, a bend does not.
         if (!nw) {
            return !m.at(x, z + 2) && !m.at(x + 2, z) ? RoadType.HIGHWAY_TURN_NW : RoadType.HIGHWAY_C_NW;
         }
         if (!ne) {
            return !m.at(x, z + 2) && !m.at(x - 2, z) ? RoadType.HIGHWAY_TURN_NE : RoadType.HIGHWAY_C_NE;
         }
         if (!sw) {
            return !m.at(x, z - 2) && !m.at(x + 2, z) ? RoadType.HIGHWAY_TURN_SW : RoadType.HIGHWAY_C_SW;
         }
         return !m.at(x, z - 2) && !m.at(x - 2, z) ? RoadType.HIGHWAY_TURN_SE : RoadType.HIGHWAY_C_SE;
      }
      if (count == 2) {
         if (n && e) {
            return RoadType.HIGHWAY_TURN_NE;
         }
         if (e && s) {
            return RoadType.HIGHWAY_TURN_SE;
         }
         if (s && w) {
            return RoadType.HIGHWAY_TURN_SW;
         }
         if (w && n) {
            return RoadType.HIGHWAY_TURN_NW;
         }
      }
      return null;
   }

   public static final int BEND_OUTER = 1;
   public static final int BEND_INNER = 2;
   public static final int BEND_STRAIGHT = 3;

   /** Directions as indices: 0 = north, 1 = east, 2 = south, 3 = west. */
   private static final int[] DIR_X = {0, 1, 0, -1};
   private static final int[] DIR_Z = {-1, 0, 1, 0};

   /**
    * Whether the lane chunk at (x, z) belongs to the 2x2 block of a bend, and its place in it: {role, ox, oz, a, b}.
    *
    * A bend is four chunks. Two plain turns sit diagonally (the OUTER lane's turn and the INNER lane's turn, the
    * same piece with the same rotation), and the other two are the straights that lead into the outer turn
    * (BEND_STRAIGHT). (ox, oz) is the outer turn's chunk, and a and b are the two directions the bend's arms
    * leave in. The streets that can join a bend are on its two outward sides, away from a and b, which is what
    * HighwayParcel looks at.
    *
    * Works from the mask alone, so it is the same whichever tile asks, and it is what lets HighwayParcel decide
    * for the whole block at once. Returns null for a lane that is not part of a bend.
    */
   public static int[] bendInfo(Mask m, int x, int z) {
      int[] own = turnSides(m, x, z);
      if (own != null) {
         return new int[]{BEND_OUTER, x, z, own[0], own[1]};
      }
      boolean n = m.at(x, z - 1);
      boolean e = m.at(x + 1, z);
      boolean s = m.at(x, z + 1);
      boolean w = m.at(x - 1, z);
      int count = (n ? 1 : 0) + (e ? 1 : 0) + (s ? 1 : 0) + (w ? 1 : 0);
      if (count == 4) {
         boolean nw = m.at(x - 1, z - 1);
         boolean ne = m.at(x + 1, z - 1);
         boolean sw = m.at(x - 1, z + 1);
         boolean se = m.at(x + 1, z + 1);
         if ((nw ? 0 : 1) + (ne ? 0 : 1) + (sw ? 0 : 1) + (se ? 0 : 1) != 1) {
            return null;
         }
         int a;
         int b;
         if (!se) {
            a = 1;
            b = 2;
         } else if (!sw) {
            a = 2;
            b = 3;
         } else if (!nw) {
            a = 3;
            b = 0;
         } else {
            a = 0;
            b = 1;
         }
         if (m.at(x - 2 * DIR_X[a], z - 2 * DIR_Z[a]) || m.at(x - 2 * DIR_X[b], z - 2 * DIR_Z[b])) {
            return null;   // an arm on the far side: a crossing or a T, not a bend
         }
         int ox = x - DIR_X[a] - DIR_X[b];
         int oz = z - DIR_Z[a] - DIR_Z[b];
         int[] outer = turnSides(m, ox, oz);
         if (outer == null || !((outer[0] == a && outer[1] == b) || (outer[0] == b && outer[1] == a))) {
            return null;
         }
         return new int[]{BEND_INNER, ox, oz, a, b};
      }
      if (count == 3) {
         for (int d = 0; d < 4; d++) {
            int px = x + DIR_X[d];
            int pz = z + DIR_Z[d];
            if (!m.at(px, pz)) {
               continue;
            }
            int[] sides = turnSides(m, px, pz);
            // the straight must be on one of that turn's two connected sides, i.e. the turn's arm points back at it
            if (sides != null && (sides[0] == (d + 2) % 4 || sides[1] == (d + 2) % 4)) {
               return new int[]{BEND_STRAIGHT, px, pz, sides[0], sides[1]};
            }
         }
      }
      return null;
   }

   /** The two directions a plain turn's lane connects (its only two lane neighbours), or null if it is not one. */
   private static int[] turnSides(Mask m, int x, int z) {
      boolean n = m.at(x, z - 1);
      boolean e = m.at(x + 1, z);
      boolean s = m.at(x, z + 1);
      boolean w = m.at(x - 1, z);
      if ((n ? 1 : 0) + (e ? 1 : 0) + (s ? 1 : 0) + (w ? 1 : 0) != 2) {
         return null;
      }
      if (n && e) {
         return new int[]{0, 1};
      }
      if (e && s) {
         return new int[]{1, 2};
      }
      if (s && w) {
         return new int[]{2, 3};
      }
      if (w && n) {
         return new int[]{3, 0};
      }
      return null;
   }

   /** The geometric shape of a highway lane chunk, which the piece alone cannot say (see shapeAt()). */
   public enum Shape {
      /** One lane of a straight two-lane road. */
      STRAIGHT,
      /** One lane of a bend (the outer and the inner lane both). */
      BEND,
      /** One of the two cells of a T junction that sit on the side of the stem. */
      T,
      /** One of the four cells of the 2x2 core of a four-way crossing. */
      X
   }

   /**
    * What shape the lane at (x, z) is part of, or null if it is not a lane or a shape pieceFor() has no piece for.
    * The pieces cannot tell: HIGHWAY_C_* serves the cells of a crossing and the shoulders of a T alike, and
    * HIGHWAY_TURN_* both lanes of a bend. A C piece is one cell of the 2x2 core of a junction; the other three
    * cells of that core tell the two apart -- in a crossing every one of them is open on all four sides, in a
    * T the two on the side away from the stem are plain straight lanes.
    */
   public static Shape shapeAt(Mask m, int x, int z) {
      RoadType t = pieceFor(m, x, z);
      if (t == null) {
         return null;
      }
      String name = t.name();
      if (name.startsWith("HIGHWAY_TURN")) {
         return Shape.BEND;
      }
      if (!name.startsWith("HIGHWAY_C")) {
         return Shape.STRAIGHT;
      }
      // the missing diagonal's corner is the last two letters; the rest of the core lies away from it
      int dx = name.endsWith("W") ? 1 : -1;
      int dz = name.charAt(name.length() - 2) == 'N' ? 1 : -1;
      int open = (openOnAllSides(m, x + dx, z) ? 1 : 0)
              + (openOnAllSides(m, x, z + dz) ? 1 : 0)
              + (openOnAllSides(m, x + dx, z + dz) ? 1 : 0);
      return open == 3 ? Shape.X : Shape.T;
   }

   private static boolean openOnAllSides(Mask m, int x, int z) {
      return m.at(x, z - 1) && m.at(x + 1, z) && m.at(x, z + 1) && m.at(x - 1, z);
   }

   /** The piece to place, never null: a malformed cell falls back to a straight so a world always generates. */
   public static RoadType pieceOrFallback(Mask m, int x, int z) {
      RoadType t = pieceFor(m, x, z);
      if (t != null) {
         return t;
      }
      boolean n = m.at(x, z - 1);
      boolean s = m.at(x, z + 1);
      boolean e = m.at(x + 1, z);
      boolean w = m.at(x - 1, z);
      if ((e || w) && !(n || s)) {
         return RoadType.HIGHWAY_SIDE_N;
      }
      if (n || s) {
         return RoadType.HIGHWAY_SIDE_W;
      }
      return RoadType.HIGHWAY_SIDE_N;
   }
}
