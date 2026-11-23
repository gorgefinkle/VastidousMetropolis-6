package ch.k42.metropolis.grid.urbanGrid.layout;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.Arrays;
public final class R30baRegionOwnershipProbe {
  public static void main(String[] args) {
    long seed = args.length>0?Long.parseLong(args[0]):12345L;
    HighwayNetwork h = new HighwayNetwork(seed,230,68,.5,1,0,20,null,9);
    WorldLayout w = new WorldLayout(h,seed,7,14,99,18,27,(x,z)->false);
    w.setBlockSlack(3);
    w.setZoner((bx,bz,sx,sz,minx,minz,maxx,maxz)->{ContextType[] a=new ContextType[bx.length]; Arrays.fill(a,ContextType.FARM);return a;});
    int nDomains=0, nStreets=0, nRoots=0,foreign=0, failed=0;
    PlanRegions p=new PlanRegions(w);
    int[][] centers={{-5,1},{32,38},{-160,-30},{-82,-95},{120,70}};
    for(int[] c:centers){
      WorldLayout.Domain d=w.domainAt(c[0],c[1]);
      ++nDomains;
      for(int x=d.minX;x<=d.maxX;x++)for(int z=d.minZ;z<=d.maxZ;z++){
        if(!w.ownedByDomain(d,x,z)){foreign++;continue;}
        int k=w.kindAt(x,z);
        if(k!=WorldLayout.STREET && k!=WorldLayout.BLOCK)continue;
        try {
          if(k==WorldLayout.STREET)nStreets++;
          if(p.regionFor(x,z)!=null)nRoots++;
        }catch(NullPointerException ex){failed++; System.err.println("NPE domain="+d.id+" point="+x+","+z);ex.printStackTrace();throw ex;}
      }
    }
    System.out.printf("seed=%d domains=%d streets=%d regionHits=%d bboxForeign=%d failures=%d%n",seed,nDomains,nStreets,nRoots,foreign,failed);
  }
}
