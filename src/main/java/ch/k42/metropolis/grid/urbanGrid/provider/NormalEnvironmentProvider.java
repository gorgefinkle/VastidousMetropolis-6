package ch.k42.metropolis.grid.urbanGrid.provider;

import org.bukkit.Material;
import org.bukkit.World;

public class NormalEnvironmentProvider extends EnvironmentProvider {
   public NormalEnvironmentProvider(Long newSeed) {
      super(newSeed);
   }

   @Override
   public Material checkBlock(World world, int posX, int posY, int posZ) {
      return null;
   }
}
