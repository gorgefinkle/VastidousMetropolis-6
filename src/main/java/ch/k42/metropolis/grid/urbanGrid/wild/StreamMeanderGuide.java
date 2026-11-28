package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * Deterministic, broad-scale lateral guidance for one wilderness stream.
 * The route planner pays a small cost for leaving this smooth winding
 * centreline, while retaining the freedom to detour around reserved terrain.
 */
public final class StreamMeanderGuide {
   private final double startX,startZ,unitX,unitZ,normalX,normalZ,length,amplitude,waves,phase;
   private final Noise2D noise;

   public StreamMeanderGuide(long seed,int startX,int startZ,int endX,int endZ) {
      this.startX=startX;
      this.startZ=startZ;
      double dx=endX-startX,dz=endZ-startZ;
      this.length=Math.hypot(dx,dz);
      if(this.length<1.0e-6)throw new IllegalArgumentException("A stream meander needs distinct endpoints");
      this.unitX=dx/this.length;
      this.unitZ=dz/this.length;
      this.normalX=-this.unitZ;
      this.normalZ=this.unitX;
      this.amplitude=Math.min(9.0,Math.max(3.0,this.length*0.035));
      this.waves=Math.min(7.0,Math.max(2.0,this.length/56.0));
      this.noise=new Noise2D(seed);
      long phaseBits=Noise2D.mix(seed^0x4D45414E44455231L);
      this.phase=((phaseBits>>>11)*0x1.0p-53)*240.0-120.0;
   }

   /** Lateral displacement from the direct line, in chunks. */
   public double lateralOffsetChunks(double progress) {
      double t=clamp(progress,0.0,1.0);
      if(t<=0.0||t>=1.0)return 0.0;
      double fadeIn=smoothStep(0.0,0.14,t);
      double fadeOut=smoothStep(0.0,0.14,1.0-t);
      double broad=this.noise.noise(t*this.waves+this.phase,17.31+this.phase*0.37);
      double secondary=this.noise.noise(t*this.waves*1.87+31.7-this.phase*0.11,
              -43.2+this.phase*0.23);
      double shape=0.74*broad+0.26*secondary;
      return this.amplitude*fadeIn*fadeOut*shape;
   }

   /** Signed distance from the point to the preferred meandering centreline, in chunks. */
   public double lateralErrorChunks(double x,double z) {
      double dx=x-this.startX,dz=z-this.startZ;
      double progress=clamp((dx*this.unitX+dz*this.unitZ)/this.length,0.0,1.0);
      double lateral=dx*this.normalX+dz*this.normalZ;
      return lateral-this.lateralOffsetChunks(progress);
   }

   /**
    * Non-negative A* cell cost. It is zero near the guide and rises smoothly
    * with lateral departure, leaving hard terrain/ownership checks authoritative.
    */
   public int stepCost(int chunkX,int chunkZ) {
      double deviation=Math.max(0.0,Math.abs(lateralErrorChunks(chunkX,chunkZ))-0.8);
      return (int)Math.min(5.0,Math.round(deviation*0.55));
   }

   public double centerXAt(double progress) {
      double t=clamp(progress,0.0,1.0);
      return this.startX+this.unitX*this.length*t+this.normalX*lateralOffsetChunks(t);
   }
   public double centerZAt(double progress) {
      double t=clamp(progress,0.0,1.0);
      return this.startZ+this.unitZ*this.length*t+this.normalZ*lateralOffsetChunks(t);
   }

   private static double smoothStep(double a,double b,double value) {
      double t=clamp((value-a)/(b-a),0.0,1.0);
      return t*t*(3.0-2.0*t);
   }
   private static double clamp(double value,double min,double max) {
      return Math.max(min,Math.min(max,value));
   }
}
