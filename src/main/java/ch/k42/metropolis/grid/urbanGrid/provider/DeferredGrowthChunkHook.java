package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.generator.MetropolisGenerator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * R30ea-compatible handoff of a newly loaded chunk to pending tree/branch growth.
 *
 * Recovery note: manually restored from R30ea bytecode and tested in isolation;
 * this is not an original archived Java file.  A one-tick scheduler callback
 * waits for Bukkit's chunk-load event to finish; it never forces a chunk load.
 */
public final class DeferredGrowthChunkHook {
    private DeferredGrowthChunkHook() {}

    public static void onLoad(List<MetropolisGenerator> generators, ChunkLoadEvent event, JavaPlugin plugin) {
        World world = event.getWorld();
        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        for (MetropolisGenerator generator : generators) {
            if (generator.getWorld() != world) {
                continue;
            }
            if (!(generator.getDecayProvider() instanceof DecayProviderNormal provider)) {
                continue;
            }
            if (!provider.hasPendingGrowthAt(chunkX, chunkZ)) {
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    provider.onChunkLoaded(world, chunkX, chunkZ);
                }
            }, 1L);
            return;
        }
    }
}
