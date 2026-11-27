package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.plugin.PluginConfig;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure pre-generation sample of the same natural wilderness surface that
 * ForestGenerator paints. It deliberately excludes water and post-population
 * edits: hydrology may only occupy a depression that already exists in this
 * underlying terrain field.
 */
public final class WildernessTerrainProfile {
   private final ForestTerrain terrain;
   private final ForestGenerator.WildField wild;
   private final int baseline;
   private final ConcurrentHashMap<Long,Integer> heightCache=new ConcurrentHashMap<>();

   public WildernessTerrainProfile(long worldSeed) {
      this.terrain=new ForestTerrain(worldSeed);
      this.wild=PluginConfig.isWildBiomesEnabled()&&!PluginConfig.isWildVanillaEnabled()
              ?new ForestGenerator.WildField(worldSeed,false):null;
      if(this.wild!=null)ForestGenerator.configureWildTerrain(this.wild,this.terrain);
      this.baseline=PluginConfig.getBuildHeight()+PluginConfig.getForestSurfaceOffset();
   }

   /** The undisturbed natural surface at a world column, before any water layer is applied. */
   public int surfaceHeight(int x,int z) {
      long key=((long)x<<32)^(z&0xffffffffL);
      Integer cached=this.heightCache.get(key);
      if(cached!=null)return cached;
      double relief;
      if(this.wild!=null) {
         double[] biome=this.wild.sharp(x,z);
         double[] forest=new double[ForestType.ALL.length];
         this.terrain.typeWeights(x,z,forest);
         relief=ForestGenerator.wildReliefAt(this.wild,x,z,biome,this.terrain.relief(x,z,forest),false);
      } else {
         double[] forest=new double[ForestType.ALL.length];
         this.terrain.typeWeights(x,z,forest);
         relief=this.terrain.relief(x,z,forest);
      }
      int result=this.baseline+(int)Math.round(relief);
      if(this.heightCache.size()>131072)this.heightCache.clear();
      Integer prior=this.heightCache.putIfAbsent(key,result);
      return prior==null?result:prior;
   }

   /**
    * Tests whether the existing land is a closed, shallow enough depression
    * for a natural lake. The waterline is chosen from the sampled floor and
    * lowest surrounding rim; no terrain is lowered to manufacture a basin.
    */
   public BasinFit findBasin(double centerX,double centerZ,double radiusX,double radiusZ,double angle) {
      if(!(radiusX>=96.0&&radiusZ>=96.0)||!Double.isFinite(centerX)||!Double.isFinite(centerZ))
         return null;
      double c=Math.cos(angle),s=Math.sin(angle);
      int[] core={surfaceHeight((int)Math.round(centerX),(int)Math.round(centerZ))};
      double coreSum=core[0],coreMin=core[0],coreMax=core[0];
      int coreCount=1;
      for(double radius:new double[]{0.22,0.42,0.58}) {
         int points=radius<0.3?12:16;
         for(int i=0;i<points;i++) {
            double a=(Math.PI*2.0*i)/points;
            double u=Math.cos(a)*radius,v=Math.sin(a)*radius;
            int x=(int)Math.round(centerX+c*radiusX*u-s*radiusZ*v);
            int z=(int)Math.round(centerZ+s*radiusX*u+c*radiusZ*v);
            int y=surfaceHeight(x,z);
            coreSum+=y;coreMin=Math.min(coreMin,y);coreMax=Math.max(coreMax,y);coreCount++;
         }
      }
      double coreMean=coreSum/coreCount;
      double rimSum=0.0,rimMin=Double.POSITIVE_INFINITY,rimMax=Double.NEGATIVE_INFINITY;
      int rimHigh=0,rimCount=0;
      for(int i=0;i<40;i++) {
         double a=(Math.PI*2.0*i)/40.0;
         double u=Math.cos(a),v=Math.sin(a);
         int x=(int)Math.round(centerX+c*radiusX*u-s*radiusZ*v);
         int z=(int)Math.round(centerZ+s*radiusX*u+c*radiusZ*v);
         int y=surfaceHeight(x,z);
         rimSum+=y;rimMin=Math.min(rimMin,y);rimMax=Math.max(rimMax,y);rimCount++;
      }
      double rimMean=rimSum/rimCount;
      double rise=rimMean-coreMean;
      // A flat plateau or a shallow noise dimple is not a lake basin.
      if(rise<2.25 || rimMax-rimMin>22.0) return null;
      int waterY=(int)Math.floor(Math.min(coreMean+Math.max(1.6,Math.min(5.0,rise*.55)),rimMin-1.0));
      if(waterY<coreMin+2 || waterY>=rimMin) return null;

      int submerged=0;
      for(double radius:new double[]{0.22,0.42,0.58}) {
         int points=radius<0.3?12:16;
         for(int i=0;i<points;i++) {
            double a=(Math.PI*2.0*i)/points;
            double u=Math.cos(a)*radius,v=Math.sin(a)*radius;
            int x=(int)Math.round(centerX+c*radiusX*u-s*radiusZ*v);
            int z=(int)Math.round(centerZ+s*radiusX*u+c*radiusZ*v);
            if(surfaceHeight(x,z)<=waterY+1)submerged++;
         }
      }
      if(submerged<Math.ceil(44.0*0.68)) return null;

      int rimAbove=0;
      for(int i=0;i<40;i++) {
         double a=(Math.PI*2.0*i)/40.0;
         double u=Math.cos(a),v=Math.sin(a);
         int x=(int)Math.round(centerX+c*radiusX*u-s*radiusZ*v);
         int z=(int)Math.round(centerZ+s*radiusX*u+c*radiusZ*v);
         if(surfaceHeight(x,z)>=waterY+1)rimAbove++;
      }
      if(rimAbove<30) return null;

      // Refuse escarpments that would require a vertical cut at the shore.
      int maxEdgeStep=0;
      for(int i=0;i<24;i++) {
         double a=(Math.PI*2.0*i)/24.0,u=Math.cos(a),v=Math.sin(a);
         int ix=(int)Math.round(centerX+c*radiusX*u*.84-s*radiusZ*v*.84);
         int iz=(int)Math.round(centerZ+s*radiusX*u*.84+c*radiusZ*v*.84);
         int ox=(int)Math.round(centerX+c*radiusX*u*1.08-s*radiusZ*v*1.08);
         int oz=(int)Math.round(centerZ+s*radiusX*u*1.08+c*radiusZ*v*1.08);
         maxEdgeStep=Math.max(maxEdgeStep,Math.abs(surfaceHeight(ix,iz)-surfaceHeight(ox,oz)));
      }
      if(maxEdgeStep>15) return null;
      return new BasinFit(waterY,coreMean,rimMean,coreMin,rimMin,rise,maxEdgeStep);
   }

   public static final class BasinFit {
      public final int waterY;
      public final double coreMean,rimMean,coreMin,rimMin,relief;
      public final int maximumShoreStep;
      BasinFit(int waterY,double coreMean,double rimMean,double coreMin,double rimMin,
               double relief,int maximumShoreStep) {
         this.waterY=waterY;this.coreMean=coreMean;this.rimMean=rimMean;
         this.coreMin=coreMin;this.rimMin=rimMin;this.relief=relief;
         this.maximumShoreStep=maximumShoreStep;
      }
   }
}
