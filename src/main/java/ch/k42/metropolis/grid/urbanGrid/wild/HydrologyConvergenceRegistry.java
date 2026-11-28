package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/** Per-world-plan endpoint registry; conflicting coordinates for one identity are rejected. */
public final class HydrologyConvergenceRegistry {
   private static final int MAX_ENDPOINTS=65536;
   private final Map<CellIdentity,TreeSet<HydrologyConvergenceKey>> byCell=new HashMap<>();
   private final Map<Identity,CellIdentity> cellByIdentity=new HashMap<>();
   private final Map<Identity,HydrologyConvergenceKey> endpoints=
           new LinkedHashMap<Identity,HydrologyConvergenceKey>(256,0.75f,true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Identity,HydrologyConvergenceKey> e) {
         if(size()<=MAX_ENDPOINTS)return false;
         Identity identity=e.getKey();
         HydrologyConvergenceKey key=e.getValue();
         CellIdentity cell=cellByIdentity.remove(identity);
         if(cell!=null) {
            TreeSet<HydrologyConvergenceKey> keys=byCell.get(cell);
            if(keys!=null) {
               keys.remove(key);
               if(keys.isEmpty())byCell.remove(cell);
            }
         }
         return true;
      }
   };

   public synchronized HydrologyConvergenceKey register(HydrologyConvergenceKey key) {
      if(key==null)throw new IllegalArgumentException("Convergence key is required");
      Identity id=new Identity(key);
      HydrologyConvergenceKey prior=endpoints.get(id);
      if(prior!=null&&!prior.equals(key))
         throw new IllegalStateException("Conflicting coordinates for hydrology endpoint "+id);
      if(prior!=null)return prior;

      CellIdentity cell=new CellIdentity(key);
      byCell.computeIfAbsent(cell,ignored->new TreeSet<>()).add(key);
      cellByIdentity.put(id,cell);
      endpoints.put(id,key);
      return key;
   }

   public synchronized HydrologyConvergenceKey resolve(HydrologyConvergenceKey key) {
      return key==null?null:endpoints.get(new Identity(key));
   }
   public synchronized int size(){return endpoints.size();}

   /** Stable convergence key owned by this receiver at the requested chunk. */
   public synchronized HydrologyConvergenceKey findAt(
           HydrologyConvergenceKey.Receiver receiver,int chunkX,int chunkZ) {
      if(receiver==null)return null;
      TreeSet<HydrologyConvergenceKey> keys=byCell.get(new CellIdentity(receiver,chunkX,chunkZ));
      if(keys==null||keys.isEmpty())return null;
      // A live receiver lookup also refreshes this endpoint's LRU lifetime.
      return endpoints.get(new Identity(keys.first()));
   }

   private static final class CellIdentity {
      final HydrologyConvergenceKey.Receiver receiver;
      final int x,z;
      CellIdentity(HydrologyConvergenceKey key){this(key.receiver(),key.chunkX(),key.chunkZ());}
      CellIdentity(HydrologyConvergenceKey.Receiver receiver,int x,int z){this.receiver=receiver;this.x=x;this.z=z;}
      @Override public boolean equals(Object value) {
         if(!(value instanceof CellIdentity other))return false;
         return receiver==other.receiver&&x==other.x&&z==other.z;
      }
      @Override public int hashCode(){int h=receiver.hashCode();h=31*h+x;return 31*h+z;}
   }

   private static final class Identity {
      final HydrologyConvergenceKey.Receiver receiver;final long network,endpoint;
      Identity(HydrologyConvergenceKey k){receiver=k.receiver();network=k.networkId();endpoint=k.endpointId();}
      @Override public boolean equals(Object v) {
         if(!(v instanceof Identity o))return false;
         return receiver==o.receiver&&network==o.network&&endpoint==o.endpoint;
      }
      @Override public int hashCode(){int h=receiver.hashCode();h=31*h+Long.hashCode(network);return 31*h+Long.hashCode(endpoint);}
      @Override public String toString(){return receiver+":"+Long.toUnsignedString(network,16)+":"+Long.toUnsignedString(endpoint,16);}
   }
}
