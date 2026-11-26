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
        int wood=0,leaves=0,fences=0,legacyOak=0,nearLeaves=0,outerLeaves=0;
        double maxRadius=0.0;
        for (Map.Entry<String,String> entry:blocks.entrySet()) {
            String[] xyz=entry.getKey().split(",");
            int x=Integer.parseInt(xyz[0]),y=Integer.parseInt(xyz[1]),z=Integer.parseInt(xyz[2]);
            minY=Math.min(minY,y);maxY=Math.max(maxY,y);
            double radius=Math.hypot(x,z);
            maxRadius=Math.max(maxRadius,radius);
            String state=entry.getValue();
            if(state.contains("poplar_log")||state.contains("poplar_wood"))wood++;
            if(state.contains("yellow_poplar_leaves")) {
                leaves++;
                if(radius<30.0)nearLeaves++;
                else outerLeaves++;
            }
            if(state.contains("oak_"))legacyOak++;
            if(state.contains("fence"))fences++;
        }
        if(maxY<245)throw new AssertionError("Willow did not reach old-growth scale: maxY="+maxY);
        if(minY>54)throw new AssertionError("Lateral root anchors lost their depth: minY="+minY);
        if(wood<1500)throw new AssertionError("Continuous wooden scaffold/finger branches are missing: "+wood);
        if(leaves<200)throw new AssertionError("Sparse weeping foliage is missing: "+leaves);
        if(maxRadius<65.0)throw new AssertionError("Broad outward crown was lost: radius="+maxRadius);
        if(nearLeaves>Math.max(100,leaves/4))
            throw new AssertionError("Foliage is still too dense beside the trunk: near="+nearLeaves+", total="+leaves);
        if(outerLeaves<=nearLeaves)
            throw new AssertionError("Foliage is not concentrated on the outer weeping crown.");
        if(fences!=0)throw new AssertionError("Fence blocks must not be used as branches: "+fences);
        if(legacyOak!=0)throw new AssertionError("Legacy oak materials remain in the new willow: "+legacyOak);
        System.out.println("MotherWillowGeometryProbe PASS: y="+minY+".."+maxY
                +", crown radius="+Math.round(maxRadius)+", woody voxels="+wood
                +", leaf voxels="+leaves+", inner/outer leaves="+nearLeaves+"/"+outerLeaves);
    }
}
