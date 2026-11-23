package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

/** Standalone deterministic policy probe; no Bukkit/Paper dependency. */
public final class MorphomosBoundaryFamilyProbe {
   private static void check(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }

   public static void main(String[] args) {
      int natural = MorphomosBoundaryFamily.NATURAL;
      int farm = MorphomosBoundaryFamily.FARM;
      check(MorphomosBoundaryFamily.of(ContextType.FOREST) == natural, "FOREST family");
      check(MorphomosBoundaryFamily.of(ContextType.RIVER) == natural, "RIVER family");
      check(MorphomosBoundaryFamily.of(ContextType.OCEAN) == natural, "OCEAN family");
      check(MorphomosBoundaryFamily.of(ContextType.FARM) == farm, "FARM family");
      check(MorphomosBoundaryFamily.of(ContextType.RESIDENTIAL) == MorphomosBoundaryFamily.NONE, "urban context family");

      ContextType[] naturalContexts = {ContextType.FOREST, ContextType.RIVER, ContextType.OCEAN};
      for (ContextType left : naturalContexts) for (ContextType right : naturalContexts) {
         check(MorphomosBoundaryFamily.compatible(MorphomosBoundaryFamily.of(left),
                 MorphomosBoundaryFamily.of(right)), left + "/" + right + " must be same natural family");
      }
      check(MorphomosBoundaryFamily.compatible(farm, farm), "FARM/FARM");
      check(!MorphomosBoundaryFamily.compatible(farm, natural), "FARM/natural must retain road");
      check(!MorphomosBoundaryFamily.compatible(natural, farm), "natural/FARM reciprocity");
      check(!MorphomosBoundaryFamily.compatible(natural, MorphomosBoundaryFamily.NONE), "unknown owner must not dissolve");

      check(MorphomosBoundaryFamily.shouldDissolve(farm, 2, false), "FARM/FARM dissolve");
      check(!MorphomosBoundaryFamily.shouldDissolve(farm, 1, true), "FARM/natural exterior retains road");
      check(MorphomosBoundaryFamily.shouldDissolve(natural, 2, false), "natural/natural blocks dissolve");
      check(MorphomosBoundaryFamily.shouldDissolve(natural, 1, true), "natural block + natural exterior dissolves");
      check(MorphomosBoundaryFamily.shouldDissolve(natural, 0, true), "natural exterior-to-exterior seam dissolves");
      check(!MorphomosBoundaryFamily.shouldDissolve(natural, 1, false), "single natural block without natural exterior");
      check(!MorphomosBoundaryFamily.shouldDissolve(natural, 0, false), "empty natural frame does not dissolve");
      check(!MorphomosBoundaryFamily.shouldDissolve(MorphomosBoundaryFamily.NONE, 8, true), "non-family never dissolves");
      System.out.println("MorphomosBoundaryFamilyProbe: PASS (natural matrix, farm isolation, reciprocity, exterior seams)");
   }
}
