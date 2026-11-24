package ch.k42.metropolis.grid.urbanGrid.farm;

import java.util.Arrays;

/** Regression checks for demand-only access intervals and shared-spine trimming. */
public final class FarmRoadRoutePolicyProbe {
   public static void main(String[] args) {
      int street = FarmRoadRoutePolicy.nearestStreet(10, Arrays.asList(-20, 22, 80), 20);
      check(street == -20, "equal-distance tie must resolve to the lower street endpoint");

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

      System.out.println("FarmRoadRoutePolicyProbe PASS: nearest street selection, negative coordinates, bounded access, shared trunk, no dead tails");
   }

   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
