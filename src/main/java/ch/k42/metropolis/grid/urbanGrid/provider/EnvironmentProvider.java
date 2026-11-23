package ch.k42.metropolis.grid.urbanGrid.provider;

import org.bukkit.Material;
import org.bukkit.World;

public abstract class EnvironmentProvider {
   private Long seed;

   public EnvironmentProvider(Long newSeed) {
      this.seed = newSeed;
   }

   public abstract Material checkBlock(World var1, int var2, int var3, int var4);
}
