package ch.k42.metropolis.grid.urbanGrid.forest;

import org.bukkit.Material;

/** Old saved chunks lose only a buried copy of the legacy safety cap. */
public final class BuriedFallbackCapProbe {
    public static void main(String[] args) {
        if (ForestGenerator.buriedFallbackCap(Material.SMOOTH_STONE, 72, 64) != Material.STONE) {
            throw new AssertionError("buried legacy Y=64 cap was not normalized to stone");
        }
        if (ForestGenerator.buriedFallbackCap(Material.SMOOTH_STONE, 64, 64) != Material.SMOOTH_STONE) {
            throw new AssertionError("surface-level block was changed");
        }
        if (ForestGenerator.buriedFallbackCap(Material.GRASS_BLOCK, 72, 64) != Material.GRASS_BLOCK) {
            throw new AssertionError("non-cap terrain was changed");
        }
        if (ForestGenerator.buriedFallbackCap(Material.SMOOTH_STONE, 72, 63) != Material.SMOOTH_STONE) {
            throw new AssertionError("unrelated Y level was changed");
        }
        System.out.println("BuriedFallbackCapProbe PASS: only legacy smooth stone buried below a raised surface becomes stone");
    }
}
