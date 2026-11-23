package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.grid.common.Grid;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import ch.k42.metropolis.plugin.PluginConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * The schematic index and the landmark placement log.
 *
 * This used to be a database (Ebean/SQLite) behind three tables. It is not
 * any more:
 *
 * - CLIPBOARD_SCHEMATICS was never a persistent source of truth: every
 *   start, ClipboardLoaderMkII deletes and re-stores a row for each
 *   schematic it loads (see its containsHash()/deleteClipboardHashes()/
 *   storeClipboard() sequence). So the index lives in memory and is rebuilt
 *   the same way. Rows are kept in insertion order, which is what the old
 *   unordered queries returned in practice -- selection stays deterministic.
 * - LANDMARK_PLACEMENTS has to survive restarts and tile-cache eviction (it
 *   is the landmarkCloneRadius duplicate check), so it is a small text file
 *   inside the WORLD's own folder: delete or recreate the world and the log
 *   goes with it, instead of leaking into a new world of the same name.
 *
 * The public API is unchanged, so no caller needed touching.
 */
public class ClipboardDAO {
   private final MetropolisPlugin plugin;
   private final List<ClipboardBean> clipboards = new CopyOnWriteArrayList<>();
   private final AtomicInteger nextId = new AtomicInteger(1);
   private final Map<String, LandmarkLog> landmarkLogs = new ConcurrentHashMap<>();

   public ClipboardDAO(MetropolisPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean storeClipboard(String fileHash, String fileName, Direction direction, SchematicConfig config, Cartesian2D size) {
      if (config == null) {
         Minions.w(String.format("Schematic '%s' has no valid config file.", fileName));
         return false;
      }

      if (config.getContext() == null) {
         Minions.w(String.format("Schematic '%s' has no valid context in config", fileName));
         return false;
      }

      if (config.getSchematicType() == null) {
         Minions.w(String.format("Schematic '%s' has no valid SchematicType in config", fileName));
         return false;
      }

      List<ContextType> contexts = config.getContext();

      // A directional cutout (cutoutN/E/S/W) only makes sense on a
      // schematic guaranteed a road on every side -- see SchematicConfig's
      // own javadoc on those four fields. Landmarks are the only
      // schematic type that guarantee actually holds for: an ordinary
      // BUILD/FILLER placed via District.placeRandomBuild() only ever has
      // a road confirmed on whichever side(s) District.findRoad() found
      // for THAT particular placement, so a cutout on one of the other
      // sides would open straight into a wall with no road behind it.
      // Rather than trust every author to remember that by hand, a
      // schematic that sets ANY of the four is routed to
      // ContextType.LANDMARK exclusively right here -- its own declared
      // `contexts` (and the DENSERESIDENTIAL/DENSERESIDENTIAL_PRIMARY
      // auto-mirrors below, which key off those same `contexts`) are
      // never stored at all, so it can never be selected by ordinary
      // district carving no matter what context array it also lists.
      boolean hasDirectionalCutout = config.getCutoutN().length > 0
              || config.getCutoutE().length > 0
              || config.getCutoutS().length > 0
              || config.getCutoutW().length > 0;
      boolean hasCreekOrigin = config.getRiverStartBoundsN().length > 0
              || config.getRiverStartBoundsE().length > 0
              || config.getRiverStartBoundsS().length > 0
              || config.getRiverStartBoundsW().length > 0;
      if (config.isLandmark() || hasDirectionalCutout || hasCreekOrigin) {
         this.storeClipboard(fileHash, fileName, direction, ContextType.LANDMARK, config.getSchematicType(), config.getRoadType(), size);
         return true;
      }

      for (ContextType context : contexts) {
         this.storeClipboard(fileHash, fileName, direction, context, config.getSchematicType(), config.getRoadType(), size);
      }

      // DENSERESIDENTIAL BUILD pool relies wholly on the explicit
      // "DENSERESIDENTIAL" context key -- a BUILD schematic only ends up
      // there because its OWN CONFIG.json lists it (handled by the
      // ordinary per-context loop above, same as any other context; no
      // special-casing needed here for that). There used to also be an
      // auto-mirror step that pulled in any BUILD schematic whose context
      // was exclusively RESIDENTIAL (nothing else), with zero opt-in --
      // removed, since it meant the DENSERESIDENTIAL BUILD pool was being
      // populated from two different, overlapping definitions of
      // "eligible" at once. See PluginConfig.isDenseResidentialEnabled().
      //
      // FILLER keeps its auto-mirror, unconditionally: any filler already
      // eligible for RESIDENTIAL (plazas, meadows, parks, whether or not
      // it's also tagged LOWRISE) stays exactly as eligible for
      // DENSERESIDENTIAL. Filler is generic, low-stakes infill by design
      // (see PluginConfig.isFillerExemptFromCloneRadius()'s javadoc);
      // restricting it here would only starve DENSERESIDENTIAL of
      // gap-filling options for no benefit, since "less filler overall"
      // is already achieved by this context's clone-radius exemption in
      // District, not by narrowing FILLER's own pool.
      if (PluginConfig.isDenseResidentialEnabled() && contexts.contains(ContextType.RESIDENTIAL)
              && config.getSchematicType() == SchematicType.FILLER) {
         this.storeClipboard(fileHash, fileName, direction, ContextType.DENSERESIDENTIAL, config.getSchematicType(), config.getRoadType(), size);
      }

      // DENSERESIDENTIAL_PRIMARY, unlike the auto-derived mirroring above,
      // is a deliberate, hand-authored opt-in: a schematic lands here only
      // when its OWN CONFIG.json explicitly lists "DENSERESIDENTIAL" in its
      // context array -- checked directly against `contexts` as originally
      // declared, never against anything this method itself added. No
      // "strictly residential" bar either: an author marking something
      // DENSERESIDENTIAL on purpose is trusted to mean it, even if the same
      // file is also tagged LOWRISE or anything else. Scoped to BUILD only
      // -- this tier exists to guarantee actual houses get placed (see
      // District.placeRandomBuild()'s primary-tier lookup), not to expand
      // the filler pool, which already isn't restricted at all here.
      if (PluginConfig.isDenseResidentialEnabled() && contexts.contains(ContextType.DENSERESIDENTIAL)
              && config.getSchematicType() == SchematicType.BUILD) {
         this.storeClipboard(fileHash, fileName, direction, ContextType.DENSERESIDENTIAL_PRIMARY, config.getSchematicType(), config.getRoadType(), size);
      }

      return true;
   }

   public void storeClipboard(
           String fileHash, String fileName, Direction direction, ContextType context, SchematicType schematicType, RoadType roadType, Cartesian2D size
   ) {
      ClipboardBean bean = new ClipboardBean();
      bean.setId(this.nextId.getAndIncrement());
      bean.setContext(context);
      bean.setDirection(direction);
      bean.setFileHash(fileHash);
      bean.setSize(size);
      bean.setFileName(fileName);
      bean.setRoadType(roadType);
      bean.setSchematicType(schematicType);
      this.clipboards.add(bean);
   }

   public boolean containsHash(String hash) {
      for (ClipboardBean bean : this.clipboards) {
         if (Objects.equals(bean.getFileHash(), hash)) {
            return true;
         }
      }
      return false;
   }

   public List<String> findAllClipboardHashes(Cartesian2D size, ContextType context, SchematicType schematicType, Direction direction) {
      return this.getHashes(this.findAllClipboards(size, context, schematicType, direction));
   }

   public List<ClipboardBean> findAllClipboards(Cartesian2D size, ContextType context, SchematicType schematicType, Direction direction) {
      List<ClipboardBean> result = new ArrayList<>();
      for (ClipboardBean bean : this.clipboards) {
         if (bean.getSize_x() == size.X && bean.getSize_y() == size.Y
                 && bean.getContext() == context && bean.getDirection() == direction
                 && bean.getSchematicType() == schematicType) {
            result.add(bean);
         }
      }
      return result;
   }

   public List<String> findAllClipboardHashes(Cartesian2D size, SchematicType schematicType, Direction direction) {
      return this.getHashes(this.findAllClipboards(size, schematicType, direction));
   }

   /**
    * Size-independent lookup used only by DENSERESIDENTIAL's faux-family
    * district. The caller deliberately filters by family first and by
    * physical fit second, so a 3x3 Capecod variant remains available when
    * the current partition is 4x4, rather than disappearing because the
    * lookup demanded an exact 4x4 entry.
    */
   public List<String> findAllClipboardHashes(ContextType context, SchematicType schematicType, Direction direction) {
      return this.getHashes(this.findAllClipboards(context, schematicType, direction));
   }

   public List<ClipboardBean> findAllClipboards(ContextType context, SchematicType schematicType, Direction direction) {
      List<ClipboardBean> result = new ArrayList<>();
      for (ClipboardBean bean : this.clipboards) {
         if ((context == null || bean.getContext() == context) && bean.getDirection() == direction && bean.getSchematicType() == schematicType) {
            result.add(bean);
         }
      }
      return result;
   }

   public List<ClipboardBean> findAllClipboards(Cartesian2D size, SchematicType schematicType, Direction direction) {
      List<ClipboardBean> result = new ArrayList<>();
      for (ClipboardBean bean : this.clipboards) {
         if (bean.getSize_x() == size.X && bean.getSize_y() == size.Y
                 && bean.getDirection() == direction && bean.getSchematicType() == schematicType) {
            result.add(bean);
         }
      }
      return result;
   }

   /**
    * Finds candidate chunk sizes for landmark districts -- schematics whose
    * own footprint exceeds the normal blockSize cap in at least one
    * dimension, but still small enough to fit within the region currently
    * being carved (maxFit). Direction is deliberately NOT filtered here:
    * a landmark's exact placement direction depends on which side(s) of
    * the carved region end up road-adjacent, which isn't known until after
    * a size is chosen and the region is carved -- see
    * UrbanGrid.tryPlaceLandmark(). Callers re-query via the normal getFit()
    * once a size and direction are both known, to reuse its existing
    * weighting/selection logic rather than duplicating it here.
    *
    * This is purely a "does a landmark of roughly this size exist here"
    * probe, not a final selection, so only the sizes are returned (one
    * entry per stored schematic, duplicates included, exactly as before).
    */
   public List<Cartesian2D> findLandmarkSizes(int minChunkSize, ContextType context, SchematicType schematicType, Cartesian2D maxFit) {
      List<Cartesian2D> sizes = new ArrayList<>();
      for (ClipboardBean bean : this.clipboards) {
         if (bean.getContext() == context && bean.getSchematicType() == schematicType
                 && bean.getSize_x() <= maxFit.X && bean.getSize_y() <= maxFit.Y
                 && (bean.getSize_x() >= minChunkSize || bean.getSize_y() >= minChunkSize)) {
            sizes.add(bean.getSize());
         }
      }
      return sizes;
   }

   // ---------------------------------------------------------------- landmark placements

   /**
    * All groupIds (deduplicated) already logged as landmark placements
    * within landmarkCloneRadius grid TILES of (tileX, tileZ) in this world
    * -- see PluginConfig.getLandmarkCloneRadius(). One call per landmark
    * attempt, returning the full nearby set to filter candidates against in
    * memory (same pattern UrbanGrid already uses for the ordinary
    * cloneRadius/nearbyGroupIds check).
    *
    * The bounds are inclusive on both sides (infinity norm / square
    * neighbourhood), same distance convention as cloneRadius elsewhere in
    * this codebase, just in tile-sized steps instead of chunk-sized ones.
    */
   public Set<String> findNearbyLandmarkGroupIds(String worldName, int tileX, int tileZ, int landmarkCloneRadius) {
      int span = landmarkCloneRadius * Grid.GRID_SIZE;
      return this.landmarkLog(worldName).nearby(tileX, tileZ, span);
   }

   /**
    * Logs a successfully carved landmark for future findNearbyLandmarkGroupIds()
    * checks -- called once, right after UrbanGrid.carveLandmark() commits.
    * tileX/tileZ are the grid TILE's own origin (Grid.root, a multiple of
    * Grid.GRID_SIZE), not the landmark's exact chunk position.
    */
   public void storeLandmarkPlacement(String worldName, String groupId, int tileX, int tileZ) {
      this.landmarkLog(worldName).add(groupId, tileX, tileZ);
   }

   private LandmarkLog landmarkLog(String worldName) {
      return this.landmarkLogs.computeIfAbsent(worldName, name -> {
         World world = Bukkit.getWorld(name);
         File worldFolder = world != null ? world.getWorldFolder() : new File(Bukkit.getWorldContainer(), name);
         return new LandmarkLog(new File(new File(worldFolder, "metropolis"), "landmark-placements.tsv"));
      });
   }

   /**
    * One world's landmark placements: an in-memory list backed by an
    * append-only text file (groupId, tileX, tileZ, tab-separated, one per
    * line) inside the world's own folder. Loaded once, on first use.
    */
   private static final class LandmarkLog {
      private static final class Entry {
         final String groupId;
         final int tileX;
         final int tileZ;

         Entry(String groupId, int tileX, int tileZ) {
            this.groupId = groupId;
            this.tileX = tileX;
            this.tileZ = tileZ;
         }
      }

      private final File file;
      private final List<Entry> entries = new ArrayList<>();

      LandmarkLog(File file) {
         this.file = file;
         if (!file.isFile()) {
            return;
         }
         try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
               String[] parts = line.split("\t");
               if (parts.length != 3) {
                  continue;
               }
               try {
                  this.entries.add(new Entry(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
               } catch (NumberFormatException e) {
                  Minions.w("Skipping malformed landmark placement line in %s", file.getPath());
               }
            }
         } catch (IOException e) {
            Minions.w("Couldn't read landmark placements from %s: %s", file.getPath(), e.getMessage());
         }
      }

      synchronized Set<String> nearby(int tileX, int tileZ, int span) {
         Set<String> groupIds = new HashSet<>();
         for (Entry entry : this.entries) {
            if (entry.tileX >= tileX - span && entry.tileX <= tileX + span
                    && entry.tileZ >= tileZ - span && entry.tileZ <= tileZ + span) {
               groupIds.add(entry.groupId);
            }
         }
         return groupIds;
      }

      synchronized void add(String groupId, int tileX, int tileZ) {
         this.entries.add(new Entry(groupId, tileX, tileZ));
         try {
            File folder = this.file.getParentFile();
            if (folder != null) {
               Files.createDirectories(folder.toPath());
            }
            String safeId = groupId.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            String line = safeId + "\t" + tileX + "\t" + tileZ + "\n";
            Files.write(this.file.toPath(), line.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
         } catch (IOException e) {
            // Still logged in memory, so this session's check is right; only a restart forgets it.
            Minions.w("Couldn't persist landmark placement to %s: %s", this.file.getPath(), e.getMessage());
         }
      }
   }

   // ---------------------------------------------------------------- roads / housekeeping

   public List<ClipboardBean> findAllClipboardRoads(RoadType roadType) {
      List<ClipboardBean> result = new ArrayList<>();
      for (ClipboardBean bean : this.clipboards) {
         if (bean.getRoadType() == roadType) {
            result.add(bean);
         }
      }
      return result;
   }

   public List<String> findAllClipboardRoadHashes(RoadType roadType) {
      return this.getHashes(this.findAllClipboardRoads(roadType));
   }

   public List<String> findAllClipboardHashes() {
      return this.getHashes(this.clipboards);
   }

   /** Removes every entry whose hash starts with `hash` (prefix match, as the old LIKE 'hash%' delete did). */
   public boolean deleteClipboardHashes(String hash) {
      this.clipboards.removeIf(bean -> bean.getFileHash() != null && bean.getFileHash().startsWith(hash));
      return true;
   }

   private List<String> getHashes(Iterable<ClipboardBean> beans) {
      List<String> hashes = new LinkedList<>();
      for (ClipboardBean bean : beans) {
         hashes.add(bean.getFileHash());
      }
      return hashes;
   }
}
