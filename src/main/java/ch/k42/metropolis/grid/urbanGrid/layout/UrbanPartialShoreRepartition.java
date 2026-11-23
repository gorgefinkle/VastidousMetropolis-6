package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * R30ea-compatible coastal district repartition. A district is shortened only
 * when the removed perimeter can reach natural terrain and the surviving
 * district keeps a complete road wrapper, including a new separating road.
 *
 * Recovered from the shipped class; this is planner source, not a precompiled
 * plugin or a runtime bytecode overlay.
 */
final class UrbanPartialShoreRepartition {
    private static final int[] DX = {0,1,0,-1};
    private static final int[] DZ = {-1,0,1,0};
    private UrbanPartialShoreRepartition() {}

    private static final class Plan {
        WorldLayout.Rect original, next;
        int cutCoord, side, removed;
        boolean alongX, prefix;
    }

    private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }
    private static boolean inside(WorldLayout.Domain d, int x, int z) {
        return x >= d.minX && x <= d.maxX && z >= d.minZ && z <= d.maxZ;
    }
    private static boolean road(WorldLayout.Domain d, int x, int z) {
        int kind = d.kindAt(x,z);
        return kind == WorldLayout.STREET || kind == WorldLayout.LANE;
    }
    /** Max eight natural-zone hops, or direct contact with water/natural mask. */
    private static boolean opensToNatural(WorldLayout.Domain d, WorldLayout.Mask shore, int x, int z) {
        if (!inside(d,x,z)) return false;
        ArrayDeque<int[]> open = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        open.add(new int[]{x,z,0});
        visited.add(key(x,z));
        while (!open.isEmpty()) {
            int[] p = open.removeFirst();
            if (shore != null && shore.at(p[0],p[1])) return true;
            int k = d.kindAt(p[0],p[1]);
            if (k == WorldLayout.NATURAL) return true;
            if (k != WorldLayout.ZONE || p[2] >= 8) continue;
            for(int side = 0; side < 4; side++) {
                int nx = p[0] + DX[side], nz = p[1] + DZ[side];
                if (!inside(d,nx,nz)) continue;
                int kind = d.kindAt(nx,nz);
                if ((kind == WorldLayout.ZONE || kind == WorldLayout.NATURAL) && visited.add(key(nx,nz))) {
                    open.addLast(new int[]{nx,nz,p[2]+1});
                }
            }
        }
        return false;
    }
    private static int edgeLength(WorldLayout.Rect r, int side) {
        return (side & 1)==0 ? r.width() : r.depth();
    }
    private static int edgeX(WorldLayout.Rect r, int side, int position) {
        return (side & 1)==0 ? r.minX+position : (side==1 ? r.maxX : r.minX);
    }
    private static int edgeZ(WorldLayout.Rect r, int side, int position) {
        return (side & 1)==1 ? r.minZ+position : (side==2 ? r.maxZ : r.minZ);
    }
    private static boolean fullyWrapped(WorldLayout.Domain d, WorldLayout.Rect next,
                                        int cutX, int cutZ, boolean alongX) {
        for (int side = 0; side < 4; side++) {
            for (int p = 0; p < edgeLength(next,side); p++) {
                int x = edgeX(next,side,p)+DX[side], z = edgeZ(next,side,p)+DZ[side];
                if (alongX ? (x==cutX && z>=next.minZ && z<=next.maxZ) :
                             (z==cutZ && x>=next.minX && x<=next.maxX)) continue;
                if (!road(d,x,z)) return false;
            }
        }
        return true;
    }

    static int repair(WorldLayout.Domain d, WorldLayout.Mask coast, int minBlock) {
        int replaced = 0;
        for (WorldLayout.Rect original : new ArrayList<>(d.blocks)) {
            ContextType context = d.contexts.get(original);
            if (context==null || context.isMorphomos() || d.landmarks.containsKey(original)) continue;
            Plan best = null;
            for (int side=0; side<4; side++) {
                int length = edgeLength(original,side), missing=0, present=0;
                for(int p=0; p<length; p++) {
                    int x=edgeX(original,side,p)+DX[side];
                    int z=edgeZ(original,side,p)+DZ[side];
                    if (!inside(d,x,z)) {missing=-999; break;}
                    if (road(d,x,z)) present++; else missing++;
                }
                if (missing<=0 || present==0) continue;
                for (boolean prefix : new boolean[]{true,false}) {
                    for (int removed=2; removed<=length-minBlock; removed++) {
                        boolean alongX = (side & 1)==0;
                        int coord = prefix ? (alongX ? original.minX : original.minZ)+removed-1 :
                                              (alongX ? original.maxX : original.maxZ)-removed+1;
                        WorldLayout.Rect next;
                        if (alongX) {
                            next=prefix ? new WorldLayout.Rect(coord+1,original.minZ,original.maxX,original.maxZ) :
                                          new WorldLayout.Rect(original.minX,original.minZ,coord-1,original.maxZ);
                        } else {
                            next=prefix ? new WorldLayout.Rect(original.minX,coord+1,original.maxX,original.maxZ) :
                                          new WorldLayout.Rect(original.minX,original.minZ,original.maxX,coord-1);
                        }
                        if (next.width()<minBlock || next.depth()<minBlock) continue;
                        if (!fullyWrapped(d,next,alongX?coord:Integer.MIN_VALUE,alongX?Integer.MIN_VALUE:coord,alongX)) continue;
                        int start=alongX? original.minZ : original.minX;
                        int end=alongX? original.maxZ : original.maxX;
                        // Both ends of the new separating road must meet old roads.
                        if (!road(d,alongX?coord:start-1,alongX?start-1:coord) ||
                            !road(d,alongX?coord:end+1,alongX?end+1:coord)) continue;
                        boolean safe=true,natural=false;
                        for(int v=start; v<=end && safe; v++) {
                            int x=alongX?coord:v,z=alongX?v:coord;
                            if(d.kindAt(x,z)==WorldLayout.BLOCK &&
                              (d.creekOutletRing.containsKey(key(x,z)) ||
                               d.urbanBoundaryRoads.contains(key(x,z)))) safe=false;
                        }
                        if(!safe) continue;
                        for (int p=0; p<length && safe; p++) {
                            int ox=edgeX(original,side,p),oz=edgeZ(original,side,p);
                            boolean trimmed=alongX ? (prefix ? ox<coord : ox>coord) :
                                                    (prefix ? oz<coord : oz>coord);
                            if(!trimmed) continue;
                            int x=ox+DX[side],z=oz+DZ[side];
                            if (!inside(d,x,z) || road(d,x,z)) {safe=false; break;}
                            if (coast!=null && coast.at(x,z) || d.kindAt(x,z)==WorldLayout.NATURAL) natural=true;
                            else if (d.kindAt(x,z)==WorldLayout.ZONE) {
                                if (opensToNatural(d,coast,x,z)) natural=true;
                            } else safe=false;
                        }
                        if(!safe || !natural) continue;
                        Plan plan=new Plan();
                        plan.original=original; plan.next=next; plan.cutCoord=coord;
                        plan.side=side; plan.removed=removed;
                        plan.alongX=alongX; plan.prefix=prefix;
                        if(best==null || plan.removed>best.removed) best=plan;
                    }
                }
            }
            if (best==null) continue;
            int index=d.blocks.indexOf(original);
            if(index<0) throw new IllegalStateException("Subdistrict no longer owned");
            for(int x=original.minX; x<=original.maxX; x++) for(int z=original.minZ; z<=original.maxZ; z++) {
                if(x>=best.next.minX && x<=best.next.maxX && z>=best.next.minZ && z<=best.next.maxZ) continue;
                boolean separator=best.alongX ? x==best.cutCoord : z==best.cutCoord;
                d.kind[x-d.minX][z-d.minZ]=(byte)(separator ? WorldLayout.STREET : WorldLayout.NATURAL);
                if(separator) d.urbanBoundaryRoads.add(key(x,z));
            }
            d.blocks.set(index,best.next);
            d.contexts.remove(original);
            d.contexts.put(best.next,context);
            replaced++;
        }
        return replaced;
    }
}
