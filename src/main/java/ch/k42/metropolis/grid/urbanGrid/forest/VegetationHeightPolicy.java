package ch.k42.metropolis.grid.urbanGrid.forest;

/**
 * Shared vertical bounds for terrain vegetation. Natural surfaces may extend
 * below Y=0 in modern worlds; only the world's actual minimum build height is
 * an invalid lower bound.
 */
final class VegetationHeightPolicy {
    private VegetationHeightPolicy() {}

    static boolean allows(int y, int minHeight, int maxHeight) {
        return y > minHeight && y < maxHeight - 2;
    }
}
