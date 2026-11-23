package ch.k42.metropolis.grid.urbanGrid.ocean;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.SeaPickle;

/** Deterministic seagrass and sea-pickle placement restored from R30ea. */
public final class MarineEcology {
    private MarineEcology() { }

    private static long mix(long h) {
        h ^= h >>> 30;
        h *= -4658895280553007687L;
        h ^= h >>> 27;
        h *= -7723592293110705685L;
        return h ^ (h >>> 31);
    }

    private static double unit(long seed, long x, long z, long salt) {
        return (mix(seed ^ mix(x * -7046029254386353131L + z * -4417276706812531889L + salt)) >>> 11)
                * 0x1.0p-53;
    }

    private static double smooth(double t) {
        t = Math.min(1.0, Math.max(0.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    private static double patch(long seed, int x, int z, double scale, long salt) {
        double fx = x / scale, fz = z / scale;
        long ix = (long) Math.floor(fx), iz = (long) Math.floor(fz);
        double tx = smooth(fx - ix), tz = smooth(fz - iz);
        double a = unit(seed,ix,iz,salt) * (1.0 - tx) + unit(seed,ix + 1,iz,salt) * tx;
        double b = unit(seed,ix,iz + 1,salt) * (1.0 - tx) + unit(seed,ix + 1,iz + 1,salt) * tx;
        return a * (1.0 - tz) + b * tz;
    }

    public static int habitat(long seed, int x, int z, int depth, double shoreDistance) {
        if (depth < 8 || depth > 40 || shoreDistance < 24.0) return 0;
        double density = patch(seed,x,z,43.0,84959843077975L) * 0.72
                + patch(seed,x,z,17.0,301927977537L) * 0.28;
        if (density < 0.48) return 0;
        double coin = unit(seed,x,z,91604443419988L);
        double chance = 0.11 + Math.min(0.25, (density - 0.48) * 0.8);
        if (coin > chance) return 0;
        return unit(seed,x,z,88275591842885L) < 0.085 ? 2 : 1;
    }

    public static boolean candidate(long seed, int x, int z, int depth, double shoreDistance) {
        return habitat(seed,x,z,depth,shoreDistance) != 0;
    }

    public static void paint(World world, long seed, int x, int z, int floorY, int seaY,
                             int depth, double shoreDistance) {
        if (!OceanConfig.isOceanSeabedDetailsEnabled()) return;
        if (floorY < world.getMinHeight() + 2 || floorY + 5 >= seaY) return;
        int habitat = habitat(seed,x,z,depth,shoreDistance);
        if (habitat == 0) return;
        Block floor = world.getBlockAt(x,floorY,z);
        Block water = world.getBlockAt(x,floorY + 1,z);
        Material ground = floor.getType();
        if (water.getType() != Material.WATER) return;
        if (ground != Material.STONE && ground != Material.TUFF && ground != Material.ANDESITE
                && ground != Material.SAND && ground != Material.GRAVEL && ground != Material.CLAY) return;
        if (habitat == 1) {
            water.setType(Material.SEAGRASS,false);
        } else {
            water.setType(Material.SEA_PICKLE,false);
            BlockData data = water.getBlockData();
            if (data instanceof SeaPickle) {
                SeaPickle pickle = (SeaPickle) data;
                pickle.setPickles(1 + (int)(unit(seed,x,z,1347767372L) * 3.0));
                pickle.setWaterlogged(true);
                water.setBlockData(pickle,false);
            }
        }
    }
}
