package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

/** Release pairs of road/forest fringe cells stranded by a closed highway bend.
 * Recovered against the R30ea reference; the action still obeys water,
 * immutable-ring and retired-road ownership constraints.
 */
final class HighwayBendFringe {
    private static final int[] DX={0,1,0,-1};
    private static final int[] DZ={-1,0,1,0};
    private static final int[] OPEN={1,2,4,8};
    private HighwayBendFringe() {}
    interface Outside { int kindAt(int x,int z); boolean forestAt(int x,int z); }
    interface RetiredStreet { boolean isRetiredForestRoad(int x,int z); }
    static int release(WorldLayout.Domain domain, HighwayNetwork highway, WorldLayout.Mask water) {
        return release(domain,highway.snapshot(domain.minX - 2,domain.minZ - 2,domain.maxX + 2,domain.maxZ + 2),water);
    }
    static int release(WorldLayout.Domain domain, HighwayNetwork.Mask road, WorldLayout.Mask water) {
        return release(domain,road,water,(Outside)null);
    }
    static int release(WorldLayout.Domain domain, HighwayNetwork.Mask road, WorldLayout.Mask water, Outside outside) {
        return release(domain,road,water,outside,null);
    }
    static int release(WorldLayout.Domain domain, HighwayNetwork.Mask road, WorldLayout.Mask water, Outside outside, RetiredStreet retired) {
        return release(domain,road,water,outside,retired,
                new PlannedShoreOwnership.Prepared(water,domain.minX,domain.minZ,domain.maxX,domain.maxZ));
    }
    static int release(WorldLayout.Domain domain, HighwayNetwork.Mask road, WorldLayout.Mask water, Outside outside, RetiredStreet retired, PlannedShoreOwnership.Prepared shore) {
        int changed=0;
        for(int x=domain.minX;x<=domain.maxX;x++)for(int z=domain.minZ;z<=domain.maxZ;z++)
            for(int side=0;side<4;side++)for(int across:new int[]{(side+1)&3,(side+3)&3}) {
                int dx=DX[side],dz=DZ[side], ax=DX[across],az=DZ[across];
                if(!pair(domain,water,shore,x,z,ax,az,outside))continue;
                int highwayX=x-dx,highwayZ=z-dz;
                if(!road.at(highwayX,highwayZ))continue;
                RoadType piece=HighwayNetwork.pieceFor(road,highwayX,highwayZ);
                if(piece==null || PieceOpenness.opens(piece,OPEN[side]))continue;
                if(domain.kindAt(x+ax,z+az)!=4)continue;
                if(!forest(domain,outside,x-ax,z-az) || !forest(domain,outside,x-2*ax,z-2*az))continue;
                int length=0;
                while(pair(domain,water,shore,x+dx*length,z+dz*length,ax,az,outside))length++;
                if(length<2)continue;
                int endX=x+dx*length,endZ=z+dz*length;
                int endKind=domain.kindAt(endX,endZ);
                if(endKind==1)continue;
                if(endKind==2 && (retired==null || !retired.isRetiredForestRoad(endX,endZ)))continue;
                if(!safeEnvelope(domain,outside,water,x,z,dx,dz,ax,az,length,retired))continue;
                for(int i=0;i<length;i++){
                    int px=x+dx*i,pz=z+dz*i;
                    domain.kind[px-domain.minX][pz-domain.minZ]=5;
                    domain.kind[px+ax-domain.minX][pz+az-domain.minZ]=5;
                }
                changed+=length*2;
            }
        return changed;
    }
    private static boolean pair(WorldLayout.Domain domain, WorldLayout.Mask water, PlannedShoreOwnership.Prepared shore,
                                int x,int z,int dx,int dz, Outside outside) {
        if(domain.kindAt(x,z)!=2 || domain.kindAt(x+dx,z+dz)!=4)return false;
        if(!inside(domain,x,z)||!inside(domain,x+dx,z+dz))return false;
        if(shore.owns(x,z)||shore.owns(x+dx,z+dz))return false;
        if(water!=null && (water.at(x,z)||water.at(x+dx,z+dz)))return false;
        if(water instanceof WaterField field &&
          (field.zoneKind(x,z)!=WaterField.Kind.NONE || field.zoneKind(x+dx,z+dz)!=WaterField.Kind.NONE))return false;
        long k=key(x,z);
        if(domain.urbanBoundaryRoads.contains(k)||domain.creekOutletRing.containsKey(k))return false;
        if(domain.shoreRoad!=null && domain.shoreRoad[x-domain.minX][z-domain.minZ])return false;
        return forest(domain,outside,x-dx,z-dz) && forest(domain,outside,x-2*dx,z-2*dz)
            && forest(domain,outside,x+2*dx,z+2*dz) && forest(domain,outside,x+3*dx,z+3*dz);
    }
    private static long key(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}
    private static boolean inside(WorldLayout.Domain d,int x,int z) {
        return x>=d.minX && x<=d.maxX && z>=d.minZ && z<=d.maxZ;
    }
    private static int kind(WorldLayout.Domain d,Outside outside,int x,int z) {
        return inside(d,x,z)?d.kindAt(x,z):outside==null?0:outside.kindAt(x,z);
    }
    private static boolean forest(WorldLayout.Domain d,Outside outside,int x,int z) {
        if(!inside(d,x,z))return outside!=null && outside.forestAt(x,z);
        int k=d.kindAt(x,z);
        if(k==5)return true;
        if(k!=3)return false;
        for(WorldLayout.Rect r:d.blocks)
            if(x>=r.minX&&x<=r.maxX&&z>=r.minZ&&z<=r.maxZ)
                return d.contexts.get(r)==ContextType.FOREST;
        return false;
    }
    private static boolean safeEnvelope(WorldLayout.Domain d,Outside outside,WorldLayout.Mask water,
                                        int x,int z,int dx,int dz,int ax,int az,int length,RetiredStreet retired) {
        for(int i=0;i<length;i++)for(int cross=0;cross<=1;cross++)for(int face=0;face<4;face++) {
            int px=x+dx*i+ax*cross+DX[face],pz=z+dz*i+az*cross+DZ[face];
            if(water!=null && (water.at(px,pz) || (water instanceof WaterField field && field.zoneKind(px,pz)!=WaterField.Kind.NONE)))return false;
            int k=kind(d,outside,px,pz);
            if(k==0 && !inside(d,px,pz))return false;
            if(k==3 && !forest(d,outside,px,pz))return false;
            if(k==2 && !(px==x+dx*i || px==x+dx*i+ax&&pz==z+dz*i+az ||
                    px==x+dx*(i+1)&&pz==z+dz*(i+1)+0 || px==x+dx*(i-1)&&pz==z+dz*(i-1) ||
                    retired!=null && retired.isRetiredForestRoad(px,pz)))return false;
            if(k==1 && !(i==0 && px==x-dx && pz==z-dz || i==0 && px==x-dx+ax && pz==z-dz+az))return false;
        }
        return true;
    }
}
