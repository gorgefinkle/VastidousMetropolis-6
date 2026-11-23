package ch.k42.metropolis.grid.urbanGrid.river;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable chunk-ownership proposal for a bounded tributary and its origin park.
 *
 * Recovered from the R30ea behavior reference.  This recovery reconstructs the
 * previously absent source; it is NOT the new persistent queue implementation.
 */
public final class BoundedRiverOwnershipPlan {
    public enum Owner {
        PARK, PARK_ROAD, PARK_BRIDGE, WATER, RECEIVING_WATER,
        VEGETATED_BANK, DRY_CANDIDATE, CITY, CITY_ROAD, OUTSIDE
    }

    public enum DryUse { CITY_BLOCK, PUBLIC_ROAD, NATURAL_BANK }

    @FunctionalInterface
    public interface DryLand { boolean isValidatedCityCell(int x, int z); }

    @FunctionalInterface
    public interface PlannedDryUse { DryUse useAt(int x, int z); }

    @FunctionalInterface
    public interface BaseWater {
        boolean openWater(int x, int z);
        default boolean intendedReceiverAt(int x, int z) { return false; }
    }

    private final BoundedRiverSegment segment;
    private final Map<Long, Owner> owner;
    private final int parkMinX;
    private final int parkMinZ;
    private final int parkMaxX;
    private final int parkMaxZ;
    private final BoundedRiverReservation.Exit exit;
    private final boolean resolved;

    private BoundedRiverOwnershipPlan(BoundedRiverSegment segment, Map<Long, Owner> owner,
            int minX, int minZ, int maxX, int maxZ,
            BoundedRiverReservation.Exit exit, boolean resolved) {
        this.segment = segment;
        this.owner = Collections.unmodifiableMap(new HashMap<>(owner));
        this.parkMinX = minX;
        this.parkMinZ = minZ;
        this.parkMaxX = maxX;
        this.parkMaxZ = maxZ;
        this.exit = exit;
        this.resolved = resolved;
    }

    private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    public static BoundedRiverOwnershipPlan propose(BoundedRiverSegment segment,
            int minX, int minZ, int maxX, int maxZ,
            BoundedRiverReservation.Exit exit, BaseWater water) {
        if (segment == null || exit == null || water == null || minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid bounded river ownership proposal");
        }
        Map<Long, Owner> proposed = new HashMap<>();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int z = minZ - 1; z <= maxZ + 1; z++) {
                proposed.put(key(x,z), parkCell(x,z,minX,minZ,maxX,maxZ,exit));
            }
        }
        int from = segment.getAlongLoChunk();
        int to = segment.getAlongHiChunk();
        int half = (int) Math.ceil(segment.getEnvelopeHalfWidth()) + 2;
        for (int along = from; along <= to; along++) {
            double across = segment.spineAcross(along);
            if (!Double.isFinite(across)) continue;
            for (int cross = (int)Math.floor(across)-half;
                    cross <= (int)Math.ceil(across)+half; cross++) {
                int x = segment.isZBound()?cross:along;
                int z = segment.isZBound()?along:cross;
                boolean channel = segment.isRiver(x,z);
                if (!channel && !segment.isRiverOrBank(x,z)) continue;
                long key = key(x,z);
                Owner existing = proposed.get(key);
                if (existing == Owner.PARK) {
                    if (channel && !outwardParkFace(x,z,minX,minZ,maxX,maxZ,exit)) {
                        throw new IllegalArgumentException("Bounded water would flood origin park at (" + x + "," + z + ")");
                    }
                    continue;
                }
                if (existing == Owner.PARK_ROAD) {
                    if (channel) {
                        if (!outwardFace(x,z,minX,minZ,maxX,maxZ,exit)) {
                            throw new IllegalArgumentException("Bounded water would remove mandatory origin road at (" + x + "," + z + ")");
                        }
                        proposed.put(key, Owner.PARK_BRIDGE);
                    }
                    continue;
                }
                if (water.openWater(x,z)) {
                    double dx = x*16.0+8.0 - (segment.isZBound()?
                        segment.endpointAcrossBlock():segment.endpointAlongBlock());
                    double dz = z*16.0+8.0 - (segment.isZBound()?
                        segment.endpointAlongBlock():segment.endpointAcrossBlock());
                    if (!water.intendedReceiverAt(x,z) && Math.hypot(dx,dz)>48.0) {
                        throw new IllegalArgumentException("Bounded river overlaps unrelated water before its confluence at (" + x + "," + z + ")");
                    }
                    proposed.put(key, Owner.RECEIVING_WATER);
                } else {
                    proposed.put(key, channel?Owner.WATER:Owner.DRY_CANDIDATE);
                }
            }
        }
        return new BoundedRiverOwnershipPlan(segment, proposed,minX,minZ,maxX,maxZ,exit,false);
    }

    private static boolean atOriginCell(BoundedRiverSegment segment,int x,int z) {
        double ox = segment.isZBound()?segment.originAcrossBlock():segment.originAlongBlock();
        double oz = segment.isZBound()?segment.originAlongBlock():segment.originAcrossBlock();
        return x == (int)Math.floor(ox/16.0) && z == (int)Math.floor(oz/16.0);
    }

    private static boolean outwardParkFace(int x,int z,int minX,int minZ,int maxX,int maxZ,
            BoundedRiverReservation.Exit exit) {
        return switch(exit) {
            case NORTH -> z==minZ && x>=minX && x<=maxX;
            case SOUTH -> z==maxZ && x>=minX && x<=maxX;
            case EAST -> x==maxX && z>=minZ && z<=maxZ;
            case WEST -> x==minX && z>=minZ && z<=maxZ;
        };
    }

    private static Owner parkCell(int x,int z,int minX,int minZ,int maxX,int maxZ,
            BoundedRiverReservation.Exit exit) {
        return x>=minX && x<=maxX && z>=minZ && z<=maxZ? Owner.PARK:Owner.PARK_ROAD;
    }

    private static boolean outwardFace(int x,int z,int minX,int minZ,int maxX,int maxZ,
            BoundedRiverReservation.Exit exit) {
        return switch(exit) {
            case NORTH -> z==minZ-1 && x>=minX && x<=maxX;
            case SOUTH -> z==maxZ+1 && x>=minX && x<=maxX;
            case EAST -> x==maxX+1 && z>=minZ && z<=maxZ;
            case WEST -> x==minX-1 && z>=minZ && z<=maxZ;
        };
    }

    public BoundedRiverOwnershipPlan resolve(DryLand land) {
        if (land==null) throw new IllegalArgumentException("Dry land validator is required");
        return resolvePlanned((x,z)->land.isValidatedCityCell(x,z)?DryUse.CITY_BLOCK:DryUse.NATURAL_BANK);
    }

    public BoundedRiverOwnershipPlan resolvePlanned(PlannedDryUse planner) {
        if (resolved) throw new IllegalStateException("Ownership was already resolved");
        if (planner==null) throw new IllegalArgumentException("Dry land planner is required");
        Map<Long,Owner> next = new HashMap<>(owner);
        for (Map.Entry<Long,Owner> entry: next.entrySet()) {
            if (entry.getValue()!=Owner.DRY_CANDIDATE) continue;
            long point = entry.getKey();
            int x=(int)(point >> 32), z=(int)point;
            DryUse use=planner.useAt(x,z);
            if (use==null) throw new IllegalArgumentException("Null dry use at " + x + "," + z);
            entry.setValue(switch(use) {
                case CITY_BLOCK -> Owner.CITY;
                case PUBLIC_ROAD -> Owner.CITY_ROAD;
                case NATURAL_BANK -> Owner.VEGETATED_BANK;
            });
        }
        return new BoundedRiverOwnershipPlan(segment,next,parkMinX,parkMinZ,parkMaxX,parkMaxZ,exit,true);
    }

    public Owner at(int x,int z) {return owner.getOrDefault(key(x,z),Owner.OUTSIDE);}
    public boolean isResolved() {return resolved;}
    public int size() {return owner.size();}
    public BoundedRiverSegment segment() {return segment;}
    public BoundedRiverReservation.Exit exit() {return exit;}
    public int count(Owner value) {
        int total=0;
        for (Owner entry:owner.values()) if (entry==value) total++;
        return total;
    }
    public List<String> audit() {
        List<String> issues=new ArrayList<>();
        if (resolved && count(Owner.DRY_CANDIDATE)>0) issues.add("unresolved dry candidates");
        for (Map.Entry<Long,Owner> entry:owner.entrySet()) {
            Owner value=entry.getValue();
            if (value!=Owner.CITY && value!=Owner.CITY_ROAD && value!=Owner.VEGETATED_BANK) continue;
            int x=(int)(entry.getKey()>>32),z=(int)(long)entry.getKey();
            if (segment.isRiver(x,z)) issues.add("wet dry-cell ownership at " + x + "," + z);
        }
        return issues;
    }
}
