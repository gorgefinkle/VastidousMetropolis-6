package ch.k42.metropolis.grid.urbanGrid.layout;
import java.util.*;
/** Candidate must reserve its own roads before ownership can be committed. */
public final class CandidateRoadFramesAcceptanceProbe {
    private static int count;
    static void ok(boolean b,String msg){count++;if(!b)throw new AssertionError(msg);}
    private static final int[] dx={0,1,0,-1},dz={-1,0,1,0};
    static int cx(WorldLayout.Rect r,int s,int q){return (s%2==0?r.minX+q:s==1?r.maxX:r.minX);}
    static int cz(WorldLayout.Rect r,int s,int q){return (s%2==1?r.minZ+q:s==0?r.minZ:r.maxZ);}
    static class Case {
        WorldLayout.Domain d;WorldLayout.Rect rect;
        boolean[][] st,closed;
        final int size=7;
        Case(int ox,int oz,int missing,boolean abandoned){
            d=new WorldLayout.Domain(1,ox-4,oz-4,ox+12,oz+12,1);
            rect=new WorldLayout.Rect(ox,oz,ox+size-1,oz+size-1);
            int w=d.kind.length,h=d.kind[0].length;
            st=new boolean[w][h];closed=new boolean[w][h];
            for(int s=0;s<4;s++)for(int t=0;t<size;t++){
                int x=cx(rect,s,t)+dx[s],z=cz(rect,s,t)+dz[s];
                if(s==missing){
                    if(abandoned)closed[x-d.minX][z-d.minZ]=true;
                    else d.kind[x-d.minX][z-d.minZ]=(byte)WorldLayout.ZONE;
                }else st[x-d.minX][z-d.minZ]=true;
            }
        }
        LayoutSearch.Result plan(WorldLayout.Mask water){return CandidateRoadFrames.reserve(d,
            new LayoutSearch.Result(List.of(rect),st,closed),water,5,14,(x,z)->false);}
        int at(LayoutSearch.Result result,int x,int z){return result.street[x-d.minX][z-d.minZ]?WorldLayout.STREET:d.kindAt(x,z);}
    }
    public static void main(String[] args){
        for(int side=0;side<4;side++)for(int ox:new int[]{-72,13})for(int oz:new int[]{-94,22})for(boolean abandoned:new boolean[]{false,true}){
            String label=side+"/"+ox+","+oz+"/abandoned="+abandoned;
            Case c=new Case(ox,oz,side,abandoned);
            LayoutSearch.Result out=c.plan((x,z)->false);
            ok(out!=null,"dry outside must be made a street "+label);
            for(int s=0;s<4;s++)for(int t=0;t<c.size;t++){
                int x=cx(c.rect,s,t)+dx[s],z=cz(c.rect,s,t)+dz[s];
                ok(c.at(out,x,z)==WorldLayout.STREET,"full frame "+label+" at "+x+","+z);
            }
            ok(c.d.blocks.isEmpty(),"no publication before acceptance "+label);
            Case wet=new Case(ox,oz,side,false);
            int wx=cx(wet.rect,side,3)+dx[side],wz=cz(wet.rect,side,3)+dz[side];
            LayoutSearch.Result wetResult=wet.plan((x,z)->x==wx&&z==wz);
            ok(wetResult!=null && !wetResult.street[wx-wet.d.minX][wz-wet.d.minZ],"water not paved "+label);
            ok(wet.d.kindAt(wx,wz)==WorldLayout.ZONE,"wet cell preserved "+label);
        }
        // A dry frame cannot be published where the entire face is physically water
        // and its own dimensions cannot donate a row.
        Case impossible=new Case(11,-21,0,false);
        LayoutSearch.Result denied=CandidateRoadFrames.reserve(impossible.d,
            new LayoutSearch.Result(List.of(impossible.rect),impossible.st,impossible.closed),
            (x,z)->z==-22,7,14,(x,z)->false);
        ok(denied==null,"reject truly unwrappable geometry");
        System.out.println("CandidateRoadFramesAcceptanceProbe PASS checks="+count);
    }
}
