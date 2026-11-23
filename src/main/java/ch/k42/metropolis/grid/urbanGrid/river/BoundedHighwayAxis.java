package ch.k42.metropolis.grid.urbanGrid.river;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;

/** Determine highway crossing direction at planned river locations. */
public final class BoundedHighwayAxis {
    public static final int NORTH_SOUTH = 1;
    public static final int EAST_WEST = 2;
    private BoundedHighwayAxis() { }

    public static int classify(Iterable<HighwayNetwork.Rect> rectangles, int x, int z) {
        boolean found = false;
        int axis = 0;
        for (HighwayNetwork.Rect r : rectangles) {
            if (!r.contains(x, z)) continue;
            found = true;
            long dx = (long) r.maxX - r.minX + 1;
            long dz = (long) r.maxZ - r.minZ + 1;
            if (dz > 2 && dz > dx) axis |= NORTH_SOUTH;
            else if (dx > 2 && dx > dz) axis |= EAST_WEST;
            else axis |= NORTH_SOUTH | EAST_WEST;
        }
        return found ? axis : 0;
    }

    public static int at(HighwayNetwork highway, int x, int z) {
        return highway == null ? 0 : classify(highway.rectsIn(x, z, x, z), x, z);
    }

    public static boolean perpendicular(int axis, boolean riverZBound) {
        return axis == (riverZBound ? EAST_WEST : NORTH_SOUTH);
    }

    public static boolean completeCrossing(HighwayNetwork highway, RiverProvider river, int x, int z) {
        if (highway == null || river == null || !river.isRiver(x, z)) return false;
        boolean axisZ = river.isZBound();
        if (!perpendicular(at(highway, x, z), axisZ)) return false;
        for (int sign : new int[]{-1, 1}) {
            boolean reachedBank = false;
            for (int distance = 1; distance <= 12; distance++) {
                int nx = x + (axisZ ? sign * distance : 0);
                int nz = z + (axisZ ? 0 : sign * distance);
                if (!perpendicular(at(highway, nx, nz), axisZ)) return false;
                if (!river.isRiver(nx, nz)) { reachedBank = true; break; }
            }
            if (!reachedBank) return false;
        }
        return true;
    }
}
