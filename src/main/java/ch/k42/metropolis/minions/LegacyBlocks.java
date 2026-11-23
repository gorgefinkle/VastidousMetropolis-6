package ch.k42.metropolis.minions;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.world.registry.LegacyMapper;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * The 1.12.2 farm, forest and wilderness code describes every block as a numeric id plus a data nibble, and picks
 * variants by doing arithmetic on those (planks 0/1/2/4/5, "leaves + 4 = no decay", chest facing 2..5, ...). Rewriting all
 * of that logic would change what it generates, so it is kept exactly as it was and this class is the ONE place where an
 * (id, data) pair becomes a modern block.
 *
 * The translation is Paper's own legacy conversion (the same table the server uses to load pre-1.13 worlds and plugins),
 * so orientation and variant nibbles (stairs, torches, chests, ladders, vines, logs, ...) come out as the block states
 * they always meant. Results are cached per (id, data).
 *
 * If a future Paper drops its legacy materials, this class is the only thing that has to be replaced.
 */
@SuppressWarnings("deprecation")
public final class LegacyBlocks {
   private static final int MAX_ID = 4096;
   private static final Material[] LEGACY_BY_ID = new Material[MAX_ID];
   private static final BlockData[] DATA_CACHE = new BlockData[MAX_ID * 16];
   private static final Set<Material>[] FAMILY = newFamilies();
   private static final ConcurrentHashMap<Integer, Boolean> WARNED = new ConcurrentHashMap<>();
   private static final AtomicBoolean READY = new AtomicBoolean();

   private LegacyBlocks() {
   }

   @SuppressWarnings("unchecked")
   private static Set<Material>[] newFamilies() {
      return (Set<Material>[]) new Set[MAX_ID];
   }

   private static void init() {
      if (READY.get()) {
         return;
      }
      synchronized (LegacyBlocks.class) {
         if (READY.get()) {
            return;
         }
         for (Material m : Material.values()) {
            if (m.isLegacy()) {
               int id = m.getId();
               if (id >= 0 && id < MAX_ID) {
                  LEGACY_BY_ID[id] = m;
               }
            }
         }
         int indexed = 0;
         for (Material m : LEGACY_BY_ID) {
            if (m != null) {
               indexed++;
            }
         }
         if (indexed == 0) {
            Minions.w("LegacyBlocks: Material.values() holds no legacy materials, so Paper's own legacy conversion cannot work here. "
                    + "Legacy (id, data) blocks will be converted through WorldEdit's LegacyMapper instead.");
         } else {
            Minions.d(String.format("LegacyBlocks: %d legacy materials indexed", indexed));
         }
         READY.set(true);
      }
   }

   /** The modern block state for a 1.12 id and data nibble. Unknown ids come out as air (warned about once). */
   public static BlockData data(int id, int data) {
      return resolve(id, data, true);
   }

   /** warn=false is for family() scans over nibbles an id may never use: no warning, and an unmapped result is not remembered. */
   private static BlockData resolve(int id, int data, boolean warn) {
      init();
      if (id < 0 || id >= MAX_ID) {
         return Material.AIR.createBlockData();
      }
      int key = id << 4 | (data & 15);
      BlockData cached = DATA_CACHE[key];
      if (cached != null) {
         return cached;
      }
      BlockData result = null;
      Material legacy = LEGACY_BY_ID[id];
      if (legacy != null) {
         try {
            result = Bukkit.getUnsafe().fromLegacy(legacy, (byte) (data & 15));
         } catch (RuntimeException | LinkageError e) {
            noteFailure("Paper's legacy conversion", id, data, e);
            result = null;
         }
      }
      if (result == null || (id != 0 && result.getMaterial().isAir())) {
         // Paper had no answer (or called a real block "air"): ask WorldEdit, which keeps a complete 1.12 table and
         // upgrades it through the data fixer, so blocks renamed since 1.13 come out right.
         BlockData viaWe = viaWorldEdit(id, data);
         if (viaWe != null) {
            result = viaWe;
         }
      }
      if (result == null || (id != 0 && result.getMaterial().isAir())) {
         if (!warn) {
            return Material.AIR.createBlockData();
         }
         if (WARNED.putIfAbsent(id, Boolean.TRUE) == null) {
            Minions.w("No modern block for legacy id %d (data %d) -- placing air instead", id, data);
         }
         result = Material.AIR.createBlockData();
      }
      DATA_CACHE[key] = result;
      return result;
   }

   private static final AtomicBoolean PAPER_FAILURE_NOTED = new AtomicBoolean();
   private static final AtomicBoolean WE_FALLBACK_NOTED = new AtomicBoolean();
   private static final AtomicBoolean WE_FAILURE_NOTED = new AtomicBoolean();

   /** The first failure of a conversion path is logged with its real exception; the rest stay quiet. */
   private static void noteFailure(String what, int id, int data, Throwable e) {
      if (PAPER_FAILURE_NOTED.compareAndSet(false, true)) {
         Minions.w("LegacyBlocks: %s threw for legacy id %d (data %d): %s: %s (first failure only; falling back to WorldEdit).",
                 what, id, data, e.getClass().getName(), e.getMessage());
      }
   }

   private static BlockData viaWorldEdit(int id, int data) {
      try {
         com.sk89q.worldedit.world.block.BlockState state = LegacyMapper.getInstance().getBlockFromLegacy(id, data & 15);
         if (state == null) {
            return null;
         }
         BlockData out = BukkitAdapter.adapt(state);
         if (WE_FALLBACK_NOTED.compareAndSet(false, true)) {
            Minions.w("LegacyBlocks: Paper's conversion gave nothing for legacy id %d (data %d); using WorldEdit's LegacyMapper -> %s. "
                    + "Further fallbacks are not logged.", id, data, out.getAsString());
         }
         return out;
      } catch (Throwable e) {
         if (WE_FAILURE_NOTED.compareAndSet(false, true)) {
            Minions.w("LegacyBlocks: WorldEdit's LegacyMapper failed for legacy id %d (data %d): %s: %s", id, data,
                    e.getClass().getName(), e.getMessage());
         }
         return null;
      }
   }

   public static Material material(int id, int data) {
      return data(id, data).getMaterial();
   }

   /** Writes the block, without physics (like setTypeIdAndData(id, data, false)). */
   public static void set(Block block, int id, int data) {
      BlockData converted = data(id, data);
      if (ch.k42.metropolis.grid.urbanGrid.parcel.ShorePlantSafety.terrestrial(converted.getMaterial())
              && !ch.k42.metropolis.grid.urbanGrid.parcel.ShorePlantSafety.canPlaceTerrestrial(block, converted.getMaterial())) {
         return;
      }
      block.setBlockData(converted, false);
   }

   /**
    * Every modern material that the legacy id can be, over all its data values. "Is this block a chest / a plank /
    * grass?" in the old code compared only the id, which is exactly "is the material in this family".
    */
   public static Set<Material> family(int id) {
      init();
      if (id < 0 || id >= MAX_ID) {
         return EnumSet.noneOf(Material.class);
      }
      Set<Material> set = FAMILY[id];
      if (set == null) {
         set = EnumSet.noneOf(Material.class);
         for (int d = 0; d < 16; d++) {
            set.add(resolve(id, d, false).getMaterial());
         }
         if (id != 0) {
            set.remove(Material.AIR);
         } else {
            set.add(Material.AIR);
            set.add(Material.CAVE_AIR);
            set.add(Material.VOID_AIR);
         }
         FAMILY[id] = set;
      }
      return set;
   }

   /** The old {@code block.getTypeId() == id}. */
   public static boolean is(Block block, int id) {
      return family(id).contains(block.getType());
   }

   public static boolean is(Material type, int id) {
      return family(id).contains(type);
   }

   /** The old {@code block.getTypeId() == id && block.getData() == data}, ignoring block states the old nibble could not hold. */
   public static boolean is(Block block, int id, int data) {
      return block.getType() == material(id, data);
   }
}
