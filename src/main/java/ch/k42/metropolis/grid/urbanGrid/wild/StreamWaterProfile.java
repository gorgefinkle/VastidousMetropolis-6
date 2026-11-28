package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.ArrayList;
import java.util.List;

/**
 * Longitudinal water surface for a carved wilderness stream.
 *
 * The channel may cross terrain that is above its water surface: the stream
 * carver creates the valley and its banks. Existing surface heights only
 * nudge the profile. The total descent is concentrated into several short
 * cascade reaches separated by slower pool/riffle sections.
 */
public final class StreamWaterProfile {
   @FunctionalInterface
   public interface TerrainHeight {
      int at(int blockX,int blockZ);
   }

   public static final class Profile {
      private final double[] x,z,waterY;
      private final int cascades;
      private final double lengthBlocks;
      private Profile(double[] x,double[] z,double[] waterY,int cascades,double lengthBlocks) {
         this.x=x;this.z=z;this.waterY=waterY;this.cascades=cascades;this.lengthBlocks=lengthBlocks;
      }
      public double[] x(){return this.x.clone();}
      public double[] z(){return this.z.clone();}
      public double[] waterY(){return this.waterY.clone();}
      public int pointCount(){return this.x.length;}
      public int cascadeCount(){return this.cascades;}
      public double lengthBlocks(){return this.lengthBlocks;}
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

      // Dense samples make the water surface and the carved channel continuous
      // along long A* straights, not merely at their distant turning points.
      List<Double> px=new ArrayList<>(),pz=new ArrayList<>();
      px.add(inputX[0]);pz.add(inputZ[0]);
      double totalLength=0.0;
      for(int i=1;i<inputX.length;i++) {
         double dx=inputX[i]-inputX[i-1],dz=inputZ[i]-inputZ[i-1];
         double length=Math.hypot(dx,dz);
         if(length<1.0e-6)continue;
         totalLength+=length;
         int pieces=Math.max(1,(int)Math.ceil(length/20.0));
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

      int drop=sourceY-receiverY;
      int cascadeCount=drop>=6&&totalLength>=180.0
              ?Math.min(5,Math.max(1,drop/4)):0;
      double[] centers=new double[cascadeCount];
      Noise2D noise=new Noise2D(seed^0x4341534341444553L);
      for(int c=0;c<cascadeCount;c++) {
         double base=0.14+0.72*(c+1.0)/(cascadeCount+1.0);
         double jitter=noise.noise(c*7.13+1.9,seed*0.0000001+2.7)*0.035;
         centers[c]=Math.max(0.12,Math.min(0.88,base+jitter));
      }

      double[] weights=new double[n-1],cumulative=new double[n],distanceAlong=new double[n];
      for(int i=1;i<n;i++)
         distanceAlong[i]=distanceAlong[i-1]+Math.hypot(x[i]-x[i-1],z[i]-z[i-1]);
      double totalWeight=0.0;
      for(int i=0;i<n-1;i++) {
         double mx=(x[i]+x[i+1])*0.5,mz=(z[i]+z[i+1])*0.5;
         double length=Math.max(1.0,Math.hypot(x[i+1]-x[i],z[i+1]-z[i]));
         double progress=totalLength<=1.0e-9?0.0:
                 (distanceAlong[i]+length*0.5)/totalLength;
         double broad=noise.noise(mx/1750.0+0.37,mz/1750.0-0.61);
         double riffle=noise.noise(mx/430.0-1.13,mz/430.0+0.79);
         double terrainCue=0.0;
         if(terrain!=null) {
            int tx=(int)Math.floor(mx),tz=(int)Math.floor(mz);
            int here=terrain.at(tx,tz);
            int next=terrain.at((int)Math.floor(x[i+1]),(int)Math.floor(z[i+1]));
            terrainCue=Math.max(-1.0,Math.min(1.0,(next-here)/12.0));
         }
         double modulation=0.88+0.11*broad+0.07*riffle+0.05*terrainCue;
         double nearestCascade=Double.POSITIVE_INFINITY;
         for(double center:centers)nearestCascade=Math.min(nearestCascade,Math.abs(progress-center));
         if(cascadeCount>0) {
            double radius=Math.max(0.009,Math.min(0.038,22.0/Math.max(1.0,totalLength)));
            if(nearestCascade<radius) {
               double t=1.0-nearestCascade/radius;
               // Concentrate the vertical change into a short cascade lip.
               modulation*=1.0+9.0*t*t*(3.0-2.0*t);
            } else {
               // Long quiet reaches between drops behave like pools and slow
               // riffles. The remaining total fall is delivered at the next lip.
               modulation*=0.22;
            }
         }
         weights[i]=length*Math.max(0.45,Math.min(18.0,modulation));
         totalWeight+=weights[i];
         cumulative[i+1]=totalWeight;
      }

      double[] levels=new double[n];
      for(int i=0;i<n;i++) {
         double t=totalWeight<=1.0e-9?i/(double)(n-1):cumulative[i]/totalWeight;
         levels[i]=sourceY-drop*t;
      }
      levels[0]=sourceY;
      levels[n-1]=receiverY;
      for(int i=1;i<n;i++)levels[i]=Math.min(levels[i],levels[i-1]);
      levels[n-1]=receiverY;

      // A cascade is a short reach that loses two or more whole water levels.
      // Report actual cascades after rounding, not the number merely proposed.
      int actualCascades=0;
      for(int i=1;i<n;i++)
         if(Math.floor(levels[i-1])-Math.floor(levels[i])>=2.0)actualCascades++;
      return new Profile(x,z,levels,actualCascades,totalLength);
   }

}
