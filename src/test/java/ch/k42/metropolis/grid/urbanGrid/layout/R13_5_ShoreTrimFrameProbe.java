package ch.k42.metropolis.grid.urbanGrid.layout;
import java.lang.reflect.Method;
import java.util.*;
public final class R13_5_ShoreTrimFrameProbe {
  static int assertions;
  static void check(boolean yes,String message) { assertions++; if (!yes) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    WorldLayout l=new WorldLayout(null,123L,7,14,99);
    Method trim=WorldLayout.class.getDeclaredMethod("trimShoreRoad",WorldLayout.Domain.class);
    trim.setAccessible(true);
    for(int ox=2;ox<=4;ox++) for(int oz=2;oz<=4;oz++) {
      // Every rectangle is already accepted. Its corner's east neighbour is
      // retained but the north-south approach hasn't been published, leaving
      // one road neighbour. A shore-prune must not delete that corner.
      WorldLayout.Domain d=new WorldLayout.Domain(1,0,0,15,15,1);
      WorldLayout.Rect r=new WorldLayout.Rect(ox,oz,ox+2,oz+2);
      d.blocks.add(r);
      d.shoreRoad=new boolean[16][16];
      int[][] corners={{r.minX-1,r.minZ-1},{r.maxX+1,r.minZ-1},
                       {r.minX-1,r.maxZ+1},{r.maxX+1,r.maxZ+1}};
      for(int[] c:corners){
        int x=c[0],z=c[1];
        d.kind[x][z]=(byte)WorldLayout.STREET;
        d.shoreRoad[x][z]=true;
        check(WorldLayout.requiredAcceptedFrame(d.blocks,x,z),"corner must be frame");
      }
      trim.invoke(l,d);
      for(int[] c:corners)check(d.kind[c[0]][c[1]]==WorldLayout.STREET,"accepted corner erased");
      for(int z=r.minZ;z<=r.maxZ;z++){
        check(WorldLayout.requiredAcceptedFrame(d.blocks,r.minX-1,z),"west frame");
        check(WorldLayout.requiredAcceptedFrame(d.blocks,r.maxX+1,z),"east frame");
      }
      for(int x=r.minX;x<=r.maxX;x++){
        check(WorldLayout.requiredAcceptedFrame(d.blocks,x,r.minZ-1),"north frame");
        check(WorldLayout.requiredAcceptedFrame(d.blocks,x,r.maxZ+1),"south frame");
      }
      check(!WorldLayout.requiredAcceptedFrame(d.blocks,r.minX,r.minZ),"interior should not be frame");
    }
    // A disposable road without a committed district must still be removed.
    WorldLayout.Domain orphan=new WorldLayout.Domain(2,0,0,15,15,1);
    orphan.shoreRoad=new boolean[16][16];
    orphan.shoreRoad[6][7]=true;orphan.kind[6][7]=(byte)WorldLayout.STREET;
    trim.invoke(l,orphan);
    check(orphan.kind[6][7]==WorldLayout.ZONE,"orphan shore road retained");
    System.out.println("R13.5 shoreline-frame test PASS "+assertions+" checks");
  }
}
