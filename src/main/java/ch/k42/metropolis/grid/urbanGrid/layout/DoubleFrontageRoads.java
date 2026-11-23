package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Resolves adjacent road strips by assigning the intervening road to the first
 * eligible district. The map's existing fixed, shoreline and creek roads are
 * immutable. Recovered from the shipped R30ea behavior; not a redesigned planner.
 */
final class DoubleFrontageRoads {
    private DoubleFrontageRoads() {}

    interface FixedBoundary {
        boolean at(int x, int z);
        default boolean shoreAt(int x, int z) { return false; }
    }

    private static long key(int x, int z) {
        return ((long)x << 32) ^ (z & 0xffffffffL);
    }

    static int prevent(WorldLayout.Domain domain, int maximum) {
        return prevent(domain, maximum, (x,z) -> false);
    }

    static int preventWithPlan(WorldLayout.Domain domain, int maximum, FixedBoundary lattice, WorldLayout plan) {
        return prevent(domain, maximum, new FixedBoundary() {
            @Override public boolean at(int x, int z) { return lattice.at(x, z); }
            @Override public boolean shoreAt(int x, int z) {
                return plan.physicalWaterWithoutDomainLookup(x, z);
            }
        });
    }

    static int prevent(WorldLayout.Domain domain, int maximum, FixedBoundary fixed) {
        int changed = 0;
        boolean restart;
        do {
            restart = false;
            List<WorldLayout.Rect> blocks = new ArrayList<>(domain.blocks);
            blocks.sort(Comparator.comparingInt((WorldLayout.Rect r) -> isCountryside(domain.contexts.get(r)) ? 0 : 1)
                   .thenComparingInt(r -> r.minZ).thenComparingInt(r -> r.minX));
            for (WorldLayout.Rect rect : blocks) {
                if (!domain.blocks.contains(rect) || domain.landmarks.containsKey(rect)) continue;
                ContextType context = domain.contexts.get(rect);
                if (context == null) continue;
                for (int side = 0; side < 4; side++) {
                    if (!valid(domain, rect, context, side, maximum, fixed)) continue;
                    WorldLayout.Rect expanded = expand(rect, side);
                    domain.blocks.remove(rect);
                    domain.blocks.add(expanded);
                    domain.contexts.remove(rect);
                    domain.contexts.put(expanded, context);
                    int begin = side < 2 ? rect.minX : rect.minZ;
                    int end   = side < 2 ? rect.maxX : rect.maxZ;
                    int face = side == 0 ? rect.minZ - 1 : side == 1 ? rect.maxZ + 1 : side == 2 ? rect.minX - 1 : rect.maxX + 1;
                    for (int t = begin; t <= end; t++) {
                        int x = side < 2 ? t : face;
                        int z = side < 2 ? face : t;
                        domain.kind[x - domain.minX][z - domain.minZ] = 3;
                        domain.urbanBoundaryRoads.remove(key(x, z));
                        changed++;
                    }
                    restart = true;
                    break;
                }
                if (restart) break;
            }
        } while (restart);
        return changed;
    }

    private static boolean isCountryside(ContextType c) {
        return c != null && c.isMorphomos();
    }

    private static WorldLayout.Rect expand(WorldLayout.Rect r, int side) {
        return switch (side) {
            case 0 -> new WorldLayout.Rect(r.minX,r.minZ - 1,r.maxX,r.maxZ);
            case 1 -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX,r.maxZ + 1);
            case 2 -> new WorldLayout.Rect(r.minX - 1,r.minZ,r.maxX,r.maxZ);
            default -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX + 1,r.maxZ);
        };
    }

    private static boolean valid(WorldLayout.Domain domain, WorldLayout.Rect r, ContextType context,
                                 int side, int maximum, FixedBoundary fixed) {
        boolean alongX = side < 2;
        int length = alongX ? r.width() : r.depth();
        if (length < 3 || (alongX ? r.depth() : r.width()) + 1 > maximum) return false;
        int dx = side == 2 ? -1 : side == 3 ? 1 : 0;
        int dz = side == 0 ? -1 : side == 1 ? 1 : 0;
        int begin = alongX ? r.minX : r.minZ;
        int end = alongX ? r.maxX : r.maxZ;
        int face = side == 0 ? r.minZ : side == 1 ? r.maxZ : side == 2 ? r.minX : r.maxX;

        for (int t = begin; t <= end; t++) {
            int x = alongX ? t : face;
            int z = alongX ? face : t;
            int nearX = x + dx, nearZ = z + dz;
            int midX = x + dx * 2, midZ = z + dz * 2;
            int farX = x + dx * 3, farZ = z + dz * 3;
            if (domain.kindAt(nearX,nearZ) != 2) return false;
            if (isImmutableRoad(domain, nearX,nearZ, fixedOutsideFace(fixed, alongX, face, t, dx, dz))) return false;
            boolean fixedMid = fixed.at(midX,midZ);
            int middleKind = domain.kindAt(midX,midZ);
            if (middleKind != 2 && !(fixedMid && middleKind == 0)) return false;
            if (!fixedMid) {
                if (domain.kindAt(farX,farZ) != 3) return false;
                WorldLayout.Rect adjacent = blockAt(domain,farX,farZ);
                ContextType other = adjacent == null ? null : domain.contexts.get(adjacent);
                if (other == null || isCountryside(context) == isCountryside(other)) return false;
            }
        }
        int outsideConnections = 0;
        for (int t : new int[]{begin - 1,end + 1}) {
            int x = alongX ? t : face;
            int z = alongX ? face : t;
            if (!streetAt(domain,x + dx,z + dz)) return false;
            int edgeX = x + dx * 2, edgeZ = z + dz * 2;
            if (roadOrFixedOutside(domain,edgeX,edgeZ,fixed)) outsideConnections++;
            else if (!fixed.shoreAt(edgeX,edgeZ)) return false;
        }
        return outsideConnections > 0;
    }

    private static boolean streetAt(WorldLayout.Domain d, int x, int z) { return d.kindAt(x,z) == 2; }
    private static boolean roadOrFixedOutside(WorldLayout.Domain d, int x, int z, FixedBoundary fixed) {
        int kind = d.kindAt(x,z);
        return kind == 2 || (kind == 0 && fixed.at(x,z));
    }
    private static WorldLayout.Rect blockAt(WorldLayout.Domain d, int x, int z) {
        for (WorldLayout.Rect r : d.blocks)
            if (x >= r.minX && x <= r.maxX && z >= r.minZ && z <= r.maxZ) return r;
        return null;
    }
    private static boolean fixedOutsideFace(FixedBoundary fixed, boolean alongX, int face, int t, int dx, int dz) {
        int x = alongX ? t : face;
        int z = alongX ? face : t;
        return fixed.at(x + 2 * dx,z + 2 * dz);
    }
    private static boolean isImmutableRoad(WorldLayout.Domain d, int x, int z, boolean fixedOutside) {
        if ((!fixedOutside && d.urbanBoundaryRoads.contains(key(x,z))) || d.creekOutletRing.containsKey(key(x,z))) return true;
        int ix = x - d.minX, iz = z - d.minZ;
        if (ix < 0 || iz < 0 || ix >= d.kind.length || iz >= d.kind[0].length) return true;
        boolean shore = d.shoreRoad != null && d.shoreRoad[ix][iz];
        boolean originalFixed = d.fixedRoadBeforeCarve != null && d.fixedRoadBeforeCarve[ix][iz];
        if (shore && fixedOutside) return false;
        return originalFixed || shore;
    }
}
