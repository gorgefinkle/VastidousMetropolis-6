package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.FileClipboard;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;
import ch.k42.metropolis.minions.Minions;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * RESTORATION, not the missing original Java source. Reproduces R30ea's
 * overflow slice drain as shown by the supplied JAR's compiled methods.
 *
 * WARNING: This class is intentionally NOT a corrected or persistent queue.
 * Jobs retain ClipboardParcel and FileClipboard references, and the registry
 * retains generators. The success-notification failure path still drops a job.
 * The new durable queue must change those semantics in a separately tested
 * production integration, rather than silently changing recovered history.
 */
public final class SchematicOverflowDrain {
    private static final Map<UUID, Scope> SCOPES = new HashMap<>();

    private SchematicOverflowDrain() {}

    public static int register(ClipboardParcel building, MetropolisGenerator generator) {
        Clipboard clipboard = building.getClipboard();
        if (!(clipboard instanceof FileClipboard file)) {
            return 0;
        }
        Cartesian3D size = file.getSize();
        int actualChunksX = Math.floorDiv(size.X + 15, 16);
        int actualChunksZ = Math.floorDiv(size.Z + 15, 16);
        int declaredX = building.getChunkSizeX();
        int declaredZ = building.getChunkSizeZ();
        if (actualChunksX <= declaredX && actualChunksZ <= declaredZ) {
            return 0;
        }
        World world = generator.getWorld();
        if (world == null) {
            throw new IllegalStateException("Cannot schedule schematic overflow before world init");
        }
        Scope scope = SCOPES.computeIfAbsent(world.getUID(), ignored -> new Scope(generator));
        int queued = 0;
        for (int dz = 0; dz < actualChunksZ; dz++) {
            for (int dx = 0; dx < actualChunksX; dx++) {
                if (dx < declaredX && dz < declaredZ) {
                    continue;
                }
                scope.jobs.addLast(new Job(building, file,
                        building.getChunkX() + dx, building.getChunkZ() + dz));
                ++queued;
            }
        }
        if (queued > 0) {
            Minions.i("[R30bp] Queued %d real overflow chunk slices (NOT clipped) for %s at [%d,%d] actual=%dx%d declared=%dx%d",
                    queued, file.getGroupId(), building.getChunkX(), building.getChunkZ(),
                    size.X, size.Z, declaredX * 16, declaredZ * 16);
            scope.schedule(1L);
        }
        return queued;
    }

    private static final class Scope {
        final MetropolisGenerator generator;
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        boolean scheduled;
        boolean loading;

        Scope(MetropolisGenerator generator) { this.generator = generator; }

        void schedule(long delayTicks) {
            if (scheduled || loading || jobs.isEmpty()) {
                return;
            }
            if (!generator.getPlugin().isEnabled()) {
                return;
            }
            scheduled = true;
            Bukkit.getScheduler().runTaskLater(generator.getPlugin(), () -> {
                scheduled = false;
                pump();
            }, delayTicks);
        }

        void pump() {
            if (loading || jobs.isEmpty() || !generator.getPlugin().isEnabled()) {
                return;
            }
            World world = generator.getWorld();
            if (world == null) {
                return;
            }
            Job job = jobs.peekFirst();
            if (!world.isChunkLoaded(job.x, job.z)) {
                loading = true;
                CompletableFuture<Chunk> loaded;
                try {
                    loaded = world.getChunkAtAsync(job.x, job.z, true);
                } catch (RuntimeException e) {
                    loading = false;
                    fail(job, e);
                    return;
                }
                loaded.whenComplete((chunk, problem) -> {
                    if (!generator.getPlugin().isEnabled()) {
                        return;
                    }
                    Bukkit.getScheduler().runTask(generator.getPlugin(), () -> {
                        loading = false;
                        if (problem != null) {
                            fail(job, problem);
                        } else {
                            try {
                                job.hasTicket = chunk.addPluginChunkTicket(generator.getPlugin());
                                job.pinnedChunk = chunk;
                                schedule(1L);
                            } catch (RuntimeException e) {
                                fail(job, e);
                            }
                        }
                    });
                });
                return;
            }
            try {
                job.file.pasteChunk(generator, new Cartesian2D(job.building.getChunkX(), job.building.getChunkZ()),
                        job.building.effectiveBuildHeight(), job.x, job.z);
            } catch (Throwable e) {
                fail(job, e);
                return;
            }
            jobs.removeFirst();
            try {
                // Keep the chunk ticket through the building callback: it
                // records interior candidates from this already-loaded slice
                // and may finish the logical BUILD after the final overflow.
                job.building.overflowCompleted(job.x, job.z, generator);
            } catch (Throwable e) {
                Minions.w("[R30bp] Overflow notification failed for [%d,%d]: %s", job.x, job.z, e.toString());
            } finally {
                release(job);
            }
            schedule(1L);
        }

        void release(Job job) {
            if (job.hasTicket) {
                job.hasTicket = false;
                Chunk pinned = job.pinnedChunk;
                job.pinnedChunk = null;
                if (pinned != null) {
                    try {
                        pinned.removePluginChunkTicket(generator.getPlugin());
                    } catch (RuntimeException e) {
                        Minions.w("[R30bp] Failed to release temporary overflow ticket for [%d,%d]: %s",
                                job.x, job.z, e.toString());
                    }
                }
            }
        }

        void fail(Job job, Throwable problem) {
            release(job);
            ++job.failures;
            if (job.failures == 1 || job.failures % 20 == 0) {
                Minions.w("[R30bp] Overflow slice [%d,%d] for %s is still pending after %d failed attempt(s): %s",
                        job.x, job.z, job.file.getGroupId(), job.failures, problem.toString());
            }
            jobs.removeFirst();
            jobs.addLast(job);
            schedule(Math.min(200L, 10L + 10L * job.failures));
        }
    }

    private static final class Job {
        final ClipboardParcel building;
        final FileClipboard file;
        final int x;
        final int z;
        int failures;
        boolean hasTicket;
        Chunk pinnedChunk;
        Job(ClipboardParcel building, FileClipboard file, int x, int z) {
            this.building = building;
            this.file = file;
            this.x = x;
            this.z = z;
        }
    }
}
