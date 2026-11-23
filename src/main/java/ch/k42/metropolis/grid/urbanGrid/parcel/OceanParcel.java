package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.layout.StructureDistance;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaWilderness;
import ch.k42.metropolis.grid.urbanGrid.forest.ForestGenerator;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanDepthTier;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanFloorSafety;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanSeabed;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.World;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One chunk of ocean: water, a carved bed beneath it, and a blended
 * sand/gravel/grass beach fading out to dry land -- same role RiverParcel
 * plays for the river, and deliberately structured the same way (procedural
 * per-block painting rather than schematic-based, for the same reasons: no
 * new schematic assets needed, and any width/shape the noise produces is
 * handled automatically).
 *
 * Placed directly by UrbanGrid (see placeOceanIfPresent()) across the
 * ocean's ENTIRE bounding square, unconditionally -- exactly the same
 * "hand every cell to the per-block painter, don't pre-filter at chunk
 * granularity" approach carveRiverCorridor() uses for RiverParcel, and for
 * the same reason: the noise-perturbed coastline is a continuous function
 * of per-block distance from the ocean's center that has no relationship
 * to chunk boundaries, so deciding water/beach/land per CHUNK would notch
 * visibly wherever a chunk boundary happened to cross the true edge.
 * populate() below resolves every one of its 256 columns independently
 * against OceanProvider's own continuous distance function, so a corner
 * chunk of the bounding square -- which is almost always dry land, since
 * the box is square and the ocean itself is roughly circular -- just
 * paints as ordinary flat land, no different from RiverParcel's own
 * "beyond the bank" case.
 *
 * DEPTH TAGGING: unlike the river (where nothing downstream currently
 * cares about exact water depth), this parcel tags itself with the ocean's
 * rough bed depth at its own chunk center -- see getWaterColumnDepthBlocks()
 * -- specifically so a future feature (depth-gated boat/ship schematics)
 * has something to query without having to re-derive it from OceanProvider
 * itself. One representative value per PARCEL (sampled at chunk center,
 * same convention RiverProvider.spineZ() uses for its own chunk-level
 * queries), not per block -- a boat schematic occupies a whole handful of
 * chunks anyway, so a per-block figure would be more precision than
 * anything placing one could actually use.
 */
public class OceanParcel extends Parcel {

    // One-shot, per JVM: the first time any ocean chunk is actually painted, say so -- the proof that
    // the carve made it all the way from UrbanGrid to real blocks (see MetropolisGenerator.announceOcean()).
    /** Ground elevation from the same deterministic model used by the ocean painter. */
    public static int shoreGroundAt(OceanProvider ocean, UrbanGrid grid, int x, int z) {
        return floorYAt(ocean, grid, x, z);
    }

    /** Ground height from the natural ocean/river beach; no road-driven shore movement. */
    public static int floorYAt(OceanProvider ocean, UrbanGrid grid, int x, int z) {
        int build = OceanConfig.getOceanBuildHeight();
        OceanProvider.Sample sample = ocean.sample(x, z);
        if (sample.distanceInside > 0) return sample.surfaceY;
        double ramp = beachRunBlocks(OceanConfig.getOceanBankMargin() * 16.0);
        int top = (int) Math.round(beachTargetY(ocean, grid, x, z, sample.surfaceY, build, ramp));
        return Math.min(build, Math.max(sample.surfaceY, top));
    }

    private static final AtomicBoolean FIRST_POPULATE_LOGGED = new AtomicBoolean(false);
    private static final Cartesian2D size = new Cartesian2D(1, 1);

    // Set at CONSTRUCTION time, not populate() -- see the constructor's own
    // comment for why. 0 permanently if this chunk's center turned out to
    // be dry land rather than open water.
    private final int waterColumnDepthBlocks;
    // Kept alongside it purely for the one-time FIRST_POPULATE_LOGGED debug
    // line in populate() -- see the constructor's own comment.
    private final double centerDistanceInsideShore;

    public OceanParcel(UrbanGrid grid, Cartesian2D base) {
        super(base, size, ContextType.OCEAN, SchematicType.OCEAN, grid);
        grid.fillParcels(this.chunkX, this.chunkZ, this);
        // ocean.sample() is a pure, deterministic function of OceanProvider's
        // own noise field -- NOT a read of real generated Minecraft terrain
        // (that's carveColumn()'s job, per-block, still only done at
        // populate() time) -- so there was never an actual reason to defer
        // this one value to populate(). Computing it here instead, at grid-
        // carve time, is what lets OceanBoatPlacer (see that class's own
        // javadoc) call isOpenWater()/getDepthTier() on THIS parcel right
        // after placeOceanIfPresent()'s own carve loop, long before Bukkit
        // ever calls this chunk's populate() -- getting the exact same
        // answer either way, since it's the same pure call either way.
        OceanProvider.Sample centerSample = grid.getOceanProvider().sample((this.chunkX << 4) + 8.0, (this.chunkZ << 4) + 8.0);
        this.waterColumnDepthBlocks = centerSample.columnDepth;
        this.centerDistanceInsideShore = centerSample.distanceInside;
    }

    /**
     * Rough depth, in blocks, of the ocean bed at this parcel's own chunk
     * center -- 0 if this chunk isn't open water at all (dry land or
     * beach). Set at construction time (see the constructor's own
     * comment) -- valid immediately, not just after populate() has run.
     */
    public int getWaterColumnDepthBlocks() {
        return this.waterColumnDepthBlocks;
    }

    /** Convenience for future depth-gated placement logic -- equivalent to getWaterColumnDepthBlocks() > 0. */
    public boolean isOpenWater() {
        return this.waterColumnDepthBlocks > 0;
    }

    /**
     * Depth tier for this parcel's water column -- maps the raw depth in blocks
     * to one of three brackets (SHORE/MID/DEEP) for depth-gated boat placement.
     * Returns SHORE if this chunk is dry land (waterColumnDepthBlocks == 0).
     */
    public OceanDepthTier getDepthTier() {
        return OceanDepthTier.getTier(this.waterColumnDepthBlocks);
    }

    @Override
    public void populate(MetropolisGenerator generator, Chunk chunk) {
        if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) {
            return;
        }
        carveChunk(generator);
    }

    /**
     * The carve itself, without the "is this the chunk being populated" guard,
     * so an OceanBoatParcel can carve the other chunks its boat covers before
     * pasting (see OceanBoatParcel.populate()).
     */
    protected void carveChunk(MetropolisGenerator generator) {
        World world = generator.getWorld();
        if (world == null) {
            return;
        }

        OceanProvider ocean = this.grid.getOceanProvider();
        GridRandom rand = this.grid.getRandom();
        int baseX = this.chunkX << 4;
        int baseZ = this.chunkZ << 4;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                carveColumn(world, ocean, this.grid, rand, baseX + lx, baseZ + lz);
            }
        }

        buildRiverHandoffWalls(world, ocean);

        if (FIRST_POPULATE_LOGGED.compareAndSet(false, true)) {
            Minions.i("First ocean chunk populated: chunk (%d,%d), chunk-centre %.1f blocks inside the shoreline (negative = beach/land), water column %d block(s)",
                    this.chunkX, this.chunkZ, this.centerDistanceInsideShore, this.waterColumnDepthBlocks);
        }

        if (OceanConfig.isOceanTreesEnabled() || OceanConfig.isOceanVegetationEnabled() || VanillaWilderness.isActive(world)
                || ForestGenerator.isShoreReliefActive()) {
            ForestGenerator.populateShore(generator, this.grid, world.getChunkAt(this.chunkX, this.chunkZ), new BeachSite(this.grid, ocean),
                    OceanConfig.isOceanTreesEnabled(), OceanConfig.getOceanTreesChancePercent(),
                    OceanConfig.isOceanVegetationEnabled(), OceanConfig.getOceanVegetationChancePercent());
        }

        this.markPopulated();
    }

    /**
     * Blocks inland from the shoreline beyond which the beach's top block is always grass -- the same wet-band
     * limit RiverParcel's banks use, since the beach is painted by the very same paintBank().
     */
    private static final double GRASS_SAFE_BLOCKS = 7.0 * 1.25 + 1.0;

    /**
     * The grass beyond an ocean's beach as a tree site, for ForestGenerator.populateShore() -- the ocean
     * counterpart of RiverParcel's BankSite: the forest's own trees, ground cover and biome tint, on columns
     * that are certain to be grass in an ocean chunk away from the tile edge (where the river hand-off walls
     * go), with groundAt() repeating carveColumn()'s own height rules.
     */
    /** R30ea pure shoreline site used by cross-owner vegetation roots. */
    public static ForestGenerator.TreeSite vegetationSite(UrbanGrid grid) {
        return new BeachSite(grid, grid.getOceanProvider());
    }

    private static final class BeachSite implements ForestGenerator.TreeSite {
        private final UrbanGrid grid;
        private final OceanProvider ocean;

        BeachSite(UrbanGrid grid, OceanProvider ocean) {
            this.grid = grid;
            this.ocean = ocean;
        }

        @Override
        public boolean allows(int x, int z) {
            // Decided from the plan, not from a parcel a tile may or may not have built yet: the chunk is the ocean's own
            // (water, or the shore land WaterField hands to the ocean) and is not a highway lane. Every chunk a tree
            // reaches into reaches the same verdict about the same column, tile edge or not.
            long seed = this.ocean.getWorldSeed();
            WaterField water = WorldLayouts.waterFor(seed);
            WorldLayout layout = WorldLayouts.forSeed(seed);
            if (water == null || layout == null) {
                return false;
            }
            int cx = x >> 4;
            int cz = z >> 4;
            int kind = layout.kindAt(cx, cz);
            boolean oceans = water.kindAt(cx, cz) == WaterField.Kind.OCEAN
                    || (kind == WorldLayout.ZONE && water.zoneKind(cx, cz) == WaterField.Kind.OCEAN);
            if (!oceans || kind == WorldLayout.LANE) {
                return false;
            }
            return -this.ocean.sample(x, z).distanceInside > GRASS_SAFE_BLOCKS;
        }

        @Override
        public double fade(int x, int z) {
            return 1.0;
        }

        @Override
        public double edgeDistance(int x, int z, int reachChunks) {
            return ForestGenerator.probeEdge(this, x, z, reachChunks);
        }

        @Override
        public int groundAt(int x, int z) {
            int buildHeight = OceanConfig.getOceanBuildHeight();
            int waterY = this.ocean.sample(x, z).surfaceY;
            double rampBlock = beachRunBlocks(OceanConfig.getOceanBankMargin() * 16.0);
            // The very height carveColumn() builds the bank to (beside a river that is the river's own ramp, smoothed), so
            // trees root on the real ground at a river's mouth too.
            int targetY = (int) Math.round(beachTargetY(this.ocean, this.grid, x, z, waterY, buildHeight, rampBlock));
            return Math.min(buildHeight, Math.max(waterY, targetY));
        }
    }

    /**
     * Where a river crosses a highway ring: an interior edge chunk whose ring
     * is kept (not dropped), with a river channel reaching it. Whatever tile
     * lies beyond -- a river tile or another ocean tile -- bores its own half
     * of a drain pipe through the two-chunk-wide ring (see
     * RiverParcel.buildHighwayCrossingWall()); this bores this side's half,
     * with the same retaining wall, so the water has somewhere to go instead
     * of ending against the highway.
     */
    private void buildRiverHandoffWalls(World world, OceanProvider ocean) {
        int rx = OceanProvider.tileRootOf(this.chunkX);
        int rz = OceanProvider.tileRootOf(this.chunkZ);
        int lx = this.chunkX - rx;
        int lz = this.chunkZ - rz;
        // A side whose highway ring was dropped has no ring to bore through: the
        // river simply continues into this tile's own reclaimed ring chunk.
        boolean[] dropped = ocean.droppedRingSides(rx, rz);
        // Every side whose ring is KEPT, whatever the neighbouring tile is: the ring is
        // a real highway between two ocean tiles just as much as between an ocean tile
        // and a river tile, and nothing but this pipe lets the river through it. (Each
        // side bores its own half; both compute the centreline from the middle of the
        // ring, so the halves meet.)
        boolean west = lx == 1 && !dropped[OceanProvider.RING_COL_MIN];
        boolean east = lx == 62 && !dropped[OceanProvider.RING_COL_MAX];
        boolean north = lz == 1 && !dropped[OceanProvider.RING_ROW_MIN];
        boolean south = lz == 62 && !dropped[OceanProvider.RING_ROW_MAX];
        if (!west && !east && !north && !south) {
            return;
        }

        RiverProvider xr = ocean.getXRiver();
        if ((west || east) && xr != null && xr.isEnabled() && xr.isRiverOrBank(this.chunkX, this.chunkZ)) {
            RiverParcel.buildHighwayCrossingWallForRiver(this.grid, world, xr, this.chunkX, this.chunkZ, west);
        }
        RiverProvider zr = ocean.getZRiver();
        if ((north || south) && zr != null && zr.isEnabled() && zr.isRiverOrBank(this.chunkX, this.chunkZ)) {
            RiverParcel.buildHighwayCrossingWallForRiverZ(this.grid, world, zr, this.chunkX, this.chunkZ, north);
        }
    }

    /**
     * Carves a single column: open water within the noise-perturbed
     * coastline, a blended beach out to oceanBankMargin beyond that, or
     * flat dry land beyond the beach -- same three-way split
     * RiverParcel.carveColumn() uses, just keyed off OceanProvider's
     * merged water field (the ocean's coast plus any river channel that
     * crosses its box -- see OceanProvider.sample()) instead of a linear
     * distance-from-spine.
     */
    public static void carveColumn(World world, OceanProvider ocean, GridRandom rand, int blockX, int blockZ) {
        carveColumn(world, ocean, null, rand, blockX, blockZ);
    }

    /**
     * Carves a single column, with the grid the river's own bank ramp is read from.
     *
     * Where a river runs into the sea the river's chunks (RiverParcel: a bank ramp that finishes at the nearest street or
     * district, smoothed over a small window) and the ocean's chunks (this painter: the short beach ramp) meet along the
     * channel's banks, and the two profiles do not agree -- the river's bank is longer and gentler, so the ground stepped
     * by several blocks at the chunk line. With a grid, the bank ramp beside a river's channel is therefore the RIVER's
     * ramp, handed over to the beach's ramp by the sea's weight (OceanProvider.riverBankWeight()), and the ground is
     * averaged over the same small window RiverParcel uses wherever a river is near, so the channel's banks and the
     * coast's beach flow into each other with no seam. With grid == null (BridgeParcel's columns) it is the plain beach.
     */
    public static void carveColumn(World world, OceanProvider ocean, UrbanGrid grid, GridRandom rand, int blockX, int blockZ) {
        int buildHeight = OceanConfig.getOceanBuildHeight();
        // One sample per column: the merged distance (ocean united with any
        // river channel crossing this box), the water surface level -- one
        // level for the whole box, the river's own when rivers converge -- and
        // the depth.
        OceanProvider.Sample sample = ocean.sample(blockX, blockZ);
        int waterY = sample.surfaceY;
        // Natural, merged ocean/river shoreline ONLY. Roads must never pull water inland.
        // Purpose-built port aprons are painted independently by OceanPortParcel within reserved sites.
        double distanceInside = sample.distanceInside;
        double bankMarginBlock = OceanConfig.getOceanBankMargin() * 16.0;
        double rampBlock = beachRunBlocks(bankMarginBlock);

        if (distanceInside > 0) {
            // 0 right at the shoreline, ramping up to 1 a few blocks in --
            // same shore-taper role as RiverParcel's own shoreRamp, kept
            // short (unlike the much longer waterColumnDepthBlocks() ramp)
            // so the immediate shoreline still reads as walkable shallows
            // rather than dropping off within the very first blocks.
            double shoreRamp = Math.min(4.0, bankMarginBlock > 0 ? bankMarginBlock : 4.0);
            double shoreT = shoreRamp <= 0 ? 1.0 : Math.min(1.0, distanceInside / shoreRamp);
            int depth = Math.max(1, sample.columnDepth);
            paintWater(world, rand, blockX, blockZ, waterY, buildHeight, shoreT, depth);
            // R30ea parity: detail the actual submerged floor after the water column
            // has been carved.  Restore the original integration point rather than
            // applying seabed materials globally to unrelated terrain.
            if (OceanConfig.isOceanSeabedDetailsEnabled()) {
                int floorY = OceanFloorSafety.seabedY(waterY, depth, distanceInside, bankMarginBlock);
                OceanSeabed.paint(world, ocean.getWorldSeed(), blockX, blockZ,
                        floorY, waterY, waterY - floorY, distanceInside);
            }
            return;
        }

        // Same bank as the river's: the shared RiverParcel.paintBank()
        // palette (a narrow, ragged sand/gravel shore band, then grass) on
        // the same smoothstep rise. The run ends at the nearest street, block or highway if that is closer than the usual
        // run, so the beach reaches build height exactly where the road begins instead of stopping short of it with a step;
        // beside a river it is the river's own run, eased into the beach's by the sea's weight (see above).
        double run = bankRunBlocks(ocean, grid, blockX, blockZ, distanceInside, rampBlock);
        if (-distanceInside < run) {
            double bankT = -distanceInside / run; // 0 at shoreline, 1 where the rise tops out
            double targetY = beachTargetY(ocean, grid, blockX, blockZ, waterY, buildHeight, rampBlock);
            RiverParcel.paintBank(world, rand, blockX, blockZ, waterY, buildHeight, bankT, targetY, -distanceInside);
        } else {
            paintLand(world, blockX, blockZ, buildHeight);
        }
    }

    /**
     * The length of the bank's rise at this (non-water) column: the beach's run (rampRunBlocks()), or, beside a river's
     * channel, the run the river's own painter gives that bank (RiverParcel.bankRampEnd(), measured from the water's edge),
     * blended by OceanProvider.riverBankWeight() so it is exactly the river's where the sea has no weight and exactly the
     * beach's in the beach band. Pure in the column, and continuous.
     */
    static double bankRunBlocks(OceanProvider ocean, UrbanGrid grid, int blockX, int blockZ, double distanceInside, double rampBlock) {
        double oceanRun = rampRunBlocks(ocean, blockX, blockZ, distanceInside, rampBlock);
        if (grid == null || distanceInside > 0) {
            return oceanRun;
        }
        double weight = ocean.riverBankWeight(blockX, blockZ);
        if (weight <= 0.0) {
            return oceanRun;
        }
        RiverProvider river = ocean.dominantRiver(blockX, blockZ);
        if (river == null) {
            return oceanRun;
        }
        double signed = river.distanceFromCenterBlock(blockX, blockZ);
        if (Double.isNaN(signed)) {
            return oceanRun;
        }
        double halfWidth = river.widthAtBlock(river.isZBound() ? blockZ : blockX) / 2.0;
        double riverRun = Math.max(1.0, RiverParcel.bankRampEnd(river, grid, blockX, blockZ, signed < 0, halfWidth) - halfWidth);
        return oceanRun + (riverRun - oceanRun) * weight;
    }

    /** How far from a river's water the ground is averaged over a window (the river's bank run can be long; this covers it). */
    private static final double RIVER_SMOOTH_MARGIN_BLOCKS = 96.0;
    /** Half-size of the averaging window -- the same as RiverParcel's own bank smoothing. */
    private static final int BEACH_TARGET_BLEND_RADIUS = 2;

    /**
     * The height the bank is built to at this column. Away from rivers it is the beach's own smoothstep rise. Where a river
     * is near it is averaged over the same small window RiverParcel averages its banks over, so the ground at the river's
     * mouth -- where its banks run into the coast -- is as smooth as the river's own, with no crease along the seam.
     */
    private static double beachTargetY(OceanProvider ocean, UrbanGrid grid, int blockX, int blockZ, int waterY, int buildHeight, double rampBlock) {
        if (grid == null || !ocean.isNearRiver(blockX, blockZ, RIVER_SMOOTH_MARGIN_BLOCKS)) {
            return rawBeachTargetY(ocean, grid, blockX, blockZ, buildHeight, rampBlock);
        }
        double sum = 0.0;
        int count = 0;
        for (int dx = -BEACH_TARGET_BLEND_RADIUS; dx <= BEACH_TARGET_BLEND_RADIUS; dx++) {
            for (int dz = -BEACH_TARGET_BLEND_RADIUS; dz <= BEACH_TARGET_BLEND_RADIUS; dz++) {
                sum += rawBeachTargetY(ocean, grid, blockX + dx, blockZ + dz, buildHeight, rampBlock);
                count++;
            }
        }
        return sum / count;
    }

    /** This one column's own bank height: the water level over water, the smoothstep rise over the bank, build height beyond it. */
    private static double rawBeachTargetY(OceanProvider ocean, UrbanGrid grid, int blockX, int blockZ, int buildHeight, double rampBlock) {
        OceanProvider.Sample sample = ocean.sample(blockX, blockZ);
        int waterY = sample.surfaceY;
        double distanceInside = sample.distanceInside;
        if (distanceInside > 0) {
            return waterY;
        }
        double run = bankRunBlocks(ocean, grid, blockX, blockZ, distanceInside, rampBlock);
        double t = -distanceInside / run;
        if (t >= 1.0) {
            return buildHeight;
        }
        return waterY + smoothstep(t) * (buildHeight - waterY);
    }

    /**
     * Package-private-in-spirit (public static, same visibility choice
     * RiverParcel makes for carveColumn() so BridgeParcel-style external
     * callers stay possible later) water painter -- same shape as
     * RiverParcel.paintWater() but taking an already-resolved `depth`
     * rather than re-deriving RiverConfig.getRiverWaterColumnDepth()
     * itself, since the ocean's depth genuinely varies chunk-to-chunk
     * (near-shore vs. open water) in a way the river's fixed max depth
     * never needed to.
     */
    private static void paintWater(World world, GridRandom rand, int x, int z, int waterY, int buildHeight, double shoreT, int depth) {
        int currentTop = world.getHighestBlockYAt(x, z);
        for (int y = waterY + 1; y <= Math.max(currentTop, buildHeight + 2); y++) {
            world.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
        // No physics: adjacent columns can now sit at different surface
        // levels (a river's water climbs to sea level in steps at its mouth),
        // and a source block placed with physics next to a lower column's
        // air would spill over it as flowing water at generation time.
        for (int y = 1; y <= waterY; y++) {
            world.getBlockAt(x, y, z).setType(Material.WATER, false);
        }

        int columnDepth = Math.max(1, (int) Math.round(1 + shoreT * (depth - 1)));
        int bedY = Math.max(1, waterY - columnDepth);

        // Mostly sand near shore, with more gravel/clay further out -- same
        // reasoning as RiverParcel.paintWater()'s own bed palette.
        Material bed = shoreT < 0.4
                ? (rand.getChance(80) ? Material.SAND : Material.GRAVEL)
                : (rand.getChance(60) ? Material.GRAVEL : (rand.getChance(50) ? Material.SAND : Material.CLAY));
        world.getBlockAt(x, bedY, z).setType(bed, false);

        for (int y = bedY - 1; y >= 1; y--) {
            world.getBlockAt(x, y, z).setType(Material.STONE, false);
        }
    }

    /**
     * The length of the beach's rise at this column: the usual run, or less where a street, block or highway lies nearer
     * than that, so the rise ends where the road begins. Pure in the column (the plan decides what is built), and it
     * changes by at most one block per block, so neighbouring columns never step.
     */
    static double rampRunBlocks(OceanProvider ocean, int blockX, int blockZ, double distanceInside, double rampBlock) {
        double d = -distanceInside;
        if (d >= rampBlock) {
            return rampBlock;
        }
        WorldLayout layout = WorldLayouts.forSeed(ocean.getWorldSeed());
        if (layout == null) {
            return rampBlock;
        }
        double toStructure = StructureDistance.blocksToStructure(layout, blockX, blockZ, rampBlock);
        return Math.max(1.0, Math.min(rampBlock, d + toStructure));
    }

    /**
     * Length, in blocks, of the beach's rise from the water to buildHeight.
     * Automatic (oceanBankRunBlocks = 0) is the reclaim berth -- the distance
     * at which OceanReclaim's districts start -- so the rise finishes right at
     * the district edge, exactly as the river's bank ramp does; capped at the
     * bank margin.
     */
    private static double beachRunBlocks(double bankMarginBlock) {
        return Math.min(bankMarginBlock, OceanProvider.beachRunBlocks());
    }

    /** Classic Hermite smoothstep -- same curve RiverParcel uses for its own bank blend, duplicated for the same reason it duplicates RiverProvider's copy (a two-line function, not worth a shared utility class for). */
    private static double smoothstep(double t) {
        double c = Math.max(0.0, Math.min(1.0, t));
        return c * c * (3.0 - 2.0 * c);
    }

    /** Flat, deterministic dry land pinned to buildHeight -- the "beyond the beach" case, same role as RiverParcel.paintLand(). */
    private static void paintLand(World world, int x, int z, int buildHeight) {
        int currentTop = world.getHighestBlockYAt(x, z);
        clearAboveKeepingTrees(world, x, z, buildHeight + 1, Math.max(currentTop, buildHeight + 1));
        world.getBlockAt(x, buildHeight, z).setType(Material.GRASS_BLOCK, false);
        for (int y = buildHeight - 1; y >= Math.max(1, buildHeight - 3); y--) {
            world.getBlockAt(x, y, z).setType(Material.DIRT, false);
        }
    }

    @Override
    public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
        // No schematic, no decay pass, no cutout notifications, no growth
        // to cache -- mirrors RiverParcel/TerrainFallbackParcel's
        // postPopulate() reasoning exactly. populate() already marked this
        // parcel populated, which is all any neighbour's growth-readiness
        // check needs from it.
    }
}