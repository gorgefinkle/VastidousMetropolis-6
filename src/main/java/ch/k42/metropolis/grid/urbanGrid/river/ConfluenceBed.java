package ch.k42.metropolis.grid.urbanGrid.river;

/**
 * R30ea bytecode-guided restoration of the confluence open-water transition.
 * This is reconstructed Java, not the missing original source file.
 */
public final class ConfluenceBed {
    private ConfluenceBed() {}

    public static double shoreFraction(WaterCourseCarver course, int worldBlockX, int worldBlockZ) {
        double distance = course.distanceFromCenterBlock(worldBlockX, worldBlockZ);
        double halfWidth = course.widthAtBlock(course.isZBound() ? worldBlockZ : worldBlockX) / 2.0;
        if (!Double.isFinite(distance) || !Double.isFinite(halfWidth)
                || halfWidth <= 0.0 || Math.abs(distance) > halfWidth) {
            return Double.NaN;
        }
        double ramp = Math.min(4.0, halfWidth);
        return Math.min(1.0, (halfWidth - Math.abs(distance)) / ramp);
    }

    public static double deeperFraction(double first, double second) {
        if (!Double.isFinite(first) || !Double.isFinite(second)
                || first < 0.0 || first > 1.0 || second < 0.0 || second > 1.0) {
            throw new IllegalArgumentException("Confluence requires open-water fractions in [0,1]");
        }
        return Math.max(first, second);
    }
}
