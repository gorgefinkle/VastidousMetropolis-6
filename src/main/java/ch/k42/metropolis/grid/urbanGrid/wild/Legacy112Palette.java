package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.minions.LegacyBlocks;
import org.bukkit.Chunk;
import org.bukkit.block.Block;

/**
 * Minecraft 1.12.2: blocks are a numeric id plus a data nibble. Every legacy id of the wilderness lives in this one table, so
 * it is also the complete list of what a newer version has to re-spell.
 */
@SuppressWarnings("deprecation")
public final class Legacy112Palette implements BlockPalette {
   private static final int[] ID = new int[WildBlock.values().length];
   private static final byte[] DATA = new byte[WildBlock.values().length];

   private static void def(WildBlock b, int id, int data) {
      ID[b.ordinal()] = id;
      DATA[b.ordinal()] = (byte) data;
   }

   static {
      def(WildBlock.AIR, 0, 0);
      def(WildBlock.STONE, 1, 0);
      def(WildBlock.ANDESITE, 1, 5);
      def(WildBlock.GRASS_BLOCK, 2, 0);
      def(WildBlock.DIRT, 3, 0);
      def(WildBlock.COARSE_DIRT, 3, 1);
      def(WildBlock.PODZOL, 3, 2);
      def(WildBlock.COBBLESTONE, 4, 0);
      def(WildBlock.SAND, 12, 0);
      def(WildBlock.RED_SAND, 12, 1);
      def(WildBlock.GRAVEL, 13, 0);
      def(WildBlock.SANDSTONE, 24, 0);
      def(WildBlock.TALL_GRASS, 31, 1);
      def(WildBlock.FERN, 31, 2);
      def(WildBlock.DEAD_BUSH, 32, 0);
      def(WildBlock.DANDELION, 37, 0);
      def(WildBlock.POPPY, 38, 0);
      def(WildBlock.BLUE_ORCHID, 38, 1);
      def(WildBlock.ALLIUM, 38, 2);
      def(WildBlock.AZURE_BLUET, 38, 3);
      def(WildBlock.RED_TULIP, 38, 4);
      def(WildBlock.ORANGE_TULIP, 38, 5);
      def(WildBlock.WHITE_TULIP, 38, 6);
      def(WildBlock.PINK_TULIP, 38, 7);
      def(WildBlock.OXEYE_DAISY, 38, 8);
      def(WildBlock.BROWN_MUSHROOM, 39, 0);
      def(WildBlock.RED_MUSHROOM, 40, 0);
      def(WildBlock.SNOW_BLOCK, 80, 0);
      def(WildBlock.CACTUS, 81, 0);
      def(WildBlock.CLAY_WHITE, 159, 0);
      def(WildBlock.CLAY_ORANGE, 159, 1);
      def(WildBlock.CLAY_YELLOW, 159, 4);
      def(WildBlock.CLAY_SILVER, 159, 8);
      def(WildBlock.CLAY_BROWN, 159, 12);
      def(WildBlock.CLAY_RED, 159, 14);
      def(WildBlock.HARDENED_CLAY, 172, 0);
      def(WildBlock.RED_SANDSTONE, 179, 0);
      def(WildBlock.MOSSY_COBBLE, 48, 0);
      def(WildBlock.SANDSTONE_SMOOTH, 24, 2);
      def(WildBlock.COBBLE_WALL, 139, 0);
      def(WildBlock.COBWEB, 30, 0);
      def(WildBlock.CONC_WHITE, 251, 0);
      def(WildBlock.CONC_ORANGE, 251, 1);
      def(WildBlock.CONC_YELLOW, 251, 4);
      def(WildBlock.CONC_RED, 251, 14);
      def(WildBlock.CONC_LIGHT_GRAY, 251, 8);
      def(WildBlock.CONC_GRAY, 251, 7);
      def(WildBlock.CONC_BLACK, 251, 15);
      def(WildBlock.CONC_BLUE, 251, 11);
      def(WildBlock.CONC_GREEN, 251, 13);
      def(WildBlock.CONC_BROWN, 251, 12);
      def(WildBlock.OAK_STAIRS_E, 53, 0);
      def(WildBlock.OAK_STAIRS_W, 53, 1);
      def(WildBlock.OAK_STAIRS_S, 53, 2);
      def(WildBlock.OAK_STAIRS_N, 53, 3);
      def(WildBlock.DARK_OAK_STAIRS_E, 164, 0);
      def(WildBlock.DARK_OAK_STAIRS_W, 164, 1);
      def(WildBlock.DARK_OAK_STAIRS_S, 164, 2);
      def(WildBlock.DARK_OAK_STAIRS_N, 164, 3);
      def(WildBlock.LADDER_N, 65, 2);
      def(WildBlock.LADDER_S, 65, 3);
      def(WildBlock.LADDER_W, 65, 4);
      def(WildBlock.LADDER_E, 65, 5);
      def(WildBlock.CLAY_BLACK, 159, 15);
      def(WildBlock.CLAY_GRAY, 159, 7);
      def(WildBlock.CLAY_BLUE, 159, 11);
      def(WildBlock.CLAY_GREEN, 159, 13);
      def(WildBlock.IRON_BLOCK, 42, 0);
      def(WildBlock.COAL_BLOCK, 173, 0);
      def(WildBlock.GLASS, 20, 0);
      def(WildBlock.GLASS_PANE, 102, 0);
      def(WildBlock.IRON_BARS, 101, 0);
      def(WildBlock.PLANKS, 5, 0);
      def(WildBlock.FENCE, 85, 0);
      def(WildBlock.WOOL_WHITE, 35, 0);
      def(WildBlock.WOOL_ORANGE, 35, 1);
      def(WildBlock.WOOL_GRAY, 35, 7);
      def(WildBlock.WOOL_GREEN, 35, 13);
      def(WildBlock.WOOL_BROWN, 35, 12);
      def(WildBlock.WOOL_RED, 35, 14);
      def(WildBlock.RAIL_Z, 66, 0);
      def(WildBlock.RAIL_X, 66, 1);
      def(WildBlock.COAL_ORE, 16, 0);
      def(WildBlock.IRON_ORE, 15, 0);
      def(WildBlock.GOLD_ORE, 14, 0);
      // Historical numeric fallback values retained for callers that still require legacy ids.
      // The modern blocks are written and checked directly in set()/is() below.
      def(WildBlock.MUD, 3, 0);
      def(WildBlock.MOSS, 2, 0);
      def(WildBlock.BASALT, 1, 0);
      def(WildBlock.BLACKSTONE, 1, 0);
      def(WildBlock.TUFF, 1, 5);
   }

   @Override
   public void set(Chunk chunk, int lx, int y, int lz, WildBlock block) {
      org.bukkit.Material material = modern(block);
      if (material != null) {
         chunk.getBlock(lx, y, lz).setType(material, false);
      } else {
         LegacyBlocks.set(chunk.getBlock(lx, y, lz), ID[block.ordinal()], DATA[block.ordinal()]);
      }
   }

   @Override
   public boolean is(Block block, WildBlock kind) {
      org.bukkit.Material material = modern(kind);
      return material != null ? block.getType() == material
              : LegacyBlocks.is(block, ID[kind.ordinal()], DATA[kind.ordinal()]);
   }

   private static org.bukkit.Material modern(WildBlock block) {
      return switch (block) {
         case MUD -> org.bukkit.Material.MUD;
         case MOSS -> org.bukkit.Material.MOSS_BLOCK;
         case BASALT -> org.bukkit.Material.BASALT;
         case BLACKSTONE -> org.bukkit.Material.BLACKSTONE;
         case TUFF -> org.bukkit.Material.TUFF;
         default -> null;
      };
   }

   /** Legacy numeric id of a block, for the places that still speak ids (the rest of the 1.12 code base). */
   public static int idOf(WildBlock b) {
      return ID[b.ordinal()];
   }

   public static byte dataOf(WildBlock b) {
      return DATA[b.ordinal()];
   }
}
