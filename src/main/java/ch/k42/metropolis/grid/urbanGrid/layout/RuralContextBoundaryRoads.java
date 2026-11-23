package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.ArrayList;
import java.util.List;

/**
 * Planning-time reservation of a missing road along a complete FARM rectangle
 * face that meets FOREST or planned water/shore.  Only the farm rectangle
 * donates a row/column of its OWN dry footprint. A face against another farm
 * is never eligible: that seam still belongs to Morphomos.
 *
 * The candidate must join at least one existing street; the other end must
 * join a street or terminate at an actual masked waterfront. This prevents
 * adding disconnected border roads and preserves the accepted creek/water
 * geometry. There is no parcel painter or after-generation fix here.
 */
final class RuralContextBoundaryRoads {
    private RuralContextBoundaryRoads() { }
    private static long key(int x,int z) { return ((long)x<<32)^(z&0xffffffffL); }

    private static ContextType contextAt(WorldLayout.Domain d,int x,int z) {
        if (d.kindAt(x,z)!=WorldLayout.BLOCK) return null;
        for (WorldLayout.Rect r : d.blocks) {
            if(x>=r.minX && x<=r.maxX && z>=r.minZ && z<=r.maxZ)
                return d.contexts.get(r);
        }
        return null;
    }

    /** The river-style creek planning lattice can temporarily hold entirely
     * DRY chunks so the rectangle solver doesn't crowd the future channel.
     * Those temporary reservations are not actual riverbank ownership and
     * must never license a new road or a disconnected shore end cap.
     */
    private static boolean verifiedWaterZone(WorldLayout.Mask zone,int x,int z) {
        if(zone==null || !zone.at(x,z)) return false;
        return !(zone instanceof WaterField water && water.isCreekPlanningOnlyDryAt(x,z));
    }

    private static boolean foreignWilderness(WorldLayout.Domain d,WorldLayout.Mask zone,int x,int z) {
        int kind=d.kindAt(x,z);
        if(kind==WorldLayout.BLOCK) return contextAt(d,x,z)==ContextType.FOREST;
        // NATURAL is the committed dry woodland handoff used on creek banks
        // and reclaimed rural margins, not an unclassified empty cell.
        if(kind==WorldLayout.NATURAL) return true;
        // A creek's real bank / an actual river shore belongs to wilderness,
        // not to the adjacent FARM rectangle. ZONE by itself isn't proof.
        return kind==WorldLayout.ZONE && verifiedWaterZone(zone,x,z);
    }

    private static boolean waterEndpoint(WorldLayout.Domain d,WorldLayout.Mask zone,int x,int z) {
        int kind=d.kindAt(x,z);
        return (kind==WorldLayout.ZONE || kind==WorldLayout.UNCLAIMED)
                && verifiedWaterZone(zone,x,z);
    }

    private static WorldLayout.Rect inset(WorldLayout.Rect r,int side) {
        return switch(side) {
            case 0 -> new WorldLayout.Rect(r.minX,r.minZ+1,r.maxX,r.maxZ);
            case 1 -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX,r.maxZ-1);
            case 2 -> new WorldLayout.Rect(r.minX+1,r.minZ,r.maxX,r.maxZ);
            default -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX-1,r.maxZ);
        };
    }

    static int repair(WorldLayout.Domain d,int minimum,WorldLayout.Mask zone) {
        int total=0;
        // A fragmented waterfront can span more than one farm rectangle.
        // Work from an already connected road outward, never mint a blind spur.
        boolean changed;
        int passes=0;
        do {
            changed=false;
            List<WorldLayout.Rect> candidates=new ArrayList<>(d.blocks);
            for(WorldLayout.Rect rect:candidates) {
                if(d.contexts.get(rect)!=ContextType.FARM) continue;
                for(int side=0;side<4;side++) {
                    boolean alongX=side<2;
                    if((alongX?rect.depth():rect.width())-1<minimum) continue;
                    int dx=side==2?-1:side==3?1:0;
                    int dz=side==0?-1:side==1?1:0;
                    int edge=side==0?rect.minZ:side==1?rect.maxZ:
                            side==2?rect.minX:rect.maxX;
                    int from=alongX?rect.minX:rect.minZ;
                    int to=alongX?rect.maxX:rect.maxZ;
                    // Every position along the whole face is a protected
                    // mixed/shore boundary and belongs to the selected farm.
                    boolean eligible=true;
                    for(int a=from;a<=to;a++) {
                        int x=alongX?a:edge,z=alongX?edge:a;
                        if(d.kindAt(x,z)!=WorldLayout.BLOCK
                                || contextAt(d,x,z)!=ContextType.FARM
                                || !foreignWilderness(d,zone,x+dx,z+dz)) {
                            eligible=false;break;
                        }
                    }
                    if(!eligible)continue;
                    int ax=alongX?from-1:edge,az=alongX?edge:from-1;
                    int bx=alongX?to+1:edge,bz=alongX?edge:to+1;
                    boolean aRoad=d.kindAt(ax,az)==WorldLayout.STREET;
                    boolean bRoad=d.kindAt(bx,bz)==WorldLayout.STREET;
                    if(!(aRoad||bRoad))continue;
                    if(!(aRoad||waterEndpoint(d,zone,ax,az))
                            || !(bRoad||waterEndpoint(d,zone,bx,bz))) continue;
                    WorldLayout.Rect smaller=inset(rect,side);
                    if(smaller.width()<minimum || smaller.depth()<minimum)continue;
                    d.blocks.remove(rect);
                    d.blocks.add(smaller);
                    d.contexts.remove(rect);
                    d.contexts.put(smaller,ContextType.FARM);
                    for(int a=from;a<=to;a++) {
                        int x=alongX?a:edge,z=alongX?edge:a;
                        d.kind[x-d.minX][z-d.minZ]=WorldLayout.STREET;
                        d.ruralBoundaryRoads.add(key(x,z));
                        total++;
                    }
                    // The existing street corner belongs to this accepted
                    // boundary route as well. Without this, Morphomos can
                    // dissolve the junction as FARM/FARM (its side faces),
                    // even though the newly committed mixed-boundary row
                    // must connect through it into the public street grid.
                    if(aRoad) d.ruralBoundaryRoads.add(key(ax,az));
                    if(bRoad) d.ruralBoundaryRoads.add(key(bx,bz));
                    changed=true;
                    break;
                }
            }
        } while(changed && ++passes<d.blocks.size()+2);
        return total;
    }
}
