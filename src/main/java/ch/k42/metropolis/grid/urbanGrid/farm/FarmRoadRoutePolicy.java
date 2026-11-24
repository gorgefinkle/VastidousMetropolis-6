package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.Set;

/** Pure interval rules for a requested farm-site-to-street access route. */
final class FarmRoadRoutePolicy {
   private FarmRoadRoutePolicy() {
   }

   /**
    * Choose the closest reachable paved-road endpoint. Ties are resolved
    * toward the lower along-coordinate, so adjacent chunk requests agree.
    */
   static int nearestStreet(int siteGate, Iterable<Integer> streetEnds, int maximumDistance) {
      int nearest = Integer.MIN_VALUE;
      int distance = Integer.MAX_VALUE;
      for (int street : streetEnds) {
         int candidate = Math.abs(siteGate - street);
         if (candidate <= maximumDistance
                 && (candidate < distance || (candidate == distance && street < nearest))) {
            nearest = street;
            distance = candidate;
         }
      }
      return nearest;
   }

   /** True only on the minimal inclusive interval joining one gate to one street. */
   static boolean contains(int query, int siteGate, int streetEnd) {
      return query >= Math.min(siteGate, streetEnd)
              && query <= Math.max(siteGate, streetEnd);
   }

   /**
    * Evaluates the actual access demand on one contiguous farm boundary run.
    * A road block exists only if the queried chunk lies between a recorded
    * site gate and a reachable paved-street endpoint; an empty line or a gate
    * outside this connected run cannot create a road.
    */
   static boolean requestedRouteContains(int query, Set<Integer> farmRun,
                                          Set<Integer> siteGates, Set<Integer> streetEnds,
                                          int maximumDistance) {
      if (!farmRun.contains(query) || siteGates.isEmpty() || streetEnds.isEmpty()) return false;
      for (int gate : siteGates) {
         if (!farmRun.contains(gate)) continue;
         int street = nearestStreet(gate, streetEnds, maximumDistance);
         if (street != Integer.MIN_VALUE && contains(query, gate, street)) return true;
      }
      return false;
   }

}
