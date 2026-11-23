package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.GridRandom;
import org.bukkit.util.noise.SimplexOctaveGenerator;

public class ZoneProvider {
   private MetropolisGenerator generator;

   public ZoneProvider(MetropolisGenerator generator) {
      this.generator = generator;
   }

   public ContextType getContext(long seed, int chunkX, int chunkZ, GridRandom random) {
      SimplexOctaveGenerator gen1 = new SimplexOctaveGenerator(seed, 2);
      SimplexOctaveGenerator gen2 = new SimplexOctaveGenerator(seed, 2);
      double holeScale = 0.03;
      double maxHeight = gen1.noise(chunkX * holeScale, chunkZ * holeScale, 0.3, 0.6, true);
      double altscale = 0.1;
      double alternate = gen2.noise(chunkX * altscale, chunkZ * altscale, 0.3, 0.6, true);
      if (maxHeight < -0.3) {
         return ContextType.RESIDENTIAL;
      } else if (maxHeight < 0.2) {
         return alternate > 0.4 ? ContextType.INDUSTRIAL : ContextType.LOWRISE;
      } else {
         return maxHeight < 0.5 ? ContextType.MIDRISE : ContextType.HIGHRISE;
      }
   }
}
