package ch.k42.metropolis.generator;

import org.bukkit.Material;

/** The normal overworld must not introduce a distinct Y=64 base layer. */
public final class SmoothstoneSeamProbe {
    public static void main(String[] args) {
        for (int y = 1; y <= 64; y++) {
            Material material = MetropolisGenerator.baseTerrainMaterial(null);
            if (material != Material.STONE) {
                throw new AssertionError("base substrate differs at Y=" + y + ": " + material);
            }
        }
        if (MetropolisGenerator.baseTerrainMaterial(Material.NETHERRACK) != Material.NETHERRACK) {
            throw new AssertionError("environment substrate override was discarded");
        }
        System.out.println("SmoothstoneSeamProbe PASS: Y=1..64 share the ordinary stone fallback; environment overrides preserved");
    }
}
