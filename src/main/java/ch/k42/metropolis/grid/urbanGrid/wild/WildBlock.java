package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * Every block the wilderness generator places, by what it is and not by how a server version spells it. The generator only
 * ever says WildBlock.SAND; BlockPalette decides what that is on this server (see Palettes). One entry here and one mapping per
 * palette is all a new block costs, and a new Minecraft version costs one new palette class.
 */
public enum WildBlock {
   AIR, STONE, ANDESITE, DIRT, COARSE_DIRT, PODZOL, GRASS_BLOCK, GRAVEL, COBBLESTONE, SNOW_BLOCK,
   SAND, RED_SAND, SANDSTONE, RED_SANDSTONE, HARDENED_CLAY,
   CLAY_WHITE, CLAY_ORANGE, CLAY_YELLOW, CLAY_BROWN, CLAY_RED, CLAY_SILVER,
   TALL_GRASS, FERN, DEAD_BUSH, CACTUS, DANDELION, BROWN_MUSHROOM, RED_MUSHROOM,
   POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP, OXEYE_DAISY,
   // the wilderness set pieces (WildDecor): crashed aircraft, camps, mine pits and masts
   MOSSY_COBBLE, SANDSTONE_SMOOTH, COBBLE_WALL, COBWEB,
   CONC_WHITE, CONC_ORANGE, CONC_YELLOW, CONC_RED, CONC_LIGHT_GRAY, CONC_GRAY, CONC_BLACK, CONC_BLUE, CONC_GREEN, CONC_BROWN,
   OAK_STAIRS_E, OAK_STAIRS_W, OAK_STAIRS_S, OAK_STAIRS_N,
   DARK_OAK_STAIRS_E, DARK_OAK_STAIRS_W, DARK_OAK_STAIRS_S, DARK_OAK_STAIRS_N,
   LADDER_N, LADDER_S, LADDER_W, LADDER_E,
   CLAY_BLACK, CLAY_GRAY, CLAY_BLUE, CLAY_GREEN,
   IRON_BLOCK, COAL_BLOCK, GLASS, GLASS_PANE, IRON_BARS, PLANKS, FENCE,
   WOOL_WHITE, WOOL_ORANGE, WOOL_GRAY, WOOL_GREEN, WOOL_BROWN, WOOL_RED,
   RAIL_X, RAIL_Z, COAL_ORE, IRON_ORE, GOLD_ORE,
   MUD, MOSS, BASALT, BLACKSTONE, TUFF;

   /** The nine flower kinds the forest scatters in drifts, in the order of the old red-rose data values 0..8. */
   public static final WildBlock[] FLOWERS = {POPPY, BLUE_ORCHID, ALLIUM, AZURE_BLUET, RED_TULIP, ORANGE_TULIP, WHITE_TULIP, PINK_TULIP, OXEYE_DAISY};
}
