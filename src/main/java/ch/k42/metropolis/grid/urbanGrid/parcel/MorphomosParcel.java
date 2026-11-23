package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.farm.FarmGenerator;
import ch.k42.metropolis.grid.urbanGrid.forest.ForestGenerator;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaWilderness;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;
import org.bukkit.Chunk;

/**
 * One chunk of a morphomos region (forest or farm). For a forest region,
 * populate() hands the chunk to ForestGenerator, which shapes the ground and
 * plants it using the whole region's mask; for a farm region it goes to
 * FarmGenerator, which lays out fields, fences, barns and silos the same way.
 * Either way the parcel is then reported populated so neighbouring builds'
 * growth-readiness checks don't wait on it.
 */
public class MorphomosParcel extends Parcel {
   private final MorphomosRegion region;

   public MorphomosParcel(Cartesian2D base, ContextType contextType, UrbanGrid grid, MorphomosRegion region) {
      super(base, new Cartesian2D(1, 1), contextType, SchematicType.FILLER, grid);
      this.region = region;
   }

   public MorphomosRegion getRegion() {
      return this.region;
   }

   private boolean isForest() {
      return this.region != null && this.region.getContext() == ContextType.FOREST;
   }

   private boolean isFarm() {
      return this.region != null && this.region.getContext() == ContextType.FARM;
   }

   @Override
   public void populate(MetropolisGenerator generator, Chunk chunk) {
      if (chunk.getX() == this.chunkX && chunk.getZ() == this.chunkZ) {
         if (this.isForest()) {
            // generator.wilderness.vanilla.enabled: the server's own generation, eased to the street at the region's edges.
            // If vanilla cannot be had for this chunk (see VanillaSource) the Metropolis forest is the fallback.
            if (!VanillaWilderness.isActive(generator.getWorld())
                    || !VanillaWilderness.populateChunk(generator, chunk, this.grid, this.region)) {
               ForestGenerator.populateChunk(generator, chunk, this.grid, this.region);
            }
         } else if (this.isFarm()) {
            FarmGenerator.populateChunk(generator, chunk, this.grid, this.region);
         }
         this.markPopulated();
      }
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
      if (this.isFarm() && chunk.getX() == this.chunkX && chunk.getZ() == this.chunkZ) {
         PlacedParcels.of(generator).buildingFinished(this, generator);   // cuts toward roads that finished first
      }
      // Biomes were set during populate(); push them to clients.
      if ((this.isForest() || this.isFarm()) && generator.getWorld() != null && chunk.getX() == this.chunkX && chunk.getZ() == this.chunkZ) {
         generator.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
      }
   }

   /**
    * Called by a StreetParcel next to this chunk once that road has been fully
    * built (the same hook buildings use, see ClipboardParcel.makeCutoutsToward).
    * If one of this farm's dirt roads runs into that road, the road's kerb and
    * sidewalk are cut open for it with the shared cutout code, the width of the
    * dirt road. The street surface is left alone.
    */
   public void makeCutoutsToward(StreetParcel road, MetropolisGenerator generator) {
      if (!this.isFarm()) {
         return;
      }
      int[] cut = FarmGenerator.roadOpening(generator, this.grid, this.region,
              this.chunkX, this.chunkZ, road.getChunkX(), road.getChunkZ());
      if (cut == null) {
         return;
      }
      // y = 64: the same start height ClipboardParcel uses (the road surface is 63, the kerb above it).
      // floor = null: only the kerb/sidewalk is cleared; the street surface itself is never replaced.
      ClipboardParcel.cutoutBlocks(generator, new Cartesian3D(cut[0], 64, cut[1]), new Cartesian3D(cut[2], 11, cut[3]), null);
   }
}
