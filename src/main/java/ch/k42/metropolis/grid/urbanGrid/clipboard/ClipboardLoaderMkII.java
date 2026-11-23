package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.grid.urbanGrid.config.GlobalSchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.regions.Region;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClipboardLoaderMkII implements ClipboardLoader {
   private GlobalSchematicConfig globalConfig;
   private Map<String, Clipboard> clipstore;
   private ClipboardDAO dao;

   public ClipboardLoaderMkII(ClipboardDAO dao) {
      this.dao = dao;
   }

   private SchematicConfig getConfig(File folder) {
      SchematicConfig config = SchematicConfig.fromFile(new File(folder, "CONFIG.json"));
      if (config == null) {
         Minions.w("Found no config in folder: " + folder.getName());
      }

      return config;
   }

   public Map<String, Clipboard> loadSchematics() {
      this.clipstore = new HashMap<>();
      Minions.i("loading schematic config files...");
      this.globalConfig = GlobalSchematicConfig.fromFile("plugins/Metropolis/import" + File.separator + "global_settings.json");
      File cacheFolder = new File("plugins/Metropolis/cache");
      File[] schematicFolders = cacheFolder.listFiles(new FilenameFilter() {
         @Override
         public boolean accept(File dir, String name) {
            return new File(dir, name).isDirectory();
         }
      });
      int length = schematicFolders.length;
      Minions.d("ClipboardLoaderMkII schematic folder count: " + length);

      for (int i = 0; i < length; i++) {
         File folder = schematicFolders[i];
         Minions.i("Loading schematic %4d of %d (%.2f%%) : %s", i, length, (double)i / length * 100.0, folder.getName());

         try {
            this.loadFolder(folder);
         } catch (IOException e) {
            Minions.e(e);
         }
      }

      return this.clipstore;
   }

   private List<Clipboard> loadFolder(File folder) throws IOException {
      List<Clipboard> clips = new ArrayList<>();
      clips.addAll(this.loadStreet(folder));
      clips.addAll(this.loadBuilds(folder));
      return clips;
   }

   private List<Clipboard> loadStreet(File folder) throws IOException {
      File streetFile = new File(folder, "STREET.schem");
      if (!streetFile.exists()) {
         return Collections.emptyList();
      }

      ClipboardFormat format = ClipboardFormats.findByFile(streetFile);
      if (format == null) {
         throw new IOException("Unrecognised schematic format for '" + streetFile.getName() + "'!");
      }

      com.sk89q.worldedit.extent.clipboard.Clipboard loaded;
      // The reader closes the stream it wraps. Matches FileClipboard.loadClipboard().
      try (ClipboardReader reader = format.getReader(new FileInputStream(streetFile))) {
         loaded = reader.read();
      }

      // The original CuboidClipboard-era code re-saved the file
      // immediately after loading it (format.save(cuboid, streetFile)) --
      // preserved here as a re-save through the modern writer, so this
      // still normalizes/reserializes STREET.schematic on first load the
      // same way it always has.
      try (ClipboardWriter writer = format.getWriter(new FileOutputStream(streetFile))) {
         writer.write(loaded);
      }

      SchematicConfig config = this.getConfig(folder);
      String hash = folder.getName();
      FileClipboard.registerStreetMetadataSource(streetFile, loaded, config, this.globalConfig, hash);
      Clipboard clip = new ClipboardWE(loaded, config, this.globalConfig, hash, streetFile);
      hash = hash + ".STREET";
      this.clipstore.put(hash, clip);
      if (this.dao.containsHash(hash)) {
         this.dao.deleteClipboardHashes(hash);
      }

      this.dao.storeClipboard(hash, streetFile.getName(), Direction.NONE, config, new Cartesian2D(1, 1));
      return Collections.singletonList(clip);
   }

   private List<Clipboard> loadBuilds(File folder) {
      File northFile = new File(folder, "NORTH.schem");
      if (!northFile.exists()) {
         return Collections.emptyList();
      }

      File eastFile = new File(folder, "EAST.schem");
      File southFile = new File(folder, "SOUTH.schem");
      File westFile = new File(folder, "WEST.schem");
      // Mirrored counterparts of each rotation -- see
      // ClipboardImporter.rotateAndCacheSchematic() for how these get
      // generated. Optional: older cache folders (or a fresh re-import
      // that hasn't regenerated yet) may not have these files, so each is
      // checked for existence individually below rather than assumed.
      File northMirroredFile = new File(folder, "NORTH_M.schem");
      File eastMirroredFile = new File(folder, "EAST_M.schem");
      File southMirroredFile = new File(folder, "SOUTH_M.schem");
      File westMirroredFile = new File(folder, "WEST_M.schem");

      String hash = folder.getName();
      SchematicConfig config = this.getConfig(folder);
      List<Clipboard> clips = new ArrayList<>();
      boolean flippedOnly = PluginConfig.isFlippedOnlyForTesting();
      if (!flippedOnly) {
         clips.add(this.loadFromCache(eastFile, hash, Direction.EAST, config, false));
         clips.add(this.loadFromCache(westFile, hash, Direction.WEST, config, false));
         clips.add(this.loadFromCache(southFile, hash, Direction.SOUTH, config, false));
         clips.add(this.loadFromCache(northFile, hash, Direction.NORTH, config, false));
      }

      if (eastMirroredFile.exists()) {
         clips.add(this.loadFromCache(eastMirroredFile, hash, Direction.EAST, config, true));
      }
      if (westMirroredFile.exists()) {
         clips.add(this.loadFromCache(westMirroredFile, hash, Direction.WEST, config, true));
      }
      if (southMirroredFile.exists()) {
         clips.add(this.loadFromCache(southMirroredFile, hash, Direction.SOUTH, config, true));
      }
      if (northMirroredFile.exists()) {
         clips.add(this.loadFromCache(northMirroredFile, hash, Direction.NORTH, config, true));
      }

      return clips;
   }

   private Clipboard loadFromCache(File file, String hash, Direction direction, SchematicConfig config, boolean mirrored) {
      try {
         FileClipboard clip = new FileClipboard(file, config, this.globalConfig, hash, mirrored);
         // Mirrored variants need their own distinct storage key -- without
         // the ".M" suffix, a mirrored file would collide with its
         // unmirrored counterpart's thash (same folder hash + same
         // direction), silently overwriting one clipstore entry and one DB
         // row with the other instead of both existing side by side as
         // separate, independently-selectable candidates.
         String thash = hash + "." + direction.name() + (mirrored ? ".M" : "");
         this.clipstore.put(thash, clip);
         if (this.dao.containsHash(thash)) {
            this.dao.deleteClipboardHashes(thash);
         }

         // FileClipboard.loadClipboard() already parsed this file once at
         // construction time (to compute blockCount / groundLevelY) and
         // cached the result in its static schematicCache -- calling it
         // again here just returns that cached object instead of parsing
         // the file a second time, which the old code was doing (once via
         // the now-removed CuboidClipboard load here, once again inside
         // FileClipboard itself).
         Region region = clip.loadClipboard().getRegion();
         Cartesian2D size = new Cartesian2D(region.getWidth() >> 4, region.getLength() >> 4);
         this.dao.storeClipboard(thash, file.getName(), direction, config, size);
         if (!config.getRoadFacing()) {
            this.dao.storeClipboard(thash, file.getName(), Direction.NONE, config, size);
         }

         return clip;
      } catch (RuntimeException e) {
         Minions.e(e);
         return null;
      }
   }

   @Override
   public Map<String, Clipboard> loadSchematics(File schematicsFolder, File cacheFolder) {
      return this.loadSchematics();
   }
}