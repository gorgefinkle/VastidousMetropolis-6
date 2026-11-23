package ch.k42.metropolis.grid.urbanGrid.plan;

/**
 * What is at every chunk of the world, worked out from the world seed alone.
 *
 * There is no tile in this interface and none behind it: a question about chunk (x, z) is answered the same
 * whether or not anything near it has been generated, in any order, by any caller. A block is one rectangle
 * whatever 64-chunk line it crosses, and every chunk of it reports that same rectangle, so whoever fills a block
 * (with a random stream seeded from the block) gets the same buildings from any chunk that asks.
 *
 * Pure Java, no Bukkit. Implementations cache what they compute; the cache only saves work and is never
 * what makes an answer true.
 */
public interface WorldPlan {

   /** What a chunk is. Every chunk of the world is exactly one of these. */
   enum Kind {
      /** Open land outside every city's territory: forest and farm, which this slice of the plan does not carve yet. */
      WILD,
      /** One lane of a highway. */
      LANE,
      /** A street between two blocks. */
      STREET,
      /** Part of a block (a district). */
      BLOCK,
      /** Inside a city's highway ring but claimed by neither a block nor a street: a gap in the plan. Must never occur. */
      UNCLAIMED
   }

   /** Where a block sits: inside a city's ring or in the band around it. */
   enum Zone {
      NONE, CITY, OUTER
   }

   /** An axis-aligned rectangle of chunks, both ends inclusive. */
   final class Rect {
      public final int minX, minZ, maxX, maxZ;

      public Rect(int minX, int minZ, int maxX, int maxZ) {
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

      public boolean intersects(int x0, int z0, int x1, int z1) {
         return this.maxX >= x0 && this.minX <= x1 && this.maxZ >= z0 && this.minZ <= z1;
      }

      @Override
      public boolean equals(Object o) {
         if (!(o instanceof Rect)) {
            return false;
         }
         Rect r = (Rect) o;
         return r.minX == this.minX && r.minZ == this.minZ && r.maxX == this.maxX && r.maxZ == this.maxZ;
      }

      @Override
      public int hashCode() {
         return ((this.minX * 31 + this.minZ) * 31 + this.maxX) * 31 + this.maxZ;
      }

      @Override
      public String toString() {
         return "[" + this.minX + ".." + this.maxX + "," + this.minZ + ".." + this.maxZ + "]";
      }
   }

   /** The answer for one chunk. */
   final class Cell {
      public final Kind kind;
      public final Zone zone;
      /** For a BLOCK, the whole block this chunk belongs to (the same rectangle for every one of its chunks); otherwise null. */
      public final Rect block;

      public Cell(Kind kind, Zone zone, Rect block) {
         this.kind = kind;
         this.zone = zone;
         this.block = block;
      }
   }

   /** What chunk (x, z) is. Never null. */
   Cell cellAt(int chunkX, int chunkZ);

   /** Every block that touches the window (ends inclusive), each once. */
   java.util.List<Rect> blocksIn(int minX, int minZ, int maxX, int maxZ);
}
