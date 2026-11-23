package ch.k42.metropolis.grid.urbanGrid.farm;

/** One thing in a farm cell (a field, a barn, a silo...). Paints only what falls inside the painter's chunk. */
abstract class FarmFeature {
   final int minX;
   final int minZ;
   final int maxX;
   final int maxZ;

   FarmFeature(int minX, int minZ, int maxX, int maxZ) {
      this.minX = minX;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxZ = maxZ;
   }

   final void paintIfVisible(FarmPainter p) {
      if (p.intersects(this.minX, this.minZ, this.maxX, this.maxZ)) {
         this.paint(p);
      }
   }

   abstract void paint(FarmPainter p);
}
