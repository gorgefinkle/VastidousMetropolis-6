package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanBoatParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.plugin.PluginConfig;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places depth-gated boats/ships onto open-water OceanParcels.
 *
 * Runs once per ocean tile, from UrbanGrid.placeOceanIfPresent(), right after
 * the OceanParcel carve loop and reclaim. A boat does NOT replace the ocean
 * chunk: it swaps in an OceanBoatParcel (an OceanParcel subclass), so the
 * water is still carved and the boat is pasted on top of it.
 *
 * Per candidate chunk:
 *   1. density roll (OceanConfig.getOceanBoatsChancePercent());
 *   2. the whole 16x16 footprint must be open water, not just the centre;
 *   3. boats are fetched (context OCEAN, 1x1) and gated by the water's real
 *      depth under the whole footprint (see depthAllows()): shallow boats on
 *      the shallow shelf, mid boats in mid-depth water, deep boats only where
 *      the whole hull is in the DEEP zone, and every boat clears its own draft;
 *   4. exact duplicates (same groupId) within the clone radius are rejected;
 *      inside the soft radius the odds of a repeat are cut, not removed. BIG
 *      builds (see cloneRadiiFor()) use a much larger clone radius than the
 *      standard one, and checking it reads the ships already placed on this
 *      tile and on already-built neighbouring tiles.
 *
 * Uses its own Random stream (seeded from the tile) instead of the grid's,
 * so enabling boats never shifts the random draws district carving makes
 * afterwards in the same tile.
 *
 * Boat schematics need CONFIG.json: context ["ocean"], schematicType "BUILD",
 * roadFacing false (so they're also stored under Direction.NONE), footprint
 * 16x16, optional "depthTier": "shore" | "mid" | "deep".
 */
public class OceanBoatPlacer {
    /** Every sampled footprint point must be at least this many blocks inside the shoreline. */
    private static final double FOOTPRINT_MARGIN_BLOCKS = 6.0;
    /** Boats spanning more chunks than this on either axis are skipped (paste crosses chunk borders). */
    private static final int MAX_SPAN_CHUNKS = 16;

    private final UrbanGrid grid;
    private final OceanProvider ocean;
    private final GridRandom random;
    private final int cloneRadius;
    private final int cloneRadiusSoftLimit;
    private final OceanShipRegistry shipRegistry;
    /** Water depth at a block column, cached: footprint checks re-sample the same points for every candidate. */
    private final Map<Long, Integer> depthCache = new HashMap<Long, Integer>();

    public OceanBoatPlacer(UrbanGrid grid, long worldSeed, int tileRootX, int tileRootZ,
                           int cloneRadius, int cloneRadiusSoftLimit) {
        this.grid = grid;
        this.ocean = grid.getOceanProvider();
        this.random = new GridRandom(worldSeed ^ 0x4F4345414E424F41L, tileRootX, tileRootZ);
        this.cloneRadius = cloneRadius;
        this.cloneRadiusSoftLimit = cloneRadiusSoftLimit;
        this.shipRegistry = grid.getOceanShipRegistry();
    }

    /** Scans the inclusive chunk rectangle and returns how many boats were placed. */
    public int populateOceanBoats(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        int chance = OceanConfig.getOceanBoatsChancePercent();
        int placed = 0;
        int total = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        Minions.dOcean("[BOAT_PLACER] START: scanning %d chunks, x=[%d,%d] z=[%d,%d], chance=%d%%",
                total, minChunkX, maxChunkX, minChunkZ, maxChunkZ, chance);

        logDepthCensus(minChunkX, maxChunkX, minChunkZ, maxChunkZ);

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (tryPlaceBoat(cx, cz, chance)) {
                    placed++;
                }
            }
        }
        Minions.dOcean("[BOAT_PLACER] DONE: placed %d boat(s) of %d chunks scanned",
                placed, total);
        return placed;
    }

    private boolean tryPlaceBoat(int chunkX, int chunkZ, int chance) {
        Parcel existing = this.grid.getParcel(chunkX, chunkZ);
        // Exactly OceanParcel: skips reclaimed districts/roads, and any cell that already has a boat.
        if (existing == null) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) SKIP: no parcel exists", chunkX, chunkZ);
            return false;
        }
        if (existing.getClass() != OceanParcel.class) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) SKIP: not OceanParcel (is %s)", chunkX, chunkZ, existing.getClass().getSimpleName());
            return false;
        }

        OceanParcel oceanParcel = (OceanParcel) existing;
        Minions.dOcean(
                "[BOAT_DEBUG] chunk=(%d,%d) depth=%d",
                chunkX,
                chunkZ,
                oceanParcel.getWaterColumnDepthBlocks()
        );
        if (!oceanParcel.isOpenWater()) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) SKIP: not open water", chunkX, chunkZ);
            return false;
        }

        if (!this.random.getChance(chance)) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) SKIP: density roll failed (chance=%d%%)", chunkX, chunkZ, chance);
            return false;
        }

        // Which boats may sit here is decided by the water's real depth under
        // the whole footprint -- see depthAllows().
        Minions.dOcean("[BOAT_PLACER] (%d,%d) CANDIDATE: depth=%d tier=%s shore-distance=%.0f",
                chunkX, chunkZ, oceanParcel.getWaterColumnDepthBlocks(), oceanParcel.getDepthTier(),
                this.ocean.oceanDistanceInsideShoreBlock(chunkX * 16.0 + 8.0, chunkZ * 16.0 + 8.0));

        // One lookup per tier, merged: a boat with no depthTier comes back from
        // every call, so de-duplicate.
        Set<Clipboard> all = new LinkedHashSet<Clipboard>();
        for (OceanDepthTier t : OceanDepthTier.values()) {
            all.addAll(this.grid.getClipboardProvider().getFitForOcean(
                    new Cartesian2D(1, 1), t.string, SchematicType.BUILD, Direction.NONE));
        }
        if (all.isEmpty()) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) FAIL: no boat candidates at all", chunkX, chunkZ);
            return false;
        }
        List<Clipboard> candidates = new LinkedList<Clipboard>();
        int footprintFailed = 0;
        int depthFailed = 0;
        for (Clipboard clip : all) {
            if (!footprintFits(chunkX, chunkZ, clip)) {
                footprintFailed++;
            } else if (!depthAllows(chunkX, chunkZ, clip)) {
                depthFailed++;
            } else {
                candidates.add(clip);
            }
        }
        if (candidates.isEmpty()) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) SKIP: 0 of %d candidates usable (footprint failed=%d, depth gate failed=%d)",
                    chunkX, chunkZ, all.size(), footprintFailed, depthFailed);
            return false;
        }
        Minions.dOcean("[BOAT_PLACER] (%d,%d) QUERY: %d of %d candidates usable (footprint failed=%d, depth gate failed=%d)",
                chunkX, chunkZ, candidates.size(), all.size(), footprintFailed, depthFailed);

        Clipboard chosen = pickBoat(chunkX, chunkZ, candidates);
        if (chosen == null) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) FAIL: all candidates rejected (duplicate within clone radius)", chunkX, chunkZ);
            return false;
        }
        Minions.dOcean("[BOAT_PLACER] (%d,%d) CHOSEN: %s (group=%s)", chunkX, chunkZ, chosen.getConfig().getBuildName(), chosen.getGroupId());

        // Pre-validate: check collision against registered ships before pasting.
        int buildHeight =  PluginConfig.getBuildHeight();
        OceanBoundingBox bbox = new OceanBoundingBox(chunkX, chunkZ, chosen.getSize(), buildHeight);
        Minions.dOcean("[BOAT_PLACER] (%d,%d) COLLISION_CHECK: bbox=%s, registered_ships=%d", chunkX, chunkZ, bbox, this.shipRegistry.getShipCount());

        if (!this.shipRegistry.canPlace(bbox)) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) FAIL: collision detected with existing ship", chunkX, chunkZ);
            return false;
        }

        // Registers itself over the plain OceanParcel (see OceanBoatParcel).
        try {
            new OceanBoatParcel(this.grid, new Cartesian2D(chunkX, chunkZ), chosen);
            this.shipRegistry.register(bbox, chosen, chunkX, chunkZ);
            Minions.dOcean("[BOAT_PLACER] (%d,%d) SUCCESS: placed %s (tier=%s, ships_now=%d)",
                    chunkX, chunkZ, chosen.getConfig().getBuildName(), chosen.getConfig().getDepthTier(), this.shipRegistry.getShipCount());
            return true;
        } catch (Exception e) {
            Minions.dOcean("[BOAT_PLACER] (%d,%d) EXCEPTION: %s", chunkX, chunkZ, e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * The boat's whole footprint (real schematic width x length, anchored at
     * this chunk's corner) must lie over plain open-water OceanParcels and
     * every sampled point must be well inside the shoreline.
     */
    private boolean footprintFits(int chunkX, int chunkZ, Clipboard clip) {
        Cartesian3D size = clip.getSize();
        int spanX = (size.X + 15) >> 4;
        int spanZ = (size.Z + 15) >> 4;
        if (spanX > MAX_SPAN_CHUNKS || spanZ > MAX_SPAN_CHUNKS) {
            return false;
        }
        for (int cx = chunkX; cx < chunkX + spanX; cx++) {
            for (int cz = chunkZ; cz < chunkZ + spanZ; cz++) {
                Parcel p = this.grid.peekParcel(cx, cz);
                if (p == null || p.getClass() != OceanParcel.class || !((OceanParcel) p).isOpenWater()) {
                    return false;
                }
            }
        }
        double x0 = chunkX * 16.0;
        double z0 = chunkZ * 16.0;
        for (int dx : sampleOffsets(size.X)) {
            for (int dz : sampleOffsets(size.Z)) {
                if (this.ocean.oceanDistanceInsideShoreBlock(x0 + dx, z0 + dz) < FOOTPRINT_MARGIN_BLOCKS) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Depth gate. The water depth is sampled across the boat's whole footprint
     * and compared with the boat's depthTier:
     *   no tier          any water deep enough for the hull;
     *   shallow / shore  the WHOLE footprint on the shallow shelf;
     *   mid              the middle of the footprint in mid-depth water;
     *   deep             the WHOLE footprint in the DEEP zone.
     * On top of that every boat must clear its own draft (the schematic's
     * ground level: how far the hull sits below the waterline) with the
     * shallowest point of the footprint, so no hull is pasted into the sea
     * floor.
     */
    private boolean depthAllows(int chunkX, int chunkZ, Clipboard clip) {
        Cartesian3D size = clip.getSize();
        int minDepth = Integer.MAX_VALUE;
        int maxDepth = 0;
        double x0 = chunkX * 16.0;
        double z0 = chunkZ * 16.0;
        for (int dx : sampleOffsets(size.X)) {
            for (int dz : sampleOffsets(size.Z)) {
                int d = depthAt((int) (x0 + dx), (int) (z0 + dz));
                minDepth = Math.min(minDepth, d);
                maxDepth = Math.max(maxDepth, d);
            }
        }
        // getBottom(0) = -(hull blocks below the waterline), by the paste's own arithmetic.
        int draft = Math.max(0, -clip.getBottom(0));
        if (minDepth <= draft + 1) {
            return false;
        }

        String tier = clip.getConfig().getDepthTier();
        OceanDepthTier wanted = tier == null ? null : OceanDepthTier.getByString(tier);
        // R30ea checks each tier first, then runs the full hull-floor
        // safety pass even when no tier is specified.
        if (wanted != null) {
            switch (wanted) {
                case SHORE:
                    if (maxDepth > OceanConfig.getShallowTierMaxDepth()) return false;
                    break;
                case MID:
                    int centre = depthAt((int) (x0 + size.X / 2.0), (int) (z0 + size.Z / 2.0));
                    if (OceanDepthTier.getTier(centre) != OceanDepthTier.MID) return false;
                    break;
                case DEEP:
                    if (minDepth < OceanConfig.getDeepTierMinDepth()) return false;
                    break;
                default:
                    return false;
            }
        }
        // Seabed features can protrude above the floor; checking only
        // sampleOffsets() misses those in the interior hull footprint.
        for (int dx = 0; dx < size.X; dx++) {
            for (int dz = 0; dz < size.Z; dz++) {
                int columnDepth = depthAt((int) x0 + dx, (int) z0 + dz);
                if (columnDepth <= draft + OceanSeabed.maximumFeatureHeight(columnDepth) + 1) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Ocean-only water column depth at a block column (0 outside open water), cached. */
    private int depthAt(int blockX, int blockZ) {
        long key = ((long) blockX << 32) ^ (blockZ & 0xffffffffL);
        Integer cached = this.depthCache.get(key);
        if (cached != null) {
            return cached;
        }
        double dist = this.ocean.oceanDistanceInsideShoreBlock(blockX, blockZ);
        int depth = dist > 0 ? this.ocean.waterColumnDepthBlocks(blockX, blockZ, dist) : 0;
        this.depthCache.put(key, depth);
        return depth;
    }

    /** Logs how many open-water chunks of this scan fall in each depth tier (debug only). */
    private void logDepthCensus(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ) {
        if (!PluginConfig.isOceanDebugEnabled()) {
            return;
        }
        int shore = 0, mid = 0, deep = 0;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Parcel p = this.grid.peekParcel(cx, cz);
                if (p != null && p.getClass() == OceanParcel.class && ((OceanParcel) p).isOpenWater()) {
                    switch (((OceanParcel) p).getDepthTier()) {
                        case SHORE: shore++; break;
                        case MID: mid++; break;
                        default: deep++; break;
                    }
                }
            }
        }
        Minions.dOcean("[BOAT_PLACER] depth census (open-water chunks): shallow=%d (<=%d blocks) mid=%d deep=%d (>=%d blocks)",
                shore, OceanConfig.getShallowTierMaxDepth(), mid, deep, OceanConfig.getDeepTierMinDepth());
    }

    /** 0, 8, 16, ... plus the last block of the extent (for a 16 extent: 0, 8, 15). */
    private static int[] sampleOffsets(int extent) {
        int last = Math.max(0, extent - 1);
        int n = last / 8 + 1;
        boolean addLast = last % 8 != 0;
        int[] out = new int[n + (addLast ? 1 : 0)];
        for (int i = 0; i < n; i++) {
            out[i] = i * 8;
        }
        if (addLast) {
            out[n] = last;
        }
        return out;
    }

    /**
     * {hard, soft} clone radius in chunks for this build.
     *
     * A schematic's own oceanCloneRadius / oceanCloneRadiusSoftLimit CONFIG
     * entries win. Otherwise BIG builds -- footprint at least bigMinChunks
     * chunks on either axis, or any DEEP-tier build -- use the much larger
     * generator.ocean.boats.bigCloneRadius / bigCloneRadiusSoftLimit; everything
     * else uses the standard generator.cloneRadius / cloneRadiusSoftLimit. A
     * big build's radius is never smaller than the standard one.
     */
    private int[] cloneRadiiFor(Clipboard clip) {
        SchematicConfig config = clip.getConfig();
        int bigHard = Math.max(this.cloneRadius, OceanConfig.getOceanBoatsBigCloneRadius());
        int bigSoft = Math.max(this.cloneRadiusSoftLimit, OceanConfig.getOceanBoatsBigCloneRadiusSoftLimit());

        if (config.getOceanCloneRadius() > 0) {
            int hard = config.getOceanCloneRadius();
            int soft = config.getOceanCloneRadiusSoftLimit() > 0
                    ? config.getOceanCloneRadiusSoftLimit()
                    : hard + Math.max(0, bigSoft - bigHard);
            return new int[]{hard, Math.max(hard, soft)};
        }
        if (isBigBuild(clip)) {
            return new int[]{bigHard, Math.max(bigHard, bigSoft)};
        }
        return new int[]{this.cloneRadius, Math.max(this.cloneRadius, this.cloneRadiusSoftLimit)};
    }

    private boolean isBigBuild(Clipboard clip) {
        if (OceanDepthTier.getByString(clip.getConfig().getDepthTier()) == OceanDepthTier.DEEP) {
            return true;
        }
        int minChunks = OceanConfig.getOceanBoatsBigMinChunks();
        if (minChunks <= 0) {
            return false;
        }
        Cartesian3D size = clip.getSize();
        return Math.max((size.X + 15) >> 4, (size.Z + 15) >> 4) >= minChunks;
    }

    private Clipboard pickBoat(int chunkX, int chunkZ, List<Clipboard> candidates) {
        // Hard block: same groupId within the clone radius. Soft ring: a repeat is only accepted with reduced odds.
        // Each candidate has its own radius (big builds use a much larger one), so look up the
        // already-placed ships once, out to the largest radius any candidate needs.
        int searchRadius = 0;
        for (Clipboard clip : candidates) {
            int[] r = cloneRadiiFor(clip);
            searchRadius = Math.max(searchRadius, Math.max(r[0], r[1]));
        }
        List<OceanShipRegistry.PlacedShip> nearby = this.grid.peekOceanShipsNear(chunkX, chunkZ, searchRadius);

        List<Clipboard> pool = new LinkedList<Clipboard>(candidates);
        int attempts = 0;
        while (!pool.isEmpty()) {
            Clipboard clip = pool.get(this.random.getRandomInt(pool.size()));
            String id = clip.getGroupId();
            int[] radii = cloneRadiiFor(clip);
            int hard = radii[0];
            int soft = radii[1];
            int nearest = nearestDuplicateDistance(nearby, chunkX, chunkZ, id, Math.max(hard, soft));
            boolean hardBlocked = nearest >= 0 && nearest <= hard;
            boolean softBlocked = !hardBlocked && nearest >= 0 && nearest <= soft
                    && soft > hard
                    && !this.random.getChance(softAcceptPercent(nearest, hard, soft));

            attempts++;
            if (!hardBlocked && !softBlocked) {
                Minions.dOcean("[BOAT_PLACER] (%d,%d) pickBoat: accepted %s after %d attempts (clone radius %d/%d)", chunkX, chunkZ, id, attempts, hard, soft);
                return clip;
            }

            Minions.dOcean("[BOAT_PLACER] (%d,%d) pickBoat: rejected %s (hardBlocked=%s, softBlocked=%s, nearest=%d, clone radius %d/%d)",
                    chunkX, chunkZ, id, hardBlocked, softBlocked, nearest, hard, soft);

            final String rejectedId = id;
            for (java.util.Iterator<Clipboard> it = pool.iterator(); it.hasNext(); ) {
                if (rejectedId.equals(it.next().getGroupId())) {
                    it.remove();
                }
            }
        }
        Minions.dOcean("[BOAT_PLACER] (%d,%d) pickBoat: exhausted all candidates after %d attempts", chunkX, chunkZ, attempts);
        return null;
    }

    /** Chance (0-100) a duplicate at this distance is still accepted: 0 at the hard radius, ramping to 100 at the soft limit. */
    private static int softAcceptPercent(int distance, int hard, int soft) {
        int span = soft - hard;
        return span <= 0 ? 100 : Math.max(0, Math.min(100, (distance - hard) * 100 / span));
    }

    /** Chebyshev distance (chunks) to the closest already-placed boat with this groupId within `radius`, or -1 if none. */
    private static int nearestDuplicateDistance(List<OceanShipRegistry.PlacedShip> ships, int chunkX, int chunkZ, String groupId, int radius) {
        int best = -1;
        for (OceanShipRegistry.PlacedShip ship : ships) {
            if (!groupId.equals(ship.getGroupId())) {
                continue;
            }
            int d = Math.max(Math.abs(ship.chunkX - chunkX), Math.abs(ship.chunkZ - chunkZ));
            if (d <= radius && (best < 0 || d < best)) {
                best = d;
            }
        }
        return best;
    }
}