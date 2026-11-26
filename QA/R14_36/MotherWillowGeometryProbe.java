package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;

public final class MotherWillowGeometryProbe {
    public static void main(String[] args) {
        Map<String,String> blocks = new HashMap<>();
        MotherWillowGeometry.Sink sink = (x,y,z,state) -> blocks.put(x+","+y+","+z,state);
        MotherWillowGeometry.Terrain terrain = new MotherWillowGeometry.Terrain() {
            public int groundAt(int x,int z) { return 64; }
            public boolean allows(int x,int z) { return true; }
        };
        MotherWillowGeometry.grow(sink,terrain,0,0,0x26_03_2026L);

        int minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE;
        int wood=0,leaves=0,fences=0,legacyOak=0;
        for (Map.Entry<String,String> entry:blocks.entrySet()) {
            String[] xyz=entry.getKey().split(",");
            int y=Integer.parseInt(xyz[1]);
            minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            String state=entry.getValue();
            if(state.contains("poplar_log")||state.contains("poplar_wood"))wood++;
            if(state.contains("yellow_poplar_leaves"))leaves++;
            if(state.contains("oak_"))legacyOak++;
            if(state.contains("fence"))fences++;
        }
        if(maxY<245)throw new AssertionError("Willow did not reach old-growth scale: maxY="+maxY);
        if(minY>54)throw new AssertionError("Lateral root anchors lost their depth: minY="+minY);
        if(wood<1500)throw new AssertionError("Woody scaffold/finger branches are missing: "+wood);
        if(leaves<1000)throw new AssertionError("Pendulous foliage is too sparse: "+leaves);
        if(fences!=0)throw new AssertionError("Fence blocks must not be used as foliage: "+fences);
        if(legacyOak!=0)throw new AssertionError("Legacy oak materials remain in the new willow: "+legacyOak);
        System.out.println("MotherWillowGeometryProbe PASS: y="+minY+".."+maxY
                +", woody voxels="+wood+", golden leaf voxels="+leaves);
    }
}
