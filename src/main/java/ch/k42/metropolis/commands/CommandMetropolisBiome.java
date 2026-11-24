package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.forest.ForestGenerator;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;

/**
 * /metrobiome tp <biome> -- progressively searches the planner's deterministic
 * wilderness field, then verifies and teleports to the nearest safe generated
 * column. There is no arbitrary user radius or small fixed search cap.
 */
public final class CommandMetropolisBiome implements CommandExecutor, TabCompleter {
   // The final wilderness regions are hundreds of blocks across. A 32-block
   // lattice finds their broad cores without spending most of the search on
   // redundant samples; every candidate chunk is still verified column-by-column.
   private static final int SAMPLE_STEP = 32;
   private static final int SAMPLES_PER_TICK = 96;
   /** A stalled Paper chunk future must not hold a rare-biome search forever. */
   private static final long DESTINATION_TIMEOUT_TICKS = 20L * 180L;
   /** Conservative bound from a lattice sample to a safe landing in its chunk. */
   private static final double LANDING_COVER_RADIUS = 23.0;
   private static final ConcurrentMap<UUID, Search> ACTIVE = new ConcurrentHashMap<>();

   private final ch.k42.metropolis.plugin.MetropolisPlugin plugin;

   public CommandMetropolisBiome(ch.k42.metropolis.plugin.MetropolisPlugin plugin) {
      this.plugin = plugin;
   }

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!(sender instanceof Player player)) {
         sender.sendMessage("Use this command in a Metropolis world as a player.");
         return true;
      }
      if (!player.hasPermission("metropolis.command")) {
         player.sendMessage("You do not have permission.");
         return true;
      }
      if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
         player.sendMessage("Metropolis wilderness biomes: " + String.join(", ", MetropolisBiomeTargets.commandNames()));
         player.sendMessage("Teleport with /metrobiome tp <biome>. Wetlands is retired and merged into forest.");
         return true;
      }
      if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
         Search running = ACTIVE.remove(player.getUniqueId());
         if (running == null) player.sendMessage("You do not have an active biome search.");
         else {
            running.cancel();
            player.sendMessage("Biome search cancelled.");
         }
         return true;
      }
      if (args.length != 2 || !args[0].equalsIgnoreCase("tp")) {
         usage(player);
         return true;
      }

      WildBiome biome = MetropolisBiomeTargets.resolve(args[1]);
      if (biome == null) {
         player.sendMessage("Unknown Metropolis biome. Available: " + String.join(", ", MetropolisBiomeTargets.commandNames()));
         return true;
      }
      if (biome == WildBiome.WETLANDS) {
         player.sendMessage("Wetlands is retired as a separate biome and is now part of forest.");
         return true;
      }

      MetropolisGenerator generator = Minions.getGeneratorForPlayer(player);
      if (generator == null || generator.getWorld() != player.getWorld()) {
         player.sendMessage("This is not a Metropolis-generated world.");
         return true;
      }
      if (!ForestGenerator.isWildBiomeSearchAvailable(generator, biome)) {
         player.sendMessage(MetropolisBiomeTargets.displayName(biome)
                 + " is not enabled as a separately generated wilderness biome in this world.");
         return true;
      }
      if (ACTIVE.containsKey(player.getUniqueId())) {
         player.sendMessage("Your previous biome search is still running. Use /metrobiome cancel to stop it.");
         return true;
      }

      Search search = new Search(player, generator, biome, player.getLocation());
      Search previous = ACTIVE.putIfAbsent(player.getUniqueId(), search);
      if (previous != null) {
         player.sendMessage("Your previous biome search is still running. Use /metrobiome cancel to stop it.");
         return true;
      }
      player.sendMessage("Searching outward for the nearest " + MetropolisBiomeTargets.displayName(biome)
              + ". The search has no fixed radius; it checks the world-generation plan and loads only candidate destinations.");
      search.begin();
      return true;
   }

   private static void usage(Player player) {
      player.sendMessage("Usage: /metrobiome tp <biome> | /metrobiome list | /metrobiome cancel");
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1) return prefix(List.of("tp", "list", "cancel"), args[0]);
      if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
         return prefix(MetropolisBiomeTargets.commandNames(), args[1]);
      }
      return List.of();
   }

   private static List<String> prefix(List<String> choices, String typed) {
      String prefix = typed == null ? "" : typed.toLowerCase(Locale.ROOT);
      List<String> result = new ArrayList<>();
      for (String choice : choices) if (choice.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(choice);
      return result;
   }

   private final class Search {
      private final Player player;
      private final MetropolisGenerator generator;
      private final WildBiome biome;
      private final World world;
      private final int originX;
      private final int originZ;
      private final double borderMinX, borderMaxX, borderMinZ, borderMaxZ;
      private final int maxRing;
      private final java.util.Set<Long> candidateChunks = ConcurrentHashMap.newKeySet();
      private final java.util.Set<Long> plannerRejectedChunks = ConcurrentHashMap.newKeySet();
      private final java.util.Set<Long> coreRejectedChunks = ConcurrentHashMap.newKeySet();
      private final List<Candidate> candidates = new ArrayList<>();
      private int ring;
      private long pointIndex;
      private long pointsInRing;
      private boolean closed;
      private boolean resolving;
      private long scannedSamples;
      private long destinationChunksChecked;
      private long destinationGenerationFailures;
      private long landingRejectedChunks;
      private long safeLandingChunks;
      private long bestDistanceSquared = Long.MAX_VALUE;
      private int[] bestLanding;
      private long lastProgressMillis = System.currentTimeMillis();

      Search(Player player, MetropolisGenerator generator, WildBiome biome, Location origin) {
         this.player = player;
         this.generator = generator;
         this.biome = biome;
         this.world = player.getWorld();
         this.originX = origin.getBlockX();
         this.originZ = origin.getBlockZ();
         WorldBorder border = world.getWorldBorder();
         Location center = border.getCenter();
         double half = border.getSize() / 2.0;
         this.borderMinX = center.getX() - half;
         this.borderMaxX = center.getX() + half;
         this.borderMinZ = center.getZ() - half;
         this.borderMaxZ = center.getZ() + half;
         double reachX = Math.max(Math.abs(originX - borderMinX), Math.abs(originX - borderMaxX));
         double reachZ = Math.max(Math.abs(originZ - borderMinZ), Math.abs(originZ - borderMaxZ));
         this.maxRing = (int) Math.min(Integer.MAX_VALUE - 1L,
                 (long) Math.ceil(Math.max(reachX, reachZ) / SAMPLE_STEP) + 1L);
      }

      private boolean active() {
         return !closed && player.isOnline() && player.getWorld() == world && plugin.isEnabled()
                 && ACTIVE.get(player.getUniqueId()) == this;
      }

      void cancel() {
         closed = true;
         ACTIVE.remove(player.getUniqueId(), this);
      }

      private void close() {
         if (closed) return;
         closed = true;
         ACTIVE.remove(player.getUniqueId(), this);
      }

      void begin() {
         nextRing();
      }

      private void nextRing() {
         if (!active()) { close(); return; }
         if (ring > maxRing) { finishSearch(); return; }
         pointsInRing = ring == 0 ? 1L : 8L * ring;
         pointIndex = 0L;
         candidates.clear();
         Bukkit.getScheduler().runTask(plugin, this::scanBatch);
      }

      private long distanceSquared(int x, int z) {
         long dx = (long) x - originX;
         long dz = (long) z - originZ;
         return dx * dx + dz * dz;
      }

      private int[] pointAt(long index) {
         if (ring == 0) return new int[] {originX, originZ};
         long r = ring;
         long edge = 2L * r + 1L;
         long dx, dz;
         if (index < edge) { // north edge, including both corners
            dx = -r + index; dz = -r;
         } else if ((index -= edge) < edge) { // south edge
            dx = -r + index; dz = r;
         } else if ((index -= edge) < 2L * r - 1L) { // west edge, excluding corners
            dx = -r; dz = -r + 1L + index;
         } else { // east edge, excluding corners
            index -= 2L * r - 1L;
            dx = r; dz = -r + 1L + index;
         }
         long x = (long) originX + dx * SAMPLE_STEP;
         long z = (long) originZ + dz * SAMPLE_STEP;
         if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return null;
         return new int[] {(int) x, (int) z};
      }

      private boolean insideBorder(int x, int z) {
         double px = x + 0.5, pz = z + 0.5;
         return px >= borderMinX && px < borderMaxX && pz >= borderMinZ && pz < borderMaxZ;
      }

      private void scanBatch() {
         if (!active()) { close(); return; }
         int budget = SAMPLES_PER_TICK;
         while (pointIndex < pointsInRing && budget-- > 0) {
            int[] point = pointAt(pointIndex++);
            if (point == null || !insideBorder(point[0], point[1])) continue;
            scannedSamples++;
            int chunkX = point[0] >> 4, chunkZ = point[1] >> 4;
            long chunkKey = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
            if (candidateChunks.contains(chunkKey) || plannerRejectedChunks.contains(chunkKey)
                    || coreRejectedChunks.contains(chunkKey)) continue;
            try {
               int score = ForestGenerator.wildBiomeCandidateScore(generator, biome, point[0], point[1]);
               if (score == -2) {
                  plannerRejectedChunks.add(chunkKey);
               } else if (score == -3) {
                  coreRejectedChunks.add(chunkKey);
               } else if (score >= 0) {
                  if (candidateChunks.add(chunkKey)) {
                     candidates.add(new Candidate(point[0], point[1], distanceSquared(point[0], point[1])));
                  }
               }
            } catch (RuntimeException ex) {
               Minions.w("[METROBIOME] planner lookup failed for %s at %d,%d: %s",
                       biome.key(), point[0], point[1], ex.toString());
               close();
               player.sendMessage("Metropolis biome lookup failed. Check the server log.");
               return;
            }
         }
         reportProgress();
         if (pointIndex < pointsInRing) {
            Bukkit.getScheduler().runTaskLater(plugin, this::scanBatch, 1L);
            return;
         }
         candidates.sort(Comparator.comparingLong(candidate -> candidate.distanceSquared));
         processCandidates();
      }

      private void reportProgress() {
         long now = System.currentTimeMillis();
         if (now - lastProgressMillis < 10000L) return;
         lastProgressMillis = now;
         player.sendMessage("Still searching for " + MetropolisBiomeTargets.displayName(biome)
                 + "... sampled " + String.format(Locale.ROOT, "%,d", scannedSamples) + " biome-field positions; "
                 + String.format(Locale.ROOT, "%,d", candidateChunks.size() + plannerRejectedChunks.size() + coreRejectedChunks.size())
                 + " chunks contain a biome match ("
                 + String.format(Locale.ROOT, "%,d", candidateChunks.size()) + " broad-core planner-owned, "
                 + String.format(Locale.ROOT, "%,d", plannerRejectedChunks.size()) + " rejected by ownership, "
                 + String.format(Locale.ROOT, "%,d", coreRejectedChunks.size()) + " rejected as edge slivers); "
                 + "destinations tested=" + String.format(Locale.ROOT, "%,d", destinationChunksChecked)
                 + ", safe landings=" + String.format(Locale.ROOT, "%,d", safeLandingChunks)
                 + ", no-safe-landing=" + String.format(Locale.ROOT, "%,d", landingRejectedChunks)
                 + ", generation failures=" + String.format(Locale.ROOT, "%,d", destinationGenerationFailures) + ".");
      }

      private void processCandidates() {
         if (!active()) { close(); return; }
         if (resolving) return;
         while (!candidates.isEmpty()) {
            Candidate candidate = candidates.remove(0);
            double lowerBound = Math.max(0.0, Math.sqrt(candidate.distanceSquared) - LANDING_COVER_RADIUS);
            if (bestLanding != null && lowerBound * lowerBound >= bestDistanceSquared) continue;
            resolving = true;
            int chunkX = candidate.x >> 4, chunkZ = candidate.z >> 4;
            destinationChunksChecked++;
            boolean generated = world.isChunkGenerated(chunkX, chunkZ);
            player.sendMessage(MetropolisBiomeTargets.displayName(biome) + " candidate found at "
                    + candidate.x + ", " + candidate.z + ". "
                    + (generated ? "Verifying the existing terrain..." : "Generating and verifying this destination chunk..."));
            AtomicBoolean completed = new AtomicBoolean(false);
            BukkitTask timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
               if (!completed.compareAndSet(false, true)) return;
               TimeoutException failure = new TimeoutException("destination chunk did not complete within 180 seconds");
               Minions.w("[METROBIOME] destination chunk timed out at %d,%d; continuing biome search",
                       candidate.x, candidate.z);
               finishCandidate(candidate, null, failure);
            }, DESTINATION_TIMEOUT_TICKS);
            try {
               world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) -> {
                  if (!completed.compareAndSet(false, true)) return;
                  timeout.cancel();
                  if (!plugin.isEnabled()) return;
                  try {
                     Bukkit.getScheduler().runTask(plugin, () -> finishCandidate(candidate, chunk, error));
                  } catch (IllegalPluginAccessException ignored) {
                     // Plugin shutdown cancels the search; no callback should outlive it.
                  }
               });
            } catch (RuntimeException ex) {
               if (completed.compareAndSet(false, true)) timeout.cancel();
               resolving = false;
               destinationGenerationFailures++;
               Minions.w("[METROBIOME] async chunk request failed at %d,%d: %s", candidate.x, candidate.z, ex.toString());
               processCandidates();
            }
            return;
         }
         resolving = false;
         continueOrFinish();
      }

      private void finishCandidate(Candidate candidate, Chunk chunk, Throwable error) {
         if (!active()) { close(); return; }
         resolving = false;
         if (error != null || chunk == null) {
            destinationGenerationFailures++;
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            Minions.w("[METROBIOME] destination generation failed at %d,%d: %s", candidate.x, candidate.z,
                    cause == null ? "unknown error" : cause.toString());
            processCandidates();
            return;
         }
         int[] landing;
         try {
            landing = ForestGenerator.safeWildBiomeLanding(generator, chunk, biome, originX, originZ);
         } catch (RuntimeException ex) {
            Minions.w("[METROBIOME] safe landing verification failed at %d,%d: %s",
                    candidate.x, candidate.z, ex.toString());
            landing = null;
         }
         if (landing != null && insideBorder(landing[0], landing[2])) {
            safeLandingChunks++;
            long distance = distanceSquared(landing[0], landing[2]);
            if (distance < bestDistanceSquared) {
               bestDistanceSquared = distance;
               bestLanding = landing;
            }
         } else {
            landingRejectedChunks++;
         }
         processCandidates();
      }

      private void continueOrFinish() {
         if (!active()) { close(); return; }
         double nextRingLowerBound = Math.max(0.0, (ring + 1.0) * SAMPLE_STEP - LANDING_COVER_RADIUS);
         if (bestLanding != null && bestDistanceSquared <= nextRingLowerBound * nextRingLowerBound) {
            finishSearch();
            return;
         }
         if (ring >= maxRing) {
            finishSearch();
            return;
         }
         ring++;
         nextRing();
      }

      private void finishSearch() {
         if (!active()) { close(); return; }
         int[] landing = bestLanding;
         close();
         if (landing == null) {
            player.sendMessage("No safe " + MetropolisBiomeTargets.displayName(biome)
                    + " destination was found inside this world's border. Samples="
                    + String.format(Locale.ROOT, "%,d", scannedSamples)
                    + "; field-match chunks=" + String.format(Locale.ROOT, "%,d", candidateChunks.size() + plannerRejectedChunks.size())
                    + " (planner-owned=" + String.format(Locale.ROOT, "%,d", candidateChunks.size())
                    + ", ownership-rejected=" + String.format(Locale.ROOT, "%,d", plannerRejectedChunks.size()) + ")"
                    + "; destination chunks tested=" + String.format(Locale.ROOT, "%,d", destinationChunksChecked)
                    + " (safe=" + String.format(Locale.ROOT, "%,d", safeLandingChunks)
                    + ", no-safe-landing=" + String.format(Locale.ROOT, "%,d", landingRejectedChunks)
                    + ", generation-failed=" + String.format(Locale.ROOT, "%,d", destinationGenerationFailures) + ").");
            return;
         }
         Location destination = new Location(world, landing[0] + 0.5, landing[1], landing[2] + 0.5,
                 player.getLocation().getYaw(), player.getLocation().getPitch());
         if (player.teleport(destination)) {
            double distance = Math.sqrt(bestDistanceSquared);
            player.sendMessage("Teleported to the nearest verified " + MetropolisBiomeTargets.displayName(biome)
                    + " at x=" + landing[0] + " y=" + landing[1] + " z=" + landing[2]
                    + " (" + Math.round(distance) + " blocks away).");
         } else {
            player.sendMessage("Found " + MetropolisBiomeTargets.displayName(biome) + " at x=" + landing[0]
                    + " y=" + landing[1] + " z=" + landing[2] + ", but the teleport was rejected.");
         }
      }

      private record Candidate(int x, int z, long distanceSquared) {}
   }
}
