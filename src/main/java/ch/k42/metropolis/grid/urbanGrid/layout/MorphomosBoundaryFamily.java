package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

/**
 * Boundary adjacency policy for Morphomos roads.
 *
 * This class intentionally does not decide terrain ownership or region
 * membership. FOREST, RIVER and OCEAN share natural-boundary behavior, while
 * FARM remains its own family. WorldLayout ZONE/NATURAL cells are mapped to
 * NATURAL by PlanRegions before these predicates are used.
 */
public final class MorphomosBoundaryFamily {
   public static final int NONE = 0;
   public static final int FARM = 1;
   public static final int NATURAL = 2;

   private MorphomosBoundaryFamily() { }

   public static int of(ContextType context) {
      if (context == ContextType.FARM) return FARM;
      if (context != null && context.isNaturalBoundaryFamily()) return NATURAL;
      return NONE;
   }

   /** A non-empty neighbor can join the current family only when equal. */
   public static boolean compatible(int current, int neighbor) {
      return neighbor != NONE && (current == NONE || current == neighbor);
   }

   /**
    * Whether the local frame qualifies for a dissolved street. FARM requires
    * two distinct FARM blocks. Natural blocks can merge with each other, or
    * join a natural ZONE/NATURAL exterior; neither case creates a new region
    * or changes which painter owns the terrain.
    */
   public static boolean shouldDissolve(int family, int distinctBlocks, boolean naturalExterior) {
      if (family == FARM) return distinctBlocks >= 2;
      if (family == NATURAL) return distinctBlocks >= 2 || naturalExterior;
      return false;
   }
}
