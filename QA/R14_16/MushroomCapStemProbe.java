package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    private static boolean connected(Voxel start,Voxel target,java.util.Set<Voxel> stems) {
        java.util.ArrayDeque<Voxel> queue=new java.util.ArrayDeque<>();
        java.util.HashSet<Voxel> seen=new java.util.HashSet<>();
        queue.add(start);seen.add(start);
        int[][] directions={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while(!queue.isEmpty()) {
            Voxel current=queue.removeFirst();
            if(current.equals(target))return true;
            for(int[] d:directions) {
                Voxel next=new Voxel(current.x()+d[0],current.y()+d[1],current.z()+d[2]);
                if(seen.add(next)&&stems.contains(next))queue.addLast(next);
            }
        }
        return false;
    }

    private static boolean isStem(String state) {
        return state != null && state.startsWith("minecraft:mushroom_stem[");
    }

    private static boolean isCap(String state) {
        return state != null && (state.startsWith("minecraft:red_mushroom_block[")
                || state.startsWith("minecraft:brown_mushroom_block["));
    }

    public static void main(String[] args) {
        int shapes=0, groupedCapConnections=0;
        for(int sample=0;sample<96;sample++) {
            long seed=0x4D555348524F4F4DL ^ (sample*0x9E3779B97F4A7C15L);
            int x=sample*137-6200;
            int z=sample*83-3900;
            int ground=62+Math.floorMod(x*31+z*17,5)-2;
            for(int variant=0;variant<6;variant++) {
                Map<Voxel,String> voxels=new HashMap<>();
                Set<Voxel> stemCapCollisions=new HashSet<>();
                MushroomGeometry.grow((vx,vy,vz,state)->{
                    Voxel voxel=new Voxel(vx,vy,vz);
                    String previous=voxels.get(voxel);
                    if((isStem(previous)&&isCap(state))||(isCap(previous)&&isStem(state)))
                        stemCapCollisions.add(voxel);
                    voxels.put(voxel,state);
                },
                    new MushroomGeometry.Terrain() {
                        public int groundAt(int gx,int gz) {
                            return 62+Math.floorMod(gx*31+gz*17,5)-2;
                        }
                        public boolean allows(int gx,int gz) { return true; }
                    },x,z,seed,variant);

                if(!stemCapCollisions.isEmpty()) {
                    throw new AssertionError("stem and cap attempted to occupy the same voxel: variant="
                            +variant+" sample="+sample+" voxel="+stemCapCollisions.iterator().next());
                }
                if(variant==5||(variant==4&&MushroomGeometry.unit(MushroomGeometry.mix(seed ^ MushroomGeometry.mix(
                        x*-7046029254386353131L + z*-4417276706812531889L))+55)>.4)) {
                    long geometrySeed=MushroomGeometry.mix(seed ^ MushroomGeometry.mix(
                        x*-7046029254386353131L + z*-4417276706812531889L));
                    int height=(variant==5?29:24)+(int)(MushroomGeometry.unit(geometrySeed+2)*21);
                    double angle=MushroomGeometry.unit(geometrySeed+11)*6.28318530718;
                    double lean=3.0+MushroomGeometry.unit(geometrySeed+15)*10.0;
                    java.util.Set<Voxel> stemCells=new java.util.HashSet<>();
                    for(Map.Entry<Voxel,String> entry:voxels.entrySet())
                        if(entry.getValue().startsWith("minecraft:mushroom_stem["))stemCells.add(entry.getKey());
                    int groupedCaps=variant==5?5:3;
                    for(int branch=0;branch<groupedCaps;branch++) {
                        double theta=branch*2.399963+MushroomGeometry.unit(geometrySeed+branch*421)*.7;
                        double fraction=.3+branch*.11;
                        int by=ground+(int)(height*fraction);
                        double bx=x+Math.cos(angle)*lean*fraction+Math.cos(theta)*6.0;
                        double bz=z+Math.sin(angle)*lean*fraction+Math.sin(theta)*6.0;
                        int capX=(int)Math.round(bx),capZ=(int)Math.round(bz);
                        int capBaseY=by+1;
                        String capAtUnderside=voxels.get(new Voxel(capX,capBaseY,capZ));
                        if(capAtUnderside==null||(!capAtUnderside.startsWith("minecraft:red_mushroom_block[")
                                &&!capAtUnderside.startsWith("minecraft:brown_mushroom_block[")))
                            throw new AssertionError("grouped crown missing its own underside: branch="+branch+" sample="+sample);
                        Voxel neck=new Voxel(capX,capBaseY-1,capZ);
                        if(!stemCells.contains(neck))
                            throw new AssertionError("grouped crown has no stem meeting its own underside: branch="+branch+" sample="+sample);
                        if(!connectedToGround(neck,stemCells))
                            throw new AssertionError("grouped crown stem is not connected through the emitted leaning bole to ground: branch="+branch+" sample="+sample);
                    }
                }

                if(variant==5||(variant==4&&MushroomGeometry.unit(MushroomGeometry.mix(seed ^ MushroomGeometry.mix(
                        x*-7046029254386353131L + z*-4417276706812531889L))+55)>.4)) {
                    groupedCapConnections += variant==5 ? 5 : 3;
                }
                shapes++;
            }
        }
        System.out.println("MushroomCapStemProbe: PASS shapes="+shapes
                +" groupedCapConnections="+groupedCapConnections
                +" (exact block collisions absent; three- and five-cap groups connect at each cap underside to the leaning bole)");
    }
}
