package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.grid.urbanGrid.wild.BlockPalette;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.plugin.PluginConfig;
import ch.k42.metropolis.grid.urbanGrid.wild.Palettes;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBlock;
import org.bukkit.Chunk;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Random;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the wilderness holds of the world that ended: light aircraft, turboprops and business jets that came down and ploughed
 * furrows, a helicopter gutted by its crash, survivors' camps gone cold, a mine complex (open pit with a haul road, adits, a
 * head shaft and a deep level, and the plant on the surface), wind farms in rows with the odd turbine on its side,
 * a radio mast; and, on the mesa, weathered rock spires.
 *
 * Placed the way the trees are, so the result does not depend on which chunk generates first: the land is cut into cells, each
 * holds at most one piece whose origin, kind and every block come from the seed and the cell alone, and each chunk draws the share
 * of every piece that reaches into it. Pieces gather in "forgotten places": whole districts hold several close together, others
 * are bare. A piece keeps clear of anything that is not wilderness (streets, blocks, farms, the water's edge) by its own radius,
 * and the big ones only stand on level enough ground. Wrecks, camps and mines carry loot chests named for the plugin's collections.
 *
 * Survival rule: nothing here is built of iron. Concrete, stone, wood and glass only; the odd iron block can turn up in scattered
 * wreckage, and rails run through the mine. Every Random roll of a piece is made up front, never conditionally on the chunk
 * being drawn; anything made later uses Painter.rnd, a pure function of the position.
 */
public final class WildDecor {
   private static final int SMALL_CELL = 26;
   private static final int SMALL_REACH = 6;
   private static final int BIG_CELL = 64;
   private static final int BIG_REACH = 84;
   // Wind installations need their own sparse layer; a 64-block decoration cell
   // cannot contain their 4..6 by 2..3 tower footprint or long road approach.
   // Wind turbine geometry is not a small wilderness prop: separated towers
   // need their own full-diameter rotor clearance and entire installation
   // needs a civil/water setback. Keep the candidate-cell field sparse and
   // query enough neighboring cells to paint the same farm in any order.
   private static final Object NO_WIND_SITE = new Object();
   private static final int MAX_WIND_PLANS = 8192;
   private record WindPlanKey(long scope, long seed, int percent, int spacing, int cx, int cz) {}
   private static final Map<WindPlanKey, Object> WIND_PLANS =
           new LinkedHashMap<WindPlanKey, Object>(512, 0.75f, true) {
              @Override protected boolean removeEldestEntry(Map.Entry<WindPlanKey, Object> eldest) {
                 return size() > MAX_WIND_PLANS;
              }
           };

   private static Object cachedWindPlan(long scope, long seed, int percent, int cx, int cz) {
      WindPlanKey key = new WindPlanKey(scope, seed, percent,
              PluginConfig.getWildSpacing(Prop.WINDFARM.ordinal()), cx, cz);
      synchronized (WIND_PLANS) { return WIND_PLANS.get(key); }
   }

   private static void rememberWindPlan(long scope, long seed, int percent,
                                        int cx, int cz, WindSite site) {
      WindPlanKey key = new WindPlanKey(scope, seed, percent,
              PluginConfig.getWildSpacing(Prop.WINDFARM.ordinal()), cx, cz);
      synchronized (WIND_PLANS) { WIND_PLANS.put(key, site == null ? NO_WIND_SITE : site); }
   }

   private static final int WIND_CELL = 1024;
   // A major farm can extend more than a kilometre from its entry road.
   // Candidate search must cover the entire deterministic turbine layout.
   private static final int WIND_REACH = 1550;
   private static final int WIND_GAP = 650;
   private static final int WIND_CONFLICT_REACH = 3000;
   private static final int WIND_TOWER_SPACING = 112;
   private static final int WIND_ROW_SPACING = 104;
   // Check the real facility footprint instead of requiring a 490-block-wide
   // wilderness disk around its centre. The rotor sweep is 39 blocks; the
   // extra six protect the rotor and tower foundation from neighbouring plans.
   private static final int WIND_ROTOR_CLEARANCE = 45;
   private static final int WIND_SUBSTATION_CLEARANCE = 15;
   private static final int WIND_ROAD_REACH_CHUNKS = 34;
   private static final BlockPalette PAL = Palettes.current();

   /** What the decorations need to know about the land they stand on. */
   public interface Env {
      WildBiome biomeAt(int x, int z);

      /** The block of the mesa's banded clay at height y. */
      WildBlock band(int y);

      /** May a piece stand at this column (inside the wilderness)? */
      boolean allows(int x, int z);

      /** Must the final parcel owner actually invoke the wilderness decorator?
       * A PLAN_REGIONS forest mask alone is insufficient: creek, river, road,
       * and highway owners may have replaced its cell. Do not advertise a
       * turbine or private road in a chunk whose final painter cannot draw it.
       * Standalone procedural environments retain the ordinary allows rule. */
      default boolean windPaintable(int x, int z) { return allows(x, z); }

      /** Blocks from this column to the nearest column that does not allow pieces, Double.MAX_VALUE if none within reachChunks chunks. */
      double edgeDistance(int x, int z, int reachChunks);

      /** Y of the top block of the ground at a column. */
      int groundAt(int x, int z);

      /** The highest ground a mine may be dug into: the low land, not the mesa's plateaus. */
      int lowLimit();

      /** Percent chance a planned chest is really placed, and the loot level range (1..5) of the chests. */
      int chestOdds();

      int lootMin();

      int lootMax();

      /**
       * The spacing of a kind of piece in percent of the default distance between pieces of that kind (100 = default, 200 = twice as
       * far apart, so a quarter as many to the square block, 50 = half the distance). prop is the Prop ordinal.
       */
      int spacing(int prop);

      /** Port-style approach: a straight, checked private dirt connection
       * over exclusively forest plan cells to a surviving planned paved street. Returns
       * {direction 0..3, roadX, roadZ}, or null. No unconnected farms. */
      int[] windPortRoad(int x, int z, int reachChunks);

      /** As above, but only select an approach which leaves room for the
       * service trunk, substation, and at least a short dirt road to the street.
       * Legacy envs can implement only the original overload. */
      default int[] windPortRoad(int x, int z, int reachChunks, int minimumDistanceBlocks) {
         int[] road = windPortRoad(x, z, reachChunks);
         if (road == null) return null;
         int distance = road[0] % 2 == 0 ? Math.abs(road[1] - x) : Math.abs(road[2] - z);
         return distance >= minimumDistanceBlocks ? road : null;
      }

      /** A routed dirt access path. Coordinates are stored in deterministic
       * world space: {orientation,streetX,streetZ,exitX,exitZ,...streetX,streetZ}.
       * Existing standalone/synthetic environments may use a straight road. */
      default int[] windAccessRoute(int x, int z, int reachChunks, int exitBlocks, int orientation) {
         int[] road = windPortRoad(x, z, reachChunks, exitBlocks + 12);
         return road != null && road[0] == orientation ? road : null;
      }

      /** Places a loot chest (facing is the legacy data value 2 north, 3 south, 4 west, 5 east) named for a loot collection and level. */
      void chest(Chunk chunk, int x, int y, int z, int facing, String collection, int level);
   }

   enum Prop {PLANE, HELI, TENTS, MINE, WINDFARM, MAST, BOULDER, HOODOO}

   // Ordinary large sites and crashed aircraft have independent layers.
   // Mixing aircraft into BIG made helicopter/plane locations compete with
   // camps and mines, and erased their original long-distance separation.
   private static final Prop[] BIG = {Prop.TENTS, Prop.MINE, Prop.MAST};
   private static final double[] B_FOREST = {4.0, 0.0, 1.0};
   private static final double[] B_MOUNTAIN = {2.0, 0.0, 3.0};
   private static final double[] B_DESERT = {3.0, 0.0, 0.0};
   private static final double[] B_MESA = {3.0, 5.0, 0.0};
   private static final int AIRCRAFT_CELL = 160;
   private static final int PLANE_GAP = 260;
   private static final int HELI_GAP = 340;

   private static final Prop[] SMALL = {Prop.BOULDER, Prop.HOODOO};
   //                                          boulder hoodoo
   private static final double[] S_FOREST   = {0.8, 0.0};
   private static final double[] S_MOUNTAIN = {1.6, 0.0};
   private static final double[] S_DESERT   = {1.2, 0.0};
   private static final double[] S_MESA     = {1.6, 4.0};

   private static final int[] DX = {1, 0, -1, 0};
   private static final int[] DZ = {0, 1, 0, -1};

   private WildDecor() {
   }

   /** Site planning for a regional windfarm is independent of the forest-region
    * slice currently being populated. The finder already plans from the origin
    * region; use that same context when painting every remotely reached chunk.
    * Null means the origin has no eligible real forest parcel. */
   @FunctionalInterface
   public interface WindOriginEnvironment {
      Env atOrigin(int originX, int originZ);
      /** Zero disables cross-chunk plan reuse for unscoped test environments. */
      default long windPlanningScope() { return 0L; }
   }

   /** Draws this chunk's share of the pieces. percent scales how many there are (100 = the default, 0 = none). */
   public static void populate(Chunk chunk, long seed, Env env, int percent) {
      populate(chunk, seed, env, percent, null);
   }

   public static void populate(Chunk chunk, long seed, Env env, int percent, WindOriginEnvironment windOrigins) {
      if (percent <= 0 || env == null) {
         return;
      }
      Painter p = new Painter(chunk, env, seed);
      layer(p, seed, env, percent, SMALL_CELL, SMALL_REACH, 61, 0.12, false);
      layer(p, seed, env, percent, BIG_CELL, BIG_REACH, 71, 0.044, true);
      windLayer(p, seed, env, percent, windOrigins);
      // These have their own sparse spatial processes, as in the original
      // working wilderness, rather than sharing camp/mine dice rolls.
      aircraftLayer(p, seed, env, percent, Prop.PLANE, 40, PLANE_GAP, 91, 0.12);
      aircraftLayer(p, seed, env, percent, Prop.HELI, 24, HELI_GAP, 101, 0.10);
   }

   /** Crash-site layer that never depends on which adjacent chunk populates first. */
   private static void aircraftLayer(Painter p, long seed, Env env, int percent,
                                     Prop kind, int reach, int defaultGap, int salt, double base) {
      int cell = AIRCRAFT_CELL;
      int cx0 = Math.floorDiv(p.minX - reach, cell);
      int cx1 = Math.floorDiv(p.minX + 15 + reach, cell);
      int cz0 = Math.floorDiv(p.minZ - reach, cell);
      int cz1 = Math.floorDiv(p.minZ + 15 + reach, cell);
      int spacing = Math.max(10, env.spacing(kind.ordinal()));
      int gap = Math.max(120, Math.min(520, (int) Math.round(defaultGap * spacing / 100.0)));
      for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
         long hash = ForestTerrain.hash(seed, cx, cz, salt);
         int ox = cx * cell + (int) ((hash >>> 8) % cell);
         int oz = cz * cell + (int) ((hash >>> 24) % cell);
         if (!aircraftCandidate(seed, env, percent, kind, cx, cz, ox, oz, salt, base)) continue;
         long myRank = ForestTerrain.hash(seed, cx, cz, salt + 4);
         boolean winner = true;
         int range = (gap + cell - 1) / cell + 1;
         for (int dx = -range; dx <= range && winner; dx++) {
            for (int dz = -range; dz <= range; dz++) {
               if (dx == 0 && dz == 0) continue;
               int ncx = cx + dx, ncz = cz + dz;
               long nh = ForestTerrain.hash(seed, ncx, ncz, salt);
               int nx = ncx * cell + (int) ((nh >>> 8) % cell);
               int nz = ncz * cell + (int) ((nh >>> 24) % cell);
               long ddx = (long) ox - nx, ddz = (long) oz - nz;
               if (ddx * ddx + ddz * ddz >= (long) gap * gap) continue;
               if (!aircraftCandidate(seed, env, percent, kind, ncx, ncz, nx, nz, salt, base)) continue;
               long rival = ForestTerrain.hash(seed, ncx, ncz, salt + 4);
               if (Long.compareUnsigned(rival, myRank) > 0 ||
                       (rival == myRank && (ncx < cx || (ncx == cx && ncz < cz)))) {
                  winner = false;
                  break;
               }
            }
         }
         if (!winner || !p.touches(ox, oz, reach)) continue;
         int radius = radius(kind);
         if (env.edgeDistance(ox, oz, 1 + radius / 16) < radius ||
                 !suitable(p, env, kind, ox, oz)) continue;
         build(p, kind, env.biomeAt(ox, oz), ox, oz, new Random(ForestTerrain.hash(seed, cx, cz, salt + 2)));
      }
   }

   private static boolean aircraftCandidate(long seed, Env env, int percent, Prop kind,
                                            int cx, int cz, int ox, int oz, int salt, double base) {
      if (!env.allows(ox, oz)) return false;
      WildBiome biome = env.biomeAt(ox, oz);
      double weight;
      if (kind == Prop.PLANE) {
         weight = biome.is(WildBiome.MOUNTAINS) ? 2.0 :
                  biome.is(WildBiome.MESA) ? 2.0 : 3.0;
      } else {
         weight = biome.is(WildBiome.MOUNTAINS) ? 3.0 : 2.0;
      }
      int spacing = Math.max(10, env.spacing(kind.ordinal()));
      double chance = Math.min(0.95, base * weight * 10000.0 / (spacing * spacing) * percent / 100.0);
      return ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, salt + 1)) < chance;
   }

   private static void layer(Painter p, long seed, Env env, int percent, int cell, int reach, int salt, double base, boolean big) {
      int cellMinX = Math.floorDiv(p.minX - reach, cell);
      int cellMaxX = Math.floorDiv(p.minX + 15 + reach, cell);
      int cellMinZ = Math.floorDiv(p.minZ - reach, cell);
      int cellMaxZ = Math.floorDiv(p.minZ + 15 + reach, cell);
      for (int cx = cellMinX; cx <= cellMaxX; cx++) {
         for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
            long h = ForestTerrain.hash(seed, cx, cz, salt);
            int ox = cx * cell + (int) ((h >>> 8) % cell);
            int oz = cz * cell + (int) ((h >>> 24) % cell);
            if (!env.allows(ox, oz)) {
               continue;
            }
            WildBiome biome = env.biomeAt(ox, oz);
            double[] defaults = weights(biome, big);
            Prop[] kinds = big ? BIG : SMALL;
            // spacing d times the default distance means 1/d^2 as many pieces of that kind: each kind's weight is scaled by that
            double[] w = new double[defaults.length];
            double sum = 0.0;
            for (int i = 0; i < w.length; i++) {
               int spacing = Math.max(10, env.spacing(kinds[i].ordinal()));
               if (kinds[i] == Prop.TENTS) {
                  spacing = WildDecorSpacingPolicy.campSpacing(spacing);
               }
               w[i] = defaults[i] * 10000.0 / ((double) spacing * spacing);
               sum += w[i];
            }
            if (sum <= 0.0) {
               continue;
            }
            double chance = base * sum * zone(seed, ox, oz, big ? 192 : 176) * percent / 100.0;
            if (ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, salt + 1)) >= chance) {
               continue;
            }
            Random r = new Random(ForestTerrain.hash(seed, cx, cz, salt + 2));
            double roll = r.nextDouble() * sum;
            Prop prop = kinds[kinds.length - 1];
            for (int i = 0; i < kinds.length; i++) {
               roll -= w[i];
               if (roll < 0.0) {
                  prop = kinds[i];
                  break;
               }
            }
            // Cross-cell, deterministic exclusion for all three aircraft models.
            // Candidate ranking never depends on chunk generation order.
            if (big && prop == Prop.PLANE && !planeSiteWins(seed, env, percent, cx, cz, ox, oz, h, base, salt)) {
               continue;
            }
            int radius = radius(prop);
            if (!p.touches(ox, oz, radius)) {
               continue;
            }
            if (env.edgeDistance(ox, oz, 1 + radius / 16) < radius || !suitable(p, env, prop, ox, oz)) {
               continue;
            }
            build(p, prop, biome, ox, oz, r);
         }
      }
   }

   /** One row of a stagger-ended installation. A row is always connected to
    * the substation trunk through its own continuous dirt service lane. */
   private static final class WindRow {
      final int j, maxCols;
      int firstI = -1;
      WindRow(int j, int maxCols) { this.j = j; this.maxCols = maxCols; }
   }

   /** One and only one deterministic layout, used by both /windfarm and the
    * chunk painter. Never select optional turbines based on generated blocks. */
   private static final class WindSite {
      final int[] road;
      final Frame frame;
      final ArrayList<WindRow> rows = new ArrayList<>();
      final int exitA = WIND_TOWER_SPACING + 38;
      final int trunkA = WIND_TOWER_SPACING + 16;
      int target, count, heightClass;
      int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
      int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
      WindSite(int[] road, int ox, int oz, int target, int heightClass) {
         this.road = road;
         this.frame = new Frame(ox, oz, road[0]);
         this.target = target;
         this.heightClass = heightClass;
      }
      void addTower(int i, int j) {
         int x = frame.x(i * WIND_TOWER_SPACING, rowCenter(j));
         int z = frame.z(i * WIND_TOWER_SPACING, rowCenter(j));
         minX = Math.min(minX, x - WIND_ROTOR_CLEARANCE);
         maxX = Math.max(maxX, x + WIND_ROTOR_CLEARANCE);
         minZ = Math.min(minZ, z - WIND_ROTOR_CLEARANCE);
         maxZ = Math.max(maxZ, z + WIND_ROTOR_CLEARANCE);
         count++;
      }
   }

   /** Two central rows, with optional contiguous wings that stretch away from
    * the street. Rows may end at different distances: one bad terrain patch
    * stops only its row, NOT the whole wind farm. */
   private static int rowCenter(int j) {
      return (int) Math.round((j - 0.5) * WIND_ROW_SPACING);
   }
   private static int rowLane(int j) {
      return (j <= 0 ? j : j - 1) * WIND_ROW_SPACING;
   }
   private static int rowMaxCols(long seed, int cx, int cz, int j, int maxCols) {
      // Vary the row ends even on uniformly flat land, while preserving
      // >=3 turbines in each accepted row and no isolated interior gaps.
      long noise = ForestTerrain.hash(seed, cx * 23 + j, cz, 89);
      return Math.max(3, maxCols - (int) Long.remainderUnsigned(noise, 3));
   }

   /** Private dirt service roads must stay on committed wilderness. Sample
    * the whole segment rather than treating a good endpoint as a legal route. */
   private static boolean windRoadClear(Env env, Frame f, int a0, int c0, int a1, int c1) {
      int span = Math.max(Math.abs(a1 - a0), Math.abs(c1 - c0));
      int steps = Math.max(1, (span + 5) / 6);
      for (int t = 0; t <= steps; t++) {
         int a = a0 + (a1 - a0) * t / steps;
         int c = c0 + (c1 - c0) * t / steps;
         int x = f.x(a, c), z = f.z(a, c);
         if (!env.allows(x, z) || !env.windPaintable(x, z)) return false;
      }
      return true;
   }

   private static boolean windWorldRouteClear(Env env, int x0, int z0, int x1, int z1,
                                             boolean streetEndpoint) {
      int span = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
      int steps = Math.max(1, (span + 5) / 6);
      for (int t = 0; t <= steps; t++) {
         if (streetEndpoint && t >= steps) break;
         int x = x0 + (x1 - x0) * t / steps;
         int z = z0 + (z1 - z0) * t / steps;
         if (!env.allows(x, z) || !env.windPaintable(x, z)) return false;
      }
      return true;
   }

   private static boolean windTowerClear(Env env, Frame f, int i, int j) {
      int a = i * WIND_TOWER_SPACING, c = rowCenter(j);
      int x = f.x(a, c), z = f.z(a, c);
      return env.allows(x, z) && env.windPaintable(x, z)
              && env.edgeDistance(x, z, 4) >= WIND_ROTOR_CLEARANCE
              && flat(env, x, z, 5, 4)
              && windRoadClear(env, f, a, c + (rowLane(j) > c ? 6 : -6), a, rowLane(j));
   }

   private static WindSite windSite(long seed, Env env, int percent,
                                    int cx, int cz, int ox, int oz) {
      if (!windCandidate(seed, env, percent, cx, cz, ox, oz)
              || !env.windPaintable(ox, oz)
              || !flat(env, ox, oz, 60, 18)) return null;
      Random layout = new Random(ForestTerrain.hash(seed, cx, cz, 83));
      // Keep rare compact installations, mostly 20..40 turbines and a few
      // regional wind plants of 45..80+. This is an aspiration, never a
      // demand that an entire giant rectangle must fit on the landscape.
      int kind = layout.nextInt(100);
      int target, maxRows, maxCols, heightClass;
      if (kind < 15) {
         target = 6 + layout.nextInt(7);
         maxRows = 3; maxCols = 5; heightClass = 0;
      } else if (kind < 80) {
         target = 24 + layout.nextInt(18);
         maxRows = 5; maxCols = 10; heightClass = 1;
      } else {
         target = 55 + layout.nextInt(27);
         maxRows = 7; maxCols = 13; heightClass = 2;
      }
      int exitA = WIND_TOWER_SPACING + 38;
      WindSite site = null;
      Frame f = null;
      // A proper access road may turn through wilderness before meeting a
      // paved street. Try all four valid orientations, checking the six-tower
      // core BEFORE running the bounded route finder. This also allows a
      // candidate to use another side when its nearest road is obstructed.
      int firstDir = (int) (ForestTerrain.hash(seed, cx, cz, 86) & 3L);
      for (int turn = 0; turn < 4; turn++) {
         int dir = (firstDir + turn) & 3;
         Frame trial = new Frame(ox, oz, dir);
         int trunk = WIND_TOWER_SPACING + 16;
         int subX = trial.x(trunk + 9, 16), subZ = trial.z(trunk + 9, 16);
         if (!env.allows(subX, subZ) || !env.windPaintable(subX, subZ)
                 || env.edgeDistance(subX, subZ, 3) < WIND_SUBSTATION_CLEARANCE
                 || !windRoadClear(env, trial, -WIND_TOWER_SPACING - 16, 0, exitA, 0)) continue;
         boolean core = true;
         for (int j = 0; j <= 1 && core; j++) {
            for (int i = -1; i <= 1; i++) {
               if (!windTowerClear(env, trial, i, j)) { core = false; break; }
            }
         }
         if (!core) continue;
         int[] road = env.windAccessRoute(ox, oz, WIND_ROAD_REACH_CHUNKS, exitA, dir);
         if (road == null) continue;
         // Legacy straight routes return three fields. Routed paths include
         // the complete checked polyline after those three metadata values.
         if (road.length == 3) {
            int approachBlocks = dir % 2 == 0 ? Math.abs(road[1] - ox) : Math.abs(road[2] - oz);
            if (!windRoadClear(env, trial, exitA, 0, Math.max(exitA, approachBlocks - 16), 0)) continue;
         } else {
            boolean routeClear = road.length >= 9 && (road.length & 1) == 1;
            for (int k = 5; routeClear && k + 1 < road.length; k += 2) {
               // Ends on the paved street itself, not an owned wilderness cell.
               if (k + 2 < road.length &&
                       !windWorldRouteClear(env, road[k-2], road[k-1], road[k], road[k+1], false)) routeClear = false;
            }
            if (!routeClear) continue;
         }
         site = new WindSite(road, ox, oz, target, heightClass);
         f = site.frame;
         break;
      }
      if (site == null) return null;
      for (int j = 0; j <= 1; j++) {
         site.rows.add(new WindRow(j, rowMaxCols(seed, cx, cz, j, maxCols)));
         for (int i = -1; i <= 1; i++) site.addTower(i, j);
      }
      int trunk = site.trunkA;
      // Grow sideways only where the new lane, trunk branch, and *all* its
      // initial turbine spurs are legal. Either side may fail independently.
      int[] sideOrder = {-1, 2, -2, 3, -3};
      int laneLo = 0, laneHi = 0;
      for (int j : sideOrder) {
         if (site.rows.size() >= maxRows || site.count + 3 > target) break;
         int lane = rowLane(j);
         int nearest = lane < laneLo ? laneLo : lane > laneHi ? laneHi : lane;
         if (!windRoadClear(env, f, trunk, nearest, trunk, lane)
                 || !windRoadClear(env, f, -WIND_TOWER_SPACING - 16, lane, trunk, lane)) continue;
         boolean valid = true;
         for (int i = -1; i <= 1; i++) {
            if (!windTowerClear(env, f, i, j)) { valid = false; break; }
         }
         if (!valid) continue;
         site.rows.add(new WindRow(j, rowMaxCols(seed, cx, cz, j, maxCols)));
         for (int i = -1; i <= 1; i++) site.addTower(i, j);
         laneLo = Math.min(laneLo, lane); laneHi = Math.max(laneHi, lane);
      }
      // Extend each row away from the road in round-robin order. Failed rows
      // do not veto their neighbours; staggered ends make irregular clusters.
      HashSet<Integer> blocked = new HashSet<>();
      while (site.count < target && blocked.size() < site.rows.size()) {
         boolean progress = false;
         for (WindRow row : site.rows) {
            if (site.count >= target) break;
            if (blocked.contains(row.j)) continue;
            int next = row.firstI - 1;
            if (2 - next > row.maxCols || !windTowerClear(env, f, next, row.j)
                    || !windRoadClear(env, f, next * WIND_TOWER_SPACING - 16,
                             rowLane(row.j), row.firstI * WIND_TOWER_SPACING - 16, rowLane(row.j))) {
               blocked.add(row.j);
               continue;
            }
            row.firstI = next;
            site.addTower(next, row.j);
            progress = true;
         }
         if (!progress) break;
      }
      return site;
   }

   /** Placement and the finder share the exact same viable site and layout.
    * Ranking resolves only actual near/overlapping installations, never a
    * rejected phantom candidate. */
   public static boolean isPlannedWindFarm(long seed, Env env, int percent, int cellX, int cellZ, int ox, int oz) {
      return plannedWindSite(seed, env, percent, cellX, cellZ, ox, oz) != null;
   }

   /** Exact tower count for a found site; use in /windfarm diagnostics so a
    * compact site is not mistaken for the intended major regional layout. */
   public static int plannedWindFarmTurbines(long seed, Env env, int percent,
                                            int cellX, int cellZ, int ox, int oz) {
      WindSite site = plannedWindSite(seed, env, percent, cellX, cellZ, ox, oz);
      return site == null ? 0 : site.count;
   }

   /** One guaranteed core turbine and an adjacent ground-level visitor point.
    * The command uses the same accepted geometry as the painter, rather than
    * teleporting to the empty space between two 104-block-spaced rows.
    * Return {originX, originZ, count, towerX, towerZ, plannedGroundY, visitX, visitZ}.
    * No block queries: this is still a deterministic *plan*, not a claim that
    * the world has already been generated with this version of Metropolis. */
   public static int[] plannedWindFarmVisit(long seed, Env env, int percent,
                                            int cellX, int cellZ, int ox, int oz) {
      WindSite site = plannedWindSite(seed, env, percent, cellX, cellZ, ox, oz);
      if (site == null) return null;
      Frame f = site.frame;
      int row = rowCenter(0);
      int tx = f.x(0, row), tz = f.z(0, row);
      return new int[]{ox, oz, site.count, tx, tz, env.groundAt(tx, tz),
              f.x(0, row + 12), f.z(0, row + 12)};
   }

   private static WindSite plannedWindSite(long seed, Env env, int percent,
                                            int cellX, int cellZ, int ox, int oz) {
      WindSite site = windSite(seed, env, percent, cellX, cellZ, ox, oz);
      if (site == null) return null;
      long rank = ForestTerrain.hash(seed, cellX, cellZ, 85);
      int d = (WIND_CONFLICT_REACH + WIND_CELL - 1) / WIND_CELL + 1;
      long reach2 = (long) WIND_CONFLICT_REACH * WIND_CONFLICT_REACH;
      for (int ix = cellX - d; ix <= cellX + d; ix++) for (int iz = cellZ - d; iz <= cellZ + d; iz++) {
         if (ix == cellX && iz == cellZ) continue;
         long nh = ForestTerrain.hash(seed, ix, iz, 81);
         int nx = ix * WIND_CELL + (int) ((nh >>> 8) % WIND_CELL);
         int nz = iz * WIND_CELL + (int) ((nh >>> 24) % WIND_CELL);
         long ddx = (long) nx - ox, ddz = (long) nz - oz;
         if (ddx * ddx + ddz * ddz > reach2) continue;
         long other = ForestTerrain.hash(seed, ix, iz, 85);
         if (Long.compareUnsigned(other, rank) < 0 ||
                 (other == rank && !(ix < cellX || (ix == cellX && iz < cellZ)))) continue;
         if (ForestTerrain.unit(ForestTerrain.hash(seed, ix, iz, 82)) >= 0.85) continue;
         WindSite rival = windSite(seed, env, percent, ix, iz, nx, nz);
         if (rival == null) continue;
         if (ddx * ddx + ddz * ddz < (long) WIND_GAP * WIND_GAP ||
                 !(site.maxX + 110 < rival.minX || rival.maxX + 110 < site.minX ||
                         site.maxZ + 110 < rival.minZ || rival.maxZ + 110 < site.minZ)) return null;
      }
      return site;
   }

   /** The identical candidate-cell origin used by the renderer and the TP finder. */
   public static int[] windCellOrigin(long seed, int cellX, int cellZ) {
      long hash = ForestTerrain.hash(seed, cellX, cellZ, 81);
      return new int[]{cellX * WIND_CELL + (int) ((hash >>> 8) % WIND_CELL),
              cellZ * WIND_CELL + (int) ((hash >>> 24) % WIND_CELL)};
   }

   public static int windCellBlocks() { return WIND_CELL; }

   private static boolean flat(Env env, int ox, int oz, int rad, int maxVar) {
      int lo = env.groundAt(ox, oz), hi = lo;
      final int[][] d = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{-1,1},{1,-1},{-1,-1}};
      for (int[] v : d) {
         int h = env.groundAt(ox + v[0] * rad, oz + v[1] * rad);
         lo = Math.min(lo, h); hi = Math.max(hi, h);
      }
      return hi - lo <= maxVar;
   }

   /** Draw the chunk-local portion of each accepted installation. The farm
    * footprint can be larger than a candidate cell; always search by its
    * maximum geometric reach, not the cell of its substation. */
   private static void windLayer(Painter p, long seed, Env env, int percent, WindOriginEnvironment windOrigins) {
      int minX = Math.floorDiv(p.minX - WIND_REACH, WIND_CELL);
      int maxX = Math.floorDiv(p.minX + 15 + WIND_REACH, WIND_CELL);
      int minZ = Math.floorDiv(p.minZ - WIND_REACH, WIND_CELL);
      int maxZ = Math.floorDiv(p.minZ + 15 + WIND_REACH, WIND_CELL);
      for (int cx = minX; cx <= maxX; cx++) for (int cz = minZ; cz <= maxZ; cz++) {
         // Most cells fail this terrain-independent upper bound. Skip creating
         // a remote tile/context for them.
         if (ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, 82)) >= 0.85) continue;
         int[] origin = windCellOrigin(seed, cx, cz);
         int ox = origin[0], oz = origin[1];
         long scope = windOrigins == null ? 0L : windOrigins.windPlanningScope();
         Object cached = scope == 0L ? null : cachedWindPlan(scope, seed, percent, cx, cz);
         if (cached == NO_WIND_SITE) continue;
         // Reference's terrain-independent chance bound, evaluated before
         // constructing a potentially expensive remote site owner.
         int spacing = Math.max(10, PluginConfig.getWildSpacing(Prop.WINDFARM.ordinal()));
         double chanceUpper = Math.min(0.85, 1.2 * percent / 100.0 /
                 Math.pow(spacing / 100.0, 2.0));
         if (ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, 82)) >= chanceUpper) continue;
         Env siteEnv = windOrigins == null ? env : windOrigins.atOrigin(ox, oz);
         if (siteEnv == null) {
            if (scope != 0L) rememberWindPlan(scope, seed, percent, cx, cz, null);
            continue;
         }
         WindSite site;
         if (cached instanceof WindSite plan) {
            site = plan;
         } else {
            if (!windCandidate(seed, siteEnv, percent, cx, cz, ox, oz)) continue;
            site = plannedWindSite(seed, siteEnv, percent, cx, cz, ox, oz);
            if (scope != 0L) rememberWindPlan(scope, seed, percent, cx, cz, site);
         }
         if (site == null) continue;
         // The painter's region may be another PlanRegions component or even
         // another 64-chunk tile. Never re-plan or measure its tower height
         // from that unrelated local region: use the exact accepted origin
         // site's height, road route and orientation on every chunk.
         Painter sitePainter = windOrigins == null ? p : new Painter(p.chunk, siteEnv, seed);
         windFarm(sitePainter, new Random(ForestTerrain.hash(seed, cx, cz, 83)), site);
         if (windOrigins != null && org.bukkit.Bukkit.getServer() != null) {
            // One diagnostic per actually generated core tower chunk, NOT a
            // flood of messages about planned farm cells elsewhere.
            Frame f = site.frame;
            int tx = f.x(0, rowCenter(0)), tz = f.z(0, rowCenter(0));
            if ((tx >> 4) == p.chunk.getX() && (tz >> 4) == p.chunk.getZ()) {
               int g = siteEnv.groundAt(tx, tz), found = 0;
               int[][] offsets = {{3,0},{-3,0},{0,3},{0,-3}};
               for (int[] o : offsets) {
                  int lx = tx + o[0] - p.minX, lz = tz + o[1] - p.minZ;
                  // A ring arm may belong to a neighbouring chunk. Never
                  // query it here (that would initiate nested generation).
                  if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) continue;
                  for (int dy = 1; dy <= 4; dy++) {
                     Material mat = p.chunk.getBlock(lx, g + dy, lz).getType();
                     if (mat == Material.LIGHT_GRAY_CONCRETE || mat == Material.WHITE_CONCRETE) { found++; break; }
                  }
               }
               if (found < 2 && (tx - p.minX >= 4 && tx - p.minX <= 11)
                       && (tz - p.minZ >= 4 && tz - p.minZ <= 11)) {
                  ch.k42.metropolis.minions.Minions.w("[WIND-PAINT] FAILED tower=(%d,%d,%d) chunk=(%d,%d) cell=(%d,%d) towers=%d reason=foundation-missing-after-paint",
                          tx, g, tz, p.chunk.getX(), p.chunk.getZ(), cx, cz, site.count);
               } else if (found >= 2) {
                  ch.k42.metropolis.minions.Minions.i("[WIND-PAINT] BUILT tower=(%d,%d,%d) chunk=(%d,%d) cell=(%d,%d) turbines=%d foundation=%d",
                          tx, g, tz, p.chunk.getX(), p.chunk.getZ(), cx, cz, site.count, found);
               }
            }
         }
      }
   }

   private static boolean windCandidate(long seed, Env env, int percent, int cx, int cz, int ox, int oz) {
      if (!env.allows(ox, oz)) return false;
      WildBiome biome = env.biomeAt(ox, oz);
      double weight = switch (biome) {
         case MOUNTAINS -> 1.0;
         case DESERT -> 3.0;
         case MESA -> 1.5;
         default -> 1.5;
      };
      double spacing = Math.max(10, env.spacing(Prop.WINDFARM.ordinal())) / 100.0;
      double chance = Math.min(0.85, 0.40 * weight * percent / 100.0 / (spacing * spacing));
      return ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, 82)) < chance;
   }

   /** Guaranteed clearance between crash-site origins, independent of chunk order or aircraft variant. */
   private static final int PLANE_MIN_SEPARATION = 192;

   /**
    * Compare against other *potential* plane cells. This deliberately includes candidates whose
    * terrain might later prove unsuitable: rejecting an extra candidate is safer than permitting
    * chunk-order-dependent overlapping wrecks. Never loads chunks or queries live blocks.
    */
   private static boolean planeSiteWins(long seed, Env env, int percent, int cx, int cz,
                                        int ox, int oz, long ownHash, double base, int salt) {
      final long minSquared = (long) PLANE_MIN_SEPARATION * PLANE_MIN_SEPARATION;
      final int cells = (PLANE_MIN_SEPARATION + BIG_CELL - 1) / BIG_CELL + 1;
      long myRank = ForestTerrain.hash(seed, cx, cz, salt + 73);
      for (int x = cx - cells; x <= cx + cells; x++) {
         for (int z = cz - cells; z <= cz + cells; z++) {
            if (x == cx && z == cz) continue;
            long h = ForestTerrain.hash(seed, x, z, salt);
            int nx = x * BIG_CELL + (int) ((h >>> 8) % BIG_CELL);
            int nz = z * BIG_CELL + (int) ((h >>> 24) % BIG_CELL);
            long dx = (long) nx - ox, dz = (long) nz - oz;
            if (dx * dx + dz * dz >= minSquared || !env.allows(nx, nz)) continue;
            double[] defaults = weights(env.biomeAt(nx, nz), true);
            double[] weights = new double[defaults.length];
            double sum = 0.0;
            for (int i = 0; i < defaults.length; i++) {
               int spacing = Math.max(10, env.spacing(BIG[i].ordinal()));
               weights[i] = defaults[i] * 10000.0 / ((double) spacing * spacing);
               sum += weights[i];
            }
            if (sum <= 0.0) continue;
            double chance = base * sum * zone(seed, nx, nz, 192) * percent / 100.0;
            if (ForestTerrain.unit(ForestTerrain.hash(seed, x, z, salt + 1)) >= chance) continue;
            Random random = new Random(ForestTerrain.hash(seed, x, z, salt + 2));
            double roll = random.nextDouble() * sum;
            Prop kind = BIG[BIG.length - 1];
            for (int i = 0; i < BIG.length; i++) {
               roll -= weights[i];
               if (roll < 0.0) { kind = BIG[i]; break; }
            }
            if (kind != Prop.PLANE) continue;
            long otherRank = ForestTerrain.hash(seed, x, z, salt + 73);
            if (Long.compareUnsigned(otherRank, myRank) < 0 ||
                (otherRank == myRank && (x < cx || (x == cx && z < cz)))) return false;
         }
      }
      return true;
   }

   private static double[] weights(WildBiome biome, boolean big) {
      switch (biome) {
         case MOUNTAINS:
            return big ? B_MOUNTAIN : S_MOUNTAIN;
         case DESERT:
            return big ? B_DESERT : S_DESERT;
         case MESA:
            return big ? B_MESA : S_MESA;
         default:
            return big ? B_FOREST : S_FOREST;
      }
   }

   /** Whole districts are forgotten places with several pieces close together; others are nearly bare. */
   private static double zone(long seed, int x, int z, int size) {
      double u = ForestTerrain.unit(ForestTerrain.hash(seed, Math.floorDiv(x, size), Math.floorDiv(z, size), 60));
      return u < 0.22 ? 2.4 : (u < 0.55 ? 0.9 : 0.35);
   }

   /** The furthest a piece reaches from its origin, which is also the clearance it keeps from the edge of the wilderness. */
   static int radius(Prop prop) {
      switch (prop) {
         case PLANE:
            return 34;
         case HELI:
            return 18;
         case TENTS:
            return 12;
         case MINE:
            return 50;
         case WINDFARM:
            return 150;
         case MAST:
            return 10;
         default:
            return 4;
      }
   }

   /** Is the ground right for this piece: level enough, and for a mine low enough. */
   private static boolean suitable(Painter p, Env env, Prop prop, int ox, int oz) {
      switch (prop) {
         case PLANE:
            return flat(p, ox, oz, 9, 6) && flat(p, ox, oz, 4, 3);
         case HELI:
            return flat(p, ox, oz, 7, 4);
         case TENTS:
            return flat(p, ox, oz, 6, 4);
         case MAST:
            return flat(p, ox, oz, 4, 4);
         case MINE:
            return p.ground(ox, oz) <= env.lowLimit() && flat(p, ox, oz, 16, 7);
         case WINDFARM:
            return flat(p, ox, oz, 60, 18);
         default:
            return true;
      }
   }

   /** The ground at a centre and eight points around it varies by no more than maxVar blocks. */
   private static boolean flat(Painter p, int ox, int oz, int rad, int maxVar) {
      int lo = p.ground(ox, oz);
      int hi = lo;
      final int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
      for (int[] v : d) {
         int g = p.ground(ox + v[0] * rad, oz + v[1] * rad);
         lo = Math.min(lo, g);
         hi = Math.max(hi, g);
      }
      return hi - lo <= maxVar;
   }

   static void build(Painter p, Prop prop, WildBiome biome, int ox, int oz, Random r) {
      switch (prop) {
         case PLANE:
            planeCrash(p, biome, r, ox, oz);
            break;
         case HELI:
            heliCrash(p, r, ox, oz);
            break;
         case TENTS:
            tentCamp(p, r, ox, oz);
            break;
         case MINE:
            mine(p, r, ox, oz);
            break;
         case WINDFARM:
            // Planned by its independent large-site field, never this small-prop painter.
            break;
         case MAST:
            mast(p, r, ox, oz);
            break;
         case HOODOO:
            hoodoo(p, r, ox, oz);
            break;
         default:
            boulder(p, biome, r, ox, oz);
            break;
      }
   }

   // ------------------------------------------------------------------
   // Shared bits
   // ------------------------------------------------------------------

   /** A frame of reference for a piece: a runs forward, c to the side, rotated by dir (0 = +x, 1 = +z, 2 = -x, 3 = -z). */
   static final class Frame {
      final int ox, oz, dir;

      Frame(int ox, int oz, int dir) {
         this.ox = ox;
         this.oz = oz;
         this.dir = dir & 3;
      }

      int x(int a, int c) {
         switch (this.dir) {
            case 0:
               return this.ox + a;
            case 1:
               return this.ox - c;
            case 2:
               return this.ox - a;
            default:
               return this.ox + c;
         }
      }

      int z(int a, int c) {
         switch (this.dir) {
            case 0:
               return this.oz + c;
            case 1:
               return this.oz + a;
            case 2:
               return this.oz - c;
            default:
               return this.oz - a;
         }
      }
   }

   private static final WildBlock[] OAK_STAIRS = {WildBlock.OAK_STAIRS_E, WildBlock.OAK_STAIRS_W, WildBlock.OAK_STAIRS_S, WildBlock.OAK_STAIRS_N};
   private static final WildBlock[] DARK_STAIRS = {WildBlock.DARK_OAK_STAIRS_E, WildBlock.DARK_OAK_STAIRS_W, WildBlock.DARK_OAK_STAIRS_S, WildBlock.DARK_OAK_STAIRS_N};
   /** For a frame turned dir, the stair data (0 ascends east, 1 west, 2 south, 3 north) whose back is toward -a, so the seat faces +a. */
   private static final int[] SEAT = {1, 3, 0, 2};
   /** The ladder that faces +c in a frame turned dir (it hangs on the wall at -c). */
   private static final WildBlock[] LADDER = {WildBlock.LADDER_S, WildBlock.LADDER_W, WildBlock.LADDER_N, WildBlock.LADDER_E};

   /** A wooden seat facing forward (+a). */
   private static WildBlock seat(boolean dark, int dir) {
      return (dark ? DARK_STAIRS : OAK_STAIRS)[SEAT[dir & 3]];
   }

   /** Burnt ground: black concrete, gravel, grey concrete; coal blocks only very rarely. One Random call. */
   private static WildBlock scorch(Random r) {
      int k = r.nextInt(50);
      return k < 28 ? WildBlock.CONC_BLACK : k < 40 ? WildBlock.GRAVEL : k < 49 ? WildBlock.CONC_GRAY : WildBlock.COAL_BLOCK;
   }

   /** Scattered wreckage. An iron block is one in a hundred pieces. One Random call. */
   private static WildBlock wreckage(Random r) {
      int k = r.nextInt(100);
      return k < 30 ? WildBlock.CONC_WHITE : k < 55 ? WildBlock.CONC_LIGHT_GRAY : k < 75 ? WildBlock.CONC_GRAY
              : k < 87 ? WildBlock.CONC_BLACK : k < 99 ? WildBlock.COBBLE_WALL : WildBlock.IRON_BLOCK;
   }

   /** What a plough of wreckage throws up at the side of its furrow. */
   private static WildBlock berm(WildBiome biome) {
      return biome == WildBiome.FOREST ? WildBlock.COARSE_DIRT : WildBlock.GRAVEL;
   }

   /** A box of blocks in a frame, each cell lost with dmg percent. y is absolute. */
   private static void box(Painter p, Random r, Frame f, int a0, int a1, int c0, int c1, int y0, int y1, WildBlock m, int dmg) {
      for (int a = a0; a <= a1; a++) {
         for (int c = c0; c <= c1; c++) {
            for (int y = y0; y <= y1; y++) {
               int roll = r.nextInt(100);
               if (roll >= dmg) {
                  p.put(f.x(a, c), y, f.z(a, c), m);
               }
            }
         }
      }
   }

   /** Digs a box of a frame out of the ground, everything but bedrock. Uses no Random. */
   private static void carve(Painter p, Frame f, int a0, int a1, int c0, int c1, int y0, int y1) {
      for (int a = a0; a <= a1; a++) {
         for (int c = c0; c <= c1; c++) {
            int x = f.x(a, c);
            int z = f.z(a, c);
            if (!p.inXZ(x, z)) {
               continue;
            }
            for (int y = y0; y <= y1; y++) {
               p.air(x, y, z);
            }
         }
      }
   }

   /** Burnt patches round a wreck. */
   private static void scorchPatches(Painter p, Random r, Frame f, int a0, int a1, int cMax, int density) {
      for (int a = a0; a <= a1; a++) {
         for (int c = -cMax; c <= cMax; c++) {
            int roll = r.nextInt(100);
            WildBlock s = scorch(r);
            if (roll < density - Math.max(0, Math.abs(c) - 3) * 3) {
               p.floor(f.x(a, c), f.z(a, c), s);
            }
         }
      }
   }

   /** Wreckage thrown across the ground. */
   private static void scatter(Painter p, Random r, Frame f, int count, int a0, int a1, int cMax) {
      for (int i = 0; i < count; i++) {
         int a = a0 + r.nextInt(a1 - a0 + 1);
         int c = -cMax + r.nextInt(2 * cMax + 1);
         WildBlock m = wreckage(r);
         int h = 1 + r.nextInt(2);
         int x = f.x(a, c);
         int z = f.z(a, c);
         for (int k = 1; k <= h; k++) {
            p.put(x, p.ground(x, z) + k, z, m);
         }
      }
   }

   /** The furrow an aircraft ploughed: five wide, one deep, scorched, a ridge thrown up along each side. */
   private static void furrow(Painter p, Random r, Frame f, WildBiome biome, int aFrom, int aTo) {
      for (int a = aFrom; a <= aTo; a++) {
         for (int c = -3; c <= 3; c++) {
            int x = f.x(a, c);
            int z = f.z(a, c);
            int g = p.ground(x, z);
            WildBlock burnt = scorch(r);
            int rough = r.nextInt(3);
            int ridge = r.nextInt(5);
            if (Math.abs(c) <= 2 && !(Math.abs(c) == 2 && rough == 0)) {
               p.air(x, g + 1, z);
               p.air(x, g, z);
               p.set(x, g - 1, z, burnt);
            } else if (Math.abs(c) == 3 && ridge < 3) {
               p.put(x, g + 1, z, berm(biome));
            }
         }
      }
   }

   // ------------------------------------------------------------------
   // Aircraft
   // ------------------------------------------------------------------

   private static final class Livery {
      final WildBlock body, trim;

      Livery(WildBlock body, WildBlock trim) {
         this.body = body;
         this.trim = trim;
      }
   }

   private static Livery livery(Random r) {
      WildBlock[] bodies = {WildBlock.CONC_WHITE, WildBlock.CONC_WHITE, WildBlock.CONC_LIGHT_GRAY, WildBlock.CONC_YELLOW, WildBlock.CONC_ORANGE};
      WildBlock[] trims = {WildBlock.CONC_BLUE, WildBlock.CONC_RED, WildBlock.CONC_BLACK, WildBlock.CONC_GREEN};
      WildBlock body = bodies[r.nextInt(bodies.length)];
      WildBlock trim = trims[r.nextInt(trims.length)];
      return new Livery(body, trim);
   }

   /**
    * A fuselage: a ring of concrete, floor and roof half-1 either side of the centre, the sides at +-half, a stripe at y2, a row of
    * windows at y3. Both ends are battered; tearSide 1/-1 tears the side wall out over tearLen blocks from tearA, 2 tears both sides.
    */
   private static void fuselage(Painter p, Random r, Frame f, int base, int a0, int a1, int half, int hgt, Livery lv, int tearSide, int tearA, int tearLen) {
      for (int a = a0; a <= a1; a++) {
         int end = Math.min(a - a0, a1 - a);
         int damage = end < 3 ? 45 - end * 12 : 5;
         for (int y = 1; y <= hgt; y++) {
            boolean cap = y == 1 || y == hgt;
            int lim = cap ? half - 1 : half;
            for (int c = -lim; c <= lim; c++) {
               if (!cap && Math.abs(c) != half) {
                  continue;
               }
               int roll = r.nextInt(100);
               int soot = r.nextInt(100);
               int sootKind = r.nextInt(2);
               boolean torn = !cap && tearSide != 0 && (tearSide == 2 ? Math.abs(c) == half : c == tearSide * half) && a >= tearA && a < tearA + tearLen;
               if (torn || roll < damage) {
                  continue;
               }
               WildBlock m;
               if (y == 1) {
                  m = WildBlock.CONC_LIGHT_GRAY;
               } else if (y == hgt) {
                  m = c == 0 ? lv.trim : lv.body;
               } else if (y == 3) {
                  m = (a & 1) == 0 ? WildBlock.GLASS_PANE : lv.body;
               } else if (y == 2) {
                  m = lv.trim;
               } else {
                  m = lv.body;
               }
               if (soot < 12 + (end < 3 ? 25 : 0)) {
                  m = sootKind == 0 ? WildBlock.CONC_BLACK : WildBlock.CONC_GRAY;
               }
               p.put(f.x(a, c), base + y, f.z(a, c), m);
            }
         }
      }
   }

   /** A crushed nose: the cockpit screen, its glass half gone, and the folded cowling in front of it. */
   private static void nose(Painter p, Random r, Frame f, int base, int aStart, int half, int hgt, int len) {
      for (int y = 2; y <= hgt - 1; y++) {
         for (int c = -(half - 1); c <= half - 1; c++) {
            if (r.nextInt(10) < 5) {
               p.put(f.x(aStart, c), base + y, f.z(aStart, c), WildBlock.GLASS);
            }
         }
      }
      for (int k = 1; k <= len; k++) {
         for (int c = -(half - 1); c <= half - 1; c++) {
            for (int y = 1; y <= hgt - 1; y++) {
               int roll = r.nextInt(100);
               int kind = r.nextInt(4);
               WildBlock m = kind == 0 ? WildBlock.CONC_BLACK : kind == 1 ? WildBlock.CONC_LIGHT_GRAY : WildBlock.CONC_GRAY;
               if (roll < 70 - (k - 1) * 22 - (y - 1) * 9) {
                  p.put(f.x(aStart + k, c), base + y, f.z(aStart + k, c), m);
               }
            }
         }
      }
   }

   /** Rows of wooden seats either side of an aisle, from a0 to a1, one row every two blocks; some are gone. */
   private static void seats(Painter p, Random r, Frame f, int base, int a0, int a1, boolean dark) {
      for (int a = a0; a <= a1; a += 2) {
         for (int c = -1; c <= 1; c += 2) {
            if (r.nextInt(10) < 7) {
               p.put(f.x(a, c), base + 2, f.z(a, c), seat(dark, f.dir));
            }
         }
      }
   }

   /** One wing from the fuselage side out: tapering, swept back, drooping at the tip; a torn wing is a stub. */
   private static void wing(Painter p, Random r, Frame f, int base, int y, int rootA, int chord, int span, double sweep, int side,
                            boolean intact, boolean droop, WildBlock mat, WildBlock edge) {
      int len = intact ? span : 2 + r.nextInt(2);
      for (int i = 0; i < len; i++) {
         int c = 3 + i;
         int w = Math.max(2, chord - (chord - 2) * i / Math.max(1, span - 1));
         int shift = (int) Math.round(-sweep * i);
         int yy = (droop && i >= span - 3) ? y - 1 : y;
         for (int k = 0; k < w; k++) {
            int a = rootA + shift - w / 2 + k;
            int roll = r.nextInt(100);
            if (roll < (intact ? 6 : 25)) {
               continue;
            }
            p.put(f.x(a, side * c), base + yy, f.z(a, side * c), (k == w - 1 && (i & 1) == 0) ? edge : mat);
         }
      }
   }

   /** A propeller seen end on: a hub and two black blades crossing, a few blades gone or bent. */
   private static void propeller(Painter p, Random r, Frame f, int a, int c, int yc, int len, boolean bent) {
      p.put(f.x(a, c), yc, f.z(a, c), WildBlock.CONC_GRAY);
      for (int k = 1; k <= len; k++) {
         for (int dir = 0; dir < 4; dir++) {
            int roll = r.nextInt(100);
            int dc = dir == 0 ? k : dir == 1 ? -k : 0;
            int dy = dir == 2 ? k : dir == 3 ? -k : 0;
            if (roll < 14 || (bent && dir == 3 && k > 1)) {
               continue;
            }
            p.put(f.x(a, c + dc), yc + dy, f.z(a, c + dc), WildBlock.CONC_BLACK);
         }
      }
   }

   /**
    * The tail section, torn off and lying in the furrow: a tube three wide, fin up, and either a tailplane or a T-tail across the
    * top of the fin. brokenSide is the side whose tailplane is gone.
    */
   private static void tailSection(Painter p, Random r, Frame f, int tg, int a0, int a1, int hgt, Livery lv, int finH, boolean tTail, int span, int brokenSide) {
      for (int a = a0; a <= a1; a++) {
         for (int y = 1; y <= hgt; y++) {
            for (int c = -1; c <= 1; c++) {
               boolean cap = y == 1 || y == hgt;
               if (cap ? c != 0 : Math.abs(c) != 1) {
                  continue;
               }
               int roll = r.nextInt(100);
               if (roll < (a >= a1 - 1 ? 40 : 6)) {
                  continue;
               }
               p.put(f.x(a, c), tg + y, f.z(a, c), y == hgt ? lv.trim : lv.body);
            }
         }
      }
      for (int a = a0; a <= a0 + 1; a++) {
         for (int y = hgt + 1; y <= hgt + finH; y++) {
            if (r.nextInt(100) >= 10) {
               p.put(f.x(a, 0), tg + y, f.z(a, 0), y == hgt + finH ? lv.trim : lv.body);
            }
         }
         int yt = tTail ? tg + hgt + finH + 1 : tg + hgt - 1;
         for (int c = 2; c <= span; c++) {
            for (int side = -1; side <= 1; side += 2) {
               if (r.nextInt(100) >= (side == brokenSide ? 55 : 15)) {
                  p.put(f.x(a, side * c), yt, f.z(a, side * c), WildBlock.CONC_LIGHT_GRAY);
               }
            }
         }
      }
   }

   /** Wheels, and the struts that carried them. */
   private static void gear(Painter p, Random r, Frame f, int base, int a, int c) {
      for (int side = -1; side <= 1; side += 2) {
         int roll = r.nextInt(100);
         if (roll >= 15) {
            p.put(f.x(a, side * c), base + 1, f.z(a, side * c), WildBlock.CONC_BLACK);
            p.put(f.x(a, side * (c - 1)), base + 1, f.z(a, side * (c - 1)), WildBlock.COBBLE_WALL);
         }
      }
   }

   /**
    * An aircraft that came down: a light single-engine plane, a twin turboprop or a business jet, chosen by the piece's roll. Each is a
    * furrow ploughed through the ground, the fuselage split open and scorched at the end of it, one wing sheared off, the tail left
    * behind in the furrow, wooden seats, and cargo chests; wreckage is scattered the whole way.
    */
   private static void planeCrash(Painter p, WildBiome biome, Random r, int ox, int oz) {
      int kind = r.nextInt(100);
      if (kind < 42) {
         lightPlane(p, biome, r, ox, oz);
      } else if (kind < 72) {
         turboprop(p, biome, r, ox, oz);
      } else {
         businessJet(p, biome, r, ox, oz);
      }
   }

   private static void lightPlane(Painter p, WildBiome biome, Random r, int ox, int oz) {
      Frame f = new Frame(ox, oz, r.nextInt(4));
      int base = p.ground(ox, oz);
      Livery lv = livery(r);
      int wingSide = r.nextBoolean() ? 1 : -1;
      int tearSide = r.nextBoolean() ? 1 : -1;
      int tearA = -2 + r.nextInt(3);
      boolean bent = r.nextBoolean();
      furrow(p, r, f, biome, -18, -5);
      fuselage(p, r, f, base, -4, 3, 2, 4, lv, tearSide, tearA, 3);
      nose(p, r, f, base, 4, 2, 4, 2);
      propeller(p, r, f, 7, 0, base + 3, 2, bent);
      seats(p, r, f, base, -1, 1, false);
      p.chest(f.x(-3, 0), base + 2, f.z(-3, 0), f.dir, "residential", 1);
      for (int side = -1; side <= 1; side += 2) {
         wing(p, r, f, base, 4, 0, 3, 8, 0.0, side, side == wingSide, false, lv.body, lv.trim);
      }
      gear(p, r, f, base, 2, 3);
      int tg = p.ground(f.x(-11, 0), f.z(-11, 0)) - 1;
      tailSection(p, r, f, tg, -14, -8, 3, lv, 3, false, 4, wingSide);
      scorchPatches(p, r, f, -7, 8, 6, 22);
      scatter(p, r, f, 12, -17, 9, 8);
   }

   private static void turboprop(Painter p, WildBiome biome, Random r, int ox, int oz) {
      Frame f = new Frame(ox, oz, r.nextInt(4));
      int base = p.ground(ox, oz);
      Livery lv = livery(r);
      int wingSide = r.nextBoolean() ? 1 : -1;
      int tearSide = r.nextBoolean() ? 1 : -1;
      int tearA = -4 + r.nextInt(6);
      furrow(p, r, f, biome, -26, -7);
      fuselage(p, r, f, base, -7, 6, 2, 5, lv, tearSide, tearA, 5);
      nose(p, r, f, base, 7, 2, 5, 3);
      seats(p, r, f, base, -5, 3, false);
      p.chest(f.x(-6, 0), base + 2, f.z(-6, 0), f.dir, "residential", 1);
      p.chest(f.x(4, 0), base + 2, f.z(4, 0), f.dir, "residential", 2);
      for (int side = -1; side <= 1; side += 2) {
         boolean intact = side == wingSide;
         wing(p, r, f, base, 2, 0, 5, 10, 0.0, side, intact, true, lv.body, lv.trim);
         if (intact) {
            box(p, r, f, -1, 3, side * 6, side * 6, base + 3, base + 4, lv.body, 6);
            box(p, r, f, 4, 4, side * 6, side * 6, base + 3, base + 4, WildBlock.CONC_GRAY, 0);
            propeller(p, r, f, 5, side * 6, base + 4, 2, r.nextBoolean());
         } else {
            box(p, r, f, 0, 2, side * 6, side * 6, base, base, WildBlock.CONC_GRAY, 30);
         }
      }
      gear(p, r, f, base, 1, 4);
      int tg = p.ground(f.x(-15, 0), f.z(-15, 0)) - 1;
      tailSection(p, r, f, tg, -17, -10, 4, lv, 5, true, 4, wingSide);
      scorchPatches(p, r, f, -8, 10, 7, 22);
      scatter(p, r, f, 16, -24, 10, 10);
   }

   private static void businessJet(Painter p, WildBiome biome, Random r, int ox, int oz) {
      Frame f = new Frame(ox, oz, r.nextInt(4));
      int base = p.ground(ox, oz);
      Livery lv = livery(r);
      int wingSide = r.nextBoolean() ? 1 : -1;
      int tearSide = r.nextBoolean() ? 1 : -1;
      int tearA = -5 + r.nextInt(7);
      furrow(p, r, f, biome, -30, -9);
      fuselage(p, r, f, base, -9, 8, 2, 5, lv, tearSide, tearA, 6);
      nose(p, r, f, base, 9, 2, 5, 3);
      seats(p, r, f, base, -6, 4, true);
      p.chest(f.x(-8, 0), base + 2, f.z(-8, 0), f.dir, "residential", 1);
      p.chest(f.x(5, 0), base + 2, f.z(5, 0), f.dir, "residential", 2);
      for (int side = -1; side <= 1; side += 2) {
         boolean intact = side == wingSide;
         wing(p, r, f, base, 2, -1, 6, 9, 0.7, side, intact, true, lv.body, lv.trim);
         // the engine pod on the rear fuselage: grey casing, black intake
         box(p, r, f, -9, -5, side * 3, side * 3, base + 3, base + 4, WildBlock.CONC_GRAY, intact ? 8 : 45);
         box(p, r, f, -9, -5, side * 4, side * 4, base + 3, base + 4, WildBlock.CONC_LIGHT_GRAY, intact ? 8 : 45);
         box(p, r, f, -4, -4, side * 3, side * 4, base + 3, base + 4, WildBlock.CONC_BLACK, 10);
      }
      gear(p, r, f, base, 3, 3);
      int tg = p.ground(f.x(-19, 0), f.z(-19, 0)) - 1;
      tailSection(p, r, f, tg, -22, -12, 4, lv, 6, true, 5, wingSide);
      scorchPatches(p, r, f, -9, 12, 7, 22);
      scatter(p, r, f, 20, -28, 12, 11);
   }

   // ------------------------------------------------------------------
   // Helicopter
   // ------------------------------------------------------------------

   /** The world direction of a frame step, as an offset. */
   private static int[] vec(Frame f, int da, int dc) {
      return new int[]{f.x(da, dc) - f.x(0, 0), f.z(da, dc) - f.z(0, 0)};
   }

   /** Lays a blade or a boom section on the ground along a direction. */
   private static void lay(Painter p, int sx, int sz, int vx, int vz, int n, WildBlock m) {
      for (int i = 0; i < n; i++) {
         int x = sx + vx * i;
         int z = sz + vz * i;
         p.put(x, p.ground(x, z) + 1, z, m);
      }
   }

   /**
    * A transport helicopter that came down hard: an olive military airframe, a white medevac one or an orange coastguard one. The cabin
    * is split by its sliding doors, the cockpit canopy is glass and half gone, the engine housing sits on the roof, the tail boom has
    * snapped and sagged to the ground with its fin and rotor, the skids are bent, and of the four rotor blades one is still on
    * the mast, one is snapped, one is gone and one has sagged. Wooden seats and a chest are inside.
    */
   private static void heliCrash(Painter p, Random r, int ox, int oz) {
      Frame f = new Frame(ox, oz, r.nextInt(4));
      int base = p.ground(ox, oz);
      int style = r.nextInt(4);
      boolean military = style < 2;
      Livery lv = military ? new Livery(WildBlock.CONC_GREEN, WildBlock.CONC_BLACK)
              : style == 2 ? new Livery(WildBlock.CONC_WHITE, WildBlock.CONC_RED) : new Livery(WildBlock.CONC_ORANGE, WildBlock.CONC_WHITE);
      String loot = military ? "military" : "residential";
      int broken = r.nextInt(4);
      int lost = (broken + 1 + r.nextInt(3)) & 3;
      int lostSide = r.nextBoolean() ? 1 : -1;
      int skidBent = r.nextBoolean() ? 1 : -1;

      scorchPatches(p, r, f, -9, 9, 9, 55);
      fuselage(p, r, f, base, -4, 3, 2, 4, lv, 2, -1, 3);
      // the cockpit canopy: glass round a nose that narrows
      for (int a = 4; a <= 6; a++) {
         int half = a == 6 ? 1 : 2;
         for (int y = 1; y <= 4; y++) {
            boolean cap = y == 1 || y == 4;
            for (int c = -half; c <= half; c++) {
               if (cap ? Math.abs(c) > half - 1 : Math.abs(c) != half) {
                  continue;
               }
               int roll = r.nextInt(100);
               if (roll < 15) {
                  continue;
               }
               p.put(f.x(a, c), base + y, f.z(a, c), cap ? lv.body : WildBlock.GLASS);
            }
         }
      }
      p.put(f.x(4, -1), base + 2, f.z(4, -1), seat(false, f.dir));
      p.put(f.x(4, 1), base + 2, f.z(4, 1), seat(false, f.dir));
      seats(p, r, f, base, -3, 1, false);
      p.chest(f.x(-3, 0), base + 2, f.z(-3, 0), f.dir, loot, 1);
      // engine housing on the roof, exhaust at its back, the rotor mast above it
      box(p, r, f, -2, 1, -1, 1, base + 5, base + 6, WildBlock.CONC_GRAY, 6);
      box(p, r, f, -3, -3, 0, 0, base + 5, base + 6, WildBlock.CONC_BLACK, 0);
      p.put(ox, base + 7, oz, WildBlock.COBBLE_WALL);
      p.put(ox, base + 8, oz, WildBlock.CONC_GRAY);
      // the tail boom: straight for five blocks, then snapped and sagging to the ground
      for (int a = -5; a >= -9; a--) {
         int roll = r.nextInt(100);
         if (roll >= 8) {
            p.put(f.x(a, 0), base + 3, f.z(a, 0), lv.body);
            p.put(f.x(a, 0), base + 4, f.z(a, 0), lv.trim);
         }
      }
      for (int a = -10; a >= -14; a--) {
         int y = a == -10 ? 3 : a >= -12 ? 2 : 1;
         if (r.nextInt(100) >= 8) {
            p.put(f.x(a, 1), base + y, f.z(a, 1), lv.body);
         }
      }
      for (int y = 2; y <= 6; y++) {
         p.put(f.x(-14, 1), base + y, f.z(-14, 1), lv.trim);
      }
      for (int y = 3; y <= 5; y++) {
         p.put(f.x(-14, 2), base + y, f.z(-14, 2), WildBlock.CONC_BLACK);
      }
      box(p, r, f, -15, -13, 2, 2, base + 4, base + 4, WildBlock.CONC_BLACK, 15);
      // skids, one of them bent, and their struts
      for (int side = -1; side <= 1; side += 2) {
         box(p, r, f, -4, 4, side * 3, side * 3, base + 1, base + 1, WildBlock.COBBLE_WALL, side == skidBent ? 45 : 6);
         p.put(f.x(-2, side * 2), base + 1, f.z(-2, side * 2), WildBlock.COBBLE_WALL);
         p.put(f.x(2, side * 2), base + 1, f.z(2, side * 2), WildBlock.COBBLE_WALL);
      }
      // the main rotor: four blades from the hub, a long way out
      for (int k = 0; k < 4; k++) {
         int da = k == 2 ? 1 : k == 3 ? -1 : 0;
         int dc = k == 0 ? 1 : k == 1 ? -1 : 0;
         int len = k == lost ? 0 : k == broken ? 3 : 9;
         for (int t = 1; t <= len; t++) {
            int y = base + 9 - (t > 5 ? (t - 4) / 2 : 0);
            p.put(f.x(da * t, dc * t), y, f.z(da * t, dc * t), t <= 2 ? WildBlock.CONC_GRAY : WildBlock.CONC_BLACK);
         }
         if (k == broken) {
            int[] v = vec(f, da, dc);
            lay(p, f.x(da * 4, dc * 4) - v[1] * 4 * lostSide, f.z(da * 4, dc * 4) + v[0] * 4 * lostSide, v[0], v[1], 6, WildBlock.CONC_BLACK);
         }
         if (k == lost) {
            int[] v = vec(f, da, dc);
            lay(p, ox + v[0] * 9 - v[1] * 6 * lostSide, oz + v[1] * 9 + v[0] * 6 * lostSide, v[0], v[1], 9, WildBlock.CONC_BLACK);
         }
      }
      scatter(p, r, f, 14, -13, 10, 9);
   }

   // ------------------------------------------------------------------
   // Camp
   // ------------------------------------------------------------------

   /**
    * A survivors' camp, long cold: one to three A-frame tents turned to a ring of fire stones around dead ashes, a flag on a pole,
    * supply crates, bedrolls, a chest in the tents that were not stripped. Some tents have fallen in.
    */
   private static void tentCamp(Painter p, Random r, int ox, int oz) {
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) {
               continue;
            }
            int x = ox + dx;
            int z = oz + dz;
            if (r.nextInt(4) != 0) {
               p.put(x, p.ground(x, z) + 1, z, WildBlock.COBBLESTONE);
            }
         }
      }
      p.floor(ox, oz, WildBlock.CONC_BLACK);
      WildBlock[] colours = {WildBlock.WOOL_GREEN, WildBlock.WOOL_BROWN, WildBlock.WOOL_ORANGE, WildBlock.WOOL_GRAY};
      int count = 1 + r.nextInt(3);
      int first = r.nextInt(4);
      for (int t = 0; t < count; t++) {
         int dir = (first + t * (count == 2 ? 2 : 1)) & 3;
         int tx = ox + DX[dir] * 5;
         int tz = oz + DZ[dir] * 5;
         Frame f = new Frame(tx, tz, dir);
         int base = p.ground(tx, tz);
         WildBlock cloth = colours[r.nextInt(colours.length)];
         boolean trim = r.nextBoolean();
         boolean fallen = r.nextInt(3) == 0;
         for (int a = 0; a <= 4; a++) {
            for (int c = -2; c <= 2; c++) {
               for (int y = 1; y <= 3; y++) {
                  int roll = r.nextInt(100);
                  boolean skin = (y == 1 && Math.abs(c) == 2) || (y == 2 && Math.abs(c) == 1) || (y == 3 && c == 0);
                  boolean back = a == 4 && ((y == 1 && Math.abs(c) <= 1) || (y == 2 && c == 0));
                  if (!skin && !back) {
                     continue;
                  }
                  if (roll < 9 || (fallen && y == 3 && roll < 70)) {
                     continue;
                  }
                  p.put(f.x(a, c), base + y, f.z(a, c), (y == 3 && trim) ? WildBlock.WOOL_WHITE : cloth);
               }
            }
         }
         p.put(f.x(-1, 0), base + 1, f.z(-1, 0), WildBlock.FENCE);
         p.put(f.x(-1, 0), base + 2, f.z(-1, 0), WildBlock.FENCE);
         p.put(f.x(2, 1), base + 1, f.z(2, 1), WildBlock.WOOL_RED);
         p.chest(f.x(3, 0), base + 1, f.z(3, 0), dir, "residential", 10 + t);
         p.put(f.x(1, 3), base + 1, f.z(1, 3), WildBlock.PLANKS);
         if (r.nextBoolean()) {
            p.put(f.x(1, 3), base + 2, f.z(1, 3), WildBlock.PLANKS);
         }
      }
      // a flag on a pole
      int fx = ox + 3 - 6 * r.nextInt(2);
      int fz = oz + 3 - 6 * r.nextInt(2);
      int fg = p.ground(fx, fz);
      for (int y = 1; y <= 4; y++) {
         p.put(fx, fg + y, fz, WildBlock.FENCE);
      }
      p.put(fx + 1, fg + 4, fz, WildBlock.WOOL_RED);
      p.put(fx + 1, fg + 3, fz, WildBlock.WOOL_RED);
   }

   // ------------------------------------------------------------------
   // Mine
   // ------------------------------------------------------------------

   private static final double TWO_PI = Math.PI * 2.0;
   private static final double PHI_MAX = TWO_PI * 1.4;

   private static int cone(double inside) {
      return (int) Math.round(inside * 0.55);
   }

   /**
    * The geometry of an open-cast pit, pure functions of the world position: an irregular rim, benches three blocks high, and a haul road
    * that spirals down the walls from the rim to the bottom, cut four wide into the benches.
    */
   private static final class Pit {
      final int ox, oz, radius, gc;
      final double phi, ang0;

      Pit(int ox, int oz, int radius, int gc, double phi, double ang0) {
         this.ox = ox;
         this.oz = oz;
         this.radius = radius;
         this.gc = gc;
         this.phi = phi;
         this.ang0 = ang0;
      }

      double rim(double ang) {
         return this.radius + 2.0 * Math.sin(3.0 * ang + this.phi) + 1.0 * Math.sin(5.0 * ang + this.phi * 1.7);
      }

      /** Y of the pit's floor block at a column, Integer.MAX_VALUE where the pit does not reach. */
      int floorY(Painter p, int x, int z) {
         double dx = x - this.ox;
         double dz = z - this.oz;
         double dist = Math.sqrt(dx * dx + dz * dz);
         double ang = Math.atan2(dz, dx);
         double rim = this.rim(ang);
         if (dist >= rim) {
            return Integer.MAX_VALUE;
         }
         int depth = Math.max(1, (cone(rim - dist) / 3) * 3);
         double rel = ((ang - this.ang0) % TWO_PI + TWO_PI) % TWO_PI;
         double top = this.radius - 1.5;
         for (int m = 0; m < 2; m++) {
            double ph = rel + TWO_PI * m;
            if (ph > PHI_MAX) {
               break;
            }
            double rr = top - (top - 3.0) * ph / PHI_MAX;
            if (Math.abs(dist - rr) <= 2.3) {
               depth = Math.max(depth, Math.max(1, cone(rim - rr)));
            }
         }
         depth = Math.min(depth, 16);
         int fy = this.gc - depth;
         return fy < p.ground(x, z) ? fy : Integer.MAX_VALUE;
      }

      /** Y of the highest solid block at a column once the pit is dug. */
      int solidTop(Painter p, int x, int z) {
         int f = this.floorY(p, x, z);
         return f == Integer.MAX_VALUE ? p.ground(x, z) : f;
      }
   }

   private static WildBlock railBlock(Frame f, boolean alongA) {
      boolean x = alongA == ((f.dir & 1) == 0);
      return x ? WildBlock.RAIL_X : WildBlock.RAIL_Z;
   }

   /** Rails along a drift, a part of them gone. Uses no Random. */
   private static void rails(Painter p, Frame f, boolean alongA, int from, int to, int fixed, int y) {
      WildBlock rail = railBlock(f, alongA);
      for (int i = from; i <= to; i++) {
         int x = f.x(alongA ? i : fixed, alongA ? fixed : i);
         int z = f.z(alongA ? i : fixed, alongA ? fixed : i);
         if (p.rnd(x, y, z, 13) < 0.6) {
            p.put(x, y, z, rail);
         }
      }
   }

   /** A timber set across a drift that runs along a: two posts and a cap beam. Uses no Random. */
   private static void timberC(Painter p, Frame f, int a, int half, int y0) {
      for (int s = -1; s <= 1; s += 2) {
         int x = f.x(a, s * half);
         int z = f.z(a, s * half);
         for (int y = 1; y <= 3; y++) {
            if (p.rnd(x, y0 + y, z, 11) >= 0.06) {
               p.put(x, y0 + y, z, WildBlock.FENCE);
            }
         }
      }
      for (int c = -half; c <= half; c++) {
         int x = f.x(a, c);
         int z = f.z(a, c);
         if (p.rnd(x, y0 + 4, z, 12) >= 0.10) {
            p.put(x, y0 + 4, z, WildBlock.PLANKS);
         }
      }
   }

   /** A timber set across a drift that runs along c, centred on a0. Uses no Random. */
   private static void timberA(Painter p, Frame f, int a0, int c, int half, int y0) {
      for (int s = -1; s <= 1; s += 2) {
         int x = f.x(a0 + s * half, c);
         int z = f.z(a0 + s * half, c);
         for (int y = 1; y <= 3; y++) {
            if (p.rnd(x, y0 + y, z, 11) >= 0.06) {
               p.put(x, y0 + y, z, WildBlock.FENCE);
            }
         }
      }
      for (int a = a0 - half; a <= a0 + half; a++) {
         int x = f.x(a, c);
         int z = f.z(a, c);
         if (p.rnd(x, y0 + 4, z, 12) >= 0.10) {
            p.put(x, y0 + 4, z, WildBlock.PLANKS);
         }
      }
   }

   /** A fall of rock across a drift, with webs above it. Uses no Random. */
   private static void rubble(Painter p, Frame f, int a0, int a1, int c0, int c1, int y0, int y1) {
      for (int a = a0; a <= a1; a++) {
         for (int c = c0; c <= c1; c++) {
            int x = f.x(a, c);
            int z = f.z(a, c);
            if (!p.inXZ(x, z)) {
               continue;
            }
            for (int y = y0; y <= y1; y++) {
               double k = p.rnd(x, y, z, 14);
               if (k < 0.2) {
                  continue;
               }
               p.set(x, y, z, k < 0.55 ? WildBlock.GRAVEL : k < 0.8 ? WildBlock.COBBLESTONE : WildBlock.COARSE_DIRT);
            }
            for (int y = y1 + 1; y <= y1 + 2; y++) {
               if (p.rnd(x, y, z, 15) < 0.4) {
                  p.put(x, y, z, WildBlock.COBWEB);
               }
            }
         }
      }
   }

   /** A mine dump truck, yellow, parked where it was left: black wheels, a loaded bed, a cab with its windscreen. Uses no Random. */
   private static void truck(Painter p, Frame f, int base) {
      for (int a = 0; a <= 6; a++) {
         for (int c = -2; c <= 2; c++) {
            for (int y = 1; y <= 5; y++) {
               WildBlock m = null;
               boolean wheel = (a == 1 || a == 5) && Math.abs(c) == 2 && y <= 2;
               if (wheel) {
                  m = WildBlock.CONC_BLACK;
               } else if (Math.abs(c) <= 1 && y == 2) {
                  m = WildBlock.CONC_GRAY;
               } else if (a <= 4 && y >= 3 && y <= 4 && (Math.abs(c) == 1 || a == 0)) {
                  m = WildBlock.CONC_YELLOW;
               } else if (a <= 3 && y == 3 && Math.abs(c) <= 0 && a >= 1) {
                  m = WildBlock.GRAVEL;
               } else if (a >= 5 && Math.abs(c) <= 1 && y >= 3 && y <= 5) {
                  m = (a == 6 && y == 4) ? WildBlock.GLASS : (y == 5 && Math.abs(c) <= 1 ? WildBlock.CONC_YELLOW : (Math.abs(c) == 1 || a == 5 ? WildBlock.CONC_YELLOW : null));
               }
               if (m == null) {
                  continue;
               }
               int x = f.x(a, c);
               int z = f.z(a, c);
               if (p.rnd(x, base + y, z, 16) >= 0.08) {
                  p.put(x, base + y, z, m);
               }
            }
         }
      }
   }

   /**
    * A mine, abandoned: an open-cast pit with an irregular rim, benches and a haul road spiralling down its walls; an adit driven from the
    * pit floor into the rock, timbered, with rails, side drifts, a rock fall and a chamber with its chest; a head shaft with a ladder and a
    * deep level 16 blocks lower with its own drifts, collapse and chamber; and on the surface a head frame over the shaft collar, a winding
    * house, ore bins, a parked dump truck and a broken fence. Only the plan is drawn from the Random, everything else from the position.
    */
   private static void mine(Painter p, Random r, int ox, int oz) {
      int radius = 17 + r.nextInt(7);
      double phi = r.nextDouble() * TWO_PI;
      double ang0 = r.nextDouble() * TWO_PI;
      Frame f = new Frame(ox, oz, r.nextInt(4));
      int len1 = 9 + r.nextInt(5);
      int len2 = 10 + r.nextInt(5);
      int collapseAt = 12 + r.nextInt(6);
      int side1 = r.nextBoolean() ? 1 : -1;
      int gc = p.ground(ox, oz);
      Pit pit = new Pit(ox, oz, radius, gc, phi, ang0);
      int bottom = pit.floorY(p, ox, oz);
      if (bottom == Integer.MAX_VALUE) {
         return;
      }

      // 1. the pit: benches and the haul road dug out, a floor of gravel and red sand, ore showing in the walls, a broken fence on the rim
      int reach = radius + 3;
      for (int dx = -reach; dx <= reach; dx++) {
         for (int dz = -reach; dz <= reach; dz++) {
            int x = ox + dx;
            int z = oz + dz;
            if (!p.inXZ(x, z)) {
               continue;
            }
            int g = p.ground(x, z);
            int fy = pit.floorY(p, x, z);
            if (fy != Integer.MAX_VALUE) {
               for (int y = fy + 1; y <= g + 3; y++) {
                  p.air(x, y, z);
               }
               double k = p.rnd(x, fy, z, 3);
               p.set(x, fy, z, k < 0.45 ? WildBlock.GRAVEL : k < 0.75 ? WildBlock.RED_SAND : k < 0.92 ? p.env.band(fy) : WildBlock.COAL_ORE);
               if (fy == bottom && p.rnd(x, 0, z, 4) < 0.2) {
                  p.put(x, fy + 1, z, p.rnd(x, 1, z, 4) < 0.55 ? WildBlock.GRAVEL : WildBlock.COAL_ORE);
               }
            } else {
               double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
               double ang = Math.atan2(dz, dx);
               double rim = pit.rim(ang);
               double da = Math.abs(Math.atan2(Math.sin(ang - ang0), Math.cos(ang - ang0)));
               if (dist >= rim && dist < rim + 1.6 && da > 0.35 && p.rnd(x, 0, z, 5) < 0.35) {
                  p.put(x, g + 1, z, WildBlock.FENCE);
               }
            }
            int top = pit.solidTop(p, x, z);
            for (int n = 0; n < 4; n++) {
               int nf = pit.floorY(p, x + DX[n], z + DZ[n]);
               if (nf == Integer.MAX_VALUE) {
                  continue;
               }
               for (int y = nf + 1; y <= top; y++) {
                  if (p.rnd(x, y, z, 6) < 0.07) {
                     double m = p.rnd(x, y, z, 7);
                     p.replaceSolid(x, y, z, m < 0.55 ? WildBlock.COAL_ORE : m < 0.9 ? WildBlock.IRON_ORE : WildBlock.GOLD_ORE);
                  }
               }
            }
         }
      }

      // 2. the main adit from the pit floor to the head shaft
      int y0 = bottom;
      int aS = radius + 7;
      int y2 = bottom - 16;
      carve(p, f, 4, aS - 2, -2, 2, y0 + 1, y0 + 4);
      for (int a = 8; a <= aS - 4; a += 4) {
         timberC(p, f, a, 2, y0);
      }
      rails(p, f, true, 4, aS - 2, 0, y0 + 1);
      rubble(p, f, collapseAt, collapseAt + 2, -2, 2, y0 + 1, y0 + 2);
      for (int a = 6; a <= aS - 3; a++) {
         for (int s = -1; s <= 1; s += 2) {
            for (int y = y0 + 1; y <= y0 + 3; y++) {
               int x = f.x(a, s * 3);
               int z = f.z(a, s * 3);
               if (p.inXZ(x, z) && p.rnd(x, y, z, 17) < 0.05) {
                  p.replaceSolid(x, y, z, p.rnd(x, y, z, 18) < 0.6 ? WildBlock.COAL_ORE : WildBlock.IRON_ORE);
               }
            }
         }
      }
      // side drift one, ending in a fall; side drift two, ending in a chamber with its chests
      int a1 = 9;
      int a2 = 16;
      int[] sa = {a1, a2};
      int[] sl = {len1, len2};
      int[] ss = {side1, -side1};
      for (int k = 0; k < 2; k++) {
         int side = ss[k];
         int c0 = side > 0 ? 3 : -(2 + sl[k]);
         int c1 = side > 0 ? 2 + sl[k] : -3;
         carve(p, f, sa[k] - 2, sa[k] + 2, c0, c1, y0 + 1, y0 + 4);
         for (int c = 5; c <= 2 + sl[k]; c += 4) {
            timberA(p, f, sa[k], side * c, 2, y0);
         }
         rails(p, f, false, c0, c1, sa[k], y0 + 1);
      }
      rubble(p, f, a1 - 2, a1 + 2, side1 > 0 ? 2 + len1 - 2 : -(2 + len1), side1 > 0 ? 2 + len1 : -(2 + len1) + 2, y0 + 1, y0 + 2);
      int s2 = -side1;
      int ce0 = 3 + len2;
      int ce1 = 3 + len2 + 6;
      carve(p, f, a2 - 4, a2 + 4, s2 > 0 ? ce0 : -ce1, s2 > 0 ? ce1 : -ce0, y0 + 1, y0 + 5);
      int cc = s2 * (3 + len2 + 3);
      p.chest(f.x(a2 - 2, cc), y0 + 1, f.z(a2 - 2, cc), f.dir, "industrial", 40);
      p.chest(f.x(a2 + 2, cc), y0 + 1, f.z(a2 + 2, cc), f.dir, "industrial", 41);
      p.put(f.x(a2, cc), y0 + 1, f.z(a2, cc), WildBlock.PLANKS);
      for (int s = -1; s <= 1; s += 2) {
         for (int t = -1; t <= 1; t += 2) {
            for (int y = 1; y <= 4; y++) {
               p.put(f.x(a2 + s * 4, cc + t * 3), y0 + y, f.z(a2 + s * 4, cc + t * 3), WildBlock.FENCE);
            }
         }
      }

      // 3. the head shaft, three by three, from the deep level to the surface, with a ladder on its wall
      for (int a = aS - 1; a <= aS + 1; a++) {
         for (int c = -1; c <= 1; c++) {
            int x = f.x(a, c);
            int z = f.z(a, c);
            if (!p.inXZ(x, z)) {
               continue;
            }
            int g = p.ground(x, z);
            for (int y = y2 + 1; y <= g + 3; y++) {
               p.air(x, y, z);
            }
         }
      }
      int lx = f.x(aS - 1, -1);
      int lz = f.z(aS - 1, -1);
      if (p.inXZ(lx, lz)) {
         for (int y = y2 + 1; y <= p.ground(lx, lz); y++) {
            p.set(lx, y, lz, LADDER[f.dir]);
         }
      }

      // 4. the deep level: a drift along c with rails and timber, a fall at one end and a chamber with chests at the other
      carve(p, f, aS, aS + 4, -18, 18, y2 + 1, y2 + 4);
      for (int c = -16; c <= 16; c += 4) {
         if (Math.abs(c) > 2) {
            timberA(p, f, aS + 2, c, 2, y2);
         }
      }
      rails(p, f, false, -17, 17, aS + 2, y2 + 1);
      rubble(p, f, aS, aS + 4, -14, -12, y2 + 1, y2 + 3);
      carve(p, f, aS - 2, aS + 6, 19, 27, y2 + 1, y2 + 5);
      p.chest(f.x(aS, 23), y2 + 1, f.z(aS, 23), f.dir, "industrial", 42);
      p.chest(f.x(aS + 4, 23), y2 + 1, f.z(aS + 4, 23), f.dir, "industrial", 43);
      for (int a = aS - 1; a <= aS + 5; a += 2) {
         for (int c = 20; c <= 26; c += 2) {
            if (p.rnd(f.x(a, c), y2 + 1, f.z(a, c), 19) < 0.45) {
               p.put(f.x(a, c), y2 + 1, f.z(a, c), p.rnd(f.x(a, c), y2 + 2, f.z(a, c), 19) < 0.5 ? WildBlock.COAL_ORE : WildBlock.GRAVEL);
            }
         }
      }

      // 5. the surface plant round the shaft collar
      int gs = p.ground(f.x(aS, 0), f.z(aS, 0));
      for (int a = aS - 3; a <= aS + 3; a++) {
         for (int c = -3; c <= 3; c++) {
            boolean ring = Math.abs(a - aS) == 3 || Math.abs(c) == 3;
            int x = f.x(a, c);
            int z = f.z(a, c);
            if (ring && p.rnd(x, 0, z, 20) >= 0.1) {
               p.put(x, p.ground(x, z) + 1, z, WildBlock.CONC_GRAY);
            }
         }
      }
      // the head frame: four black legs, bracing at three levels, the sheave wheels, the rope down the shaft
      for (int s = -1; s <= 1; s += 2) {
         for (int t = -1; t <= 1; t += 2) {
            for (int y = 2; y <= 13; y++) {
               int x = f.x(aS + s * 3, t * 3);
               int z = f.z(aS + s * 3, t * 3);
               if (p.rnd(x, y, z, 21) >= 0.04) {
                  p.put(x, gs + y, z, WildBlock.CONC_BLACK);
               }
            }
         }
      }
      for (int y = 4; y <= 12; y += 4) {
         for (int i = -2; i <= 2; i++) {
            for (int[] e : new int[][]{{aS + i, -3}, {aS + i, 3}, {aS - 3, i}, {aS + 3, i}}) {
               int x = f.x(e[0], e[1]);
               int z = f.z(e[0], e[1]);
               if (p.rnd(x, y, z, 22) >= 0.12) {
                  p.put(x, gs + y, z, WildBlock.CONC_GRAY);
               }
            }
         }
      }
      for (int c = -3; c <= 3; c++) {
         p.put(f.x(aS, c), gs + 14, f.z(aS, c), WildBlock.CONC_GRAY);
      }
      for (int s = -1; s <= 1; s += 2) {
         for (int da = -1; da <= 1; da++) {
            for (int dy = 0; dy <= 2; dy++) {
               if (!(Math.abs(da) == 1 && dy != 1)) {
                  p.put(f.x(aS + da, s), gs + 14 + dy, f.z(aS + da, s), WildBlock.CONC_LIGHT_GRAY);
               }
            }
         }
      }
      for (int y = gs - 10; y <= gs + 13; y++) {
         p.put(f.x(aS, 0), y, f.z(aS, 0), WildBlock.FENCE);
      }
      p.put(f.x(aS, 0), gs - 11, f.z(aS, 0), WildBlock.PLANKS);
      // the winding house, its door toward the shaft, the drum and a chest inside
      int wb = p.ground(f.x(aS + 8, 0), f.z(aS + 8, 0));
      for (int a = aS + 5; a <= aS + 11; a++) {
         for (int c = -3; c <= 3; c++) {
            boolean wall = Math.abs(c) == 3 || a == aS + 5 || a == aS + 11;
            int x = f.x(a, c);
            int z = f.z(a, c);
            for (int y = 1; y <= 5; y++) {
               double k = p.rnd(x, wb + y, z, 23);
               if (y == 5 ? k < 0.28 : (!wall || k < 0.1 || (a == aS + 5 && c == 0 && y <= 2))) {
                  continue;
               }
               WildBlock m = y == 5 ? WildBlock.CONC_GRAY : (y == 3 && Math.abs(c) == 3 && (a & 1) == 0) ? WildBlock.GLASS_PANE : WildBlock.CONC_LIGHT_GRAY;
               p.put(x, wb + y, z, m);
            }
         }
      }
      p.put(f.x(aS + 8, 0), wb + 1, f.z(aS + 8, 0), WildBlock.CONC_GRAY);
      p.put(f.x(aS + 8, 0), wb + 2, f.z(aS + 8, 0), WildBlock.CONC_GRAY);
      p.chest(f.x(aS + 9, 2), wb + 1, f.z(aS + 9, 2), f.dir, "industrial", 44);
      // two ore bins
      for (int s = -1; s <= 1; s += 2) {
         int bx = f.x(aS + 3, s * 9);
         int bz = f.z(aS + 3, s * 9);
         int bb = p.ground(bx, bz);
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                  continue;
               }
               for (int y = 1; y <= 7; y++) {
                  double k = p.rnd(bx + dx, bb + y, bz + dz, 24);
                  boolean split = s == 1 && dx == 1 && y >= 3 && y <= 5;
                  if (split || k < 0.05) {
                     continue;
                  }
                  p.put(bx + dx, bb + y, bz + dz, y == 4 ? WildBlock.CONC_RED : WildBlock.CONC_LIGHT_GRAY);
               }
            }
         }
      }
      // the dump truck, and a fence along the compound
      Frame tf = new Frame(f.x(aS + 9, 8), f.z(aS + 9, 8), f.dir);
      truck(p, tf, p.ground(tf.ox, tf.oz));
      for (int a = aS - 6; a <= aS + 16; a += 2) {
         for (int s = -1; s <= 1; s += 2) {
            int x = f.x(a, s * 13);
            int z = f.z(a, s * 13);
            if (p.rnd(x, 0, z, 25) < 0.55) {
               p.put(x, p.ground(x, z) + 1, z, WildBlock.FENCE);
            }
         }
      }
   }

   // ------------------------------------------------------------------
   // Wind farm
   // ------------------------------------------------------------------

   /** Connected, stagger-ended wind installation. All sites and their lanes
    * are accepted by windSite; rendering never re-rolls turbine positions. */
   private static void windFarm(Painter p, Random r, WindSite site) {
      Frame f = site.frame;
      int[] target = site.road;
      int ox = f.ox, oz = f.oz;
      final int spacing = WIND_TOWER_SPACING;
      final int rowSpacing = WIND_ROW_SPACING;
      int windDir = (f.dir + (r.nextBoolean() ? 1 : 3)) & 3;
      int farmPhase = r.nextInt(12);
      int farmHeight = 82 + r.nextInt(15);
      // Large parks retain damaged blades, but not a 100-block fallen tower
      // that would intersect other operating turbine foundations.
      int n = site.count;
      int toppledIdx = n <= 12 && r.nextInt(100) < 25 ? r.nextInt(n) : -1;
      int[] state = new int[n];
      int[] miss = new int[n];
      for (int i = 0; i < n; i++) {
         int roll = r.nextInt(100);
         state[i] = i == toppledIdx ? 3 : roll < 35 ? 0 : roll < 70 ? 1 : 2;
         miss[i] = r.nextInt(3);
      }
      // Carry the exact accepted winding route all the way to the paved
      // street. The same geometry is used during remote-chunk painting.
      int[] route;
      if (target.length > 3) {
         route = java.util.Arrays.copyOfRange(target, 3, target.length);
      } else {
         route = new int[]{f.x(site.exitA, 0), f.z(site.exitA, 0), target[1], target[2]};
      }
      // Test actual route legs, not the enclosing rectangle of a winding
      // road. An L-shaped access track can enclose hundreds of chunks that
      // contain neither road nor turbine; don't render the entire farm there.
      boolean nearRoute = false;
      for (int q = 0; q + 3 < route.length; q += 2) {
         int bx0 = Math.min(route[q], route[q + 2]);
         int bx1 = Math.max(route[q], route[q + 2]);
         int bz0 = Math.min(route[q + 1], route[q + 3]);
         int bz1 = Math.max(route[q + 1], route[q + 3]);
         if (bx1 + 20 >= p.minX && bx0 - 20 <= p.minX + 15
                 && bz1 + 20 >= p.minZ && bz0 - 20 <= p.minZ + 15) {
            nearRoute = true;
            break;
         }
      }
      if (!nearRoute && (site.maxX + 115 < p.minX || site.minX - 115 > p.minX + 15
              || site.maxZ + 115 < p.minZ || site.minZ - 115 > p.minZ + 15)) return;

      // A lane along each accepted row meets the common trunk. A failed
      // extension never leaves a hanging road stub or unconnected tower.
      int loLane = 0, hiLane = 0;
      for (WindRow row : site.rows) {
         int lane = rowLane(row.j);
         loLane = Math.min(loLane, lane); hiLane = Math.max(hiLane, lane);
         for (int a = row.firstI * spacing - 16; a <= site.trunkA; a++) {
            for (int c = lane - 1; c <= lane + 1; c++) roadCell(p, f, a, c, 33);
         }
      }
      for (int c = loLane - 1; c <= hiLane + 1; c++) {
         for (int a = site.trunkA - 1; a <= site.trunkA + 1; a++) roadCell(p, f, a, c, 33);
      }
      for (int a = site.trunkA; a <= site.exitA; a++) {
         for (int c = -1; c <= 1; c++) roadCell(p, f, a, c, 33);
      }
      int idx = 0;
      for (WindRow row : site.rows) {
         int c = rowCenter(row.j), lane = rowLane(row.j);
         for (int i = row.firstI; i <= 1; i++) {
            int a = i * spacing;
            int tx = f.x(a, c), tz = f.z(a, c);
            int step = lane >= c ? 1 : -1;
            for (int cc = c + step * 6; step > 0 ? cc <= lane : cc >= lane; cc += step) {
               roadCell(p, f, a, cc, 34);
            }
            if (p.touches(tx, tz, state[idx] == 3 ? 115 : 52)) {
               int fallDir = (f.dir + (a >= 0 ? 2 : 0)) & 3;
               turbine(p, tx, tz, farmHeight, state[idx], farmPhase, miss[idx], windDir, fallDir);
            }
            idx++;
         }
      }

      // the substation beside the road out, past the last turbine: a concrete house with its door toward the road, three transformers, a fence
      // round all of it with a gate on the road side and a spur of dirt to the gate
      int cS = 16;
      int gateA = site.trunkA + 12;
      int sb = p.ground(f.x(site.trunkA + 9, cS), f.z(site.trunkA + 9, cS));
      for (int a = site.trunkA + 6; a <= site.trunkA + 12; a++) {
         for (int c = cS - 2; c <= cS + 2; c++) {
            boolean wall = Math.abs(c - cS) == 2 || a == site.trunkA + 6 || a == site.trunkA + 12;
            int x = f.x(a, c);
            int z = f.z(a, c);
            for (int y = 1; y <= 5; y++) {
               double k = p.rnd(x, sb + y, z, 35);
               if (y == 5 ? k < 0.25 : (!wall || k < 0.1 || (a == site.trunkA + 9 && c == cS - 2 && y <= 2))) {
                  continue;
               }
               p.put(x, sb + y, z, y == 5 ? WildBlock.CONC_GRAY : (y == 3 && Math.abs(c - cS) == 2 && (a & 1) == 0) ? WildBlock.GLASS_PANE : WildBlock.CONC_LIGHT_GRAY);
            }
         }
      }
      p.chest(f.x(site.trunkA + 9, cS), sb + 1, f.z(site.trunkA + 9, cS), f.dir, "industrial", 51);
      for (int t = -1; t <= 1; t++) {
         int a0 = site.trunkA + 14;
         int c0 = cS + t * 4;
         for (int da = 0; da <= 1; da++) {
            for (int dc = 0; dc <= 1; dc++) {
               for (int y = 1; y <= 3; y++) {
                  int x = f.x(a0 + da, c0 + dc);
                  int z = f.z(a0 + da, c0 + dc);
                  if (p.rnd(x, y, z, 36) >= 0.08) {
                     p.put(x, p.ground(x, z) + y, z, y == 3 ? WildBlock.CONC_BLACK : WildBlock.CONC_GRAY);
                  }
               }
            }
         }
      }
      for (int a = site.trunkA + 4; a <= site.trunkA + 18; a++) {
         for (int c = cS - 7; c <= cS + 7; c++) {
            boolean edge = a == site.trunkA + 4 || a == site.trunkA + 18 || Math.abs(c - cS) == 7;
            boolean gate = c == cS - 7 && Math.abs(a - gateA) <= 1;
            int x = f.x(a, c);
            int z = f.z(a, c);
            if (edge && !gate && p.rnd(x, 0, z, 37) < 0.6) {
               p.put(x, p.ground(x, z) + 1, z, WildBlock.FENCE);
            }
         }
      }
      for (int c = 2; c <= cS - 8; c++) {
         roadCell(p, f, gateA, c, 34);
      }

      drawRoute(p, route);
   }

   /** What a dirt road is laid with: packed and coarse earth, a little gravel. */
   private static WildBlock dirtRoadBlock(double k) {
      return k < 0.5 ? WildBlock.COARSE_DIRT : k < 0.9 ? WildBlock.DIRT : WildBlock.GRAVEL;
   }

   /** One cell of a farm's own road in its frame: the trees cleared off it and the ground made dirt. Uses no Random. */
   private static void roadCell(Painter p, Frame f, int a, int c, int salt) {
      int x = f.x(a, c);
      int z = f.z(a, c);
      if (!p.inXZ(x, z) || !p.env.allows(x, z)) {
         return;
      }
      int g = p.ground(x, z);
      for (int y = g + 1; y <= g + 9; y++) {
         p.air(x, y, z);
      }
      double k = p.rnd(x, 0, z, salt);
      if (k < 0.92) {
         p.set(x, g, z, dirtRoadBlock(p.rnd(x, 1, z, salt)));
      }
   }

   private static void drawRoute(Painter p, int[] v) {
      HashSet<Long> seen = new HashSet<Long>();
      int n = v.length / 2;
      double cx = v[0];
      double cz = v[1];
      for (int i = 1; i < n; i++) {
         double vx = v[i * 2];
         double vz = v[i * 2 + 1];
         boolean lastLeg = i == n - 1;
         if (i < n - 1) {
            double nx = v[(i + 1) * 2];
            double nz = v[(i + 1) * 2 + 1];
            double inLen = Math.sqrt((vx - cx) * (vx - cx) + (vz - cz) * (vz - cz));
            double outLen = Math.sqrt((nx - vx) * (nx - vx) + (nz - vz) * (nz - vz));
            double rad = Math.min(8.0, Math.min(inLen, outLen) * 0.45);
            if (inLen < 1.0 || outLen < 1.0) {
               rad = 0.0;
            }
            double ax = inLen < 1.0 ? vx : vx - (vx - cx) / inLen * rad;
            double az = inLen < 1.0 ? vz : vz - (vz - cz) / inLen * rad;
            double bx = outLen < 1.0 ? vx : vx + (nx - vx) / outLen * rad;
            double bz = outLen < 1.0 ? vz : vz + (nz - vz) / outLen * rad;
            walk(p, seen, cx, cz, ax, az, false, 0);
            int steps = Math.max(2, (int) (rad * 2.0));
            double px = ax;
            double pz = az;
            for (int k = 1; k <= steps; k++) {
               double t = k / (double) steps;
               double qx = (1 - t) * (1 - t) * ax + 2 * (1 - t) * t * vx + t * t * bx;
               double qz = (1 - t) * (1 - t) * az + 2 * (1 - t) * t * vz + t * t * bz;
               walk(p, seen, px, pz, qx, qz, false, 0);
               px = qx;
               pz = qz;
            }
            cx = bx;
            cz = bz;
         } else {
            walk(p, seen, cx, cz, vx, vz, lastLeg, 8);
            cx = vx;
            cz = vz;
         }
      }
   }

   /** Stamps the road along a straight run in half-block steps; flare = widen the last flareLen blocks of it. */
   private static void walk(Painter p, HashSet<Long> seen, double x0, double z0, double x1, double z1, boolean flare, int flareLen) {
      double len = Math.sqrt((x1 - x0) * (x1 - x0) + (z1 - z0) * (z1 - z0));
      int steps = Math.max(1, (int) (len * 2.0));
      for (int i = 0; i <= steps; i++) {
         double t = i / (double) steps;
         int x = (int) Math.round(x0 + (x1 - x0) * t);
         int z = (int) Math.round(z0 + (z1 - z0) * t);
         if (x < p.minX - 3 || x > p.minX + 18 || z < p.minZ - 3 || z > p.minZ + 18) {
            continue;
         }
         stamp(p, seen, x, z, flare && len * (1.0 - t) <= flareLen ? 2 : 1);
      }
   }

   /** One dab of the road: every cell within rad steps of the point, the outermost ring a little ragged. */
   private static void stamp(Painter p, HashSet<Long> seen, int x, int z, int rad) {
      for (int dx = -rad; dx <= rad; dx++) {
         for (int dz = -rad; dz <= rad; dz++) {
            int ring = Math.abs(dx) + Math.abs(dz);
            if (ring > rad) {
               continue;
            }
            int cx = x + dx;
            int cz = z + dz;
            if (!p.inXZ(cx, cz) || !seen.add(((long) cx << 32) ^ (cz & 0xFFFFFFFFL)) || !p.env.allows(cx, cz)) {
               continue;
            }
            if (ring == rad && ring > 0 && p.rnd(cx, 3, cz, 47) >= 0.85) {
               continue;
            }
            int g = p.ground(cx, cz);
            for (int y = g + 1; y <= g + 9; y++) {
               p.air(cx, y, cz);
            }
            p.set(cx, g, cz, dirtRoadBlock(p.rnd(cx, 4, cz, 47)));
         }
      }
   }

   /** The shape of a tower's wall at one height: radius 3 is a ring of about thirty cells, 2 a ring of sixteen, 1 a ring of eight, round a hollow. */
   private static boolean towerRing(int dx, int dz, int rad) {
      double d = Math.sqrt(dx * dx + dz * dz);
      return rad == 3 ? (d > 2.1 && d <= 3.5) : rad == 2 ? (d > 1.1 && d <= 2.5) : (d > 0.4 && d <= 1.5);
   }

   /** One turbine: foundation curb, tapered concrete tower with a door and a chest at its foot, deep nacelle, spinner, three blades. */
   private static void turbine(Painter p, int tx, int tz, int height, int state, int phase, int miss, int yaw, int fallDir) {
      int g = p.ground(tx, tz);
      int top = state == 3 ? 5 + (int) (p.rnd(tx, 0, tz, 30) * 5) : height;
      int low = (int) (height * 0.28);
      int mid = (int) (height * 0.65);
      // clear the trees from round the foot, and curb the foundation
      for (int dx = -7; dx <= 7; dx++) {
         for (int dz = -7; dz <= 7; dz++) {
            int x = tx + dx;
            int z = tz + dz;
            if (!p.inXZ(x, z)) {
               continue;
            }
            int gg = p.ground(x, z);
            for (int y = gg + 1; y <= gg + 14; y++) {
               p.air(x, y, z);
            }
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > 3.8 && d <= 4.8) {
               p.put(x, gg + 1, z, WildBlock.CONC_GRAY);
            } else if (d > 4.8 && d <= 6.2 && p.rnd(x, 0, z, 38) < 0.45) {
               p.floor(x, z, WildBlock.GRAVEL);
            }
         }
      }
      int doorDx = DX[(yaw + 2) & 3] * 3;
      int doorDz = DZ[(yaw + 2) & 3] * 3;
      for (int y = 1; y <= top; y++) {
         int rad = y <= low ? 3 : y <= mid ? 2 : 1;
         for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
               if (!towerRing(dx, dz, rad) || (y <= 3 && rad == 3 && dx == doorDx && dz == doorDz)) {
                  continue;
               }
               int x = tx + dx;
               int z = tz + dz;
               double k = p.rnd(x, y, z, 31);
               if (state == 3 && y == top && k < 0.5) {
                  continue;
               }
               p.put(x, g + y, z, y <= 4 ? WildBlock.CONC_LIGHT_GRAY : (k < 0.1 ? WildBlock.CONC_LIGHT_GRAY : WildBlock.CONC_WHITE));
            }
         }
      }
      p.chest(tx, g + 1, tz, yaw, "industrial", 52);
      if (state == 3) {
         toppledTurbine(p, tx, tz, height - top, top, yaw, fallDir, phase);
         return;
      }
      Frame nf = new Frame(tx, tz, yaw);
      int hubY = g + height + 3;
      for (int a = -5; a <= 3; a++) {
         for (int c = -2; c <= 2; c++) {
            for (int y = 1; y <= 4; y++) {
               int x = nf.x(a, c);
               int z = nf.z(a, c);
               if (p.rnd(x, g + height + y, z, 39) >= 0.06) {
                  p.put(x, g + height + y, z, y == 4 ? WildBlock.CONC_LIGHT_GRAY : a <= -4 ? WildBlock.CONC_GRAY : WildBlock.CONC_WHITE);
               }
            }
         }
      }
      for (int c = -2; c <= 2; c++) {
         for (int y = 1; y <= 4; y++) {
            p.put(nf.x(4, c), g + height + y, nf.z(4, c), WildBlock.CONC_LIGHT_GRAY);
         }
      }
      for (int c = -1; c <= 1; c++) {
         for (int dy = -1; dy <= 1; dy++) {
            p.put(nf.x(5, c), hubY + dy, nf.z(5, c), WildBlock.CONC_LIGHT_GRAY);
         }
      }
      p.put(nf.x(6, 0), hubY, nf.z(6, 0), WildBlock.CONC_LIGHT_GRAY);
      blades(p, nf, hubY, 39, phase, state, miss, tx, tz, yaw);
   }

   /** Three blades from the hub, each tapering from a wide thick root to a red tip, the broken ones lying where their missing lengths fell. */
   private static void blades(Painter p, Frame nf, int hubY, int length, int phase, int state, int miss, int tx, int tz, int yaw) {
      for (int k = 0; k < 3; k++) {
         double th = Math.toRadians(phase * 30.0 + k * 120.0);
         double sx = Math.sin(th);
         double cy = Math.cos(th);
         int pc = (int) Math.round(cy);
         int py = (int) Math.round(-sx);
         int mode = 0;
         if (state == 1 && k == miss) {
            mode = 3;
         } else if (state == 2) {
            mode = k == miss ? 3 : (k == (miss + 1) % 3 ? 2 : 0);
         }
         int len = mode == 3 ? 0 : mode == 2 ? 6 + (int) (p.rnd(tx, k, tz, 32) * 6) : length;
         for (double t = 2.0; t <= len; t += 0.5) {
            int c = (int) Math.round(t * sx);
            int y = hubY + (int) Math.round(t * cy);
            WildBlock m = t <= 5 ? WildBlock.CONC_LIGHT_GRAY : (len == length && t > length - 4) ? WildBlock.CONC_RED : WildBlock.CONC_WHITE;
            p.put(nf.x(5, c), y, nf.z(5, c), m);
            if (t <= length * 0.5) {
               p.put(nf.x(6, c), y, nf.z(6, c), m);
            }
            if (t <= length * 0.6) {
               p.put(nf.x(5, c + pc), y + py, nf.z(5, c + pc), m);
            }
         }
         int fallen = length - len;
         if (fallen > 0) {
            int d = (yaw + 1 + k) & 3;
            int vd = (d + 1) & 3;
            int sx0 = tx + DX[d] * (11 + 4 * k);
            int sz0 = tz + DZ[d] * (11 + 4 * k);
            lay(p, sx0, sz0, DX[vd], DZ[vd], fallen, WildBlock.CONC_WHITE);
            lay(p, sx0 + DX[d], sz0 + DZ[d], DX[vd], DZ[vd], fallen, WildBlock.CONC_WHITE);
         }
      }
   }

   /**
    * A turbine that came down: the stump of the tower, the tower itself lying along the ground in lengths with gaps where it broke, the
    * nacelle crumpled at the end of it, the spinner, and the three blades laid out on the ground from the hub.
    */
   private static void toppledTurbine(Painter p, int tx, int tz, int len, int top, int yaw, int fallDir, int phase) {
      int vx = DX[fallDir & 3];
      int vz = DZ[fallDir & 3];
      int pxv = -vz;
      int pzv = vx;
      for (int t = 0; t < len; t++) {
         if (t % 14 >= 12) {
            continue;
         }
         int shift = (t / 14) % 2;
         for (int cc = -2; cc <= 2; cc++) {
            for (int yy = 1; yy <= 5; yy++) {
               double d = Math.sqrt(cc * cc + (yy - 3) * (yy - 3));
               if (d <= 1.1 || d > 2.5) {
                  continue;
               }
               int x = tx + vx * (3 + t) + pxv * (cc + shift);
               int z = tz + vz * (3 + t) + pzv * (cc + shift);
               double k = p.rnd(x, yy, z, 40);
               if (k < 0.08) {
                  continue;
               }
               p.put(x, p.ground(x, z) + yy, z, k < 0.25 ? WildBlock.CONC_LIGHT_GRAY : WildBlock.CONC_WHITE);
            }
         }
      }
      int nacelleStart = 3 + len + 1;
      for (int t = 0; t < 9; t++) {
         for (int cc = -2; cc <= 2; cc++) {
            for (int yy = 1; yy <= 4; yy++) {
               int x = tx + vx * (nacelleStart + t) + pxv * cc;
               int z = tz + vz * (nacelleStart + t) + pzv * cc;
               double k = p.rnd(x, yy, z, 41);
               if (k < 0.12 + t * 0.02) {
                  continue;
               }
               p.put(x, p.ground(x, z) + yy, z, yy == 4 ? WildBlock.CONC_LIGHT_GRAY : WildBlock.CONC_WHITE);
            }
         }
      }
      int hx = tx + vx * (nacelleStart + 11);
      int hz = tz + vz * (nacelleStart + 11);
      for (int cc = -2; cc <= 2; cc++) {
         for (int yy = 1; yy <= 4; yy++) {
            if (p.rnd(hx + pxv * cc, yy, hz + pzv * cc, 42) >= 0.2) {
               p.put(hx + pxv * cc, p.ground(hx + pxv * cc, hz + pzv * cc) + yy, hz + pzv * cc, WildBlock.CONC_LIGHT_GRAY);
            }
         }
      }
      final int[] turn = {0, 1, 3};
      final int[] lens = {27, 19, 13};
      for (int k = 0; k < 3; k++) {
         int d = (fallDir + turn[k]) & 3;
         int vd = (d + 1) & 3;
         lay(p, hx + DX[d] * 3, hz + DZ[d] * 3, DX[d], DZ[d], lens[k], WildBlock.CONC_WHITE);
         lay(p, hx + DX[d] * 3 + DX[vd], hz + DZ[d] * 3 + DZ[vd], DX[d], DZ[d], lens[k] - 3, WildBlock.CONC_WHITE);
      }
   }

   // ------------------------------------------------------------------
   // Landmarks
   // ------------------------------------------------------------------

   /** A radio mast: a lattice of stone walls on four legs with a white dish at the top, a service shed at its foot, and its chest. */
   private static void mast(Painter p, Random r, int ox, int oz) {
      int g = p.ground(ox, oz);
      int height = 16 + r.nextInt(5);
      int cut = height - r.nextInt(4);
      int legTop = height * 2 / 3;
      for (int y = 1; y <= Math.min(height, cut); y++) {
         p.put(ox, g + y, oz, WildBlock.COBBLE_WALL);
         if (y <= legTop) {
            for (int sx = -1; sx <= 1; sx += 2) {
               for (int sz = -1; sz <= 1; sz += 2) {
                  p.put(ox + sx, g + y, oz + sz, WildBlock.COBBLE_WALL);
               }
            }
            if (y % 4 == 0) {
               for (int i = -1; i <= 1; i++) {
                  p.put(ox + i, g + y, oz - 1, WildBlock.COBBLE_WALL);
                  p.put(ox + i, g + y, oz + 1, WildBlock.COBBLE_WALL);
                  p.put(ox - 1, g + y, oz + i, WildBlock.COBBLE_WALL);
                  p.put(ox + 1, g + y, oz + i, WildBlock.COBBLE_WALL);
               }
            }
         }
      }
      if (cut >= height) {
         for (int c = -2; c <= 2; c++) {
            p.put(ox + c, g + height + 1, oz, WildBlock.COBBLE_WALL);
         }
         for (int dy = 0; dy <= 2; dy++) {
            p.put(ox + 2, g + height - 1 + dy, oz, WildBlock.CONC_WHITE);
            p.put(ox + 3, g + height - 1 + dy, oz, WildBlock.CONC_WHITE);
         }
      }
      Frame f = new Frame(ox, oz, r.nextInt(4));
      int base = p.ground(f.x(5, 0), f.z(5, 0));
      for (int a = 4; a <= 7; a++) {
         for (int c = -2; c <= 2; c++) {
            boolean wall = Math.abs(c) == 2 || a == 4 || a == 7;
            for (int y = 1; y <= 3; y++) {
               int roll = r.nextInt(100);
               if (y == 3 ? roll < 25 : (!wall || roll < 12 || (a == 4 && c == 0))) {
                  continue;
               }
               p.put(f.x(a, c), base + y, f.z(a, c), WildBlock.PLANKS);
            }
         }
      }
      p.chest(f.x(6, 0), base + 1, f.z(6, 0), f.dir, "industrial", 30);
   }

   // ------------------------------------------------------------------
   // Rock
   // ------------------------------------------------------------------

   /** A rounded boulder, half sunk in the ground. */
   private static void boulder(Painter p, WildBiome biome, Random r, int ox, int oz) {
      int rad = 1 + r.nextInt(2);
      int cy = p.ground(ox, oz);
      double reach = (rad + 0.5) * (rad + 0.5);
      for (int dx = -rad; dx <= rad; dx++) {
         for (int dz = -rad; dz <= rad; dz++) {
            int trim = r.nextInt(4);
            double d2 = (dx * dx + dz * dz) / reach;
            if (d2 >= 1.0) {
               continue;
            }
            int h = (int) Math.round((rad + 0.3) * Math.sqrt(1.0 - d2) * 1.1) - (trim == 0 ? 1 : 0);
            int x = ox + dx;
            int z = oz + dz;
            for (int y = p.ground(x, z); y <= cy + h; y++) {
               int k = r.nextInt(10);
               WildBlock b;
               switch (biome) {
                  case MOUNTAINS:
                     b = k < 4 ? WildBlock.ANDESITE : k < 7 ? WildBlock.COBBLESTONE : k < 9 ? WildBlock.STONE : WildBlock.GRAVEL;
                     break;
                  case DESERT:
                     b = k < 7 ? WildBlock.SANDSTONE : WildBlock.SANDSTONE_SMOOTH;
                     break;
                  case MESA:
                     b = k < 6 ? p.env.band(y) : k < 9 ? WildBlock.RED_SANDSTONE : WildBlock.HARDENED_CLAY;
                     break;
                  default:
                     b = k < 5 ? WildBlock.MOSSY_COBBLE : k < 7 ? WildBlock.COBBLESTONE : WildBlock.ANDESITE;
                     break;
               }
               p.embed(x, y, z, b);
            }
         }
      }
   }

   /** A slim spire of weathered rock in the mesa's own stripes, widest at the foot, sometimes capped by a balanced stone. */
   private static void hoodoo(Painter p, Random r, int ox, int oz) {
      int h = 5 + r.nextInt(7);
      int g = p.ground(ox, oz);
      boolean capped = r.nextInt(5) < 3;
      for (int k = 0; k < h; k++) {
         int y = g + 1 + k;
         boolean plus = k < 2 || k % 4 == 3;
         spire(p, r, ox, oz, g, y);
         if (plus) {
            spire(p, r, ox + 1, oz, g, y);
            spire(p, r, ox - 1, oz, g, y);
            spire(p, r, ox, oz + 1, g, y);
            spire(p, r, ox, oz - 1, g, y);
         }
      }
      if (capped) {
         int y = g + h + 1;
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               p.put(ox + dx, y, oz + dz, spireBlock(p, r, y));
            }
         }
      }
   }

   /** One block of a spire, with the ground under it filled in so the spire does not float on a slope. */
   private static void spire(Painter p, Random r, int x, int z, int baseGround, int y) {
      if (y == baseGround + 1) {
         int from = Math.min(p.ground(x, z), baseGround);
         for (int fy = from + 1; fy <= y; fy++) {
            p.embed(x, fy, z, spireBlock(p, r, fy));
         }
         return;
      }
      p.put(x, y, z, spireBlock(p, r, y));
   }

   private static WildBlock spireBlock(Painter p, Random r, int y) {
      return r.nextInt(5) == 0 ? WildBlock.RED_SANDSTONE : p.env.band(y);
   }

   // ------------------------------------------------------------------
   // Writing, clipped to the chunk being generated.
   // ------------------------------------------------------------------

   static final class Painter {
      final Chunk chunk;
      final Env env;
      final long seed;
      final int minX, minZ;
      private final HashMap<Long, Integer> groundCache = new HashMap<Long, Integer>();

      Painter(Chunk chunk, Env env, long seed) {
         this.chunk = chunk;
         this.env = env;
         this.seed = seed;
         this.minX = chunk.getX() << 4;
         this.minZ = chunk.getZ() << 4;
      }

      /** Y of the ground at a column; cached, because the pieces ask for the same columns again and again. */
      int ground(int x, int z) {
         long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
         Integer known = this.groundCache.get(key);
         if (known != null) {
            return known;
         }
         int g = this.env.groundAt(x, z);
         this.groundCache.put(key, g);
         return g;
      }

      /** A stable number in [0, 1) for a position: the same whichever chunk asks. */
      double rnd(int x, int y, int z, int salt) {
         return ForestTerrain.unit(ForestTerrain.hash(this.seed, x * 131 + y, z, 500 + salt));
      }

      /** Does a circle of radius rad round (x, z) reach into this chunk? Only ever used to skip work, never to decide what a piece is. */
      boolean touches(int x, int z, int rad) {
         return x + rad >= this.minX && x - rad <= this.minX + 15 && z + rad >= this.minZ && z - rad <= this.minZ + 15;
      }

      boolean inXZ(int x, int z) {
         int lx = x - this.minX;
         int lz = z - this.minZ;
         return lx >= 0 && lx < 16 && lz >= 0 && lz < 16;
      }

      private boolean in(int x, int z, int y) {
         return this.inXZ(x, z) && y >= 1 && y <= 250;
      }

      private Material typeAt(int x, int y, int z) {
         return this.chunk.getBlock(x - this.minX, y, z - this.minZ).getType();
      }

      /**
       * Ground plants (grass, ferns, flowers, mushrooms, dead bush, snow layers, vines) a piece may stand on or replace.
       * The old code compared the 1.12 ids 31, 32, 37, 38, 39, 40, 78, 106 and 175; each of those is a family of materials now.
       */
      private static boolean plant(Material m) {
         return Mats.SHORT_PLANTS.contains(m) || Mats.TALL_PLANTS.contains(m) || Mats.SMALL_FLOWERS.contains(m)
                 || m == Material.BROWN_MUSHROOM || m == Material.RED_MUSHROOM || m == Material.SNOW || m == Material.VINE;
      }

      /** The soil of the surface: grass, dirt (incl. coarse dirt and podzol), sand (incl. red), gravel -- the old ids 2, 3, 12, 13. */
      private static boolean soil(Material m) {
         return m == Material.GRASS_BLOCK || Mats.DIRT_FAMILY.contains(m) || m == Material.SAND || m == Material.RED_SAND
                 || m == Material.GRAVEL;
      }

      /** Land vegetation must rest on solid soil. Decoration may be drawn on
       * a neighbouring chunk whose terrain was repainted after the origin was
       * planned, so the predicted ground height is not sufficient evidence. */
      private static boolean terrestrialPlant(WildBlock b) {
         switch (b) {
            case TALL_GRASS: case FERN: case DEAD_BUSH: case DANDELION:
            case BROWN_MUSHROOM: case RED_MUSHROOM:
            case POPPY: case BLUE_ORCHID: case ALLIUM: case AZURE_BLUET:
            case RED_TULIP: case ORANGE_TULIP: case WHITE_TULIP:
            case PINK_TULIP: case OXEYE_DAISY:
               return true;
            default:
               return false;
         }
      }

      /** Fast path for the three vanilla air block types; preserves Material
       * semantics while avoiding a registry lookup on common air placements. */
      private static boolean isAir(Material m) {
         return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR || m.isAir();
      }

      /** Into air or a ground plant only. */
      void put(int x, int y, int z, WildBlock b) {
         if (!this.in(x, z, y)) {
            return;
         }
         Material at = this.typeAt(x, y, z);
         if (terrestrialPlant(b) && (y <= 1 || !soil(this.typeAt(x, y - 1, z)))) {
            return; // Never place a grass tuft above water, air, wood or a missing soil column.
         }
         if (isAir(at) || plant(at)) {
            PAL.set(this.chunk, x - this.minX, y, z - this.minZ, b);
         }
      }

      /** Like put, but also replaces the soil, for things that sit in the ground rather than on it. */
      void embed(int x, int y, int z, WildBlock b) {
         if (!this.in(x, z, y)) {
            return;
         }
         Material at = this.typeAt(x, y, z);
         if (isAir(at) || plant(at) || soil(at)) {
            PAL.set(this.chunk, x - this.minX, y, z - this.minZ, b);
         }
      }

      /** Replaces whatever is there, except floating terrestrial plants. */
      void set(int x, int y, int z, WildBlock b) {
         if (this.in(x, z, y)) {
            if (terrestrialPlant(b) && (y <= 1 || !soil(this.typeAt(x, y - 1, z)))) return;
            PAL.set(this.chunk, x - this.minX, y, z - this.minZ, b);
         }
      }

      /** Replaces a block only if it is solid: ore in a wall, not in the air beside it. */
      void replaceSolid(int x, int y, int z, WildBlock b) {
         if (this.in(x, z, y) && !isAir(this.typeAt(x, y, z)) && this.typeAt(x, y, z) != Material.BEDROCK) {
            PAL.set(this.chunk, x - this.minX, y, z - this.minZ, b);
         }
      }

      /** Digs a block out: everything but bedrock. */
      void air(int x, int y, int z) {
         if (this.in(x, z, y) && this.typeAt(x, y, z) != Material.BEDROCK) {
            PAL.set(this.chunk, x - this.minX, y, z - this.minZ, WildBlock.AIR);
         }
      }

      /** Rewrites the surface block of a column, if it is soil. */
      void floor(int x, int z, WildBlock b) {
         int y = this.ground(x, z);
         if (this.in(x, z, y) && soil(this.typeAt(x, y, z))) {
            PAL.set(this.chunk, x - this.minX, y, z - this.minZ, b);
         }
      }

      /** A loot chest named for a loot collection, if the odds allow it; the level is a stable function of where it stands. */
      void chest(int x, int y, int z, int facing, String collection, int salt) {
         if (!this.in(x, z, y)) {
            return;
         }
         Material at = this.typeAt(x, y, z);
         if (!isAir(at) && !plant(at)) {
            return;
         }
         if (ForestTerrain.unit(ForestTerrain.hash(this.seed, x * 31 + y, z, 900 + salt)) * 100.0 >= this.env.chestOdds()) {
            return;
         }
         int min = Math.max(1, Math.min(5, this.env.lootMin()));
         int max = Math.max(min, Math.min(5, this.env.lootMax()));
         int level = min + (int) (ForestTerrain.unit(ForestTerrain.hash(this.seed, x, z * 7 + y, 950 + salt)) * (max - min + 1));
         this.env.chest(this.chunk, x, y, z, 2 + (facing & 3), collection, level);
      }
   }
}
