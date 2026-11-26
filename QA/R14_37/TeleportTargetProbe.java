import ch.k42.metropolis.commands.MetropolisBiomeTargets;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;

import java.util.HashSet;
import java.util.Set;

public final class TeleportTargetProbe {
   public static void main(String[] args) {
      Set<WildBiome> exposed = new HashSet<>();
      for (String name : MetropolisBiomeTargets.commandNames()) {
         WildBiome biome = MetropolisBiomeTargets.resolve(name);
         require(biome != null, "unresolvable biome target: " + name);
         require(exposed.add(biome), "duplicate biome target: " + biome);
      }
      require(exposed.contains(WildBiome.MOUNTAINS),
              "large mountain biome target is missing from /metrobiome tab completion");
      require(MetropolisBiomeTargets.resolve("mountains") == WildBiome.MOUNTAINS,
              "mountains command name must resolve");
      require(MetropolisBiomeTargets.resolve("mountain") == null
                      || MetropolisBiomeTargets.resolve("mountain") == WildBiome.MOUNTAINS,
              "mountain singular alias must not resolve to an unrelated biome");
      require(MetropolisBiomeTargets.resolve("wetlands") == WildBiome.WETLANDS,
              "retired wetlands name should retain its explanatory response");
      System.out.println("PASS: " + exposed.size()
              + " biome teleport targets exposed, including the large mountain biome.");
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
