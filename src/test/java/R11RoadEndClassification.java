import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.layout.*;
/** Regression for seed 12345's two previously unclaimed dry road cells. */
public final class R11RoadEndClassification {
  public static void main(String[] args) throws Exception {
    R11RoadMatrixAudit.setup();
    WorldLayout plan=WorldLayouts.forSeed(12345L);
    plan.setZoner((bx,bz,sx,sz,minX,minZ,maxX,maxZ)->{
      ContextType[] out=new ContextType[bx.length];
      java.util.Arrays.fill(out, ContextType.RESIDENTIAL);
      return out;
    });
    RoadShapes roads=WorldLayouts.roadShapesFor(12345L);
    WaterField water=WorldLayouts.waterFor(12345L);
    int checks=0;
    for(int[] p:new int[][]{{25,53},{41,53}}) {
      int x=p[0],z=p[1];
      if(water.kindAt(x,z)!=WaterField.Kind.NONE || water.zoneKind(x,z)!=WaterField.Kind.NONE)
        throw new AssertionError("fixture must be dry at "+x+","+z);
      if(plan.kindAt(x,z)!=WorldLayout.STREET)
        throw new AssertionError("reference road missing at "+x+","+z);
      if(roads.opennessAt(x,z)!=15)
        throw new AssertionError("reference four-way road missing at "+x+","+z);
      checks+=3;
      for(int[] d:new int[][]{{0,-1},{1,0},{0,1},{-1,0}}){
        int xx=x+d[0],zz=z+d[1];
        if(plan.kindAt(xx,zz)!=WorldLayout.STREET && plan.kindAt(xx,zz)!=WorldLayout.LANE)
          throw new AssertionError("missing connected neighbor at "+xx+","+zz);
        checks++;
      }
    }
    System.out.println("R11RoadEndClassification PASS checks="+checks);
  }
}
