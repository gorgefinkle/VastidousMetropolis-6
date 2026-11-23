package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Diagnostics ONLY. Track *already placed* branch leaf blocks and identify
 * subsequent writes. Neither this class nor its callers alter branch geometry.
 *
 * The writer hook records a POSSIBLE cause before a queued WorldEdit write;
 * the later world read confirms actual damage. Uninstrumented writes can only
 * be attributed to the interval in which they were first detected.
 */
public final class BranchDamageWatch {
   private static final int MAX_TRACKED = 50000;
   private static final int SAMPLED_PER_TICK = 256;
   private static final Map<UUID, Map<Long, Entry>> positions = new HashMap<UUID, Map<Long, Entry>>();
   private static final Map<UUID, Map<Long, List<Entry>>> chunks = new HashMap<UUID, Map<Long, List<Entry>>>();
   private static final Deque<Entry> oldestFirst = new ArrayDeque<Entry>();
   private static boolean samplerStarted;

   private BranchDamageWatch() { }

   // Bukkit 1.12 world X/Z coordinates lie within +/- 30,000,000 blocks.
   private static long position(int x, int y, int z) {
      return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
   }
   private static long chunk(int x, int z) {
      return ((long)x << 32) ^ (z & 0xffffffffL);
   }

   public static void watch(Block block) {
      if (!PluginConfig.isBranchDamageDebugEnabled() || block == null) return;
      UUID wid = block.getWorld().getUID();
      Map<Long, Entry> world = positions.get(wid);
      if (world == null) { world = new HashMap<Long, Entry>(); positions.put(wid, world); }
      long pos = position(block.getX(), block.getY(), block.getZ());
      Entry prev = world.get(pos);
      if (prev != null) drop(prev);
      Entry entry = new Entry(block);
      world.put(pos, entry);
      Map<Long, List<Entry>> byChunk = chunks.get(wid);
      if (byChunk == null) { byChunk = new HashMap<Long, List<Entry>>(); chunks.put(wid, byChunk); }
      long ck = chunk(entry.x >> 4, entry.z >> 4);
      List<Entry> list = byChunk.get(ck);
      if (list == null) { list = new ArrayList<Entry>(); byChunk.put(ck, list); }
      list.add(entry);
      oldestFirst.addLast(entry);
      while (oldestFirst.size() > MAX_TRACKED) {
         // O(1) eviction from the bounded queue, even in very dense towns.
         Entry evicted = oldestFirst.removeFirst();
         dropWithoutDeque(evicted);
      }
   }

   /** Fast enough for each queued schematic write; no live world reads. */
   public static void attemptedWrite(World world, int x, int y, int z,
                                     int nextId, int nextData, String source) {
      if (!PluginConfig.isBranchDamageDebugEnabled() || !PluginConfig.isBranchDamageWritesDebugEnabled() || world == null) return;
      Map<Long, Entry> wm = positions.get(world.getUID());
      if (wm == null) return;
      Entry e = wm.get(position(x, y, z));
      if (e == null || !e.active || false) return;
      // A queued WorldEdit write might be superseded before flush. Record its
      // probable source; only a later mismatch is reported as proven damage.
      e.candidateWriter = source;
      if (e.writerStack == null) {
         StackTraceElement[] trace = Thread.currentThread().getStackTrace();
         StringBuilder sb = new StringBuilder();
         int taken = 0;
         for (StackTraceElement frame : trace) {
            if (!frame.getClassName().startsWith("ch.k42.metropolis.")) continue;
            if (frame.getClassName().equals(BranchDamageWatch.class.getName())) continue;
            if (taken++ > 0) sb.append(" <- ");
            sb.append(frame.getClassName()).append('.').append(frame.getMethodName())
                    .append(':').append(frame.getLineNumber());
            if (taken >= 5) break;
         }
         e.writerStack = sb.toString();
      }
   }

   /** Checks same + immediately adjacent chunks after a generation stage. */
   public static void scanNearby(World world, int chunkX, int chunkZ, String stage) {
      scanChunks(world, chunkX - 1, chunkZ - 1, chunkX + 1, chunkZ + 1, stage);
   }

   /** Checks all chunks overlapped by a potentially multi-chunk schematic. */
   public static void scanBlockArea(World world, int minX, int minZ, int maxX, int maxZ, String stage) {
      scanChunks(world, minX >> 4, minZ >> 4, maxX >> 4, maxZ >> 4, stage);
   }

   private static void scanChunks(World world, int x0, int z0, int x1, int z1, String stage) {
      if (!PluginConfig.isBranchDamageDebugEnabled() || world == null) return;
      Map<Long, List<Entry>> index = chunks.get(world.getUID());
      if (index == null) return;
      int changed = 0;
      for (int cx = x0; cx <= x1; cx++) {
         for (int cz = z0; cz <= z1; cz++) {
            List<Entry> list = index.get(chunk(cx, cz));
            if (list == null) continue;
            // snapshot: remove() mutates the list when damage is detected
            for (Entry e : new ArrayList<Entry>(list)) {
               if (isChanged(world, e, stage)) {
                  if (changed == 0) Minions.dBranchDamage("FIRST HIT at " + e.x + "," + e.y + "," + e.z
                          + " after " + stage + " writerCandidate="
                          + (e.candidateWriter == null ? "unknown" : e.candidateWriter)
                          + " (full stack in branch-nodes.log)");
                  drop(e); changed++;
               }
            }
         }
      }
      if (changed > 0) Minions.dBranchDamage("SUMMARY: " + changed + " watched branch leaf blocks changed after " + stage);
   }

   private static boolean isChanged(World world, Entry e, String stage) {
      if (!e.active || !world.isChunkLoaded(e.x >> 4, e.z >> 4)) return false;
      Block block = world.getBlockAt(e.x, e.y, e.z);
      if (block.getType() == e.material && block.getBlockData().getAsString().equals(e.blockData)) return false;
      String writer = e.candidateWriter == null ? "UNKNOWN (unintercepted writer)" : e.candidateWriter + " (candidate write, not proven final writer)";
      Minions.dBranchDamageDetail("world=" + world.getName() + " x=" + e.x + " y=" + e.y + " z=" + e.z
              + " expected=" + e.material + ":" + e.blockData
              + " found=" + block.getType() + ":" + block.getBlockData().getAsString()
              + " firstDetectedAfter=" + stage + " writer=" + writer
              + (e.writerStack == null ? "" : " stack=" + e.writerStack));
      return true;
   }

   private static void drop(Entry e) {
      if (e == null || !e.active) return;
      e.active = false;
      Map<Long, Entry> world = positions.get(e.worldId);
      if (world != null) world.remove(position(e.x, e.y, e.z));
      Map<Long, List<Entry>> index = chunks.get(e.worldId);
      if (index != null) {
         List<Entry> list = index.get(chunk(e.x >> 4, e.z >> 4));
         if (list != null) {
            list.remove(e);
            if (list.isEmpty()) index.remove(chunk(e.x >> 4, e.z >> 4));
         }
      }
      oldestFirst.remove(e); // Rare damage/replacement path, not per-leaf eviction.
   }

   private static void dropWithoutDeque(Entry e) {
      if (e == null || !e.active) return;
      e.active = false;
      Map<Long, Entry> world = positions.get(e.worldId);
      if (world != null) world.remove(position(e.x, e.y, e.z));
      Map<Long, List<Entry>> index = chunks.get(e.worldId);
      if (index != null) {
         List<Entry> list = index.get(chunk(e.x >> 4, e.z >> 4));
         if (list != null) {
            list.remove(e);
            if (list.isEmpty()) index.remove(chunk(e.x >> 4, e.z >> 4));
         }
      }
   }

   public static void startSampler(final MetropolisPlugin plugin) {
      if (!PluginConfig.isBranchDamageDebugEnabled() || samplerStarted) return;
      samplerStarted = true;
      Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
         @Override public void run() {
            if (oldestFirst.isEmpty()) return;
            int count = Math.min(SAMPLED_PER_TICK, oldestFirst.size());
            int damaged = 0;
            for (int i = 0; i < count; i++) {
               Entry e = oldestFirst.removeFirst();
               World world = Bukkit.getWorld(e.worldId);
               if (world != null && isChanged(world, e, "periodic sampler")) {
                  if (damaged == 0) Minions.dBranchDamage("PERIODIC FIRST HIT at "
                          + e.x + "," + e.y + "," + e.z + " writerCandidate="
                          + (e.candidateWriter == null ? "unknown" : e.candidateWriter)
                          + " (full stack in branch-nodes.log)");
                  dropWithoutDeque(e);
                  damaged++;
               } else if (e.active) {
                  oldestFirst.addLast(e);
               }
            }
            if (damaged > 0) Minions.dBranchDamage("PERIODIC SUMMARY: " + damaged
                    + " watched branch leaf blocks changed outside sampled generation stages; see branch-nodes.log");
         }
      }, 20L, 20L);
   }

   public static void reset() {
      positions.clear(); chunks.clear(); oldestFirst.clear(); samplerStarted = false;
   }

   private static final class Entry {
      final UUID worldId;
      final int x, y, z, materialId;
      final byte data;
      final org.bukkit.Material material;
      final String blockData;
      boolean active = true;
      String candidateWriter, writerStack;
      Entry(Block b) {
         worldId = b.getWorld().getUID(); x = b.getX(); y = b.getY(); z = b.getZ();
         material = b.getType(); blockData = b.getBlockData().getAsString();
         // Legacy IDs are retained only for recording the old queued-write hooks; live checks use full BlockData.
         materialId = -1; data = 0;
      }
   }
}
