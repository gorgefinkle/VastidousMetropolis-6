package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.minions.Cartesian3D;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.config.AbstractSchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.StreetParcel;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.parcel.TerrainFallbackParcel;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class District implements IDistrict {
    private final int MAX_ITERATIONS = PluginConfig.getIterations();
    private Cartesian2D base;
    private Cartesian2D size;
    private UrbanGrid grid;      // only to build parcels
    private DistrictHost host;   // the ground being filled and the dice
    private ContextType context;

    // Set by UrbanGrid before the fillDistrict() loop when
    // generator.districtNeighborRules is on (see DistrictContextAssigner).
    // Null means "sample the noise lookup at base", the original behaviour.
    private ContextType assignedContext = null;
    // Set only for a landmark district (see initForcedDistrict()). When
    // non-null, fillDistrict() places this single clip directly across the
    // whole base/size footprint instead of running the normal random
    // partition -- the clip and its size were already matched and carved
    // to fit by UrbanGrid.tryPlaceLandmark() before this district was ever
    // created, so there's nothing left to search for.
    private Clipboard forcedClip;
    // Direction confirmed valid by UrbanGrid.carveLandmark() at carve time
    // -- see IDistrict.initForcedDistrict() for why this must be reused
    // as-is rather than recomputed via findRoad() once base/size already
    // equal this district's own footprint.
    private Direction forcedDirection;
    // Not final, unlike VARIANTRADIUS/VARIANTPENALTY/etc. below -- its real
    // value depends on this.context, which isn't known until fillDistrict()
    // actually resolves it (see the assignment there). Defaults to the
    // ordinary cloneRadius purely so the field has a sane value before
    // fillDistrict() runs; nothing reads it before then.
    private int CLONERADIUS = PluginConfig.getCloneRadius();
    private final int VARIANTRADIUS = PluginConfig.getVariantRadius();
    private final int VARIANTPENALTY = PluginConfig.getVariantPenalty();
    // Per-attempt roll (see placeRandomBuild(base,size,previous)) -- NOT a
    // per-district guarantee. A rectangle that's too small to subdivide
    // further only gets ONE roll total, so this being < 100 (default 90)
    // used to mean roughly that percentage of small districts came out
    // completely empty by pure chance, sitting right next to identically
    // sized districts that happened to pass -- see nextRecPartition()'s
    // degraded-split fallback for the fix (every subdivision gets its own
    // independent roll instead of one all-or-nothing attempt).
    private int BUILD_CHANCE = PluginConfig.getBuildChance();

    // Set the first time a BUILD schematic gets placed in a DENSERESIDENTIAL
    // district (see placeRandomBuild()), to whichever schematic's
    // SchematicConfig.getDenseResidentialFamilyKey() that was -- normally
    // the content hash of the CONFIG.json it came from (so every schematic
    // listed under that one config all share this key), but a config can
    // opt into sharing a key with OTHER, separately-authored configs via
    // its variantGroup tag -- see getDenseResidentialFamilyKey(). Every later BUILD placement in this same
    // district gets filtered down to clips sharing this key, so one
    // DENSERESIDENTIAL development reads as "block after block of the same
    // house", never a mix (a bungalow street never grows a duplex). FILLER
    // is untouched by this -- see the DENSERESIDENTIAL comment in
    // ClipboardDAO.storeClipboard() for why filler stays generic. Reset to
    // null at the top of each MAX_ITERATIONS pass in fillDistrict(), so a
    // discarded iteration's lock never leaks into the next attempt.
    private String denseResidentialFamilyKey = null;

    // Lightweight per-district counters, reset at fillDistrict() entry.
    // Explain 0%-coverage fallbacks without per-candidate log spam.
    private long diagnosticBuildRollFailed;
    private long diagnosticBuildPoolEmpty;
    private long diagnosticBuildCandidatesSelected;
    private long diagnosticBuildDuplicateRejected;
    private long diagnosticBuildVariantRejected;
    private long diagnosticVariantRadiusRejected;
    private long diagnosticVariantDistrictRejected;
    private long diagnosticBuildNoRoadRequests;
    private long diagnosticBuildNoRoadEmptyPools;
    private long diagnosticBuildAccepted;
    private long diagnosticBuildInvalidFootprint;

    @Override
    public Cartesian2D getBase() {
        return this.base;
    }

    @Override
    public ContextType getContext() {
        return this.context != null ? this.context : this.assignedContext;
    }

    @Override
    public void assignContext(ContextType context) {
        this.assignedContext = context;
    }

    @Override
    public Cartesian2D getSize() {
        return this.size;
    }

    /**
     * Widens this district by dx chunks eastward and dz chunks southward, keeping its base. Only meant for
     * UrbanGrid.absorbSeamRows(), which hands a district the spare edge row of its tile before anything is
     * filled; never call it once fillDistrict() has run.
     */
    // Which sides of this district have a street (or highway) along them. Always true for a district that was
    // carved on its own; a block of a city that is cut off by a tile edge is only part of the real block, and
    // the cut is not a street, so the side along it is switched off here and no building turns to face it.
    private boolean roadNorth = true;
    private boolean roadSouth = true;
    private boolean roadEast = true;
    private boolean roadWest = true;
    // Pass05 flags retained without disabling the existing normal filler
    // sweep: buildable districts must still use real schematic fillers.
    private boolean creekReclaimSchematicFill = false;
    private boolean strictPortBoundary = false;

    public void setCreekReclaimSchematicFill(boolean value) { this.creekReclaimSchematicFill = value; }
    public boolean isCreekReclaimSchematicFill() { return this.creekReclaimSchematicFill; }
    public void setStrictPortBoundary(boolean value) { this.strictPortBoundary = value; }


    private boolean fitsDistrict(ClipboardParcel p) {
        return p != null && p.getChunkX() >= this.base.X
                && p.getChunkZ() >= this.base.Y
                && p.getChunkSizeX() > 0 && p.getChunkSizeZ() > 0
                && (long)p.getChunkX() + p.getChunkSizeX() <= (long)this.base.X + this.size.X
                && (long)p.getChunkZ() + p.getChunkSizeZ() <= (long)this.base.Y + this.size.Y;
    }

    /** A street-facing schematic is legal only when every chunk of its actual
     * frontage touches a REAL street, not just a guessed district edge.
     *
     * Ordinary city blocks use the world-wide street plan; unlike querying
     * neighboring tiles this is independent of chunk/tile generation order.
     * Port private access lanes are not public building frontage; only
     * existing StreetParcel streets may qualify a normal schematic.
     * This check is about SCHEMATIC placement only: non-road-facing fillers
     * remain free to occupy interior lots.
     */
    private boolean hasRealRoadFrontage(Cartesian2D at, Cartesian2D footprint, Direction facing) {
        if (facing == Direction.NONE || footprint.X <= 0 || footprint.Y <= 0) return false;
        int dx = facing == Direction.WEST ? -1 : facing == Direction.EAST ? 1 : 0;
        int dz = facing == Direction.NORTH ? -1 : facing == Direction.SOUTH ? 1 : 0;
        int fixedX = dx < 0 ? at.X - 1 : at.X + footprint.X;
        int fixedZ = dz < 0 ? at.Y - 1 : at.Y + footprint.Y;
        int length = dx == 0 ? footprint.X : footprint.Y;
        WorldLayout layout = this.strictPortBoundary ? null : this.grid.cityLayout();
        for (int i = 0; i < length; i++) {
            int x = dx == 0 ? at.X + i : fixedX;
            int z = dz == 0 ? at.Y + i : fixedZ;
            if (layout != null) {
                if (!layout.isCityRoadCell(x, z)) return false;
            } else {
                Parcel adjacent = this.grid.peekParcel(x, z);
                if (!(adjacent instanceof StreetParcel)
                        ) return false;
            }
        }
        return true;
    }

    /** Marks which sides have a street along them (see roadNorth). */
    public void setRoadSides(boolean north, boolean south, boolean east, boolean west) {
        this.roadNorth = north;
        this.roadSouth = south;
        this.roadEast = east;
        this.roadWest = west;
    }

    public void growBy(int dx, int dz) {
        this.size = new Cartesian2D(this.size.X + dx, this.size.Y + dz);
    }

    /**
     * Widens this district by the given number of chunks on each side, which can move its base west and north.
     * Only meant for UrbanGrid.absorbStreetStrips(), before anything is filled; never call it once fillDistrict()
     * has run.
     */
    public void growSides(int west, int north, int east, int south) {
        this.base = new Cartesian2D(this.base.X - west, this.base.Y - north);
        this.size = new Cartesian2D(this.size.X + west + east, this.size.Y + north + south);
    }

    /** True for a landmark district, whose footprint is committed to one clipboard and must not change. */
    public boolean isForced() {
        return this.forcedClip != null;
    }

    /**
     * Scans this district's own base/size footprint and marks every
     * parcel found there as a member of THIS district (see
     * Parcel.setDistrict()) -- pure metadata at this point (growth no
     * longer batches by district; see DecayProviderNormal's per-build
     * cache/neighbourhood-trigger instead), kept because it's still a
     * meaningful "which city block is this building part of" fact about
     * a parcel. Must only run once this district's placements are truly
     * FINAL for this footprint -- see the two call sites
     * (fillDistrict()/fillForcedDistrict()) for why extraCutMode's
     * re-cut path deliberately skips this on the ORIGINAL (pre-recut)
     * district: its children own this same footprint afterward and each
     * do this same assignment themselves, over their own smaller
     * base/size.
     */
    private void assignDistrictMembership() {
        int assigned = 0;
        for (int x = this.base.X; x < this.base.X + this.size.X; x++) {
            for (int y = this.base.Y; y < this.base.Y + this.size.Y; y++) {
                Parcel p = this.host.getParcel(x, y);
                // Port annexes cannot claim unrelated natural shoreline as an
                // industrial district merely because it falls inside a candidate rectangle.
                if (p != null && (!this.strictPortBoundary || p instanceof ClipboardParcel)) {
                    p.setDistrict(this);
                    assigned++;
                }
            }
        }
        Minions.dDistrict("DISTRICT MEMBERSHIP ASSIGNED context='%s' base=%s size=%s chunk cells assigned=%d",
                this.context, this.base, this.size, assigned);
    }


    @Override
    public void fillDistrict() {
        this.context = this.assignedContext != null
                ? this.assignedContext
                : this.host.getContextProvider().getContext(this.base);
        // DENSERESIDENTIAL is deliberately immune to the ordinary hard
        // cloneRadius (and, further down in placeRandomBuild()/getWeights(),
        // the ordinary cloneRadiusSoftLimit/clonePenalty too) -- see
        // PluginConfig.isDenseResidentialEnabled()'s javadoc. Resolving to 0
        // here means getNeighbours(center, this.CLONERADIUS) in
        // placeRandomBuild() effectively never finds a same-spot hard
        // duplicate to reject, exactly like a real tract-housing
        // development repeating the same handful of house models block
        // after block. This can only be resolved here, once this.context is
        // actually known -- see the CLONERADIUS field comment above.
        this.CLONERADIUS = this.context == ContextType.DENSERESIDENTIAL ? 0 : PluginConfig.getCloneRadius();

        if (this.forcedClip != null) {
            this.fillForcedDistrict();
            return;
        }

        Minions.dDistrict("SELECTED context='%s' base=%s size=%s", this.context, this.base, this.size);
        // IMPORTANT: this is a List, not a Set. ClipboardParcel.equals()/hashCode()
        // intentionally identify a schematic by groupId for clone checks. That
        // equality is incompatible with using Set as the district's placement
        // container when exact BUILD repeats are allowed (especially
        // DENSERESIDENTIAL). A Set would silently discard the second placement
        // of the same house family member, leaving its footprint absent from
        // the final commit/coverage calculation. The list is the authoritative
        // collection of actual spatial claims; neighbour sets are built only
        // where equality-by-groupId is actually desired.
        diagnosticBuildRollFailed = diagnosticBuildPoolEmpty = diagnosticBuildCandidatesSelected = 0;
        diagnosticBuildDuplicateRejected = diagnosticBuildVariantRejected = 0;
        diagnosticVariantRadiusRejected = diagnosticVariantDistrictRejected = 0;
        diagnosticBuildNoRoadRequests = diagnosticBuildNoRoadEmptyPools = 0;
        diagnosticBuildAccepted = diagnosticBuildInvalidFootprint = 0;
        List<ClipboardParcel> max = null;
        int score = Integer.MIN_VALUE;

        // Fixed mode (the default): exactly `iterations` passes, keep the best -- as always. Dynamic mode: keep running passes
        // until the best one covers dynamicTargetCoverage percent of this district with BUILD parcels, or dynamicMaxIterations
        // passes have run, in which case it says so loudly and carries on with the best pass (filler fills the rest, below).
        // A pass's score is the total area of its builds (see scoreArea()), so "coverage" is just score / area. The passes
        // draw from the same seeded stream either way, so dynamic mode is exactly as seed-determined as fixed mode.
        final boolean dynamic = PluginConfig.isDynamicIterationsEnabled();
        final int passLimit = dynamic ? Math.max(1, PluginConfig.getDynamicMaxIterations()) : Math.max(1, this.MAX_ITERATIONS);
        final long districtArea = (long) this.size.X * (long) this.size.Y;
        final long targetScore = dynamic
                ? Math.max(1L, (long) Math.ceil(PluginConfig.getDynamicTargetCoverage() / 100.0 * districtArea))
                : Long.MAX_VALUE;
        boolean reachedTarget = false;
        int passesRun = 0;

        for (int i = 0; i < passLimit; i++) {
            Minions.dDistrict("ITERATION %d/%d START context='%s' base=%s size=%s",
                    i + 1, passLimit, this.context, this.base, this.size);
            this.denseResidentialFamilyKey = null;
            List<ClipboardParcel> parcels = new LinkedList<>();
            int tscore = this.nextRecPartition(parcels, this.base, this.size);
            passesRun++;
            if (tscore >= score) {
                max = parcels;
                score = tscore;
            }
            if (dynamic && score >= targetScore) {
                reachedTarget = true;
                break;
            }
        }

        if (dynamic) {
            double coveragePercent = districtArea > 0 ? 100.0 * score / districtArea : 0.0;
            if (reachedTarget) {
                Minions.dDistrict("DYNAMIC ITERATIONS reached target after %d pass(es): coverage=%.1f%% (target %d%%) context='%s' base=%s size=%s",
                        passesRun, coveragePercent, PluginConfig.getDynamicTargetCoverage(), this.context, this.base, this.size);
            } else {
                Minions.w("DYNAMIC ITERATIONS FALLBACK: target %d%% build coverage NOT reached after %d passes (best %.1f%%) -- using the best pass and filling the rest with filler. context='%s' base=%s size=%s",
                        PluginConfig.getDynamicTargetCoverage(), passesRun, coveragePercent, this.context, this.base, this.size);
                Minions.w("DYNAMIC SEARCH REASONS context='%s' base=%s size=%s attempts=%d candidatesSelected=%d accepted=%d emptyPools=%d duplicateRejections=%d variantRejections=%d [radius=%d district=%d] noRoadRequests=%d noRoadEmptyPools=%d invalidFootprints=%d buildRollFails=%d",
                        this.context, this.base, this.size, passesRun,
                        diagnosticBuildCandidatesSelected, diagnosticBuildAccepted, diagnosticBuildPoolEmpty,
                        diagnosticBuildDuplicateRejected, diagnosticBuildVariantRejected,
                        diagnosticVariantRadiusRejected, diagnosticVariantDistrictRejected,
                        diagnosticBuildNoRoadRequests, diagnosticBuildNoRoadEmptyPools,
                        diagnosticBuildInvalidFootprint, diagnosticBuildRollFailed);
            }
        }

        Minions.dDistrict("SELECTED FINAL context='%s' base=%s size=%s score=%d parcels=%d",
                this.context, this.base, this.size, score, max == null ? 0 : max.size());

        // BUILD packing is completed before filler is even considered.  This
        // makes filler a true last-stage infill operation rather than a
        // competing random outcome that can steal space from a house. Runs
        // for every context now (see placeRandomBuild()'s javadoc for why
        // ordinary districts moved to this same sweep DENSERESIDENTIAL
        // always used) -- largest fitting filler first, per-district usage
        // tracked so one schematic can't dominate a district, and it always
        // places SOMETHING for any leftover space (down to a last-resort
        // 1x1), so filler as a fallback is never skipped.
        if (max != null) {
            // Filler is deterministic, but we deliberately track exact filler
            // usage within this district so one schematic cannot monopolize
            // the cleanup pass when other fitting fillers are available.
            Map<String, Integer> fillerUsage = new HashMap<>();
            this.fillRemainingWithFiller(max, this.base, this.size, fillerUsage);
            Minions.dFillerResult("FILL PASS COMPLETE context=%s base=%s size=%s parcels=%d",
                    this.context, this.base, this.size, max.size());
        }

        for (Parcel parcel : max) {
            if (parcel instanceof ClipboardParcel) {
                ClipboardParcel clipParcel = (ClipboardParcel) parcel;
                if (clipParcel.getClipboard() != null
                        && clipParcel.getClipboard().getConfig() != null
                        && clipParcel.getClipboard().getConfig().getRoadFacing()
                        && !hasRealRoadFrontage(
                                new Cartesian2D(clipParcel.getChunkX(), clipParcel.getChunkZ()),
                                new Cartesian2D(clipParcel.getChunkSizeX(), clipParcel.getChunkSizeZ()),
                                clipParcel.getDirection())) {
                    Minions.w("REJECT street-facing schematic without actual road: district=%s/%s parcel=%s",
                            this.base, this.size, clipParcel);
                    continue;
                }
            }
            // Retain the 1.12.2 port annex boundary: never manufacture or
            // paste shoreline parcels outside the authorized reclaim rectangle.
            if (this.strictPortBoundary && (!(parcel instanceof ClipboardParcel)
                    || !fitsDistrict((ClipboardParcel) parcel))) {
                Minions.w("[Ports] REJECT OUT-OF-DISTRICT schematic base=%s size=%s parcel=%s",
                        this.base, this.size, parcel);
                continue;
            }
            this.host.fillParcels(parcel.getChunkX(), parcel.getChunkZ(), parcel);
            if (PluginConfig.isDistrictDebugEnabled()) {
                Minions.dDistrict("Parcel: " + parcel.toString());
            }
        }

        // Unclaimed cells remain unclaimed. A failed search is not a reason
        // to manufacture a 1x1 parcel after the fact; terminal fallback is
        // handled by nextRecPartition() itself.

        boolean finalized = true;
        // Port annexes cannot recut to create an artificial street into the ocean.
        if (PluginConfig.isExtraCutModeEnabled() && !this.strictPortBoundary && !max.isEmpty()) {
            finalized = this.checkExtraCut();
        }
        if (finalized) {
            this.assignDistrictMembership();
        }
    }

    /**
     * Places this district's single pre-chosen landmark clip directly
     * across its entire base/size footprint. No iteration, no scoring, no
     * search -- UrbanGrid.tryPlaceLandmark() already confirmed this exact
     * clip fits this exact footprint before this district was created, so
     * this is a straight placement.
     */
    private void fillForcedDistrict() {
        Minions.dDistrict("LANDMARK PLACEMENT context='%s' base=%s size=%s direction=%s clip=%s",
                this.context, this.base, this.size, this.forcedDirection, this.describeClip(this.forcedClip));

        if (this.forcedClip != null && this.forcedClip.getConfig() != null
                && this.forcedClip.getConfig().getRoadFacing()
                && !hasRealRoadFrontage(this.base, this.size, this.forcedDirection)) {
            Minions.w("REJECT landmark with no real street frontage: district=%s/%s facing=%s",
                    this.base, this.size, this.forcedDirection);
            return;
        }
        ClipboardParcel parcel = new ClipboardParcel(
                this.grid, this.base, this.size, this.forcedClip, this.context, SchematicType.BUILD, this.forcedDirection
        );
        this.host.fillParcels(parcel.getChunkX(), parcel.getChunkZ(), parcel);
        this.assignDistrictMembership();
    }

    @Override
    public void initForcedDistrict(Cartesian2D base, Cartesian2D size, UrbanGrid grid, Clipboard clip, Direction direction) {
        this.base = base;
        this.size = size;
        this.grid = grid;
        this.forcedClip = clip;
        this.forcedDirection = direction;
    }

    /**
     * If extraCutMode is on, checks how much of this now-finished district's
     * chunk area ended up FILLER rather than BUILD. If that's over the
     * configured threshold, scraps this district and re-cuts its exact
     * base/size area with a fresh road split -- see UrbanGrid.forceCut() --
     * producing two smaller districts in its place. Each of those gets
     * filled and checked the same way (see UrbanGrid.recutAndRefill()), so a
     * stubborn district keeps getting subdivided purely based on filler
     * percentage until it's build-full enough, or it hits extraCutBlockSize
     * and genuinely can't be cut any smaller. This floor is intentionally
     * separate from (and normally smaller than) the ordinary blockSize used
     * everywhere else -- extraCutMode doesn't stop at the normal city block
     * granularity, only at its own configured limit.
     *
     * Returns true if THIS district's own base/size footprint ended up
     * final (nothing left below it needs to run assignDistrictMembership()
     * a second time), false if it delegated to recutAndRefill() instead --
     * see fillDistrict()'s use of the return value.
     */
    public boolean isForcedDistrict() {
        return this.forcedClip != null;
    }

    /**
     * Turns a landmark district back into an ordinary one: fillDistrict() then
     * fills its footprint normally, with whatever context it was zoned with,
     * instead of pasting the pre-chosen landmark. Used by UrbanGrid when
     * generator.landmarkZoneMatch is on and the zone this district ended up
     * with cannot host its landmark. Must run before fillDistrict().
     */
    public void clearForcedClip() {
        this.forcedClip = null;
        this.forcedDirection = null;
    }

    private boolean checkExtraCut() {
        int extraCutBlockSize = PluginConfig.getExtraCutBlockSize();
        if (this.size.X < extraCutBlockSize && this.size.Y < extraCutBlockSize) {
            // At extraCutMode's own floor -- forceCut() would have nothing
            // left it could legally split. This is as good as it gets.
            Minions.dDistrict("EXTRACUT SKIPPED (at extraCutBlockSize floor) context='%s' base=%s size=%s",
                    this.context, this.base, this.size);
            return true;
        }

        int fillerChunks = 0;
        int totalChunks = 0;
        for (int x = this.base.X; x < this.base.X + this.size.X; x++) {
            for (int y = this.base.Y; y < this.base.Y + this.size.Y; y++) {
                Parcel p = this.host.getParcel(x, y);
                totalChunks++;
                if (p instanceof ClipboardParcel && ((ClipboardParcel) p).getSchematicType() == SchematicType.FILLER) {
                    fillerChunks++;
                }
            }
        }

        if (totalChunks == 0) {
            return true;
        }

        int fillerPercent = fillerChunks * 100 / totalChunks;
        int threshold = PluginConfig.getExtraCutFillerThreshold();

        if (fillerPercent <= threshold) {
            Minions.dDistrict("EXTRACUT OK context='%s' base=%s size=%s fillerPercent=%d%% threshold=%d%%",
                    this.context, this.base, this.size, fillerPercent, threshold);
            return true;
        }

        Minions.dDistrict("EXTRACUT TRIGGERED context='%s' base=%s size=%s fillerPercent=%d%% threshold=%d%% -- retrying whole footprint before re-cutting",
                this.context, this.base, this.size, fillerPercent, threshold);

        // Before scrapping this district into smaller pieces, try it as one
        // single placement covering the ENTIRE footprint -- reusing
        // placeRandomBuildForSure()'s existing exhaustive BUILD -> context
        // FILLER -> context-free FILLER search (the same one the 1x1
        // last-resort case uses), just run here at the district's full
        // size instead of 1x1. A district ends up filler-heavy specifically
        // because it got fragmented into many small pieces during normal
        // recursive partitioning -- a single BUILD or a single large
        // FILLER spanning the whole area was never actually tried as one
        // placement during that process, since nextRecPartition only
        // tries the CURRENT (shrinking) size at each step. This
        // retry bypasses that by directly checking "does anything -- BUILD
        // or a big FILLER -- fit this exact, still-full-sized footprint",
        // with the same weighting/duplicate protection as any other
        // placement. Only if that comes back empty do we fall through to
        // the existing re-cut-smaller behaviour.
        ClipboardParcel whole = this.placeRandomBuildForSure(this.base, this.size, new HashSet<>());
        if (whole != null) {
            Minions.dDistrict("EXTRACUT WHOLE-AREA RETRY SUCCEEDED context='%s' base=%s size=%s clip=%s -- keeping this district as a single parcel",
                    this.context, this.base, this.size, this.describeClip(whole.getClipboard()));
            this.host.fillParcels(this.base.X, this.base.Y, whole);
            return true;
        }

        Minions.dDistrict("EXTRACUT WHOLE-AREA RETRY FAILED context='%s' base=%s size=%s -- falling back to re-cut smaller",
                this.context, this.base, this.size);

        this.host.recutAndRefill(this, this.base, this.size);
        return false;
    }

    private ClipboardParcel placeRandomBuild(Cartesian2D base, Cartesian2D size, Collection<ClipboardParcel> previous, SchematicType schematicType) {
        Direction direction = this.findRoad(base, size);
        Cartesian2D center = new Cartesian2D(base.X + size.X / 2, base.Y + size.Y / 2);

        // DENSERESIDENTIAL behaves like a temporary/faux district whose
        // BUILD pool is locked to one family. The important distinction is
        // that family membership is resolved WITHOUT asking the database
        // for the current region's exact dimensions first. Once the family
        // is known, every BUILD variant in that family remains eligible and
        // the physical-fit check happens in memory.
        if (this.context == ContextType.DENSERESIDENTIAL && schematicType == SchematicType.BUILD) {
            // DENSERESIDENTIAL roads are defined by the ORIGINAL district
            // perimeter, never by a recursive search rectangle.  An interior
            // rectangle is therefore not a valid BUILD frontage, even if it
            // happens to be the current partition being searched.  Returning
            // null here leaves that area unclaimed so the partitioner can keep
            // searching other branches; the final filler pass handles the
            // genuinely interior remainder.
            if (direction == Direction.NONE) {
                Minions.dDenseResidential("FAUX BUILD SKIPPED: interior search rectangle has no original district road frontage context=%s base=%s size=%s",
                        this.context, base, size);
                return null;
            }

            List<Clipboard> familyPool = this.getDenseResidentialFamilyPool(direction);
            this.debugDenseResidentialFamilies("FAUX", size, direction, familyPool);

            List<Clipboard> familyClips = this.filterByDenseResidentialFamily(familyPool);
            this.debugDenseResidentialFamilyFilter("FAUX", size, direction, familyPool, familyClips);

            List<Clipboard> fittingFamilyClips = this.filterDenseResidentialFitting(familyClips, size);
            this.debugDenseResidentialFitting(size, direction, familyClips, fittingFamilyClips);

            // Equal-weight family roll -- ONLY meaningful while unlocked.
            // Once denseResidentialFamilyKey is already set,
            // filterByDenseResidentialFamily() above has already narrowed
            // familyClips/fittingFamilyClips down to that one family, so
            // grouping+picking here would just find a single group and
            // hand it straight back -- see pickEqualWeightFamily()'s own
            // no-op guard for that case too, kept as defense in depth.
            if (this.denseResidentialFamilyKey == null) {
                fittingFamilyClips = this.pickEqualWeightFamily(fittingFamilyClips);
            }

            Minions.dDenseResidential("FAUX BUILD POOL context=%s base=%s size=%s direction=%s family=%s allFamilyCandidates=%d fittingCandidates=%d clips=%s",
                    this.context, base, size, direction,
                    this.denseResidentialFamilyKey == null ? "<UNLOCKED>" : this.denseResidentialFamilyKey,
                    familyClips.size(), fittingFamilyClips.size(), this.describeClips(fittingFamilyClips));

            Minions.dDenseResidential("FAUX BUILD SELECTING context=%s base=%s size=%s direction=%s family=%s fittingCandidates=%d",
                    this.context, base, size, direction,
                    this.denseResidentialFamilyKey == null ? "<UNLOCKED>" : this.denseResidentialFamilyKey,
                    fittingFamilyClips.size());

            ClipboardParcel familyParcel = this.placeRandomRec(
                    base, size, direction, fittingFamilyClips,
                    Collections.emptySet(), Collections.emptySet(), Collections.emptySet(),
                    previous, center, schematicType, true
            );
            if (familyParcel != null) {
                Minions.dDenseResidential("FAUX BUILD SELECTED context=%s base=%s size=%s family=%s clip=%s",
                        this.context, base, size,
                        this.denseResidentialFamilyKey == null ? "<UNLOCKED>" : this.denseResidentialFamilyKey,
                        this.describeClip(familyParcel.getClipboard()));
                this.lockDenseResidentialFamily(familyParcel);
            } else {
                Minions.dDenseResidential("FAUX BUILD FAILED context=%s base=%s size=%s family=%s fittingCandidates=%d",
                        this.context, base, size,
                        this.denseResidentialFamilyKey == null ? "<UNLOCKED>" : this.denseResidentialFamilyKey,
                        fittingFamilyClips.size());
            }
            return familyParcel;
        }

        Set<ClipboardParcel> neighbours = this.host.getNeighbours(center, this.CLONERADIUS);
        // Enforce clone spacing by the configured radius for provisional builds as
        // well as already-committed neighbours. These placements live only in
        // `previous` until the district is committed: adding the entire list
        // made a zero/small cloneRadius behave as district-wide hard exclusion.
        this.addPreviousWithinRadius(neighbours, previous, center, this.CLONERADIUS);
        // Fetch a wider neighbour set used only for variant weight scaling --
        // schematics sharing the same variant key (same originating config's
        // content hash -- see SchematicConfig.getVariantKey()) that land in
        // this radius will have their selection weight reduced, favouring
        // more spread-out variety without ever hard-blocking placement.
        Set<ClipboardParcel> variantNeighbours = this.host.getNeighbours(center, this.VARIANTRADIUS);
        // Include provisional siblings only if their ACTUAL rectangles touch the
        // configured radius, rather than implicitly treating a whole district as nearby.
        this.addPreviousWithinRadius(variantNeighbours, previous, center, this.VARIANTRADIUS);
        // Optional wider ring beyond the hard cloneRadius block -- only
        // populated when cloneRadiusSoftLimit is configured larger than
        // cloneRadius (same "only matters if actually wider" pattern as
        // variantNeighbours above). Gives exact duplicates a tapering soft
        // penalty in the region just past the hard wall, instead of full
        // odds resuming abruptly the instant you're one chunk outside it.
        // DENSERESIDENTIAL never consults this global setting -- see
        // getEffectiveCloneRadiusSoftLimit()'s javadoc.
        int cloneRadiusSoftLimit = this.getEffectiveCloneRadiusSoftLimit();
        boolean cloneSoftEnabled = cloneRadiusSoftLimit > this.CLONERADIUS;
        Set<ClipboardParcel> cloneSoftNeighbours = cloneSoftEnabled
                ? this.host.getNeighbours(center, cloneRadiusSoftLimit)
                : Collections.emptySet();
        if (cloneSoftEnabled) {
            cloneSoftNeighbours.addAll(previous);
        }
        if (schematicType == SchematicType.FILLER) {
            Minions.dFillerQuery("CONFIG context=%s base=%s size=%s direction=%s cloneRadius=%d softLimit=%d clonePenalty=%d hardCloneBlock=%s fillerExempt=%s softEnabled=%s softNeighbourCount=%d previousCount=%d",
                    this.context, base, size, direction, this.CLONERADIUS,
                    cloneRadiusSoftLimit, this.getEffectiveClonePenalty(),
                    !PluginConfig.isFillerExemptFromCloneRadius(), PluginConfig.isFillerExemptFromCloneRadius(),
                    cloneSoftEnabled, cloneSoftNeighbours.size(), previous.size());
        }
        // Candidate rectangles produced by nextRecPartition()'s splitting are
        // just search areas -- they have no relationship to any schematic's
        // actual footprint, and were never meant to. getFit() did an EXACT
        // size_x/size_y match, which meant a BUILD could only ever be found
        // here on the (extremely rare) occasion the recursive split happened
        // to produce a rectangle exactly matching some schematic's chunk
        // footprint. getFittingClips() below instead pulls every schematic
        // for this context/type/direction and keeps whichever ones physically
        // FIT WITHIN size (w <= size.X && h <= size.Y) -- the same "does it
        // fit, not is it exactly this size" model placeLargestFiller() /
        // getFillerCandidates() already use for the filler sweep. The clip
        // is then placed at its own true footprint (see claimSize below /
        // getPlacementBase()), not stretched to fill the whole rectangle.
        if (schematicType == SchematicType.BUILD && direction == Direction.NONE) {
            diagnosticBuildNoRoadRequests++;
        }
        List<Clipboard> clips = this.getFittingClips(this.context, schematicType, direction, size);
        if (schematicType == SchematicType.BUILD && direction == Direction.NONE && clips.isEmpty()) {
            diagnosticBuildNoRoadEmptyPools++;
        }
        clips = this.excludeCreekOriginBuildings(clips, base, size, direction);
        clips = this.host.filterRiverFacing(clips, base.X + size.X / 2, base.Y + size.Y / 2, direction);
        if (this.context == ContextType.DENSERESIDENTIAL && schematicType == SchematicType.BUILD) {
            this.debugDenseResidentialFamilies("NORMAL", size, direction, clips);
            List<Clipboard> beforeFamilyFilter = clips;
            clips = this.filterByDenseResidentialFamily(clips);
            this.debugDenseResidentialFamilyFilter("NORMAL", size, direction, beforeFamilyFilter, clips);
        }
        if (schematicType == SchematicType.FILLER) {
            Minions.dFillerQuery("CONTEXT QUERY context=%s base=%s size=%s direction=%s results=%d",
                    this.context, base, size, direction, clips.size());
            Minions.dFillerCandidate("CONTEXT CANDIDATES context=%s base=%s size=%s direction=%s clips=%s",
                    this.context, base, size, direction, this.describeClips(clips));
        } else {
            Minions.dDistrict("FIT QUERY context='%s' type=%s base=%s size=%s direction=%s results=%d clips=%s",
                    this.context, schematicType, base, size, direction, clips.size(), this.describeClips(clips));
        }
        ClipboardParcel parcel = this.placeRandomRec(base, size, direction, clips, neighbours, variantNeighbours, cloneSoftNeighbours, previous, center, schematicType, false);
        if (this.context == ContextType.DENSERESIDENTIAL && schematicType == SchematicType.BUILD) {
            this.lockDenseResidentialFamily(parcel);
        }
        return parcel;
    }

    /**
     * DENSERESIDENTIAL family membership is district-wide, not tied to the
     * road direction of the particular search rectangle.  Road-facing pieces
     * still use the exact direction requested by findRoad(); interior pieces
     * have no road direction, so they must be allowed to use any rotated
     * member of the already-locked family.
     */
    private List<Clipboard> getDenseResidentialFamilyPool(Direction direction) {
        if (direction != Direction.NONE) {
            return this.host.getClipboardProvider().getAll(
                    ContextType.DENSERESIDENTIAL, SchematicType.BUILD, direction);
        }

        List<Clipboard> all = new LinkedList<>();
        Direction[] directions = new Direction[]{
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NONE
        };
        for (Direction candidateDirection : directions) {
            List<Clipboard> clips = this.host.getClipboardProvider().getAll(
                    ContextType.DENSERESIDENTIAL, SchematicType.BUILD, candidateDirection);
            for (Clipboard clip : clips) {
                if (!all.contains(clip)) {
                    all.add(clip);
                }
            }
        }
        return all;
    }

    /**
     * If this DENSERESIDENTIAL district has already locked onto a house
     * family (see denseResidentialFamilyKey), narrows clips down to only
     * the ones sharing that SchematicConfig.getDenseResidentialFamilyKey()
     * -- normally only schematics from the exact same CONFIG.json (like
     * getVariantKey()), but also pulls in any other config that opted in
     * via a matching variantGroup tag. No-op (returns clips unchanged)
     * before the lock exists yet, so the very first BUILD placement in a
     * district is still a free pick across the whole
     * DENSERESIDENTIAL(_PRIMARY) pool.
     */
    private List<Clipboard> filterByDenseResidentialFamily(List<Clipboard> clips) {
        if (this.denseResidentialFamilyKey == null || clips.isEmpty()) {
            return clips;
        }

        List<Clipboard> filtered = new LinkedList<>();
        for (Clipboard clip : clips) {
            if (this.denseResidentialFamilyKey.equals(clip.getConfig().getDenseResidentialFamilyKey())) {
                filtered.add(clip);
            }
        }
        return filtered;
    }

    /**
     * Locks this DENSERESIDENTIAL district onto parcel's house family, if
     * it hasn't already locked onto one this iteration -- see the field
     * comment on denseResidentialFamilyKey. Harmless no-op for a null
     * parcel/clipboard (a failed placement) or once a lock already exists.
     */
    private void lockDenseResidentialFamily(ClipboardParcel parcel) {
        if (this.denseResidentialFamilyKey != null || parcel == null || parcel.getClipboard() == null) {
            return;
        }

        this.denseResidentialFamilyKey = parcel.getClipboard().getConfig().getDenseResidentialFamilyKey();
        Minions.dDistrict("DENSERESIDENTIAL FAMILY LOCKED context='%s' base=%s size=%s key=%s",
                this.context, this.base, this.size, this.denseResidentialFamilyKey);
    }

    private void debugDenseResidentialFamilies(String poolName, Cartesian2D size, Direction direction, List<Clipboard> clips) {
        if (!PluginConfig.isDenseResidentialFamilyDebugEnabled()) {
            return;
        }

        java.util.Map<String, List<Clipboard>> groups = new java.util.TreeMap<>();
        if (clips != null) {
            for (Clipboard clip : clips) {
                if (clip == null || clip.getConfig() == null) {
                    continue;
                }
                String key = clip.getConfig().getDenseResidentialFamilyKey();
                if (key == null || key.length() == 0) {
                    key = "<NO-FAMILY-KEY>";
                }
                List<Clipboard> group = groups.get(key);
                if (group == null) {
                    group = new LinkedList<>();
                    groups.put(key, group);
                }
                group.add(clip);
            }
        }

        Minions.dDenseResidential("FAMILY POOL %s context=%s base=%s size=%s direction=%s lockedFamily=%s totalCandidates=%d families=%d",
                poolName, this.context, this.base, size, direction,
                this.denseResidentialFamilyKey == null ? "<UNLOCKED>" : this.denseResidentialFamilyKey,
                clips == null ? 0 : clips.size(), groups.size());

        for (java.util.Map.Entry<String, List<Clipboard>> entry : groups.entrySet()) {
            Minions.dDenseResidential("FAMILY GROUP %s key=%s candidates=%d clips=%s",
                    poolName, entry.getKey(), entry.getValue().size(), this.describeClips(entry.getValue()));
        }
    }

    private void debugDenseResidentialFamilyFilter(String poolName, Cartesian2D size, Direction direction,
                                                   List<Clipboard> before, List<Clipboard> after) {
        if (!PluginConfig.isDenseResidentialFamilyDebugEnabled()) {
            return;
        }

        Minions.dDenseResidential("FAMILY FILTER %s context=%s size=%s direction=%s lockedFamily=%s before=%d after=%d kept=%s",
                poolName, this.context, size, direction,
                this.denseResidentialFamilyKey == null ? "<UNLOCKED>" : this.denseResidentialFamilyKey,
                before == null ? 0 : before.size(), after == null ? 0 : after.size(),
                this.describeClips(after));
    }

    private List<Clipboard> filterDenseResidentialFitting(List<Clipboard> clips, Cartesian2D size) {
        if (clips == null || clips.isEmpty()) {
            return new LinkedList<>();
        }

        List<Clipboard> fitting = new LinkedList<>();
        for (Clipboard clip : clips) {
            if (clip == null || clip.getSize() == null) {
                continue;
            }
            // Clipboard#getSize() is measured in BLOCKS, while District
            // parcel sizes are measured in CHUNKS.  The DB/importer uses
            // regionWidth >> 4 / regionLength >> 4 for its fit keys, so
            // the in-memory DENSERESIDENTIAL family filter must use the
            // exact same units.  Comparing (for example) a 48x48 block
            // house against a 3x3 chunk parcel makes every house look too
            // large and drives the recursive partition all the way down
            // to 1x1, where the filler fallback takes over.
            int clipWidthChunks = clip.getSize().X >> 4;
            int clipLengthChunks = clip.getSize().Z >> 4;
            if (clipWidthChunks <= size.X && clipLengthChunks <= size.Y) {
                fitting.add(clip);
            }
        }
        return fitting;
    }

    /**
     * Groups fittingClips by DenseResidentialFamilyKey and picks ONE family
     * uniformly at random -- every eligible family (one with at least one
     * schematic fitting the current footprint) gets exactly the same odds
     * of winning here, regardless of how many variants it has or what
     * their individual oddsOfAppearanceInPercent/size values are. Those
     * per-schematic weights still apply -- but only AFTER a family has
     * already been chosen -- to pick which specific schematic within that
     * family gets used (see placeRandomRec()/getWeights(), called on
     * whatever this method returns).
     *
     * No-op (returns clips unchanged) when clips is empty or already all
     * one family -- the common already-locked case, where
     * filterByDenseResidentialFamily() has already narrowed the pool down
     * to a single family before this is ever called.
     */
    private List<Clipboard> pickEqualWeightFamily(List<Clipboard> clips) {
        if (clips.isEmpty()) {
            return clips;
        }

        Map<String, List<Clipboard>> families = new java.util.LinkedHashMap<>();
        for (Clipboard clip : clips) {
            String key = clip.getConfig().getDenseResidentialFamilyKey();
            families.computeIfAbsent(key, k -> new LinkedList<>()).add(clip);
        }

        if (families.size() <= 1) {
            return clips;
        }

        List<String> familyKeys = new java.util.ArrayList<>(families.keySet());
        String chosenKey = familyKeys.get(this.host.getRandom().getRandomInt(familyKeys.size()));
        List<Clipboard> chosen = families.get(chosenKey);

        Minions.dDenseResidential("FAMILY ROLL context=%s eligibleFamilies=%d chosenFamily=%s chosenCandidates=%d (of %d total fitting)",
                this.context, families.size(), chosenKey, chosen.size(), clips.size());

        return chosen;
    }

    private void debugDenseResidentialFitting(Cartesian2D size, Direction direction,
                                              List<Clipboard> familyClips, List<Clipboard> fittingClips) {
        if (!PluginConfig.isDenseResidentialFamilyDebugEnabled()) {
            return;
        }
        Minions.dDenseResidential("FAMILY FIT context=%s size=%s direction=%s familyCandidates=%d fitting=%d removedTooLarge=%d fittingClips=%s",
                this.context, size, direction,
                familyClips == null ? 0 : familyClips.size(),
                fittingClips == null ? 0 : fittingClips.size(),
                (familyClips == null ? 0 : familyClips.size()) - (fittingClips == null ? 0 : fittingClips.size()),
                this.describeClips(fittingClips));
    }

    private ClipboardParcel placeRandomBuildForSure(Cartesian2D base, Cartesian2D size, Collection<ClipboardParcel> previous) {
        Minions.dDistrict("PRBFS START context='%s' base=%s size=%s previous=%d",
                this.context, base, size, previous.size());

        ClipboardParcel p = this.placeRandomBuild(base, size, previous, SchematicType.BUILD);
        if (p != null) {
            Minions.dDistrict("PRBFS BUILD SUCCESS context='%s' base=%s size=%s parcel=%s",
                    this.context, base, size, p);
            return p;
        }

        Minions.dDistrict("PRBFS BUILD FAILED context='%s' base=%s size=%s",
                this.context, base, size);

        Minions.dFiller("BUILD -> FILLER HANDOFF context=%s base=%s size=%s reason=BUILD_RETURNED_NULL",
                this.context, base, size);
        Minions.dFiller("PRBFS FILLER ATTEMPT context=%s base=%s size=%s",
                this.context, base, size);
        p = this.placeRandomBuild(base, size, previous, SchematicType.FILLER);
        if (p != null) {
            Minions.dFillerResult("PRBFS FILLER SUCCESS context=%s base=%s size=%s parcel=%s",
                    this.context, base, size, p);
            return p;
        }

        Minions.dFillerResult("PRBFS CONTEXT FILLER FAILED context=%s base=%s size=%s",
                this.context, base, size);

        Direction direction = this.findRoad(base, size);
        Cartesian2D center = new Cartesian2D(base.X + size.X / 2, base.Y + size.Y / 2);
        Set<ClipboardParcel> neighbours = this.host.getNeighbours(center, this.CLONERADIUS);
        // Enforce clone spacing by the configured radius for provisional builds as
        // well as already-committed neighbours. These placements live only in
        // `previous` until the district is committed: adding the entire list
        // made a zero/small cloneRadius behave as district-wide hard exclusion.
        this.addPreviousWithinRadius(neighbours, previous, center, this.CLONERADIUS);
        Set<ClipboardParcel> variantNeighbours = this.host.getNeighbours(center, this.VARIANTRADIUS);
        this.addPreviousWithinRadius(variantNeighbours, previous, center, this.VARIANTRADIUS);
        int cloneRadiusSoftLimit = this.getEffectiveCloneRadiusSoftLimit();
        boolean cloneSoftEnabled = cloneRadiusSoftLimit > this.CLONERADIUS;
        Set<ClipboardParcel> cloneSoftNeighbours = cloneSoftEnabled
                ? this.host.getNeighbours(center, cloneRadiusSoftLimit)
                : Collections.emptySet();
        if (cloneSoftEnabled) {
            cloneSoftNeighbours.addAll(previous);
        }

        // Context-scoped tier: same "fits within" model as getFittingClips()
        // elsewhere, rather than the old exact size_x/size_y match. The
        // context-free tier just below stays on the old exact-match getFit()
        // -- it's the ultimate last-resort fallback and not worth the extra
        // ClipboardProvider surface (getAll() has no context-free overload).
        List<Clipboard> clips = this.getFittingClips(this.context, SchematicType.FILLER, direction, size);
        clips = this.excludeCreekOriginBuildings(clips, base, size, direction);
        clips = this.host.filterRiverFacing(clips, base.X + size.X / 2, base.Y + size.Y / 2, direction);

        Minions.dFillerQuery("CONTEXT FILLER QUERY context=%s size=%s direction=%s results=%d",
                this.context, size, direction, clips.size());
        Minions.dFillerCandidate("CONTEXT FILLER CANDIDATES context=%s size=%s direction=%s clips=%s",
                this.context, size, direction, this.describeClips(clips));

        if (clips.size() == 0) {
            Minions.dFillerFallback("CONTEXT FILLER EMPTY -> trying CONTEXT-FREE FALLBACK context=%s size=%s direction=%s",
                    this.context, size, direction);

            clips = this.host.getClipboardProvider().getFit(size, SchematicType.FILLER, direction);
            clips = this.excludeCreekOriginBuildings(clips, base, size, direction);
            clips = this.host.filterRiverFacing(clips, base.X + size.X / 2, base.Y + size.Y / 2, direction);

            Minions.dFillerQuery("CONTEXT-FREE FILLER QUERY context=%s size=%s direction=%s results=%d",
                    this.context, size, direction, clips.size());
            Minions.dFillerCandidate("CONTEXT-FREE FILLER CANDIDATES context=%s size=%s direction=%s clips=%s",
                    this.context, size, direction, this.describeClips(clips));

            if (clips.size() == 0) {
                Minions.dFillerFallback("CONTEXT-FREE FALLBACK EMPTY context=%s size=%s direction=%s",
                        this.context, size, direction);
                return null;
            }
        }

        // Both tiers above (context-matched and context-free) used to pick
        // via clips.get(getRandomInt(clips.size())) -- a raw uniform pick
        // with NONE of placeRandomRec()'s protections: no odds-of-appearance
        // weighting, no hard-duplicate rejection, no clone-radius-soft-limit
        // penalty, no variant-group penalty. This tier is only reached once
        // the fully-protected FILLER attempt above (in placeRandomBuild())
        // has already returned null -- typically because every nearby
        // candidate got hard-rejected as a too-close duplicate -- so an
        // unprotected pick here would happily re-select and re-place that
        // exact same duplicate immediately next to itself, repeatedly, with
        // nothing to stop it. That's the mechanism behind dense
        // repeated-identical-filler clusters: once a context's filler pool
        // at a given size/direction is small enough to get exhausted by
        // duplicate-rejection, EVERY cell in that stretch falls through to
        // this tier and the last-resort pick just keeps finding the same
        // nearby answer. Routing through placeRandomRec() gives this tier
        // the exact same weighting and duplicate handling as every other
        // placement, context-matched or not.
        ClipboardParcel chosen = this.placeRandomRec(
                base, size, direction, clips, neighbours, variantNeighbours, cloneSoftNeighbours, previous, center, SchematicType.FILLER, false
        );

        if (chosen == null) {
            Minions.dFillerResult("FALLBACK EXHAUSTED context=%s size=%s direction=%s",
                    this.context, size, direction);
            return null;
        }

        Minions.dFillerSelect("FALLBACK FILLER SELECTED context=%s base=%s size=%s direction=%s clip=%s",
                this.context, base, size, direction, this.describeClip(chosen.getClipboard()));
        Minions.dFillerResult("FILLER READY TO COMMIT context=%s base=%s size=%s direction=%s clip=%s",
                this.context, base, size, direction, this.describeClip(chosen.getClipboard()));

        // This clip may have come from the CONTEXT-FREE query above --
        // tagging the resulting parcel as BUILD here was hiding every
        // context-free-fallback filler from both scoreParcel()'s iteration
        // scoring and checkExtraCut()'s filler percentage count, which is
        // why extraCutMode was never actually triggering a re-cut: most of
        // what a player would call "filler" was being counted as BUILD.
        return chosen;
    }

    /**
     * Keeps schematics that declare a creek origin (any of riverStartBoundsN/E/S/W non-empty --
     * see AbstractSchematicConfig) out of ordinary district carving entirely.
     *
     * A creek origin is real water painted into the building's own wall, so such a building is
     * only ever valid where a creek provably leaves it. Those placements are decided up front,
     * from the seed, by BoundedRiverSites, and the building is put into its reserved lot by the
     * site itself -- so ordinary carving must never place one: it would be a wall of water with
     * nothing behind it. Ordinary schematics pass through untouched.
     */
    private List<Clipboard> excludeCreekOriginBuildings(
            List<Clipboard> clips, Cartesian2D base, Cartesian2D size, Direction direction
    ) {
        if (clips.isEmpty()) {
            return clips;
        }
        List<Clipboard> kept = new ArrayList<>(clips.size());
        for (Clipboard clip : clips) {
            if (!ClipboardParcel.hasCreekOrigin(clip)) {
                kept.add(clip);
            }
        }
        return kept;
    }

    /**
     * True when `outward` points toward the side of the spine `target`'s
     * signedDistance says this lot is actually on -- the sign half of the
     * reach check just above, which Math.abs() alone can never answer.
     * distanceFromCenter()'s sign convention (see RiverProvider): for an
     * X-bound (east-west) river, positive means this lot is SOUTH of the
     * spine (so NORTH is the way to it), negative means NORTH of it (so
     * SOUTH is the way); for a Z-bound (north-south) river, positive means
     * EAST of the spine (WEST is the way), negative means WEST (EAST is
     * the way). isZBound() picks which convention applies rather than
     * assuming `target` is always the X-bound river.
     */
    private static boolean facesTowardRiver(RiverProvider target, Direction outward, double signedDistance) {
        if (target.isZBound()) {
            return signedDistance >= 0 ? outward == Direction.WEST : outward == Direction.EAST;
        }
        return signedDistance >= 0 ? outward == Direction.NORTH : outward == Direction.SOUTH;
    }

    /**
     * True if any parcel in neighbours has a clip sharing variantKey.
     * Unlike the exact-groupId duplicate check (which can use
     * Set.contains() because ClipboardParcel.equals()/hashCode() are
     * groupId-based -- see ClipboardParcel), variantKey equality needs
     * an explicit scan, same as the soft penalty loop in
     * Minions.getPlacementWeights() does.
     */
    /** Filter provisional placements by the same square neighbourhood used by UrbanGrid.getNeighbours().
     * A district's previous buildings are not yet committed to UrbanGrid, but they still have
     * real spatial footprints; unconditionally adding them turned the 17-chunk variant radius
     * into an unlimited intra-district radius. */
    private void addPreviousWithinRadius(Set<ClipboardParcel> target, Collection<ClipboardParcel> previous,
                                         Cartesian2D center, int radius) {
        if (radius < 0 || previous == null) return;
        for (ClipboardParcel p : previous) {
            if (p == null) continue;
            int x0 = p.getChunkX(), z0 = p.getChunkZ();
            int x1 = x0 + p.getChunkSizeX() - 1, z1 = z0 + p.getChunkSizeZ() - 1;
            if (x0 <= center.X + radius && x1 >= center.X - radius
                    && z0 <= center.Y + radius && z1 >= center.Y - radius) {
                target.add(p);
            }
        }
    }

    private boolean hasVariantNeighbour(Collection<ClipboardParcel> neighbours, String variantKey) {
        for (ClipboardParcel neighbour : neighbours) {
            Clipboard neighbourClip = neighbour.getClipboard();
            if (neighbourClip != null && variantKey.equals(neighbourClip.getConfig().getVariantKey())) {
                return true;
            }
        }
        return false;
    }

    private ClipboardParcel placeRandomRec(
            Cartesian2D base, Cartesian2D size, Direction direction, List<Clipboard> clips,
            Set<ClipboardParcel> neighbours, Set<ClipboardParcel> variantNeighbours,
            Set<ClipboardParcel> cloneSoftNeighbours, Collection<ClipboardParcel> previous, Cartesian2D center, SchematicType type,
            boolean neverDropped
    ) {
        Minions.dDistrict("PRR Started");
        if (clips.size() != 0) {
            if (type == SchematicType.BUILD) diagnosticBuildCandidatesSelected++;
            if (type == SchematicType.FILLER) {
                Minions.dFillerQuery("PRR FITS context=%s base=%s size=%s direction=%s candidates=%d",
                        this.context, base, size, direction, clips.size());
            } else {
                Minions.dDistrict("PRR FITS context='%s' base=%s size=%s direction=%s type=%s candidates=%d",
                        this.context, base, size, direction, type, clips.size());
            }
            List<Integer> weights = this.getWeights(clips, center, cloneSoftNeighbours, variantNeighbours);
            int chosenIndex = Minions.getRandomWeighted(weights, this.host.getRandom());
            Clipboard clip = clips.get(chosenIndex);
            if (type == SchematicType.FILLER) {
                Minions.dFillerSelect("PRR CHOSEN context=%s base=%s size=%s clip=%s weight=%d candidateIndex=%d/%d",
                        this.context, base, size, this.describeClip(clip), weights.get(chosenIndex), chosenIndex + 1, clips.size());
            } else {
                Minions.dDistrict("PRR CHOSEN context='%s' base=%s size=%s type=%s clip=%s",
                        this.context, base, size, type, this.describeClip(clip));
            }
            Cartesian2D claimSize = this.getClipboardChunkSize(clip);
            if (claimSize.X <= 0 || claimSize.Y <= 0
                    || claimSize.X > size.X || claimSize.Y > size.Y) {
                if (type == SchematicType.BUILD) diagnosticBuildInvalidFootprint++;
                return null;
            }
            Cartesian2D placementBase = this.getPlacementBase(base, size, claimSize, direction);
            // Never place a roadFacing=true schematic against wilderness or
            // water, even if a district's coarse road-side flags claim otherwise.
            // A rejected orientation is removed from the pool and an alternate
            // build can be tried; we do not silently rotate it toward no road.
            if (clip.getConfig() != null && clip.getConfig().getRoadFacing()
                    && !this.hasRealRoadFrontage(placementBase, claimSize, direction)) {
                Minions.dDistrict("ROAD FRONTAGE REJECT context=%s base=%s size=%s facing=%s clip=%s",
                        this.context, placementBase, claimSize, direction, this.describeClip(clip));
                clips.remove(chosenIndex);
                return this.placeRandomRec(base, size, direction, clips, neighbours, variantNeighbours,
                        cloneSoftNeighbours, previous, center, type, neverDropped);
            }
            ClipboardParcel p = new ClipboardParcel(this.grid, placementBase, claimSize, clip, this.context, type, direction);
            // cloneRadius is a hard limit for BUILD -- an exact duplicate
            // (same groupId) within it is rejected outright, no matter how
            // clonePenalty/cloneRadiusSoftLimit are tuned. The optional soft
            // term above (see getWeights()) only ever discourages placement
            // in the wider ring BEYOND this hard radius (when
            // cloneRadiusSoftLimit is configured larger than cloneRadius) --
            // it never substitutes for this check.
            //
            // FILLER can opt out of this hard block entirely (see
            // PluginConfig.isFillerExemptFromCloneRadius()). Fillers are
            // small, generic, tileable infill pieces (parking lots, plazas,
            // parks) that are normally FINE to repeat nearby, unlike a
            // unique BUILD schematic -- but they were getting the exact
            // same "never duplicate within cloneRadius" treatment. With a
            // large cloneRadius relative to blockSize (smaller blockSize
            // means many more placement attempts packed into the same
            // radius window) and a filler library much smaller than the
            // build library, every available filler groupId nearby can get
            // hard-rejected in turn, exhausting the entire candidate list
            // -- filler fails for the same reason BUILD failed, and if
            // enableFillerTerrainFallback is off there's nothing left to
            // place at all, leaving the raw pregenerated world (often a
            // flat stone plateau) showing through untouched.
            boolean hardBlockApplies = type == SchematicType.BUILD || !PluginConfig.isFillerExemptFromCloneRadius();
            // neverDropped -- used only by the DENSERESIDENTIAL_PRIMARY
            // tier (see placeRandomBuild()) -- short-circuits every
            // rejection check below to false, unconditionally, regardless
            // of what neighbours/variantNeighbours/previous or any
            // PluginConfig toggle would otherwise say. This is deliberate,
            // not merely "very lax": a hand-curated primary schematic is
            // meant to place wherever it fits, full stop, which is exactly
            // what "emphasized, primary, and never dropped" means -- it
            // wouldn't be a meaningful guarantee if it could still lose to
            // a clone-radius or variant check under the right settings.
            boolean exactDuplicateRejected = !neverDropped && hardBlockApplies && neighbours.contains(p);
            // Two independent, separately-toggleable ways a BUILD
            // candidate's variant FAMILY (not necessarily the exact same
            // schematic file) can get it hard-rejected -- see
            // PluginConfig.isVariantHardBlockEnabled() and
            // isVariantUniquePerDistrictEnabled() for the full reasoning
            // on each. Both exist because variantPenalty (see getWeights())
            // is always soft and can only shift weight toward a competing
            // variantKey in the same candidate pool -- it does nothing
            // when every candidate in that pool shares the one variantKey
            // there is to shift away from, which is exactly the case for a
            // BUILD schematic that's the only one fitting a given
            // size/context/direction.
            String variantKey = clip.getConfig().getVariantKey();
            boolean variantRadiusRejected = !neverDropped && type == SchematicType.BUILD && PluginConfig.isVariantHardBlockEnabled()
                    && this.hasVariantNeighbour(variantNeighbours, variantKey);
            boolean variantDistrictRejected = !neverDropped && type == SchematicType.BUILD && PluginConfig.isVariantUniquePerDistrictEnabled()
                    && this.hasVariantNeighbour(previous, variantKey);
            boolean variantRejected = variantRadiusRejected || variantDistrictRejected;
            if (exactDuplicateRejected || variantRejected) {
                if (type == SchematicType.BUILD) {
                    if (exactDuplicateRejected) diagnosticBuildDuplicateRejected++;
                    if (variantRejected) diagnosticBuildVariantRejected++;
                    if (variantRadiusRejected) diagnosticVariantRadiusRejected++;
                    if (variantDistrictRejected) diagnosticVariantDistrictRejected++;
                }
                String reason = variantDistrictRejected ? "VARIANT-DISTRICT" : variantRadiusRejected ? "VARIANT-RADIUS" : "DUPLICATE";
                if (type == SchematicType.FILLER) {
                    Minions.dFillerReject("PRR %s context=%s base=%s size=%s clip=%s",
                            reason, this.context, base, size, this.describeClip(clip));
                } else {
                    Minions.dDistrict("PRR %s REJECTED context='%s' base=%s size=%s type=%s clip=%s",
                            reason, this.context, base, size, type, this.describeClip(clip));
                }
                // Every variant in `clips` sharing this groupId (e.g. the
                // north/east/south/west rotations of the same building) is
                // EQUALLY disqualified by an exact-duplicate rejection --
                // duplicate-checking compares by groupId, not by the
                // specific Clipboard instance. Removing only `clip` left
                // every other rotation of the exact same already-rejected
                // building sitting in the list, so a heavily-weighted
                // groupId could get re-picked (and re-rejected) several
                // times in a row before the recursion ever reached a
                // genuinely different groupId -- the dominant cause of the
                // near-total duplicate-rejection rate driving districts
                // into the 1x1 filler fallback.
                //
                // A variant rejection (either kind) is even broader: EVERY
                // clip sharing this variantKey -- every rotation of every
                // file in the whole variant family, not just this one
                // groupId -- gets stripped in one pass, since the whole
                // family is disqualified, not just the specific file that
                // happened to get picked this round.
                int before = clips.size();
                if (variantRejected) {
                    clips.removeIf(c -> variantKey.equals(c.getConfig().getVariantKey()));
                    Minions.dDistrict("PRR STRIPPED ALL VARIANTS OF variantKey='%s' (%d -> %d candidates remaining)",
                            variantKey, before, clips.size());
                } else {
                    String rejectedGroupId = clip.getGroupId();
                    clips.removeIf(c -> rejectedGroupId.equals(c.getGroupId()));
                    Minions.dDistrict("PRR STRIPPED ALL VARIANTS OF groupId='%s' (%d -> %d candidates remaining)",
                            rejectedGroupId, before, clips.size());
                }
                return this.placeRandomRec(base, size, direction, clips, neighbours, variantNeighbours, cloneSoftNeighbours, previous, center, type, neverDropped);
            } else {
                Minions.dDistrict("PRR FINISHED");
                if (type == SchematicType.BUILD) diagnosticBuildAccepted++;
                return p;
            }
        } else {
            if (type == SchematicType.FILLER) {
                Minions.dFillerQuery("PRR NO FITS context=%s base=%s size=%s direction=%s",
                        this.context, base, size, direction);
            } else {
                diagnosticBuildPoolEmpty++;
                Minions.dDistrict("PRR NO FITS context='%s' base=%s size=%s direction=%s type=%s",
                        this.context, base, size, direction, type);
            }
            return null;
        }
    }

    /** Convert a schematic's block footprint into the grid's chunk footprint. */
    private Cartesian2D getClipboardChunkSize(Clipboard clip) {
        if (clip == null || clip.getSize() == null) {
            return new Cartesian2D(0, 0);
        }
        return new Cartesian2D(clip.getSize().X >> 4, clip.getSize().Z >> 4);
    }

    /**
     * Keep the selected schematic on the road edge represented by the search
     * rectangle. Exact-fit placements are unchanged; smaller DENSERESIDENTIAL
     * placements are simply anchored to the same selected road edge.
     */
    private Cartesian2D getPlacementBase(Cartesian2D base, Cartesian2D searchSize,
                                         Cartesian2D claimSize, Direction direction) {
        int x = base.X;
        int z = base.Y;
        if (direction == Direction.EAST) {
            x = base.X + searchSize.X - claimSize.X;
        } else if (direction == Direction.SOUTH) {
            z = base.Y + searchSize.Y - claimSize.Y;
        }
        return new Cartesian2D(x, z);
    }

    /**
     * describeClip()/describeClips() only ever feed Minions.d* log calls, but their result is built BEFORE the call, whether or
     * not the log is on -- and describeClips() walks every candidate schematic, on every placement attempt. So they return
     * nothing unless one of the debug flags that actually prints them is enabled.
     */
    private static boolean clipDescriptionsWanted() {
        return (PluginConfig.isDistrictDebugEnabled() && PluginConfig.isDistrictVerboseDebugEnabled())
                || PluginConfig.isFillerDebugEnabled()
                || PluginConfig.isDenseResidentialFamilyDebugEnabled();
    }

    private String describeClips(List<Clipboard> clips) {
        if (!clipDescriptionsWanted()) {
            return "";
        }
        if (clips == null || clips.size() == 0) {
            return "[]";
        }

        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < Math.min(8, clips.size()); i++) {
            if (i > 0) {
                result.append(", ");
            }
            result.append(this.describeClip(clips.get(i)));
        }
        if (clips.size() > 8) result.append(", ... +").append(clips.size() - 8).append(" more");
        result.append("]");
        return result.toString();
    }

    private String describeClip(Clipboard clip) {
        if (!clipDescriptionsWanted()) {
            return "";
        }
        if (clip == null) {
            return "null";
        }

        try {
            return "groupId='" + clip.getGroupId()
                    + "', config='" + clip.getConfig().getPath()
                    + "', type=" + clip.getConfig().getSchematicType();
        } catch (Exception e) {
            return clip.toString();
        }
    }

    private List<Integer> getWeights(
            Collection<Clipboard> clips, Cartesian2D center,
            Set<ClipboardParcel> cloneSoftNeighbours, Set<ClipboardParcel> variantNeighbours
    ) {
        // Delegates to Minions.getPlacementWeights() so this logic isn't
        // duplicated between here and UrbanGrid.carveLandmark(), which
        // needs the exact same weighting for landmark selection. The clone
        // term here only ever concerns the OPTIONAL ring beyond the hard
        // cloneRadius wall (cloneSoftNeighbours is empty unless
        // cloneRadiusSoftLimit > cloneRadius) -- placeRandomRec()'s hard
        // rejection handles everything within cloneRadius itself, so this
        // never needs to (and shouldn't) duplicate that.
        return Minions.getPlacementWeights(
                clips, center,
                variantNeighbours, this.VARIANTRADIUS, this.VARIANTPENALTY,
                cloneSoftNeighbours, this.getEffectiveCloneRadiusSoftLimit(), this.getEffectiveClonePenalty(),
                PluginConfig.isNormalizeOddsOfAppearance()
        );
    }

    /**
     * Per-attempt roll deciding whether THIS candidate rectangle is
     * deliberately reserved for filler, before any size/fit search even
     * runs. This is the ONLY thing BUILD_CHANCE controls -- see its own
     * javadoc. A failed roll here must never be treated as "couldn't find
     * a fit, try smaller" by the caller: those are different questions
     * with different correct responses (see nextRecPartition()).
     */
    private boolean rollBuildChance(Cartesian2D base, Cartesian2D size) {
        int buildChance = this.context == ContextType.DENSERESIDENTIAL
                ? PluginConfig.getDenseResidentialBuildChance()
                : this.BUILD_CHANCE;
        boolean buildRoll = this.host.getRandom().getChance(buildChance);
        if (!buildRoll) diagnosticBuildRollFailed++;
        Minions.dFillerRoll("BUILD GATE context=%s base=%s size=%s buildChance=%d result=%s",
                this.context, base, size, buildChance, buildRoll ? "PASS" : "FAIL");
        return buildRoll;
    }

    /**
     * cloneRadiusSoftLimit, but resolved per-context: DENSERESIDENTIAL
     * reads its own dedicated config key (see
     * PluginConfig.getDenseResidentialCloneRadiusSoftLimit()) no matter
     * what the ordinary cloneRadiusSoftLimit is set to, and every other
     * context keeps reading the ordinary one no matter what
     * DENSERESIDENTIAL's is set to -- the two are never allowed to
     * influence each other. Must be called fresh everywhere the value is
     * needed (placeRandomBuild() and getWeights()) rather than cached in a
     * field, same reasoning as CLONERADIUS: it depends on this.context.
     */
    private int getEffectiveCloneRadiusSoftLimit() {
        return this.context == ContextType.DENSERESIDENTIAL
                ? PluginConfig.getDenseResidentialCloneRadiusSoftLimit()
                : PluginConfig.getCloneRadiusSoftLimit();
    }

    /** clonePenalty's per-context counterpart to getEffectiveCloneRadiusSoftLimit() above. */
    private int getEffectiveClonePenalty() {
        return this.context == ContextType.DENSERESIDENTIAL
                ? PluginConfig.getDenseResidentialClonePenalty()
                : PluginConfig.getClonePenalty();
    }

    /**
     * HARD floor nextRecPartition() enforces when it has to split a
     * candidate rectangle further after nothing fit at its current size:
     * a split is only ever made if it leaves both resulting sides at or
     * above this value; otherwise the rectangle is left as-is (terminal,
     * unclaimed for the filler sweep) rather than cut any smaller -- see
     * that branch's comment. DENSERESIDENTIAL reads its own separate key
     * here (PluginConfig.getDenseResidentialBlockSizeMin(), default 1)
     * rather than sharing blockSizeMin with every other context --
     * DENSERESIDENTIAL exists to pack small, repetitive tract-housing
     * lots, which are typically much smaller than blockSizeMin was ever
     * tuned for, so a global floor tight enough for tract housing would
     * be far too permissive for ordinary districts, and vice versa.
     */
    private int getParcelMinSize() {
        return this.context == ContextType.DENSERESIDENTIAL
                ? PluginConfig.getDenseResidentialBlockSizeMin()
                : PluginConfig.getBlockSizeMin();
    }

    private int nextRecPartition(List<ClipboardParcel> parcels, Cartesian2D base, Cartesian2D size) {
        if (size.X <= 0 || size.Y <= 0) {
            return 0;
        }

        Minions.dDistrict("NRP ENTER context='%s' base=%s size=%s", this.context, base, size);

        // BUILD_CHANCE is rolled ONCE, for THIS exact rectangle, before any
        // size/fit search runs -- and a failed roll ends this rectangle's
        // BUILD consideration right here, full stop. It must NOT fall into
        // the "search smaller" logic below: that path exists to recover
        // from a genuine SIZE problem (nothing in the library fits this
        // rectangle), where trying smaller candidate rectangles is exactly
        // the right move. A chance-based rejection isn't a size problem --
        // splitting after one would hand every retry to whatever's small
        // enough to fit the shrinking pieces, systematically favouring
        // small schematics over large ones every time a large candidate's
        // single roll happened to fail, and would turn "roughly X% of
        // space is deliberately left as filler" into "X% of the SMALLEST
        // possible slivers are," which is a completely different (and far
        // less visible) outcome than intended. This rectangle is simply
        // left unclaimed for fillRemainingWithFiller()'s later sweep.
        if (!this.rollBuildChance(base, size)) {
            Minions.dFiller("BUILD GATE FAILED -> intentional filler, no split context=%s base=%s size=%s",
                    this.context, base, size);
            return 0;
        }

        // This is a BUILD-first packing pass for every context.  FILLER is
        // never selected here: a failed search is only a reason to search
        // smaller, never a reason to consume the space with filler.
        ClipboardParcel parcel = this.placeRandomBuild(base, size, parcels, SchematicType.BUILD);
        if (parcel != null) {
            parcels.add(parcel);

            Cartesian2D placedBase = new Cartesian2D(parcel.getChunkX(), parcel.getChunkZ());
            Cartesian2D placedSize = new Cartesian2D(parcel.getChunkSizeX(), parcel.getChunkSizeZ());
            int endX = base.X + size.X;
            int endZ = base.Y + size.Y;
            int score = scoreParcel(parcel, placedSize);

            if (placedBase.Y > base.Y) {
                score += this.nextRecPartition(parcels, base,
                        new Cartesian2D(size.X, placedBase.Y - base.Y));
            }
            if (placedBase.Y + placedSize.Y < endZ) {
                score += this.nextRecPartition(parcels,
                        new Cartesian2D(base.X, placedBase.Y + placedSize.Y),
                        new Cartesian2D(size.X, endZ - placedBase.Y - placedSize.Y));
            }
            if (placedBase.X > base.X) {
                score += this.nextRecPartition(parcels,
                        new Cartesian2D(base.X, placedBase.Y),
                        new Cartesian2D(placedBase.X - base.X, placedSize.Y));
            }
            if (placedBase.X + placedSize.X < endX) {
                score += this.nextRecPartition(parcels,
                        new Cartesian2D(placedBase.X + placedSize.X, placedBase.Y),
                        new Cartesian2D(endX - placedBase.X - placedSize.X, placedSize.Y));
            }
            return score;
        }

        // Nothing fit at this rectangle -- the roll above already PASSED,
        // so this is a genuine SEARCH/size failure, not a chance-based
        // rejection. This is still only a SEARCH failure; no space is
        // claimed.  Keep splitting until every possible smaller region has
        // had a BUILD opportunity.
        Minions.dFiller("BUILD ATTEMPT FAILED (roll passed, nothing fit) -> searching smaller context=%s base=%s size=%s",
                this.context, base, size);
        //
        // parcelMin is a HARD floor: a split only ever happens if it leaves
        // BOTH resulting sides at or above parcelMin (makeCutNoRoad(random,
        // x, min) guarantees that on its own, given x >= 2*min -- see its
        // javadoc). If NEITHER axis can support that, this rectangle is
        // terminal: it's left unclaimed here for the district-wide filler
        // sweep (fillDistrict() -> fillRemainingWithFiller()), exactly like
        // the old 1x1-only terminal case below, just reached at parcelMin
        // instead of always exactly 1x1. No more degrading the floor down
        // toward whatever `size` happens to support -- once a rectangle is
        // at or under parcelMin, it does not get cut any smaller, so you
        // can no longer end up with a chain of adjacent parcels each right
        // at (or under) the configured minimum.
        int parcelMin = this.getParcelMinSize();
        boolean splitY = size.X < size.Y;
        boolean canSplit = splitY ? size.Y >= 2 * parcelMin : size.X >= 2 * parcelMin;
        if (!canSplit) {
            // The preferred (longer) axis can't honour the floor -- see if
            // the other axis can instead before giving up entirely.
            splitY = !splitY;
            canSplit = splitY ? size.Y >= 2 * parcelMin : size.X >= 2 * parcelMin;
        }

        if (canSplit) {
            if (splitY) {
                int cut = Minions.makeCutNoRoad(this.host.getRandom(), size.Y, parcelMin);
                return this.nextRecPartition(parcels, base, new Cartesian2D(size.X, cut))
                        + this.nextRecPartition(parcels,
                        new Cartesian2D(base.X, base.Y + cut),
                        new Cartesian2D(size.X, size.Y - cut));
            }

            int cut = Minions.makeCutNoRoad(this.host.getRandom(), size.X, parcelMin);
            return this.nextRecPartition(parcels, base, new Cartesian2D(cut, size.Y))
                    + this.nextRecPartition(parcels,
                    new Cartesian2D(base.X + cut, base.Y),
                    new Cartesian2D(size.X - cut, size.Y));
        }

        // Neither axis can honour the FULL parcelMin floor -- but refusing
        // to split at all here is worse than the sliver a degraded split
        // produces. This rectangle only reaches this branch because the
        // BUILD_CHANCE roll in placeRandomBuild() (see its javadoc) failed
        // for the FULL rectangle, or nothing fit it -- and that roll is
        // independent PER ATTEMPT. If this rectangle is simply abandoned
        // here, that one roll (default 90% pass, i.e. a genuine 1-in-10
        // chance per district) is the ONLY chance this entire area ever
        // gets, with zero recourse -- exactly what produced districts that
        // came out 100% empty/filler sitting directly next to identically
        // sized, fully-built ones purely by the luck of one coin flip (see
        // BUILD_CHANCE's own javadoc for the log evidence). Splitting
        // anyway, clamped down to whatever this rectangle can actually
        // support (minimum 1), gives each half its OWN independent
        // BUILD_CHANCE roll and its own getFittingClips() search instead
        // of gambling the whole rectangle on a single attempt. Only a
        // rectangle that's already 1 on both axes is truly terminal.
        if (size.X <= 1 && size.Y <= 1) {
            Minions.dDistrict("NRP BUILD EXHAUSTED (1x1, cannot split further) context='%s' base=%s size=%s",
                    this.context, base, size);
            return 0;
        }

        splitY = size.X < size.Y;
        int degradedMin = Math.max(1, Math.min(parcelMin, (splitY ? size.Y : size.X) / 2));
        Minions.dDistrict("NRP DEGRADED SPLIT (parcelMin=%d unreachable) context='%s' base=%s size=%s effectiveMin=%d",
                parcelMin, this.context, base, size, degradedMin);
        if (splitY) {
            int cut = Minions.makeCutNoRoad(this.host.getRandom(), size.Y, degradedMin);
            return this.nextRecPartition(parcels, base, new Cartesian2D(size.X, cut))
                    + this.nextRecPartition(parcels,
                    new Cartesian2D(base.X, base.Y + cut),
                    new Cartesian2D(size.X, size.Y - cut));
        }
        int cut = Minions.makeCutNoRoad(this.host.getRandom(), size.X, degradedMin);
        return this.nextRecPartition(parcels, base, new Cartesian2D(cut, size.Y))
                + this.nextRecPartition(parcels,
                new Cartesian2D(base.X + cut, base.Y),
                new Cartesian2D(size.X - cut, size.Y));
    }

    /** True when an already-selected parcel intersects the search rectangle. */
    private ClipboardParcel findIntersectingParcel(Collection<ClipboardParcel> parcels, Cartesian2D base, Cartesian2D size) {
        int endX = base.X + size.X;
        int endZ = base.Y + size.Y;
        for (ClipboardParcel parcel : parcels) {
            if (parcel == null) {
                continue;
            }
            int px1 = parcel.getChunkX();
            int pz1 = parcel.getChunkZ();
            int px2 = px1 + parcel.getChunkSizeX();
            int pz2 = pz1 + parcel.getChunkSizeZ();
            if (px1 < endX && px2 > base.X && pz1 < endZ && pz2 > base.Y) {
                return parcel;
            }
        }
        return null;
    }

    /**
     * After BUILD packing is complete, populate every remaining rectangle
     * with the largest filler footprint that actually fits.  Search boundaries
     * never become claims; only the returned ClipboardParcel does.
     */
    private void fillRemainingWithFiller(List<ClipboardParcel> parcels, Cartesian2D base, Cartesian2D size, Map<String, Integer> fillerUsage) {
        if (size.X <= 0 || size.Y <= 0) {
            return;
        }

        ClipboardParcel blocker = this.findIntersectingParcel(parcels, base, size);
        if (blocker != null) {
            int endX = base.X + size.X;
            int endZ = base.Y + size.Y;
            int ix1 = Math.max(base.X, blocker.getChunkX());
            int iz1 = Math.max(base.Y, blocker.getChunkZ());
            int ix2 = Math.min(endX, blocker.getChunkX() + blocker.getChunkSizeX());
            int iz2 = Math.min(endZ, blocker.getChunkZ() + blocker.getChunkSizeZ());

            if (iz1 > base.Y) {
                this.fillRemainingWithFiller(parcels, base, new Cartesian2D(size.X, iz1 - base.Y), fillerUsage);
            }
            if (iz2 < endZ) {
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(base.X, iz2), new Cartesian2D(size.X, endZ - iz2), fillerUsage);
            }
            if (ix1 > base.X && iz2 > iz1) {
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(base.X, iz1), new Cartesian2D(ix1 - base.X, iz2 - iz1), fillerUsage);
            }
            if (ix2 < endX && iz2 > iz1) {
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(ix2, iz1), new Cartesian2D(endX - ix2, iz2 - iz1), fillerUsage);
            }
            return;
        }

        ClipboardParcel filler = this.placeLargestFiller(base, size, parcels, fillerUsage);
        if (filler != null) {
            parcels.add(filler);
            int endX = base.X + size.X;
            int endZ = base.Y + size.Y;
            Cartesian2D placedBase = new Cartesian2D(filler.getChunkX(), filler.getChunkZ());
            Cartesian2D placedSize = new Cartesian2D(filler.getChunkSizeX(), filler.getChunkSizeZ());

            if (placedBase.Y > base.Y) {
                this.fillRemainingWithFiller(parcels, base,
                        new Cartesian2D(size.X, placedBase.Y - base.Y), fillerUsage);
            }
            if (placedBase.Y + placedSize.Y < endZ) {
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(base.X, placedBase.Y + placedSize.Y),
                        new Cartesian2D(size.X, endZ - placedBase.Y - placedSize.Y), fillerUsage);
            }
            if (placedBase.X > base.X) {
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(base.X, placedBase.Y),
                        new Cartesian2D(placedBase.X - base.X, placedSize.Y), fillerUsage);
            }
            if (placedBase.X + placedSize.X < endX) {
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(placedBase.X + placedSize.X, placedBase.Y),
                        new Cartesian2D(endX - placedBase.X - placedSize.X, placedSize.Y), fillerUsage);
            }
            return;
        }

        // No filler fits this whole free rectangle.  Split the search rather
        // than abandoning it, so a smaller filler can still populate it.
        if (size.X > 1 || size.Y > 1) {
            if (size.X < size.Y) {
                int cut = Minions.makeCut(this.host.getRandom(), size.Y);
                this.fillRemainingWithFiller(parcels, base, new Cartesian2D(size.X, cut), fillerUsage);
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(base.X, base.Y + cut), new Cartesian2D(size.X, size.Y - cut), fillerUsage);
            } else {
                int cut = Minions.makeCut(this.host.getRandom(), size.X);
                this.fillRemainingWithFiller(parcels, base, new Cartesian2D(cut, size.Y), fillerUsage);
                this.fillRemainingWithFiller(parcels,
                        new Cartesian2D(base.X + cut, base.Y), new Cartesian2D(size.X - cut, size.Y), fillerUsage);
            }
        } else {
            Minions.dFillerFallback("NO FILLER FITS 1x1 context=%s base=%s", this.context, base);
            if (PluginConfig.isFillerTerrainFallbackEnabled()) {
                parcels.add(new ClipboardParcel(this.grid, base, new Cartesian2D(1, 1),
                        (Clipboard)null, this.context, SchematicType.FILLER, Direction.NONE));
            } else {
                Minions.w("UNFILLED DISTRICT LOT context=%s chunk=(%d,%d) roadDirection=%s: no suitable NORMAL filler schematic; terrain fallback disabled",
                        this.context, base.X, base.Y, this.findRoad(base, size));
            }
        }
    }

    /** Select only the largest physical filler footprint that fits the free area. */
    private ClipboardParcel placeLargestFiller(Cartesian2D base, Cartesian2D size, Collection<ClipboardParcel> previous, Map<String, Integer> fillerUsage) {
        Direction direction = this.findRoad(base, size);
        List<Clipboard> candidates = this.getFillerCandidates(direction);
        int largestArea = 0;
        List<Clipboard> fitting = new LinkedList<>();

        for (Clipboard clip : candidates) {
            Cartesian3D clipSize = clip == null ? null : clip.getSize();
            if (clipSize == null) {
                continue;
            }
            int w = clipSize.X >> 4;
            int h = clipSize.Z >> 4;
            if (w <= 0 || h <= 0 || w > size.X || h > size.Y) {
                continue;
            }
            fitting.add(clip);
            largestArea = Math.max(largestArea, w * h);
        }

        if (fitting.isEmpty()) {
            // A context-specific library can lack the small footprint needed
            // by a residual lot. Retry with the ordinary context-free filler
            // pool BEFORE splitting again or abandoning the cell. Never make
            // up a terrain parcel to conceal a missing schematic.
            Set<Clipboard> seen = new HashSet<>(candidates);
            Direction[] directions = direction == Direction.NONE
                    ? new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NONE}
                    : new Direction[]{direction};
            for (Direction d : directions) {
                for (Clipboard clip : this.host.getClipboardProvider().getAll(null, SchematicType.FILLER, d)) {
                    if (!seen.add(clip) || clip == null || clip.getSize() == null) continue;
                    int w = clip.getSize().X >> 4;
                    int h = clip.getSize().Z >> 4;
                    if (w > 0 && h > 0 && w <= size.X && h <= size.Y) {
                        fitting.add(clip);
                        largestArea = Math.max(largestArea, w * h);
                    }
                }
            }
            if (!fitting.isEmpty()) {
                Minions.dFillerFallback("CONTEXT-FREE INFILL context=%s base=%s size=%s candidates=%d",
                        this.context, base, size, fitting.size());
            }
        }
        if (fitting.isEmpty()) {
            return null;
        }

        // Diversity is a secondary objective: keep the filler footprint
        // reasonably large, but among the largest-ish fitting footprints
        // choose the least-used exact schematic. This makes variants cycle
        // instead of repeatedly selecting the first/most-favored filler. If
        // only one filler can fit, it remains eligible and coverage is
        // never sacrificed.
        //
        // "largest-ish" (not just the single exact-largest area) is
        // deliberate: with a strict == largestArea match, any open area
        // where only ONE schematic happens to hold the single biggest
        // footprint would ALWAYS pick that one schematic -- every smaller,
        // otherwise-suitable filler gets structurally excluded from ever
        // being chosen there, regardless of clonePenalty/variantPenalty/
        // normalizeOddsOfAppearance, since none of that weighting runs
        // until AFTER this pool is built. fillerLargestAreaTolerancePercent
        // widens the qualifying band so genuinely comparable-sized fillers
        // (not just the single largest) compete for selection too -- 0
        // reproduces the old exact-match-only behaviour.
        int minQualifyingArea = Math.max(1,
                largestArea - (largestArea * PluginConfig.getFillerLargestAreaTolerancePercent()) / 100);
        List<Clipboard> preferred = new LinkedList<>();
        int minUsage = Integer.MAX_VALUE;
        for (Clipboard clip : fitting) {
            int w = clip.getSize().X >> 4;
            int h = clip.getSize().Z >> 4;
            if (w * h < minQualifyingArea) {
                continue;
            }
            int usage = fillerUsage.getOrDefault(this.fillerUsageKey(clip), 0);
            if (usage < minUsage) {
                minUsage = usage;
                preferred.clear();
            }
            if (usage == minUsage) {
                preferred.add(clip);
            }
        }

        List<Clipboard> selectedPool = preferred.isEmpty() ? fitting : preferred;
        if (fitting.isEmpty()) {
            return null;
        }

        Cartesian2D center = new Cartesian2D(base.X + size.X / 2, base.Y + size.Y / 2);
        Set<ClipboardParcel> neighbours = this.host.getNeighbours(center, this.CLONERADIUS);
        // Enforce clone spacing by the configured radius for provisional builds as
        // well as already-committed neighbours. These placements live only in
        // `previous` until the district is committed: adding the entire list
        // made a zero/small cloneRadius behave as district-wide hard exclusion.
        this.addPreviousWithinRadius(neighbours, previous, center, this.CLONERADIUS);
        Set<ClipboardParcel> variantNeighbours = this.host.getNeighbours(center, this.VARIANTRADIUS);
        this.addPreviousWithinRadius(variantNeighbours, previous, center, this.VARIANTRADIUS);
        int softLimit = this.getEffectiveCloneRadiusSoftLimit();
        boolean softEnabled = softLimit > this.CLONERADIUS;
        Set<ClipboardParcel> cloneSoftNeighbours = softEnabled
                ? this.host.getNeighbours(center, softLimit) : Collections.emptySet();
        if (softEnabled) {
            cloneSoftNeighbours.addAll(previous);
        }

        ClipboardParcel placed = this.placeRandomRec(base, size, direction, selectedPool, neighbours, variantNeighbours,
                cloneSoftNeighbours, previous, center, SchematicType.FILLER, false);
        if (placed != null && placed.getClipboard() != null) {
            String key = this.fillerUsageKey(placed.getClipboard());
            fillerUsage.put(key, fillerUsage.getOrDefault(key, 0) + 1);
        }
        return placed;
    }

    private String fillerUsageKey(Clipboard clip) {
        if (clip == null) {
            return "<null>";
        }
        String groupId = clip.getGroupId();
        return groupId == null ? "identity:" + System.identityHashCode(clip) : "group:" + groupId;
    }

    /**
     * Fetch every schematic for this context/type/direction (unfiltered by
     * size -- see ClipboardProvider.getAll()) and keep only the ones that
     * physically FIT WITHIN searchSize (w <= searchSize.X && h <= searchSize.Y).
     * This is deliberately a "fits within", not "equals", comparison: a
     * candidate rectangle from nextRecPartition()'s splitting has no tie to
     * any schematic's actual footprint, so demanding an exact match (the old
     * getFit() behaviour) meant a BUILD only ever surfaced here by chance.
     * Downstream, placeRandomRec() already claims the schematic at its own
     * true chunk footprint (claimSize) and anchors it to the road edge via
     * getPlacementBase() -- it was always written to handle claimSize being
     * smaller than searchSize, only the candidate fetch itself wasn't.
     */
    private List<Clipboard> getFittingClips(ContextType context, SchematicType schematicType, Direction direction, Cartesian2D searchSize) {
        List<Clipboard> all = this.host.getClipboardProvider().getAll(context, schematicType, direction);
        List<Clipboard> fitting = new ArrayList<>(all.size());
        for (Clipboard clip : all) {
            if (clip == null || clip.getSize() == null) {
                continue;
            }
            int w = clip.getSize().X >> 4;
            int h = clip.getSize().Z >> 4;
            if (w > 0 && h > 0 && w <= searchSize.X && h <= searchSize.Y) {
                fitting.add(clip);
            }
        }
        return fitting;
    }

    /** Gather context fillers, falling back to context-free fillers when needed. */
    private List<Clipboard> getFillerCandidates(Direction direction) {
        List<Clipboard> candidates = new LinkedList<>();
        // contains() on a LinkedList is a scan; the same question asked of a hash set is not (Clipboard compares by identity)
        Set<Clipboard> seen = new HashSet<>();
        Direction[] directions = direction == Direction.NONE
                ? new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NONE}
                : new Direction[]{direction};
        for (Direction d : directions) {
            List<Clipboard> clips = this.host.getClipboardProvider().getAll(this.context, SchematicType.FILLER, d);
            for (Clipboard clip : clips) {
                if (seen.add(clip)) {
                    candidates.add(clip);
                }
            }
        }
        return candidates;
    }

    private void fastPartition(List<ClipboardParcel> parcels, Cartesian2D base, Cartesian2D size) {
        ClipboardParcel parcel;
        if ((parcel = this.placeRandomBuildForSure(base, size, parcels)) != null) {
            parcels.add(parcel);
        } else {
            Minions.dDistrict("Found absolutely no schems for context '%s' and size '%s' ", this.context, size);
            parcels.add(new ClipboardParcel(this.grid, base, size, (Clipboard)null, this.context, SchematicType.FILLER, Direction.NONE));
        }
    }

    private static final int scoreParcel(ClipboardParcel parcel, Cartesian2D size) {
        return parcel.getSchematicType().equals(SchematicType.BUILD) ? scoreArea(size) : -scoreArea(size);
    }

    private static final int scoreArea(Cartesian2D size) {
        return size.X * size.Y;
    }

    private Direction findRoad(Cartesian2D base, Cartesian2D size) {
        List<Direction> directions = new LinkedList<>();
        if (this.roadNorth && this.base.Y == base.Y) {
            directions.add(Direction.NORTH);
        }

        if (this.roadSouth && this.base.Y + this.size.Y == base.Y + size.Y) {
            directions.add(Direction.SOUTH);
        }

        if (this.roadWest && this.base.X == base.X) {
            directions.add(Direction.WEST);
        }

        if (this.roadEast && this.base.X + this.size.X == base.X + size.X) {
            directions.add(Direction.EAST);
        }

        return directions.size() == 0 ? Direction.NONE : directions.get(this.host.getRandom().getRandomInt(directions.size()));
    }

    @Override
    public void initDistrict(Cartesian2D base, Cartesian2D size, UrbanGrid grid) {
        this.base = base;
        this.size = size;
        this.grid = grid;
        this.host = grid;
    }

    /** Fill against another host than the tile's own (see BlockHost). Parcels are still built on the tile's grid. */
    public void useHost(DistrictHost host) {
        this.host = host;
    }
}