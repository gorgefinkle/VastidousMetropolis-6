package ch.k42.metropolis.grid.urbanGrid.ocean;

/**
 * R30ea bytecode-guided restoration of the ocean/shore depth guard.
 * The original Java source is missing; values and boundary behavior are
 * derived from the reference compiled method bodies and tested in isolation.
 */
public final class OceanFloorSafety {
    private OceanFloorSafety() {}

    public static double shoreT(double distance, double margin) {
        double ramp = Math.min(4.0, margin > 0.0 ? margin : 4.0);
        return ramp <= 0.0 ? 1.0 : Math.max(0.0, Math.min(1.0, distance / ramp));
    }

    public static int paintedDepth(int depth, double fraction) {
        return Math.max(1, (int) Math.round(1.0 + Math.max(0.0, Math.min(1.0, fraction))
                * (Math.max(1, depth) - 1)));
    }

    public static int seabedY(int seaLevel, int depth, double distance, double margin) {
        return Math.max(1, seaLevel - paintedDepth(depth, shoreT(distance, margin)));
    }

    public static boolean protectsCave(int y, double distance, int seaLevel, int depth, double margin) {
        return distance > 0.0 && y <= seaLevel
                && y >= seabedY(seaLevel, depth, distance, margin) - 2;
    }
}
