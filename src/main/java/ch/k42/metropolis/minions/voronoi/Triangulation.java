package ch.k42.metropolis.minions.voronoi;

import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class Triangulation extends AbstractSet<Triangle> {
   private Triangle mostRecent = null;
   private Graph<Triangle> triGraph = new Graph<>();

   public Triangulation(Triangle triangle) {
      this.triGraph.add(triangle);
      this.mostRecent = triangle;
   }

   @Override
   public Iterator<Triangle> iterator() {
      return this.triGraph.nodeSet().iterator();
   }

   @Override
   public int size() {
      return this.triGraph.nodeSet().size();
   }

   @Override
   public String toString() {
      return "Triangulation with " + this.size() + " triangles";
   }

   @Override
   public boolean contains(Object triangle) {
      return this.triGraph.nodeSet().contains(triangle);
   }

   public Triangle neighborOpposite(Pnt site, Triangle triangle) {
      if (!triangle.contains(site)) {
         throw new IllegalArgumentException("Bad vertex; not in triangle");
      }

      for (Triangle neighbor : this.triGraph.neighbors(triangle)) {
         if (!neighbor.contains(site)) {
            return neighbor;
         }
      }

      return null;
   }

   public Set<Triangle> neighbors(Triangle triangle) {
      return this.triGraph.neighbors(triangle);
   }

   public List<Triangle> surroundingTriangles(Pnt site, Triangle triangle) {
      if (!triangle.contains(site)) {
         throw new IllegalArgumentException("Site not in triangle");
      }

      List<Triangle> list = new ArrayList<>();
      Triangle start = triangle;
      Pnt guide = triangle.getVertexButNot(site);

      do {
         list.add(triangle);
         Triangle previous = triangle;
         triangle = this.neighborOpposite(guide, triangle);
         guide = previous.getVertexButNot(site, guide);
      } while (triangle != start);

      return list;
   }

   public Triangle locate(Pnt point) {
      Triangle triangle = this.mostRecent;
      if (!this.contains(triangle)) {
         triangle = null;
      }

      Set<Triangle> visited = new HashSet<>();

      while (triangle != null) {
         if (visited.contains(triangle)) {
            System.out.println("Warning: Caught in a locate loop");
            break;
         }

         visited.add(triangle);
         Pnt corner = point.isOutside(triangle.toArray(new Pnt[0]));
         if (corner == null) {
            return triangle;
         }

         triangle = this.neighborOpposite(corner, triangle);
      }

      System.out.println("Warning: Checking all triangles for " + point);

      for (Triangle tri : this) {
         if (point.isOutside(tri.toArray(new Pnt[0])) == null) {
            return tri;
         }
      }

      System.out.println("Warning: No triangle holds " + point);
      return null;
   }

   public void delaunayPlace(Pnt site) {
      Triangle triangle = this.locate(site);
      if (triangle == null) {
         throw new IllegalArgumentException("No containing triangle");
      }

      if (!triangle.contains(site)) {
         Set<Triangle> cavity = this.getCavity(site, triangle);
         this.mostRecent = this.update(site, cavity);
      }
   }

   private Set<Triangle> getCavity(Pnt site, Triangle triangle) {
      Set<Triangle> encroached = new HashSet<>();
      Queue<Triangle> toBeChecked = new LinkedList<>();
      Set<Triangle> marked = new HashSet<>();
      toBeChecked.add(triangle);
      marked.add(triangle);

      while (!toBeChecked.isEmpty()) {
         triangle = toBeChecked.remove();
         if (site.vsCircumcircle(triangle.toArray(new Pnt[0])) != 1) {
            encroached.add(triangle);

            for (Triangle neighbor : this.triGraph.neighbors(triangle)) {
               if (!marked.contains(neighbor)) {
                  marked.add(neighbor);
                  toBeChecked.add(neighbor);
               }
            }
         }
      }

      return encroached;
   }

   private Triangle update(Pnt site, Set<Triangle> cavity) {
      Set<Set<Pnt>> boundary = new HashSet<>();
      Set<Triangle> theTriangles = new HashSet<>();

      for (Triangle triangle : cavity) {
         theTriangles.addAll(this.neighbors(triangle));

         for (Pnt vertex : triangle) {
            Set<Pnt> facet = triangle.facetOpposite(vertex);
            if (boundary.contains(facet)) {
               boundary.remove(facet);
            } else {
               boundary.add(facet);
            }
         }
      }

      theTriangles.removeAll(cavity);

      for (Triangle triangle : cavity) {
         this.triGraph.remove(triangle);
      }

      Set<Triangle> newTriangles = new HashSet<>();

      for (Set<Pnt> vertices : boundary) {
         vertices.add(site);
         Triangle tri = new Triangle(vertices);
         this.triGraph.add(tri);
         newTriangles.add(tri);
      }

      theTriangles.addAll(newTriangles);

      for (Triangle triangle : newTriangles) {
         for (Triangle other : theTriangles) {
            if (triangle.isNeighbor(other)) {
               this.triGraph.add(triangle, other);
            }
         }
      }

      return newTriangles.iterator().next();
   }

   public static void main(String[] args) {
      Triangle tri = new Triangle(new Pnt(-10.0, 10.0), new Pnt(10.0, 10.0), new Pnt(0.0, -10.0));
      System.out.println("Triangle created: " + tri);
      Triangulation dt = new Triangulation(tri);
      System.out.println("DelaunayTriangulation created: " + dt);
      dt.delaunayPlace(new Pnt(0.0, 0.0));
      dt.delaunayPlace(new Pnt(1.0, 0.0));
      dt.delaunayPlace(new Pnt(0.0, 1.0));
      System.out.println("After adding 3 points, we have a " + dt);
      Triangle.moreInfo = true;
      System.out.println("Triangles: " + dt.triGraph.nodeSet());
   }
}
