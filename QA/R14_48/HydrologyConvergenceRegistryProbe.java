package ch.k42.metropolis.grid.urbanGrid.wild;

/** Deterministic endpoint identity, idempotent registration and conflict regression. */
public final class HydrologyConvergenceRegistryProbe {
   public static void main(String[] args) {
      HydrologyConvergenceRegistry registry=new HydrologyConvergenceRegistry();
      long network=HydrologyConvergenceKey.stableId("probe:creek:alpha");
      HydrologyConvergenceKey first=new HydrologyConvergenceKey(HydrologyConvergenceKey.Receiver.CREEK,network,17L,120.0,344.0);
      HydrologyConvergenceKey same=new HydrologyConvergenceKey(HydrologyConvergenceKey.Receiver.CREEK,network,17L,120.0,344.0);
      if(registry.register(first)!=first)throw new AssertionError("First endpoint was not retained");
      if(!registry.register(same).equals(first))throw new AssertionError("Duplicate endpoint did not resolve canonically");
      if(registry.resolve(same)==null||registry.size()!=1)throw new AssertionError("Registry lookup/count mismatch");
      boolean rejected=false;
      try{registry.register(new HydrologyConvergenceKey(HydrologyConvergenceKey.Receiver.CREEK,network,17L,121.0,344.0));}
      catch(IllegalStateException expected){rejected=true;}
      if(!rejected)throw new AssertionError("Conflicting coordinates reused one endpoint identity");
      for(HydrologyConvergenceKey.Receiver receiver:HydrologyConvergenceKey.Receiver.values()){
         HydrologyConvergenceKey k=new HydrologyConvergenceKey(receiver,HydrologyConvergenceKey.stableId(receiver+":probe"),1L,16.0,32.0);
         if(!k.equals(registry.register(k)))throw new AssertionError("Receiver registration failed: "+receiver);
      }
      System.out.println("HydrologyConvergenceRegistryProbe PASS endpoints="+registry.size());
   }
}
