package ch.k42.metropolis.grid.urbanGrid.plan;

import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;
import ch.k42.metropolis.grid.urbanGrid.layout.CityLayout;
import ch.k42.metropolis.grid.urbanGrid.layout.OuterLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The world plan, put together from the three pieces that already work from the seed alone:
 * <pre>
 *   HighwayNetwork   the lanes: city rings, the roads across them, the links between cities
 *   CityLayout       the blocks and streets inside each city's ring
 *   OuterLayout      the blocks and streets of the band outside the rings, carved from the highways outward
 * </pre>
 * and nothing else: no tile, no grid array, no build order. Precedence at a chunk is lane, then the city inside its
 * ring, then the outer band, then wild land.
 *
 * Highway lanes are looked up through a cache of fixed-size panels of the network's answer. A panel is only a cache
 * line for a pure function; nothing is stored in one that the network would not give again.
 */
public final class SeedPlan implements WorldPlan {

   private static final int PANEL = 64;
   private static final int PANELS_KEPT = 512;

   private final HighwayNetwork network;
   private final CityLayout city;
   private final OuterLayout outer;
   private final Map<Long, boolean[]> panels = new LinkedHashMap<Long, boolean[]>(64, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, boolean[]> eldest) {
         return this.size() > PANELS_KEPT;
      }
   };

   public SeedPlan(HighwayNetwork network, CityLayout city, OuterLayout outer) {
      this.network = network;
      this.city = city;
      this.outer = outer;
   }

   private static Rect of(CityLayout.Rect r) {
      return new Rect(r.minX, r.minZ, r.maxX, r.maxZ);
   }

   private static Rect of(OuterLayout.Rect r) {
      return new Rect(r.minX, r.minZ, r.maxX, r.maxZ);
   }

   private synchronized boolean laneAt(int x, int z) {
      int px = Math.floorDiv(x, PANEL);
      int pz = Math.floorDiv(z, PANEL);
      long key = ((long) px << 32) ^ (pz & 0xffffffffL);
      boolean[] panel = this.panels.get(key);
      if (panel == null) {
         int minX = px * PANEL;
         int minZ = pz * PANEL;
         HighwayNetwork.Snapshot s = this.network.snapshot(minX, minZ, minX + PANEL - 1, minZ + PANEL - 1);
         panel = new boolean[PANEL * PANEL];
         for (int dx = 0; dx < PANEL; dx++) {
            for (int dz = 0; dz < PANEL; dz++) {
               panel[dx * PANEL + dz] = s.at(minX + dx, minZ + dz);
            }
         }
         this.panels.put(key, panel);
      }
      return panel[(x - px * PANEL) * PANEL + (z - pz * PANEL)];
   }

   @Override
   public Cell cellAt(int x, int z) {
      if (this.laneAt(x, z)) {
         return new Cell(Kind.LANE, Zone.NONE, null);
      }
      switch (this.city.kindAt(x, z)) {
         case BLOCK:
            return new Cell(Kind.BLOCK, Zone.CITY, of(this.city.blockAt(x, z)));
         case STREET:
            return new Cell(Kind.STREET, Zone.CITY, null);
         default:
            break;
      }
      switch (this.outer.kindAt(x, z)) {
         case OuterLayout.STREET:
            return new Cell(Kind.STREET, Zone.OUTER, null);
         case OuterLayout.BLOCK: {
            for (OuterLayout.Rect r : this.outer.blocksIn(x, z, x, z)) {
               if (x >= r.minX && x <= r.maxX && z >= r.minZ && z <= r.maxZ) {
                  return new Cell(Kind.BLOCK, Zone.OUTER, of(r));
               }
            }
            return new Cell(Kind.UNCLAIMED, Zone.OUTER, null);
         }
         case OuterLayout.BOX:
         case OuterLayout.UNCLAIMED:
            // inside a city's box, or in the band but claimed by nothing: the plan has a gap here
            return new Cell(Kind.UNCLAIMED, Zone.NONE, null);
         default:
            return new Cell(Kind.WILD, Zone.NONE, null);
      }
   }

   @Override
   public List<Rect> blocksIn(int minX, int minZ, int maxX, int maxZ) {
      Set<Rect> seen = new HashSet<Rect>();
      List<Rect> out = new ArrayList<Rect>();
      for (CityLayout.Rect r : this.city.blocksIn(minX, minZ, maxX, maxZ)) {
         Rect b = of(r);
         if (seen.add(b)) {
            out.add(b);
         }
      }
      for (OuterLayout.Rect r : this.outer.blocksIn(minX, minZ, maxX, maxZ)) {
         Rect b = of(r);
         if (seen.add(b)) {
            out.add(b);
         }
      }
      return out;
   }
}
