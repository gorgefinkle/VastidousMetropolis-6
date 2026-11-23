package ch.k42.metropolis.grid.urbanGrid;

/**
 * The only two measurements RiverReclaim.compute() actually needs from a
 * body of water: per chunk-column, where its edge sits on each side of
 * some baseline split (a river's "north bank" / "south bank" around its
 * spine; an ocean's "shoreward of center" / "shoreward of center" split on
 * either side of its own center line -- see OceanWaterEdgeProvider).
 *
 * Extracted specifically so RiverReclaim -- collectRuns(), emit(),
 * insertRoadSeparators()/insertWaterFrontageRoads(), all of it -- can be
 * reused verbatim for the ocean instead of duplicating that whole
 * algorithm a second time. RiverProvider implements this directly (its own
 * waterEdgeNorthBlock()/waterEdgeSouthBlock() ARE these two methods,
 * unchanged); OceanWaterEdgeProvider is an adapter that derives the same
 * two measurements from OceanProvider's radial shoreline function via
 * bisection, since the ocean has no linear spine/width to read them off
 * directly.
 *
 * NaN means "no water at all in this column on this side" -- e.g. a river
 * column always has one (the river is an unbounded line), but most columns
 * near the corners of the ocean's bounding square legitimately have
 * neither, and RiverReclaim already treats that as "nothing claimable on
 * this side here" via its own MIN_VALUE/MAX_VALUE sentinel handling, no
 * changes needed there.
 */
public interface WaterEdgeProvider {

   /**
    * The worst-case (smallest, i.e. furthest toward negative Z) block Z
    * the water reaches anywhere within chunk column worldChunkX, on the
    * "north"/near side of this provider's own baseline split. NaN if this
    * column has no water on that side at all.
    */
   double waterEdgeNorthBlock(int worldChunkX);

   /** Mirror of waterEdgeNorthBlock() for the "south"/far side of the split. */
   double waterEdgeSouthBlock(int worldChunkX);
}