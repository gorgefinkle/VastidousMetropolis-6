package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;

/** Regression test: original mushroom trunk geometry meets, but never pierces, its cap. */
public final class MushroomCapStemProbe {
    private record Voxel(int x,int y,int z) {}
    private record Column(int x,int z) {}

    private static boolean connectedToGround(Voxel start,java.util.Set<Voxel> stems) {
        java.util.ArrayDeque<Voxel> queue=new java.util.ArrayDeque<>();
        java.util.HashSet<Voxel> seen=new java.util.HashSet<>();
        queue.add(start);seen.add(start);
        int[][] directions={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while(!queue.isEmpty()) {
            Voxel current=queue.removeFirst();
            int ground=60+Math.floorMod(current.x()*31+current.z()*17,5);
            if(current.y()==ground+1)return true;
            for(int[] d:directions) {
                Voxel next=new Voxel(current.x()+d[0],current.y()+d[1],current.z()+d[2]);
                if(seen.add(next)&&stems.contains(next))queue.addLast(next);
            }
        }
        return false;
    }

    public static void main(String[] args) {
        int shapes=0, overlappingColumns=0;
        for(int sample=0;sample<96;sample++) {
            long seed=0x4D555348524F4F4DL ^ (sample*0x9E3779B97F4A7C15L);
            int x=sample*137-6200;
            int z=sample*83-3900;
            int ground=62+Math.floorMod(x*31+z*17,5)-2;
            for(int variant=0;variant<6;variant++) {
                Map<Voxel,String> voxels=new HashMap<>();
                MushroomGeometry.grow((vx,vy,vz,state)->voxels.put(new Voxel(vx,vy,vz),state),
                    new MushroomGeometry.Terrain() {
                        public int groundAt(int gx,int gz) {
                            return 62+Math.floorMod(gx*31+gz*17,5)-2;
                        }
                        public boolean allows(int gx,int gz) { return true; }
                    },x,z,seed,variant);

                Map<Column,Integer> capBottom=new HashMap<>();
                Map<Column,Integer> stemTop=new HashMap<>();
                for(Map.Entry<Voxel,String> entry:voxels.entrySet()) {
                    Voxel v=entry.getKey();
                    Column c=new Column(v.x(),v.z());
                    String state=entry.getValue();
                    if(state.startsWith("minecraft:red_mushroom_block[")
                            ||state.startsWith("minecraft:brown_mushroom_block[")) {
                        capBottom.merge(c,v.y(),Math::min);
                    } else if(state.startsWith("minecraft:mushroom_stem[")) {
                        stemTop.merge(c,v.y(),Math::max);
                    }
                }
                int overlaps=0;
                for(Map.Entry<Column,Integer> entry:stemTop.entrySet()) {
                    Integer firstCap=capBottom.get(entry.getKey());
                    if(firstCap==null)continue; // exterior buttress column, not a cap penetration
                    overlaps++;
                    if(entry.getValue()>=firstCap) {
                        throw new AssertionError("stem pierces cap: variant="+variant+" sample="+sample
                                +" column="+entry.getKey()+" stemTopY="+entry.getValue()
                                +" capUndersideY="+firstCap);
                    }
                }
                if(overlaps==0)throw new AssertionError("no trunk/cap join columns for variant="+variant+" sample="+sample);
                overlappingColumns+=overlaps;
                shapes++;
            }
        }
        System.out.println("MushroomCapStemProbe: PASS shapes="+shapes
                +" overlappingColumns="+overlappingColumns
                +" (96 sites x all 6 profiles; no stem reaches or crosses cap underside)");
    }
}
