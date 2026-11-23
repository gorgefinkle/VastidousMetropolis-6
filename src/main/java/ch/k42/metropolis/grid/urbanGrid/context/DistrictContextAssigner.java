package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Neighbor-aware district zoning.
 *
 * The plain noise lookup in ContextProviderSimplex picks every district's
 * context on its own, so nothing stops a HIGHRISE district from landing next
 * to farmland. This class instead assigns contexts to ALL of a tile's districts
 * at once, against one rule: contexts sit on a ladder, and two districts may
 * only touch when their rungs are the same or directly adjacent.
 *
 *    HIGHRISE -> MIDRISE -> LOWRISE / INDUSTRIAL -> RESIDENTIAL (+ DENSE) -> FOREST / FARM
 *
 * Both members of a two-context rung share its logic (they may touch each
 * other, the rung above and the rung below), and every rung may touch itself.
 *
 * contexts.json weights keep their meaning, they just become roles:
 *   - the weight of every context on a rung, summed, is that rung's share of
 *     the map (nested groups are normalised the same way the noise lookup
 *     normalises them, so a group's weight is split by its children's weights);
 *   - a context's own share of its rung decides which member of the rung a
 *     district becomes (LOWRISE vs INDUSTRIAL, RESIDENTIAL vs DENSE, FOREST vs FARM).
 *
 * Rungs with no weight in contexts.json are skipped and the ladder closes
 * over the gap, so a config without forest/farm simply ends at RESIDENTIAL.
 *
 * Method: the existing simplex field still says what each district WANTS to be
 * (mapped onto the ladder in order, so smooth noise gives smooth
 * highrise-to-countryside gradients). Districts are then assigned most
 * confident first, and each assignment narrows the allowed rung range of its
 * neighbours, and theirs in turn. Because narrowing runs ahead of the
 * assignments, a district is never left with nothing legal to pick.
 *
 * The result is deterministic for a given district layout: no ordering
 * depends on hash iteration, and all randomness comes from the world-seeded
 * noise.
 */
public class DistrictContextAssigner {

   /** Looks up the rung a neighbouring, already-built tile recorded for a chunk cell, or -1. */
   public interface TierLookup {
      int tierAt(int chunkX, int chunkZ);
   }

   public static final class Result {
      /** One context per input district, same order as the input arrays. */
      public final ContextType[] contexts;
      /** Ladder rung (0 = HIGHRISE .. 4 = FOREST/FARM) per input district. */
      public final int[] tiers;
      public final int borders;
      /** Borders whose rungs differ by more than one. 0 unless seams with other tiles clashed. */
      public final int violations;
      public final int seamConflicts;

      Result(ContextType[] contexts, int[] tiers, int borders, int violations, int seamConflicts) {
         this.contexts = contexts;
         this.tiers = tiers;
         this.borders = borders;
         this.violations = violations;
         this.seamConflicts = seamConflicts;
      }
   }

   private static final ContextType[][] LADDER = {
           {ContextType.HIGHRISE},
           {ContextType.MIDRISE},
           {ContextType.LOWRISE, ContextType.INDUSTRIAL},
           {ContextType.RESIDENTIAL, ContextType.DENSERESIDENTIAL},
           {ContextType.FOREST, ContextType.FARM}
   };

   /** Chunk cells inside a tile's edge that a neighbouring tile is allowed to see. */
   public static final int SEAM_REACH = 2;
   /** How far outside a district's edge to look for a neighbouring tile's cells. */
   private static final int SEAM_LOOKAHEAD = 4;

   private final ContextProviderSimplex noise;
   private final ContextType[] singleMember = new ContextType[LADDER.length];
   private final ContextZone[][] members = new ContextZone[LADDER.length][];
   private final int[] compress = new int[LADDER.length];
   private final int[] present;
   private final double[] cumulative;
   private final int roadGap;
   private final boolean crossTile;
   /** Non-null in city-rings mode (generator.cityRings.enabled): replaces the plain zone noise as each district's preference. */
   private final CityRingField rings;
   /** Cumulative shares of the city rungs only (the outer farm/forest rung is open-ended), last entry 1.0. */
   private final double[] innerCumulative;
   /** Index of the outer rung in present[], or -1 when forest/farm aren't configured. */
   private final int outerRung;

   public DistrictContextAssigner(ContextProviderSimplex noise, ContextConfig config) {
      this.noise = noise;
      this.roadGap = Math.max(1, PluginConfig.getDistrictNeighborRulesRoadGap());
      this.crossTile = PluginConfig.isDistrictNeighborRulesCrossTile();

      Map<ContextType, Double> leafWeights = new EnumMap<ContextType, Double>(ContextType.class);
      flatten(config.getContextZones(), 1.0, leafWeights, new HashSet<ContextType>());

      double[] tierWeight = new double[LADDER.length];
      Arrays.fill(this.compress, -1);
      List<Integer> presentTiers = new ArrayList<Integer>();
      for (int t = 0; t < LADDER.length; t++) {
         List<ContextZone> zones = new ArrayList<ContextZone>();
         double total = 0.0;
         int weightSum = 0;
         for (ContextType type : LADDER[t]) {
            Double w = leafWeights.get(type);
            if (w != null && w > 0.0) {
               long scaled = Math.max(1L, Math.round(w * 1000.0));
               zones.add(new ContextZone(type, (double) scaled));
               weightSum += (int) scaled;
               total += w;
            }
         }
         tierWeight[t] = total;
         if (zones.isEmpty()) {
            continue;
         }
         if (zones.size() == 1) {
            this.singleMember[t] = zones.get(0).getContextType();
         } else {
            // fresh nodes, never the config tree's own: ContextProviderSimplex
            // writes totalWeight into those in place.
            for (ContextZone z : zones) {
               z.setTotalWeight(weightSum);
            }
            this.members[t] = zones.toArray(new ContextZone[0]);
         }
         this.compress[t] = presentTiers.size();
         presentTiers.add(t);
      }

      this.present = new int[presentTiers.size()];
      for (int i = 0; i < this.present.length; i++) {
         this.present[i] = presentTiers.get(i);
      }
      this.cumulative = new double[this.present.length];
      double all = 0.0;
      for (int t : this.present) {
         all += tierWeight[t];
      }
      double run = 0.0;
      for (int c = 0; c < this.present.length; c++) {
         run += tierWeight[this.present[c]];
         this.cumulative[c] = all > 0.0 ? run / all : 1.0;
      }
      if (this.cumulative.length > 0) {
         this.cumulative[this.cumulative.length - 1] = 1.0;
      }

      CityRingField field = null;
      double[] inner = this.cumulative;
      int outer = -1;
      if (PluginConfig.isCityRingsEnabled() && this.present.length > 0) {
         int outerIdx = -1;
         for (int i = 0; i < this.present.length; i++) {
            if (this.present[i] == LADDER.length - 1) {
               outerIdx = i;
            }
         }
         int m = this.present.length - (outerIdx >= 0 ? 1 : 0);
         if (m > 0) {
            double sum = 0.0;
            for (int i = 0; i < this.present.length; i++) {
               if (i != outerIdx) {
                  sum += tierWeight[this.present[i]];
               }
            }
            double[] cum = new double[m];
            double innerRun = 0.0;
            int j = 0;
            for (int i = 0; i < this.present.length; i++) {
               if (i != outerIdx) {
                  innerRun += tierWeight[this.present[i]];
                  cum[j++] = sum > 0.0 ? innerRun / sum : 1.0;
               }
            }
            cum[m - 1] = 1.0;
            inner = cum;
            outer = outerIdx;
            field = new CityRingField(noise.getWorldSeed());
         }
      }
      this.rings = field;
      this.innerCumulative = inner;
      this.outerRung = outer;
   }

   public boolean isUsable() {
      return this.present.length > 0;
   }

   public static int tierOf(ContextType type) {
      for (int t = 0; t < LADDER.length; t++) {
         for (ContextType c : LADDER[t]) {
            if (c == type) {
               return t;
            }
         }
      }
      return -1;
   }

   private static void flatten(ContextZone[] zones, double scale, Map<ContextType, Double> out, Set<ContextType> warned) {
      if (zones == null) {
         return;
      }
      double sum = 0.0;
      for (ContextZone z : zones) {
         sum += Math.max(0.0, z.getWeight());
      }
      if (sum <= 0.0) {
         return;
      }
      for (ContextZone z : zones) {
         double share = scale * Math.max(0.0, z.getWeight()) / sum;
         if (share <= 0.0) {
            continue;
         }
         if (z.hasChildren()) {
            flatten(z.getContextZones(), share, out, warned);
            continue;
         }
         ContextType type = z.getContextType();
         if (type == null) {
            continue;
         }
         if (type == ContextType.DENSERESIDENTIAL && !PluginConfig.isDenseResidentialEnabled()) {
            continue;
         }
         if (tierOf(type) < 0) {
            if (warned.add(type)) {
               Minions.w("contexts.json lists " + type + ", which is not on the district neighbor ladder; it is ignored while districtNeighborRules is on.");
            }
            continue;
         }
         Double prev = out.get(type);
         out.put(type, prev == null ? share : prev + share);
      }
   }

   private int bandOf(double u) {
      for (int c = 0; c < this.cumulative.length; c++) {
         if (u <= this.cumulative[c]) {
            return c;
         }
      }
      return this.cumulative.length - 1;
   }

   private static int propagate(List<List<Integer>> adj, int[] lo, int[] hi, ArrayDeque<Integer> stack) {
      int conflicts = 0;
      while (!stack.isEmpty()) {
         int a = stack.pop();
         for (int b : adj.get(a)) {
            int nl = Math.max(lo[b], lo[a] - 1);
            int nh = Math.min(hi[b], hi[a] + 1);
            if (nl > nh) {
               conflicts++;
               continue;
            }
            if (nl != lo[b] || nh != hi[b]) {
               lo[b] = nl;
               hi[b] = nh;
               stack.push(b);
            }
         }
      }
      return conflicts;
   }

   /**
    * @param bx,bz,sx,sz  every district's base and size in chunks (index = district)
    * @param tileMin/Max  the tile's inner chunk range (districts never extend outside it)
    * @param external     rungs already recorded by neighbouring tiles, or null
    */
   public Result assign(int[] bx, int[] bz, int[] sx, int[] sz,
                        int tileMinX, int tileMinZ, int tileMaxX, int tileMaxZ,
                        TierLookup external) {
      return this.assign(bx, bz, sx, sz, tileMinX, tileMinZ, tileMaxX, tileMaxZ, external, null);
   }

   /**
    * @param pinned  per district, a context it must be given (or null for none), or the
    *                whole array null. A pinned district is assigned before every other one
    *                (so its neighbours adapt to it, not the reverse) and takes the pinned
    *                context itself instead of the usual pick among its rung's members. A pin
    *                can still lose to a rung a neighbouring tile already fixed at this seam;
    *                the caller compares the result against what it pinned.
    */
   public Result assign(int[] bx, int[] bz, int[] sx, int[] sz,
                        int tileMinX, int tileMinZ, int tileMaxX, int tileMaxZ,
                        TierLookup external, ContextType[] pinned) {
      final int n = bx.length;
      ContextType[] contexts = new ContextType[n];
      int[] tiers = new int[n];
      if (n == 0 || !this.isUsable()) {
         return new Result(contexts, tiers, 0, 0, 0);
      }
      final int k = this.present.length;

      List<List<Integer>> adj = new ArrayList<List<Integer>>();
      for (int i = 0; i < n; i++) {
         adj.add(new ArrayList<Integer>());
      }
      List<int[]> pairs = new ArrayList<int[]>();
      for (int i = 0; i < n; i++) {
         for (int j = i + 1; j < n; j++) {
            int gx = Math.max(bx[j] - (bx[i] + sx[i]), bx[i] - (bx[j] + sx[j]));
            int gz = Math.max(bz[j] - (bz[i] + sz[i]), bz[i] - (bz[j] + sz[j]));
            int ox = Math.min(bx[i] + sx[i], bx[j] + sx[j]) - Math.max(bx[i], bx[j]);
            int oz = Math.min(bz[i] + sz[i], bz[j] + sz[j]) - Math.max(bz[i], bz[j]);
            boolean touch = (gx >= 0 && gx <= this.roadGap && oz >= 1)
                    || (gz >= 0 && gz <= this.roadGap && ox >= 1);
            if (touch) {
               adj.get(i).add(j);
               adj.get(j).add(i);
               pairs.add(new int[]{i, j});
            }
         }
      }

      final int[] pref = new int[n];
      final double[] conf = new double[n];
      double[] confOut = new double[1];
      for (int i = 0; i < n; i++) {
         pref[i] = this.preferenceAt(bx[i] + sx[i] / 2, bz[i] + sz[i] / 2, confOut);
         conf[i] = confOut[0];
      }

      if (pinned != null) {
         for (int i = 0; i < n; i++) {
            if (pinned[i] == null) {
               continue;
            }
            int pinTier = tierOf(pinned[i]);
            int pinRung = pinTier >= 0 ? this.compress[pinTier] : -1;
            if (pinRung < 0) {
               continue;   // that rung isn't configured: nothing to pin to
            }
            pref[i] = pinRung;
            conf[i] = Double.MAX_VALUE;
         }
      }

      int[] lo = new int[n];
      int[] hi = new int[n];
      Arrays.fill(hi, k - 1);
      int seamConflicts = 0;

      if (this.crossTile && external != null) {
         ArrayDeque<Integer> narrowed = new ArrayDeque<Integer>();
         for (int i = 0; i < n; i++) {
            boolean west = bx[i] <= tileMinX + SEAM_REACH - 1;
            boolean east = bx[i] + sx[i] - 1 >= tileMaxX - SEAM_REACH + 1;
            boolean north = bz[i] <= tileMinZ + SEAM_REACH - 1;
            boolean south = bz[i] + sz[i] - 1 >= tileMaxZ - SEAM_REACH + 1;
            if (!(west || east || north || south)) {
               continue;
            }
            boolean touched = false;
            if (west) {
               for (int z = bz[i]; z < bz[i] + sz[i]; z++) {
                  for (int d = 1; d <= SEAM_LOOKAHEAD; d++) {
                     int t = external.tierAt(bx[i] - d, z);
                     if (t >= 0) {
                        if (this.narrow(lo, hi, i, t)) { touched = true; } else { seamConflicts++; }
                        break;
                     }
                  }
               }
            }
            if (east) {
               for (int z = bz[i]; z < bz[i] + sz[i]; z++) {
                  for (int d = 0; d < SEAM_LOOKAHEAD; d++) {
                     int t = external.tierAt(bx[i] + sx[i] + d, z);
                     if (t >= 0) {
                        if (this.narrow(lo, hi, i, t)) { touched = true; } else { seamConflicts++; }
                        break;
                     }
                  }
               }
            }
            if (north) {
               for (int x = bx[i]; x < bx[i] + sx[i]; x++) {
                  for (int d = 1; d <= SEAM_LOOKAHEAD; d++) {
                     int t = external.tierAt(x, bz[i] - d);
                     if (t >= 0) {
                        if (this.narrow(lo, hi, i, t)) { touched = true; } else { seamConflicts++; }
                        break;
                     }
                  }
               }
            }
            if (south) {
               for (int x = bx[i]; x < bx[i] + sx[i]; x++) {
                  for (int d = 0; d < SEAM_LOOKAHEAD; d++) {
                     int t = external.tierAt(x, bz[i] + sz[i] + d);
                     if (t >= 0) {
                        if (this.narrow(lo, hi, i, t)) { touched = true; } else { seamConflicts++; }
                        break;
                     }
                  }
               }
            }
            if (touched) {
               narrowed.push(i);
            }
         }
         seamConflicts += propagate(adj, lo, hi, narrowed);
      }

      List<Integer> order = new ArrayList<Integer>();
      for (int i = 0; i < n; i++) {
         order.add(i);
      }
      final int[] fbx = bx;
      final int[] fbz = bz;
      Collections.sort(order, new Comparator<Integer>() {
         @Override
         public int compare(Integer a, Integer b) {
            int c = Double.compare(conf[b], conf[a]);
            if (c != 0) return c;
            c = Integer.compare(fbx[a], fbx[b]);
            if (c != 0) return c;
            return Integer.compare(fbz[a], fbz[b]);
         }
      });

      int[] rung = new int[n];
      for (int i : order) {
         int t = Math.max(lo[i], Math.min(hi[i], pref[i]));
         rung[i] = t;
         lo[i] = t;
         hi[i] = t;
         ArrayDeque<Integer> stack = new ArrayDeque<Integer>();
         stack.push(i);
         propagate(adj, lo, hi, stack);
      }

      for (int i = 0; i < n; i++) {
         int tier = this.present[rung[i]];
         tiers[i] = tier;
         ContextType pin = pinned != null ? pinned[i] : null;
         contexts[i] = pin != null && tierOf(pin) == tier
                 ? pin
                 : this.memberFor(tier, bx[i] + sx[i] / 2, bz[i] + sz[i] / 2);
      }

      int violations = 0;
      for (int[] p : pairs) {
         if (Math.abs(rung[p[0]] - rung[p[1]]) > 1) {
            violations++;
         }
      }
      return new Result(contexts, tiers, pairs.size(), violations, seamConflicts);
   }

   /**
    * The rung (index into present[]) a district centred on chunk (cx, cz)
    * WANTS, before any neighbour narrows it, and in confOut[0] how sure that
    * is. This is the preference step of assign(), unchanged, so a caller that
    * only needs the expectation (preferredContext()) gets the same answer.
    */
   private int preferenceAt(int cx, int cz, double[] confOut) {
      if (this.rings != null) {
         // city rings: u = squared distance in city radii, so equal weights mean equal area.
         double d = this.rings.distance(cx, cz);
         double u = d * d;
         int m = this.innerCumulative.length;
         int c;
         double cf;
         if (u <= 1.0 || this.outerRung < 0) {
            c = m - 1;
            for (int b = 0; b < m; b++) {
               if (u <= this.innerCumulative[b]) {
                  c = b;
                  break;
               }
            }
            double below = c > 0 ? u - this.innerCumulative[c - 1] : Double.MAX_VALUE;
            double above = u <= 1.0 ? this.innerCumulative[c] - u : Double.MAX_VALUE;
            cf = Math.min(below, above);
         } else {
            c = this.outerRung;
            cf = Math.min(u - 1.0, 0.3);
         }
         confOut[0] = Math.max(cf, 0.001);
         return c;
      }
      int k = this.present.length;
      double u = (this.noise.getZoneNoise(cx, cz, 1) + 1.0) / 2.0;
      int c = this.bandOf(u);
      double below = c > 0 ? u - this.cumulative[c - 1] : Double.MAX_VALUE;
      double above = c < k - 1 ? this.cumulative[c] - u : Double.MAX_VALUE;
      confOut[0] = Math.min(below, above);
      return c;
   }

   /**
    * The context a district centred on chunk (cx, cz) would be given if no
    * neighbour narrowed it: a pure function of the seed and the position.
    * Null when this assigner has nothing to assign (see isUsable()). Used to
    * decide, before districts are zoned, which zone a landmark is expected
    * to stand in (generator.landmarkZoneMatch).
    */
   public ContextType preferredContext(int cx, int cz) {
      if (!this.isUsable()) {
         return null;
      }
      int rung = this.preferenceAt(cx, cz, new double[1]);
      return this.memberFor(this.present[rung], cx, cz);
   }

   private boolean narrow(int[] lo, int[] hi, int i, int tier) {
      int c = this.compress[tier];
      if (c < 0) {
         return true;
      }
      int nl = Math.max(lo[i], c - 1);
      int nh = Math.min(hi[i], c + 1);
      if (nl > nh) {
         return false;
      }
      lo[i] = nl;
      hi[i] = nh;
      return true;
   }

   private ContextType memberFor(int tier, int cx, int cz) {
      if (this.singleMember[tier] != null) {
         return this.singleMember[tier];
      }
      ContextZone[] zones = this.members[tier];
      if (this.rings != null) {
         ContextType ringPick = this.rings.pickMember(zones, tier, cx, cz);
         if (ringPick != null) {
            return ringPick;
         }
      }
      ContextType picked = this.noise.getContext(zones, cx, cz, 2);
      if (picked == null || picked == ContextType.UNDEFINED || tierOf(picked) != tier) {
         picked = zones[0].getContextType();
      }
      return picked;
   }
}
