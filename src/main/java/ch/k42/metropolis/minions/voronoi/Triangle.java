package ch.k42.metropolis.minions.voronoi;

import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class Triangle extends ArraySet<Pnt> {
   private int idNumber;
   private Pnt circumcenter = null;
   private static int idGenerator = 0;
   public static boolean moreInfo = false;

   public Triangle(Pnt... vertices) {
      this(Arrays.asList(vertices));
   }

   public Triangle(Collection<? extends Pnt> collection) {
      super(collection);
      this.idNumber = idGenerator++;
      if (this.size() != 3) {
         throw new IllegalArgumentException("Triangle must have 3 vertices");
      }
   }

   @Override
   public String toString() {
      return !moreInfo ? "Triangle" + this.idNumber : "Triangle" + this.idNumber + super.toString();
   }

   public Pnt getVertexButNot(Pnt... badVertices) {
      Collection<Pnt> bad = Arrays.asList(badVertices);

      for (Pnt v : this) {
         if (!bad.contains(v)) {
            return v;
         }
      }

      throw new NoSuchElementException("No vertex found");
   }

   public boolean isNeighbor(Triangle triangle) {
      int count = 0;

      for (Pnt vertex : this) {
         if (!triangle.contains(vertex)) {
            count++;
         }
      }

      return count == 1;
   }

   public ArraySet<Pnt> facetOpposite(Pnt vertex) {
      ArraySet<Pnt> facet = new ArraySet<>(this);
      if (!facet.remove(vertex)) {
         throw new IllegalArgumentException("Vertex not in triangle");
      } else {
         return facet;
      }
   }

   public Pnt getCircumcenter() {
      if (this.circumcenter == null) {
         this.circumcenter = Pnt.circumcenter(this.toArray(new Pnt[0]));
      }

      return this.circumcenter;
   }

   public boolean add(Pnt vertex) {
      throw new UnsupportedOperationException();
   }

   @Override
   public Iterator<Pnt> iterator() {
      return new Iterator<Pnt>() {
         private Iterator<Pnt> it = Triangle.super.iterator();

         @Override
         public boolean hasNext() {
            return this.it.hasNext();
         }

         public Pnt next() {
            return this.it.next();
         }

         @Override
         public void remove() {
            throw new UnsupportedOperationException();
         }
      };
   }

   @Override
   public int hashCode() {
      return this.idNumber ^ this.idNumber >>> 32;
   }

   @Override
   public boolean equals(Object o) {
      return this == o;
   }
}
