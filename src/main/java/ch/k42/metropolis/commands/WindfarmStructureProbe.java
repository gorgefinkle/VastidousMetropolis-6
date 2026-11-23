package ch.k42.metropolis.commands;

import org.bukkit.Material;
import org.bukkit.World;

/** Scans windfarm support structure at requested X/Z without retaining a world. */
public final class WindfarmStructureProbe {
    private static final int[][] ARMS = {{3,0},{-3,0},{0,3},{0,-3}};
    private WindfarmStructureProbe() { }
    @FunctionalInterface
    public interface Blocks { Material at(int x, int y, int z); }
    public static final class Result {
        public final boolean found;
        public final int ringY, ringArms, shaftArms, curbArms;
        private Result(boolean found, int ringY, int ringArms, int shaftArms, int curbArms) {
            this.found = found;
            this.ringY = ringY;
            this.ringArms = ringArms;
            this.shaftArms = shaftArms;
            this.curbArms = curbArms;
        }
    }
    private static boolean towerMaterial(Material type) {
        return type == Material.LIGHT_GRAY_CONCRETE || type == Material.WHITE_CONCRETE;
    }
    public static Result inspect(World world, int x, int y, int z) {
        return inspect((bx, by, bz) -> world.getBlockAt(bx,by,bz).getType(),
                x, y, z, world.getMinHeight(), world.getMaxHeight());
    }
    public static Result inspect(Blocks blocks, int x, int y, int z, int minY, int maxY) {
        int start = Math.max(minY + 1, y - 32), end = Math.min(maxY - 12, y + 33);
        Result nearest = new Result(false, y + 1, 0, 0, 0);
        int highestScore = -1;
        for (int height = start; height <= end; height++) {
            int ring = 0, shaft = 0, curb = 0, nearShaft = 0;
            for (int[] offset : ARMS) {
                int dx = offset[0], dz = offset[1];
                if (blocks.at(x + dx, height, z + dz) == Material.LIGHT_GRAY_CONCRETE) ring++;
                if (towerMaterial(blocks.at(x + dx, height + 3, z + dz))) nearShaft++;
                if (towerMaterial(blocks.at(x + dx, height + 8, z + dz))) shaft++;
                if (blocks.at(x + dx * 4 / 3, height, z + dz * 4 / 3)
                        == Material.GRAY_CONCRETE) curb++;
            }
            boolean found = ring >= 2 && nearShaft >= 2 && (shaft >= 2 || curb >= 2);
            int score = ring * 3 + nearShaft * 2 + shaft * 2 + curb;
            if (found) return new Result(true, height, ring, shaft, curb);
            if (score > highestScore) {
                nearest = new Result(false, height, ring, shaft, curb);
                highestScore = score;
            }
        }
        return nearest;
    }
}
