package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.common.Grid;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.Chunk;
import org.bukkit.Material;

public abstract class StreetParcel extends Parcel {

   /**
    * The Y level at which a road/highway/bridge's actual walkable surface
    * sits. Every road-network parcel that needs to know "where is street
    * level" -- RoadParcel's/HighwayParcel's own decay pass, and
    * BridgeParcel's deck -- reads this ONE constant rather than each
    * carrying its own hardcoded height literal, so they cannot silently
    * drift apart from each other the way BridgeParcel's deck height and
    * RiverConfig's water level otherwise could (see
    * BridgeParcel.warnIfClearanceBelowConfigured()).
    *
    * Deliberately NOT derived from PluginConfig.getBuildHeight(): that
    * config value also drives terrain fallback, building placement, and
    * river depth, and letting road height silently follow it would mean
    * reconfiguring buildHeight for an unrelated reason quietly moves every
    * road and bridge on the server too. The two currently agree (both 63)
    * only because that's what keeps the default river-bridge clearance at
    * its configured value -- if buildHeight is ever reconfigured, this
    * constant should be revisited and updated deliberately alongside it,
    * not left to drift.
    */
   public static final int ROAD_SURFACE_Y = 63;

   protected StreetParcel(Cartesian2D base, Cartesian2D size, ContextType contextType, SchematicType schematicType, UrbanGrid grid) {
      super(base, size, contextType, schematicType, grid);
   }

   @Override
   public abstract void populate(MetropolisGenerator var1, Chunk var2);

   /**
    * Called as the very last step of postPopulate() in every subclass,
    * after paste + decay + sidewalk are fully done on this road chunk.
    *
    * For each of the four orthogonal neighbours, this method looks up the
    * parcel through GridProvider -- not through this.grid -- so it crosses
    * grid boundaries correctly. If that neighbour is a ClipboardParcel
    * (a building), it asks the building to re-cut the face it has toward
    * this road.
    *
    * This is the correct direction for the dependency: the road knows when
    * it is done, so the road drives the cutout timing. The building knows
    * its own cutout geometry, so the building does the actual block edits.
    * Neither side needs to store Runnables, pending queues, or any state
    * beyond what they already have.
    */
   protected void notifyNeighboursToRecut(MetropolisGenerator generator) {
      // Recorded by world chunk (see PlacedParcels): no tile is consulted, so it makes no difference whether the
      // neighbour's tile exists yet. A building that finishes after this road cuts toward it itself.
      PlacedParcels.of(generator).roadFinished(this, generator);
   }

   protected void decayRoadChunk(MetropolisGenerator generator, Chunk chunk, int y) {
      int amount = 20;
      GridRandom rand = this.grid.getRandom();

      while (amount > 0) {
         amount--;
         int x = rand.getRandomInt(16);
         int z = rand.getRandomInt(16);
         if (chunk.getBlock(x, y + 1, z).getType().isAir() && !chunk.getBlock(x, y, z).getType().isAir()) {
            switch (rand.getRandomInt(10)) {
               case 0:
               case 1:
               case 2:
                  chunk.getBlock(x, y, z).setType(Material.MOSSY_COBBLESTONE, false);
                  break;
               case 3:
               case 4:
               case 5:
               case 6:
                  chunk.getBlock(x, y, z).setType(Material.COBBLESTONE, false);
                  break;
               case 7:
               case 8:
                  chunk.getBlock(x, y, z).setType(Material.DIRT, false);
                  Mats.setGroundPlant(chunk.getBlock(x, y + 1, z), rand.getRandomInt(3)); // 0 dead shrub, 1 grass, 2 fern
                  break;
               case 9:
                  // STEP data 3 was the cobblestone slab
                  chunk.getBlock(x, y, z).setType(Material.COBBLESTONE_SLAB, false);
            }
         }
      }
   }

   protected void decaySidewalk(MetropolisGenerator generator, Chunk chunk, int y) {
      int amount = 20;
      GridRandom rand = this.grid.getRandom();

      while (amount > 0) {
         amount--;
         int x = rand.getRandomInt(16);
         int z = rand.getRandomInt(16);
         if (chunk.getBlock(x, y + 1, z).getType().isAir() && !chunk.getBlock(x, y, z).getType().isAir() && rand.getChance(50)) {
            chunk.getBlock(x, y, z).setType(Material.COBBLESTONE_SLAB, false); // STEP data 3
         }
      }
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
   }
}