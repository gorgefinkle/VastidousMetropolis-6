package ch.k42.metropolis.grid.urbanGrid.forest;

/** Deterministic policy for spacing and layout of clustered wilderness camps. */
final class WildDecorSpacingPolicy {
    static final int CAMP_SPACING_FLOOR = 400;
    static final int CAMP_GROUP_RADIUS = 32;
    private static final int CAMP_MINIMUM_GROUP_GAP = 384;
    private static final int CAMP_MAXIMUM_GROUP_GAP = 768;

    private WildDecorSpacingPolicy() {}

    static int campSpacing(int configuredPercent) {
        return Math.max(CAMP_SPACING_FLOOR, Math.max(10, configuredPercent));
    }

    /** Minimum distance between group centres; larger configured spacing widens it too. */
    static int campMinimumGroupGap(int configuredPercent) {
        return Math.max(CAMP_MINIMUM_GROUP_GAP,
                Math.min(CAMP_MAXIMUM_GROUP_GAP, campSpacing(configuredPercent) * 96 / 100));
    }

    /**
     * Two-site groups use a short line; three-site groups use a compact triangle.
     * Sites are around 32 blocks apart, while the whole cluster fits within
     * CAMP_GROUP_RADIUS once the small placement jitter and each camp's radius are included.
     */
    static int[][] campOffsets(int groupSize, int rotation) {
        if (groupSize != 2 && groupSize != 3) {
            throw new IllegalArgumentException("camp group size must be 2 or 3");
        }
        int[][] base = groupSize == 2
                ? new int[][]{{-16, 0}, {16, 0}}
                : new int[][]{{-16, -9}, {16, -9}, {0, 18}};
        int[][] rotated = new int[base.length][2];
        for (int i = 0; i < base.length; i++) {
            int x = base[i][0];
            int z = base[i][1];
            switch (rotation & 3) {
                case 1 -> { rotated[i][0] = -z; rotated[i][1] = x; }
                case 2 -> { rotated[i][0] = -x; rotated[i][1] = -z; }
                case 3 -> { rotated[i][0] = z; rotated[i][1] = -x; }
                default -> { rotated[i][0] = x; rotated[i][1] = z; }
            }
        }
        return rotated;
    }
}
