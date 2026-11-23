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
 * Source-first R30ea restoration, guided by the original compiled class.
 *
 * This deliberately PRESERVES R30ea's in-memory scheduling and failure handling
 * for binary-behavior comparisons. Do not install as a durable queue fix.
 * Job references are strong; errors in a flush can drop pending growth.
 */
public final class BranchGrowthDrain implements Runnable {
    private static final int BUILDS_PER_TICK = 1;
    private static final int SCANS_PER_TICK = 12;
    private static final int GENERATED_RELOADS_PER_TICK = 1;
    private static final int MAX_RETAINED_CHUNKS = 128;
    private static final long SOFT_WORK_BUDGET_NS = 5_000_000L;
    private static final long RETRY_TICKS = 100L;
    private static BranchGrowthDrain instance;

    private final Plugin plugin;
    private BukkitTask ticker;
    private long tick;
    private final ArrayDeque<Job> rotation = new ArrayDeque<>();
    private final IdentityHashMap<Object, IdentityHashMap<Object, Job>> jobs = new IdentityHashMap<>();
    private Job staging;
    private long lastWarningTick;
    private long stagingSince;
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
        if (current == null) {
            Plugin p = Bukkit.getPluginManager().getPlugin("Metropolis");
            if (p == null || !p.isEnabled()) {
                throw new IllegalStateException("Metropolis branching drain needs its active plugin");
            }
            current = new BranchGrowthDrain(p);
            instance = current;
        }
        IdentityHashMap<Object, Job> map = current.jobs.computeIfAbsent(owner, ignored -> new IdentityHashMap<>());
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

    private void reflect(Job job) throws ReflectiveOperationException {
        if (flushMethod != null) return;
        Class<?> ownerClass = job.owner.getClass();
        Class<?> accClass = job.acc.getClass();
        Class<?> buildClass = job.build.getClass();
        Class<?> parcelClass = Class.forName("ch.k42.metropolis.grid.urbanGrid.parcel.Parcel", false, ownerClass.getClassLoader());
        flushMethod = ownerClass.getDeclaredMethod("flushBuildNow", parcelClass, accClass);
        flushMethod.setAccessible(true);
        pendingField = ownerClass.getDeclaredField("pendingBuilds");
        pendingField.setAccessible(true);
        waitingField = accClass.getDeclaredField("waitingChunks");
        waitingField.setAccessible(true);
        worldField = accClass.getDeclaredField("world");
        worldField.setAccessible(true);
    }

    private boolean stillPending(Job job) throws ReflectiveOperationException {
        @SuppressWarnings("unchecked") Map<Object, Object> pending = (Map<Object, Object>) pendingField.get(job.owner);
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

    /** Returns true if all pending chunks were already present at the start of this visit. */
    private boolean advanceStaging(Job job) throws ReflectiveOperationException {
        if (staging != job) return false;
        World w = world(job);
        if (w == null) return false;
        Set<Long> waiting = missing(job);
        if (waiting.isEmpty()) return true;
        for (Long value : waiting) {
            long packed = value;
            int cx = (int) (packed >> 32), cz = (int) packed;
            if (w.isChunkLoaded(cx, cz)) {
                if (!job.retained.contains(value) && job.retained.size() < MAX_RETAINED_CHUNKS) {
                    if (w.addPluginChunkTicket(cx, cz, plugin)) job.retained.add(value);
                }
                continue;
            }
            if (job.retained.contains(value)) continue;
            if (job.retained.size() >= MAX_RETAINED_CHUNKS) {
                if (tick - lastWarningTick >= 200L) {
                    plugin.getLogger().warning("Branch growth requires more than 128 simultaneous loaded chunks; waiting for natural chunk loads to complete this building.");
                    // The original binary narrows this value to int, even though the field is long.
                    lastWarningTick = (long) (int) tick;
                }
                return false;
            }
            Long last = job.lastReloadAttempt.get(value);
            if (last != null && tick - last < RETRY_TICKS) continue;
            job.lastReloadAttempt.put(value, tick);
            boolean loaded = w.loadChunk(cx, cz, false);
            if (loaded && w.isChunkLoaded(cx, cz) && w.addPluginChunkTicket(cx, cz, plugin)) {
                job.lastReloadAttempt.remove(value);
                job.retained.add(value);
                return false;
            }
            return false;
        }
        return true;
    }

    @Override
    public void run() {
        if (!plugin.isEnabled()) { shutdown(); return; }
        ++tick;
        if (staging != null && tick - stagingSince >= 120L) {
            staging.stagingAllowedAt = tick + 20L;
            release(staging);
        }
        if (!rotation.isEmpty() && tick % 400L == 0 && rotation.size() > 20) {
            plugin.getLogger().info("Branch growth queue depth: " + rotation.size());
        }
        long start = System.nanoTime();
        int reloads = 0, built = 0;
        int scans = Math.min(SCANS_PER_TICK, rotation.size());
        for (int i = 0; i < scans && built < BUILDS_PER_TICK; ++i) {
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
                if (!missing(job).isEmpty()) {
                    if (staging == null && tick >= job.stagingAllowedAt) {
                        staging = job;
                        stagingSince = tick;
                    }
                    if (staging == job && reloads < GENERATED_RELOADS_PER_TICK && tick >= job.retryAt) {
                        advanceStaging(job);
                        reloads++;
                        job.retryAt = tick + 1L;
                    }
                    boolean allLoaded = true;
                    for (long key : missing(job)) {
                        if (!w.isChunkLoaded((int) (key >> 32), (int) key)) { allLoaded = false; break; }
                    }
                    if (!allLoaded) { rotation.addLast(job); continue; }
                }
                if (System.nanoTime() - start >= SOFT_WORK_BUDGET_NS && built > 0) {
                    rotation.addFirst(job);
                    break;
                }
                flushMethod.invoke(job.owner, job.build, job.acc);
                built++;
                if (!stillPending(job)) complete(job);
                else rotation.addLast(job);
            } catch (InvocationTargetException e) {
                // Preserve original loss path for red/green tests of replacement queue.
                logError("Branch growth build failed", e.getCause());
                complete(job);
            } catch (Throwable e) {
                logError("Branch growth scheduler failed", e);
                job.retryAt = tick + RETRY_TICKS;
                rotation.addLast(job);
            }
            if (System.nanoTime() - start >= SOFT_WORK_BUDGET_NS) break;
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
        staging = null;
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
        long stagingAllowedAt;
        boolean queued = true;

        Job(Object owner, Object build, Object acc) {
            this.owner = owner;
            this.build = build;
            this.acc = acc;
        }
    }
}
