package ch.k42.metropolis.grid.urbanGrid.provider;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
 * Fair, time-budgeted main-thread drain for deferred branch-growth builds.
 *
 * Bukkit world/block access stays on the server thread. Throughput comes from
 * draining multiple independent builds per tick and staging multiple chunk
 * loads per tick, rather than serializing the entire queue behind one build.
 */
public final class BranchGrowthDrain implements Runnable {
    private static final int BUILDS_PER_TICK = 24;
    private static final int SCANS_PER_TICK = 256;
    private static final int CHUNK_LOADS_PER_TICK = 16;
    private static final int MAX_RETAINED_CHUNKS = 128;
    private static final long SOFT_WORK_BUDGET_NS = 12_000_000L;
    private static final long RETRY_TICKS = 20L;
    private static final long STAGING_TICKET_TTL_TICKS = 600L;
    private static BranchGrowthDrain instance;

    private final Plugin plugin;
    private BukkitTask ticker;
    private long tick;
    private long lastWarningTick;
    private final ArrayDeque<Job> rotation = new ArrayDeque<>();
    private final IdentityHashMap<Object, IdentityHashMap<Object, Job>> jobs = new IdentityHashMap<>();
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
            return;
        }
        Job job = new Job(owner, build, acc);
        map.put(build, job);
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

    private boolean retainChunk(Job job, World world, long key) {
        if (job.retained.contains(key)) return true;
        Map<Long, Integer> refs = ticketReferences.get(world);
        int count = refs == null ? 0 : refs.getOrDefault(key, 0);
        if (count == 0) {
            int cx = (int) (key >> 32), cz = (int) key;
            if (!world.addPluginChunkTicket(cx, cz, plugin)) return false;
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
        job.stagingSince = 0L;
    }

    private void complete(Job job) {
        release(job);
        IdentityHashMap<Object, Job> ownersJobs = jobs.get(job.owner);
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
     * Acquires tickets for every required support chunk. A shared per-tick
     * load budget is spread over all queued jobs, so one large build cannot
     * monopolize staging while every other ready build waits behind it.
     */
    private boolean advanceStaging(Job job, int[] loadsRemaining) throws ReflectiveOperationException {
        World w = world(job);
        if (w == null) return false;
        Set<Long> waiting = missing(job);
        if (waiting.isEmpty()) return true;
        if (job.stagingSince == 0L) job.stagingSince = tick;

        for (Long value : waiting) {
            int cx = (int) (value >> 32), cz = (int) (long) value;
            if (w.isChunkLoaded(cx, cz)) {
                if (!job.retained.contains(value)) {
                    if (job.retained.size() >= MAX_RETAINED_CHUNKS) {
                        warnTicketLimit();
                        return false;
                    }
                    retainChunk(job, w, value);
                }
                continue;
            }
            if (job.retained.size() >= MAX_RETAINED_CHUNKS) {
                warnTicketLimit();
                return false;
            }
            Long last = job.lastReloadAttempt.get(value);
            if (last != null && tick - last < RETRY_TICKS) continue;
            if (loadsRemaining[0] <= 0) return false;

            job.lastReloadAttempt.put(value, tick);
            loadsRemaining[0]--;
            boolean loaded = w.loadChunk(cx, cz, false);
            if (loaded && w.isChunkLoaded(cx, cz) && retainChunk(job, w, value)) {
                job.lastReloadAttempt.remove(value);
            }
            if (System.nanoTime() >= job.tickDeadlineNanos) return false;
        }

        for (Long value : waiting) {
            int cx = (int) (value >> 32), cz = (int) (long) value;
            if (!w.isChunkLoaded(cx, cz) || !job.retained.contains(value)) return false;
        }
        return true;
    }

    private void warnTicketLimit() {
        if (tick - lastWarningTick >= 200L) {
            plugin.getLogger().warning("Branch growth needs more than 128 simultaneous support chunks for one build; waiting for natural chunk loads.");
            lastWarningTick = tick;
        }
    }

    @Override
    public void run() {
        if (!plugin.isEnabled()) { shutdown(); return; }
        ++tick;
        if (!rotation.isEmpty() && tick % 400L == 0 && rotation.size() > 20) {
            plugin.getLogger().info("Branch growth queue depth: " + rotation.size());
        }

        long start = System.nanoTime();
        int[] loadsRemaining = {CHUNK_LOADS_PER_TICK};
        int built = 0;
        int scans = Math.min(SCANS_PER_TICK, rotation.size());
        for (int i = 0; i < scans && built < BUILDS_PER_TICK; ++i) {
            if (built > 0 && System.nanoTime() - start >= SOFT_WORK_BUDGET_NS) break;
            Job job = rotation.pollFirst();
            if (job == null) break;
            if (!job.queued) continue;
            try {
                reflect(job);
                if (!stillPending(job)) { complete(job); continue; }

                World w = world(job);
                if (w == null) {
                    job.retryAt = tick + RETRY_TICKS;
                    rotation.addLast(job);
                    continue;
                }

                Set<Long> waiting = missing(job);
                if (!waiting.isEmpty()) {
                    if (tick >= job.retryAt) {
                        job.tickDeadlineNanos = start + SOFT_WORK_BUDGET_NS;
                        advanceStaging(job, loadsRemaining);
                        job.retryAt = tick + 1L;
                    }
                    boolean allLoaded = true;
                    for (long key : waiting) {
                        if (!w.isChunkLoaded((int) (key >> 32), (int) key) || !job.retained.contains(key)) {
                            allLoaded = false;
                            break;
                        }
                    }
                    if (!allLoaded) {
                        if (job.stagingSince != 0L && tick - job.stagingSince >= STAGING_TICKET_TTL_TICKS) {
                            release(job);
                            job.retryAt = tick + RETRY_TICKS;
                        }
                        rotation.addLast(job);
                        continue;
                    }
                }

                flushMethod.invoke(job.owner, job.build, job.acc);
                built++;
                if (!stillPending(job)) complete(job);
                else rotation.addLast(job);
            } catch (InvocationTargetException e) {
                // Keep the accumulator queued: a failed flush must not silently
                // discard the only copy of its candidate pool.
                logError("Branch growth build flush failed; retaining it for retry", e.getCause());
                job.retryAt = tick + RETRY_TICKS;
                rotation.addLast(job);
            } catch (Throwable e) {
                logError("Branch growth scheduler failed; retaining build for retry", e);
                job.retryAt = tick + RETRY_TICKS;
                rotation.addLast(job);
            }
        }
        if (rotation.isEmpty()) shutdown();
    }

    private void shutdown() {
        if (ticker != null) { ticker.cancel(); ticker = null; }
        for (IdentityHashMap<Object, Job> map : jobs.values()) {
            for (Job job : map.values()) release(job);
        }
        jobs.clear();
        rotation.clear();
        if (instance == this) instance = null;
    }

    static final class Job {
        final Object owner;
        final Object build;
        final Object acc;
        final Set<Long> retained = new HashSet<>();
        final Map<Long, Long> lastReloadAttempt = new HashMap<>();
        World world;
        long retryAt;
        long stagingSince;
        long tickDeadlineNanos;
        boolean queued = true;

        Job(Object owner, Object build, Object acc) {
            this.owner = owner;
            this.build = build;
            this.acc = acc;
        }
    }
}
