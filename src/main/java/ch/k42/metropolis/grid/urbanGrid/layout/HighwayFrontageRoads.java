package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.PieceOpenness;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * R30ea highway-facing district repair. Recover from the archived behavior,
 * not from a new road-planning policy. The frontage check is intentionally
 * conservative around previously owned boundary streets.
 */
final class HighwayFrontageRoads {
   private HighwayFrontageRoads() { }

   private static long key(int x, int z) {
      return ((long)x << 32) ^ ((long)z & 0xffffffffL);
   }

   static int prevent(WorldLayout.Domain d, HighwayNetwork.Mask highway, int minimum) {
      int changed = 0;
      List<WorldLayout.Rect> original = new ArrayList<>(d.blocks);
      original.sort(Comparator.comparingInt((WorldLayout.Rect r) -> r.minZ)
              .thenComparingInt(r -> r.minX));
      for (WorldLayout.Rect rect : original) {
         if (!d.blocks.contains(rect) || d.landmarks.containsKey(rect)) continue;
         ContextType context = d.contexts.get(rect);
         if (context == null) continue;
         for (int side = 0; side < 4; side++) {
            if (!eligible(d, highway, rect, side, minimum)) continue;
            boolean alongX = side < 2;
            int first = alongX ? rect.minX : rect.minZ;
            int last = alongX ? rect.maxX : rect.maxZ;
            int beyond = side == 0 ? rect.minZ-1 : side == 1 ? rect.maxZ+1
                    : side == 2 ? rect.minX-1 : rect.maxX+1;
            WorldLayout.Rect next = side == 0
                    ? new WorldLayout.Rect(rect.minX, rect.minZ-1, rect.maxX, rect.maxZ)
                    : side == 1 ? new WorldLayout.Rect(rect.minX, rect.minZ, rect.maxX, rect.maxZ+1)
                    : side == 2 ? new WorldLayout.Rect(rect.minX-1, rect.minZ, rect.maxX, rect.maxZ)
                    : new WorldLayout.Rect(rect.minX, rect.minZ, rect.maxX+1, rect.maxZ);
            for (int at = first; at <= last; at++) {
               int x = alongX ? at : beyond, z = alongX ? beyond : at;
               d.kind[x-d.minX][z-d.minZ] = 3;
            }
            d.blocks.remove(rect);
            d.blocks.add(next);
            d.contexts.remove(rect);
            d.contexts.put(next, context);
            changed += last-first+1;
            break;
         }
      }
      return changed;
   }

   private static boolean eligible(WorldLayout.Domain d, HighwayNetwork.Mask highway,
                                   WorldLayout.Rect rect, int side, int minimum) {
      boolean alongX = side < 2;
      int first = alongX ? rect.minX : rect.minZ;
      int last = alongX ? rect.maxX : rect.maxZ;
      if (last - first + 1 < 3) return false;
      if ((alongX ? rect.depth() : rect.width()) + 1 > minimum) return false;
      int dx = side == 2 ? -1 : side == 3 ? 1 : 0;
      int dz = side == 0 ? -1 : side == 1 ? 1 : 0;
      int mask = alongX ? 10 : 5;
      for (int at = first; at <= last; at++) {
         int x = alongX ? at : side == 2 ? rect.minX : rect.maxX;
         int z = alongX ? side == 0 ? rect.minZ : rect.maxZ : at;
         if (d.kindAt(x+dx,z+dz) != 2 || immutable(d,x+dx,z+dz)) return false;
         for (int distance = 2; distance <= 3; distance++) {
            int hx=x+dx*distance,hz=z+dz*distance;
            if (!highway.at(hx,hz)) return false;
            RoadType piece = HighwayNetwork.pieceFor(highway,hx,hz);
            if (piece == null || (PieceOpenness.sides(piece) & mask) != mask) return false;
         }
      }
      for (int end : new int[]{first-1,last+1}) {
         int a = alongX ? rect.minZ : rect.minX;
         int b = alongX ? rect.maxZ : rect.maxX;
         for (int cross=a; cross<=b; cross++) {
            int x=alongX ? end : cross, z=alongX ? cross : end;
            if (d.kindAt(x,z) != 2) return false;
         }
         int x=alongX ? end : side==2 ? rect.minX:rect.maxX;
         int z=alongX ? side==0 ? rect.minZ:rect.maxZ : end;
         if (d.kindAt(x+dx,z+dz) != 2 || !highway.at(x+2*dx,z+2*dz)) return false;
      }
      return true;
   }

   private static boolean immutable(WorldLayout.Domain d, int x, int z) {
      int ix=x-d.minX, iz=z-d.minZ;
      if (ix<0 || iz<0 || ix>=d.kind.length || iz>=d.kind[0].length) return true;
      return d.urbanBoundaryRoads.contains(key(x,z)) || d.creekOutletRing.containsKey(key(x,z))
              || d.shoreRoad != null && d.shoreRoad[ix][iz]
              || d.fixedRoadBeforeCarve != null && d.fixedRoadBeforeCarve[ix][iz];
   }
}
