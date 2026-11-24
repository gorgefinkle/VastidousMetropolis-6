package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;

/** Every mushroom profile must replace single-block forest cover at its foot. */
public final class MushroomGroundContactProbe {
    private record Voxel(int x, int y, int z) {}

    public static void main(String[] args) {
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
            int ground = 63 + Math.floorMod(x * 31 + z * 17, 9) - 4;
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
                        return 63 + Math.floorMod(gx * 31 + gz * 17, 9) - 4;
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
                Map<Long,Integer> lowestStem = new HashMap<>();
                for (Map.Entry<Voxel,String> entry : placed.entrySet()) {
                    if (!MushroomGeometry.STEM.equals(entry.getValue())) continue;
                    Voxel voxel = entry.getKey();
                    long key = ((long) voxel.x() << 32) | (voxel.z() & 0xffffffffL);
                    lowestStem.merge(key, voxel.y(), Math::min);
                }
                for (Map.Entry<Long,Integer> stemColumn : lowestStem.entrySet()) {
                    int sx = (int) (stemColumn.getKey() >> 32);
                    int sz = (int) (long) stemColumn.getKey();
                    int expected = 63 + Math.floorMod(sx * 31 + sz * 17, 9) - 4 + 1;
                    if (stemColumn.getValue() != expected) {
                        throw new AssertionError("stalk column does not reach its own uneven ground: variant="
                                + variant + " sample=" + sample + " at=" + sx + "," + sz
                                + " lowest=" + stemColumn.getValue() + " expected=" + expected);
                    }
                }
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
