package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.HashSet;
import java.util.Set;

/**
 * Protect ALREADY PLANNED street pieces along a countryside/wilderness
 * boundary, plus their immediate street junctions.  The rectangle's road
 * geometry is not altered: this only records mandatory owners before
 * Morphomos decides which same-context internal streets may disappear.
 *
 * A NATURAL cell is an explicit, committed wilderness parcel in the world
 * plan.  Treating it as UNKNOWN here silently converted FARM/FOREST frontage
 * into an eligible FARM/FARM seam and stranded creek-side road corners.
 */
final class RuralBoundaryLinks {
    private RuralBoundaryLinks() {}
    private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}

    private static ContextType blockContext(WorldLayout.Domain d,int x,int z) {
        if(d.kindAt(x,z)!=WorldLayout.BLOCK)return null;
        for(WorldLayout.Rect r:d.blocks) if(x>=r.minX&&x<=r.maxX&&z>=r.minZ&&z<=r.maxZ)
            return d.contexts.get(r);
        return null;
    }
    private static boolean farm(WorldLayout.Domain d,int x,int z) {
        return blockContext(d,x,z)==ContextType.FARM;
    }
    private static boolean wilderness(WorldLayout.Domain d,WorldLayout.Mask water,int x,int z) {
        int k=d.kindAt(x,z);
        if(k==WorldLayout.NATURAL)return true;
        if(k==WorldLayout.BLOCK)return blockContext(d,x,z)==ContextType.FOREST;
        // An unsolved (UNCLAIMED) cell has no committed identity. A ZONE
        // cell, however, is independently reserved for water, shore, or
        // the bounded creek's unbuilt dry bank and is never another FARM.
        return k==WorldLayout.ZONE && water!=null && water.at(x,z);
    }
    private static boolean direct(WorldLayout.Domain d,WorldLayout.Mask water,int x,int z) {
        if(d.kindAt(x,z)!=WorldLayout.STREET)return false;
        return farm(d,x,z+1)&&wilderness(d,water,x,z-1)
            || farm(d,x,z-1)&&wilderness(d,water,x,z+1)
            || farm(d,x+1,z)&&wilderness(d,water,x-1,z)
            || farm(d,x-1,z)&&wilderness(d,water,x+1,z);
    }
    private static boolean street(WorldLayout.Domain d,int x,int z) {
        return d.kindAt(x,z)==WorldLayout.STREET;
    }
    private static boolean sameContextOpposite(WorldLayout.Domain d,int x,int z) {
        ContextType w=blockContext(d,x-1,z),e=blockContext(d,x+1,z);
        ContextType n=blockContext(d,x,z-1),s=blockContext(d,x,z+1);
        return w!=null&&w==e && w.isMorphomos() || n!=null&&n==s && n.isMorphomos();
    }

    static int protect(WorldLayout.Domain d,WorldLayout.Mask water) {
        Set<Long> direct=new HashSet<>();
        for(int x=d.minX;x<=d.maxX;x++)for(int z=d.minZ;z<=d.maxZ;z++)
            if(direct(d,water,x,z))direct.add(key(x,z));
        Set<Long> protectedRoads=new HashSet<>(direct);
        for(int x=d.minX;x<=d.maxX;x++)for(int z=d.minZ;z<=d.maxZ;z++) {
            if(!street(d,x,z)||direct.contains(key(x,z))||sameContextOpposite(d,x,z))continue;
            boolean west=direct.contains(key(x-1,z));
            boolean east=direct.contains(key(x+1,z));
            boolean north=direct.contains(key(x,z-1));
            boolean south=direct.contains(key(x,z+1));
            // One-cell interruption between two legal boundary spans.
            boolean across=(west&&east)||(north&&south);
            // Their terminal corners must also connect into perpendicular
            // existing streets.  Never invent a street in a BLOCK or ZONE.
            boolean turn=(west||east)&&(street(d,x,z-1)||street(d,x,z+1))
                       ||(north||south)&&(street(d,x-1,z)||street(d,x+1,z));
            if(across||turn)protectedRoads.add(key(x,z));
        }
        int added=0;
        for(Long k:protectedRoads)if(d.ruralBoundaryRoads.add(k))added++;
        return added;
    }
}
