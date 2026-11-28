package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
   private String lastBasinReject="not-evaluated";
   private volatile String lastSearchSummary="no basin search yet";

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
      if(!(radiusX>=40.0&&radiusZ>=28.0)||!Double.isFinite(centerX)||!Double.isFinite(centerZ)) {
         this.lastBasinReject="invalid-footprint";return null;
      }
      double c=Math.cos(angle),s=Math.sin(angle);
      int[] core={surfaceHeight((int)Math.round(centerX),(int)Math.round(centerZ))};
      double coreSum=core[0],coreMin=core[0],coreMax=core[0];
      int coreCount=1;
      for(double radius:new double[]{0.22,0.42}) {
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
      if(rise<1.5 || rimMax-rimMin>34.0) { this.lastBasinReject=rise<1.5?"insufficient-rise":"uneven-rim";return null; }
      // The exact candidate centre must itself be submerged. A mean over the
      // floor ring can otherwise select a level that leaves the lake's named
      // centre column dry even though its surrounding low points are wet.
      double desiredWaterline=Math.max(coreMean+Math.max(1.25,Math.min(3.5,rise*.45)),core[0]+2.0);
      int waterY=(int)Math.floor(Math.min(desiredWaterline,rimMin-1.0));
      if(waterY<core[0]+2 || waterY>=rimMin) { this.lastBasinReject="waterline-not-contained";return null; }

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
      if(submerged<Math.ceil(44.0*0.60)) { this.lastBasinReject="insufficient-submerged-area";return null; }

      int rimAbove=0;
      for(int i=0;i<40;i++) {
         double a=(Math.PI*2.0*i)/40.0;
         double u=Math.cos(a),v=Math.sin(a);
         int x=(int)Math.round(centerX+c*radiusX*u-s*radiusZ*v);
         int z=(int)Math.round(centerZ+s*radiusX*u+c*radiusZ*v);
         if(surfaceHeight(x,z)>=waterY+1)rimAbove++;
      }
      if(rimAbove<28) { this.lastBasinReject="open-rim";return null; }

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
      if(maxEdgeStep>15) { this.lastBasinReject="steep-shore";return null; }
      this.lastBasinReject="accepted";
      return new BasinFit(waterY,coreMean,rimMean,coreMin,rimMin,rise,maxEdgeStep);
   }

   /**
    * Search the unmodified wilderness surface for naturally enclosed depressions.
    * This is deliberately a terrain-first scan: feature-cell randomness supplies
    * the search domain, not the lake centre. Returned candidates are ranked by
    * their natural low-point / closed-rim score and still pass findBasin().
    */
   public List<BasinCandidate> findNearbyBasins(double originX,double originZ,
           double searchRadiusBlocks,double radiusX,double radiusZ,double preferredAngle,int maximum) {
      if(maximum<=0||searchRadiusBlocks<0||!Double.isFinite(originX)||!Double.isFinite(originZ))
         return java.util.Collections.emptyList();
      final class Scored {
         final double x,z,score;
         Scored(double x,double z,double score){this.x=x;this.z=z;this.score=score;}
      }
      List<Scored> scored=new ArrayList<>();
      int side=21;
      double bestRise=Double.NEGATIVE_INFINITY,bestSpread=Double.POSITIVE_INFINITY;
      int coarseRejected=0,fullTested=0,accepted=0;
      java.util.Map<String,Integer> rejectionCounts=new java.util.TreeMap<>();
      double step=side<=1?0.0:(searchRadiusBlocks*2.0)/(side-1);
      double c=Math.cos(preferredAngle),sn=Math.sin(preferredAngle);
      for(int ix=0;ix<side;ix++)for(int iz=0;iz<side;iz++) {
         double x=originX-searchRadiusBlocks+ix*step;
         double z=originZ-searchRadiusBlocks+iz*step;
         int center=surfaceHeight((int)Math.round(x),(int)Math.round(z));
         double sum=0.0,min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
         int above=0,count=16;
         for(int p=0;p<count;p++) {
            double a=Math.PI*2.0*p/count;
            double u=Math.cos(a)*0.72,v=Math.sin(a)*0.72;
            int sx=(int)Math.round(x+c*radiusX*u-sn*radiusZ*v);
            int sz=(int)Math.round(z+sn*radiusX*u+c*radiusZ*v);
            int height=surfaceHeight(sx,sz);
            sum+=height;min=Math.min(min,height);max=Math.max(max,height);
            if(height>=center+1)above++;
         }
         double rise=sum/count-center;
         double spread=max-min;
         if(rise>bestRise){bestRise=rise;bestSpread=spread;}
         // Cheap coarse rejection. Full closure, waterline and shoreline-grade
         // tests are applied to the best candidates below.
         if(rise<1.0||above<9||spread>42.0){coarseRejected++;continue;}
         double score=rise+0.18*(min-center)-0.035*spread;
         scored.add(new Scored(x,z,score));
      }
      scored.sort(Comparator.comparingDouble((Scored candidate)->candidate.score).reversed()
              .thenComparingDouble(candidate->candidate.x).thenComparingDouble(candidate->candidate.z));
      List<BasinCandidate> result=new ArrayList<>();
      // The closed rim is the 72% contour used by the coarse suitability pass.
      // Use that as the actual lake radius; the wider search radius is not a
      // licence to extend water over the surrounding wilderness.
      double basinRadiusX=radiusX*0.72,basinRadiusZ=radiusZ*0.72;
      for(Scored candidate:scored) {
         fullTested++;
         BasinFit fit=findBasin(candidate.x,candidate.z,basinRadiusX,basinRadiusZ,preferredAngle);
         if(fit==null) {
            rejectionCounts.merge(this.lastBasinReject,1,Integer::sum);
            continue;
         }
         accepted++;
         result.add(new BasinCandidate(candidate.x,candidate.z,preferredAngle,
                 basinRadiusX,basinRadiusZ,fit,candidate.score));
         if(result.size()>=maximum)break;
      }
      this.lastSearchSummary="sampled="+(side*side)+" coarseAccepted="+scored.size()
              +" coarseRejected="+coarseRejected+" fullTested="+fullTested
              +" accepted="+accepted+" bestRise="+String.format(java.util.Locale.ROOT,"%.2f",bestRise)
              +" bestRimSpread="+String.format(java.util.Locale.ROOT,"%.2f",bestSpread)
              +" rejects="+rejectionCounts;
      return java.util.Collections.unmodifiableList(result);
   }

   public String lastSearchDiagnostic(){return this.lastSearchSummary;}

   public static final class BasinCandidate {
      public final double centerX,centerZ,angle,radiusX,radiusZ,score;
      public final BasinFit fit;
      BasinCandidate(double centerX,double centerZ,double angle,double radiusX,double radiusZ,
                     BasinFit fit,double score) {
         this.centerX=centerX;this.centerZ=centerZ;this.angle=angle;
         this.radiusX=radiusX;this.radiusZ=radiusZ;this.fit=fit;this.score=score;
      }
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
