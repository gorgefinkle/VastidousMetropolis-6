package ch.k42.metropolis.grid.urbanGrid.layout;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import java.lang.reflect.*;
import java.util.*;
/** Road ownership proof: CLOSED means not a possible building, not ineligible as a dry road junction. */
public final class PeelerStrandedRoadProof {
 public static void main(String[] args) throws Exception {
   long seed=9001;
   WorldLayout parent=new WorldLayout(new HighwayNetwork(seed,230,68,.5,1,0,20,null,9),seed,7,14,99);
   WorldLayout.Domain d=new WorldLayout.Domain(1,0,0,18,18,64);
   boolean[][] free=new boolean[19][19];for(boolean[] row:free)Arrays.fill(row,true);
   Class<?> cl=Class.forName("ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout$Peeler");
   Constructor<?> ctor=cl.getDeclaredConstructor(WorldLayout.class,WorldLayout.Domain.class,boolean[][].class,Random.class);
   ctor.setAccessible(true);Object p=ctor.newInstance(parent,d,free,new Random(3));
   Field cF=cl.getDeclaredField("c");cF.setAccessible(true);byte[][] c=(byte[][])cF.get(p);
   // A dry cell already ruled out as a buildable rectangle; its 4 adjacent frontages remain available.
   c[3][3]=7; // Peeler.CLOSEDC
   Method frame=cl.getDeclaredMethod("frame",int.class,int.class,int.class,int.class,List.class);
   frame.setAccessible(true);List<int[]> made=new ArrayList<>();frame.invoke(p,4,4,7,7,made);
   Method complete=cl.getDeclaredMethod("completeFrame",int.class,int.class,int.class,int.class);complete.setAccessible(true);
   boolean accepted=(boolean)complete.invoke(p,4,4,7,7);
   System.out.println("cornerOwner="+c[3][3]+" frameApproved="+accepted);
   if(c[3][3]!=2||!accepted)throw new AssertionError("dry stranded junction still unclaimed");
 }
}
