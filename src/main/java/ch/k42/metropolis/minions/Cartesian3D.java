package ch.k42.metropolis.minions;

import com.sk89q.worldedit.math.BlockVector3;

public final class Cartesian3D {
   public final int X;
   public final int Y;
   public final int Z;

   public Cartesian3D(int x, int y, int z) {
      this.X = x;
      this.Y = y;
      this.Z = z;
   }

   public final Cartesian3D add(Cartesian3D c) {
      return new Cartesian3D(this.X + c.X, this.Y + c.Y, this.Z + c.Z);
   }

   public final Cartesian2D get2D() {
      return new Cartesian2D(this.X, this.Z);
   }

   public static final Cartesian3D fromVector(BlockVector3 v) {
      return new Cartesian3D(v.x(), v.y(), v.z());
   }
}
