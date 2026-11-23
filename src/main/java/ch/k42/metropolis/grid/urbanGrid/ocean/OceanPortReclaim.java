package ch.k42.metropolis.grid.urbanGrid.ocean;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.District;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanPortInlandParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.StreetParcel;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;

/**
 * Port-adjacent district reclamation in the ACTIVE (world-layout) generator.
 *
 * This does not re-run the obsolete tile carve or replace the natural coast.
 * A site must first have passed the port planner's land/water/street tests.
 * Only dry OceanParcels to the side of that site's existing access corridor
 * may become industrial district blocks. The shore in every other location
 * is completely unchanged. All configuration dimensions are CHUNKS.
 *
 * A district fronts the port's access road; it never spans the road, its quay,
 * or any already planned street/building/river/highway. The districts are
 * committed only after the complete parcel footprint has been checked.
 */
public final class OceanPortReclaim {
    private OceanPortReclaim() { }

    public static final class Plan {
        public final int baseX, baseZ, width, depth, side;
        private final boolean north, south, east, west;

        private Plan(int baseX, int baseZ, int width, int depth, int side,
                     boolean north, boolean south, boolean east, boolean west) {
            this.baseX = baseX;
            this.baseZ = baseZ;
            this.width = width;
            this.depth = depth;
            this.side = side;
            this.north = north;
            this.south = south;
            this.east = east;
            this.west = west;
        }
    }

    /** Null means no SAFE industrial district here; short, direct ports may still be placed. */
    public static Plan find(UrbanGrid grid, OceanProvider ocean, int tileX, int tileZ,
                            int cx, int cz, int dx, int dz, int streetDistance) {
        // Port reclamation is controlled by ocean.ports.enabled and this
        // site's zoning/footprint checks. Reuse the river reclaim DIMENSIONS
        // (in chunks), not its on/off switch: disabling river reclamation
        // must not silently prevent otherwise eligible industrial ports.
        final int minWidth = Math.max(1, RiverConfig.getRiverReclaimMinWidth());
        final int maxWidth = Math.max(minWidth, RiverConfig.getRiverReclaimMaxWidth());
        final int minDepth = Math.max(1, RiverConfig.getRiverReclaimMinDepth());
        if (streetDistance <= minDepth) return null;

        // Keep the district directly behind the dock. A second district can be
        // designed later; never annex the full stretch of coast in one pass.
        int depth = Math.min(streetDistance - 1, Math.max(minDepth, 3));
        final int sideX = -dz, sideZ = dx;
        Plan best = null;
        for (int side : new int[]{1, -1}) {
            // side=1 starts just outside the port's corridor on the right;
            // side=-1 starts on its left. The parcel at s=0 stays reserved
            // for the actual city-to-quay road.
            int usableWidth = 0;
            for (int s = 1; s <= maxWidth; s++) {
                boolean ok = true;
                for (int t = streetDistance - 1; t >= streetDistance - depth; t--) {
                    int x = cx - t * dx + side * s * sideX;
                    int z = cz - t * dz + side * s * sideZ;
                    if (x < tileX + 1 || x > tileX + 62 || z < tileZ + 1 || z > tileZ + 62) {
                        ok = false;
                        break;
                    }
                    Parcel p = grid.getParcel(x, z);
                    if (p == null || p.getClass() != OceanParcel.class || !OceanReclaim.chunkUsable(ocean, x, z)) {
                        ok = false;
                        break;
                    }
                }
                if (!ok) break;
                usableWidth = s;
            }
            if (usableWidth < minWidth) continue;
            // A long continuous seawall of industrial buildings would be as
            // artificial as the old boardwalk. Respect maxWidth, but prefer a
            // modest 3..6 chunk block until each building is inspected in game.
            int width = Math.min(usableWidth, Math.max(minWidth, 6));
            int aX = cx - (streetDistance - 1) * dx + side * sideX;
            int aZ = cz - (streetDistance - 1) * dz + side * sideZ;
            int bX = cx - (streetDistance - depth) * dx + side * width * sideX;
            int bZ = cz - (streetDistance - depth) * dz + side * width * sideZ;
            int baseX = Math.min(aX, bX), baseZ = Math.min(aZ, bZ);
            int sizeX = Math.abs(bX - aX) + 1, sizeZ = Math.abs(bZ - aZ) + 1;
            // The physical side toward the port's access lane is the only
            // 'road side' of the annexed block. No imaginary perimeter roads.
            int inwardX = -side * sideX, inwardZ = -side * sideZ;
            Plan plan = new Plan(baseX, baseZ, sizeX, sizeZ, side,
                    inwardZ < 0, inwardZ > 0, inwardX > 0, inwardX < 0);
            if (best == null || sizeX * sizeZ > best.width * best.depth) best = plan;
        }
        return best;
    }

    /** Add an ordinary industrial district, using its existing schematic/filler/chest machinery. */
    public static void commit(UrbanGrid grid, Plan plan) {
        if (plan == null) return;
        // The city block cannot simply CLAIM that it fronts the approach.
        // Make sure every frontage chunk actually touches the built port
        // access lane or a planned ordinary street, and every reserved
        // district cell is still free. Otherwise do not annex or build.
        for (int x = plan.baseX; x < plan.baseX + plan.width; x++) {
            for (int z = plan.baseZ; z < plan.baseZ + plan.depth; z++) {
                if (grid.peekParcel(x, z) == null
                        || grid.peekParcel(x, z).getClass() != OceanParcel.class) {
                    Minions.w("[Ports] reclaimed district rejected: occupied chunk (%d,%d)", x, z);
                    return;
                }
            }
        }
        int length = (plan.north || plan.south) ? plan.width : plan.depth;
        for (int i = 0; i < length; i++) {
            int edgeX = plan.north || plan.south ? plan.baseX + i
                    : plan.west ? plan.baseX - 1 : plan.baseX + plan.width;
            int edgeZ = plan.west || plan.east ? plan.baseZ + i
                    : plan.north ? plan.baseZ - 1 : plan.baseZ + plan.depth;
            Parcel road = grid.peekParcel(edgeX, edgeZ);
            if (!(road instanceof StreetParcel) && !(road instanceof OceanPortInlandParcel)) {
                Minions.w("[Ports] reclaimed district rejected: missing real access lane at (%d,%d)", edgeX, edgeZ);
                return;
            }
        }
        District district = new District();
        district.initDistrict(new Cartesian2D(plan.baseX, plan.baseZ),
                new Cartesian2D(plan.width, plan.depth), grid);
        district.assignContext(ContextType.INDUSTRIAL);
        district.setRoadSides(plan.north, plan.south, plan.east, plan.west);
        // Annex may only use its own rectangle. Do not let extra-cut roads
        // or oversized rotated schematics claim neighbouring shore chunks.
        district.setStrictPortBoundary(true);
        // Select valid buildings FIRST. Mark only the actual claimed builds
        // as urban ground: a rejected lot must remain natural shoreline,
        // not an empty artificially graded rectangle.
        grid.registerPortReclaimedDistrict(district);
        district.fillDistrict();
        int builtChunks = 0;
        for (int x = plan.baseX; x < plan.baseX + plan.width; x++) {
            for (int z = plan.baseZ; z < plan.baseZ + plan.depth; z++) {
                if (grid.peekParcel(x, z) instanceof ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel) {
                    grid.markPortGradedCell(x, z);
                    builtChunks++;
                }
            }
        }
        Minions.i("[Ports] reclaimed INDUSTRIAL district at chunk (%d,%d) size=%dx%d CHUNKS, built=%d chunks (rest natural), side=%d",
                plan.baseX, plan.baseZ, plan.width, plan.depth, builtChunks, plan.side);
    }
}
