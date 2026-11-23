package ch.k42.metropolis.grid.urbanGrid.layout;

import java.util.List;
/** Complete road wrappers need four corner cells as well as four road faces. */
public final class R13_4_StrictCornerFramesProbe {
  private static int checks;
  private static void ok(boolean valid, String message) { checks++; if(!valid)throw new AssertionError(message); }
  private static final int[][] OFFSETS={{-1,-1},{7,-1},{-1,7},{7,7}};
  private static WorldLayout.Domain domain(int ox,int oz) {
    return new WorldLayout.Domain(7,ox-3,oz-3,ox+10,oz+10,1);
  }
  private static LayoutSearch.Result candidate(WorldLayout.Domain d,int ox,int oz){
    var rect = new WorldLayout.Rect(ox,oz,ox+6,oz+6);
    int w=d.kind.length,h=d.kind[0].length;
    boolean[][] road=new boolean[w][h],closed=new boolean[w][h];
    for(int x=ox;x<=ox+6;x++){
      road[x-d.minX][oz-1-d.minZ]=true;
      road[x-d.minX][oz+7-d.minZ]=true;
    }
    for(int z=oz;z<=oz+6;z++){
      road[ox-1-d.minX][z-d.minZ]=true;
      road[ox+7-d.minX][z-d.minZ]=true;
    }
    return new LayoutSearch.Result(List.of(rect),road,closed);
  }
  private static boolean connected(WorldLayout.Domain d,LayoutSearch.Result r,int x,int z){
    return r.street[x-d.minX][z-d.minZ] || d.kindAt(x,z)==WorldLayout.STREET || d.kindAt(x,z)==WorldLayout.LANE;
  }
  public static void main(String[] args){
    for(int ox:new int[]{-72,13})for(int oz:new int[]{-94,22}){
      WorldLayout.Domain d=domain(ox,oz);
      LayoutSearch.Result accepted=CandidateRoadFrames.reserve(d,candidate(d,ox,oz),null,5,14,(x,z)->false);
      ok(accepted!=null,"dry candidates must admit four missing corners "+ox+","+oz);
      for(int[] p:OFFSETS) {
        int x=ox+p[0],z=oz+p[1];
        ok(connected(d,accepted,x,z),"missing reserved corner "+x+","+z);
      }
      ok(WorldLayout.exactPlanHasCompleteFrames(d,accepted),"strict full-frame acceptance");
      for(int[] bad:OFFSETS){
        int x=ox+bad[0],z=oz+bad[1];
        WorldLayout.Domain wet=domain(ox,oz);
        LayoutSearch.Result denied=CandidateRoadFrames.reserve(wet,candidate(wet,ox,oz),
                (bx,bz)->bx==x&&bz==z,5,14,(bx,bz)->false);
        ok(denied==null,"blocked/wet corner cannot be silently omitted "+x+","+z);
        WorldLayout.Domain occupied=domain(ox,oz);
        occupied.kind[x-occupied.minX][z-occupied.minZ]=WorldLayout.BLOCK;
        ok(CandidateRoadFrames.reserve(occupied,candidate(occupied,ox,oz),null,5,14,
                (bx,bz)->false)==null,"already developed corner cannot be paved "+x+","+z);
      }
      WorldLayout.Domain external=domain(ox,oz);
      LayoutSearch.Result good=CandidateRoadFrames.reserve(external,candidate(external,ox,oz),null,5,14,
              (x,z)->false);
      ok(good!=null,"valid original still accepted");
      ok(external.blocks.isEmpty(),"acceptance must not commit district to domain");
    }
    System.out.println("R13_4_StrictCornerFramesProbe PASS checks="+checks);
  }
}
