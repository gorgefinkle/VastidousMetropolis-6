package ch.k42.metropolis.grid.urbanGrid.layout;

/** Existing-road alignment and duplicate parallel ring avoidance. */
public final class FixedParkRingRoads {
    private FixedParkRingRoads() { }

    @FunctionalInterface
    public interface RoadAt {
        boolean at(int x, int z);
    }

    public static int[] alignToExistingRoad(int x, int z, int width, int depth, RoadAt road) {
        if (road == null || width < 2 || depth < 2) return new int[]{x, z};
        boolean north = completeHorizontal(road, x, z - 2, width) && !anyHorizontal(road, x, z - 1, width);
        boolean south = completeHorizontal(road, x, z + depth + 1, width)
                && !anyHorizontal(road, x, z + depth, width);
        if (north != south) z += north ? -1 : 1;
        boolean west = completeVertical(road, x - 2, z, depth) && !anyVertical(road, x - 1, z, depth);
        boolean east = completeVertical(road, x + width + 1, z, depth)
                && !anyVertical(road, x + width, z, depth);
        if (west != east) x += west ? -1 : 1;
        return new int[]{x, z};
    }

    private static boolean completeHorizontal(RoadAt road, int x, int z, int width) {
        for (int i = x; i < x + width; i++) if (!road.at(i, z)) return false;
        return true;
    }

    private static boolean anyHorizontal(RoadAt road, int x, int z, int width) {
        for (int i = x; i < x + width; i++) if (road.at(i, z)) return true;
        return false;
    }

    private static boolean completeVertical(RoadAt road, int x, int z, int depth) {
        for (int i = z; i < z + depth; i++) if (!road.at(x, i)) return false;
        return true;
    }

    private static boolean anyVertical(RoadAt road, int x, int z, int depth) {
        for (int i = z; i < z + depth; i++) if (road.at(x, i)) return true;
        return false;
    }

    public static boolean parallelsNewRing(int x, int z, int width, int depth, RoadAt road) {
        int northFar = z - 2, southFar = z + depth + 1;
        int westFar = x - 2, eastFar = x + width + 1;
        for (int i = x; i < x + width - 1; i++) {
            if (!road.at(i, z - 1) && !road.at(i + 1, z - 1)
                    && road.at(i, northFar) && road.at(i + 1, northFar)) return true;
            if (!road.at(i, z + depth) && !road.at(i + 1, z + depth)
                    && road.at(i, southFar) && road.at(i + 1, southFar)) return true;
        }
        for (int i = z; i < z + depth - 1; i++) {
            if (!road.at(x - 1, i) && !road.at(x - 1, i + 1)
                    && road.at(westFar, i) && road.at(westFar, i + 1)) return true;
            if (!road.at(x + width, i) && !road.at(x + width, i + 1)
                    && road.at(eastFar, i) && road.at(eastFar, i + 1)) return true;
        }
        return false;
    }
}
