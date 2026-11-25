package ch.k42.metropolis.grid.urbanGrid.forest;

/** Regression checks for clustered camps and the wider separation between groups. */
public final class WildDecorCampSpacingProbe {
    public static void main(String[] args) {
        if (WildDecorSpacingPolicy.campSpacing(100) != 400)
            throw new AssertionError("legacy/default camp spacing was not raised to 400%");
        if (WildDecorSpacingPolicy.campSpacing(250) != 400)
            throw new AssertionError("older 250% camp configs must receive the new 400% floor");
        if (WildDecorSpacingPolicy.campSpacing(400) != 400)
            throw new AssertionError("new 400% camp default changed unexpectedly");
        if (WildDecorSpacingPolicy.campSpacing(600) != 600)
            throw new AssertionError("a higher configured camp spacing must remain respected");
        if (WildDecorSpacingPolicy.campSpacing(0) != 400)
            throw new AssertionError("invalid lower values must not restore frequent camps");

        if (WildDecorSpacingPolicy.campMinimumGroupGap(250) != 384)
            throw new AssertionError("minimum group gap did not use the 384-block floor");
        if (WildDecorSpacingPolicy.campMinimumGroupGap(500) != 480)
            throw new AssertionError("larger configured spacing did not widen group separation");
        if (WildDecorSpacingPolicy.campMinimumGroupGap(1000) != 768)
            throw new AssertionError("group separation cap changed unexpectedly");

        for (int rotation = 0; rotation < 4; rotation++) {
            checkLayout(2, rotation);
            checkLayout(3, rotation);
        }
        System.out.println("WildDecorCampSpacingProbe: PASS 2-3 site clusters, 400% spacing floor, >=384-block group gap");
    }

    private static void checkLayout(int size, int rotation) {
        int[][] offsets = WildDecorSpacingPolicy.campOffsets(size, rotation);
        if (offsets.length != size)
            throw new AssertionError("wrong number of camp sites");
        int maxRadiusSquared = 0;
        for (int i = 0; i < offsets.length; i++) {
            maxRadiusSquared = Math.max(maxRadiusSquared,
                    offsets[i][0] * offsets[i][0] + offsets[i][1] * offsets[i][1]);
            for (int j = i + 1; j < offsets.length; j++) {
                int dx = offsets[i][0] - offsets[j][0];
                int dz = offsets[i][1] - offsets[j][1];
                if (dx * dx + dz * dz < 900)
                    throw new AssertionError("camp compounds are too close within a cluster");
            }
        }
        if (maxRadiusSquared > 20 * 20)
            throw new AssertionError("camp group layout grew beyond its intended compact footprint");
    }
}
