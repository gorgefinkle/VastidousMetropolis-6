package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.minions.GridRandom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the districts (blocks) and the streets between them go, worked out per CITY instead of per
 * 64x64 tile.
 *
 * A city box (HighwayNetwork.City) is a square ring road with two through-roads crossing it, which
 * leaves four rectangular quadrants. Each quadrant is cut up the same way UrbanGrid.recSetDistricts()
 * cuts a tile: split the longer side at a gaussian cut, put a one-chunk street in the cut, recurse,
 * and stop when a piece is smaller than blockSize or too small to cut. The difference is the random
 * stream: it is seeded from (world seed, city), never from a tile, so the layout is identical
 * whichever tile asks and a block can straddle a tile edge.
 *
 * All coordinates are global chunk coordinates, ends inclusive. Nothing here touches Bukkit (it
 * only needs HighwayNetwork and GridRandom, which don't either), so it runs and is tested standalone.
 * Results are cached per city; the class is thread safe.
 */
public final class CityLayout {

   /** Width in chunks of the street between two blocks. */
   public static final int STREET = 1;

   /** An axis-aligned rectangle of chunks, both ends inclusive. */
   public static final class Rect {
      public final int minX;
      public final int minZ;
      public final int maxX;
      public final int maxZ;

      Rect(int minX, int minZ, int maxX, int maxZ) {
         this.minX = minX;
         this.minZ = minZ;
         this.maxX = maxX;
         this.maxZ = maxZ;
      }

      public int width() {
         return this.maxX - this.minX + 1;
      }

      public int depth() {
         return this.maxZ - this.minZ + 1;
      }

      public boolean contains(int x, int z) {
         return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
      }

      public boolean intersects(int ominX, int ominZ, int omaxX, int omaxZ) {
         return this.minX <= omaxX && this.maxX >= ominX && this.minZ <= omaxZ && this.maxZ >= ominZ;
      }

      @Override
      public String toString() {
         return "[" + this.minX + "," + this.minZ + " .. " + this.maxX + "," + this.maxZ + "]";
      }
   }

   /** What one chunk is, as far as the city layout goes. */
   public enum Kind {
      /** Not inside any city's streets or blocks (highway band, outside every box, or a city that is not present). */
      NONE,
      /** Part of a block (a district). */
      BLOCK,
      /** A street between two blocks. */
      STREET
   }

   /** The blocks and streets of one city, in the order they were carved. */
   public static final class Layout {
      public final HighwayNetwork.City city;
      public final List<Rect> blocks;
      public final List<Rect> streets;

      Layout(HighwayNetwork.City city, List<Rect> blocks, List<Rect> streets) {
         this.city = city;
         this.blocks = Collections.unmodifiableList(blocks);
         this.streets = Collections.unmodifiableList(streets);
      }
   }

   private final HighwayNetwork network;
   private final long seed;
   private final int spacing;
   private final int blockSize;
   private final int blockSizeMin;
   private final int sigmaFactor;
   private final Map<Long, Layout> cache = new HashMap<Long, Layout>();

   /**
    * @param spacing      the city grid spacing the network was built with (generator.cityRings.spacing)
    * @param blockSize    a piece smaller than this in its long side is not cut any more
    * @param blockSizeMin neither side of a cut may be smaller than this
    * @param sigmaFactor  generator.sigmaCut: the cut's gaussian has sigma = (size / 2) / sigmaFactor
    */
   public CityLayout(HighwayNetwork network, long seed, int spacing, int blockSize, int blockSizeMin, int sigmaFactor) {
      this.network = network;
      this.seed = seed;
      this.spacing = spacing;
      this.blockSize = blockSize;
      this.blockSizeMin = Math.max(1, blockSizeMin);
      this.sigmaFactor = Math.max(1, sigmaFactor);
   }

   // ------------------------------------------------------------------ per city

   /** The layout of city (a, b). Empty for a city that is not present. */
   public synchronized Layout layout(int a, int b) {
      Long key = (((long) a) << 32) ^ (b & 0xffffffffL);
      Layout l = this.cache.get(key);
      if (l == null) {
         l = this.carve(this.network.city(a, b));
         this.cache.put(key, l);
      }
      return l;
   }

   private Layout carve(HighwayNetwork.City c) {
      List<Rect> blocks = new ArrayList<Rect>();
      List<Rect> streets = new ArrayList<Rect>();
      if (c.present) {
         GridRandom random = new GridRandom(this.seed ^ 0x43495459L, c.a, c.b);
         // Inside the ring (2 wide) and the through-roads (2 wide, chunks cx..cx+1 and cz..cz+1).
         Rect nw = new Rect(c.x0 + 2, c.z0 + 2, c.cx - 1, c.cz - 1);
         Rect ne = new Rect(c.cx + 2, c.z0 + 2, c.x1 - 2, c.cz - 1);
         Rect sw = new Rect(c.x0 + 2, c.cz + 2, c.cx - 1, c.z1 - 2);
         Rect se = new Rect(c.cx + 2, c.cz + 2, c.x1 - 2, c.z1 - 2);

         // Each quadrant is cut on its own, so streets reach a through-road from either side at independent
         // positions: where one arrives and none meets it on the far side that is a T, where two line up it is
         // an X. HighwayParcel and RoadParcel pick the T/X pieces from the neighbours, as they always did.
         this.carveQuadrant(random, nw, blocks, streets);
         this.carveQuadrant(random, ne, blocks, streets);
         this.carveQuadrant(random, sw, blocks, streets);
         this.carveQuadrant(random, se, blocks, streets);
      }
      return new Layout(c, blocks, streets);
   }

   private void carveQuadrant(GridRandom random, Rect q, List<Rect> blocks, List<Rect> streets) {
      if (q.width() > 0 && q.depth() > 0) {
         this.split(random, q.minX, q.minZ, q.width(), q.depth(), blocks, streets);
      }
   }

   /** The same decision tree as UrbanGrid.recSetDistricts(), minus landmarks, river and dense-residential caps. */
   private void split(GridRandom random, int baseX, int baseZ, int sizeX, int sizeZ, List<Rect> blocks, List<Rect> streets) {
      if (sizeX > sizeZ) {
         if (sizeX < this.blockSize || !this.canCut(sizeX)) {
            blocks.add(new Rect(baseX, baseZ, baseX + sizeX - 1, baseZ + sizeZ - 1));
         } else {
            int cut = this.makeCut(random, sizeX);
            streets.add(new Rect(baseX + cut, baseZ, baseX + cut, baseZ + sizeZ - 1));
            this.split(random, baseX, baseZ, cut, sizeZ, blocks, streets);
            this.split(random, baseX + cut + STREET, baseZ, sizeX - cut - STREET, sizeZ, blocks, streets);
         }
      } else if (sizeZ < this.blockSize || !this.canCut(sizeZ)) {
         blocks.add(new Rect(baseX, baseZ, baseX + sizeX - 1, baseZ + sizeZ - 1));
      } else {
         int cut = this.makeCut(random, sizeZ);
         streets.add(new Rect(baseX, baseZ + cut, baseX + sizeX - 1, baseZ + cut));
         this.split(random, baseX, baseZ, sizeX, cut, blocks, streets);
         this.split(random, baseX, baseZ + cut + STREET, sizeX, sizeZ - cut - STREET, blocks, streets);
      }
   }

   /** A dimension can be cut only if both sides keep blockSizeMin and the street fits between them. */
   private boolean canCut(int dimension) {
      return dimension >= 2 * this.blockSizeMin + STREET;
   }

   /** Minions.makeCut(random, size, min): a gaussian around the middle, clamped so both sides keep blockSizeMin. */
   private int makeCut(GridRandom random, int size) {
      double mean = size / 2.0;
      double sigma = mean / this.sigmaFactor;
      int cut = (int) Math.round(mean + random.getRandomGaussian() * sigma);
      int lower = this.blockSizeMin;
      int upper = size - 1 - lower;
      return cut < lower ? lower : (cut > upper ? upper : cut);
   }

   // ------------------------------------------------------------------ queries by area

   /** Every city whose box can reach the chunk rectangle (the same neighbourhood HighwayNetwork.distance() reads). */
   private List<Layout> layoutsNear(int minX, int minZ, int maxX, int maxZ) {
      int a0 = this.cellOf(minX) - 1;
      int a1 = this.cellOf(maxX) + 1;
      int b0 = this.cellOf(minZ) - 1;
      int b1 = this.cellOf(maxZ) + 1;
      List<Layout> out = new ArrayList<Layout>();
      for (int a = a0; a <= a1; a++) {
         for (int b = b0; b <= b1; b++) {
            Layout l = this.layout(a, b);
            if (l.city.present && l.city.x1 >= minX && l.city.x0 <= maxX && l.city.z1 >= minZ && l.city.z0 <= maxZ) {
               out.add(l);
            }
         }
      }
      return out;
   }

   private int cellOf(int chunk) {
      return (int) Math.floor(chunk / (double) this.spacing);
   }

   /**
    * The blocks that touch the chunk rectangle, whole (not clipped to it). A block that straddles the
    * rectangle's edge is returned in full, so a caller can see the part outside it and clip itself.
    */
   public List<Rect> blocksIn(int minX, int minZ, int maxX, int maxZ) {
      return this.collect(minX, minZ, maxX, maxZ, true);
   }

   /** The streets that touch the chunk rectangle, whole. */
   public List<Rect> streetsIn(int minX, int minZ, int maxX, int maxZ) {
      return this.collect(minX, minZ, maxX, maxZ, false);
   }

   private List<Rect> collect(int minX, int minZ, int maxX, int maxZ, boolean blocks) {
      List<Rect> out = new ArrayList<Rect>();
      for (Layout l : this.layoutsNear(minX, minZ, maxX, maxZ)) {
         for (Rect r : blocks ? l.blocks : l.streets) {
            if (r.intersects(minX, minZ, maxX, maxZ)) {
               out.add(r);
            }
         }
      }
      return out;
   }

   /** True if chunk (x, z) is inside some city's box (ring, through-roads, blocks and streets alike). */
   public boolean insideCityBox(int x, int z) {
      for (Layout l : this.layoutsNear(x, z, x, z)) {
         if (l.city.boxContains(x, z)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Is chunk (x, z) a street of a city, or a lane of that city's ring or through-roads? Used by road pieces to
    * tell what lies beside them without asking a neighbouring tile that may not exist yet. Only answers for
    * cells in or on the edge of a city box; a link road further out is not covered (and never touches a street).
    */
   public boolean isCityRoadCell(int x, int z) {
      for (Layout l : this.layoutsNear(x, z, x, z)) {
         for (Rect r : l.streets) {
            if (r.contains(x, z)) {
               return true;
            }
         }
         for (HighwayNetwork.Rect r : HighwayNetwork.cityRects(l.city)) {
            if (r.contains(x, z)) {
               return true;
            }
         }
      }
      return false;
   }

   /** Is chunk (x, z) a street between blocks (not a highway lane)? */
   public boolean isStreetCell(int x, int z) {
      return this.kindAt(x, z) == Kind.STREET;
   }

   /** What chunk (x, z) is: a block, a street, or neither. */
   public Kind kindAt(int x, int z) {
      for (Layout l : this.layoutsNear(x, z, x, z)) {
         for (Rect r : l.blocks) {
            if (r.contains(x, z)) {
               return Kind.BLOCK;
            }
         }
         for (Rect r : l.streets) {
            if (r.contains(x, z)) {
               return Kind.STREET;
            }
         }
      }
      return Kind.NONE;
   }

   /** The block containing chunk (x, z), or null. */
   public Rect blockAt(int x, int z) {
      for (Layout l : this.layoutsNear(x, z, x, z)) {
         for (Rect r : l.blocks) {
            if (r.contains(x, z)) {
               return r;
            }
         }
      }
      return null;
   }
}