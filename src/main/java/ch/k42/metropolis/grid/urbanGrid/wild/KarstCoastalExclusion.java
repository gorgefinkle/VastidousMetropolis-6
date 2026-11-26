package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * Prevents fractured-limestone karst from meeting the open-ocean coastline.
 * Signed ocean distance follows OceanProvider's convention: positive is
 * water, zero is shoreline, negative is dry land. The first 512 inland blocks
 * are ineligible; suitability then fades smoothly to full strength by 1024
 * blocks inland. NaN means the ocean is disabled/unavailable.
 */
public final class KarstCoastalExclusion {
    public static final double HARD_EXCLUSION_BLOCKS = 512.0;
    public static final double FULL_SUITABILITY_BLOCKS = 1024.0;

    private KarstCoastalExclusion() {}

    public static double factor(double oceanSignedDistance) {
        if (!Double.isFinite(oceanSignedDistance)) return 1.0;
        if (oceanSignedDistance >= 0.0) return 0.0;

        double inland = -oceanSignedDistance;
        if (inland <= HARD_EXCLUSION_BLOCKS) return 0.0;
        if (inland >= FULL_SUITABILITY_BLOCKS) return 1.0;

        double t = (inland - HARD_EXCLUSION_BLOCKS)
                / (FULL_SUITABILITY_BLOCKS - HARD_EXCLUSION_BLOCKS);
        return t * t * (3.0 - 2.0 * t);
    }
}
