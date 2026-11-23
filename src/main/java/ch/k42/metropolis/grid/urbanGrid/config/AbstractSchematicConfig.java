package ch.k42.metropolis.grid.urbanGrid.config;

import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.entity.EntityType;

public abstract class AbstractSchematicConfig {
   protected AbstractSchematicConfig.SchematicSpawner[] Spawners = null;
   protected transient int cachedSpawnerLevelSum = -1;
   protected transient boolean cachedSpawner = false;

   protected void cacheSpawnerThresholds() {
      if (this.Spawners != null && this.Spawners.length == 0) {
      }

      this.Spawners[0].setThreshold(this.Spawners[0].getWeight());
      AbstractSchematicConfig.SchematicSpawner previous = this.Spawners[0];

      for (int i = 1; i < this.Spawners.length; i++) {
         AbstractSchematicConfig.SchematicSpawner current = this.Spawners[i];
         current.setThreshold(previous.getThreshold() + current.getWeight());
         previous = current;
      }

      this.cachedSpawnerLevelSum = previous.getThreshold();
      this.cachedSpawner = true;
   }

   protected int getSpawnerEntityWeightSum() {
      if (!this.cachedSpawner) {
         this.cacheSpawnerThresholds();
      }

      return this.cachedSpawnerLevelSum;
   }

   public EntityType getRandomSpawnerEntity(GridRandom rand) {
      if (!this.cachedSpawner) {
         this.cacheSpawnerThresholds();
      }

      int random = rand.getRandomInt(this.getSpawnerEntityWeightSum());

      for (int i = 0; i < this.Spawners.length; i++) {
         if (random < this.Spawners[i].getThreshold()) {
            return this.Spawners[i].getType();
         }
      }

      Minions.w("No EntityType for Spawner could be randomly determinated, random argument too low?");
      return this.Spawners[0].getType();
   }

   public void setSpawners(AbstractSchematicConfig.SchematicSpawner[] spawners) {
      this.Spawners = spawners;
   }

   public AbstractSchematicConfig.SchematicSpawner[] getSpawners() {
      return this.Spawners;
   }

   public class RoadCutout {
      public final int startPoint;
      public final int length;

      private RoadCutout(int lengthStart, int lengthEnd) {
         this.startPoint = lengthStart;
         this.length = lengthEnd;
      }
   }

   /**
    * Same {startPoint, length} shape as RoadCutout (and parsed the exact
    * same way, from the same kind of JSON array), but a different
    * MEANING: this doesn't cut into anything. It marks a point along one
    * side of the schematic's own unrotated reference file where a creek
    * is allowed to originate -- length here is the creek's STARTING
    * WIDTH IN BLOCKS, not a span to cut. See
    * SchematicConfig.riverStartBoundsN/E/S/W for the per-side rotation
    * handling, which mirrors cutoutN/E/S/W exactly.
    */
   public class RiverStartBound {
      public final int startPoint;
      public final int length;

      private RiverStartBound(int startPoint, int length) {
         this.startPoint = startPoint;
         this.length = length;
      }
   }

   protected class SchematicSpawner {
      private EntityType Type = EntityType.ZOMBIE;
      private int Weight = 10;
      private transient int threshold = -1;

      public SchematicSpawner(EntityType type, int weight) {
         this.Type = type;
         this.Weight = weight;
      }

      public EntityType getType() {
         return this.Type;
      }

      public int getWeight() {
         return this.Weight;
      }

      public int getThreshold() {
         return this.threshold;
      }

      public void setThreshold(int threshold) {
         this.threshold = threshold;
      }
   }
}