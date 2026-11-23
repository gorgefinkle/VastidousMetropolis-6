package ch.k42.metropolis.grid.urbanGrid.wild;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;

/**
 * Bukkit biomes by logical name, resolved against whatever names this server version has: each biome lists its names newest
 * first and the first one that exists wins. Biomes are registry entries (not an enum) since 1.21, so they are looked up by
 * their namespaced key. EXTREME_HILLS is WINDSWEPT_HILLS, ICE_MOUNTAINS became SNOWY_SLOPES, and so on, so a server update does
 * not need a code change here, only (rarely) one more name in a list.
 */
public final class BiomeNames {
   private static final Map<String, Biome> CACHE = new HashMap<String, Biome>();

   private BiomeNames() {
   }

   /** The first of these names that is a Biome on this server, or fallback if none is. */
   public static synchronized Biome resolve(Biome fallback, String... names) {
      String key = String.join("|", names);
      Biome b = CACHE.get(key);
      if (b == null) {
         b = fallback;
         for (String n : names) {
            NamespacedKey id = NamespacedKey.minecraft(n.toLowerCase(Locale.ROOT));
            Biome found = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(id);
            if (found != null) {
               b = found;
               break;
            }
         }
         CACHE.put(key, b);
      }
      return b;
   }

   public static Biome prairie() { return resolve(Biome.PLAINS, "PLAINS", "SUNFLOWER_PLAINS"); }
   public static Biome wetlands() { return resolve(Biome.PLAINS, "SWAMP", "MANGROVE_SWAMP"); }
   public static Biome oldGrowth() { return resolve(Biome.FOREST, "OLD_GROWTH_PINE_TAIGA", "OLD_GROWTH_SPRUCE_TAIGA", "TAIGA"); }
   public static Biome deciduous() { return resolve(Biome.FOREST, "FOREST", "BIRCH_FOREST"); }
   public static Biome dunes() { return resolve(Biome.BEACH, "BEACH", "WINDSWEPT_SAVANNA"); }
   public static Biome volcanic() { return resolve(Biome.PLAINS, "STONY_PEAKS", "WINDSWEPT_GRAVELLY_HILLS"); }

   public static Biome mountains() {
      return resolve(Biome.PLAINS, "WINDSWEPT_HILLS", "MOUNTAINS", "EXTREME_HILLS");
   }

   public static Biome snowyMountains() {
      return resolve(Biome.PLAINS, "SNOWY_SLOPES", "SNOWY_MOUNTAINS", "ICE_MOUNTAINS");
   }

   /** Dry-forest transition belt; prefer wooded badlands / savanna where available. */
   public static Biome dryForest() {
      return resolve(Biome.PLAINS, "SAVANNA", "WOODED_BADLANDS", "SAVANNA_PLATEAU");
   }

   public static Biome desert() {
      return resolve(Biome.PLAINS, "DESERT");
   }

   public static Biome mesa() {
      return resolve(Biome.PLAINS, "BADLANDS", "MESA");
   }
}
