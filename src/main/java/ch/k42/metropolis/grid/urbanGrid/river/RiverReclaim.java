package ch.k42.metropolis.grid.urbanGrid.river;

import ch.k42.metropolis.grid.urbanGrid.WaterEdgeProvider;
import ch.k42.metropolis.minions.Minions;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which of a river corridor's leftover green space can be handed
 * back to ordinary district carving ("reclaimed"), and -- once that's
 * settled -- where the bank's elevation ramp has to top out so it meets
 * the reclaimed district's own edge instead of running flat across it.
 *
 * WHY THIS EXISTS: UrbanGrid.carveRiverCorridor() reserves the river's
 * whole guaranteed envelope (RiverProvider.getEnvelopeHalfWidth() padded
 * around the locally-sampled spine range) and hands every non-edge cell
 * in it to RiverParcel. That envelope is deliberately a worst case -- it
 * has to contain the river no matter which width bucket widthAt() rolls
 * or where the meander happens to sit -- so on most tiles a large
 * fraction of it is nowhere near the water and gets painted as flat
 * RiverParcel.paintLand() grass. That's correct (no build ever lands in
 * the water) but wasteful: a band of empty green potentially dozens of
 * chunks deep, on both banks, through every river tile.
 *
 * WHAT THIS DOES: measures where the water ACTUALLY is (per chunk column,
 * at block precision -- see waterEdge* below), steps a configured berth
 * back from it, and reports the rectangles of corridor that are provably
 * clear enough to build on. UrbanGrid turns those into ordinary districts
 * via addDistrict(), so they get normal contexts, normal schematics and
 * normal filler -- they are not a special parcel type. Every side of
 * every reported district ends up road-adjacent: the away-from-water
 * side either reuses the corridor's own riverside road or (right at a
 * tile boundary) the highway ring itself, the water-facing side gets its
 * own road via getWaterFrontageRoads(), and the two lateral sides get
 * one each via getRoadSeparators() -- a single shared column where two
 * runs sit flush against each other, or a column peeled off each rect's
 * own edge wherever it instead borders a gap of unreclaimed land or the
 * end of the bank's run list.
 *
 * THE BERTH IS NON-NEGOTIABLE. getRiverReclaimBerthChunks() is clamped to
 * a minimum of 1 whole chunk (see RiverConfig), measured against the
 * WORST CASE waterline anywhere in that chunk column rather than its
 * center -- NOT combined with bankMargin, which is a separate,
 * material-blend-only setting (see RiverConfig.getRiverBankMargin()'s
 * javadoc). Cutting a build into the river -- or even onto the bank's slope -- is the one outcome this whole
 * class exists to make impossible, so every rounding decision below is
 * deliberately taken in the conservative direction (see
 * northLimitChunk()/southLimitChunk()).
 *
 * All coordinates here are world coordinates: *Chunk* fields/params are
 * chunk coords, *Block* ones are block coords. Nothing in here is tile-
 * relative, so the numbers mean the same thing to a neighbouring tile.
 */
public class RiverReclaim {

   /** One reclaimed rectangle, in chunk coordinates, ready for UrbanGrid.addDistrict(). */
   public static class Rect {
      public final int baseX;
      public final int baseZ;
      public final int sizeX;
      public final int sizeZ;
      /** True if this rect sits on the NORTH bank (smaller Z than the water). */
      public final boolean north;

      Rect(int baseX, int baseZ, int sizeX, int sizeZ, boolean north) {
         this.baseX = baseX;
         this.baseZ = baseZ;
         this.sizeX = sizeX;
         this.sizeZ = sizeZ;
         this.north = north;
      }

      @Override
      public String toString() {
         return String.format("Rect[%s base=(%d,%d) size=(%d,%d)]",
                 this.north ? "N" : "S", this.baseX, this.baseZ, this.sizeX, this.sizeZ);
      }
   }

   private static final int CHUNK = 16;

   private final int tileMinX;
   private final int width;

   // Per chunk-column (indexed by worldChunkX - tileMinX): the block Z of
   // the reclaimed district's WATER-FACING edge on each bank, or NaN where
   // nothing was reclaimed on that side in that column. This is the single
   // value RiverParcel.carveColumn() needs from this class -- see
   // bankRampEndBlockZ().
   private final double[] northEdgeBlockZ;
   private final double[] southEdgeBlockZ;

   private final List<Rect> rects;

   // One-chunk-wide strips, in the same coordinate system as Rect, that
   // separate two reclaimed districts which would otherwise sit directly
   // against each other with no road between them -- see
   // insertRoadSeparators() for why that happens and getRoadSeparators()
   // for how UrbanGrid uses this list.
   private final List<Rect> roadSeparators;

   // One-chunk-deep strips, full width, peeled off the WATER-FACING edge
   // of every rect in getRects() -- see insertWaterFrontageRoads(). Gives
   // reclaimed districts a real road on the water side instead of relying
   // on buildings simply facing the river; getRects() itself already
   // excludes these rows (they're carved out before rects is built, not
   // on top of it).
   private final List<Rect> waterFrontageRoads;

   // Full rectangles (not just edge strips) for runs collectRuns() closed
   // narrower than getRiverReclaimMinWidth() -- see emit(). Every column
   // in one of these already cleared reservedDepth, same as any other
   // run; it's only too NARROW to stand as its own district. Paving it as
   // road instead of leaving it un-emitted means it can't be stranded as
   // untouched RiverParcel grass wedged between two properly-finished
   // districts. Depth is NOT capped -- it covers the full berth-clear
   // ground the run measured, same as an ordinary district's depth is
   // never capped either (only WIDTH is, via getRiverReclaimMaxWidth()).
   // Capping it would leave the excess depth as a plain flat notch with
   // no road, sitting flush against neighbouring districts that reach
   // all the way to the water -- a visible seam this class doesn't
   // otherwise produce anywhere.
   private final List<Rect> fallbackRoads;

   private RiverReclaim(int tileMinX, int width, double[] northEdgeBlockZ, double[] southEdgeBlockZ,
                        List<Rect> rects, List<Rect> roadSeparators, List<Rect> waterFrontageRoads,
                        List<Rect> fallbackRoads) {
      this.tileMinX = tileMinX;
      this.width = width;
      this.northEdgeBlockZ = northEdgeBlockZ;
      this.southEdgeBlockZ = southEdgeBlockZ;
      this.rects = rects;
      this.roadSeparators = roadSeparators;
      this.waterFrontageRoads = waterFrontageRoads;
      this.fallbackRoads = fallbackRoads;
   }

   public List<Rect> getRects() {
      return this.rects;
   }

   /**
    * One-chunk-wide, full-depth columns UrbanGrid must paint as RoadParcel
    * (not addDistrict()) alongside getRects(). Every one of these is
    * carved out of a Rect's OWN already-berth-cleared footprint (see
    * insertRoadSeparators()), so painting it as road is exactly as safe as
    * the district it came from -- nothing here ever reaches closer to the
    * water than that district already did.
    */
   public List<Rect> getRoadSeparators() {
      return this.roadSeparators;
   }

   /** One-chunk-deep, full-width road strips along the water-facing edge of every getRects() entry -- see waterFrontageRoads' field javadoc. */
   public List<Rect> getWaterFrontageRoads() {
      return this.waterFrontageRoads;
   }

   /**
    * Full-area road rectangles for runs too narrow to become their own
    * district -- see fallbackRoads' field javadoc. Unlike
    * getRoadSeparators()/getWaterFrontageRoads(), these can be more than
    * one column/row deep, so the caller must paint the whole rectangle,
    * not just an edge.
    */
   public List<Rect> getFallbackRoads() {
      return this.fallbackRoads;
   }

   public boolean isEmpty() {
      return this.rects.isEmpty();
   }

   /**
    * Whether (chunkX, chunkZ) is inside one of getRects() -- i.e. this
    * chunk belongs to a reclaimed district rather than the ordinary land
    * carved elsewhere in the tile. Used by Parcel.effectiveBuildHeight()
    * so anything pasted/carved in a reclaimed district sits at the
    * river's own buildHeight rather than the general one -- see that
    * method's javadoc for why the two can differ at all. Linear in the
    * (typically single-digit) number of reclaimed rects in this tile,
    * which is cheap enough to just check directly rather than maintaining
    * a separate lookup structure.
    */
   public boolean containsChunk(int chunkX, int chunkZ) {
      for (Rect r : this.rects) {
         if (chunkX >= r.baseX && chunkX < r.baseX + r.sizeX
                 && chunkZ >= r.baseZ && chunkZ < r.baseZ + r.sizeZ) {
            return true;
         }
      }
      return false;
   }

   /**
    * Block Z at which the bank's elevation ramp must have reached full
    * build height for this column, on whichever side of the centerline the
    * caller is painting -- i.e. the reclaimed district's water-facing
    * boundary. NaN when nothing was reclaimed on that side of that column,
    * which tells RiverParcel to fall back to the plain bankMargin ramp it
    * used before any of this existed.
    *
    * Reading this PER COLUMN (not per chunk) is the whole point: the
    * district edge is a straight chunk boundary while the waterline is a
    * smooth curve, so the gap between them changes continuously along X.
    * Sampling it per column is what lets the ramp stretch and compress to
    * land exactly on that boundary everywhere, instead of terminating at a
    * fixed distance from the water and leaving either a flat shelf or a
    * step where it meets the builds.
    */
   public double bankRampEndBlockZ(int worldBlockX, boolean north) {
      int idx = (worldBlockX >> 4) - this.tileMinX;
      if (idx < 0 || idx >= this.width) {
         return Double.NaN;
      }
      return north ? this.northEdgeBlockZ[idx] : this.southEdgeBlockZ[idx];
   }

   /**
    * Measures both banks across the corridor and returns everything that
    * can be built on. Pure measurement plus grouping -- creates no parcels
    * and no districts; UrbanGrid does that from getRects().
    *
    * corridorMinZ/corridorMaxZ are the corridor's own edge rows. Where
    * nearIsBoundary/farIsBoundary is false, carveRiverCorridor() has
    * already claimed that row as the riverside road, and reclaimed land
    * starts one chunk inside it; where true, that row coincides with the
    * tile's own boundary, carveRiverCorridor() paints no road of its own
    * there (the highway ring is already the frontage), and reclaimed land
    * starts right at the row itself. Either way the away-from-water side
    * ends up road-adjacent -- see nearIsBoundary/farIsBoundary just below.
    *
    * Takes a WaterEdgeProvider, not a concrete RiverProvider -- everything
    * below this point only ever calls waterEdgeNorthBlock()/
    * waterEdgeSouthBlock() on it, so this is exactly as usable for the
    * ocean's own reclaim pass (via OceanWaterEdgeProvider) as it is for
    * the river. The size/shape thresholds below (minWidth, maxWidth,
    * minDepth, berth) still read from RiverConfig either way -- v1 has the
    * ocean's reclaim share the river's own knobs rather than duplicating a
    * parallel OceanConfig.reclaim.* section for numbers that mean the same
    * thing either way (how wide a strip has to be to stand as its own
    * district, how much clearance from the water it needs).
    */
   public static RiverReclaim compute(WaterEdgeProvider river, int tileMinX, int tileWidth,
                                      int corridorMinZ, int corridorMaxZ,
                                      boolean nearIsBoundary, boolean farIsBoundary) {
      double[] northEdge = new double[tileWidth];
      double[] southEdge = new double[tileWidth];
      for (int i = 0; i < tileWidth; i++) {
         northEdge[i] = Double.NaN;
         southEdge[i] = Double.NaN;
      }

      List<Rect> roadSeparators = new ArrayList<>();
      List<Rect> waterFrontageRoads = new ArrayList<>();
      List<Rect> fallbackRoads = new ArrayList<>();
      // NOT also gated on RiverConfig.isRiverEnabled() -- the only caller
      // that matters for the river itself (UrbanGrid.placeCityDistricts())
      // already returns before ever reaching this call when the river is
      // disabled, so that check was always true by the time it got here
      // anyway; gating on it directly would incorrectly also block the
      // ocean's reclaim pass (a separate call site, see
      // UrbanGrid.reclaimOceanDistricts()) whenever the river feature
      // happens to be off, which has nothing to do with whether the ocean
      // should reclaim its own shoreline.
      if (!RiverConfig.isRiverReclaimEnabled()) {
         return new RiverReclaim(tileMinX, tileWidth, northEdge, southEdge, new ArrayList<>(), roadSeparators, waterFrontageRoads, fallbackRoads);
      }

      // The two rows the riverside road already owns are excluded up
      // front, so a reclaimed district can never eat the road that serves
      // it -- UNLESS that edge is nearIsBoundary/farIsBoundary, meaning
      // it lands exactly on the tile's own edge. There, the highway ring
      // one row further out (placeHighways() rings every tile) already
      // IS that frontage road; carveRiverCorridor() was told to skip
      // painting a second, redundant one right next to it (see its
      // javadoc), so reclaimed land can start right at the corridor edge
      // itself instead of wasting a row backing away from a road that
      // was never painted there. Getting this wrong is exactly what used
      // to push reclaimed districts an extra, pointless row further from
      // an already-adjacent highway.
      // NOT nearIsBoundary/farIsBoundary ? corridorMinZ : corridorMinZ + 1.
      // That looked like a free optimisation -- "no separate riverside
      // road exists on this side, so reclaimed land can start right at
      // the corridor edge itself instead of wasting a row" -- but it
      // conflates two different things this row can mean. When the
      // highway ring itself IS this edge, carveRiverCorridor() doesn't
      // leave that row empty: it unconditionally calls
      // markHighwayBoundary() on EVERY column of it, the whole tile
      // width, specifically so RiverParcel's retaining wall (and, where
      // the channel actually reaches, its drain pipe -- see
      // buildHighwayBoundaryWall()) runs the crossing's FULL width, not
      // just the columns presently wet enough to fail a buildability
      // check. Starting reclaim ON that row instead of past it measures
      // most of those columns as ordinary dry land and hands them to a
      // ground-level district or fallback road, discarding the marked
      // RiverParcel (and the wall it would have built) under an ordinary
      // building -- leaving only the handful of columns too wet to
      // reclaim with any wall at all, i.e. an intact highway on both
      // approaches with a bare gap (at most a lone drain pipe) exactly
      // where it crosses the water. The row was never free space; it was
      // already spoken for. Reclaim starts one row past the corridor edge
      // either way, same as when an ordinary riverside road already
      // occupies that row.
      int firstBuildableZ = corridorMinZ + 1;
      int lastBuildableZ = corridorMaxZ - 1;
      if (firstBuildableZ > lastBuildableZ) {
         return new RiverReclaim(tileMinX, tileWidth, northEdge, southEdge, new ArrayList<>(), roadSeparators, waterFrontageRoads, fallbackRoads);
      }

      // Deepest chunk (inclusive) each column may build to on each bank,
      // or a sentinel meaning "nothing fits here".
      int[] northLimit = new int[tileWidth];
      int[] southStart = new int[tileWidth];

      for (int i = 0; i < tileWidth; i++) {
         int x = tileMinX + i;
         northLimit[i] = Math.min(northLimitChunk(river, x), lastBuildableZ);
         southStart[i] = Math.max(southStartChunk(river, x), firstBuildableZ);
      }

      smoothLimits(northLimit, southStart, tileWidth);

      // Collected per bank, not straight into one shared list: a wrap-
      // around road only ever needs inserting BETWEEN two runs of the
      // SAME bank (north runs and south runs never sit next to each other
      // -- the water is always between them), and insertRoadSeparators()
      // below needs each bank's runs in isolation, in left-to-right order,
      // to find those adjacencies.
      List<Rect> northRects = new ArrayList<>();
      List<Rect> southRects = new ArrayList<>();
      collectRuns(northRects, northEdge, fallbackRoads, tileMinX, tileWidth, northLimit, firstBuildableZ, true);
      collectRuns(southRects, southEdge, fallbackRoads, tileMinX, tileWidth, southStart, lastBuildableZ, false);

      // Water frontage BEFORE the lateral separators below: it shrinks
      // each rect's depth by one row, and insertRoadSeparators() sizes its
      // separator columns off that already-shrunk depth -- then deliberately
      // reaches ONE ROW FURTHER toward the water so each separator meets
      // the deeper rect's frontage road at a real corner. Without that
      // extra row the separator and the frontage only touch diagonally,
      // leaving the corner cell unclaimed and both roads dead-ending.
      insertWaterFrontageRoads(northRects, waterFrontageRoads);
      insertWaterFrontageRoads(southRects, waterFrontageRoads);

      insertRoadSeparators(northRects, roadSeparators, tileMinX, tileWidth);
      insertRoadSeparators(southRects, roadSeparators, tileMinX, tileWidth);

      List<Rect> rects = new ArrayList<>(northRects.size() + southRects.size());
      rects.addAll(northRects);
      rects.addAll(southRects);

      Minions.dReclaimRoads("compute() tile x=[%d,%d) -- firstBuildableZ=%d lastBuildableZ=%d (nearIsBoundary=%b farIsBoundary=%b) -- %d district(s), %d separator(s), %d frontage strip(s), %d fallback (too-narrow) road(s)",
              tileMinX, tileMinX + tileWidth, firstBuildableZ, lastBuildableZ, nearIsBoundary, farIsBoundary,
              rects.size(), roadSeparators.size(), waterFrontageRoads.size(), fallbackRoads.size());

      return new RiverReclaim(tileMinX, tileWidth, northEdge, southEdge, rects, roadSeparators, waterFrontageRoads, fallbackRoads);
   }

   /**
    * Peels the single water-facing chunk row off every run collectRuns()
    * accepted and reports it as a road, mutating the run's own Rect down
    * to the depth that's left -- the actual district. Gives reclaimed
    * districts real frontage on their water side too, instead of relying
    * on District.findRoad() orienting buildings toward the river because
    * there was nothing else to orient toward.
    *
    * Safe by construction, not by a check here: collectRuns() only ever
    * accepts a run whose shared depth already clears
    * getRiverReclaimMinDepth() PLUS one (see reservedDepth there)
    * specifically so this method always has a spare row to take.
    * Removing it can't shrink a district below the configured minimum.
    *
    * bankRampEndBlockZ() is untouched by this: emit() already recorded
    * the water-facing edge from the run's FULL depth, before this runs,
    * and that edge is still the correct ramp target -- the road this
    * method paints sits exactly on that line, so the bank's ramp still
    * climbs to meet the same physical boundary, just with a street on it
    * instead of the first row of buildings.
    */
   private static void insertWaterFrontageRoads(List<Rect> bankRects, List<Rect> out) {
      for (int i = 0; i < bankRects.size(); i++) {
         Rect r = bankRects.get(i);
         if (r.north) {
            // North bank: baseZ is the away-from-water edge and depth
            // grows toward the water, so the water-facing row is the LAST one.
            Rect frontage = new Rect(r.baseX, r.baseZ + r.sizeZ - 1, r.sizeX, 1, true);
            out.add(frontage);
            Rect shrunk = new Rect(r.baseX, r.baseZ, r.sizeX, r.sizeZ - 1, true);
            bankRects.set(i, shrunk);
            Minions.dReclaimRoads("insertWaterFrontageRoads() -- %s -> frontage=%s, district shrinks to %s", r, frontage, shrunk);
         } else {
            // South bank: baseZ IS the water-facing row (depth grows away from water).
            Rect frontage = new Rect(r.baseX, r.baseZ, r.sizeX, 1, false);
            out.add(frontage);
            Rect shrunk = new Rect(r.baseX, r.baseZ + 1, r.sizeX, r.sizeZ - 1, false);
            bankRects.set(i, shrunk);
            Minions.dReclaimRoads("insertWaterFrontageRoads() -- %s -> frontage=%s, district shrinks to %s", r, frontage, shrunk);
         }
      }
   }

   /**
    * Finds every pair of same-bank runs that collectRuns() closed and
    * reopened back-to-back with no gap between them -- which happens
    * whenever a meander or the maxWidth cap forces a run to end at
    * exactly the column the next one starts at (see collectRuns()) -- and
    * carves a real one-chunk road out of one of the two, so the two
    * districts get an actual street between them instead of sharing a
    * wall with no frontage on that side.
    *
    * The column is always taken from whichever of the two rects can
    * afford to lose it without dropping below getRiverReclaimMinWidth();
    * if NEITHER can afford it (both already sitting exactly at the
    * configured minimum), the pair is left touching rather than shrinking
    * a district below the width the server operator asked for -- a rare
    * edge case, logged so it's visible rather than silently accepted.
    *
    * This never needs to touch northEdge/southEdge: shrinking a run's
    * WIDTH doesn't change its DEPTH, and the recorded per-column bank-ramp
    * target only depends on depth, so every column's value -- including
    * the one that just became a road -- is still exactly right.
    *
    * Handles only the touching case, though -- a rect whose neighbour on
    * a given side ISN'T another rect (a genuine gap of unreclaimed land,
    * or simply the end of the bank's run list) never goes through the
    * loop below at all, and used to end up with no lateral road on that
    * side whatsoever: the water-facing and away-from-water sides are both
    * unconditionally guaranteed (insertWaterFrontageRoads() and the
    * corridor/highway road respectively), but that side was neither.
    * capLateralEdges() below closes that gap.
    */
   private static void insertRoadSeparators(List<Rect> bankRects, List<Rect> out, int tileMinX, int tileWidth) {
      int minWidth = Math.max(1, RiverConfig.getRiverReclaimMinWidth());

      // Snapshotted BEFORE any mutation below: a touching pair this loop
      // turns into a gap (by carving the separator out of one side, which
      // shifts that rect's baseX/sizeX) must not then look, to
      // capLateralEdges(), like it was a gap all along -- that separator
      // already serves both districts, so neither should also get a
      // lateral cap.
      boolean[] isGap = new boolean[Math.max(0, bankRects.size() - 1)];
      for (int i = 0; i < isGap.length; i++) {
         Rect a = bankRects.get(i);
         Rect b = bankRects.get(i + 1);
         isGap[i] = a.baseX + a.sizeX != b.baseX;
         Minions.dReclaimRoads("insertRoadSeparators() -- pair[%d]: %s / %s -- %s",
                 i, a, b, isGap[i] ? "GAP (unreclaimed land between them, capLateralEdges() will cap both sides)"
                         : "TOUCHING (no gap -- this loop will try to carve a separator between them)");
      }

      for (int i = 0; i + 1 < bankRects.size(); i++) {
         if (isGap[i]) {
            continue; // already a gap here (unreclaimed land) -- capLateralEdges() caps each side of it instead
         }

         Rect a = bankRects.get(i);
         Rect b = bankRects.get(i + 1);

         // The riverbank curves, so adjacent runs routinely have
         // DIFFERENT depths (collectRuns() computes each column's depth
         // independently). A separator sized off only ONE of the two
         // rects leaves the deeper rect's extra row(s) -- the ones the
         // shallower rect doesn't reach -- with no separator at all on
         // that side: not a gap capLateralEdges() would catch either,
         // since isGap[i] is (correctly) false here. Union the two
         // rects' Z spans so the separator covers every row either one
         // actually occupies.
         int sepBaseZ = Math.min(a.baseZ, b.baseZ);
         int sepEndZ = Math.max(a.baseZ + a.sizeZ, b.baseZ + b.sizeZ); // exclusive

         // The rects were already shrunk by insertWaterFrontageRoads(), so
         // the union above stops at the last DISTRICT row on the water
         // side. The deeper rect's frontage row sits one row beyond that
         // (north bank: baseZ + sizeZ; south bank: baseZ - 1), and it only
         // spans that rect's own columns -- when this separator is carved
         // from the shallower rect, its column isn't in that frontage
         // strip. Stopping short leaves the corner cell unclaimed (river)
         // and turns both the separator's end and the frontage's end into
         // dead ends. Reaching one row further makes the corner road, so
         // the two roads connect at a proper L.
         if (b.north) {
            sepEndZ += 1;
         } else {
            sepBaseZ -= 1;
         }
         int sepSizeZ = sepEndZ - sepBaseZ;

         if (b.sizeX > 1) {
            Rect sep = new Rect(b.baseX, sepBaseZ, 1, sepSizeZ, b.north);
            out.add(sep);
            Rect shrunkB = new Rect(b.baseX + 1, b.baseZ, b.sizeX - 1, b.sizeZ, b.north);
            bankRects.set(i + 1, shrunkB);
            Minions.dReclaimRoads("insertRoadSeparators() -- carved from EAST rect (%s): separator=%s (z-span unioned with %s), %s shrinks to %s", b, sep, a, b, shrunkB);
         } else if (a.sizeX > 1) {
            Rect sep = new Rect(a.baseX + a.sizeX - 1, sepBaseZ, 1, sepSizeZ, a.north);
            out.add(sep);
            Rect shrunkA = new Rect(a.baseX, a.baseZ, a.sizeX - 1, a.sizeZ, a.north);
            bankRects.set(i, shrunkA);
            Minions.dReclaimRoads("insertRoadSeparators() -- carved from WEST rect (%s): separator=%s (z-span unioned with %s), %s shrinks to %s", a, sep, b, a, shrunkA);
         } else {
            // Both a and b are already 1 chunk wide -- taking a column
            // from either would erase it outright, not just narrow it
            // below minWidth. This is the genuine floor: unlike the
            // minWidth check this replaced, there's no server-config
            // knob that gets us out of it, since a 1-wide district has
            // nothing left to give. isGap[i] is still false here, so
            // capLateralEdges() will (correctly) also treat this edge as
            // already guarded and leave it alone -- the pair really is
            // left touching, with no road between them, same as before.
            Minions.dRiver("RIVER RECLAIM -- %s and %s are adjacent but both are already at getRiverReclaimMinWidth()=%d; leaving them touching (no separating road)",
                    a, b, minWidth);
            Minions.dReclaimRoads("insertRoadSeparators() -- NEITHER %s nor %s can spare a column (both at getRiverReclaimMinWidth()=%d) -- leaving this shared edge with NO road at all",
                    a, b, minWidth);
         }
      }

      capLateralEdges(bankRects, out, isGap, minWidth, tileMinX, tileWidth);
   }

   /**
    * Peels a single lateral (X) chunk-column road off the outer edge of
    * every rect whose neighbour on that side ISN'T another rect it's
    * already sharing an insertRoadSeparators() separator with, and isn't
    * the tile's own outer boundary (the highway ring already covers that
    * side there -- see westIsTileBoundary/eastIsTileBoundary below).
    * Mirrors insertWaterFrontageRoads(): unconditional, not opportunistic,
    * so every reclaimed district ends up with all four sides road-adjacent
    * no matter what's next to it, instead of only the sides that happen to
    * face another district.
    *
    * Unlike insertRoadSeparators()'s genuinely optional column-sharing
    * decision, a cap here is refused only if it would eliminate the
    * district outright (sizeX would drop to 0) -- see capWestEdge()/
    * capEastEdge()'s own javadoc for why minWidth doesn't gate this the
    * way it gates insertRoadSeparators().
    */
   private static void capLateralEdges(List<Rect> bankRects, List<Rect> out, boolean[] isGap, int minWidth,
                                       int tileMinX, int tileWidth) {
      int tileMaxX = tileMinX + tileWidth - 1; // last valid chunk X in this tile

      for (int i = 0; i < bankRects.size(); i++) {
         Rect r = bankRects.get(i);

         // A run's outer edge only ever reaches the tile's own X boundary
         // at the first (west) or last (east) rect in the list -- runs are
         // built left to right, so nothing in between can. Where it does,
         // placeHighways()'s ring is already sitting directly against it
         // (see UrbanGrid.reclaimRiverDistricts()'s javadoc), exactly the
         // same situation nearIsBoundary/farIsBoundary handles for the
         // Z-direction away-from-water side -- painting a cap road here
         // too would just be a second, redundant road with nothing
         // between it and the highway.
         boolean westIsTileBoundary = i == 0 && r.baseX == tileMinX;
         boolean eastIsTileBoundary = i == bankRects.size() - 1 && r.baseX + r.sizeX - 1 == tileMaxX;

         boolean westGuarded = westIsTileBoundary || (i > 0 && !isGap[i - 1]);
         boolean eastGuarded = eastIsTileBoundary || (i < bankRects.size() - 1 && !isGap[i]);
         Minions.dReclaimRoads("capLateralEdges() -- [%d] %s -- westGuarded=%b (%s) eastGuarded=%b (%s)",
                 i, r,
                 westGuarded, westIsTileBoundary ? "tile boundary, highway ring already covers this side" : (i == 0 ? "first rect in run, always capped" : (westGuarded ? "separator already covers this side" : "not guarded, will cap")),
                 eastGuarded, eastIsTileBoundary ? "tile boundary, highway ring already covers this side" : (i == bankRects.size() - 1 ? "last rect in run, always capped" : (eastGuarded ? "separator already covers this side" : "not guarded, will cap")));

         if (!westGuarded) {
            r = capWestEdge(r, out, minWidth);
         }
         if (!eastGuarded) {
            r = capEastEdge(r, out, minWidth);
         }
         bankRects.set(i, r);
      }
   }

   private static Rect capWestEdge(Rect r, List<Rect> out, int minWidth) {
      // Every caller of this method needs the cap unconditionally --
      // capLateralEdges() only invokes it for sides with no neighbouring
      // district to share a road with (see its own javadoc: "unconditional,
      // not opportunistic"). Refusing here because of minWidth (as
      // insertRoadSeparators()'s genuinely optional column-sharing decision
      // does) doesn't protect anything -- it just leaves the district with
      // NO road at all on that side. The only real reason to refuse is the
      // district vanishing outright.
      if (r.sizeX <= 1) {
         Minions.dRiver("RIVER RECLAIM -- %s is only 1 chunk wide; leaving its west side uncapped (capping would eliminate the district)",
                 r);
         Minions.dReclaimRoads("capWestEdge() -- %s is only 1 chunk wide; NO road added on west side", r);
         return r;
      }
      Rect cap = new Rect(r.baseX, r.baseZ, 1, r.sizeZ, r.north);
      out.add(cap);
      Rect shrunk = new Rect(r.baseX + 1, r.baseZ, r.sizeX - 1, r.sizeZ, r.north);
      Minions.dReclaimRoads("capWestEdge() -- %s -> cap=%s, district shrinks to %s", r, cap, shrunk);
      return shrunk;
   }

   private static Rect capEastEdge(Rect r, List<Rect> out, int minWidth) {
      // Same "unconditional, not opportunistic" reasoning as capWestEdge()
      // -- see that method's javadoc. Both sides of this pair used to
      // disagree (west used this rule, east still refused below
      // minWidth), which meant a rect could end up with a west cap but no
      // matching east cap purely from which side happened to be checked
      // last, not from any real width shortfall.
      if (r.sizeX <= 1) {
         Minions.dRiver("RIVER RECLAIM -- %s is only 1 chunk wide; leaving its east side uncapped (capping would eliminate the district)",
                 r);
         Minions.dReclaimRoads("capEastEdge() -- %s is only 1 chunk wide; NO road added on east side", r);
         return r;
      }
      Rect cap = new Rect(r.baseX + r.sizeX - 1, r.baseZ, 1, r.sizeZ, r.north);
      out.add(cap);
      Rect shrunk = new Rect(r.baseX, r.baseZ, r.sizeX - 1, r.sizeZ, r.north);
      Minions.dReclaimRoads("capEastEdge() -- %s -> cap=%s, district shrinks to %s", r, cap, shrunk);
      return shrunk;
   }

   /**
    * Last chunk Z on the NORTH bank whose ENTIRE 16-block span clears the
    * berth. Conservative on purpose in three separate ways, each of which
    * would otherwise be a way to shave a build into the water:
    *
    *  - the waterline used is waterEdgeNorthBlock(), the northernmost the
    *    open water reaches anywhere in this chunk column's X span, not the
    *    value at its center -- a chunk is 16 blocks wide and both the
    *    meander and the width bucket move across it;
    *  - the berth (bank margin + at least one whole chunk) is subtracted
    *    from that before any rounding happens;
    *  - the floor()-then-minus-one below picks the last chunk that ends
    *    strictly BEFORE the limit, so a limit landing mid-chunk discards
    *    that whole chunk rather than rounding into it. The one case that
    *    looks like it loses a chunk unnecessarily -- a limit landing
    *    exactly on a chunk boundary -- doesn't: floor(96/16)-1 = 5, and
    *    chunk 5 ends at block 95, exactly one block clear.
    */
   /**
    * Smooths northLimit[]/southStart[] IN PLACE, in one pass over the
    * whole tile, before collectRuns() ever quantizes them into chunk-wide
    * runs -- this is what actually shapes the district edges, unlike
    * UrbanGrid.computeBankEdges()'s smoothing, which only reshapes the
    * bank ramp painted on top of districts that already exist. Smoothing
    * here instead of there means the staircase never gets cut into the
    * Rects in the first place, rather than being cut and then papered
    * over downstream.
    *
    * SLOPE-LIMITED MAJORANT, not a windowed extremum -- this used to take
    * a plain min/max (north: min, south: max) over a fixed radius-2
    * window, explicitly modelled on computeBankEdges()'s OLD approach.
    * That turned out to be the same dilation-not-blur problem
    * computeBankEdges() itself was later rewritten to fix (see that
    * method's own javadoc for the full "flat SHELF bounded by two sharp
    * corners" reasoning): a window copies whichever value is most
    * extreme across its whole span, so one column where the river
    * happens to swing close -- even briefly, for just a column or two --
    * flattens a 5-column-wide shelf of otherwise-open bank to that one
    * tight column's own limit, reserving far more "too close to the
    * water" land than the water ever actually reached. On a river with
    * frequent bends (a tighter meander, more of these close columns to
    * begin with) this compounds into exactly the wide, needlessly
    * unbuilt green band along a bank that's visibly dry through most of
    * it.
    *
    * The fix carries over unchanged: instead of copying the extremum
    * across a fixed window, each column's limit tapers away from a real
    * measurement at a bounded rate (RiverConfig.getRiverBankEdgeMaxSlopePerChunk(),
    * converted from blocks to whole chunks here since these arrays are
    * chunk-indexed) instead of jumping straight to it. This still
    * satisfies the same non-negotiable-berth constraint the old
    * windowed version was written to guarantee -- THE BERTH IS
    * NON-NEGOTIABLE, see the class javadoc -- and satisfies it exactly
    * the same way: at every column where a real raw measurement exists,
    * the majorant is provably no less conservative than that raw value
    * itself (see slopeLimitedMajorantInt()'s own javadoc for why). It
    * just no longer sacrifices EVERY column within a fixed radius of a
    * single tight point to that one point's own extremum; it only ever
    * sacrifices as much as the slope cap actually requires to stay safe
    * between that tight point and its more open neighbours.
    *  - north bank: larger chunk Z = deeper = closer to the water, so
    *    the majorant never sits BELOW (takeMax=false) any real column's
    *    own raw value.
    *  - south bank: smaller chunk Z = deeper, so the majorant never sits
    *    ABOVE (takeMax=true) any real column's own raw value -- the
    *    mirror image, same reasoning.
    *
    * Sentinel entries (Integer.MIN_VALUE / MAX_VALUE, meaning "nothing
    * fits here at all" -- see northLimitChunk()/southStartChunk()) carry
    * no constraint of their own, same as before: a column with no water
    * on that side isn't a real, more-conservative measurement to taper
    * from, so it's skipped rather than treated as an extreme value that
    * would spread "no data" into genuinely buildable neighbours. A
    * column stays a sentinel only if the WHOLE array is sentinel on that
    * side -- with no windowing, there's no longer a small local
    * neighbourhood for "no data" to be confined to; the slope cap itself
    * is what keeps a single real measurement from propagating further
    * than it safely can.
    */
   private static void smoothLimits(int[] northLimit, int[] southStart, int tileWidth) {
      // Same config the visual bank ramp already tapers at
      // (computeBankEdges()), converted from blocks to whole chunks since
      // these arrays are chunk-indexed -- keeps the two smoothing passes
      // moving at a consistent, recognisable rate rather than each
      // inventing its own. At least 1 chunk/column so a very small
      // configured slope can't collapse this back into a rigid,
      // unsmoothed staircase.
      int maxSlopePerColumn = Math.max(1, (int) Math.round(RiverConfig.getRiverBankEdgeMaxSlopePerChunk() / (double) CHUNK));
      int[] smoothNorth = slopeLimitedMajorantInt(northLimit, Integer.MIN_VALUE, maxSlopePerColumn, false);
      int[] smoothSouth = slopeLimitedMajorantInt(southStart, Integer.MAX_VALUE, maxSlopePerColumn, true);

      System.arraycopy(smoothNorth, 0, northLimit, 0, tileWidth);
      System.arraycopy(smoothSouth, 0, southStart, 0, tileWidth);

      Minions.dRiver("RIVER RECLAIM -- smoothed berth limits via slope-limited majorant (max %d chunk(s)/column) before collectRuns(), so district edges themselves step gently instead of only the bank ramp painted on top of them",
              maxSlopePerColumn);
   }

   /**
    * Int/sentinel port of UrbanGrid.slopeLimitedMajorant() -- see that
    * method's own javadoc for the full derivation (two-pass, slope-capped
    * propagation from every real value, equivalent to 1D grayscale
    * morphological closing with a cone-shaped structuring element).
    * Ported rather than shared because that method works in double blocks
    * with NaN as "no data"; this one works in int chunks with an explicit
    * sentinel, and RiverReclaim has no dependency on UrbanGrid to call
    * back into it.
    *
    * Returns the smallest curve that (a) is never less conservative, at
    * ANY index where raw[] has a real (non-sentinel) value, than that
    * value itself -- takeMax=false: result <= raw[i]; takeMax=true:
    * result >= raw[i] -- and (b) never changes by more than
    * maxSlopePerColumn between adjacent indices. Sentinel entries carry
    * no constraint of their own; the result there is whatever the
    * nearest real entry's slope-capped taper reaches (still the sentinel
    * if no real entry exists anywhere in the array).
    */
   private static int[] slopeLimitedMajorantInt(int[] raw, int sentinel, int maxSlopePerColumn, boolean takeMax) {
      int n = raw.length;
      int[] forward = new int[n];
      boolean[] hasForward = new boolean[n];
      int running = 0;
      boolean has = false;
      for (int i = 0; i < n; i++) {
         if (raw[i] != sentinel) {
            running = !has ? raw[i]
                    : (takeMax ? Math.max(raw[i], running - maxSlopePerColumn) : Math.min(raw[i], running + maxSlopePerColumn));
            has = true;
         } else if (has) {
            running = takeMax ? running - maxSlopePerColumn : running + maxSlopePerColumn;
         }
         forward[i] = running;
         hasForward[i] = has;
      }

      int[] result = new int[n];
      running = 0;
      has = false;
      for (int i = n - 1; i >= 0; i--) {
         if (raw[i] != sentinel) {
            running = !has ? raw[i]
                    : (takeMax ? Math.max(raw[i], running - maxSlopePerColumn) : Math.min(raw[i], running + maxSlopePerColumn));
            has = true;
         } else if (has) {
            running = takeMax ? running - maxSlopePerColumn : running + maxSlopePerColumn;
         }
         if (!hasForward[i]) {
            result[i] = has ? running : sentinel;
         } else if (!has) {
            result[i] = forward[i];
         } else {
            result[i] = takeMax ? Math.max(forward[i], running) : Math.min(forward[i], running);
         }
      }
      return result;
   }

   private static int northLimitChunk(WaterEdgeProvider river, int worldChunkX) {
      double water = river.waterEdgeNorthBlock(worldChunkX);
      if (Double.isNaN(water)) {
         return Integer.MIN_VALUE;
      }
      double limitBlock = water - berthBlocks();
      return (int) Math.floor(limitBlock / (double) CHUNK) - 1;
   }

   /** South-bank mirror of northLimitChunk(); ceil() plays the same role floor()-1 does there. */
   private static int southStartChunk(WaterEdgeProvider river, int worldChunkX) {
      double water = river.waterEdgeSouthBlock(worldChunkX);
      if (Double.isNaN(water)) {
         return Integer.MAX_VALUE;
      }
      double limitBlock = water + berthBlocks();
      return (int) Math.ceil(limitBlock / (double) CHUNK);
   }

   /**
    * Total clearance in BLOCKS between open water and the nearest buildable
    * chunk. Deliberately NOT bankMargin-inclusive: bankMargin is a
    * material-blend distance (see RiverConfig.getRiverBankMargin()'s own
    * javadoc) that controls where the terrain visually finishes
    * transitioning from sand/gravel to grass, not a buildable-clearance
    * setting -- folding it in here made raising it silently eat into the
    * buildable footprint too, which is a different knob. The berth alone
    * is what the caller asked for in chunks and RiverConfig floors at 1,
    * so this is never less than a full chunk -- there is always
    * undeveloped ground between a building and the waterline.
    */
   private static double berthBlocks() {
      return RiverConfig.getRiverReclaimBerthChunks() * (double) CHUNK;
   }

   /**
    * Walks the columns left to right and cuts them into rectangles.
    *
    * A rectangle's depth is the SHALLOWEST depth of any column in it (the
    * running min below), because a district is a rectangle and the water
    * curves: one column bulging toward the road has to pull the whole
    * rectangle back with it, or that rectangle's far corner sits inside
    * the berth of the column next door. A run is closed and restarted
    * whenever extending it would drag the shared depth under
    * getRiverReclaimMinDepth() -- that's what makes the reclaimed edge
    * step down in stages along a meander instead of being flattened to the
    * single worst column in the tile.
    *
    * Runs are also capped at getRiverReclaimMaxWidth() so a long straight
    * bank becomes several districts rather than one enormous one; each
    * then draws its own context and its own buildings, the same as any
    * ordinary block.
    */
   private static void collectRuns(List<Rect> out, double[] edgeOut, List<Rect> fallbackRoads, int tileMinX, int tileWidth,
                                   int[] limits, int anchorZ, boolean north) {
      int minDepth = Math.max(1, RiverConfig.getRiverReclaimMinDepth());
      // +1: insertWaterFrontageRoads() always peels one row off the
      // water-facing edge of whatever run gets emitted here, so a run
      // only gets to exist at all if minDepth of ACTUAL building still
      // remains after that row is gone. The full (unreserved) depth is
      // still what gets stored in the Rect below -- reservedDepth only
      // gates which runs are usable, it never gets recorded anywhere.
      int reservedDepth = minDepth + 1;
      int minWidth = Math.max(1, RiverConfig.getRiverReclaimMinWidth());
      int maxWidth = Math.max(minWidth, RiverConfig.getRiverReclaimMaxWidth());

      int runStart = -1;
      int runDepth = 0; // shared (minimum) depth across the run so far

      // Runs are gathered here first, as {startIdx, width, depth}, and only
      // emitted once the whole tile has been walked -- see
      // absorbTailSlivers() for why emission can't happen inline.
      List<int[]> runs = new ArrayList<>();

      for (int i = 0; i <= tileWidth; i++) {
         int depth = i < tileWidth ? depthAt(limits[i], anchorZ, north) : 0;
         boolean usable = i < tileWidth && depth >= reservedDepth;

         if (usable && runStart < 0) {
            runStart = i;
            runDepth = depth;
            continue;
         }

         if (usable) {
            int merged = Math.min(runDepth, depth);
            if (merged >= reservedDepth && (i - runStart) < maxWidth) {
               runDepth = merged;
               continue;
            }
         }

         if (runStart >= 0) {
            runs.add(new int[]{runStart, i - runStart, runDepth});
            runStart = -1;
            runDepth = 0;
            if (usable) {
               // This column couldn't join the run that just closed, but
               // it's perfectly buildable on its own -- it opens the next
               // one rather than being skipped.
               runStart = i;
               runDepth = depth;
            }
         }
      }

      absorbTailSlivers(runs, minWidth);

      for (int[] run : runs) {
         emit(out, edgeOut, fallbackRoads, tileMinX, run[0], run[1], run[2], anchorZ, north, minWidth);
      }
   }

   /**
    * Folds a too-narrow tail run into the run right before it, instead of
    * letting emit() pave it as a fallback road.
    *
    * WHY: when a stretch of buildable columns is cut into getRiverReclaimMaxWidth()-
    * wide runs, whatever is left over at the far (east) end of the stretch
    * becomes one last, short run -- e.g. a 62-wide tile cut into 13-wide
    * runs leaves the final run 1 wide. emit() paved that leftover as a
    * fallback road, and then capLateralEdges() ALSO capped the district
    * before it, because with the leftover sitting between them the district
    * no longer reaches the end of the stretch itself. Result: two parallel
    * road columns (cap + fallback) where the district should simply have run
    * on to the end. At the tile's east edge that end is the highway ring,
    * which is already a road -- so the district sat two chunks off a highway
    * it should have been butting against, with two redundant roads between.
    * Absorbing the leftover lets the district reach the end of the stretch,
    * where capLateralEdges() then does the right thing on its own (no cap at
    * the tile boundary; one cap, not two, at an interior gap).
    *
    * Only a leftover TOUCHING the run before it can be a tail: the only
    * thing that closes a run and immediately opens the next one with no gap
    * is the maxWidth cut (two usable columns never merge to a depth below
    * reservedDepth), and a maxWidth cut always leaves the earlier run
    * full-width, so the narrow one is always the later of the pair. Narrow
    * runs bordered by unusable columns on both sides (a genuinely isolated
    * sliver) have nothing to join and still go through emit()'s fallback
    * road, unchanged.
    *
    * SAFETY: the absorbed columns adopt the earlier run's depth, so that
    * depth must not exceed what the leftover itself measured (its own depth
    * is the shallowest of its columns) -- building any deeper than a column
    * measured would break the berth guarantee. If the leftover is shallower
    * than the run before it, nothing is merged: pulling the whole run back
    * to the leftover's depth would cost far more buildable ground than the
    * leftover is worth, so it stays a fallback road as before.
    *
    * The merged run may exceed getRiverReclaimMaxWidth() by at most
    * (minWidth - 1) columns; insertRoadSeparators() then carves its lateral
    * column out of it, which typically brings it back to or under the cap.
    */
   private static void absorbTailSlivers(List<int[]> runs, int minWidth) {
      for (int k = runs.size() - 1; k >= 1; k--) {
         int[] tail = runs.get(k);
         int[] prev = runs.get(k - 1);
         if (tail[1] < minWidth
                 && prev[0] + prev[1] == tail[0]   // touches the run before it
                 && tail[2] >= prev[2]) {          // never builds deeper than measured
            Minions.dReclaimRoads("absorbTailSlivers() -- tail x-idx=[%d,%d) depth=%d joins run x-idx=[%d,%d) depth=%d (instead of fallback road)",
                    tail[0], tail[0] + tail[1], tail[2], prev[0], prev[0] + prev[1], prev[2]);
            prev[1] += tail[1];
            runs.remove(k);
         }
      }
   }

   /** Chunks available between the riverside road (anchorZ) and this column's berth limit, inclusive. */
   private static int depthAt(int limit, int anchorZ, boolean north) {
      if (limit == Integer.MIN_VALUE || limit == Integer.MAX_VALUE) {
         return 0;
      }
      return north ? (limit - anchorZ + 1) : (anchorZ - limit + 1);
   }

   /**
    * Commits one run as a Rect and records the water-facing edge it
    * creates for every column it covers -- the two always happen together,
    * because that edge is only meaningful once the rectangle's final
    * (shared, already-shrunk-to-the-minimum) depth is known.
    *
    * The recorded Z is the boundary BETWEEN the district's last chunk and
    * the ground beyond it: on the north bank that's the block just south
    * of the district, on the south bank the block at its north face. That
    * is the exact line RiverParcel ramps up to.
    */
   private static void emit(List<Rect> out, double[] edgeOut, List<Rect> fallbackRoads, int tileMinX, int startIdx, int runWidth,
                            int depth, int anchorZ, boolean north, int minWidth) {
      if (depth <= 0) {
         return;
      }

      int baseX = tileMinX + startIdx;

      if (runWidth < minWidth) {
         // Too narrow to stand as its own district, but every column in
         // it already cleared reservedDepth (see collectRuns()'s
         // `usable` check) -- it's real, berth-clear ground, just not
         // wide enough. Previously this was simply dropped: the columns
         // kept whatever flat RiverParcel.paintLand() grass was already
         // there, with no road and no building, stranded between two
         // runs that DID get emitted -- exactly the "corner" that's
         // never populated with anything.
         //
         // Only ONE column is ever paved as the actual connecting road,
         // exactly like insertWaterFrontageRoads()/insertRoadSeparators()
         // peel a single column and shrink the district around it --
         // RoadParcel has no notion of a multi-lane paved block, only a
         // 1-wide lane network, and its shape-selection reads EVERY
         // neighbouring road cell as a turn/intersection to route
         // through. Painting the run's full width as road (as this used
         // to) put two or more of those 1-wide lanes flush against each
         // other for the run's entire depth, so every interior cell had
         // a "street" on every side and rendered as an unbroken field of
         // 4-way intersections -- worse, adjacent narrow runs chained
         // this way paint seamlessly into one continuous paved block far
         // bigger than any single run, with no district or building
         // anywhere in it. A run this narrow only needs ONE lane to tie
         // the districts on either side of it together; whatever width
         // is left over is real, berth-clear ground (same as any other
         // emitted run) and becomes its own (tiny) district instead of
         // more pavement, exactly like every other leftover strip in
         // this file. Full depth, uncapped either way -- see
         // fallbackRoads' field javadoc for why capping IT would be
         // worse, not better.
         int fallbackBaseZ = north ? anchorZ : (anchorZ - depth + 1);
         fallbackRoads.add(new Rect(baseX, fallbackBaseZ, 1, depth, north));
         Minions.dRiver("RIVER RECLAIM -- run at x=[%d,%d) depth=%d is narrower than getRiverReclaimMinWidth()=%d; paving one lane as road instead of leaving it unclaimed",
                 baseX, baseX + runWidth, depth, minWidth);
         Minions.dReclaimRoads("emit() -- run x=[%d,%d) depth=%d < minWidth=%d -- FALLBACK ROAD (1 lane, was previously dropped entirely)",
                 baseX, baseX + runWidth, depth, minWidth);

         if (runWidth > 1) {
            // Everything past that one lane is real, berth-clear ground --
            // hand it to `out` as a genuine (if small) district rather
            // than paving it too. Its own water-facing edge still needs
            // recording exactly like any other emitted run below.
            int remainderStartIdx = startIdx + 1;
            int remainderWidth = runWidth - 1;
            int remainderBaseX = baseX + 1;
            out.add(new Rect(remainderBaseX, fallbackBaseZ, remainderWidth, depth, north));
            Minions.dReclaimRoads("emit() -- run x=[%d,%d) remainder after 1-lane fallback road -> district Rect x=[%d,%d) depth=%d",
                    baseX, baseX + runWidth, remainderBaseX, remainderBaseX + remainderWidth, depth);

            double edge = north
                    ? (anchorZ + depth) * (double) CHUNK
                    : (anchorZ - depth + 1) * (double) CHUNK;
            for (int i = remainderStartIdx; i < remainderStartIdx + remainderWidth; i++) {
               edgeOut[i] = edge;
            }
         }
         return;
      }

      int baseZ = north ? anchorZ : (anchorZ - depth + 1);
      out.add(new Rect(baseX, baseZ, runWidth, depth, north));

      double edge = north
              ? (anchorZ + depth) * (double) CHUNK          // first block south of the district
              : (anchorZ - depth + 1) * (double) CHUNK;     // first block of the district, its north face

      for (int i = startIdx; i < startIdx + runWidth; i++) {
         edgeOut[i] = edge;
      }
   }
}