package ch.k42.metropolis.grid.urbanGrid.vanilla;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Where the vanilla wilderness comes from: the server's OWN world generator, not a copy of it.
 *
 * A Bukkit world has exactly one ChunkGenerator, so Metropolis cannot ask "what would vanilla put here" of its own world. Instead
 * it keeps a companion world with the same seed and no custom generator ("<world>_vanilla" unless generator.wilderness.vanilla.world
 * says otherwise) and reads chunks out of that. Everything Minecraft generates a chunk with -- terrain shape, biomes, caves, ores,
 * trees, lakes, dungeons, and whatever a later version adds -- therefore arrives as it is, and updating the server updates the
 * wilderness with it. Nothing in this plugin reimplements any of it.
 *
 * A chunk is only final once the chunks around it exist (decoration of a neighbour spills over the border), so fetch() loads the
 * 3x3 block around the one asked for and snapshots the middle. Recently used companion chunks stay loaded so walking along a
 * border does not generate everything nine times; the oldest are unloaded WITHOUT saving, so the companion world does not pile up
 * on disk (the folder is safe to delete at any time: it regrows from the seed).
 *
 * This is the one version-specific piece of the vanilla wilderness. Since 1.17 the same job can be done in place, with
 * ChunkGenerator.shouldGenerateNoise / Surface / Caves / Decorations returning true for the wilderness chunks, which would make
 * this class (and the companion world) unnecessary. It is kept as it is so the wilderness is produced exactly the way the
 * 1.12.2 plugin produced it.
 *
 * Main thread only: it generates chunks. Off the main thread fetch() returns null and the caller falls back.
 */
public final class VanillaSource {
   /** Companion chunks kept loaded for reuse before the oldest are let go. */
   private static final int MAX_LOADED = 160;
   /** After this many failures in a row the source gives up for the rest of the run instead of failing every chunk. */
   private static final int MAX_FAILURES = 3;
   /** Dungeon spawners live below this (from the bottom of the world up); no point scanning the sky for them. */
   private static final int SPAWNER_SCAN_TOP = 96;

   private static final Map<String, VanillaSource> SOURCES = new HashMap<String, VanillaSource>();

   /** Written into a companion world this plugin made, so a world that was already there (and is someone's) is never mistaken for it. */
   private static final String MARKER = "metropolis-companion.txt";

   private final String ownerName;
   private final String companionName;
   /** Set when generator.wilderness.vanilla.world named the Metropolis world itself, which cannot be its own source. */
   private final String renamedFrom;
   /** True for a world that was already there before the plugin used it: it is read, never altered, and what is loaded from it is saved on unload. */
   private boolean borrowed;
   private boolean announced;
   private final long seed;
   private final World.Environment environment;
   private final LinkedHashSet<Long> loaded = new LinkedHashSet<Long>();
   private World companion;
   private int failures;
   private boolean warnedThread;

   private VanillaSource(World world) {
      String[] resolved = resolveName(world.getName());
      this.companionName = resolved[0];
      this.renamedFrom = resolved[1];
      this.ownerName = world.getName();
      this.seed = world.getSeed();
      this.environment = world.getEnvironment();
   }

   /**
    * The companion world's name for a Metropolis world: the configured one, or "<world>_vanilla". A configured name that is the
    * Metropolis world itself cannot work (it would read the very world being generated), so it is replaced by the default rather
    * than switching the vanilla wilderness off; the second element is then the name that was replaced, otherwise null.
    */
   private static String[] resolveName(String owner) {
      String configured = PluginConfig.getWildVanillaWorld();
      String name = configured == null ? "" : configured.trim();
      String fallback = owner + "_vanilla";
      if (name.isEmpty()) {
         return new String[]{fallback, null};
      }
      if (name.equalsIgnoreCase(owner)) {
         return new String[]{fallback, name};
      }
      return new String[]{name, null};
   }

   /** One line saying where this world's vanilla wilderness comes from, for the log as the world loads. */
   public static String describe(String ownerWorld) {
      String[] r = resolveName(ownerWorld);
      return r[1] == null ? "companion world '" + r[0] + "'"
              : "companion world '" + r[0] + "' (generator.wilderness.vanilla.world was '" + r[1] + "', the Metropolis world itself, so the default is used)";
   }

   /** The source for this world (one per world). */
   public static synchronized VanillaSource of(World world) {
      VanillaSource s = SOURCES.get(world.getName());
      if (s == null) {
         s = new VanillaSource(world);
         SOURCES.put(world.getName(), s);
      }
      return s;
   }

   /** The chunk as vanilla generates it, or null if it cannot be had right now (wrong thread, no companion world, an error). */
   public synchronized VanillaChunk fetch(int chunkX, int chunkZ) {
      if (this.failures >= MAX_FAILURES) {
         return null;
      }
      if (!Bukkit.isPrimaryThread()) {
         if (!this.warnedThread) {
            this.warnedThread = true;
            Minions.i("Vanilla wilderness: chunk (%d,%d) was populated off the main thread, which cannot be served from the companion world; "
                    + "such chunks fall back to the Metropolis wilderness", chunkX, chunkZ);
         }
         return null;
      }
      try {
         World w = this.companion();
         if (w == null) {
            return null;
         }
         Chunk centre = null;
         for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
               boolean wasLoaded = w.isChunkLoaded(chunkX + dx, chunkZ + dz);
               Chunk c = w.getChunkAt(chunkX + dx, chunkZ + dz);   // loads, generates and decorates as vanilla would
               if (!wasLoaded || this.loaded.contains(key(chunkX + dx, chunkZ + dz))) {
                  this.touch(chunkX + dx, chunkZ + dz);   // only chunks this source loaded are ever unloaded by it
               }
               if (dx == 0 && dz == 0) {
                  centre = c;
               }
            }
         }
         ChunkSnapshot snapshot = centre.getChunkSnapshot(true, true, false);
         VanillaChunk result = new VanillaChunk(snapshot, readSpawners(centre, snapshot));
         this.trim(w, chunkX, chunkZ);
         this.failures = 0;
         if (!this.announced) {
            this.announced = true;
            Minions.i("Vanilla wilderness: working. World '%s' now reads its forest regions from companion world '%s' (seed %d); first chunk served (%d,%d)",
                    this.ownerName, this.companionName, this.seed, chunkX, chunkZ);
         }
         return result;
      } catch (Throwable t) {
         this.failures++;
         Minions.i("Vanilla wilderness: could not read chunk (%d,%d) from companion world '%s' (%s)%s", chunkX, chunkZ,
                 this.companionName, t, this.failures >= MAX_FAILURES ? " -- giving up, the Metropolis wilderness is used instead" : "");
         return null;
      }
   }

   private World companion() {
      if (this.companion != null) {
         return this.companion;
      }
      if (this.renamedFrom != null) {
         Minions.i("Vanilla wilderness: generator.wilderness.vanilla.world is '%s', which is the Metropolis world itself and cannot be its own "
                 + "source. Using '%s' instead; set the option to another name, or leave it empty, to stop this message.", this.renamedFrom, this.companionName);
      }
      World w = Bukkit.getWorld(this.companionName);
      if (w != null) {
         this.borrowed = true;       // already loaded by someone else: read it, change nothing about it
      } else {
         File dir = new File(Bukkit.getWorldContainer(), this.companionName);
         this.borrowed = new File(dir, "level.dat").exists() && !new File(dir, MARKER).exists();   // a world that was there before this plugin
         Minions.i("Vanilla wilderness: %s world '%s' (seed %d) -- the server's own generator is read from it",
                 this.borrowed ? "using the existing" : "creating companion", this.companionName, this.seed);
         WorldCreator creator = new WorldCreator(this.companionName)
                 .environment(this.environment)
                 .type(WorldType.NORMAL)
                 .seed(this.seed)
                 .generateStructures(PluginConfig.isWildVanillaStructures());
         w = Bukkit.createWorld(creator);
      }
      if (w == null) {
         this.failures = MAX_FAILURES;
         Minions.i("Vanilla wilderness: companion world '%s' could not be created", this.companionName);
         return null;
      }
      // The companion has to be a plain vanilla world. If the name is the Metropolis world itself (or any world the Metropolis generator
      // runs), chunks would be read from the very world being generated, and nothing vanilla would ever come out of it.
      if (w.getName().equals(this.ownerName) || w.getGenerator() instanceof MetropolisGenerator) {
         this.failures = MAX_FAILURES;
         Minions.i("Vanilla wilderness: '%s' is a Metropolis world, not a vanilla one (world '%s' is the one being generated). "
                 + "Set generator.wilderness.vanilla.world to another name, or leave it empty. The Metropolis wilderness is used instead.",
                 w.getName(), this.ownerName);
         return null;
      }
      if (this.borrowed) {
         Minions.i("Vanilla wilderness: '%s' was not made by Metropolis (seed %d, the Metropolis world's is %d); it is read as it is and never altered. "
                 + "The wilderness will be that world's terrain.", this.companionName, w.getSeed(), this.seed);
      } else {
         // (setKeepSpawnInMemory and setSpawnFlags are gone as functionality: there are no spawn chunks any more, and the
         // companion is only ever loaded chunk by chunk with nobody in it, then unloaded unsaved.)
         w.setAllowMonsterSpawning(false);
         try {
            File marker = new File(w.getWorldFolder(), MARKER);
            if (!marker.exists()) {
               java.nio.file.Files.write(marker.toPath(), "Made by Metropolis: vanilla chunks for the wilderness are read from this world. Safe to delete.\n".getBytes("UTF-8"));
            }
         } catch (Throwable ignored) {
            // the marker only protects a world that was already there; without it the next run just treats this one as its own
         }
      }
      this.companion = w;
      return w;
   }

   private void touch(int x, int z) {
      Long key = key(x, z);
      this.loaded.remove(key);
      this.loaded.add(key);
   }

   /** Lets the oldest chunks this source loaded go once more than MAX_LOADED are held (unsaved, unless the world is someone else's). */
   private void trim(World w, int keepX, int keepZ) {
      Iterator<Long> it = this.loaded.iterator();
      while (this.loaded.size() > MAX_LOADED && it.hasNext()) {
         long k = it.next();
         int x = (int) (k >> 32);
         int z = (int) k;
         if (Math.abs(x - keepX) <= 2 && Math.abs(z - keepZ) <= 2) {
            continue;
         }
         it.remove();
         w.unloadChunk(x, z, this.borrowed);
      }
   }

   private static long key(int x, int z) {
      return ((long) x << 32) | (z & 0xFFFFFFFFL);
   }

   /** The mob of each spawner in the chunk: it is tile-entity data, so the snapshot does not carry it. */
   private static List<VanillaChunk.Spawner> readSpawners(Chunk centre, ChunkSnapshot snapshot) {
      List<VanillaChunk.Spawner> out = new ArrayList<VanillaChunk.Spawner>();
      for (int y = centre.getWorld().getMinHeight(); y <= SPAWNER_SCAN_TOP; y++) {
         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               if (snapshot.getBlockType(x, y, z) != Material.SPAWNER) {
                  continue;
               }
               BlockState state = centre.getBlock(x, y, z).getState();
               if (state instanceof CreatureSpawner) {
                  EntityType type = ((CreatureSpawner) state).getSpawnedType();
                  if (type != null) {
                     out.add(new VanillaChunk.Spawner(x, y, z, type));
                  }
               }
            }
         }
      }
      return out;
   }
}
