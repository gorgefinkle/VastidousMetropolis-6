package ch.k42.metropolis.grid.urbanGrid.parcel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional, file-only export of completed BUILD parcels for external consumers.
 *
 * Metropolis has no runtime dependency on the consumer plugin. Generation
 * threads submit immutable records; one daemon writer serializes, de-duplicates
 * and durably appends them away from world-generation threads.
 */
public final class MetropolisBuildFlagExporter implements AutoCloseable {
   public static final int SCHEMA_VERSION = 1;
   private static final String EXPORT_DIRECTORY = "deadstreets-build-flags-v1";
   private static final int MAX_CANDIDATES_PER_RECORD = 128;

   /**
    * Only ordinary BUILD parcels represent population-bearing buildings.
    * Forced landmark districts are excluded even when their selected clip
    * falls back to an ordinary context; PARK and LANDMARK contexts are also
    * excluded as a final guard against config-tagged BUILD assets.
    */
   public static boolean isEligibleBuild(SchematicType schematicType,
                                         ContextType districtContext,
                                         boolean forcedDistrict) {
      return schematicType == SchematicType.BUILD && !forcedDistrict
              && districtContext != null
              && districtContext != ContextType.PARK
              && districtContext != ContextType.LANDMARK;
   }

   public record Footprint(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) { }
   public record Reference(int centerX, int centerZ) { }
   public record InteriorCandidate(int x, int floorY, int z,
                                   boolean verifiedInterior, boolean standingClearance) { }
   public record BuildingRecord(int schemaVersion, String buildingId, String worldId,
                                String districtType, Footprint footprint, Reference reference,
                                List<InteriorCandidate> interiorCandidates) { }

   private static final class WorldState {
      final Path file;
      final Set<String> writtenIds = new HashSet<>();
      boolean loaded;
      WorldState(Path file) { this.file = file; }
   }

   private final Path dataFolder;
   private final Logger logger;
   private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
   private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
      Thread thread = new Thread(task, "Metropolis-BuildFlag-Writer");
      thread.setDaemon(true);
      return thread;
   });
   private final ConcurrentHashMap<UUID, WorldState> worlds = new ConcurrentHashMap<>();
   private volatile boolean closed;

   public MetropolisBuildFlagExporter(Path pluginDataFolder, Logger logger) {
      this.dataFolder = pluginDataFolder;
      this.logger = logger;
   }

   /** Stable across generation retries and server restarts for the same planned building. */
   public static String buildingId(UUID worldId, String districtType, String schematicId,
                                   Footprint footprint) {
      String identity = worldId + "|" + districtType + "|" + schematicId + "|"
              + footprint.minX() + "|" + footprint.maxX() + "|"
              + footprint.minY() + "|" + footprint.maxY() + "|"
              + footprint.minZ() + "|" + footprint.maxZ();
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256")
                 .digest(identity.getBytes(StandardCharsets.UTF_8));
         return "metro-" + HexFormat.of().formatHex(digest, 0, 16);
      } catch (NoSuchAlgorithmException impossible) {
         throw new IllegalStateException("SHA-256 is unavailable", impossible);
      }
   }

   /** Queue an immutable record. This method never performs filesystem I/O. */
   public void export(UUID worldId, String buildingId, String districtType,
                      Footprint footprint, Reference reference,
                      List<InteriorCandidate> candidates) {
      if (closed || worldId == null || buildingId == null || districtType == null || footprint == null || reference == null) {
         return;
      }
      List<InteriorCandidate> safeCandidates = candidates == null ? List.of()
              : List.copyOf(candidates.size() > MAX_CANDIDATES_PER_RECORD
                    ? evenlySample(candidates, MAX_CANDIDATES_PER_RECORD) : candidates);
      BuildingRecord record = new BuildingRecord(SCHEMA_VERSION, buildingId, worldId.toString(),
              districtType, footprint, reference, safeCandidates);
      try {
         writer.execute(() -> append(record));
      } catch (RejectedExecutionException rejected) {
         if (!closed) logger.log(Level.WARNING, "[BUILD-FLAGS] Export queue rejected " + buildingId, rejected);
      }
   }

   private static List<InteriorCandidate> evenlySample(List<InteriorCandidate> values, int limit) {
      java.util.ArrayList<InteriorCandidate> selected = new java.util.ArrayList<>(limit);
      for (int i = 0; i < limit; i++) {
         int index = (int)(((long)i * (values.size() - 1)) / (limit - 1));
         selected.add(values.get(index));
      }
      return selected;
   }

   private void append(BuildingRecord record) {
      UUID worldId = UUID.fromString(record.worldId());
      Path directory = dataFolder.resolve(EXPORT_DIRECTORY).resolve(worldId.toString());
      Path file = directory.resolve("buildings.jsonl");
      WorldState state = worlds.computeIfAbsent(worldId, ignored -> new WorldState(file));
      try {
         if (!state.loaded) loadState(state);
         if (state.writtenIds.contains(record.buildingId())) return;

         byte[] line = (gson.toJson(record) + "\n").getBytes(StandardCharsets.UTF_8);
         for (int attempt = 1; attempt <= 3; attempt++) {
            try {
               Files.createDirectories(directory);
               // If a prior write failed halfway through, discard just its
               // unterminated tail before retrying this complete record.
               if (Files.exists(file)) {
                  try (FileChannel repair = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                     truncateIncompleteTail(repair);
                  }
               }
               try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                       StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                  ByteBuffer buffer = ByteBuffer.wrap(line);
                  while (buffer.hasRemaining()) channel.write(buffer);
                  channel.force(true);
               }
               state.writtenIds.add(record.buildingId());
               return;
            } catch (Exception failure) {
               // force() can fail after bytes reached the file. Re-read the
               // durable index before retrying so that case cannot duplicate.
               try {
                  refreshWrittenIds(state);
                  if (state.writtenIds.contains(record.buildingId())) return;
               } catch (IOException indexFailure) {
                  failure.addSuppressed(indexFailure);
               }
               if (attempt == 3) {
                  logger.log(Level.SEVERE, "[BUILD-FLAGS] Could not export completed building "
                          + record.buildingId() + " after " + attempt + " attempts", failure);
               } else {
                  try { Thread.sleep(150L * attempt); }
                  catch (InterruptedException interrupted) {
                     Thread.currentThread().interrupt();
                     logger.log(Level.WARNING, "[BUILD-FLAGS] Export retry interrupted for " + record.buildingId(), interrupted);
                     return;
                  }
               }
            }
         }
      } catch (Exception failure) {
         logger.log(Level.SEVERE, "[BUILD-FLAGS] Could not initialize export for completed building "
                 + record.buildingId(), failure);
      }
   }

   private void loadState(WorldState state) throws IOException {
      Files.createDirectories(state.file.getParent());
      refreshWrittenIds(state);
      state.loaded = true;
   }

   private void refreshWrittenIds(WorldState state) throws IOException {
      state.writtenIds.clear();
      if (!Files.exists(state.file)) return;
      try (FileChannel channel = FileChannel.open(state.file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
         truncateIncompleteTail(channel);
      }
      try (BufferedReader reader = Files.newBufferedReader(state.file, StandardCharsets.UTF_8)) {
         String line;
         while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
               JsonElement parsed = JsonParser.parseString(line);
               if (!parsed.isJsonObject()) continue;
               JsonObject object = parsed.getAsJsonObject();
               JsonElement id = object.get("buildingId");
               JsonElement version = object.get("schemaVersion");
               if (id != null && version != null && version.getAsInt() == SCHEMA_VERSION
                       && !id.getAsString().isBlank()) {
                  state.writtenIds.add(id.getAsString());
               }
            } catch (RuntimeException malformed) {
               logger.warning("[BUILD-FLAGS] Ignoring malformed completed-building line in " + state.file);
            }
         }
      }
   }

   /** Drop only an unterminated final JSONL row left by an interrupted append. */
   private static void truncateIncompleteTail(FileChannel channel) throws IOException {
      long size = channel.size();
      if (size == 0) return;
      ByteBuffer one = ByteBuffer.allocate(1);
      channel.position(size - 1);
      channel.read(one);
      one.flip();
      if (one.get() == (byte)'\n') return;

      ByteBuffer window = ByteBuffer.allocate(4096);
      long cursor = size;
      while (cursor > 0) {
         long start = Math.max(0, cursor - window.capacity());
         int length = (int)(cursor - start);
         window.clear();
         window.limit(length);
         channel.position(start);
         while (window.hasRemaining()) {
            int read = channel.read(window);
            if (read <= 0) break;
         }
         byte[] bytes = window.array();
         for (int i = length - 1; i >= 0; i--) {
            if (bytes[i] == (byte)'\n') {
               channel.truncate(start + i + 1);
               return;
            }
         }
         cursor = start;
      }
      channel.truncate(0);
   }

   @Override
   public void close() {
      closed = true;
      writer.shutdown();
      try {
         if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
            // Do not cancel accepted records: the daemon writer remains in
            // orderly-shutdown mode and continues draining its queue while
            // the server process is still alive.
            logger.severe("[BUILD-FLAGS] Export queue is still draining after 30 seconds; accepted records were not cancelled.");
         }
      } catch (InterruptedException interrupted) {
         // shutdown() has already forbidden new work; leave queued records
         // running instead of interrupting a write in the middle of a line.
         logger.log(Level.WARNING, "[BUILD-FLAGS] Interrupted while waiting for export queue to drain.", interrupted);
         Thread.currentThread().interrupt();
      }
   }
}
