package ch.k42.metropolis.grid.urbanGrid.river;

/**
 * River/bridge settings, kept as their own class rather than folded into
 * PluginConfig -- same field+getter shape PluginConfig itself uses, just
 * broken out so it didn't require restructuring PluginConfig.java's
 * existing content.
 *
 * WIRING: PluginConfig.loadFromFile() reads the generator.river.* keys
 * below and pushes them into the setters here on every config reload, the
 * same pass as every other generator.* key. Until config.yml actually has
 * a generator.river.enabled: true, riverEnabled stays at its hardcoded
 * default (false) and the river is inert -- these fields are only ever as
 * current as the last successful loadFromFile() call.
 */
public class RiverConfig {

   private static boolean riverEnabled = true;

   // Bounded tributaries are the sole creek-site implementation.
   // The regular river enable switch still controls all river generation.
   private static int boundedMaxLengthChunks = 192;

   public static int getBoundedMaxLengthChunks() { return boundedMaxLengthChunks; }
   public static void setBoundedMaxLengthChunks(int value) {
      boundedMaxLengthChunks = Math.max(16, Math.min(512, value));
   }

   // River/creek/riverbank street level, kept deliberately SEPARATE from
   // PluginConfig.getBuildHeight() (which every building/road/bridge deck
   // is pasted/decayed relative to). RiverParcel.carveColumn()/CreekParcel
   // use this for waterY, but keep the corridor's dry-land/ramp-target
   // height (paintLand(), paintBank()'s far endpoint) on the general
   // buildHeight -- that's what makes the bank's existing elevation ramp
   // (paintBank()'s targetY interpolation from waterY up to buildHeight)
   // automatically absorb any gap between the two instead of producing a
   // seam, rather than needing a second, separate ramp of its own. Own
   // hardcoded default (63) matches PluginConfig's buildHeight default, so
   // behaviour is unchanged until generator.river.buildHeight is set.
   private static int riverBuildHeight = 63;

   // Build height for the RECLAIMED DISTRICTS themselves (the ordinary
   // buildings RiverReclaim hands back to addDistrict()), kept separate
   // from riverBuildHeight above. riverBuildHeight is a water-relative
   // level -- it's what RiverParcel/CreekParcel paint the bank's flat
   // land and ramp target at, so it has to stay tied to waterY. A
   // reclaimed district's buildings have no such requirement; forcing
   // them onto riverBuildHeight just because Parcel.effectiveBuildHeight()
   // needed SOME non-default height to key off of was a shortcut, not a
   // real constraint. Own hardcoded default (63) matches both the other
   // two defaults, so behaviour is unchanged until generator.river.reclaim.buildHeight is set.
   private static int riverReclaimBuildHeight = 63;

   // When true, RiverProvider.spineZBlock() skips its meander noise
   // entirely and returns a perfectly straight centerline at baseZ for
   // every X -- same baseZ placement/seed as the normal meandering river,
   // just with the wobble turned off. isEastWestOriented() and widthAt()
   // are unaffected: a straight river can still be wide/narrow in bands
   // and (in the unlikely case baseZ itself sits near a tile's diagonal)
   // still gets oriented correctly, since a flat line's own slope is
   // always 0.
   private static boolean riverStraight = false;

   // Cap on the CROSS-flow dimension once UrbanGrid.recSetDistricts() finds
   // a candidate rectangle whose corner falls in the river's width band --
   // same role PluginConfig.getDenseResidentialBlockSize() plays for
   // DENSERESIDENTIAL, just applied to one axis only (see
   // RiverProvider.isEastWestOriented()) so the long axis is left free to
   // run the full length of the strip instead of also being capped.
   private static int riverBlockSize = 5;

   // Chunks of blended sand/gravel/grass on each side of open water before
   // RiverParcel treats a column as ordinary dry land.
   private static int riverBankMargin = 2;

   // How many blocks below this class's own riverBuildHeight (below)
   // the water's surface sits.
   private static int riverDepth = 6;

   // How many blocks deep the water COLUMN itself is at its deepest --
   // i.e. how far the riverbed sits below the water's surface once you're
   // a few blocks in from the shore. Independent of riverDepth above:
   // riverDepth only controls where the surface sits relative to street
   // level, this controls how deep it visually reads once you're looking
   // at it. Used by RiverParcel.paintWater(), which tapers the actual
   // per-column depth from 1 block right at the shoreline up to this full
   // value -- so this is a maximum, not a fixed depth applied everywhere.
   private static int riverWaterColumnDepth = 4;

   // Blocks of clearance BridgeParcel leaves between the water surface and
   // the underside of the bridge deck.
   private static int riverBridgeClearance = 4;

   // How many chunks the river's BASELINE (its centerline's resting
   // position, before the meander wobbles it) is kept clear of a 64-chunk
   // tile's Z-edge -- see RiverProvider's constructor. A river that starts
   // out sitting right on a tile boundary is the case most likely to drift
   // across the corner where four tiles meet, which reads as visibly
   // unnatural (a river can't really cross its own bend at a perfect grid
   // intersection). Constrains only the BASELINE, not the meander itself
   // -- a wide meander can still occasionally swing close to an edge over
   // a long enough X distance, which is a much softer, rarer case than
   // defaulting to sitting on one. 0 disables this (baseZ can land
   // anywhere in its range, including right on a tile edge, same as
   // before this existed).
   private static int riverTileEdgeMargin = 12;

   // When the ocean is enabled, anchor the river's centerline so it passes
   // through the middle of the ocean instead of being placed independently
   // of it -- the river then runs into the sea from both sides of it, and
   // OceanProvider merges its channel into the ocean at the mouth. Ignored
   // (the river keeps its old, independent placement) when the ocean or the
   // river is disabled.
   private static boolean riverConvergeOnOcean = true;

   // Second river, running north-south instead of east-west, anchored on the
   // ocean the same way. Only exists when riverConvergeOnOcean is on.
   private static boolean riverZBoundEnabled = true;

   // Open-water width, in CHUNKS, RiverProvider.widthAt() picks between
   // based on noise -- narrowest band, middle band, widest band. Was
   // hardcoded to 3/5/7 chunks (48/80/112 blocks) with no config knob at
   // all; combined with bankMargin on both sides that put the total
   // footprint as high as 176 blocks across at its widest, read as a bay
   // rather than a river. Defaults below are noticeably smaller -- scale
   // up freely if you actually want something that wide.
   private static int riverWidthNarrow = 2;
   private static int riverWidthMedium = 3;
   private static int riverWidthWide = 4;

   // Creeks are rivers: a creek is a RiverProvider (RiverProvider.forCreek()) that shares every
   // RiverConfig meander/bank/reclaim setting with the main river, differing only in its exact
   // anchor at the origin wall, its fixed width, a meander scale derived from that width, and the
   // along-range it is valid over. The settings below only govern WHERE creeks are put.
   //
   // A creek site's march from its origin toward the target river gives up (the site is dropped,
   // nothing is placed) if it hasn't reached the river's open water within this many chunks. It is
   // a validity limit only -- sites are derived from the seed before any tile builds, so a creek
   // either provably reaches water or never exists.
   private static int creekMaxTraceChunks = 32;

   // Percent chance, per 64x64 tile, that the tile hosts a creek site (a building that declares a
   // riverStartBounds side, placed so its creek provably reaches a river).
   private static int creekSiteChancePercent = 20;
   // How many (variant, position) draws a tile's site gets before it gives up for good.
   private static int creekSiteAttempts = 24;

   // Minimum straight-line separation between accepted creek origins, in world chunks.
   // This is global across city boundaries; 0 disables the spacing arbitration.
   private static int creekOriginSpacingChunks = 120;

   public static int getCreekOriginSpacingChunks() { return creekOriginSpacingChunks; }
   public static void setCreekOriginSpacingChunks(int value) {
      creekOriginSpacingChunks = Math.max(0, Math.min(512, value));
   }

   // The meander fades in from zero over this many chunks from the anchor, so the creek leaves the
   // building wall straight, exactly on the wall's centre.
   private static double creekMeanderRampChunks = 3.0;

   // UrbanGrid.tryPlaceRiverBand()'s "clean margin" peel requires an
   // ENTIRE row (or column) to be river-free across the full current
   // width (or height) of whatever rectangle it's looking at. On a large
   // rectangle -- the very first call for a tile is up to 62 chunks wide
   // -- a river sweeping diagonally through it almost never leaves a
   // genuinely wide clean margin near the water, just a series of
   // increasingly thin ones as the peel narrows in one strip at a time,
   // laying a NEW road at every thin peel along the way: a staircase of
   // many roads hugging the river instead of one road tracing its edge.
   // This caps how large the PERPENDICULAR dimension is allowed to be
   // before peeling on that axis is even attempted -- above this, the
   // rectangle bisects (no road placed) first, so "fully clean" only
   // ever gets evaluated once it's actually a tight, edge-hugging
   // signal rather than a near-impossible ask across dozens of chunks.
   private static int riverPeelMaxDimension = 20;

   // --- district reclamation (RiverReclaim / UrbanGrid.reclaimRiverDistricts()) ---

   // Master toggle for handing the river corridor's leftover green space
   // back to ordinary district carving. Off reproduces exactly the old
   // behaviour: the whole envelope stays RiverParcel, flat grass out to
   // the riverside road on both banks.
   private static boolean riverReclaimEnabled = true;

   // Whole chunks of clearance kept between open water and the nearest
   // buildable chunk. Floored at 1 by the getter below and NOT settable
   // lower: the berth is measured against the worst-case waterline in a
   // chunk column (see RiverReclaim.northLimitChunk()), and a value of 0
   // would let a building's own chunk share a boundary with the bank's
   // slope -- which is how you end up with a foundation cut into the
   // river. Deliberately NOT combined with riverBankMargin -- that's a
   // material-blend distance, not a buildable-clearance one; see its own
   // javadoc. Raise this for a wider park-like buffer along the water;
   // it cannot go below one chunk.
   private static int riverReclaimBerthChunks = 1;

   // Smallest reclaimed district, in chunks. A 1-deep or 1-wide strip of
   // buildings pinned between a road and a riverbank reads as a wall, not
   // a block, and gives District's packing almost nothing to work with --
   // below these, the space is left as green bank instead.
   private static int riverReclaimMinDepth = 2;
   private static int riverReclaimMinWidth = 3;

   // Longest run of columns folded into a single reclaimed district before
   // starting another. A bank can run the full 62-chunk width of a tile;
   // without this the whole thing becomes one district with one context
   // and one building pass, which reads as a single repeated terrace.
   private static int riverReclaimMaxWidth = 8;

   // Optional cap, in chunks, on how far the bank's elevation ramp is
   // allowed to run before it levels off -- see
   // RiverParcel.carveColumn(). 0 (the default) means no cap: the ramp
   // stretches across whatever gap reclamation left, so the bank rises
   // continuously from the water all the way to the district's edge. Set
   // a value here if a very deep berth produces a slope too gentle to
   // read as a bank; the ramp then finishes within that many chunks and
   // the remainder is flat ground at build height, which still meets the
   // district's edge seamlessly (same height), just without a visible
   // slope for the last stretch.
   private static int riverBankMaxRunChunks = 0;

   // Meander shape: two stacked noise layers (a broad "slow" bend plus a
   // subtler "fast" wobble on top). Frequency is in cycles per chunk --
   // roughly 1/frequency chunks is the layer's characteristic wavelength
   // -- and amplitude is in chunks of Z-deviation from baseZ. Defaults
   // reproduce RiverProvider's previous hardcoded shape (slow: freq
   // 0.0025/amplitude 18, fast: freq 0.015/amplitude 3), which gives the
   // slow layer a ~400-chunk wavelength -- several tiles wide, so a
   // single 64-chunk tile only shows a small slice of the bend and can
   // read as nearly straight. Raise the frequencies (shorten the
   // wavelength) if you want the curve visible within one tile.
   private static double riverMeanderSlowFrequency = 0.0025;
   private static double riverMeanderSlowAmplitude = 18.0;
   private static double riverMeanderFastFrequency = 0.015;
   private static double riverMeanderFastAmplitude = 3.0;

   // Modulates slow/fast amplitude together, over a MUCH longer wavelength
   // than either -- so long stretches read as curvier or straighter than
   // others, instead of the same curviness everywhere. The multiplier
   // stays in [riverMeanderIntensityMin, 1.0]: it only ever SCALES DOWN
   // the configured amplitudes above, never past them, so
   // getEnvelopeHalfWidth()'s corridor math (and everything else that
   // trusts slow/fastAmplitude as the worst case) stays valid without any
   // change on its end.
   private static double riverMeanderIntensityFrequency = 0.0003;
   private static double riverMeanderIntensityMin = 0.3;

   // Max change, in blocks, that computeBankEdges() lets the bank-ramp
   // target move between two adjacent chunk columns -- see that method's
   // own javadoc. Bigger = gentler/longer transitions between two very
   // different nearby depths, smaller = the ramp target can jump faster
   // (closer to the raw, un-smoothed measurement).
   private static double riverBankEdgeMaxSlopePerChunk = 4.0;

   // --- riverside vegetation (RiverParcel's BankSite, planted by ForestGenerator.populateShore()) ---

   // Independent toggles: trees and ground cover can be turned on separately. Both are the FOREST's own --
   // its species, density, ground cover and biome tint for whatever forest type the spot falls in -- so a
   // bank blends into a forest that runs down to it. The percentages scale the forest's own tree density and
   // ground cover: 100 = exactly as dense as the forest, 50 = half. Trees only ever root past the wet
   // sand/gravel band, ground cover only on columns whose FINAL top block came out as GRASS, never on open water.
   private static boolean riverTreesEnabled = false;
   private static int riverTreesChancePercent = 100;
   private static boolean riverVegetationEnabled = false;
   private static int riverVegetationChancePercent = 100;

   private RiverConfig() {
   }

   /** Master toggle -- RiverProvider returns false/NaN for everything when off, so no river is ever carved. */
   public static boolean isRiverEnabled() {
      return riverEnabled;
   }

   public static void setRiverEnabled(boolean value) {
      riverEnabled = value;
   }

   /** River/creek/riverbank street level -- independent of PluginConfig.getBuildHeight(). */
   public static int getRiverBuildHeight() {
      return riverBuildHeight;
   }

   public static void setRiverBuildHeight(int value) {
      riverBuildHeight = value;
   }

   /** Build height for reclaimed-district buildings -- independent of both getRiverBuildHeight() and PluginConfig.getBuildHeight(). */
   public static int getRiverReclaimBuildHeight() {
      return riverReclaimBuildHeight;
   }

   public static void setRiverReclaimBuildHeight(int value) {
      riverReclaimBuildHeight = value;
   }

   /** When true, the river runs perfectly straight (no meander noise) at its usual baseZ placement. */
   public static boolean isRiverStraight() {
      return riverStraight;
   }

   public static void setRiverStraight(boolean value) {
      riverStraight = value;
   }

   public static int getRiverBlockSize() {
      return riverBlockSize;
   }

   public static void setRiverBlockSize(int value) {
      riverBlockSize = value;
   }

   public static int getRiverBankMargin() {
      return riverBankMargin;
   }

   public static void setRiverBankMargin(int value) {
      riverBankMargin = value;
   }

   public static int getRiverDepth() {
      return riverDepth;
   }

   public static void setRiverDepth(int value) {
      riverDepth = value;
   }

   public static int getRiverWaterColumnDepth() {
      return riverWaterColumnDepth;
   }

   public static void setRiverWaterColumnDepth(int value) {
      riverWaterColumnDepth = value;
   }

   public static int getRiverBridgeClearance() {
      return riverBridgeClearance;
   }

   public static void setRiverBridgeClearance(int value) {
      riverBridgeClearance = value;
   }

   public static int getRiverTileEdgeMargin() {
      return riverTileEdgeMargin;
   }

   public static void setRiverTileEdgeMargin(int value) {
      riverTileEdgeMargin = value;
   }

   public static int getRiverWidthNarrow() {
      return riverWidthNarrow;
   }

   public static void setRiverWidthNarrow(int value) {
      riverWidthNarrow = value;
   }

   public static int getRiverWidthMedium() {
      return riverWidthMedium;
   }

   public static void setRiverWidthMedium(int value) {
      riverWidthMedium = value;
   }

   public static int getRiverWidthWide() {
      return riverWidthWide;
   }

   public static void setRiverWidthWide(int value) {
      riverWidthWide = value;
   }

   public static int getCreekMaxTraceChunks() {
      return creekMaxTraceChunks;
   }

   /**
    * The longest a creek is ever allowed to be, whatever the config says: three 64-chunk grids. A creek
    * is a short tributary of the water it flows into; a site whose creek would need to run further than
    * this to reach a river's water is simply not a site.
    */
   public static final int CREEK_MAX_TRACE_LIMIT_CHUNKS = 192;

   public static void setCreekMaxTraceChunks(int value) {
      creekMaxTraceChunks = Math.max(1, Math.min(CREEK_MAX_TRACE_LIMIT_CHUNKS, value));
   }

   public static int getCreekSiteChancePercent() {
      return creekSiteChancePercent;
   }

   public static void setCreekSiteChancePercent(int value) {
      creekSiteChancePercent = value;
   }

   public static int getCreekSiteAttempts() {
      return creekSiteAttempts;
   }

   public static void setCreekSiteAttempts(int value) {
      creekSiteAttempts = value;
   }

   public static double getCreekMeanderRampChunks() {
      return creekMeanderRampChunks;
   }

   public static int getRiverPeelMaxDimension() {
      return riverPeelMaxDimension;
   }

   public static void setRiverPeelMaxDimension(int value) {
      riverPeelMaxDimension = value;
   }

   /** Master toggle for reclaiming corridor green space as buildable districts. */
   public static boolean isRiverReclaimEnabled() {
      return riverReclaimEnabled;
   }

   public static void setRiverReclaimEnabled(boolean value) {
      riverReclaimEnabled = value;
   }

   /**
    * Chunks of clearance between open water and the nearest buildable
    * chunk, on top of the bank margin. Clamped to a minimum of 1 HERE
    * rather than at the call site so no caller -- config reload included
    * -- can drive it to 0 and let a build share an edge with the bank.
    */
   public static int getRiverReclaimBerthChunks() {
      return Math.max(1, riverReclaimBerthChunks);
   }

   public static void setRiverReclaimBerthChunks(int value) {
      riverReclaimBerthChunks = Math.max(1, value);
   }

   public static int getRiverReclaimMinDepth() {
      return riverReclaimMinDepth;
   }

   public static void setRiverReclaimMinDepth(int value) {
      riverReclaimMinDepth = value;
   }

   public static int getRiverReclaimMinWidth() {
      return riverReclaimMinWidth;
   }

   public static void setRiverReclaimMinWidth(int value) {
      riverReclaimMinWidth = value;
   }

   public static int getRiverReclaimMaxWidth() {
      return riverReclaimMaxWidth;
   }

   public static void setRiverReclaimMaxWidth(int value) {
      riverReclaimMaxWidth = value;
   }

   /** 0 = uncapped: the bank ramp runs the full distance to the reclaimed district's edge. */
   public static int getRiverBankMaxRunChunks() {
      return riverBankMaxRunChunks;
   }

   public static void setRiverBankMaxRunChunks(int value) {
      riverBankMaxRunChunks = Math.max(0, value);
   }

   public static double getRiverMeanderSlowFrequency() {
      return riverMeanderSlowFrequency;
   }

   public static void setRiverMeanderSlowFrequency(double value) {
      riverMeanderSlowFrequency = value;
   }

   public static double getRiverMeanderSlowAmplitude() {
      return riverMeanderSlowAmplitude;
   }

   public static void setRiverMeanderSlowAmplitude(double value) {
      riverMeanderSlowAmplitude = value;
   }

   public static double getRiverMeanderFastFrequency() {
      return riverMeanderFastFrequency;
   }

   public static void setRiverMeanderFastFrequency(double value) {
      riverMeanderFastFrequency = value;
   }

   public static double getRiverMeanderFastAmplitude() {
      return riverMeanderFastAmplitude;
   }

   public static void setRiverMeanderFastAmplitude(double value) {
      riverMeanderFastAmplitude = value;
   }

   public static double getRiverMeanderIntensityFrequency() {
      return riverMeanderIntensityFrequency;
   }

   public static void setRiverMeanderIntensityFrequency(double value) {
      riverMeanderIntensityFrequency = value;
   }

   public static double getRiverMeanderIntensityMin() {
      return riverMeanderIntensityMin;
   }

   /** Clamped to [0,1] -- outside that range the multiplier could scale amplitude ABOVE the configured max, which callers rely on as a hard ceiling. */
   public static void setRiverMeanderIntensityMin(double value) {
      riverMeanderIntensityMin = Math.max(0.0, Math.min(1.0, value));
   }

   public static double getRiverBankEdgeMaxSlopePerChunk() {
      return riverBankEdgeMaxSlopePerChunk;
   }

   public static void setRiverBankEdgeMaxSlopePerChunk(double value) {
      riverBankEdgeMaxSlopePerChunk = Math.max(0.0, value);
   }

   public static boolean isRiverTreesEnabled() {
      return riverTreesEnabled;
   }

   public static void setRiverTreesEnabled(boolean value) {
      riverTreesEnabled = value;
   }

   public static int getRiverTreesChancePercent() {
      return riverTreesChancePercent;
   }

   public static void setRiverTreesChancePercent(int value) {
      riverTreesChancePercent = Math.max(0, Math.min(100, value));
   }

   public static boolean isRiverVegetationEnabled() {
      return riverVegetationEnabled;
   }

   public static void setRiverVegetationEnabled(boolean value) {
      riverVegetationEnabled = value;
   }

   public static int getRiverVegetationChancePercent() {
      return riverVegetationChancePercent;
   }

   public static void setRiverVegetationChancePercent(int value) {
      riverVegetationChancePercent = Math.max(0, Math.min(100, value));
   }

   public static boolean isRiverConvergeOnOcean() {
      return riverConvergeOnOcean;
   }

   public static void setRiverConvergeOnOcean(boolean value) {
      riverConvergeOnOcean = value;
   }

   public static boolean isRiverZBoundEnabled() {
      return riverZBoundEnabled;
   }

   public static void setRiverZBoundEnabled(boolean value) {
      riverZBoundEnabled = value;
   }
}