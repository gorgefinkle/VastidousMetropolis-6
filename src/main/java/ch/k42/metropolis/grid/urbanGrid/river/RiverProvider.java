package ch.k42.metropolis.grid.urbanGrid.river;

import ch.k42.metropolis.grid.urbanGrid.WaterEdgeProvider;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import org.bukkit.util.noise.SimplexOctaveGenerator;

import java.util.Random;

/**
 * Computes river geometry as a pure function of world-chunk coordinates and
 * the world seed -- deliberately stateless, with no notion of "this tile"
 * or "that tile". Two different UrbanGrid tiles evaluating this at the same
 * world chunk X/Z always get the same answer, which is what lets a river
 * cross a 64x64 tile boundary correctly without either tile ever needing to
 * look at its neighbour (UrbanGrid's cross-tile lookups are read-only by
 * design -- see peekGrid()/peekParcel() -- so this has to be true by
 * construction, not by coordination).
 *
 * The river is modelled as a single meandering centerline z = spineZ(x),
 * i.e. it generally runs west-to-east across the world with a slow bend.
 * A tile where the local slope gets steep enough that the crossing reads
 * more like a north-south strip is handled by isEastWestOriented() rather
 * than by a second, independent spine function -- see its javadoc.
 *
 * AXIS AND ANCHORING: a RiverProvider is either X-bound (the default: it
 * runs west-to-east, centerline z = spine(x)) or Z-bound (it runs
 * north-to-south, centerline x = spine(z)). Everything below is written in
 * terms of an "along" coordinate (the axis the river runs along) and an
 * "across" coordinate (the one it meanders in), and the older X-bound
 * method names (spineZ(), spineZBlock(), ...) are kept as thin aliases so
 * existing callers are untouched. A provider can also be ANCHORED: told a
 * point the river has to pass through (UrbanGrid uses the ocean's center),
 * in which case the whole meander curve is shifted so it crosses exactly
 * that point, instead of the baseline being an independent random draw.
 *
 * Implements WaterEdgeProvider so RiverReclaim.compute() -- and, via that
 * same interface, the ocean's reclaim pass too, see OceanWaterEdgeProvider
 * -- can consume this without depending on the rest of this class's API;
 * waterEdgeNorthBlock()/waterEdgeSouthBlock() below already ARE that
 * interface's two methods, unchanged.
 */
public class RiverProvider implements WaterEdgeProvider, WaterCourseCarver {

   private final boolean enabled;
   // false: runs along X, meanders in Z. true: runs along Z, meanders in X.
   private final boolean zBound;
   // Baseline in the ACROSS coordinate, in chunks.
   private final double baseAcross;
   // Raw meander offset (chunks) at the anchor's along-position, subtracted
   // everywhere so the centerline passes exactly through baseAcross there.
   // 0 for an unanchored river, which then behaves exactly as before.
   private final double anchorOffset;
   private final SimplexOctaveGenerator meander;
   private final SimplexOctaveGenerator widthNoise;
   // Modulates slow/fast amplitude together over a much longer wavelength
   // than either -- see RiverConfig.getRiverMeanderIntensityFrequency()'s
   // javadoc. Salted separately so its own long-wavelength bends don't
   // correlate with the meander it's scaling.
   private final SimplexOctaveGenerator intensity;

   // ---- Creek mode (see forCreek()) -- all inert (false / 1.0 / NaN / infinite) for the main river ----
   // A creek IS a river: the same spine, the same meander noise and the same RiverConfig
   // amplitudes/frequencies. It differs only in what it is constructed with: an exact anchor at
   // the origin wall, a meander scale (the creek's width relative to the narrowest real river:
   // amplitude x scale, frequency / scale, so the whole bend pattern shrinks geometrically), a
   // FIXED width instead of the noise-bucketed one, and a bounded along-range instead of running
   // the whole world. Everything else -- distance, water-edge, bank maths -- is this class's own.
   private final boolean creekMode;
   private final double meanderScale; // 1.0 for a main river: every use below is then an exact no-op
   private final double creekWidthBlocks;
   // Meander is faded in from 0 over this many chunks either side of the anchor, so the centerline
   // leaves the origin wall dead straight instead of already mid-bend. 0 = no fade.
   private final double creekAnchorAlongChunk;
   private final double creekRampChunks;
   // The meander's own value at the anchor. A creek measures its bend as the CHANGE from this, so
   // fading the bend in from the anchor never means swinging sideways by the noise's absolute value
   // there (which, at river-scale amplitude, is several chunks). 0 for a main river.
   private final double creekAnchorMeander;
   // Inclusive chunk range along the axis this provider is valid over (creek mode only).
   private final double alongLoChunk, alongHiChunk;
   // Per-carrier bank-edge samples (creek mode only; a main river keeps its own in UrbanGrid's
   // tile-wide arrays) -- see recordBankEdges().
   private double[] bankEdgeNegativeBlocks;
   private double[] bankEdgePositiveBlocks;
   /** Resolution, in blocks along the axis, of the bank-edge samples recordBankEdges() stores -- one per chunk. */
   public static final double BANK_EDGE_SAMPLE_STRIDE_BLOCKS = 16.0;

   public RiverProvider(long worldSeed) {
      this(worldSeed, false, Double.NaN, Double.NaN);
   }

   /**
    * The meander scale for a creek of the given width: its width relative to the NARROWEST real
    * river bucket, clamped to (0.05, 1.0] so a creek can never meander harder than the river
    * itself and a strange width can never collapse or explode the bend.
    */
   public static double creekMeanderScale(double widthBlocks) {
      double reference = Math.max(1.0, RiverConfig.getRiverWidthNarrow() * 16.0);
      return Math.max(0.05, Math.min(1.0, widthBlocks / reference));
   }

   /**
    * A creek: a river whose centerline passes EXACTLY through (anchorAlongBlock, anchorAcrossBlock)
    * -- world BLOCK coordinates, no tile-edge remap, no chunk snapping -- with the river's own
    * meander shrunk by meanderScale, a fixed width, and valid only for along chunks in
    * [alongLoChunk, alongHiChunk]. Outside that range every query reports "no water here"
    * (false / NaN), exactly like a disabled river does.
    *
    * worldSeed should already be mixed with whatever makes this creek unique -- this class only
    * salts by axis.
    */
   public static RiverProvider forCreek(long worldSeed, boolean zBound, double anchorAlongBlock, double anchorAcrossBlock,
                                        double meanderScale, double rampChunks, double widthBlocks,
                                        double alongLoChunk, double alongHiChunk) {
      return new RiverProvider(worldSeed, zBound, anchorAlongBlock / 16.0, anchorAcrossBlock / 16.0, true,
              meanderScale, rampChunks, widthBlocks, alongLoChunk, alongHiChunk);
   }

   /**
    * @param zBound            false = X-bound (runs west-east), true = Z-bound (runs north-south)
    * @param anchorAlongChunk  world chunk coordinate, ALONG the river's axis, at which it must pass
    *                          anchorAcrossChunk -- NaN (either one) leaves the river unanchored
    * @param anchorAcrossChunk world chunk coordinate, ACROSS the river's axis, that the centerline
    *                          must be at (or, after the tile-edge remap below, very near) at
    *                          anchorAlongChunk
    */
   public RiverProvider(long worldSeed, boolean zBound, double anchorAlongChunk, double anchorAcrossChunk) {
      this(worldSeed, zBound, anchorAlongChunk, anchorAcrossChunk, false,
              1.0, 0.0, Double.NaN,
              Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
   }

   private RiverProvider(long worldSeed, boolean zBound, double anchorAlongChunk, double anchorAcrossChunk, boolean creekMode,
                         double meanderScale, double rampChunks,
                         double widthBlocks, double alongLoChunk, double alongHiChunk) {
      this.enabled = RiverConfig.isRiverEnabled();
      this.zBound = zBound;
      // Creek fields FIRST: spineChunksRaw() below reads them.
      this.creekMode = creekMode;
      this.meanderScale = meanderScale;
      this.creekRampChunks = rampChunks;
      this.creekWidthBlocks = widthBlocks;
      this.creekAnchorAlongChunk = anchorAlongChunk; // chunks, fractional: the exact anchor position
      this.alongLoChunk = alongLoChunk;
      this.alongHiChunk = alongHiChunk;
      // A Z-bound river gets every noise stream salted differently from the
      // X-bound one, so the two rivers never share a meander. For the
      // X-bound river the salt is 0, i.e. the seeds are exactly what they
      // always were.
      long axisSalt = zBound ? 0x5A5A5A5A5A5A5A5AL : 0L;
      // Salted away from worldSeed so this never shares an identical noise
      // stream with any other Simplex generator seeded directly off it
      // elsewhere in generation (context zoning, terrain fallback, etc).
      this.meander = new SimplexOctaveGenerator((worldSeed ^ 0x5249564552_4C4EL) ^ axisSalt, 2);
      this.widthNoise = new SimplexOctaveGenerator((worldSeed ^ 0x5249564552_574CL) ^ axisSalt, 2);
      this.intensity = new SimplexOctaveGenerator((worldSeed ^ 0x5249565F494E54L) ^ axisSalt, 2);
      this.meander.setScale(1.0);
      this.widthNoise.setScale(1.0);
      this.intensity.setScale(1.0);
      this.creekAnchorMeander = (creekMode && !Double.isNaN(anchorAlongChunk)) ? meanderSum(anchorAlongChunk * 16.0) : 0.0;

      boolean anchored = !Double.isNaN(anchorAlongChunk) && !Double.isNaN(anchorAcrossChunk);
      if (anchored) {
         // The baseline is the anchor's own across-coordinate, nudged by the
         // same tile-edge remap an unanchored river gets (so the river never
         // hugs a tile boundary) -- at most a few chunks off the anchor.
         // A creek's anchor is exact: it MUST leave the origin wall on the wall, so no tile-edge remap.
         // An anchored river passes EXACTLY through its anchor (the ocean's centre): that is what makes it converge. It used
         // to be nudged into the safe band of the tile its anchor fell in, which only exists to keep an unanchored river off
         // a tile edge, and which pushed it up to margin chunks off the ocean it was meant to run into (the river ran
         // alongside the sea instead of into it). There is no tile edge to avoid any more, so the anchor is taken as it is.
         this.baseAcross = anchorAcrossChunk;
         this.anchorOffset = creekMode
                 ? spineChunksRaw(anchorAlongChunk * 16.0, 0.0)
                 : spineChunksRaw(anchorAlongChunk * 16.0 + 8.0, 0.0);
      } else {
         // One deterministic draw to place the river somewhere other than
         // dead-center on every single world -- same seed always reproduces
         // the same placement. Only reached when the river is NOT anchored on
         // the ocean (convergeOnOcean off, or the ocean disabled); there is
         // no separate river range any more, the ocean's own
         // maxDistanceFromSpawn (+/- chunks from world origin) governs this
         // baseline too.
         int range = Math.max(1, OceanConfig.getOceanMaxDistanceFromSpawn());
         long rawSalt = zBound ? 0x5249565F5845524FL : 0x5249565F5A45524FL;
         int rawBase = new Random(worldSeed ^ rawSalt).nextInt(range * 2) - range;
         this.baseAcross = keepBaselineAwayFromTileEdges(rawBase);
         this.anchorOffset = 0.0;
      }
   }

   /** True for the north-south river, false for the west-east one. */
   public boolean isZBound() {
      return zBound;
   }

   public boolean isEnabled() {
      return enabled;
   }

   /**
    * See WaterCourseCarver.getFallbackBankMarginBlocks()'s own javadoc for
    * why this is a per-carrier value rather than a shared constant.
    */
   @Override
   public double getFallbackBankMarginBlocks() {
      return RiverConfig.getRiverBankMargin() * 16.0;
   }

   @Override
   public double getRampMaxRunBlocks() {
      int maxRun = RiverConfig.getRiverBankMaxRunChunks();
      return maxRun > 0 ? maxRun * 16.0 : 0;
   }

   /**
    * See WaterCourseCarver.getBankEdgeBlockAt()'s own javadoc -- must
    * return a DISTANCE from centerline, not the raw world coordinate
    * UrbanGrid's arrays actually store; converts via this river's own
    * spineXBlock()/spineZBlock() (safe here, unlike doing the same thing
    * generically in RiverParcel.bankRampEnd(), because a RiverProvider's
    * own along-axis genuinely IS world X or Z).
    */
   @Override
   public double getBankEdgeBlockAt(ch.k42.metropolis.grid.urbanGrid.UrbanGrid grid, double worldBlockX, double worldBlockZ, boolean negativeSide) {
      if (creekMode) {
         return creekBankEdgeAt(zBound ? worldBlockZ : worldBlockX, negativeSide);
      }
      if (grid == null) {
         return Double.NaN;
      }
      double edge;
      double spine;
      if (zBound) {
         edge = grid.getBankEdgeBlockX((int) Math.round(worldBlockZ), negativeSide);
         spine = spineXBlock(worldBlockZ);
      } else {
         edge = grid.getBankEdgeBlockZ((int) Math.round(worldBlockX), negativeSide);
         spine = spineZBlock(worldBlockX);
      }
      if (Double.isNaN(edge)) {
         return Double.NaN;
      }
      return negativeSide ? (spine - edge) : (edge - spine);
   }

   /** The baseline in the across coordinate, in chunks (test/diagnostic accessor). */
   public double getBaseAcrossChunk() {
      return baseAcross;
   }

   /**
    * Remaps a raw baseZ so its position WITHIN its 64-chunk tile (i.e.
    * baseZ mod 64) never lands within riverTileEdgeMargin chunks of that
    * tile's Z-edge -- see RiverConfig.getRiverTileEdgeMargin()'s javadoc
    * for why. Preserves which tile-row baseZ coarsely falls in (the
    * multiple-of-64 part is untouched); only the local offset within that
    * row is rescaled into the safe center band. Deterministic and a pure
    * function of the input, same as everything else here -- two tiles
    * evaluating this see the same baseZ regardless of which one computes
    * it first.
    */
   private static double keepBaselineAwayFromTileEdges(int raw) {
      int margin = RiverConfig.getRiverTileEdgeMargin();
      if (margin <= 0) {
         return raw;
      }
      int tileSize = 64;
      margin = Math.min(margin, (tileSize - 1) / 2); // never collapse the safe band to nothing
      int mod = ((raw % tileSize) + tileSize) % tileSize; // 0..63, raw's offset within its tile row
      int safeSpan = tileSize - 2 * margin;
      int remapped = margin + (mod * safeSpan) / tileSize; // rescaled into [margin, tileSize-margin]
      return raw - mod + remapped;
   }

   /**
    * Center Z (in chunks, may be fractional) of the river's meander at a
    * given world chunk X. Thin wrapper around spineZBlock() sampled at
    * this chunk's center column, so chunk-granularity carving decisions
    * (UrbanGrid) and block-granularity rendering (RiverParcel,
    * BridgeParcel) are always reading the same underlying curve rather
    * than two independently-tuned approximations of it.
    *
    * (For a Z-bound provider this is the X of the centerline at a given
    * chunk Z -- see spineAcross().)
    */
   public double spineZ(int worldChunkX) {
      return spineAcross(worldChunkX);
   }

   /** Axis-neutral name for spineZ(): the across-coordinate of the centerline, in chunks, at an along chunk. */
   public double spineAcross(int alongChunk) {
      if (!enabled) {
         return Double.NaN;
      }
      return spineBlock(alongChunk * 16.0 + 8.0) / 16.0;
   }

   /** X-bound alias of spineBlock(): the centerline's block Z at a block X. */
   public double spineZBlock(double worldBlockX) {
      return spineBlock(worldBlockX);
   }

   /** Z-bound alias of spineBlock(): the centerline's block X at a block Z. */
   public double spineXBlock(double worldBlockZ) {
      return spineBlock(worldBlockZ);
   }

   /**
    * Same centerline as spineZ(), but sampled continuously in BLOCK
    * coordinates and returned in block units. This is what gives the
    * river a smooth, non-staircased edge in-world even though the
    * district carver only ever reasons about it at chunk resolution --
    * RiverParcel/BridgeParcel sample this once per column (block) when
    * painting the actual bed/bank/water, entirely independent of the
    * chunk-level isRiver() staircase used for carving.
    *
    * alongBlock is the block coordinate along the river's axis (X for an
    * X-bound river, Z for a Z-bound one); the result is the centerline's
    * block coordinate across it.
    */
   public double spineBlock(double alongBlock) {
      if (!enabled) {
         return Double.NaN;
      }
      if (RiverConfig.isRiverStraight()) {
         return baseAcross * 16.0;
      }
      return (spineChunksRaw(alongBlock, baseAcross) - anchorOffset) * 16.0;
   }

   /**
    * base + the meander's slow bend + its fast wobble, in chunks. Split out
    * of spineBlock() so an anchored river can evaluate the meander alone
    * (base = 0) once at its anchor and subtract that everywhere. The sum is
    * deliberately (base + slow) + fast, left to right, exactly as it was
    * before anchoring existed: an unanchored river's anchorOffset is 0, and
    * subtracting 0.0 is exact, so its spine is bit-for-bit what it always was.
    */
   private double spineChunksRaw(double alongBlock, double base) {
      double along = alongBlock / 16.0;
      if (!creekMode) {
         // The main river: exactly its original expression.
         double intensityNoise = intensity.noise(along * RiverConfig.getRiverMeanderIntensityFrequency(), 1000, 0.5, 0.5, true);
         double intensityMin = RiverConfig.getRiverMeanderIntensityMin();
         double intensityFactor = intensityMin + (1.0 - intensityMin) * (intensityNoise + 1.0) / 2.0;
         double slow = meander.noise(along * RiverConfig.getRiverMeanderSlowFrequency(), 0, 0.5, 0.5, true)
                 * RiverConfig.getRiverMeanderSlowAmplitude() * intensityFactor;
         double fast = meander.noise(along * RiverConfig.getRiverMeanderFastFrequency(), 500, 0.5, 0.5, true)
                 * RiverConfig.getRiverMeanderFastAmplitude() * intensityFactor;
         return base + slow + fast;
      }
      // A creek: the SAME meander (same noise, same RiverConfig amplitudes and frequencies), shrunk
      // geometrically by meanderScale (see meanderSum()), measured as the change from its value at
      // the anchor and faded in over creekRampChunks so it leaves the origin wall dead straight.
      return base + creekRamp(along) * (meanderSum(alongBlock) - creekAnchorMeander);
   }

   /**
    * slow + fast meander, in chunks, at this along position. For a creek every amplitude is scaled by
    * meanderScale and every frequency divided by it (the whole bend pattern shrinks geometrically);
    * a main river never gets here with anything but scale 1.0. The intensity modulation stays, on the
    * same scaled footing, so stretches of one creek still read as curvier or straighter than others.
    * See RiverConfig.getRiverMeander*() for what the amplitudes and frequencies mean.
    */
   private double meanderSum(double alongBlock) {
      double along = alongBlock / 16.0;
      double scale = meanderScale;
      double intensityNoise = intensity.noise(along * (RiverConfig.getRiverMeanderIntensityFrequency() / scale), 1000, 0.5, 0.5, true);
      double intensityMin = RiverConfig.getRiverMeanderIntensityMin();
      double intensityFactor = intensityMin + (1.0 - intensityMin) * (intensityNoise + 1.0) / 2.0;
      double slow = meander.noise(along * (RiverConfig.getRiverMeanderSlowFrequency() / scale), 0, 0.5, 0.5, true)
              * (RiverConfig.getRiverMeanderSlowAmplitude() * scale) * intensityFactor;
      double fast = meander.noise(along * (RiverConfig.getRiverMeanderFastFrequency() / scale), 500, 0.5, 0.5, true)
              * (RiverConfig.getRiverMeanderFastAmplitude() * scale) * intensityFactor;
      return slow + fast;
   }

   /**
    * Padding, in chunks, needed on EACH side of an already-sampled local
    * spine range (minSpine..maxSpine, from directly evaluating spineZ()
    * at every X column in the range of interest -- see
    * UrbanGrid.placeCityDistricts()) to guarantee the corridor contains
    * every actual river/bank cell in that range.
    *
    * Deliberately does NOT include meander amplitude: that only matters
    * for a caller trying to bound how far the spine COULD be from baseZ
    * without ever evaluating spineZ() itself. placeCityDistricts()
    * doesn't need that -- it already calls spineZ() at every column in
    * the tile, so minSpine/maxSpine is the exact true range for this
    * tile, not an estimate. Padding an already-exact local measurement
    * with the FULL GLOBAL meander amplitude (the most the spine could
    * ever drift from baseZ anywhere in the entire world) double-counts
    * the meander and inflates the corridor far beyond the river's actual
    * local footprint -- e.g. a tile where the sampled spine only moves
    * ~2 chunks still got a ~50-chunk-tall corridor once the (now-removed)
    * 21-chunk meander term was added on each side, swallowing most of
    * the tile and spilling into neighbouring ones. All that's actually
    * needed beyond the sampled range is:
    *  - half of the widest width bucket (RiverConfig.getRiverWidthWide()),
    *    since widthAt() can pick the wide bucket at any X independently
    *    of where the meander currently is;
    *  - + RiverConfig.getRiverBankMargin(), since isRiverOrBank() (what
    *    the corridor actually has to contain) extends that far past the
    *    open-water width band on both sides.
    *
    * Returns 0 when the river is disabled, so callers that gate on
    * "envelope <= 0" correctly fall back to ordinary carving.
    */
   public double getEnvelopeHalfWidth() {
      if (!enabled) {
         return 0.0;
      }
      // A creek's water is a fixed width, so its corridor is that half-width plus the river's own bank margin.
      double maxHalfWidth = creekMode ? creekWidthBlocks / 32.0 : RiverConfig.getRiverWidthWide() / 2.0;
      return maxHalfWidth + RiverConfig.getRiverBankMargin();
   }

   /**
    * Odd-chunk width bucket (3/5/7). Bucketed rather than continuous so a
    * small, hand-tuned set of bank/bridge-span treatments can cover every
    * width the river actually produces, instead of needing one per exact
    * value.
    */
   public int widthAt(int worldChunkX) {
      if (creekMode) {
         return Math.max(1, (int) Math.ceil(creekWidthBlocks / 16.0));
      }
      double n = widthNoise.noise(worldChunkX * 0.01, 0, 0.5, 0.5, true);
      if (n < -0.2) {
         return RiverConfig.getRiverWidthNarrow();
      } else if (n < 0.4) {
         return RiverConfig.getRiverWidthMedium();
      } else {
         return RiverConfig.getRiverWidthWide();
      }
   }

   /**
    * Same three width buckets as widthAt(), but sampled continuously in
    * BLOCK coordinates and blended smoothly across each threshold instead
    * of snapping -- the width counterpart to what spineZBlock() already
    * does for the centerline (see its javadoc). widthAt() flooring
    * worldBlockX to a whole chunk before bucketing is exactly what made
    * carveColumn()'s open-water edge jump a full bucket's width (up to
    * getRiverWidthWide()-getRiverWidthNarrow() chunks, i.e. tens of
    * blocks) in a single 16-block chunk column the moment the underlying
    * noise crossed -0.2 or 0.4 -- the noise itself moves gradually there
    * (0.01 chunk-frequency), only the bucketing was instant.
    *
    * WIDTH_BLEND_HALF is how far to each side of a threshold, in the
    * SAME noise units as the -0.2/0.4 cutoffs, the ramp runs; a
    * smoothstep (not a linear lerp) is used across it so the width's
    * rate of change is itself continuous, not just the width -- a linear
    * ramp still has a visible kink where it starts/ends.
    */
   private static final double WIDTH_BLEND_HALF = 0.05;

   public double widthAtBlock(double worldBlockX) {
      if (!enabled) {
         return Double.NaN;
      }
      if (creekMode) {
         return creekWidthBlocks;
      }
      double n = widthNoise.noise(worldBlockX / 16.0 * 0.01, 0, 0.5, 0.5, true);
      double narrow = RiverConfig.getRiverWidthNarrow() * 16.0;
      double medium = RiverConfig.getRiverWidthMedium() * 16.0;
      double wide = RiverConfig.getRiverWidthWide() * 16.0;
      return blendedWidth(n, narrow, medium, wide);
   }

   private static double blendedWidth(double n, double narrow, double medium, double wide) {
      double lowLo = -0.2 - WIDTH_BLEND_HALF, lowHi = -0.2 + WIDTH_BLEND_HALF;
      double highLo = 0.4 - WIDTH_BLEND_HALF, highHi = 0.4 + WIDTH_BLEND_HALF;
      if (n < lowLo) {
         return narrow;
      } else if (n < lowHi) {
         return lerp(narrow, medium, smoothstep((n - lowLo) / (lowHi - lowLo)));
      } else if (n < highLo) {
         return medium;
      } else if (n < highHi) {
         return lerp(medium, wide, smoothstep((n - highLo) / (highHi - highLo)));
      } else {
         return wide;
      }
   }

   // ------------------------------------------------------------------ creek-mode carrier hooks

   /** True for a creek (bounded, fixed-width, exactly anchored); false for a main river. */
   public boolean isCreek() {
      return creekMode;
   }

   /** Creek mode: half the fixed water width, in blocks. */
   public double getHalfWidthBlocks() {
      return creekWidthBlocks / 2.0;
   }

   /** Creek mode: smaller end of the valid along-range, in world chunks. */
   public int getAlongLoChunk() {
      return (int) alongLoChunk;
   }

   /** Creek mode: larger end of the valid along-range, in world chunks. */
   public int getAlongHiChunk() {
      return (int) alongHiChunk;
   }

   /** Creek mode: how many along-axis samples recordBankEdges() needs -- one per chunk of the range. */
   public int getBankEdgeSampleCount() {
      return creekMode ? (int) (alongHiChunk - alongLoChunk) + 1 : 0;
   }

   /** Creek mode: world block point of the centerline at sample index i (that along-chunk's centre), as {x, z}. */
   public double[] bankEdgeSamplePoint(int sampleIndex) {
      double along = ((int) alongLoChunk + sampleIndex) * 16.0 + 8.0;
      double across = spineBlock(along);
      return zBound ? new double[]{across, along} : new double[]{along, across};
   }

   /** Creek mode: world block point stepsFromCenter whole chunks out from that sample, across the axis; negative = north/west side. */
   public double[] bankEdgeScanPoint(int sampleIndex, int stepsFromCenter) {
      double[] c = bankEdgeSamplePoint(sampleIndex);
      double off = stepsFromCenter * BANK_EDGE_SAMPLE_STRIDE_BLOCKS;
      return zBound ? new double[]{c[0] + off, c[1]} : new double[]{c[0], c[1] + off};
   }

   /** Creek mode: stores the smoothed real-edge distances (from the centerline) UrbanGrid measured off the final parcel grid. */
   public void recordBankEdges(double[] negativeBlocks, double[] positiveBlocks) {
      this.bankEdgeNegativeBlocks = negativeBlocks;
      this.bankEdgePositiveBlocks = positiveBlocks;
   }

   private double creekBankEdgeAt(double alongBlock, boolean negativeSide) {
      double[] arr = negativeSide ? bankEdgeNegativeBlocks : bankEdgePositiveBlocks;
      if (arr == null) {
         return Double.NaN;
      }
      int idx = (int) Math.floor(alongBlock / BANK_EDGE_SAMPLE_STRIDE_BLOCKS) - (int) alongLoChunk;
      return (idx < 0 || idx >= arr.length) ? Double.NaN : arr[idx];
   }

   /** 0 at the creek's anchor, smoothly up to 1 at creekRampChunks away (either side); always 1 when there's no ramp. */
   private double creekRamp(double alongChunks) {
      if (creekRampChunks <= 0.0) {
         return 1.0;
      }
      return smoothstep(Math.abs(alongChunks - creekAnchorAlongChunk) / creekRampChunks);
   }

   /** Creek mode only: is this along BLOCK coordinate inside the range this creek is valid over? Always true for a river. */
   private boolean alongBlockInBounds(double alongBlock) {
      return !creekMode || (alongBlock >= alongLoChunk * 16.0 && alongBlock <= (alongHiChunk + 1.0) * 16.0);
   }

   private static double lerp(double a, double b, double t) {
      return a + (b - a) * t;
   }

   /** Classic Hermite smoothstep: flat (zero slope) at both t=0 and t=1, so the ramp has no kink where it meets the unblended buckets on either side. */
   private static double smoothstep(double t) {
      t = Math.max(0.0, Math.min(1.0, t));
      return t * t * (3.0 - 2.0 * t);
   }

   /**
    * Northernmost (smallest) block Z the river's OPEN WATER surface
    * reaches anywhere within chunk column worldChunkX -- the true
    * waterline for that column, not an estimate and not a chunk-snapped
    * approximation of one.
    *
    * This is what "define its edge by where the water is sitting" means
    * in practice, and it's why RiverReclaim can safely reclaim ground the
    * chunk-level isRiverOrBank() envelope reserves: that envelope is a
    * worst case across the whole tile, this is the actual measured extent
    * at this X.
    *
    * Sampled at every one of the chunk's 16 block columns PLUS the first
    * column of the next chunk (lx <= 16, deliberately not < 16). Both the
    * meander and the blended width bucket move continuously across a
    * chunk, so the widest point in a column is rarely its center and can
    * sit exactly on its far boundary; sampling the shared edge from both
    * sides means two adjacent chunk columns can never disagree about the
    * waterline running between them, which is what would otherwise let a
    * one-block sliver of water fall outside every column's measurement.
    */
   @Override
   public double waterEdgeNorthBlock(int worldChunkX) {
      if (!enabled || !alongBlockInBounds(worldChunkX * 16.0 + 8.0)) {
         return Double.NaN;
      }
      double min = Double.MAX_VALUE;
      int baseX = worldChunkX << 4;
      for (int lx = 0; lx <= 16; lx++) {
         double bx = baseX + lx;
         min = Math.min(min, spineBlock(bx) - widthAtBlock(bx) / 2.0);
      }
      return min;
   }

   /** South-bank mirror of waterEdgeNorthBlock() -- see its javadoc. */
   @Override
   public double waterEdgeSouthBlock(int worldChunkX) {
      if (!enabled || !alongBlockInBounds(worldChunkX * 16.0 + 8.0)) {
         return Double.NaN;
      }
      double max = -Double.MAX_VALUE;
      int baseX = worldChunkX << 4;
      for (int lx = 0; lx <= 16; lx++) {
         double bx = baseX + lx;
         max = Math.max(max, spineBlock(bx) + widthAtBlock(bx) / 2.0);
      }
      return max;
   }

   /**
    * Block-resolution counterpart to distanceFromCenter(), used by
    * RiverParcel/BridgeParcel. Signed distance from the centerline in
    * WORLD block coordinates: for an X-bound river, Z minus the spine's Z
    * (negative = north); for a Z-bound river, X minus the spine's X
    * (negative = west).
    */
   public double distanceFromCenterBlock(double worldBlockX, double worldBlockZ) {
      if (creekMode && !alongBlockInBounds(zBound ? worldBlockZ : worldBlockX)) {
         return Double.NaN;
      }
      return zBound ? worldBlockX - spineBlock(worldBlockZ) : worldBlockZ - spineBlock(worldBlockX);
   }

   /** True if this exact chunk falls inside the river's current width band. */
   public boolean isRiver(int worldChunkX, int worldChunkZ) {
      if (!enabled) {
         return false;
      }
      int along = zBound ? worldChunkZ : worldChunkX;
      int across = zBound ? worldChunkX : worldChunkZ;
      if (creekMode) {
         double alongBlock = along * 16.0 + 8.0;
         return alongBlockInBounds(alongBlock)
                 && Math.abs(across * 16.0 + 8.0 - spineBlock(alongBlock)) <= creekWidthBlocks / 2.0 + 8.0; // +8: half a chunk, so a chunk that merely overlaps the water counts
      }
      double half = widthAt(along) / 2.0;
      return Math.abs(across - spineAcross(along)) <= half;
   }

   /**
    * True if this chunk falls inside the river's OPEN WATER width band, OR
    * within riverBankMargin chunks beyond it -- i.e. the full water+bank
    * footprint, not just open water.
    *
    * This is the predicate UrbanGrid actually carves/reserves against, and
    * the one that decides which chunks get a RiverParcel placed on them at
    * all. Using the narrower isRiver() for BOTH of those was the reason
    * banks looked like they abruptly stopped mid-gradient on some sides:
    * RiverParcel.carveColumn() already blends smoothly from open water out
    * to bankMargin blocks of sand/gravel/grass, based on continuous
    * per-block distance from the spine -- but carveColumn() only ever RUNS
    * on chunks that got a RiverParcel in the first place. A chunk sitting
    * entirely within the bank's blend zone but outside the raw width band
    * never got a RiverParcel, so its share of the gradient was simply
    * never painted -- the blend hit that chunk's border and stopped dead,
    * with whatever ordinary land content happened to be next door
    * providing zero transition. Carving and placement against this wider
    * predicate instead means every chunk that's supposed to carry part of
    * the bank gradient actually gets the parcel that paints it, on
    * whichever side it falls on.
    */
   public boolean isRiverOrBank(int worldChunkX, int worldChunkZ) {
      if (!enabled) {
         return false;
      }
      int along = zBound ? worldChunkZ : worldChunkX;
      int across = zBound ? worldChunkX : worldChunkZ;
      if (creekMode) {
         double alongBlock = along * 16.0 + 8.0;
         return alongBlockInBounds(alongBlock)
                 && Math.abs(across * 16.0 + 8.0 - spineBlock(alongBlock)) <= creekWidthBlocks / 2.0 + RiverConfig.getRiverBankMargin() * 16.0 + 8.0;
      }
      double half = widthAt(along) / 2.0 + RiverConfig.getRiverBankMargin();
      return Math.abs(across - spineAcross(along)) <= half;
   }

   /**
    * Signed distance (in chunks) from worldChunkZ to the river's
    * centerline at worldChunkX. Negative = north of center, positive =
    * south. RiverParcel uses this to blend bed -> bank -> dry land instead
    * of a hard on/off edge.
    */
   public double distanceFromCenter(int worldChunkX, int worldChunkZ) {
      if (creekMode) {
         // Chunk-centre to centerline, in chunks, NaN outside this creek's range.
         return distanceFromCenterBlock(worldChunkX * 16.0 + 8.0, worldChunkZ * 16.0 + 8.0) / 16.0;
      }
      return zBound ? worldChunkX - spineAcross(worldChunkZ) : worldChunkZ - spineAcross(worldChunkX);
   }

   /**
    * True when the river's local slope through worldChunkX is shallow
    * enough to treat the crossing as a west-to-east strip (the district
    * carver should cap the Z dimension); false when the slope is steep
    * enough that an X-axis strip (cap X instead) is the better fit. Sampled
    * over an 8-chunk window centered on worldChunkX -- wide enough to
    * average out per-chunk noise jitter, narrow enough to still react to a
    * genuine bend.
    */
   public boolean isEastWestOriented(int worldChunkX) {
      if (!enabled) {
         return true;
      }
      double slope = spineAcross(worldChunkX + 4) - spineAcross(worldChunkX - 4);
      return Math.abs(slope) < 8.0;
   }
}