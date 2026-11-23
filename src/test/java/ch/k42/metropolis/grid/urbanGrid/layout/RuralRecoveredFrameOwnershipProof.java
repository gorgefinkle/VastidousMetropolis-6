package ch.k42.metropolis.grid.urbanGrid.layout;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import java.lang.reflect.Method;
/** Classify reclaimed frontage using zoned geometric owners before Morphomos. */
public final class RuralRecoveredFrameOwnershipProof {
 static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void run(boolean urban) throws Exception {
   long seed=9001;
   WorldLayout layout=new WorldLayout(new HighwayNetwork(seed,230,68,.5,1,0,20,null,9),seed,7,14,99);
   layout.setZoner((bx,bz,sx,sz,a,b,c,d)->{
     ContextType[] out=new ContextType[bx.length];
     for(int i=0;i<out.length;i++)out[i]=urban&&bx[i]==12?ContextType.RESIDENTIAL:ContextType.FARM;
     return out;
   });
   WorldLayout.Domain dom=new WorldLayout.Domain(1,0,0,24,24,64);
   WorldLayout.Rect a=new WorldLayout.Rect(4,4,10,10),b=new WorldLayout.Rect(12,4,18,10);
   dom.blocks.add(a);dom.blocks.add(b);
   for(WorldLayout.Rect r:dom.blocks)for(int x=r.minX;x<=r.maxX;x++)for(int z=r.minZ;z<=r.maxZ;z++)dom.kind[x][z]=(byte)WorldLayout.BLOCK;
   for(int z=4;z<=10;z++){
     dom.kind[11][z]=(byte)WorldLayout.STREET;
     dom.urbanBoundaryRoads.add(key(11,z));
     dom.provisionalRecoveredFrameRoads.add(key(11,z));
   }
   // A separate hard protection, not originating with the recovered frame, survives classification.
   dom.urbanBoundaryRoads.add(key(1,1));
   // Register the synthetic domain so PlanRegions observes exactly these
   // real final contexts, instead of generating unrelated world domains.
   java.lang.reflect.Field field=WorldLayout.class.getDeclaredField("byCell");field.setAccessible(true);
   @SuppressWarnings("unchecked")
   java.util.Map<Long,WorldLayout.Domain> domains=(java.util.Map<Long,WorldLayout.Domain>)field.get(layout);
   domains.put(key(0,0),dom);
   Method method=WorldLayout.class.getDeclaredMethod("zoneBlocks",WorldLayout.Domain.class);method.setAccessible(true);
   method.invoke(layout,dom);
   for(int z=4;z<=10;z++){
     check(dom.kind[11][z]==WorldLayout.STREET,"zoning modified road geometry");
     check(dom.urbanBoundaryRoads.contains(key(11,z))==urban,"rural seam mislabeled urban="+urban);
   }
   check(dom.urbanBoundaryRoads.contains(key(1,1)),"unrelated hard protection lost");
   boolean dissolves=new PlanRegions(layout).dissolvedAt(11,7);
   check(dissolves!=urban,"Morphomos seam dissolve mismatch urban="+urban+" got="+dissolves);
   check(dom.provisionalRecoveredFrameRoads.isEmpty(),"temporary provenance not discharged");
 }
 public static void main(String[] args) throws Exception {run(false);run(true);System.out.println("PASS rural/urban recovered frontage classification");}
}
