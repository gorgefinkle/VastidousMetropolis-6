package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.RoadParcel;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaWilderness;
import ch.k42.metropolis.grid.urbanGrid.wild.BiomeNames;
import ch.k42.metropolis.grid.urbanGrid.wild.BlockPalette;
import ch.k42.metropolis.grid.urbanGrid.wild.CityDistance;
import ch.k42.metropolis.grid.urbanGrid.wild.MesaBands;
import ch.k42.metropolis.grid.urbanGrid.wild.Noise2D;
import ch.k42.metropolis.grid.urbanGrid.wild.Palettes;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBiomes;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBlock;
import ch.k42.metropolis.grid.urbanGrid.wild.WildRelief;
import ch.k42.metropolis.grid.urbanGrid.wild.WildGeography;
import ch.k42.metropolis.grid.urbanGrid.wild.SpatialCache;
import ch.k42.metropolis.grid.urbanGrid.wild.WildHabitatBias;
import ch.k42.metropolis.minions.LegacyBlocks;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Generates the ground, ground cover and trees of one chunk of a morphomos
 * region (see MorphomosRegion / MorphomosParcel).
 *
 * The one hard rule: the surface meets the build height exactly along every
 * edge of the region. Height is baseline + relief * fade, where fade is 0 on
 * the outermost block ring of the region and eases to 1 over
 * generator.forest.edgeBlend blocks. "Edge" means any side where the region's
 * chunk mask ends -- roads, buildings, highways, another region, rivers, the
 * tile ring -- measured as true distance, so concave and convex corners round
 * off instead of stepping.
 *
 * With generator.forest.mountains.enabled, a region big enough to sit between
 * cities is not rolling hills but a mountain range (see ForestMountains): the
 * same edge fade, applied to ridged relief that climbs from the edges to a
 * core of crests, with bare rock above the tree line (and on steep slopes) and
 * snow above the snow line. Smaller regions are untouched.
 *
 * With generator.wilderness.biomes, the wilderness is one unbroken field instead of per-region ranges: forest, mountain, desert and
 * mesa biomes in big patches (see WildBiomes), with no region limit anywhere. "Edge" then means only a real edge (a city, a farm, a
 * street, a highway or water), never the border between two forest regions, and terrain still meets the build height there.
 *
 * Everything is a pure function of world coordinates plus the region's mask,
 * and a chunk only ever writes into itself. Trees near a chunk border are
 * drawn by both chunks (each draws its own share of the same tree), so there
 * are no seams and no dependence on generation order.
 */
public final class ForestGenerator {
   private static final Map<MetropolisGenerator, ForestTerrain> TERRAINS = new WeakHashMap<MetropolisGenerator, ForestTerrain>();

   private static final Map<MetropolisGenerator, ForestMountains> MOUNTAINS = new WeakHashMap<MetropolisGenerator, ForestMountains>();

   /** The one place block ids are chosen: see wild.Palettes for how a newer Minecraft is supported. */
   private static final BlockPalette PAL = Palettes.current();

   private static final Map<MetropolisGenerator, WildField> WILDS = new WeakHashMap<MetropolisGenerator, WildField>();

   /** Match the reference's per-generator scoped wind plan cache, not a world-seed-only global key. */
   private static final Map<MetropolisGenerator, Long> WIND_SCOPE_IDS =
           java.util.Collections.synchronizedMap(new WeakHashMap<>());
   private static final java.util.concurrent.atomic.AtomicLong NEXT_WIND_SCOPE =
           new java.util.concurrent.atomic.AtomicLong(1L);


   /** Column clearing stops here; the base terrain tops out at 64. */
   private static final int CLEAR_TOP = 72;
   /** Grid size of tree candidate sites, in blocks. */
   private static final int SITE = 4;

   private ForestGenerator() {
   }

   private static int floorDiv(int a, int b) {
      int q = a / b;
      return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q;
   }

   private static synchronized ForestMountains mountainsFor(MetropolisGenerator generator) {
      ForestMountains m = MOUNTAINS.get(generator);
      if (m == null) {
         m = new ForestMountains(generator.getWorldSeed());
         MOUNTAINS.put(generator, m);
      }
      return m;
   }

   private static synchronized ForestTerrain terrainFor(MetropolisGenerator generator) {
      ForestTerrain t = TERRAINS.get(generator);
      if (t == null) {
         t = new ForestTerrain(generator.getWorldSeed());
         TERRAINS.put(generator, t);
      }
      return t;
   }

   /** The wilderness fields of one world: which biome is where, and the relief and mesa bands that go with it. */
   static final class WildField {
      final WildBiomes biomes;
      final WildRelief relief;
      final WildGeography geography;
      final double[] requestedShares;
      final MesaBands bands;
      /** Distance to the nearest farm or city block, or null when the plan is unavailable or nothing is kept away. */
      final CityDistance civil;
      /** Per biome, in WildBiome order, the distance in blocks at which it reaches full strength (0 = may touch the cities). */
      final double[] awayBlocks = new double[WildBiome.ALL.length];
      /** How far in blocks the mesa / desert border waves in and out (0 = a clean line), and the noise that waves it. */
      final double mergeBlocks;
      final Noise2D mergeX, mergeZ;
      /** The keep-away ramp (blocks it grows in over), and how far in blocks its distance wanders (see sharp). */
      final double awayMerge, awayVariance;
      final Noise2D awayNoise;
      /** generator.wilderness.smoothBlocks: the radius the biome weights are averaged over (0 = off), and the lattice the average is cached on. */
      final int smoothBlocks;
      private static final int NODE = 16;
      private final java.util.concurrent.ConcurrentHashMap<Long, double[]> nodes = new java.util.concurrent.ConcurrentHashMap<Long, double[]>();

      WildField(long seed) {
         this(seed, true);
      }

      /** Offline QA constructor; it builds the identical field but suppresses Bukkit logger access. */
      WildField(long seed, boolean reportToServerLog) {
         this.smoothBlocks = PluginConfig.getWildSmoothBlocks();
         this.mergeBlocks = PluginConfig.getWildMesaDesertMerge();
         this.mergeX = new Noise2D(seed ^ 0x4D45524745584CL);
         this.mergeZ = new Noise2D(seed ^ 0x4D4552474553L);
         this.awayMerge = PluginConfig.getWildKeepAwayMerge() * 16.0;
         this.awayVariance = PluginConfig.getWildKeepAwayVariance() * 16.0;
         this.awayNoise = new Noise2D(seed ^ 0x41574159L);
         java.util.List<String> ruleProblems = new java.util.ArrayList<String>();
         int[][] rules = WildBiomes.parseTransitions(PluginConfig.getWildTransitions(), ruleProblems);
         if (reportToServerLog) {
            for (String problem : ruleProblems) {
               Minions.w("generator.wilderness.transitions: " + problem);
            }
         }
         // Retired biome: correct the actual seed distribution before any
         // WildLayers labels are assigned. A late presentation-only filter
         // left "wetlands 4% placed" in live server logs and could still
         // influence the positions of neighboring biomes.
         double[] wantShares = PluginConfig.getWildBiomeShares().clone();
         int wet = WildBiome.WETLANDS.ordinal();
         wantShares[WildBiome.FOREST.ordinal()] += Math.max(0.0, wantShares[wet]);
         wantShares[wet] = 0.0;
         this.requestedShares = wantShares.clone();
         this.biomes = new WildBiomes(seed, PluginConfig.getWildBiomeScale(), PluginConfig.getWildBiomeBlend(), wantShares, rules,
                 PluginConfig.getWildBiomeZoomLevels(), PluginConfig.getWildBiomeTransitionPasses(), PluginConfig.getWildBiomeWarpPercent());
         double[] placed = this.biomes.realisedShares();
         double wantTotal = 0.0;
         for (double v : wantShares) {
            wantTotal += v;
         }
         StringBuilder report = new StringBuilder();
         for (WildBiome b : WildBiome.ALL) {
            report.append(report.length() == 0 ? "" : ", ").append(b.key()).append(' ')
                    .append(Math.round(wantTotal > 0.0 ? 100.0 * wantShares[b.ordinal()] / wantTotal : 0.0)).append("% wanted / ")
                    .append(Math.round(100.0 * placed[b.ordinal()])).append("% placed");
         }
         if (reportToServerLog) Minions.i("Wilderness biomes: " + report);
         this.relief = new WildRelief(seed, PluginConfig.getForestMountainHeight(), PluginConfig.getWildMesaTerraceHeight(), PluginConfig.getWildMesaTerraces(), PluginConfig.getWildDuneScale() / 100.0, PluginConfig.getMountainKindShares());
         this.bands = new MesaBands(seed);
         this.geography = new WildGeography(seed, this.relief, PluginConfig.getWildWetlandReachChunks(),
                 PluginConfig.getWildDuneInlandBlocks(), PluginConfig.getWildMinimumRegionBlocks());
         int[] away = PluginConfig.getWildKeepAwayChunks();
         int cap = 0;
         for (int i = 0; i < this.awayBlocks.length && i < away.length; i++) {
            this.awayBlocks[i] = away[i] * 16.0;
            cap = Math.max(cap, away[i]);
         }
         if (cap > 0) {
            cap += PluginConfig.getWildKeepAwayVariance();     // the distance may wander that far short of the farm
         }
         WorldLayout layout = cap > 0 ? WorldLayouts.forSeed(seed) : null;
         this.civil = layout != null ? new CityDistance(layout, cap) : null;

         // Report the distribution after blur, habitat bias, keep-away,
         // sharpening and the production picker. The old WildLayers
         // calibration only measured an intermediate zoom level and could
         // conceal a rare-biome collapse later in the actual field pipeline.
         if (reportToServerLog) {
            final int sampleSide = 96;
            final int sampleStep = 128;
            int[] picked = new int[WildBiome.ALL.length];
            for (int sx = 0; sx < sampleSide; sx++) {
               for (int sz = 0; sz < sampleSide; sz++) {
                  int x = (sx - sampleSide / 2) * sampleStep;
                  int z = (sz - sampleSide / 2) * sampleStep;
                  // Match populateChunk exactly: ForestTerrain salts the world seed before hashing its per-column picker roll.
                  double roll = ForestTerrain.unit(ForestTerrain.hash(seed ^ 0x464F52455354L, x, z, 41));
                  picked[this.pick(x, z, roll).ordinal()]++;
               }
            }
            StringBuilder finalField = new StringBuilder();
            for (WildBiome biome : WildBiome.ALL) {
               int count = picked[biome.ordinal()];
               if (count == 0 && biome != WildBiome.BLOOMLANDS) continue;
               finalField.append(finalField.length() == 0 ? "" : ", ")
                       .append(biome.key()).append(' ')
                       .append(String.format(java.util.Locale.ROOT, "%.2f%%", 100.0 * count / (sampleSide * sampleSide)));
            }
            Minions.i("Wilderness final picker sample (" + (sampleSide * sampleSide)
                    + " points): " + finalField);
         }
      }

      /**
       * The biome weights at a column, kept away from the cities and farms and pulled toward the biome cores: the one answer
       * inland wilderness and the river and ocean shores both read, so the two meet without a seam.
       */
      double[] sharp(int x, int z) {
         double[] raw = this.kept(x, z);
         double[] out = new double[raw.length];
         WildBiomes.sharpen(raw, out);
         return out;
      }

      /**
       * How far the mesa relief carries on into the desert at a column, 0..1: the mesa own weight read through a gentler
       * sharpening than sharp() uses (which counts nothing below a tenth), so the long tail of the weight past the border keeps
       * a little of the mesa height. generator.wilderness.mesaFoot sets how low the tail is followed (0 = exactly sharp()).
       */
      double mesaFoot(int x, int z) {
         int pct = PluginConfig.getWildMesaFoot();
         if (pct <= 0) {
            return 0.0;
         }
         double lo = 0.10 * (1.0 - 0.9 * pct / 100.0);
         double t = Math.max(0.0, Math.min(1.0, (this.kept(x, z)[WildBiome.MESA.ordinal()] - lo) / (0.90 - lo)));
         return t * t * (3.0 - 2.0 * t);
      }

      /**
       * The raw biome weights averaged over smoothBlocks around the column, so a sliver or a spot narrower than that averages out
       * and never wins a column: no little islands of one biome inside another, no thin bands of a third between two. The average
       * is taken at the nodes of a 16-block lattice (cached, so it costs next to nothing per column) and interpolated between them,
       * which keeps the borders smooth. A pure function of (seed, x, z), so every chunk gets the same answer in any order.
       */
      private double[] blurred(int x, int z) {
         double[] out = new double[WildBiome.ALL.length];
         if (this.smoothBlocks <= 0) {
            this.biomes.weights(x, z, out);
            return out;
         }
         int i0 = Math.floorDiv(x, NODE);
         int j0 = Math.floorDiv(z, NODE);
         double fx = (x - i0 * NODE) / (double) NODE;
         double fz = (z - j0 * NODE) / (double) NODE;
         double[] a = this.node(i0, j0), b = this.node(i0 + 1, j0), c = this.node(i0, j0 + 1), d = this.node(i0 + 1, j0 + 1);
         double sum = 0.0;
         for (int k = 0; k < out.length; k++) {
            out[k] = (a[k] * (1.0 - fx) + b[k] * fx) * (1.0 - fz) + (c[k] * (1.0 - fx) + d[k] * fx) * fz;
            sum += out[k];
         }
         if (sum > 1e-12) {
            for (int k = 0; k < out.length; k++) {
               out[k] /= sum;
            }
         }
         return out;
      }

      /** The averaged weights at lattice node (i, j): the column itself, and two rings of eight around it at half and full radius. */
      private double[] node(int i, int j) {
         long key = SpatialCache.key(i, j);
         double[] known = this.nodes.get(key);
         if (known != null) {
            return known;
         }
         int n = WildBiome.ALL.length;
         double[] acc = new double[n];
         double[] w = new double[n];
         int cx = i * NODE;
         int cz = j * NODE;
         this.biomes.weights(cx, cz, w);
         // Keep the unblurred center values for the two rare specialist
         // habitats. Their own discrete region field already smooths and
         // preserves coherent cores; averaging them over the broad 192-block
         // habitat blur can shrink the last surviving cores below dominance.
         double[] rareCore = {
               w[WildBiome.VOLCANIC_BARRENS.ordinal()],
               w[WildBiome.BLOOMLANDS.ordinal()]
         };
         double total = 3.0;
         for (int k = 0; k < n; k++) {
            acc[k] = 3.0 * w[k];
         }
         final double[][] dir = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {0.7071, 0.7071}, {-0.7071, 0.7071}, {0.7071, -0.7071}, {-0.7071, -0.7071}};
         for (int ring = 1; ring <= 2; ring++) {
            double r = this.smoothBlocks * ring / 2.0;
            for (double[] dv : dir) {
               this.biomes.weights(cx + (int) Math.round(dv[0] * r), cz + (int) Math.round(dv[1] * r), w);
               for (int k = 0; k < n; k++) {
                  acc[k] += w[k];
               }
               total += 1.0;
            }
         }
         for (int k = 0; k < n; k++) {
            acc[k] /= total;
         }
         acc[WildBiome.VOLCANIC_BARRENS.ordinal()] = rareCore[0];
         acc[WildBiome.BLOOMLANDS.ordinal()] = rareCore[1];
         SpatialCache.prune(this.nodes, 400000, 300000);
         this.nodes.putIfAbsent(key, acc);
         return acc;
      }

      /** The biome weights after the keep-away from the cities and farms, before they are sharpened. */
      private double[] kept(int x, int z) {
         double[] raw = this.blurred(x, z);
         WildHabitatBias.apply(raw, this.geography, x, z, this.requestedShares,
                 PluginConfig.getWildWetlandCoreStrength(),
                 PluginConfig.getWildDuneCoreStrength(),
                 PluginConfig.getWildVolcanicCoreStrength());
         // The geographic core must not bypass an explicit near-city buffer.
         if (this.civil != null) {
            double dist = this.civil.at(x,z);
            if (this.awayVariance > 0) {
               double wave = Math.max(96.0, this.awayVariance * 3.0);
               dist = Math.max(0.0, dist + this.awayVariance * Math.max(-1.0, Math.min(1.0,
                       1.6 * this.awayNoise.fbm(x / wave, z / wave, 2, 0.5))));
            }
            WildBiomes.keepAway(raw,dist,this.awayBlocks,this.awayMerge);
         }
         return raw;
      }

      /**
       * The biome of a column. Forest, mountains and the dry biomes are stippled column by column where they grade into one
       * another, as before; but the two dry biomes, mesa and desert, are told apart by a border that waves (see mesaOrDesert),
       * not by a spray of single columns.
       */
      WildBiome pick(int x, int z, double u) {
         if (PluginConfig.isWildHardBorders()) {
            // generator.wilderness.hardBorders: whichever biome holds most of the column at the waved point wins, so EVERY border
            // (forest/mountain too, not only mesa/desert) is one wavy line instead of a stipple of single columns
            double[] v = this.warpedSharp(x, z);
            WildBiome top = WildBiomes.dominant(v);
            if (top.is(WildBiome.DESERT) || top.is(WildBiome.MESA)) {
               return v[WildBiome.MESA.ordinal()] - v[WildBiome.DESERT.ordinal()] > 0.0 ? WildBiome.MESA : WildBiome.DESERT;
            }
            return top;
         }
         double[] w = this.sharp(x, z);
         WildBiome b = WildBiomes.pick(w, u);
         return b.is(WildBiome.DESERT) || b.is(WildBiome.MESA) ? this.mesaOrDesert(x, z, w) : b;
      }

      /**
       * Mesa or desert at a column whose dry biome is one of the two: whichever holds more of the column, read at a point
       * moved by up to mergeBlocks by a slow noise. The border between the two is the line where they hold the same, so
       * it becomes a wave reaching mergeBlocks either way instead of a gradient of scattered columns. here = sharp(x, z).
       */
      WildBiome mesaOrDesert(int x, int z, double[] here) {
         return this.mesaLead(x, z, here) > 0.0 ? WildBiome.MESA : WildBiome.DESERT;
      }

      /**
       * How far ahead the mesa is of the desert at a column, at the same waved point mesaOrDesert reads: above 0 the surface is
       * mesa, below 0 desert, and the further below, the deeper in the desert (-1 = no mesa at all there). here = sharp(x, z).
       */
      double mesaLead(int x, int z, double[] here) {
         double[] w = this.mergeBlocks > 0.0 ? this.warpedSharp(x, z) : here;
         return w[WildBiome.MESA.ordinal()] - w[WildBiome.DESERT.ordinal()];
      }

      /** sharp() read at the column moved by up to mergeBlocks by the slow merge noise: the one waved point every border reads. */
      double[] warpedSharp(int x, int z) {
         double wave = Math.max(16.0, this.mergeBlocks * 4.0);
         int wx = x + (int) Math.round(this.mergeBlocks * Math.max(-1.0, Math.min(1.0, 1.6 * this.mergeX.fbm(x / wave, z / wave, 2, 0.5))));
         int wz = z + (int) Math.round(this.mergeBlocks * Math.max(-1.0, Math.min(1.0, 1.6 * this.mergeZ.fbm(x / wave + 40.0, z / wave - 40.0, 2, 0.5))));
         return this.sharp(wx, wz);
      }
   }

   /** The wilderness fields of this world, or null when generator.wilderness.biomes is off. */
   private static synchronized WildField wildFor(MetropolisGenerator generator) {
      // With the vanilla wilderness on there is no desert / mesa / mountain biome inland to meet, so shores plant plain forest
      if (!PluginConfig.isWildBiomesEnabled() || PluginConfig.isWildVanillaEnabled()) {
         return null;
      }
      WildField f = WILDS.get(generator);
      if (f == null) {
         f = new WildField(generator.getWorldSeed());
         WILDS.put(generator, f);
         // the rainforest is a wilderness biome: its weight there is its share of every forest column's type weights
         final WildField field = f;
         terrainFor(generator).setRainfall(new ForestTerrain.Rainfall() {
            @Override
            public double at(int x, int z) {
               return field.sharp(x, z)[WildBiome.RAINFOREST.ordinal()];
            }
         });
         terrainFor(generator).setTypeBias(new ForestTerrain.TypeBias() {
            @Override public void apply(int x, int z, double[] weights) {
               double[] biomes = field.sharp(x, z);
               double prairie = biomes[WildBiome.PRAIRIE.ordinal()];
               double wetlands = biomes[WildBiome.WETLANDS.ordinal()];
               double oldGrowth = biomes[WildBiome.OLD_GROWTH.ordinal()];
               double deciduous = biomes[WildBiome.DECIDUOUS.ordinal()];
               double influence = Math.min(1.0, prairie + wetlands + oldGrowth + deciduous);
               if (influence <= 0.001) return;
               for (int i = 0; i < weights.length; i++) weights[i] *= 1.0 - influence;
               weights[ForestType.MEADOW_WOODS.ordinal()] += prairie;
               weights[ForestType.DARK_FOREST.ordinal()] += wetlands * 0.7 + oldGrowth * 0.25;
               weights[ForestType.SPRUCE_TAIGA.ordinal()] += oldGrowth * 0.75;
               weights[ForestType.OAK_WOODLAND.ordinal()] += deciduous * 0.6 + wetlands * 0.3;
               weights[ForestType.BIRCH_GROVE.ordinal()] += deciduous * 0.4;
               double sum = 0.0;
               for (double q : weights) sum += q;
               if (sum > 0) for (int i = 0; i < weights.length; i++) weights[i] /= sum;
            }
         });
      }
      return f;
   }

   /** Finds the nearest fully valid *planned* wind installation, including a real road approach.
    * Candidate locations use the exact same cell field, randomness, clearance and footing checks
    * as WildDecor.populate. This does not rely on chunks already being generated. */
   public static int[] nearestWindFarm(MetropolisGenerator generator, int x, int z, int radiusBlocks) {
      return nearestWindFarm(generator, x, z, radiusBlocks, 1);
   }

   /** Minimum turbine count is checked against the exact committed layout,
    * not the aspirational seed roll. */
   public static int[] nearestWindFarm(MetropolisGenerator generator, int x, int z, int radiusBlocks, int minimumTurbines) {
      return nearestWindFarm(generator, x, z, radiusBlocks, minimumTurbines, null);
   }

   /** Optional final-world verification. The TP command supplies a predicate
    * that loads the planned core tower chunk and checks actual foundation
    * blocks. A stale or unpainted plan does not stop the search: try the next
    * real site. Ordinary non-TP finder remains planning-only and fast. */
   public static int[] nearestWindFarm(MetropolisGenerator generator, int x, int z, int radiusBlocks,
                                      int minimumTurbines, java.util.function.Predicate<int[]> builtFilter) {
      if (generator == null || PluginConfig.getWildDecorations() <= 0 || !PluginConfig.isForestGenerationEnabled()
              || VanillaWilderness.isActive(generator.getWorld())) return null;
      long seed = generator.getWorldSeed();
      int cell = WildDecor.windCellBlocks();
      int centerX = Math.floorDiv(x, cell), centerZ = Math.floorDiv(z, cell);
      int maxR = Math.max(0, (radiusBlocks + cell - 1) / cell + 1);
      long range2 = (long) radiusBlocks * radiusBlocks;
      int[] best = null;
      long bestDist = Long.MAX_VALUE;
      ForestTerrain terrain = terrainFor(generator);
      for (int r = 0; r <= maxR; r++) {
         for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int cx = centerX + dx, cz = centerZ + dz;
            int[] origin = WildDecor.windCellOrigin(seed, cx, cz);
            long ddx = (long) origin[0] - x, ddz = (long) origin[1] - z;
            long dist = ddx * ddx + ddz * ddz;
            if (dist > range2 || dist >= bestDist) continue;
            // Any biome's candidate chance is <= 0.85. Reject most cells without
            // constructing a remote UrbanGrid (which can be expensive).
            if (ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, 82)) >= 0.85) continue;
            ch.k42.metropolis.grid.common.Grid tile = generator.getGridProvider().getGrid(origin[0] >> 4, origin[1] >> 4);
            if (!(tile instanceof UrbanGrid grid)) continue;
            MorphomosRegion region = grid.getMorphomosRegionAt(origin[0] >> 4, origin[1] >> 4);
            if (region == null || region.getContext() != ch.k42.metropolis.grid.urbanGrid.enums.ContextType.FOREST) continue;
            Ctx site = new Ctx(generator, grid, region, terrain);
            WildDecor.Env env = decorEnv(generator, portSafe(site, grid), site.wild, seed, grid);
            int[] siteVisit = WildDecor.plannedWindFarmVisit(seed, env, PluginConfig.getWildDecorations(), cx, cz, origin[0], origin[1]);
            if (siteVisit != null && siteVisit[2] >= Math.max(1, minimumTurbines)
                    && (builtFilter == null || builtFilter.test(siteVisit))) {
               best = siteVisit;
               bestDist = dist;
            }
         }
         // A candidate farther than two whole cells cannot improve on a found
         // site after completing the current ring; avoid building distant tiles.
         if (best != null && (r * cell - cell) * (long)(r * cell - cell) > bestDist) break;
      }
      return best;
   }


   /** Planner owner for a natural-forest chunk, including creek interfluves. */
   private record WildBiomeOwner(UrbanGrid grid, MorphomosRegion region) {}

   private static WildBiomeOwner wildBiomeOwner(MetropolisGenerator generator, int chunkX, int chunkZ) {
      if (generator == null || generator.getGridProvider() == null) return null;
      ch.k42.metropolis.grid.common.Grid tile = generator.getGridProvider().getGrid(chunkX, chunkZ);
      if (!(tile instanceof UrbanGrid grid)) return null;
      MorphomosRegion region = grid.getMorphomosRegionAt(chunkX, chunkZ);
      if (region == null) region = grid.localCreekNaturalForestAt(chunkX, chunkZ);
      if (region == null || region.getContext() != ContextType.FOREST) return null;
      return new WildBiomeOwner(grid, region);
   }

   /** True when this named biome can be selected by the world's canonical field. */
   public static boolean isWildBiomeSearchAvailable(MetropolisGenerator generator, WildBiome biome) {
      if (generator == null || generator.getWorld() == null || biome == null
              || biome == WildBiome.WETLANDS
              || !PluginConfig.isForestGenerationEnabled()
              || !PluginConfig.isWildBiomesEnabled()
              || PluginConfig.isWildVanillaEnabled()) return false;
      double[] shares = PluginConfig.getWildBiomeShares();
      if (shares == null || biome.ordinal() >= shares.length) return false;
      double share = shares[biome.ordinal()];
      if (biome == WildBiome.FOREST && WildBiome.WETLANDS.ordinal() < shares.length) {
         share += Math.max(0.0, shares[WildBiome.WETLANDS.ordinal()]);
      }
      return share > 0.0;
   }

   /** Deterministic candidate probe using the exact production picker and planner ownership. */
   public static int wildBiomeCandidateScore(MetropolisGenerator generator, WildBiome biome, int x, int z) {
      if (!isWildBiomeSearchAvailable(generator, biome)) return -1;

      // The wilderness field is a pure world-coordinate function. Probe it
      // BEFORE asking GridProvider for a planner tile: a rare-biome search can
      // visit hundreds of thousands of positions, but only actual target
      // hits need an expensive 64x64-chunk UrbanGrid ownership lookup.
      WildField wild = wildFor(generator);
      if (wild == null) return -1;
      ForestTerrain terrain = terrainFor(generator);
      if (!wildBiomeMatchesAt(wild, terrain, biome, x, z)) return -1;
      WildBiomeOwner owner = wildBiomeOwner(generator, x >> 4, z >> 4);
      // -2 = no forest planner owner; -3 = a one-off field hit without a sustained core.
      if (owner == null) return -2;
      final int radius = 96;
      final int[][] offsets = {
            {0, 0}, {radius, 0}, {-radius, 0}, {0, radius}, {0, -radius},
            {radius, radius}, {radius, -radius}, {-radius, radius}, {-radius, -radius}
      };
      for (int[] offset : offsets) {
         int sx = x + offset[0], sz = z + offset[1];
         if (!wildBiomeMatchesAt(wild, terrain, biome, sx, sz)) return -3;
         if (wildBiomeOwner(generator, sx >> 4, sz >> 4) == null) return -2;
      }
      return 10000;
   }

   private static boolean wildBiomeMatchesAt(WildField wild, ForestTerrain terrain,
                                             WildBiome biome, int x, int z) {
      double roll = ForestTerrain.unit(ForestTerrain.hash(terrain.getSeed(), x, z, 41));
      return wild.pick(x, z, roll) == biome;
   }

   /** Verify the generated destination chunk and return its nearest safe column {x, feetY, z}. */
   public static int[] safeWildBiomeLanding(MetropolisGenerator generator, Chunk chunk, WildBiome biome,
                                             int originX, int originZ) {
      if (!isWildBiomeSearchAvailable(generator, biome) || chunk == null) return null;
      WildBiomeOwner owner = wildBiomeOwner(generator, chunk.getX(), chunk.getZ());
      if (owner == null) return null;
      Ctx ctx = new Ctx(generator, owner.grid(), owner.region(), terrainFor(generator));
      if (ctx.wild == null) return null;
      World world = generator.getWorld();
      int minY = world.getMinHeight() + 1;
      int maxY = world.getMaxHeight() - 3;
      int baseX = chunk.getX() << 4, baseZ = chunk.getZ() << 4;
      long bestDistance = Long.MAX_VALUE;
      int[] best = null;
      for (int lx = 0; lx < 16; lx++) for (int lz = 0; lz < 16; lz++) {
         int x = baseX + lx, z = baseZ + lz;
         double roll = ForestTerrain.unit(ForestTerrain.hash(ctx.terrain.getSeed(), x, z, 41));
         if (ctx.wild.pick(x, z, roll) != biome) continue;
         int groundY = ctx.height(x, z);
         if (groundY < minY || groundY > maxY) continue;
         Material ground = chunk.getBlock(lx, groundY, lz).getType();
         if (!isSafeWildBiomeGround(ground)) continue;
         Block feet = chunk.getBlock(lx, groundY + 1, lz);
         Block head = chunk.getBlock(lx, groundY + 2, lz);
         if (!isSafeWildBiomeAir(feet) || !isSafeWildBiomeAir(head)) continue;
         long dx = (long)x - originX, dz = (long)z - originZ;
         long distance = dx * dx + dz * dz;
         if (distance < bestDistance) {
            bestDistance = distance;
            best = new int[] {x, groundY + 1, z};
         }
      }
      return best;
   }

   private static boolean isSafeWildBiomeGround(Material material) {
      if (material == null || !material.isSolid() || material.isAir()) return false;
      return switch (material) {
         case CACTUS, MAGMA_BLOCK, CAMPFIRE, SOUL_CAMPFIRE, FIRE, SOUL_FIRE, POWDER_SNOW -> false;
         default -> true;
      };
   }

   private static boolean isSafeWildBiomeAir(Block block) {
      if (block == null) return false;
      Material material = block.getType();
      if (material == Material.POWDER_SNOW || material == Material.COBWEB
              || material == Material.SWEET_BERRY_BUSH || material == Material.FIRE
              || material == Material.SOUL_FIRE || material == Material.CACTUS
              || material == Material.MAGMA_BLOCK || material == Material.CAMPFIRE
              || material == Material.SOUL_CAMPFIRE) return false;
      return block.isPassable() && !block.isLiquid();
   }

   public static void populateChunk(MetropolisGenerator generator, Chunk chunk, UrbanGrid grid, MorphomosRegion region) {
      if (!PluginConfig.isForestGenerationEnabled() || region == null || generator.getWorld() == null) {
         return;
      }
      final Ctx ctx = new Ctx(generator, grid, region, terrainFor(generator));
      World world = generator.getWorld();
      int baseX = chunk.getX() << 4;
      int baseZ = chunk.getZ() << 4;

      double[] w = new double[ForestType.ALL.length];
      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            int x = baseX + lx;
            int z = baseZ + lz;
            int h = ctx.height(x, z);
            ctx.terrain.typeWeights(x, z, w);
            WildBiome biome = ctx.wild == null ? WildBiome.FOREST :
                    ctx.wild.pick(x, z, ForestTerrain.unit(ForestTerrain.hash(ctx.terrain.getSeed(), x, z, 41)));
            writeColumn(chunk, lx, lz, x, z, h, w, ctx, biome);
            try {
               world.setBiome(x, z, biomeFor(ctx, x, z, h, w, biome));
            } catch (Throwable ignored) {
               // biome tinting is cosmetic; never let it stop terrain
            }
         }
      }

      // R30ea: roots in forests and the adjoining river/ocean bank must consult
      // the SAME ownership model. Terrain painting above remains forest-owned.
      TreeSite roots = VanillaWilderness.isActive(world) || grid.planWater() == null || grid.cityLayout() == null
              ? ctx : new SharedVegetationSite(generator, grid, ctx.terrain);
      placeTrees(chunk, ctx.terrain, portSafe(roots, grid), PluginConfig.getForestTreeDensity());
      WildDecor.populate(chunk, ctx.terrain.getSeed(), decorEnv(generator, portSafe(ctx, grid), ctx.wild, ctx.terrain.getSeed(), grid), PluginConfig.getWildDecorations(), windOrigins(generator));
      BiomeEcology.decorate(chunk, portSafe(ctx, grid), ctx.wild, generator.getWorldSeed());
      Minions.dDistrict("FOREST chunk=(%d,%d) region=%d", chunk.getX(), chunk.getZ(), region.getId());
   }

   /**
    * Where trees may root and what the ground is there. The forest's is its region mask (Ctx below); a river or
    * ocean bank's is the grass beyond the wet shore band (RiverParcel, OceanParcel). Every answer must be a pure
    * function of world position, because a tree is drawn by every chunk it reaches into: each chunk asks the same
    * questions about the same columns and comes out with its own share of the same tree.
    */
   public interface TreeSite {
      /** May a tree take root at this column? */
      boolean allows(int x, int z);

      /** 0..1 factor on tree density at this column (the forest's tree line); 0 or less means no tree. */
      double fade(int x, int z);

      /** Blocks from this column to the nearest column that does not allow trees, Double.MAX_VALUE if none within reachChunks chunks. */
      double edgeDistance(int x, int z, int reachChunks);

      /** Surface height (y of the top block) at a world column. */
      int groundAt(int x, int z);
   }

   /** Optional cross-owner density multiplier used by R30ea's root planner. */
   public interface RootDensity extends TreeSite {
      double densityAt(int x, int z);
   }

   /** A disabled river/ocean tree painter must not plant shared roots just
    * because forest generation is enabled. Forestry on neighbouring natural
    * land continues through its separate FOREST ownership category. */
   public static double shoreTreeDensity(boolean enabled, int chancePercent) {
      return enabled ? Math.max(0.0, Math.min(1.0, chancePercent / 100.0)) : 0.0;
   }

   /** World-coordinate vegetation ownership, shared by forest, river bank and ocean shore. */
   private static final class SharedVegetationSite implements RootDensity {
      private static final int NONE = 0, FOREST = 1, RIVER = 2, OCEAN = 3, CHECK_DRY_BANK = 4;
      private final MetropolisGenerator generator;
      private final UrbanGrid grid;
      private final ForestTerrain terrain;
      private final WaterField water;
      private final WorldLayout layout;
      private final TreeSite river;
      private final TreeSite ocean;
      private final WildField wild;
      private final ShoreGround shoreRelief;
      private final Map<Long, Ctx> forestSites = new HashMap<>();
      // Several NATURAL cells and creek-interfluve cells share one logical
      // owner. Reuse one site per region instead of allocating a ShoreCells
      // field for every probed trunk/crown chunk.
      private final Map<MorphomosRegion, Ctx> forestContextByOwner = new java.util.IdentityHashMap<>();
      private final Map<Long, Integer> ownerCellCache = new HashMap<>();
      private final Map<Long, Integer> ownerCache = new HashMap<>();
      private final Map<Long, Boolean> allowedCache = new HashMap<>();
      // NATURAL is committed wilderness outside PlanRegions. It really is
      // painted by a MorphomosParcel (interfluveNaturalGround), but that
      // synthetic region is not queryable from an unbuilt adjacent tile.
      // A shared deterministic forest context lets roots spanning tile edges
      // use the immutable NATURAL plan instead of vanishing at that edge.
      private final MorphomosRegion plannedNaturalRootRegion =
              new MorphomosRegion(Integer.MIN_VALUE, ContextType.FOREST);

      SharedVegetationSite(MetropolisGenerator generator, UrbanGrid grid, ForestTerrain terrain) {
         this.generator = generator;
         this.grid = grid;
         this.terrain = terrain;
         this.water = grid.planWater();
         this.layout = grid.cityLayout();
         this.river = ch.k42.metropolis.grid.urbanGrid.parcel.RiverParcel.vegetationSite(grid);
         this.ocean = OceanParcel.vegetationSite(grid);
         this.wild = wildFor(generator);
         ShoreCells cells = new ShoreCells(grid);
         this.shoreRelief = this.wild != null && cells.usable()
                 ? new ShoreGround(grid, terrain, wild, cells) : null;
      }

      private static long key(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

      private int ownerCellAt(int cx, int cz) {
         long key = key(cx, cz);
         Integer saved = ownerCellCache.get(key);
         if (saved != null) return saved;
         int result = NONE;
         if (water != null && layout != null && !grid.isPortGradedCell(cx, cz)) {
            int kind = layout.kindAt(cx, cz);
            if (kind != 1 && !grid.isPlanStreet(cx, cz)) {
               // R14: The world plan, not the tile-local synthetic forest
               // parcel, owns creek-side woodland. A shared root is sampled
               // from neighbouring chunks before that neighbour's tile may
               // have been constructed. A tile-local lookup dropped those
               // roots at the border and made population order observable.
               if (grid.isWorldPlannedCreekForestAt(cx, cz)) {
                  result = FOREST;
               } else if (kind == 3 || kind == 2) {
                  MorphomosRegion region = grid.getMorphomosRegionAt(cx, cz);
                  if (sharedForestRootOwner(kind, false, region == null ? null : region.getContext())) {
                     result = FOREST;
                  }
               } else {
                  WaterField.Kind waterKind = water.kindAt(cx, cz);
                  if (waterKind == WaterField.Kind.NONE && kind == 4) {
                     waterKind = water.zoneKind(cx, cz);
                  }
                  if (waterKind == WaterField.Kind.OCEAN) result = OCEAN;
                  else if (waterKind == WaterField.Kind.RIVER || waterKind == WaterField.Kind.CREEK) result = RIVER;
                  else {
                     MorphomosRegion region = grid.getMorphomosRegionAt(cx, cz);
                     if (region != null && region.getContext() == ContextType.FOREST) result = FOREST;
                     else if (grid.isPlannedNaturalWildernessAt(cx, cz)) result = FOREST;
                     else if (kind == 4) result = CHECK_DRY_BANK;
                  }
               }
            }
         }
         ownerCellCache.put(key, result);
         return result;
      }

      private int ownerAt(int x, int z) {
         long key = key(x, z);
         Integer saved = ownerCache.get(key);
         if (saved != null) return saved;
         int owner = ownerCellAt(x >> 4, z >> 4);
         if (owner == CHECK_DRY_BANK) {
            owner = grid.creekBankAt(x, z) == null ? NONE : RIVER;
         }
         ownerCache.put(key, owner);
         return owner;
      }

      private Ctx forestAt(int x, int z) {
         int cx = x >> 4, cz = z >> 4;
         long key = key(cx, cz);
         Ctx cached = forestSites.get(key);
         if (cached != null) return cached;
         // Use the same stable synthetic root context on both sides of a
         // 64-chunk boundary; do not borrow a tile-local synthetic parcel.
         boolean planNatural = grid.isWorldPlannedCreekForestAt(cx, cz)
                 || grid.isPlannedNaturalWildernessAt(cx, cz);
         MorphomosRegion region = planNatural ? null : grid.getMorphomosRegionAt(cx, cz);
         region = sharedRootRegion(region, planNatural, plannedNaturalRootRegion);
         if (region == null || region.getContext() != ContextType.FOREST) return null;
         Ctx result = forestContextByOwner.get(region);
         if (result == null) {
            result = new Ctx(generator, grid, region, terrain, planNatural);
            forestContextByOwner.put(region, result);
         }
         forestSites.put(key, result);
         return result;
      }

      @Override public boolean allows(int x, int z) {
         long key = key(x, z);
         Boolean saved = allowedCache.get(key);
         if (saved != null) return saved;
         boolean allowed = switch (ownerAt(x, z)) {
            case FOREST -> forestAt(x, z) != null;
            case RIVER -> river.allows(x, z);
            case OCEAN -> ocean.allows(x, z);
            default -> false;
         };
         allowedCache.put(key, allowed);
         return allowed;
      }

      @Override public double fade(int x, int z) {
         if (!allows(x, z)) return 0.0;
         int owner = ownerAt(x, z);
         if (owner == FOREST) {
            Ctx ctx = forestAt(x, z);
            return ctx == null ? 0.0 : ctx.fade(x, z);
         }
         TreeSite shore = owner == RIVER ? river : ocean;
         if (wild != null) shore = new WildSite(shore, wild);
         if (shoreRelief != null) shore = new LiftSite(shore, shoreRelief, wild);
         return shore.fade(x, z);
      }

      @Override public double densityAt(int x, int z) {
         return switch (ownerAt(x, z)) {
            case FOREST -> 1.0;
            case RIVER -> shoreTreeDensity(ch.k42.metropolis.grid.urbanGrid.river.RiverConfig.isRiverTreesEnabled(),
                    ch.k42.metropolis.grid.urbanGrid.river.RiverConfig.getRiverTreesChancePercent());
            case OCEAN -> shoreTreeDensity(ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig.isOceanTreesEnabled(),
                    ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig.getOceanTreesChancePercent());
            default -> 0.0;
         };
      }

      @Override public double edgeDistance(int x, int z, int reachChunks) {
         return probeEdge(this, x, z, reachChunks);
      }

      @Override public int groundAt(int x, int z) {
         int owner = ownerAt(x, z);
         if (owner == FOREST) {
            Ctx forest = forestAt(x, z);
            return forest == null ? wildBase(grid, x, z) : forest.groundAt(x, z);
         }
         if (owner != RIVER && owner != OCEAN) return wildBase(grid, x, z);
         TreeSite shore = owner == RIVER ? river : ocean;
         return shore.groundAt(x, z) + (shoreRelief == null ? 0 : shoreRelief.lift(x, z));
      }
   }

   /** Translate a plan-backed NATURAL cell into the shared tree root mask.
    * Only actual planned wilderness gets a surrogate region; cities, farms,
    * water, and unclaimed cells never do. */
   static MorphomosRegion sharedRootRegion(MorphomosRegion actual, boolean natural,
                                          MorphomosRegion naturalContext) {
      return actual != null ? actual : (natural ? naturalContext : null);
   }

   /** R30ea only treats forest-context land as shared woodland roots in plan kinds 2/3. */
   static boolean sharedForestRootOwner(int kind, boolean excluded, ContextType context) {
      return !excluded && context == ContextType.FOREST && (kind == 3 || kind == 2);
   }

   /**
    * edgeDistance() for a site that has no region mask: walks outwards from the column in the eight compass
    * directions and reports how far it got before reaching a column that does not allow trees. Capped at
    * reachChunks chunks, which is as far as the density fade and the tree margins ever look.
    */
   public static double probeEdge(TreeSite site, int x, int z, int reachChunks) {
      int max = reachChunks * 16;
      for (int r = 2; r <= max; r += 2) {
         if (!site.allows(x + r, z) || !site.allows(x - r, z) || !site.allows(x, z + r) || !site.allows(x, z - r)
                 || !site.allows(x + r, z + r) || !site.allows(x - r, z + r)
                 || !site.allows(x + r, z - r) || !site.allows(x - r, z - r)) {
            return r - 2;
         }
      }
      return Double.MAX_VALUE;
   }

   /**
    * Plants one chunk of a river or ocean bank the way the wilderness plants itself, so the two blend where they meet: each
    * column whose real final top block is grass takes the biome the wilderness has there (the same weights, the same keep-away
    * from the cities and the same stippled pick as inland, so any biome can reach a shore and none ends at a line): forest or
    * mountain ground gets the forest type's biome (so the grass and leaf tint match) and its own ground cover; desert and mesa
    * columns take their sand or banded clay, cactus and dead bush. Then the forest's trees, wherever the site allows them and the
    * biome is wooded. The shore keeps its own height (the beach, the bank and the water edge), so only the ground's surface,
    * cover, trees and biome follow the wilderness. treePercent and coverPercent scale the forest's own tree density and ground
    * cover (100 = the same as a forest).
    */
   public static void populateShore(MetropolisGenerator generator, Chunk chunk, TreeSite site,
                                    boolean trees, int treePercent, boolean cover, int coverPercent) {
      populateShore(generator, null, chunk, site, trees, treePercent, cover, coverPercent);
   }

   /**
    * True when shores take the wilderness's relief (see populateShore): the wilderness biomes are on and the vanilla wilderness
    * is not. The river and ocean painters call populateShore() whenever this is true even if their own trees and vegetation are off.
    */
   public static boolean isShoreReliefActive() {
      return PluginConfig.isWildBiomesEnabled() && !PluginConfig.isWildVanillaEnabled();
   }

   /**
    * populateShore() with the grid, which lets a shore carry the wilderness's RELIEF as well as its biome: the ground rises and
    * falls with the biome's own amplitude (mountain ridges, dunes, mesa terraces, forest hills) and takes its surface (bare rock and
    * snow high on a mountain), and trees root on it. The relief is the very one the inland wilderness has, faded in from the water's
    * edge (0 at the waterline, full a few dozen blocks in, as far as the biome's ramp) and out toward any street, block, farm or
    * lane, so it meets the bank, the beach and the streets at their own height with no step and meets the inland wilderness with no
    * seam -- both ends read the same fade (see ShoreCells and Ctx.reliefFade). Without the grid (null) the shore keeps its own height.
    */
   public static void populateShore(MetropolisGenerator generator, UrbanGrid grid, Chunk chunk, TreeSite site,
                                    boolean trees, int treePercent, boolean cover, int coverPercent) {
      World world = generator.getWorld();
      if (world == null) {
         return;
      }
      // generator.wilderness.vanilla.enabled: the bank continues vanilla's own look; if that cannot be had it is planted as forest
      if (VanillaWilderness.isActive(world) && VanillaWilderness.populateShore(generator, chunk, portSafe(site, grid), trees, cover)) {
         return;
      }
      ForestTerrain terrain = terrainFor(generator);
      WildField wild = wildFor(generator);
      long seed = terrain.getSeed();
      ShoreGround ground = null;
      String dbgWhy = wild == null ? "OFF(no wild)" : (grid == null ? "OFF(no grid)" : "ON");
      if (wild != null && grid != null) {
         ShoreCells cells = new ShoreCells(grid);
         if (cells.usable()) {
            ground = new ShoreGround(grid, terrain, wild, cells);
         } else {
            dbgWhy = "OFF(cells not usable)";
         }
      }
      int baseX = chunk.getX() << 4;
      int baseZ = chunk.getZ() << 4;
      final boolean forestEnabled = PluginConfig.isForestGenerationEnabled();
      final int effectiveCoverPercent = cover ? coverPercent : 100;
      // An ocean-owned chunk can contain both dry beach and open water.
      // Respect the exact per-block shoreline before ANY biome/relief writer
      // touches the column, not only at ground-cover placement time. This
      // also prevents dry-surface decoration being planned against a chunk
      // whose water will be carved later in a different population order.
      boolean oceanShore = grid != null && grid.getParcel(chunk.getX(), chunk.getZ()) instanceof OceanParcel;
      double[] w = new double[ForestType.ALL.length];
      int dbgGrass = 0, dbgLifted = 0, dbgFlatGrass = 0, dbgMaxLift = 0, dbgSand = 0, dbgOther = 0;
      // debugShore edge probe: along each side of this chunk that borders the wilderness, how far the painter's own ground (before
      // any relief) stands from the wilderness's base. Both sides add the same relief there, so any non-zero value IS the wall.
      int[] edgeMin = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
      int[] edgeMax = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
      boolean[] edgeWild = new boolean[4];   // N (lz 0), S (lz 15), E (lx 15), W (lx 0)
      if (ground != null && PluginConfig.isShoreDebugEnabled()) {
         int pcx = chunk.getX();
         int pcz = chunk.getZ();
         edgeWild[0] = ground.cells.kind(pcx, pcz - 1) == CELL_WILD;
         edgeWild[1] = ground.cells.kind(pcx, pcz + 1) == CELL_WILD;
         edgeWild[2] = ground.cells.kind(pcx + 1, pcz) == CELL_WILD;
         edgeWild[3] = ground.cells.kind(pcx - 1, pcz) == CELL_WILD;
      }
      for (int lx = 0; lx < 16; lx++) {
         for (int lz = 0; lz < 16; lz++) {
            int x = baseX + lx;
            int z = baseZ + lz;
            if (oceanShore && grid.getOceanProvider().sample(x, z).distanceInside > 0.0) {
               continue; // open water is exclusively the ocean painter's property
            }
            int topY = Mats.firstAirAbove(world, x, z) - 1; // first non-air block below the surface
            if (topY < 1) {
               continue;
            }
            if (ground != null && (edgeWild[0] || edgeWild[1] || edgeWild[2] || edgeWild[3])) {
               int step = topY - wildBase(grid, x, z);
               int[] sides = {edgeWild[0] && lz == 0 ? 0 : -1, edgeWild[1] && lz == 15 ? 1 : -1, edgeWild[2] && lx == 15 ? 2 : -1, edgeWild[3] && lx == 0 ? 3 : -1};
               for (int side : sides) {
                  if (side >= 0) {
                     edgeMin[side] = Math.min(edgeMin[side], step);
                     edgeMax[side] = Math.max(edgeMax[side], step);
                  }
               }
            }
            if (wild != null && PAL.is(world.getBlockAt(x, topY, z), WildBlock.SAND)) {
               dbgSand++;
               // dry sand of the bank or beach: red where the mesa runs into it (the same pick as the ground beside it), with the
               // sand under it, so a cut or a slope shows no pale stripe
               WildBiome sandBiome = wild.pick(x, z, ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 41)));
               terrain.typeWeights(x, z, w);
               // R30ea also assigns the natural biome on dry sand, even when
               // no grass-based cover pass follows this branch.
               setShoreBiome(world, x, z, naturalShoreBiome(sandBiome, w));
               if (sandBiome.is(WildBiome.MESA)) {
                  for (int y = topY; y >= Math.max(1, topY - 3) && PAL.is(world.getBlockAt(x, y, z), WildBlock.SAND); y--) {
                     PAL.set(chunk, lx, y, lz, WildBlock.RED_SAND);
                  }
               }
               continue;
            }
            if (!PAL.is(world.getBlockAt(x, topY, z), WildBlock.GRASS_BLOCK)) {
               dbgOther++;
               continue;
            }
            terrain.typeWeights(x, z, w);
            WildBiome biome = WildBiome.FOREST;
            if (wild != null) {
               // the same pick as writeColumn: a border between two biomes grades instead of drawing a line
               biome = wild.pick(x, z, ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 41)));
            }
            // The ocean's BeachSite knows which columns belong to DRY shoreline.
            // Previously this rule gated trees and decorations, but NOT ground
            // cover. A shore painter could therefore plant legacy tallgrass in
            // a column the deterministic ocean plan reserves for water, when
            // adjacent chunks are populated in a different order.
            // Do not scan or delete finished ocean blocks; refuse the plant.
            boolean coverAllowed = (cover || forestEnabled) && ShoreCoverGate.canPlant(
                    site.allows(x, z),
                    chunk.getBlock(lx, topY, lz).getType(),
                    topY + 1 < world.getMaxHeight() ? chunk.getBlock(lx, topY + 1, lz).getType() : org.bukkit.Material.VOID_AIR);
            int lift = ground == null ? 0 : ground.lift(x, z);
            dbgGrass++;
            if (lift != 0) {
               dbgLifted++;
               dbgMaxLift = Math.max(dbgMaxLift, Math.abs(lift));
            } else {
               dbgFlatGrass++;
            }
            if (lift != 0) {
               writeShoreRelief(world, chunk, lx, lz, x, z, topY, lift, w, seed, wild, biome, ground, coverAllowed, effectiveCoverPercent);
               continue;
            }
            if (biome.is(WildBiome.DESERT)) {
               writeDesert(chunk, lx, lz, x, z, topY, seed);
               setShoreBiome(world, x, z, BiomeNames.desert());
               continue;
            }
            if (biome.is(WildBiome.MESA)) {
               writeMesa(chunk, lx, lz, x, z, topY, seed, wild.bands);
               setShoreBiome(world, x, z, BiomeNames.mesa());
               continue;
            }
            setShoreBiome(world, x, z, naturalShoreBiome(biome, w));
            if (biome == WildBiome.BLOOMLANDS || biome == WildBiome.WETLANDS || biome == WildBiome.OLD_GROWTH ||
                    biome == WildBiome.COASTAL_DUNES || biome == WildBiome.VOLCANIC_BARRENS) {
               paintHabitat(chunk, lx, lz, x, z, topY, seed, biome,
                       coverAllowed && ForestTerrain.unit(ForestTerrain.hash(seed,x,z,15))*100.0<effectiveCoverPercent,
                       false, 0);
            } else if (coverAllowed && ForestTerrain.unit(ForestTerrain.hash(seed,x,z,15))*100.0<effectiveCoverPercent) {
               cover(chunk,lx,lz,x,z,topY,w,true,seed,biome.dryness());
            }
         }
      }
      if (PluginConfig.isShoreDebugEnabled()) {
         logShore(world, chunk, ground, dbgWhy, dbgGrass, dbgLifted, dbgFlatGrass, dbgMaxLift, dbgSand, dbgOther, edgeWild, edgeMin, edgeMax);
      }
      // R30ea's primary shore tree dispatcher shares forest, riverbank, and
      // ocean dry-beach root ownership whenever a bounded city/water plan
      // exists. Do not use the local shore site in that case: it causes
      // duplicate/missing trees at region boundaries and chunk seams.
      if (grid != null && grid.planWater() != null && grid.cityLayout() != null && forestEnabled) {
         placeTrees(chunk, terrain,
                 portSafe(new SharedVegetationSite(generator, grid, terrain), grid),
                 PluginConfig.getForestTreeDensity());
      } else if (trees && treePercent > 0) {
         TreeSite wooded = wild == null ? site : new WildSite(site, wild);
         if (ground != null) {
            wooded = new LiftSite(wooded, ground, wild);
         }
         wooded = new FinalShoreSite(wooded, world, chunk);
         placeTrees(chunk, terrain, portSafe(wooded, grid), PluginConfig.getForestTreeDensity() * treePercent / 100.0);
      }
      if (wild != null && (trees || cover)) {
         // the wilderness's old things reach the shore too, on the same ground (including its relief) the trees root on
         TreeSite decorSite = ground != null ? new LiftSite(site, ground, wild) : site;
         decorSite = new FinalShoreSite(decorSite, world, chunk);
         WildDecor.populate(chunk, seed, decorEnv(generator, portSafe(decorSite, grid), wild, seed, grid), PluginConfig.getWildDecorations(), windOrigins(generator));
      }
   }

   /** The same forest owner/region context used by /windfarm to choose an
    * installation MUST be used when its other chunks paint it. A chunk's local
    * forest mask, cliff height and port setbacks can differ across regions or
    * 64-chunk tile boundaries. Construct one canonical site context per origin,
    * cached for this population pass. No world-block reads or mutable votes. */
   private static long windScope(MetropolisGenerator generator) {
      synchronized (WIND_SCOPE_IDS) {
         return WIND_SCOPE_IDS.computeIfAbsent(generator, g -> NEXT_WIND_SCOPE.getAndIncrement());
      }
   }

   private static WildDecor.WindOriginEnvironment windOrigins(final MetropolisGenerator generator) {
      final java.util.Map<Long, WildDecor.Env> cache = new java.util.HashMap<>();
      final java.util.Set<Long> invalid = new java.util.HashSet<>();
      final long scope = windScope(generator);
      return new WildDecor.WindOriginEnvironment() {
         @Override public long windPlanningScope() { return scope; }
         @Override public WildDecor.Env atOrigin(int x, int z) {
         long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
         if (invalid.contains(key)) return null;
         WildDecor.Env existing = cache.get(key);
         if (existing != null) return existing;
         ch.k42.metropolis.grid.common.Grid tile = generator.getGridProvider().getGrid(x >> 4, z >> 4);
         if (!(tile instanceof UrbanGrid originGrid)) {
            invalid.add(key);
            return null;
         }
         MorphomosRegion region = originGrid.getMorphomosRegionAt(x >> 4, z >> 4);
         if (region == null || region.getContext() != ContextType.FOREST) {
            invalid.add(key);
            return null;
         }
         Ctx site = new Ctx(generator, originGrid, region, terrainFor(generator));
         WildDecor.Env env = decorEnv(generator, portSafe(site, originGrid), site.wild, site.terrain.getSeed(), originGrid);
         cache.put(key, env);
         return env;
         }
      };
   }

   /** What the wilderness decorations (WildDecor) need to know about a site: its biome, its mesa bands, its edge and its ground. */
   private static WildDecor.Env decorEnv(final MetropolisGenerator generator, final TreeSite site, final WildField wild, final long seed, final UrbanGrid grid) {
      return new WildDecor.Env() {
         // The same origin/road may be examined thousands of times while
         // a large farm is tested. Cache final parcel ownership per chunk
         // during this painting/query call; never infer it from forest masks.
         private final java.util.Map<Long, Boolean> windParcelCache = new java.util.HashMap<>();

         @Override
         public boolean windPaintable(int x, int z) {
            if (VanillaWilderness.isActive(generator.getWorld())) return false;
            int cx = x >> 4, cz = z >> 4;
            long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
            Boolean cached = windParcelCache.get(key);
            if (cached != null) return cached;
            ch.k42.metropolis.grid.common.Grid owner = generator.getGridProvider().getGrid(cx, cz);
            boolean paintable = false;
            if (owner instanceof UrbanGrid urban) {
               Parcel parcel = urban.getParcel(cx, cz);
               paintable = parcel instanceof ch.k42.metropolis.grid.urbanGrid.parcel.MorphomosParcel forest
                       && forest.getRegion() != null && forest.getRegion().getContext() == ContextType.FOREST;
            }
            windParcelCache.put(key, paintable);
            return paintable;
         }
         @Override
         public WildBiome biomeAt(int x, int z) {
            return wild == null ? WildBiome.FOREST : wild.pick(x, z, ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 41)));
         }

         @Override
         public WildBlock band(int y) {
            return wild == null ? WildBlock.HARDENED_CLAY : wild.bands.at(y);
         }

         @Override
         public boolean allows(int x, int z) {
            return site.allows(x, z);
         }

         @Override
         public double edgeDistance(int x, int z, int reachChunks) {
            return site.edgeDistance(x, z, reachChunks);
         }

         @Override
         public int groundAt(int x, int z) {
            return site.groundAt(x, z);
         }

         @Override
         public int spacing(int prop) {
            return PluginConfig.getWildSpacing(prop);
         }

         @Override
         public int[] windPortRoad(int x, int z, int reachChunks) {
            return windPortRoad(x, z, reachChunks, 0);
         }

         @Override
         public int[] windPortRoad(int x, int z, int reachChunks, int minimumDistanceBlocks) {
            // As for the working port planner, require a real, connected street
            // and a *straight* unobstructed private access strip. No A* route
            // across unrelated parcels, no road ending in the countryside.
            // RoadShapes / PlanRegions are world-coordinate deterministic.
            // Do not clip this search at the originating 64-chunk UrbanGrid tile:
            // a valid forest corridor and paved street may be in the next tile.
            int cx = x >> 4, cz = z >> 4;
            int[][] dirs = {{1,0},{0,1},{-1,0},{0,-1}};
            int bestDistance = Integer.MAX_VALUE;
            int[] best = null;
            for (int dir = 0; dir < 4; dir++) {
               int dx = dirs[dir][0], dz = dirs[dir][1];
               // The wind facility extends about nine chunks in its exit
               // direction: enforce enough land for the substation and trunk.
               for (int d = 1; d <= reachChunks; d++) {
                  int nx = cx + dx * d, nz = cz + dz * d;
                  if (grid.isPortConstructionCell(nx, nz)) break;
                  // Read the deterministic road plan, not instantiated
                  // RoadParcel objects. This works across tile boundaries
                  // regardless of generation/load order.
                  if (grid.isPlanStreet(nx, nz)) {
                     int streetX = dx > 0 ? (nx << 4) : dx < 0 ? (nx << 4) + 15 : x;
                     int streetZ = dz > 0 ? (nz << 4) : dz < 0 ? (nz << 4) + 15 : z;
                     int blocks = dx != 0 ? Math.abs(streetX - x) : Math.abs(streetZ - z);
                     if (blocks >= minimumDistanceBlocks && d < bestDistance) {
                        bestDistance = d;
                        best = new int[]{dir, streetX, streetZ};
                     }
                     break;
                  }
                  MorphomosRegion region = grid.getMorphomosRegionAt(nx, nz);
                  // R30q may own a dry NATURAL wilderness cell without a
                  // Morphomos region. It is legitimate countryside, not a
                  // road/urban/water obstacle to a private wind-farm track.
                  if ((region == null || region.getContext() != ContextType.FOREST)
                          && !grid.isPlannedNaturalWildernessAt(nx, nz)) break;
               }
            }
            return best;
         }

         /** A real, potentially turning access track to an undissolved
          * public street. Every intermediate chunk must be paintable by the
          * final forest parcel, never an uncommitted forest-region mask. */
         @Override
         public int[] windAccessRoute(int x, int z, int reachChunks, int exitBlocks, int facing) {
            return WindAccessRouter.find(x, z, exitBlocks, facing, reachChunks,
                  new WindAccessRouter.Terrain() {
                     @Override
                     public boolean pavedStreet(int cx, int cz) {
                        return !grid.isPortConstructionCell(cx, cz) && grid.isPlanStreet(cx, cz);
                     }

                     @Override
                     public boolean wilderness(int cx, int cz) {
                        if (grid.isPortConstructionCell(cx, cz)) return false;
                        MorphomosRegion region = grid.getMorphomosRegionAt(cx, cz);
                        if ((region == null || region.getContext() != ContextType.FOREST)
                              && !grid.isPlannedNaturalWildernessAt(cx, cz)) return false;
                        int px = (cx << 4) + 8, pz = (cz << 4) + 8;
                        return site.allows(px, pz) && windPaintable(px, pz);
                     }

                     @Override
                     public boolean clearSegment(int x0, int z0, int x1, int z1, boolean streetEndpoint) {
                        int span = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
                        int steps = Math.max(1, (span + 3) / 4);
                        for (int i = 0; i <= steps; i++) {
                           if (streetEndpoint && i == steps) break;
                           int px = x0 + (x1 - x0) * i / steps;
                           int pz = z0 + (z1 - z0) * i / steps;
                           if (!site.allows(px, pz) || !windPaintable(px, pz)) return false;
                        }
                        return true;
                     }
                  });
         }

         @Override
         public int lowLimit() {
            return PluginConfig.getBuildHeight() + 14;
         }

         @Override
         public int chestOdds() {
            return PluginConfig.getWildChestOdds();
         }

         @Override
         public int lootMin() {
            return PluginConfig.getWildLootMin();
         }

         @Override
         public int lootMax() {
            return PluginConfig.getWildLootMax();
         }

         @Override
         public void chest(Chunk chunk, int x, int y, int z, int facing, String collection, int level) {
            org.bukkit.block.Block block = chunk.getBlock(x & 15, y, z & 15);
            LegacyBlocks.set(block, 54, facing);
            if (!PluginConfig.isChestRenaming()) {
               return;
            }
            try {
               org.bukkit.block.BlockState state = block.getState();
               if (state instanceof org.bukkit.block.Chest) {
                  // named the way the plugin's own chests are, so the loot plugin fills it: section sign, colour, collection, level
                  ch.k42.metropolis.minions.DirtyHacks.setChestName((org.bukkit.block.Chest) state,
                          "\u00a7" + org.bukkit.ChatColor.GREEN.getChar() + collection + "_" + level);
                  state.update();
               }
            } catch (Throwable t) {
               // an unnamed chest is still a usable chest; never let it stop the chunk
            }
         }
      };
   }

   private static String kindName(int kind) {
      return kind == CELL_WILD ? "WILD" : (kind == CELL_SHORE ? "SHORE" : "HARD");
   }

   /**
    * consoleOutput.debugShore: one line for a shore chunk, written after it is painted. Only seam chunks are logged -- relief
    * off, a wilderness chunk next to it, or a column where the bank's lift and what the inland wilderness would be there differ
    * by 2 or more -- so a pregeneration run does not drown the log. Search the log for the chunk's coordinates.
    */
   private static void logShore(World world, Chunk chunk, ShoreGround ground, String why, int grass, int lifted, int flatGrass,
                                int maxLift, int sand, int other, boolean[] edgeWild, int[] edgeMin, int[] edgeMax) {
      int cx = chunk.getX();
      int cz = chunk.getZ();
      StringBuilder sb = new StringBuilder();
      boolean interesting = ground == null;
      sb.append("chunk=(").append(cx).append(',').append(cz).append(") block=(").append(cx << 4).append(',').append(cz << 4)
              .append(") relief=").append(why)
              .append(" columns: grass=").append(grass).append(" lifted=").append(lifted).append(" grassNoLift=").append(flatGrass)
              .append(" maxLift=").append(maxLift).append(" sand=").append(sand).append(" other=").append(other);
      if (ground != null) {
         ShoreCells c = ground.cells;
         int self = c.kind(cx, cz);
         int[] around = {c.kind(cx, cz - 1), c.kind(cx, cz + 1), c.kind(cx + 1, cz), c.kind(cx - 1, cz)};
         sb.append(" | kinds self=").append(kindName(self)).append(" N=").append(kindName(around[0])).append(" S=")
                 .append(kindName(around[1])).append(" E=").append(kindName(around[2])).append(" W=").append(kindName(around[3]));
         for (int k : around) {
            if (k == CELL_WILD) {
               interesting = true;
            }
         }
         String[] sideName = {"N", "S", "E", "W"};
         for (int side = 0; side < 4; side++) {
            if (edgeWild[side] && edgeMin[side] != Integer.MAX_VALUE) {
               sb.append(" | edgeStep").append(sideName[side]).append("=").append(edgeMin[side]).append("..").append(edgeMax[side]);
            }
         }
         int bx = cx << 4;
         int bz = cz << 4;
         int[][] pts = {{8, 8}, {8, 1}, {8, 14}, {1, 8}, {14, 8}};
         for (int[] p : pts) {
            int x = bx + p[0];
            int z = bz + p[1];
            int[] li = new int[2];
            String d = ground.describe(x, z, li);
            if (Math.abs(li[0] - li[1]) >= 2) {
               interesting = true;
            }
            sb.append(" | ").append(d).append(" top=").append(world.getBlockAt(x, Mats.firstAirAbove(world, x, z) - 1, z).getType());
         }
      }
      if (interesting) {
         Minions.dShore("%s", sb.toString());
      }
   }

   /**
    * One shore column whose ground the wilderness's relief moves: the surface goes to topY + lift (the bank's own height plus the
    * relief), what was above it is cleared, and the column is rebuilt the way an inland one is -- stone under soil, sand or banded
    * clay, bare rock and snow high on a mountain -- with the shore's own ground cover and biome.
    */
   private static void writeShoreRelief(World world, Chunk chunk, int lx, int lz, int x, int z, int topY, int lift, double[] w,
                                        long seed, WildField wild, WildBiome biome, ShoreGround ground, boolean cover, int coverPercent) {
      int h = Math.max(1, topY + lift);
      for (int y = h + 1; y <= Math.max(CLEAR_TOP, topY + 2); y++) {
         PAL.set(chunk, lx, y, lz, WildBlock.AIR);
      }
      // The bank this column replaces had its own soil: a few blocks of dirt under its grass, topped by air above the old surface.
      // Everything the lifted ground is built on must be rewritten down through that soil, or it survives as a band of dirt with a
      // one-block gap of air above it under the new ground (which a mesa's striped cliffs show).
      int soilFloor = Math.max(1, Math.min(topY, 65) - 4);
      if (biome.is(WildBiome.DESERT)) {
         for (int y = h - 4; y >= soilFloor; y--) {
            PAL.set(chunk, lx, y, lz, y >= h - 8 ? WildBlock.SANDSTONE : WildBlock.STONE);
         }
         writeDesert(chunk, lx, lz, x, z, h, seed);
         setShoreBiome(world, x, z, BiomeNames.desert());
         return;
      }
      if (biome.is(WildBiome.MESA)) {
         for (int y = h; y >= soilFloor; y--) {
            PAL.set(chunk, lx, y, lz, wild.bands.at(y));
         }
         writeMesa(chunk, lx, lz, x, z, h, seed, wild.bands);
         setShoreBiome(world, x, z, BiomeNames.mesa());
         return;
      }
      for (int y = h - 4; y >= soilFloor; y--) {
         PAL.set(chunk, lx, y, lz, WildBlock.STONE);
      }
      int surface = biome.is(WildBiome.MOUNTAINS) ? ground.mountainSurface(x, z, lift) : Ctx.SURFACE_GROUND;
      paintGround(chunk, lx, lz, x, z, h, w, surface, seed, false, biome.dryness());
      if (surface == Ctx.SURFACE_GROUND) paintHabitat(chunk,lx,lz,x,z,h,seed,biome,false,false,0);
      if (biome.is(WildBiome.MOUNTAINS)) {
         int snow = PluginConfig.getForestMountainSnowLine();
         setShoreBiome(world, x, z, snow > 0 && lift >= snow - 6 ? BiomeNames.snowyMountains() : BiomeNames.mountains());
      } else {
         setShoreBiome(world,x,z,
            biome == WildBiome.PRAIRIE ? BiomeNames.prairie() :
            biome == WildBiome.WETLANDS ? BiomeNames.wetlands() :
            biome == WildBiome.OLD_GROWTH ? BiomeNames.oldGrowth() :
            biome == WildBiome.DECIDUOUS ? BiomeNames.deciduous() :
            biome == WildBiome.COASTAL_DUNES ? BiomeNames.dunes() :
            biome == WildBiome.VOLCANIC_BARRENS ? BiomeNames.volcanic() :
            biome.dryness() >= 0.5 ? BiomeNames.dryForest() : ForestTerrain.dominant(w).biome);
      }
      if (surface == Ctx.SURFACE_GROUND && cover && ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 15)) * 100.0 < coverPercent) {
         if (biome != WildBiome.COASTAL_DUNES && biome != WildBiome.VOLCANIC_BARRENS && biome != WildBiome.WETLANDS && biome != WildBiome.OLD_GROWTH)
            cover(chunk, lx, lz, x, z, h, w, true, seed, biome.dryness());
         else paintHabitat(chunk,lx,lz,x,z,h,seed,biome,true,false,0);
      }
   }

   // ------------------------------------------------------------------
   // Shore relief: the pure functions both a shore's painter and the inland wilderness read, so the ground carries on across the seam.
   // ------------------------------------------------------------------

   private static final int CELL_WILD = 0, CELL_SHORE = 1, CELL_HARD = 2;

   /**
    * Classifies chunk cells for the relief fade, from the plan alone (so any chunk, any tile, gives the same answer): WILD is a
    * forest-context region (the wilderness), SHORE is water or the land the plan hands to the water's own painter (the bank, the
    * beach, the flat shore), HARD is everything else (streets, blocks, highways, farms), where the ground must be exactly at the
    * build height. The relief fades out toward a HARD cell and in from the water, and ignores a SHORE cell as an edge.
    */
   private static final class ShoreCells {
      private final UrbanGrid grid;
      private final WaterField water;
      private final WorldLayout layout;
      private final Map<Long, Integer> cache = new HashMap<Long, Integer>();

      ShoreCells(UrbanGrid grid) {
         this.grid = grid;
         this.water = grid.planWater();
         this.layout = grid.cityLayout();
      }

      boolean usable() {
         return this.water != null && this.layout != null;
      }

      int kind(int cx, int cz) {
         long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
         Integer known = this.cache.get(key);
         if (known == null) {
            int v;
            if (this.grid.isPortGradedCell(cx, cz)) {
               v = CELL_HARD;
            } else if (this.water.kindAt(cx, cz) != WaterField.Kind.NONE || this.layout.kindAt(cx, cz) == WorldLayout.ZONE) {
               v = CELL_SHORE;
            } else {
               MorphomosRegion r = this.grid.getMorphomosRegionAt(cx, cz);
               // A naturalized road fringe has no ordinary PlanRegions region.
               // It is still wilderness, not a hard edge to flatten against.
               v = ((r != null && r.getContext() == ContextType.FOREST)
                       || this.grid.isPlannedNaturalWildernessAt(cx, cz)) ? CELL_WILD : CELL_HARD;
            }
            known = v;
            this.cache.put(key, known);
         }
         return known;
      }

      /**
       * out[0] = true distance in blocks from this column to the nearest HARD cell (Double.MAX_VALUE if none within reach chunks),
       * measured exactly as Ctx.edgeDistance() measures; out[1] = 1 if any SHORE cell lies within reach chunks, else 0.
       */
      void scan(int x, int z, int reach, double[] out) {
         int cx = x >> 4;
         int cz = z >> 4;
         double px = x + 0.5;
         double pz = z + 0.5;
         double best = Double.MAX_VALUE;
         double shore = 0.0;
         for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
               int k = kind(cx + dx, cz + dz);
               if (k == CELL_WILD) {
                  continue;
               }
               if (k == CELL_SHORE) {
                  shore = 1.0;
                  continue;
               }
               double rx0 = (cx + dx) * 16.0;
               double rz0 = (cz + dz) * 16.0;
               double ddx = Math.max(Math.max(rx0 - px, 0.0), px - (rx0 + 16.0));
               double ddz = Math.max(Math.max(rz0 - pz, 0.0), pz - (rz0 + 16.0));
               double d = Math.sqrt(ddx * ddx + ddz * ddz);
               if (d < best) {
                  best = d;
               }
            }
         }
         out[0] = best == Double.MAX_VALUE ? best : Math.max(0.0, best - 0.5);
         out[1] = shore;
      }
   }

   /**
    * Blocks from this column to the nearest edge of the main water: the merged ocean (with any river converging on it) and each
    * river's own channel. Negative inside the water. Pure in the column; creeks are not counted.
    */
   private static double waterDistance(UrbanGrid grid, int x, int z) {
      double[] nearest = new double[2];
      waterNearest(grid, x, z, nearest);
      return nearest[0];
   }

   /**
    * waterDistance() together with the build height the nearest water's own painter lays its shore and bank at (river.buildHeight
    * or ocean.buildHeight): out[0] = the distance, out[1] = that height (the wilderness's own base when there is no water).
    */
   private static void waterNearest(UrbanGrid grid, int x, int z, double[] out) {
      double best = Double.MAX_VALUE;
      double base = wildBase(grid, x, z);
      OceanProvider ocean = grid.getOceanProvider();
      if (ocean != null && ocean.isEnabled()) {
         OceanProvider.Sample s = ocean.sample(x, z);
         if (s != null && !Double.isNaN(s.distanceInside) && -s.distanceInside < best) {
            best = -s.distanceInside;
            base = ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig.getOceanBuildHeight();
         }
      }
      RiverProvider[] rivers = {grid.getRiverProvider(), grid.getZRiverProvider()};
      for (RiverProvider river : rivers) {
         if (river == null || !river.isEnabled()) {
            continue;
         }
         double along = river.isZBound() ? z : x;
         double across = river.isZBound() ? x : z;
         double d = Math.abs(across - river.spineBlock(along)) - river.widthAtBlock(along) / 2.0;
         if (d < best) {
            best = d;
            base = RiverConfig.getRiverBuildHeight();
         }
      }
      // Pass05: creek water influences the same wilderness slope blend as rivers.
      double creekDistance = grid.nearestCreekShoreDistance(x, z);
      if (creekDistance < best) {
         best = creekDistance;
         base = RiverConfig.getRiverBuildHeight();
      }
      out[0] = best;
      out[1] = base;
   }

   // ------------------------------------------------------------------
   // THE shared ground rules. The inland wilderness (Ctx) and the shores (ShoreGround) read exactly these, and nothing else, for the
   // base height, the ramp a biome's relief eases in over and the fade -- so a river, the ocean and the wilderness cannot drift apart.
   // ------------------------------------------------------------------

   /** The build height the neighbouring streets and lots sit at for this column. */
   private static int wildBaseline(UrbanGrid grid, int x, int z) {
      if (grid.isRiverReclaimedChunk(x >> 4, z >> 4)) {
         return RiverConfig.getRiverReclaimBuildHeight();
      }
      return PluginConfig.getBuildHeight();
   }

   /** The wilderness's own ground height at a column before any relief: the baseline plus forest.surfaceOffset. */
   private static int wildBase(UrbanGrid grid, int x, int z) {
      return wildBaseline(grid, x, z) + PluginConfig.getForestSurfaceOffset();
   }

   /** Blocks over which the relief at a column eases in: tall relief (mountains, mesas) takes a longer ramp than a gentle forest. */
   private static double wildBlend(int blend, double[] n) {
      double ramp = Math.max(blend, PluginConfig.getForestMountainRamp());
      return blend + (ramp - blend) * (WildBiome.weight(n, WildBiome.MOUNTAINS) + 0.5 * WildBiome.weight(n, WildBiome.MESA));
   }

   /**
    * How much of its relief the ground has at a column, 0..1: eased in from every HARD cell (a street, block, highway or farm,
    * where the ground is exactly the build height) and, where a shore cell lies within reach, from the edge of the water. THE one
    * definition, used by the inland wilderness (Ctx.reliefFade) and by every shore (ShoreGround).
    */
   private static double wildFade(UrbanGrid grid, ShoreCells cells, double blendHere, int x, int z) {
      double[] out = new double[2];
      cells.scan(x, z, (int) (blendHere / 16) + 2, out);
      double fade = out[0] == Double.MAX_VALUE ? 1.0 : ForestTerrain.smooth(out[0] / blendHere);
      if (out[1] > 0.0 && fade > 0.0) {
         fade = Math.min(fade, ForestTerrain.smooth(waterDistance(grid, x, z) / blendHere));
      }
      return fade;
   }

   /** How far into the desert (as WildField.mesaLead, below 0) the mesa's terraces have eased out completely into dunes. */
   private static final double MESA_EASE_LEAD = 0.5;

   /**
    * Each biome's own relief, blended by how much of the column it holds (the one function the inland wilderness and the shores
    * share). The mesa's terraces belong to the mesa: up to the border where the surface turns to desert (the same waved border
    * as WildField.mesaOrDesert) they stay exactly as they are, plateaus and all, and from there into the desert they ease out
    * into dunes, so sand never carries plateau steps.
    */
   private static double wildReliefAt(WildField wild, int x, int z, double[] n, double forestRelief,
                                        boolean allowKarstBridge) {
      double ordinary = WildBiome.weight(n, WildBiome.FOREST)
              - n[WildBiome.PRAIRIE.ordinal()] - n[WildBiome.WETLANDS.ordinal()]
              - n[WildBiome.COASTAL_DUNES.ordinal()] - n[WildBiome.VOLCANIC_BARRENS.ordinal()]
              - n[WildBiome.BLOOMLANDS.ordinal()];
      double r = Math.max(0.0, ordinary) * forestRelief;
      r += n[WildBiome.PRAIRIE.ordinal()] * wild.relief.prairie(x,z);
      r += n[WildBiome.WETLANDS.ordinal()] * wild.relief.wetlands(x,z);
      r += n[WildBiome.COASTAL_DUNES.ordinal()] * wild.relief.coastalDunes(x,z);
      r += n[WildBiome.VOLCANIC_BARRENS.ordinal()] * (0.55*wild.relief.mountains(x,z) + 0.45*wild.relief.profile(ch.k42.metropolis.grid.urbanGrid.wild.MountainKind.VOLCANO,x,z));
      if (WildBiome.weight(n, WildBiome.MOUNTAINS) > 0.0) {
         r += WildBiome.weight(n, WildBiome.MOUNTAINS) * wild.relief.mountains(x, z);
      }
      double dunes = 0.0;
      boolean haveDunes = false;
      if (WildBiome.weight(n, WildBiome.DESERT) > 0.0) {
         dunes = wild.relief.desert(x, z);
         haveDunes = true;
         r += WildBiome.weight(n, WildBiome.DESERT) * dunes;
      }
      if (WildBiome.weight(n, WildBiome.MESA) > 0.0) {
         double terraces = ForestTerrain.smooth(1.0 + wild.mesaLead(x, z, n) / MESA_EASE_LEAD);
         double mesa = wild.relief.mesa(x, z);
         if (terraces < 1.0) {
            if (!haveDunes) {
               dunes = wild.relief.desert(x, z);
            }
            mesa = terraces * mesa + (1.0 - terraces) * dunes;
         }
         r += WildBiome.weight(n, WildBiome.MESA) * mesa;
      }
      if (WildBiome.weight(n, WildBiome.DESERT) > 0.0) {
         // the mesa foot: past the border the desert keeps part of the smooth (unterraced) mesa height, fading out with the
         // tail of the mesa weight, so the high ground comes down as an eroded slope and not as a sandy cliff
         double foot = wild.mesaFoot(x, z) - WildBiome.weight(n, WildBiome.MESA);
         if (foot > 0.0) {
            if (!haveDunes) {
               dunes = wild.relief.desert(x, z);
            }
            r += WildBiome.weight(n, WildBiome.DESERT) * foot * Math.max(0.0, wild.relief.mesaSlope(x, z) - dunes);
         }
      }
      return BiomeEcology.relief(r, n, x, z, wild.relief, allowKarstBridge);
   }

   /** The shore's relief at a column: blocks to add to the bank's own height. */
   private static final class ShoreGround {
      private final UrbanGrid grid;
      private final ForestTerrain terrain;
      private final WildField wild;
      private final ShoreCells cells;
      private final int blend = Math.max(4, PluginConfig.getForestEdgeBlend());
      private final Map<Long, Integer> lifts = new HashMap<Long, Integer>();

      ShoreGround(UrbanGrid grid, ForestTerrain terrain, WildField wild, ShoreCells cells) {
         this.grid = grid;
         this.terrain = terrain;
         this.wild = wild;
         this.cells = cells;
      }

      int lift(int x, int z) {
         long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
         Integer known = this.lifts.get(key);
         if (known == null) {
            known = compute(x, z);
            this.lifts.put(key, known);
         }
         return known;
      }

      private int compute(int x, int z) {
         double[] n = this.wild.sharp(x, z);
         // the very fade, ramp and base the inland wilderness uses (wildFade / wildBlend / wildBase)
         double blendHere = wildBlend(this.blend, n);
         double fade = wildFade(this.grid, this.cells, blendHere, x, z);
         if (fade <= 0.0) {
            return 0;
         }
         double[] wt = new double[ForestType.ALL.length];
         this.terrain.typeWeights(x, z, wt);
         double relief = wildReliefAt(this.wild, x, z, n, this.terrain.relief(x, z, wt), false);
         return (int) Math.round(fade * (relief + this.baseGap(x, z)));
      }

      /**
       * The shore is painted at the nearest water's own build height (river.buildHeight / ocean.buildHeight); the wilderness stands
       * at its own (build height + forest.surfaceOffset). The difference is carried in with the same fade as the relief, so far from
       * the water and from any street the shore's ground is exactly the wilderness's, whatever the three settings are, and at the
       * waterline and at a street it is still the painter's own height. 0 when the settings agree.
       */
      private double baseGap(int x, int z) {
         double[] nearest = new double[2];
         waterNearest(this.grid, x, z, nearest);
         return wildBase(this.grid, x, z) - nearest[1];
      }

      /**
       * Debug (consoleOutput.debugShore): what this column's lift was, and what the inland wilderness (Ctx.reliefFade, which only
       * applies the water fade when a shore cell is in reach) would put there. li[0] = the lift applied, li[1] = the inland value.
       */
      String describe(int x, int z, int[] li) {
         double[] n = this.wild.sharp(x, z);
         double blendHere = wildBlend(this.blend, n);
         double[] out = new double[2];
         this.cells.scan(x, z, (int) (blendHere / 16) + 2, out);
         double hardFade = out[0] == Double.MAX_VALUE ? 1.0 : ForestTerrain.smooth(out[0] / blendHere);
         double waterDist = waterDistance(this.grid, x, z);
         double waterFade = ForestTerrain.smooth(waterDist / blendHere);
         double shoreFade = Math.min(hardFade, waterFade);
         double inlandFade = (out[1] > 0.0 && hardFade > 0.0) ? shoreFade : hardFade;
         double[] wt = new double[ForestType.ALL.length];
         this.terrain.typeWeights(x, z, wt);
         double relief = wildReliefAt(this.wild, x, z, n, this.terrain.relief(x, z, wt), false);
         li[0] = lift(x, z);
         li[1] = (int) Math.round(inlandFade * (relief + baseGap(x, z)));
         return String.format("(%d,%d) lift=%d inland=%d relief=%.1f hardFade=%.2f waterFade=%.2f inlandFade=%.2f hardDist=%s waterDist=%.1f blend=%.0f nF/Mt/D/Ms=%.2f/%.2f/%.2f/%.2f",
                 x, z, li[0], li[1], relief, hardFade, waterFade, inlandFade,
                 out[0] == Double.MAX_VALUE ? "none" : String.format("%.0f", out[0]), waterDist, blendHere,
                 n[WildBiome.FOREST.ordinal()], n[WildBiome.MOUNTAINS.ordinal()], n[WildBiome.DESERT.ordinal()], n[WildBiome.MESA.ordinal()]);
      }

      /** Soil, bare rock or snow for a mountain column, from its relief above the bank (as Ctx.mountainSurface() does inland). */
      int mountainSurface(int x, int z, int rel) {
         long seed = this.terrain.getSeed();
         double ragged = (ForestTerrain.unit(ForestTerrain.hash(seed, x >> 2, z >> 2, 31)) - 0.5) * 8.0;
         int snow = PluginConfig.getForestMountainSnowLine();
         if (snow > 0 && rel >= snow + ragged) {
            return Ctx.SURFACE_SNOW;
         }
         if (rel >= PluginConfig.getForestMountainTreeLine() + ragged) {
            return Ctx.SURFACE_ROCK;
         }
         if (rel > 8) {
            int slope = Math.max(Math.max(Math.abs(rel - lift(x + 1, z)), Math.abs(rel - lift(x - 1, z))),
                    Math.max(Math.abs(rel - lift(x, z + 1)), Math.abs(rel - lift(x, z - 1))));
            if (slope >= 3) {
               return Ctx.SURFACE_ROCK;
            }
         }
         return Ctx.SURFACE_GROUND;
      }
   }

   /** A shore's tree site on its relief: roots at the raised ground, and thins to nothing at a mountain's tree line. */
   private static final class FinalShoreSite implements TreeSite {
      private final TreeSite inner;
      private final World world;
      private final int chunkX, chunkZ;

      FinalShoreSite(TreeSite inner, World world, Chunk chunk) {
         this.inner = inner;
         this.world = world;
         this.chunkX = chunk.getX();
         this.chunkZ = chunk.getZ();
      }

      @Override public boolean allows(int x, int z) { return inner.allows(x, z); }
      @Override public double fade(int x, int z) { return inner.fade(x, z); }
      @Override public double edgeDistance(int x, int z, int reachChunks) {
         return inner.edgeDistance(x, z, reachChunks);
      }
      @Override public int groundAt(int x, int z) {
         int predicted = inner.groundAt(x, z);
         if ((x >> 4) != chunkX || (z >> 4) != chunkZ) {
            return predicted;
         }
         int highest = world.getHighestBlockYAt(x, z) - 1;
         // Trees and decorations may already exist above the soil. Only use
         // the actual terrain surface, never a leaf, trunk, flower or water.
         for (int y = highest; y >= Math.max(1, highest - 32); y--) {
            Material m = world.getBlockAt(x, y, z).getType();
            if (m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.STONE
                    || m == Material.SAND || m == Material.GRAVEL || m == Material.CLAY
                    || m == Material.TERRACOTTA || m == Material.WHITE_TERRACOTTA
                    || m == Material.MYCELIUM || m == Material.SNOW_BLOCK) {
               return y;
            }
         }
         return predicted;
      }
   }

   private static final class LiftSite implements TreeSite {
      private final TreeSite inner;
      private final ShoreGround ground;
      private final WildField wild;

      LiftSite(TreeSite inner, ShoreGround ground, WildField wild) {
         this.inner = inner;
         this.ground = ground;
         this.wild = wild;
      }

      @Override
      public boolean allows(int x, int z) {
         return this.inner.allows(x, z);
      }

      @Override
      public double fade(int x, int z) {
         double f = this.inner.fade(x, z);
         if (f <= 0.0) {
            return 0.0;
         }
         if (WildBiome.weight(this.wild.sharp(x, z), WildBiome.MOUNTAINS) > 0.2) {
            double line = PluginConfig.getForestMountainTreeLine();
            int rel = this.ground.lift(x, z);
            if (rel >= line) {
               return 0.0;
            }
            f = Math.min(f, ForestTerrain.clamp((line - rel) / 12.0, 0.0, 1.0));
         }
         return f;
      }

      @Override
      public double edgeDistance(int x, int z, int reachChunks) {
         return this.inner.edgeDistance(x, z, reachChunks);
      }

      @Override
      public int groundAt(int x, int z) {
         return this.inner.groundAt(x, z) + this.ground.lift(x, z);
      }
   }

   /** R30ea's canonical biome assignment for dry bank and beach columns. */
   private static Biome naturalShoreBiome(WildBiome biome, double[] weights) {
      if (biome == WildBiome.BLOOMLANDS) return BiomeNames.bloomlands();
      if (biome == WildBiome.PRAIRIE) return BiomeNames.prairie();
      if (biome == WildBiome.WETLANDS) return BiomeNames.wetlands();
      if (biome == WildBiome.OLD_GROWTH) return BiomeNames.oldGrowth();
      if (biome == WildBiome.DECIDUOUS) return BiomeNames.deciduous();
      if (biome == WildBiome.COASTAL_DUNES) return BiomeNames.dunes();
      if (biome == WildBiome.VOLCANIC_BARRENS) return BiomeNames.volcanic();
      if (biome.is(WildBiome.DESERT)) return BiomeNames.desert();
      if (biome.is(WildBiome.MESA)) return BiomeNames.mesa();
      if (biome.is(WildBiome.MOUNTAINS)) return BiomeNames.mountains();
      return biome.dryness() >= 0.5 ? BiomeNames.dryForest() : ForestTerrain.dominant(weights).biome;
   }

   private static void setShoreBiome(World world, int x, int z, Biome biome) {
      try {
         world.setBiome(x, z, biome);
      } catch (Throwable ignored) {
         // biome tinting is cosmetic; never let it stop terrain
      }
   }

   /** A shore's tree site with the wilderness's own thinning: trees stop where the biome is desert, mesa or bare mountain. */
   private static final class WildSite implements TreeSite {
      private final TreeSite inner;
      private final WildField wild;

      WildSite(TreeSite inner, WildField wild) {
         this.inner = inner;
         this.wild = wild;
      }

      @Override
      public boolean allows(int x, int z) {
         return this.inner.allows(x, z);
      }

      @Override
      public double fade(int x, int z) {
         double f = this.inner.fade(x, z);
         if (f <= 0.0) {
            return 0.0;
         }
         double[] n = this.wild.sharp(x, z);
         return Math.min(f, ForestTerrain.smooth((WildBiome.treeWeight(n) - 0.25) / 0.5));
      }

      @Override
      public double edgeDistance(int x, int z, int reachChunks) {
         return this.inner.edgeDistance(x, z, reachChunks);
      }

      @Override
      public int groundAt(int x, int z) {
         return this.inner.groundAt(x, z);
      }
   }

   /**
    * The normal-overworld pre-paint safety cap at Y=64 remains intentional
    * in chunks that have not been populated. When this regular forest-column
    * painter raises the surface above Y=64, the cap is now buried substrate;
    * normalize only that buried block so no internal smoothstone seam remains.
    * Exposed caps and all other materials are kept.
    */
   static Material buriedFallbackCap(Material existing, int surfaceY, int blockY) {
      return existing == Material.SMOOTH_STONE && blockY == 64 && surfaceY > blockY
              ? Material.STONE : existing;
   }

   private static void writeColumn(Chunk chunk, int lx, int lz, int x, int z, int h, double[] w, Ctx ctx, WildBiome biome) {
      if (h > 64) {
         Material existingCap = chunk.getBlock(lx, 64, lz).getType();
         Material naturalSubstrate = buriedFallbackCap(existingCap, h, 64);
         if (naturalSubstrate != existingCap) PAL.set(chunk, lx, 64, lz, WildBlock.STONE);
      }
      for (int y = h + 1; y <= CLEAR_TOP; y++) {
         PAL.set(chunk, lx, y, lz, WildBlock.AIR);
      }
      if (ctx.wild != null) {
         if (biome.is(WildBiome.DESERT)) {
            writeDesert(chunk, lx, lz, x, z, h, ctx.terrain.getSeed());
            return;
         }
         if (biome.is(WildBiome.MESA)) {
            writeMesa(chunk, lx, lz, x, z, h, ctx.terrain.getSeed(), ctx.wild.bands);
            return;
         }
      }
      for (int y = h - 4; y >= 65; y--) {
         Material existing = chunk.getBlock(lx, y, lz).getType();
         if (!existing.name().endsWith("_ORE")) PAL.set(chunk, lx, y, lz, WildBlock.STONE);
      }
      int surface = ctx.surfaceKind(x, z, h, biome);
      paintGround(chunk, lx, lz, x, z, h, w, surface, ctx.terrain.getSeed(), true, biome.dryness());
      if (surface == Ctx.SURFACE_GROUND) paintHabitat(chunk,lx,lz,x,z,h,ctx.terrain.getSeed(),biome,true,
                  ctx.karstBridgeAllowed(x,z,ctx.sharpWeights(x,z)),ctx.baseline(x,z)+ctx.offset);
   }

   /** Biome-specific soil, rock, and supported ground cover shared by inland and shore painters.
    * No artificial water is inserted; river/ocean painters retain water and shoreline ownership. */
   private static void paintHabitat(Chunk chunk, int lx, int lz, int x, int z, int h, long seed,
                                    WildBiome b, boolean plants, boolean allowKarstBridge, int baseY) {
      // Ground was first painted generically; clear ONLY its temporary plant
      // before switching substrate. A fern on a new sand or basalt block would
      // otherwise appear suspended or unsupported after the material change.
      if (b == WildBiome.WETLANDS || b == WildBiome.OLD_GROWTH || b == WildBiome.COASTAL_DUNES || b == WildBiome.VOLCANIC_BARRENS) {
         Material upper = chunk.getBlock(lx,h+1,lz).getType();
         if (upper == Material.SHORT_GRASS || upper == Material.FERN || upper == Material.DEAD_BUSH ||
                 upper == Material.DANDELION || upper == Material.POPPY || upper == Material.BROWN_MUSHROOM ||
                 upper == Material.RED_MUSHROOM || upper.name().contains("TULIP") || upper.name().contains("ORCHID") ||
                 upper.name().contains("AZURE_BLUET") || upper.name().contains("ALLIUM") ||
                 upper.name().contains("OXEYE_DAISY")) PAL.set(chunk,lx,h+1,lz,WildBlock.AIR);
      }
      if (b == WildBiome.COASTAL_DUNES) {
         for (int y = Math.max(1,h-3); y <= h; y++) PAL.set(chunk,lx,y,lz,y==h-3 ? WildBlock.SANDSTONE : WildBlock.SAND);
         if (plants && ForestTerrain.unit(ForestTerrain.hash(seed,x,z,101)) < .012)
            PAL.set(chunk,lx,h+1,lz,WildBlock.DEAD_BUSH);
      } else if (b == WildBiome.VOLCANIC_BARRENS) {
         long patch=ForestTerrain.hash(seed, x>>3,z>>3,102);
         WildBlock rock=ForestTerrain.unit(patch)<.60 ? WildBlock.BASALT : WildBlock.TUFF;
         for (int y=Math.max(1,h-3);y<h;y++) PAL.set(chunk,lx,y,lz,rock);
         PAL.set(chunk,lx,h,lz,ForestTerrain.unit(ForestTerrain.hash(seed,x,z,103))<.28 ? WildBlock.BLACKSTONE : rock);
      } else if (b == WildBiome.WETLANDS) {
         double n=ForestTerrain.unit(ForestTerrain.hash(seed,x>>2,z>>2,104));
         PAL.set(chunk,lx,h,lz,n<.68 ? WildBlock.MUD : WildBlock.GRASS_BLOCK);
         if (plants && n>=.68 && ForestTerrain.unit(ForestTerrain.hash(seed,x,z,105))<.27)
            PAL.set(chunk,lx,h+1,lz,WildBlock.FERN);
      } else if (b == WildBiome.OLD_GROWTH) {
         double patch=ForestTerrain.unit(ForestTerrain.hash(seed,x>>2,z>>2,106));
         PAL.set(chunk,lx,h,lz,patch<.50 ? WildBlock.MOSS : WildBlock.PODZOL);
         if (plants && patch<.50 && ForestTerrain.unit(ForestTerrain.hash(seed,x,z,107))<.28)
            PAL.set(chunk,lx,h+1,lz,WildBlock.FERN);
      } else if (b == WildBiome.PRAIRIE) {
         if (plants && ForestTerrain.unit(ForestTerrain.hash(seed,x,z,108))<.32)
            PAL.set(chunk,lx,h+1,lz,WildBlock.TALL_GRASS);
      } else if (b == WildBiome.DECIDUOUS) {
         if (plants && ForestTerrain.unit(ForestTerrain.hash(seed,x,z,109))<.14)
            PAL.set(chunk,lx,h+1,lz,WildBlock.FERN);
      }
      BiomeEcology.paintGround(chunk, lx, lz, x, z, h, seed, b, plants, allowKarstBridge, baseY);
   }

   /** The top of a column of ground: bare rock or snow, or soil with grass (or podzol, coarse dirt) and, if plants, its ground cover. */
   private static void paintGround(Chunk chunk, int lx, int lz, int x, int z, int h, double[] w, int surface, long seed, boolean plants) {
      paintGround(chunk, lx, lz, x, z, h, w, surface, seed, plants, 0.0);
   }

   /** As above, for a biome with the given dryness (0..1: more coarse dirt, and the ground cover dries, see cover). */
   private static void paintGround(Chunk chunk, int lx, int lz, int x, int z, int h, double[] w, int surface, long seed, boolean plants, double dry) {
      if (surface != Ctx.SURFACE_GROUND) {
         // Above the tree line, or on a slope too steep to hold soil: bare rock, and snow higher up.
         for (int y = h - 1; y >= h - 3 && y >= 1; y--) {
            PAL.set(chunk, lx, y, lz, WildBlock.STONE);
         }
         WildBlock top = WildBlock.STONE;
         double r = ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 32));
         if (surface == Ctx.SURFACE_SNOW) {
            top = WildBlock.SNOW_BLOCK;
         } else if (r < 0.12) {
            top = WildBlock.GRAVEL;
         } else if (r < 0.18) {
            top = WildBlock.COBBLESTONE;
         } else if (r < 0.30) {
            top = WildBlock.ANDESITE;
         }
         PAL.set(chunk, lx, h, lz, top);
         return;
      }
      for (int y = h - 1; y >= h - 3 && y >= 1; y--) {
         PAL.set(chunk, lx, y, lz, WildBlock.DIRT);
      }

      double roll = ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 11));
      double rain = w[ForestType.RAINFOREST.ordinal()];
      double podzol = w[ForestType.SPRUCE_TAIGA.ordinal()] * 0.40 + w[ForestType.DARK_FOREST.ordinal()] * 0.12 + rain * 0.55;
      double coarse = w[ForestType.SPRUCE_TAIGA.ordinal()] * 0.06 + dry * 0.55 + rain * 0.10;
      boolean grass = true;
      if (roll < podzol) {
         PAL.set(chunk, lx, h, lz, WildBlock.PODZOL);
         grass = false;
      } else if (roll < podzol + coarse) {
         PAL.set(chunk, lx, h, lz, WildBlock.COARSE_DIRT);
         grass = false;
      } else {
         PAL.set(chunk, lx, h, lz, WildBlock.GRASS_BLOCK);
      }

      if (plants) {
         cover(chunk, lx, lz, x, z, h, w, grass, seed, dry);
      }
   }

   /** Desert: sand over sandstone, with the odd cactus and dead bush. */
   private static void writeDesert(Chunk chunk, int lx, int lz, int x, int z, int h, long seed) {
      for (int y = h - 4; y >= 65; y--) {
         PAL.set(chunk, lx, y, lz, y >= h - 8 ? WildBlock.SANDSTONE : WildBlock.STONE);
      }
      for (int y = h - 3; y <= h; y++) {
         if (y >= 1) {
            PAL.set(chunk, lx, y, lz, WildBlock.SAND);
         }
      }
      // one possible cactus per 6x6 cell, placed inside its inner 4x4, so two never touch
      long cell = ForestTerrain.hash(seed, Math.floorDiv(x, 6), Math.floorDiv(z, 6), 52);
      int cx = 1 + (int) ((cell >>> 8) % 4);
      int cz = 1 + (int) ((cell >>> 20) % 4);
      if (Math.floorMod(x, 6) == cx && Math.floorMod(z, 6) == cz && ForestTerrain.unit(cell) < 0.16) {
         int tall = 1 + (int) ((cell >>> 32) % 3);
         for (int k = 1; k <= tall; k++) {
            PAL.set(chunk, lx, h + k, lz, WildBlock.CACTUS);
         }
      } else if (ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 53)) < 0.012) {
         PAL.set(chunk, lx, h + 1, lz, WildBlock.DEAD_BUSH);
      }
   }

   /** Mesa: the whole column is banded clay, so a terrace's cliff shows its stripes; red sand patches the tops. */
   private static void writeMesa(Chunk chunk, int lx, int lz, int x, int z, int h, long seed, MesaBands bands) {
      for (int y = h; y >= 65; y--) {
         PAL.set(chunk, lx, y, lz, bands.at(y));
      }
      for (int y = Math.min(64, h); y >= h - 3 && y >= 1; y--) {
         PAL.set(chunk, lx, y, lz, bands.at(y));
      }
      if (ForestTerrain.unit(ForestTerrain.hash(seed, x >> 1, z >> 1, 54)) < 0.40) {
         PAL.set(chunk, lx, h, lz, WildBlock.RED_SAND);
      }
      if (ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 55)) < 0.006) {
         PAL.set(chunk, lx, h + 1, lz, WildBlock.DEAD_BUSH);
      }
   }

   /** The biome of the column: the forest type's, or desert / mesa / mountains (snowy high up) so grass tint and weather match. */
   private static Biome biomeFor(Ctx ctx, int x, int z, int h, double[] w, WildBiome b) {
      if (ctx.wild != null) {
         if (b == WildBiome.BLOOMLANDS) return BiomeNames.bloomlands();
         if (b == WildBiome.PRAIRIE) return BiomeNames.prairie();
         if (b == WildBiome.WETLANDS) return BiomeNames.wetlands();
         if (b == WildBiome.OLD_GROWTH) return BiomeNames.oldGrowth();
         if (b == WildBiome.DECIDUOUS) return BiomeNames.deciduous();
         if (b == WildBiome.COASTAL_DUNES) return BiomeNames.dunes();
         if (b == WildBiome.VOLCANIC_BARRENS) return BiomeNames.volcanic();
         if (b.is(WildBiome.DESERT)) {
            return BiomeNames.desert();
         }
         if (b.is(WildBiome.MESA)) {
            return BiomeNames.mesa();
         }
         if (b.is(WildBiome.FOREST) && b.dryness() >= 0.5) {
            return BiomeNames.dryForest();
         }
         if (b.is(WildBiome.MOUNTAINS)) {
            int rel = h - (ctx.baseline(x, z) + ctx.offset);
            int snow = PluginConfig.getForestMountainSnowLine();
            return snow > 0 && rel >= snow - 6 ? BiomeNames.snowyMountains() : BiomeNames.mountains();
         }
      } else if (ctx.mountain != null) {
         int rel = h - (ctx.baseline(x, z) + ctx.offset);
         int snow = PluginConfig.getForestMountainSnowLine();
         if (snow > 0 && rel >= snow - 6) {
            return BiomeNames.snowyMountains();
         }
         if (rel >= PluginConfig.getForestMountainTreeLine() - 6) {
            return BiomeNames.mountains();
         }
      }
      return ForestTerrain.dominant(w).biome;
   }

   /** Ground plants. Trees are drawn afterwards and may replace them. */
   private static void cover(Chunk chunk, int lx, int lz, int x, int z, int h, double[] w, boolean grass, long seed) {
      cover(chunk, lx, lz, x, z, h, w, grass, seed, 0.0);
   }

   /**
    * As above, for ground with the given dryness (0..1). Dry ground grows no mushrooms or ferns and few flowers or tall grass, and
    * dead bushes stand on its bare dirt (a dead bush needs dirt under it, so only where the top is not grass).
    */
   private static void cover(Chunk chunk, int lx, int lz, int x, int z, int h, double[] w, boolean grass, long seed, double dry) {
      double r = ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 12));
      double oak = w[ForestType.OAK_WOODLAND.ordinal()];
      double birch = w[ForestType.BIRCH_GROVE.ordinal()];
      double spruce = w[ForestType.SPRUCE_TAIGA.ordinal()];
      double dark = w[ForestType.DARK_FOREST.ordinal()];
      double meadow = w[ForestType.MEADOW_WOODS.ordinal()];

      double rain = w[ForestType.RAINFOREST.ordinal()];
      double wet = 1.0 - dry;
      double mushroom = (dark * 0.012 + rain * 0.02) * wet;
      double fern = (spruce * 0.14 + dark * 0.05 + rain * 0.34) * wet;
      double flower = (0.004 + meadow * 0.07 + birch * 0.012 + oak * 0.006) * (1.0 - 0.85 * dry) * (1.0 - 0.7 * rain);
      double tall = grass ? (0.08 + (oak + birch) * 0.16 + meadow * 0.30 + rain * 0.12) * (1.0 - 0.75 * dry) : 0.0;
      double dead = grass ? 0.0 : dry * 0.20;

      WildBlock plant = null;
      double acc = mushroom;
      if (r < acc) {
         plant = ForestTerrain.unit(ForestTerrain.hash(seed, x, z, 13)) < 0.5 ? WildBlock.BROWN_MUSHROOM : WildBlock.RED_MUSHROOM;
      } else if (r < (acc += fern)) {
         plant = WildBlock.FERN;
      } else if (grass && r < (acc += flower)) {
         // flowers grow in drifts: one kind per 4x4 patch
         long patch = ForestTerrain.hash(seed, x >> 2, z >> 2, 14);
         int kind = (int) (patch >>> 40) % 10;
         plant = kind == 9 ? WildBlock.DANDELION : WildBlock.FLOWERS[kind];
      } else if (r < (acc += tall)) {
         plant = WildBlock.TALL_GRASS;
      } else if (r < (acc += dead)) {
         plant = WildBlock.DEAD_BUSH;
      }
      if (plant != null && h + 1 < chunk.getWorld().getMaxHeight()
              && chunk.getBlock(lx, h + 1, lz).getType().isAir()
              && (chunk.getBlock(lx, h, lz).getType() == Material.GRASS_BLOCK
                  || Mats.DIRT_FAMILY.contains(chunk.getBlock(lx, h, lz).getType())
                  || chunk.getBlock(lx, h, lz).getType() == Material.SAND
                  || chunk.getBlock(lx, h, lz).getType() == Material.RED_SAND)) {
         PAL.set(chunk, lx, h + 1, lz, plant);
      }
   }

   /** A tree crown must never be clipped by the edge of a port apron or
    * its graded access lane. Reject only roots within 14 blocks of reserved
    * port chunks. The shared biome/relief stays continuous beyond that verge. */
   private static TreeSite portSafe(final TreeSite site, final UrbanGrid grid) {
      if (grid == null) return site;
      if (site instanceof RootDensity root) {
         return new RootDensity() {
            @Override public double densityAt(int x, int z) { return root.densityAt(x, z); }
            @Override public boolean allows(int x, int z) {
               return root.allows(x, z) && portDistance(x, z, grid) >= 14;
            }
            @Override public double fade(int x, int z) { return root.fade(x, z); }
            @Override public int groundAt(int x, int z) { return root.groundAt(x, z); }
            @Override public double edgeDistance(int x, int z, int reach) {
               return Math.min(root.edgeDistance(x, z, reach), portDistance(x, z, grid));
            }
         };
      }
      return new TreeSite() {
         @Override public boolean allows(int x, int z) {
            return site.allows(x, z) && portDistance(x, z, grid) >= 14;
         }
         @Override public double fade(int x, int z) { return site.fade(x, z); }
         @Override public int groundAt(int x, int z) { return site.groundAt(x, z); }
         @Override public double edgeDistance(int x, int z, int reach) {
            return Math.min(site.edgeDistance(x, z, reach), portDistance(x, z, grid));
         }
      };
   }

   /** Euclidean distance to reserved port chunk rectangles, independent of
    * their population order; only looks inside the local 3x3 chunk vicinity. */
   private static double portDistance(int x, int z, UrbanGrid grid) {
      int cx = x >> 4, cz = z >> 4;
      double best = Double.MAX_VALUE;
      for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
         int nx = cx + dx, nz = cz + dz;
         if (!grid.isPortConstructionCell(nx, nz)) continue;
         double ax = Math.max(Math.max((nx << 4) - x, 0), x - ((nx << 4) + 15));
         double az = Math.max(Math.max((nz << 4) - z, 0), z - ((nz << 4) + 15));
         best = Math.min(best, Math.hypot(ax, az));
      }
      return best;
   }

   private static void placeTrees(Chunk chunk, ForestTerrain terrain, final TreeSite site, double density) {
      int minX = chunk.getX() << 4;
      int minZ = chunk.getZ() << 4;
      ForestTrees trees = new ForestTrees(chunk);
      ForestTrees.GroundLookup ground = new ForestTrees.GroundLookup() {
         @Override
         public int groundAt(int x, int z) {
            return site.groundAt(x, z);
         }
         @Override public boolean allowsAt(int x, int z) { return site.allows(x, z); }
      };

      int reach = ForestTrees.MAX_REACH;
      int cellMinX = floorDiv(minX - reach, SITE);
      int cellMaxX = floorDiv(minX + 15 + reach, SITE);
      int cellMinZ = floorDiv(minZ - reach, SITE);
      int cellMaxZ = floorDiv(minZ + 15 + reach, SITE);

      double[] w = new double[ForestType.ALL.length];
      long seed = terrain.getSeed();

      for (int cx = cellMinX; cx <= cellMaxX; cx++) {
         for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
            long h = ForestTerrain.hash(seed, cx, cz, 21);
            int ox = cx * SITE + (int) ((h >>> 8) % SITE);
            int oz = cz * SITE + (int) ((h >>> 24) % SITE);
            if (!site.allows(ox, oz)) {
               continue;
            }

            // trees thin out toward a mountain's tree line and stop at it
            double treeLineFade = site.fade(ox, oz);
            if (treeLineFade <= 0.0) {
               continue;
            }

            terrain.typeWeights(ox, oz, w);
            ForestType type = ForestTerrain.pick(w, ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, 22)));
            boolean bush = ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, 23)) < (type == ForestType.MEADOW_WOODS ? 0.35 : 0.08);
            long treeHash = ForestTerrain.hash(seed, cx, cz, 25);

            int margin = ForestTrees.margin(type, bush, treeHash);
            double edge = site.edgeDistance(ox, oz, 1 + margin / 16);
            if (edge < margin) {
               continue;
            }

            double rootDensity = site instanceof RootDensity roots ? roots.densityAt(ox, oz) : 1.0;
            double p = (bush ? 0.5 : type.treeDensity) * density * rootDensity * (1.0 - terrain.openness(ox, oz));
            p *= Math.min(1.0, 0.4 + edge / 24.0) * treeLineFade;
            if (ForestTerrain.unit(ForestTerrain.hash(seed, cx, cz, 24)) >= p) {
               continue;
            }

            // A shared forest/river/ocean root can be allowed at its trunk and at
            // the eight coarse edge probes while an unsampled corner of its
            // crown/limb footprint lands in a creek, road or protected parcel.
            // Check the chosen tree's full horizontal margin against the SAME
            // immutable, world-coordinate owner used by the root selector.
            // Only accepted trees pay this per-block cost; no extra chunk
            // loads, no painter overrides and no population-order dependent
            // block-state reads are introduced here.
            if (!footprintAllows(site, ox, oz, margin)) {
               continue;
            }
            trees.place(type, bush, ox, oz, treeHash, ground);
         }
      }
   }

   /** Per-call context: the region mask, the terrain fields and the edge/height maths. */
   /**
    * Deterministic pre-placement clearance for the intended tree's bounding
    * footprint. Unlike probeEdge's eight spokes, this includes odd offsets
    * and off-axis corners. This is a veto only: normal forest/shore density,
    * type and candidate hashes stay unchanged.
    */
   static boolean footprintAllows(TreeSite site, int x, int z, int margin) {
      if (site == null || margin < 0 || !site.allows(x, z)) return false;
      for (int dx = -margin; dx <= margin; dx++) {
         for (int dz = -margin; dz <= margin; dz++) {
            if (!site.allows(x + dx, z + dz)) return false;
         }
      }
      return true;
   }

   private static final class Ctx implements TreeSite {
      final MetropolisGenerator generator;
      final UrbanGrid grid;
      final MorphomosRegion region;
      final ForestTerrain terrain;
      final int blend;
      final int offset;
      /** True for a synthetic tree root on planned NATURAL, which has no
       * globally published MorphomosRegion but is still real wilderness. */
      final boolean plannedNaturalRoot;
      /** Mountain-range field of this region, or null for ordinary forest. */
      final ForestMountains.Field mountain;
      final ForestMountains mountains;
      /** The wilderness fields, or null when generator.wilderness.biomes is off (then the old per-region forest and mountains apply). */
      final WildField wild;
      final double[] scratch = new double[ForestType.ALL.length];
      /** Whether a chunk cell counts as this wilderness (see sameKind), remembered for the life of this chunk's work. */
      private final Map<Long, Boolean> kindCache = new HashMap<Long, Boolean>();
      /** Cache strict chunk-footprint ownership checks for natural arches. */
      private final Map<Long, Boolean> karstBridgeOwnerCache = new HashMap<Long, Boolean>();
      /** The relief fade's view of the plan: wilderness, shore and hard cells (null without the wilderness biomes). */
      private ShoreCells cells;

      static final int SURFACE_GROUND = 0;
      static final int SURFACE_ROCK = 1;
      static final int SURFACE_SNOW = 2;

      Ctx(MetropolisGenerator generator, UrbanGrid grid, MorphomosRegion region, ForestTerrain terrain) {
         this(generator, grid, region, terrain, false);
      }

      Ctx(MetropolisGenerator generator, UrbanGrid grid, MorphomosRegion region,
          ForestTerrain terrain, boolean plannedNaturalRoot) {
         this.plannedNaturalRoot = plannedNaturalRoot;
         this.wild = wildFor(generator);
         if (this.wild == null && PluginConfig.isForestMountainsEnabled()) {
            this.mountains = mountainsFor(generator);
            this.mountain = this.mountains.fieldFor(region);
         } else {
            this.mountains = null;
            this.mountain = null;
         }
         this.generator = generator;
         this.grid = grid;
         this.region = region;
         this.terrain = terrain;
         if (this.wild != null) {
            ShoreCells c = new ShoreCells(grid);
            this.cells = c.usable() ? c : null;
         }
         this.blend = Math.max(4, PluginConfig.getForestEdgeBlend());
         this.offset = PluginConfig.getForestSurfaceOffset();
      }

      /** The build height the neighbouring streets and lots sit at for this column. */
      int baseline(int x, int z) {
         return wildBaseline(this.grid, x, z);
      }

      /**
       * Blocks from this column to the nearest chunk cell outside the region,
       * 0 for the outermost ring of the region, Double.MAX_VALUE when no such
       * cell lies within reach chunks.
       */
      @Override
      public double edgeDistance(int x, int z, int reach) {
         if (this.mountain != null) {
            // exact distance from the precomputed field; same meaning, but it also sees far past `reach`
            double d = this.mountain.depth(x, z);
            return d <= (reach + 1) * 16.0 ? d : Double.MAX_VALUE;
         }
         int cx = x >> 4;
         int cz = z >> 4;
         double px = x + 0.5;
         double pz = z + 0.5;
         double best = Double.MAX_VALUE;
         for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
               if (this.sameKind(cx + dx, cz + dz)) {
                  continue;
               }
               double rx0 = (cx + dx) * 16.0;
               double rz0 = (cz + dz) * 16.0;
               double ddx = Math.max(Math.max(rx0 - px, 0.0), px - (rx0 + 16.0));
               double ddz = Math.max(Math.max(rz0 - pz, 0.0), pz - (rz0 + 16.0));
               double d = Math.sqrt(ddx * ddx + ddz * ddz);
               if (d < best) {
                  best = d;
               }
            }
         }
         return best == Double.MAX_VALUE ? best : Math.max(0.0, best - 0.5);
      }

      @Override
      public boolean allows(int x, int z) {
         return this.sameKind(x >> 4, z >> 4);
      }

      /**
       * Is this chunk cell the same wilderness as the one being generated? With the wilderness biomes on, any forest-context region
       * is: two forest regions that meet (the plan cuts them at a street line) are one unbroken forest, so there is no edge between
       * them and the ground carries straight on. Off, only this very region is, as before. Roads, farms, cities, highways and water
       * are never the same kind, so those stay real edges.
       */
      boolean sameKind(int cx, int cz) {
         long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
         Boolean known = this.kindCache.get(key);
         if (known == null) {
            MorphomosRegion r = this.grid.getMorphomosRegionAt(cx, cz);
            // CreekReclaim's natural forest has its own tile-local MorphomosRegion,
            // not a PlanRegions region. This region owns some cells explicitly;
            // globally planned dry creek forest also continues beyond tile edges.
            boolean same = r == this.region
                    || (this.wild != null && r != null && r.getContext() == this.region.getContext())
                    || this.region.contains(cx, cz)
                    || (this.grid.isCreekNaturalForestRegion(this.region)
                            && this.grid.isPlannedCreekNaturalForestAt(cx, cz))
                    || ((this.wild != null || this.plannedNaturalRoot
                            || this.grid.isCreekNaturalForestRegion(this.region))
                            && (this.grid.isPlannedNaturalWildernessAt(cx, cz)
                                || this.grid.isWorldPlannedCreekForestAt(cx, cz)));
            known = same;
            this.kindCache.put(key, known);
         }
         return known;
      }

      /** Biome weights here pulled toward the biome cores (see WildBiomes.sharpen); the wilderness must be on. */
      double[] sharpWeights(int x, int z) {
         return this.wild.sharp(x, z);
      }

       /** Artificial Karst landbridges are disabled; natural massif and ravine relief remain active. */
       boolean karstBridgeAllowed(int x, int z, double[] weights) {
          return false;
       }

      /** The relief of the wilderness at a column: each biome's own relief, blended by how much of the column it holds. */
      private double wildRelief(int x, int z, double[] n, double forestRelief) {
         return wildReliefAt(this.wild, x, z, n, forestRelief, this.karstBridgeAllowed(x, z, n));
      }

      /**
       * How much of its relief the ground has at a column, 0..1: eased in from every HARD edge of the wilderness (a street, block,
       * highway or farm, where the ground is exactly the build height) and from the edge of the water. The land the plan hands to
       * a river or the ocean (its bank, beach and shore) is NOT an edge here: it carries the relief itself (populateShore), so the
       * wilderness runs on into it, fading only with the distance to the water. ShoreGround.compute() is the same fade on the other side.
       */
      private double reliefFade(int x, int z, double blendHere) {
         int reach = (int) (blendHere / 16) + 2;
         if (this.cells == null) {
            double edge = edgeDistance(x, z, reach);
            return edge == Double.MAX_VALUE ? 1.0 : ForestTerrain.smooth(edge / blendHere);
         }
         return wildFade(this.grid, this.cells, blendHere, x, z);
      }

      /** Soil, bare rock or snow for the column. Only a mountain's heights ever take rock and snow; the other biomes have soil of their own. */
      int surfaceKind(int x, int z, int h, WildBiome biome) {
         if (this.wild != null) {
            return biome.is(WildBiome.MOUNTAINS) ? this.mountainSurface(x, z, h) : SURFACE_GROUND;
         }
         return this.mountain != null ? this.mountainSurface(x, z, h) : SURFACE_GROUND;
      }

      @Override
      public double fade(int x, int z) {
         if (this.wild != null) {
            // trees thin out as the forest gives way to desert, mesa or bare mountain, and stop at a mountain's tree line
            double[] n = this.sharpWeights(x, z);
            double f = ForestTerrain.smooth((WildBiome.treeWeight(n) - 0.25) / 0.5);
            if (f <= 0.0) {
               return 0.0;
            }
            if (WildBiome.weight(n, WildBiome.MOUNTAINS) > 0.2) {
               int rel = height(x, z) - (baseline(x, z) + this.offset);
               double line = PluginConfig.getForestMountainTreeLine();
               if (rel >= line) {
                  return 0.0;
               }
               f = Math.min(f, ForestTerrain.clamp((line - rel) / 12.0, 0.0, 1.0));
            }
            return f;
         }
         if (this.mountain == null) {
            return 1.0;
         }
         int rel = height(x, z) - (baseline(x, z) + this.offset);
         double line = PluginConfig.getForestMountainTreeLine();
         if (rel >= line) {
            return 0.0;
         }
         return ForestTerrain.clamp((line - rel) / 12.0, 0.0, 1.0);
      }

      @Override
      public int groundAt(int x, int z) {
         return height(x, z);
      }

      /** Surface height (y of the top grass/podzol block) at a world column. */
      int height(int x, int z) {
         int base = baseline(x, z) + this.offset;
         if (this.wild != null) {
            // tall relief (mountains, mesas) eases in over a longer ramp than a gentle forest does
            double[] n = this.sharpWeights(x, z);
            double blendHere = wildBlend(this.blend, n);
            double fadeHere = reliefFade(x, z, blendHere);
            if (fadeHere <= 0.0) {
               return base;
            }
            double[] wt = new double[this.scratch.length];
            this.terrain.typeWeights(x, z, wt);
            return base + (int) Math.round(fadeHere * this.wildRelief(x, z, n, this.terrain.relief(x, z, wt)));
         }
         double d = edgeDistance(x, z, this.blend / 16 + 2);
         double fade = d == Double.MAX_VALUE ? 1.0 : ForestTerrain.smooth(d / this.blend);
         if (fade <= 0.0) {
            return base;
         }
         double[] w = new double[this.scratch.length];
         this.terrain.typeWeights(x, z, w);
         double relief = this.terrain.relief(x, z, w);
         if (this.mountain != null) {
            // lowland areas of a range region keep this hilly relief (and its meadows and glades)
            relief = this.mountains.reliefBlend(this.mountain, x, z, relief);
         }
         return base + (int) Math.round(fade * relief);
      }

      /**
       * What the top of a mountain column is: soil, bare rock, or snow. Rock starts at the
       * tree line and on any steep slope above the foothills; both lines are ragged by a
       * few blocks so they don't read as a contour.
       */
      int mountainSurface(int x, int z, int h) {
         int rel = h - (baseline(x, z) + this.offset);
         double ragged = (ForestTerrain.unit(ForestTerrain.hash(this.terrain.getSeed(), x >> 2, z >> 2, 31)) - 0.5) * 8.0;
         int snow = PluginConfig.getForestMountainSnowLine();
         if (snow > 0 && rel >= snow + ragged) {
            return SURFACE_SNOW;
         }
         if (rel >= PluginConfig.getForestMountainTreeLine() + ragged) {
            return SURFACE_ROCK;
         }
         if (rel > 8) {
            int slope = Math.max(Math.max(Math.abs(h - height(x + 1, z)), Math.abs(h - height(x - 1, z))),
                    Math.max(Math.abs(h - height(x, z + 1)), Math.abs(h - height(x, z - 1))));
            if (slope >= 3) {
               return SURFACE_ROCK;
            }
         }
         return SURFACE_GROUND;
      }
   }
}
