package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.grid.urbanGrid.enums.LootType;
import ch.k42.metropolis.minions.DirtyHacks;
import ch.k42.metropolis.minions.LegacyBlocks;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.BlockFace;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;

/**
 * Writes blocks into ONE chunk. Every call takes world coordinates and is
 * silently clipped to the chunk, so a structure can be painted in full by
 * every chunk it overlaps and each chunk keeps only its own share. That is
 * what makes the result independent of generation order.
 */
public final class FarmPainter {
   // Legacy block ids (the same numeric style the forest generator uses).
   public static final int TIER_LOW = 0, TIER_MID = 1, TIER_TOP = 2;
   public static final int AIR = 0;
   public static final int STONE = 1;
   public static final int GRASS = 2;
   public static final int DIRT = 3;          // data 1 = coarse dirt
   public static final int COBBLE = 4;
   public static final int PLANKS = 5;        // 0 oak, 1 spruce, 2 birch, 4 acacia, 5 dark oak
   public static final int WATER = 9;
   public static final int LOG = 17;
   public static final int GLASS_PANE = 102;
   public static final int SLAB_STONE = 44;
   public static final int SLAB_WOOD = 126;
   public static final int TORCH = 50;        // data 5 = standing on the floor
   public static final int CHEST = 54;
   public static final int LADDER = 65;
   public static final int FENCE = 85;
   public static final int FENCE_GATE = 107;
   public static final int PUMPKIN = 86;
   public static final int MELON = 103;
   public static final int FARMLAND = 60;
   public static final int WHEAT = 59;
   public static final int CARROTS = 141;
   public static final int POTATOES = 142;
   public static final int BEETROOTS = 207;
   public static final int HAY = 170;
   public static final int CAULDRON = 118;    // data 3 = full: used as a water trough
   public static final int STONE_BRICK = 98;
   public static final int TERRACOTTA = 159;  // stained clay
   public static final int GRASS_PATH = 208;
   public static final int GRAVEL = 13;
   public static final int IRON_BARS = 101;
   public static final int TALL_GRASS = 31;      // data 1 grass, 2 fern
   public static final int LEAVES = 18;           // +4 = no decay (4 oak, 5 spruce)
   public static final int SAPLING = 6;
   public static final int DEAD_BUSH = 32;
   public static final int COBWEB = 30;
   public static final int VINE = 106;            // data bit: 1 south, 2 west, 4 north, 8 east face attached
   public static final int LILY_PAD = 111;
   public static final int MOSSY_COBBLE = 48;
   public static final int COAL_BLOCK = 173;
   public static final int DANDELION = 37;
   public static final int RED_ROSE = 38;

   /** Persistent marker for fully painted farm chunks; used only for lazy edge reconnection. */
   private static final NamespacedKey FARM_GROUND = new NamespacedKey("metropolis", "farm_pane_ground");
   private static final int PANE_SCAN_HEIGHT = 32;

   public final Chunk chunk;
   final long seed;
   final int groundY;
   final int minX;
   final int minZ;
   final int maxX;
   final int maxZ;
   final int minY;
   final int maxY;

   public FarmPainter(Chunk chunk, long seed, int groundY) {
      this.chunk = chunk;
      this.seed = seed;
      this.groundY = groundY;
      this.minX = chunk.getX() << 4;
      this.minZ = chunk.getZ() << 4;
      this.maxX = this.minX + 15;
      this.maxZ = this.minZ + 15;
      this.minY = chunk.getWorld().getMinHeight();
      this.maxY = chunk.getWorld().getMaxHeight();
   }

   boolean intersects(int x0, int z0, int x1, int z1) {
      return x1 >= this.minX && x0 <= this.maxX && z1 >= this.minZ && z0 <= this.maxZ;
   }

   public boolean in(int x, int z) {
      return x >= this.minX && x <= this.maxX && z >= this.minZ && z <= this.maxZ;
   }

   public void set(int x, int y, int z, int id, int data) {
      if (y < this.minY || y >= this.maxY || !this.in(x, z)) {
         return;
      }
      LegacyBlocks.set(this.chunk.getBlock(x & 15, y, z & 15), id, data);
   }

   void fill(int x0, int y0, int z0, int x1, int y1, int z1, int id, int data) {
      int ax0 = Math.max(Math.min(x0, x1), this.minX);
      int ax1 = Math.min(Math.max(x0, x1), this.maxX);
      int az0 = Math.max(Math.min(z0, z1), this.minZ);
      int az1 = Math.min(Math.max(z0, z1), this.maxZ);
      int ay0 = Math.max(this.minY, Math.min(y0, y1));
      int ay1 = Math.min(this.maxY - 1, Math.max(y0, y1));
      for (int x = ax0; x <= ax1; x++) {
         for (int z = az0; z <= az1; z++) {
            for (int y = ay0; y <= ay1; y++) {
               LegacyBlocks.set(this.chunk.getBlock(x & 15, y, z & 15), id, data);
            }
         }
      }
   }

   /** Resolve pane and iron-bar connections as part of the farm's normal paint phase.
    * Legacy 1.12 block metadata contains no directional connection bits; writing
    * modern blocks with physics disabled otherwise leaves every pane as a post.
    * This is scoped to farm geometry and never touches already-fixed fences.
    * The neighboring chunk is read only if loaded; on each side's paint pass,
    * the new side also synchronizes the facing bit of any loaded old side.
    */
   public void finishPaneAndBarConnections() {
      int bottom = Math.max(1, this.groundY);
      int top = Math.min(this.chunk.getWorld().getMaxHeight() - 1, this.groundY + PANE_SCAN_HEIGHT);
      for (int lx=0; lx<16; lx++) for (int lz=0; lz<16; lz++) for(int y=bottom;y<=top;y++) {
         Block here=this.chunk.getBlock(lx,y,lz);
         if (!isPaneOrBars(here.getType())) continue;
         syncPane(here);
         if(lx==0)syncLoadedNeighbor(here,BlockFace.WEST);
         if(lx==15)syncLoadedNeighbor(here,BlockFace.EAST);
         if(lz==0)syncLoadedNeighbor(here,BlockFace.NORTH);
         if(lz==15)syncLoadedNeighbor(here,BlockFace.SOUTH);
      }
      // Mark only after the local roof, windows and internal railings have been completely painted.
      // When a neighbouring chunk is first generated, only it may be loaded; an older
      // farm chunk can be unloaded. ChunkLoadEvent repairs the saved seam when it returns.
      this.chunk.getPersistentDataContainer().set(FARM_GROUND, PersistentDataType.INTEGER, this.groundY);
      reconnectFarmSeams(this.chunk);
   }

   /**
    * Reconcile only the 1-block shared boundary of loaded chunks. This never loads a
    * neighbour or repaints terrain, and never changes a non-farm block state.
    * Farm ground height is persisted so seam repairs also work after restart.
    */
   public static void reconnectFarmSeams(Chunk chunk) {
      World world = chunk.getWorld();
      Integer thisGround = chunk.getPersistentDataContainer().get(FARM_GROUND, PersistentDataType.INTEGER);
      for (BlockFace side : SIDES) {
         int ncx = chunk.getX() + side.getModX(), ncz = chunk.getZ() + side.getModZ();
         if (!world.isChunkLoaded(ncx, ncz)) continue;
         Chunk other = world.getChunkAt(ncx, ncz); // loaded only: no generation of neighbours
         Integer otherGround = other.getPersistentDataContainer().get(FARM_GROUND, PersistentDataType.INTEGER);
         if (thisGround == null && otherGround == null) continue;
         int ground = thisGround != null ? thisGround : otherGround;
         int bottom = Math.max(world.getMinHeight(), ground);
         int top = Math.min(world.getMaxHeight() - 1, ground + PANE_SCAN_HEIGHT);
         if (thisGround != null && otherGround != null) {
            bottom = Math.max(world.getMinHeight(), Math.min(thisGround, otherGround));
            top = Math.min(world.getMaxHeight() - 1, Math.max(thisGround, otherGround) + PANE_SCAN_HEIGHT);
         }
         for (int edge = 0; edge < 16; edge++) for (int y = bottom; y <= top; y++) {
            int ax = side == BlockFace.EAST ? 15 : side == BlockFace.WEST ? 0 : edge;
            int az = side == BlockFace.SOUTH ? 15 : side == BlockFace.NORTH ? 0 : edge;
            int bx = side == BlockFace.EAST ? 0 : side == BlockFace.WEST ? 15 : edge;
            int bz = side == BlockFace.SOUTH ? 0 : side == BlockFace.NORTH ? 15 : edge;
            Block a = chunk.getBlock(ax, y, az), b = other.getBlock(bx, y, bz);
            if (thisGround != null && isPaneOrBars(a.getType())) syncPane(a);
            if (otherGround != null && isPaneOrBars(b.getType())) syncPane(b);
         }
      }
   }

   private static boolean isPaneOrBars(Material m) {
      return m==Material.IRON_BARS || m.name().endsWith("_GLASS_PANE") || m==Material.GLASS_PANE;
   }

   private static boolean attachesTo(Material m) {
      if (isPaneOrBars(m) || m == Material.GLASS || m.name().endsWith("_GLASS")) return true;
      // Exclude the universal empty neighbors before querying block occlusion.
      // This also avoids unnecessary registry lookups for the common air cells.
      if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return false;
      return m.isOccluding();
   }

   private static final BlockFace[] SIDES={BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST};

   private static void syncPane(Block here) {
      BlockData state=here.getBlockData();
      if (!(state instanceof MultipleFacing facing)) return;
      boolean changed=false;
      for (BlockFace side:SIDES) {
         if(!facing.getAllowedFaces().contains(side))continue;
         int nx=here.getX()+side.getModX(), nz=here.getZ()+side.getModZ();
         // Never create/generate other chunks as a side effect of connecting panes.
         if(!here.getWorld().isChunkLoaded(Math.floorDiv(nx,16),Math.floorDiv(nz,16)))continue;
         boolean desired=attachesTo(here.getWorld().getBlockAt(nx,here.getY(),nz).getType());
         if(facing.hasFace(side)!=desired){facing.setFace(side,desired);changed=true;}
      }
      if(changed)here.setBlockData(facing,false);
   }

   private static void syncLoadedNeighbor(Block here,BlockFace direction) {
      int nx=here.getX()+direction.getModX(),nz=here.getZ()+direction.getModZ();
      if(!here.getWorld().isChunkLoaded(Math.floorDiv(nx,16),Math.floorDiv(nz,16)))return;
      Block neighbor=here.getWorld().getBlockAt(nx,here.getY(),nz);
      if(isPaneOrBars(neighbor.getType()))syncPane(neighbor);
   }

   /**
    * A loot chest. facing is the legacy chest data value (2 north, 3 south,
    * 4 west, 5 east) and is where the lid of the chest looks. The chest is
    * named "(section sign)a" + residential + "_" + level, the format
    * ChestLootz reads, using the same collection the residential buildings
    * use. It is skipped by chance (generator.farm.chestOddsInPercent), and
    * left unnamed when generator.chestRenaming is off, like every other chest.
    */
   void chest(int x, int y, int z, int facing, int salt) {
      chest(x, y, z, facing, salt, TIER_MID);
   }

   void chest(int x, int y, int z, int facing, int salt, int tier) {
      if (!this.in(x, z)) {
         return;
      }
      if (FarmRandom.unit(this.seed, x * 31 + y, z, 900 + salt) * 100.0 >= PluginConfig.getFarmChestOddsPercent()) {
         return;
      }
      Block block = this.chunk.getBlock(x & 15, y, z & 15);
      LegacyBlocks.set(block, CHEST, facing);
      if (!PluginConfig.isChestRenaming()) {
         return;
      }
      try {
         BlockState state = block.getState();
         if (state instanceof Chest) {
            int min = Math.max(1, Math.min(5, PluginConfig.getFarmLootMinLevel()));
            int max = Math.max(min, Math.min(5, PluginConfig.getFarmLootMaxLevel()));
            int level = tier <= TIER_LOW ? min : tier >= TIER_TOP ? max
                    : min + (max - min) / 2;
            DirtyHacks.setChestName((Chest) state, "\u00a7" + ChatColor.GREEN.getChar() + LootType.RESIDENTIAL.name + "_" + level);
            state.update();
         }
      } catch (Throwable t) {
         // an unnamed chest is still a usable chest; never let it stop the chunk
      }
   }
}
