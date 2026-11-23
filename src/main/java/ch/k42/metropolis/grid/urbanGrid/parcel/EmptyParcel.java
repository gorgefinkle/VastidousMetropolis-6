package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;
import org.bukkit.Chunk;

public class EmptyParcel extends Parcel {
   public EmptyParcel(Cartesian2D base, Cartesian2D size, ContextType contextType, SchematicType schematicType, UrbanGrid grid) {
      super(base, size, contextType, schematicType, grid);
   }

   @Override
   public void populate(MetropolisGenerator generator, Chunk chunk) {
   }

   @Override
   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
   }
}
