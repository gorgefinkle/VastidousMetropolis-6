package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Ocean counterpart of RiverReclaim: hands the dry, berth-clear part of the
 * ocean's carve box back to ordinary districts and roads.
 *
 * WHAT WENT WRONG WITH THE PREVIOUS VERSION (row-scan tiling + peel-everything):
 *  - tileUsableArea() only merged rows whose usable span matched EXACTLY. A
 *    curved shoreline changes its span by a chunk or two on almost every row, so
 *    the usable land was cut into thousands of 1-row-tall slivers. Every sliver
 *    is narrower than minWidth, and every too-narrow piece was paved as a
 *    full-area "fallback road" -- that is where the sea of road chunks came from
 *    (on a tile with ~350-500 chunks of real district, 1000+ chunks of road);
 *  - every accepted district peeled its own road off all four sides, so two
 *    neighbours ended up with a 2-chunk-wide road between them, and a district
 *    that touched the tile's highway ring got a second road right next to it;
 *  - each tile tiled its own clipped slice of the box independently, so nothing
 *    lined up across tile edges, and nothing lined up with the districts
 *    outside the box either (their streets just ran into the box and stopped).
 *
 * HOW THIS VERSION WORKS:
 *  1. classify every chunk as usable (berth-clear of the water over its whole
 *     footprint) or not -- classifyChunks(), unchanged;
 *  2. take the layout the ORDINARY district carve (UrbanGrid.recSetDistricts())
 *     makes over the tile's carve rect -- recorded, not painted (see Layout and
 *     UrbanGrid.layoutRecorder). Same cuts, same block sizes, same random stream
 *     as any other tile: T-junction streets and uneven blocks, never a regular
 *     lattice. The tile's outer edge carries no street of its own (it is the
 *     highway ring, or a neighbouring ocean tile's seam street);
 *  3. every carved block is filled with the best all-usable rectangle(s) it can
 *     hold. A block that is fully usable becomes one district. A block cut by
 *     the shore becomes a district on its dry side plus a one-chunk frontage road
 *     along whatever sides face the shore/beach. Usable leftovers too small for a
 *     district (< minWidth on an axis) are simply left alone -- they stay
 *     OceanParcel, which paints them as flat green land -- instead of being paved;
 *  4. road cells are kept only where they serve a district (or are the seam
 *     road), then dead ends are pruned and anything not connected to the seam
 *     road or the tile's highway ring is dropped together with the districts
 *     that depended on it. Sides that face the tile's highway ring get no road
 *     of their own -- the highway IS that road (same rule RiverReclaim uses
 *     with nearIsBoundary/farIsBoundary).
 *
 * Reuses RiverConfig's reclaim settings: getRiverReclaimMinWidth() = smallest
 * district side, getRiverReclaimBerthChunks() = clearance from the water. Block
 * sizes come from the ordinary carve (PluginConfig.getBlockSize()/getBlockSizeMin()).
 */
public class OceanReclaim {

    /** One rectangle in chunk coordinates, ready for UrbanGrid.addDistrict() or to be painted as road. */
    public static class Rect {
        public final int baseX;
        public final int baseZ;
        public final int sizeX;
        public final int sizeZ;

        Rect(int baseX, int baseZ, int sizeX, int sizeZ) {
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
        }

        @Override
        public String toString() {
            return String.format("Rect[base=(%d,%d) size=(%d,%d)]", this.baseX, this.baseZ, this.sizeX, this.sizeZ);
        }
    }

    /**
     * Which chunks a street may be carried over on a bridge: river water (and
     * its banks) that is purely the river's own, not the sea. compute() asks
     * this only about chunks that are NOT usable land.
     */
    public interface BridgeSpan {
        boolean canBridge(int chunkX, int chunkZ);
    }

    /** One chunk of bridge deck. deckAxisX = the street it carries runs east-west. */
    public static final class BridgeCell {
        public final int x;
        public final int z;
        public final boolean deckAxisX;

        BridgeCell(int x, int z, boolean deckAxisX) {
            this.x = x;
            this.z = z;
            this.deckAxisX = deckAxisX;
        }
    }

    /**
     * Everything compute() needs to know about WHERE it is, all in chunk
     * coordinates: this tile's slice of the carve box, the full (unclipped)
     * carve box, and this tile's interior (62x62 -- the highway ring sits one
     * chunk outside it on every side). Optionally also which water a street
     * may bridge (bridgeSpan, at most maxBridgeChunks wide); null = none.
     */
    public static final class Frame {
        final int areaMinX, areaMinZ, areaWidth, areaHeight;
        final int boxMinX, boxMaxX, boxMinZ, boxMaxZ;
        final int tileMinX, tileMaxX, tileMinZ, tileMaxZ;
        final BridgeSpan bridgeSpan;
        final int maxBridgeChunks;
        final int kindMinX, kindMaxX, kindMinZ, kindMaxZ;
        Layout layout;
        RingProbe ring; // null = assume the ring is a street everywhere

        /** The ordinary carve's roads and blocks for this tile -- see Layout. */
        public void setLayout(Layout layout) {
            this.layout = layout;
        }

        public void setRingProbe(RingProbe ring) {
            this.ring = ring;
        }

        public Frame(int areaMinX, int areaMinZ, int areaWidth, int areaHeight,
                     int boxMinX, int boxMaxX, int boxMinZ, int boxMaxZ,
                     int tileMinX, int tileMaxX, int tileMinZ, int tileMaxZ) {
            this(areaMinX, areaMinZ, areaWidth, areaHeight, boxMinX, boxMaxX, boxMinZ, boxMaxZ,
                    tileMinX, tileMaxX, tileMinZ, tileMaxZ, null, 0);
        }

        public Frame(int areaMinX, int areaMinZ, int areaWidth, int areaHeight,
                     int boxMinX, int boxMaxX, int boxMinZ, int boxMaxZ,
                     int tileMinX, int tileMaxX, int tileMinZ, int tileMaxZ,
                     BridgeSpan bridgeSpan, int maxBridgeChunks) {
            this(areaMinX, areaMinZ, areaWidth, areaHeight, boxMinX, boxMaxX, boxMinZ, boxMaxZ,
                    tileMinX, tileMaxX, tileMinZ, tileMaxZ, bridgeSpan, maxBridgeChunks,
                    SIDE_RING, SIDE_RING, SIDE_RING, SIDE_RING);
        }

        /**
         * kindMinX/kindMaxX/kindMinZ/kindMaxZ say what lies just beyond each edge of the
         * tile rect: SIDE_RING (a highway ring -- the default), SIDE_NEIGHBOR (the next
         * ocean tile, which paints the seam street there) or SIDE_PAINT (the next ocean
         * tile is beyond, and THIS tile paints the seam street along its own outermost
         * chunks). See the SIDE_* constants.
         */
        public Frame(int areaMinX, int areaMinZ, int areaWidth, int areaHeight,
                     int boxMinX, int boxMaxX, int boxMinZ, int boxMaxZ,
                     int tileMinX, int tileMaxX, int tileMinZ, int tileMaxZ,
                     BridgeSpan bridgeSpan, int maxBridgeChunks,
                     int kindMinX, int kindMaxX, int kindMinZ, int kindMaxZ) {
            this.kindMinX = kindMinX;
            this.kindMaxX = kindMaxX;
            this.kindMinZ = kindMinZ;
            this.kindMaxZ = kindMaxZ;
            this.bridgeSpan = bridgeSpan;
            this.maxBridgeChunks = maxBridgeChunks;
            this.areaMinX = areaMinX;
            this.areaMinZ = areaMinZ;
            this.areaWidth = areaWidth;
            this.areaHeight = areaHeight;
            this.boxMinX = boxMinX;
            this.boxMaxX = boxMaxX;
            this.boxMinZ = boxMinZ;
            this.boxMaxZ = boxMaxZ;
            this.tileMinX = tileMinX;
            this.tileMaxX = tileMaxX;
            this.tileMinZ = tileMinZ;
            this.tileMaxZ = tileMaxZ;
        }
    }

    /**
     * What the ordinary district carve (UrbanGrid.recSetDistricts()) laid out over a tile's
     * carve rect, recorded instead of painted: each cut's road as a one-chunk-wide segment
     * {x0, z0, x1, z1} (inclusive, world chunk coords) and each leaf it would have handed to
     * addDistrict() as a block {x, z, sizeX, sizeZ}. OceanReclaim never chooses a cut -- it
     * only decides how much of each block is dry enough to build on.
     */
    /**
     * Whether the chunk just OUTSIDE this tile's carve rect on a highway-ring side really is a street.
     * The ring is not drawn everywhere: where the ocean is on both sides of the boundary it is open
     * water (UrbanGrid.placeHighwayCell()), and a road running into that has nothing to join.
     */
    public interface RingProbe {
        boolean isStreet(int chunkX, int chunkZ);
    }

    public static final class Layout {
        final List<int[]> roads = new ArrayList<int[]>();
        final List<int[]> blocks = new ArrayList<int[]>();

        public void road(int x0, int z0, int x1, int z1) {
            roads.add(new int[]{x0, z0, x1, z1});
        }

        public void block(int x, int z, int sizeX, int sizeZ) {
            blocks.add(new int[]{x, z, sizeX, sizeZ});
        }
    }

    /**
     * What lies just beyond one edge of the tile rect handed to compute().
     * Two ocean tiles side by side have NO highway between them (the shared ring
     * sides are dropped and become interior), but they still need a street there:
     * without one, each tile's outermost districts sat flush against the other
     * tile's, and the streets of one side ran into the seam and stopped. So the
     * seam gets ONE street, painted by the tile on the low side (its MAX edge:
     * SIDE_PAINT) and relied on by the tile on the high side (its MIN edge:
     * SIDE_NEIGHBOR). Anywhere else the edge is the highway ring (SIDE_RING).
     */
    public static final int SIDE_RING = 0;
    public static final int SIDE_NEIGHBOR = 1;
    public static final int SIDE_PAINT = 2;

    private static final byte NONE = 0;
    private static final byte ROAD = 1;
    private static final byte DISTRICT = 2;

    private final List<Rect> rects;
    private final List<Rect> roads;
    private final List<BridgeCell> bridges;
    private final int usableChunks;
    private final int districtChunks;
    private final int roadCells;

    private OceanReclaim(List<Rect> rects, List<Rect> roads, List<BridgeCell> bridges, int usableChunks, int districtChunks, int roadCells) {
        this.rects = rects;
        this.roads = roads;
        this.bridges = bridges;
        this.usableChunks = usableChunks;
        this.districtChunks = districtChunks;
        this.roadCells = roadCells;
    }

    /** District rectangles, ready for UrbanGrid.addDistrict(). */
    public List<Rect> getRects() {
        return this.rects;
    }

    /**
     * Every road cell, as horizontal one-chunk-tall runs: lattice streets,
     * the seam road along the box's outer edge, and the frontage roads along
     * shore-facing district sides. Paint these BEFORE any addDistrict().
     */
    public List<Rect> getRoads() {
        return this.roads;
    }

    /**
     * Bridge deck cells: street chunks carried over river water. NOT part of
     * getRoads() -- UrbanGrid places a BridgeParcel on each instead of a road.
     */
    public List<BridgeCell> getBridges() {
        return this.bridges;
    }

    public boolean isEmpty() {
        return this.rects.isEmpty() && this.roads.isEmpty() && this.bridges.isEmpty();
    }

    /** Chunks in this tile's slice that cleared the berth (whether or not they ended up built on). */
    public int getUsableChunkCount() {
        return this.usableChunks;
    }

    public int getDistrictChunkCount() {
        return this.districtChunks;
    }

    public int getRoadCellCount() {
        return this.roadCells;
    }

    // ------------------------------------------------------------------ compute

    public static OceanReclaim compute(OceanProvider ocean, Frame f) {
        if (!RiverConfig.isRiverReclaimEnabled() || f.areaWidth <= 0 || f.areaHeight <= 0) {
            return new OceanReclaim(new ArrayList<Rect>(), new ArrayList<Rect>(), new ArrayList<BridgeCell>(), 0, 0, 0);
        }

        final int w = f.areaWidth;
        final int h = f.areaHeight;
        final int minSize = Math.max(1, RiverConfig.getRiverReclaimMinWidth());

        boolean[][] usable = classifyChunks(ocean, f.areaMinX, f.areaMinZ, w, h);
        int usableCount = 0;
        for (int i = 0; i < w; i++) {
            for (int j = 0; j < h; j++) {
                if (usable[i][j]) {
                    usableCount++;
                }
            }
        }

        Grid g = new Grid(f, usable, minSize, ocean);

        // The layout is whatever the ordinary district carve (UrbanGrid.recSetDistricts())
        // produced for this tile's carve rect: the roads its cuts put down and the blocks
        // it would have turned into districts. Nothing here decides where to cut.
        Layout layout = f.layout;
        if (layout == null) {
            layout = new Layout(); // no carve handed over: treat the whole rect as one block
            layout.block(f.areaMinX, f.areaMinZ, w, h);
        }

        // streets: {x0, z0, x1, z1, seam}, seam = -1 for an ordinary cut road, else 0 / 1 for a seam
        // street running along X / along Z (it only exists where usable land is long enough to sit
        // a district beside -- see Grid.seamRunOk()).
        List<int[]> streets = new ArrayList<int[]>();
        for (int[] road : layout.roads) {
            streets.add(new int[]{road[0], road[1], road[2], road[3], -1});
        }
        if (f.kindMaxX == SIDE_PAINT && f.tileMaxX > f.tileMinX) {
            streets.add(new int[]{f.tileMaxX, f.areaMinZ, f.tileMaxX, f.areaMinZ + h - 1, 1});
        }
        if (f.kindMaxZ == SIDE_PAINT && f.tileMaxZ > f.tileMinZ) {
            streets.add(new int[]{f.areaMinX, f.tileMaxZ, f.areaMinX + w - 1, f.tileMaxZ, 0});
        }

        // 1. paint the streets: usable cells on them become road CANDIDATES (kept or dropped in settle()).
        for (int[] st : streets) {
            for (int x = st[0]; x <= st[2]; x++) {
                for (int z = st[1]; z <= st[3]; z++) {
                    if (st[4] < 0 || g.seamRunOk(x, z, st[4] == 1)) {
                        g.markLattice(x, z);
                    }
                }
            }
        }

        // 1b. where a painted street meets river water, carry it across on a bridge
        //     (settle() drops the bridge again if either end turns out not to be wanted)
        g.markBridges(streets);

        // 1c. drop cut roads the shore has left dead-ended, BEFORE blocks are fitted: fillBlock()
        //     treats a street as a side a district can sit flush against, so it has to know which
        //     ones will actually still be there (a side without one gets its own frontage road).
        g.pruneDeadEnds();

        // 2. fill every carved block (clipped to this tile's slice of the box)
        for (int[] b : layout.blocks) {
            int x0 = Math.max(b[0], f.areaMinX);
            int x1 = Math.min(b[0] + b[2] - 1, f.areaMinX + w - 1);
            int z0 = Math.max(b[1], f.areaMinZ);
            int z1 = Math.min(b[1] + b[3] - 1, f.areaMinZ + h - 1);
            if (x0 > x1 || z0 > z1) {
                continue;
            }
            g.fillBlock(x0, x1, z0, z1);
        }

        // 3+4. keep only roads that serve something, prune dead ends, drop
        //      anything cut off from the network (and the districts that
        //      relied on it) -- repeat until nothing changes, since dropping
        //      a district can orphan the road that served it.
        g.settle();
        g.settle();

        return g.result(usableCount);
    }

    /**
     * A chunk is usable if it's berth-clear of the water EVERYWHERE in its
     * own 16x16 footprint, sampled at all four corners. distanceInsideShoreBlock()
     * is positive in water, <=0 on land, so the WORST (most water-ward)
     * corner has to clear the berth on the land side.
     */
    private static boolean[][] classifyChunks(OceanProvider ocean, int minX, int minZ, int width, int height) {
        boolean[][] usable = new boolean[width][height];
        for (int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                usable[i][j] = chunkUsable(ocean, minX + i, minZ + j);
            }
        }
        return usable;
    }

    /** classifyChunks() for one chunk -- also used for the cell just outside the rect, on a seam side. */
    static boolean chunkUsable(OceanProvider ocean, int chunkX, int chunkZ) {
        double berthBlocks = Math.max(1, RiverConfig.getRiverReclaimBerthChunks()) * 16.0;
        double x0 = chunkX * 16.0;
        double z0 = chunkZ * 16.0;
        double worst = Math.max(
                Math.max(ocean.distanceInsideShoreBlock(x0, z0), ocean.distanceInsideShoreBlock(x0 + 16.0, z0)),
                Math.max(ocean.distanceInsideShoreBlock(x0, z0 + 16.0), ocean.distanceInsideShoreBlock(x0 + 16.0, z0 + 16.0))
        );
        return worst <= -berthBlocks;
    }

    // ------------------------------------------------------------------ working grid

    private static final class Grid {
        final Frame f;
        final int w, h, minSize;
        final boolean[][] usable;
        final byte[][] cell;
        final boolean[][] lattice;
        final OceanProvider ocean;
        final byte[][] bridgeAxis; // 0 = not a bridge, bit 1 = deck runs along X, bit 2 = along Z
        final List<int[]> districts = new ArrayList<int[]>(); // {x, z, sizeX, sizeZ} in world chunk coords

        Grid(Frame f, boolean[][] usable, int minSize, OceanProvider ocean) {
            this.f = f;
            this.ocean = ocean;
            this.w = f.areaWidth;
            this.h = f.areaHeight;
            this.minSize = minSize;
            this.usable = usable;
            this.cell = new byte[w][h];
            this.lattice = new boolean[w][h];
            this.bridgeAxis = new byte[w][h];
        }

        boolean inArea(int x, int z) {
            return x >= f.areaMinX && x < f.areaMinX + w && z >= f.areaMinZ && z < f.areaMinZ + h;
        }

        boolean inTile(int x, int z) {
            return x >= f.tileMinX && x <= f.tileMaxX && z >= f.tileMinZ && z <= f.tileMaxZ;
        }

        /**
         * Is (x,z), a cell OUTSIDE the tile rect, a street? Beyond a highway ring: yes.
         * Beyond a side whose neighbour paints the seam street: yes wherever that chunk
         * is usable land (the neighbour paints it there). Beyond a side this tile
         * paints itself: no -- that is the neighbour's own interior.
         */
        boolean outsideStreet(int x, int z) {
            if ((x > f.tileMaxX && f.kindMaxX == SIDE_PAINT) || (z > f.tileMaxZ && f.kindMaxZ == SIDE_PAINT)) {
                return false;
            }
            boolean neighbour = (x < f.tileMinX && f.kindMinX == SIDE_NEIGHBOR) || (z < f.tileMinZ && f.kindMinZ == SIDE_NEIGHBOR)
                    || (x > f.tileMaxX && f.kindMaxX == SIDE_NEIGHBOR) || (z > f.tileMaxZ && f.kindMaxZ == SIDE_NEIGHBOR);
            if (!neighbour) {
                return f.ring == null || f.ring.isStreet(x, z);
            }
            // the seam street of the neighbouring tile runs along Z beside a west/east
            // edge and along X beside a north/south one
            boolean alongZ = (x < f.tileMinX && f.kindMinX == SIDE_NEIGHBOR) || (x > f.tileMaxX && f.kindMaxX == SIDE_NEIGHBOR);
            boolean alongX = (z < f.tileMinZ && f.kindMinZ == SIDE_NEIGHBOR) || (z > f.tileMaxZ && f.kindMaxZ == SIDE_NEIGHBOR);
            return (alongZ && seamRunOk(x, z, true)) || (alongX && seamRunOk(x, z, false));
        }

        /**
         * Is this chunk part of a seam street? A seam street is every usable chunk of the
         * seam line that lies in a run of at least minSize usable chunks along it (a
         * shorter run could never have a district beside it). Depends ONLY on which
         * chunks are usable, never on what a tile chose to build, so the tile that paints
         * the street and the tile that relies on it always agree on where it is.
         */
        boolean seamRunOk(int x, int z, boolean alongZ) {
            if (!chunkUsable(ocean, x, z)) {
                return false;
            }
            int run = 1;
            for (int d = 1; run < minSize; d++) {
                if (!chunkUsable(ocean, alongZ ? x : x - d, alongZ ? z - d : z)) {
                    break;
                }
                run++;
            }
            for (int d = 1; run < minSize; d++) {
                if (!chunkUsable(ocean, alongZ ? x : x + d, alongZ ? z + d : z)) {
                    break;
                }
                run++;
            }
            return run >= minSize;
        }

        /**
         * On the seam street this tile paints along its own outermost chunk. Never
         * pruned: the tile on the other side treats every usable chunk of it as a
         * street (outsideStreet()), so it has to actually be there.
         */
        boolean onPaintedSeam(int x, int z) {
            return (f.kindMaxX == SIDE_PAINT && x == f.tileMaxX) || (f.kindMaxZ == SIDE_PAINT && z == f.tileMaxZ);
        }

        boolean onBoxPerimeter(int x, int z) {
            return x == f.boxMinX || x == f.boxMaxX || z == f.boxMinZ || z == f.boxMaxZ;
        }

        byte at(int x, int z) {
            return cell[x - f.areaMinX][z - f.areaMinZ];
        }

        void set(int x, int z, byte v) {
            cell[x - f.areaMinX][z - f.areaMinZ] = v;
        }

        void markLattice(int x, int z) {
            if (inArea(x, z) && usable[x - f.areaMinX][z - f.areaMinZ]) {
                lattice[x - f.areaMinX][z - f.areaMinZ] = true;
                cell[x - f.areaMinX][z - f.areaMinZ] = ROAD;
            }
        }

        boolean isBridge(int x, int z) {
            return inArea(x, z) && bridgeAxis[x - f.areaMinX][z - f.areaMinZ] != 0;
        }

        /**
         * Along every painted street, a run of NOT-usable chunks with a lattice street
         * chunk at both ends, no wider than maxBridgeChunks, every chunk of which is
         * river-only water (BridgeSpan), becomes a bridge deck.
         */
        void markBridges(List<int[]> streets) {
            if (f.bridgeSpan == null || f.maxBridgeChunks <= 0) {
                return;
            }
            for (int[] st : streets) {
                boolean alongX = st[1] == st[3] && st[2] > st[0];
                boolean alongZ = st[0] == st[2] && st[3] > st[1];
                if (alongX) { // constant-Z street, running along X
                    int z = st[1];
                    if (z < f.areaMinZ || z >= f.areaMinZ + h) {
                        continue;
                    }
                    int end = Math.min(st[2], f.areaMinX + w - 1);
                    int x = Math.max(st[0], f.areaMinX);
                    while (x <= end) {
                        if (usable[x - f.areaMinX][z - f.areaMinZ]) {
                            x++;
                            continue;
                        }
                        int start = x;
                        while (x <= end && !usable[x - f.areaMinX][z - f.areaMinZ]) {
                            x++;
                        }
                        tryBridge(start, x - 1, z, true);
                    }
                } else if (alongZ) { // constant-X street, running along Z
                    int x = st[0];
                    if (x < f.areaMinX || x >= f.areaMinX + w) {
                        continue;
                    }
                    int end = Math.min(st[3], f.areaMinZ + h - 1);
                    int z = Math.max(st[1], f.areaMinZ);
                    while (z <= end) {
                        if (usable[x - f.areaMinX][z - f.areaMinZ]) {
                            z++;
                            continue;
                        }
                        int start = z;
                        while (z <= end && !usable[x - f.areaMinX][z - f.areaMinZ]) {
                            z++;
                        }
                        tryBridge(start, z - 1, x, false);
                    }
                }
            }
        }

        /** run = [from, to] along the street's own axis; fixed = the street's constant coordinate. */
        private void tryBridge(int from, int to, int fixed, boolean alongX) {
            int lo = from - 1;
            int hi = to + 1;
            int areaLo = alongX ? f.areaMinX : f.areaMinZ;
            int areaHi = areaLo + (alongX ? w : h) - 1;
            if (lo < areaLo || hi > areaHi || to - from + 1 > f.maxBridgeChunks) {
                return;
            }
            // both ends must be real street chunks of this lattice line
            int loX = alongX ? lo : fixed, loZ = alongX ? fixed : lo;
            int hiX = alongX ? hi : fixed, hiZ = alongX ? fixed : hi;
            if (!lattice[loX - f.areaMinX][loZ - f.areaMinZ] || !lattice[hiX - f.areaMinX][hiZ - f.areaMinZ]) {
                return;
            }
            for (int i = from; i <= to; i++) {
                int x = alongX ? i : fixed;
                int z = alongX ? fixed : i;
                if (!f.bridgeSpan.canBridge(x, z)) {
                    return;
                }
            }
            for (int i = from; i <= to; i++) {
                int x = alongX ? i : fixed;
                int z = alongX ? fixed : i;
                cell[x - f.areaMinX][z - f.areaMinZ] = ROAD;
                lattice[x - f.areaMinX][z - f.areaMinZ] = true;
                bridgeAxis[x - f.areaMinX][z - f.areaMinZ] |= (byte) (alongX ? 1 : 2);
            }
        }

        boolean isFree(int x, int z) {
            return usable[x - f.areaMinX][z - f.areaMinZ] && cell[x - f.areaMinX][z - f.areaMinZ] == NONE;
        }

        /** Is (x,z) -- a cell just outside a candidate district -- already a street (or the highway ring)? */
        boolean backed(int x, int z) {
            if (!inTile(x, z)) {
                return outsideStreet(x, z);   // highway ring / the neighbour's seam street
            }
            if (!inArea(x, z)) {
                return false;
            }
            // a bridge deck is only open at its two span ends -- a district can't face its side
            return at(x, z) == ROAD && !isBridge(x, z);
        }

        /** 1 = every cell along this side is already a street, 0 = none is, -1 = mixed (a district can't sit against that). */
        int sideBacked(int x0, int x1, int z0, int z1) {
            int yes = 0;
            int total = 0;
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    total++;
                    if (backed(x, z)) {
                        yes++;
                    }
                }
            }
            return yes == total ? 1 : (yes == 0 ? 0 : -1);
        }

        /**
         * Fills one block with districts: repeatedly takes the rectangle that
         * leaves the biggest district AFTER paying for a frontage road on every
         * side that isn't already a street, until nothing >= minSize fits.
         */
        void fillBlock(int x0, int x1, int z0, int z1) {
            while (true) {
                int bestScore = -1;
                int[] best = null;
                boolean[] bestBacked = null;
                for (int ax0 = x0; ax0 <= x1; ax0++) {
                    for (int az0 = z0; az0 <= z1; az0++) {
                        if (!isFree(ax0, az0)) {
                            continue;
                        }
                        for (int ax1 = ax0; ax1 <= x1; ax1++) {
                            if (!isFree(ax1, az0)) {
                                break;
                            }
                            for (int az1 = az0; az1 <= z1; az1++) {
                                if (!rowFree(ax0, ax1, az1)) {
                                    break;
                                }
                                // upper bound (every side already a street): skip if it can't beat the best so far
                                if ((ax1 - ax0 + 1) * (az1 - az0 + 1) * 1000 + (ax1 - ax0 + 1) * (az1 - az0 + 1) <= bestScore) {
                                    continue;
                                }
                                // A side is either fully on a street or not on one at all. A side that is
                                // only PARTLY on a street (street broken by water) would either leave a
                                // gap or force a second road right beside the first -- skip it, a
                                // slightly smaller rectangle that lines up cleanly will be found.
                                int w0 = sideBacked(ax0 - 1, ax0 - 1, az0, az1);
                                int e0 = sideBacked(ax1 + 1, ax1 + 1, az0, az1);
                                int n0 = sideBacked(ax0, ax1, az0 - 1, az0 - 1);
                                int s0 = sideBacked(ax0, ax1, az1 + 1, az1 + 1);
                                if (w0 < 0 || e0 < 0 || n0 < 0 || s0 < 0) {
                                    continue;
                                }
                                boolean west = w0 == 1;
                                boolean east = e0 == 1;
                                boolean north = n0 == 1;
                                boolean south = s0 == 1;
                                int sx = ax1 - ax0 + 1 - (west ? 0 : 1) - (east ? 0 : 1);
                                int sz = az1 - az0 + 1 - (north ? 0 : 1) - (south ? 0 : 1);
                                if (sx < minSize || sz < minSize) {
                                    continue;
                                }
                                int score = sx * sz * 1000 + (ax1 - ax0 + 1) * (az1 - az0 + 1);
                                if (score > bestScore) {
                                    bestScore = score;
                                    best = new int[]{ax0, ax1, az0, az1};
                                    bestBacked = new boolean[]{west, east, north, south};
                                }
                            }
                        }
                    }
                }
                if (best == null) {
                    return;
                }
                commit(best[0], best[1], best[2], best[3], bestBacked);
            }
        }

        boolean rowFree(int x0, int x1, int z) {
            for (int x = x0; x <= x1; x++) {
                if (!isFree(x, z)) {
                    return false;
                }
            }
            return true;
        }

        void commit(int ax0, int ax1, int az0, int az1, boolean[] backed) {
            int bx0 = ax0, bx1 = ax1, bz0 = az0, bz1 = az1;
            if (!backed[0]) {
                for (int z = az0; z <= az1; z++) {
                    set(ax0, z, ROAD);
                }
                bx0++;
            }
            if (!backed[1]) {
                for (int z = az0; z <= az1; z++) {
                    set(ax1, z, ROAD);
                }
                bx1--;
            }
            if (!backed[2]) {
                for (int x = bx0; x <= bx1; x++) {
                    set(x, az0, ROAD);
                }
                bz0++;
            }
            if (!backed[3]) {
                for (int x = bx0; x <= bx1; x++) {
                    set(x, az1, ROAD);
                }
                bz1--;
            }
            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    set(x, z, DISTRICT);
                }
            }
            districts.add(new int[]{bx0, bz0, bx1 - bx0 + 1, bz1 - bz0 + 1});
        }

        // ---------------------------------------------------------- settle

        boolean isRoad(int x, int z) {
            return inArea(x, z) && at(x, z) == ROAD;
        }

        /** True if this bridge cell has street/bridge on both sides along (at least one of) its deck axes. */
        boolean deckContinues(int x, int z) {
            int bits = bridgeAxis[x - f.areaMinX][z - f.areaMinZ];
            return ((bits & 1) != 0 && isRoad(x - 1, z) && isRoad(x + 1, z))
                    || ((bits & 2) != 0 && isRoad(x, z - 1) && isRoad(x, z + 1));
        }

        boolean deckAlongX(int x, int z) {
            int bits = bridgeAxis[x - f.areaMinX][z - f.areaMinZ];
            if ((bits & 1) != 0 && (bits & 2) != 0) {
                return isRoad(x - 1, z) && isRoad(x + 1, z);
            }
            return (bits & 1) != 0;
        }

        boolean nextToDistrict8(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && inArea(x + dx, z + dz) && at(x + dx, z + dz) == DISTRICT) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Road neighbours, counting the highway ring as one wherever this cell sits on the tile's edge. */
        int degree(int x, int z) {
            int d = 0;
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] dir : dirs) {
                int nx = x + dir[0];
                int nz = z + dir[1];
                if (inTile(nx, nz) ? isRoad(nx, nz) : outsideStreet(nx, nz)) {
                    d++;
                }
            }
            return d;
        }

        boolean anchored(int x, int z) {
            return onBoxPerimeter(x, z) || onPaintedSeam(x, z)
                    || (x == f.tileMinX && outsideStreet(x - 1, z)) || (x == f.tileMaxX && outsideStreet(x + 1, z))
                    || (z == f.tileMinZ && outsideStreet(x, z - 1)) || (z == f.tileMaxZ && outsideStreet(x, z + 1));
        }

        /**
         * Drops every road cell with fewer than two street neighbours, repeatedly, so a road that
         * ends at nothing is removed all the way back to the junction it hangs off. Returns whether
         * anything was dropped. Used twice: before any block is filled (compute(), so a cut road
         * the shore has broken is gone BEFORE fillBlock() decides which block sides still have a
         * street -- otherwise a district is fitted flush against a road that is then pruned out from
         * under it, leaving it with no street on that side), and again from settle(), since filling
         * can add frontage roads of its own that dead-end.
         */
        boolean pruneDeadEnds() {
            boolean any = false;
            boolean pruned = true;
            while (pruned) {
                pruned = false;
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                        if (at(x, z) == ROAD && !onPaintedSeam(x, z) && degree(x, z) <= 1) {
                            set(x, z, NONE);
                            pruned = true;
                            any = true;
                        }
                    }
                }
            }
            return any;
        }

        void settle() {
            for (int pass = 0; pass < 16; pass++) {
                boolean changed = false;

                // a road cell only stays if it serves a district, or is the seam road
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                        if (at(x, z) == ROAD && !onBoxPerimeter(x, z) && !onPaintedSeam(x, z) && !isBridge(x, z) && !nextToDistrict8(x, z)) {
                            set(x, z, NONE);
                            changed = true;
                        }
                    }
                }

                // a bridge cell must still be carried on along its deck axis at both ends
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                        if (isBridge(x, z) && !deckContinues(x, z)) {
                            set(x, z, NONE);
                            bridgeAxis[x - f.areaMinX][z - f.areaMinZ] = 0;
                            changed = true;
                        }
                    }
                }

                // dead-end pruning
                if (pruneDeadEnds()) {
                    changed = true;
                }

                // connectivity: keep only road reachable from the seam road / highway ring
                boolean[][] seen = new boolean[w][h];
                List<int[]> stack = new ArrayList<int[]>();
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                        if (at(x, z) == ROAD && anchored(x, z)) {
                            seen[x - f.areaMinX][z - f.areaMinZ] = true;
                            stack.add(new int[]{x, z});
                        }
                    }
                }
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                while (!stack.isEmpty()) {
                    int[] c = stack.remove(stack.size() - 1);
                    for (int[] dir : dirs) {
                        int nx = c[0] + dir[0];
                        int nz = c[1] + dir[1];
                        if (isRoad(nx, nz) && !seen[nx - f.areaMinX][nz - f.areaMinZ]) {
                            seen[nx - f.areaMinX][nz - f.areaMinZ] = true;
                            stack.add(new int[]{nx, nz});
                        }
                    }
                }
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                        if (at(x, z) == ROAD && !seen[x - f.areaMinX][z - f.areaMinZ]) {
                            set(x, z, NONE);
                            changed = true;
                        }
                    }
                }

                // a road cell -- box-perimeter seam, tile-to-tile painted seam,
                // interior, or bridge alike -- only survives if it's connected,
                // through other road cells, to something that actually touches a
                // kept district somewhere. The box-perimeter seam, the painted
                // tile-to-tile seam, and bridges are deliberately exempt from the
                // plain per-cell "next to a district" prune above -- a bridge's
                // own middle cells span open water and can never themselves be
                // next to a district, and both kinds of seam need to run
                // continuously past a short gap between two nearby developed
                // stretches, not fragment into a stub in front of each one. But
                // that exemption has no lower bound: a run of seam tracing an
                // empty stretch of coast (or an empty stretch of the tile-to-tile
                // seam) with nothing built anywhere along it, or a bridge whose
                // far bank never got developed, is exempt from the per-cell check
                // for exactly the same reason and survives just as permanently,
                // with nothing to justify it. This pass is the actual backstop:
                // whatever a whole connected run of road never reaches -- not one
                // cell of it anywhere along that run -- gets dropped as a unit,
                // seam (either kind) and bridge included.
                boolean[][] servesDistrict = new boolean[w][h];
                List<int[]> dStack = new ArrayList<int[]>();
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                        if (at(x, z) == ROAD && nextToDistrict8(x, z)) {
                            servesDistrict[x - f.areaMinX][z - f.areaMinZ] = true;
                            dStack.add(new int[]{x, z});
                        }
                    }
                }
                while (!dStack.isEmpty()) {
                    int[] c = dStack.remove(dStack.size() - 1);
                    for (int[] dir : dirs) {
                        int nx = c[0] + dir[0];
                        int nz = c[1] + dir[1];
                        if (isRoad(nx, nz) && !servesDistrict[nx - f.areaMinX][nz - f.areaMinZ]) {
                            servesDistrict[nx - f.areaMinX][nz - f.areaMinZ] = true;
                            dStack.add(new int[]{nx, nz});
                        }
                    }
                }
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                        if (at(x, z) == ROAD && !servesDistrict[x - f.areaMinX][z - f.areaMinZ]) {
                            set(x, z, NONE);
                            changed = true;
                        }
                    }
                }

                // a district with no street on any side is dropped (left as green land)
                List<int[]> kept = new ArrayList<int[]>();
                for (int[] d : districts) {
                    if (touchesRoad(d)) {
                        kept.add(d);
                    } else {
                        for (int x = d[0]; x < d[0] + d[2]; x++) {
                            for (int z = d[1]; z < d[1] + d[3]; z++) {
                                set(x, z, NONE);
                            }
                        }
                        changed = true;
                    }
                }
                districts.clear();
                districts.addAll(kept);

                if (!changed) {
                    return;
                }
            }
        }

        boolean touchesRoad(int[] d) {
            for (int x = d[0] - 1; x <= d[0] + d[2]; x++) {
                for (int z = d[1] - 1; z <= d[1] + d[3]; z++) {
                    boolean edgeX = x == d[0] - 1 || x == d[0] + d[2];
                    boolean edgeZ = z == d[1] - 1 || z == d[1] + d[3];
                    if (edgeX == edgeZ) {
                        continue; // corners and the interior don't count
                    }
                    if (inTile(x, z) ? (isRoad(x, z) && !isBridge(x, z)) : outsideStreet(x, z)) {
                        return true;
                    }
                }
            }
            return false;
        }

        OceanReclaim result(int usableCount) {
            List<Rect> rects = new ArrayList<Rect>();
            int districtChunks = 0;
            for (int[] d : districts) {
                rects.add(new Rect(d[0], d[1], d[2], d[3]));
                districtChunks += d[2] * d[3];
            }
            List<Rect> roads = new ArrayList<Rect>();
            List<BridgeCell> bridges = new ArrayList<BridgeCell>();
            int roadCells = 0;
            for (int z = f.areaMinZ; z < f.areaMinZ + h; z++) {
                for (int x = f.areaMinX; x < f.areaMinX + w; x++) {
                    if (at(x, z) == ROAD && isBridge(x, z)) {
                        bridges.add(new BridgeCell(x, z, deckAlongX(x, z)));
                    }
                }
                int runStart = Integer.MIN_VALUE;
                for (int x = f.areaMinX; x <= f.areaMinX + w; x++) {
                    boolean road = x < f.areaMinX + w && at(x, z) == ROAD && !isBridge(x, z);
                    if (road && runStart == Integer.MIN_VALUE) {
                        runStart = x;
                    } else if (!road && runStart != Integer.MIN_VALUE) {
                        roads.add(new Rect(runStart, z, x - runStart, 1));
                        roadCells += x - runStart;
                        runStart = Integer.MIN_VALUE;
                    }
                }
            }
            return new OceanReclaim(rects, roads, bridges, usableCount, districtChunks, roadCells);
        }
    }
}