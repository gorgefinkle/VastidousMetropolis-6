package ch.k42.metropolis.generator;

import ch.k42.metropolis.generator.populators.BedrockFloorPopulator;
import ch.k42.metropolis.generator.populators.CavePopulator;
import ch.k42.metropolis.generator.populators.OrePopulator;
import ch.k42.metropolis.grid.common.Factory;
import ch.k42.metropolis.grid.common.Grid;
import ch.k42.metropolis.grid.common.GridProvider;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProvider;
import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.provider.DecayProvider;
import ch.k42.metropolis.grid.urbanGrid.provider.DecayProviderNether;
import ch.k42.metropolis.grid.urbanGrid.provider.DecayProviderNormal;
import ch.k42.metropolis.grid.urbanGrid.provider.EnvironmentProvider;
import ch.k42.metropolis.grid.urbanGrid.provider.NetherEnvironmentProvider;
import ch.k42.metropolis.grid.urbanGrid.provider.NormalEnvironmentProvider;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaSource;
import ch.k42.metropolis.grid.urbanGrid.vanilla.VanillaWilderness;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import ch.k42.metropolis.plugin.PluginConfig;
import ch.k42.metropolis.grid.common.Grid;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanBoatParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanBoatTailParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.RiverParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.TerrainFallbackParcel;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

public class MetropolisGenerator extends ChunkGenerator {
   private MetropolisPlugin plugin;
   private Long worldSeed;
   private World world;
   private MetropolisBlockPopulator blockPopulator;
   public String worldName;
   private ClipboardProvider clipboardProvider;
   private GridProvider gridProvider;
   private ContextProvider contextProvider;
   private DecayProvider decayProvider;
   private EnvironmentProvider natureDecay;
   private static final int SPAWN_RADIUS = 100;
   /** Terrain fills y = 1 .. TERRAIN_TOP - 1 (the original 1.8 value; buildHeight sits just above it). */
   private static final int TERRAIN_TOP = 65;

   /**
    * One biome everywhere, as before (the old BiomeGrid was set to PLAINS
    * for every column). Modern servers ask a BiomeProvider instead.
    */
   private static final BiomeProvider PLAINS_EVERYWHERE = new BiomeProvider() {
      @Override
      public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
         return Biome.PLAINS;
      }

      @Override
      public List<Biome> getBiomes(WorldInfo worldInfo) {
         return List.of(Biome.PLAINS);
      }
   };

   public MetropolisGenerator(MetropolisPlugin plugin, String worldName, ClipboardProvider clipboardProvider) {
      this.clipboardProvider = clipboardProvider;
      this.plugin = plugin;
      this.worldName = worldName;
      Minions.i("Running MetropolisGenerator for world: %s", worldName);
   }

   public ClipboardProvider getClipboardProvider() {
      return this.clipboardProvider;
   }

   public GridProvider getGridProvider() {
      return this.gridProvider;
   }

   public ContextProvider getContextProvider() {
      return this.contextProvider;
   }

   public DecayProvider getDecayProvider() {
      this.ensureProviders();
      return this.decayProvider;
   }

   public EnvironmentProvider getNatureDecayProvider() {
      this.ensureProviders();
      return this.natureDecay;
   }

   public World getWorld() {
      return this.world;
   }

   public MetropolisPlugin getPlugin() {
      return this.plugin;
   }

   public Long getWorldSeed() {
      return this.worldSeed;
   }

   public List<BlockPopulator> getDefaultPopulators(World world) {
      List<BlockPopulator> populators = new ArrayList<>();
      Minions.d("getDefaultPopulators: " + world.toString());
      this.world = world;
      this.worldSeed = world.getSeed();
      this.announceOcean(world);
      this.announceVanilla(world);
      this.gridProvider = new GridProvider(this);
      this.contextProvider = Factory.getDefaultContextProvider(this, this.plugin.getContextConfig());
      // R30ea: initialize the shared world plan before the first tile can solve a domain.
      UrbanGrid.prepareWorldPlan(this);
      this.blockPopulator = new MetropolisBlockPopulator(world);
      populators.add(this.blockPopulator);
      // vanilla wilderness chunks already have their own caves, ores and bedrock
      populators.add(new SkipVanilla(new CavePopulator(this.worldSeed)));
      populators.add(new SkipVanilla(new OrePopulator(world, this.plugin.getPopulatorConfig().getOres())));
      // With a vanilla underworld the world is open below Y=0, so the old
      // bedrock floor at Y=0..3 would seal it off; the populator then only
      // stamps the two easter-egg chunks, on the real bottom layer.
      populators.add(new SkipVanilla(new BedrockFloorPopulator(PluginConfig.isVanillaUnderworld()
              && world.getEnvironment() == Environment.NORMAL && world.getMinHeight() < 0)));
      // (No lighting populator any more: it only existed to call FAWE's
      // //fixlighting after every chunk. The modern lighting engine updates
      // as blocks are placed, and WorldEdit's pastes apply light updates
      // themselves.)
      return populators;
   }

   /**
    * Logs where this world's ocean is, once, as the world loads. Its
    * placement is a pure function of the world seed (see OceanProvider), so
    * the answer is available now -- long before any tile near it has been
    * generated -- and this is the only way to find it without wandering.
    * Always logged (not behind debugOcean): it's a single line per world
    * load. Wrapped so a debugging aid can never stop a world from loading.
    */
   private void announceOcean(World world) {
      try {
         OceanProvider ocean = new OceanProvider(this.worldSeed);
         if (ocean.isEnabled()) {
            Minions.i("Ocean in world '%s': %s", world.getName(), ocean.describe());
         } else {
            Minions.i("Ocean is disabled (generator.ocean.enabled: false) -- none will be placed in world '%s'", world.getName());
         }
      } catch (Exception e) {
         Minions.i("Could not work out the ocean's location for world '%s': %s", world.getName(), e);
      }
   }

   /**
    * Says, once per world load, whether the wilderness here is vanilla and where it is read from, so "vanilla does nothing" is
    * answered by the log and not by guessing: the usual causes are the option being false (or missing from an older config.yml,
    * which Bukkit never adds keys to) and chunks that were generated before it was switched on.
    */
   private void announceVanilla(World world) {
      try {
         if (!PluginConfig.isWildVanillaEnabled()) {
            Minions.i("Vanilla wilderness in world '%s': OFF (generator.wilderness.vanilla.enabled is false, or missing from config.yml)", world.getName());
         } else if (world.getEnvironment() != World.Environment.NORMAL) {
            Minions.i("Vanilla wilderness in world '%s': ON in the config but not used: only overworlds have it", world.getName());
         } else {
            Minions.i("Vanilla wilderness in world '%s': ON, read from %s. Only chunks generated from now on get it.",
                    world.getName(), VanillaSource.describe(world.getName()));
         }
      } catch (Exception e) {
         Minions.i("Could not report the vanilla wilderness for world '%s': %s", world.getName(), e);
      }
   }

   /**
    * Is this chunk wilderness that comes from the server's own generator (generator.wilderness.vanilla.enabled)? That is every
    * chunk of a forest region, which is also what MorphomosParcel hands to VanillaWilderness.
    */
   public boolean isVanillaChunk(int chunkX, int chunkZ) {
      if (this.world == null || this.gridProvider == null || !VanillaWilderness.isActive(this.world)) {
         return false;
      }
      Grid grid = this.gridProvider.getGrid(chunkX, chunkZ);
      if (!(grid instanceof UrbanGrid)) {
         return false;
      }
      MorphomosRegion region = ((UrbanGrid) grid).getMorphomosRegionAt(chunkX, chunkZ);
      return region != null && region.getContext() == ContextType.FOREST;
   }

   /** Runs the populator it wraps everywhere except in vanilla wilderness chunks. */
   private class SkipVanilla extends BlockPopulator {
      private final BlockPopulator inner;

      private SkipVanilla(BlockPopulator inner) {
         this.inner = inner;
      }

      @Override
      public void populate(World aWorld, Random random, Chunk chunk) {
         if (!MetropolisGenerator.this.isVanillaChunk(chunk.getX(), chunk.getZ())) {
            this.inner.populate(aWorld, random, chunk);
         }
      }
   }

   public boolean canSpawn(World world, int x, int z) {
      return true;
   }

   /**
    * decayProvider/natureDecay used to only ever get created inline at the
    * top of generateBlockSections() -- fine as long as Bukkit always
    * generates a chunk's terrain before populating it, for this generator
    * instance, with no exceptions. That held for ordinary fresh-world
    * generation, but chunk POPULATION can also be reached via the async
    * chunk-loading path (Chunk.loadNearby, off an already-saved or
    * externally-triggered load) without generateBlockSections() having
    * run first on this instance -- which is exactly what produced a
    * NullPointerException in FileClipboard.place() calling
    * getNatureDecayProvider().checkBlock() while it was still null.
    * Extracting the same init logic here and calling it from both
    * generateBlockSections() AND the two getters below means whichever
    * path asks first gets a valid provider, not just the terrain-gen path.
    * synchronized for the same reason as NetherEnvironmentProvider's
    * cache: async chunk loading means this could plausibly be reached
    * from more than one thread, and creating two different provider
    * instances for the same world (one thread's write clobbering the
    * other's) would be a subtler, harder-to-diagnose version of the same
    * bug.
    */
   private void ensureProviders() {
      this.ensureProviders(this.world.getEnvironment(), this.world.getSeed());
   }

   /**
    * The same lazy init, but from the environment and seed the modern
    * generation hooks are handed (a WorldInfo), so terrain generation never
    * needs the Bukkit World object -- which may not be assigned yet, and
    * which generation threads must not touch anyway.
    */
   private synchronized void ensureProviders(Environment environment, long seed) {
      if (this.natureDecay == null || this.decayProvider == null) {
         if (environment == Environment.NETHER) {
            this.decayProvider = new DecayProviderNether(this, new Random(seed));
            this.natureDecay = new NetherEnvironmentProvider(seed);
         } else {
            this.decayProvider = new DecayProviderNormal(this, new Random(seed + 10L));
            this.natureDecay = new NormalEnvironmentProvider(seed);
         }
      }
   }

   /**
    * True when everything below Y=0 should be generated by vanilla: the
    * feature is on, this is an ordinary overworld-style world, and the world
    * actually reaches below Y=0 (it does on 1.18+; the Nether starts at 0).
    */
   private static boolean vanillaUnderworld(WorldInfo worldInfo) {
      return PluginConfig.isVanillaUnderworld()
              && worldInfo.getEnvironment() == Environment.NORMAL
              && worldInfo.getMinHeight() < 0;
   }

   /**
    * Vanilla noise first: stone, vanilla's noise caves, aquifers and the big
    * copper/iron ore veins. The server runs it BEFORE generateNoise() below,
    * which then takes over everything from Y=0 up. Per-chunk variant of the
    * hook, so the Nether (which has no underworld) is left alone.
    */
   @Override
   public boolean shouldGenerateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
      return vanillaUnderworld(worldInfo);
   }

   /**
    * Vanilla ore and underground features (the regular ore veins, geodes,
    * springs...). The server runs these BEFORE any BlockPopulator, so
    * Metropolis's parcels are painted over whatever they did. Vanilla
    * features cannot be limited by height, so ores also appear in the stone
    * above Y=0, on top of the ones OrePopulator adds there. Trees, grass and
    * flowers do not take: they need a dirt or grass surface and Metropolis's
    * base terrain is bare stone.
    */
   @Override
   public boolean shouldGenerateDecorations(WorldInfo worldInfo, Random random, int chunkX, int chunkZ) {
      return vanillaUnderworld(worldInfo);
   }

   /**
    * The base terrain: stone from y=1 (y=0 when the underworld is vanilla) up
    * to just below buildHeight, or whatever the environment provider
    * substitutes -- the Nether's lava sea. Normal-overworld Y=64 is an
    * intentional smoothstone pre-paint safety cap for unpopulated chunks.
    * Everything else, including the
    * real surface, is painted later by the grid's populate step.
    *
    * This replaces generateBlockSections(), whose byte[][] of numeric block
    * IDs no longer exists. All of the plugin's Y coordinates (buildHeight,
    * the ocean's water line) keep the meaning they always had.
    *
    * Below Y=0 there are two modes (PluginConfig.isVanillaUnderworld()):
    *
    * - On (default): vanilla has already filled the chunk (see
    *   shouldGenerateNoise). This wipes Y=0 and up -- the vanilla hills, ocean
    *   water and caves up there are not wanted -- lays Metropolis's own stone
    *   layer, then finishes the underworld: stone becomes deepslate, and the
    *   bottom five layers become a vanilla-style bedrock floor. Deepslate and
    *   bedrock come from vanilla's SURFACE stage, which stays off, because
    *   that stage would also dress Metropolis's own stone layer in grass and
    *   dirt; so they are done here instead.
    * - Off: nothing is generated below Y=0, so it is empty void under a
    *   bedrock floor placed at Y=0..3 by BedrockFloorPopulator.
    *
    * This runs on a world-generation thread: it must not touch the Bukkit
    * World, and doesn't (EnvironmentProvider.checkBlock() only computes).
    */
   /** Base substrate fallback after environment-specific material selection. */
   static Material baseTerrainMaterial(Material environmentOverride) {
      return environmentOverride != null ? environmentOverride : Material.STONE;
   }

   /**
    * The normal overworld has an intentional pre-paint safety cap at Y=64.
    * It must be written during base generation, before parcels populate, so
    * chunks that remain unpopulated never expose a grass-cap surface.
    * Populated parcels may replace it; raised forest columns normalize it only
    * when it is buried beneath their actual surface.
    */
   static Material baseTerrainMaterial(Material environmentOverride, Environment environment, int y) {
      if (environment == Environment.NORMAL && y == 64) {
         return Material.SMOOTH_STONE;
      }
      return baseTerrainMaterial(environmentOverride);
   }

   @Override
   public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
      this.ensureProviders(worldInfo.getEnvironment(), worldInfo.getSeed());

      boolean underworld = vanillaUnderworld(worldInfo);
      int firstY = 1;
      if (underworld) {
         // Vanilla's noise is already in the chunk. Clear it from Y=0 upward;
         // everything above is ours.
         CachedChunkWrite.setRegion(chunkData, 0, 0, 0, 16, chunkData.getMaxHeight(), 16, Material.AIR);
         firstY = 0;
      }

      for (int x = 0; x < 16; x++) {
         for (int z = 0; z < 16; z++) {
            for (int y = firstY; y < TERRAIN_TOP; y++) {
               Material decay = this.natureDecay.checkBlock(this.world, chunkX * 16 + x, y, chunkZ * 16 + z);
               CachedChunkWrite.set(chunkData, x, y, z,
                       baseTerrainMaterial(decay, worldInfo.getEnvironment(), y));
            }
         }
      }

      if (underworld) {
         this.dressUnderworld(chunkData, random);
      }
   }

   /**
    * Deepslate and the bedrock floor, the two things vanilla adds in its
    * surface stage (see generateNoise for why that stage is off).
    *
    * Deepslate: every stone block at or below Y=-1 becomes deepslate; between
    * Y=0 and Y=7 it thins out linearly, so the change is not a hard line;
    * from Y=8 up it is all stone. Only STONE is converted -- ore veins the
    * noise stage placed are already the right (deepslate or plain) variant
    * for their height.
    *
    * Bedrock: the bottom layer is solid, then each layer above has a 20%
    * lower chance, over five layers -- vanilla's own bedrock gradient.
    */
   private void dressUnderworld(ChunkData chunkData, Random random) {
      int minY = chunkData.getMinHeight();
      for (int x = 0; x < 16; x++) {
         for (int z = 0; z < 16; z++) {
            for (int y = minY; y <= 7; y++) {
               if (chunkData.getType(x, y, z) != Material.STONE) {
                  continue;
               }
               if (y < 0 || random.nextDouble() < (8 - y) / 8.0) {
                  CachedChunkWrite.set(chunkData, x, y, z, Material.DEEPSLATE);
               }
            }
            for (int layer = 0; layer < 5; layer++) {
               if (layer == 0 || random.nextDouble() < (5 - layer) / 5.0) {
                  CachedChunkWrite.set(chunkData, x, minY + layer, z, Material.BEDROCK);
               }
            }
         }
      }
   }

   @Override
   public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
      return PLAINS_EVERYWHERE;
   }

   public Location getFixedSpawnLocation(World world, Random random) {
      int spawnX = random.nextInt(200) - 100;
      int spawnZ = random.nextInt(200) - 100;
      // The first free cell above the surface -- getHighestBlockYAt() is the
      // surface block itself now, and a spawn Location needs the cell above it.
      int spawnY = Mats.firstAirAbove(world, spawnX, spawnZ);
      return new Location(world, spawnX, spawnY, spawnZ);
   }

   /**
    * Queues every already-generated chunk in the box for another populate pass
    * (the /repopulate command). Returns how many were queued.
    */
   public int requeueChunks(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
      return this.blockPopulator == null ? 0 : this.blockPopulator.requeue(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
   }

   /** Resume jobs restored before the plugin reached its enabled state. */
   public void resumeDeferredQueue() {
      if (this.blockPopulator != null) this.blockPopulator.startIfPending();
   }

   /** Pending work is already persisted before this shutdown hook. */
   public void saveDeferredQueue() {
      if (this.blockPopulator != null) {
         this.blockPopulator.saveQueue();
      }
   }

   private class MetropolisBlockPopulator extends BlockPopulator {
      // Populating a chunk can force OTHER chunks to load and generate (a
      // building paste spills into its neighbours; WorldEdit loads the chunks it
      // writes to), and on a modern server a chunk that is forced to load runs
      // its own populate right there, nested inside the first one. So populate is
      // guarded:
      //
      // - nesting is limited to MAX_NESTED_POPULATES levels. Anything deeper is
      //   durably queued and staged asynchronously from disk, so a long chain
      //   becomes queued work instead of recursive synchronous chunk loading;
      // - deferred jobs are saved to disk and reloaded at the next start;
      // - the drain stages already-generated chunks asynchronously from disk,
      //   then runs one population callback on the server thread. It never
      //   synchronously loads a queued chunk from inside the drain. This matters:
      //   synchronous loading can re-enter chunk generation/lighting while a
      //   player tick is itself waiting for chunk completion.
      // - nested starts, slow populates and stuck populates are logged (the
      //   stuck one with the main thread's stack), plus a summary line every 30 s
      //   saying how much main-thread time populating is costing.
      private static final int MAX_NESTED_POPULATES = 3;
      private static final long SAVE_INTERVAL_MS = 5000;
      private static final long STATS_INTERVAL_MS = 30000;
      private static final long SLOW_POPULATE_MS = 3000;
      private static final long ASYNC_CHUNK_LOAD_TIMEOUT_TICKS = 600L;
      private static final long STUCK_REPORT_MS = 15000;
      private static final int DIAGNOSTIC_LINES = 40;

      // DIAGNOSTICS for the legacy hook (see PORTING.md, D11): both hooks log
      // once. Confirmed on 26.2: the legacy hook IS called, on the main thread.
      private final AtomicBoolean loggedLegacy = new AtomicBoolean();
      private final AtomicBoolean loggedRegion = new AtomicBoolean();

      private final World owner;
      private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
      private final DiskDeferredChunkQueue deferred;
      private final AtomicBoolean drainScheduled = new AtomicBoolean();
      private final AtomicInteger nestedLines = new AtomicInteger();
      private final AtomicInteger deferredLines = new AtomicInteger();
      private final AtomicInteger slowLines = new AtomicInteger();
      private final AtomicBoolean watchdogStarted = new AtomicBoolean();
      private volatile Thread outermostThread;
      private volatile long outermostStartNanos;
      private volatile int outermostChunkX;
      private volatile int outermostChunkZ;
      private volatile int deepestNesting;

      // stats
      private final AtomicLong populatedTotal = new AtomicLong();
      private final AtomicLong populatedNanos = new AtomicLong();
      private final AtomicLong deferredTotal = new AtomicLong();
      private final AtomicLong slowestMillis = new AtomicLong();
      private volatile int slowestChunkX;
      private volatile int slowestChunkZ;
      private volatile int maxQueue;


      private MetropolisBlockPopulator(World owner) {
         this.owner = owner;
         try {
            this.deferred = new DiskDeferredChunkQueue(
                    new File(new File(owner.getWorldFolder(), "metropolis"), "deferred-populates").toPath(),
                    this.queueFile().toPath());
         } catch (IOException ex) {
            throw new IllegalStateException("Cannot recover pending Metropolis population jobs; refusing to lose them", ex);
         }
         this.reportPending();
         this.startIfPending();
      }

      private void startIfPending() {
         if (MetropolisGenerator.this.plugin.isEnabled() && this.deferred.hasPending()) this.scheduleDrain();
      }

      public void populate(World aWorld, Random random, Chunk chunk) {
         this.populateComplete(aWorld, random, chunk);
      }

      /** Returns false only when the operation was durably deferred again. */
      private boolean populateComplete(World aWorld, Random random, Chunk chunk) {
         if (this.loggedLegacy.compareAndSet(false, true)) {
            Minions.i("Populate step reached through the LEGACY BlockPopulator hook (first chunk: [%d,%d])", chunk.getX(), chunk.getZ());
         }

         int level = this.depth.get();
         if (level >= MAX_NESTED_POPULATES) {
            this.defer(chunk);
            return false;
         }
         if (level == 0) {
            this.startWatchdog();
            this.outermostThread = Thread.currentThread();
            this.outermostChunkX = chunk.getX();
            this.outermostChunkZ = chunk.getZ();
            this.deepestNesting = 0;
            this.outermostStartNanos = System.nanoTime();
         } else {
            this.deepestNesting = Math.max(this.deepestNesting, level);
            if (this.nestedLines.getAndIncrement() < DIAGNOSTIC_LINES) {
               Minions.w("NESTED populate: chunk [%d,%d] started while [%d,%d] is still populating (depth %d)",
                       chunk.getX(), chunk.getZ(), this.outermostChunkX, this.outermostChunkZ, level);
            }
         }

         this.depth.set(level + 1);
         long started = System.nanoTime();
         try {
            // The same Grid must own both phases; R30ea also notifies the city registry
            // and external LOD pipeline after completing both block-population stages.
            Grid grid = MetropolisGenerator.this.gridProvider.getGrid(chunk.getX(), chunk.getZ());
            grid.populate(chunk);
            grid.postPopulate(chunk);
            ContextType cityContext = null;
            if (grid instanceof UrbanGrid urbanGrid) {
               Parcel cityParcel = urbanGrid.getParcel(chunk.getX(), chunk.getZ());
               if (cityParcel != null) cityContext = cityParcel.getContextType();
            }
            MetropolisGenerator.this.plugin.recordCityChunk(aWorld, chunk.getX(), chunk.getZ(), cityContext);
            LodCompletionSync.changed(aWorld, chunk.getX(), chunk.getZ());
         } finally {
            this.depth.set(level);
            long elapsed = System.nanoTime() - started;
            long millis = elapsed / 1_000_000L;
            if (level == 0) {
               this.outermostStartNanos = 0;
               // Only the outermost populate is counted: its time already includes
               // everything nested inside it.
               this.populatedTotal.incrementAndGet();
               this.populatedNanos.addAndGet(elapsed);
               if (millis > this.slowestMillis.get()) {
                  this.slowestMillis.set(millis);
                  this.slowestChunkX = chunk.getX();
                  this.slowestChunkZ = chunk.getZ();
               }
            }
            if (millis >= SLOW_POPULATE_MS && this.slowLines.getAndIncrement() < DIAGNOSTIC_LINES) {
               Minions.w("SLOW populate: chunk [%d,%d] took %d ms (nesting depth %d)", chunk.getX(), chunk.getZ(), millis, level);
            }
         }
         return true;
      }

      /** Queues a chunk whose populate would nest too deeply; it runs later from the top of the stack. */
      private void reportPending() {
         int deferred = (int) Math.min(Integer.MAX_VALUE, this.deferred.pending());
         int growth = MetropolisGenerator.this.decayProvider != null
                 ? MetropolisGenerator.this.decayProvider.pendingGrowthWorkCount() : 0;
         ChunkyMemoryValve.pending(this.owner, deferred);
         ChunkyMemoryValve.growthPending(this.owner, growth);
      }

      private void defer(Chunk chunk) {
         try {
            if (this.deferred.enqueue(chunk.getX(), chunk.getZ())) this.deferredTotal.incrementAndGet();
         } catch (IOException ex) {
            throw new IllegalStateException("Cannot durably defer chunk [" + chunk.getX() + "," + chunk.getZ() + "]", ex);
         }
         this.maxQueue = (int) Math.min(Integer.MAX_VALUE, Math.max(this.maxQueue, this.deferred.pending()));
         this.reportPending();
         if (this.deferredLines.getAndIncrement() < DIAGNOSTIC_LINES) {
            Minions.w("DEFERRED populate of chunk [%d,%d]: nesting limit (%d); durable disk backlog %d, RAM window %d",
                    chunk.getX(), chunk.getZ(), MAX_NESTED_POPULATES, this.deferred.pending(), this.deferred.inMemory());
         }
         this.scheduleDrain();
      }

      /**
       * Stages one durable job at a time with Paper's non-generating async
       * disk-load path. Population itself remains on the server thread, but
       * chunk retrieval no longer blocks that thread or recursively generates
       * a support chunk from inside the deferred queue drain.
       */
      private void scheduleDrain() {
         this.scheduleDrain(1L);
      }

      /**
       * One in-flight scheduler token per world. The token is held until the
       * drain callback exits, so nested enqueue/retry signals cannot create
       * competing drain loops. Submission failure always releases the token;
       * the plugin's periodic queue watchdog will try again.
       */
      private void scheduleDrain(long delayTicks) {
         if (!MetropolisGenerator.this.plugin.isEnabled()) return;
         if (!this.drainScheduled.compareAndSet(false, true)) return;
         try {
            Bukkit.getScheduler().runTaskLater(MetropolisGenerator.this.plugin, () -> {
               long followUpDelayTicks = 1L;
               boolean asyncInFlight = false;
               DiskDeferredChunkQueue.Chunk job = null;
               try {
                  job = this.deferred.poll(System.nanoTime());
                  if (job == null) {
                     long retryDelay = this.deferred.nextRetryDelayMillis();
                     followUpDelayTicks = retryDelay <= 0L ? 1L
                             : Math.max(1L, Math.min(1200L, (retryDelay + 49L) / 50L));
                     return;
                  }

                  // This chunk was already generated when its original
                  // BlockPopulator callback was deferred. Never synchronously
                  // load it from the server-thread drain: doing so can enter
                  // ChunkTaskScheduler/lighting while a player tick is waiting
                  // for the same chunk, contributing to a server-thread stall.
                  asyncInFlight = true;
                  DiskDeferredChunkQueue.Chunk staged = job;
                  AtomicBoolean settled = new AtomicBoolean();
                  Bukkit.getScheduler().runTaskLater(MetropolisGenerator.this.plugin, () -> {
                     if (!settled.compareAndSet(false, true)) return;
                     try {
                        this.deferred.retry(System.nanoTime());
                        Minions.w("Deferred chunk [%d,%d] async disk staging exceeded %d ticks; durable job returned to retry queue",
                                staged.x(), staged.z(), ASYNC_CHUNK_LOAD_TIMEOUT_TICKS);
                     } catch (RuntimeException retryFailure) {
                        Minions.w("Could not retry timed-out deferred chunk [%d,%d]: %s",
                                staged.x(), staged.z(), retryFailure);
                     }
                     this.reportPending();
                     this.drainScheduled.set(false);
                     if (this.deferred.hasPending()) this.scheduleDrain(20L);
                  }, ASYNC_CHUNK_LOAD_TIMEOUT_TICKS);
                  try {
                     this.owner.getChunkAtAsync(staged.x(), staged.z(), false, false)
                             .whenComplete((loaded, loadFailure) -> {
                                if (!settled.compareAndSet(false, true)) return;
                                try {
                                   Bukkit.getScheduler().runTask(MetropolisGenerator.this.plugin,
                                           () -> this.finishDeferredLoad(staged, loaded, loadFailure));
                                } catch (RuntimeException schedulingFailure) {
                                   try {
                                      this.deferred.retry(System.nanoTime());
                                   } catch (RuntimeException retryFailure) {
                                      schedulingFailure.addSuppressed(retryFailure);
                                   }
                                   Minions.w("Could not schedule deferred populate completion for chunk [%d,%d]; job retained: %s",
                                           staged.x(), staged.z(), schedulingFailure);
                                   this.reportPending();
                                   this.drainScheduled.set(false);
                                   if (this.deferred.hasPending()) this.scheduleDrain(20L);
                                }
                             });
                  } catch (RuntimeException stagingFailure) {
                     settled.set(true);
                     throw stagingFailure;
                  }
               } catch (IOException | RuntimeException failure) {
                  if (job != null && asyncInFlight) {
                     try { this.deferred.retry(System.nanoTime()); }
                     catch (RuntimeException retryFailure) { failure.addSuppressed(retryFailure); }
                  }
                  Minions.w("Deferred Metropolis population could not be staged; durable job retained: %s", failure);
                  Minions.e(failure);
                  followUpDelayTicks = 20L;
                  asyncInFlight = false;
               } finally {
                  // The single-flight token remains held only while the
                  // asynchronous disk load is outstanding. Empty/cooling
                  // queues and synchronous staging failures release it here.
                  if (!asyncInFlight) {
                     this.drainScheduled.set(false);
                     if (this.deferred.hasPending()) this.scheduleDrain(followUpDelayTicks);
                  }
               }
            }, Math.max(1L, delayTicks));
         } catch (RuntimeException submissionFailure) {
            this.drainScheduled.set(false);
            Minions.w("Could not schedule deferred Metropolis drain; jobs remain on disk and watchdog will retry: %s",
                    submissionFailure);
         }
      }

      /** Complete one disk-backed async chunk load on the server thread. */
      private void finishDeferredLoad(DiskDeferredChunkQueue.Chunk job, Chunk loaded, Throwable loadFailure) {
         try {
            if (loadFailure != null || loaded == null) {
               this.deferred.retry(System.nanoTime());
               Minions.w("Deferred chunk [%d,%d] was not available from disk; job retained for retry: %s",
                       job.x(), job.z(), loadFailure == null ? "chunk absent" : loadFailure);
            } else {
               boolean finished = this.populateComplete(this.owner, new Random(), loaded);
               if (finished) this.deferred.complete();
               else this.deferred.retry(System.nanoTime());
            }
         } catch (IOException | RuntimeException failure) {
            try { this.deferred.retry(System.nanoTime()); }
            catch (RuntimeException retryFailure) { failure.addSuppressed(retryFailure); }
            Minions.w("Deferred populate of chunk [%d,%d] failed; job retained on disk: %s",
                    job.x(), job.z(), failure);
            Minions.e(failure);
         } catch (Error fatal) {
            try { this.deferred.retry(System.nanoTime()); }
            catch (RuntimeException retryFailure) { fatal.addSuppressed(retryFailure); }
            throw fatal;
         } finally {
            this.reportPending();
            this.drainScheduled.set(false);
            if (this.deferred.hasPending()) {
               long retryDelay = this.deferred.nextRetryDelayMillis();
               long delay = retryDelay <= 0L ? 1L
                       : Math.max(1L, Math.min(1200L, (retryDelay + 49L) / 50L));
               this.scheduleDrain(delay);
            }
         }
      }

      /**
       * Queues chunks for another populate pass. Skips chunks that are not
       * generated yet (they will populate normally), and parcels that are only
       * ever painted with plain block placement -- ocean land, rivers, fallback
       * terrain -- which never lose anything. Everything that depends on a
       * WorldEdit paste (streets, buildings, boats) is queued.
       */
      int requeue(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
         int queued = 0;
         for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
               if (!this.owner.isChunkGenerated(cx, cz)) continue;
               Grid grid = MetropolisGenerator.this.gridProvider.peekGrid(cx, cz);
               if (grid instanceof UrbanGrid) {
                  Parcel parcel = ((UrbanGrid) grid).peekParcel(cx, cz);
                  boolean boat = parcel instanceof OceanBoatParcel || parcel instanceof OceanBoatTailParcel;
                  if (parcel != null && ((parcel instanceof OceanParcel && !boat)
                          || parcel instanceof RiverParcel || parcel instanceof TerrainFallbackParcel)) continue;
               }
               try {
                  if (this.deferred.enqueue(cx, cz)) queued++;
               } catch (IOException ex) {
                  throw new IllegalStateException("Cannot durably requeue chunk [" + cx + "," + cz + "]", ex);
               }
            }
         }
         this.reportPending();
         if (this.deferred.hasPending()) this.scheduleDrain();
         return queued;
      }

      // Legacy TSV is imported atomically per job at queue construction time.
      private File queueFile() {
         return new File(new File(this.owner.getWorldFolder(), "metropolis"), "deferred-populates.tsv");
      }

      /** Jobs were already written to disk before the population callback returned. */
      void saveQueue() {
         this.reportPending();
         try { this.deferred.close(); }
         catch (IOException | IllegalStateException ex) {
            Minions.w("Cannot close deferred spool cleanly (jobs remain on disk): %s", ex);
         }
      }

      // ---- watchdog and statistics ----------------------------------------------

      /**
       * Watches the outermost populate from a separate thread: if it has been
       * running for more than STUCK_REPORT_MS it logs where the main thread is
       * right now; and every STATS_INTERVAL_MS it logs how much main-thread time
       * populating used.
       */
      private void startWatchdog() {
         if (!this.watchdogStarted.compareAndSet(false, true)) {
            return;
         }
         Thread watchdog = new Thread(() -> {
            long lastReport = 0;
            long lastStats = System.currentTimeMillis();
            long lastCount = 0;
            long lastNanos = 0;
            while (true) {
               try {
                  Thread.sleep(5000);
               } catch (InterruptedException e) {
                  return;
               }

               long nowMillis = System.currentTimeMillis();
               if (nowMillis - lastStats >= STATS_INTERVAL_MS) {
                  long count = this.populatedTotal.get();
                  long nanos = this.populatedNanos.get();
                  if (count != lastCount) {
                     long chunks = count - lastCount;
                     long totalMs = (nanos - lastNanos) / 1_000_000L;
                     Minions.i("Populate stats (last %d s): %d chunk(s), %d ms of main-thread time (%d ms each on average), slowest %d ms at [%d,%d]; %d queued, %d deferred in total, deepest queue %d",
                             (nowMillis - lastStats) / 1000, chunks, totalMs, totalMs / chunks, this.slowestMillis.getAndSet(0),
                             this.slowestChunkX, this.slowestChunkZ, this.deferred.pending(), this.deferredTotal.get(), this.maxQueue);
                     lastCount = count;
                     lastNanos = nanos;
                  }
                  lastStats = nowMillis;
               }

               long started = this.outermostStartNanos;
               Thread thread = this.outermostThread;
               if (started == 0 || thread == null) {
                  continue;
               }
               long runningMs = (System.nanoTime() - started) / 1_000_000L;
               if (runningMs >= STUCK_REPORT_MS && nowMillis - lastReport >= STUCK_REPORT_MS) {
                  lastReport = nowMillis;
                  Minions.w("POPULATE STUCK: chunk [%d,%d] has been populating for %d s (deepest nesting %d, %d chunk(s) queued). Main thread is at:",
                          this.outermostChunkX, this.outermostChunkZ, runningMs / 1000, this.deepestNesting, this.deferred.pending());
                  StackTraceElement[] stack = thread.getStackTrace();
                  for (int i = 0; i < Math.min(stack.length, 45); i++) {
                     Minions.w("    at %s", stack[i]);
                  }
               }
            }
         }, "Metropolis-populate-watchdog");
         watchdog.setDaemon(true);
         watchdog.start();
      }

      /**
       * Deliberately does nothing but log. It exists only to reveal whether
       * the server drives this populator through the region-limited hook (it
       * does, on a worker thread, in addition to the legacy hook); the real work
       * stays in the legacy method above.
       */
      @Override
      public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
         if (this.loggedRegion.compareAndSet(false, true)) {
            Minions.i("Populate step reached through the REGION-LIMITED BlockPopulator hook (first chunk: [%d,%d]) -- nothing is placed through this path yet", chunkX, chunkZ);
         }
      }
   }
}