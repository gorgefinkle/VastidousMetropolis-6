package ch.k42.metropolis.commands;

import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** User-facing names for every separately generated Metropolis wilderness biome. */
public final class MetropolisBiomeTargets {
   private static final Map<String, WildBiome> LOOKUP;
   private static final List<String> COMMAND_NAMES;

   static {
      Map<String, WildBiome> lookup = new LinkedHashMap<>();
      List<String> names = new ArrayList<>();
      for (WildBiome biome : WildBiome.ALL) {
         // Wetlands is retired in ForestGenerator: its configured share is
         // deliberately moved to ordinary forest before the picker is built.
         if (biome == WildBiome.WETLANDS) continue;
         String commandName = commandName(biome);
         names.add(commandName);
         lookup.put(normalize(commandName), biome);
         lookup.put(normalize(biome.key()), biome);
         lookup.put(normalize(biome.name()), biome);
      }
      // Public-facing alias; the internal enum/config key remains karstlands
      // for compatibility with existing world configuration and terrain code.
      lookup.put("karst", WildBiome.VOLCANIC_BARRENS);
      lookup.put("wetlands", WildBiome.WETLANDS); // recognized only to explain its retired status
      LOOKUP = Collections.unmodifiableMap(lookup);
      COMMAND_NAMES = Collections.unmodifiableList(names);
   }

   private MetropolisBiomeTargets() {}

   public static WildBiome resolve(String input) {
      if (input == null) return null;
      return LOOKUP.get(normalize(input));
   }

   public static List<String> commandNames() {
      return COMMAND_NAMES;
   }

   public static String commandName(WildBiome biome) {
      if (biome == WildBiome.VOLCANIC_BARRENS) return "karst";
      return normalize(biome.key());
   }

   public static String displayName(WildBiome biome) {
      return switch (biome) {
         case DRY_FOREST -> "Dry Forest";
         case OLD_GROWTH -> "Old Growth";
         case COASTAL_DUNES -> "Coastal Dunes";
         case VOLCANIC_BARRENS -> "Karst";
         case WETLANDS -> "Wetlands";
         default -> commandName(biome).substring(0, 1).toUpperCase(Locale.ROOT)
                 + commandName(biome).substring(1);
      };
   }

   private static String normalize(String input) {
      return input.trim().toLowerCase(Locale.ROOT).replace("-", "")
              .replace("_", "").replace(" ", "");
   }
}
