package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.LinkedHashMap;
import java.util.Map;

/** Per-world-plan endpoint registry; conflicting coordinates for one identity are rejected. */
public final class HydrologyConvergenceRegistry {
   private static final int MAX_ENDPOINTS=65536;
   private final Map<Identity,HydrologyConvergenceKey> endpoints=new LinkedHashMap<Identity,HydrologyConvergenceKey>(256,0.75f,true){
      @Override protected boolean removeEldestEntry(Map.Entry<Identity,HydrologyConvergenceKey> e){return size()>MAX_ENDPOINTS;}
   };
   public synchronized HydrologyConvergenceKey register(HydrologyConvergenceKey key) {
      if(key==null)throw new IllegalArgumentException("Convergence key is required");
      Identity id=new Identity(key);HydrologyConvergenceKey prior=endpoints.get(id);
      if(prior!=null&&!prior.equals(key))throw new IllegalStateException("Conflicting coordinates for hydrology endpoint "+id);
      if(prior==null)endpoints.put(id,key);return prior==null?key:prior;
   }
   public synchronized HydrologyConvergenceKey resolve(HydrologyConvergenceKey key){return key==null?null:endpoints.get(new Identity(key));}
   public synchronized int size(){return endpoints.size();}
   private static final class Identity {
      final HydrologyConvergenceKey.Receiver receiver;final long network,endpoint;
      Identity(HydrologyConvergenceKey k){receiver=k.receiver();network=k.networkId();endpoint=k.endpointId();}
      @Override public boolean equals(Object v){if(!(v instanceof Identity o))return false;return receiver==o.receiver&&network==o.network&&endpoint==o.endpoint;}
      @Override public int hashCode(){int h=receiver.hashCode();h=31*h+Long.hashCode(network);return 31*h+Long.hashCode(endpoint);}
      @Override public String toString(){return receiver+":"+Long.toUnsignedString(network,16)+":"+Long.toUnsignedString(endpoint,16);}
   }
}
