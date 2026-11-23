package ch.k42.metropolis.grid.common;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import org.bukkit.Chunk;

public abstract class Grid {
   public static final int GRID_SIZE = 64;
   protected final GridRandom random;
   protected final GridProvider gridProvider;
   protected final MetropolisGenerator generator;
   protected final Cartesian2D root;

   protected Grid(GridRandom random, GridProvider gridProvider, MetropolisGenerator generator, Cartesian2D root) {
      this.random = random;
      this.gridProvider = gridProvider;
      this.generator = generator;
      this.root = root;
   }

   public abstract void populate(Chunk var1);

   public void postPopulate(Chunk chunk) {
   }

   public GridRandom getRandom() {
      return this.random;
   }
}
