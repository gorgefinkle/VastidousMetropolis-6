package ch.k42.metropolis.grid.urbanGrid.forest;

/** Centralized spacing policy for wilderness decoration types. */
final class WildDecorSpacingPolicy {
    static final int CAMP_SPACING_FLOOR = 250;

    private WildDecorSpacingPolicy() {}

    static int campSpacing(int configuredPercent) {
        return Math.max(CAMP_SPACING_FLOOR, Math.max(10, configuredPercent));
    }
}
