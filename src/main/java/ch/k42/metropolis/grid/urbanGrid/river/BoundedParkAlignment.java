package ch.k42.metropolis.grid.urbanGrid.river;

/** Restored from the R30ea member/bytecode reference; not an October 31 source. */
public final class BoundedParkAlignment {
    private BoundedParkAlignment() {}

    public static int[] align(int x, int z, int widthX, int widthZ,
                              int tolerance, int originX, int originZ, int cellSize) {
        return new int[] {axis(x, widthX, tolerance, originX, cellSize),
                          axis(z, widthZ, tolerance, originZ, cellSize)};
    }

    static int axis(int start, int width, int tolerance, int origin, int size) {
        if (tolerance <= width + 2 || width < 1 || size < 2) return start;
        int left = start - 1;
        int right = start + width;
        int firstGrid = Math.floorDiv(left - origin, tolerance) * tolerance + origin;
        int lastGrid = Math.floorDiv(right - origin + tolerance - 1, tolerance) * tolerance + origin;
        int leftGap = left - firstGrid - 1;
        int rightGap = lastGrid - right - 1;
        if (leftGap > 0 && leftGap < size) return start - leftGap - 1;
        if (rightGap > 0 && rightGap < size) return start + rightGap + 1;
        return start;
    }
}
