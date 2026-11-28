package ch.k42.metropolis.grid.urbanGrid.wild;

/** Regression for elevated lake outlets, pools, cascades and receiver continuity. */
public final class StreamWaterProfileProbe {
   public static void main(String[] args) {
      double[] routeX={0.0,1200.0};
      double[] routeZ={0.0,0.0};
      StreamWaterProfile.TerrainHeight ridge=(x,z)->
              104+(int)Math.round(17.0*Math.sin(x/135.0)+8.0*Math.sin(x/39.0));
      StreamWaterProfile.Profile high=StreamWaterProfile.create(
              routeX,routeZ,142,72,0x484947484C414B45L,ridge);
      StreamWaterProfile.Profile repeat=StreamWaterProfile.create(
              routeX,routeZ,142,72,0x484947484C414B45L,ridge);
      double[] levels=high.waterY();
      double[] repeatLevels=repeat.waterY();
      if(high.pointCount()<50||high.lengthBlocks()<1199.0)
         throw new AssertionError("Long outlet was not densely sampled for continuous carving");
      if(levels[0]!=142.0||levels[levels.length-1]!=72.0)
         throw new AssertionError("Elevated lake outlet did not preserve exact source and receiver levels");
      if(high.cascadeCount()<3)
         throw new AssertionError("Higher lake did not produce multiple visible cascades: "+high.cascadeCount());
      int poolSegments=0,sharpDrops=0;
      for(int i=1;i<levels.length;i++) {
         if(levels[i]>levels[i-1])
            throw new AssertionError("Multi-level stream profile rises downstream at sample "+i);
         double fall=levels[i-1]-levels[i];
         if(fall<0.15)poolSegments++;
         if(fall>=2.0)sharpDrops++;
         if(Math.abs(levels[i]-repeatLevels[i])>1.0e-12)
            throw new AssertionError("Stream water profile is not deterministic");
      }
      if(poolSegments<3||sharpDrops<3)
         throw new AssertionError("Profile lacks both slow pools and concentrated cascade drops: pools="
                 +poolSegments+" drops="+sharpDrops);

      // A low-relief connection remains a quiet stream instead of receiving
      // artificial waterfalls merely because all courses share one renderer.
      StreamWaterProfile.Profile gentle=StreamWaterProfile.create(
              new double[]{0.0,420.0},new double[]{0.0,65.0},91,89,0x47454E544C45L,
              (x,z)->110);
      if(gentle.waterY()[0]!=91.0||gentle.waterY()[gentle.pointCount()-1]!=89.0)
         throw new AssertionError("Gentle profile lost its endpoint water levels");
      if(gentle.cascadeCount()!=0)
         throw new AssertionError("A two-block fall should not be inflated into a waterfall");

      boolean uphillRejected=false;
      try {
         StreamWaterProfile.create(routeX,routeZ,72,74,7L,ridge);
      } catch(IllegalArgumentException expected) {
         uphillRejected=true;
      }
      if(!uphillRejected)throw new AssertionError("An uphill receiver was accepted as a downstream junction");
      System.out.printf(java.util.Locale.ROOT,
              "StreamWaterProfileProbe PASS points=%d sourceY=142 receiverY=72 cascades=%d pools=%d sharpDrops=%d%n",
              high.pointCount(),high.cascadeCount(),poolSegments,sharpDrops);
   }
}
