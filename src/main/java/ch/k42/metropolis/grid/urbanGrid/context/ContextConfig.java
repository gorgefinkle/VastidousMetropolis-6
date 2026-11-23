package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import com.google.gson.annotations.Expose;
import java.util.Arrays;

public class ContextConfig {
   @Expose
   private double scale = 0.2;
   @Expose
   private double scatterScale = 0.2;
   @Expose
   private double scatterAmount = 5.0;
   @Expose
   private double mainScale = 0.01;
   @Expose
   private double recurseScale = 10.0;
   @Expose
   // RESIDENTIAL is nested with DENSERESIDENTIAL as its own sub-group
   // rather than a single leaf so that dense pockets read as scattered
   // "neighborhoods" carved out of the wider residential noise-zone
   // (the recursive zone lookup applies a finer noise scale one level
   // down -- see ContextProviderSimplex.getContext()) instead of one
   // single, contiguous dense super-zone. The group's own weight (1.0)
   // matches the plain weight RESIDENTIAL used before this split
   // existed, so its share against the LOWRISE+INDUSTRIAL/MIDRISE/
   // HIGHRISE entries below is unchanged -- only the makeup *within*
   // what was previously pure RESIDENTIAL now splits 5:2 in favour of
   // ordinary RESIDENTIAL. See PluginConfig.isDenseResidentialEnabled():
   // when off, ContextProviderSimplex strips the DENSERESIDENTIAL leaf
   // out of this group entirely before ever computing normalized
   // weights, so RESIDENTIAL alone reclaims the whole group, exactly as
   // if DENSERESIDENTIAL had never been listed here.
   private ContextZone[] contextZones = new ContextZone[]{
           new ContextZone(new ContextZone[]{new ContextZone(ContextType.RESIDENTIAL, 5.0), new ContextZone(ContextType.DENSERESIDENTIAL, 2.0)}, 9.0),
           new ContextZone(new ContextZone[]{new ContextZone(ContextType.LOWRISE, 3.0), new ContextZone(ContextType.INDUSTRIAL, 2.0)}, 7.0),
           new ContextZone(ContextType.MIDRISE, 5.0),
           new ContextZone(ContextType.HIGHRISE, 2.0),
           // Weights are the city-rung shares (highrise 2 : midrise 5 : lowrise+industrial 7 : residential 9)
           // used by generator.cityRings; the forest/farm group only needs to be present there -- its
           // size and the farm/forest split come from generator.cityRings.farmBandWidth.
           // Only used while generator.districtNeighborRules is on (they are
           // stripped from the plain noise lookup otherwise). Read by
           // DistrictContextAssigner as the last rung of the ladder.
           new ContextZone(new ContextZone[]{new ContextZone(ContextType.FOREST, 1.0), new ContextZone(ContextType.FARM, 1.0)}, 4.0)
   };

   public double getScale(int level) {
      double levelScale = this.recurseScale * level;
      return levelScale * this.mainScale * this.scale;
   }

   public double getScatterScale() {
      return this.scatterScale;
   }

   public double getScatterAmount() {
      return this.scatterAmount;
   }

   public ContextZone[] getContextZones() {
      return this.contextZones;
   }

   @Override
   public String toString() {
      return "ContextConfig{scale="
              + this.scale
              + ", scatterScale="
              + this.scatterScale
              + ", scatterAmount="
              + this.scatterAmount
              + ", mainScale="
              + this.mainScale
              + ", recurseScale="
              + this.recurseScale
              + ", contextZones="
              + Arrays.toString(this.contextZones)
              + '}';
   }
}