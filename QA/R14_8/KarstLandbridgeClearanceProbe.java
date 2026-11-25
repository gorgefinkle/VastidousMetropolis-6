package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.Random;

/** Confirms artificial Karst landbridges are disabled while natural massif relief remains. */
public final class KarstLandbridgeClearanceProbe {
    private static void check(boolean ok,String message) {
        if(!ok)throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long[] seeds={-1482741523194359869L,1750414970189483479L,1414199025253064524L};
        for(long seed:seeds) {
            Random random=new Random(seed ^ 0x4b415253544e4f42L);
            for(int sample=0;sample<4000;sample++) {
                int x=random.nextInt(200001)-100000;
                int z=random.nextInt(200001)-100000;
                check(KarstLandforms.archWeight(seed,x,z)==0.0,
                        "Karst arch coverage remained enabled at "+x+","+z);
                double natural=KarstLandforms.unbridgedRelief(seed,x,z);
                check(Math.abs(KarstLandforms.relief(seed,x,z,false,true)-natural)<1e-9,
                        "bridge flag still changes natural Karst relief at "+x+","+z);
                int top=65+(int)Math.round(natural);
                check(KarstLandforms.bridgeFloor(seed,x,z,top,65,1)==-1,
                        "retired Karst bridge still returns a floor at "+x+","+z);
                check(KarstLandforms.bridgeCeiling(seed,x,z,top)==-1,
                        "retired Karst bridge still returns an undercut at "+x+","+z);
                check(!KarstLandforms.bridgeApproachActive(seed,x,z),
                        "retired Karst bridge approach erosion is still active");
                check(!KarstLandforms.erodeBridgeShoulder(seed,x,top-20,z,top,top-60,top-12),
                        "retired Karst bridge shoulder erosion is still active");
            }
        }
        System.out.println("KarstLandbridgeClearanceProbe PASS: zero artificial spans; natural massif/ravine relief retained");
    }
}
