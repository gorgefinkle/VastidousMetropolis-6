package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;

/**
 * One continuous, narrow wilderness tributary. The route is stored as a
 * world-block polyline and distance is measured to its nearest segment;
 * bends therefore remain one connected channel instead of becoming two
 * unrelated straight river parcels with a possible gap at the join.
 *
 * The shared course has a default axis for read-only bank queries. UrbanGrid
 * creates a lightweight local-axis view for each RiverParcel so the existing
 * highway retaining-wall handoff still knows which way that local section
 * crosses a highway.
 */
public final class WildernessStreamCourse extends RiverProvider {
   private final long courseSeed;
   private final double[] xs;
   private final double[] zs;
   private final double widthBlocks;
   private final double bankMarginBlocks;
   private final double minX, maxX, minZ, maxZ;
   private final boolean localAxisZ;
   private final boolean axisPinned;
   private final HydrologyConvergenceKey convergenceKey;

   public WildernessStreamCourse(long seed,double[] xs,double[] zs,double widthBlocks,double bankMarginBlocks) {
      this(seed,xs,zs,widthBlocks,bankMarginBlocks,null);
   }
   public WildernessStreamCourse(long seed,double[] xs,double[] zs,double widthBlocks,double bankMarginBlocks,
                                 HydrologyConvergenceKey convergenceKey) {
      this(seed,xs.clone(),zs.clone(),widthBlocks,bankMarginBlocks,dominantAxis(xs,zs),false,convergenceKey);
   }
   private WildernessStreamCourse(long seed,double[] xs,double[] zs,double widthBlocks,double bankMarginBlocks,
                                  boolean localAxisZ,boolean axisPinned,HydrologyConvergenceKey convergenceKey) {
      super(seed, false, Double.NaN, Double.NaN);
      if (xs == null || zs == null || xs.length != zs.length || xs.length < 2)
         throw new IllegalArgumentException("A wilderness stream needs at least two matched path points");
      if (!Double.isFinite(widthBlocks) || widthBlocks < 2.0 || widthBlocks > 64.0)
         throw new IllegalArgumentException("Invalid wilderness stream width");
      if (!Double.isFinite(bankMarginBlocks) || bankMarginBlocks < 0.0 || bankMarginBlocks > 64.0)
         throw new IllegalArgumentException("Invalid wilderness stream bank margin");
      this.courseSeed = seed;
      this.xs = xs;
      this.zs = zs;
      this.widthBlocks = widthBlocks;
      this.bankMarginBlocks = bankMarginBlocks;
      this.localAxisZ = localAxisZ;
      this.axisPinned = axisPinned;
      this.convergenceKey=convergenceKey;
      double loX = Double.POSITIVE_INFINITY, hiX = Double.NEGATIVE_INFINITY;
      double loZ = Double.POSITIVE_INFINITY, hiZ = Double.NEGATIVE_INFINITY;
      for (int i = 0; i < xs.length; i++) {
         if (!Double.isFinite(xs[i]) || !Double.isFinite(zs[i]))
            throw new IllegalArgumentException("Wilderness stream points must be finite");
         loX = Math.min(loX, xs[i]); hiX = Math.max(hiX, xs[i]);
         loZ = Math.min(loZ, zs[i]); hiZ = Math.max(hiZ, zs[i]);
      }
      this.minX = loX; this.maxX = hiX; this.minZ = loZ; this.maxZ = hiZ;
   }

   private static boolean dominantAxis(double[] xs, double[] zs) {
      double alongX = 0.0, alongZ = 0.0;
      if (xs == null || zs == null || xs.length != zs.length) return false;
      for (int i = 1; i < xs.length; i++) {
         alongX += Math.abs(xs[i] - xs[i - 1]);
         alongZ += Math.abs(zs[i] - zs[i - 1]);
      }
      return alongZ > alongX;
   }

   public long courseSeed() {
      return this.courseSeed;
   }
   /** Exact receiving-network junction shared with the receiver plan. */
   public HydrologyConvergenceKey convergenceKey(){return this.convergenceKey;}
   /** The receiving network owns this exact terminal chunk, not the tributary. */
   public boolean terminatesAt(int chunkX,int chunkZ) {
      return this.convergenceKey!=null&&this.convergenceKey.chunkX()==chunkX
              &&this.convergenceKey.chunkZ()==chunkZ;
   }

   public int pointCount() {
      return this.xs.length;
   }

   /** World-block centreline point, exposed read-only for deterministic ownership audits. */
   public double pointXBlock(int point) { return this.xs[point]; }

   /** World-block centreline point, exposed read-only for deterministic ownership audits. */
   public double pointZBlock(int point) { return this.zs[point]; }

   public WildernessStreamCourse forChunk(int chunkX, int chunkZ) {
      boolean zAxis = isZBoundAt(chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0);
      return new WildernessStreamCourse(this.courseSeed,this.xs,this.zs,
              this.widthBlocks,this.bankMarginBlocks,zAxis,true,this.convergenceKey);
   }

   public boolean isZBoundAt(int chunkX, int chunkZ) {
      return isZBoundAt(chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0);
   }

   private boolean isZBoundAt(double x, double z) {
      Nearest nearest = nearest(x, z);
      if (nearest == null) return this.localAxisZ;
      return Math.abs(nearest.dz) > Math.abs(nearest.dx);
   }

   @Override
   public boolean isZBound() {
      return this.localAxisZ;
   }

   @Override
   public boolean isCreek() {
      // It is a narrow river-course for carving, but it is deliberately not
      // registered with UrbanGrid's city-creek origin/park lifecycle.
      return true;
   }

   @Override
   public boolean isEnabled() {
      return super.isEnabled();
   }

   @Override
   public double widthAtBlock(double alongWorldBlock) {
      return isEnabled() ? this.widthBlocks : Double.NaN;
   }

   @Override
   public int widthAt(int alongChunk) {
      return Math.max(1, (int)Math.ceil(this.widthBlocks / 16.0));
   }

   @Override
   public double getHalfWidthBlocks() {
      return this.widthBlocks / 2.0;
   }

   @Override
   public double getFallbackBankMarginBlocks() {
      return this.bankMarginBlocks;
   }

   @Override
   public double getRampMaxRunBlocks() {
      return this.bankMarginBlocks;
   }

   @Override
   public double getEnvelopeHalfWidth() {
      return (this.widthBlocks / 2.0 + this.bankMarginBlocks) / 16.0 + 1.0;
   }

   @Override
   public double distanceFromCenterBlock(double worldBlockX, double worldBlockZ) {
      if (!isEnabled()) return Double.NaN;
      Nearest nearest = nearest(worldBlockX, worldBlockZ);
      if (nearest == null) return Double.NaN;
      double max = this.widthBlocks / 2.0 + this.bankMarginBlocks + 24.0;
      return nearest.distance <= max ? nearest.signedDistance : Double.NaN;
   }

   @Override
   public double distanceFromCenter(int worldChunkX, int worldChunkZ) {
      return distanceFromCenterBlock(worldChunkX * 16.0 + 8.0,
              worldChunkZ * 16.0 + 8.0) / 16.0;
   }

   @Override
   public boolean isRiver(int worldChunkX, int worldChunkZ) {
      double distance = distanceFromCenterBlock(worldChunkX * 16.0 + 8.0,
              worldChunkZ * 16.0 + 8.0);
      return Double.isFinite(distance) && Math.abs(distance) <= this.widthBlocks / 2.0 + 8.0;
   }

   @Override
   public boolean isRiverOrBank(int worldChunkX, int worldChunkZ) {
      double distance = distanceFromCenterBlock(worldChunkX * 16.0 + 8.0,
              worldChunkZ * 16.0 + 8.0);
      return Double.isFinite(distance)
              && Math.abs(distance) <= this.widthBlocks / 2.0 + this.bankMarginBlocks + 8.0;
   }

   @Override
   public double spineBlock(double alongBlock) {
      return this.isZBound() ? spineXBlock(alongBlock) : spineZBlock(alongBlock);
   }

   @Override
   public double spineAcross(int alongChunk) {
      return spineBlock(alongChunk * 16.0 + 8.0) / 16.0;
   }

   @Override
   public double spineZ(int worldChunkX) {
      return spineAcross(worldChunkX);
   }

   @Override
   public double spineZBlock(double worldBlockX) {
      return nearestAcross(worldBlockX, false);
   }

   @Override
   public double spineXBlock(double worldBlockZ) {
      return nearestAcross(worldBlockZ, true);
   }

   private double nearestAcross(double along, boolean alongZ) {
      double best = Double.POSITIVE_INFINITY;
      double across = Double.NaN;
      for (int i = 0; i < this.xs.length - 1; i++) {
         double ax = this.xs[i], az = this.zs[i];
         double bx = this.xs[i + 1], bz = this.zs[i + 1];
         double da = alongZ ? bz - az : bx - ax;
         if (Math.abs(da) < 1.0e-6) continue;
         double t = (along - (alongZ ? az : ax)) / da;
         t = Math.max(0.0, Math.min(1.0, t));
         double projectedAlong = alongZ ? az + da * t : ax + da * t;
         double delta = Math.abs(along - projectedAlong);
         if (delta < best) {
            best = delta;
            across = alongZ ? ax + (bx - ax) * t : az + (bz - az) * t;
         }
      }
      if (Double.isFinite(across)) return across;
      Nearest nearest = alongZ ? nearestOnZ(along) : nearestOnX(along);
      return nearest == null ? Double.NaN : (alongZ ? nearest.x : nearest.z);
   }

   private Nearest nearestOnX(double x) {
      Nearest best = null;
      double bestDelta = Double.POSITIVE_INFINITY;
      for (int i = 0; i < this.xs.length - 1; i++) {
         double dx = this.xs[i + 1] - this.xs[i];
         double dz = this.zs[i + 1] - this.zs[i];
         double t = Math.abs(dx) < 1.0e-6 ? 0.0
                 : Math.max(0.0, Math.min(1.0, (x - this.xs[i]) / dx));
         double px = this.xs[i] + dx * t, pz = this.zs[i] + dz * t;
         double delta = Math.abs(px - x);
         if (delta < bestDelta) {
            bestDelta = delta;
            best = new Nearest(px, pz, dx, dz, 0.0, 0.0);
         }
      }
      return best;
   }

   private Nearest nearestOnZ(double z) {
      Nearest best = null;
      double bestDelta = Double.POSITIVE_INFINITY;
      for (int i = 0; i < this.xs.length - 1; i++) {
         double dx = this.xs[i + 1] - this.xs[i];
         double dz = this.zs[i + 1] - this.zs[i];
         double t = Math.abs(dz) < 1.0e-6 ? 0.0
                 : Math.max(0.0, Math.min(1.0, (z - this.zs[i]) / dz));
         double px = this.xs[i] + dx * t, pz = this.zs[i] + dz * t;
         double delta = Math.abs(pz - z);
         if (delta < bestDelta) {
            bestDelta = delta;
            best = new Nearest(px, pz, dx, dz, 0.0, 0.0);
         }
      }
      return best;
   }

   @Override
   public double getBankEdgeBlockAt(UrbanGrid grid, double worldBlockX, double worldBlockZ, boolean negativeSide) {
      // The stream uses its own narrow, deterministic fallback bank margin.
      return Double.NaN;
   }

   @Override
   public int getAlongLoChunk() {
      double min = this.isZBound() ? this.minZ : this.minX;
      return (int)Math.floor(min / 16.0);
   }

   @Override
   public int getAlongHiChunk() {
      double max = this.isZBound() ? this.maxZ : this.maxX;
      return (int)Math.floor(max / 16.0);
   }

   @Override
   public int getBankEdgeSampleCount() {
      return 0;
   }

   @Override
   public double[] bankEdgeSamplePoint(int sampleIndex) {
      throw new IndexOutOfBoundsException("Wilderness stream has no scanned bank-edge samples");
   }

   @Override
   public double[] bankEdgeScanPoint(int sampleIndex, int offset) {
      throw new IndexOutOfBoundsException("Wilderness stream has no scanned bank-edge samples");
   }

   @Override
   public void recordBankEdges(double[] negative, double[] positive) {
      // No tile-local scan is needed: stream banks are a fixed distance from
      // the seed-stable polyline and remain identical across tile boundaries.
   }

   @Override
   public double waterEdgeNorthBlock(int worldChunkX) {
      if (!isEnabled() || this.isZBound()) return Double.NaN;
      double min = Double.POSITIVE_INFINITY;
      int baseX = worldChunkX << 4;
      for (int i = 0; i <= 16; i++) {
         double x = baseX + i;
         double z = spineZBlock(x);
         if (Double.isFinite(z)) min = Math.min(min, z - this.widthBlocks / 2.0);
      }
      return Double.isFinite(min) ? min : Double.NaN;
   }

   @Override
   public double waterEdgeSouthBlock(int worldChunkX) {
      if (!isEnabled() || this.isZBound()) return Double.NaN;
      double max = Double.NEGATIVE_INFINITY;
      int baseX = worldChunkX << 4;
      for (int i = 0; i <= 16; i++) {
         double x = baseX + i;
         double z = spineZBlock(x);
         if (Double.isFinite(z)) max = Math.max(max, z + this.widthBlocks / 2.0);
      }
      return Double.isFinite(max) ? max : Double.NaN;
   }

   @Override
   public boolean isEastWestOriented(int worldChunkX) {
      return !this.isZBound();
   }

   private Nearest nearest(double x, double z) {
      double bestDistanceSq = Double.POSITIVE_INFINITY;
      Nearest best = null;
      for (int i = 0; i < this.xs.length - 1; i++) {
         double ax = this.xs[i], az = this.zs[i];
         double dx = this.xs[i + 1] - ax, dz = this.zs[i + 1] - az;
         double lengthSq = dx * dx + dz * dz;
         if (lengthSq < 1.0e-9) continue;
         double t = ((x - ax) * dx + (z - az) * dz) / lengthSq;
         t = Math.max(0.0, Math.min(1.0, t));
         double px = ax + dx * t, pz = az + dz * t;
         double ox = x - px, oz = z - pz;
         double distanceSq = ox * ox + oz * oz;
         if (distanceSq < bestDistanceSq) {
            bestDistanceSq = distanceSq;
            double length = Math.sqrt(lengthSq);
            double signed = (dx * oz - dz * ox) / length;
            best = new Nearest(px, pz, dx, dz, Math.sqrt(distanceSq), signed);
         }
      }
      return best;
   }

   private static final class Nearest {
      final double x, z, dx, dz, distance, signedDistance;
      Nearest(double x, double z, double dx, double dz, double distance, double signedDistance) {
         this.x=x; this.z=z; this.dx=dx; this.dz=dz;
         this.distance=distance; this.signedDistance=signedDistance;
      }
   }
}
