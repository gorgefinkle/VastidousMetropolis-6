package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.ArrayList;
import java.util.List;

/**
 * Source restoration of the R30ea dry-road-wrapper completion pass.
 *
 * It can absorb a full dry strip when a road already runs beyond that strip,
 * or connect dry interior/endpoint gaps. It never intentionally takes protected
 * shore, creek, park-ring or owned cells. No new queue behavior is implemented.
 */
final class UrbanPerimeterCompletion {
    private static final int[] DX={0,1,0,-1};
    private static final int[] DZ={-1,0,1,0};
    private UrbanPerimeterCompletion() {}

    static final class Result {
        int absorbed;
        int linked;
        int stillOpen;
    }

    static Result repair(WorldLayout.Domain d, WorldLayout.Mask protectedArea, int minBlock) {
        Result result = new Result();
        for(WorldLayout.Rect original : new ArrayList<>(d.blocks)) {
            ContextType context=d.contexts.get(original);
            if(context==null || context.isMorphomos() || d.landmarks.containsKey(original))continue;
            WorldLayout.Rect current=original;
            for(int side=0;side<4;side++) {
                WorldLayout.Rect expanded=absorbDryStrip(d,protectedArea,current,side,minBlock);
                if(expanded==null)continue;
                int start=sideStart(current,side),end=sideEnd(current,side);
                for(int t=start;t<=end;t++) {
                    int x=edgeX(current,side,t)+DX[side],z=edgeZ(current,side,t)+DZ[side];
                    d.kind[x-d.minX][z-d.minZ]=3;
                }
                int at=d.blocks.indexOf(current);
                if(at<0)throw new IllegalStateException("Moved district missing during perimeter repair");
                d.blocks.set(at,expanded);
                d.contexts.remove(current);
                d.contexts.put(expanded,context);
                result.absorbed+=end-start+1;
                current=expanded;
            }
            for(int side=0;side<4;side++) {
                result.linked+=closeDryInteriorGap(d,protectedArea,current,side);
                result.linked+=closeDryEndpointGaps(d,protectedArea,current,side);
            }
        }
        for(WorldLayout.Rect box:d.blocks) {
            ContextType context=d.contexts.get(box);
            if(context==null || context.isMorphomos())continue;
            for(int side=0;side<4;side++)for(int t=sideStart(box,side);t<=sideEnd(box,side);t++) {
                int x=edgeX(box,side,t)+DX[side],z=edgeZ(box,side,t)+DZ[side];
                if(in(d,x,z)&&!road(d,x,z))result.stillOpen++;
            }
        }
        return result;
    }

    private static long key(int x,int z) {return ((long)x<<32) ^ (z & 0xffffffffL);}
    private static int sideStart(WorldLayout.Rect r,int side) {return (side&1)==0?r.minX:r.minZ;}
    private static int sideEnd(WorldLayout.Rect r,int side) {return (side&1)==0?r.maxX:r.maxZ;}
    private static int edgeX(WorldLayout.Rect r,int side,int t) {return (side&1)==0?t:side==1?r.maxX:r.minX;}
    private static int edgeZ(WorldLayout.Rect r,int side,int t) {return (side&1)==1?t:side==0?r.minZ:r.maxZ;}
    private static boolean in(WorldLayout.Domain d,int x,int z) {return x>=d.minX&&x<=d.maxX&&z>=d.minZ&&z<=d.maxZ;}
    private static boolean unallocatedDry(WorldLayout.Domain d,WorldLayout.Mask mask,int x,int z) {
        if(!in(d,x,z)||d.kindAt(x,z)!=4 || mask!=null&&mask.at(x,z))return false;
        int xx=x-d.minX,zz=z-d.minZ;
        if(d.shoreRoad!=null&&d.shoreRoad[xx][zz])return false;
        if(d.noRing!=null&&d.noRing[xx][zz])return false;
        return !d.creekOutletRing.containsKey(key(x,z));
    }
    private static boolean road(WorldLayout.Domain d,int x,int z) {
        int kind=d.kindAt(x,z);
        return kind==2||kind==1;
    }
    private static WorldLayout.Rect absorbDryStrip(WorldLayout.Domain d,WorldLayout.Mask mask,WorldLayout.Rect r,int side,int minBlock) {
        if(((side&1)==0?r.depth():r.width())>=minBlock)return null;
        for(int t=sideStart(r,side);t<=sideEnd(r,side);t++) {
            int x=edgeX(r,side,t)+DX[side],z=edgeZ(r,side,t)+DZ[side];
            if(!unallocatedDry(d,mask,x,z)||!road(d,x+DX[side],z+DZ[side]))return null;
        }
        return switch(side) {
            case 0 -> new WorldLayout.Rect(r.minX,r.minZ-1,r.maxX,r.maxZ);
            case 1 -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX+1,r.maxZ);
            case 2 -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX,r.maxZ+1);
            default -> new WorldLayout.Rect(r.minX-1,r.minZ,r.maxX,r.maxZ);
        };
    }
    private static int closeDryEndpointGaps(WorldLayout.Domain d,WorldLayout.Mask mask,WorldLayout.Rect r,int side) {
        int start=sideStart(r,side),end=sideEnd(r,side);
        boolean hasRoad=false,hasDry=false;
        for(int t=start;t<=end;t++) {
            int x=edgeX(r,side,t)+DX[side],z=edgeZ(r,side,t)+DZ[side];
            if(road(d,x,z))hasRoad=true;
            else if(unallocatedDry(d,mask,x,z))hasDry=true;
            else return 0;
        }
        if(!hasRoad||!hasDry)return 0;
        int painted=0;
        int t=start;
        while(t<=end&&unallocatedDry(d,mask,edgeX(r,side,t)+DX[side],edgeZ(r,side,t)+DZ[side]))t++;
        if(t>start&&t<=end) {
            int x=edgeX(r,side,start)+DX[side],z=edgeZ(r,side,start)+DZ[side];
            int ax=(side&1)==0?1:0,az=(side&1)==1?1:0;
            if(road(d,x-ax,z-az)&&safeGapSegment(d,mask,r,side,start,t-1))
                painted+=paveGapSegment(d,r,side,start,t-1);
        }
        t=end;
        while(t>=start&&unallocatedDry(d,mask,edgeX(r,side,t)+DX[side],edgeZ(r,side,t)+DZ[side]))t--;
        if(t<end&&t>=start) {
            int x=edgeX(r,side,end)+DX[side],z=edgeZ(r,side,end)+DZ[side];
            int ax=(side&1)==0?1:0,az=(side&1)==1?1:0;
            if(road(d,x+ax,z+az)&&safeGapSegment(d,mask,r,side,t+1,end))
                painted+=paveGapSegment(d,r,side,t+1,end);
        }
        return painted;
    }
    private static int paveGapSegment(WorldLayout.Domain d,WorldLayout.Rect r,int side,int start,int end) {
        int n=0;
        for(int t=start;t<=end;t++) {
            int x=edgeX(r,side,t)+DX[side],z=edgeZ(r,side,t)+DZ[side];
            d.kind[x-d.minX][z-d.minZ]=2;
            d.urbanBoundaryRoads.add(key(x,z));n++;
        }
        return n;
    }
    private static boolean safeGapSegment(WorldLayout.Domain d,WorldLayout.Mask mask,WorldLayout.Rect r,int side,int start,int end) {
        for(int t=start;t<=end;t++) {
            int x=edgeX(r,side,t)+DX[side],z=edgeZ(r,side,t)+DZ[side];
            if(!unallocatedDry(d,mask,x,z)||road(d,x+DX[side],z+DZ[side]))return false;
            for(int xx=x-1;xx<=x;xx++)for(int zz=z-1;zz<=z;zz++) {
                boolean allRoad=true;
                for(int a=0;a<2;a++)for(int b=0;b<2;b++) {
                    int px=xx+a,pz=zz+b;
                    boolean originalEdge=(side&1)==0?
                         pz==z&&px>=edgeX(r,side,start)&&px<=edgeX(r,side,end):
                         px==x&&pz>=edgeZ(r,side,start)&&pz<=edgeZ(r,side,end);
                    if(!originalEdge&&!road(d,px,pz))allRoad=false;
                }
                if(allRoad)return false;
            }
        }
        return true;
    }
    private static int closeDryInteriorGap(WorldLayout.Domain d,WorldLayout.Mask mask,WorldLayout.Rect r,int side) {
        int start=sideStart(r,side),end=sideEnd(r,side);
        boolean hasRoad=false,hasDry=false;
        for(int t=start;t<=end;t++) {
            int x=edgeX(r,side,t)+DX[side],z=edgeZ(r,side,t)+DZ[side];
            if(road(d,x,z))hasRoad=true;
            else if(!unallocatedDry(d,mask,x,z))return 0;
            else hasDry=true;
        }
        if(!hasRoad||!hasDry)return 0;
        int painted=0;
        for(int t=start+1;t<end;t++) {
            int x=edgeX(r,side,t)+DX[side],z=edgeZ(r,side,t)+DZ[side];
            if(!unallocatedDry(d,mask,x,z))continue;
            int first=t;
            while(t<=end&&unallocatedDry(d,mask,edgeX(r,side,t)+DX[side],edgeZ(r,side,t)+DZ[side]))t++;
            int last=t-1;
            if(last>=end)continue;
            if(!road(d,edgeX(r,side,first-1)+DX[side],edgeZ(r,side,first-1)+DZ[side]) ||
               !road(d,edgeX(r,side,last+1)+DX[side],edgeZ(r,side,last+1)+DZ[side]))continue;
            boolean safe=true;
            for(int j=first;j<=last&&safe;j++) {
                int gx=edgeX(r,side,j)+DX[side],gz=edgeZ(r,side,j)+DZ[side];
                if(road(d,gx+DX[side],gz+DZ[side])) {safe=false;break;}
                for(int a=gx-1;a<=gx&&safe;a++)for(int b=gz-1;b<=gz&&safe;b++) {
                    boolean full=true;
                    for(int aa=0;aa<2;aa++)for(int bb=0;bb<2;bb++) {
                        int px=a+aa,pz=b+bb;
                        if(px==gx&&pz==gz)continue;
                        boolean orig=(side&1)==0?
                              pz==gz&&px>=edgeX(r,side,first)&&px<=edgeX(r,side,last):
                              px==gx&&pz>=edgeZ(r,side,first)&&pz<=edgeZ(r,side,last);
                        if(!orig&&!road(d,px,pz))full=false;
                    }
                    if(full)safe=false;
                }
            }
            if(safe)painted+=paveGapSegment(d,r,side,first,last);
        }
        return painted;
    }
}
