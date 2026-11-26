package ch.k42.metropolis.grid.urbanGrid.forest;

/**
 * Maps Mother Willow's geometry vocabulary before it reaches the Bukkit
 * chunk painter. Keep this separate from geometry so the adapter contract
 * can be tested without constructing a live chunk.
 */
final class MotherWillowBlockState {
    private MotherWillowBlockState() {}

    static boolean isWood(String state) {
        return "minecraft:poplar_wood".equals(state)
                || (state != null && state.startsWith("minecraft:poplar_log["));
    }

    static boolean isBarkWood(String state) {
        return "minecraft:poplar_wood".equals(state);
    }

    static boolean isLeaves(String state) {
        return state != null && state.startsWith("minecraft:yellow_poplar_leaves");
    }
}
