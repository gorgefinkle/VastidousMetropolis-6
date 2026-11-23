package ch.k42.metropolis.commands;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.forest.ForestGenerator;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaWilderness;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;

/** Finds Bloomlands from Metropolis' own deterministic wilderness field. */
public final class CommandMetropolisBloomlands implements CommandExecutor {
   private static final java.util.Set<UUID> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
   private static final int SAMPLE_STEP = 32;
   private static final int SAMPLES_PER_TICK = 96;
   private static final int DEFAULT_RADIUS_CHUNKS = 64;
   private static final int MAX_RADIUS_CHUNKS = 256;

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
      MetropolisGenerator generator = Minions.getGeneratorForPlayer(player);
      if (generator == null || generator.getWorld() != player.getWorld()) {
         player.sendMessage("This is not a Metropolis-generated world.");
         return true;
      }
      boolean teleport = args.length > 0 && args[0].equalsIgnoreCase("tp");
      int radiusArg = teleport ? 1 : 0;
      if (args.length > radiusArg + 1) {
         usage(player);
         return true;
      }
      int radiusChunks = DEFAULT_RADIUS_CHUNKS;
      if (args.length > radiusArg) {
         try {
            radiusChunks = Integer.parseInt(args[radiusArg]);
         } catch (NumberFormatException ex) {
            usage(player);
            return true;
         }
      }
      if (radiusChunks < 1 || radiusChunks > MAX_RADIUS_CHUNKS) {
         player.sendMessage("Search radius must be between 1 and " + MAX_RADIUS_CHUNKS + " chunks.");
         return true;
      }
      if (!generator.getWorld().isChunkLoaded(player.getLocation().getBlockX() >> 4,
              player.getLocation().getBlockZ() >> 4)) {
         player.sendMessage("Your current chunk is not loaded; try again in a moment.");
         return true;
      }
      if (!ACTIVE.add(player.getUniqueId())) {
         player.sendMessage("Your previous Bloomlands search is still running.");
         return true;
      }
      if (VanillaWilderness.isActive(generator.getWorld())) {
         ACTIVE.remove(player.getUniqueId());
         player.sendMessage("Bloomlands is unavailable while generator.wilderness.vanilla.enabled is true.");
         return true;
      }
      player.sendMessage("Searching the planned wilderness for Bloomlands within " + radiusChunks + " chunks...");
      new Search(player, generator, player.getLocation(), radiusChunks * 16, teleport).begin();
      return true;
   }

   private static void usage(Player player) {
      player.sendMessage("Usage: /bloomlands [radiusChunks]");
      player.sendMessage("       /bloomlands tp [radiusChunks]");
      player.sendMessage("Default radius: " + DEFAULT_RADIUS_CHUNKS + " chunks; maximum: " + MAX_RADIUS_CHUNKS + ".");
   }

   private static final class Search {
      private final Player player;
      private final MetropolisGenerator generator;
      private final World world;
      private final int fromX, fromZ, range;
      private final boolean teleport;
      private final int maxRing;
      private int ring;
      private List<int[]> points = List.of();
      private int pointIndex;
      private final List<int[]> candidates = new ArrayList<>();
      private boolean closed;

      Search(Player player, MetropolisGenerator generator, Location origin, int range, boolean teleport) {
         this.player = player;
         this.generator = generator;
         this.world = player.getWorld();
         this.fromX = origin.getBlockX();
         this.fromZ = origin.getBlockZ();
         this.range = range;
         this.teleport = teleport;
         this.maxRing = (range + SAMPLE_STEP - 1) / SAMPLE_STEP;
      }

      private boolean active() {
         return !closed && player.isOnline() && player.getWorld() == world
                 && generator.getPlugin().isEnabled();
      }

      private void close() {
         if (!closed) {
            closed = true;
            ACTIVE.remove(player.getUniqueId());
         }
      }

      void begin() {
         nextRing();
      }

      private void nextRing() {
         if (!active()) { close(); return; }
         if (ring > maxRing) { exhausted(); return; }
         points = ringPoints(ring);
         pointIndex = 0;
         Bukkit.getScheduler().runTask(generator.getPlugin(), this::scanBatch);
      }

      private List<int[]> ringPoints(int r) {
         List<int[]> result = new ArrayList<>();
         if (r == 0) {
            result.add(new int[]{fromX, fromZ});
            return result;
         }
         int offset = r * SAMPLE_STEP;
         for (int dx = -r; dx <= r; dx++) {
            result.add(new int[]{fromX + dx * SAMPLE_STEP, fromZ - offset});
            result.add(new int[]{fromX + dx * SAMPLE_STEP, fromZ + offset});
         }
         for (int dz = -r + 1; dz < r; dz++) {
            result.add(new int[]{fromX - offset, fromZ + dz * SAMPLE_STEP});
            result.add(new int[]{fromX + offset, fromZ + dz * SAMPLE_STEP});
         }
         return result;
      }

      private void scanBatch() {
         if (!active()) { close(); return; }
         int end = Math.min(points.size(), pointIndex + SAMPLES_PER_TICK);
         while (pointIndex < end) {
            int[] p = points.get(pointIndex++);
            long dx = (long)p[0] - fromX;
            long dz = (long)p[1] - fromZ;
            if (dx * dx + dz * dz > (long)range * range) continue;
            try {
               int score = ForestGenerator.bloomlandsCandidateScore(generator, p[0], p[1]);
               if (score >= 0) candidates.add(new int[]{p[0], p[1], score});
            } catch (RuntimeException ex) {
               Minions.w("[BLOOMLANDS] candidate lookup failed at %d,%d: %s", p[0], p[1], ex.toString());
               close();
               player.sendMessage("Bloomlands planning lookup failed. Check the server log.");
               return;
            }
         }
         if (pointIndex < points.size()) {
            Bukkit.getScheduler().runTaskLater(generator.getPlugin(), this::scanBatch, 1L);
            return;
         }
         chooseCandidateOrContinue();
      }

      private long distanceSquared(int[] candidate) {
         long dx = (long) candidate[0] - fromX;
         long dz = (long) candidate[1] - fromZ;
         return dx * dx + dz * dz;
      }

      private void chooseCandidateOrContinue() {
         candidates.sort((a, b) -> {
            int distance = Long.compare(distanceSquared(a), distanceSquared(b));
            return distance != 0 ? distance : Integer.compare(b[2], a[2]);
         });
         long nextRingMinimum = (long)(ring + 1) * SAMPLE_STEP;
         if (!candidates.isEmpty()
                 && (ring >= maxRing || distanceSquared(candidates.get(0)) <= nextRingMinimum * nextRingMinimum)) {
            found(candidates.remove(0));
            return;
         }
         if (ring >= maxRing) {
            exhausted();
            return;
         }
         ring++;
         nextRing();
      }

      private void found(int[] candidate) {
         int x = candidate[0], z = candidate[1];
         int chunkX = x >> 4, chunkZ = z >> 4;
         boolean wasGenerated = world.isChunkGenerated(chunkX, chunkZ);
         player.sendMessage("Bloomlands candidate found at " + x + ", " + z
                 + " (field strength " + String.format(java.util.Locale.ROOT, "%.1f%%", candidate[2] / 100.0)
                 + "). " + (wasGenerated ? "Checking generated terrain..." : "Generating its destination chunk..."));
         try {
            world.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) ->
                    Bukkit.getScheduler().runTask(generator.getPlugin(), () -> finish(candidate, chunk, error)));
         } catch (RuntimeException ex) {
            Minions.w("[BLOOMLANDS] chunk load failed at %d,%d: %s", x, z, ex.toString());
            chooseCandidateOrContinue();
         }
      }

      private void finish(int[] candidate, Chunk chunk, Throwable error) {
         if (!active()) { close(); return; }
         if (error != null || chunk == null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            Minions.w("[BLOOMLANDS] destination generation failed at %d,%d: %s", candidate[0], candidate[1],
                    cause == null ? "unknown error" : cause.toString());
            chooseCandidateOrContinue();
            return;
         }
         int[] landing;
         try {
            landing = ForestGenerator.safeBloomlandsLanding(generator, chunk, candidate[0], candidate[1]);
         } catch (RuntimeException ex) {
            Minions.w("[BLOOMLANDS] safe landing verification failed at %d,%d: %s",
                    candidate[0], candidate[1], ex.toString());
            landing = null;
         }
         if (landing == null) {
            Minions.w("[BLOOMLANDS] candidate at %d,%d had no safe landing column; trying next candidate",
                    candidate[0], candidate[1]);
            chooseCandidateOrContinue();
            return;
         }
         Location destination = new Location(world, landing[0] + 0.5, landing[1], landing[2] + 0.5,
                 player.getLocation().getYaw(), player.getLocation().getPitch());
         if (!teleport) {
            close();
            player.sendMessage("Nearest verified Bloomlands at " + landing[0] + ", " + landing[1] + ", " + landing[2]
                    + ". Use /bloomlands tp to travel there.");
            return;
         }
         boolean success = player.teleport(destination);
         close();
         if (success) {
            player.sendMessage("Teleported to Bloomlands at " + landing[0] + ", " + landing[1] + ", " + landing[2] + ".");
         } else {
            player.sendMessage("Bloomlands found at " + landing[0] + ", " + landing[1] + ", " + landing[2]
                    + ", but the teleport was rejected.");
         }
      }

      private void exhausted() {
         close();
         player.sendMessage("No suitable Bloomlands patch found within " + (range / 16) + " chunks."
                 + " Check that wilderness biomes are enabled and Bloomlands has a nonzero configured share.");
      }
   }
}
