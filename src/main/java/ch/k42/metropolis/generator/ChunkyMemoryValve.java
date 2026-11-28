package ch.k42.metropolis.generator;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Cooperative, main-thread back-pressure for the Chunky API.
 * Never invokes Bukkit or Chunky on a background thread, never forces GC,
 * and never mutates world planning or chunk content.
 *
 * R30ea expands independent planning/clipboard/metadata retention diagnostics.
 * The simple 57% recovery threshold in R30dy could strand a healthy worker
 * when persistent live heap exceeded 57%. This version uses 68% normal
 * recovery and offers bounded, guarded work probes when a higher retained
 * heap baseline is stable, while refusing probes with little free headroom.
 */
public final class ChunkyMemoryValve {
    private static final double SOFT_PAUSE = ChunkyBackpressurePolicy.SOFT_PAUSE;
    private static final double HARD_PAUSE = ChunkyBackpressurePolicy.HARD_PAUSE;
    private static final int QUEUE_PAUSE = ChunkyBackpressurePolicy.QUEUE_PAUSE;
    private static final int BRANCH_GROWTH_PAUSE = ChunkyBackpressurePolicy.BRANCH_GROWTH_PAUSE;
    private static final int QUEUE_RESUME = ChunkyBackpressurePolicy.QUEUE_RESUME;
    private static final int BRANCH_GROWTH_RESUME = ChunkyBackpressurePolicy.BRANCH_GROWTH_RESUME;
    private static final long STATUS_NS = 60_000_000_000L;
    private static final long RESUME_CONFIRM_NS = ChunkyBackpressurePolicy.RESUME_CONFIRM_NS;
    private static final long MIN_FREE_RESUME_BYTES = ChunkyBackpressurePolicy.MIN_FREE_RESUME_BYTES;
    private static final int MIN_STABLE_SAMPLES = ChunkyBackpressurePolicy.MIN_QUEUE_STABLE_SAMPLES;
    private static final long RAPID_REPAUSE_COOLDOWN_NS = ChunkyBackpressurePolicy.RAPID_REPAUSE_COOLDOWN_NS;

    /** Population backlog only; branch growth has an independent scheduler. */
    private static final Map<String, Integer> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, Integer> DEFERRED = new ConcurrentHashMap<>();
    /** All retained branch builds, including those waiting for future generation. */
    private static final Map<String, Integer> GROWTH = new ConcurrentHashMap<>();
    /** Branch builds ready to flush now (not waiting for parcels or unloaded support chunks). */
    private static final Map<String, Integer> GROWTH_RUNNABLE = new ConcurrentHashMap<>();
    /** Exact compact candidate count and backing-array bytes retained by pending branch builds. */
    private static final Map<String, Long> GROWTH_SUPPORT_HITS = new ConcurrentHashMap<>();
    private static final Map<String, Long> GROWTH_SUPPORT_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, State> STATES = new HashMap<>(); // main thread only
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean missingServiceWarned;
    private static volatile long lastIntegrationErrorNs;
    private static volatile long lastDiagnosticNs;
    private static volatile LongSupplier CLOCK = System::nanoTime; // testable monotonic clock

    private static final class State {
        boolean ourPause;
        long pausedAt;
        /** Low queue-depth dwell; sample count, not a timestamp sentinel, marks tracking. */
        long stableSince;
        long lastStatusNs;
        long resumeConfirmUntil;
        boolean resumeUnconfirmed;
        int stableSamples;
        boolean hasResumed;
        long lastResumeAt;
        boolean recoveryCooldownActive;
        long recoveryNotBefore;
        int pauseCount;
        int unconfirmedResumes;
    }

    private ChunkyMemoryValve() {}

    public static void startFromPluginEnable() { startMonitorIfNeeded(); }

    /** Updates durable deferred-populate jobs for one world. */
    public static void pending(World world, int count) {
        updateQueue(world, DEFERRED, count);
    }

    /** Updates all retained branch-growth builds for one world. */
    public static void growthPending(World world, int count) {
        updateQueue(world, GROWTH, count);
    }

    /** Updates the subset of branch-growth builds that can flush with loaded support chunks. */
    public static void growthRunnablePending(World world, int count) {
        updateQueue(world, GROWTH_RUNNABLE, count);
    }

    /** Updates exact retained primitive candidate count and backing-array size. */
    public static void growthStorage(World world, long candidateCount, long backingBytes) {
        if (world == null) return;
        String name = world.getName();
        GROWTH_SUPPORT_HITS.put(name, Math.max(0L, candidateCount));
        GROWTH_SUPPORT_BYTES.put(name, Math.max(0L, backingBytes));
    }

    private static void updateQueue(World world, Map<String, Integer> source, int count) {
        if (world == null) return;
        String name = world.getName();
        source.put(name, Math.max(0, count));
        PENDING.put(name, ChunkyBackpressurePolicy.populationQueueSize(
                DEFERRED.getOrDefault(name, 0)));
        // Population may be reentrant through WorldEdit and chunk loads.
        // Never schedule Bukkit tasks from that callback; onEnable owns startup.
    }

    private static void startMonitorIfNeeded() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("Metropolis");
            if (plugin == null || !plugin.isEnabled()) {
                STARTED.set(false);
                return;
            }
            Bukkit.getScheduler().runTaskTimer(plugin, ChunkyMemoryValve::tick, 20L, 20L);
            Bukkit.getLogger().info("[Metropolis] Chunky back-pressure enabled: pause at 74% heap or 1024 deferred population jobs; resume only at <=68% heap and deferred <=64 for 10s/10 samples, with >=512 MiB free; branch growth uses its own queue and drain.");
        } catch (RuntimeException | LinkageError ex) {
            STARTED.set(false);
            Bukkit.getLogger().severe("[Metropolis] Chunky heap guard NOT SCHEDULED: " + ex);
        }
    }

    public static void tick() {
        Runtime rt = Runtime.getRuntime();
        check(rt.totalMemory() - rt.freeMemory(), rt.maxMemory());
    }

    // Invoked by the Bukkit scheduler, and by API-shape-correct integration tests.
    static synchronized void check(long used, long max) {
        if (max <= 0 || Bukkit.getServer().isStopping()) return;
        long now = CLOCK.getAsLong();
        try {
            Plugin chunky = Bukkit.getPluginManager().getPlugin("Chunky");
            if (chunky == null || !chunky.isEnabled()) return;
            Class<?> apiClass = Class.forName("org.popcraft.chunky.api.ChunkyAPI", false, ((Object) chunky).getClass().getClassLoader());
            Object api = Bukkit.getServicesManager().load(apiClass);
            if (api == null) {
                if (!missingServiceWarned) {
                    missingServiceWarned = true;
                    Bukkit.getLogger().severe("[Metropolis] No ChunkyAPI service: heap back-pressure cannot operate.");
                }
                return;
            }
            Method isRunning = apiClass.getMethod("isRunning", String.class);
            Method pause = apiClass.getMethod("pauseTask", String.class);
            Method resume = apiClass.getMethod("continueTask", String.class);
            Logger log = Bukkit.getLogger();
            double usage = Math.max(0, used) / (double) max;
            long remaining = Math.max(0, max - used);
            long postGcBytes = postGcHeapBytes();
            double retainedUsage = postGcBytes >= 0L ? postGcBytes / (double) max : usage;

            // Retained heap remains observable, but recovery is governed by
            // runnable backlog depth rather than a retained-heap percentage.
            if (lastDiagnosticNs == 0L || now - lastDiagnosticNs >= 300_000_000_000L) {
                lastDiagnosticNs = now;
                long deferredTotal = sum(DEFERRED);
                long growthTotal = sum(GROWTH);
                long growthRunnableTotal = sum(GROWTH_RUNNABLE);
                log.info("[Metropolis] GENERATION HEAP AUDIT: used=" + mib(used)
                        + " MiB, max=" + mib(max) + " MiB (" + pct(usage)
                        + "%), free=" + mib(remaining) + " MiB, deferred=" + deferredTotal
                        + ", branchGrowth=" + growthTotal + ", branchGrowthReady=" + growthRunnableTotal
                        + ", branchGrowthWaiting=" + Math.max(0L, growthTotal - growthRunnableTotal)
                        + ", branchSupportHits=" + sumLong(GROWTH_SUPPORT_HITS)
                        + ", branchSupportBuffer=" + mib(sumLong(GROWTH_SUPPORT_BYTES)) + " MiB"
                        + ", populationQueue=" + sum(PENDING)
                        + ", postGcRetained=" + pct(retainedUsage) + "% (diagnostic only), "
                        + gcStats() + ", " + retentionStats());
            }

            for (World world : Bukkit.getWorlds()) {
                String name = world.getName();
                State state = STATES.computeIfAbsent(name, ignored -> new State());
                int deferred = DEFERRED.getOrDefault(name, 0);
                int growthTotal = GROWTH.getOrDefault(name, 0);
                int growthRunnable = GROWTH_RUNNABLE.getOrDefault(name, 0);
                int growthWaiting = Math.max(0, growthTotal - growthRunnable);
                int activeQueue = ChunkyBackpressurePolicy.populationQueueSize(deferred);
                boolean running = Boolean.TRUE.equals(isRunning.invoke(api, name));

                // An API continueTask() success can precede the new worker becoming
                // visible. Verify progress to the running state rather than declaring
                // a successful recovery while it remains stopped.
                if (state.resumeUnconfirmed && running) {
                    state.resumeUnconfirmed = false;
                    state.unconfirmedResumes = 0;
                    log.info("[Metropolis] Chunky running confirmed after automatic resume in " + name + ".");
                } else if (state.resumeUnconfirmed && now - state.resumeConfirmUntil >= 0L) {
                    state.resumeUnconfirmed = false;
                    state.unconfirmedResumes++;
                    state.ourPause = true;
                    state.pausedAt = now;
                    state.stableSince = 0L;
                    state.stableSamples = 0;
                    log.severe("[Metropolis] Chunky continueTask returned success but the task never became running in " + name
                            + "; retry budget " + state.unconfirmedResumes + "/3, verify saved task.");
                }

                boolean danger = ChunkyBackpressurePolicy.shouldPause(usage, deferred, growthRunnable);
                if (state.ourPause) {
                    if (state.unconfirmedResumes >= 3 && !running) {
                        status(log, name, state, now, usage, retainedUsage, remaining,
                                deferred, growthTotal, growthRunnable,
                                "3 continue attempts failed; task stopped and operator investigation required");
                        continue;
                    }

                    // Expire a prior repeat-pause cooldown using monotonic-time
                    // subtraction, which remains valid across a negative nanoTime origin.
                    if (state.recoveryCooldownActive && now - state.recoveryNotBefore >= 0L) {
                        state.recoveryCooldownActive = false;
                    }

                    // Track the LOW QUEUE state, not the post-GC heap percentage.
                    // A single spike above either low-water mark resets the dwell.
                    boolean queuesLow = ChunkyBackpressurePolicy.queuesLowEnough(deferred, growthRunnable);
                    if (!queuesLow) {
                        state.stableSince = 0L;
                        state.stableSamples = 0;
                    } else if (state.stableSamples == 0) {
                        state.stableSince = now;
                        state.stableSamples = 1;
                    } else {
                        state.stableSamples++;
                    }

                    boolean recover = ChunkyBackpressurePolicy.canResume(
                            deferred, growthRunnable, usage, remaining,
                            state.stableSince, state.stableSamples,
                            state.recoveryCooldownActive, state.recoveryNotBefore, now);
                    if (!recover || running) {
                        if (running) pause.invoke(api, name);
                        String reason;
                        if (!queuesLow) {
                            reason = "waiting for deferred population queue to drain";
                        } else if (remaining < MIN_FREE_RESUME_BYTES) {
                            reason = "waiting for at least 512 MiB absolute free heap";
                        } else if (usage > ChunkyBackpressurePolicy.RESUME_HEAP) {
                            reason = "waiting for heap use to fall to " + pct(ChunkyBackpressurePolicy.RESUME_HEAP) + "% or lower";
                        } else if (state.recoveryCooldownActive) {
                            reason = "waiting for rapid repeat-pause cooldown";
                        } else if (running) {
                            reason = "waiting for Chunky worker stop/save";
                        } else {
                            reason = "waiting for ten seconds of stable low queue depth";
                        }
                        status(log, name, state, now, usage, retainedUsage, remaining,
                                deferred, growthTotal, growthRunnable, reason);
                        continue;
                    }

                    if (Boolean.TRUE.equals(resume.invoke(api, name))) {
                        state.ourPause = false;
                        state.hasResumed = true;
                        state.lastResumeAt = now;
                        state.stableSince = 0L;
                        state.stableSamples = 0;
                        state.resumeUnconfirmed = true;
                        state.resumeConfirmUntil = now + RESUME_CONFIRM_NS;
                        log.info("[Metropolis] Chunky resumed after population/heap recovery in " + name
                                + ": deferred=" + deferred
                                + ", branchGrowthReady=" + growthRunnable
                                + ", branchGrowthWaiting=" + growthWaiting
                                + ", populationQueue=" + activeQueue
                                + ", heap=" + pct(usage) + "% (resume ceiling "
                                + pct(ChunkyBackpressurePolicy.RESUME_HEAP) + "%), free=" + mib(remaining)
                                + " MiB (post-GC retained " + pct(retainedUsage) + "% is diagnostic only).");
                    } else {
                        status(log, name, state, now, usage, retainedUsage, remaining,
                                deferred, growthTotal, growthRunnable,
                                "Chunky saved task is not yet resumable");
                    }
                    continue;
                }

                if (danger && running && Boolean.TRUE.equals(pause.invoke(api, name))) {
                    state.ourPause = true;
                    state.pausedAt = now;
                    state.stableSince = 0L;
                    state.stableSamples = 0;
                    state.lastStatusNs = now;
                    state.pauseCount++;
                    boolean rapidRePause = ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                            state.hasResumed, state.lastResumeAt, now);
                    if (rapidRePause) {
                        state.recoveryCooldownActive = true;
                        state.recoveryNotBefore = now + RAPID_REPAUSE_COOLDOWN_NS;
                    }
                    String pauseReason = deferred >= QUEUE_PAUSE
                            ? "deferred population backlog reached its pause limit"
                            : "heap protective threshold reached";
                    log.warning("[Metropolis] Chunky protective pause in " + name
                            + ": " + pauseReason
                            + "; deferred=" + deferred
                            + ", readyBranchGrowth=" + growthRunnable
                            + ", waitingBranchGrowth=" + growthWaiting
                            + ", populationQueue=" + activeQueue
                            + ", heap=" + pct(usage) + "%, post-GC retained=" + pct(retainedUsage)
                            + "% (diagnostic only), free=" + mib(remaining) + " MiB"
                            + "; resume after deferred <= " + QUEUE_RESUME
                            + " and heap <= " + pct(ChunkyBackpressurePolicy.RESUME_HEAP)
                            + "% for 10s/10 samples, plus >=512 MiB free; branch growth drains independently"
                            + (state.recoveryCooldownActive
                                ? ", repeat-pause cooldown "
                                    + Math.max(0L, state.recoveryNotBefore - now) / 1_000_000_000L + "s"
                                : "")
                            + ". GC: " + gcStats() + "; planning: " + retentionStats());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (lastIntegrationErrorNs == 0L || now - lastIntegrationErrorNs >= STATUS_NS) {
                lastIntegrationErrorNs = now;
                Bukkit.getLogger().severe("[Metropolis] Chunky heap guard failed (generation protection NOT assured): " + ex);
            }
        }
    }

    private static long sum(Map<String, Integer> values) {
        long total = 0L;
        for (int count : values.values()) total += Math.max(0, count);
        return total;
    }

    private static long sumLong(Map<String, Long> values) {
        long total = 0L;
        for (long value : values.values()) total += Math.max(0L, value);
        return total;
    }

    private static void status(Logger log, String name, State state, long now,
                               double usage, double retainedUsage, long remaining,
                               int deferred, int growthTotal, int growthRunnable, String reason) {
        if (now - state.lastStatusNs < STATUS_NS) return;
        state.lastStatusNs = now;
        int growthWaiting = Math.max(0, growthTotal - growthRunnable);
        int activeQueue = ChunkyBackpressurePolicy.populationQueueSize(deferred);
        log.warning("[Metropolis] Chunky paused in " + name + ": " + reason
                + "; deferred=" + deferred
                + ", readyBranchGrowth=" + growthRunnable
                + ", waitingBranchGrowth=" + growthWaiting
                + ", populationQueue=" + activeQueue
                + ", heap=" + pct(usage) + "%, post-GC retained=" + pct(retainedUsage)
                + "% (diagnostic only), free=" + mib(remaining) + " MiB"
                + ", paused=" + ((now - state.pausedAt) / 1_000_000_000L)
                + "s, low-queue samples=" + state.stableSamples + "/" + MIN_STABLE_SAMPLES
                + ", recovery cooldown="
                + (state.recoveryCooldownActive
                    ? Math.max(0L, state.recoveryNotBefore - now) / 1_000_000_000L : 0L)
                + "s, GC=" + gcStats() + ", planning=" + retentionStats() + ".");
    }

    private static int pct(double value) { return (int) Math.round(value * 100); }
    private static long mib(long value) { return Math.max(0, value / 1_048_576L); }

    /** Returns the latest post-collection live heap, or -1 when unavailable. */
    private static long postGcHeapBytes() {
        try {
            long afterGc = 0L;
            boolean haveAfterGc = false;
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
                MemoryUsage usage = pool.getCollectionUsage();
                if (usage != null && usage.getUsed() >= 0L) {
                    afterGc += usage.getUsed();
                    haveAfterGc = true;
                }
            }
            return haveAfterGc ? afterGc : -1L;
        } catch (RuntimeException | LinkageError e) {
            return -1L;
        }
    }

    /** Diagnostics only. CollectionUsage may be unavailable for some collectors. */
    private static String gcStats() {
        try {
            long collections = 0, time = 0, afterGc = 0;
            boolean haveAfterGc = false;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (gc.getCollectionCount() > 0) collections += gc.getCollectionCount();
                if (gc.getCollectionTime() > 0) time += gc.getCollectionTime();
            }
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() != java.lang.management.MemoryType.HEAP) continue;
                MemoryUsage u = pool.getCollectionUsage();
                if (u != null && u.getUsed() >= 0) { afterGc += u.getUsed(); haveAfterGc = true; }
            }
            return "collections=" + collections + " gcMs=" + time
                    + " postGcHeapMiB=" + (haveAfterGc ? Long.toString(mib(afterGc)) : "unavailable");
        } catch (RuntimeException | LinkageError e) { return "unavailable(" + e.getClass().getSimpleName() + ")"; }
    }

    /**
     * Read-only map cardinalities. These are counts, NOT retained byte sizes.
     * Report each family independently: one optional subsystem being absent must
     * not suppress diagnostics for the rest of the generator.
     * Do not traverse / evict actual plan entries while async chunk planning is active.
     */
    private static String retentionStats() {
        StringBuilder out = new StringBuilder(320);
        try {
            Map<?,?> layouts = staticMap("ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts", "CACHE");
            out.append("layouts=").append(layouts.size());
            long cells = 0, conflicts = 0, domains = 0;
            for (Object layout : layouts.values()) {
                cells += instanceMap(layout, "byCell").size();
                conflicts += instanceMap(layout, "conflicts").size();
                domains += instanceMap(layout, "byId").size();
            }
            out.append(" layoutCells=").append(cells)
               .append(" layoutConflicts=").append(conflicts)
               .append(" layoutDomains=").append(domains);
            out.append(" roadShapes=")
               .append(staticMap("ch.k42.metropolis.grid.urbanGrid.layout.WorldLayouts", "SHAPES").size());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.append(" layouts=unavailable(").append(e.getClass().getSimpleName()).append(')');
        }
        try {
            Map<?,?> highways = staticMap("ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetworks", "CACHE");
            out.append(" highwayNetworks=").append(highways.size());
            long cities = 0, candidates = 0, candidateKnown = 0, survives = 0, baseSurvives = 0;
            for (Object highway : highways.values()) {
                cities += instanceMap(highway, "cities").size();
                candidates += instanceMap(highway, "candidates").size();
                candidateKnown += instanceMap(highway, "candidateKnown").size();
                survives += instanceMap(highway, "survives").size();
                baseSurvives += instanceMap(highway, "baseSurvives").size();
            }
            out.append(" highwayCities=").append(cities)
               .append(" candidateRoutes=").append(candidates)
               .append(" candidateChecks=").append(candidateKnown)
               .append(" survivingChecks=").append(survives)
               .append(" baseSurvivingChecks=").append(baseSurvives);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.append(" highway=unavailable(").append(e.getClass().getSimpleName()).append(')');
        }
        // The completed-parcel registry is not an LRU. It retains actual
        // Parcel objects, and its footprint can grow with explored chunks.
        // Observe it before considering any eviction that could break cutouts.
        try {
            Map<?,?> worlds = staticMap("ch.k42.metropolis.grid.urbanGrid.parcel.PlacedParcels", "BY_WORLD");
            long chunks = 0, cuts = 0, ports = 0, connections = 0;
            for (Object worldState : worlds.values()) {
                chunks += instanceMap(worldState, "finished").size();
                cuts += collectionSize(instanceField(worldState, "cut"));
                ports += instanceMap(worldState, "finishedPorts").size();
                connections += collectionSize(instanceField(worldState, "connectedPorts"));
            }
            out.append(" parcelWorlds=").append(worlds.size())
               .append(" parcelChunkRefs=").append(chunks)
               .append(" cutoutKeys=").append(cuts)
               .append(" portRefs=").append(ports)
               .append(" portConnections=").append(connections);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.append(" parcels=unavailable(").append(e.getClass().getSimpleName()).append(')');
        }
        // Track the bounded caches as controls: their counts should plateau.
        appendStaticCount(out, "blockPlans", "ch.k42.metropolis.grid.urbanGrid.districts.BlockPlans", "CACHE");
        appendStaticCount(out, "farmPlans", "ch.k42.metropolis.grid.urbanGrid.farm.FarmLayout", "CACHE");
        appendStaticCount(out, "windPlans", "ch.k42.metropolis.grid.urbanGrid.forest.WildDecor", "WIND_PLANS");
        try {
            String clips = "ch.k42.metropolis.grid.urbanGrid.clipboard.FileClipboard";
            out.append(" schematicClipboards=").append(staticMap(clips, "schematicCache").size())
               .append(" deferredSources=").append(staticMap(clips, "deferredSources").size());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.append(" schematics=unavailable(").append(e.getClass().getSimpleName()).append(')');
        }
        try {
            Map<?,?> scopes = staticMap("ch.k42.metropolis.grid.urbanGrid.clipboard.DeferredSchematicMetadata", "scopes");
            long inMemory = 0;
            for (Object scope : scopes.values()) {
                Object queue = instanceField(scope, "window");
                if (queue instanceof java.util.Collection<?>) inMemory += ((java.util.Collection<?>)queue).size();
            }
            out.append(" metadataScopes=").append(scopes.size())
               .append(" metadataWindow=").append(inMemory);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.append(" metadata=unavailable(").append(e.getClass().getSimpleName()).append(')');
        }
        return out.toString().trim();
    }

    private static int collectionSize(Object obj) {
        return obj instanceof java.util.Collection<?> ? ((java.util.Collection<?>)obj).size() : 0;
    }
    private static void appendStaticCount(StringBuilder out, String label, String classname, String fieldname) {
        try {
            out.append(' ').append(label).append('=').append(staticMap(classname, fieldname).size());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.append(' ').append(label).append("=unavailable(").append(e.getClass().getSimpleName()).append(')');
        }
    }
    private static Map<?,?> staticMap(String classname, String fieldname) throws ReflectiveOperationException {
        Field field = Class.forName(classname).getDeclaredField(fieldname);
        field.setAccessible(true);
        return (Map<?,?>) field.get(null);
    }
    private static Object instanceField(Object instance, String fieldname) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(fieldname);
        field.setAccessible(true);
        return field.get(instance);
    }
    private static Map<?,?> instanceMap(Object instance, String fieldname) throws ReflectiveOperationException {
        return (Map<?,?>) instanceField(instance, fieldname);
    }
}
