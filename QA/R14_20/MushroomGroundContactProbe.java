package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;

/** Every mushroom profile must replace single-block forest cover at its foot. */
public final class MushroomGroundContactProbe {
    private record Voxel(int x, int y, int z) {}

    private static void verifyBottomOnlyExtension() {
        java.util.Set<MushroomGeometry.P> authored = new java.util.HashSet<>();
        authored.add(new MushroomGeometry.P(0, 10, 0));
        authored.add(new MushroomGeometry.P(0, 11, 0));
        authored.add(new MushroomGeometry.P(0, 12, 0));
        authored.add(new MushroomGeometry.P(1, 14, 0));
        authored.add(new MushroomGeometry.P(1, 15, 0));
        authored.add(new MushroomGeometry.P(1, 16, 0));
        authored.add(new MushroomGeometry.P(2, 18, 0));

        Map<Long,Integer> capBottom = new HashMap<>();
        capBottom.put(0L, 13);
        capBottom.put(1L << 32, 17);
        capBottom.put(2L << 32, 19);
        java.util.Set<MushroomGeometry.P> emitted = new java.util.HashSet<>();
        MushroomGeometry.emitTrunkStems((x,y,z,state) -> {
            if (MushroomGeometry.STEM.equals(state)) emitted.add(new MushroomGeometry.P(x,y,z));
        }, new MushroomGeometry.Terrain() {
            public int groundAt(int x,int z) {
                return x == 0 ? 5 : x == 1 ? 12 : 17;
            }
            public boolean allows(int x,int z) { return true; }
        }, authored, capBottom, 10);

        java.util.Set<MushroomGeometry.P> expected = new java.util.HashSet<>();
        for(int y=6;y<=12;y++)expected.add(new MushroomGeometry.P(0,y,0));
        for(int y=14;y<=16;y++)expected.add(new MushroomGeometry.P(1,y,0));
        expected.add(new MushroomGeometry.P(2,18,0));
        if(!emitted.equals(expected)) {
            throw new AssertionError("stem correction changed the leaning bole instead of extending only its original base row; "
                    + "expected="+expected.size()+" voxels actual="+emitted.size());
        }
    }

    private static void verifyUnevenRootRow(int x,int z,long worldSeed,int variant,
                                             Map<Voxel,String> placed) {
        long seed=MushroomGeometry.mix(worldSeed ^ MushroomGeometry.mix(
                x*-7046029254386353131L + z*-4417276706812531889L));
        int mode=Math.floorMod(variant,6);
        int ground=-3+Math.floorMod(x*31+z*17,9);
        int height=(mode==5?29:24)+(int)(MushroomGeometry.unit(seed+2)*21);
        double angle=MushroomGeometry.unit(seed+11)*6.28318530718;
        double lean=3.0+MushroomGeometry.unit(seed+15)*10.0;
        double t=1.0/height;
        double ease=t*t*(3.0-2.0*t);
        double cx=x+Math.cos(angle)*lean*ease+Math.sin(t*4.1+seed%37)*t*1.3;
        double cz=z+Math.sin(angle)*lean*ease+Math.cos(t*3.5+seed%29)*t*1.5;
        double rad=(mode==1?3.6:2.2)+(1.0-t)*(mode==1?3.1:2.7)
                +2.0*Math.pow(1.0-t,5.0);
        if(mode==4)rad+=.6*Math.sin(t*15.0+seed%100);
        int checked=0;
        for(int px=(int)(cx-rad-1);px<=cx+rad+1;px++)
            for(int pz=(int)(cz-rad-1);pz<=cz+rad+1;pz++) {
                double dist=Math.hypot(px-cx,pz-cz);
                if(dist>=rad*(.94+.08*MushroomGeometry.unit(
                        MushroomGeometry.mix(seed ^ px*257L ^ pz*433L))))continue;
                int localGround=-3+Math.floorMod(px*31+pz*17,9);
                if(localGround>ground)continue; // uphill columns meet the bole higher up naturally
                Voxel expectedRoot=new Voxel(px,localGround+1,pz);
                if(!MushroomGeometry.STEM.equals(placed.get(expectedRoot))) {
                    throw new AssertionError("original bole base row floats above lower terrain: variant="+variant
                            +" at="+px+","+pz+" localGround="+localGround+" expectedRootY="+(localGround+1));
                }
                checked++;
            }
        if(checked==0)throw new AssertionError("root-row probe did not sample any terrain columns");
    }

    public static void main(String[] args) {
        verifyBottomOnlyExtension();
        if (!VegetationHeightPolicy.allows(-2, -64, 320))
            throw new AssertionError("negative-Y mushroom stalks are still blocked by the world-height policy");
        Material[] groundCover = {
            Material.TALL_GRASS, Material.DEAD_BUSH, Material.DANDELION,
            Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM,
            Material.AZURE_BLUET, Material.RED_TULIP, Material.ORANGE_TULIP,
            Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY
        };
        // Check every material which the forest ground-cover painter can put
        // in the first stalk cell. This tests the exact placement gate directly.
        for (Material cover : groundCover) {
            if (!BiomeEcology.replaceable(cover)) {
                throw new AssertionError("natural ground cover not replaceable by stalk: " + cover);
            }
        }

        // Geometry is identical regardless of the vegetation occupying that
        // cell, so generate each shape once instead of repeating it per cover.
        int shapes = 0;
        for (int sample = 0; sample < 80; sample++) {
            int x = sample * 137 - 5300;
            int z = sample * 83 - 4100;
            int ground = -3 + Math.floorMod(x * 31 + z * 17, 9);
            for (int variant = 0; variant < 6; variant++) {
                Map<Voxel, String> placed = new HashMap<>();
                MushroomGeometry.grow((vx, vy, vz, state) -> {
                    Material existing = (vx == x && vz == z && vy == ground + 1)
                            ? Material.TALL_GRASS : Material.AIR;
                    if (existing == Material.AIR || BiomeEcology.replaceable(existing)) {
                        placed.put(new Voxel(vx, vy, vz), state);
                    }
                }, new MushroomGeometry.Terrain() {
                    @Override public int groundAt(int gx, int gz) {
                        return -3 + Math.floorMod(gx * 31 + gz * 17, 9);
                    }
                    @Override public boolean allows(int gx, int gz) { return true; }
                }, x, z, 0x4D555348524F4F4DL ^ (sample * 0x9E3779B97F4A7C15L),
                variant);

                String foot = placed.get(new Voxel(x, ground + 1, z));
                if (!MushroomGeometry.STEM.equals(foot)) {
                    throw new AssertionError("floating mushroom foot: variant=" + variant
                            + " sample=" + sample + " ground cover=TALL_GRASS"
                            + " groundY=" + ground + " rootCell=" + foot);
                }
                for(Map.Entry<Voxel,String> entry:placed.entrySet()) {
                    if(!MushroomGeometry.STEM.equals(entry.getValue()))continue;
                    Voxel voxel=entry.getKey();
                    int localGround=-3+Math.floorMod(voxel.x()*31+voxel.z()*17,9);
                    if(voxel.y()<=localGround)
                        throw new AssertionError("stem was written inside terrain: variant="+variant
                                +" sample="+sample+" at="+voxel.x()+","+voxel.y()+","+voxel.z());
                }
                verifyUnevenRootRow(x,z,0x4D555348524F4F4DL ^ (sample*0x9E3779B97F4A7C15L),
                        variant,placed);
                shapes++;
            }
        }

        for (Material solid : new Material[] {Material.STONE, Material.DIRT, Material.GRASS_BLOCK,
                Material.PODZOL, Material.SAND, Material.TUFF}) {
            if (BiomeEcology.replaceable(solid)) {
                throw new AssertionError("solid terrain unexpectedly replaceable: " + solid);
            }
        }
        System.out.println("MushroomGroundContactProbe PASS: " + shapes
                + " stalk feet replace forest cover and touch ground; solid terrain remains protected");
    }
}
