package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.grid.urbanGrid.provider.DecayProviderNormal.Ctx;
import ch.k42.metropolis.grid.urbanGrid.provider.DecayProviderNormal.GNode;
import ch.k42.metropolis.minions.DecayOption;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * The tree-canopy / sagging-branch growth system: turns a decayed
 * building's exposed support surface into a network of branches that
 * sag, chain down toward the ground, and occasionally fork.
 *
 * Kept separate from DecayProviderNormal, which owns the block-destruction
 * pass itself plus the unrelated slime and vine decorative growth systems.
 * This class calls back into `owner` (the DecayProviderNormal instance that
 * created it) for anything genuinely shared with those other systems --
 * block probing (safeBlockAt/safeRelative), leaf placement, the bounds
 * check, the isSupporting() material rule, and handing finished branch
 * anchors off to slime seeding -- rather than duplicating that shared
 * state and risking it drifting out of sync.
 */
final class DecayProviderBranch {
    // Branch density scales with available growth surface so small structures
    // do not get the same branch budget as a large building/district.
    private static final int CANDIDATES_PER_BRANCH_DENSITY_UNIT = 10;
    // Rough building-footprint-sized bucket used to judge a candidate's
    // height RELATIVE TO ITS OWN NEIGHBORHOOD instead of relative to the
    // whole district. Without this, a single tall structure's absolute
    // height dominates every global height comparison and every other
    // building in the district is statistically starved of branch starts.
    //
    // This also sets the connected-component merge range in
    // buildCellComponents() (Moore-adjacent cells merge into one
    // "building" for start-selection fairness). Two cells this size
    // touching corner-to-corner can be up to ~2x this value apart, so a
    // larger constant here risks merging buildings across an ordinary
    // street gap into a single fairness vote. Kept smaller than a typical
    // building-to-building gap for that reason, at some cost to how well
    // a single very wide building's own footprint gets treated as one
    // component (a wide building can still legitimately split into a few
    // adjacent components; that just means it gets a couple of starting
    // votes instead of exactly one, which is a much smaller problem than
    // silently merging two separate buildings).
    private static final int BUILDING_CELL_SIZE = 6;
    // FIXED per-building candidate budget, not a total split across every
    // building in the pass. This was originally a flat total (8192) shared
    // by componentCount buildings -- fine when generateGrowth() ran once
    // per building (componentCount==1, so the whole 8192 went to it), but
    // with tileWideGrowth/districtWideGrowth batching hundreds of buildings
    // into one generateGrowth() call, that same flat total got divided by
    // componentCount, so EVERY building's candidate pool -- and therefore
    // the branch budget derived from it in generateGrowth()'s
    // candidatesPerUnit/densityBasedMax math -- shrank as the tile got
    // bigger, exactly backwards from what a bigger city should produce.
    // Keeping this fixed per building means the tile's total candidate
    // pool (and branch budget) scales UP with how many buildings are in
    // the pass, the same way it already implicitly did for a single
    // building before tile/district-wide batching existed.
    private static final int MAX_GROWTH_CANDIDATES_PER_BUILDING = 64;

    private static final BlockFace[] HORIZONTAL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };
    private static final BlockFace[] ALL_FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    // These blocks are physically too thin to be useful as standalone
    // growth supports. They may still count as ordinary supporting blocks
    // elsewhere; this list only controls growth-candidate seeding.
    // Substring match (not an exact Material list) so every fence variant
    // -- FENCE, NETHER_FENCE, IRON_FENCE (iron bars), and every wood-species
    // *_FENCE / *_FENCE_GATE -- is caught without enumerating each one.
    private static final Set<String> THIN_STANDALONE_SUPPORT_MATERIAL_CONTAINS = ImmutableSet.of(
            "FENCE"
    );

    private final DecayProviderNormal owner;
    private final Random random;

    DecayProviderBranch(DecayProviderNormal owner, Random random) {
        this.owner = owner;
        this.random = random;
    }

    private int randomInt(int min, int max) {
        if (max <= min) {
            return min;
        }
        return min + this.random.nextInt(max - min + 1);
    }

    /**
     * Return value from buildBranch() that carries both where the branch
     * actually ended AND whether it ended because the sag curve hit a wall
     * mid-arc (as opposed to running cleanly to its intended lower anchor).
     *
     * wallHit is true only when the sag curve walked into a solid block
     * that was inside bounds and in a loaded chunk -- i.e. an actual wall
     * face the branch physically struck. It is false for a clean run to
     * `b`, and also false when the walk stopped for environmental reasons
     * (outside bounds, unloaded chunk) -- those stops don't produce a
     * meaningful wall face to re-anchor a chain from.
     */
    private static final class BranchResult {
        final GNode endpoint;
        final boolean wallHit;
        BranchResult(GNode endpoint, boolean wallHit) {
            this.endpoint = endpoint;
            this.wallHit = wallHit;
        }
    }

    void generateGrowth(World world, DecayOption options, Ctx ctx) {
        if (PluginConfig.getMasterBranchIntensity() <= 0 || options.getLeavesScale() <= 0) {
            return;
        }

        // Building components are computed ONCE, up front, straight from the
        // full unsampled candidate set -- not from whatever survives
        // sampling. Computing it any later means components (and therefore
        // per-building fairness) can only reflect buildings that happened to
        // survive an earlier, building-blind sampling pass; a short building
        // that lost that earlier competition would already be invisible by
        // the time components are built, making the "fairness" downstream
        // meaningless for it.
        List<GNode> rawCandidatePoints = this.collectRawGrowthCandidates(ctx);
        if (rawCandidatePoints.isEmpty()) {
            return;
        }
        Map<Long, Integer> cellComponents = this.buildCellComponents(rawCandidatePoints);

        List<GNode> candidatePoints = this.sampleGrowthCandidates(rawCandidatePoints, cellComponents);
        if (candidatePoints.isEmpty()) {
            return;
        }

        int units = this.computeGrowthUnits(cellComponents);

        // Do not give a tiny structure the same random branch budget as a large
        // one.  This was the main source of the "fuzz": small structures could
        // roll many starts even though they had very little usable surface,
        // causing overlapping short pieces, thickness, hangs, and anchor growth
        // to pile up in a small area.  Large structures still reach the configured
        // branchMaxCount because their candidate density is much higher.
        //
        // masterBranchIntensity is a separate master multiplier (0-100+%) on
        // top of branchMinCount/branchMaxCount -- it scales overall branch/
        // slime structural density up or down without having to retune the
        // shape (min/max spread) those two configure. Independent of
        // masterGrowthIntensity, which only scales leaf coverage.
        double branchIntensityMultiplier = PluginConfig.getMasterBranchIntensity() / 100.0;
        int minBranchCount = Math.max(0, (int) Math.round(PluginConfig.getBranchMinCount() * branchIntensityMultiplier));
        int configuredMaxBranchCount = Math.max(minBranchCount,
                (int) Math.round(PluginConfig.getBranchMaxCount() * branchIntensityMultiplier));
        int candidatesPerUnit = Math.max(1, candidatePoints.size() / units);
        int densityBasedMax = Math.max(minBranchCount,
                candidatesPerUnit / CANDIDATES_PER_BRANCH_DENSITY_UNIT);
        int effectiveMaxBranchCount = Math.min(configuredMaxBranchCount, densityBasedMax);

        int target = 0;
        for (int i = 0; i < units; i++) {
            target += this.randomInt(minBranchCount, effectiveMaxBranchCount);
        }

        Minions.dNode("growth branch budget: candidates=%d units=%d candidatesPerUnit=%d effectiveMax=%d target=%d",
                candidatePoints.size(), units, candidatesPerUnit, effectiveMaxBranchCount, target);
        if (target <= 0) {
            return;
        }

        // Every seed comes from upperPool -- still picked per-building and
        // height-weighted via pickHeightWeightedIndex(), so a house's roof
        // and a skyscraper's roof are equally "near the top" for seeding
        // purposes, same as before. What's gone is the top-level pairing
        // step that used to aim each seed at another real growth candidate
        // before doing anything else. A seed now starts its own
        // ground-seeking descent immediately via chainBranchToGround(),
        // whose own hop-by-hop trajectory (still governed by
        // branchMaxHeight, branchMaxVerticalToHorizontalRatio,
        // branchMinSpan, branchMaxHorizontalSpan, and its direction-spread
        // throw) already sends each hop sideways as well as down -- so a
        // seed on a real building can still incidentally land on or near a
        // neighboring structure on its way to the ground, it's just no
        // longer a guaranteed, deliberately-aimed first hop the way
        // pairing was. A skyscraper's seed simply has much farther to
        // fall, which means far more recursive hops, which means far more
        // chances to fork along the way -- so taller structures naturally
        // end up with visibly more branching complexity without any
        // separate "tall buildings get extra branches" rule.
        //
        // The old top-level branchMinGroundClearanceRatio/Floor check is
        // gone too, not just moved -- it existed to catch a landing that
        // ended up too close to true ground, computed from `upper`'s own
        // column and applied to a POSSIBLY DISTANT `realLower` reached by
        // pairing. Now that there's no separate landing point to check --
        // a seed just starts descending from itself -- that whole problem
        // doesn't exist. chainBranchToGround() already checks the ACTUAL
        // current anchor's real distance to ground on every single hop (see
        // its own findGroundBelow(anchor, ...) call), which is a strictly
        // more accurate version of the same idea with no risk of the
        // column mismatch the ratio check had. What chainBranchToGround()
        // did NOT already handle -- and initialClearance below fixes -- is
        // that its minimumChainLinksNearGround=3 floor used to apply
        // equally regardless of how much height a chain started with,
        // which forced short buildings through pointless extra hops (or
        // outright chain failure hunting for horizontal room) that a
        // skyscraper's chain legitimately needs but a house's doesn't.
        List<GNode> upperPool = new ArrayList<>();
        int seedCount = Math.max(4, target * 4);
        this.seedNodePools(candidatePoints, seedCount, world, upperPool, cellComponents);

        List<Block> branchAnchors = new ArrayList<>();
        List<GNode> placedAnchorPoints = new ArrayList<>();
        int built = 0;
        int reseeds = 0;
        // No longer needs to be inflated for a top-level validation/retry
        // budget -- that whole stage is gone. The real per-hop retrying now
        // happens entirely inside chainBranchToGround()'s own `attempts`
        // loop; this outer budget only needs to cover popping seeds and
        // occasionally retrying a too-close-to-existing-anchor seed.
        int maxAttempts = target * 4 + seedCount * 2;
        int attempts = 0;

        while (built < target && attempts < maxAttempts) {
            attempts++;

            if (upperPool.isEmpty()) {
                if (reseeds < PluginConfig.getBranchNodeReseedAttempts()) {
                    reseeds++;
                    this.seedNodePools(candidatePoints, seedCount, world, upperPool, cellComponents);
                    continue;
                }
                break;
            }

            int upperIndex = this.pickHeightWeightedIndex(upperPool, cellComponents);
            GNode seed = upperPool.remove(upperIndex);
            Minions.dNodeVerbose("SEED (%d,%d,%d) popped -- live: %s",
                    seed.x, seed.y, seed.z, this.describeNodeState(seed, world));
            if (this.isTooCloseToExistingAnchor(seed, placedAnchorPoints)) {
                continue;
            }

            Block seedAnchor = this.owner.safeBlockAt(world, seed.x, seed.y, seed.z);
            if (seedAnchor != null) {
                branchAnchors.add(seedAnchor);
            }

            // How much true-ground clearance this SPECIFIC seed started
            // with -- computed once, here, and threaded unchanged through
            // every recursive chainBranchToGround() call for this seed's
            // whole descent. This is what lets a house's chain (small
            // initialClearance) exit as soon as it's near the ground,
            // while a skyscraper's chain (large initialClearance) still
            // has to prove itself over a few real hops first -- see
            // chainBranchToGround()'s own comment on
            // minimumChainLinksNearGround for why a flat 3-hop minimum for
            // every chain regardless of starting height was penalizing
            // short buildings specifically. A null findGroundBelow() (no
            // ground found within its scan range) is treated as an
            // unknown/very deep drop, keeping the conservative full 3-hop
            // requirement.
            Integer seedGroundBelow = this.findGroundBelow(seed, world);
            int initialClearance = seedGroundBelow != null ? seed.y - seedGroundBelow : Integer.MAX_VALUE;

            // Bias the starting heading away from whatever solid
            // geometry this seed is actually mounted against, instead
            // of picking uniformly across the full circle. A blind
            // uniform angle sent a large, consistent fraction of every
            // hop's 270 attempts straight back into the seed's own
            // support -- directionSpread's +-80..95 degrees just
            // re-randomizes within that same blocked half of the
            // circle, it doesn't get you out of it. computeOutwardAngle()
            // returns null when the seed has no solid horizontal
            // neighbour to avoid (e.g. rooftop seeds only supported from
            // DOWN), in which case uniform random really is the right
            // fallback.
            Double outwardAngle = this.computeOutwardAngle(seed, world);
            double approachAngle = outwardAngle != null
                    ? outwardAngle
                    : this.random.nextDouble() * Math.PI * 2;
            List<GNode> existingAnchors = new ArrayList<>(placedAnchorPoints);
            List<GNode> chainAnchors = new ArrayList<>();
            this.chainBranchToGround(seed, approachAngle, 0, world, ctx, branchAnchors, existingAnchors, chainAnchors, false, initialClearance);

            // Only reserve `seed`'s own 20-block exclusion radius (via
            // isTooCloseToExistingAnchor() above) once its chain actually
            // built something. A seed whose chain comes back empty --
            // overwhelmingly the common case for a low-clearance seed that
            // exits at chainLength=0 with nothing built -- produces zero
            // visible geometry, so there's nothing there to protect from
            // overcrowding. Reserving the radius anyway (the old
            // behaviour: `placedAnchorPoints.add(seed)` ran unconditionally,
            // right after the too-close check) meant every dead seed
            // permanently poisoned a real chunk of the tile for the rest
            // of this entire generateGrowth() call, actively blocking
            // later, potentially far more useful seeds from ever getting
            // popped nearby -- on a real city, where short/ground-level
            // candidates vastly outnumber tall ones, this compounds fast
            // enough to make MOST of upperPool unreachable well before the
            // budget or target ever came into it.
            if (!chainAnchors.isEmpty()) {
                placedAnchorPoints.add(seed);
                placedAnchorPoints.addAll(chainAnchors);
                built++;
            }
        }

        for (Block anchor : branchAnchors) {
            this.owner.trySeedSlime(anchor, options, ctx);
        }
    }

    /**
     * Total "growth units" for this pass, summed PER BUILDING rather than
     * derived from a single sqrt() of the whole pass's combined footprint.
     *
     * The old formula -- sqrt(ctx.footprintChunks) -- was fine when
     * generateGrowth() ran once per building: footprintChunks WAS that
     * one building's own footprint. With tileWideGrowth/districtWideGrowth
     * batching hundreds of independent buildings into a single
     * generateGrowth() call, ctx.footprintChunks became the COMBINED
     * footprint of every building in the tile, and sqrt() of that huge
     * combined number collapsed all of them down into what the budget math
     * effectively treated as one single (very dense) structure -- crushing
     * candidatesPerUnit, and therefore effectiveMaxBranchCount, down toward
     * the branchMinCount floor almost no matter how much real candidate
     * surface the tile actually had. A tile with millions of supportHits
     * and thousands of surviving candidates was still landing on
     * effectiveMax=4/target=248, because units alone (sqrt of ~3800+
     * chunks) was already in the 60s before candidates ever entered the
     * calculation.
     *
     * Summing sqrt(cellCount) PER COMPONENT (component == one building,
     * same grouping sampleGrowthCandidates()/seedNodePools()/
     * pickHeightWeightedIndex() already use for fairness) instead reduces
     * to approximately the old per-building behaviour when there's exactly
     * one building in the pass, but scales up correctly as more buildings
     * are added to the batch -- each building contributes its own units,
     * instead of every building diluting everyone else's.
     */
    private int computeGrowthUnits(Map<Long, Integer> cellComponents) {
        Map<Integer, Integer> cellCountByComponent = new HashMap<>();
        for (Integer component : cellComponents.values()) {
            cellCountByComponent.merge(component, 1, Integer::sum);
        }
        int units = 0;
        for (int cellCount : cellCountByComponent.values()) {
            units += Math.max(1, (int) Math.round(Math.sqrt(cellCount)));
        }
        return Math.max(1, units);
    }

    /**
     * Picks an index from `nodes`, biased toward height -- but relative to
     * each candidate's OWN building, not globally. Buildings are identified
     * via cellComponents (see buildCellComponents()): connected building-
     * cells count as one building regardless of footprint size, so a giant
     * tower and a single small building get an equal shot at being picked.
     * Once a building is picked, height weighting is scoped to just that
     * building's own points -- preferring its own top, whether that's 8
     * blocks up or 200.
     */
    private int pickHeightWeightedIndex(List<GNode> nodes, Map<Long, Integer> cellComponents) {
        if (nodes.size() <= 1) {
            return 0;
        }

        Map<Integer, List<Integer>> byBuilding = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            GNode n = nodes.get(i);
            Integer component = cellComponents.get(buildingCellKey(n.x, n.z));
            int key = component != null ? component : -1;
            byBuilding.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        List<Integer> buildingKeys = new ArrayList<>(byBuilding.keySet());
        int chosenBuilding = buildingKeys.get(this.random.nextInt(buildingKeys.size()));
        List<Integer> buildingIndices = byBuilding.get(chosenBuilding);

        List<Integer> order = new ArrayList<>(buildingIndices);
        order.sort((a, b) -> Integer.compare(nodes.get(b).y, nodes.get(a).y));

        int totalWeight = 0;
        int[] weights = new int[order.size()];
        for (int rank = 0; rank < order.size(); rank++) {
            int weight = Math.max(1, order.size() - rank);
            if (rank < Math.max(1, order.size() / 4)) {
                weight *= 3;
            }
            weights[rank] = weight;
            totalWeight += weight;
        }

        int roll = this.random.nextInt(totalWeight);
        for (int rank = 0; rank < order.size(); rank++) {
            roll -= weights[rank];
            if (roll < 0) {
                return order.get(rank);
            }
        }
        return order.get(order.size() - 1);
    }

    private boolean isTooCloseToExistingAnchor(GNode point, List<GNode> placedAnchorPoints) {
        return this.isTooCloseToExistingAnchor(point, placedAnchorPoints, 20.0);
    }

    private boolean isTooCloseToExistingAnchor(GNode point, List<GNode> placedAnchorPoints, double minSeparation) {
        for (GNode anchor : placedAnchorPoints) {
            if (distance3D(point, anchor) < minSeparation) {
                return true;
            }
        }
        return false;
    }

    private List<GNode> collectRawGrowthCandidates(Ctx ctx) {
        // Every exposed, empty neighbour of every qualifying support hit,
        // completely unsampled. This is the SAME set of Block queries
        // collectGrowthCandidates() used to make while sampling inline --
        // materializing the full list first costs no extra Block lookups,
        // it just lets buildCellComponents() and sampleGrowthCandidates()
        // see every building's true candidate set, including buildings that
        // a height-banded sample would otherwise have thrown away before
        // fairness ever got a chance to run.
        List<GNode> raw = new ArrayList<>();
        for (int i = 0; i < ctx.supportHits.size(); i++) {
            // Rehydrate Bukkit wrappers only for this bounded flush. The
            // PendingBuild itself stores only packed primitive coordinates.
            Block support = world.getBlockAt(
                    ctx.supportHits.x(i), ctx.supportHits.y(i), ctx.supportHits.z(i));
            if (this.isThinStandaloneSupport(support)) {
                continue;
            }
            // Glass panes (etc) are still ordinary decay/vine supports, but
            // are excluded from branch anchors independently of that rule.
            if (!this.owner.isBranchableSupport(support)) {
                continue;
            }
            for (Block neighbour : this.getOutwardNeighbours(support)) {
                if (!neighbour.isEmpty()) {
                    continue;
                }
                raw.add(new GNode(neighbour.getX(), neighbour.getY(), neighbour.getZ()));
            }
        }
        return raw;
    }

    /**
     * Caps the raw candidate set down, per building, to
     * MAX_GROWTH_CANDIDATES_PER_BUILDING -- dividing the
     * budget fairly ACROSS BUILDINGS first, then by height WITHIN each
     * building -- not by height across the whole tile/district at once.
     *
     * A single tile-wide height-band reservoir (the previous approach) let
     * every ordinary building's candidates pile into whichever one or two
     * bands cover typical building heights, competing with every OTHER
     * ordinary building on the tile for that one shared slice of the cap --
     * while a single tall tower's candidates spread across most or all of
     * the bands, largely uncontested. The surviving pool ended up
     * disproportionately full of tall-building points before
     * pickHeightWeightedIndex()'s per-building fairness ever got a chance to
     * run on it. Splitting by building component first means a modest house
     * and a skyscraper both get their own guaranteed share of the overall
     * cap, and within that share, their own guaranteed share of their own
     * top -- regardless of how many other buildings of what height happen
     * to share the same tile or district.
     */
    private List<GNode> sampleGrowthCandidates(List<GNode> rawCandidates, Map<Long, Integer> cellComponents) {
        Map<Integer, List<GNode>> byComponent = new HashMap<>();
        for (GNode g : rawCandidates) {
            Integer component = cellComponents.get(buildingCellKey(g.x, g.z));
            int key = component != null ? component : -1;
            byComponent.computeIfAbsent(key, k -> new ArrayList<>()).add(g);
        }

        int componentCount = Math.max(1, byComponent.size());
        int perComponentCap = MAX_GROWTH_CANDIDATES_PER_BUILDING;

        List<GNode> combined = new ArrayList<>(Math.min(perComponentCap * componentCount, rawCandidates.size()));
        for (List<GNode> componentCandidates : byComponent.values()) {
            combined.addAll(this.reservoirSampleByHeightBand(componentCandidates, perComponentCap));
        }
        return combined;
    }

    /**
     * Reservoir-samples one building's candidates down to `cap`, split
     * across up to 10 height bands local to THIS building's own Y range, so
     * a thin roof/spire -- which contributes far fewer raw candidates than
     * the building's bulk -- gets a guaranteed slice of that building's
     * budget instead of rarely surviving a flat random keep/replace.
     */
    private List<GNode> reservoirSampleByHeightBand(List<GNode> candidates, int cap) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (GNode g : candidates) {
            minY = Math.min(minY, g.y);
            maxY = Math.max(maxY, g.y);
        }
        int bandCount = Math.max(1, Math.min(10, maxY - minY + 1));
        int span = Math.max(1, maxY - minY + 1);
        int perBandCap = Math.max(1, cap / bandCount);

        List<List<GNode>> bandReservoirs = new ArrayList<>(bandCount);
        int[] bandSeen = new int[bandCount];
        for (int i = 0; i < bandCount; i++) {
            bandReservoirs.add(new ArrayList<>(perBandCap));
        }

        for (GNode candidate : candidates) {
            int band = (int) ((long) (candidate.y - minY) * bandCount / span);
            band = Math.min(bandCount - 1, Math.max(0, band));

            List<GNode> reservoir = bandReservoirs.get(band);
            bandSeen[band]++;
            if (reservoir.size() < perBandCap) {
                reservoir.add(candidate);
            } else {
                int slot = this.random.nextInt(bandSeen[band]);
                if (slot < perBandCap) {
                    reservoir.set(slot, candidate);
                }
            }
        }

        List<GNode> combined = new ArrayList<>(cap);
        for (List<GNode> reservoir : bandReservoirs) {
            combined.addAll(reservoir);
        }
        return combined;
    }

    /**
     * Horizontal direction pointing AWAY from this seed's solid
     * neighbours -- i.e. away from whatever wall/mass it's actually
     * mounted on -- so the first throw (and, via directionSpread,
     * every throw derived from it) starts biased toward open space
     * instead of uniformly across the full 360 degrees. Averages the
     * unit vectors of every solid horizontal neighbour and returns
     * the opposite heading.
     *
     * Returns null when the seed has no solid HORIZONTAL neighbour at
     * all -- e.g. it's sitting on a rooftop and is only supported
     * from DOWN -- since there's no "into the wall" direction to
     * avoid in that case and a uniform random angle is the correct
     * fallback.
     */
    private Double computeOutwardAngle(GNode seed, World world) {
        Block seedBlock = this.owner.safeBlockAt(world, seed.x, seed.y, seed.z);
        if (seedBlock == null) {
            return null;
        }
        double sumX = 0, sumZ = 0;
        int solidCount = 0;
        for (BlockFace face : HORIZONTAL_FACES) {
            Block neighbour = this.owner.safeRelative(seedBlock, face);
            if (this.isValidBranchAnchorBlock(neighbour)) {
                sumX += face.getModX();
                sumZ += face.getModZ();
                solidCount++;
            }
        }
        if (solidCount == 0) {
            return null;
        }
        // The averaged solid-neighbour vector points INTO the
        // wall/mass; outward is simply the opposite heading.
        return Math.atan2(-sumZ, -sumX);
    }

    private List<Block> getOutwardNeighbours(Block block) {
        List<Block> neighbours = new ArrayList<>(5);
        if (block.getY() < block.getWorld().getMaxHeight() - 1) {
            neighbours.add(block.getRelative(0, 1, 0));
        }
        if ((block.getX() & 15) != 0) {
            neighbours.add(block.getRelative(-1, 0, 0));
        }
        if ((block.getX() & 15) != 15) {
            neighbours.add(block.getRelative(1, 0, 0));
        }
        if ((block.getZ() & 15) != 0) {
            neighbours.add(block.getRelative(0, 0, -1));
        }
        if ((block.getZ() & 15) != 15) {
            neighbours.add(block.getRelative(0, 0, 1));
        }
        return neighbours;
    }

    // Restored from R30ea's shipped branch-support checks.  These rules
    // reject thin, unsupported anchors before spending a branch ticket.
    private boolean isExcludedSeedSupport(Block block) {
        if (block == null) {
            return true;
        }
        String name = block.getType().name();
        if (name.contains("TORCH") || name.contains("IRON_BARS") || name.equals("CHAIN")) {
            return true;
        }
        for (String thin : THIN_STANDALONE_SUPPORT_MATERIAL_CONTAINS) {
            if (name.contains(thin)) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidBranchAnchorBlock(Block block) {
        return block != null && !this.isExcludedSeedSupport(block)
                && this.owner.isBranchableSupport(block) && this.owner.isSupporting(block);
    }

    private boolean hasValidBranchAnchorSupport(GNode node, World world) {
        Block seed = this.owner.safeBlockAt(world, node.x, node.y, node.z);
        if (seed == null) {
            return false;
        }
        for (BlockFace face : ALL_FACES) {
            if (this.isValidBranchAnchorBlock(this.owner.safeRelative(seed, face))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasValidSeedSupport(GNode node, World world) {
        Block seed = this.owner.safeBlockAt(world, node.x, node.y, node.z);
        return seed != null && seed.isEmpty() && this.hasValidBranchAnchorSupport(node, world);
    }

    private void placeTrackedBranchLeaf(Block block) {
        this.owner.placeLeaf(block);
        BranchDamageWatch.watch(block);
    }

    private boolean isThinStandaloneSupport(Block block) {
        String name = block.getType().name();
        for (String thin : THIN_STANDALONE_SUPPORT_MATERIAL_CONTAINS) {
            if (name.contains(thin)) {
                return true;
            }
        }
        for (BlockFace face : HORIZONTAL_FACES) {
            Block neighbour = this.owner.safeRelative(block, face);
            if (this.isValidBranchAnchorBlock(neighbour)) {
                return false;
            }
        }
        return true;
    }

    private void seedNodePools(List<GNode> pool, int count, World world, List<GNode> upperPool,
                               Map<Long, Integer> cellComponents) {
        if (pool.isEmpty()) {
            return;
        }

        // Stratify by BUILDING COMPONENT first, then by height band WITHIN
        // each building -- not by height across the whole pool at once. A
        // pool-wide band split has the same problem sampleGrowthCandidates()
        // was fixed for: at tile scale the pool spans every building's
        // height at once, so ordinary buildings' points all land in
        // whichever band(s) cover typical building heights and compete with
        // every OTHER ordinary building for that one shared slice of the
        // sample budget, while a single tall tower's points spread across
        // most or all of the bands, largely uncontested. That left
        // upperPool -- the actual pool branch starts get picked from --
        // disproportionately full of tall-building points, regardless of
        // how many ordinary buildings were actually on the tile.
        Map<Integer, List<GNode>> byComponent = new HashMap<>();
        for (GNode g : pool) {
            Integer component = cellComponents.get(buildingCellKey(g.x, g.z));
            int key = component != null ? component : -1;
            byComponent.computeIfAbsent(key, k -> new ArrayList<>()).add(g);
        }
        int componentCount = Math.max(1, byComponent.size());
        int perComponentBudget = Math.max(1, (count + componentCount - 1) / componentCount);

        // Fully qualify (sky-check + band-stratify) each building's own pool
        // FIRST, uncapped -- i.e. every sky-visible candidate that building
        // actually has, not just its fair-share slice of `count`. That true
        // supply is needed below: a building's fair share is often just 1
        // candidate once a tile has more than a couple hundred buildings,
        // and canSeeSky() alone rejects most exposed-neighbor blocks (walls
        // facing another building, undersides of overhangs, etc). Asking
        // qualifyByHeightBand() for exactly `perComponentBudget` candidates
        // and taking whatever it hands back means a building whose one
        // allotted slot happens to fail canSeeSky() contributes NOTHING,
        // permanently -- with no way for the backfill pass below to know
        // that building actually had other, perfectly good candidates just
        // past that cap. Multiply that across every building on a tile and
        // upperPool could end up empty even though the tile as a whole has
        // plenty of valid candidates.
        Map<Integer, List<GNode>> qualifiedByComponent = new HashMap<>();
        for (Map.Entry<Integer, List<GNode>> entry : byComponent.entrySet()) {
            List<GNode> componentPool = entry.getValue();
            qualifiedByComponent.put(entry.getKey(),
                    this.qualifyByHeightBand(componentPool, componentPool.size(), world));
        }

        // Pass 1: every building gets its fair share of `count`, capped by
        // how much it actually has available.
        List<GNode> qualified = new ArrayList<>();
        Map<Integer, Integer> taken = new HashMap<>();
        for (Map.Entry<Integer, List<GNode>> entry : qualifiedByComponent.entrySet()) {
            List<GNode> available = entry.getValue();
            int take = Math.min(perComponentBudget, available.size());
            for (int i = 0; i < take; i++) {
                qualified.add(available.get(i));
            }
            taken.put(entry.getKey(), take);
        }

        // Pass 2: backfill from buildings that had MORE candidates than
        // their fair share, so slack left by buildings that couldn't fill
        // their share (too few sky-visible candidates) doesn't just vanish.
        if (qualified.size() < count) {
            for (Map.Entry<Integer, List<GNode>> entry : qualifiedByComponent.entrySet()) {
                if (qualified.size() >= count) {
                    break;
                }
                List<GNode> available = entry.getValue();
                int already = taken.getOrDefault(entry.getKey(), 0);
                for (int i = already; i < available.size() && qualified.size() < count; i++) {
                    qualified.add(available.get(i));
                }
            }
        }

        if (qualified.isEmpty()) {
            return;
        }

        qualified.sort((a, b) -> Integer.compare(b.y, a.y));

        // Keep only points near the top of THEIR OWN NEIGHBORHOOD, not
        // relative to a single global sort across the whole district. A
        // global cutoff meant a district containing one tall structure
        // locked every other building out of upperPool entirely -- nothing
        // short of the tall structure's own height could ever land in the
        // globally-highest slice. Judging each point against its own local
        // (x,z) neighborhood's min/max Y lets a modest building's roof
        // qualify as "near the top" on equal footing with a skyscraper's
        // own upper levels.
        Map<Long, int[]> cellYRange = new HashMap<>();
        for (GNode g : qualified) {
            long key = buildingCellKey(g.x, g.z);
            int[] range = cellYRange.get(key);
            if (range == null) {
                cellYRange.put(key, new int[]{g.y, g.y});
            } else {
                range[0] = Math.min(range[0], g.y);
                range[1] = Math.max(range[1], g.y);
            }
        }
        for (GNode g : qualified) {
            int[] range = cellYRange.get(buildingCellKey(g.x, g.z));
            double localSpan = Math.max(1, range[1] - range[0]);
            double heightFrac = (g.y - range[0]) / localSpan; // 0..1 within its OWN neighborhood
            if (heightFrac >= 0.35) {
                upperPool.add(g);
            }
        }
    }

    /**
     * Qualifies (sky-checked, band-stratified) candidates for ONE building
     * down to roughly `count`, split across up to 10 height bands local to
     * THIS building's own Y range. A flat random sample of a building's
     * points is dominated by whichever height happens to have the most raw
     * exposed surface -- on a tower that is almost never the thin roof or
     * spire at the very top. Stratifying by local height band first means
     * every band -- including that building's own true top -- gets a fair,
     * guaranteed slice of its budget.
     */
    private List<GNode> qualifyByHeightBand(List<GNode> pool, int count, World world) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (GNode g : pool) {
            minY = Math.min(minY, g.y);
            maxY = Math.max(maxY, g.y);
        }

        int bandCount = Math.max(1, Math.min(10, maxY - minY + 1));
        int span = Math.max(1, maxY - minY + 1);
        List<List<GNode>> bands = new ArrayList<>();
        for (int i = 0; i < bandCount; i++) {
            bands.add(new ArrayList<>());
        }
        for (GNode g : pool) {
            int band = (int) ((long) (g.y - minY) * bandCount / span);
            band = Math.min(bandCount - 1, Math.max(0, band));
            bands.get(band).add(g);
        }
        for (List<GNode> band : bands) {
            Collections.shuffle(band, this.random);
        }

        int[] cursor = new int[bandCount];
        List<GNode> qualified = new ArrayList<>();
        int perBand = Math.max(1, (count + bandCount - 1) / bandCount);

        // First pass: every band gets its fair share, highest band included,
        // regardless of how much raw surface that band actually has.
        for (int b = 0; b < bandCount; b++) {
            List<GNode> band = bands.get(b);
            int taken = 0;
            while (taken < perBand && cursor[b] < band.size()) {
                GNode candidate = band.get(cursor[b]++);
                if (this.canSeeSky(candidate, world)) {
                    qualified.add(candidate);
                    taken++;
                }
            }
        }

        // Second pass: bands with little exposed surface at that height leave
        // budget unused. Backfill from the highest band downward so leftover
        // budget still favors height coverage instead of whichever band is
        // most numerous.
        for (int b = bandCount - 1; b >= 0 && qualified.size() < count; b--) {
            List<GNode> band = bands.get(b);
            while (qualified.size() < count && cursor[b] < band.size()) {
                GNode candidate = band.get(cursor[b]++);
                if (this.canSeeSky(candidate, world)) {
                    qualified.add(candidate);
                }
            }
        }

        return qualified;
    }

    private boolean canSeeSky(GNode point, World world) {
        int maxY = world.getMaxHeight();
        for (int y = point.y + 1; y < maxY; y++) {
            if (!world.getBlockAt(point.x, y, point.z).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the empty point is within 1 block of the solid point on
     * every axis -- face, edge, AND corner contact all count, not just a
     * shared face. Diagonal attachments are an intended part of the
     * branch look. This still keeps a collision endpoint tied to the
     * solid block that actually stopped it (nothing more than 1 block
     * away in any axis); we never snap to some other, unrelated support.
     */
    private boolean isAdjacentTouch(GNode emptyPoint, int solidX, int solidY, int solidZ) {
        int dx = Math.abs(emptyPoint.x - solidX);
        int dy = Math.abs(emptyPoint.y - solidY);
        int dz = Math.abs(emptyPoint.z - solidZ);
        return dx <= 1 && dy <= 1 && dz <= 1 && (dx + dy + dz) > 0;
    }

    private BranchResult buildBranch(GNode a, GNode b, World world, Ctx ctx) {
        return this.buildBranch(a, b, world, ctx, false);
    }

    /**
     * Build a branch candidate without modifying the world until the whole
     * candidate has passed validation.
     *
     * smoothHorizontalSag is used by horizontal chain segments.  They still
     * sag between their supports, but use a smoother curve with a near-zero
     * slope at both ends so a long bridge does not make an unrealistically
     * sharp drop immediately after leaving a building.
     */
    private BranchResult buildBranch(GNode a, GNode b, World world, Ctx ctx, boolean smoothHorizontalSag) {
        int thickness = this.randomInt(PluginConfig.getBranchMinThickness(), PluginConfig.getBranchMaxThickness());

        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double dz = b.z - a.z;
        double horizDist = Math.sqrt(dx * dx + dz * dz);
        double straightDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(2, (int) Math.round(straightDist));
        double sagDepth = horizDist * PluginConfig.getBranchSagRatio();

        // Build the entire candidate in memory first.  Do not modify the
        // world until the branch has a valid, physically supported endpoint.
        // This prevents rejected sag/collision candidates from leaving behind
        // partial leaf geometry that appears to float in mid-air.
        List<GNode> pendingPoints = new ArrayList<>();
        List<GNode> pendingHangingLeaves = new ArrayList<>();

        GNode prev = null;
        GNode lastReached = a;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double sagCurve;
            if (smoothHorizontalSag && dy == 0.0 && horizDist > 0.0) {
                // Horizontal chains are genuine unsupported spans and must sag.
                // sin(pi*t)^2 already has zero slope AND zero curvature-jump at
                // t=0 and t=1, so it leaves/meets the supporting walls cleanly
                // on its own. The earlier version additionally held perfectly
                // flat for a few blocks before this curve kicked in -- two
                // zero-slope sections back to back, but the flat one has zero
                // curvature everywhere while the eased one doesn't, so the seam
                // between them was still visible as a "shelf, then it sags"
                // kink. Just use the smooth curve across the whole span.
                double sin = Math.sin(Math.PI * t);
                sagCurve = sin * sin;
            } else {
                sagCurve = 4.0 * t * (1.0 - t);
            }
            double sag = sagDepth * sagCurve;
            int x = a.x + (int) Math.round(dx * t);
            int y = a.y + (int) Math.round(dy * t - sag);
            int z = a.z + (int) Math.round(dz * t);
            GNode point = new GNode(x, y, z);

            if (prev != null && point.x == prev.x && point.y == prev.y && point.z == prev.z) {
                continue;
            }

            if (prev != null) {
                BranchResult bridged = this.connectBranchPoints(prev, point, world, ctx, pendingPoints);
                if (bridged != null) {
                    // A bridge collision is only a usable endpoint when the
                    // last empty node physically touches the solid block.
                    // Otherwise reject the whole candidate. Nothing has been
                    // placed in the world yet, so the rejection is clean.
                    if (bridged.endpoint == null) {
                        return null;
                    }

                    this.commitBranchGeometry(world, pendingPoints, pendingHangingLeaves, thickness, ctx);
                    return bridged;
                }
            }
            prev = point;

            if (!this.owner.withinBounds(point, ctx)) {
                continue;
            }

            Block block = this.owner.safeBlockAt(world, x, y, z);
            if (i > 0 && (block == null || !block.isEmpty())) {
                boolean isWall = block != null && !block.isEmpty();
                if (!isWall) {
                    return null;
                }
                if (!this.isAdjacentTouch(lastReached, x, y, z)) {
                    Minions.dNodeVerbose("buildBranch: rejected sag collision at (%d,%d,%d) because lastReached (%d,%d,%d) is more than 1 block away",
                            x, y, z, lastReached.x, lastReached.y, lastReached.z);
                    return null;
                }

                // The branch genuinely hit this solid block. Commit only the
                // portion leading up to the physical contact point.
                this.commitBranchGeometry(world, pendingPoints, pendingHangingLeaves, thickness, ctx);
                return new BranchResult(lastReached, true);
            }

            if (block != null && block.isEmpty()) {
                pendingPoints.add(point);
                lastReached = point;
            }

            if (t > 0.25 && t < 0.75
                    && this.random.nextDouble() < PluginConfig.getBranchHangChancePercent() / 100.0) {
                this.collectHangingLeaves(point, world, ctx, pendingHangingLeaves);
            }
        }

        // Reaching the raytrace endpoint without a collision is valid because
        // `b` came from a raytrace that already proved contact with solid
        // geometry. Commit the complete candidate now that validation passed.
        this.commitBranchGeometry(world, pendingPoints, pendingHangingLeaves, thickness, ctx);
        return new BranchResult(lastReached, false);
    }

    private BranchResult connectBranchPoints(GNode from, GNode to, World world, Ctx ctx,
                                             List<GNode> pendingPoints) {
        int dx = to.x - from.x;
        int dy = to.y - from.y;
        int dz = to.z - from.z;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (steps <= 1) {
            return null;
        }

        GNode lastReached = from;
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = from.x + (int) Math.round(dx * t);
            int y = from.y + (int) Math.round(dy * t);
            int z = from.z + (int) Math.round(dz * t);
            GNode point = new GNode(x, y, z);

            if (!this.owner.withinBounds(point, ctx)) {
                continue;
            }

            Block block = this.owner.safeBlockAt(world, x, y, z);
            if (block == null || !block.isEmpty()) {
                boolean isWall = block != null && !block.isEmpty();
                if (!isWall) {
                    return new BranchResult(null, false);
                }
                if (!this.isAdjacentTouch(lastReached, x, y, z)) {
                    Minions.dNodeVerbose("connectBranchPoints: rejected collision at (%d,%d,%d) because lastReached (%d,%d,%d) is more than 1 block away",
                            x, y, z, lastReached.x, lastReached.y, lastReached.z);
                    return new BranchResult(null, true);
                }
                return new BranchResult(lastReached, true);
            }

            // Validation only.  Actual placement is deferred until the entire
            // candidate has been accepted.
            pendingPoints.add(point);
            lastReached = point;
        }
        return null;
    }

    private void commitBranchGeometry(World world, List<GNode> pendingPoints,
                                      List<GNode> pendingHangingLeaves, int thickness, Ctx ctx) {
        for (GNode point : pendingPoints) {
            if (!this.owner.withinBounds(point, ctx)) {
                continue;
            }
            Block block = this.owner.safeBlockAt(world, point.x, point.y, point.z);
            if (block == null || !block.isEmpty()) {
                continue;
            }
            this.placeTrackedBranchLeaf(block);
            this.applyBranchThickness(world, point, thickness, ctx);
        }

        for (GNode point : pendingHangingLeaves) {
            if (!this.owner.withinBounds(point, ctx)) {
                continue;
            }
            Block block = this.owner.safeBlockAt(world, point.x, point.y, point.z);
            if (block != null && block.isEmpty()) {
                this.placeTrackedBranchLeaf(block);
            }
        }
    }

    private void collectHangingLeaves(GNode point, World world, Ctx ctx, List<GNode> pendingHangingLeaves) {
        int length = this.randomInt(PluginConfig.getBranchHangMinLength(), PluginConfig.getBranchHangMaxLength());
        for (int i = 1; i <= length; i++) {
            int y = point.y - i;
            GNode n = new GNode(point.x, y, point.z);
            if (!this.owner.withinBounds(n, ctx)) {
                break;
            }
            Block below = world.getBlockAt(point.x, y, point.z);
            if (!below.isEmpty()) {
                break;
            }
            pendingHangingLeaves.add(n);
        }
    }

    /**
     * initialClearance is the seed's OWN starting distance-to-ground,
     * computed once in generateGrowth() before the very first call and
     * passed through unchanged on every recursive call for this chain
     * (including forks) -- see where minimumChainLinksNearGround is
     * derived from it, below, for why.
     */
    private void chainBranchToGround(GNode anchor, double approachAngle, int chainLength, World world, Ctx ctx, List<Block> branchAnchors, List<GNode> existingAnchors, List<GNode> chainAnchors, boolean fromWallHit, int initialClearance) {
        if (PluginConfig.getBranchGroundConnectDistance() <= 0) {
            Minions.dNode("chain(len=%d,fromWallHit=%s): disabled (branchGroundConnectDistance <= 0)", chainLength, fromWallHit);
            return;
        }

        int maxChainLength = Math.max(10, PluginConfig.getBranchMaxChainLength());
        if (chainLength >= maxChainLength) {
            Minions.dNode("chain(len=%d,fromWallHit=%s): stopping at (%d,%d,%d) -- hit max chain length (%d)%s",
                    chainLength, fromWallHit, anchor.x, anchor.y, anchor.z, maxChainLength,
                    fromWallHit ? " -- STILL UNANCHORED, budget ran out" : "");
            return;
        }

        Integer trueGroundY = this.findGroundBelow(anchor, world);
        if (trueGroundY == null) {
            Minions.dNode("chain(len=%d,fromWallHit=%s): stopping at (%d,%d,%d) -- no ground found within search range%s",
                    chainLength, fromWallHit, anchor.x, anchor.y, anchor.z,
                    fromWallHit ? " -- STILL UNANCHORED" : "");
            return;
        }

        int distanceToGround = anchor.y - trueGroundY;
        // A flat 3-hop minimum before an early "close enough" exit is
        // trusted made sense as a guard against a SKYSCRAPER's chain
        // ending after one lucky early landing -- but applied equally to
        // every chain regardless of how much height it started with, it
        // forced a short building's chain through the same extra hops
        // (hunting for horizontal room it often doesn't have, right along
        // the ground) even though there was never a "lucky landing" risk
        // to guard against in the first place. Scaling the requirement by
        // how many multiples of branchGroundConnectDistance the chain
        // actually started with keeps the full 3-hop guard for anything
        // tall enough to need it, while a chain that started already at
        // (or near) ground level only needs 1 hop instead of 3.
        //
        // Floored at 1, NOT 0: at chainLength=0 this check runs before
        // chainBranchToGround has ever built a single segment -- letting
        // the floor round down to 0 here (as it did originally, whenever
        // initialClearance < groundConnectDistance) meant a seed that
        // started already close to the ground exited immediately with
        // ZERO built geometry, not just fewer hops. Logged data behind
        // this floor: on a real city, the overwhelming majority of seeds
        // start close to the ground (short/ordinary buildings vastly
        // outnumber tall ones), so without this floor nearly every seed
        // was popping and vanishing without ever placing a single leaf --
        // sparse-to-invisible branching wasn't a budget/pairing problem,
        // it was this. Every seed is now guaranteed at least one real
        // segment before it's ever allowed to exit early.
        int groundConnectDistance = Math.max(1, PluginConfig.getBranchGroundConnectDistance());
        int minimumChainLinksNearGround = Math.max(1, Math.min(3, initialClearance / groundConnectDistance));
        if (!fromWallHit
                && distanceToGround <= PluginConfig.getBranchGroundConnectDistance()
                && chainLength >= minimumChainLinksNearGround) {
            Minions.dNode("chain(len=%d): (%d,%d,%d) is close enough to ground (groundY=%d, dist=%d, requiredLinks=%d from initialClearance=%d) and minimum chain depth was reached -- stopping",
                    chainLength, anchor.x, anchor.y, anchor.z, trueGroundY, distanceToGround, minimumChainLinksNearGround, initialClearance);
            return;
        }

        Minions.dNode("chain(len=%d,fromWallHit=%s): (%d,%d,%d) is %d above true ground (groundY=%d) -- attempting segment",
                chainLength, fromWallHit, anchor.x, anchor.y, anchor.z, distanceToGround, trueGroundY);

        double ratio = PluginConfig.getBranchMaxVerticalToHorizontalRatio();
        int maxDropPerHop = Math.max(1, PluginConfig.getBranchMaxHeight());
        double minHoriz = ratio > 0 ? PluginConfig.getBranchMinSpan() / ratio : 0;
        double maxHoriz = Math.max(minHoriz + 1, PluginConfig.getBranchMaxHorizontalSpan());

        // A chain is now a sequence of local side-to-side hops.  Each hop may
        // descend by at most branchMaxHeight, but never asks the next node to
        // span the whole skyscraper.  This preserves the old 10..50-ish hop
        // scale while allowing 200+ high buildings to descend hop by hop.
        int minimumProgress = Math.max(8, Math.min(20, PluginConfig.getBranchMinSpan()));
        int preferredDrop = Math.max(4, Math.min(maxDropPerHop, (int)Math.round(maxDropPerHop * 0.65)));

        // Re-derive the outward bias fresh at THIS anchor, not just the
        // seed -- a hop three segments into a chain sits against completely
        // different geometry than where the chain started. Falls back to
        // the inherited approachAngle (carried forward from the previous
        // hop, keeping the chain's forward momentum) when this specific
        // anchor has no solid horizontal neighbour to avoid.
        Double freshOutward = this.computeOutwardAngle(anchor, world);
        double effectiveApproachAngle = freshOutward != null ? freshOutward : approachAngle;
        int directionSpread = anchor.y >= 150 ? 95 : 80;

        // Candidate-driven instead of guess-and-check: scan every REAL
        // surface actually reachable this hop up front, then pick among
        // them, instead of rolling a blind (angle, distance) pair and only
        // afterward asking the world whether anything is there. Filtering
        // out too-close/too-short candidates here (rather than inside the
        // pick loop) means every remaining candidate is a genuine attempt,
        // not wasted cycles on ground already ruled out.
        List<HopCandidate> candidates = this.scanHopCandidates(
                anchor, world, minHoriz, maxHoriz, maxDropPerHop, effectiveApproachAngle, directionSpread);
        candidates.removeIf(c -> c.horizDist < minimumProgress
                || this.isTooCloseToExistingAnchor(c.point, existingAnchors, 10.0));

        if (candidates.isEmpty()) {
            Minions.dNode("chain(len=%d,fromWallHit=%s): no real, reachable, non-crowded surfaces found from (%d,%d,%d)%s",
                    chainLength, fromWallHit, anchor.x, anchor.y, anchor.z,
                    fromWallHit ? " -- chain ends here, STILL UNANCHORED" : " -- chain ends here (already anchored, this is fine)");
            return;
        }

        // getBranchChainAttempts() still caps how many candidates we're
        // willing to try before giving up on this hop, in case a huge
        // candidate set turns up -- it just bounds real attempts now
        // instead of blind ones.
        int maxTries = Math.max(1, Math.min(candidates.size(), PluginConfig.getBranchChainAttempts()));
        int totalCandidates = candidates.size();
        int tried = 0;

        while (tried < maxTries && !candidates.isEmpty()) {
            tried++;
            HopCandidate chosen = this.pickHopCandidate(candidates, maxDropPerHop, preferredDrop);
            candidates.remove(chosen);

            GNode aim = chosen.point;
            double angle = Math.atan2(aim.z - anchor.z, aim.x - anchor.x);
            Minions.dNodeVerbose("CHAIN-AIM candidate %d/%d (of %d found) from anchor (%d,%d,%d): angle=%.1fdeg(approach=%.1fdeg) horizDist=%.1f drop=%d/%d -> aim (%d,%d,%d) -- live: %s",
                    tried, maxTries, totalCandidates, anchor.x, anchor.y, anchor.z,
                    Math.toDegrees(angle), Math.toDegrees(effectiveApproachAngle), chosen.horizDist, chosen.drop, maxDropPerHop,
                    aim.x, aim.y, aim.z, this.describeNodeState(aim, world));

            // NOTE: no separate raytrace pre-check here anymore. raytraceToNode()
            // only ever walked a pure STRAIGHT line, but buildBranch() applies a
            // real sag dip below that line (sagDepth = horizDist * sagRatio,
            // independent of actual vertical drop) -- so a candidate the straight
            // check certified as clear could still collide once the real curve
            // was placed, and vice versa a straight-line "collision" a few blocks
            // out says nothing about whether the sagged path actually clips it.
            // `aim` is already a REAL, scanned surface (not a guess needing
            // discovery), and buildBranch() builds entirely in memory before
            // committing anything, so it's already safe to call directly and
            // let its own sag-aware walk be the single source of truth. The
            // "collapsed to anchor" / null-endpoint checks below already measure
            // the real (sagged) endpoint, so they now cover what the old
            // actualDist<2 straight-line check used to only guess at.
            BranchResult result = this.buildBranch(anchor, aim, world, ctx, true);
            if (result == null || result.endpoint == null) {
                Minions.dNodeVerbose("  candidate %d/%d: aim (%d,%d,%d) rejected -- sagged branch could not reach a supported endpoint",
                        tried, maxTries, aim.x, aim.y, aim.z);
                continue;
            }

            // buildBranch() can legitimately collapse to (or very near) its
            // own starting point -- e.g. the sag curve clips something
            // essentially adjacent to `anchor` itself, more likely now that
            // isAdjacentTouch() accepts diagonal/corner contact as a hit,
            // not just a shared face. Accepting that result unchecked used
            // to recurse chainBranchToGround() right back onto (or almost
            // onto) the SAME anchor, which then had every reason to
            // collapse the exact same way again on its next attempt --
            // burning the whole per-chain hop budget on invisible,
            // zero-length "segments" instead of failing this attempt and
            // trying a different candidate. Same minBranchDistance floor
            // the top-level pairing loop already applies to its own
            // result, just applied here too.
            double segmentDist = distance3D(anchor, result.endpoint);
            double minSegmentDistance = Math.max(2.0, PluginConfig.getBranchMinSpan());
            if (segmentDist < minSegmentDistance) {
                Minions.dNodeVerbose("  candidate %d/%d: rejected -- built segment collapsed to (%.1f < %.1f) from anchor",
                        tried, maxTries, segmentDist, minSegmentDistance);
                continue;
            }

            GNode realLower = result.endpoint;
            Block lowerBlock = this.owner.safeBlockAt(world, realLower.x, realLower.y, realLower.z);
            if (lowerBlock != null) {
                branchAnchors.add(lowerBlock);
            }
            chainAnchors.add(realLower);

            boolean anchored = lowerBlock != null && this.owner.hasAdjacentSupport(lowerBlock);
            Minions.dNode("  built segment (%d,%d,%d)->(%d,%d,%d), anchored=%s, wallHit=%s",
                    anchor.x, anchor.y, anchor.z, realLower.x, realLower.y, realLower.z, anchored, result.wallHit);

            // Keep the stronger branching behavior from the previous version.
            double forkChance;
            if (chainLength == 1) {
                forkChance = 0.18;
            } else if (chainLength == 2) {
                forkChance = 0.35;
            } else if (chainLength == 3) {
                forkChance = 0.28;
            } else if (chainLength == 4) {
                forkChance = 0.18;
            } else {
                forkChance = 0.10;
            }
            int remainingDrop = Math.max(0, realLower.y - trueGroundY);
            if (chainLength >= 1
                    && remainingDrop > PluginConfig.getBranchGroundConnectDistance() * 2
                    && this.random.nextDouble() < forkChance) {
                double forkAngle = angle + Math.toRadians(this.random.nextBoolean() ? 70 : -70);
                Minions.dNode("  FORK: spawning side chain from (%d,%d,%d) at %.1fdeg",
                        realLower.x, realLower.y, realLower.z, Math.toDegrees(forkAngle));
                this.chainBranchToGround(realLower, forkAngle, chainLength + 1, world, ctx,
                        branchAnchors, existingAnchors, chainAnchors, false, initialClearance);
            }

            this.chainBranchToGround(realLower, angle, chainLength + 1, world, ctx,
                    branchAnchors, existingAnchors, chainAnchors, result.wallHit || !anchored, initialClearance);
            return;
        }

        Minions.dNode("chain(len=%d,fromWallHit=%s): all %d/%d candidates failed from (%d,%d,%d)%s",
                chainLength, fromWallHit, tried, totalCandidates, anchor.x, anchor.y, anchor.z,
                fromWallHit ? " -- chain ends here, STILL UNANCHORED" : " -- chain ends here (already anchored, this is fine)");
    }

    /**
     * Finds a real support surface in the local horizontal target area while
     * allowing a controlled downward search of at most maxDrop blocks.  The
     * search is intentionally biased toward a useful downward drop rather than
     * blindly selecting the first solid block under the current X/Z.
     */
    /**
     * A real, already-confirmed landing surface found by scanHopCandidates(),
     * not a blindly-guessed point. horizDist/drop/angleOffsetDeg are all
     * relative to the anchor the scan was run from, precomputed once so
     * picking and banding don't need to re-derive them per candidate.
     */
    private static final class HopCandidate {
        final GNode point;
        final double horizDist;
        final int drop;
        final double angleOffsetDeg;

        HopCandidate(GNode point, double horizDist, int drop, double angleOffsetDeg) {
            this.point = point;
            this.horizDist = horizDist;
            this.drop = drop;
            this.angleOffsetDeg = angleOffsetDeg;
        }
    }

    /** Smallest angle between two headings, in radians, always in [0, PI]. */
    private static double angleDifference(double a, double b) {
        double diff = Math.abs(a - b) % (Math.PI * 2);
        return diff > Math.PI ? Math.PI * 2 - diff : diff;
    }

    /**
     * Replaces the old approach of guessing an (angle, distance) pair and
     * ONLY THEN checking whether anything real exists there -- which is why
     * "no real surface within hop window" and "collided almost immediately"
     * used to dominate the rejection log even after the aim angle itself was
     * fixed to point away from the anchor's own support. This scans every
     * REAL surface actually reachable this hop up front: within
     * [minHoriz,maxHoriz] horizontally, within maxDrop vertically, and
     * within directionSpread degrees of approachAngle (the same fan the old
     * blind throws were restricted to, just scanned exhaustively instead of
     * sampled randomly). One downward column scan per (dx,dz) offset in
     * range -- the same per-column cost findChainSurfaceDownward used to
     * pay per blind attempt, except this runs ONCE per hop instead of up to
     * `attempts` times.
     */
    private List<HopCandidate> scanHopCandidates(GNode anchor, World world, double minHoriz, double maxHoriz,
                                                 int maxDrop, double approachAngle, int directionSpread) {
        List<HopCandidate> found = new ArrayList<>();
        int radius = (int) Math.ceil(maxHoriz);
        double spreadRad = Math.toRadians(directionSpread);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double horizDist = Math.hypot(dx, dz);
                if (horizDist < minHoriz || horizDist > maxHoriz) {
                    continue;
                }
                double offset = angleDifference(Math.atan2(dz, dx), approachAngle);
                if (offset > spreadRad) {
                    continue;
                }

                int columnX = anchor.x + dx;
                int columnZ = anchor.z + dz;
                int minY = Math.max(0, anchor.y - maxDrop);
                for (int y = anchor.y - 1; y >= minY; y--) {
                    Block block = this.owner.safeBlockAt(world, columnX, y, columnZ);
                    if (block == null) {
                        break;
                    }
                    if (!block.isEmpty()) {
                        int surfaceY = y + 1;
                        int drop = anchor.y - surfaceY;
                        if (drop > 0) {
                            found.add(new HopCandidate(new GNode(columnX, surfaceY, columnZ),
                                    horizDist, drop, Math.toDegrees(offset)));
                        }
                        break;
                    }
                }
            }
        }
        return found;
    }

    /**
     * Buckets candidates into near/mid/far drop bands -- same "give every
     * band a fair shot instead of always converging on one exact value" idea
     * as reservoirSampleByHeightBand()'s height bands -- then picks from
     * whichever band is closest to preferredDrop AND non-empty, falling back
     * outward (next-nearest band) if the preferred band came up empty this
     * hop. That fallback is the actual fix: a hop that finds real surfaces,
     * just not at the exact preferred depth, no longer fails outright the
     * way a single best-score pick used to.
     */
    private HopCandidate pickHopCandidate(List<HopCandidate> candidates, int maxDrop, int preferredDrop) {
        if (candidates.isEmpty()) {
            return null;
        }
        int bandCount = 3;
        List<List<HopCandidate>> bands = new ArrayList<>(bandCount);
        for (int i = 0; i < bandCount; i++) {
            bands.add(new ArrayList<>());
        }
        for (HopCandidate c : candidates) {
            int band = Math.min(bandCount - 1, c.drop * bandCount / Math.max(1, maxDrop));
            bands.get(band).add(c);
        }

        int preferredBand = Math.min(bandCount - 1, preferredDrop * bandCount / Math.max(1, maxDrop));
        List<Integer> bandOrder = new ArrayList<>();
        bandOrder.add(preferredBand);
        for (int offset = 1; offset < bandCount; offset++) {
            if (preferredBand - offset >= 0) {
                bandOrder.add(preferredBand - offset);
            }
            if (preferredBand + offset < bandCount) {
                bandOrder.add(preferredBand + offset);
            }
        }

        for (int band : bandOrder) {
            List<HopCandidate> pool = bands.get(band);
            if (!pool.isEmpty()) {
                return this.weightedPickByAngle(pool);
            }
        }
        return null;
    }

    /**
     * Weighted (not hard-sorted) pick favoring candidates closer to the
     * approach direction, so hops still read as "generally forward" rather
     * than scattering uniformly across the whole allowed cone -- while still
     * leaving room for the wider side-to-side variety directionSpread was
     * meant to give a chain in the first place.
     */
    private HopCandidate weightedPickByAngle(List<HopCandidate> pool) {
        double[] weights = new double[pool.size()];
        double totalWeight = 0;
        for (int i = 0; i < pool.size(); i++) {
            double w = 1.0 / (1.0 + pool.get(i).angleOffsetDeg);
            weights[i] = w;
            totalWeight += w;
        }
        double roll = this.random.nextDouble() * totalWeight;
        double cumulative = 0;
        for (int i = 0; i < pool.size(); i++) {
            cumulative += weights[i];
            if (roll <= cumulative) {
                return pool.get(i);
            }
        }
        return pool.get(pool.size() - 1);
    }

    private Integer findGroundBelow(GNode from, World world) {
        int limit = Math.max(1, PluginConfig.getBranchMaxHeight() * Math.max(1, PluginConfig.getBranchMaxChainLength()));
        int minY = Math.max(0, from.y - limit);
        for (int y = from.y - 1; y >= minY; y--) {
            Block block = this.owner.safeBlockAt(world, from.x, y, from.z);
            if (block == null) {
                return null;
            }
            if (!block.isEmpty()) {
                return y + 1;
            }
        }
        return null;
    }

    /**
     * Like findGroundBelow(), but bounded to `maxDepth` instead of the
     * full chain-height budget -- used to find the nearest real foothold
     * (a ledge, a wall shelf, or the ground itself) reachable within ONE
     * hop, not the true ground however far below that might be. This is
     * what lets a chain climb down a tall structure hop by hop, landing
     * on whatever's actually there at each step, instead of either (a)
     * blindly guessing a height with no relation to real geometry, or
     * (b) jumping straight to true ground and skipping every intermediate
     * foothold (and failing outright when true ground is farther than one
     * hop can reach at all).
     */
    private GNode findSurfaceNodeWithinRange(GNode from, World world, int maxDepth, int horizontalRadius) {
        GNode best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        int radius = Math.max(0, horizontalRadius);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = from.x + dx;
                int z = from.z + dz;
                int minY = Math.max(0, from.y - maxDepth);

                for (int y = from.y - 1; y >= minY; y--) {
                    Block block = this.owner.safeBlockAt(world, x, y, z);
                    if (block == null) {
                        break;
                    }
                    if (!block.isEmpty()) {
                        int surfaceY = y + 1;
                        double verticalDelta = Math.abs(from.y - surfaceY);
                        double horizontalDelta = Math.hypot(dx, dz);
                        double score = horizontalDelta * 2.0 + verticalDelta;
                        if (score < bestScore) {
                            bestScore = score;
                            best = new GNode(x, surfaceY, z);
                        }
                        break;
                    }
                }
            }
        }
        return best;
    }

    private void applyBranchThickness(World world, GNode point, int thickness, Ctx ctx) {
        if (thickness <= 1) {
            return;
        }
        List<BlockFace> faces = new ArrayList<>();
        Collections.addAll(faces, ALL_FACES);
        Collections.shuffle(faces, this.random);

        int extra = thickness - 1;
        int placed = 0;
        for (BlockFace face : faces) {
            if (placed >= extra) {
                break;
            }
            int nx = point.x + face.getModX();
            int ny = point.y + face.getModY();
            int nz = point.z + face.getModZ();
            GNode n = new GNode(nx, ny, nz);
            if (!this.owner.withinBounds(n, ctx)) {
                continue;
            }
            Block neighbour = this.owner.safeBlockAt(world, nx, ny, nz);
            if (neighbour != null && neighbour.isEmpty()) {
                this.placeTrackedBranchLeaf(neighbour);
                placed++;
            }
        }
    }

    private void growHangingLeaves(World world, GNode point, Ctx ctx) {
        int length = this.randomInt(PluginConfig.getBranchHangMinLength(), PluginConfig.getBranchHangMaxLength());
        for (int i = 1; i <= length; i++) {
            int y = point.y - i;
            GNode n = new GNode(point.x, y, point.z);
            if (!this.owner.withinBounds(n, ctx)) {
                break;
            }
            Block below = world.getBlockAt(point.x, y, point.z);
            if (!below.isEmpty()) {
                break;
            }
            this.placeTrackedBranchLeaf(below);
        }
    }

    /**
     * Snapshot of a node's local support state AT THE MOMENT THIS IS
     * CALLED -- used purely for branch node-selection debug tracing
     * (Minions.dNode()). Deliberately re-derives isEmpty/hasAdjacentSupport
     * live rather than trusting whatever was true when the node was first
     * pooled/computed, so a "SEED-VALID -> now STALE" mismatch in the log
     * is itself the diagnostic signal, not something this method hides.
     */
    private String describeNodeState(GNode point, World world) {
        Block block = this.owner.safeBlockAt(world, point.x, point.y, point.z);
        if (block == null) {
            return "unloaded";
        }
        boolean empty = block.isEmpty();
        boolean supported = this.owner.hasAdjacentSupport(block);
        boolean sky = this.canSeeSky(point, world);
        return String.format("empty=%s supported=%s canSeeSky=%s block=%s",
                empty, supported, sky, block.getType());
    }

    /**
     * Groups a point into a rough building-footprint-sized bucket. Used to
     * judge "is this near the top" and "should this get picked" relative to
     * a candidate's own local neighborhood, rather than relative to every
     * other candidate in the whole (possibly multi-building) district.
     */
    private static long buildingCellKey(int x, int z) {
        long cx = Math.floorDiv(x, BUILDING_CELL_SIZE);
        long cz = Math.floorDiv(z, BUILDING_CELL_SIZE);
        return (cx << 32) ^ (cz & 0xffffffffL);
    }

    private static long cellKeyFromCellCoords(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }

    /**
     * Merges building-cells into connected components (8-neighbor adjacent
     * occupied cells) so a large structure's footprint -- which can span
     * many cells -- counts as ONE building for selection-fairness purposes,
     * not one vote per cell. Without this, picking a cell uniformly still
     * let a big building dominate selection just by covering more cells
     * than a small one, which is the same "more raw material wins" bias
     * the per-cell height split was meant to remove, just moved up a level.
     */
    private Map<Long, Integer> buildCellComponents(List<GNode> allCandidates) {
        Set<Long> occupiedCells = new HashSet<>();
        for (GNode g : allCandidates) {
            occupiedCells.add(buildingCellKey(g.x, g.z));
        }
        Map<Long, Integer> componentOf = new HashMap<>();
        int nextComponent = 0;
        for (Long startCell : occupiedCells) {
            if (componentOf.containsKey(startCell)) {
                continue;
            }
            int component = nextComponent++;
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(startCell);
            componentOf.put(startCell, component);
            while (!queue.isEmpty()) {
                long current = queue.poll();
                int cx = (int) (current >> 32);
                int cz = (int) (current & 0xffffffffL);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        long neighborKey = cellKeyFromCellCoords(cx + dx, cz + dz);
                        if (occupiedCells.contains(neighborKey) && !componentOf.containsKey(neighborKey)) {
                            componentOf.put(neighborKey, component);
                            queue.add(neighborKey);
                        }
                    }
                }
            }
        }
        return componentOf;
    }

    private static double distance3D(GNode a, GNode b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

}