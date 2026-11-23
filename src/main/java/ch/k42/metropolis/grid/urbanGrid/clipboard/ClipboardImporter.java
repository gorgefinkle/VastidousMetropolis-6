package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.extent.transform.BlockTransformExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BaseBlock;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClipboardImporter {
   private Map<String, SchematicConfig> configs;

   private void assertFolders() {
      File[] folders = new File[]{new File("plugins/Metropolis/import"), new File("plugins/Metropolis/cache"), new File("plugins/Metropolis/done")};

      for (File folder : folders) {
         if (!folder.isDirectory()) {
            folder.mkdirs();
         }
      }
   }

   private boolean loadSchematicConfigs() {
      this.configs = new HashMap<>();

      for (File file : Minions.findAllFilesRecursively("plugins/Metropolis/import", new ArrayList<>(), new FilenameFilter() {
         @Override
         public boolean accept(File dir, String name) {
            return name.endsWith(".json");
         }
      })) {
         SchematicConfig config = SchematicConfig.fromFile(file);
         if (config != null) {
            this.configs.put(file.getName(), config);

            for (String schem : config.getSchematics()) {
               this.configs.put(schem, config);
            }
         } else {
            Minions.w("Couldn't read config file: " + file.getName());
         }
      }

      return true;
   }

   private SchematicConfig findConfig(String name) {
      SchematicConfig config = this.configs.get(name);
      if (config == null) {
         config = this.configs.get(name + ".json");
      }

      if (config == null) {
         // The per-schematic JSON configs were written when these were
         // MCEdit ".schematic" files, and list them by that name (and are
         // often named after it). The library has since been converted to
         // ".schem", so also try the same name with the old extension, and
         // the bare "<name>.json" form.
         String base = name.endsWith(".schem") ? name.substring(0, name.length() - ".schem".length()) : name;
         config = this.configs.get(base + ".schematic");
         if (config == null) {
            config = this.configs.get(base + ".json");
         }
      }

      if (config == null) {
         Minions.w("Found no config for: " + name);
         return null;
      }

      return config;
   }

   private Clipboard loadClipboard(ClipboardFormat format, File file) throws IOException {
      // The reader closes the stream it wraps.
      try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
         return reader.read();
      }
   }

   /**
    * Applies a rotation/flip Transform to a clipboard, producing a
    * genuinely new, independent Clipboard rather than mutating the
    * original in place -- the old CuboidClipboard.rotate2D()/.flip()
    * mutated one shared instance across all 8 saves in a row, which was a
    * plausible source of the mirrored variants showing up in-game with
    * the unflipped structure's blocks still present underneath. Each call
    * here starts fresh from whatever clipboard it's given and hands back
    * a fully separate object, so there's no shared mutable state between
    * any of the rotation/flip steps at all.
    *
    * FlattenedClipboardTransform also correctly carries tile-entities
    * (chests, signs, etc.) and per-block facing/orientation data through
    * the transform automatically, which the old manual rotate2D()/flip()
    * never did natively -- one plausible additional contributor to past
    * mirroring artifacts, now handled by the library instead of by hand.
    */
   private Clipboard applyTransform(Clipboard source, AffineTransform transform) throws WorldEditException {
      // WorldEdit's own FlattenedClipboardTransform helper no longer lives
      // where the old code imported it from, so this does the same job
      // directly: every block is moved by the transform (applied about the
      // clipboard's origin), and its STATE is transformed by the same
      // Transform via BlockTransformExtent -- that is what turns a stair
      // facing north into one facing east, a door hinge left into right,
      // and so on, exactly as a WorldEdit //rotate or //flip would.
      //
      // The transforms used here are 90-degree turns and a mirror about an
      // integer origin, so every block lands exactly on a block position;
      // the rounding below only absorbs floating-point noise from
      // sin/cos (cos(90 degrees) comes out as 6e-17, not 0).
      Region region = source.getRegion();
      BlockVector3 origin = source.getOrigin();
      BlockVector3 min = region.getMinimumPoint();
      BlockVector3 max = region.getMaximumPoint();

      // The new bounding box: where the 8 corners of the old one land.
      BlockVector3[] corners = {
              BlockVector3.at(min.x(), min.y(), min.z()), BlockVector3.at(max.x(), min.y(), min.z()),
              BlockVector3.at(min.x(), max.y(), min.z()), BlockVector3.at(max.x(), max.y(), min.z()),
              BlockVector3.at(min.x(), min.y(), max.z()), BlockVector3.at(max.x(), min.y(), max.z()),
              BlockVector3.at(min.x(), max.y(), max.z()), BlockVector3.at(max.x(), max.y(), max.z())
      };
      BlockVector3 newMin = this.transformPosition(corners[0], origin, transform);
      BlockVector3 newMax = newMin;
      for (BlockVector3 corner : corners) {
         BlockVector3 moved = this.transformPosition(corner, origin, transform);
         newMin = newMin.getMinimum(moved);
         newMax = newMax.getMaximum(moved);
      }

      Clipboard target = new BlockArrayClipboard(new CuboidRegion(newMin, newMax));
      target.setOrigin(origin);

      BlockTransformExtent transformedStates = new BlockTransformExtent(source, transform);
      for (BlockVector3 position : region) {
         BaseBlock block = transformedStates.getFullBlock(position);
         target.setBlock(this.transformPosition(position, origin, transform), block);
      }

      return target;
   }

   /** Moves a block position by `transform`, applied about `origin`, back onto the block grid. */
   private BlockVector3 transformPosition(BlockVector3 position, BlockVector3 origin, AffineTransform transform) {
      Vector3 relative = position.subtract(origin).toVector3();
      Vector3 moved = transform.apply(relative);
      return origin.add((int)Math.round(moved.x()), (int)Math.round(moved.y()), (int)Math.round(moved.z()));
   }

   /**
    * Deletes the target file before saving, if it happens to already
    * exist. The cache folder itself already gets wiped and recreated
    * fresh at the top of rotateAndCacheSchematic(), so under normal
    * operation this file never actually exists yet -- kept as a
    * deliberate safety net regardless of the underlying clipboard API,
    * since it costs nothing and removes any possibility of a previous
    * save's bytes surviving into this one.
    */
   private void saveClean(ClipboardFormat format, Clipboard clipboard, File file) throws IOException {
      if (file.exists() && !file.delete()) {
         Minions.w("Could not delete stale cache file before re-saving: " + file.getAbsolutePath());
      }

      try (ClipboardWriter writer = format.getWriter(new FileOutputStream(file))) {
         writer.write(clipboard);
      }
   }

   /**
    * Bump this when what a cache folder CONTAINS changes (the rotation code, the
    * file format, the naming), and every existing cache is rebuilt once.
    */
   private static final String CACHE_VERSION = "26.2-1";

   /**
    * True if {@code cacheFolder} already holds a complete, current import of this
    * schematic, so it can be skipped. A cache folder is named by the MD5 of the
    * schematic's file name and bytes, so a changed schematic never matches an old
    * folder; this adds the three things the name does not cover: the config
    * (compared byte for byte with the cached copy), the format version, and that
    * every file this import should have produced is there and not empty.
    *
    * This replaces "re-import everything on every start". That used to be hidden by
    * moving the whole import folder away afterwards, and on Windows that move can
    * fail (antivirus, Explorer or an indexer holding a file open), which cost over
    * two minutes at every start.
    */
   private static boolean isCacheCurrent(File cacheFolder, String configPath, String... requiredFiles) {
      try {
         if (!cacheFolder.isDirectory()) {
            return false;
         }
         File version = new File(cacheFolder, "CACHE_VERSION");
         if (!version.isFile() || !CACHE_VERSION.equals(new String(Files.readAllBytes(version.toPath()), StandardCharsets.UTF_8).trim())) {
            return false;
         }
         File cachedConfig = new File(cacheFolder, "CONFIG.json");
         if (!cachedConfig.isFile() || !Arrays.equals(Files.readAllBytes(cachedConfig.toPath()), Files.readAllBytes(Paths.get(configPath)))) {
            return false;
         }
         for (String name : requiredFiles) {
            File required = new File(cacheFolder, name);
            if (!required.isFile() || required.length() == 0) {
               return false;
            }
         }
         return true;
      } catch (IOException e) {
         return false;
      }
   }

   /** Written LAST, so a folder whose import was interrupted never looks complete. */
   private static void markCacheComplete(File cacheFolder) throws IOException {
      Files.write(new File(cacheFolder, "CACHE_VERSION").toPath(), CACHE_VERSION.getBytes(StandardCharsets.UTF_8));
   }

   private boolean rotateAndCacheSchematic(File schematicFile) throws IOException, NoSuchAlgorithmException, WorldEditException {
      ClipboardFormat format = ClipboardFormats.findByFile(schematicFile);
      if (format == null) {
         Minions.w("Unrecognised schematic format for '" + schematicFile.getName() + "'!");
         return false;
      }

      SchematicConfig config = this.findConfig(schematicFile.getName());
      if (config == null) {
         Minions.w("No config found for '" + schematicFile.getName() + "'!");
         return false;
      }

      String configPath = config.getPath();
      File cacheFolder = new File("plugins/Metropolis/cache", Minions.getMD5Checksum(schematicFile));
      boolean mirrored = PluginConfig.isFlippedVariantsEnabled() && !config.getNeverFlip();
      String[] expected = mirrored
              ? new String[]{"NORTH.schem", "EAST.schem", "SOUTH.schem", "WEST.schem",
                             "NORTH_M.schem", "EAST_M.schem", "SOUTH_M.schem", "WEST_M.schem"}
              : new String[]{"NORTH.schem", "EAST.schem", "SOUTH.schem", "WEST.schem"};
      if (isCacheCurrent(cacheFolder, configPath, expected)) {
         Minions.d("Cache for '%s' is up to date, skipping it.", schematicFile.getName());
         return true;
      }
      if (cacheFolder.isDirectory()) {
         deleteDirectory(cacheFolder);
      }

      if (!cacheFolder.mkdirs()) {
         Minions.w("Cannot create directory '" + cacheFolder.getName() + "'");
         return false;
      } else {
         File configFile = new File(cacheFolder, "CONFIG.json");
         Minions.d("Copying config from " + Paths.get(configPath).toString() + " to " + configFile.toPath());
         Files.copy(Paths.get(configPath), cacheFolder.toPath().resolve("CONFIG.json"));
         File northFile = new File(cacheFolder, "NORTH.schem");
         File eastFile = new File(cacheFolder, "EAST.schem");
         File southFile = new File(cacheFolder, "SOUTH.schem");
         File westFile = new File(cacheFolder, "WEST.schem");
         // "_M" files are the mirrored counterpart of each rotation --
         // same 4 world-facing directions, but flipped left-to-right along
         // the road-facing edge, so a schematic's door/entrance point ends
         // up on the opposite side of that edge. Doubles variety per
         // schematic (4 rotations -> 8 total variants) without needing any
         // additional source files. Only generated when
         // PluginConfig.isFlippedVariantsEnabled() is on AND this
         // schematic's own CONFIG.json doesn't set "neverFlip": true (see
         // SchematicConfig.getNeverFlip() -- for buildings with large,
         // readable text/signage baked into the geometry, which reads
         // backwards once mirrored). See ClipboardParcel.makeCutoutsToward()
         // for how the cutout offset math accounts for the mirror on the
         // placement side.
         File northMirroredFile = new File(cacheFolder, "NORTH_M.schem");
         File eastMirroredFile = new File(cacheFolder, "EAST_M.schem");
         File southMirroredFile = new File(cacheFolder, "SOUTH_M.schem");
         File westMirroredFile = new File(cacheFolder, "WEST_M.schem");

         Clipboard original = this.loadClipboard(format, schematicFile);

         // rotateY(-90), NOT +90. This was the actual bug behind the whole
         // "flipped/facing wrong way" investigation -- it had nothing to
         // do with the mirror axis at all. A 180-degree turn lands at the
         // same place regardless of rotation direction (clockwise twice or
         // counterclockwise twice both end up facing opposite), but a
         // single 90-degree turn absolutely depends on direction. With
         // rotateY(90) (the wrong sign), the save sequence below --
         // north (unrotated) -> rotate+save as "east" -> rotate+save as
         // "south" -> rotate+save as "west" -- produced a NORTH file that
         // was correct, a SOUTH file that was also correct (180 degrees
         // either direction lands the same), but "east" and "west" files
         // that were each other's actual facing: EAST.schematic faced
         // west, WEST.schematic faced east. That exactly matches the
         // observed symptom (north/south fine, east/west swapped) and
         // explains why it happened uniformly regardless of whether
         // mirroring was even enabled -- this bug is in the PLAIN rotation
         // chain every schematic goes through, mirrored or not.
         AffineTransform rotate90 = new AffineTransform().rotateY(-90);

         Clipboard north = original;
         this.saveClean(format, north, northFile);
         Clipboard east = this.applyTransform(north, rotate90);
         this.saveClean(format, east, eastFile);
         Clipboard south = this.applyTransform(east, rotate90);
         this.saveClean(format, south, southFile);
         Clipboard west = this.applyTransform(south, rotate90);
         this.saveClean(format, west, westFile);

         if (PluginConfig.isFlippedVariantsEnabled() && !config.getNeverFlip()) {
            // A fresh, independent load from the source file -- not a
            // continuation of the plain-rotation chain above. Starting
            // from the actual pristine schematic on disk means the flip
            // branch shares zero state with the rotation branch: this
            // clipboard has never been touched by anything else at all.
            Clipboard mirroredOriginal = this.loadClipboard(format, schematicFile);
            // Mirrors the local X axis (west-east), not Z (north-south) --
            // confirmed against actual in-game output. scale(-1, 1, 1)
            // keeps the mirrored variant facing the same way as its
            // unmirrored counterpart while swapping which side the
            // door/entrance sits on.
            AffineTransform mirror = new AffineTransform().scale(-1, 1, 1);

            Clipboard northM = this.applyTransform(mirroredOriginal, mirror);
            this.saveClean(format, northM, northMirroredFile);
            Clipboard eastM = this.applyTransform(northM, rotate90);
            this.saveClean(format, eastM, eastMirroredFile);
            Clipboard southM = this.applyTransform(eastM, rotate90);
            this.saveClean(format, southM, southMirroredFile);
            Clipboard westM = this.applyTransform(southM, rotate90);
            this.saveClean(format, westM, westMirroredFile);
         }

         markCacheComplete(cacheFolder);
         return true;
      }
   }

   /**
    * True if this street/highway schematic's buildName or raw filename
    * marks it as a damaged variant (rubble, holes, wreckage) rather than
    * a clean one. The asset pack uses four consistent suffixes wherever a
    * piece has damage variants at all -- CLEAR, COLLAPSED, DEEP, OBSTRUCT
    * (e.g. HIGHWAY_SIDE_N_OBSTRUCT.json, STREET_I_EW_COLLAPSED.json) --
    * some base pieces (HIGHWAY_C_*, STREET_T_*, STREET_X) have no variant
    * suffix at all and are always clean already, so they're correctly
    * left alone by this check regardless.
    *
    * Originally only checked for "OBSTRUCT" specifically, which missed
    * COLLAPSED and DEEP entirely -- those are just as much "not clean" as
    * OBSTRUCT, so clearStreetsOnly was still letting two-thirds of the
    * damaged variants straight through.
    */
   private static final String[] DAMAGED_STREET_MARKERS = {"OBSTRUCT", "COLLAPSED", "DEEP"};

   private boolean isDamagedStreetVariant(SchematicConfig config, File file) {
      String buildName = config.getBuildName();
      String upperBuildName = buildName != null ? buildName.toUpperCase() : null;
      String upperFileName = file.getName().toUpperCase();

      for (String marker : DAMAGED_STREET_MARKERS) {
         if ((upperBuildName != null && upperBuildName.contains(marker)) || upperFileName.contains(marker)) {
            return true;
         }
      }

      return false;
   }

   private boolean cacheStreet(File file) throws IOException, NoSuchAlgorithmException {
      SchematicConfig config = this.findConfig(file.getName());
      if (config == null) {
         Minions.w("No config found for '" + file.getName() + "'!");
         return false;
      }

      String configPath = config.getPath();
      File cacheFolder = new File("plugins/Metropolis/cache", Minions.getMD5Checksum(file));
      if (isCacheCurrent(cacheFolder, configPath, "STREET.schem")) {
         Minions.d("Cache for '%s' is up to date, skipping it.", file.getName());
         return true;
      }
      if (cacheFolder.isDirectory()) {
         deleteDirectory(cacheFolder);
      }

      if (!cacheFolder.mkdir()) {
         Minions.w("Cannot create directory '" + cacheFolder.getName() + "'");
         return false;
      } else {
         File configFile = new File(cacheFolder, "CONFIG.json");
         Minions.d("Copying config from " + Paths.get(configPath).toString() + " to " + configFile.toPath());
         Files.copy(Paths.get(configPath), cacheFolder.toPath().resolve("CONFIG.json"));
         File streetFile = new File(cacheFolder, "STREET.schem");
         Files.copy(file.toPath(), streetFile.toPath());
         markCacheComplete(cacheFolder);
         return true;
      }
   }

   public void importSchematics() {
      this.assertFolders();
      // Drop any cached Clipboard from a previous import in this same JVM
      // session before regenerating the cache files on disk -- otherwise
      // FileClipboard.loadClipboard() would keep serving whatever was
      // parsed before this reimport for any path that's already cached,
      // regardless of what actually gets written below. See
      // FileClipboard.schematicCache for the full explanation.
      FileClipboard.clearCache();
      File schematicsFolder = new File("plugins/Metropolis/import");
      Minions.i("loading schematic config files...");
      this.loadSchematicConfigs();
      List<File> schematicFiles = Minions.findAllFilesRecursively(schematicsFolder, new ArrayList<>(), new FilenameFilter() {
         @Override
         public boolean accept(File dir, String name) {
            return name.endsWith(".schem");
         }
      });
      int length = schematicFiles.size();
      Minions.d("ClipboardImporter schematic files count:" + length);
      // This build reads only Sponge ".schem" files. Say so at WARNING level if the import folder holds the old MCEdit
      // ".schematic" format instead (or nothing), because the plugin would otherwise boot normally and build no buildings.
      int legacyFiles = Minions.findAllFilesRecursively(schematicsFolder, new ArrayList<>(), new FilenameFilter() {
         @Override
         public boolean accept(File dir, String name) {
            return name.endsWith(".schematic");
         }
      }).size();
      if (legacyFiles > 0) {
         Minions.w("%d legacy .schematic file(s) under plugins/Metropolis/import are IGNORED: this build reads only .schem. "
                 + "Convert them (WorldEdit: //schem load, then //schem save sponge) or the city will have no buildings.", legacyFiles);
      }
      if (length == 0) {
         Minions.w("No .schem files found under plugins/Metropolis/import: nothing will be built from schematics.");
      }

      for (int i = 0; i < length; i++) {
         File file = schematicFiles.get(i);
         Minions.i("Importing schematic %4d of %d (%.2f%%) : %s", i, length, (double)i / length * 100.0, file.getName());
         SchematicConfig config = this.findConfig(file.getName());
         if (config == null) {
            Minions.d("No config for '" + file.getName() + "' found.");
         } else {
            try {
               boolean isStreetOrHighway = config.getContext().contains(ContextType.STREET) || config.getContext().contains(ContextType.HIGHWAY);
               if (isStreetOrHighway && PluginConfig.isClearStreetsOnly() && isDamagedStreetVariant(config, file)) {
                  Minions.d("Skipping damaged-variant street/highway '" + file.getName() + "' (clearStreetsOnly enabled).");
               } else if (!isStreetOrHighway) {
                  this.rotateAndCacheSchematic(file);
               } else {
                  this.cacheStreet(file);
               }
            } catch (IOException | NoSuchAlgorithmException | WorldEditException | RuntimeException e) {
               // RuntimeException too, deliberately: WorldEdit's schematic readers throw
               // UNCHECKED exceptions for files they cannot parse (for example a
               // NoSuchElementException for a painting or item frame that has no
               // "Rotation" tag). Letting one of those escape used to abort
               // onEnable() and take the whole plugin down because of a single
               // building. Skip that schematic, say which one and why, carry on.
               Minions.w("SKIPPED schematic %s -- it could not be imported and will not be used: %s", file.getName(), e);
               if (e.getMessage() != null && e.getMessage().contains("'Rotation'")) {
                  Minions.w("  (WorldEdit cannot read entities such as paintings and item frames in this file; Metropolis does not use entities, so remove the schematic's Entities list)");
               }
               Minions.e(e);
            }
         }
      }

      try {
         moveDirectoryToDirectory(new File("plugins/Metropolis/import"), new File("plugins/Metropolis/done"));
      } catch (IOException e) {
         Minions.w("Could not move plugins/Metropolis/import to plugins/Metropolis/done (%s). This is harmless: unchanged schematics are skipped on the next start. On Windows it usually means something (antivirus, Explorer, an indexer) has a file in that folder open.", e.toString());
      }
   }

   /** Recursively deletes a directory (what commons-io's FileUtils.deleteDirectory did). */
   private static void deleteDirectory(File directory) throws IOException {
      if (!directory.exists()) {
         return;
      }
      Files.walkFileTree(directory.toPath(), new SimpleFileVisitor<Path>() {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
            if (exc != null) {
               throw exc;
            }
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
         }
      });
   }

   /**
    * Moves `source` INTO `destinationParent` (creating it if needed) --
    * what FileUtils.moveDirectoryToDirectory(src, dest, true) did. Like it,
    * fails with an IOException if the destination already exists.
    */
   private static void moveDirectoryToDirectory(File source, File destinationParent) throws IOException {
      Files.createDirectories(destinationParent.toPath());
      Path target = destinationParent.toPath().resolve(source.getName());
      if (Files.exists(target)) {
         // A "done/import" from an earlier run is still there. Failing here
         // (as commons-io did) left the import folder in place, and the plugin
         // then re-imported every schematic on EVERY start -- over two minutes
         // each time. Keep the old one under a different name instead.
         target = destinationParent.toPath().resolve(source.getName() + "-" + System.currentTimeMillis());
      }
      Files.move(source.toPath(), target);
   }
}