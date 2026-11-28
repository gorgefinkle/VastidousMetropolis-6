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
      // Spatial lookup remains deterministic when distinct networks terminate
      // in the same receiver chunk, independent of insertion order.
      HydrologyConvergenceRegistry spatial=new HydrologyConvergenceRegistry();
      HydrologyConvergenceKey later=new HydrologyConvergenceKey(
              HydrologyConvergenceKey.Receiver.RIVER,99L,20L,160.0,320.0);
      HydrologyConvergenceKey earlier=new HydrologyConvergenceKey(
              HydrologyConvergenceKey.Receiver.RIVER,11L,10L,160.0,320.0);
      spatial.register(later);
      spatial.register(earlier);
      HydrologyConvergenceKey expected=earlier.compareTo(later)<0?earlier:later;
      if(!expected.equals(spatial.findAt(HydrologyConvergenceKey.Receiver.RIVER,10,20)))
         throw new AssertionError("Spatial endpoint index is not deterministic for shared receiver chunks");
      if(spatial.findAt(HydrologyConvergenceKey.Receiver.OCEAN,10,20)!=null)
         throw new AssertionError("Spatial endpoint index leaked across receiver types");
      System.out.println("HydrologyConvergenceRegistryProbe PASS endpoints="+registry.size());
   }
}
