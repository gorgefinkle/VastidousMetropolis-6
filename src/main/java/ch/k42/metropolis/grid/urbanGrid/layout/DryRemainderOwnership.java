package ch.k42.metropolis.grid.urbanGrid.layout;

/** Handoff of genuinely unassigned dry cells to woodland, reconstructed from R30ea. */
final class DryRemainderOwnership {
    private DryRemainderOwnership() { }

    static final class Result {
        int woodland;
        int shore;
        int unresolved;
    }

    static Result commit(WorldLayout.Domain domain, WorldLayout.Mask shore) {
        return commit(domain, shore, new PlannedShoreOwnership.Prepared(shore,
                domain.minX,domain.minZ,domain.maxX,domain.maxZ));
    }

    static Result commit(WorldLayout.Domain domain, WorldLayout.Mask shore,
                         PlannedShoreOwnership.Prepared prepared) {
        Result result = new Result();
        for (int i = 0; i < domain.kind.length; i++) {
            for (int j = 0; j < domain.kind[i].length; j++) {
                if (domain.kind[i][j] != 4) continue;
                int x = domain.minX + i, z = domain.minZ + j;
                if (shore != null && shore.at(x,z)) continue;
                if (prepared.owns(x,z)) { result.shore++; continue; }
                if ((domain.shoreRoad != null && domain.shoreRoad[i][j])
                        || (domain.noRing != null && domain.noRing[i][j])
                        || (domain.fixedRoadBeforeCarve != null && domain.fixedRoadBeforeCarve[i][j])
                        || domain.urbanBoundaryRoads.contains(key(x,z))
                        || domain.creekOutletRing.containsKey(key(x,z))) {
                    result.unresolved++;
                    continue;
                }
                boolean touchesCity = false;
                for (int dir = 0; dir < 4; dir++) {
                    int xx = x + (dir == 0 ? -1 : dir == 1 ? 1 : 0);
                    int zz = z + (dir == 2 ? -1 : dir == 3 ? 1 : 0);
                    if (domain.kindAt(xx,zz) == 3) { touchesCity = true; break; }
                }
                if (touchesCity) { result.unresolved++; continue; }
                domain.kind[i][j] = 5;
                result.woodland++;
            }
        }
        return result;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
