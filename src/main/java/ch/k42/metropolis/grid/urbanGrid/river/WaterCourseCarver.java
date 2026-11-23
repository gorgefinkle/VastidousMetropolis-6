package ch.k42.metropolis.grid.urbanGrid.river;

/**
 * Common carving interface for rivers and creeks -- methods that determine
 * whether a chunk/block falls within the water course's corridor and at what
 * distance from its center spine.
 *
 * Used to unify river and creek corridor carving without forcing them to
 * share the spine/width representation (rivers use noise meanders; creeks use
 * distinct path logic). Both implement this interface so carveRiverCorridor()
 * and similar can work on either.
 */
public interface WaterCourseCarver {

   /**
    * True if the given chunk is within the river's/creek's open water
    * footprint (spine ± half-width, not including bank margin). Used for
    * corridor detection and path termination.
    */
   boolean isRiver(int worldChunkX, int worldChunkZ);

   /**
    * True if the given chunk is within the river's/creek's water + bank
    * margin footprint (spine ± (half-width + bankMargin)). Used for
    * creek termination and determining if a building can reach the water.
    */
   boolean isRiverOrBank(int worldChunkX, int worldChunkZ);

   /**
    * Minimum distance in chunks from the spine center to the given chunk's
    * center, or NaN if disabled. Used for precise carving boundaries and
    * creek path finding.
    */
   double distanceFromCenter(int worldChunkX, int worldChunkZ);

   /**
    * SIGNED distance in blocks from the centerline to the given point --
    * NOT pre-clamped or offset by half-width: magnitude is (approximately,
    * for a curved spine) the perpendicular distance to the centerline
    * itself, and the sign says which side ("negative side" vs the other)
    * the point falls on. RiverParcel.carveColumn()/bankRampEnd() are the
    * actual contract: they compare Math.abs(this) against half-width
    * themselves and use `this < 0` to pick which side's bank edge to
    * read -- an implementation that instead pre-subtracted half-width or
    * dropped the sign would silently break both.
    */
   double distanceFromCenterBlock(double worldBlockX, double worldBlockZ);

   /**
    * True if this carver is oriented along the Z axis (north-south creeks,
    * east-west rivers) rather than the X axis. Used to determine sampling
    * direction for spine lookups.
    */
   boolean isZBound();

   /**
    * True if this course is enabled in the world config. Used to gate
    * carving and check validity.
    */
   boolean isEnabled();

   /**
    * Full water width in blocks at the given along-axis world block
    * coordinate (worldBlockZ for a Z-bound carrier, worldBlockX
    * otherwise) -- RiverProvider's varies with the meander noise; a
    * creek's is fixed for its whole length (see CreekSpine's own class
    * javadoc), so it ignores the argument.
    */
   double widthAtBlock(double alongWorldBlock);

   /**
    * How far beyond half-width RiverParcel.carveColumn()'s bank ramp
    * reaches when getBankEdgeBlockAt() below has nothing real to report
    * (open water/bank on that whole side, or -- for a creek -- outside
    * whatever this carrier has actually scanned). Deliberately a
    * per-carrier value, not a single shared config field: the main
    * river's corridor is chunks wide and its fallback margin
    * (RiverConfig.getRiverBankMargin(), in whole CHUNKS) is sized for
    * that; a creek's footprint is a few blocks either side of a thin
    * line and uses RiverConfig.getCreekBankMarginBlocks() (already in
    * BLOCKS) instead -- using the river's own chunk-scaled value here
    * would balloon every creek's ramp out to match the main river's,
    * which is exactly the "claiming way more than it needs to, for no
    * reason" the halfWidthBlocks/bankMargin split was already keeping
    * separate everywhere else.
    */
   double getFallbackBankMarginBlocks();

   /**
    * Hard cap, in BLOCKS beyond half-width, on how far
    * RiverParcel.carveColumn()'s bank ramp is EVER allowed to reach, even
    * when a real scanned edge (see getBankEdgeBlockAt()) is further out
    * than that -- 0/negative means uncapped (limited only by whatever the
    * real edge itself turns out to be, or the fallback margin when
    * there's no real edge). A per-carrier value for the same reason
    * getFallbackBankMarginBlocks() is: the main river's corridor is many
    * chunks wide (RiverConfig.getRiverBankMaxRunChunks()) and a creek's
    * ramp needs to stay proportionate to its own much smaller footprint
    * (RiverConfig.getCreekRampMaxBlocks()) instead.
    */
   double getRampMaxRunBlocks();

   /**
    * Block distance from the centerline, on the given side, that
    * RiverParcel.carveColumn()'s bank ramp should actually reach at this
    * point -- the real, scanned edge of whatever ended up sitting next to
    * the water there (a district, a road, or just more open bank),
    * NOT a geometric guess. NaN means nothing scanned/recorded for this
    * point (falls back to getFallbackBankMarginBlocks() -- see
    * RiverParcel.bankRampEnd()).
    *
    * RiverProvider answers this off UrbanGrid's existing tile-wide,
    * world-X-(or Z-)indexed getBankEdgeBlockZ()/X() arrays (computed by
    * UrbanGrid.computeBankEdges()/computeBankEdgesZ()); CreekSpine
    * answers it off its own per-spine, along-axis-indexed arrays (see
    * CreekSpine.recordBankEdges()) -- grid is passed through either way
    * since both ultimately need to have already scanned the FINAL parcel
    * grid to answer this at all.
    */
   double getBankEdgeBlockAt(ch.k42.metropolis.grid.urbanGrid.UrbanGrid grid, double worldBlockX, double worldBlockZ, boolean negativeSide);
}