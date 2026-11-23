package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.context.ContextConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.Cartesian2D;

public class Context {
   private final ContextType type;
   private final ContextConfig config;

   private Context(ContextType type, ContextConfig config) {
      this.type = type;
      this.config = config;
   }

   public static Context getRandom(UrbanGrid grid, Cartesian2D place) {
      return new Context(grid.getContextProvider().getContext(place), null);
   }

   public ContextType getType() {
      return this.type;
   }
}
