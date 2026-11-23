package ch.k42.metropolis.grid.urbanGrid.layout;
import java.util.List;
/** Regress: a dry unclaimed perimeter can be paved without resizing a legal district. */
public final class UnclaimedRoadBudgetProbe {
    private static int checks;
    private static void ok(boolean value, String why) {
        checks++; if(!value) throw new AssertionError(why);
    }
    private static int edgeX(WorldLayout.Rect r,int side,int t) {return (side%2==0?r.minX+t:side==1?r.maxX:r.minX);}
    private static int edgeZ(WorldLayout.Rect r,int side,int t) {return (side%2==1?r.minZ+t:side==0?r.minZ:r.maxZ);}
    public static void main(String[] args) {
        for(int sx: new int[]{-128,15}) for(int sz:new int[]{-72,31}) for(int side=0;side<4;side++) {
            WorldLayout.Domain d=new WorldLayout.Domain(1,sx-3,sz-3,sx+10,sz+10,1);
            WorldLayout.Rect block=new WorldLayout.Rect(sx,sz,sx+6,sz+6);
            boolean[][] street=new boolean[d.kind.length][d.kind[0].length];
            boolean[][] closed=new boolean[d.kind.length][d.kind[0].length];
            int[] dx={0,1,0,-1},dz={-1,0,1,0};
            for(int s=0;s<4;s++)for(int t=0;t<7;t++){
                int x=edgeX(block,s,t)+dx[s],z=edgeZ(block,s,t)+dz[s];
                if(s!=side)street[x-d.minX][z-d.minZ]=true;
            }
            // The missing side has UNCLAIMED dry cells: ordinary unused buildable area,
            // not zone, not solver-closed, and not inside an existing block.
            LayoutSearch.Result out=CandidateRoadFrames.reserve(d,new LayoutSearch.Result(List.of(block),street,closed),
                (x,z)->false,7,14,(x,z)->false);
            ok(out!=null,"dry UNCLAIMED side rejected "+side+" at "+sx+","+sz);
            ok(out.blocks.size()==1 && out.blocks.get(0).width()==7 && out.blocks.get(0).depth()==7,
               "district wrongly resized even though external dry road exists: "+(out==null?"null":out.blocks));
            for(int t=0;t<7;t++) {
                int x=edgeX(block,side,t)+dx[side],z=edgeZ(block,side,t)+dz[side];
                ok(out.street[x-d.minX][z-d.minZ],"missing paved road at "+x+","+z);
            }
            // A genuine protected cell never becomes a street, even when dry-sized.
            final int px=edgeX(block,side,3)+dx[side],pz=edgeZ(block,side,3)+dz[side];
            LayoutSearch.Result denied=CandidateRoadFrames.reserve(d,new LayoutSearch.Result(List.of(block),street,closed),
                (x,z)->x==px&&z==pz,7,14,(x,z)->false);
            ok(denied==null,"road attempted to claim reserved terrain at "+px+","+pz);
        }
        System.out.println("UnclaimedRoadBudgetProbe PASS checks="+checks);
    }
}
