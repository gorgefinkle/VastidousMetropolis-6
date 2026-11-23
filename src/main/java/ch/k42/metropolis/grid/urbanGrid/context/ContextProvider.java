package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.Cartesian2D;

public interface ContextProvider {
   ContextType getContext(ContextZone[] var1, int var2, int var3, int var4);

   ContextType getContext(Cartesian2D var1);
}
