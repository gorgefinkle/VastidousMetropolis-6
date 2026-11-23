package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.grid.common.Factory;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.WorldEditPlugin;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;

public class ClipboardProviderDB implements ClipboardProvider {
   private static final String WE_PLUGIN = "WorldEdit";
   private static final String SCHEMATIC_FOLDER = "schematics";
   private static final String CACHE_FOLDER = "cache";
   private WorldEdit worldEdit;
   private ClipboardDAO dao;
   private Map<String, Clipboard> clipstore = new HashMap<>();
   private boolean isLoaded = false;

   // ------------------------------------------------------------------
   // Query result caches. getFit()/getRoadFit()/getLandmarkSizes() were
   // each hitting SQLite (via Ebean) on EVERY call -- and getFit() in
   // particular is called on every single placement attempt across the
   // ENTIRE recursive district-partition tree, not occasionally. A server
   // thread dump confirmed this directly: the main thread was repeatedly
   // stuck cycling through Ebean's own per-query debug overhead
   // (DefaultServer.createCallStack(), which captures a full stack trace
   // for every query) and the actual SQLite round-trip
   // (NativeDB.step/reset), all reached through
   // ClipboardDAO.findAllClipboardHashes() <- ClipboardProviderDB.getFit()
   // <- District.placeRandomBuild()/placeRandomBuildForSure().
   //
   // The schematic library is fully static once loadClips() finishes --
   // nothing ever adds, removes, or changes a cached Clipboard afterward
   // -- so every one of these queries' results is deterministic and
   // permanently valid for the rest of the server's run. There's no
   // reason to ever ask the database the same question twice; the first
   // answer is cached in memory (a ConcurrentHashMap, since Bukkit's
   // chunk-populate machinery can invoke this from more than one thread)
   // and reused for every subsequent identical query.
   //
   // IMPORTANT: District.placeRandomRec() and UrbanGrid.carveLandmark()
   // both call clips.removeIf(...) directly on the list a getFit() call
   // returns, as part of their hard-duplicate-rejection retry logic. If
   // getFit() ever handed back the actual list object stored in these
   // caches, that removeIf() would permanently strip a candidate from the
   // SHARED cached result the first time a duplicate got rejected --
   // silently corrupting every future query for that same key, forever,
   // for every district on the map. Every method below stores the built
   // list in its cache, then returns a FRESH COPY of it -- on every call,
   // hit or miss -- so callers are always free to mutate their own copy
   // without touching what's cached.
   private final Map<RoadType, List<Clipboard>> roadFitCache = new ConcurrentHashMap<>();
   private final Map<FitKey, List<Clipboard>> fitCache = new ConcurrentHashMap<>();
   private final Map<OceanFitKey, List<Clipboard>> oceanFitCache = new ConcurrentHashMap<>();
   private final Map<AllKey, List<Clipboard>> allCache = new ConcurrentHashMap<>();
   private final Map<LandmarkSizeKey, List<Cartesian2D>> landmarkSizeCache = new ConcurrentHashMap<>();

   @Override
   public void loadClips(MetropolisPlugin plugin) throws FileNotFoundException {
      if (!this.assertWorldEditLoaded()) {
         throw new ClipboardProviderDB.PluginNotFoundException("Couldn't find WorldEdit plugin.");
      }

      this.dao = new ClipboardDAO(plugin);
      File pluginFolder = plugin.getDataFolder();
      Minions.i("looking for PluginFolder");
      if (!pluginFolder.isDirectory()) {
         pluginFolder.mkdir();
      }

      ClipboardImporter importer = new ClipboardImporter();
      importer.importSchematics();
      ClipboardImporter var10 = null;
      ClipboardLoader loader = Factory.getDefaultLoader(this.dao);
      this.clipstore = loader.loadSchematics(null, null);
      Minions.i("loaded clips");

      for (String clip : this.clipstore.keySet()) {
         Minions.d("clipstore: " + clip);
      }

      for (String clip : this.dao.findAllClipboardHashes()) {
         Minions.d("dao hash: " + clip);
      }

      this.isLoaded = true;
   }

   @Override
   public List<Clipboard> getRoadFit(RoadType roadType) {
      if (!this.isLoaded) {
         Minions.w("Schematics not loaded!");
      }

      List<Clipboard> cached = this.roadFitCache.get(roadType);
      if (cached == null) {
         List<Clipboard> clips = new LinkedList<>();
         for (String hash : this.dao.findAllClipboardRoadHashes(roadType)) {
            clips.add(this.clipstore.get(hash));
         }
         this.roadFitCache.put(roadType, clips);
         cached = clips;
      }

      return new LinkedList<>(cached);
   }

   @Override
   public List<Clipboard> getRoadFit(RoadType roadType, ContextType contextType) {
      return this.getRoadFit(roadType);
   }

   @Override
   public List<Clipboard> getFit(Cartesian2D size, ContextType contextType, SchematicType schematicType, Direction direction) {
      if (!this.isLoaded) {
         Minions.w("Schematics not loaded!");
      }

      FitKey key = new FitKey(size.X, size.Y, contextType, schematicType, direction);
      List<Clipboard> cached = this.fitCache.get(key);
      if (cached == null) {
         List<Clipboard> clips = new LinkedList<>();
         for (String hash : this.dao.findAllClipboardHashes(size, contextType, schematicType, direction)) {
            clips.add(this.clipstore.get(hash));
         }
         this.fitCache.put(key, clips);
         cached = clips;
      }

      return new LinkedList<>(cached);
   }

   @Override
   public List<Clipboard> getAll(ContextType contextType, SchematicType schematicType, Direction direction) {
      if (!this.isLoaded) {
         Minions.w("Schematics not loaded!");
      }

      AllKey key = new AllKey(contextType, schematicType, direction);
      List<Clipboard> cached = this.allCache.get(key);
      if (cached == null) {
         List<Clipboard> clips = new LinkedList<>();
         for (String hash : this.dao.findAllClipboardHashes(contextType, schematicType, direction)) {
            Clipboard clip = this.clipstore.get(hash);
            if (clip != null) {
               clips.add(clip);
            }
         }
         this.allCache.put(key, clips);
         cached = clips;
      }

      return new LinkedList<>(cached);
   }

   @Override
   public List<Clipboard> getFitForOcean(Cartesian2D size, String depthTier, SchematicType schematicType, Direction direction) {
      if (!this.isLoaded) {
         Minions.w("Schematics not loaded!");
      }

      // Its own dedicated cache/key -- NOT FitKey. FitKey's shape here would
      // have been (size.X, size.Y, null, schematicType, direction), which is
      // EXACTLY what the 4-arg getFit(size, schematicType, roadDir) below
      // already builds for its own context-free (road) lookups -- same size,
      // same schematicType, same direction is a completely realistic
      // collision (e.g. a 1x1 BUILD with Direction.NONE, which boats always
      // request). The two caches would have silently overwritten each
      // other's entries. depthTier has to be part of the key too: it was
      // missing entirely before, so the tier of whichever call happened to
      // run first "won" the cache for every (size,type,direction) triple and
      // every other tier kept reading that same, wrong, stale list back.
      OceanFitKey key = new OceanFitKey(size.X, size.Y, depthTier, schematicType, direction);
      List<Clipboard> cached = this.oceanFitCache.get(key);
      if (cached == null) {
         List<Clipboard> clips = new LinkedList<>();
         // Scoped to ContextType.OCEAN, not the context-free query the
         // generic getFit(size, schematicType, roadDir) below uses -- an
         // ordinary land building never tagged for the ocean at all (which
         // is every schematic that predates this feature) has no depthTier
         // set, and this method used to treat a null configTier as "matches
         // any tier requested" with NO context filter ahead of it, so any
         // such building was a valid "boat" candidate in open water. Scoping
         // to OCEAN first means only schematics someone actually tagged for
         // the ocean are ever considered; a null depthTier among THOSE is a
         // reasonable "works at any depth" boat, not an accident.
         // Size is deliberately NOT part of the DB query: the importer stores
         // (width >> 4, length >> 4), so a boat that isn't exactly 16-31
         // blocks on both axes never had a (1,1) row and could never match.
         // OceanBoatPlacer checks the real footprint against the water itself.
         for (String hash : this.dao.findAllClipboardHashes(ContextType.OCEAN, schematicType, direction)) {
            Clipboard clip = this.clipstore.get(hash);
            if (clip != null) {
               if (oceanTierMatches(clip.getConfig().getDepthTier(), depthTier)) {
                  clips.add(clip);
               }
            }
         }
         this.oceanFitCache.put(key, clips);
         cached = clips;
      }

      return new LinkedList<>(cached);
   }

   /**
    * Case-insensitive depthTier match. CONFIG.json uses MID/DEEP/SHALLOW while
    * OceanDepthTier strings are shore/mid/deep, so SHALLOW is treated as
    * SHORE. A missing tier means "any depth".
    */
   private static boolean oceanTierMatches(String configTier, String requestedTier) {
      if (configTier == null || configTier.trim().isEmpty()) {
         return true;
      }
      String c = configTier.trim();
      if (c.equalsIgnoreCase("shallow")) {
         c = "shore";
      }
      return c.equalsIgnoreCase(requestedTier);
   }

   @Override
   public List<Clipboard> getFit(Cartesian2D size, SchematicType schematicType, Direction roadDir) {
      if (!this.isLoaded) {
         Minions.w("Schematics not loaded!");
      }

      // Same cache/key shape as the 4-arg getFit() above, with contextType
      // simply left null -- FitKey's equals()/hashCode() treat that as its
      // own distinct, stable key (via Objects.equals/Objects.hashCode,
      // both null-safe), so context-free queries and context-scoped ones
      // never collide with each other.
      FitKey key = new FitKey(size.X, size.Y, null, schematicType, roadDir);
      List<Clipboard> cached = this.fitCache.get(key);
      if (cached == null) {
         List<Clipboard> clips = new LinkedList<>();
         for (String hash : this.dao.findAllClipboardHashes(size, schematicType, roadDir)) {
            clips.add(this.clipstore.get(hash));
         }
         this.fitCache.put(key, clips);
         cached = clips;
      }

      return new LinkedList<>(cached);
   }

   @Override
   public List<Cartesian2D> getLandmarkSizes(int minChunkSize, ContextType context, SchematicType schematicType, Cartesian2D maxFit) {
      if (!this.isLoaded) {
         Minions.w("Schematics not loaded!");
      }

      LandmarkSizeKey key = new LandmarkSizeKey(minChunkSize, context, schematicType, maxFit.X, maxFit.Y);
      List<Cartesian2D> cached = this.landmarkSizeCache.get(key);
      if (cached == null) {
         // Dedup by value: a schematic with multiple direction rotations
         // produces one DB row per rotation, all sharing the same size.
         // Without dedup a building with 4 rotations would be 4x as
         // likely to have its size picked as one with a single rotation
         // -- rotation availability is re-checked later via getFit() with
         // the actual computed direction anyway. Cartesian2D has no
         // equals()/hashCode(), so a Set<Cartesian2D> would dedup by
         // object identity and do nothing here -- key on the packed X/Y
         // pair instead.
         List<Cartesian2D> raw = this.dao.findLandmarkSizes(minChunkSize, context, schematicType, maxFit);
         Set<Long> seen = new LinkedHashSet<>();
         List<Cartesian2D> distinct = new ArrayList<>();
         for (Cartesian2D size : raw) {
            long packed = (((long) size.X) << 32) | (size.Y & 0xFFFFFFFFL);
            if (seen.add(packed)) {
               distinct.add(size);
            }
         }
         this.landmarkSizeCache.put(key, distinct);
         cached = distinct;
      }

      return new ArrayList<>(cached);
   }

   @Override
   public Set<String> findNearbyLandmarkGroupIds(String worldName, int tileX, int tileZ, int landmarkCloneRadius) {
      // Deliberately NOT cached, unlike getLandmarkSizes()/getFit() above --
      // this reflects a small, rarely-hit, and constantly-changing placement
      // log (every landmark carved anywhere invalidates it), not the static
      // schematic library those caches are built for. Landmark attempts are
      // rare enough (gated by landmarkAttemptChancePercent/landmarkMaxPerGrid)
      // that a plain per-call query is in no danger of repeating the
      // getFit() hot-path problem described above.
      return this.dao.findNearbyLandmarkGroupIds(worldName, tileX, tileZ, landmarkCloneRadius);
   }

   @Override
   public void storeLandmarkPlacement(String worldName, String groupId, int tileX, int tileZ) {
      this.dao.storeLandmarkPlacement(worldName, groupId, tileX, tileZ);
   }

   private boolean assertWorldEditLoaded() {
      WorldEditPlugin worldEditPlugin = null;
      PluginManager pm = Bukkit.getServer().getPluginManager();
      worldEditPlugin = (WorldEditPlugin)pm.getPlugin("WorldEdit");
      if (worldEditPlugin == null) {
         Minions.w("No WorldEdit found!");
         return false;
      }

      if (!pm.isPluginEnabled(worldEditPlugin)) {
         pm.enablePlugin(worldEditPlugin);
      }

      this.worldEdit = worldEditPlugin.getWorldEdit();
      return true;
   }

   public static class PluginNotFoundException extends RuntimeException {
      public PluginNotFoundException(String message) {
         super(message);
      }
   }

   /**
    * Cache key for both getFit() overloads. Stores size.X/size.Y as plain
    * ints rather than holding the Cartesian2D object itself -- Cartesian2D
    * has no equals()/hashCode() (confirmed elsewhere in this codebase,
    * e.g. getLandmarkSizes()'s manual dedup above), so using it directly
    * as or within a HashMap key would silently fall back to identity
    * equality and never actually hit the cache. contextType is nullable
    * (the 3-arg, context-free getFit() overload passes null) and handled
    * null-safely throughout via Objects.equals()/Objects.hashCode().
    */
   private static final class AllKey {
      private final ContextType contextType;
      private final SchematicType schematicType;
      private final Direction direction;

      private AllKey(ContextType contextType, SchematicType schematicType, Direction direction) {
         this.contextType = contextType;
         this.schematicType = schematicType;
         this.direction = direction;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (!(o instanceof AllKey)) {
            return false;
         }
         AllKey other = (AllKey) o;
         return this.contextType == other.contextType
                 && this.schematicType == other.schematicType
                 && this.direction == other.direction;
      }

      @Override
      public int hashCode() {
         return Objects.hash(this.contextType, this.schematicType, this.direction);
      }
   }

   private static final class FitKey {
      private final int sizeX;
      private final int sizeY;
      private final ContextType contextType;
      private final SchematicType schematicType;
      private final Direction direction;

      private FitKey(int sizeX, int sizeY, ContextType contextType, SchematicType schematicType, Direction direction) {
         this.sizeX = sizeX;
         this.sizeY = sizeY;
         this.contextType = contextType;
         this.schematicType = schematicType;
         this.direction = direction;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (!(o instanceof FitKey)) {
            return false;
         }
         FitKey other = (FitKey) o;
         return this.sizeX == other.sizeX
                 && this.sizeY == other.sizeY
                 && this.contextType == other.contextType
                 && this.schematicType == other.schematicType
                 && this.direction == other.direction;
      }

      @Override
      public int hashCode() {
         return Objects.hash(this.sizeX, this.sizeY, this.contextType, this.schematicType, this.direction);
      }
   }

   /**
    * Cache key for getLandmarkSizes(). Same reasoning as FitKey -- stores
    * maxFit.X/maxFit.Y as plain ints rather than holding the Cartesian2D
    * object, since Cartesian2D has no value-based equals()/hashCode().
    */
   private static final class LandmarkSizeKey {
      private final int minChunkSize;
      private final ContextType context;
      private final SchematicType schematicType;
      private final int maxFitX;
      private final int maxFitY;

      private LandmarkSizeKey(int minChunkSize, ContextType context, SchematicType schematicType, int maxFitX, int maxFitY) {
         this.minChunkSize = minChunkSize;
         this.context = context;
         this.schematicType = schematicType;
         this.maxFitX = maxFitX;
         this.maxFitY = maxFitY;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (!(o instanceof LandmarkSizeKey)) {
            return false;
         }
         LandmarkSizeKey other = (LandmarkSizeKey) o;
         return this.minChunkSize == other.minChunkSize
                 && this.maxFitX == other.maxFitX
                 && this.maxFitY == other.maxFitY
                 && this.context == other.context
                 && this.schematicType == other.schematicType;
      }

      @Override
      public int hashCode() {
         return Objects.hash(this.minChunkSize, this.context, this.schematicType, this.maxFitX, this.maxFitY);
      }
   }

   /**
    * Cache key for getFitForOcean() -- its own class rather than reusing
    * FitKey, since FitKey has no depthTier field and, with contextType left
    * null the way getFitForOcean() used to build one, is indistinguishable
    * from the 4-arg getFit(size, schematicType, roadDir)'s own key shape.
    * See getFitForOcean()'s own comment for what that collision meant.
    */
   private static final class OceanFitKey {
      private final int sizeX;
      private final int sizeY;
      private final String depthTier;
      private final SchematicType schematicType;
      private final Direction direction;

      private OceanFitKey(int sizeX, int sizeY, String depthTier, SchematicType schematicType, Direction direction) {
         this.sizeX = sizeX;
         this.sizeY = sizeY;
         this.depthTier = depthTier;
         this.schematicType = schematicType;
         this.direction = direction;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         }
         if (!(o instanceof OceanFitKey)) {
            return false;
         }
         OceanFitKey other = (OceanFitKey) o;
         return this.sizeX == other.sizeX
                 && this.sizeY == other.sizeY
                 && Objects.equals(this.depthTier, other.depthTier)
                 && this.schematicType == other.schematicType
                 && this.direction == other.direction;
      }

      @Override
      public int hashCode() {
         return Objects.hash(this.sizeX, this.sizeY, this.depthTier, this.schematicType, this.direction);
      }
   }
}
