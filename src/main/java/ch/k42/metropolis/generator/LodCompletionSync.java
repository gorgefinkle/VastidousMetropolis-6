package ch.k42.metropolis.generator;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * BYTECODE-GUIDED MANUAL RESTORATION of R30ea's LodServerSupport dirty-chunk bridge.
 * This was NOT present in the 2026-10-31 authentic source archive. The existing
 * binary is used as behavioral evidence, not as an overlay build dependency.
 *
 * NOT durable, NOT a save-completion protocol: markDirty is a notification.
 * R30ea only buffers notices if LodServerSupport exists but is not enabled.
 * Pending changes before its installation may be lost. This parity version
 * retains those limitations pending a proper save-coordinated redesign.
 */
public final class LodCompletionSync {
    private static final String LSS_PLUGIN = "LodServerSupport";
    private static volatile Plugin boundPlugin;
    private static volatile Object boundTracker;
    private static volatile Method boundMark;
    private static volatile boolean warned;
    private static final int MAX_PENDING_PER_WORLD = 24000;
    private static final Map<World, Set<Long>> pending = new ConcurrentHashMap<>();

    public static void install(Plugin metropolis) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnabled(PluginEnableEvent event) {
                if (LSS_PLUGIN.equals(event.getPlugin().getName())) flushPending(metropolis);
            }
        }, metropolis);
        flushPending(metropolis);
    }

    private static void queueIfStarting(World world, int x, int z) {
        Plugin p = Bukkit.getPluginManager().getPlugin(LSS_PLUGIN);
        if (p == null || p.isEnabled()) return;
        Set<Long> chunks = pending.computeIfAbsent(world, w -> ConcurrentHashMap.newKeySet());
        if (chunks.size() < MAX_PENDING_PER_WORLD) {
            chunks.add(((long)x << 32) | (z & 0xffffffffL));
        }
    }

    private static void flushPending(Plugin metropolis) {
        if (tracker() == null) return;
        int count = 0;
        for (Map.Entry<World, Set<Long>> e : pending.entrySet()) {
            World w = e.getKey();
            if (!pending.remove(w, e.getValue())) continue;
            for (Long key : e.getValue()) {
                int x = (int)(key >> 32), z = key.intValue();
                changed(w, x, z);
                count++;
            }
        }
        if (count > 0) metropolis.getLogger().info("Replayed " + count + " pending LodServerSupport chunk changes");
    }

    private LodCompletionSync() {}

    private static Object tracker() {
        Plugin p = Bukkit.getPluginManager().getPlugin(LSS_PLUGIN);
        if (p == null || !p.isEnabled()) return null;
        Object cached = boundTracker;
        if (p == boundPlugin && cached != null) return cached;
        synchronized (LodCompletionSync.class) {
            if (p == boundPlugin && boundTracker != null) return boundTracker;
            boundPlugin = p;
            boundTracker = null;
            boundMark = null;
            try {
                Object service = p.getClass().getMethod("getRequestService").invoke(p);
                if (service == null) return null;
                Object dirty = service.getClass().getMethod("getDirtyTracker").invoke(service);
                if (dirty == null) return null;
                Method mark = dirty.getClass().getMethod("markDirty", String.class, int.class, int.class);
                boundMark = mark;
                boundTracker = dirty;
                return dirty;
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (!warned) {
                    warned = true;
                    Bukkit.getLogger().warning("LodServerSupport dirty-tracker bridge not available: " + e);
                }
                return null;
            }
        }
    }

    public static void changed(World world, int x, int z) {
        if (world == null) return;
        queueIfStarting(world, x, z);
        Object dirty = tracker();
        Method mark = boundMark;
        if (dirty == null || mark == null) return;
        try {
            mark.invoke(dirty, world.getKey().toString(), x, z);
        } catch (ReflectiveOperationException | RuntimeException e) {
            synchronized (LodCompletionSync.class) {
                boundTracker = null;
                boundMark = null;
            }
            if (!warned) {
                warned = true;
                Bukkit.getLogger().warning("LodServerSupport dirty update failed: " + e);
            }
        }
    }

    public static void changed(MetropolisGenerator generator, int x, int z) {
        if (generator != null) changed(generator.getWorld(), x, z);
    }

    public static void changedFootprint(World world, int x, int z, int width, int depth) {
        if (world == null) return;
        eachFootprintChunk(x, z, width, depth, (cx, cz) -> {
            if (world.isChunkLoaded(cx, cz)) changed(world, cx, cz);
        });
    }

    static void eachFootprintChunk(int chunkX, int chunkZ, int widthBlocks, int depthBlocks,
                                   BiConsumer<Integer, Integer> observer) {
        if (widthBlocks <= 0 || depthBlocks <= 0) return;
        int endX = chunkX + (widthBlocks - 1) / 16;
        int endZ = chunkZ + (depthBlocks - 1) / 16;
        for (int cx = chunkX; cx <= endX; cx++) {
            for (int cz = chunkZ; cz <= endZ; cz++) observer.accept(cx, cz);
        }
    }

    public static void changedLoadedAround(World world, int cx, int cz, int radius) {
        if (world == null) return;
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                if (world.isChunkLoaded(x,z)) changed(world,x,z);
            }
        }
    }
}
