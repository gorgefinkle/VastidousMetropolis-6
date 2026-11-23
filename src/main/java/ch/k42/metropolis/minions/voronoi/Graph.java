package ch.k42.metropolis.minions.voronoi;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class Graph<N> {
   private Map<N, Set<N>> theNeighbors = new HashMap<>();
   private Set<N> theNodeSet = Collections.unmodifiableSet(this.theNeighbors.keySet());

   public void add(N node) {
      if (!this.theNeighbors.containsKey(node)) {
         this.theNeighbors.put(node, new ArraySet<>());
      }
   }

   public void add(N nodeA, N nodeB) throws NullPointerException {
      this.theNeighbors.get(nodeA).add(nodeB);
      this.theNeighbors.get(nodeB).add(nodeA);
   }

   public void remove(N node) {
      if (this.theNeighbors.containsKey(node)) {
         for (N neighbor : this.theNeighbors.get(node)) {
            this.theNeighbors.get(neighbor).remove(node);
         }

         this.theNeighbors.get(node).clear();
         this.theNeighbors.remove(node);
      }
   }

   public void remove(N nodeA, N nodeB) throws NullPointerException {
      this.theNeighbors.get(nodeA).remove(nodeB);
      this.theNeighbors.get(nodeB).remove(nodeA);
   }

   public Set<N> neighbors(N node) throws NullPointerException {
      return Collections.unmodifiableSet(this.theNeighbors.get(node));
   }

   public Set<N> nodeSet() {
      return this.theNodeSet;
   }
}
