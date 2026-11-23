package ch.k42.metropolis.minions;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Leaves;

/**
 * Modern replacements for the pre-1.13 "one material + a data byte" idioms
 * the generator was written with.
 *
 * Since the 1.13 flattening every variant (oak vs. spruce planks, stone
 * brick vs. mossy stone brick, tall grass vs. large fern...) is its own
 * Material, and anything that used to live in the data byte -- stair facing,
 * door open bit, leaf persistence, vine faces, slab half -- is a BlockData
 * property instead. Everything that used to reach for
 * setTypeIdAndData(id, data, physics) or getData() goes through here so the
 * mapping lives in ONE place.
 *
 * The material "families" below are built by name from the Material enum
 * itself (skipping the LEGACY_ copies), not from Bukkit's Tag registry: that
 * way they are available the instant the class loads, with no dependency on
 * the server's tag data having been loaded yet, and they automatically pick
 * up wood types added in later versions (cherry, mangrove, bamboo...).
 */
public final class Mats {
   private Mats() {
   }

   private static Set<Material> byName(Predicate<String> test) {
      Set<Material> set = EnumSet.noneOf(Material.class);
      for (Material material : Material.values()) {
         String name = material.name();
         if (!name.startsWith("LEGACY_") && test.test(name)) {
            set.add(material);
         }
      }
      return Collections.unmodifiableSet(set);
   }

   /** Every leaf block (was LEAVES / LEAVES_2). */
   public static final Set<Material> LEAVES = byName(name -> name.endsWith("_LEAVES"));
   /** Every log and all-bark "wood" block, stripped or not (was LOG / LOG_2). */
   public static final Set<Material> LOGS = byName(name -> name.endsWith("_LOG") || name.endsWith("_WOOD"));
   /** Every plank block (was WOOD, one data value per species). */
   public static final Set<Material> PLANKS = byName(name -> name.endsWith("_PLANKS"));
   /** Every wooden door -- not iron, not copper (was WOODEN_DOOR). */
   public static final Set<Material> WOODEN_DOORS = byName(
           name -> name.endsWith("_DOOR") && !name.startsWith("IRON_") && !name.contains("COPPER"));
   /** Every coloured glass block (was STAINED_GLASS). */
   public static final Set<Material> STAINED_GLASS = byName(name -> name.endsWith("_STAINED_GLASS"));
   /** Every coloured glass pane (was STAINED_GLASS_PANE). */
   public static final Set<Material> STAINED_GLASS_PANES = byName(name -> name.endsWith("_STAINED_GLASS_PANE"));

   /** The two-block-tall plants (was DOUBLE_PLANT, one data value per species). */
   public static final Set<Material> TALL_PLANTS = Collections.unmodifiableSet(EnumSet.of(
           Material.TALL_GRASS, Material.LARGE_FERN, Material.SUNFLOWER, Material.LILAC, Material.ROSE_BUSH, Material.PEONY));
   /** The one-block ground plants (was LONG_GRASS: data 0 dead shrub, 1 grass, 2 fern). */
   public static final Set<Material> SHORT_PLANTS = Collections.unmodifiableSet(EnumSet.of(
           Material.SHORT_GRASS, Material.FERN, Material.DEAD_BUSH));

   /** RED_ROSE's nine data values, in data order 0..8. (YELLOW_FLOWER was just the dandelion.) */
   private static final Material[] RED_ROSE_VARIANTS = {
           Material.POPPY, Material.BLUE_ORCHID, Material.ALLIUM, Material.AZURE_BLUET,
           Material.RED_TULIP, Material.ORANGE_TULIP, Material.WHITE_TULIP, Material.PINK_TULIP, Material.OXEYE_DAISY
   };
   /** Every small flower the old YELLOW_FLOWER / RED_ROSE pair covered. */
   public static final Set<Material> SMALL_FLOWERS;

   static {
      Set<Material> flowers = EnumSet.of(Material.DANDELION);
      Collections.addAll(flowers, RED_ROSE_VARIANTS);
      SMALL_FLOWERS = Collections.unmodifiableSet(flowers);
   }

   /** The three stone-brick looks the decay pass picks between (was SMOOTH_BRICK data 0, 1, 2). */
   public static final Material[] STONE_BRICK_LOOKS = {
           Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS
   };
   /** Every stone-brick block, chiseled included (was SMOOTH_BRICK, any data). */
   public static final Set<Material> STONE_BRICK_FAMILY = Collections.unmodifiableSet(EnumSet.of(
           Material.STONE_BRICKS, Material.MOSSY_STONE_BRICKS, Material.CRACKED_STONE_BRICKS, Material.CHISELED_STONE_BRICKS));

   /**
    * In 1.8 these three names each covered a whole family through the data
    * byte; each is a separate material now. Anything that matched the old
    * name -- decay, the Nether conversion -- matches every member.
    */
   public static final Set<Material> STONE_FAMILY = Collections.unmodifiableSet(EnumSet.of(
           Material.STONE, Material.GRANITE, Material.POLISHED_GRANITE, Material.DIORITE,
           Material.POLISHED_DIORITE, Material.ANDESITE, Material.POLISHED_ANDESITE));
   public static final Set<Material> SANDSTONE_FAMILY = Collections.unmodifiableSet(EnumSet.of(
           Material.SANDSTONE, Material.CHISELED_SANDSTONE, Material.SMOOTH_SANDSTONE));
   public static final Set<Material> DIRT_FAMILY = Collections.unmodifiableSet(EnumSet.of(
           Material.DIRT, Material.COARSE_DIRT, Material.PODZOL));

   private static final Material[] LEAF_TYPES = {
           Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES,
           Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES
   };
   /** Stair data 0..3 was east, west, south, north. */
   private static final BlockFace[] STAIR_FACINGS = {BlockFace.EAST, BlockFace.WEST, BlockFace.SOUTH, BlockFace.NORTH};

   /**
    * Resolves a material name from a config file, strictly: it either returns
    * a real modern Material or throws -- it never returns null. Case, spaces
    * and an optional "minecraft:" prefix are forgiven; nothing else is.
    *
    * Pre-1.13 names (THIN_GLASS, STAINED_GLASS, MOB_SPAWNER, ...) are NOT
    * translated at runtime on purpose: a silent translation is exactly how
    * wrong materials end up in a world unnoticed. The config files are
    * converted once, up front, by tools/ModernizeConfigs.java.
    *
    * @param raw   the text from the file
    * @param where where it came from, for the error message
    * @throws IllegalArgumentException if it is not a modern material name
    */
   public static Material require(String raw, String where) {
      if (raw == null || raw.trim().isEmpty()) {
         throw new IllegalArgumentException("Empty material name " + where + ".");
      }
      String name = raw.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
      if (name.startsWith("MINECRAFT:")) {
         name = name.substring("MINECRAFT:".length());
      }
      Material material = name.startsWith("LEGACY_") ? null : Material.getMaterial(name);
      if (material == null) {
         throw new IllegalArgumentException("'" + raw + "' " + where + " is not a modern material name. If it is a "
                 + "pre-1.13 name, convert the config with: java tools/ModernizeConfigs.java <config folder>");
      }
      return material;
   }

   /**
    * The stairs that go with a plank block: OAK_PLANKS -> OAK_STAIRS,
    * CHERRY_PLANKS -> CHERRY_STAIRS... (the old code switched on the plank's
    * data byte). Anything unexpected falls back to oak.
    */
   public static Material stairsFor(Material planks) {
      Material stairs = Material.getMaterial(planks.name().replace("_PLANKS", "_STAIRS"));
      return stairs != null ? stairs : Material.OAK_STAIRS;
   }

   /** Places stairs facing one of the four horizontal directions at random (was data = nextInt(4)). No physics. */
   public static void setStairs(Block block, Material stairs, Random random) {
      BlockData data = Bukkit.createBlockData(stairs);
      if (data instanceof Directional directional) {
         directional.setFacing(STAIR_FACINGS[random.nextInt(STAIR_FACINGS.length)]);
      }
      block.setBlockData(data, false);
   }

   /**
    * Places a random species of leaf that will never decay on its own (was
    * new Leaves(species, false) -- the "not decayable" flag is the
    * `persistent` property now).
    */
   public static void placeLeaf(Block block, Random random) {
      BlockData data = Bukkit.createBlockData(LEAF_TYPES[random.nextInt(LEAF_TYPES.length)]);
      if (data instanceof Leaves leaves) {
         leaves.setPersistent(true);
      }
      block.setBlockData(data, false);
   }

   /** Places a vine clinging to {@code attachFace} (was new Vine().putOnFace(face)). No physics. */
   public static void placeVine(Block block, BlockFace attachFace) {
      BlockData data = Bukkit.createBlockData(Material.VINE);
      if (data instanceof MultipleFacing vine && vine.getAllowedFaces().contains(attachFace)) {
         vine.setFace(attachFace, true);
      }
      block.setBlockData(data, false);
   }

   /**
    * Flips a door between open and closed -- was data ^ 4 on the lower half.
    * Both halves carry the open state now, so both are updated. Does nothing
    * (returns false) unless {@code lower} really is the bottom half of a door.
    */
   public static boolean toggleDoor(Block lower) {
      if (!(lower.getBlockData() instanceof Door door) || door.getHalf() != Bisected.Half.BOTTOM) {
         return false;
      }
      boolean open = !door.isOpen();
      door.setOpen(open);
      lower.setBlockData(door, false);
      Block upper = lower.getRelative(BlockFace.UP);
      if (upper.getType() == lower.getType() && upper.getBlockData() instanceof Door upperDoor) {
         upperDoor.setOpen(open);
         upper.setBlockData(upperDoor, false);
      }
      return true;
   }

   /** True for a water block OR a block holding water (waterlogged stairs, slabs, fences...). */
   public static boolean holdsWater(Block block) {
      if (block.getType() == Material.WATER) {
         return true;
      }
      return block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
   }

   /** The old LONG_GRASS kinds: 0 dead shrub, 1 tall grass, 2 fern. No physics. */
   public static void setGroundPlant(Block block, int kind) {
      Material plant = kind == 0 ? Material.DEAD_BUSH : (kind == 1 ? Material.SHORT_GRASS : Material.FERN);
      block.setType(plant, false);
   }

   /** Places a two-block-tall plant: lower half at {@code lower}, upper half above it. No physics. */
   public static void placeTallPlant(Block lower, Material plant) {
      Block upper = lower.getRelative(BlockFace.UP);
      BlockData bottomData = Bukkit.createBlockData(plant);
      BlockData topData = Bukkit.createBlockData(plant);
      if (bottomData instanceof Bisected bottom && topData instanceof Bisected top) {
         bottom.setHalf(Bisected.Half.BOTTOM);
         top.setHalf(Bisected.Half.TOP);
      }
      lower.setBlockData(bottomData, false);
      upper.setBlockData(topData, false);
   }

   /**
    * One square of ground cover -- the shared body of OceanParcel's and
    * RiverParcel's placeGroundCover(). Draws from the grid's random stream
    * in exactly the order the original code did (roll, then the per-branch
    * chance/variant), so a given seed still lays out the same pattern.
    *
    * 50%: tall grass (20% of those a fern). 30%: a flower (60% dandelion,
    * else one of the nine rose-family flowers). 20%: a two-block tall
    * plant (30% large fern, else double tall grass) if the cell above is free.
    */
   public static void placeGroundCover(World world, GridRandom rand, int x, int y, int z) {
      int roll = rand.getRandomInt(10);
      if (roll < 5) {
         setGroundPlant(world.getBlockAt(x, y, z), rand.getChance(20) ? 2 : 1);
      } else if (roll < 8) {
         Material flower = rand.getChance(60) ? Material.DANDELION : RED_ROSE_VARIANTS[rand.getRandomInt(9)];
         world.getBlockAt(x, y, z).setType(flower, false);
      } else if (world.getBlockAt(x, y + 1, z).getType().isAir()) {
         placeTallPlant(world.getBlockAt(x, y, z), rand.getChance(30) ? Material.LARGE_FERN : Material.TALL_GRASS);
      }
   }

   /**
    * Y of the first free cell above the surface at (x, z) -- the value
    * World.getHighestBlockYAt() used to return. Modern servers return the Y
    * of the highest block itself, one lower. Use this wherever the original
    * arithmetic depended on the old number (height averaging, spawn point).
    */
   public static int firstAirAbove(World world, int x, int z) {
      return world.getHighestBlockYAt(x, z) + 1;
   }

   /**
    * Like firstAirAbove(), but for a column that may lie in ANOTHER chunk: if
    * that chunk is not loaded yet it returns {@code fallback} instead of
    * asking for the height.
    *
    * That matters on a modern server. getHighestBlockYAt() on an unloaded
    * chunk used to read raw pregenerated terrain; now it forces the chunk all
    * the way to fully generated -- which runs ITS populate, which reads ITS
    * neighbours, and so on, outward across whole tiles. Called from inside a
    * populate, that is a runaway cascade. A chunk that is already loaded is
    * safe to ask.
    */
   public static int firstAirAboveIfLoaded(World world, int x, int z, int fallback) {
      if (!world.isChunkLoaded(x >> 4, z >> 4)) {
         return fallback;
      }
      return firstAirAbove(world, x, z);
   }
}
