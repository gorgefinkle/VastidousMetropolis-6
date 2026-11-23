package ch.k42.metropolis.grid.urbanGrid.vanilla;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.forest.ForestGenerator;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.minions.LegacyBlocks;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;

/**
 * The wilderness as plain vanilla Minecraft, switched on with generator.wilderness.vanilla.enabled. It replaces ForestGenerator
 * (and with it the Metropolis forest / mountain / desert / mesa biomes) for forest regions: the chunk is whatever the server's own
 * generator makes (see VanillaSource), copied in as it is.
 *
 * The one thing that is not left alone is where the wilderness meets something Metropolis built. A vanilla hillside would hit a
 * street as a wall, so within generator.wilderness.vanilla.blendChunks chunks of any edge (a street, a farm, a city block, a
 * highway, a river or the ocean) the ground is eased to the build height: each column keeps all of its vanilla layers (grass,
 * dirt, sand, stone, caves, ores) and is moved up or down as a whole by the difference between the height it should have and the
 * height vanilla gave it, where "should" slides smoothly from vanilla (far from the edge) to the surface of the neighbouring
 * streets and lots (at the edge), the same height ForestGenerator uses. Deeper than that the chunk is vanilla, byte for byte.
 *
 * Trees ride the ground they stand on, except within generator.wilderness.vanilla.verge blocks of the edge, where they are left
 * out: a canopy there would be cut off by the street chunk next to it, which is not part of the wilderness.
 *
 * Like ForestGenerator, a chunk only ever writes into itself and every answer is a pure function of the plan and the seed, so
 * chunks come out the same in any order. Only the blocks that differ from what is already in the chunk are written.
 *
 * Heights are the world's own: columns run from World.getMinHeight() (below zero on 26.x) to World.getMaxHeight() - 1, and the
 * bedrock band that is never moved is the bottom FLOOR_DEPTH layers of that range.
 */
public final class VanillaWilderness {
   /** How many layers above the bottom of the world are never moved (bedrock and the deep stone around it stay as vanilla made them). */
   private static final int FLOOR_DEPTH = 5;
   /** Water at or below this is sea level (oceans, rivers, beaches): it stays where it is while the ground under it moves. */
   private static final int SEA_TOP = 63;
   /** Where drained ocean floor is brought up to: just above sea level, rising a little where the water was deep. */
   private static final int DRY_LEVEL = 64;
   /** "No ground in this column." Heights are negative in the deep world, so -1 can no longer mean "none". */
   private static final int NONE = Integer.MIN_VALUE;

   /** Legacy block ids this class still reasons in (translated by LegacyBlocks): grass block and the dirt family. */
   private static final int GRASS = 2;
   private static final int DIRT = 3;
   /** Soil layers of a bank that take the wilderness's own surface (grass over dirt is three or four deep). */
   private static final int SOIL_DEPTH = 4;
   /** Blocks of clear ground a tree needs on every side before a shore grows it: a canopy must not be cut off by the beach beside it. */
   private static final double SHORE_TREE_MARGIN = 4.0;

   private static final BlockData AIR_DATA = Material.AIR.createBlockData();
   private static final BlockData STONE_DATA = Material.STONE.createBlockData();
   private static final BlockData WATER_DATA = Material.WATER.createBlockData();
   private static final BlockData LAVA_DATA = Material.LAVA.createBlockData();
   private static final BlockData GRASS_DATA = Material.GRASS_BLOCK.createBlockData();
   private static final BlockData DIRT_DATA = Material.DIRT.createBlockData();

   private VanillaWilderness() {
   }

   /** Is the vanilla wilderness on for this world? (Only ordinary overworlds: the Nether has no forest to replace.) */
   public static boolean isActive(World world) {
      return PluginConfig.isWildVanillaEnabled() && world != null && world.getEnvironment() == World.Environment.NORMAL;
   }

   /**
    * Ground: solid, light-blocking blocks that are not part of a tree. Asked of the server's own materials, not hand-listed:
    * a block counts if it occludes light, except logs and the huge-mushroom blocks, which are trees.
    */
   private static boolean isGround(Material m) {
      if (m == Material.STONE) {
         return true;
      }
      if (!m.isBlock() || !m.isOccluding()) {
         return false;
      }
      return !Tag.LOGS.isTagged(m) && m != Material.BROWN_MUSHROOM_BLOCK && m != Material.RED_MUSHROOM_BLOCK && m != Material.MUSHROOM_STEM;
   }

   /**
    * Which fluid a block belongs to when it sits on top of a column's ground: WATER (water, the ice that floats on it, and the
    * plants that only exist under water), LAVA, or null for anything that is not a fluid.
    */
   private static Material fluidKind(Material m) {
      // what grows on a warm ocean's floor: it is under the water, not "something solid on the ground"
      if (m == Material.SEA_PICKLE || Tag.CORALS.isTagged(m) || Tag.WALL_CORALS.isTagged(m)) {
         return Material.WATER;
      }
      switch (m) {
         case WATER:
         case ICE:
         case BUBBLE_COLUMN:
         case KELP:
         case KELP_PLANT:
         case SEAGRASS:
         case TALL_SEAGRASS:
            return Material.WATER;
         case LAVA:
            return Material.LAVA;
         default:
            return null;
      }
   }

   /**
    * Fills one chunk of a forest region with vanilla terrain. Returns false, having touched nothing, if vanilla could not be had
    * (the caller then falls back to ForestGenerator so the chunk is not left as a bare plate).
    */
   public static boolean populateChunk(MetropolisGenerator generator, Chunk chunk, UrbanGrid grid, MorphomosRegion region) {
      try {
         return copyChunk(generator, chunk, grid, region);
      } catch (Throwable t) {
         Minions.i("Vanilla wilderness: chunk (%d,%d) could not be filled (%s); the Metropolis wilderness is used for it", chunk.getX(), chunk.getZ(), t);
         return false;
      }
   }

   private static boolean copyChunk(MetropolisGenerator generator, Chunk chunk, UrbanGrid grid, MorphomosRegion region) {
      World world = generator.getWorld();
      if (world == null || region == null || !isActive(world)) {
         return false;
      }
      int cx = chunk.getX();
      int cz = chunk.getZ();
      VanillaChunk vc = VanillaSource.of(world).fetch(cx, cz);
      if (vc == null) {
         return false;
      }

      int minY = world.getMinHeight();
      int top = world.getMaxHeight() - 1;
      int floor = minY + FLOOR_DEPTH;
      int blend = PluginConfig.getWildVanillaBlendChunks() * 16;
      int verge = Math.min(PluginConfig.getWildVanillaVergeBlocks(), blend);
      boolean drain = !PluginConfig.isWildVanillaOceans();
      int reach = blend / 16 + 1;
      Edges edges = new Edges(grid, region, cx, cz, reach);
      int base = (grid.isRiverReclaimedChunk(cx, cz) ? RiverConfig.getRiverReclaimBuildHeight() : PluginConfig.getBuildHeight())
              + PluginConfig.getForestSurfaceOffset();

      ChunkSnapshot live = chunk.getChunkSnapshot();
      BlockData[] target = new BlockData[top - minY + 1];
      int[] shiftOf = new int[256];
      int baseX = cx << 4;
      int baseZ = cz << 4;
      int written = 0;
      int ramped = 0;

      for (int lz = 0; lz < 16; lz++) {
         for (int lx = 0; lx < 16; lx++) {
            double d = edges.distance(baseX + lx, baseZ + lz);
            int hv = NONE;
            int shift = 0;
            boolean lifted = false;
            boolean clear = d < verge;
            if (d < blend || drain) {
               hv = groundTop(vc, lx, lz, minY, top);
               if (hv != NONE) {
                  int want = hv;
                  if (drain && hv < SEA_TOP && standingWater(vc, lx, hv, lz) && !biomeKey(vc, lx, lz).contains("river")) {
                     want = DRY_LEVEL + (SEA_TOP - hv) / 10;     // an ocean or pool becomes low ground, a little higher where it was deep
                     lifted = true;
                  }
                  int h = want;
                  if (d < blend) {
                     double t = Math.max(0.0, d / blend);
                     double f = t * t * (3.0 - 2.0 * t);
                     h = base + (int) Math.round(f * (want - base));
                  }
                  shift = h - hv;
               }
            }
            shiftOf[lz << 4 | lx] = shift;
            if (shift == 0 && !clear) {
               for (int y = minY; y <= top; y++) {
                  target[y - minY] = vc.block(lx, y, lz);
               }
            } else {
               ramped++;
               buildEased(vc, lx, lz, hv, shift, clear, target, minY, top, floor);
               if (lifted) {
                  topsoil(hv + shift, target, minY, top, floor);      // the old sea floor is sand and gravel: it becomes ground
               }
            }
            for (int y = minY; y <= top; y++) {
               BlockData want = target[y - minY];
               if (!live.getBlockData(lx, y, lz).equals(want)) {
                  chunk.getBlock(lx, y, lz).setBlockData(want, false);
                  written++;
               }
            }
            try {
               Biome biome = vc.biome(lx, lz);
               world.setBiome(baseX + lx, baseZ + lz, lifted && biomeKey(vc, lx, lz).contains("ocean") ? Biome.PLAINS : biome);
            } catch (Throwable ignored) {
               // biome tinting is cosmetic; never let it stop terrain
            }
         }
      }

      restoreSpawners(chunk, vc, shiftOf, floor, minY, top);
      Minions.dDistrict("VANILLA chunk=(%d,%d) region=%d eased=%d written=%d", cx, cz, region.getId(), ramped, written);
      return true;
   }

   /**
    * A river or ocean bank in vanilla's own look. The bank keeps its height (the beach, the slope, the water's edge are the
    * water's business), but every column of its grass takes what vanilla has at that position: the biome, the surface layers
    * (grass, sand, stone, snow, podzol...), the ground cover and the trees. So the wilderness carries straight on to the water
    * instead of ending at the line where the bank begins, which is what plain forest planting there did.
    *
    * Trees are copied only where the shore's own site allows them and not within SHORE_TREE_MARGIN of where it stops, so a
    * canopy is never cut off at the beach; they ride the bank's ground like they ride the eased ground inland. Columns where
    * vanilla has water or an ocean/river biome are left as the bank made them.
    *
    * Returns false, having touched nothing, if vanilla could not be had (the caller plants the Metropolis forest instead).
    */
   public static boolean populateShore(MetropolisGenerator generator, Chunk chunk, ForestGenerator.TreeSite site, boolean trees, boolean cover) {
      try {
         return copyShore(generator, chunk, site, trees, cover);
      } catch (Throwable t) {
         Minions.i("Vanilla wilderness: shore chunk (%d,%d) could not be filled (%s); the Metropolis forest planting is used for it", chunk.getX(), chunk.getZ(), t);
         return false;
      }
   }

   private static boolean copyShore(MetropolisGenerator generator, Chunk chunk, ForestGenerator.TreeSite site, boolean trees, boolean cover) {
      World world = generator.getWorld();
      if (world == null || !isActive(world)) {
         return false;
      }
      int minY = world.getMinHeight();
      int top = world.getMaxHeight() - 1;
      int cx = chunk.getX();
      int cz = chunk.getZ();
      int baseX = cx << 4;
      int baseZ = cz << 4;
      int[] topY = new int[256];
      boolean any = false;
      for (int lz = 0; lz < 16; lz++) {
         for (int lx = 0; lx < 16; lx++) {
            // getHighestBlockYAt() is the highest block itself on this server (it was the air above it on 1.12.2).
            int y = world.getHighestBlockYAt(baseX + lx, baseZ + lz);
            boolean grass = y >= minY + 1 && chunk.getBlock(lx, y, lz).getType() == Material.GRASS_BLOCK;
            topY[lz << 4 | lx] = grass ? y : NONE;
            any |= grass;
         }
      }
      if (!any) {
         return true;       // all water, beach or wall: nothing for vanilla to continue, and no reason to generate it
      }
      VanillaChunk vc = VanillaSource.of(world).fetch(cx, cz);
      if (vc == null) {
         return false;
      }

      int skinned = 0;
      for (int lz = 0; lz < 16; lz++) {
         for (int lx = 0; lx < 16; lx++) {
            int ty = topY[lz << 4 | lx];
            if (ty == NONE) {
               continue;
            }
            int hv = groundTop(vc, lx, lz, minY, top);
            if (hv == NONE || hv < minY + SOIL_DEPTH) {
               continue;
            }
            Material above = vc.type(lx, hv + 1, lz);
            if (fluidKind(above) != null) {
               continue;      // vanilla has water here
            }
            int x = baseX + lx;
            int z = baseZ + lz;
            try {
               String biome = vc.biome(lx, lz).getKey().getKey().toLowerCase(Locale.ROOT);
               if (!biome.contains("ocean") && !biome.contains("river")) {
                  world.setBiome(x, z, vc.biome(lx, lz));
               }
            } catch (Throwable ignored) {
               // biome tinting is cosmetic
            }

            // the bank's own soil takes vanilla's surface layers, top down
            for (int k = 0; k < SOIL_DEPTH; k++) {
               Block b = chunk.getBlock(lx, ty - k, lz);
               Material type = b.getType();
               if (!LegacyBlocks.is(type, GRASS) && !LegacyBlocks.is(type, DIRT)) {
                  break;
               }
               b.setBlockData(vc.block(lx, hv - k, lz), false);
            }

            // what stands on it: plants, snow, and (where they fit) trees
            int wooded = -1;     // -1 = not asked yet, 0 = no trees here, 1 = trees allowed
            for (int y = hv + 1; y <= top; y++) {
               Material type = vc.type(lx, y, lz);
               if (type.isAir() || type == Material.SUGAR_CANE) {
                  continue;
               }
               if (isFoliage(type, y, hv)) {
                  if (!trees) {
                     continue;
                  }
                  if (wooded < 0) {
                     wooded = site.allows(x, z) && site.edgeDistance(x, z, 1) >= SHORE_TREE_MARGIN ? 1 : 0;
                  }
                  if (wooded == 0) {
                     continue;
                  }
               } else if (!cover) {
                  continue;
               }
               int at = ty + (y - hv);
               if (at > top) {
                  break;
               }
               Block b = chunk.getBlock(lx, at, lz);
               if (b.getType().isAir()) {
                  b.setBlockData(vc.block(lx, y, lz), false);
               }
            }
            skinned++;
         }
      }
      Minions.dDistrict("VANILLA shore chunk=(%d,%d) columns=%d", cx, cz, skinned);
      return true;
   }

   /**
    * One column, moved by `shift` blocks (and, if `clear`, without its trees). Everything from the floor up is read from the
    * vanilla column `shift` blocks away, so the surface layers stay on the surface; raising the ground leaves a gap at the bottom
    * that is filled with stone, lowering it leaves air at the top. Water at sea level stays at sea level whatever the ground
    * does; water higher up (a pond on a hill) does not survive being moved, since it would otherwise stand on the ground as a tower.
    */
   private static void buildEased(VanillaChunk vc, int lx, int lz, int hv, int shift, boolean clear, BlockData[] target, int minY, int top, int floor) {
      boolean moved = shift != 0;
      int fluidTop = hv;
      Material fluid = null;
      if (hv != NONE) {
         for (int y = hv + 1; y <= top; y++) {
            Material kind = fluidKind(vc.type(lx, y, lz));
            if (kind == null) {
               break;
            }
            if (fluid == null) {
               fluid = kind;
            }
            fluidTop = y;
         }
      }
      boolean pond = fluidTop > hv;

      for (int y = minY; y <= top; y++) {
         BlockData block;
         if (y <= floor) {
            block = vc.block(lx, y, lz);
         } else {
            int s = y - shift;
            if (s <= floor) {
               block = STONE_DATA;
            } else if (s > top) {
               block = AIR_DATA;
            } else {
               block = vc.block(lx, s, lz);
               if (s > hv) {
                  if (moved && pond) {
                     block = AIR_DATA;                      // the water is put back below; what hung over it goes
                  } else if (clear && isFoliage(vc.type(lx, s, lz), s, hv)) {
                     block = AIR_DATA;
                  }
               }
            }
         }
         target[y - minY] = block;
      }

      if (moved && pond && fluidTop <= SEA_TOP) {
         int h = hv + shift;
         BlockData still = fluid == Material.LAVA ? LAVA_DATA : WATER_DATA;
         for (int y = Math.max(h + 1, minY + 1); y <= fluidTop; y++) {
            target[y - minY] = y == fluidTop ? vc.block(lx, fluidTop, lz) : still;
         }
      }
   }

   /** Parts of a tree (and what hangs or lies on it): logs, leaves, vines, cocoa, huge mushrooms, snow lying above the ground. */
   private static boolean isFoliage(Material type, int y, int groundY) {
      if (Tag.LOGS.isTagged(type) || Tag.LEAVES.isTagged(type)) {
         return true;
      }
      switch (type) {
         case BROWN_MUSHROOM_BLOCK:
         case RED_MUSHROOM_BLOCK:
         case MUSHROOM_STEM:
         case VINE:
         case COCOA:
         case BEE_NEST:
         case MANGROVE_ROOTS:
         case MUDDY_MANGROVE_ROOTS:
            return true;
         case SNOW:
            return y > groundY + 1;
         default:
            return false;
      }
   }

   /** The vanilla biome's registry name at a column, lower-case ("deep_ocean", "frozen_river"...). */
   private static String biomeKey(VanillaChunk vc, int lx, int lz) {
      return vc.biome(lx, lz).getKey().getKey().toLowerCase(Locale.ROOT);
   }

   /**
    * Is the ground of this column under water or ice (an ocean, a lake, a swamp pool)? Asked through fluidKind(), so the
    * kelp, seagrass and bubble columns that fill a modern ocean floor count as water, not as "something else on the ground".
    */
   private static boolean standingWater(VanillaChunk vc, int lx, int hv, int lz) {
      return fluidKind(vc.type(lx, hv + 1, lz)) == Material.WATER;
   }

   /** Grass over three layers of dirt with its top at h: what drained sea floor is turned into. */
   private static void topsoil(int h, BlockData[] target, int minY, int top, int floor) {
      if (h < minY + 1 || h > top) {
         return;
      }
      target[h - minY] = GRASS_DATA;
      for (int k = 1; k <= 3; k++) {
         if (h - k > floor) {
            target[h - k - minY] = DIRT_DATA;
         }
      }
   }

   /** Y of the highest ground block of a vanilla column (trees, plants, snow and water above it do not count), or NONE. */
   private static int groundTop(VanillaChunk vc, int lx, int lz, int minY, int top) {
      for (int y = top; y >= minY; y--) {
         if (isGround(vc.type(lx, y, lz))) {
            return y;
         }
      }
      return NONE;
   }

   /** Dungeon spawners are tile entities: the block is copied with everything else, the mob has to be set on it. */
   private static void restoreSpawners(Chunk chunk, VanillaChunk vc, int[] shiftOf, int floor, int minY, int top) {
      for (VanillaChunk.Spawner sp : vc.spawners()) {
         try {
            int y = sp.y <= floor ? sp.y : sp.y + shiftOf[sp.lz << 4 | sp.lx];
            if (y < minY || y > top) {
               continue;
            }
            Block b = chunk.getBlock(sp.lx, y, sp.lz);
            if (b.getType() == Material.SPAWNER) {
               BlockState state = b.getState();
               if (state instanceof CreatureSpawner) {
                  ((CreatureSpawner) state).setSpawnedType(sp.type);
                  state.update(true, false);
               }
            }
         } catch (Throwable ignored) {
            // a spawner that keeps its default mob is a nuisance, not a reason to fail a chunk
         }
      }
   }

   /**
    * Distance to the nearest thing that is not wilderness. Every forest region counts as the same wilderness (two that meet are
    * one stretch of land with no edge between them); anything else -- street, farm, city, highway, water -- is an edge. Only the
    * cells within `reach` chunks matter, because nothing farther than that is inside the blend.
    */
   private static final class Edges {
      private final List<int[]> cells = new ArrayList<int[]>();

      Edges(UrbanGrid grid, MorphomosRegion region, int cx, int cz, int reach) {
         for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
               MorphomosRegion r = grid.getMorphomosRegionAt(cx + dx, cz + dz);
               boolean wilderness = r == region || (r != null && r.getContext() == ContextType.FOREST);
               if (!wilderness) {
                  this.cells.add(new int[]{(cx + dx) << 4, (cz + dz) << 4});
               }
            }
         }
      }

      /** Blocks from the column to the nearest edge cell: 0 on the outermost ring of the wilderness, Double.MAX_VALUE if none is near. */
      double distance(int x, int z) {
         double px = x + 0.5;
         double pz = z + 0.5;
         double best = Double.MAX_VALUE;
         for (int[] c : this.cells) {
            double dx = Math.max(Math.max(c[0] - px, 0.0), px - (c[0] + 16.0));
            double dz = Math.max(Math.max(c[1] - pz, 0.0), pz - (c[1] + 16.0));
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < best) {
               best = d;
            }
         }
         return best == Double.MAX_VALUE ? best : Math.max(0.0, best - 0.5);
      }
   }
}
