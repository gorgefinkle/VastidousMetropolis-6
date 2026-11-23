package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.minions.Mats;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/** Reject unsupported terrestrial flora above water or air. */
public final class ShorePlantSafety {
    private ShorePlantSafety() { }

    public static boolean terrestrial(Material m) {
        return m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN
                || m == Material.LARGE_FERN || m == Material.DEAD_BUSH || m == Material.DANDELION
                || m == Material.POPPY || m == Material.BLUE_ORCHID || m == Material.ALLIUM
                || m == Material.AZURE_BLUET || m == Material.RED_TULIP || m == Material.ORANGE_TULIP
                || m == Material.WHITE_TULIP || m == Material.PINK_TULIP || m == Material.OXEYE_DAISY
                || m == Material.CORNFLOWER || m == Material.LILY_OF_THE_VALLEY
                || m == Material.BROWN_MUSHROOM || m == Material.RED_MUSHROOM
                || m == Material.SUNFLOWER || m == Material.LILAC || m == Material.PEONY
                || m == Material.ROSE_BUSH;
    }

    public static boolean canPlaceTerrestrial(Block block, Material material) {
        if (block == null || material == null || !terrestrial(material)) return true;
        if (block.getY() <= block.getWorld().getMinHeight()) return false;
        Material below = block.getRelative(BlockFace.DOWN).getType();
        boolean soil = below == Material.GRASS_BLOCK || Mats.DIRT_FAMILY.contains(below)
                || below == Material.MOSS_BLOCK || below == Material.MUD;
        if (material == Material.DEAD_BUSH) {
            soil |= below == Material.SAND || below == Material.RED_SAND || below == Material.TERRACOTTA;
        }
        Material above = block.getType();
        return soil && (above == Material.AIR || above == Material.CAVE_AIR || above == Material.VOID_AIR);
    }

    public static boolean unsupported(Material material, Material below) {
        return terrestrial(material) && (below == Material.AIR || below == Material.CAVE_AIR
                || below == Material.VOID_AIR || below == Material.WATER || below == Material.BUBBLE_COLUMN
                || below == Material.KELP || below == Material.KELP_PLANT
                || below == Material.SEAGRASS || below == Material.TALL_SEAGRASS);
    }
}
