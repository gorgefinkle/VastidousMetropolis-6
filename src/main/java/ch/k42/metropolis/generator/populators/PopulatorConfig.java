package ch.k42.metropolis.generator.populators;

import com.google.gson.annotations.Expose;
import java.util.Arrays;

public class PopulatorConfig {
   @Expose
   private OreVein[] ores = new OreVein[]{
      new OreVein("GRAVEL", 32, 10, 1.0, 0.0),
      new OreVein("COAL_ORE", 16, 21, 1.0, 0.0),
      new OreVein("IRON_ORE", 8, 21, 0.8, 0.0),
      new OreVein("GOLD_ORE", 8, 8, 0.75, 0.0),
      new OreVein("REDSTONE_ORE", 7, 10, 0.7, 0.0),
      new OreVein("DIAMOND_ORE", 7, 1, 0.4, 0.0),
      new OreVein("LAPIS_ORE", 6, 3, 0.5, 0.0)
   };

   public OreVein[] getOres() {
      return this.ores;
   }

   @Override
   public String toString() {
      return "PopulatorConfig{ores=" + Arrays.toString(this.ores) + '}';
   }
}
