package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;

/** Regression test: a giant mushroom trunk must meet, but never pierce, its cap. */
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
            int ground=-3+Math.floorMod(current.x()*31+current.z()*17,9);
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
                if(variant==5) {
                    long geometrySeed=MushroomGeometry.mix(seed ^ MushroomGeometry.mix(
                        x*-7046029254386353131L + z*-4417276706812531889L));
                    int height=29+(int)(MushroomGeometry.unit(geometrySeed+2)*21);
                    double angle=MushroomGeometry.unit(geometrySeed+11)*6.28318530718;
                    double lean=3.0+MushroomGeometry.unit(geometrySeed+15)*10.0;
                    java.util.HashSet<Voxel> stemCells=new java.util.HashSet<>();
                    for(Map.Entry<Voxel,String> entry:voxels.entrySet())
                        if(entry.getValue().startsWith("minecraft:mushroom_stem["))stemCells.add(entry.getKey());
                    for(int branch=0;branch<5;branch++) {
                        double theta=branch*2.399963+MushroomGeometry.unit(geometrySeed+branch*421)*.7;
                        double fraction=.3+branch*.11;
                        int by=ground+(int)(height*fraction);
                        double bx=x+Math.cos(angle)*lean*fraction+Math.cos(theta)*6.0;
                        double bz=z+Math.sin(angle)*lean*fraction+Math.sin(theta)*6.0;
                        int capX=(int)Math.round(bx),capZ=(int)Math.round(bz);
                        Integer underside=capBottom.get(new Column(capX,capZ));
                        if(underside==null)throw new AssertionError("grouped cap missing at branch="+branch
                                +" sample="+sample);
                        Voxel neck=new Voxel(capX,underside-1,capZ);
                        if(!stemCells.contains(neck))
                            throw new AssertionError("grouped cap has no stem directly under its underside: branch="
                                +branch+" sample="+sample+" cap="+capX+","+capZ+" y="+underside);
                        if(!connectedToGround(neck,stemCells))
                            throw new AssertionError("grouped cap stem does not connect down to a rooted stalk: branch="
                                +branch+" sample="+sample);
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
