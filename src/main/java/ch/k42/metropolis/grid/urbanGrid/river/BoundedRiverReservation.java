package ch.k42.metropolis.grid.urbanGrid.river;

import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;

/**
 * Atomic acceptance gate for a bounded creek and its origin park.
 * Restored from R30ea validation bytecode; this is baseline behavior, not a proposed redesign.
 */
public final class BoundedRiverReservation {
    private final BoundedRiverSegment segment;
    private final int parkX0, parkZ0, parkX1, parkZ1;
    private final Exit exit;
    private final RiverProvider receivingRiver;
    private final OceanProvider receivingOcean;
    private final ChunkOwnership ownership;
    private final Reject status;

    public enum Exit { NORTH, SOUTH, EAST, WEST }
    public enum Reject {
        NONE, ENDPOINT_NOT_IN_RIVER, INVALID_ORIGIN_EDGE, PARK_INTERIOR_WATER,
        WRONG_PARK_RING_EDGE, PARALLEL_EXISTING_ROAD, UNBRIDGEABLE_HIGHWAY_CROSSING,
        EXISTING_WATER_COLLISION
    }
    public interface ChunkOwnership {
        boolean northSouthRoadAt(int x, int z);
        boolean eastWestRoadAt(int x, int z);
        boolean protectedWaterAt(int x, int z);
        default boolean perpendicularBridgeableAt(int x, int z, boolean zBound) { return false; }
    }

    public BoundedRiverReservation(BoundedRiverSegment segment,
                                   int parkX0, int parkZ0, int parkX1, int parkZ1,
                                   Exit exit, RiverProvider receivingRiver,
                                   OceanProvider receivingOcean, ChunkOwnership ownership) {
        if (segment == null || exit == null || parkX0 > parkX1 || parkZ0 > parkZ1
                || (receivingRiver == null && receivingOcean == null))
            throw new IllegalArgumentException("A bounded river needs a park, exit and receiving water");
        this.segment = segment;
        this.parkX0 = parkX0; this.parkZ0 = parkZ0;
        this.parkX1 = parkX1; this.parkZ1 = parkZ1;
        this.exit = exit;
        this.receivingRiver = receivingRiver;
        this.receivingOcean = receivingOcean;
        this.ownership = ownership;
        this.status = validate();
    }

    public Reject check() { return status; }
    public BoundedRiverSegment acceptedSegment() { return check() == Reject.NONE ? segment : null; }
    public BoundedRiverOwnershipPlan ownershipProposal(BoundedRiverOwnershipPlan.BaseWater baseWater) {
        return check() == Reject.NONE ? BoundedRiverOwnershipPlan.propose(
                segment, parkX0, parkZ0, parkX1, parkZ1, exit, baseWater) : null;
    }

    private Reject validate() {
        boolean zBound = segment.isZBound();
        if (zBound != (exit == Exit.NORTH || exit == Exit.SOUTH)) return Reject.INVALID_ORIGIN_EDGE;
        double ox = zBound ? segment.originAcrossBlock() : segment.originAlongBlock();
        double oz = zBound ? segment.originAlongBlock() : segment.originAcrossBlock();
        double ex = zBound ? segment.endpointAcrossBlock() : segment.endpointAlongBlock();
        double ez = zBound ? segment.endpointAlongBlock() : segment.endpointAcrossBlock();
        double x0 = parkX0 * 16.0, x1 = (parkX1 + 1) * 16.0;
        double z0 = parkZ0 * 16.0, z1 = (parkZ1 + 1) * 16.0;
        final double epsilon = 0.00001;
        boolean goodOrigin = switch (exit) {
            case NORTH -> Math.abs(oz - z0) < epsilon && ox >= x0 && ox <= x1;
            case SOUTH -> Math.abs(oz - z1) < epsilon && ox >= x0 && ox <= x1;
            case WEST -> Math.abs(ox - x0) < epsilon && oz >= z0 && oz <= z1;
            case EAST -> Math.abs(ox - x1) < epsilon && oz >= z0 && oz <= z1;
        };
        if (!goodOrigin) return Reject.INVALID_ORIGIN_EDGE;
        if (exit == Exit.NORTH && ez >= oz || exit == Exit.SOUTH && ez <= oz
                || exit == Exit.WEST && ex >= ox || exit == Exit.EAST && ex <= ox)
            return Reject.INVALID_ORIGIN_EDGE;
        if (!reachesReceivingWater(ex, ez)) return Reject.ENDPOINT_NOT_IN_RIVER;

        int alongLo = segment.getAlongLoChunk(), alongHi = segment.getAlongHiChunk();
        int envelope = (int) Math.ceil(segment.getEnvelopeHalfWidth()) + 1;
        for (int along = alongLo; along <= alongHi; along++) {
            double spine = segment.spineAcross(along);
            if (!Double.isFinite(spine)) continue;
            for (int across = (int) Math.floor(spine) - envelope;
                 across <= (int) Math.ceil(spine) + envelope; across++) {
                int cx = zBound ? across : along;
                int cz = zBound ? along : across;
                boolean river = segment.isRiver(cx, cz);
                boolean riverOrBank = river || segment.isRiverOrBank(cx, cz);
                if (!riverOrBank) continue;
                boolean parkInterior = cx >= parkX0 && cx <= parkX1
                        && cz >= parkZ0 && cz <= parkZ1;
                if (river && parkInterior && !outwardParkFace(cx, cz))
                    return Reject.PARK_INTERIOR_WATER;
                boolean parkRing = cx >= parkX0 - 1 && cx <= parkX1 + 1
                        && cz >= parkZ0 - 1 && cz <= parkZ1 + 1 && !parkInterior;
                if (river && parkRing && !outwardRingCell(cx, cz))
                    return Reject.WRONG_PARK_RING_EDGE;
                if (ownership != null && river && !parkRing && !parkInterior) {
                    boolean parallel = zBound
                            ? ownership.northSouthRoadAt(cx, cz)
                            : ownership.eastWestRoadAt(cx, cz);
                    if (parallel) return Reject.PARALLEL_EXISTING_ROAD;
                    boolean perpendicular = zBound
                            ? ownership.eastWestRoadAt(cx, cz)
                            : ownership.northSouthRoadAt(cx, cz);
                    if (perpendicular && !ownership.perpendicularBridgeableAt(cx, cz, zBound))
                        return Reject.UNBRIDGEABLE_HIGHWAY_CROSSING;
                }
                if (ownership != null && river && ownership.protectedWaterAt(cx, cz)
                        && !nearEndpoint(cx, cz))
                    return Reject.EXISTING_WATER_COLLISION;
            }
        }
        return Reject.NONE;
    }

    private boolean outwardParkFace(int x, int z) {
        return switch (exit) {
            case NORTH -> z == parkZ0 && x >= parkX0 && x <= parkX1;
            case SOUTH -> z == parkZ1 && x >= parkX0 && x <= parkX1;
            case EAST -> x == parkX1 && z >= parkZ0 && z <= parkZ1;
            case WEST -> x == parkX0 && z >= parkZ0 && z <= parkZ1;
        };
    }
    private boolean outwardRingCell(int x, int z) {
        return switch (exit) {
            case NORTH -> z == parkZ0 - 1 && x >= parkX0 && x <= parkX1;
            case SOUTH -> z == parkZ1 + 1 && x >= parkX0 && x <= parkX1;
            case EAST -> x == parkX1 + 1 && z >= parkZ0 && z <= parkZ1;
            case WEST -> x == parkX0 - 1 && z >= parkZ0 && z <= parkZ1;
        };
    }
    private boolean nearEndpoint(int x, int z) {
        double bx = x * 16.0 + 8.0, bz = z * 16.0 + 8.0;
        double ex = segment.isZBound() ? segment.endpointAcrossBlock() : segment.endpointAlongBlock();
        double ez = segment.isZBound() ? segment.endpointAlongBlock() : segment.endpointAcrossBlock();
        return Math.hypot(bx - ex, bz - ez) <= 32.0;
    }
    private boolean reachesReceivingWater(double x, double z) {
        if (receivingRiver != null) {
            double distance = receivingRiver.distanceFromCenterBlock(x, z);
            double width = receivingRiver.widthAtBlock(receivingRiver.isZBound() ? z : x);
            if (Double.isFinite(distance) && Double.isFinite(width)
                    && Math.abs(distance) <= width * 0.5) return true;
        }
        if (receivingOcean != null && receivingOcean.isEnabled()) {
            double distance = receivingOcean.distanceInsideShoreBlock(x, z);
            return Double.isFinite(distance) && distance >= 0;
        }
        return false;
    }
}
