package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.Arrays;

/** Regression checks for demand-only access intervals and shared-spine trimming. */
public final class FarmRoadRoutePolicyProbe {
   public static void main(String[] args) {
      int street = FarmRoadRoutePolicy.nearestStreet(10, Arrays.asList(-2, 22, 80), 20);
      check(street == -2, "equal-distance tie must resolve to the lower street endpoint");

      street = FarmRoadRoutePolicy.nearestStreet(-30, Arrays.asList(-100, -5, 60), 26);
      check(street == -5, "negative-coordinate nearest endpoint");

      street = FarmRoadRoutePolicy.nearestStreet(0, Arrays.asList(70, -90), 66);
      check(street == Integer.MIN_VALUE, "out-of-range streets must not create a route");

      check(FarmRoadRoutePolicy.contains(10, 10, 22), "site gate is part of the route");
      check(FarmRoadRoutePolicy.contains(16, 10, 22), "interior spine chunk is part of the route");
      check(FarmRoadRoutePolicy.contains(22, 10, 22), "paved-road endpoint is part of the route");
      check(!FarmRoadRoutePolicy.contains(9, 10, 22), "road must not extend behind the site gate");
      check(!FarmRoadRoutePolicy.contains(23, 10, 22), "road must not continue beyond the street");

      // Two nearby sites sharing one spine produce overlapping, not full-line,
      // segments. The union is continuous only between the outer site and road.
      boolean[] union = new boolean[30];
      for (int i = 8; i <= 22; i++) union[i] |= FarmRoadRoutePolicy.contains(i, 8, 22);
      for (int i = 14; i <= 22; i++) union[i] |= FarmRoadRoutePolicy.contains(i, 14, 22);
      for (int i = 8; i <= 22; i++) check(union[i], "shared trunk gap at " + i);
      check(!union[7] && !union[23], "shared spine must not leave an unused tail");

      java.util.Set<Integer> run = new java.util.HashSet<Integer>();
      for (int i = 0; i <= 8; i++) run.add(i);
      java.util.Set<Integer> gates = new java.util.HashSet<Integer>(Arrays.asList(1, 3));
      java.util.Set<Integer> streets = new java.util.HashSet<Integer>(Arrays.asList(8));
      check(FarmRoadRoutePolicy.requestedRouteContains(2, run, gates, streets, 66),
              "a requested site-to-street connection must paint its interval");
      check(FarmRoadRoutePolicy.requestedRouteContains(7, run, gates, streets, 66),
              "shared access trunk must stay connected to the paved street");
      check(!FarmRoadRoutePolicy.requestedRouteContains(0, run, gates, streets, 66),
              "unused farm-run tail before the first site must remain unpainted");
      check(!FarmRoadRoutePolicy.requestedRouteContains(4, run, java.util.Collections.<Integer>emptySet(), streets, 66),
              "a boundary with no requesting farm site must remain unpainted");
      check(!FarmRoadRoutePolicy.requestedRouteContains(4, run, gates, java.util.Collections.<Integer>emptySet(), 66),
              "without a paved street there must be no dirt road");
      java.util.Set<Integer> outsideGate = new java.util.HashSet<Integer>(Arrays.asList(-2));
      check(!FarmRoadRoutePolicy.requestedRouteContains(1, run, outsideGate, streets, 66),
              "a site outside the connected farm run must not request a road");

      System.out.println("FarmRoadRoutePolicyProbe PASS: nearest street selection, negative coordinates, bounded access, shared trunks, no orphan sites or dead tails");
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
