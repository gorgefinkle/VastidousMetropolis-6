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
    private static final double SOFT_PAUSE = 0.74;
    private static final double NORMAL_RESUME = 0.68;
    private static final double PROBE_MAX_USAGE = 0.80;
    private static final double HARD_PAUSE = 0.86;
    private static final int QUEUE_PAUSE = 1024;
    private static final int QUEUE_RESUME = 128;
    private static final long STATUS_NS = 60_000_000_000L;
    private static final long STABILITY_NS = 90_000_000_000L;
    private static final long PROBE_NS = 30_000_000_000L;
    private static final long MIN_FREE_PROBE = 1536L * 1024L * 1024L;

    private static final Map<String, Integer> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, State> STATES = new HashMap<>(); // main thread only
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static volatile boolean missingServiceWarned;
    private static volatile long lastIntegrationErrorNs;
    private static volatile long lastDiagnosticNs;
    private static volatile LongSupplier CLOCK = System::nanoTime; // testable monotonic clock

    private static final class State {
        boolean ourPause;
        long pausedAt;
        double initialPauseUsage;
        double stableAtUsage;
        long stableSince;
        long lastStatusNs;
        long probeUntil;
        long resumeConfirmUntil;
        boolean resumeUnconfirmed;
        int pauseCount;
        int probeCount;
        int unconfirmedResumes;
    }

    private ChunkyMemoryValve() {}

    public static void startFromPluginEnable() { startMonitorIfNeeded(); }

    public static void pending(World world, int count) {
        if (world == null) return;
        PENDING.put(world.getName(), Math.max(0, count));
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
            Bukkit.getLogger().info("[Metropolis] Chunky heap guard enabled: pause 74% (hard 86%), normal resume 68%; stable-heap limited probes at <=80%, queue <=128.");
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
            // This periodic series is necessary to distinguish rising post-GC
            // retention from ordinary temporary chunk/WorldEdit allocations.
            if (lastDiagnosticNs == 0L || now - lastDiagnosticNs >= 300_000_000_000L) {
                lastDiagnosticNs = now;
                long deferred = 0;
                for (int count : PENDING.values()) deferred += count;
                log.info("[Metropolis] GENERATION HEAP AUDIT: used=" + mib(used)
                        + " MiB, max=" + mib(max) + " MiB (" + pct(usage)
                        + "%), free=" + mib(remaining) + " MiB, deferred=" + deferred
                        + ", " + gcStats() + ", " + retentionStats());
            }
            for (World world : Bukkit.getWorlds()) {
                String name = world.getName();
                State state = STATES.computeIfAbsent(name, ignored -> new State());
                int queue = PENDING.getOrDefault(name, 0);
                boolean running = Boolean.TRUE.equals(isRunning.invoke(api, name));

                // An API continueTask() success can precede the new worker becoming
                // visible. Verify progress to the running state rather than declaring
                // a successful recovery while it remains stopped.
                if (state.resumeUnconfirmed && running) {
                    state.resumeUnconfirmed = false;
                    state.unconfirmedResumes = 0;
                    log.info("[Metropolis] Chunky running confirmed after automatic resume in " + name + ".");
                } else if (state.resumeUnconfirmed && now >= state.resumeConfirmUntil) {
                    state.resumeUnconfirmed = false;
                    state.unconfirmedResumes++;
                    state.ourPause = true;
                    state.pausedAt = now;
                    state.initialPauseUsage = usage;
                    state.stableAtUsage = usage;
                    state.stableSince = now;
                    state.probeUntil = 0;
                    log.severe("[Metropolis] Chunky continueTask returned success but the task never became running in " + name
                            + "; retry budget " + state.unconfirmedResumes + "/3, verify saved task.");
                }

                boolean probeActive = now < state.probeUntil;
                boolean danger = usage >= HARD_PAUSE || queue >= QUEUE_PAUSE
                        || (!probeActive && usage >= SOFT_PAUSE);
                if (state.ourPause) {
                    if (state.unconfirmedResumes >= 3 && !running) {
                        status(log, name, state, now, usage, queue, max,
                                "3 continue attempts failed; task stopped and operator investigation required");
                        continue;
                    }
                    // A manual /chunky continue can occur during pause/shutdown;
                    // always reapply pause if running until recovery is permissible.
                    boolean coolEnough = usage <= NORMAL_RESUME;
                    // A recovery probe requires measured stability for a full
                    // window, not merely a low reading at the end of a timer.
                    boolean probeHeadroom = usage <= PROBE_MAX_USAGE && remaining >= MIN_FREE_PROBE;
                    if (!probeHeadroom || Math.abs(usage - state.stableAtUsage) > 0.01) {
                        state.stableSince = now;
                        state.stableAtUsage = usage;
                    }
                    boolean stableWithHeadroom = probeHeadroom
                            && state.stableSince > 0L
                            && now - state.stableSince >= STABILITY_NS;
                    boolean safeQueue = queue <= QUEUE_RESUME;
                    boolean recover = safeQueue && (coolEnough || stableWithHeadroom);
                    if (!recover || running) {
                        if (running) pause.invoke(api, name);
                        status(log, name, state, now, usage, queue, max,
                                recover && running ? "waiting for Chunky worker stop/save" : "waiting for safe recovery");
                        continue;
                    }
                    if (Boolean.TRUE.equals(resume.invoke(api, name))) {
                        state.ourPause = false;
                        state.resumeUnconfirmed = true;
                        state.resumeConfirmUntil = now + PROBE_NS;
                        if (stableWithHeadroom && !coolEnough) {
                            state.probeUntil = now + PROBE_NS;
                            state.probeCount++;
                            log.info("[Metropolis] Chunky limited recovery probe in " + name
                                    + ": heap " + pct(usage) + "%, free headroom " + mib(remaining)
                                    + " MiB, queue " + queue + "; hard pause at 86% during next 30s.");
                        } else {
                            state.probeUntil = 0;
                            log.info("[Metropolis] Chunky resumed after worker stop and recovery in " + name
                                    + ": heap " + pct(usage) + "%, pending " + queue + ".");
                        }
                    } else {
                        status(log, name, state, now, usage, queue, max,
                                "Chunky saved task is not yet resumable");
                    }
                    continue;
                }
                if (danger && running && Boolean.TRUE.equals(pause.invoke(api, name))) {
                    state.ourPause = true;
                    state.pausedAt = now;
                    state.initialPauseUsage = usage;
                    state.stableAtUsage = usage;
                    state.stableSince = now;
                    state.lastStatusNs = now;
                    state.probeUntil = 0;
                    state.pauseCount++;
                    log.warning("[Metropolis] Chunky protective pause in " + name
                            + ": heap " + pct(usage) + "%, free " + mib(remaining)
                            + " MiB, queue " + queue + "; resume at <=68% or stable <=80% with guarded probes."
                            + " GC: " + gcStats() + "; planning: " + retentionStats());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
            if (lastIntegrationErrorNs == 0L || now - lastIntegrationErrorNs >= STATUS_NS) {
                lastIntegrationErrorNs = now;
                Bukkit.getLogger().severe("[Metropolis] Chunky heap guard failed (generation protection NOT assured): " + ex);
            }
        }
    }

    private static void status(Logger log, String name, State state, long now,
                               double usage, int queue, long max, String reason) {
        if (now - state.lastStatusNs < STATUS_NS) return;
        state.lastStatusNs = now;
        log.warning("[Metropolis] Chunky paused in " + name + ": " + reason
                + "; heap " + pct(usage) + "%, free " + mib((long)(max * (1.0 - usage)))
                + " MiB, pending " + queue + ", paused " + ((now - state.pausedAt) / 1_000_000_000L)
                + " s, probes " + state.probeCount + ", GC " + gcStats()
                + ", planning " + retentionStats() + ".");
    }

    private static int pct(double value) { return (int) Math.round(value * 100); }
    private static long mib(long value) { return Math.max(0, value / 1_048_576L); }

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
