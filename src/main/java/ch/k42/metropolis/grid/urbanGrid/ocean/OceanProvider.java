package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Random;

/**
 * Computes ocean geometry as a pure function of world-BLOCK coordinates and
 * the world seed -- same determinism guarantee as RiverProvider, and for
 * the same reason: two different UrbanGrid tiles evaluating this at the
 * same world coordinates always get the same answer, which is what lets
 * the ocean cross a tile boundary correctly without any tile needing to
 * look at its neighbour.
 *
 * Unlike the river (an unbounded meandering LINE), the ocean is a single
 * BODY placed at one seed-derived center point. Its shape used to be a
 * circle whose radius was perturbed by angle-based noise -- which can only
 * ever produce a "star-shaped" blob (every point of the coast is visible
 * from the center), with no inlets, no islands, and every bulge about the
 * same size. It is now built as a single scalar FIELD over the world, F(x,z),
 * in blocks: F > 0 is open water, F = 0 is the shoreline, F < 0 is land.
 * That field is assembled from several layers, coarse to fine:
 *
 *   1. STRETCH: the base circle becomes an ellipse (area-preserving, so
 *      "sizeTiles" still means roughly the same amount of water) with a
 *      seed-derived orientation and elongation -- a long sea, not a round
 *      one.
 *   2. DOMAIN WARP: the world is bent by low-frequency noise before the
 *      ellipse is evaluated, so the whole body curves and kinks like a
 *      kidney or a bay instead of staying a symmetric oval.
 *   3. BAYS AND HEADLANDS: broad noise added straight onto the field, sampled
 *      in world space (not angle), which pushes the coast in and out by up
 *      to a fifth of the radius. Because it is not tied to a bearing from the
 *      center it can make genuine concave bays and peninsulas.
 *   4. DETAIL: a finer octave of the same idea for coves and points, plus the
 *      original per-block jitter for beach-scale roughness.
 *   5. INLETS: thin, winding channels of water that reach a limited distance
 *      inland from the coast (a "ridged" noise -- a narrow crest along the
 *      noise's zero line -- added to the field, so it only matters close to
 *      the shore).
 *   6. ISLANDS: patches subtracted from the field, so land rises out of the
 *      shallower water near the coast. They only form in the outer belt, so
 *      the deep middle stays open sea.
 *   7. A HARD CAP: the field is smooth-min'd with an ellipse that water can
 *      never cross. Every layer above is bounded, but adding worst cases
 *      together gives a needlessly huge box; the cap makes the carving
 *      envelope (getEnvelopeRadiusChunks()) provably correct at a sane size
 *      -- the noise can never place water outside it, so nothing is ever
 *      clipped by the box.
 *
 * RIVERS. When rivers are attached (attachRivers()), the ocean also owns the
 * space of the river channels that cross its tiles: everything inside an
 * ocean tile (see TILE OWNERSHIP below -- whole tiles, not the square carve
 * box) is painted from one merged water field, the ocean's own coast
 * united with the channel of an X-bound river and a Z-bound river that are
 * both anchored on the ocean's center (see RiverProvider). The sea sits at
 * the river's own water level (no separate ocean level), where a channel
 * meets the coast it flares open and the inside corner between bank and
 * coast is rounded off, and the channel's bed stays at full river depth out
 * through the shelf until the sea floor is deeper than it. See sample() for the details, and the
 * OceanConfig.getOceanMouth*() knobs. The merge is kept short and is faded out
 * before the tile's highway ring (OceanConfig.getOceanRiverHandoffBlocks()):
 * outside an ocean tile the rivers are the ordinary river pipeline's business
 * and this class is not consulted.
 *
 * "Distance from shore" is then F divided by the local slope of F (the
 * standard first-order signed-distance estimate), so beach width and shelf
 * length come out in real blocks even where the coast is steep or shallow.
 * The slope is only measured near the coast; in deep water, and far inland,
 * nothing reads the distance precisely.
 */
public class OceanProvider {

    /** Bounds on the local field slope used to turn F into a distance -- keeps the estimate sane where the field is nearly flat or very steep. */
    private static final double GRAD_MIN = 0.6;
    private static final double GRAD_MAX = 1.8;
    /** Half-step, in blocks, for the finite-difference slope. */
    private static final double GRAD_STEP = 6.0;
    /** Deeper than this inside the coast the raw field is used as the distance (blended in over the next stretch): no beach or shelf math needs a precise number that far out. */
    private static final double DEEP_BLEND_START = 60.0;
    private static final double DEEP_BLEND_END = 120.0;
    /**
     * Outside the coast the slope-corrected distance is used as far as the river mouth zone reaches (see preciseBandOutsideBlocks()),
     * then eased into the raw field over this many field units. The old code simply SWITCHED from the corrected distance to the raw
     * field a little beyond the beach. On a stretched coast the two differ by up to ~40 blocks, so the distance jumped by that much
     * along a line parallel to the shore, right inside the mouth zone -- the river's flare (which is driven by this distance) snapped
     * open there instead of widening smoothly into a funnel.
     */
    private static final double OUTER_BLEND_BLOCKS = 64.0;
    /** How far past the (edge-noise-modulated) ellipse water may ever reach, as a fraction of the base radius -- see the HARD CAP note above. */
    private static final double CAP_EXTRA = 0.0;

    // Feature sizes, as fractions of the base radius.
    private static final double WARP_WAVELENGTH = 1.4;
    private static final double BAY_WAVELENGTH = 0.8;
    private static final double DETAIL_WAVELENGTH = 0.18;
    private static final double INLET_WAVELENGTH = 0.9;
    private static final double ISLAND_WAVELENGTH = 0.7;
    private static final double BED_WAVELENGTH_BROAD = 0.4;
    private static final double BED_WAVELENGTH_MID = 0.11;
    private static final double BED_WAVELENGTH_FINE = 0.035;

    private final boolean enabled;
    private final double centerBlockX;
    private final double centerBlockZ;
    private final double baseRadiusBlock;
    private final SimplexOctaveGenerator edgeNoise;

    // Separate, much-higher-frequency noise field sampled directly in
    // world block space rather than through the angle parametrization
    // edgeRadiusBlock() uses -- see OceanConfig.getOceanCoastJitterBlocks()'s
    // own javadoc for why this is what actually breaks the coastline out
    // of reading as a smooth vector curve. Salted independently from
    // edgeNoise so the two noise fields are uncorrelated.
    private final SimplexOctaveGenerator coastJitter;

    // One independently-salted generator per shape layer, so no two layers
    // ever share a noise stream (which would correlate, say, where the bays
    // are with where the islands are).
    private final SimplexOctaveGenerator warpNoise;
    private final SimplexOctaveGenerator bayNoise;
    private final SimplexOctaveGenerator detailNoise;
    private final SimplexOctaveGenerator inletNoise;
    private final SimplexOctaveGenerator islandNoise;
    private final SimplexOctaveGenerator bedNoise;

    // Set once by attachRivers(); null until then (and forever, if rivers are
    // not converging on the ocean), in which case everything below behaves as
    // a plain ocean with no river channels in it.
    private volatile RiverProvider xRiver;
    private volatile RiverProvider zRiver;
    // getEnvelopeRadiusChunks() is queried per block column (tile ownership tests), so it is computed once.
    private volatile double envelopeCacheX = Double.NaN;
    private volatile double envelopeCacheZ = Double.NaN;

    // Orientation and elongation of the ellipse, drawn once from the seed.
    private final double cosTheta;
    private final double sinTheta;
    private final double stretch;
    private final double semiMajorBlock;
    private final double semiMinorBlock;
    private final long worldSeed;
    // With the world plan there is no tile to own the ocean: the river/ocean merge is a function of the point alone. It is
    // already smooth by itself (oceanWeight() tapers it to nothing over the mouth zone outside the coast, and the mouth flare
    // scales with that weight), so the tile gates -- inOceanDomain() and the tile-edge hand-off fade -- only ever cut it short.
    private final boolean tileFree;

    public OceanProvider(long worldSeed) {
        this.worldSeed = worldSeed;
        this.tileFree = PluginConfig.isHighwayNetworkEnabled();
        this.enabled = OceanConfig.isOceanEnabled();
        // Salted away from worldSeed (and from RiverProvider's own salts --
        // see its constructor) so this never shares an identical noise
        // stream or draw sequence with anything else seeded off worldSeed
        // elsewhere in generation.
        this.edgeNoise = new SimplexOctaveGenerator(worldSeed ^ 0x4F4345414E5F4544L, Math.max(1, OceanConfig.getOceanEdgeNoiseOctaves()));
        this.edgeNoise.setScale(1.0);
        this.coastJitter = new SimplexOctaveGenerator(worldSeed ^ 0x4F4345414E5F4A49L, 4);
        this.coastJitter.setScale(1.0);
        this.warpNoise = layer(worldSeed ^ 0x4F4345414E5F5750L, 2);
        this.bayNoise = layer(worldSeed ^ 0x4F4345414E5F4259L, 2);
        this.detailNoise = layer(worldSeed ^ 0x4F4345414E5F4454L, 2);
        this.inletNoise = layer(worldSeed ^ 0x4F4345414E5F494EL, 1);
        this.islandNoise = layer(worldSeed ^ 0x4F4345414E5F4953L, 1);
        this.bedNoise = layer(worldSeed ^ 0x4F4345414E5F4244L, 2);

        int range = Math.max(1, OceanConfig.getOceanMaxDistanceFromSpawn());
        Random rand = new Random(worldSeed ^ 0x4F4345414E5F4C4FL);
        int centerChunkX = rand.nextInt(range * 2) - range;
        int centerChunkZ = rand.nextInt(range * 2) - range;
        this.centerBlockX = centerChunkX * 16.0 + 8.0;
        this.centerBlockZ = centerChunkZ * 16.0 + 8.0;

        // oceanSizeTiles is a DIAMETER in tile units (1 tile = 64 chunks =
        // 1024 blocks) -- see OceanConfig's own javadoc on that field.
        double diameterBlock = Math.max(16.0, OceanConfig.getOceanSizeTiles() * 64.0 * 16.0);
        this.baseRadiusBlock = diameterBlock / 2.0;

        // Shape parameters come from their OWN draw, not `rand` above, so the
        // center (and therefore where in the world the ocean is) is exactly
        // what it was before the ocean got a shape.
        Random shape = new Random(worldSeed ^ 0x4F4345414E5F5348L);
        double theta = shape.nextDouble() * Math.PI;
        this.cosTheta = Math.cos(theta);
        this.sinTheta = Math.sin(theta);
        double maxStretch = Math.max(1.0, OceanConfig.getOceanStretch());
        // At least half of the configured elongation, so it is never accidentally round.
        this.stretch = 1.0 + (maxStretch - 1.0) * (0.5 + 0.5 * shape.nextDouble());
        this.semiMajorBlock = baseRadiusBlock * Math.sqrt(this.stretch);
        this.semiMinorBlock = baseRadiusBlock / Math.sqrt(this.stretch);
    }

    private static SimplexOctaveGenerator layer(long seed, int octaves) {
        SimplexOctaveGenerator gen = new SimplexOctaveGenerator(seed, octaves);
        gen.setScale(1.0);
        return gen;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Gives the ocean the two rivers that converge on it (either may be
     * null). Called once, right after construction, by UrbanGrid -- the
     * rivers are anchored on this ocean's center, so they can only be built
     * once the ocean exists. After this, distanceInsideShoreBlock() and
     * sample() include the river channels inside the ocean's carve box.
     */
    public long getWorldSeed() {
        return this.worldSeed;
    }

    public void attachRivers(RiverProvider xBound, RiverProvider zBound) {
        this.xRiver = xBound;
        this.zRiver = zBound;
    }

    /** True if at least one river is attached and enabled. */
    public boolean hasRivers() {
        RiverProvider x = this.xRiver;
        RiverProvider z = this.zRiver;
        return (x != null && x.isEnabled()) || (z != null && z.isEnabled());
    }

    /** What is at one block column of the merged water field -- see sample(). */
    public static final class Sample {
        /** Signed distance inside the nearest water's shore, in blocks: positive = water (the ocean or a river channel), zero or negative = land. */
        public final double distanceInside;
        /** Y of the top water block here -- the same value everywhere (see waterSurfaceY()). Meaningful for water, and for the bank/beach beside it (the ramp starts from this level). */
        public final int surfaceY;
        /** Water column depth, surface down to the bed, in blocks; 0 if this column is not water. */
        public final int columnDepth;

        Sample(double distanceInside, int surfaceY, int columnDepth) {
            this.distanceInside = distanceInside;
            this.surfaceY = surfaceY;
            this.columnDepth = columnDepth;
        }
    }

    /** Center X, in whole chunks (rounds toward the chunk containing the seed-drawn center block). */
    public int getCenterChunkX() {
        return (int) Math.floor(centerBlockX / 16.0);
    }

    /** Center Z, in whole chunks -- see getCenterChunkX(). */
    public int getCenterChunkZ() {
        return (int) Math.floor(centerBlockZ / 16.0);
    }

    /**
     * Center Z at full block precision -- unlike getCenterChunkZ(), not
     * rounded to a chunk. OceanWaterEdgeProvider uses it as the middle of the
     * range it scans across each column.
     */
    public double getCenterBlockZ() {
        return centerBlockZ;
    }

    /**
     * One-line, human-readable summary of where this world's ocean is --
     * for startup/debug logging, so the coordinates can be read straight off
     * the console instead of being hunted for in an ungenerated world (the
     * ocean's parcels don't exist until a tile touching it is generated, but
     * its placement is a pure function of the seed, so it's known up front).
     * "Carved box" is the square UrbanGrid unconditionally carves, i.e.
     * everything that can turn into water or bank.
     */
    public String describe() {
        long cx = Math.round(centerBlockX);
        long cz = Math.round(centerBlockZ);
        // Per-axis, not a single square box -- an elongated or rotated
        // ocean has a genuinely different reach in X than in Z (see
        // getEnvelopeRadiusChunksX()/Z()'s own javadoc), and printing a
        // square here was itself part of what made a stretched ocean's
        // real, much-larger-than-expected carve box hard to notice.
        long boxX = (long) Math.ceil(getEnvelopeRadiusChunksX() * 16.0);
        long boxZ = (long) Math.ceil(getEnvelopeRadiusChunksZ() * 16.0);
        double bearing = Math.toDegrees(Math.atan2(sinTheta, cosTheta));
        return String.format("center X=%d Z=%d (chunk %d, %d), body ~%d x %d blocks (%.2f:1 stretch, long axis %.0f deg), coast wanders +/-%d local jitter, carved box X %d..%d Z %d..%d" + (hasRivers() ? ", rivers attached" : ""),
                cx, cz, getCenterChunkX(), getCenterChunkZ(),
                Math.round(semiMajorBlock * 2.0), Math.round(semiMinorBlock * 2.0), stretch, bearing,
                Math.round(Math.abs(OceanConfig.getOceanCoastJitterBlocks())),
                cx - boxX, cx + boxX, cz - boxZ, cz + boxZ);
    }

    /**
     * Roots, as "(x,z)", of the 64x64-chunk generator tiles whose interior
     * (root+1 .. root+62, same range UrbanGrid logs as chunkRangeX/Z) the
     * ocean's carving box reaches -- the exact test
     * UrbanGrid.placeOceanIfPresent() applies per tile. Those are the only
     * tiles that will ever log "Ocean carved into tile ...", so this is
     * what to look for in the console once generation reaches them.
     * Empty string if the ocean is disabled.
     */
    public String describeTilesReached() {
        if (!enabled) {
            return "";
        }
        // Per-axis now, matching tileReached() -- see
        // getEnvelopeRadiusChunksX()/Z()'s javadoc for why this is no
        // longer a single square radius. The envelope is measured from the
        // exact seed-derived center in chunk coordinates. Do not recenter
        // it on getCenterChunkX/Z(): those methods intentionally return the
        // containing chunk. The current constructor places the ocean
        // center at +8 blocks in that chunk, so using the integer chunk
        // coordinate shifts the envelope by 0.5 chunk and can make an edge
        // tile look out of range when it is actually touched by the carve
        // envelope.
        double envX = getEnvelopeRadiusChunksX();
        double envZ = getEnvelopeRadiusChunksZ();
        double cx = centerBlockX / 16.0;
        double cz = centerBlockZ / 16.0;
        int rootMinX = Math.floorDiv((int) Math.floor(cx - envX), 64) * 64;
        int rootMaxX = (int) Math.ceil(cx + envX);
        int rootMinZ = Math.floorDiv((int) Math.floor(cz - envZ), 64) * 64;
        int rootMaxZ = (int) Math.ceil(cz + envZ);
        StringBuilder sb = new StringBuilder();
        for (int rx = rootMinX; rx <= rootMaxX; rx += 64) {
            for (int rz = rootMinZ; rz <= rootMaxZ; rz += 64) {
                boolean missed = cx + envX < rx + 1 || cx - envX > rx + 62
                        || cz + envZ < rz + 1 || cz - envZ > rz + 62;
                if (!missed) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append('(').append(rx).append(',').append(rz).append(')');
                }
            }
        }
        return sb.toString();
    }

    /** Normalised radius (in ellipse units) past which the field is forced to land -- the hard cap. */
    private double capNormalizedRadius() {
        return 1.0 + Math.abs(OceanConfig.getOceanEdgeNoiseAmplitudeFraction()) + CAP_EXTRA;
    }

    /**
     * Half-width, in CHUNKS, of the square bounding box guaranteed to
     * contain every open-water and bank cell -- the ocean's counterpart to
     * RiverProvider.getEnvelopeHalfWidth(). UrbanGrid carves every cell
     * inside this box unconditionally (see its own javadoc for why: exactly
     * the same "let the per-block painter decide, don't pre-filter at chunk
     * granularity" reasoning RiverParcel's carveColumn() already
     * established).
     *
     * Provable rather than estimated: water only exists where the field is
     * positive, the hard cap forces the field to zero at normalised radius
     * capNormalizedRadius(), and the domain warp moves any point by at most
     * warpBlocks per axis. So water lies inside the cap ellipse grown by the
     * worst-case warp, and the beach reaches at most bankMargin blocks
     * further, measured in the worst-case (GRAD_MAX) field slope and
     * stretched along the long axis.
     */
    /**
     * Half-width, in CHUNKS, of the square bounding box guaranteed to
     * contain every open-water and bank cell -- the ocean's counterpart to
     * RiverProvider.getEnvelopeHalfWidth(). The larger of
     * getEnvelopeRadiusChunksX()/Z(): a single, conservative scalar for
     * callers that only ever wanted one worst-case radius (describe()'s
     * printed box, OceanWaterEdgeProvider) and were never doing a per-axis
     * tile-ownership test in the first place. tileReached() itself no
     * longer uses this -- see getEnvelopeRadiusChunksX()/Z()'s own javadoc
     * for why a single scalar there was actively wrong for anything but a
     * near-circular ocean.
     */
    public double getEnvelopeRadiusChunks() {
        return Math.max(getEnvelopeRadiusChunksX(), getEnvelopeRadiusChunksZ());
    }

    /** X half-width, in chunks -- see getEnvelopeRadiusChunksX()/Z()'s shared javadoc below. */
    public double getEnvelopeRadiusChunksX() {
        if (!enabled) {
            return 0.0;
        }
        double cached = this.envelopeCacheX;
        if (!Double.isNaN(cached)) {
            return cached;
        }
        double computed = computeAxisEnvelopeChunks(true);
        this.envelopeCacheX = computed;
        return computed;
    }

    /**
     * Z half-width, in chunks. Provable rather than estimated, same
     * reasoning as X: water only exists where the field is positive, the
     * hard cap forces the field to zero at normalised radius
     * capNormalizedRadius(), and the domain warp moves any point by at
     * most warpBlocks per axis. So water lies inside the cap ellipse grown
     * by the worst-case warp, and the beach reaches at most bankMargin
     * blocks further, measured in the worst-case (GRAD_MAX) field slope.
     *
     * X and Z are kept SEPARATE all the way through tileReached() now,
     * rather than both being set to Math.max(hx, hz) -- an ocean that's
     * elongated or rotated (getOceanStretchFactor() != 1, or just an
     * off-axis long-axis bearing) has a real, and often large, difference
     * between hx and hz; collapsing both to the larger one made
     * tileReached()'s bounding box a SQUARE stretched out to the long
     * axis's own reach in BOTH directions -- for a ~2:1 stretched ocean
     * that's roughly 2x the true area in the short-axis direction, and for
     * one rotated close to 45 degrees hx and hz both approach the full
     * semi-major length, so the square's short-axis reach could be nearly
     * as large as its long-axis reach even for a modestly stretched shape.
     * Every tile inside that excess is wrongly treated as "ocean tile,
     * carve the WHOLE interior" by UrbanGrid.placeOceanIfPresent() (see
     * that method's own TILE OWNERSHIP note) -- including tiles nowhere
     * near real water, with an ordinary river of their own that then never
     * gets its normal corridor/highway-crossing treatment at all, just the
     * ocean's own whole-tile carve-and-reclaim, which has no idea what a
     * highway/river crossing even is. That's what produced highway rings
     * silently failing to draw, and river crossings losing their bridge
     * deck down to just the bare culvert, many tiles away from any visible
     * water -- both traced back to this same over-square envelope.
     *
     * A previous attempt at this exact change was reverted with a comment
     * warning it made rivers hand off too early. That regression was real,
     * but the cause wasn't the axis split itself -- it was handoffFade()
     * (now handoffFadeForRiver()) checking all FOUR neighbour-tile
     * directions regardless of which axis the river being faded actually
     * travels along, so a Z-bound river's width could get cut by an
     * unrelated X-direction tile edge. The old, over-square envelope had
     * been accidentally masking that: with tileReached() falsely reporting
     * "reached" for more cross-axis neighbour tiles than were really in
     * range, the bad fade rarely triggered. Splitting the envelope without
     * also fixing the fade removed that accidental mask and the
     * cross-axis cut became visible, which read as "the split broke
     * rivers." handoffFadeForRiver() now scopes each river's fade to only
     * the two tile edges along its OWN travel axis, so that interaction is
     * gone and the split is safe on its own terms. Flagging this here
     * rather than silently dropping the old warning, since it's a genuine
     * scar worth knowing about if something in this area regresses again.
     */
    public double getEnvelopeRadiusChunksZ() {
        if (!enabled) {
            return 0.0;
        }
        double cached = this.envelopeCacheZ;
        if (!Double.isNaN(cached)) {
            return cached;
        }
        double computed = computeAxisEnvelopeChunks(false);
        this.envelopeCacheZ = computed;
        return computed;
    }

    private double computeAxisEnvelopeChunks(boolean xAxis) {
        double rhoCap = capNormalizedRadius();
        double a2 = semiMajorBlock * semiMajorBlock;
        double b2 = semiMinorBlock * semiMinorBlock;

        // The true axis-aligned half-extent of the rotated cap ellipse,
        // along just this one axis -- see this method's callers for why
        // X and Z are no longer collapsed into a single Math.max() value.
        double h = xAxis
                ? rhoCap * Math.sqrt(a2 * cosTheta * cosTheta + b2 * sinTheta * sinTheta)
                : rhoCap * Math.sqrt(a2 * sinTheta * sinTheta + b2 * cosTheta * cosTheta);

        double warpSlack = warpBlocks() * Math.sqrt(2.0);
        double waterHalfChunks = (h + warpSlack) / 16.0;
        double beachChunks = OceanConfig.getOceanBankMargin() * GRAD_MAX * Math.sqrt(stretch);
        return waterHalfChunks + beachChunks + OceanConfig.getOceanEnvelopeMargin();
    }

    private double warpBlocks() {
        return Math.max(0.0, OceanConfig.getOceanWarpFraction()) * baseRadiusBlock;
    }

    /** Coherent noise in [-1, 1], features roughly `wavelength` blocks across; ox/oz decorrelate two samples of the same generator. */
    private static double sample(SimplexOctaveGenerator gen, double x, double z, double wavelength, double ox, double oz) {
        return gen.noise(x / wavelength + ox, z / wavelength + oz, 2.0, 0.5, true);
    }

    /**
     * Noise-perturbed distance from center, in BLOCKS, of the open-water
     * boundary along the bearing `angleRad`. (nx, nz) below trace a smooth
     * unit circle as angleRad sweeps 0..2*PI, so sampling 2D noise at that
     * moving point -- rather than sampling 1D noise directly on angleRad --
     * means the result is automatically continuous and seamless all the
     * way around (no jump between angleRad=~2*PI and angleRad=0, which a
     * naive "noise(angle)" would produce). Now only one layer of the shape
     * (a gentle overall wobble of the ellipse); see the class javadoc.
     */
    private double edgeRadiusBlock(double angleRad) {
        double freq = OceanConfig.getOceanEdgeNoiseFrequency();
        double nx = Math.cos(angleRad) * freq;
        double nz = Math.sin(angleRad) * freq;
        double n = edgeNoise.noise(nx, nz, 0.5, 0.5, true); // -1..1
        double amp = OceanConfig.getOceanEdgeNoiseAmplitudeFraction();
        return baseRadiusBlock * (1.0 + n * amp);
    }

    /**
     * Local, per-block roughness in BLOCKS to add onto the shoreline
     * distance test at this exact world column -- see
     * OceanConfig.getOceanCoastJitterBlocks()'s own javadoc for why this
     * (sampled directly in world XZ space) is what actually gives the
     * coastline genuine small-scale raggedness, rather than another
     * smooth wave layered onto edgeRadiusBlock()'s angle-only noise.
     */
    private double coastJitterBlock(double worldBlockX, double worldBlockZ) {
        double amp = OceanConfig.getOceanCoastJitterBlocks();
        if (amp <= 0) {
            return 0.0;
        }
        double freq = OceanConfig.getOceanCoastJitterFrequency();
        double n = coastJitter.noise(worldBlockX * freq, worldBlockZ * freq, 0.5, 0.5, true); // -1..1
        return n * amp;
    }

    /** Polynomial smooth minimum: like min(a, b) but rounded over a band of width k, never above min(a, b). */
    private static double smoothMin(double a, double b, double k) {
        double h = Math.max(k - Math.abs(a - b), 0.0) / k;
        return Math.min(a, b) - h * h * k * 0.25;
    }

    /**
     * The shoreline field F(x, z), in blocks: positive = open water,
     * zero = the shoreline, negative = land. See the class javadoc for the
     * layers. Not itself a true distance (see distanceInsideShoreBlock()).
     */
    private double field(double x, double z) {
        double r = baseRadiusBlock;

        // 2. Domain warp: bend the world before evaluating the ellipse.
        double warp = warpBlocks();
        double px = x;
        double pz = z;
        if (warp > 0) {
            double wl = r * WARP_WAVELENGTH;
            px += sample(warpNoise, x, z, wl, 0.0, 0.0) * warp;
            pz += sample(warpNoise, x, z, wl, 57.3, -91.1) * warp;
        }

        // 1. Stretched, rotated ellipse, with the old angle-based wobble on its radius.
        double dx = px - centerBlockX;
        double dz = pz - centerBlockZ;
        double u = dx * cosTheta + dz * sinTheta;
        double v = -dx * sinTheta + dz * cosTheta;
        double rho = Math.sqrt((u * u) / (semiMajorBlock * semiMajorBlock) + (v * v) / (semiMinorBlock * semiMinorBlock));
        double edgeFactor = edgeRadiusBlock(Math.atan2(v, u)) / r;
        double f = r * (1.0 - rho / edgeFactor);

        // 3 + 4. Bays and headlands, then coves and points, in world space.
        double bay = OceanConfig.getOceanBayFraction() * r;
        if (bay > 0) {
            f += bay * sample(bayNoise, x, z, r * BAY_WAVELENGTH, 0.0, 0.0);
            f += bay * 0.15 * sample(detailNoise, x, z, r * DETAIL_WAVELENGTH, 0.0, 0.0);
        }
        f += coastJitterBlock(x, z);

        // 5. Inlets: a ribbon along the noise's zero line (crest^3 keeps it
        // tens of blocks wide, not a hairline), reaching at most inletBlocks
        // inland from the coast, and only where a second, slow noise field
        // allows it -- so inlets punctuate stretches of coast rather than
        // lining all of it.
        double inlet = OceanConfig.getOceanInletFraction() * r;
        if (inlet > 0) {
            double crest = 1.0 - Math.abs(sample(inletNoise, x, z, r * INLET_WAVELENGTH, 0.0, 0.0));
            double allowed = Math.max(0.0, Math.min(1.0, (sample(bayNoise, x, z, r * BAY_WAVELENGTH, 200.0, -200.0) + 0.1) / 0.5));
            f += inlet * crest * crest * crest * allowed;
        }

        // 6. Islands: land rising out of the water where a noise field is high.
        double island = OceanConfig.getOceanIslandFraction() * r;
        if (island > 0) {
            double t = (sample(islandNoise, x, z, r * ISLAND_WAVELENGTH, 0.0, 0.0) - 0.35) / 0.4;
            if (t > 0) {
                t = Math.min(1.0, t);
                f -= island * t * t * (3.0 - 2.0 * t);
            }
        }

        // 7. Hard cap.
        double cap = r * (capNormalizedRadius() - rho);
        return smoothMin(f, cap, r * 0.06);
    }

    /**
     * Signed distance INSIDE the shoreline, in blocks, at this exact block
     * column: positive means this point is open water, and is how far it
     * sits from the shoreline; zero or negative means dry land, and how far
     * outside the shoreline it sits. RiverProvider's rough equivalent is
     * distanceFromCenterBlock(), except sign convention there is "which
     * side of the spine", not "inside vs outside" -- there's no single
     * natural sign convention shared between a line and a body, so this
     * picks the one that reads most directly as "how deep/how far out",
     * matching what OceanParcel actually needs it for.
     *
     * This is the OCEAN alone. distanceInsideShoreBlock() is the same thing
     * merged with the river channels, and is what everything outside this
     * class should call.
     *
     * Computed as F / |slope of F|: exact for a straight coast, a good
     * first-order estimate for a curved one. The slope is measured only
     * where a precise distance matters (the shelf and the beach); in deep
     * water it fades to plain F, and far inland it isn't consulted at all.
     */
    public double oceanDistanceInsideShoreBlock(double worldBlockX, double worldBlockZ) {
        if (!enabled) {
            return Double.NaN;
        }
        double f = field(worldBlockX, worldBlockZ);
        double outerStart = preciseBandOutsideBlocks();
        if (f >= DEEP_BLEND_END || f <= -(outerStart + OUTER_BLEND_BLOCKS)) {
            return f;
        }

        double gx = (field(worldBlockX + GRAD_STEP, worldBlockZ) - field(worldBlockX - GRAD_STEP, worldBlockZ)) / (2.0 * GRAD_STEP);
        double gz = (field(worldBlockX, worldBlockZ + GRAD_STEP) - field(worldBlockX, worldBlockZ - GRAD_STEP)) / (2.0 * GRAD_STEP);
        double g = Math.min(GRAD_MAX, Math.max(GRAD_MIN, Math.sqrt(gx * gx + gz * gz)));
        double d = f / g;
        if (f > DEEP_BLEND_START) {
            double w = (f - DEEP_BLEND_START) / (DEEP_BLEND_END - DEEP_BLEND_START);
            w = w * w * (3.0 - 2.0 * w);
            d = d + (f - d) * w;
        }
        if (f < -outerStart) {
            // Far outside the coast nothing reads the distance precisely: ease into the raw field instead of jumping to it.
            double w = smooth01((-outerStart - f) / OUTER_BLEND_BLOCKS);
            d = d + (f - d) * w;
        }
        return d;
    }

    /**
     * How far outside the coast, in FIELD units (blocks of F), the slope-corrected distance must stay exact: the beach band and
     * the whole river mouth zone. With the worst-case slope (GRAD_MAX) this is where the corrected distance reaches
     * max(beach, mouth zone) blocks, so everything the mouth merge reads (oceanWeight() is zero from the zone's edge outwards)
     * lies inside it, and the blend to the raw field happens where the weight is already zero.
     */
    private static double preciseBandOutsideBlocks() {
        double beachBlocks = OceanConfig.getOceanBankMargin() * 16.0;
        double zone = Math.max(beachRunBlocks() + 16.0, OceanConfig.getOceanMouthZoneBlocks());
        return Math.max(beachBlocks, zone) * GRAD_MAX;
    }

    /**
     * Signed distance inside the shoreline of the MERGED water, in blocks:
     * the ocean's coast united with the channels of the attached rivers,
     * inside the ocean's carve box. Positive = water, zero or negative =
     * land. With no rivers attached, or outside the box, this is exactly
     * oceanDistanceInsideShoreBlock(). This is what OceanParcel paints from
     * and what OceanWaterEdgeProvider measures, so the rivers are treated as
     * water everywhere the ocean's painter and the reclaim pass are
     * concerned.
     */
    public double distanceInsideShoreBlock(double worldBlockX, double worldBlockZ) {
        double oceanDistance = oceanDistanceInsideShoreBlock(worldBlockX, worldBlockZ);
        if (Double.isNaN(oceanDistance) || !hasRivers() || !inOceanDomain(worldBlockX, worldBlockZ)) {
            return oceanDistance;
        }
        return mergedDistance(worldBlockX, worldBlockZ, oceanDistance, mouthWeight(worldBlockX, worldBlockZ, oceanDistance));
    }

    /**
     * Y of the top water block everywhere the ocean's painter runs. When
     * rivers converge on the ocean this is simply THE RIVER'S water level
     * (river build height minus river depth): the sea and the channels that
     * feed it are one body of water at one level, with nothing to blend or
     * step between. Only an ocean with no rivers attached falls back to its
     * own buildHeight/depth settings.
     */
    public int waterSurfaceY() {
        if (hasRivers()) {
            return RiverConfig.getRiverBuildHeight() - RiverConfig.getRiverDepth();
        }
        return OceanConfig.getOceanBuildHeight() - OceanConfig.getOceanDepth();
    }

    /**
     * Everything the painter needs to know about one block column, computed
     * once: the merged distance, the water surface level, and the water
     * column depth.
     *
     * THE MOUTH MERGE. Far from the ocean a river is entirely its own
     * thing (weight m = 0): its own width. Within
     * OceanConfig.getOceanMouthZoneBlocks() of the shoreline the ocean's
     * weight m rises smoothly from 0 to 1 (and is exactly 1 in the beach
     * band and everywhere in the water), and that single weight blends:
     *
     *   - CHANNEL WIDTH: the river's half-width is multiplied by up to the
     *     mouth flare, so the channel opens into a funnel;
     *   - CORNER: the merged distance is a smooth maximum of the ocean's and
     *     the channel's, which rounds the inside corner where bank meets
     *     coast by OceanConfig.getOceanMouthFilletBlocks().
     *
     * What is deliberately NOT blended:
     *
     *   - WATER SURFACE: one level for everything -- see waterSurfaceY().
     *   - BED: the deeper of the river's bed and the ocean's bed, per
     *     column. The river's channel keeps its full depth all the way down
     *     to the coast and out through the shelf (a trench through the
     *     shallows), until the ocean's own shelf is deeper than the channel
     *     and takes over. Interpolating the river's bed toward the ocean's
     *     used to raise a shallow bar across every river mouth, because the
     *     ocean's shelf is only one block deep at its own coast.
     *
     * Where the column is in the ocean's water away from any channel, the
     * result is exactly what the ocean alone would give. Where it is far
     * upstream, it is exactly what the river's own painter would give.
     */
    public Sample sample(double worldBlockX, double worldBlockZ) {
        double oceanDistance = oceanDistanceInsideShoreBlock(worldBlockX, worldBlockZ);
        int surface = waterSurfaceY();
        if (Double.isNaN(oceanDistance) || !hasRivers() || !inOceanDomain(worldBlockX, worldBlockZ)) {
            int depth = oceanDistance > 0 ? Math.max(1, waterColumnDepthBlocks(worldBlockX, worldBlockZ, oceanDistance)) : 0;
            return new Sample(oceanDistance, surface, depth);
        }

        double m = mouthWeight(worldBlockX, worldBlockZ, oceanDistance);
        double channel = channelInside(worldBlockX, worldBlockZ, m);
        double distance = mergedDistance(worldBlockX, worldBlockZ, oceanDistance, m, channel);

        int depth = 0;
        if (distance > 0) {
            // Ocean bed: the real shelf inside the coast, and the shelf's own
            // depth AT the coast (one block) outside it.
            int oceanDepth = oceanDistance > 0 ? Math.max(1, waterColumnDepthBlocks(worldBlockX, worldBlockZ, oceanDistance)) : 1;
            // River bed: RiverParcel.paintWater()'s own shore taper (0 at the
            // channel's edge, full depth four blocks in).
            double riverFull = Math.max(1, RiverConfig.getRiverWaterColumnDepth());
            double shoreT = channel > 0 ? Math.min(1.0, channel / 4.0) : 0.0;
            int riverDepth = Math.max(1, (int) Math.round(1 + shoreT * (riverFull - 1)));
            depth = Math.max(oceanDepth, riverDepth);
        }
        return new Sample(distance, surface, depth);
    }

    /**
     * Weight of the ocean, 0..1, at a point that is oceanDistance blocks
     * inside the ocean's coast (negative = outside it): exactly 1 within
     * the beach band and everywhere in the water, falling smoothly to 0 at
     * OceanConfig.getOceanMouthZoneBlocks() outside the coast. Drives the
     * whole mouth merge -- see sample().
     */
    private double mouthWeight(double worldBlockX, double worldBlockZ, double oceanDistance) {
        // The ocean itself does not need a handoff fade.  Handoff is a
        // property of the ATTACHED RIVER'S orientation: an X-bound river
        // hands off at X tile edges, while a Z-bound river hands off at Z
        // tile edges. Applying one generic edge fade to both rivers lets a
        // perpendicular tile edge suppress a river that is actually meant
        // to continue through that tile.
        return oceanWeight(oceanDistance);
    }

    private double oceanWeight(double oceanDistance) {
        double plateau = beachRunBlocks();
        double zone = Math.max(plateau + 16.0, OceanConfig.getOceanMouthZoneBlocks());
        double t = (oceanDistance + zone) / (zone - plateau);
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * Signed distance inside the nearest attached river's channel, in
     * blocks (positive = inside the water), with the channel's half-width
     * scaled up by the mouth flare in proportion to the ocean's weight m.
     * Negative infinity when no attached river is enabled.
     */
    private double channelInside(double worldBlockX, double worldBlockZ, double m) {
        double flare = Math.max(1.0, OceanConfig.getOceanMouthFlare());
        double best = Double.NEGATIVE_INFINITY;
        RiverProvider[] rivers = {this.xRiver, this.zRiver};
        for (RiverProvider river : rivers) {
            if (river == null || !river.isEnabled()) {
                continue;
            }

            double along = river.isZBound() ? worldBlockZ : worldBlockX;
            double across = river.isZBound() ? worldBlockX : worldBlockZ;

            // Handoff belongs to the river's travel axis.  Do not let a
            // perpendicular ocean-tile edge fade this river out: that was
            // the subtle X/Z asymmetry where one attached river could make
            // it through the ocean boundary while the other stopped at the
            // ocean-owned tile.
            double riverM = m * handoffFadeForRiver(worldBlockX, worldBlockZ, river);
            double halfWidth = river.widthAtBlock(along) / 2.0
                    * (1.0 + (flare - 1.0) * riverM);
            best = Math.max(best, halfWidth - Math.abs(across - river.spineBlock(along)));
        }
        return best;
    }

    private double mergedDistance(double worldBlockX, double worldBlockZ, double oceanDistance, double m) {
        return mergedDistance(worldBlockX, worldBlockZ, oceanDistance, m, channelInside(worldBlockX, worldBlockZ, m));
    }

    private double mergedDistance(double worldBlockX, double worldBlockZ, double oceanDistance, double m, double channel) {
        if (channel == Double.NEGATIVE_INFINITY) {
            return oceanDistance;
        }
        double fillet = Math.max(0.0, OceanConfig.getOceanMouthFilletBlocks());
        if (fillet <= 0) {
            return Math.max(oceanDistance, channel);
        }
        return -smoothMin(-oceanDistance, -channel, fillet);
    }

    /** Signed distance inside one river's own (un-flared) channel at this point, in blocks: positive = in the water. */
    private static double riverInside(RiverProvider river, double worldBlockX, double worldBlockZ) {
        double along = river.isZBound() ? worldBlockZ : worldBlockX;
        double across = river.isZBound() ? worldBlockX : worldBlockZ;
        return river.widthAtBlock(along) / 2.0 - Math.abs(across - river.spineBlock(along));
    }

    /** The attached river whose channel is nearest to this point (the one a bank here belongs to), or null if none is attached. */
    public RiverProvider dominantRiver(double worldBlockX, double worldBlockZ) {
        RiverProvider best = null;
        double bestInside = Double.NEGATIVE_INFINITY;
        RiverProvider[] rivers = {this.xRiver, this.zRiver};
        for (RiverProvider river : rivers) {
            if (river == null || !river.isEnabled()) {
                continue;
            }
            double inside = riverInside(river, worldBlockX, worldBlockZ);
            if (inside > bestInside) {
                bestInside = inside;
                best = river;
            }
        }
        return best;
    }

    /** True if an attached river's channel (at its widest flare) lies within marginBlocks of this point. */
    public boolean isNearRiver(double worldBlockX, double worldBlockZ, double marginBlocks) {
        if (!enabled || !hasRivers()) {
            return false;
        }
        double flare = Math.max(1.0, OceanConfig.getOceanMouthFlare());
        RiverProvider[] rivers = {this.xRiver, this.zRiver};
        for (RiverProvider river : rivers) {
            if (river == null || !river.isEnabled()) {
                continue;
            }
            double along = river.isZBound() ? worldBlockZ : worldBlockX;
            double across = river.isZBound() ? worldBlockX : worldBlockZ;
            double reach = river.widthAtBlock(along) / 2.0 * flare + marginBlocks;
            if (Math.abs(across - river.spineBlock(along)) <= reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * How much the bank at this point belongs to the RIVER rather than the coast, 0..1: 1 where a river's channel is the
     * nearest water and the sea has no weight (so the ground must take the shape the river's own painter gives its banks),
     * 0 in the sea's beach band and wherever the coast is the nearer water. It is the weight OceanParcel uses to hand the
     * bank's ramp from the river's profile to the beach's, so the ground does not step where the river's chunks meet the
     * ocean's. Continuous in position.
     */
    public double riverBankWeight(double worldBlockX, double worldBlockZ) {
        if (!enabled || !hasRivers() || !inOceanDomain(worldBlockX, worldBlockZ)) {
            return 0.0;
        }
        double oceanDistance = oceanDistanceInsideShoreBlock(worldBlockX, worldBlockZ);
        if (Double.isNaN(oceanDistance)) {
            return 0.0;
        }
        double m = mouthWeight(worldBlockX, worldBlockZ, oceanDistance);
        if (m >= 1.0) {
            return 0.0;
        }
        double channel = channelInside(worldBlockX, worldBlockZ, m);
        if (channel == Double.NEGATIVE_INFINITY) {
            return 0.0;
        }
        double lead = smooth01((channel - oceanDistance + 16.0) / 32.0);
        return (1.0 - m) * lead;
    }

    // ------------------------------------------------------------------
    // TILE OWNERSHIP. The ocean owns WHOLE 64x64-chunk tiles, never a slice of
    // one: every tile whose interior (root+1 .. root+62 on both axes -- the
    // highway ring sits on root and root+63) the carve envelope reaches is an
    // "ocean tile", painted entirely under the ocean's rules (this class's
    // merged water field for the terrain, OceanReclaim for the districts).
    // Rivers converging on the ocean are part of that field inside an ocean
    // tile and are handed back to the ordinary river pipeline AT the tile's
    // highway ring, so a tile is never half ocean rule and half river rule.
    // ------------------------------------------------------------------

    private static final int TILE = 64;

    /** Root chunk of the 64-chunk tile containing this chunk coordinate (works for negatives). */
    public static int tileRootOf(int chunk) {
        return Math.floorDiv(chunk, TILE) * TILE;
    }

    /**
     * True if the ocean's carve envelope reaches the interior of the tile
     * rooted at (rootX, rootZ) -- i.e. it is an ocean tile. The exact test
     * UrbanGrid.placeOceanIfPresent() applies.
     */
    public boolean tileReached(int rootX, int rootZ) {
        double envX = getEnvelopeRadiusChunksX();
        double envZ = getEnvelopeRadiusChunksZ();
        if (envX <= 0 || envZ <= 0) {
            return false;
        }

        // IMPORTANT: envX/envZ are measured from centerBlockX/Z, not from
        // the containing chunk's integer coordinate. With this provider's
        // +8 block center offset, using getCenterChunkX/Z() shifts the
        // envelope by 0.5 chunk and can incorrectly exclude a boundary
        // tile. That exclusion propagates into inOceanDomainChunk() and
        // handoffFadeForRiver(), which is why rivers could disappear
        // immediately outside the ocean's owned tiles.
        double cx = centerBlockX / 16.0;
        double cz = centerBlockZ / 16.0;

        return !(cx + envX < rootX + 1 || cx - envX > rootX + 62
                || cz + envZ < rootZ + 1 || cz - envZ > rootZ + 62);
    }

    /** Indices into droppedRingSides(): the tile's west/east columns (x = root, root+63) and north/south rows (z = root, root+63). */
    public static final int RING_COL_MIN = 0;
    public static final int RING_COL_MAX = 1;
    public static final int RING_ROW_MIN = 2;
    public static final int RING_ROW_MAX = 3;

    private final java.util.concurrent.ConcurrentHashMap<Long, boolean[]> ringDropCache =
            new java.util.concurrent.ConcurrentHashMap<Long, boolean[]>();

    /**
     * Which sides of an ocean tile's highway ring are NOT drawn. The ring
     * always wraps a tile, except that a side which could not run all the
     * way across without meeting open ocean is dropped whole -- no partial
     * highway stubs ending at the water. A dropped side's chunks become part
     * of the tile's own interior: painted by the ocean and handed to
     * OceanReclaim like any other land, so districts use that space from the
     * initial drawing.
     *
     * A side is decided by boundaryHitsOcean() at the exact seam between
     * this tile and its neighbour on that side -- see its javadoc for why
     * that, and not either tile's ring row considered alone, is what keeps
     * two neighbouring tiles from disagreeing about their shared edge.
     *
     * This briefly used tile ADJACENCY (is the neighbour tileReached()) as a
     * shortcut for "is the neighbour ocean too" -- structurally symmetric,
     * since tile A asking about neighbour B computes the exact same call B
     * makes asking about A, so the two could never disagree. But
     * tileReached() is deliberately the coarse, provably-safe bound over the
     * ocean's WHOLE envelope (see getEnvelopeRadiusChunks()), not a local
     * shoreline test -- for an elongated or bayed ocean it is true for many
     * tiles nowhere near their shared edge (e.g. two tiles both inside the
     * bounding ellipse of a bay, with dry land between them). Chaining that
     * through neighbour after neighbour dropped rings across almost the
     * whole ocean-touching block, not just the tiles actually on the water.
     * boundaryHitsOcean() keeps the same symmetry (both tiles ask about the
     * same seam) while staying tied to where the water actually is.
     */
    public boolean[] droppedRingSides(int rootX, int rootZ) {
        long key = (((long) rootX) << 32) ^ (rootZ & 0xffffffffL);
        boolean[] cached = ringDropCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean[] dropped = new boolean[4];
        if (tileReached(rootX, rootZ)) {
            double margin = OceanConfig.getOceanBankMargin() * 16.0;
            dropped[RING_COL_MIN] = verticalBoundaryHitsOcean(rootX, rootZ, margin);
            dropped[RING_COL_MAX] = verticalBoundaryHitsOcean(rootX + TILE, rootZ, margin);
            dropped[RING_ROW_MIN] = horizontalBoundaryHitsOcean(rootX, rootZ, margin);
            dropped[RING_ROW_MAX] = horizontalBoundaryHitsOcean(rootX, rootZ + TILE, margin);
        }
        ringDropCache.put(key, dropped);
        return dropped;
    }

    /**
     * True if the shoreline comes within marginBlocks of the vertical seam
     * at chunk-x = boundaryX, anywhere along the tile-length seam starting
     * at rootZ -- tested against BOTH ring columns either side of the seam
     * (boundaryX - 1, the west tile's own column, and boundaryX, the east
     * tile's own column). Any tile computing its COL_MIN at boundaryX = its
     * own rootX gets the identical call, in the identical order, as its west
     * neighbour computing its COL_MAX at boundaryX = (west neighbour's
     * rootX) + TILE, which is the same number -- so the two can never
     * disagree about their shared edge, while the test itself still only
     * cares about the real shoreline within one margin of that exact seam,
     * not the ocean's whole bounding envelope.
     */
    private boolean verticalBoundaryHitsOcean(int boundaryX, int rootZ, double marginBlocks) {
        for (int i = 0; i < TILE; i++) {
            int z = rootZ + i;
            if (ringCellHitsOcean(boundaryX - 1, z, marginBlocks)) {
                return true;
            }
            if (ringCellHitsOcean(boundaryX, z, marginBlocks)) {
                return true;
            }
        }
        return false;
    }

    /** Horizontal-seam counterpart of verticalBoundaryHitsOcean(), at chunk-z = boundaryZ. */
    private boolean horizontalBoundaryHitsOcean(int rootX, int boundaryZ, double marginBlocks) {
        for (int i = 0; i < TILE; i++) {
            int x = rootX + i;
            if (ringCellHitsOcean(x, boundaryZ - 1, marginBlocks)) {
                return true;
            }
            if (ringCellHitsOcean(x, boundaryZ, marginBlocks)) {
                return true;
            }
        }
        return false;
    }

    /** Ocean only -- rivers crossing the ring are handled by the existing water-passes-through rule, not by dropping the side. */
    private boolean ringCellHitsOcean(int chunkX, int chunkZ, double marginBlocks) {
        double d = oceanDistanceInsideShoreBlock(chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0);
        return !Double.isNaN(d) && d > -marginBlocks;
    }

    /**
     * True if this chunk is in the ocean's domain: an interior chunk of an
     * ocean tile; a chunk of a DROPPED ring side (it is interior now -- see
     * droppedRingSides()); or a kept highway-ring chunk whose whole boundary
     * pair belongs to ocean tiles (both tiles the ring separates), where a
     * river's water is allowed to pass through the ring. A kept ring chunk
     * between an ocean tile and a non-ocean tile is NOT in the domain -- it
     * stays an ordinary highway, and is exactly where a river is handed over.
     */
    public boolean inOceanDomainChunk(int chunkX, int chunkZ) {
        int rx = tileRootOf(chunkX);
        int rz = tileRootOf(chunkZ);
        if (!tileReached(rx, rz)) {
            return false;
        }
        int lx = chunkX - rx;
        int lz = chunkZ - rz;
        boolean colRing = lx == 0 || lx == TILE - 1;
        boolean rowRing = lz == 0 || lz == TILE - 1;
        if (!colRing && !rowRing) {
            return true;
        }
        boolean[] d = droppedRingSides(rx, rz);
        boolean colOk = !colRing || (lx == 0 ? d[RING_COL_MIN] : d[RING_COL_MAX]);
        boolean rowOk = !rowRing || (lz == 0 ? d[RING_ROW_MIN] : d[RING_ROW_MAX]);
        if (colOk && rowOk) {
            return true; // this tile's own reclaimed ring space
        }
        int dx = lx == 0 ? -TILE : (lx == TILE - 1 ? TILE : 0);
        int dz = lz == 0 ? -TILE : (lz == TILE - 1 ? TILE : 0);
        if (dx != 0 && !tileReached(rx + dx, rz)) {
            return false;
        }
        if (dz != 0 && !tileReached(rx, rz + dz)) {
            return false;
        }
        return dx == 0 || dz == 0 || tileReached(rx + dx, rz + dz);
    }

    private boolean inOceanDomain(double worldBlockX, double worldBlockZ) {
        if (this.tileFree) {
            return true;
        }
        return inOceanDomainChunk((int) Math.floor(worldBlockX / 16.0), (int) Math.floor(worldBlockZ / 16.0));
    }

    /**
     * 0..1 factor that takes the river/ocean merge (the mouth flare) to zero
     * as a point nears an edge of its tile where the river is handed over to
     * the river pipeline -- an edge whose neighbouring tile is NOT an ocean
     * tile. Reaches 0 at the tile's interior edge, 1 from
     * OceanConfig.getOceanRiverHandoffBlocks() inward, so the channel arrives
     * at the highway with exactly the river's own width. Edges shared with
     * another ocean tile don't count: the water simply continues across them.
     */
    private double handoffFadeForRiver(double worldBlockX, double worldBlockZ, RiverProvider river) {
        if (this.tileFree) {
            return 1.0;       // no tile edge to hand the river over at: it simply carries on, flaring with the sea's weight
        }
        double h = OceanConfig.getOceanRiverHandoffBlocks();
        if (h <= 0 || river == null) {
            return 1.0;
        }

        int chunkX = (int) Math.floor(worldBlockX / 16.0);
        int chunkZ = (int) Math.floor(worldBlockZ / 16.0);
        int rx = tileRootOf(chunkX);
        int rz = tileRootOf(chunkZ);

        // The tile's edge is its interior edge -- or the ring's own outer
        // edge on a side whose ring was dropped. Only the two edges along
        // the river's OWN travel axis are relevant to its handoff.
        boolean[] dd = droppedRingSides(rx, rz);
        double x0 = (rx + (dd[RING_COL_MIN] ? 0 : 1)) * 16.0;
        double x1 = (rx + (dd[RING_COL_MAX] ? TILE : TILE - 1)) * 16.0;
        double z0 = (rz + (dd[RING_ROW_MIN] ? 0 : 1)) * 16.0;
        double z1 = (rz + (dd[RING_ROW_MAX] ? TILE : TILE - 1)) * 16.0;

        double d = Double.POSITIVE_INFINITY;
        if (river.isZBound()) {
            if (!tileReached(rx, rz - TILE)) {
                d = Math.min(d, worldBlockZ - z0);
            }
            if (!tileReached(rx, rz + TILE)) {
                d = Math.min(d, z1 - worldBlockZ);
            }
        } else {
            if (!tileReached(rx - TILE, rz)) {
                d = Math.min(d, worldBlockX - x0);
            }
            if (!tileReached(rx + TILE, rz)) {
                d = Math.min(d, x1 - worldBlockX);
            }
        }

        if (d == Double.POSITIVE_INFINITY) {
            return 1.0;
        }
        double t = Math.max(0.0, Math.min(1.0, d / h));
        return t * t * (3.0 - 2.0 * t);
    }

    /**
     * True if this chunk is water or bank of an attached river AND has no sea
     * influence at all -- the mouth merge (see sample()) has zero weight at
     * every point sampled -- so the river's own geometry (spine, width) is
     * exactly what is painted there. That is the only water a street may be
     * bridged over: a bridge is carved from the river's own channel, and
     * across a flared mouth or open sea that would not match the painted water.
     */
    public boolean isRiverOnlyChunk(int chunkX, int chunkZ) {
        if (!hasRivers()) {
            return false;
        }
        RiverProvider x = this.xRiver;
        RiverProvider z = this.zRiver;
        boolean river = (x != null && x.isEnabled() && x.isRiverOrBank(chunkX, chunkZ))
                || (z != null && z.isEnabled() && z.isRiverOrBank(chunkX, chunkZ));
        if (!river) {
            return false;
        }
        double baseX = chunkX * 16.0;
        double baseZ = chunkZ * 16.0;
        for (int i = 0; i <= 2; i++) {
            for (int j = 0; j <= 2; j++) {
                double px = baseX + i * 8.0;
                double pz = baseZ + j * 8.0;
                double oceanDistance = oceanDistanceInsideShoreBlock(px, pz);
                if (!Double.isNaN(oceanDistance) && mouthWeight(px, pz, oceanDistance) > 0.0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Does the sea reach this point at all, i.e. is it within the mouth zone of the coast (or in the water)? Where it does,
     * a river is part of the sea's merged water and is painted by the ocean; where it does not, it is the river's own.
     */
    public boolean hasSeaInfluence(double worldBlockX, double worldBlockZ) {
        double d = oceanDistanceInsideShoreBlock(worldBlockX, worldBlockZ);
        return !Double.isNaN(d) && oceanWeight(d) > 0.0;
    }

    /** The X-bound river attached to this ocean, or null. */
    public RiverProvider getXRiver() {
        return this.xRiver;
    }

    /** The Z-bound river attached to this ocean, or null. */
    public RiverProvider getZRiver() {
        return this.zRiver;
    }

    /**
     * Length, in blocks, of the beach's rise from the water to the ocean's
     * buildHeight. Automatic (OceanConfig.getOceanBankRunBlocks() == 0) is the
     * reclaim berth -- where OceanReclaim's districts start, the same run the
     * river's bank has; never longer than the bank margin.
     */
    public static double beachRunBlocks() {
        double margin = OceanConfig.getOceanBankMargin() * 16.0;
        int run = OceanConfig.getOceanBankRunBlocks();
        double wanted = run > 0 ? run : Math.max(1, RiverConfig.getRiverReclaimBerthChunks()) * 16.0;
        return Math.min(margin, wanted);
    }

    /**
     * Water column depth, in blocks, at a point sitting `distanceInsideShoreBlock`
     * blocks inside the shoreline, ignoring where in the world the point is:
     * the plain three-zone profile with straight (un-wobbled) zone boundaries
     * and no seabed relief. Kept for callers that only have a distance;
     * OceanParcel uses the positional overload below.
     *
     * Returns 0 for a non-positive distance (i.e. this point isn't open
     * water at all).
     */
    public int waterColumnDepthBlocks(double distanceInsideShoreBlock) {
        if (!enabled || distanceInsideShoreBlock <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(zoneDepth(distanceInsideShoreBlock)));
    }

    /**
     * Water column depth with the three depth zones AND the SEABED RELIEF.
     *
     * The bed is built from three concentric zones measured in from the
     * shore (see OceanConfig): a SHALLOW shelf, the ordinary MID depth, and a
     * DEEP trench in the middle, eased into each other over their transition
     * widths. The distance the zones are measured with is stretched and
     * squeezed by a broad noise field, so each zone boundary wanders along the
     * coast (banks and drop-offs) instead of running parallel to it.
     *
     * The relief noise (basins and rises, banks and hollows, ripples) is then
     * multiplied into the depth increment above the first block, so it fades
     * to nothing at the shoreline. Its strength depends on the zone: reduced
     * on the shallow shelf (kept smooth for boats), full in MID (the sea's
     * original character), and reduced again in the DEEP zone (a predictable
     * floor for big builds).
     *
     * The bed is never allowed closer than a few blocks to the world floor.
     *
     * Returns 0 for a non-positive distance.
     */
    public int waterColumnDepthBlocks(double worldBlockX, double worldBlockZ, double distanceInsideShoreBlock) {
        if (!enabled || distanceInsideShoreBlock <= 0) {
            return 0;
        }
        double r = baseRadiusBlock;

        // Zone boundaries wander: scale the distance by 1 +/- noiseFraction.
        // Scaling (not offsetting) keeps the near-shore ramp untouched.
        double warpFraction = OceanConfig.getOceanZoneBoundaryNoiseFraction();
        double dist = distanceInsideShoreBlock;
        if (warpFraction > 0) {
            double w = sample(bedNoise, worldBlockX, worldBlockZ, r * BED_WAVELENGTH_BROAD, 211.3, -97.7);
            dist = distanceInsideShoreBlock * Math.max(0.2, 1.0 + warpFraction * w);
        }

        double base = zoneDepth(dist);

        double reliefFraction = Math.max(0.0, OceanConfig.getOceanSeabedReliefFraction());
        double relief = 0.0;
        if (reliefFraction > 0) {
            double n = 0.6 * sample(bedNoise, worldBlockX, worldBlockZ, r * BED_WAVELENGTH_BROAD, 0.0, 0.0)
                    + 0.3 * sample(bedNoise, worldBlockX, worldBlockZ, r * BED_WAVELENGTH_MID, 31.7, -13.9)
                    + 0.1 * sample(bedNoise, worldBlockX, worldBlockZ, r * BED_WAVELENGTH_FINE, -77.2, 45.1);
            double[] wgt = zoneWeights(dist);
            double reliefScale = wgt[0] * OceanConfig.getOceanShallowReliefScale()
                    + wgt[1] * 1.0
                    + wgt[2] * OceanConfig.getOceanDeepReliefScale();
            relief = reliefFraction * reliefScale * n;
        }
        // R30ea: restore world-coordinate seabed bedforms and shallow-to-mid
        // bed variation before applying the minimum navigable depth. All
        // components fade toward the shoreline with the original envelope.
        double bedformFade = smooth01(distanceInsideShoreBlock /
                Math.max(12.0, OceanConfig.getOceanShoreRampBlocks() * 1.8));
        double[] weights = zoneWeights(dist);
        double bedformScale = OceanConfig.getOceanSeabedBedformScaleBlocks();
        double broadBedform = sample(bedNoise, worldBlockX, worldBlockZ, bedformScale, 513.2, -429.1);
        double midBedform = sample(bedNoise, worldBlockX, worldBlockZ, bedformScale * 0.42, -289.7, 147.1);
        double bedform = 0.74 * broadBedform + 0.26 * midBedform;
        double shallowVariation = weights[0] * OceanConfig.getOceanSeabedRippleBlocks();
        double midVariation = weights[1] * OceanConfig.getOceanSeabedMidVariationBlocks();
        double depth = 1.0 + (base - 1.0) * (1.0 + relief)
                + bedformFade * (shallowVariation + midVariation) * bedform;
        double minimumNavigable = 1.0 + (OceanConfig.getOceanMinimumNavigableDepthBlocks() - 1.0)
                * smooth01(distanceInsideShoreBlock /
                Math.max(1.0, OceanConfig.getOceanShoreRampBlocks()));
        depth = Math.max(depth, minimumNavigable);
        // Keep the bed a few blocks above the world floor.
        double maxDepth = Math.max(1, waterSurfaceY() - 4);
        return Math.max(1, (int) Math.round(Math.min(depth, maxDepth)));
    }

    /**
     * The three-zone profile: base water column depth at `dist` blocks inside
     * the shore (before relief). One block at the shoreline, easing up to the
     * shallow shelf depth over the shore ramp, holding there to shallowEnd,
     * easing down to the MID depth over shallowToMid, holding to deepStart,
     * then easing into the DEEP depth over midToDeep.
     */
    private static double zoneDepth(double dist) {
        double shallow = Math.max(1, OceanConfig.getOceanShallowDepth());
        double mid = Math.max(shallow, OceanConfig.getOceanMaxWaterColumnDepth());
        double deep = Math.max(mid, OceanConfig.getOceanDeepDepth());
        double ramp = Math.max(1, OceanConfig.getOceanShoreRampBlocks());
        double[] b = zoneBounds();
        double depth = 1.0 + (shallow - 1.0) * smooth01(dist / ramp);
        depth += (mid - shallow) * smooth01((dist - b[0]) / (b[1] - b[0]));
        depth += (deep - mid) * smooth01((dist - b[2]) / (b[3] - b[2]));
        return depth;
    }

    /**
     * How much of `dist` is in each zone, as weights summing to 1:
     * {shallow, mid, deep}. Blended across the transitions, so the zone-
     * dependent relief strength eases the same way the depth does.
     */
    private static double[] zoneWeights(double dist) {
        double[] b = zoneBounds();
        double toMid = smooth01((dist - b[0]) / (b[1] - b[0]));
        double toDeep = smooth01((dist - b[2]) / (b[3] - b[2]));
        return new double[]{1.0 - toMid, toMid * (1.0 - toDeep), toDeep};
    }

    /**
     * {shallowEnd, midStart, deepStart, deepFull} distances in blocks, made
     * consistent: the shelf is at least as long as the shore ramp, the zones
     * never overlap, and every transition is at least a block wide.
     */
    private static double[] zoneBounds() {
        double shallowEnd = Math.max(Math.max(1, OceanConfig.getOceanShoreRampBlocks()), OceanConfig.getOceanShallowEndBlocks());
        double midStart = shallowEnd + Math.max(1, OceanConfig.getOceanShallowToMidBlocks());
        double deepStart = Math.max(midStart, OceanConfig.getOceanDeepStartBlocks());
        double deepFull = deepStart + Math.max(1, OceanConfig.getOceanMidToDeepBlocks());
        return new double[]{shallowEnd, midStart, deepStart, deepFull};
    }

    private static double smooth01(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }
}