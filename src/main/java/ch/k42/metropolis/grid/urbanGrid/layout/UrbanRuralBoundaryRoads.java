package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.ArrayList;

/** Restored from the R30ea compiled reference: protect urban streets against
 * countryside parcel expansion, converting eligible boundary strips to roads.
 * This is source recovery, not a newly approved road-planning rule. */
final class UrbanRuralBoundaryRoads {
    private UrbanRuralBoundaryRoads() {}

    private static long key(int x, int z) {
        return ((long) x << 32) ^ ((long) z & 0xffffffffL);
    }

    static int repair(WorldLayout.Domain domain, int minimum) {
        java.util.List<WorldLayout.Rect> snapshot = new ArrayList<>(domain.blocks);
        int repaired = 0;
        for (WorldLayout.Rect rect : snapshot) {
            ContextType ctx = domain.contexts.get(rect);
            if (ctx == null || !ctx.isMorphomos()) continue;
            for (int face = 0; face < 4; face++) {
                boolean alongX = face < 2;
                int edgeLength = alongX ? rect.width() : rect.depth();
                int depth = alongX ? rect.depth() : rect.width();
                if (edgeLength < Math.max(2, minimum) || depth - 1 < Math.max(2, minimum)) continue;
                int dx = face == 2 ? -1 : face == 3 ? 1 : 0;
                int dz = face == 0 ? -1 : face == 1 ? 1 : 0;
                int edge = face == 0 ? rect.minZ : face == 1 ? rect.maxZ : face == 2 ? rect.minX : rect.maxX;
                int start = alongX ? rect.minX : rect.minZ;
                int end = alongX ? rect.maxX : rect.maxZ;
                int urbanCount = 0, transitions = 0, previous = -1;
                boolean eligible = true;
                for (int a = start; a <= end; a++) {
                    int x = alongX ? a : edge, z = alongX ? edge : a;
                    int nx = x + dx, nz = z + dz;
                    int adjacent = domain.kindAt(nx, nz);
                    int classification;
                    if (adjacent == 3 && !isCountrysideAt(domain, nx, nz)) {
                        classification = 0;
                        urbanCount++;
                    } else if (adjacent == 2 || adjacent == 1) {
                        classification = 1;
                    } else {
                        eligible = false;
                        break;
                    }
                    if (previous != -1 && previous != classification) transitions++;
                    previous = classification;
                }
                if (!eligible || urbanCount < Math.max(2, minimum) || transitions > 1) continue;

                int x0 = alongX ? start - 1 : edge, z0 = alongX ? edge : start - 1;
                int x1 = alongX ? end + 1 : edge, z1 = alongX ? edge : end + 1;
                if (domain.kindAt(x0, z0) != 2 || domain.kindAt(x1, z1) != 2) continue;
                boolean connect0 = domain.kindAt(x0 + dx, z0 + dz) == 2
                        || domain.kindAt(x0 - dx, z0 - dz) == 2;
                boolean connect1 = domain.kindAt(x1 + dx, z1 + dz) == 2
                        || domain.kindAt(x1 - dx, z1 - dz) == 2;
                if (!connect0 || !connect1) continue;

                WorldLayout.Rect inset;
                if (face == 0) inset = new WorldLayout.Rect(rect.minX, rect.minZ + 1, rect.maxX, rect.maxZ);
                else if (face == 1) inset = new WorldLayout.Rect(rect.minX, rect.minZ, rect.maxX, rect.maxZ - 1);
                else if (face == 2) inset = new WorldLayout.Rect(rect.minX + 1, rect.minZ, rect.maxX, rect.maxZ);
                else inset = new WorldLayout.Rect(rect.minX, rect.minZ, rect.maxX - 1, rect.maxZ);
                domain.blocks.remove(rect);
                domain.blocks.add(inset);
                domain.contexts.remove(rect);
                domain.contexts.put(inset, ctx);
                for (int a = start; a <= end; a++) {
                    int x = alongX ? a : edge, z = alongX ? edge : a;
                    domain.kind[x - domain.minX][z - domain.minZ] = 2;
                    domain.urbanBoundaryRoads.add(key(x, z));
                }
                domain.urbanBoundaryRoads.add(key(x0, z0));
                domain.urbanBoundaryRoads.add(key(x1, z1));
                repaired += edgeLength;
                break;
            }
        }
        return repaired;
    }

    static int protectExistingUrbanFrontage(WorldLayout.Domain domain) {
        int added = 0;
        for (WorldLayout.Rect rect : domain.blocks) {
            ContextType ctx = domain.contexts.get(rect);
            if (ctx == null || ctx.isMorphomos()) continue;
            for (int x = rect.minX; x <= rect.maxX; x++) {
                added += protectIfStreet(domain, x, rect.minZ - 1);
                added += protectIfStreet(domain, x, rect.maxZ + 1);
            }
            for (int z = rect.minZ; z <= rect.maxZ; z++) {
                added += protectIfStreet(domain, rect.minX - 1, z);
                added += protectIfStreet(domain, rect.maxX + 1, z);
            }
            // The four 90-degree turn cells are part of the same accepted
            // perimeter. Protecting only straight faces lets Morphomos
            // dissolve a diagonal corner (no urban BLOCK is cardinally
            // adjacent to it), even while both adjoining faces survive.
            added += protectIfStreet(domain, rect.minX - 1, rect.minZ - 1);
            added += protectIfStreet(domain, rect.maxX + 1, rect.minZ - 1);
            added += protectIfStreet(domain, rect.minX - 1, rect.maxZ + 1);
            added += protectIfStreet(domain, rect.maxX + 1, rect.maxZ + 1);
        }
        return added;
    }

    private static int protectIfStreet(WorldLayout.Domain domain, int x, int z) {
        if (domain.kindAt(x, z) != 2) return 0;
        return domain.urbanBoundaryRoads.add(key(x, z)) ? 1 : 0;
    }

    private static boolean isCountrysideAt(WorldLayout.Domain domain, int x, int z) {
        for (WorldLayout.Rect rect : domain.blocks) {
            if (x >= rect.minX && x <= rect.maxX && z >= rect.minZ && z <= rect.maxZ) {
                ContextType ctx = domain.contexts.get(rect);
                return ctx != null && ctx.isMorphomos();
            }
        }
        return true;
    }
}
