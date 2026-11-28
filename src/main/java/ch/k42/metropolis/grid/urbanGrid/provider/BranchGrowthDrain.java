package ch.k42.metropolis.grid.urbanGrid.provider;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Fair, time-budgeted main-thread drain for deferred branch-growth builds.
 *
 * Bukkit world/block access stays on the server thread. Throughput comes from
 * draining multiple independent builds per tick and staging multiple chunk
 * loads per tick, rather than serializing the entire queue behind one build.
 */
public final class BranchGrowthDrain implements Runnable {
    private static final int BUILDS_PER_TICK = 24;
    private static final int SCANS_PER_TICK = 256;
    private static final int ASYNC_LOAD_REQUESTS_PER_TICK = 16;
    private static final int MAX_PENDING_LOADS_PER_JOB = 16;
    private static final int MAX_PENDING_LOADS_GLOBAL = 256;
    private static final int MAX_RETAINED_CHUNKS = 128;
    private static final int MAX_RETAINED_CHUNKS_GLOBAL = 4096;
    private static final long SOFT_WORK_BUDGET_NS = 12_000_000L;
    private static final long RETRY_TICKS = 20L;
    private static final long STAGING_TICKET_TTL_TICKS = 600L;
    private static BranchGrowthDrain instance;

    private final Plugin plugin;
    private BukkitTask ticker;
    private long tick;
    private long lastWarningTick;
    /** Ready work only. Jobs waiting on chunks are parked outside this deque. */
    private final ArrayDeque<Job> rotation = new ArrayDeque<>();
    private final IdentityHashMap<Object, IdentityHashMap<Object, Job>> jobs = new IdentityHashMap<>();
    private final PriorityQueue<Expiry> stagingExpiries =
            new PriorityQueue<>(Comparator.comparingLong(Expiry::due));
    private final AtomicInteger pendingLoadsInFlight = new AtomicInteger();
    /** Async callbacks publish only job signals; all job/Bukkit state changes happen in run(). */
    private final ConcurrentLinkedQueue<Job> completedLoadSignals = new ConcurrentLinkedQueue<>();
    private int pendingJobs;
    private int retainedChunkTickets;
    // Bukkit chunk tickets are plugin-scoped, not job-scoped. Reference-count
    // shared chunks so completing one build cannot release another build's pin.
    private final IdentityHashMap<World, Map<Long, Integer>> ticketReferences = new IdentityHashMap<>();
    private Method flushMethod;
    private Field pendingField;
    private Field waitingField;
    private Field worldField;

    private BranchGrowthDrain(Plugin plugin) { this.plugin = plugin; }

    public static void shutdownNow() {
        BranchGrowthDrain current = instance;
        if (current != null) current.shutdown();
    }

    public static void enqueue(Object owner, Object build, Object acc) {
        if (owner == null || build == null || acc == null) return;
        BranchGrowthDrain current = instance;
        if (current != null && !current.plugin.isEnabled()) {
            current.shutdown();
            return;
        }
        if (current == null) {
            Plugin p = Bukkit.getPluginManager().getPlugin("Metropolis");
            if (p == null || !p.isEnabled()) return;
            current = new BranchGrowthDrain(p);
            instance = current;
        }
        IdentityHashMap<Object, Job> map = current.jobs.computeIfAbsent(owner, ignored -> new IdentityHashMap<>());
        Job existing = map.get(build);
        if (existing != null) {
            existing.retryAt = Math.min(existing.retryAt, current.tick);
            // A dependency chunk just loaded. Wake parked work at the front
            // of the independent branch queue instead of waiting behind an
            // arbitrary scan of unrelated builds.
            if (existing.parked) {
                current.queueReady(existing, true);
            } else if (existing.queued) {
                current.rotation.remove(existing);
                current.rotation.addFirst(existing);
            }
            return;
        }
        Job job = new Job(owner, build, acc);
        map.put(build, job);
        current.pendingJobs++;
        current.rotation.addLast(job);
        if (current.ticker == null) {
            current.ticker = Bukkit.getScheduler().runTaskTimer(current.plugin, current, 1L, 1L);
        }
    }

    private void reflect(Job job) throws ReflectiveOperationException {
        if (flushMethod != null) return;
        ClassLoader loader = job.owner.getClass().getClassLoader();
        Class<?> parcelClass = Class.forName("ch.k42.metropolis.grid.urbanGrid.parcel.Parcel", false, loader);
        Class<?> accClass = job.acc.getClass();
        flushMethod = job.owner.getClass().getDeclaredMethod("flushBuildNow", parcelClass, accClass);
        flushMethod.setAccessible(true);
        pendingField = job.owner.getClass().getDeclaredField("pendingBuilds");
        pendingField.setAccessible(true);
        waitingField = accClass.getDeclaredField("waitingChunks");
        waitingField.setAccessible(true);
        worldField = accClass.getDeclaredField("world");
        worldField.setAccessible(true);
    }

    private boolean stillPending(Job job) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked")
        Map<Object, Object> pending = (Map<Object, Object>) pendingField.get(job.owner);
        return pending.get(job.build) == job.acc;
    }

    @SuppressWarnings("unchecked")
    private Set<Long> missing(Job job) throws ReflectiveOperationException {
        return (Set<Long>) waitingField.get(job.acc);
    }

    private World world(Job job) throws ReflectiveOperationException {
        if (job.world == null) job.world = (World) worldField.get(job.acc);
        return job.world;
    }

    private void queueReady(Job job, boolean front) {
        if (job.queued) {
            if (front) {
                rotation.remove(job);
                rotation.addFirst(job);
            }
            job.parked = false;
            return;
        }
        if (job.parked) {
            // Invalidate the old parked lease immediately. If this job parks
            // again before the stale expiry is drained, it gets a fresh TTL.
            job.stagingSince = 0L;
            job.stagingExpiryTick = 0L;
        }
        job.parked = false;
        job.queued = true;
        if (front) rotation.addFirst(job);
        else rotation.addLast(job);
    }

    private void park(Job job) {
        job.queued = false;
        job.parked = true;
        // Jobs can also arrive here after a staged-load retry cooldown. Always
        // ensure a parked job has one expiry wakeup so it cannot retain tickets
        // forever if its last dependency never produces a ChunkLoadEvent.
        scheduleStagingExpiry(job);
    }

    private void scheduleStagingExpiry(Job job) {
        if (job.stagingSince != 0L) return;
        job.stagingSince = tick;
        job.stagingExpiryTick = tick + STAGING_TICKET_TTL_TICKS;
        stagingExpiries.add(new Expiry(new java.lang.ref.WeakReference<>(job), job.stagingExpiryTick));
    }

    private boolean allWaitingChunksRetained(Job job, World world, Set<Long> waiting) {
        for (long key : waiting) {
            int cx = (int) (key >> 32), cz = (int) key;
            if (!world.isChunkLoaded(cx, cz) || !job.retained.contains(key)) return false;
        }
        return true;
    }

    private void expireParkedJobs() {
        int processed = 0;
        while (processed < 32 && !stagingExpiries.isEmpty()
                && stagingExpiries.peek().due() <= tick) {
            Expiry expiry = stagingExpiries.poll();
            Job job = expiry.job().get();
            if (job == null || job.stagingExpiryTick != expiry.due()) continue;
            if (!job.parked) {
                // The dependency event woke this job before its old lease
                // expired. Clear the stale lease; a later re-park will get a
                // fresh expiry entry.
                job.stagingSince = 0L;
                job.stagingExpiryTick = 0L;
                continue;
            }
            job.parked = false;
            release(job);
            job.retryAt = tick;
            queueReady(job, false);
            processed++;
        }
    }

    private boolean retainChunk(Job job, World world, long key) {
        if (job.retained.contains(key)) return true;
        Map<Long, Integer> refs = ticketReferences.get(world);
        int count = refs == null ? 0 : refs.getOrDefault(key, 0);
        if (count == 0) {
            if (retainedChunkTickets >= MAX_RETAINED_CHUNKS_GLOBAL) return false;
            int cx = (int) (key >> 32), cz = (int) key;
            if (!world.addPluginChunkTicket(cx, cz, plugin)) return false;
            retainedChunkTickets++;
        }
        if (refs == null) {
            refs = new HashMap<>();
            ticketReferences.put(world, refs);
        }
        refs.put(key, count + 1);
        job.retained.add(key);
        return true;
    }

    private void release(Job job) {
        if (job.world != null) {
            Map<Long, Integer> refs = ticketReferences.get(job.world);
            for (Long value : job.retained) {
                if (refs == null) continue;
                int count = refs.getOrDefault(value, 0);
                if (count <= 1) {
                    refs.remove(value);
                    retainedChunkTickets = Math.max(0, retainedChunkTickets - 1);
                    int cx = (int) (value >> 32), cz = (int) (long) value;
                    try {
                        job.world.removePluginChunkTicket(cx, cz, plugin);
                    } catch (RuntimeException e) {
                        plugin.getLogger().warning("Branch growth chunk ticket release failed: " + e);
                    }
                } else {
                    refs.put(value, count - 1);
                }
            }
            if (refs != null && refs.isEmpty()) ticketReferences.remove(job.world);
        }
        job.retained.clear();
        for (CompletableFuture<Chunk> future : job.pendingLoads.values()) {
            if (future != null && !future.isDone()) future.cancel(false);
        }
        job.pendingLoads.clear();
        job.lastReloadAttempt.clear();
        job.stagingSince = 0L;
        job.stagingExpiryTick = 0L;
    }

    private void complete(Job job) {
        release(job);
        IdentityHashMap<Object, Job> ownersJobs = jobs.get(job.owner);
        if (ownersJobs != null) {
            ownersJobs.remove(job.build);
            if (ownersJobs.isEmpty()) jobs.remove(job.owner);
        }
        job.queued = false;
        job.parked = false;
        pendingJobs = Math.max(0, pendingJobs - 1);
    }

    private void logError(String detail, Throwable error) {
        plugin.getLogger().severe(detail + ": " + error);
        error.printStackTrace();
    }

    /**
     * Requests disk-backed support chunks asynchronously, but never generates
     * missing chunks from this drain. Paper completes the future later on the
     * server thread. A false gen flag means an absent chunk resolves to null
     * and is left to normal chunk scheduling / Chunky pre-generation.
     *
     * Plugin tickets are added only after isChunkLoaded confirms completion.
     * Paper's addPluginChunkTicket ensures unloaded chunks are loaded, so
     * calling it before this check would reintroduce a synchronous load path.
     */
    private boolean advanceStaging(Job job, int[] requestsRemaining) throws ReflectiveOperationException {
        World w = world(job);
        if (w == null) return false;
        Set<Long> waiting = missing(job);
        if (waiting.isEmpty()) return true;
        scheduleStagingExpiry(job);

        for (Long value : waiting) {
            int cx = (int) (value >> 32), cz = (int) (long) value;
            if (w.isChunkLoaded(cx, cz)) {
                job.pendingLoads.remove(value);
                job.lastReloadAttempt.remove(value);
                if (!job.retained.contains(value)) {
                    if (job.retained.size() >= MAX_RETAINED_CHUNKS) {
                        warnTicketLimit();
                        return false;
                    }
                    retainChunk(job, w, value);
                }
                continue;
            }

            CompletableFuture<Chunk> pending = job.pendingLoads.get(value);
            if (pending != null) {
                if (!pending.isDone()) continue;
                job.pendingLoads.remove(value);
                try {
                    Chunk loaded = pending.isCancelled() || pending.isCompletedExceptionally()
                            ? null : pending.getNow(null);
                    if (loaded != null && w.isChunkLoaded(cx, cz)) {
                        if (job.retained.size() >= MAX_RETAINED_CHUNKS) {
                            warnTicketLimit();
                            return false;
                        }
                        if (retainChunk(job, w, value)) {
                            job.lastReloadAttempt.remove(value);
                            continue;
                        }
                    }
                } catch (RuntimeException failure) {
                    plugin.getLogger().fine("Async branch support load failed for chunk "
                            + cx + "," + cz + ": " + failure);
                }
                job.lastReloadAttempt.put(value, tick);
                continue;
            }

            if (job.retained.size() >= MAX_RETAINED_CHUNKS) {
                warnTicketLimit();
                return false;
            }
            if (job.pendingLoads.size() >= MAX_PENDING_LOADS_PER_JOB) continue;
            Long last = job.lastReloadAttempt.get(value);
            if (last != null && tick - last < RETRY_TICKS) continue;
            if (requestsRemaining[0] <= 0
                    || pendingLoadsInFlight.get() >= MAX_PENDING_LOADS_GLOBAL) continue;

            requestsRemaining[0]--;
            job.lastReloadAttempt.put(value, tick);
            pendingLoadsInFlight.incrementAndGet();
            try {
                CompletableFuture<Chunk> future = w.getChunkAtAsync(cx, cz, false, false);
                if (future == null) {
                    pendingLoadsInFlight.decrementAndGet();
                    job.lastReloadAttempt.put(value, tick);
                } else {
                    job.pendingLoads.put(value, future);
                    // This callback touches no Bukkit API or job maps. It only
                    // decrements the global counter and posts a signal; run()
                    // performs all state transitions on the server thread.
                    future.whenComplete((loaded, failure) -> {
                        pendingLoadsInFlight.decrementAndGet();
                        completedLoadSignals.add(job);
                    });
                }
            } catch (RuntimeException failure) {
                pendingLoadsInFlight.decrementAndGet();
                job.pendingLoads.remove(value);
                plugin.getLogger().fine("Could not request existing branch support chunk "
                        + cx + "," + cz + ": " + failure);
            }
        }

        for (Long value : waiting) {
            int cx = (int) (value >> 32), cz = (int) (long) value;
            if (!w.isChunkLoaded(cx, cz) || !job.retained.contains(value)) return false;
        }
        return true;
    }

    private void warnTicketLimit() {
        if (tick - lastWarningTick >= 200L) {
            plugin.getLogger().warning("Branch growth reached its bounded support-chunk ticket limit; waiting for natural chunk loads or the next staging lease.");
            lastWarningTick = tick;
        }
    }

    private boolean isCurrentJob(Job job) {
        IdentityHashMap<Object, Job> ownerJobs = this.jobs.get(job.owner);
        return ownerJobs != null && ownerJobs.get(job.build) == job;
    }

    private void wakeCompletedLoads() {
        int processed = 0;
        while (processed < 512) {
            Job job = this.completedLoadSignals.poll();
            if (job == null) break;
            processed++;
            if (!this.isCurrentJob(job)) continue;
            job.retryAt = Math.min(job.retryAt, this.tick);
            if (job.parked) {
                this.queueReady(job, true);
            } else if (job.queued) {
                this.rotation.remove(job);
                this.rotation.addFirst(job);
            }
        }
    }

    @Override
    public void run() {
        if (!plugin.isEnabled()) { shutdown(); return; }
        ++tick;
        expireParkedJobs();
        wakeCompletedLoads();
        if (!rotation.isEmpty() && tick % 400L == 0 && rotation.size() > 20) {
            plugin.getLogger().info("Branch growth ready queue depth: " + rotation.size()
                    + ", retained jobs=" + pendingJobs);
        }

        long start = System.nanoTime();
        int[] requestsRemaining = {ASYNC_LOAD_REQUESTS_PER_TICK};
        int built = 0;
        int scans = Math.min(SCANS_PER_TICK, rotation.size());
        for (int i = 0; i < scans && built < BUILDS_PER_TICK; ++i) {
            if (built > 0 && System.nanoTime() - start >= SOFT_WORK_BUDGET_NS) break;
            Job job = rotation.pollFirst();
            if (job == null) break;
            if (!job.queued) continue;
            job.queued = false;
            try {
                reflect(job);
                if (!stillPending(job)) { complete(job); continue; }
                // A failed flush or load retry must not spin every tick when
                // no dependency event has arrived to justify an early retry.
                if (tick < job.retryAt) {
                    queueReady(job, false);
                    continue;
                }

                World w = world(job);
                if (w == null) {
                    job.retryAt = tick + RETRY_TICKS;
                    queueReady(job, false);
                    continue;
                }

                Set<Long> waiting = missing(job);
                if (!waiting.isEmpty()) {
                    if (tick >= job.retryAt) {
                        advanceStaging(job, requestsRemaining);
                        job.retryAt = tick + RETRY_TICKS;
                    }
                    if (!allWaitingChunksRetained(job, w, waiting)) {
                        // Park, do not rescan this job every tick. A matching
                        // ChunkLoadEvent wakes it directly into the ready deque.
                        park(job);
                        continue;
                    }
                }

                flushMethod.invoke(job.owner, job.build, job.acc);
                built++;
                if (!stillPending(job)) {
                    complete(job);
                    continue;
                }

                // The first flush may discover unloaded support chunks and
                // populate the provider's reverse dependency index. Stage
                // disk-backed chunks once, then park until one of those exact
                // dependencies loads. No periodic scans of the waiting set.
                Set<Long> afterFlush = missing(job);
                if (!afterFlush.isEmpty()) {
                    if (tick >= job.retryAt) {
                        advanceStaging(job, requestsRemaining);
                        job.retryAt = tick + RETRY_TICKS;
                    }
                    if (allWaitingChunksRetained(job, w, afterFlush)) queueReady(job, true);
                    else park(job);
                } else {
                    job.retryAt = tick + RETRY_TICKS;
                    queueReady(job, false);
                }
            } catch (InvocationTargetException e) {
                // Keep the accumulator queued: a failed flush must not silently
                // discard the only copy of its candidate pool.
                logError("Branch growth build flush failed; retaining it for retry", e.getCause());
                job.retryAt = tick + RETRY_TICKS;
                queueReady(job, false);
            } catch (Throwable e) {
                logError("Branch growth scheduler failed; retaining build for retry", e);
                job.retryAt = tick + RETRY_TICKS;
                queueReady(job, false);
            }
        }
        if (pendingJobs == 0) shutdown();
    }

    private void shutdown() {
        if (ticker != null) { ticker.cancel(); ticker = null; }
        completedLoadSignals.clear();
        for (IdentityHashMap<Object, Job> map : jobs.values()) {
            for (Job job : map.values()) release(job);
        }
        jobs.clear();
        rotation.clear();
        stagingExpiries.clear();
        pendingJobs = 0;
        retainedChunkTickets = 0;
        if (instance == this) instance = null;
    }

    private record Expiry(java.lang.ref.WeakReference<Job> job, long due) {}

    static final class Job {
        final Object owner;
        final Object build;
        final Object acc;
        final Set<Long> retained = new HashSet<>();
        final Map<Long, Long> lastReloadAttempt = new HashMap<>();
        final Map<Long, CompletableFuture<Chunk>> pendingLoads = new HashMap<>();
        World world;
        long retryAt;
        long stagingSince;
        long stagingExpiryTick;
        boolean queued = true;
        boolean parked;

        Job(Object owner, Object build, Object acc) {
            this.owner = owner;
            this.build = build;
            this.acc = acc;
        }
    }
}
