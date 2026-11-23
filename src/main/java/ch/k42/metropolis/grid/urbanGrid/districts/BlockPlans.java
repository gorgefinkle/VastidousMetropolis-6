package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.plugin.PluginConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The buildings of every city block, decided from the plan alone.
 *
 * A building is chosen so that the same schematic does not stand within cloneRadius chunks of itself (and varied ones are spread
 * out). Done tile by tile that depends on what the tile has built so far, i.e. on the order tiles are generated in. Done here it
 * depends on a fixed order of BLOCKS instead:
 *
 *    rank(block) = (class, fine)
 *       class = which of four checkerboard classes the block's corner falls in, on cells cloneRadius wide
 *       fine  = a hash of the block's corner
 *
 * A block avoids the buildings of every block that is LOWER in this order and lies within cloneRadius of it (and is itself
 * avoided by every higher one), so every two blocks within the radius are ordered one way or the other: the avoidance covers
 * every pair, whatever order tiles are generated in, and whichever tile asks. Because a block only looks at lower blocks,
 * working out one block works out a bounded set of others (a chain through at most four classes, and within a class only
 * through blocks of one cell); each block's buildings are cached once worked out.
 *
 * There is no grid and no area: a block looks at the lower blocks around it out to the furthest radius any setting uses (the soft limit),
 * and each setting reads the ones inside its own radius, with its usual taper by distance. Looking that far costs a fresh tile a lot
 * (a few thousand blocks planned for about forty, measured), and almost nothing after it: sweeping a 4x4 patch of tiles plans about
 * six times its own blocks in all, nearly all of it in the first tile.
 *
 * Nothing about a tile, a tile edge or an earlier tile enters here, so the result is a function of (world seed, block).
 */
public final class BlockPlans {
   private BlockPlans() {
   }

   /** One building (or "no schematic fits" placeholder) a block holds: enough to build the parcel again in any tile. */
   public static final class Placement {
      public final int baseX, baseZ, sizeX, sizeZ;
      public final Clipboard clipboard;
      public final ContextType context;
      public final SchematicType type;
      public final Direction direction;

      Placement(int baseX, int baseZ, int sizeX, int sizeZ, Clipboard clipboard, ContextType context, SchematicType type, Direction direction) {
         this.baseX = baseX;
         this.baseZ = baseZ;
         this.sizeX = sizeX;
         this.sizeZ = sizeZ;
         this.clipboard = clipboard;
         this.context = context;
         this.type = type;
         this.direction = direction;
      }

      Placement(ClipboardParcel p) {
         this.baseX = p.getChunkX();
         this.baseZ = p.getChunkZ();
         this.sizeX = p.getChunkSizeX();
         this.sizeZ = p.getChunkSizeZ();
         this.clipboard = p.getClipboard();
         this.context = p.getContextType();
         this.type = p.getSchematicType();
         this.direction = p.getDirection();
      }

      /** A fresh parcel for this placement on the given tile. */
      public ClipboardParcel toParcel(UrbanGrid grid) {
         return new ClipboardParcel(grid, new Cartesian2D(this.baseX, this.baseZ), new Cartesian2D(this.sizeX, this.sizeZ),
                 this.clipboard, this.context, this.type, this.direction);
      }
   }

   private static final class Key {
      final long seed;
      final int minX, minZ, maxX, maxZ;

      Key(long seed, WorldLayout.Rect b) {
         this.seed = seed;
         this.minX = b.minX;
         this.minZ = b.minZ;
         this.maxX = b.maxX;
         this.maxZ = b.maxZ;
      }

      @Override
      public boolean equals(Object o) {
         if (!(o instanceof Key)) {
            return false;
         }
         Key k = (Key) o;
         return this.seed == k.seed && this.minX == k.minX && this.minZ == k.minZ && this.maxX == k.maxX && this.maxZ == k.maxZ;
      }

      @Override
      public int hashCode() {
         return (int) (this.seed ^ (this.seed >>> 32)) * 31 * 31 * 31 + this.minX * 31 * 31 + this.minZ * 31 + this.maxX + this.maxZ * 7;
      }
   }

   /** Worked-out blocks, least recently used dropped. A dropped block is simply worked out again, to the same answer. */
   private static final int MAX_CACHED = 40000;
   private static final Map<Key, List<Placement>> CACHE = new LinkedHashMap<Key, List<Placement>>(1024, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Key, List<Placement>> eldest) {
         return this.size() > MAX_CACHED;
      }
   };
   private static final ReentrantLock LOCK = new ReentrantLock();

   // ------------------------------------------------------------------ the order

   private static long fine(long seed, WorldLayout.Rect b) {
      return GridRandom.generateLocalSeed(GridRandom.generateLocalSeed(seed ^ 0x72616E6B21L, b.minX, b.minZ), b.maxX, b.maxZ);
   }

   private static int rankClass(WorldLayout.Rect b, int cell) {
      return (Math.floorDiv(b.minX, cell) & 1) | ((Math.floorDiv(b.minZ, cell) & 1) << 1);
   }

   /** Is a lower than b in the order? A strict total order on blocks. */
   static boolean lower(long seed, int cell, WorldLayout.Rect a, WorldLayout.Rect b) {
      int ca = rankClass(a, cell);
      int cb = rankClass(b, cell);
      if (ca != cb) {
         return ca < cb;
      }
      long fa = fine(seed, a);
      long fb = fine(seed, b);
      if (fa != fb) {
         return fa < fb;
      }
      return a.minX != b.minX ? a.minX < b.minX : a.minZ < b.minZ;
   }

   private static boolean same(WorldLayout.Rect a, WorldLayout.Rect b) {
      return a.minX == b.minX && a.minZ == b.minZ && a.maxX == b.maxX && a.maxZ == b.maxZ;
   }

   /** A side is eligible if at least one of its chunks has a planned street.
    * Each chosen street-facing schematic is independently required to have
    * road along its FULL specific frontage by District.hasRealRoadFrontage().
    * Keeping the two checks separate allows partial-coast blocks to use the
    * landward portion without falsely treating a shoreline as a street. */
   private static boolean hasPlannedFrontage(WorldLayout layout, int x, int z, int width, int depth, Direction facing) {
      if (facing == Direction.NONE || width <= 0 || depth <= 0) return false;
      if (facing == Direction.NORTH) return everyCellIsRoad(layout, x, x + width - 1, z - 1, true);
      if (facing == Direction.SOUTH) return everyCellIsRoad(layout, x, x + width - 1, z + depth, true);
      if (facing == Direction.WEST) return everyCellIsRoad(layout, z, z + depth - 1, x - 1, false);
      return everyCellIsRoad(layout, z, z + depth - 1, x + width, false);
   }

   private static boolean everyCellIsRoad(WorldLayout layout, int from, int through, int fixed, boolean alongX) {
      for (int i = from; i <= through; i++) {
         if (!(alongX ? layout.isCityRoadCell(i, fixed) : layout.isCityRoadCell(fixed, i))) return false;
      }
      return true;
   }

   private static boolean hasPlannedRoad(WorldLayout layout, int from, int through, int fixed, boolean alongX) {
      for (int i = from; i <= through; i++) {
         if (alongX ? layout.isCityRoadCell(i, fixed) : layout.isCityRoadCell(fixed, i)) {
            return true;
         }
      }
      return false;
   }

   private static boolean holdsBuildings(WorldLayout layout, WorldLayout.Rect b) {
      ContextType c = layout.contextOf(b);
      return c != null && !(PluginConfig.isMorphomosEnabled() && c.isMorphomos());
   }

   /** The world plan, not the tile, decides whether this complete block
    * abuts an accepted creek. That keeps generated schematics deterministic
    * regardless of which tile requests the block first. It does not affect
    * the main river, ocean, or ordinary city blocks. */
   private static boolean isCreekReclaimBlock(long seed, WorldLayout.Rect block) {
      WaterField water = WorldLayouts.waterFor(seed);
      // A shore-front block is separated from the creek by a one-chunk
      // street; allow the full three-chunk shoreline band to be counted.
      // A valid BLOCK cannot contain the protected creek itself; only its
      // perimeter can border the creek. Scan that shallow perimeter rather
      // than the entire rectangular area, and use the creek source directly
      // so we never recompute ocean or main-river geometry per filler query.
      for (int x = block.minX - 3; x <= block.maxX + 3; x++) {
         for (int delta = 1; delta <= 3; delta++) {
            if (water.isCreek(x, block.minZ - delta)
                    || water.isCreek(x, block.maxZ + delta)) return true;
         }
      }
      for (int z = block.minZ; z <= block.maxZ; z++) {
         for (int delta = 1; delta <= 3; delta++) {
            if (water.isCreek(block.minX - delta, z)
                    || water.isCreek(block.maxX + delta, z)) return true;
         }
      }
      return false;
   }

   // ------------------------------------------------------------------ the plan

   /**
    * How far a block looks: the furthest any clone or variant setting reaches (the soft limit, for the global setting and for dense
    * residential), at least the hard clone radius, at most 60 chunks. The checkerboard classes of the rank are as wide as this, so two
    * blocks of one class within it share a cell and the chain of "lower blocks" stays short.
    */
   private static int lookRadius() {
      int soft = Math.max(PluginConfig.getCloneRadiusSoftLimit(), PluginConfig.getDenseResidentialCloneRadiusSoftLimit());
      int hard = Math.max(1, Math.max(PluginConfig.getCloneRadius(), PluginConfig.getVariantRadius()));
      return Math.max(hard, Math.min(soft, 60));
   }

   /** The buildings of a block, the same whichever tile asks and whenever it asks. */
   public static List<Placement> planFor(UrbanGrid tile, long seed, WorldLayout layout, WorldLayout.Rect block) {
      LOCK.lock();
      boolean wasQuiet = ClipboardParcel.isQuiet();
      ClipboardParcel.setQuiet(true);      // working a block out is not building it: do not count its schematics
      try {
         return plan(tile, seed, layout, block);
      } finally {
         ClipboardParcel.setQuiet(wasQuiet);
         LOCK.unlock();
      }
   }

   private static List<Placement> plan(UrbanGrid tile, long seed, WorldLayout layout, WorldLayout.Rect block) {
      Key key = new Key(seed, block);
      List<Placement> cached = CACHE.get(key);
      if (cached != null) {
         return cached;
      }
      // A block reserved for a landmark holds that one building, chosen from the plan alone (see LandmarkPlans)
      WorldLayout.Landmark landmark = layout.landmarkAt(block);
      if (landmark != null) {
         List<Placement> single = new ArrayList<Placement>();
         Placement chosenLandmark = LandmarkPlans.placementFor(landmark, block);
         // Landmark placement bypasses District.placeRandomRec(), so it must
         // obey the same absolute rule independently.
         if (chosenLandmark != null && chosenLandmark.clipboard != null
                 && chosenLandmark.clipboard.getConfig() != null
                 && chosenLandmark.clipboard.getConfig().getRoadFacing()) {
            if (!hasPlannedFrontage(layout, chosenLandmark.baseX, chosenLandmark.baseZ,
                    chosenLandmark.sizeX, chosenLandmark.sizeZ, chosenLandmark.direction)) {
               CACHE.put(key, single);
               return single;
            }
         }
         if (chosenLandmark != null) single.add(chosenLandmark);
         CACHE.put(key, single);
         return single;
      }
      int radius = lookRadius();

      // What this block has to keep clear of: the buildings of every LOWER block within the look radius. The hard clone wall,
      // the variant spread and the soft taper each read the ones that fall inside their own radius (BlockHost.getNeighbours).
      List<ClipboardParcel> before = new ArrayList<ClipboardParcel>();
      for (WorldLayout.Rect n : layout.blocksIn(block.minX - radius, block.minZ - radius, block.maxX + radius, block.maxZ + radius)) {
         if (same(n, block) || !holdsBuildings(layout, n) || !lower(seed, radius, n, block)) {
            continue;
         }
         int gapX = Math.max(Math.max(n.minX - block.maxX, block.minX - n.maxX), 0);
         int gapZ = Math.max(Math.max(n.minZ - block.maxZ, block.minZ - n.maxZ), 0);
         if (gapX > radius || gapZ > radius) {
            continue;
         }
         for (Placement p : plan(tile, seed, layout, n)) {
            before.add(p.toParcel(tile));
         }
      }

      BlockHost host = new BlockHost(tile, seed, block.minX, block.minZ, block.maxX, block.maxZ, before);
      District whole = new District();
      whole.initDistrict(new Cartesian2D(block.minX, block.minZ),
              new Cartesian2D(block.maxX - block.minX + 1, block.maxZ - block.minZ + 1), tile);
      whole.useHost(host);
      whole.setCreekReclaimSchematicFill(isCreekReclaimBlock(seed, block));
      // The world layout can end a block against the ocean / wilderness, so
      // its four sides are NOT automatically streets. Only mark a side if
      // the immutable plan actually carries a street or highway along it.
      whole.setRoadSides(
              hasPlannedRoad(layout, block.minX, block.maxX, block.minZ - 1, true),
              hasPlannedRoad(layout, block.minX, block.maxX, block.maxZ + 1, true),
              hasPlannedRoad(layout, block.minZ, block.maxZ, block.maxX + 1, false),
              hasPlannedRoad(layout, block.minZ, block.maxZ, block.minX - 1, false));
      ContextType context = layout.contextOf(block);
      if (context != null) {
         whole.assignContext(context);
      }
      whole.fillDistrict();

      List<Placement> placements = new ArrayList<Placement>();
      for (Parcel p : host.parcels()) {
         if (p instanceof ClipboardParcel) {
            placements.add(new Placement((ClipboardParcel) p));
         }
      }
      CACHE.put(key, placements);
      return placements;
   }
}
