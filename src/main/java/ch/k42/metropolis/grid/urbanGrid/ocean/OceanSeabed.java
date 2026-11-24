package ch.k42.metropolis.grid.urbanGrid.ocean;

import org.bukkit.Material;
import org.bukkit.World;

/** R30ea seabed terrain/vegetation painter restored from the shipped method body. */
public final class OceanSeabed {
    private OceanSeabed() { }

    public static int maximumFeatureHeight(int depth) {
        if (!OceanConfig.isOceanSeabedDetailsEnabled() || depth < 3) return 0;
        return MarineEcology.maximumFeatureHeight(depth);
    }

    private static long mix(long h) {
        h ^= h >>> 30;
        h *= -4658895280553007687L;
        h ^= h >>> 27;
        h *= -7723592293110705685L;
        return h ^ (h >>> 31);
    }

    private static double cell(long seed, long x, long z, long salt) {
        long hash = mix(seed ^ mix(x * -7046029254386353131L
                + z * -4417276706812531889L + salt));
        return (hash >>> 11) * 0x1.0p-53;
    }

    private static double smooth(double t) { return t * t * (3.0 - 2.0 * t); }

    public static double patch(long seed, int x, int z, double scale, long salt) {
        double fx = x / scale, fz = z / scale;
        long ix = (long)Math.floor(fx), iz = (long)Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double a = cell(seed,ix,iz,salt) * (1.0 - tx)
                + cell(seed,ix + 1,iz,salt) * tx;
        double b = cell(seed,ix,iz + 1,salt) * (1.0 - tx)
                + cell(seed,ix + 1,iz + 1,salt) * tx;
        return a * (1.0 - tz) + b * tz;
    }

    public static void paint(World world, long seed, int x, int z,
                             int floorY, int seaY, int depth, double shoreDistance) {
        if (!OceanConfig.isOceanSeabedDetailsEnabled() || depth < 2 || floorY <= 1) {
            MarineEcology.paint(world,seed,x,z,floorY,seaY,depth,shoreDistance);
            return;
        }
        double scale = OceanConfig.getOceanSeabedPatchSizeBlocks();
        double bottomPatch = patch(seed,x,z,scale,1397048397L);
        double rocks = patch(seed,x,z,scale * 1.8,1380926283L);
        double detail = cell(seed,x,z,1396916564L);
        Material bottom;
        if (rocks > 0.67 || (rocks > 0.55 && detail < 0.22)) {
            bottom = detail > 0.68 ? Material.ANDESITE
                    : detail > 0.28 ? Material.STONE : Material.TUFF;
        } else if (bottomPatch > 0.69 && depth >= 9) {
            bottom = detail < 0.76 ? Material.CLAY : Material.GRAVEL;
        } else if (bottomPatch < 0.39) {
            bottom = detail < 0.78 ? Material.SAND : Material.GRAVEL;
        } else {
            bottom = detail < 0.58 ? Material.GRAVEL
                    : detail < 0.81 ? Material.SAND : Material.CLAY;
        }
        world.getBlockAt(x, floorY, z).setType(bottom, false);
        boolean isRock = bottom == Material.STONE || bottom == Material.TUFF || bottom == Material.ANDESITE;
        int thickness = isRock ? 1 : 2 + (int)(cell(seed,x,z,1146115144L) * 3.0);
        for (int y = floorY - 1; y > Math.max(1, floorY - thickness); --y) {
            Material backing = bottom == Material.CLAY ? Material.CLAY
                    : bottom == Material.SAND ? Material.SANDSTONE
                    : bottom == Material.GRAVEL ? Material.GRAVEL : Material.STONE;
            world.getBlockAt(x, y, z).setType(backing, false);
        }
        if (depth >= 9 && shoreDistance >= 28.0 && rocks > 0.55) {
            int spacing = 23;
            int gx = Math.floorDiv(x, spacing), gz = Math.floorDiv(z, spacing);
            for (int ix = gx - 1; ix <= gx + 1; ix++) {
                for (int iz = gz - 1; iz <= gz + 1; iz++) {
                    if (cell(seed,ix,iz,1380077388L)
                            >= OceanConfig.getOceanSeabedRockPercent()/100.0) continue;
                    int cx = ix*spacing + 4 + (int)(cell(seed,ix,iz,1113080652L)*(spacing-8));
                    int cz = iz*spacing + 4 + (int)(cell(seed,ix,iz,1113211724L)*(spacing-8));
                    double radius = 2.1 + cell(seed,ix,iz,1112686916L)*2.6;
                    double distance = Math.hypot(x-cx,z-cz);
                    if (distance >= radius) continue;
                    int max = Math.min(depth < 13 ? 1 : 3,depth-5);
                    int height = Math.max(0,Math.min(max,(int)Math.ceil((radius-distance)*0.72)));
                    for (int dy = 1;dy <= height;dy++) {
                        world.getBlockAt(x,floorY+dy,z).setType(detail > 0.6 ? Material.STONE
                                : detail > 0.26 ? Material.ANDESITE : Material.TUFF,false);
                    }
                    MarineEcology.paint(world,seed,x,z,floorY,seaY,depth,shoreDistance);
                    return;
                }
            }
        }
        // Habitat-specific plants and formations are painted by one deterministic
        // ecological model so meadow, kelp, reef, sponge and vent patches stay
        // coherent instead of competing as independent per-column rolls.
        MarineEcology.paint(world,seed,x,z,floorY,seaY,depth,shoreDistance);
    }
}
