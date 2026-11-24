package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;

public final class BloomUnderstoryGeometryProbe {
    private record V(int x,int y,int z) {}
    public static void main(String[] args) {
        if (!VegetationHeightPolicy.allows(-2, -64, 320)
                || !VegetationHeightPolicy.allows(0, -64, 320)
                || VegetationHeightPolicy.allows(-64, -64, 320)
                || VegetationHeightPolicy.allows(318, -64, 320)) {
            throw new AssertionError("vegetation placement still clips legal negative-Y terrain");
        }
        int totalWood=0,totalLeaves=0;
        for(int sample=0;sample<80;sample++) {
            int x=sample*31-1200,z=sample*19-700;
            long seed=0x424c4f4f4dL+sample*0x9e3779b97f4a7c15L;
            Map<V,String> first=new HashMap<>(),again=new HashMap<>();
            BloomUnderstoryGeometry.Terrain terrain=new BloomUnderstoryGeometry.Terrain() {
                public int groundAt(int gx,int gz){return -3+Math.floorMod(gx*7+gz*11,5);}
                public boolean allows(int gx,int gz){return true;}
            };
            BloomUnderstoryGeometry.grow((a,b,c,state)->first.put(new V(a,b,c),state),terrain,x,z,seed,sample%3);
            BloomUnderstoryGeometry.grow((a,b,c,state)->again.put(new V(a,b,c),state),terrain,x,z,seed,sample%3);
            if(!first.equals(again))throw new AssertionError("tree geometry is not deterministic at "+x+","+z);
            String foot=first.get(new V(x,terrain.groundAt(x,z)+1,z));
            if(foot==null||!foot.contains("_log["))throw new AssertionError("small tree lacks a grounded trunk at "+x+","+z);
            for(int[] offset:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int gx=x+offset[0],gz=z+offset[1];
                String rooted=first.get(new V(gx,terrain.groundAt(gx,gz)+1,gz));
                if(rooted==null||!rooted.contains("_log[")) {
                    throw new AssertionError("basal trunk is clipped above uneven local ground at "+gx+","+gz);
                }
            }
            int wood=0,leaves=0,minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE;
            for(Map.Entry<V,String> e:first.entrySet()) {
                V v=e.getKey();
                if(v.y()<=terrain.groundAt(v.x(),v.z()))throw new AssertionError("tree voxel buried at "+v);
                if(e.getValue().contains("_log["))wood++; else leaves++;
                minY=Math.min(minY,v.y());maxY=Math.max(maxY,v.y());
            }
            if(wood<25||leaves<40||maxY-minY>24)
                throw new AssertionError("understory tree lacks a compact woody canopy: wood="+wood+" leaves="+leaves);
            totalWood+=wood;totalLeaves+=leaves;
        }
        System.out.println("BloomUnderstoryGeometryProbe PASS: 80 deterministic trees with terrain-following basal roots; wood="
                +totalWood+" leaves="+totalLeaves);
    }
}
