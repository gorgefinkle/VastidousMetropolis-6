package ch.k42.metropolis.grid.urbanGrid;

/**
 * The exact four-sided road edges of one accepted creek-side rectangle.
 * Planning-time geometry only: it does not place blocks, inspect neighbours,
 * repair a world, or infer connections between unrelated parallel roads.
 * The same world coordinates produce the same reciprocal edges in every tile.
 */
public final class CreekFrameEdges {
    private CreekFrameEdges() {}

    @FunctionalInterface
    public interface EdgeSink { void edge(int x, int z, int nx, int nz); }

    public static void trace(int minX, int minZ, int maxX, int maxZ, EdgeSink out) {
        if (out == null) throw new IllegalArgumentException("missing edge sink");
        if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("invalid district bounds");
        int left = Math.subtractExact(minX, 1), top = Math.subtractExact(minZ, 1);
        int right = Math.addExact(maxX, 1), bottom = Math.addExact(maxZ, 1);
        for (int x = left; x < right; x++) {
            out.edge(x, top, x + 1, top);
            out.edge(x, bottom, x + 1, bottom);
        }
        for (int z = top; z < bottom; z++) {
            out.edge(left, z, left, z + 1);
            out.edge(right, z, right, z + 1);
        }
    }
}
