package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.ArrayList;
import java.util.List;

/**
 * Continuous longitudinal water surface for a carved wilderness stream.
 *
 * Source and receiver levels are authoritative. The complete elevation change
 * is distributed smoothly along the route with deterministic, low-frequency
 * variation in gradient. Existing terrain only nudges the profile; the route
 * and its channel carver are free to create a valley through ordinary relief.
 * Major ridges and massifs are avoided earlier by the route planner.
 */
public final class StreamWaterProfile {
   @FunctionalInterface
   public interface TerrainHeight {
      int at(int blockX,int blockZ);
   }

   public static final class Profile {
      private final double[] x,z,waterY;
      private final double lengthBlocks;
      private Profile(double[] x,double[] z,double[] waterY,double lengthBlocks) {
         this.x=x;this.z=z;this.waterY=waterY;this.lengthBlocks=lengthBlocks;
      }
      public double[] x(){return this.x.clone();}
      public double[] z(){return this.z.clone();}
      public double[] waterY(){return this.waterY.clone();}
      public int pointCount(){return this.x.length;}
      public double lengthBlocks(){return this.lengthBlocks;}
      public double totalDrop(){return this.waterY[0]-this.waterY[this.waterY.length-1];}
      public double maximumSampleDrop() {
         double max=0.0;
         for(int i=1;i<this.waterY.length;i++)max=Math.max(max,this.waterY[i-1]-this.waterY[i]);
         return max;
      }
   }

   private StreamWaterProfile(){}

   public static Profile create(double[] inputX,double[] inputZ,int sourceY,int receiverY,
                                 long seed,TerrainHeight terrain) {
      if(inputX==null||inputZ==null||inputX.length!=inputZ.length||inputX.length<2)
         throw new IllegalArgumentException("A stream profile requires matched route points");
      if(receiverY>sourceY)
         throw new IllegalArgumentException("A downstream stream cannot rise into its receiver");
      for(int i=0;i<inputX.length;i++)
         if(!Double.isFinite(inputX[i])||!Double.isFinite(inputZ[i]))
            throw new IllegalArgumentException("Stream route points must be finite");

      // Dense samples keep the carved channel and water surface continuous
      // even where the route planner contains long, straight A* segments.
      List<Double> px=new ArrayList<>(),pz=new ArrayList<>();
      px.add(inputX[0]);pz.add(inputZ[0]);
      double totalLength=0.0;
      for(int i=1;i<inputX.length;i++) {
         double dx=inputX[i]-inputX[i-1],dz=inputZ[i]-inputZ[i-1];
         double length=Math.hypot(dx,dz);
         if(length<1.0e-6)continue;
         totalLength+=length;
         int pieces=Math.max(1,(int)Math.ceil(length/12.0));
         for(int p=1;p<=pieces;p++) {
            double t=p/(double)pieces;
            px.add(inputX[i-1]+dx*t);
            pz.add(inputZ[i-1]+dz*t);
         }
      }
      int n=px.size();
      if(n<2)throw new IllegalArgumentException("A stream profile requires non-zero route length");
      double[] x=new double[n],z=new double[n];
      for(int i=0;i<n;i++){x[i]=px.get(i);z[i]=pz.get(i);}

      double[] distanceAlong=new double[n],weights=new double[n-1],cumulative=new double[n];
      for(int i=1;i<n;i++)
         distanceAlong[i]=distanceAlong[i-1]+Math.hypot(x[i]-x[i-1],z[i]-z[i-1]);

      Noise2D gradeNoise=new Noise2D(seed^0x475241444550524FL);
      double[] naturalBreak=new double[n-1];
      if(terrain!=null) {
         // A waterfall is eligible only where the existing terrain has a sharp
         // local drop relative to its wider upstream/downstream neighbourhood.
         // A steady hillside grade does not qualify as a fall.
         for(int i=0;i<n-1;i++) {
            double dx=x[i+1]-x[i],dz=z[i+1]-z[i];
            double segmentLength=Math.max(1.0,Math.hypot(dx,dz));
            double ux=dx/segmentLength,uz=dz/segmentLength;
            int from=terrain.at((int)Math.floor(x[i]),(int)Math.floor(z[i]));
            int to=terrain.at((int)Math.floor(x[i+1]),(int)Math.floor(z[i+1]));
            int before=terrain.at((int)Math.floor(x[i]-24.0*ux),(int)Math.floor(z[i]-24.0*uz));
            int after=terrain.at((int)Math.floor(x[i+1]+24.0*ux),(int)Math.floor(z[i+1]+24.0*uz));
            double naturalDrop=from-to;
            double neighbourhoodDrop=(before-after)*(segmentLength/60.0);
            double abruptDrop=Math.max(0.0,naturalDrop-Math.max(1.5,neighbourhoodDrop*1.65));
            naturalBreak[i]=Math.min(16.0,abruptDrop*0.70);
         }
      }
      double totalWeight=0.0;
      for(int i=0;i<n-1;i++) {
         double mx=(x[i]+x[i+1])*0.5,mz=(z[i]+z[i+1])*0.5;
         double segmentLength=Math.max(1.0,Math.hypot(x[i+1]-x[i],z[i+1]-z[i]));
         double broad=gradeNoise.noise(mx/1750.0+0.37,mz/1750.0-0.61);
         double local=gradeNoise.noise(mx/520.0-1.13,mz/520.0+0.79);
         double terrainCue=0.0;
         if(terrain!=null) {
            int here=terrain.at((int)Math.floor(mx),(int)Math.floor(mz));
            int next=terrain.at((int)Math.floor(x[i+1]),(int)Math.floor(z[i+1]));
            terrainCue=Math.max(-1.0,Math.min(1.0,(next-here)/16.0));
         }
         double modulation=1.0+0.10*broad+0.05*local+0.03*terrainCue;
         // Spread only a naturally detected terrain break into a short nearby
         // reach, producing a steep fall without imposing one on smooth ground.
         double fallAffinity=0.0;
         for(int j=Math.max(0,i-3);j<=Math.min(n-2,i+3);j++) {
            if(naturalBreak[j]<=0.0)continue;
            double distance=Math.abs((distanceAlong[i]+segmentLength*0.5)
                    -(distanceAlong[j]+Math.hypot(x[j+1]-x[j],z[j+1]-z[j])*0.5));
            double fade=Math.exp(-(distance*distance)/(2.0*28.0*28.0));
            fallAffinity=Math.max(fallAffinity,naturalBreak[j]*fade);
         }
         double waterfallBias=1.0+fallAffinity;
         weights[i]=segmentLength*Math.max(0.72,Math.min(18.0,modulation*waterfallBias));
         totalWeight+=weights[i];
         cumulative[i+1]=totalWeight;
      }

      int drop=sourceY-receiverY;
      double[] levels=new double[n];
      for(int i=0;i<n;i++) {
         double progress=totalWeight<=1.0e-9?i/(double)(n-1):cumulative[i]/totalWeight;
         levels[i]=sourceY-drop*progress;
      }
      levels[0]=sourceY;
      levels[n-1]=receiverY;
      // Floating-point accumulation must never create an upstream rise.
      for(int i=1;i<n;i++)levels[i]=Math.min(levels[i],levels[i-1]);
      levels[n-1]=receiverY;
      return new Profile(x,z,levels,totalLength);
   }
}
