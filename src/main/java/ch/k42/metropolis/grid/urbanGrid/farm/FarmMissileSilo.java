package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * Chunk-clipped adapter for the complete multi-variant missile installation.
 * The pure geometry is replayed deterministically for each visible chunk;
 * FarmPainter owns all world writes and clips them to that one chunk.
 */
final class FarmMissileSilo extends FarmFeature {
    static final int HALF_FOOTPRINT = FarmMissileSiloGeometry.HALF_FOOTPRINT;

    private final int cx;
    private final int cz;
    private final int groundY;
    private final long seed;
    private final FarmMissileSiloGeometry.Variant variant;

    FarmMissileSilo(int cx, int cz, int groundY, long seed, int variantIndex) {
        super(cx - HALF_FOOTPRINT, cz - HALF_FOOTPRINT,
              cx + HALF_FOOTPRINT, cz + HALF_FOOTPRINT);
        this.cx = cx;
        this.cz = cz;
        this.groundY = groundY;
        this.seed = seed;
        FarmMissileSiloGeometry.Variant[] variants = FarmMissileSiloGeometry.Variant.values();
        this.variant = variants[Math.floorMod(variantIndex, variants.length)];
    }

    @Override
    void paint(FarmPainter painter) {
        FarmMissileSiloGeometry.paint(new FarmMissileSiloGeometry.Sink() {
            @Override
            public void set(int x, int y, int z, int id, int data) {
                painter.set(x, y, z, id, data);
            }

            @Override
            public void chest(int x, int y, int z, int facing, int salt, int tier) {
                painter.chest(x, y, z, facing, salt, tier);
            }
        }, this.cx, this.cz, this.groundY, painter.minY, this.seed, this.variant);
    }
}
