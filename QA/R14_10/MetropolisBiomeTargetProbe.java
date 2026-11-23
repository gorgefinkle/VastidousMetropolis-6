import ch.k42.metropolis.commands.MetropolisBiomeTargets;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;

import java.util.HashSet;
import java.util.Set;

public final class MetropolisBiomeTargetProbe {
   public static void main(String[] args) {
      Set<WildBiome> exposed = new HashSet<>();
      for (String key : MetropolisBiomeTargets.commandNames()) {
         WildBiome biome = MetropolisBiomeTargets.resolve(key);
         require(biome != null, "command key did not resolve: " + key);
         require(biome != WildBiome.WETLANDS, "retired wetlands must not be advertised");
         require(exposed.add(biome), "duplicate command mapping for " + biome);
      }
      for (WildBiome biome : WildBiome.ALL) {
         if (biome == WildBiome.WETLANDS) continue;
         require(exposed.contains(biome), "missing generated biome: " + biome);
         require(MetropolisBiomeTargets.resolve(MetropolisBiomeTargets.commandName(biome)) == biome,
                 "canonical command does not round-trip: " + biome);
      }
      require(MetropolisBiomeTargets.resolve("karst") == WildBiome.VOLCANIC_BARRENS,
              "karst user-facing alias must map to the existing karst field");
      require(MetropolisBiomeTargets.resolve("karstlands") == WildBiome.VOLCANIC_BARRENS,
              "legacy config key must remain accepted");
      require(MetropolisBiomeTargets.resolve("dry-forest") == WildBiome.DRY_FOREST,
              "hyphenated biome names must resolve");
      require(MetropolisBiomeTargets.resolve("old-growth") == WildBiome.OLD_GROWTH,
              "hyphenated biome names must resolve");
      require(MetropolisBiomeTargets.resolve("coastal-dunes") == WildBiome.COASTAL_DUNES,
              "hyphenated biome names must resolve");
      require(MetropolisBiomeTargets.resolve("wetlands") == WildBiome.WETLANDS,
              "retired wetlands name must be recognized for its explicit explanation");
      require(!MetropolisBiomeTargets.commandNames().contains("wetlands"),
              "retired wetlands must not appear in tab completion");
      require(MetropolisBiomeTargets.resolve("not-a-biome") == null,
              "unknown names must be rejected");
      System.out.println("PASS: " + exposed.size() + " active Metropolis wilderness biomes exposed; aliases and retired wetlands behavior verified");
   }

   private static void require(boolean condition, String message) {
      if (!condition) throw new AssertionError(message);
   }
}
