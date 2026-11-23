package ch.k42.metropolis.minions;

public final class Cartesian2D {
   public final int X;
   public final int Y;

   public Cartesian2D(int x, int y) {
      this.X = x;
      this.Y = y;
   }

   public final Cartesian2D add(Cartesian2D c) {
      return new Cartesian2D(this.X + c.X, this.Y + c.Y);
   }

   public final int manhattanDistance(Cartesian2D c) {
      return Math.abs(c.X - this.X) + Math.abs(c.Y - this.Y);
   }

   @Override
   public String toString() {
      return "[" + this.X + "/" + this.Y + "]";
   }
}
