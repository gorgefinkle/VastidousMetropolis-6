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
                        int capRadius=5+(int)(MushroomGeometry.unit(geometrySeed+branch*199)*5.0);
                        boolean attached=false;
                        for(int px=(int)Math.floor(bx-capRadius-1);px<=bx+capRadius+1&&!attached;px++) {
                            for(int pz=(int)Math.floor(bz-capRadius-1);pz<=bz+capRadius+1&&!attached;pz++) {
                                double dx=px-bx,dz=pz-bz,dist=Math.hypot(dx,dz);
                                double edge=Math.atan2(dz,dx);
                                if(dist>capRadius*(1.0+.14*Math.sin(edge*5.0+branch)))continue;
                                int capTop=by+3-(int)((dist/capRadius)*4.0);
                                int underside=capTop-2;
                                Voxel capBlock=new Voxel(px,underside,pz);
                                Voxel stemTop=new Voxel(px,underside-1,pz);
                                if(isCap(voxels.get(capBlock))&&stemCells.contains(stemTop)
                                        &&connectedToGround(stemTop,stemCells)) {
                                    attached=true;
                                }
                            }
                        }
                        if(!attached) {
                            int capFootprint=0, stemColumns=0, possibleGaps=0;
                            int nearestDistance=Integer.MAX_VALUE;
                            Voxel nearestStem=null;
                            for(int px=(int)Math.floor(bx-capRadius-1);px<=bx+capRadius+1;px++) {
                                for(int pz=(int)Math.floor(bz-capRadius-1);pz<=bz+capRadius+1;pz++) {
                                    double dx=px-bx,dz=pz-bz,dist=Math.hypot(dx,dz);
                                    double edge=Math.atan2(dz,dx);
                                    if(dist>capRadius*(1.0+.14*Math.sin(edge*5.0+branch)))continue;
                                    int capTop=by+3-(int)((dist/capRadius)*4.0);
                                    int underside=capTop-2;
                                    if(isCap(voxels.get(new Voxel(px,underside,pz))))capFootprint++;
                                    boolean hasStem=false;
                                    for(Voxel stem:stemCells)if(stem.x()==px&&stem.z()==pz) {
                                        hasStem=true;
                                        int dy=underside-1-stem.y();
                                        if(dy>=0)possibleGaps++;
                                    }
                                    if(hasStem)stemColumns++;
                                }
                            }
                            for(Voxel stem:stemCells) {
                                int dx=stem.x()-(int)Math.round(bx),dz=stem.z()-(int)Math.round(bz);
                                int d=dx*dx+dz*dz;
                                if(d<nearestDistance) {nearestDistance=d;nearestStem=stem;}
                            }
                            throw new AssertionError("grouped cap has no top-layer stem continued to its own underside: branch="
                                    +branch+" sample="+sample+" capBlocks="+capFootprint+" stemColumnsInCap="+stemColumns
                                    +" candidateVerticalGaps="+possibleGaps+" nearestStem="+nearestStem
                                    +" nearestPlanarDistanceSq="+nearestDistance);
                        }
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
                +" (exact collisions absent; every grouped cap has a solid vertical stem from exposed underside to true ground)");
    }
}
