package ch.k42.metropolis.grid.urbanGrid.highway;

import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Link-level protection from longitudinal roadbed in OPEN river water.
 * Shore and bank reservations are explicitly NOT forbidden. Short complete
 * perpendicular river crossings remain legal so the existing culvert painter
 * can finish them. Pure planning-time check: never requests a world chunk.
 */
public final class RiverHighwaySafety implements HighwayNetwork.LinkWaterSafety {
   public static final int MAIN = 1, NORTH_SOUTH = 2;
   // Ordinary river widths are at most four chunks by default. A modest allowance
   // covers meandering banks without allowing highway-scale travel in the channel.
   public static final int MAX_CONTIGUOUS_WET_COLUMNS = 8;

   /** Allows deterministic synthetic tests without constructing a Bukkit world. */
   public interface Probe {
      int openWaterAt(int chunkX, int chunkZ);
      boolean mainRunsEastWestAt(int chunkX, int chunkZ);
      boolean secondRunsEastWestAt(int chunkX, int chunkZ);
   }

   private final Probe probe;
   public RiverHighwaySafety(RiverProvider main, RiverProvider second) {
      this(new Probe() {
         @Override public int openWaterAt(int x, int z) {
            return (main != null && main.isRiver(x,z) ? MAIN : 0)
                 | (second != null && second.isRiver(x,z) ? NORTH_SOUTH : 0);
         }
         @Override public boolean mainRunsEastWestAt(int x, int z) {
            return main != null && main.isEastWestOriented(x);
         }
         @Override public boolean secondRunsEastWestAt(int x, int z) {
            if (second == null) return false;
            // The second carrier is Z-bound. A sufficiently severe transverse
            // bend can momentarily turn east-west. Use its own world-block spine.
            double low = second.spineXBlock(z * 16.0 - 24.0);
            double high = second.spineXBlock(z * 16.0 + 40.0);
            return Math.abs(high - low) > 64.0;
         }
      });
   }
   public RiverHighwaySafety(Probe probe) {
      if (probe == null) throw new IllegalArgumentException("probe");
      this.probe = probe;
   }

   /** Bounded, shared plan cache: never accumulates one entry per Chunky chunk forever. */
   private final Map<Long,Integer> wetCache = new LinkedHashMap<Long,Integer>(4096, .75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<Long,Integer> e) { return size() > 8192; }
   };
   private synchronized int wetAt(int x, int z) {
      long key = ((long)x << 32) ^ (z & 0xffffffffL);
      Integer v = wetCache.get(key);
      if (v == null) {
         v = probe.openWaterAt(x,z);
         wetCache.put(key,v);
      }
      return v;
   }

   @Override public boolean permits(List<HighwayNetwork.Rect> bands) {
      // Where two bands turn they overlap by a 2x2 junction. A river in a
      // junction is not a perpendicular crossing, regardless of each band's
      // individual orientation.
      for (int i=0;i<bands.size();i++) for(int j=i+1;j<bands.size();j++) {
         HighwayNetwork.Rect a=bands.get(i), b=bands.get(j);
         int x0=Math.max(a.minX,b.minX),x1=Math.min(a.maxX,b.maxX);
         int z0=Math.max(a.minZ,b.minZ),z1=Math.min(a.maxZ,b.maxZ);
         for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)
            if(wetAt(x,z)!=0) return false;
      }
      // Candidates have 1, 3 or 5 whole bands, including two-jog dry detours.
      // No placement is committed until the complete candidate passes.
      for (HighwayNetwork.Rect r : bands) {
         int w = r.maxX - r.minX + 1, h = r.maxZ - r.minZ + 1;
         boolean alongX = w > h;
         boolean alongZ = h > w;
         if (!alongX && !alongZ) {
            // A turn/junction inside the river cannot be a simple perpendicular culvert.
            for (int x = r.minX; x <= r.maxX; x++)
               for (int z = r.minZ; z <= r.maxZ; z++)
                  if (wetAt(x,z) != 0) return false;
            continue;
         }
         int from = alongX ? r.minX : r.minZ, to = alongX ? r.maxX : r.maxZ;
         int startLane = alongX ? r.minZ : r.minX, endLane = alongX ? r.maxZ : r.maxX;
         for (int lane = startLane; lane <= endLane; lane++) {
            int consecutiveWet = 0;
            int totalWet = 0;
            for (int along = from; along <= to; along++) {
               int x = alongX ? along : lane, z = alongX ? lane : along;
               int wet = wetAt(x,z);
               if (wet == 0) { consecutiveWet = 0; continue; }
               // Longitudinal occupancy is NEVER an acceptable crossing, even
               // when the path follows the bank and dips into only one wet lane.
               if ((wet & MAIN) != 0 && probe.mainRunsEastWestAt(x,z) == alongX) return false;
               if ((wet & NORTH_SOUTH) != 0 && probe.secondRunsEastWestAt(x,z) == alongX) return false;
               // A meandering river must be crossed, not periodically re-entered
               // for miles of roadbed. Even fragmented wet stretches in the same
               // band have a bounded total.
               if (++consecutiveWet > MAX_CONTIGUOUS_WET_COLUMNS
                      || ++totalWet > 2 * MAX_CONTIGUOUS_WET_COLUMNS) return false;
            }
         }
      }
      return true;
   }
}
