import ch.k42.metropolis.plugin.PluginConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.*;
import ch.k42.metropolis.grid.urbanGrid.layout.*;
import java.util.*;
/** Seed/plan map for direct reference-source bytecode comparison. */
public final class R111PlanDump {
 public static void main(String[] arg) throws Exception {
  R11RoadMatrixAudit.setup();
  long seed=Long.parseLong(arg[0]);int x0=Integer.parseInt(arg[1]),z0=Integer.parseInt(arg[2]),w=Integer.parseInt(arg[3]),h=Integer.parseInt(arg[4]);String mode=arg[5];
  WorldLayout l=WorldLayouts.forSeed(seed);
  l.setZoner((bx,bz,sx,sz,minX,minZ,maxX,maxZ)->{
   ContextType[] o=new ContextType[bx.length];
   for(int i=0;i<o.length;i++){
    if(mode.equals("urban"))o[i]=ContextType.RESIDENTIAL;
    else {int x=bx[i]+sx[i]/2,z=bz[i]+sz[i]/2;double v=Math.sin(x*.018)+Math.cos(z*.013)+Math.sin((x+z)*.005);o[i]=v>1?ContextType.FOREST:(v>-.1?ContextType.FARM:ContextType.RESIDENTIAL);}
   }return o;
  });
  RoadShapes s=WorldLayouts.roadShapesFor(seed);
  for(int z=z0;z<z0+h;z++)for(int x=x0;x<x0+w;x++){
   int k=l.kindAt(x,z),d=((k==WorldLayout.STREET||k==WorldLayout.LANE)?s.opennessAt(x,z):0);
   System.out.println(x+","+z+","+k+","+d);
  }
  for(WorldLayout.Rect b:l.blocksIn(x0,z0,x0+w-1,z0+h-1))System.err.printf("BLOCK %d,%d %d,%d%n",b.minX,b.minZ,b.maxX,b.maxZ);
 }
}
