package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanPortAnnouncements;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.DirtyHacks;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.MultipleFacing;
import java.util.Set;
import java.util.LinkedHashSet;
import org.bukkit.Axis;

/**
 * One deliberately small, street-served working landing. This is NOT a replacement coast painter:
 * the natural ocean is carved first; the landing occupies only one 16-block-wide strip of shore
 * plus two cells of water and a narrow access lane across natural shore. No port is placed without a real road at its inland end and enough
 * flat, deep, uninterrupted water for the pier and its T-head.
 *
 * Coordinates: t increases away from the city toward water, s increases to the right of that
 * direction. Anchor chunk covers t=-8..7, reserved tails cover t=8..23 and 24..39.
 * Building geometry stays within s=-7..7, t=-7..37; the six-block gravel lane runs inland. The street cutout is deliberately
 * separate: it waits for the road to FINISH, using PlacedParcels, regardless of chunk load order.
 */
public final class OceanPortParcel extends OceanParcel {
    private final int dx, dz;
    private final int streetDistance;
    private final int roadChunkX, roadChunkZ;
    private boolean built;
    // Bars are finalized after port construction, not when first laid down.
    private final Set<Block> portBars = new LinkedHashSet<>();
    private static final BlockFace[] BAR_SIDES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private final long variation;
    public enum Kind { RESIDENTIAL_LANDING, COMMERCIAL_QUAY, INDUSTRIAL_WHARF }
    private final Kind kind;
    // Reclaimed industrial block fronts the LEFT or RIGHT edge of the actual
    // access lane, not a fictional perimeter street on the other side.
    private int annexSide = 0;

    public void setAnnexSide(int side) { annexSide = Integer.compare(side, 0); }
    private int laneMin() { return annexSide < 0 ? -7 : annexSide > 0 ? 2 : 1; }
    private int laneMax() { return annexSide < 0 ? -2 : annexSide > 0 ? 7 : 6; }

    /** Legacy constructor preserves the originally approved neighborhood landing. */
    public OceanPortParcel(UrbanGrid grid, Cartesian2D base, int outwardX, int outwardZ, int streetDistance, long variation) {
        this(grid, base, outwardX, outwardZ, streetDistance, variation, Kind.RESIDENTIAL_LANDING);
    }

    public OceanPortParcel(UrbanGrid grid, Cartesian2D base, int outwardX, int outwardZ,
                           int streetDistance, long variation, Kind kind) {
        super(grid, base);
        this.kind = kind;
        this.dx = outwardX;
        this.dz = outwardZ;
        this.streetDistance = streetDistance;
        this.roadChunkX = this.chunkX - streetDistance * dx;
        this.roadChunkZ = this.chunkZ - streetDistance * dz;
        this.variation = variation;
        // Ports are inserted after the immutable world layout. Tell the shared
        // shore/wilderness relief that their actual landward footprint is now
        // hard, grade-level city ground. Never mark the seaward tail as hard.
        grid.markPortGradedCell(chunkX, chunkZ);
        for (int t = 1; t < streetDistance; t++) {
            grid.markPortGradedCell(chunkX - t * dx, chunkZ - t * dz);
        }
        // Real commercial/industrial sites span THREE CHUNK COLUMNS along the shore,
        // with their own reserved ocean/shore parcel for each cell. Smaller residential
        // docks keep the original one-column footprint and are otherwise unchanged.
        for (int t = 0; t <= seawardCells(); t++) {
            for (int side = -sideCells(); side <= sideCells(); side++) {
                if (t == 0 && side == 0) continue;  // this anchor
                int x = chunkX + t * dx - side * dz;
                int z = chunkZ + t * dz + side * dx;
                new OceanPortTailParcel(grid, new Cartesian2D(x, z));
                grid.markPortConstructionCell(x, z);
                if (t == 0) grid.markPortGradedCell(x, z);
            }
        }
        for (int t = 1; t < streetDistance; t++) {
            new OceanPortInlandParcel(grid, new Cartesian2D(chunkX - t * dx, chunkZ - t * dz));
        }
    }

    public int sideCells() { return kind == Kind.RESIDENTIAL_LANDING ? 0 : 1; }
    public int seawardCells() { return kind == Kind.RESIDENTIAL_LANDING ? 2 : 3; }
    public Kind kind() { return kind; }

    @Override
    public void populate(MetropolisGenerator generator, Chunk chunk) {
        if (chunk.getX() != chunkX || chunk.getZ() != chunkZ) return;
        World world = generator.getWorld();
        if (world == null) return;
        // All reserved columns are carved BEFORE placing a single dock block. The tail parcels
        // never independently repaint over these finished blocks, no matter what loads first.
        for (int t = 0; t <= seawardCells(); t++) {
            for (int side = -sideCells(); side <= sideCells(); side++) {
                if (t == 0 && side == 0) continue;
                int x = chunkX + dx * t - side * dz;
                int z = chunkZ + dz * t + side * dx;
                Parcel p = grid.peekParcel(x, z);
                if (!(p instanceof OceanPortTailParcel)) {
                    Minions.w("Port reservation lost near " + x + "," + z);
                    return;
                }
                ((OceanPortTailParcel) p).carveForPort(generator);
            }
        }
        for (int t = 1; t < streetDistance; t++) {
            Parcel p = grid.peekParcel(chunkX - dx * t, chunkZ - dz * t);
            if (!(p instanceof OceanPortInlandParcel)) {
                Minions.w("Port access reservation lost near " + chunkX + "," + chunkZ);
                return;
            }
            ((OceanPortInlandParcel) p).carveForPort(generator);
        }
        super.populate(generator, chunk);
        build(world, grid.getOceanProvider());
        finishPortBarConnections();
        built = true;
        // Only announce completion after all blocks (including empty loot chests) are placed.
        OceanPortAnnouncements.built(generator, chunkX, chunkZ);
    }

    @Override
    public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
        if (built && chunk.getX() == chunkX && chunk.getZ() == chunkZ) {
            // Road-after-port and port-after-road both converge on the exact same small cutout.
            PlacedParcels.of(generator).portFinished(this,
                    roadChunkX, roadChunkZ, generator);
        }
    }

    /** Called only by PlacedParcels once both this landing and its inland road are complete. */
    public void cutRoadApproach(StreetParcel road, MetropolisGenerator generator) {
        if (!built || road.getChunkX() != roadChunkX || road.getChunkZ() != roadChunkZ) return;
        World w = generator.getWorld();
        if (w == null || !w.isChunkLoaded(roadChunkX, roadChunkZ)) return;
        int y = StreetParcel.ROAD_SURFACE_Y;
        // Five-block-wide entrance, only four blocks into the road. Do not erase its carriageway.
        int contact = -8 - 16 * (streetDistance - 1);
        for (int t = contact - 3; t <= contact; t++) {
            for (int s = laneMin(); s <= laneMax(); s++) {
                int x = bx(s, t), z = bz(s, t);
                for (int h = y + 1; h <= y + 4; h++) put(w, x, h, z, Material.AIR);
                // Reconstruct the actual approach (including any excavated
                // sidewalk crossing), not just AIR cells. Keep structural
                // stone immediately beneath it so no dirt cross-section shows.
                put(w, x, y, z, Material.STONE);
                put(w, x, y - 1, z, Material.STONE);
                put(w, x, y - 2, z, Material.STONE);
            }
        }
    }

    // Align the local 16-wide strip to chunk boundaries for ALL four
    // orientations. The midpoint is block 8 for a positive axis and 7 for a
    // negative axis; always using +8 shifted north/west builds into strangers'
    // chunks at the very last block of the approach and the pier.
    private int bx(int s, int t) {
        return (chunkX << 4) + (dx > 0 || dz < 0 ? 8 : 7) - dz * s + dx * t;
    }
    private int bz(int s, int t) {
        return (chunkZ << 4) + (dz > 0 || dx > 0 ? 8 : 7) + dx * s + dz * t;
    }
    private static void put(World w, int x, int y, int z, Material m) {
        if (y > 0 && y < 255) w.getBlockAt(x, y, z).setType(m, false);
    }
    private void block(World w, int s, int y, int t, Material m) {
        if (y <= 0 || y >= 255) return;
        Block placed = w.getBlockAt(bx(s, t), y, bz(s, t));
        placed.setType(m, false);
        if (m == Material.IRON_BARS) portBars.add(placed);
    }

    private void finishPortBarConnections() {
        resolvePortBarConnections(portBars);
        portBars.clear();
    }

    // R30ea connects a port bar to neighboring solids, panes, glass and bars,
    // but never causes a neighboring chunk to be generated in the process.
    static boolean portBarAttachesTo(Material material) {
        return material == Material.IRON_BARS || material == Material.GLASS_PANE
                || material.name().endsWith("_GLASS_PANE")
                || material == Material.GLASS || material.name().endsWith("_GLASS")
                || material.isOccluding();
    }

    static void resolvePortBarConnections(Iterable<Block> portBars) {
        for (Block block : portBars) {
            if (block.getType() != Material.IRON_BARS) continue;
            if (!(block.getBlockData() instanceof MultipleFacing bars)) continue;
            boolean changed = false;
            for (BlockFace direction : BAR_SIDES) {
                if (!bars.getAllowedFaces().contains(direction)) continue;
                int neighborX = block.getX() + direction.getModX();
                int neighborZ = block.getZ() + direction.getModZ();
                World world = block.getWorld();
                if (!world.isChunkLoaded(Math.floorDiv(neighborX, 16), Math.floorDiv(neighborZ, 16))) continue;
                Material adjacent = world.getBlockAt(neighborX, block.getY(), neighborZ).getType();
                boolean connects = portBarAttachesTo(adjacent);
                if (bars.hasFace(direction) != connects) {
                    bars.setFace(direction, connects);
                    changed = true;
                }
            }
            if (changed) block.setBlockData(bars, false);
        }
    }

    /** Steel-colored construction substitutes retain the exact old concrete color data. */
    private void grayConcrete(World w, int s, int y, int t) { block(w, s, y, t, Material.GRAY_CONCRETE); }
    private void lightGrayConcrete(World w, int s, int y, int t) { block(w, s, y, t, Material.LIGHT_GRAY_CONCRETE); }
    private void whiteConcrete(World w, int s, int y, int t) { block(w, s, y, t, Material.WHITE_CONCRETE); }

    /** Convert only the former port's documented metadata variants to native modern block states. */
    private void legacyBlock(World w, int s, int y, int t, Material m, int data) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return;
        Block b = w.getBlockAt(bx(s, t), y, bz(s, t));
        BlockData state = m.createBlockData();
        if (m == Material.SPRUCE_SLAB && state instanceof Slab slab) {
            slab.setType((data & 8) != 0 ? Slab.Type.TOP : Slab.Type.BOTTOM);
        } else if (m == Material.SPRUCE_LOG && state instanceof Orientable log) {
            log.setAxis((data & 12) == 4 ? Axis.X : (data & 12) == 8 ? Axis.Z : Axis.Y);
        } else if (m == Material.OAK_STAIRS && state instanceof Stairs stairs) {
            stairs.setFacing(stairFace(data));
        } else if (m == Material.OAK_WALL_SIGN && state instanceof Directional sign) {
            sign.setFacing(switch (data) {
                case 2 -> BlockFace.NORTH;
                case 3 -> BlockFace.SOUTH;
                case 4 -> BlockFace.WEST;
                case 5 -> BlockFace.EAST;
                default -> throw new IllegalArgumentException("Unexpected wall sign facing: " + data);
            });
        } else if (m == Material.OAK_DOOR && state instanceof Door door) {
            door.setHalf((data & 8) != 0 ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
            if ((data & 8) == 0) door.setFacing(stairFace(data & 3));
            else if (b.getRelative(BlockFace.DOWN).getBlockData() instanceof Door lower) {
                door.setFacing(lower.getFacing());
                door.setHinge(lower.getHinge());
                door.setOpen(lower.isOpen());
            }
        } else if (m == Material.OAK_PLANKS && data == 1) {
            // Old WOOD metadata 1 means spruce, not oak.
            state = Material.SPRUCE_PLANKS.createBlockData();
        } else if (m == Material.GRAY_CONCRETE || m == Material.LIGHT_GRAY_CONCRETE || m == Material.WHITE_CONCRETE) {
            // Colors are already explicit materials; metadata no longer applies.
        } else if (m != Material.OAK_PLANKS) {
            throw new IllegalArgumentException("Unhandled historic port state: " + m + ":" + data);
        }
        b.setBlockData(state, false);
    }

    private static BlockFace stairFace(int data) {
        return switch (data & 3) {
            case 0 -> BlockFace.EAST;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.SOUTH;
            default -> BlockFace.NORTH;
        };
    }

    // Minecraft 1.12: spruce WOOD_STEP is data 1, with the upper-half bit 0x8.
    // Upper slabs fill y+0.5..y+1.0. Every visible timber frame MUST occupy
    // block layer y too, so the entire deck (slabs + full-log framing) is flush.
    private void upperTimberSlab(World w, int s, int y, int t) {
        legacyBlock(w, s, y, t, Material.SPRUCE_SLAB, 9);
    }

    // 1.12 LOG data: spruce=1; x-axis=4, z-axis=8. Use actual horizontal
    // log orientation so the timber reads as a load-bearing beam, not a row
    // of upright stumps. Deck framing never uses fences as cross-bracing.
    static BlockFace dockTransitionFacing(int previous, int current, int next, int dx, int dz) {
        if (previous > current) return stairFace(stairFacing(-dx, -dz));
        if (next > current) return stairFace(stairFacing(dx, dz));
        return null;
    }

    private void timberDeckSurface(World w, int s, int t, int waterY) {
        int y = grade(t, waterY);
        upperTimberSlab(w, s, y, t);
        BlockFace facing = dockTransitionFacing(grade(t - 1, waterY), y, grade(t + 1, waterY), dx, dz);
        if (facing == null) return;
        Block target = w.getBlockAt(bx(s, t), y + 1, bz(s, t));
        Stairs step = (Stairs) Material.SPRUCE_STAIRS.createBlockData();
        step.setFacing(facing);
        step.setHalf(Bisected.Half.BOTTOM);
        step.setShape(Stairs.Shape.STRAIGHT);
        target.setBlockData(step, false);
    }

    private void timberBeam(World w, int s, int y, int t, boolean alongPier) {
        boolean worldX = alongPier ? dx != 0 : dz != 0;
        legacyBlock(w, s, y, t, Material.SPRUCE_LOG, 1 | (worldX ? 4 : 8));
    }

    private static int wallSignFacing(int x, int z) {
        return x > 0 ? 5 : x < 0 ? 4 : z > 0 ? 3 : 2;
    }

    private static int stairFacing(int x, int z) {
        return x > 0 ? 0 : x < 0 ? 1 : z > 0 ? 2 : 3;
    }

    /** Seat rotation is contextual: face the desk or work surface. The sign arms
     *  are kept on the two lateral sides of the oriented stair. */
    private void chair(World w, int s, int y, int t, int faceS, int faceT) {
        int fx = -dz * faceS + dx * faceT;
        int fz = dx * faceS + dz * faceT;
        legacyBlock(w, s, y, t, Material.OAK_STAIRS, stairFacing(fx, fz));
        int leftS = -faceT, leftT = faceS;
        int sideX = -dz * leftS + dx * leftT;
        int sideZ = dx * leftS + dz * leftT;
        // WALL_SIGN faces AWAY from its supporting block. Each side sign
        // must face outward so it attaches to the chair stair, not to air.
        legacyBlock(w, s + leftS, y, t + leftT, Material.OAK_WALL_SIGN,
                wallSignFacing(sideX, sideZ));
        legacyBlock(w, s - leftS, y, t - leftT, Material.OAK_WALL_SIGN,
                wallSignFacing(-sideX, -sideZ));
    }

    /** Remove vegetation/old shore material above the particular foundation, not whole beach chunks. */
    private void settle(World w, OceanProvider o, int s, int t, int top, Material surface) {
        int x = bx(s, t), z = bz(s, t);
        int from = Math.min(w.getMaxHeight() - 1, Math.max(top + 6, w.getHighestBlockYAt(x, z) + 2));
        for (int y = top + 1; y <= from; y++) put(w, x, y, z, Material.AIR);
        OceanProvider.Sample sample = o.sample(x, z);
        if (sample.distanceInside > 0) {
            // Piled construction on water; never fill a whole ocean column with stone.
            if ((s == 0 || s == 7 || s == -7 || s == -1) && Math.floorMod(t, 4) == 0) {
                piles(w, o, s, t, top);
            }
        } else {
            int bottom = Math.max(1, Math.min(top, w.getHighestBlockYAt(x, z) - 1));
            for (int y = bottom; y < top; y++) block(w, s, y, t, Material.COBBLESTONE);
        }
        block(w, s, top, t, surface);
    }

    private void piles(World w, OceanProvider o, int s, int t, int deck) {
        OceanProvider.Sample sample = o.sample(bx(s, t), bz(s, t));
        int bed = sample.surfaceY - Math.max(1, sample.columnDepth);
        int bottom = Math.max(1, Math.min(deck - 1, bed));
        // Spruce pile rises to the framing course itself (not merely one
        // block below the slabs). At frames its upper block is joined by
        // full-height horizontal log beams, all finishing at y+1.
        for (int y = bottom; y <= deck; y++) legacyBlock(w, s, y, t, Material.SPRUCE_LOG, 1);
    }

    private int grade(int t, int waterY) {
        // Warehouse yard stays level with the existing carriageway. Only the
        // timber shore ramp changes height; the berth itself is flat.
        int street = StreetParcel.ROAD_SURFACE_Y;
        int deck = waterY + 2;
        if (t <= 7) return street;
        if (t >= 18) return deck;
        double f = (t - 7) / 11.0;
        return street + (int) Math.round((deck - street) * f);
    }

    private void build(World w, OceanProvider o) {
        int waterY = o.waterSurfaceY();
        int floor = StreetParcel.ROAD_SURFACE_Y;
        // City-to-port lane: only six blocks wide; it replaces a narrow band of natural
        // shore, NOT a continuous boardwalk or an artificial coastline. Graded and supported.
        int contact = -8 - 16 * (streetDistance - 1);
        for (int t = contact; t <= -8; t++) {
            for (int s = laneMin(); s <= laneMax(); s++) {
                // The first few blocks continue the street's stone surface;
                // farther down, the quiet landing lane becomes worn gravel.
                // Only two narrow cobble edges retain the gravel approach.
                Material lane = kind != Kind.RESIDENTIAL_LANDING ? Material.STONE
                        : (t - contact < 10) ? Material.STONE
                        : ((s == laneMin() || s == laneMax()) ? Material.COBBLESTONE : Material.GRAVEL);
                settle(w, o, s, t, StreetParcel.ROAD_SURFACE_Y, lane);
            }
        }
        // Narrow, worn stone forecourt with working loading lane.
        for (int t = -7; t <= 7; t++) {
            for (int s = kind == Kind.INDUSTRIAL_WHARF ? -7 : 0; s <= 7; s++) {
                int y = grade(t, waterY);
                settle(w, o, s, t, y, (s == (kind == Kind.INDUSTRIAL_WHARF ? -7 : 0) || s == 7 || (t % 9 == 0))
                        ? Material.COBBLESTONE : Material.STONE);
            }
        }
        if (kind == Kind.COMMERCIAL_QUAY) {
            commercialTradingQuay(w, o, floor, waterY);
            return;
        }
        if (kind == Kind.INDUSTRIAL_WHARF) {
            industrialFreightPort(w, o, floor, waterY);
            return;
        }
        warehouse(w, o, floor);
        // The path narrows to a proper pier. Each pile is anchored to the sampled seabed;
        // rails never enclose the working edge and there is no continuous coastal boardwalk.
        for (int t = 8; t <= 37; t++) {
            int y = grade(t, waterY);
            int left = t >= 30 ? -5 : 2;
            for (int s = left; s <= 7; s++) {
                // Only the timber deck, not a gigantic stone-fill pier or sea wall.
                timberDeckSurface(w, s, t, waterY);
                // Remove air/foliage above deck but preserve water below it.
                for (int h = y + 1; h <= y + 3; h++) block(w, s, h, t, Material.AIR);
            }
            // Exposed deck edges remain upper spruce SLABS. The only full
            // logs in this course are genuine pile-connected crossbeams.
            // They are flush with slabs; there is no half-block gap.
            if (t % 5 == 0 || t == 8 || t == 29 || t == 30 || t == 37) {
                // Posts run continuously from the sampled bed to deck level.
                // Transverse beams join the pile tops across the decking at
                // that same level, so there is no floating half-block gap.
                piles(w, o, left, t, y);
                piles(w, o, 7, t, y);
                for (int s = left; s <= 7; s++) timberBeam(w, s, y, t, false);
            }
        }
        // Repeated mooring bollards/fenders only at functional corners and berth positions.
        for (int t : new int[]{16, 26, 36}) {
            block(w, 7, grade(t, waterY) + 1, t, Material.OAK_FENCE);
            block(w, 7, grade(t, waterY) - 1, t, Material.SPRUCE_LOG);
        }
        for (int s : new int[]{-5, 7}) {
            block(w, s, grade(36, waterY) + 1, 36, Material.OAK_FENCE);
            block(w, s, grade(36, waterY) - 1, 36, Material.SPRUCE_LOG);
        }
        // Small cargo piles rather than decorative clutter obstructing the entire deck.
        for (int t : new int[]{31, 33}) {
            block(w, -3, grade(t, waterY) + 1, t, Material.OAK_PLANKS);
            block(w, -2, grade(t, waterY) + 1, t, Material.OAK_PLANKS);
        }
        // A compact quayside cargo davit, outside the usable walking lane.
        // The hoist hangs OVER water, not across the pier or a warehouse doorway.
        int craneDeck = grade(35, waterY);
        // Boom and hook remain within this port's reserved 16-block strip.
        for (int h = 1; h <= 5; h++) block(w, -5, craneDeck + h, 35, Material.IRON_BARS);
        for (int s = -7; s <= -6; s++) block(w, s, craneDeck + 5, 35, Material.IRON_BARS);
        for (int h = 3; h <= 4; h++) block(w, -7, craneDeck + h, 35, Material.OAK_FENCE);
        block(w, -7, craneDeck + 2, 35, Material.IRON_BARS);
        block(w, 5, grade(14, waterY) + 1, 14, Material.OAK_FENCE);
        // No placed lighting: keep the bollard unlit.
    }

    /** The multi-chunk variants use dry masonry and enclosed, load-bearing storage.
     *  The original neighborhood dock remains entirely outside this code path.
     */
    private void cargoWarehouse(World w, OceanProvider o, int s0, int s1, int t0, int t1,
                                int ground, int height, boolean heavy, boolean opensRight) {
        // Continuous stone floor / actual masonry footings under every occupied column.
        for (int s = s0; s <= s1; s++) for (int t = t0; t <= t1; t++) {
            settle(w, o, s, t, ground, Material.STONE);
            for (int h = 1; h <= height + 3; h++) block(w, s, ground + h, t, Material.AIR);
        }
        // Four thick corner piers, intermediate structural buttresses, brick infill,
        // horizontal stone bands and occasional high industrial glazing.
        for (int s = s0; s <= s1; s++) for (int t = t0; t <= t1; t++) {
            boolean edge = s == s0 || s == s1 || t == t0 || t == t1;
            if (!edge) continue;
            boolean pier = ((s == s0 || s == s1) && (t == t0 || t == t1))
                    || ((t == t0 || t == t1) && (s - s0) % 6 == 0)
                    || ((s == s0 || s == s1) && (t - t0) % 6 == 0);
            for (int h = 1; h <= height; h++) {
                // Consistent brick all the way to the roof; stone piers and
                // structural bands are intentional, not a dark concrete attic.
                Material mat = pier || h == 1 || h == height || h == 6
                        ? Material.STONE_BRICKS : Material.BRICKS;
                block(w, s, ground + h, t, mat);
            }
            // Real daylight into the upper work floors, not just blind brick.
            // Keep the masonry end piers, loading-bay frontage, and floor
            // lines untouched. Each opening is entirely within the wall.
            if (!pier && (t == t0 || t == t1)
                    && s >= s0 + 2 && s <= s1 - 2 && (s - s0) % 4 == 2) {
                for (int h : new int[]{heavy ? 10 : 8, heavy ? 15 : 13}) {
                    if (h < height - 1) block(w, s, ground + h, t, Material.GLASS);
                }
            }
            // Additional glazing on non-loading side wall bays.
            if (!pier && (s == s0 || s == s1) && (t - t0) % 4 == 2) {
                int h = heavy ? 15 : 13;
                if (h < height - 1) block(w, s, ground + h, t, Material.GLASS);
            }
        }
        // Loading entrances face the central goods lane. Heavy warehouses get
        // one substantial SEVEN-WIDE / SEVEN-HIGH roll-up shutter; merchant
        // buildings retain their existing goods loading bays.
        // Corner piers are NEVER cut away by either design.
        int doorS = opensRight ? s1 : s0;
        if (heavy) {
            int center = (t0 + t1) / 2;
            // Reinforced independent jambs and a full-width stone lintel.
            // Seven-block shutter is inside these jambs, not an empty cavity.
            for (int t = center - 4; t <= center + 4; t++) {
                block(w, doorS, ground + 8, t, Material.STONE_BRICKS);
            }
            for (int h = 1; h <= 7; h++) {
                block(w, doorS, ground + h, center - 4, Material.STONE_BRICKS);
                block(w, doorS, ground + h, center + 4, Material.STONE_BRICKS);
                for (int t = center - 3; t <= center + 3; t++) {
                    if (Math.abs(t - center) == 3) {
                        // Two full-height metal guide tracks in the original
                        // solid masonry facade, on either side of the bay.
                        grayConcrete(w, doorS, ground + h, t);
                    } else {
                        // True inset roller shutter: only the bay aperture
                        // is cut; shutter leaf goes one block BEHIND the wall.
                        // Corners, shoulders and the lintel remain intact.
                        block(w, doorS, ground + h, t, Material.AIR);
                        int inset = opensRight ? -1 : 1;
                        if (h == 6 && Math.abs(t - center) <= 1)
                            block(w, doorS + inset, ground + h, t, Material.GLASS);
                        else if (h == 3 || h == 5)
                            grayConcrete(w, doorS + inset, ground + h, t);
                        else
                            block(w, doorS + inset, ground + h, t,
                                    (h % 2 == 0) ? Material.LIGHT_GRAY_CONCRETE : Material.GRAY_CONCRETE);
                    }
                }
            }
            // Worker entrance next to the shutter. The closed garage front is
            // visual steel construction in legacy Minecraft; this separate
            // hinged door keeps the building and its ChestLootz chests usable.
            // In the same local facade, so it rotates with all four ports.
            int staffT = center - 5;
            if (staffT > t0 && staffT < center - 4) {
                int facing = stairFacing(-dx, -dz); // 1.12 bottom-half facing bits
                legacyBlock(w, doorS, ground + 1, staffT, Material.OAK_DOOR, facing);
                legacyBlock(w, doorS, ground + 2, staffT, Material.OAK_DOOR, 8);
            }
        } else {
            // Merchant warehouses retain their existing, unobstructed loading
            // bays, so cargo and ChestLootz storage remain accessible.
            for (int center : new int[]{t0 + 4, t1 - 4}) {
                if (center - 1 <= t0 || center + 1 >= t1) continue;
                for (int t = center - 1; t <= center + 1; t++)
                    for (int h = 1; h <= 5; h++) block(w, doorS, ground + h, t, Material.AIR);
                for (int t = center - 2; t <= center + 2; t++)
                    block(w, doorS, ground + 6, t, Material.STONE_BRICKS);
            }
        }
        // Stepped shallow industrial roof with actual closed gables and repeated ribs.
        int mid = (s0 + s1) / 2;
        // Both long eaves now project two blocks; both end eaves one block.
        // The same four-sided overhang is used in all dock orientations.
        for (int s = s0 - 2; s <= s1 + 2; s++) {
            int pitch = Math.max(0, 3 - Math.abs(s - mid) / 3);
            int roof = ground + height + 1 + pitch;
            for (int t = Math.max(-7, t0 - 1); t <= t1 + 1; t++) {
                if ((t - t0) % 5 == 0) lightGrayConcrete(w, s, roof, t);
                else block(w, s, roof, t, Material.STONE);
            }
            if (s >= s0 && s <= s1)
                for (int t : new int[]{t0,t1})
                    for (int y = ground + height + 1; y < roof; y++)
                        block(w, s, y, t, Material.BRICKS);
        }
        // Close the LONG eave-side seams too. Previously only t0/t1 end
        // gables were infilled, leaving air between the top of the side walls
        // and the pitched roof for all interior t positions.
        for (int s : new int[]{s0, s1}) {
            int pitch = Math.max(0, 3 - Math.abs(s - mid) / 3);
            int underside = ground + height + 1 + pitch;
            for (int t = t0; t <= t1; t++)
                for (int fillY = ground + height + 1; fillY < underside; fillY++)
                    block(w, s, fillY, t, Material.BRICKS);
        }
        // Structural roof-ridge strip and drainage detail.
        for (int t = t0; t <= t1; t++) lightGrayConcrete(w, mid, ground + height + 4, t);
        if (heavy) industrialUpperFloors(w, s0, s1, t0, t1, ground);
        else merchantUpperFloors(w, s0, s1, t0, t1, ground);
    }

    /** Three usable levels for each merchant building: ground goods storage,
     * middle bookkeeping loft, and upper merchant office. Both stair openings
     * are kept clear rather than buried under a floor slab.
     */
    private void merchantUpperFloors(World w, int s0, int s1, int t0, int t1, int ground) {
        final int loftY = ground + 6;
        final int officeY = ground + 11;
        final int stairS = s0 + 2;
        final int stairsBegin = t0 + 2;
        final int upperStairS = s1 - 3;
        final int upperStairT = t1 - 6;
        for (int s = s0 + 1; s < s1; s++) for (int t = t0 + 1; t < t1; t++) {
            boolean lowerShaft = (s == stairS || s == stairS + 1)
                    && t >= stairsBegin && t < stairsBegin + 5;
            boolean upperShaft = (s == upperStairS || s == upperStairS + 1)
                    && t >= upperStairT && t < upperStairT + 4;
            if (!lowerShaft) block(w, s, loftY, t, Material.OAK_PLANKS);
            if (!upperShaft) block(w, s, officeY, t, Material.OAK_PLANKS);
        }
        for (int i = 0; i < 5; i++)
            for (int s = stairS; s <= stairS + 1; s++)
                legacyBlock(w, s, ground + 1 + i, stairsBegin + i,
                        Material.OAK_STAIRS, stairFacing(dx, dz));
        for (int i = 0; i < 4; i++)
            for (int s = upperStairS; s <= upperStairS + 1; s++)
                legacyBlock(w, s, loftY + 1 + i, upperStairT + i,
                        Material.OAK_STAIRS, stairFacing(dx, dz));
        // Long merchant desk and two chairs with wall-sign arms, rather
        // than a bare shell that looks unused when entered.
        int deskS = s1 - 4;
        int deskT = t0 + 4;
        for (int t = deskT; t <= deskT + 2; t++) {
            block(w, deskS, loftY + 1, t, Material.OAK_FENCE);
            block(w, deskS, loftY + 2, t, Material.OAK_PRESSURE_PLATE);
        }
        chair(w, deskS + 2, loftY + 1, deskT, -1, 0);
        chair(w, deskS + 2, loftY + 1, deskT + 2, -1, 0);
        // Shelves, ledger counter, and compact work/storage area on the
        // opposite wall; these sit above the cargo handling zone.
        for (int t = t1 - 3; t <= t1 - 1; t++) {
            block(w, s0 + 5, loftY + 1, t, Material.BOOKSHELF);
            block(w, s0 + 5, loftY + 2, t, Material.BOOKSHELF);
        }
        block(w, s1 - 3, loftY + 1, t1 - 2, Material.CRAFTING_TABLE);
        block(w, s1 - 4, loftY + 1, t1 - 2, Material.OAK_PLANKS);
        // No torches or artificial light sources in the port interiors.
        // Merchant's top-floor office: paperwork, packing orders and seats.
        for (int t = t0 + 2; t <= t0 + 4; t++) {
            block(w, s0 + 5, officeY + 1, t, Material.OAK_FENCE);
            block(w, s0 + 5, officeY + 2, t, Material.OAK_PRESSURE_PLATE);
        }
        chair(w, s0 + 7, officeY + 1, t0 + 2, -1, 0);
        chair(w, s0 + 7, officeY + 1, t0 + 4, -1, 0);
        block(w, s1 - 3, officeY + 1, t0 + 2, Material.BOOKSHELF);
        block(w, s1 - 3, officeY + 2, t0 + 2, Material.BOOKSHELF);
        block(w, s1 - 4, officeY + 1, t0 + 3, Material.CRAFTING_TABLE);
        // Two isolated, accessible EMPTY ChestLootz chests on EACH upper
        // level, matching the two ground-floor chests per warehouse.
        chest(w, s0 + 5, loftY + 1, t0 + 2, "industrial", 2);
        chest(w, s1 - 3, loftY + 1, t1 - 2, "office", 3);
        chest(w, s0 + 4, officeY + 1, t1 - 2, "industrial", 3);
        chest(w, s1 - 3, officeY + 1, t1 - 2, "office", 3);
    }

    /** The industrial freight hall is NOT an empty volume: accessible office
     * mezzanine and upper stores, with separate stair shafts and furnished work
     * spaces above the unobstructed ground-floor cargo handling area.
     */
    private void industrialUpperFloors(World w, int s0, int s1, int t0, int t1, int ground) {
        final int mezzY = ground + 8;
        final int storesY = ground + 13;
        final int lowStairS = s0 + 2;
        final int lowStairT = t0 + 2;
        final int topStairS = s1 - 3;
        final int topStairT = t1 - 7;
        for (int s = s0 + 1; s < s1; s++) for (int t = t0 + 1; t < t1; t++) {
            boolean firstStairwell = s >= lowStairS && s <= lowStairS + 1
                    && t >= lowStairT && t < lowStairT + 7;
            boolean secondStairwell = s >= topStairS && s <= topStairS + 1
                    && t >= topStairT && t < topStairT + 4;
            if (!firstStairwell) block(w, s, mezzY, t, Material.OAK_PLANKS);
            if (!secondStairwell) block(w, s, storesY, t, Material.OAK_PLANKS);
        }
        // Both stair flights end flush at their destination walking level.
        for (int i = 0; i < 7; i++)
            for (int s = lowStairS; s <= lowStairS + 1; s++)
                legacyBlock(w, s, ground + 1 + i, lowStairT + i,
                        Material.OAK_STAIRS, stairFacing(dx, dz));
        for (int i = 0; i < 4; i++)
            for (int s = topStairS; s <= topStairS + 1; s++)
                legacyBlock(w, s, mezzY + 1 + i, topStairT + i,
                        Material.OAK_STAIRS, stairFacing(dx, dz));

        // Freight supervisor desk, sign-armed stair chairs, shelving and work lights.
        int deskS = s0 + 5, deskT = t1 - 4;
        for (int t = deskT; t <= deskT + 2; t++) {
            block(w, deskS, mezzY + 1, t, Material.OAK_FENCE);
            block(w, deskS, mezzY + 2, t, Material.OAK_PRESSURE_PLATE);
        }
        chair(w, deskS + 2, mezzY + 1, deskT, -1, 0);
        chair(w, deskS + 2, mezzY + 1, deskT + 2, -1, 0);
        block(w, s1 - 5, mezzY + 1, t0 + 3, Material.CRAFTING_TABLE);
        for (int t = t0 + 2; t <= t0 + 4; t++)
            block(w, s1 - 4, mezzY + 1, t, Material.BOOKSHELF);
        // Upper level is a restrained parts store and inspection room.
        for (int t = t1 - 4; t <= t1 - 2; t++) {
            block(w, s0 + 3, storesY + 1, t, Material.OAK_PLANKS);
            block(w, s0 + 4, storesY + 1, t, Material.CRAFTING_TABLE);
        }
        for (int t = t0 + 2; t <= t0 + 4; t++)
            block(w, s1 - 4, storesY + 1, t, Material.BOOKSHELF);
        chair(w, s0 + 6, storesY + 1, t1 - 3, 0, -1);
        // Office and parts storage on EACH floor, not a cluster of loot
        // only on the ground. Every container stays empty and named.
        for (int t : new int[]{t0 + 2, t0 + 4, t1 - 2})
            chest(w, s0 + 5, mezzY + 1, t, "industrial", 3);
        for (int t : new int[]{t0 + 2, t0 + 4, t1 - 2})
            chest(w, s1 - 5, storesY + 1, t, "industrial", 4);
    }

    /** A SOLID reclaimed masonry quay, not a slab propped on occasional columns.
     *  Every covered block column is filled down to its own sampled seabed;
     *  perimeter columns are faced in stone brick, interior columns use stone.
     *  The fill is limited to this quay's reserved footprint: open berths and
     *  the wilderness coastline outside it are never changed.
     */
    private void quayApron(World w, OceanProvider o, int left, int right, int t0, int t1,
                           int waterY, boolean heavy) {
        for (int t = t0; t <= t1; t++) {
            int y = grade(t, waterY);
            for (int s = left; s <= right; s++) {
                OceanProvider.Sample sample = o.sample(bx(s, t), bz(s, t));
                // sample.columnDepth is the original ocean/river bed depth at this
                // column. On dry shore, support the masonry down to the old
                // waterline; do not alter arbitrary blocks below the foundation.
                int bed = sample.surfaceY - Math.max(1, sample.columnDepth);
                int bottom = Math.max(1, Math.min(y, bed));
                boolean retainingFace = s == left || s == right || t == t1;
                for (int h = bottom; h < y; h++) {
                    block(w, s, h, t,
                            retainingFace ? Material.STONE_BRICKS : Material.STONE);
                }
                block(w, s, y, t, (s % 7 == 0 || t % 8 == 0)
                        ? Material.STONE_BRICKS : (heavy ? Material.STONE : Material.COBBLESTONE));
                // The original shoreline may be taller than the old +3
                // clearance. Clear the FULL column above this reserved quay
                // surface, but only inside the reserved apron. Otherwise an
                // intact natural slope can poke through the paved quay.
                int topNatural = Math.min(w.getMaxHeight() - 1,
                        Math.max(y + 3, w.getHighestBlockYAt(bx(s, t), bz(s, t)) + 1));
                for (int h = y + 1; h <= topNatural; h++) block(w, s, h, t, Material.AIR);
            }
        }
    }

    /** Two narrow, dry-land graded shoulders INSIDE the reserved 3-chunk
     * port footprint. This avoids cutting surrounding wilderness or changing
     * the planned road/parcel grid. Never fills water or touches quay masonry.
     * Large cliffs outside the reservation are intentionally not terraformed.
     */
    private void gradeDryQuayShoulders(World w, OceanProvider ocean,
                                      int left, int right, int t0, int t1, int waterY) {
        for (int t = t0; t <= t1; t++) {
            int quayY = grade(t, waterY);
            for (int s : new int[]{left - 2, left - 1, right + 1}) {
                // The three reserved shore columns cover -24..23. These are
                // the only shoulders allowed; never edit outside their chunks.
                if (s < -24 || s > 23) continue;
                int x = bx(s,t), z=bz(s,t);
                if (ocean.sample(x,z).distanceInside > 0) continue;
                int high = Math.min(w.getMaxHeight() - 1,w.getHighestBlockYAt(x,z));
                // Only soften shallow steps (at most two blocks). Do not
                // excavate a giant new cliff or clear an entire wooded bank.
                if (high > quayY + 3 || high < quayY - 2) continue;
                int target = quayY + ((s == left - 2) ? 2 : 1);
                if (high > target) {
                    for (int h=target+1;h<=high;h++) put(w,x,h,z,Material.AIR);
                } else if (high < target) {
                    for (int h=high+1;h<target;h++) put(w,x,h,z,Material.DIRT);
                }
                put(w,x,target,z,Material.GRASS_BLOCK);
            }
        }
    }

    /** Additional stone-brick vertical edge on the projecting freight berth. */
    private void stonePile(World w, OceanProvider o, int s, int t, int y) {
        OceanProvider.Sample q = o.sample(bx(s, t), bz(s, t));
        int bottom = Math.max(1, Math.min(y, q.surfaceY - Math.max(1, q.columnDepth)));
        for (int h = bottom; h <= y; h++) block(w, s, h, t, Material.STONE_BRICKS);
    }

    /** Remove natural shore intrusion from the timber berth's OWN reserved
     * columns before placing decking. Shore trees and terrain elsewhere are
     * left alone. The landward end stays grounded on compacted beach; seaward
     * sections retain natural water and their piles to the seabed.
     */
    private void clearBerthDeckColumn(World w, OceanProvider ocean, int s, int t, int deckY) {
        int x = bx(s, t), z = bz(s, t);
        int high = Math.min(w.getMaxHeight() - 1,
                Math.max(deckY + 3, w.getHighestBlockYAt(x, z) + 1));
        for (int h = deckY + 1; h <= high; h++)
            put(w, x, h, z, Material.AIR);
        OceanProvider.Sample shore = ocean.sample(x, z);
        // A shore bank may rise THROUGH the floating slab course even after
        // the obvious vegetation is removed. Reduce only this particular
        // timber column to below the deck; never excavate outside the berth.
        if (shore.distanceInside <= 0) {
            for (int h = deckY; h >= deckY - 1 && h > 1; h--) {
                Material existing = w.getBlockAt(x, h, z).getType();
                if (existing != Material.AIR && existing != Material.WATER
                        && existing != Material.WATER)
                    put(w, x, h, z, h == deckY - 1 ? Material.GRAVEL : Material.AIR);
            }
        }
    }

    /** Independent timber fingers: upper spruce slabs across the deck and its
     * edges, flush crossbeams only where full-length piles actually connect.
     * No log perimeter, hanging lower stringers or fence crossbracing.
     */
    private void timberBerth(World w, OceanProvider o, int s0, int s1, int t0, int t1, int waterY) {
        for (int t = t0; t <= t1; t++) {
            int y = grade(t, waterY);
            for (int s = s0; s <= s1; s++) {
                clearBerthDeckColumn(w, o, s, t, y);
                timberDeckSurface(w, s, t, waterY);
            }
            // Slabs own the longitudinal walking edges between pile bents.
            if (Math.floorMod(t - t0, 5) == 0 || t == t1) {
                for (int s = s0; s <= s1; s++) timberBeam(w, s, y, t, false);
                piles(w, o, s0, t, y); piles(w, o, s1, t, y);
            }
        }
        for (int t : new int[]{t0 + 5, t1 - 2}) {
            block(w, s0, grade(t, waterY) + 1, t, Material.OAK_FENCE);
            block(w, s1, grade(t, waterY) + 1, t, Material.OAK_FENCE);
        }
    }

    /** No cargo may bridge a grade change or expose unsupported undersides. */
    private boolean cargoFootprintFlat(World w, int s0, int t0, int y, int width, int depth) {
        for (int ds = 0; ds < width; ds++) for (int dt = 0; dt < depth; dt++) {
            int x = bx(s0 + ds, t0 + dt), z = bz(s0 + ds, t0 + dt);
            if (!w.getBlockAt(x, y, z).getType().isSolid()) return false;
            for (int h = 1; h <= 3; h++) {
                Material m = w.getBlockAt(x, y + h, z).getType();
                if (m != Material.AIR) return false;
            }
        }
        return true;
    }

    /** Full wooden crate bodies sit directly on a continuously supported floor.
     * No lower half-slab pedestal and no unsupported shipping containers. */
    private void shippingCrate(World w, int s0, int t0, int floor,
                               int width, int depth, boolean tall) {
        if (!cargoFootprintFlat(w, s0, t0, floor, width, depth)) return;
        for (int ds = 0; ds < width; ds++) for (int dt = 0; dt < depth; dt++) {
            legacyBlock(w, s0 + ds, floor + 1, t0 + dt,
                    Material.OAK_PLANKS, ((ds + dt) & 1) == 0 ? 1 : 0);
            if (tall) legacyBlock(w, s0 + ds, floor + 2, t0 + dt,
                    Material.OAK_PLANKS, ((ds + dt) & 1) == 0 ? 1 : 0);
        }
        // A flush top lid is decorative, never an unsupported lower support.
        int lidY = floor + (tall ? 3 : 2);
        for (int ds = 0; ds < width; ds++) for (int dt = 0; dt < depth; dt++)
            legacyBlock(w, s0 + ds, lidY, t0 + dt, Material.SPRUCE_SLAB, 1);
    }

    private void crateGroup(World w, int s0, int t0, int y, int rows) {
        for (int i = 0; i < rows; i++) {
            int t = t0 + i * 2;
            if (!cargoFootprintFlat(w, s0, t, y, 3, 1)) continue;
            for (int ds = 0; ds < 3; ds++) {
                block(w, s0 + ds, y + 1, t, Material.OAK_PLANKS);
                if ((i + ds) % 3 == 0) block(w, s0 + ds, y + 2, t, Material.OAK_PLANKS);
            }
        }
    }

    /** Trade quay: two masonry merchant warehouses, street-connected covered
     * goods passage, crane and two side berths. NO ferry/waiting/ticket building.
     */
    private void commercialTradingQuay(World w, OceanProvider o, int floor, int waterY) {
        // Grade the lane FIRST. A later settle() here used to clear the
        // inward-projecting warehouse roof eaves one/two columns at a time.
        for (int t=-7;t<=7;t++) for (int s=-8;s<=8;s++)
            settle(w,o,s,t,floor, (Math.floorMod(s,7)==0)? Material.STONE_BRICKS : Material.STONE);
        // Establish the retained apron BEFORE the adjacent buildings.
        // Its above-grade shoreline clearing must never cut the seaward
        // roof eaves after the warehouse is complete.
        quayApron(w,o,-22,22,8,29,waterY,false);
        gradeDryQuayShoulders(w,o,-22,22,8,29,waterY);
        cargoWarehouse(w,o,-21,-9,-6,6,floor,15,false,true);
        cargoWarehouse(w,o,  9,21,-6,6,floor,15,false,false);
        // Deep berth separation is left OPEN WATER; no block-wide wooden T-head.
        timberBerth(w,o,-20,-13,30,55,waterY);
        timberBerth(w,o, 13, 20,30,55,waterY);
        // Freight stacks stay to the side of central transfer path.
        crateGroup(w,-18,12,grade(12,waterY),3);
        crateGroup(w, 15,12,grade(12,waterY),3);
        // Real shore cargo davit: rigid upright, boom projecting OVER BERTH
        // WATER (not onto its own paving) and a suspended working hook.
        int y=grade(28,waterY);
        for (int h=1;h<=9;h++) grayConcrete(w,-9,y+h,28);
        for (int t=28;t<=34;t++) grayConcrete(w,-9,y+9+(t-28)/4,t);
        for (int h=4;h<=10;h++) block(w,-9,y+h,34,Material.IRON_BARS);
        chest(w,-18,floor+1,1,"industrial",2);
        chest(w,-15,floor+1,-2,"industrial",3);
        chest(w, 16,floor+1,1,"industrial",2);
        chest(w, 14,floor+1,-2,"office",3);
    }

    /** Heavy working harbor: masonry freight halls, reinforced quay apron,
     * container stacks, two working berths and a STRADDLE GANTRY crane.
     * Buildings are industrial scale, not residential sheds with decoration.
     */
    private void industrialFreightPort(World w, OceanProvider o, int floor, int waterY) {
        // First reclaim and clear the full quay footprint; otherwise the
        // natural bank or a late quay pass will intersect finished eaves.
        quayApron(w,o,-22,22,8,36,waterY,true);
        gradeDryQuayShoulders(w,o,-22,22,8,36,waterY);
        quayApron(w,o,2,22,37,55,waterY,true);
        // Prepare freight traffic paving before constructing any warehouse
        // roof. The former late settle() erased the loading facade's eaves.
        for (int t=-7;t<=7;t++) for (int s=-6;s<=7;s++)
            settle(w,o,s,t,floor, (s==-6 || s==7) ? Material.STONE_BRICKS:Material.STONE);
        cargoWarehouse(w,o,-22,-7,-7,7,floor,17,true,true);
        // The facing side is an OPEN STEEL-SIDED loading shed, not a cloned
        // warehouse. Truck traffic can pull under it from the shared goods lane.
        for (int t=-7;t<=7;t++) for (int s=8;s<=22;s++)
            settle(w,o,s,t,floor,Material.STONE);
        for (int t : new int[]{-7,0,7}) for (int s : new int[]{8,22})
            for (int h=1;h<=9;h++) grayConcrete(w,s,floor+h,t);
        for (int t=-7;t<=8;t++) for (int s=8;s<=23;s++) {
            // Ribbed gray-concrete shed roof carried by the six actual columns.
            int roof=floor+10+(s>=12 && s<=19 ? 1 : 0);
            if (Math.floorMod(t,3)==0) lightGrayConcrete(w,s,roof,t);
            else block(w,s,roof,t,Material.STONE);
        }
        // Stacked supplies beneath the loading shed, with truck aisle open.
        // These are shipping crates on pallet/slab bases, never fake metal lumps.
        for (int t : new int[]{-5,2}) for (int s : new int[]{13,18})
            shippingCrate(w, s, t, floor, 2, 2, ((s + t) & 1) == 0);
        // Freight driveway, retained quay and masonry pier were graded
        // before the warehouse and shed; never run destructive clears later.
        for (int t=37;t<=55;t++) stonePile(w,o,2,t,grade(t,waterY));
        int y=grade(22,waterY);
        // Cargo stacks lie parallel to the shore and DON'T block the transfer aisle.
        for (int s0 : new int[]{-20,14}) for (int t0 : new int[]{11,19}) {
            shippingCrate(w, s0,   t0,   grade(t0, waterY), 3, 3, true);
            shippingCrate(w, s0+3, t0+1, grade(t0+1, waterY), 2, 2, false);
        }
        // Industrial gantry spans the truck channel. Boxy structural steel,
        // visible end frames and trolley; no fence used as faux crossbracing.
        for (int t : new int[]{24,28}) for (int s : new int[]{-16,16}) {
            int base=grade(t,waterY);
            for (int h=1;h<=13;h++) grayConcrete(w,s,base+h,t);
        }
        for (int t : new int[]{24,28}) for (int s=-16;s<=16;s++)
            grayConcrete(w,s,grade(t,waterY)+13,t);
        for (int t=24;t<=28;t++) {
            grayConcrete(w,0,grade(t,waterY)+14,t);
            grayConcrete(w,1,grade(t,waterY)+14,t);
        }
        for (int h=5;h<=13;h++) block(w,0,grade(26,waterY)+h,26,Material.IRON_BARS);
        grayConcrete(w,0,grade(26,waterY)+4,26);
        // Cross-yard crane cab and forklift silhouettes along the pile-side,
        // cargo lanes themselves stay clear for traversability.
        shippingCrate(w,-15,25,grade(25,waterY), 2, 2, false);
        chest(w,-18,floor+1,1,"industrial",3);
        chest(w,-16,floor+1,-3,"industrial",4);
        chest(w, 16,floor+1,-3,"industrial",4);
        chest(w, 18,floor+1,1,"office",3);
        chest(w,-14,floor+1,5,"industrial",3);
    }

    /** Compact, human-accessible brick-and-timber storehouse, doors and cargo directly facing the dock lane. */
    private void warehouse(World w, OceanProvider o, int y) {
        // Own foundations follow the beach beneath them, without flattening adjacent natural shore.
        for (int t = -5; t <= 5; t++) {
            for (int s = -7; s <= -1; s++) {
                settle(w, o, s, t, y, Material.OAK_PLANKS);
                for (int h = y + 1; h <= y + 5; h++) block(w, s, h, t, Material.AIR);
            }
        }
        for (int t = -5; t <= 5; t++) {
            for (int s = -7; s <= -1; s++) {
                if (s != -7 && s != -1 && t != -5 && t != 5) continue;
                boolean corner = (s == -7 || s == -1) && (t == -5 || t == 5);
                for (int h = 1; h <= 5; h++) {
                    Material wall = corner ? Material.SPRUCE_LOG : h <= 2 ? Material.BRICKS : Material.GRAY_CONCRETE;
                    if (wall == Material.GRAY_CONCRETE) whiteConcrete(w, s, y + h, t);
                    else block(w, s, y + h, t, wall);
                }
            }
        }
        // Side-facing double-height loading door, no step through a solid wall.
        for (int t = -1; t <= 1; t++) {
            block(w, -1, y + 1, t, Material.AIR);
            block(w, -1, y + 2, t, Material.AIR);
            block(w, -1, y + 3, t, Material.AIR);
        }
        for (int t : new int[]{-3, 3}) {
            block(w, -1, y + 3, t, Material.GLASS);
            block(w, -7, y + 3, t, Material.IRON_BARS);
        }
        // Weathered, low-pitched industrial roof, with *closed* masonry end gables.
        // The source warehouse schematics use abundant brick/stone/clay and metalwork;
        // use that vocabulary here rather than an unrelated all-plank hut.
        for (int t = -6; t <= 6; t++) {
            for (int s = -7; s <= 0; s++) {
                int pitch = 4 - Math.abs(s + 4); // one-block rise at the eaves, 4 at ridge
                int roofY = y + 6 + pitch / 2;
                block(w, s, roofY, t, ((t + 6) % 4 == 0) ? Material.STONE_BRICKS : Material.STONE);
                if ((t == -5 || t == 5) && s >= -7 && s <= -1) {
                    for (int h = 6; h < roofY - y; h++) {
                        block(w, s, y + h, t, Material.BRICKS);
                    }
                }
            }
        }
        // Load-bearing eaves and short corbels, not unsupported roof edges.
        for (int t = -5; t <= 5; t += 5) {
            block(w, -7, y + 5, t, Material.STONE_BRICKS);
            block(w, -1, y + 5, t, Material.STONE_BRICKS);
        }
        for (int t : new int[]{-4, 4}) {
            block(w, -7, y + 4, t, Material.SPRUCE_LOG);
            block(w, -1, y + 4, t, Material.SPRUCE_LOG);
        }
        // Workbenches, a small office area and restrained cargo stacks.
        block(w, -5, y + 1, -3, Material.CRAFTING_TABLE);
        block(w, -5, y + 1, 3, Material.OAK_PLANKS);
        block(w, -4, y + 1, 3, Material.OAK_PLANKS);
        // A small desk chair in this neighbourhood landing. No fence post
        // pretending to be furniture: wooden stair with proper sign arms.
        chair(w, -5, y + 1, 0, 0, 1);
        // Chests start EMPTY and retain ChestLootz's exact legacy name protocol.
        chest(w, -3, y + 1, -3, "industrial", 1 + (int) (variation & 1));
        chest(w, -3, y + 1, 3, "industrial", 2 + (int) ((variation >>> 3) & 1));
        chest(w, -5, y + 1, 1, "office", 2);
    }

    private void chest(World w, int s, int y, int t, String collection, int level) {
        Block b = w.getBlockAt(bx(s, t), y, bz(s, t));
        b.setType(Material.CHEST, false);
        // Place isolated single chests, leaving head clearance and walkable aisle.
        BlockState state = b.getState();
        if (!(state instanceof Chest)) return;
        Chest chest = (Chest) state;
        chest.getBlockInventory().clear();
        DirtyHacks.setChestName(chest, "\u00a7" + ChatColor.GREEN.getChar() + collection + "_" + Math.max(1, Math.min(5, level)));
        chest.update(true, false);
    }
}
