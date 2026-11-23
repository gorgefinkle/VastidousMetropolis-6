package ch.k42.metropolis.grid.urbanGrid.river;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;

/**
 * Bounded, meandering, world-block tributary geometry reconstructed from R30ea.
 * No chunk caches; deterministic from the seed and the endpoint coordinates.
 */
public final class BoundedRiverSegment extends RiverProvider {
   private static final double CHUNK = 16.0;
   private final boolean axisZ;
   private final double originAlong,endAlong,lowAlong,highAlong,originAcross,endAcross;
   private final double bucketWidthBlocks,bankMarginBlocks;
   private final long salt;
   private final int lowChunk,highChunk;
   private double[] negativeBank,positiveBank;

   public BoundedRiverSegment(long seed,boolean zBound,double x0,double z0,double x1,double z1,
                              double widthBlocks,double bankBlocks) {
      super(seed,zBound,Double.NaN,Double.NaN);
      this.axisZ=zBound;
      this.originAlong=zBound?z0:x0;
      this.endAlong=zBound?z1:x1;
      this.originAcross=zBound?x0:z0;
      this.endAcross=zBound?x1:z1;
      if(!Double.isFinite(originAlong)||!Double.isFinite(endAlong)||!Double.isFinite(originAcross)||!Double.isFinite(endAcross))
         throw new IllegalArgumentException("Tributary endpoints must be finite world-block coordinates");
      double run=Math.abs(endAlong-originAlong);
      if(run<16.0 || Math.abs(endAcross-originAcross)>run*0.6)
         throw new IllegalArgumentException("Tributary needs a clear cardinal-axis run of at least one chunk");
      if(!Double.isFinite(widthBlocks)||widthBlocks<2.0||widthBlocks>64.0)
         throw new IllegalArgumentException("Invalid bounded river width bucket");
      if(!Double.isFinite(bankBlocks)||bankBlocks<0.0||bankBlocks>64.0)
         throw new IllegalArgumentException("Invalid bounded river bank margin");
      this.lowAlong=Math.min(originAlong,endAlong);
      this.highAlong=Math.max(originAlong,endAlong);
      this.lowChunk=(int)Math.floor(lowAlong/CHUNK);
      this.highChunk=(int)Math.floor(highAlong/CHUNK);
      this.bucketWidthBlocks=widthBlocks;
      this.bankMarginBlocks=bankBlocks;
      this.salt=seed^4779812634650497612L;
   }
   public double originAlongBlock(){return originAlong;}
   public double endpointAlongBlock(){return endAlong;}
   public double originAcrossBlock(){return originAcross;}
   public double endpointAcrossBlock(){return endAcross;}
   public double widthBucketBlocks(){return bucketWidthBlocks;}
   public double bankMarginBlocks(){return bankMarginBlocks;}
   /** Identical accepted river path across separate tile-carrier instances.
    * Include the seed-derived meander salt: same endpoints without the same
    * salt are NOT the same wet channel and must never borrow a river parcel.
    */
   public boolean sameImmutableCourse(BoundedRiverSegment other) {
      return other != null && axisZ == other.axisZ && salt == other.salt
              && Double.compare(originAlong, other.originAlong) == 0
              && Double.compare(endAlong, other.endAlong) == 0
              && Double.compare(originAcross, other.originAcross) == 0
              && Double.compare(endAcross, other.endAcross) == 0
              && Double.compare(bucketWidthBlocks, other.bucketWidthBlocks) == 0
              && Double.compare(bankMarginBlocks, other.bankMarginBlocks) == 0;
   }
   private boolean inBounds(double at){return at>=lowAlong-1.0e-8&&at<=highAlong+1.0e-8;}
   @Override public boolean isZBound(){return axisZ;}
   @Override public boolean isCreek(){return true;}

   private static double smoothNoise(long seed,double at) {
      long lo=(long)Math.floor(at);
      double t=at-Math.floor(at);
      t=t*t*t*(t*(t*6.0-15.0)+10.0);
      double a=noiseAt(seed,lo),b=noiseAt(seed,lo+1);
      return a+(b-a)*t;
   }
   private static double noiseAt(long seed,long index) {
      long h=seed^(index*-7046029254386353131L);
      h=(h^(h>>>30))*-4658895280553007687L;
      h=(h^(h>>>27))*-7723592293110705685L;
      h=h^(h>>>31);
      return ((h>>>11)*1.1102230246251565E-16)*2.0-1.0;
   }
   @Override public double spineBlock(double along) {
      if(!isEnabled()||!inBounds(along))return Double.NaN;
      double t=Math.max(0.0,Math.min(1.0,(along-originAlong)/(endAlong-originAlong)));
      double across=originAcross+(endAcross-originAcross)*t;
      double window=Math.pow(Math.sin(Math.PI*t),2.0);
      double run=Math.abs(endAlong-originAlong);
      double bend=Math.min(run*0.115,Math.max(96.0,bucketWidthBlocks*3.5));
      double phase=((salt>>>16)&65535L)*9.587379924285257E-5;
      double harmonics=2.1+((salt>>>40)&255L)*0.001372549019607843;
      double oscillation=Math.sin(t*harmonics*Math.PI*2.0+phase);
      double noise=smoothNoise(salt^4919430807351479621L,t*6.0+13.27);
      return across+window*bend*(oscillation+0.18*noise);
   }
   private boolean intersectsAlongChunk(int at) {
      double start=at*CHUNK;
      return start<highAlong && start+CHUNK>lowAlong;
   }
   private double sampledSpine(int alongChunk){
      if(!intersectsAlongChunk(alongChunk))return Double.NaN;
      return spineBlock(Math.max(lowAlong,Math.min(highAlong,alongChunk*CHUNK+8.0)));
   }
   @Override public double spineAcross(int at){return sampledSpine(at)/CHUNK;}
   @Override public double spineZ(int at){return spineAcross(at);}
   @Override public double spineZBlock(double at){return spineBlock(at);}
   @Override public double spineXBlock(double at){return spineBlock(at);}
   @Override public double distanceFromCenterBlock(double x,double z) {
      double along=axisZ?z:x;
      double across=spineBlock(along);
      return Double.isNaN(across)?Double.NaN:(axisZ?x:z)-across;
   }
   @Override public double distanceFromCenter(int x,int z) {
      return distanceFromCenterBlock(x*CHUNK+8.0,z*CHUNK+8.0)/CHUNK;
   }
   @Override public boolean isRiver(int x,int z) {
      int at=axisZ?z:x;
      double across=(axisZ?x:z)*CHUNK+8.0-sampledSpine(at);
      return isEnabled()&&Double.isFinite(across)&&Math.abs(across)<=bucketWidthBlocks/2.0+8.0;
   }
   @Override public boolean isRiverOrBank(int x,int z) {
      int at=axisZ?z:x;
      double across=(axisZ?x:z)*CHUNK+8.0-sampledSpine(at);
      return isEnabled()&&Double.isFinite(across)&&Math.abs(across)<=bucketWidthBlocks/2.0+bankMarginBlocks+8.0;
   }
   @Override public int widthAt(int at){return (int)Math.ceil(bucketWidthBlocks/CHUNK);}
   @Override public double widthAtBlock(double at){return isEnabled()&&inBounds(at)?bucketWidthBlocks:Double.NaN;}
   @Override public double getHalfWidthBlocks(){return bucketWidthBlocks/2.0;}
   @Override public double getEnvelopeHalfWidth(){return (bucketWidthBlocks/2.0+bankMarginBlocks)/CHUNK+1.0;}
   @Override public double getFallbackBankMarginBlocks(){return bankMarginBlocks;}
   @Override public double getRampMaxRunBlocks(){return bankMarginBlocks;}
   @Override public int getAlongLoChunk(){return lowChunk;}
   @Override public int getAlongHiChunk(){return highChunk;}
   @Override public int getBankEdgeSampleCount(){return highChunk-lowChunk+1;}
   @Override public double[] bankEdgeSamplePoint(int index) {
      if(index<0||index>=getBankEdgeSampleCount())throw new IndexOutOfBoundsException();
      double along=Math.max(lowAlong,Math.min(highAlong,(lowChunk+index)*CHUNK+8.0));
      double across=spineBlock(along);
      return axisZ?new double[]{across,along}:new double[]{along,across};
   }
   @Override public double[] bankEdgeScanPoint(int index,int offset) {
      double[] p=bankEdgeSamplePoint(index);
      if(axisZ)p[0]+=offset*CHUNK;else p[1]+=offset*CHUNK;
      return p;
   }
   @Override public void recordBankEdges(double[] negative,double[] positive) {
      if(negative==null||positive==null||negative.length!=getBankEdgeSampleCount()||positive.length!=getBankEdgeSampleCount())
         throw new IllegalArgumentException("Bank-edge arrays must match bounded river's chunk span");
      negativeBank=negative.clone();positiveBank=positive.clone();
   }
   @Override public double getBankEdgeBlockAt(UrbanGrid grid,double x,double z,boolean negative) {
      double[] data=negative?negativeBank:positiveBank;
      if(data==null)return Double.NaN;
      double along=axisZ?z:x;
      int i=(int)Math.floor(along/CHUNK)-lowChunk;
      return i<0||i>=data.length?Double.NaN:data[i];
   }
   private double edgeAt(int alongChunk,boolean positive) {
      if(axisZ||!isEnabled())return Double.NaN;
      double from=Math.max(lowAlong,alongChunk*CHUNK),to=Math.min(highAlong,(alongChunk+1.0)*CHUNK);
      if(from>to)return Double.NaN;
      double edge=positive?-Double.MAX_VALUE:Double.MAX_VALUE;
      for(int n=0;n<=16;n++) {
         double along=from+(to-from)*n/16.0;
         double x=spineBlock(along)+(positive?1.0:-1.0)*bucketWidthBlocks/2.0;
         edge=positive?Math.max(edge,x):Math.min(edge,x);
      }
      return edge;
   }
   @Override public double waterEdgeNorthBlock(int at){return edgeAt(at,false);}
   @Override public double waterEdgeSouthBlock(int at){return edgeAt(at,true);}
   @Override public boolean isEastWestOriented(int at){return !axisZ;}
}
