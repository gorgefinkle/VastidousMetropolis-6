package ch.k42.metropolis.generator;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;

/**
 * BYTECODE-GUIDED SOURCE RESTORATION of a class absent from the October 31
 * source archive. Contract confirmed against R30ea class descriptors, logic
 * reconstructed from the R30ea method instructions and tested in isolation.
 * This deliberately retains the current world-name key and 200-tick cap for
 * R30ea parity. No Paper/server test is implied.
 */
public final class DeferredFailureBackoff {
    private static final ConcurrentHashMap<String, Long> DELAY_TICKS = new ConcurrentHashMap<>();

    private DeferredFailureBackoff() {}

    public static void failed(World world, int[] ignored) {
        if (world != null) DELAY_TICKS.merge(world.getName(), 20L,
                (previous, value) -> Math.min(200L, previous * 2L));
    }

    public static void succeeded(World world, int[] ignored) {
        if (world != null) DELAY_TICKS.remove(world.getName());
    }

    public static BukkitTask schedule(BukkitScheduler scheduler, Plugin plugin, Runnable task, World world) {
        long delay = world == null ? 1L : DELAY_TICKS.getOrDefault(world.getName(), 1L);
        return scheduler.runTaskLater(plugin, task, Math.max(1L, delay));
    }
}
