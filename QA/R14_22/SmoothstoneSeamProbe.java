package ch.k42.metropolis.generator;

import org.bukkit.Material;
import org.bukkit.World.Environment;

/**
 * The overworld Y=64 smoothstone cap must exist before parcel population.
 * It protects chunks that remain unpopulated; raised populated columns may
 * normalize the buried copy without removing the pre-paint safety layer.
 */
public final class SmoothstoneSeamProbe {
    public static void main(String[] args) {
        for (int y = 1; y <= 64; y++) {
            Material expected = y == 64 ? Material.SMOOTH_STONE : Material.STONE;
            Material actual = MetropolisGenerator.baseTerrainMaterial(null, Environment.NORMAL, y);
            if (actual != expected) {
                throw new AssertionError("normal-overworld pre-paint substrate differs at Y="
                        + y + ": expected=" + expected + " actual=" + actual);
            }
        }
        if (MetropolisGenerator.baseTerrainMaterial(Material.DIORITE, Environment.NORMAL, 63)
                != Material.DIORITE) {
            throw new AssertionError("ordinary environment override below the cap was discarded");
        }
        if (MetropolisGenerator.baseTerrainMaterial(Material.NETHERRACK, Environment.NETHER, 64)
                != Material.NETHERRACK) {
            throw new AssertionError("Nether environment substrate override was discarded");
        }
        if (MetropolisGenerator.baseTerrainMaterial(Material.DIORITE, Environment.NORMAL, 64)
                != Material.SMOOTH_STONE) {
            throw new AssertionError("normal-overworld Y=64 safety cap did not take precedence");
        }
        System.out.println("SmoothstoneSeamProbe PASS: overworld Y=64 pre-paint cap retained; "
                + "other heights and environment overrides preserved");
    }
}
