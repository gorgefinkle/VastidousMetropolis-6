package ch.k42.metropolis.grid.urbanGrid;

/** Restored from the R30ea member/bytecode reference; not an October 31 source. */
public final class CreekBridgeSpacing {
    public static final int MIN_AXIS_SEPARATION = 8;
    private CreekBridgeSpacing() {}

    @FunctionalInterface
    public interface BridgeLookup {
        boolean sameCreekParallelBridgeAt(int x, int z);
    }

    public static boolean overlapsExistingCrossing(
            boolean eastWest, int axis, int wetStart, int wetEnd, BridgeLookup lookup) {
        if (wetStart > wetEnd) throw new IllegalArgumentException("reversed wet span");
        if (lookup == null) throw new IllegalArgumentException("missing bridge lookup");
        for (int parallel = axis - MIN_AXIS_SEPARATION + 1;
                parallel < axis + MIN_AXIS_SEPARATION; parallel++) {
            if (parallel == axis) continue;
            for (int across = wetStart - 3; across <= wetEnd + 3; across++) {
                int x = eastWest ? across : parallel;
                int z = eastWest ? parallel : across;
                if (lookup.sameCreekParallelBridgeAt(x, z)) return true;
            }
        }
        return false;
    }
}
