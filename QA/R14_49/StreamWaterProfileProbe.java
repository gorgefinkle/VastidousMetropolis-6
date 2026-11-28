package ch.k42.metropolis.grid.urbanGrid.wild;

/** Regression for smooth stream elevation changes from high lakes to low receivers. */
public final class StreamWaterProfileProbe {
   public static void main(String[] args) {
      double[] routeX={0.0,1200.0};
      double[] routeZ={0.0,0.0};
      StreamWaterProfile.TerrainHeight varied=(x,z)->
              104+(int)Math.round(17.0*Math.sin(x/135.0)+8.0*Math.sin(x/39.0));
      StreamWaterProfile.Profile high=StreamWaterProfile.create(
              routeX,routeZ,160,72,0x484947484C414B45L,varied);
      StreamWaterProfile.Profile repeat=StreamWaterProfile.create(
              routeX,routeZ,160,72,0x484947484C414B45L,varied);
      double[] levels=high.waterY(),again=repeat.waterY();
      if(high.pointCount()<90||high.lengthBlocks()<1199.0)
         throw new AssertionError("Long stream was not densely sampled for continuous carving");
      if(levels[0]!=160.0||levels[levels.length-1]!=72.0||high.totalDrop()!=88.0)
         throw new AssertionError("High lake source or low receiver elevation was not preserved");
      if(high.maximumSampleDrop()>2.5)
         throw new AssertionError("Large elevation change was not distributed smoothly: max sample drop="
                 +high.maximumSampleDrop());
      boolean hasGradientVariation=false;
      double priorDrop=-1.0;
      for(int i=1;i<levels.length;i++) {
         double fall=levels[i-1]-levels[i];
         if(fall<0.0)throw new AssertionError("Stream water surface rises downstream at sample "+i);
         if(Math.abs(fall-(again[i-1]-again[i]))>1.0e-12)
            throw new AssertionError("Stream elevation profile is not deterministic");
         if(priorDrop>=0.0&&Math.abs(fall-priorDrop)>0.015)hasGradientVariation=true;
         priorDrop=fall;
      }
      if(!hasGradientVariation)
         throw new AssertionError("Stream grade has no natural variation along its length");

      // Smooth terrain receives a smooth descent; a genuine sharp existing
      // drop can attract a local waterfall without prescribing its location.
      StreamWaterProfile.Profile smoothGround=StreamWaterProfile.create(
              routeX,routeZ,160,72,0x464C415452495645L,(x,z)->110);
      StreamWaterProfile.Profile naturalCliff=StreamWaterProfile.create(
              routeX,routeZ,160,72,0x464C415452495645L,(x,z)->x<600?140:90);
      if(smoothGround.maximumSampleDrop()>1.0)
         throw new AssertionError("Smooth terrain produced an artificial waterfall");
      if(naturalCliff.maximumSampleDrop()<smoothGround.maximumSampleDrop()*3.0)
         throw new AssertionError("A natural terrain break did not influence the water drop: smooth="
                 +smoothGround.maximumSampleDrop()+" cliff="+naturalCliff.maximumSampleDrop());
      double[] cliffLevels=naturalCliff.waterY();
      int cliffIndex=50;
      double dropAtCliff=cliffLevels[cliffIndex-1]-cliffLevels[cliffIndex];
      double dropAway=cliffLevels[20]-cliffLevels[21];
      if(dropAtCliff<=dropAway)
         throw new AssertionError("Waterfall response was not concentrated at the pre-existing terrain break");

      StreamWaterProfile.Profile gentle=StreamWaterProfile.create(
              new double[]{0.0,420.0},new double[]{0.0,65.0},91,89,0x47454E544C45L,(x,z)->110);
      if(gentle.waterY()[0]!=91.0||gentle.waterY()[gentle.pointCount()-1]!=89.0
              ||gentle.totalDrop()!=2.0||gentle.maximumSampleDrop()>0.25)
         throw new AssertionError("Small elevation changes should remain gentle and continuous");

      boolean uphillRejected=false;
      try {
         StreamWaterProfile.create(routeX,routeZ,72,74,7L,varied);
      } catch(IllegalArgumentException expected) {uphillRejected=true;}
      if(!uphillRejected)throw new AssertionError("A receiver above the source was accepted downstream");

      System.out.printf(java.util.Locale.ROOT,
              "StreamWaterProfileProbe PASS points=%d sourceY=160 receiverY=72 totalDrop=88 maxSampleDrop=%.3f%n",
              high.pointCount(),high.maximumSampleDrop());
   }
}
