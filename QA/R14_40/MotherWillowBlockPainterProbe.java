package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;

/** Exercises the exact state classifier used by BiomeEcology's live willow painter. */
public final class MotherWillowBlockPainterProbe {
    public static void main(String[] args) {
        Map<String,String> blocks = new HashMap<>();
        MotherWillowGeometry.Sink sink = (x,y,z,state) -> blocks.put(x+","+y+","+z,state);
        MotherWillowGeometry.Terrain terrain = new MotherWillowGeometry.Terrain() {
            public int groundAt(int x,int z) { return 64; }
            public boolean allows(int x,int z) { return true; }
        };
        MotherWillowGeometry.GrowthStats stats=MotherWillowGeometry.growWithStats(sink,terrain,0,0,0x26_03_2026L);
        int wood=0,barkWood=0,logs=0,leaves=0,misclassified=0;
        for(String state:blocks.values()) {
            boolean actualWood=state.contains("poplar_log")||state.contains("poplar_wood");
            boolean actualLeaves=state.contains("yellow_poplar_leaves");
            boolean mappedWood=MotherWillowBlockState.isWood(state);
            boolean mappedLeaves=MotherWillowBlockState.isLeaves(state);
            if(actualWood && !mappedWood)misclassified++;
            if(actualLeaves && mappedWood)misclassified++;
            if(mappedWood) {
                wood++;
                if(MotherWillowBlockState.isBarkWood(state))barkWood++;
                else logs++;
            }
            if(mappedLeaves)leaves++;
        }
        if(misclassified!=0)throw new AssertionError("Wood/leaf adapter misclassified "+misclassified+" geometry blocks");
        if(wood<1500)throw new AssertionError("Live painter would lose its wooden scaffold: "+wood);
        if(barkWood<100)throw new AssertionError("Bark-covered wood sections were not preserved: "+barkWood);
        if(logs<100)throw new AssertionError("Directional log sections were not preserved: "+logs);
        if(leaves<200)throw new AssertionError("Foliage output disappeared: "+leaves);
        if(wood<=leaves)throw new AssertionError("Willow painter output is still leaf-dominated: wood="+wood+", leaves="+leaves);
        if(stats.terminalForks<20)throw new AssertionError("Terminal willow limbs still taper without splitting: forks="+stats.terminalForks);
        if(stats.terminalBranchlets<stats.terminalForks*2)
            throw new AssertionError("Terminal forks are missing their paired/tripled hanging branchlets.");
        System.out.println("MotherWillowBlockPainterProbe PASS: wood="+wood+", barkWood="+barkWood
                +", directionalLogs="+logs+", leaves="+leaves+", misclassified="+misclassified
                +", terminalForks="+stats.terminalForks+", branchlets="+stats.terminalBranchlets);
    }
}
