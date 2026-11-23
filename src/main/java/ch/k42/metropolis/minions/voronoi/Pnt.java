package ch.k42.metropolis.minions.voronoi;

public class Pnt {
   private double[] coordinates;

   public Pnt(double... coords) {
      this.coordinates = new double[coords.length];
      System.arraycopy(coords, 0, this.coordinates, 0, coords.length);
   }

   @Override
   public String toString() {
      if (this.coordinates.length == 0) {
         return "Pnt()";
      }

      String result = "Pnt(" + this.coordinates[0];

      for (int i = 1; i < this.coordinates.length; i++) {
         result = result + "," + this.coordinates[i];
      }

      return result + ")";
   }

   @Override
   public boolean equals(Object other) {
      if (!(other instanceof Pnt)) {
         return false;
      }

      Pnt p = (Pnt)other;
      if (this.coordinates.length != p.coordinates.length) {
         return false;
      }

      for (int i = 0; i < this.coordinates.length; i++) {
         if (this.coordinates[i] != p.coordinates[i]) {
            return false;
         }
      }

      return true;
   }

   @Override
   public int hashCode() {
      int hash = 0;

      for (double c : this.coordinates) {
         long bits = Double.doubleToLongBits(c);
         hash = 31 * hash ^ (int)(bits ^ bits >> 32);
      }

      return hash;
   }

   public double coord(int i) {
      return this.coordinates[i];
   }

   public int dimension() {
      return this.coordinates.length;
   }

   public int dimCheck(Pnt p) {
      int len = this.coordinates.length;
      if (len != p.coordinates.length) {
         throw new IllegalArgumentException("Dimension mismatch");
      } else {
         return len;
      }
   }

   public Pnt extend(double... coords) {
      double[] result = new double[this.coordinates.length + coords.length];
      System.arraycopy(this.coordinates, 0, result, 0, this.coordinates.length);
      System.arraycopy(coords, 0, result, this.coordinates.length, coords.length);
      return new Pnt(result);
   }

   public double dot(Pnt p) {
      int len = this.dimCheck(p);
      double sum = 0.0;

      for (int i = 0; i < len; i++) {
         sum += this.coordinates[i] * p.coordinates[i];
      }

      return sum;
   }

   public double magnitude() {
      return Math.sqrt(this.dot(this));
   }

   public Pnt subtract(Pnt p) {
      int len = this.dimCheck(p);
      double[] coords = new double[len];

      for (int i = 0; i < len; i++) {
         coords[i] = this.coordinates[i] - p.coordinates[i];
      }

      return new Pnt(coords);
   }

   public Pnt add(Pnt p) {
      int len = this.dimCheck(p);
      double[] coords = new double[len];

      for (int i = 0; i < len; i++) {
         coords[i] = this.coordinates[i] + p.coordinates[i];
      }

      return new Pnt(coords);
   }

   public double angle(Pnt p) {
      return Math.acos(this.dot(p) / (this.magnitude() * p.magnitude()));
   }

   public Pnt bisector(Pnt point) {
      this.dimCheck(point);
      Pnt diff = this.subtract(point);
      Pnt sum = this.add(point);
      double dot = diff.dot(sum);
      return diff.extend(-dot / 2.0);
   }

   public static String toString(Pnt[] matrix) {
      StringBuilder buf = new StringBuilder("{");

      for (Pnt row : matrix) {
         buf.append(" " + row);
      }

      buf.append(" }");
      return buf.toString();
   }

   public static double determinant(Pnt[] matrix) {
      if (matrix.length != matrix[0].dimension()) {
         throw new IllegalArgumentException("Matrix is not square");
      }

      boolean[] columns = new boolean[matrix.length];

      for (int i = 0; i < matrix.length; i++) {
         columns[i] = true;
      }

      try {
         return determinant(matrix, 0, columns);
      } catch (ArrayIndexOutOfBoundsException e) {
         throw new IllegalArgumentException("Matrix is wrong shape");
      }
   }

   private static double determinant(Pnt[] matrix, int row, boolean[] columns) {
      if (row == matrix.length) {
         return 1.0;
      }

      double sum = 0.0;
      int sign = 1;

      for (int col = 0; col < columns.length; col++) {
         if (columns[col]) {
            columns[col] = false;
            sum += sign * matrix[row].coordinates[col] * determinant(matrix, row + 1, columns);
            columns[col] = true;
            sign = -sign;
         }
      }

      return sum;
   }

   public static Pnt cross(Pnt[] matrix) {
      int len = matrix.length + 1;
      if (len != matrix[0].dimension()) {
         throw new IllegalArgumentException("Dimension mismatch");
      }

      boolean[] columns = new boolean[len];

      for (int i = 0; i < len; i++) {
         columns[i] = true;
      }

      double[] result = new double[len];
      int sign = 1;

      try {
         for (int i = 0; i < len; i++) {
            columns[i] = false;
            result[i] = sign * determinant(matrix, 0, columns);
            columns[i] = true;
            sign = -sign;
         }
      } catch (ArrayIndexOutOfBoundsException e) {
         throw new IllegalArgumentException("Matrix is wrong shape");
      }

      return new Pnt(result);
   }

   public static double content(Pnt[] simplex) {
      Pnt[] matrix = new Pnt[simplex.length];

      for (int i = 0; i < matrix.length; i++) {
         matrix[i] = simplex[i].extend(1.0);
      }

      int fact = 1;

      for (int i = 1; i < matrix.length; i++) {
         fact *= i;
      }

      return determinant(matrix) / fact;
   }

   public int[] relation(Pnt[] simplex) {
      int dim = simplex.length - 1;
      if (this.dimension() != dim) {
         throw new IllegalArgumentException("Dimension mismatch");
      }

      Pnt[] matrix = new Pnt[dim + 1];
      double[] coords = new double[dim + 2];

      for (int j = 0; j < coords.length; j++) {
         coords[j] = 1.0;
      }

      matrix[0] = new Pnt(coords);

      for (int i = 0; i < dim; i++) {
         coords[0] = this.coordinates[i];

         for (int j = 0; j < simplex.length; j++) {
            coords[j + 1] = simplex[j].coordinates[i];
         }

         matrix[i + 1] = new Pnt(coords);
      }

      Pnt vector = cross(matrix);
      double content = vector.coordinates[0];
      int[] result = new int[dim + 1];

      for (int i = 0; i < result.length; i++) {
         double value = vector.coordinates[i + 1];
         if (Math.abs(value) <= 1.0E-6 * Math.abs(content)) {
            result[i] = 0;
         } else if (value < 0.0) {
            result[i] = -1;
         } else {
            result[i] = 1;
         }
      }

      if (content < 0.0) {
         for (int i = 0; i < result.length; i++) {
            result[i] = -result[i];
         }
      }

      if (content == 0.0) {
         for (int i = 0; i < result.length; i++) {
            result[i] = Math.abs(result[i]);
         }
      }

      return result;
   }

   public Pnt isOutside(Pnt[] simplex) {
      int[] result = this.relation(simplex);

      for (int i = 0; i < result.length; i++) {
         if (result[i] > 0) {
            return simplex[i];
         }
      }

      return null;
   }

   public Pnt isOn(Pnt[] simplex) {
      int[] result = this.relation(simplex);
      Pnt witness = null;

      for (int i = 0; i < result.length; i++) {
         if (result[i] == 0) {
            witness = simplex[i];
         } else if (result[i] > 0) {
            return null;
         }
      }

      return witness;
   }

   public boolean isInside(Pnt[] simplex) {
      int[] result = this.relation(simplex);

      for (int r : result) {
         if (r >= 0) {
            return false;
         }
      }

      return true;
   }

   public int vsCircumcircle(Pnt[] simplex) {
      Pnt[] matrix = new Pnt[simplex.length + 1];

      for (int i = 0; i < simplex.length; i++) {
         matrix[i] = simplex[i].extend(1.0, simplex[i].dot(simplex[i]));
      }

      matrix[simplex.length] = this.extend(1.0, this.dot(this));
      double d = determinant(matrix);
      int result = d < 0.0 ? -1 : (d > 0.0 ? 1 : 0);
      if (content(simplex) < 0.0) {
         result = -result;
      }

      return result;
   }

   public static Pnt circumcenter(Pnt[] simplex) {
      int dim = simplex[0].dimension();
      if (simplex.length - 1 != dim) {
         throw new IllegalArgumentException("Dimension mismatch");
      }

      Pnt[] matrix = new Pnt[dim];

      for (int i = 0; i < dim; i++) {
         matrix[i] = simplex[i].bisector(simplex[i + 1]);
      }

      Pnt hCenter = cross(matrix);
      double last = hCenter.coordinates[dim];
      double[] result = new double[dim];

      for (int i = 0; i < dim; i++) {
         result[i] = hCenter.coordinates[i] / last;
      }

      return new Pnt(result);
   }

   public static void main(String[] args) {
      Pnt p = new Pnt(1.0, 2.0, 3.0);
      System.out.println("Pnt created: " + p);
      Pnt[] matrix1 = new Pnt[]{new Pnt(1.0, 2.0), new Pnt(3.0, 4.0)};
      Pnt[] matrix2 = new Pnt[]{new Pnt(7.0, 0.0, 5.0), new Pnt(2.0, 4.0, 6.0), new Pnt(3.0, 8.0, 1.0)};
      System.out.print("Results should be -2 and -288: ");
      System.out.println(determinant(matrix1) + " " + determinant(matrix2));
      Pnt p1 = new Pnt(1.0, 1.0);
      Pnt p2 = new Pnt(-1.0, 1.0);
      System.out.println("Angle between " + p1 + " and " + p2 + ": " + p1.angle(p2));
      System.out.println(p1 + " subtract " + p2 + ": " + p1.subtract(p2));
      Pnt v0 = new Pnt(0.0, 0.0);
      Pnt v1 = new Pnt(1.0, 1.0);
      Pnt v2 = new Pnt(2.0, 2.0);
      Pnt[] vs = new Pnt[]{v0, new Pnt(0.0, 1.0), new Pnt(1.0, 0.0)};
      Pnt vp = new Pnt(0.1, 0.1);
      System.out.println(vp + " isInside " + toString(vs) + ": " + vp.isInside(vs));
      System.out.println(v1 + " isInside " + toString(vs) + ": " + v1.isInside(vs));
      System.out.println(vp + " vsCircumcircle " + toString(vs) + ": " + vp.vsCircumcircle(vs));
      System.out.println(v1 + " vsCircumcircle " + toString(vs) + ": " + v1.vsCircumcircle(vs));
      System.out.println(v2 + " vsCircumcircle " + toString(vs) + ": " + v2.vsCircumcircle(vs));
      System.out.println("Circumcenter of " + toString(vs) + " is " + circumcenter(vs));
   }
}
