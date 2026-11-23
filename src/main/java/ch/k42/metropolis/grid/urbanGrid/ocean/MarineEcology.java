package ch.k42.metropolis.grid.urbanGrid.ocean;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.SeaPickle;

/** Deterministic painter for coherent seagrass, kelp, coral, sponge and vent habitats. */
public final class MarineEcology {
    private static final long SALT_DETAIL = 0x4D4152494E450511L;
    private static final long SALT_HEIGHT = 0x4D4152494E450522L;
    private static final long SALT_COLOR = 0x4D4152494E450533L;
    private static final long SALT_PICKLE = 0x4D4152494E450544L;
    private static final long SALT_VENT = 0x4D4152494E450555L;

    private static final Material[] CORAL_BLOCKS = {
            Material.TUBE_CORAL_BLOCK,
            Material.BRAIN_CORAL_BLOCK,
            Material.BUBBLE_CORAL_BLOCK,
            Material.FIRE_CORAL_BLOCK,
            Material.HORN_CORAL_BLOCK
    };
    private static final Material[] CORAL_FANS = {
            Material.TUBE_CORAL_FAN,
            Material.BRAIN_CORAL_FAN,
            Material.BUBBLE_CORAL_FAN,
            Material.FIRE_CORAL_FAN,
            Material.HORN_CORAL_FAN
    };

    private MarineEcology() { }

    private static long mix(long h) {
        h ^= h >>> 30;
        h *= -4658895280553007687L;
        h ^= h >>> 27;
        h *= -7723592293110705685L;
        return h ^ (h >>> 31);
    }

    private static double unit(long seed, long x, long z, long salt) {
        return (mix(seed ^ mix(x * -7046029254386353131L
                + z * -4417276706812531889L + salt)) >>> 11) * 0x1.0p-53;
    }

    public static int habitat(long seed, int x, int z, int depth, double shoreDistance) {
        return MarineHabitatModel.habitat(seed, x, z, depth, shoreDistance);
    }

    public static int maximumFeatureHeight(int depth) {
        return MarineHabitatModel.maximumFeatureHeight(depth);
    }

    private static boolean naturalSubstrate(Material material) {
        return material == Material.STONE || material == Material.TUFF
                || material == Material.ANDESITE || material == Material.SAND
                || material == Material.GRAVEL || material == Material.CLAY
                || material == Material.BASALT || material == Material.BLACKSTONE;
    }

    private static boolean clearWater(World world, int x, int z, int floorY, int height, int seaY) {
        if (floorY + height >= seaY) return false;
        for (int dy = 1; dy <= height; dy++) {
            if (world.getBlockAt(x, floorY + dy, z).getType() != Material.WATER) return false;
        }
        return true;
    }

    private static void placeWaterlogged(World world, int x, int y, int z, Material material) {
        Block block = world.getBlockAt(x, y, z);
        if (block.getType() != Material.WATER) return;
        BlockData data = Bukkit.createBlockData(material);
        if (data instanceof Waterlogged waterlogged) {
            waterlogged.setWaterlogged(true);
        }
        block.setBlockData(data, false);
    }

    private static void paintSeagrass(World world, long seed, int x, int z,
                                      int floorY, int seaY, int depth) {
        double roll = unit(seed, x, z, SALT_DETAIL);
        if (roll >= Math.min(1.0, OceanConfig.getOceanSeabedSeagrassPercent() / 100.0 * 2.35)) {
            return;
        }
        Block water = world.getBlockAt(x, floorY + 1, z);
        if (water.getType() != Material.WATER) return;
        if (depth >= 8 && unit(seed, x, z, SALT_PICKLE) < 0.075) {
            BlockData data = Bukkit.createBlockData(Material.SEA_PICKLE);
            if (data instanceof SeaPickle pickle) {
                pickle.setPickles(1 + (int) (unit(seed, x, z, SALT_PICKLE + 1) * 4.0));
                pickle.setWaterlogged(true);
                water.setBlockData(pickle, false);
            }
        } else {
            water.setType(Material.SEAGRASS, false);
        }
    }

    private static void paintKelp(World world, long seed, int x, int z,
                                  int floorY, int seaY, int depth) {
        double roll = unit(seed, x, z, SALT_DETAIL);
        if (roll >= Math.min(1.0, OceanConfig.getOceanSeabedKelpPercent() / 100.0 * 2.9)) {
            return;
        }
        int cap = Math.min(depth - 3, depth < 13 ? 3 : 6);
        if (cap < 2) return;
        int length = Math.min(cap, 2 + (int) (unit(seed, x, z, SALT_HEIGHT) * 5.0));
        if (!clearWater(world, x, z, floorY, length, seaY)) return;
        for (int dy = 1; dy < length; dy++) {
            world.getBlockAt(x, floorY + dy, z).setType(Material.KELP_PLANT, false);
        }
        world.getBlockAt(x, floorY + length, z).setType(Material.KELP, false);
    }

    private static void paintCoral(World world, long seed, int x, int z,
                                   int floorY, int seaY, int depth) {
        double roll = unit(seed, x, z, SALT_DETAIL);
        double density = 0.52 + OceanConfig.getOceanSeabedRockPercent() / 100.0 * 0.9;
        if (roll >= density) return;
        Block floor = world.getBlockAt(x, floorY, z);
        if (!naturalSubstrate(floor.getType())) return;

        int color = Math.min(CORAL_BLOCKS.length - 1,
                (int) (unit(seed, x, z, SALT_COLOR) * CORAL_BLOCKS.length));
        double form = unit(seed, x, z, SALT_COLOR + 1);
        if (form < 0.53) {
            floor.setType(CORAL_BLOCKS[color], false);
            return;
        }

        if (depth >= 9 && form > 0.82 && clearWater(world, x, z, floorY, 2, seaY)) {
            Block stem = world.getBlockAt(x, floorY + 1, z);
            stem.setType(CORAL_BLOCKS[color], false);
            placeWaterlogged(world, x, floorY + 2, z, CORAL_FANS[color]);
        } else {
            placeWaterlogged(world, x, floorY + 1, z, CORAL_FANS[color]);
        }
    }

    private static void paintSponge(World world, long seed, int x, int z,
                                    int floorY, int seaY, int depth) {
        double roll = unit(seed, x, z, SALT_DETAIL);
        double density = 0.42 + OceanConfig.getOceanSeabedRockPercent() / 100.0 * 0.75;
        if (roll >= density) return;
        Block first = world.getBlockAt(x, floorY + 1, z);
        if (first.getType() != Material.WATER) return;
        first.setType(Material.WET_SPONGE, false);
        if (depth >= 22 && unit(seed, x, z, SALT_HEIGHT) < 0.16
                && floorY + 2 < seaY
                && world.getBlockAt(x, floorY + 2, z).getType() == Material.WATER) {
            world.getBlockAt(x, floorY + 2, z).setType(Material.WET_SPONGE, false);
        }
    }

    private static void paintVent(World world, long seed, int x, int z,
                                  int floorY, int seaY, int depth) {
        final int spacing = 17;
        int gx = Math.floorDiv(x, spacing);
        int gz = Math.floorDiv(z, spacing);
        for (int ix = gx - 1; ix <= gx + 1; ix++) {
            for (int iz = gz - 1; iz <= gz + 1; iz++) {
                if (unit(seed, ix, iz, SALT_VENT) >= 0.42) continue;
                int cx = ix * spacing + 4 + (int) (unit(seed, ix, iz, SALT_VENT + 1) * (spacing - 8));
                int cz = iz * spacing + 4 + (int) (unit(seed, ix, iz, SALT_VENT + 2) * (spacing - 8));
                double radius = 1.5 + unit(seed, ix, iz, SALT_VENT + 3) * 1.6;
                double distance = Math.hypot(x - cx, z - cz);
                if (distance >= radius) continue;

                Block floor = world.getBlockAt(x, floorY, z);
                if (!naturalSubstrate(floor.getType())) return;
                double core = unit(seed, ix, iz, SALT_VENT + 4);
                floor.setType(core < 0.18 ? Material.MAGMA_BLOCK : Material.BLACKSTONE, false);

                int cap = Math.min(5, depth - 7);
                int height = Math.max(1, Math.min(cap,
                        1 + (int) Math.ceil((radius - distance) * 1.25)));
                if (!clearWater(world, x, z, floorY, height, seaY)) return;
                for (int dy = 1; dy <= height; dy++) {
                    Material chimney = dy == height && core > 0.52
                            ? Material.BASALT
                            : (dy % 2 == 0 ? Material.TUFF : Material.BLACKSTONE);
                    world.getBlockAt(x, floorY + dy, z).setType(chimney, false);
                }
                return;
            }
        }
    }

    public static void paint(World world, long seed, int x, int z, int floorY, int seaY,
                             int depth, double shoreDistance) {
        if (!OceanConfig.isOceanSeabedDetailsEnabled()) return;
        if (floorY < world.getMinHeight() + 2 || floorY + 1 >= seaY) return;

        int habitat = habitat(seed, x, z, depth, shoreDistance);
        if (habitat == MarineHabitatModel.BARREN) return;

        switch (habitat) {
            case MarineHabitatModel.SEAGRASS_MEADOW ->
                    paintSeagrass(world, seed, x, z, floorY, seaY, depth);
            case MarineHabitatModel.KELP_FOREST ->
                    paintKelp(world, seed, x, z, floorY, seaY, depth);
            case MarineHabitatModel.CORAL_REEF ->
                    paintCoral(world, seed, x, z, floorY, seaY, depth);
            case MarineHabitatModel.SPONGE_GARDEN ->
                    paintSponge(world, seed, x, z, floorY, seaY, depth);
            case MarineHabitatModel.HYDROTHERMAL_VENTS ->
                    paintVent(world, seed, x, z, floorY, seaY, depth);
            default -> { }
        }
    }
}
