package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;

/**
 * Immutable per-column result of the city-aware hydrology biome layer.
 * The same sample says who owns the column, whether it is open water or bank,
 * and (where applicable) the exact network confluence.
 */
public final class HydrologySample {
   public enum Feature { NONE, OCEAN, RIVER, CREEK, LAKE, STREAM }

   private final int blockX,blockZ;
   private final WaterField.Kind kind;
   private final Feature feature;
   private final boolean openWater;
   private final double signedDistanceBlocks;
   private final int waterY,depth;
   private final RiverProvider carrier;
   private final HydrologyConvergenceKey convergenceKey;

   public HydrologySample(int blockX,int blockZ,WaterField.Kind kind,Feature feature,
                          boolean openWater,double signedDistanceBlocks,int waterY,int depth,
                          RiverProvider carrier,HydrologyConvergenceKey convergenceKey) {
      if(kind==null||feature==null)throw new IllegalArgumentException("Hydrology sample owner is required");
      if(depth<0)throw new IllegalArgumentException("Hydrology depth cannot be negative");
      this.blockX=blockX;this.blockZ=blockZ;this.kind=kind;this.feature=feature;
      this.openWater=openWater;this.signedDistanceBlocks=signedDistanceBlocks;
      this.waterY=waterY;this.depth=depth;this.carrier=carrier;this.convergenceKey=convergenceKey;
   }
   public static HydrologySample none(int x,int z) {
      return new HydrologySample(x,z,WaterField.Kind.NONE,Feature.NONE,false,
              Double.NaN,Integer.MIN_VALUE,0,null,null);
   }
   public int blockX(){return blockX;} public int blockZ(){return blockZ;}
   public WaterField.Kind kind(){return kind;} public Feature feature(){return feature;}
   public boolean openWater(){return openWater;}
   /** Positive is waterward, negative is landward; NaN if the owner has no signed-distance field. */
   public double signedDistanceBlocks(){return signedDistanceBlocks;}
   public int waterY(){return waterY;} public int depth(){return depth;}
   public RiverProvider carrier(){return carrier;}
   public HydrologyConvergenceKey convergenceKey(){return convergenceKey;}
   public boolean hasWaterSurface(){return feature!=Feature.NONE&&openWater;}
}
