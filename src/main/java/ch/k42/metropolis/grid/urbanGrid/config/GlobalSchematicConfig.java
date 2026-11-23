package ch.k42.metropolis.grid.urbanGrid.config;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.LootType;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.minions.Nimmersatt;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.bukkit.Bukkit;
import org.bukkit.entity.EntityType;

public class GlobalSchematicConfig extends AbstractSchematicConfig {
   private LootType[] supportedLootTypes = LootType.values();
   private ContextType[] supportedContextTypes = ContextType.values();
   private EntityType[] supportedSpawnerEntities = EntityType.values();
   private RoadType[] supportedRoadTypes = RoadType.values();
   private int[] chestLevelWeights = new int[]{20, 20, 20, 20, 20};
   private AbstractSchematicConfig.SchematicSpawner[] defaultSpawners = new AbstractSchematicConfig.SchematicSpawner[]{
           new AbstractSchematicConfig.SchematicSpawner(EntityType.ZOMBIE, 60),
           new AbstractSchematicConfig.SchematicSpawner(EntityType.CREEPER, 20),
           new AbstractSchematicConfig.SchematicSpawner(EntityType.SKELETON, 20)
   };
   private transient int chestThreshold2 = -1;
   private transient int chestThreshold3 = -1;
   private transient int chestThreshold4 = -1;
   private transient int cachedChestLevelSum = -1;
   private transient boolean cachedChest = false;

   public GlobalSchematicConfig() {
      this.Spawners = this.defaultSpawners;
   }

   public int getChestLevelWeight(int i) {
      return i >= 0 && i <= this.chestLevelWeights.length - 1 ? this.chestLevelWeights[i] : 20;
   }

   private void cacheChestThresholds() {
      this.chestThreshold2 = this.chestLevelWeights[0] + this.chestLevelWeights[1];
      this.chestThreshold3 = this.chestThreshold2 + this.chestLevelWeights[2];
      this.chestThreshold4 = this.chestThreshold3 + this.chestLevelWeights[3];
      this.cachedChestLevelSum = this.chestThreshold4 + this.chestLevelWeights[4];
      this.cachedChest = true;
   }

   private int getChestLevelWeightSum() {
      if (!this.cachedChest) {
         this.cacheChestThresholds();
      }

      return this.cachedChestLevelSum;
   }

   public int getRandomChestLevel(GridRandom rand) {
      int random = rand.getRandomInt(this.getChestLevelWeightSum());
      return this.getLevel(random);
   }

   private int getLevel(int random) {
      if (!this.cachedChest) {
         this.cacheChestThresholds();
      }

      if (random < this.chestLevelWeights[0]) {
         return 1;
      } else if (random < this.chestThreshold2) {
         return 2;
      } else if (random < this.chestThreshold3) {
         return 3;
      } else {
         return random < this.chestThreshold4 ? 4 : 5;
      }
   }

   public int getRandomChestLevel(GridRandom rand, int min, int max) {
      if (min < 1 || min > 5 || max < 1 || max > 5) {
         Minions.w("Invalid config option for a schematic, minChestLevel or maxChestLevel are out of range! Valid: [1,5]");
         return 1;
      }

      if (min >= max) {
         return min;
      }

      if (!this.cachedChest) {
         this.cacheChestThresholds();
      }

      int minthreshold;
      switch (min) {
         case 1:
            minthreshold = this.chestLevelWeights[0];
            break;
         case 2:
            minthreshold = this.chestThreshold2;
            break;
         case 3:
            minthreshold = this.chestThreshold3;
            break;
         case 4:
            minthreshold = this.chestThreshold4;
            break;
         default:
            minthreshold = 0;
      }

      int maxthreshold;
      switch (max) {
         case 2:
            maxthreshold = this.chestThreshold2;
            break;
         case 3:
            maxthreshold = this.chestThreshold3;
            break;
         case 4:
            maxthreshold = this.chestThreshold4;
            break;
         default:
            maxthreshold = this.cachedChestLevelSum;
      }

      int random = rand.getRandomInt(minthreshold, maxthreshold);
      return this.getLevel(random);
   }

   public static GlobalSchematicConfig fromFile(String path) {
      Gson gson = new Gson();

      try {
         String json = new String(Files.readAllBytes(Paths.get(path)));
         json = Nimmersatt.friss(json);
         // Previously this parsed value (var3) was computed and then thrown
         // away -- a `return` in the finally block below unconditionally
         // overrode it (and swallowed any parse exception too), so
         // global_settings.json was silently ignored and every load fell
         // back to hardcoded defaults (e.g. chestLevelWeights always
         // [20,20,20,20,20]) no matter what was actually configured on disk.
         return gson.fromJson(json, GlobalSchematicConfig.class);
      } catch (JsonSyntaxException e) {
         Bukkit.getLogger().throwing(GlobalSchematicConfig.class.getName(), "Couldn't load global schematic config.", e);
      } catch (IOException e) {
         Bukkit.getLogger().throwing(GlobalSchematicConfig.class.getName(), "Couldn't load global schematic config.", e);
      }

      return new GlobalSchematicConfig();
   }

   public boolean toFile(String path) {
      try {
         Gson gson = new GsonBuilder().setPrettyPrinting().create();
         String file = gson.toJson(this);
         Files.write(Paths.get(path), file.getBytes());
         return true;
      } catch (IOException e) {
         Bukkit.getLogger().throwing(this.getClass().getName(), "storeConfig config", e);
         return false;
      }
   }
}