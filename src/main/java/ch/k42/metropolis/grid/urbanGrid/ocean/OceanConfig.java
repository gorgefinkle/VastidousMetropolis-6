package ch.k42.metropolis.grid.urbanGrid.ocean;

/**
 * Ocean settings, kept as their own class in their own package -- same
 * field+getter+setter shape RiverConfig itself uses, deliberately NOT
 * folded into RiverConfig even though the two features share a lot of
 * plumbing (both carve a body of open water into the world independently
 * of the district grid). The ocean is a single, roughly-circular body
 * placed once per world at a seed-derived center point; the river is an
 * unbounded meandering line -- different enough in shape that sharing one
 * config class would mean either a pile of fields that only make sense for
 * one of the two, or overloading one field's meaning between them (e.g.
 * "width" for a river vs. "radius" for an ocean). Two small, single-purpose
 * classes reads far more clearly than one that tries to serve both.
 *
 * WIRING: PluginConfig.loadFromFile() reads the generator.ocean.* keys
 * below and pushes them into the setters here on every config reload, same
 * pass as RiverConfig's own generator.river.* keys. Until config.yml
 * actually has a generator.ocean.enabled: true, oceanEnabled stays at its
 * hardcoded default and the ocean is inert.
 */
public class OceanConfig {

    private static boolean oceanEnabled = false;
    // Separate from the ocean toggle: coastal ports can be disabled without disabling water.
    private static boolean oceanPortsEnabled = true;
    private static boolean oceanPortsDebug = false;
    private static int oceanPortsChancePercent = 15;

    public static boolean isOceanPortsEnabled() { return oceanPortsEnabled; }
    public static void setOceanPortsEnabled(boolean enabled) { oceanPortsEnabled = enabled; }
    public static boolean isOceanPortsDebug() { return oceanPortsDebug; }
    public static void setOceanPortsDebug(boolean enabled) { oceanPortsDebug = enabled; }
    public static int getOceanPortsChancePercent() { return oceanPortsChancePercent; }
    public static void setOceanPortsChancePercent(int value) {
        oceanPortsChancePercent = Math.max(0, Math.min(100, value));
    }


    // Dry-land/shore height the ocean's beach ramps up to -- independent of
    // PluginConfig.getBuildHeight() and RiverConfig.getRiverBuildHeight(),
    // same reasoning as the river's own separate buildHeight knob: this can
    // be tuned to raise/lower the ocean without moving buildings, roads, or
    // the river. Own hardcoded default (63) matches the other two so
    // behaviour is unchanged until generator.ocean.buildHeight is set.
    private static int oceanBuildHeight = 63;

    // NOTE: only used by an ocean with NO rivers converging on it. When rivers
    // are attached the whole carve box uses the river's water level instead
    // (see OceanProvider.waterSurfaceY()).
    //
    // How many blocks below oceanBuildHeight (above) the water's surface
    // sits at the shoreline -- same role as RiverConfig.getRiverDepth().
    // OceanParcel.paintBeach() interpolates the beach's elevation from
    // waterY up to buildHeight across oceanBankMargin's whole horizontal
    // span, so THIS is what actually determines how steep that ramp
    // looks -- the old default of 1 meant the entire beach only ever
    // climbed a single block over that whole span, reading as flat no
    // matter how narrow oceanBankMargin was. 5 -- close to the river's
    // own riverDepth (6) -- gives the same span a real, visible slope.
    private static int oceanDepth = 5;

    // Overall SIZE of the ocean, in TILES (one tile = 64 chunks), measured
    // corner-to-corner across the open-water body before any edge noise is
    // applied -- i.e. this is a DIAMETER, not a radius. Deliberately a
    // double rather than an int: a whole-tile-multiple size reads as an
    // arbitrary, oddly-specific design constraint the ocean has no actual
    // need to respect, and the default itself (1.3) isn't a round number of
    // tiles either. Radius in chunks is (oceanSizeTiles * 64) / 2 --
    // see OceanProvider's constructor.
    private static double oceanSizeTiles = 1.3;

    // Chunks of blended sand/gravel/grass beyond the noise-perturbed
    // open-water edge before OceanParcel treats a column as ordinary dry
    // land -- same role as RiverConfig.getRiverBankMargin(). Matches the
    // river's own default (2) rather than being wider -- see oceanDepth
    // below for why the OLD default of 3 combined with oceanDepth=1 read
    // as a much wider, near-flat beach than the river's bank even before
    // accounting for this margin difference.
    private static int oceanBankMargin = 2;

    // How long, in BLOCKS measured inland from the waterline, the beach's
    // rise from the water level up to oceanBuildHeight is. Past this the land
    // is flat. 0 (the default) = automatic: the ramp finishes where the
    // reclaimed districts start (the reclaim berth, i.e. the same run the
    // river's bank has), which is what makes the ocean's edge as steep and
    // tight to the shore as the river's. Never longer than oceanBankMargin.
    // Raise it for a longer, gentler beach.
    private static int oceanBankRunBlocks = 0;

    // How far past the noise-perturbed edge (in CHUNKS) a chunk is still
    // guaranteed to matter for carving purposes -- see
    // OceanProvider.getEnvelopeRadius(). Kept separate from oceanBankMargin
    // (a material-blend distance) for the same reason RiverConfig keeps its
    // own envelope math independent of riverBankMargin: this only has to be
    // large enough that getEnvelopeRadius()'s bounding square can never miss
    // a genuine open-water or bank cell, not tuned for how the bank actually
    // looks.
    private static int oceanEnvelopeMargin = 2;

    // Furthest, in CHUNKS from world origin (0,0), the ocean's CENTER point
    // is allowed to land. This is THE placement range for the whole water
    // system: with convergeOnOcean on, both rivers are anchored on the
    // ocean's center, so they follow it; a river that isn't anchored on the
    // ocean (convergeOnOcean off / ocean disabled) draws its own baseline
    // from this same range. Configurable rather than a large fixed range
    // because on most seeds a huge one puts everything thousands of blocks
    // past where players or a WorldBorder pregen ever reach. Applied
    // independently on both the X and Z axis (the center is a 2D draw).
    private static int oceanMaxDistanceFromSpawn = 300;

    // Fraction (0..1) of the ocean's base radius that its noise-perturbed
    // edge is allowed to bulge out or pull in by, at any given angle around
    // the center -- what keeps the coastline from reading as a perfect
    // circle. 0.15 = the shoreline wanders +/-15% of the base radius.
    private static double oceanEdgeNoiseAmplitudeFraction = 0.15;

    // Cycles per full trip around the center (i.e. how many bulges/inlets
    // the coastline shows over its full circumference) for the noise that
    // drives oceanEdgeNoiseAmplitudeFraction above. Higher = a busier,
    // more crenellated coastline; lower = broad, gentle bulges.
    private static double oceanEdgeNoiseFrequency = 3.0;

    // Octaves for the base coastline noise above. A low octave count reads
    // as a smooth, near-regular many-lobed blob -- every bulge is roughly
    // the same size and shape, which is what makes a low-octave circle
    // look like a rounded polygon rather than a coastline. Raising this
    // layers progressively finer variation on top of the broad shape, so
    // bulges stop being uniform.
    private static int oceanEdgeNoiseOctaves = 4;

    // A SEPARATE, much higher-frequency noise field sampled directly in
    // world BLOCK space (not through the angle parametrization
    // edgeRadiusBlock() itself uses) and added straight onto the
    // shoreline distance test -- see OceanProvider.coastJitterBlock().
    // This is what actually breaks the shoreline out of reading as a
    // smooth, deterministic curve: an angle-only noise field, no matter
    // how many octaves, is still fundamentally a smooth function of ONE
    // variable swept around a circle, so it can only ever produce a
    // smooth curve -- any shape sampled that way looks vector-drawn up
    // close. Sampling directly in 2D world space instead gives genuine
    // per-block roughness -- small coves, jutting points, the ragged edge
    // real terrain-noise coastlines have -- independent of how far around
    // the circle a point is. 0 disables this layer entirely (pure smooth
    // curve, the old look).
    private static double oceanCoastJitterBlocks = 7.0;

    // Spatial frequency, in cycles per block, for oceanCoastJitterBlocks
    // above. Small values = broad, lazy local wiggles; larger values = a
    // tighter, choppier ragged edge. 0.02 -- roughly 1 cycle per ~50
    // blocks -- reads as beach-scale roughness rather than either a
    // smooth curve (too low) or visible per-block static (too high).
    private static double oceanCoastJitterFrequency = 0.02;

    // ---- the three depth zones -------------------------------------------
    // The sea floor is split into three concentric depth zones, measured by
    // distance in from the shoreline (the boundaries are wobbled by noise so
    // they read as banks and drop-offs, not perfect rings):
    //
    //   SHALLOW  a wide, gently graded shelf right off the coast, at
    //            oceanShallowDepth -- deep enough to float a speedboat,
    //            shallow enough to read as a shelf. Wide and smooth, with only
    //            a little seabed relief.
    //   MID      the ordinary open-sea depth, oceanMaxWaterColumnDepth (this is
    //            the depth the whole sea used to have). Full seabed relief.
    //   DEEP     the trench/abyssal middle at oceanDeepDepth, for the big
    //            deep-ocean schematics. Only reached well out from shore.
    //
    // Between zones the bed eases from one depth to the next over the
    // transition widths below.

    // Distance, in BLOCKS, from the shoreline over which the water column
    // ramps from a single shallow block up to oceanShallowDepth (the beach
    // run into the shelf).
    private static int oceanShoreRampBlocks = 16;

    // Water column depth of the SHALLOW shelf.
    private static int oceanShallowDepth = 8;

    // Water column depth of the MID zone -- the sea's original depth, and the
    // number OceanParcel tags each chunk's rough bed depth against.
    private static int oceanMaxWaterColumnDepth = 24;

    // Water column depth of the DEEP zone. The bed is clamped a few blocks
    // above the world floor, so a value too large for the water level just
    // bottoms out there.
    private static int oceanDeepDepth = 44;

    // Distance in from the shore, in blocks, where the shallow shelf ENDS and
    // the slope down to the MID depth begins.
    private static int oceanShallowEndBlocks = 80;

    // Width, in blocks, of the slope between the shallow shelf and MID.
    private static int oceanShallowToMidBlocks = 40;

    // Distance in from the shore, in blocks, where MID ends and the slope
    // down into the DEEP zone begins. Kept at least shallowEnd +
    // shallowToMid so the zones can never overlap.
    private static int oceanDeepStartBlocks = 240;

    // Width, in blocks, of the slope between MID and DEEP.
    private static int oceanMidToDeepBlocks = 60;

    // How far the zone boundaries wander, as a fraction of their distance
    // from shore (0.3 = each boundary can sit 30% nearer or further, varying
    // along the coast). 0 = perfectly parallel bands.
    private static double oceanZoneBoundaryNoiseFraction = 0.3;

    // How much of the seabed relief (oceanSeabedReliefFraction) is applied in
    // the shallow and deep zones. MID always gets the full relief. The shelf
    // is kept smooth so it stays clean for boats; the deep zone is calmer so
    // big builds sit on a predictable floor.
    private static double oceanShallowReliefScale = 0.2;
    private static double oceanDeepReliefScale = 0.4;

    // ---- shape of the body itself (see OceanProvider's class javadoc) ----
    // Longest the ocean is allowed to be stretched relative to how wide it
    // is: 1.0 = no stretch (a round body); 2.0 = up to 2:1 long-to-short.
    // Each world's actual ratio is drawn from the seed, between HALF of this
    // extra elongation and all of it, so it never comes out accidentally
    // round; the long axis points in a seed-drawn direction. The stretch is
    // area-preserving, so oceanSizeTiles still means roughly the same
    // amount of water -- it just becomes a long sea instead of a wide one.
    private static double oceanStretch = 2.0;
    // How far the whole body is BENT, as a fraction of the base radius: the
    // world is warped by low-frequency noise before the ellipse is
    // evaluated, so the sea curves and kinks (kidney, crescent) instead of
    // staying a symmetric oval. 0 = no bending.
    private static double oceanWarpFraction = 0.25;
    // How far bays and headlands push the coast in and out, as a fraction of
    // the base radius. Unlike edgeNoiseAmplitudeFraction (which can only
    // move the coast along a bearing from the center), this can make real
    // concave bays and peninsulas. 0 disables them.
    private static double oceanBayFraction = 0.26;
    // Longest an inlet (a thin winding channel of water) can reach inland,
    // as a fraction of the base radius. 0 = no inlets.
    private static double oceanInletFraction = 0.14;
    // How much land can rise out of the water near the coast as islands, as a
    // fraction of the base radius: bigger = more and larger islands, reaching
    // further out from shore. They only form in the shallower belt near the
    // coast, never in the deep middle. 0 = no islands.
    private static double oceanIslandFraction = 0.42;
    // How uneven the sea floor is, as a fraction of the water depth: the bed
    // in open water is shifted up and down by up to this much by layers of
    // coherent noise (broad basins and rises, banks and hollows, ripples).
    // It fades to nothing at the shoreline so the shallows stay a smooth
    // slope. 0 = the old perfectly flat bed.
    private static double oceanSeabedReliefFraction = 0.7;
    // R30ea seabed controls restored from the original compiled API.
    private static boolean oceanSeabedDetailsEnabled = true;
    private static int oceanSeabedPatchSizeBlocks = 44;
    private static int oceanSeabedRockPercent = 18;
    private static int oceanSeabedSeagrassPercent = 34;
    private static int oceanSeabedKelpPercent = 28;
    private static double oceanSeabedRippleBlocks = 2.2;
    private static double oceanSeabedMidVariationBlocks = 2.0;
    private static double oceanSeabedBedformScaleBlocks = 110.0;
    private static int oceanMinimumNavigableDepthBlocks = 7;


    // ---- where a river runs into the ocean (see OceanProvider.sample()) ----
    // How much wider a river's channel gets right at its mouth, as a multiple
    // of its normal width -- the river flares open into a funnel as it meets
    // the sea instead of arriving as a pipe of constant width. 1.0 = no flare.
    private static double oceanMouthFlare = 1.8;
    // How far upstream of the shoreline, in blocks, the river and the ocean
    // are still being blended into each other: over this distance the mouth
    // flare fades back out to the river's normal width. Bigger = a longer,
    // gentler funnel. (The water level and riverbed are NOT blended: with
    // rivers attached the sea sits at the river's water level, and the
    // channel keeps its full depth out to the sea floor.)
    private static double oceanMouthZoneBlocks = 80.0;
    // How far, in blocks, BEFORE the interior edge of an ocean tile (the
    // highway ring) where a river is handed over to the ordinary river
    // pipeline, the mouth flare is faded to nothing, so the channel arrives at
    // the highway at exactly the river's own width. Only edges facing a
    // non-ocean tile count. 0 = no fade.
    private static double oceanRiverHandoffBlocks = 48.0;
    // Radius, in blocks, of the rounding where a river's channel meets the
    // shoreline: the sharp inside corner between river bank and coast is
    // filled in smoothly instead of forming a hard notch. 0 = sharp corners.
    private static double oceanMouthFilletBlocks = 28.0;

    // Ocean counterpart of RiverConfig's riverTrees*/riverVegetation* --
    // same two independently toggleable passes (OceanParcel's BeachSite,
    // planted by ForestGenerator.populateShore()), off by default, and the
    // same forest-backed planting: the forest's own trees, ground cover and
    // biome tint, scaled by the percentages (100 = as dense as the forest).
    // Trees only root past the wet sand/gravel band and ground cover only
    // lands on columns whose final top block is GRASS, never on open water.
    private static boolean oceanTreesEnabled = false;
    private static int oceanTreesChancePercent = 100;
    private static boolean oceanVegetationEnabled = false;
    private static int oceanVegetationChancePercent = 100;

    // Depth-gated boats/ships (see OceanBoatPlacer) -- off by default, same
    // reasoning as the two vegetation toggles above: the feature can exist
    // in the code without every existing ocean silently sprouting boats the
    // moment this jar is dropped in.
    private static boolean oceanBoatsEnabled = false;
    // Per-chunk density roll inside populateOceanBoats() -- separate from
    // isOceanBoatsEnabled() (the on/off switch) the same way
    // oceanTreesChancePercent is separate from vegetation being enabled at
    // all: this tunes how SPARSE boats are once the feature is on.
    private static int oceanBoatsChancePercent = 5;
    // Which boats may sit where is decided by the water's actual depth (see
    // OceanBoatPlacer.depthAllows()): shallow boats over the shallow shelf, mid
    // boats over mid-depth water, deep boats only where the whole hull is in
    // the DEEP zone. Every boat must also clear its own draft.

    // Clone radius for BIG ocean builds (see below), in chunks -- same meaning
    // as generator.cloneRadius (a hard "no exact duplicate within this many
    // chunks") but much larger, because a huge ship or rig repeated a few
    // hundred blocks away is far more obvious than a repeated cottage.
    private static int oceanBoatsBigCloneRadius = 96;
    // Soft ring beyond the big clone radius, same meaning as
    // generator.cloneRadiusSoftLimit: a repeat is allowed with rising odds
    // out to this distance. <= bigCloneRadius disables the soft ring.
    private static int oceanBoatsBigCloneRadiusSoftLimit = 160;
    // A build counts as BIG when its footprint spans at least this many
    // chunks on either axis, or when it is a DEEP-tier build. A schematic's
    // own oceanCloneRadius / oceanCloneRadiusSoftLimit CONFIG entries win over
    // this. 0 disables the size rule (only the deep tier and per-schematic
    // overrides then count).
    private static int oceanBoatsBigMinChunks = 3;
    // Minimum open gap (blocks) kept between any two boats' hulls.
    private static int oceanBoatsSpacingBlocks = 64;

    public static boolean isOceanEnabled() {
        return oceanEnabled;
    }

    public static void setOceanEnabled(boolean value) {
        oceanEnabled = value;
    }

    public static int getOceanBuildHeight() {
        return oceanBuildHeight;
    }

    public static void setOceanBuildHeight(int value) {
        oceanBuildHeight = value;
    }

    public static int getOceanDepth() {
        return oceanDepth;
    }

    public static void setOceanDepth(int value) {
        oceanDepth = value;
    }

    public static double getOceanSizeTiles() {
        return oceanSizeTiles;
    }

    public static void setOceanSizeTiles(double value) {
        oceanSizeTiles = value;
    }

    public static int getOceanBankMargin() {
        return oceanBankMargin;
    }

    public static void setOceanBankMargin(int value) {
        oceanBankMargin = value;
    }

    public static int getOceanBankRunBlocks() {
        return oceanBankRunBlocks;
    }

    public static void setOceanBankRunBlocks(int value) {
        oceanBankRunBlocks = value;
    }

    public static int getOceanEnvelopeMargin() {
        return oceanEnvelopeMargin;
    }

    public static void setOceanEnvelopeMargin(int value) {
        oceanEnvelopeMargin = value;
    }

    public static int getOceanMaxDistanceFromSpawn() {
        return oceanMaxDistanceFromSpawn;
    }

    public static void setOceanMaxDistanceFromSpawn(int value) {
        oceanMaxDistanceFromSpawn = value;
    }

    public static double getOceanEdgeNoiseAmplitudeFraction() {
        return oceanEdgeNoiseAmplitudeFraction;
    }

    public static void setOceanEdgeNoiseAmplitudeFraction(double value) {
        oceanEdgeNoiseAmplitudeFraction = value;
    }

    public static double getOceanEdgeNoiseFrequency() {
        return oceanEdgeNoiseFrequency;
    }

    public static void setOceanEdgeNoiseFrequency(double value) {
        oceanEdgeNoiseFrequency = value;
    }

    public static int getOceanEdgeNoiseOctaves() {
        return oceanEdgeNoiseOctaves;
    }

    public static void setOceanEdgeNoiseOctaves(int value) {
        oceanEdgeNoiseOctaves = value;
    }

    public static double getOceanCoastJitterBlocks() {
        return oceanCoastJitterBlocks;
    }

    public static void setOceanCoastJitterBlocks(double value) {
        oceanCoastJitterBlocks = value;
    }

    public static double getOceanCoastJitterFrequency() {
        return oceanCoastJitterFrequency;
    }

    public static void setOceanCoastJitterFrequency(double value) {
        oceanCoastJitterFrequency = value;
    }

    public static int getOceanShoreRampBlocks() {
        return oceanShoreRampBlocks;
    }

    public static void setOceanShoreRampBlocks(int value) {
        oceanShoreRampBlocks = value;
    }

    public static int getOceanMaxWaterColumnDepth() {
        return oceanMaxWaterColumnDepth;
    }

    public static void setOceanMaxWaterColumnDepth(int value) {
        oceanMaxWaterColumnDepth = value;
    }

    public static double getOceanStretch() {
        return oceanStretch;
    }

    public static void setOceanStretch(double value) {
        oceanStretch = value;
    }

    public static double getOceanWarpFraction() {
        return oceanWarpFraction;
    }

    public static void setOceanWarpFraction(double value) {
        oceanWarpFraction = value;
    }

    public static double getOceanBayFraction() {
        return oceanBayFraction;
    }

    public static void setOceanBayFraction(double value) {
        oceanBayFraction = value;
    }

    public static double getOceanInletFraction() {
        return oceanInletFraction;
    }

    public static void setOceanInletFraction(double value) {
        oceanInletFraction = value;
    }

    public static double getOceanIslandFraction() {
        return oceanIslandFraction;
    }

    public static void setOceanIslandFraction(double value) {
        oceanIslandFraction = value;
    }

    public static boolean isOceanSeabedDetailsEnabled() { return oceanSeabedDetailsEnabled; }
    public static void setOceanSeabedDetailsEnabled(boolean enabled) { oceanSeabedDetailsEnabled = enabled; }
    public static int getOceanSeabedPatchSizeBlocks() { return oceanSeabedPatchSizeBlocks; }
    public static void setOceanSeabedPatchSizeBlocks(int value) {
        oceanSeabedPatchSizeBlocks = Math.max(12, Math.min(512, value));
    }
    public static int getOceanSeabedRockPercent() { return oceanSeabedRockPercent; }
    public static void setOceanSeabedRockPercent(int value) {
        oceanSeabedRockPercent = Math.max(0, Math.min(100, value));
    }
    public static int getOceanSeabedSeagrassPercent() { return oceanSeabedSeagrassPercent; }
    public static void setOceanSeabedSeagrassPercent(int value) {
        oceanSeabedSeagrassPercent = Math.max(0, Math.min(100, value));
    }
    public static int getOceanSeabedKelpPercent() { return oceanSeabedKelpPercent; }
    public static void setOceanSeabedKelpPercent(int value) {
        oceanSeabedKelpPercent = Math.max(0, Math.min(100, value));
    }
    public static double getOceanSeabedRippleBlocks() { return oceanSeabedRippleBlocks; }
    public static void setOceanSeabedRippleBlocks(double value) {
        oceanSeabedRippleBlocks = Math.max(0.0, Math.min(5.0, value));
    }
    public static double getOceanSeabedMidVariationBlocks() { return oceanSeabedMidVariationBlocks; }
    public static void setOceanSeabedMidVariationBlocks(double value) {
        oceanSeabedMidVariationBlocks = Math.max(0.0, Math.min(6.0, value));
    }
    public static double getOceanSeabedBedformScaleBlocks() { return oceanSeabedBedformScaleBlocks; }
    public static void setOceanSeabedBedformScaleBlocks(double value) {
        oceanSeabedBedformScaleBlocks = Math.max(24.0, Math.min(512.0, value));
    }
    public static int getOceanMinimumNavigableDepthBlocks() { return oceanMinimumNavigableDepthBlocks; }
    public static void setOceanMinimumNavigableDepthBlocks(int value) {
        oceanMinimumNavigableDepthBlocks = Math.max(1, Math.min(24, value));
    }

    public static double getOceanSeabedReliefFraction() {
        return oceanSeabedReliefFraction;
    }

    public static void setOceanSeabedReliefFraction(double value) {
        oceanSeabedReliefFraction = value;
    }

    public static double getOceanMouthFlare() {
        return oceanMouthFlare;
    }

    public static void setOceanMouthFlare(double value) {
        oceanMouthFlare = value;
    }

    public static double getOceanMouthZoneBlocks() {
        return oceanMouthZoneBlocks;
    }

    public static void setOceanMouthZoneBlocks(double value) {
        oceanMouthZoneBlocks = value;
    }

    public static double getOceanRiverHandoffBlocks() {
        return oceanRiverHandoffBlocks;
    }

    public static void setOceanRiverHandoffBlocks(double value) {
        oceanRiverHandoffBlocks = value;
    }

    public static double getOceanMouthFilletBlocks() {
        return oceanMouthFilletBlocks;
    }

    public static void setOceanMouthFilletBlocks(double value) {
        oceanMouthFilletBlocks = value;
    }

    public static boolean isOceanTreesEnabled() {
        return oceanTreesEnabled;
    }

    public static void setOceanTreesEnabled(boolean value) {
        oceanTreesEnabled = value;
    }

    public static int getOceanTreesChancePercent() {
        return oceanTreesChancePercent;
    }

    public static void setOceanTreesChancePercent(int value) {
        oceanTreesChancePercent = Math.max(0, Math.min(100, value));
    }

    public static boolean isOceanVegetationEnabled() {
        return oceanVegetationEnabled;
    }

    public static void setOceanVegetationEnabled(boolean value) {
        oceanVegetationEnabled = value;
    }

    public static int getOceanVegetationChancePercent() {
        return oceanVegetationChancePercent;
    }

    public static void setOceanVegetationChancePercent(int value) {
        oceanVegetationChancePercent = Math.max(0, Math.min(100, value));
    }

    public static boolean isOceanBoatsEnabled() {
        return oceanBoatsEnabled;
    }

    public static void setOceanBoatsEnabled(boolean value) {
        oceanBoatsEnabled = value;
    }

    public static int getOceanBoatsChancePercent() {
        return oceanBoatsChancePercent;
    }

    public static void setOceanBoatsChancePercent(int value) {
        oceanBoatsChancePercent = Math.max(0, Math.min(100, value));
    }

    public static int getOceanBoatsSpacingBlocks() {
        return oceanBoatsSpacingBlocks;
    }

    public static void setOceanBoatsSpacingBlocks(int value) {
        oceanBoatsSpacingBlocks = Math.max(0, value);
    }

    public static int getOceanShallowDepth() {
        return oceanShallowDepth;
    }

    public static void setOceanShallowDepth(int value) {
        oceanShallowDepth = Math.max(1, value);
    }

    public static int getOceanDeepDepth() {
        return oceanDeepDepth;
    }

    public static void setOceanDeepDepth(int value) {
        oceanDeepDepth = Math.max(1, value);
    }

    public static int getOceanShallowEndBlocks() {
        return oceanShallowEndBlocks;
    }

    public static void setOceanShallowEndBlocks(int value) {
        oceanShallowEndBlocks = Math.max(1, value);
    }

    public static int getOceanShallowToMidBlocks() {
        return oceanShallowToMidBlocks;
    }

    public static void setOceanShallowToMidBlocks(int value) {
        oceanShallowToMidBlocks = Math.max(1, value);
    }

    public static int getOceanDeepStartBlocks() {
        return oceanDeepStartBlocks;
    }

    public static void setOceanDeepStartBlocks(int value) {
        oceanDeepStartBlocks = Math.max(1, value);
    }

    public static int getOceanMidToDeepBlocks() {
        return oceanMidToDeepBlocks;
    }

    public static void setOceanMidToDeepBlocks(int value) {
        oceanMidToDeepBlocks = Math.max(1, value);
    }

    public static double getOceanZoneBoundaryNoiseFraction() {
        return oceanZoneBoundaryNoiseFraction;
    }

    public static void setOceanZoneBoundaryNoiseFraction(double value) {
        oceanZoneBoundaryNoiseFraction = Math.max(0.0, Math.min(0.8, value));
    }

    public static double getOceanShallowReliefScale() {
        return oceanShallowReliefScale;
    }

    public static void setOceanShallowReliefScale(double value) {
        oceanShallowReliefScale = Math.max(0.0, value);
    }

    public static double getOceanDeepReliefScale() {
        return oceanDeepReliefScale;
    }

    public static void setOceanDeepReliefScale(double value) {
        oceanDeepReliefScale = Math.max(0.0, value);
    }

    /** Deepest a SHORE/shallow tier column can be: halfway between the shallow and mid depths. */
    public static int getShallowTierMaxDepth() {
        return (int) Math.round((oceanShallowDepth + oceanMaxWaterColumnDepth) / 2.0);
    }

    /** Shallowest a DEEP tier column can be: halfway between the mid and deep depths. */
    public static int getDeepTierMinDepth() {
        return (int) Math.round((oceanMaxWaterColumnDepth + oceanDeepDepth) / 2.0) + 1;
    }

    public static int getOceanBoatsBigCloneRadius() {
        return oceanBoatsBigCloneRadius;
    }

    public static void setOceanBoatsBigCloneRadius(int value) {
        oceanBoatsBigCloneRadius = Math.max(0, value);
    }

    public static int getOceanBoatsBigCloneRadiusSoftLimit() {
        return oceanBoatsBigCloneRadiusSoftLimit;
    }

    public static void setOceanBoatsBigCloneRadiusSoftLimit(int value) {
        oceanBoatsBigCloneRadiusSoftLimit = Math.max(0, value);
    }

    public static int getOceanBoatsBigMinChunks() {
        return oceanBoatsBigMinChunks;
    }

    public static void setOceanBoatsBigMinChunks(int value) {
        oceanBoatsBigMinChunks = Math.max(0, value);
    }
}