package ch.k42.metropolis.grid.urbanGrid.wild;

/** Stable identity for a real hydrology junction shared by both water networks. */
public final class HydrologyConvergenceKey implements Comparable<HydrologyConvergenceKey> {
   public enum Receiver { OCEAN, RIVER, CREEK }
   private final Receiver receiver;
   private final long networkId,endpointId;
   private final double blockX,blockZ;
   public HydrologyConvergenceKey(Receiver receiver,long networkId,long endpointId,double blockX,double blockZ) {
      if(receiver==null)throw new IllegalArgumentException("Convergence receiver is required");
      if(!Double.isFinite(blockX)||!Double.isFinite(blockZ))throw new IllegalArgumentException("Convergence coordinates must be finite");
      this.receiver=receiver;this.networkId=networkId;this.endpointId=endpointId;this.blockX=blockX;this.blockZ=blockZ;
   }
   public Receiver receiver(){return receiver;} public long networkId(){return networkId;}
   public long endpointId(){return endpointId;} public double blockX(){return blockX;} public double blockZ(){return blockZ;}
   public int chunkX(){return (int)Math.floor(blockX/16.0);} public int chunkZ(){return (int)Math.floor(blockZ/16.0);}
   public static long stableId(String value) {
      if(value==null)throw new IllegalArgumentException("Stable identifier text is required");
      long h=0xcbf29ce484222325L;
      for(int i=0;i<value.length();i++){h^=value.charAt(i);h*=0x100000001b3L;}
      h^=h>>>30;h*=0xbf58476d1ce4e5b9L;h^=h>>>27;h*=0x94d049bb133111ebL;return h^(h>>>31);
   }
   public static long chunkEndpointId(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
   @Override public int compareTo(HydrologyConvergenceKey o) {
      int c=receiver.compareTo(o.receiver);if(c!=0)return c;c=Long.compareUnsigned(networkId,o.networkId);if(c!=0)return c;
      c=Long.compareUnsigned(endpointId,o.endpointId);if(c!=0)return c;c=Double.compare(blockX,o.blockX);return c!=0?c:Double.compare(blockZ,o.blockZ);
   }
   @Override public boolean equals(Object v) {
      if(this==v)return true;if(!(v instanceof HydrologyConvergenceKey o))return false;
      return receiver==o.receiver&&networkId==o.networkId&&endpointId==o.endpointId&&Double.compare(blockX,o.blockX)==0&&Double.compare(blockZ,o.blockZ)==0;
   }
   @Override public int hashCode(){int h=receiver.hashCode();h=31*h+Long.hashCode(networkId);h=31*h+Long.hashCode(endpointId);h=31*h+Double.hashCode(blockX);return 31*h+Double.hashCode(blockZ);}
   @Override public String toString(){return receiver+":"+Long.toUnsignedString(networkId,16)+":"+Long.toUnsignedString(endpointId,16)+"@"+blockX+","+blockZ;}
}
