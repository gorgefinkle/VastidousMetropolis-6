package ch.k42.metropolis.grid.urbanGrid.wild;

public final class MountainKindNoVolcanoProbe {
    public static void main(String[] args) {
        if(MountainKind.ALL.length!=4)
            throw new AssertionError("Only massif, peaks, range and plateau may be selected");
        for(MountainKind kind:MountainKind.ALL) {
            if("volcano".equals(kind.key()))
                throw new AssertionError("Volcano remains a selectable mountain kind");
        }
        // Supply the old fifth configured share: the current four-kind relief
        // must ignore it rather than reviving volcanic profiles.
        WildRelief relief=new WildRelief(0x4D4554524F504F4CL,120.0,4,5,1.0,
                new double[]{40.0,25.0,20.0,8.0,7.0});
        for(int x=-12000;x<=12000;x+=997) {
            for(int z=-12000;z<=12000;z+=991) {
                if(relief.volcanicInfluence(x,z)!=0.0)
                    throw new AssertionError("Legacy volcano share produced terrain at ("+x+","+z+")");
            }
        }
        System.out.println("MountainKindNoVolcanoProbe PASS: four supported forms; stale volcano config ignored");
    }
}