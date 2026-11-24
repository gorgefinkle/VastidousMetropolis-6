package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.provider.DecayProviderNormal.PendingBuild;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Fair, time-budgeted drain for completed branch-growth caches.
 *
 * The old restored-R30ea scheduler deliberately limited itself to one build
 * and one generated chunk per tick. That was a fixed 20-build/second ceiling
 * regardless of available tick time. This drain advances multiple jobs in a
 * bounded server-tick budget and no longer uses reflection to reach its owner.
 */
public final class BranchGrowthDrain implements Runnable {
    private static final int MAX_BUILDS_PER_TICK = BranchGrowthDrainThroughputPolicy.MAX_BUILDS_PER_TICK;
    private static final int SCANS_PER_TICK = BranchGrowthDrainThroughputPolicy.MAX_SCANS_PER_TICK;
    private static final int GENERATED_RELOADS_PER_TICK = BranchGrowthDrainThroughputPolicy.GENERATED_CHUNK_LOADS_PER_TICK;
    private static final int MAX_RETAINED_CHUNKS = BranchGrowthDrainBatchPolicy.MAX_CHUNKS_PER_BATCH;
    private static final long SOFT_WORK_BUDGET_NS = BranchGrowthDrainThroughputPolicy.SOFT_WORK_BUDGET_NS;
    private static final long RETRY_TICKS = 100L;
    private static final long STAGING_TIMEOUT_TICKS = 120L;
    private static final long STAGING_COOLDOWN_TICKS = 20L;
    private static BranchGrowthDrain instance;

    private final Plugin plugin;
    private BukkitTask ticker;
    private long tick;
    private final ArrayDeque<Job> rotation = new ArrayDeque<>();
    private final IdentityHashMap<DecayProviderNormal, IdentityHashMap<Parcel, Job>> jobs = new IdentityHashMap<>();
    private Job staging;
    private long lastWarningTick;
    private long stagingSince;

    private BranchGrowthDrain(Plugin plugin) { this.plugin = plugin; }

    public static void shutdownNow() {
        BranchGrowthDrain current = instance;
        if (current != null) current.shutdown();
    }

    public static void enqueue(DecayProviderNormal owner, Parcel build, PendingBuild acc) {
        if (owner == null || build == null || acc == null) return;
        BranchGrowthDrain current = instance;
        if (current != null && !current.plugin.isEnabled()) {
            current.shutdown();
            return;
        }
        if (current == null) {
            Plugin p = Bukkit.getPluginManager().getPlugin("Metropolis");
            // A late chunk/timeout callback during shutdown is a no-op, not
            // a reason to recreate the ticker or throw into the scheduler.
            if (p == null || !p.isEnabled()) return;
            current = new BranchGrowthDrain(p);
            instance = current;
        }
        IdentityHashMap<Parcel, Job> map = current.jobs.computeIfAbsent(owner, ignored -> new IdentityHashMap<>());
        Job existing = map.get(build);
        if (existing != null) {
            existing.retryAt = current.tick;
            return;
        }
        Job job = new Job(owner, build, acc);
        map.put(build, job);
        current.rotation.addLast(job);
        if (current.ticker == null) {
            current.ticker = Bukkit.getScheduler().runTaskTimer(current.plugin, current, 1L, 1L);
        }
    }

    private boolean stillPending(Job job) {
        return job.owner.isPendingForDrain(job.build, job.acc);
    }

    private Set<Long> missing(Job job) {
        return job.owner.waitingChunksForDrain(job.acc);
    }

    private World world(Job job) {
        return job.owner.worldForDrain(job.acc);
    }

    private void release(Job job) {
        if (job.world != null) {
            for (Long value : job.retained) {
                int cx = (int) (value >> 32), cz = (int) (long) value;
                try {
                    job.world.removePluginChunkTicket(cx, cz, plugin);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Branch growth chunk ticket release failed: " + e);
                }
            }
        }
        job.retained.clear();
        if (staging == job) staging = null;
    }

    private void complete(Job job) {
        release(job);
        IdentityHashMap<Parcel, Job> ownersJobs = jobs.get(job.owner);
        if (ownersJobs != null) {
            ownersJobs.remove(job.build);
            if (ownersJobs.isEmpty()) jobs.remove(job.owner);
        }
        job.queued = false;
    }

    private void logError(String detail, Throwable error) {
        plugin.getLogger().severe(detail + ": " + error);
        error.printStackTrace();
    }

    /**
     * Loads and tickets a bounded batch of support chunks. Successful loads
     * continue in the same tick until the batch or the caller's time budget
     * is exhausted; a single chunk is no longer the per-tick throughput cap.
     * Returns the number of actual load attempts made.
     */
    private int advanceStaging(Job job, int attemptBudget) {
        if (staging != job || attemptBudget <= 0) return 0;
        World w = world(job);
        if (w == null) return 0;
        Set<Long> waiting = missing(job);
        if (waiting.isEmpty()) return 0;
        int attempts = 0;
        for (Long value : waiting) {
            long packed = value;
            int cx = (int) (packed >> 32), cz = (int) (long) packed;
            if (w.isChunkLoaded(cx, cz)) {
                // Natural/player loads supersede an old failed-attempt hint;
                // do not keep one boxed retry entry for every chunk in a large build.
                job.lastReloadAttempt.remove(value);
                if (!job.retained.contains(value) && job.retained.size() < MAX_RETAINED_CHUNKS) {
                    if (w.addPluginChunkTicket(cx, cz, plugin)) job.retained.add(value);
                }
                continue;
            }
            if (job.retained.contains(value)) continue;
            if (job.retained.size() >= MAX_RETAINED_CHUNKS) {
                if (tick - lastWarningTick >= 200L) {
                    plugin.getLogger().warning("Branch growth requires more than 128 simultaneous support chunks; keeping the build deferred until enough chunks are naturally resident.");
                    lastWarningTick = tick;
                }
                break;
            }
            Long last = job.lastReloadAttempt.get(value);
            if (last != null && tick - last < RETRY_TICKS) continue;
            job.lastReloadAttempt.put(value, tick);
            attempts++;
            boolean loaded = w.loadChunk(cx, cz, false);
            if (loaded && w.isChunkLoaded(cx, cz) && w.addPluginChunkTicket(cx, cz, plugin)) {
                job.lastReloadAttempt.remove(value);
                job.retained.add(value);
            }
            if (attempts >= attemptBudget) break;
        }
        return attempts;
    }

    private boolean allMissingChunksLoaded(Job job, World world) {
        for (long key : missing(job)) {
            if (!world.isChunkLoaded((int) (key >> 32), (int) key)) return false;
        }
        return true;
    }

    @Override
    public void run() {
        if (!plugin.isEnabled()) { shutdown(); return; }
        ++tick;
        if (staging != null && tick - stagingSince >= STAGING_TIMEOUT_TICKS) {
            staging.stagingAllowedAt = tick + STAGING_COOLDOWN_TICKS;
            release(staging);
        }
        if (!rotation.isEmpty() && tick % 400L == 0 && rotation.size() > 20) {
            plugin.getLogger().info("Branch growth queue depth: " + rotation.size());
        }

        long start = System.nanoTime();
        int reloads = 0;
        int built = 0;
        int scans = 0;
        // Visit each queued job at most once per scheduler tick. Without this
        // snapshot, a waiting head job could be rotated through hundreds of
        // times in one tick after its chunk retry deadline had passed.
        int scanLimit = Math.min(SCANS_PER_TICK, rotation.size());
        while (scans < scanLimit && BranchGrowthDrainThroughputPolicy.shouldContinue(
                rotation.size(), scans, built, System.nanoTime() - start)) {
            scans++;
            Job job = rotation.pollFirst();
            if (job == null) break;
            if (!job.queued) continue;
            try {
                if (!stillPending(job)) { complete(job); continue; }
                if (tick < job.retryAt) {
                    rotation.addLast(job);
                    continue;
                }
                World w = world(job);
                if (w == null) {
                    job.retryAt = tick + RETRY_TICKS;
                    rotation.addLast(job);
                    continue;
                }
                job.world = w;
                Set<Long> waiting = missing(job);
                boolean batchReady = waiting.isEmpty();
                int used = 0;
                if (!waiting.isEmpty()) {
                    if (staging == null && tick >= job.stagingAllowedAt) {
                        staging = job;
                        stagingSince = tick;
                    }
                    if (staging == job && BranchGrowthDrainThroughputPolicy.remainingChunkLoadBudget(reloads) > 0
                            && tick >= job.retryAt) {
                        used = advanceStaging(job, BranchGrowthDrainThroughputPolicy.remainingChunkLoadBudget(reloads));
                        reloads += used;
                        // A failed load attempt is not retried every tick. Chunk-load
                        // notifications wake this job immediately if a player or
                        // another system makes one of its waiting chunks resident.
                        job.retryAt = tick + (used > 0 ? 1L : RETRY_TICKS);
                    }
                    // A build larger than the ticket window is processed as
                    // successive batches. Releasing the current 128 tickets
                    // after each batch lets the next set progress instead of
                    // pinning the build forever at the first 128 chunks.
                    boolean stagingBlockedByRetry = staging == job && job.retryAt > tick
                            && job.retained.size() < MAX_RETAINED_CHUNKS
                            && !waiting.isEmpty();
                    batchReady = BranchGrowthDrainBatchPolicy.shouldFlushBatch(
                            allMissingChunksLoaded(job, w),
                            job.owner.loadedWaitingChunkCount(job.acc), job.retained.size(),
                            stagingBlockedByRetry);
                    if (!batchReady) {
                        rotation.addLast(job);
                        continue;
                    }
                }
                if (built > 0 && System.nanoTime() - start >= SOFT_WORK_BUDGET_NS) {
                    rotation.addFirst(job);
                    break;
                }
                boolean progressed = job.owner.flushBuildFromDrain(job.build, job.acc);
                // Entries for chunks removed from the current batch are no longer
                // retry candidates. Keep per-job retry bookkeeping bounded by its
                // genuinely outstanding chunk set.
                job.lastReloadAttempt.keySet().retainAll(missing(job));
                if (progressed) built++;
                if (!stillPending(job)) complete(job);
                else {
                    if (progressed) {
                        release(job); // free the ticket window for the next batch/stage
                        job.retryAt = tick + 1L;
                    }
                    rotation.addLast(job);
                }
            } catch (Throwable e) {
                // Preserve the pending accumulator and retry. A transient
                // world/chunk failure must not silently discard a build.
                logError("Branch growth drain job failed; retaining pending build for retry", e);
                job.retryAt = tick + RETRY_TICKS;
                rotation.addLast(job);
            }
        }
        if (rotation.isEmpty()) shutdown();
    }

    private void shutdown() {
        if (ticker != null) { ticker.cancel(); ticker = null; }
        for (IdentityHashMap<Parcel, Job> map : jobs.values()) {
            for (Job job : map.values()) release(job);
        }
        jobs.clear();
        rotation.clear();
        staging = null;
        if (instance == this) instance = null;
    }

    static final class Job {
        final DecayProviderNormal owner;
        final Parcel build;
        final PendingBuild acc;
        final Set<Long> retained = new HashSet<>();
        final Map<Long, Long> lastReloadAttempt = new HashMap<>();
        World world;
        long retryAt;
        long stagingAllowedAt;
        boolean queued = true;

        Job(DecayProviderNormal owner, Parcel build, PendingBuild acc) {
            this.owner = owner;
            this.build = build;
            this.acc = acc;
        }
    }
}
