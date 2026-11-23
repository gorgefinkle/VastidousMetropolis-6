package ch.k42.metropolis.grid.urbanGrid.layout;

import java.util.ArrayList;
import java.util.List;

/** Recovered ready-first district-frame search candidate validation from R30ea. */
final class CandidateRoadFrames {
    private static final int STREET = WorldLayout.STREET, LANE = WorldLayout.LANE, ZONE=WorldLayout.ZONE;
    private static final int[] DX={0,1,0,-1};
    private static final int[] DZ={-1,0,1,0};
    private CandidateRoadFrames() {}
    interface ExternalRoad { boolean at(int x,int z); }

    static LayoutSearch.Result reserve(WorldLayout.Domain d, LayoutSearch.Result input, WorldLayout.Mask forbidden,
                                       int minBlock, int maxBlock, ExternalRoad external) {
        if(input==null) return null;
        int width=d.kind.length,depth=d.kind[0].length;
        boolean[][] streets=new boolean[width][depth], closed=new boolean[width][depth];
        for(int x=0;x<width;x++){
            System.arraycopy(input.street[x],0,streets[x],0,depth);
            System.arraycopy(input.closed[x],0,closed[x],0,depth);
        }
        List<WorldLayout.Rect> blocks=new ArrayList<>(input.blocks);
        boolean[][] claimed=new boolean[width][depth];
        for(WorldLayout.Rect r:input.blocks) {
            for(int x=r.minX;x<=r.maxX;x++)for(int z=r.minZ;z<=r.maxZ;z++){
                if(!inside(d,x,z))return null;
                claimed[x-d.minX][z-d.minZ]=true;
            }
        }
        for(int x=0;x<width;x++)for(int z=0;z<depth;z++)if(streets[x][z]&&claimed[x][z])return null;
        for(int i=0;i<blocks.size();i++) {
            WorldLayout.Rect r=blocks.get(i);
            for(int side=0;side<4;side++) {
                if(fullFrame(d,streets,r,side,external))continue;
                WorldLayout.Rect bigger=expanded(r,side);
                if(bigger.width()<=maxBlock && bigger.depth()<=maxBlock &&
                        canGrowToRoad(d,streets,claimed,r,side,forbidden,external)) {
                    r=bigger; blocks.set(i,r);
                    for(int p=0;p<length(r,side);p++){
                        int x=onX(r,side,p),z=onZ(r,side,p);
                        if(inside(d,x,z))claimed[x-d.minX][z-d.minZ]=true;
                    }
                    continue;
                }
                boolean canPave=true;
                for(int p=0;p<length(r,side);p++){
                    int x=onX(r,side,p)+DX[side],z=onZ(r,side,p)+DZ[side];
                    if(!road(d,streets,x,z,external) && !pavable(d,claimed,x,z,forbidden)){
                        canPave=false;break;
                    }
                }
                if(canPave){
                    for(int p=0;p<length(r,side);p++){
                        int x=onX(r,side,p)+DX[side],z=onZ(r,side,p)+DZ[side];
                        if(inside(d,x,z) && !road(d,streets,x,z,external)) {
                            streets[x-d.minX][z-d.minZ]=true;
                            closed[x-d.minX][z-d.minZ]=false;
                        }
                    }
                    continue;
                }
                WorldLayout.Rect smaller=inset(r,side);
                if(smaller.width()<minBlock || smaller.depth()<minBlock)return null;
                for(int p=0;p<length(r,side);p++){
                    int x=onX(r,side,p),z=onZ(r,side,p);
                    if(!inside(d,x,z)||!claimed[x-d.minX][z-d.minZ])return null;
                    streets[x-d.minX][z-d.minZ]=true;
                    closed[x-d.minX][z-d.minZ]=false;
                    claimed[x-d.minX][z-d.minZ]=false;
                }
                r=smaller;blocks.set(i,r);
            }
        }
        // Every accepted district must own all four road-ring corners as well
        // as its four straight faces. A side-only frame can pass the old exact
        // search yet fail PlanRegions.completeStreetFrame at the corners.
        // Acquire each corner inside this candidate, never after publication.
        for(WorldLayout.Rect r:blocks) {
            int[] xs={r.minX-1,r.maxX+1,r.minX-1,r.maxX+1};
            int[] zs={r.minZ-1,r.minZ-1,r.maxZ+1,r.maxZ+1};
            for(int i=0;i<4;i++) {
                int x=xs[i],z=zs[i];
                if(road(d,streets,x,z,external))continue;
                if(!pavable(d,claimed,x,z,forbidden))return null;
                streets[x-d.minX][z-d.minZ]=true;
                closed[x-d.minX][z-d.minZ]=false;
            }
        }
        boolean[][] used=new boolean[width][depth];
        for(WorldLayout.Rect r:blocks)for(int x=r.minX;x<=r.maxX;x++)for(int z=r.minZ;z<=r.maxZ;z++){
            if(!inside(d,x,z))return null;
            int cx=x-d.minX,cz=z-d.minZ;
            if(used[cx][cz] || streets[cx][cz] ||
               d.kind[cx][cz]==STREET || d.kind[cx][cz]==LANE)return null;
            used[cx][cz]=true;
        }
        for(WorldLayout.Rect r:blocks) {
            for(int side=0;side<4;side++)
                if(!fullFrame(d,streets,r,side,external))return null;
            if(!road(d,streets,r.minX-1,r.minZ-1,external)
                    || !road(d,streets,r.maxX+1,r.minZ-1,external)
                    || !road(d,streets,r.minX-1,r.maxZ+1,external)
                    || !road(d,streets,r.maxX+1,r.maxZ+1,external))return null;
        }
        for(int cx=0;cx<width;cx++)for(int cz=0;cz<depth;cz++)if(streets[cx][cz]&&!input.street[cx][cz]) {
            int x=cx+d.minX,z=cz+d.minZ;
            for(int ox=x-1;ox<=x;ox++)for(int oz=z-1;oz<=z;oz++){
                boolean allOld=true,allNew=true;
                for(int xx=ox;xx<=ox+1;xx++)for(int zz=oz;zz<=oz+1;zz++){
                    if(!road(d,input.street,xx,zz,external))allOld=false;
                    if(!road(d,streets,xx,zz,external))allNew=false;
                }
                if(allNew&&!allOld)return null;
            }
        }
        return new LayoutSearch.Result(blocks,streets,closed);
    }
    private static boolean inside(WorldLayout.Domain d,int x,int z){
        return x>=d.minX&&x<=d.maxX&&z>=d.minZ&&z<=d.maxZ;
    }
    private static boolean road(WorldLayout.Domain d,boolean[][] paved,int x,int z,ExternalRoad outside){
        if(!inside(d,x,z))return outside!=null&&outside.at(x,z);
        int k=d.kind[x-d.minX][z-d.minZ];
        return k==STREET||k==LANE||paved[x-d.minX][z-d.minZ];
    }
    private static boolean pavable(WorldLayout.Domain d,boolean[][] occupied,int x,int z,WorldLayout.Mask mask){
        if(!inside(d,x,z))return false;
        int cx=x-d.minX,cz=z-d.minZ,k=d.kind[cx][cz];
        return !occupied[cx][cz] && (k==ZONE||k==WorldLayout.UNCLAIMED) && (mask==null||!mask.at(x,z));
    }
    private static int length(WorldLayout.Rect r,int side){return (side&1)==0?r.width():r.depth();}
    private static int onX(WorldLayout.Rect r,int side,int p){return (side&1)==0?r.minX+p:side==1?r.maxX:r.minX;}
    private static int onZ(WorldLayout.Rect r,int side,int p){return (side&1)==1?r.minZ+p:side==0?r.minZ:r.maxZ;}
    private static boolean fullFrame(WorldLayout.Domain d,boolean[][] streets,WorldLayout.Rect r,int side,ExternalRoad outside){
        for(int p=0;p<length(r,side);p++)if(!road(d,streets,onX(r,side,p)+DX[side],onZ(r,side,p)+DZ[side],outside))return false;
        return true;
    }
    private static boolean canGrowToRoad(WorldLayout.Domain d,boolean[][] streets,boolean[][] occupied,
                                         WorldLayout.Rect r,int side,WorldLayout.Mask mask,ExternalRoad outside){
        for(int p=0;p<length(r,side);p++){
            int x=onX(r,side,p)+DX[side],z=onZ(r,side,p)+DZ[side];
            if(!pavable(d,occupied,x,z,mask)||!road(d,streets,x+DX[side],z+DZ[side],outside))return false;
        }
        return true;
    }
    private static WorldLayout.Rect expanded(WorldLayout.Rect r,int side){
        return switch(side){
            case 0->new WorldLayout.Rect(r.minX,r.minZ-1,r.maxX,r.maxZ);
            case 1->new WorldLayout.Rect(r.minX,r.minZ,r.maxX+1,r.maxZ);
            case 2->new WorldLayout.Rect(r.minX,r.minZ,r.maxX,r.maxZ+1);
            default->new WorldLayout.Rect(r.minX-1,r.minZ,r.maxX,r.maxZ);
        };
    }
    private static WorldLayout.Rect inset(WorldLayout.Rect r,int side){
        return switch(side){
            case 0->new WorldLayout.Rect(r.minX,r.minZ+1,r.maxX,r.maxZ);
            case 1->new WorldLayout.Rect(r.minX,r.minZ,r.maxX-1,r.maxZ);
            case 2->new WorldLayout.Rect(r.minX,r.minZ,r.maxX,r.maxZ-1);
            default->new WorldLayout.Rect(r.minX+1,r.minZ,r.maxX,r.maxZ);
        };
    }
}
