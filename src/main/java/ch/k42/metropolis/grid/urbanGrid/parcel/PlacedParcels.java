package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.LodCompletionSync;
import ch.k42.metropolis.generator.MetropolisGenerator;
import org.bukkit.World;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * R30ea behavioral-source recovery from the genuine 2026-10-31 Java source,
 * checked against R30ea PlacedParcels.class disassembly.
 *
 * IMPORTANT: This intentionally reproduces R30ea's UNBOUNDED live Parcel
 * reference maps and premature action acknowledgement. Do not ship this file
 * as a fix for the Chunky memory stall. A disk-backed replacement must follow
 * save-aware lifecycle integration and world/restart testing.
 *
 * The original tracker records completed *chunk* ownership; roads and buildings
 * can finish in either order. An onChunkLoaded callback retries only when both
 * relevant chunks are loaded.
 */
public final class PlacedParcels {
    private static final Map<UUID, PlacedParcels> BY_WORLD = new ConcurrentHashMap<>();
    private final Map<Long, Parcel> finished = new ConcurrentHashMap<>();
    private final Set<String> cut = ConcurrentHashMap.newKeySet();
    private final Map<Long, OceanPortParcel> finishedPorts = new ConcurrentHashMap<>();
    private final Set<Long> connectedPorts = ConcurrentHashMap.newKeySet();
    private static final int[] DX = {0, 0, -1, 1};
    private static final int[] DZ = {-1, 1, 0, 0};

    public static PlacedParcels of(MetropolisGenerator generator) {
        World world = generator.getWorld();
        if (world == null) {
            throw new IllegalStateException("Cannot track parcel cutouts before world initialization");
        }
        return BY_WORLD.computeIfAbsent(world.getUID(), uid -> new PlacedParcels());
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** A completed road may satisfy already-completed adjacent building chunks. */
    public void roadFinished(StreetParcel road, MetropolisGenerator generator) {
        long roadKey = key(road.getChunkX(), road.getChunkZ());
        finished.put(roadKey, road);
        OceanPortParcel port = finishedPorts.get(roadKey);
        if (port != null) connectPortOnce(roadKey, port, road, generator);
        for (int d = 0; d < 4; d++) {
            int nx = road.getChunkX() + DX[d], nz = road.getChunkZ() + DZ[d];
            Parcel parcel = finished.get(key(nx, nz));
            if (parcel != null && !(parcel instanceof StreetParcel)) {
                cutOnce(parcel, road, nx, nz, generator);
            }
        }
    }

    public void portFinished(OceanPortParcel port, int roadChunkX, int roadChunkZ,
                             MetropolisGenerator generator) {
        long roadKey = key(roadChunkX, roadChunkZ);
        finishedPorts.put(roadKey, port);
        Parcel road = finished.get(roadKey);
        if (road instanceof StreetParcel) {
            connectPortOnce(roadKey, port, (StreetParcel) road, generator);
        }
    }

    private void connectPortOnce(long roadKey, OceanPortParcel port, StreetParcel road,
                                 MetropolisGenerator generator) {
        if (generator.getWorld() == null ||
                !generator.getWorld().isChunkLoaded(road.getChunkX(), road.getChunkZ())) return;
        if (!connectedPorts.add(roadKey)) return;
        port.cutRoadApproach(road, generator);
        LodCompletionSync.changed(generator, road.getChunkX(), road.getChunkZ());
        LodCompletionSync.changed(generator, port.getChunkX(), port.getChunkZ());
    }

    /** Existing R30ea compatibility hook for multi-chunk parcels. */
    public void buildingFinished(Parcel building, MetropolisGenerator generator) {
        int x0 = building.getChunkX(), z0 = building.getChunkZ();
        int w = Math.max(1, building.getChunkSizeX());
        int h = Math.max(1, building.getChunkSizeZ());
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + h; z++) {
                finished.put(key(x, z), building);
            }
        }
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + h; z++) {
                for (int d = 0; d < 4; d++) {
                    int nx = x + DX[d], nz = z + DZ[d];
                    Parcel neighbor = finished.get(key(nx, nz));
                    if (neighbor instanceof StreetParcel) {
                        cutOnce(building, (StreetParcel) neighbor, x, z, generator);
                    }
                }
            }
        }
    }

    /** R30ea records a pasted schematic per occupied chunk, not prematurely per full footprint. */
    public void buildingChunkFinished(ClipboardParcel building, int x, int z,
                                      MetropolisGenerator generator) {
        if (x < building.getChunkX() || x >= building.getChunkX() + building.getChunkSizeX() ||
            z < building.getChunkZ() || z >= building.getChunkZ() + building.getChunkSizeZ()) {
            throw new IllegalArgumentException("Building chunk is outside schematic footprint");
        }
        finished.put(key(x, z), building);
        for (int d = 0; d < DX.length; d++) {
            Parcel p = finished.get(key(x + DX[d], z + DZ[d]));
            if (p instanceof StreetParcel) {
                cutOnce(building, (StreetParcel) p, x, z, generator);
            }
        }
    }

    /** Called after late-pasted overflow; not a completed-save acknowledgement. */
    void afterOverflow(ClipboardParcel building, int x, int z,
                       MetropolisGenerator generator) {
        Parcel parcel = finished.get(key(x, z));
        if (parcel instanceof StreetParcel && generator.getWorld() != null &&
                generator.getWorld().isChunkLoaded(x, z)) {
            building.makeCutoutsToward((StreetParcel) parcel, generator);
            LodCompletionSync.changed(generator, x, z);
        }
    }

    /** Attempts a pending adjacent cutout when the road/building chunk becomes loaded. */
    public void onChunkLoaded(int x, int z, MetropolisGenerator generator) {
        if (generator.getWorld() == null) return;
        Parcel parcel = finished.get(key(x, z));
        if (parcel instanceof StreetParcel) {
            StreetParcel road = (StreetParcel) parcel;
            if (!generator.getWorld().isChunkLoaded(x, z)) return;
            for (int d = 0; d < DX.length; d++) {
                int nx = x + DX[d], nz = z + DZ[d];
                Parcel adjacent = finished.get(key(nx, nz));
                if (adjacent != null && !(adjacent instanceof StreetParcel)) {
                    cutOnce(adjacent, road, nx, nz, generator);
                }
            }
        } else if (parcel != null) {
            for (int d = 0; d < DX.length; d++) {
                int nx = x + DX[d], nz = z + DZ[d];
                Parcel adjacent = finished.get(key(nx, nz));
                if (adjacent instanceof StreetParcel) {
                    cutOnce(parcel, (StreetParcel) adjacent, x, z, generator);
                }
            }
        }
    }

    private void cutOnce(Parcel building, StreetParcel road, int buildingChunkX, int buildingChunkZ,
                         MetropolisGenerator generator) {
        if (generator.getWorld() == null ||
            !generator.getWorld().isChunkLoaded(road.getChunkX(), road.getChunkZ()) ||
            !generator.getWorld().isChunkLoaded(buildingChunkX, buildingChunkZ)) return;
        String pair = road.getChunkX() + "," + road.getChunkZ() + ">" + buildingChunkX + "," + buildingChunkZ;
        if (!cut.add(pair)) return;
        if (building instanceof ClipboardParcel) {
            ((ClipboardParcel) building).makeCutoutsToward(road, generator);
        } else if (building instanceof MorphomosParcel) {
            ((MorphomosParcel) building).makeCutoutsToward(road, generator);
        }
        LodCompletionSync.changed(generator, buildingChunkX, buildingChunkZ);
        LodCompletionSync.changed(generator, road.getChunkX(), road.getChunkZ());
    }
}
