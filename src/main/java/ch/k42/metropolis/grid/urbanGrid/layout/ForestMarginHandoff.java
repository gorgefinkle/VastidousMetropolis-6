package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.ArrayList;
import java.util.List;

/**
 * R30ea forest frontage handoff into adjacent unclaimed dry chunks.
 * Only an explicitly owned, dry, non-road margin is released for forest painting.
 */
final class ForestMarginHandoff {
   private ForestMarginHandoff() { }

   static int reclaim(WorldLayout.Domain domain, WorldLayout.Mask water) {
      PlannedShoreOwnership.Prepared prepared = new PlannedShoreOwnership.Prepared(water,
            domain.minX, domain.minZ, domain.maxX, domain.maxZ);
      List<Long> plan = new ArrayList<>();
      for (WorldLayout.Rect rect : domain.blocks) {
         if (domain.contexts.get(rect) != ContextType.FOREST) continue;
         for (int side = 0; side < 4; side++) {
            int length = (side & 1) == 0 ? rect.width() : rect.depth();
            int start = -1;
            for (int i = 0; i <= length; i++) {
               int x = 0, z = 0;
               boolean safe = false;
               if (i < length) {
                  x = (side & 1) == 0 ? rect.minX + i : side == 1 ? rect.maxX + 1 : rect.minX - 1;
                  z = (side & 1) == 1 ? rect.minZ + i : side == 2 ? rect.maxZ + 1 : rect.minZ - 1;
                  safe = safeForestMargin(domain, water, prepared, x, z);
               }
               if (safe && start < 0) start = i;
               if ((!safe || i == length) && start >= 0) {
                  if (i - start >= 3) {
                     for (int j = start; j < i; j++) {
                        int px = (side & 1) == 0 ? rect.minX + j : side == 1 ? rect.maxX + 1 : rect.minX - 1;
                        int pz = (side & 1) == 1 ? rect.minZ + j : side == 2 ? rect.maxZ + 1 : rect.minZ - 1;
                        long key = key(px, pz);
                        if (!plan.contains(key)) plan.add(key);
                     }
                  }
                  start = -1;
               }
            }
         }
      }
      for (Long packed : plan) {
         int x = (int)(packed >> 32), z = (int)(long)packed;
         if (!safeForestMargin(domain, water, prepared, x, z))
            throw new IllegalStateException("Forest margin ownership drift");
      }
      for (Long packed : plan) {
         int x = (int)(packed >> 32), z = (int)(long)packed;
         domain.kind[x - domain.minX][z - domain.minZ] = 5;
      }
      return plan.size();
   }

   static boolean safeForestMargin(WorldLayout.Domain domain, WorldLayout.Mask water, int x, int z) {
      return safeForestMargin(domain, water, new PlannedShoreOwnership.Prepared(water,x,z,x,z),x,z);
   }

   private static boolean safeForestMargin(WorldLayout.Domain domain, WorldLayout.Mask water,
                                           PlannedShoreOwnership.Prepared prepared, int x, int z) {
      if (prepared.owns(x,z)) return false;
      if (x < domain.minX || x > domain.maxX || z < domain.minZ || z > domain.maxZ || domain.kindAt(x,z) != 4) return false;
      if (water != null && water.at(x,z)) return false;
      if (water instanceof WaterField) {
         WaterField field = (WaterField) water;
         if (field.kindAt(x,z) != WaterField.Kind.NONE || field.zoneKind(x,z) != WaterField.Kind.NONE
               || field.isCreekPlanningOnlyDryAt(x,z)) return false;
      }
      int ix = x - domain.minX, iz = z - domain.minZ;
      if (domain.shoreRoad != null && domain.shoreRoad[ix][iz]) return false;
      if (domain.noRing != null && domain.noRing[ix][iz]) return false;
      if (domain.creekOutletRing.containsKey(key(x,z)) || domain.urbanBoundaryRoads.contains(key(x,z))) return false;
      boolean adjacent = false;
      for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
         int ax = x + dx, az = z + dz;
         if (water != null && water.at(ax,az)) return false;
         if (dx == 0 && dz == 0) continue;
         if (domain.kindAt(ax,az) != 3) continue;
         WorldLayout.Rect block = null;
         for (WorldLayout.Rect rect : domain.blocks) {
            if (ax >= rect.minX && ax <= rect.maxX && az >= rect.minZ && az <= rect.maxZ) {
               block = rect;
               break;
            }
         }
         ContextType type = block == null ? null : domain.contexts.get(block);
         if (type != ContextType.FOREST) return false;
         if (Math.abs(dx) + Math.abs(dz) == 1) adjacent = true;
      }
      return adjacent;
   }
   private static long key(int x, int z) { return ((long)x << 32) ^ ((long)z & 0xffffffffL); }
}
