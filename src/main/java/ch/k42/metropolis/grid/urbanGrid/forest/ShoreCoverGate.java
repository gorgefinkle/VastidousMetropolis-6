package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.minions.Mats;
import org.bukkit.Material;

/** Restored from the R30ea member/bytecode reference; not an October 31 source. */
public final class ShoreCoverGate {
    private ShoreCoverGate() {}

    public static boolean canPlant(boolean eligible, Material ground, Material above) {
        return eligible
            && (above == Material.AIR || above == Material.CAVE_AIR || above == Material.VOID_AIR)
            && ground != null
            && (ground == Material.GRASS_BLOCK || Mats.DIRT_FAMILY.contains(ground)
                || ground == Material.SAND || ground == Material.RED_SAND);
    }
}
