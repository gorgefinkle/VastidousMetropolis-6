package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.generator.LodCompletionSync;
import ch.k42.metropolis.grid.urbanGrid.config.GlobalSchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.provider.EnvironmentProvider;
import ch.k42.metropolis.loot.VerticalLootBias;
import ch.k42.metropolis.loot.RankedChestLevels;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;
import ch.k42.metropolis.minions.DirtyHacks;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;

public class FileClipboard implements Clipboard {
   // Keyed by absolute file path. Note the type here: WorldEdit's modern
   // com.sk89q.worldedit.extent.clipboard.Clipboard interface, referenced
   // fully-qualified throughout this file rather than imported -- it
   // shares its simple name with THIS class's own Clipboard interface
   // (ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard, same package,
   // implemented by this class), so importing WorldEdit's would shadow
   // that and silently break `implements Clipboard` below.
   //
   // loadClipboard() used to re-read and re-parse the .schematic file
   // from scratch on every call -- and it's called on every single
   // paste(), not just once at import. The parsed result never changes
   // for a given file, so it's safe to parse once and reuse.
   //
   // IMPORTANT: reusing the same parsed Clipboard instance across many
   // pastes is only safe because place() below builds a fresh BaseBlock
   // for the decayed case instead of mutating one it read from the
   // clipboard -- WorldEdit's Extent.getBlock() contract returns live
   // block data, not a defensive copy, for exactly the same reason the
   // old CuboidClipboard-based version had to be careful about this.
   // Mutating a block read from the cached clipboard in place would
   // permanently bake one placement's decay into every future placement
   // of the same building.
   //
   // This is a static, JVM-lifetime cache with no expiry -- nothing ever
   // re-checks the file on disk once a path is cached. That's fine across
   // a single server run, since a given cache file's content never
   // changes without a re-import. But a LIVE re-import (running the
   // import command again without a full server restart) deletes and
   // rewrites the underlying .schematic files on disk (see
   // ClipboardImporter.saveClean()) while every already-cached Clipboard
   // for those same paths just sits here unchanged -- loadClipboard()
   // would keep serving the stale pre-reimport geometry forever, making
   // an already-fixed pipeline still look broken in-game for no reason
   // other than the cache never being told anything changed. clearCache()
   // exists so a reimport can invalidate this explicitly instead of
   // relying on a full restart to flush it.
   private static final Map<String, com.sk89q.worldedit.extent.clipboard.Clipboard> schematicCache = new ConcurrentHashMap<>();
   private static final Map<String, FileClipboard> deferredSources = new ConcurrentHashMap<>();

   static FileClipboard deferredSource(String absolutePath) {
      return deferredSources.get(absolutePath);
   }

   /** Register the already-parsed STREET clipboard with the SAME durable NBT
    * restoration queue used for building slices. No second parse or lost
    * metadata on server restart: the import loader re-registers on enable. */
   static void registerStreetMetadataSource(File file,
         com.sk89q.worldedit.extent.clipboard.Clipboard loaded,
         SchematicConfig config, GlobalSchematicConfig globalConfig, String groupId) {
      schematicCache.put(file.getAbsolutePath(), loaded);
      new FileClipboard(file, config, globalConfig, groupId, false);
   }
   /** Bumped by clearCache(): a size remembered from before it is read again from the freshly loaded clipboard. */
   private static volatile int cacheGeneration;

   /**
    * Drops every cached Clipboard so the next loadClipboard() call for
    * any given path re-reads that file from disk. Call this at the start
    * of a (re-)import so freshly rewritten .schematic files are actually
    * picked up, rather than the stale in-memory copy from before the
    * reimport silently continuing to be served for the rest of this JVM's
    * lifetime.
    */
   public static void clearCache() {
      cacheGeneration++;
      schematicCache.clear();
   }

   private File file;
   /** file.getAbsolutePath(), worked out once: it is the cache key, asked for on every loadClipboard(). */
   private String cacheKey;
   /** The size, worked out once per cache generation: getSize() is asked for every candidate of every placement attempt. */
   private volatile Cartesian3D size;
   private volatile int sizeGeneration = -1;
   private SchematicConfig config;
   private GlobalSchematicConfig globalConfig;
   // chests / spawners are NOT fields: one Clipboard is shared by every paste of it, and a paste can re-enter itself (see paste()).
   private int blockCount;
   private String groupId;
   private boolean mirrored;
   private static final char COLOR = ChatColor.GREEN.getChar();

   public FileClipboard(File file, SchematicConfig config, GlobalSchematicConfig globalConfig, String groupId, boolean mirrored) {
      this.file = file;
      this.config = config;
      this.globalConfig = globalConfig;
      Region region = this.loadClipboard().getRegion();
      this.blockCount = region.getWidth() * region.getHeight() * region.getLength();
      this.groupId = groupId;
      this.mirrored = mirrored;
      if (config.getGroundLevelY() == 0) {
         config.setGroundLevelY(this.estimateStreetLevel());
      }
      deferredSources.put(this.file.getAbsolutePath(), this);
   }

   public com.sk89q.worldedit.extent.clipboard.Clipboard loadClipboard() {
      String key = this.cacheKey;
      if (key == null) {
         key = this.file.getAbsolutePath();
         this.cacheKey = key;
      }
      com.sk89q.worldedit.extent.clipboard.Clipboard cached = schematicCache.get(key);
      if (cached != null) {
         return cached;
      }

      ClipboardFormat format = ClipboardFormats.findByFile(this.file);
      if (format == null) {
         throw new RuntimeException("Unrecognised schematic format for '" + this.file.getName() + "'!");
      }

      // The reader closes the stream it wraps; read() no longer needs a
      // WorldData registry (that was a pre-1.13 numeric-ID concept -- block
      // states carry their own identity now).
      try (ClipboardReader reader = format.getReader(new FileInputStream(this.file))) {
         com.sk89q.worldedit.extent.clipboard.Clipboard loaded = reader.read();
         schematicCache.put(key, loaded);
         return loaded;
      } catch (IOException e) {
         Minions.e(e);
         throw new RuntimeException("Failed to reload clipboard, possible corrupted cache.", e);
      }
   }

   @Override
   public void paste(MetropolisGenerator generator, Cartesian2D base, int streetLevel) {
      this.pastePart(generator, base, streetLevel, null, null);
   }

   /**
    * Paste only the slice occupying one ALREADY-POPULATING chunk. Giant
    * landmarks (including the 28x20-chunk creek origin park) cannot issue a
    * WorldEdit paste across hundreds of ungenerated chunks from inside the
    * anchor's chunk-population callback: Paper 26.2 waits recursively for the
    * neighbours and stalls. Each chunk now paints exactly its own slice, in
    * normal generator timing, without requesting other chunks to be loaded.
    * The full landmark remains one atomic site in WorldLayout.
    */
   public void pasteChunk(MetropolisGenerator generator, Cartesian2D base,
                          int streetLevel, int chunkX, int chunkZ) {
      this.pastePart(generator, base, streetLevel, chunkX, chunkZ);
   }

   private void pastePart(MetropolisGenerator generator, Cartesian2D base,
                          int streetLevel, Integer chunkX, Integer chunkZ) {
      int blockY = this.getBottom(streetLevel);
      BlockVector3 at = BlockVector3.at(base.X << 4, blockY, base.Y << 4);

      try {
         // place() only QUEUES block changes via editSession.setBlock() --
         // they aren't guaranteed to actually be applied to the world until
         // the session is flushed. Closing it (try-with-resources; this
         // replaces the old flushQueue(), which no longer exists) is what
         // flushes it. Without this, the chest/spawner checks below read
         // the world back immediately afterward and can see stale,
         // pre-paste block state, which is why both checks were failing
         // with "coordinates were wrong" even though the paste itself
         // worked.
         // Per-paste lists. place() used to fill shared fields, and the session flush / getBlockAt() below can load a neighbouring chunk,
         // whose populate pastes this same clipboard again (place() clearing and refilling the list under this loop): a
         // ConcurrentModificationException, or the wrong chests. Locals cannot be touched by a nested paste.
         List<Cartesian3D> chests = new ArrayList<>();
         List<Cartesian3D> spawners = new ArrayList<>();
         List<SkinnedHeadRestore> texturedHeads = new ArrayList<>();
         List<TileNbtRestore> tileNbt = new ArrayList<>();
         try (EditSession editSession = this.getEditSession(generator)) {
            this.place(generator, editSession, at, chests, spawners, texturedHeads, tileNbt, base, chunkX, chunkZ);
         }
         // R30ea defers block-entity, skull, chest and spawner restoration until
         // the required neighboring chunks are available. Never query distant
         // chunks from inside the chunk-population callback.
         if (!tileNbt.isEmpty() || !texturedHeads.isEmpty() || !chests.isEmpty() || !spawners.isEmpty()) {
            List<int[]> columns = new ArrayList<>();
            for (TileNbtRestore entry : tileNbt)
               columns.add(new int[] {(base.X << 4) + entry.local().X, (base.Y << 4) + entry.local().Z});
            for (SkinnedHeadRestore entry : texturedHeads)
               columns.add(new int[] {(base.X << 4) + entry.local().X, (base.Y << 4) + entry.local().Z});
            for (Cartesian3D entry : chests)
               columns.add(new int[] {(base.X << 4) + entry.X, (base.Y << 4) + entry.Z});
            for (Cartesian3D entry : spawners)
               columns.add(new int[] {(base.X << 4) + entry.X, (base.Y << 4) + entry.Z});
            // R30ea explicitly sorts the metadata halo long[] before enqueue.
            // Preserve the reference's deterministic dependency order, not the
            // HashSet iteration order of requiredChunks().
            Set<Long> required = DeferredSchematicMetadata.requiredChunks(columns);
            long[] halo = new long[required.size()];
            int haloIndex = 0;
            for (Long id : required) halo[haloIndex++] = id.longValue();
            Arrays.sort(halo);
            DeferredSchematicMetadata.enqueue(generator, new DeferredSchematicMetadata.Placement(
                    this.file.getAbsolutePath(), base.X, base.Y, streetLevel, blockY,
                    coordsOfTiles(tileNbt), coordsOfHeads(texturedHeads),
                    coordsOfBlocks(chests), coordsOfBlocks(spawners), halo));
         }
         World world = generator.getWorld();
         // WorldEdit can finish a neighbour that LSS has already served. Notify only
         // the columns actually pasted (large schematics are one-chunk slices).
         if (chunkX != null && chunkZ != null) {
            LodCompletionSync.changed(world, chunkX, chunkZ);
         } else {
            Region pasted = this.loadClipboard().getRegion();
            LodCompletionSync.changedFootprint(world, base.X, base.Y, pasted.getWidth(), pasted.getLength());
         }
      } catch (WorldEditException e) {
         Minions.w("Schematic paste failed; refusing to mark the slice complete.");
         Minions.e(e);
         throw new IllegalStateException("Schematic paste failed for " + this.groupId, e);
      }
   }

   private record TileNbtRestore(Cartesian3D local, BaseBlock original) {}

   private static int[] coordsOfBlocks(List<Cartesian3D> values) {
      int[] a = new int[values.size() * 3];
      int i=0;
      for (Cartesian3D p : values) { a[i++]=p.X; a[i++]=p.Y; a[i++]=p.Z; }
      return a;
   }
   private static int[] coordsOfTiles(List<TileNbtRestore> values) {
      List<Cartesian3D> coords = new ArrayList<>();
      for (TileNbtRestore p : values) coords.add(p.local());
      return coordsOfBlocks(coords);
   }
   private static int[] coordsOfHeads(List<SkinnedHeadRestore> values) {
      List<Cartesian3D> coords = new ArrayList<>();
      for (SkinnedHeadRestore p : values) coords.add(p.local());
      return coordsOfBlocks(coords);
   }
   private static List<Cartesian3D> restoreCoords(int[] a) {
      if (a.length % 3 != 0) throw new IllegalArgumentException("Malformed metadata coordinate triples");
      List<Cartesian3D> result = new ArrayList<>(a.length / 3);
      for (int i=0;i<a.length;i+=3) result.add(new Cartesian3D(a[i],a[i+1],a[i+2]));
      return result;
   }

   /** Replay only source-authored block metadata, after the queue verifies its halo is loaded. */
   void restoreDeferredMetadata(MetropolisGenerator generator, DeferredSchematicMetadata.Placement job) {
      if (!file.getAbsolutePath().equals(job.source()))
         throw new IllegalStateException("Mismatched deferred schematic metadata source: " + job.source());
      com.sk89q.worldedit.extent.clipboard.Clipboard source = loadClipboard();
      BlockVector3 min = source.getRegion().getMinimumPoint();
      List<TileNbtRestore> tiles = new ArrayList<>();
      for (Cartesian3D p : restoreCoords(job.tile())) {
         BaseBlock block = source.getFullBlock(min.add(BlockVector3.at(p.X, p.Y, p.Z)));
         if (!block.hasNbtData()) throw new IllegalStateException("Missing source block NBT: " + job.source());
         tiles.add(new TileNbtRestore(p, block));
      }
      List<SkinnedHeadRestore> heads = new ArrayList<>();
      for (Cartesian3D p : restoreCoords(job.head())) {
         BaseBlock block = source.getFullBlock(min.add(BlockVector3.at(p.X, p.Y, p.Z)));
         SkinnedHeadPlacement.Skin skin = SkinnedHeadPlacement.read(block);
         if (skin != null) heads.add(new SkinnedHeadRestore(p, skin));
      }
      finishMetadata(generator, new Cartesian2D(job.baseX(), job.baseZ()), job.streetLevel(),
              job.blockY(), tiles, restoreCoords(job.chest()), restoreCoords(job.spawner()), heads);
   }

   private void finishMetadata(MetropolisGenerator generator, Cartesian2D base,
                               int streetLevel, int blockY, List<TileNbtRestore> tileNbt,
                               List<Cartesian3D> chests, List<Cartesian3D> spawners,
                               List<SkinnedHeadRestore> texturedHeads) {
      World world = generator.getWorld();
      if (!tileNbt.isEmpty()) {
         try (EditSession editSession = getEditSession(generator)) {
            for (TileNbtRestore entry : tileNbt) {
               Cartesian3D p = entry.local();
               int x=(base.X<<4)+p.X, y=blockY+p.Y, z=(base.Y<<4)+p.Z;
               // Never overwrite a subsequently modified block with an older schematic type.
               if (world.getBlockAt(x,y,z).getType() == BukkitAdapter.adapt(entry.original().getBlockType()))
                  editSession.setBlock(BlockVector3.at(x,y,z),entry.original());
            }
         } catch (WorldEditException e) {
            throw new IllegalStateException("Cannot restore block NBT for " + groupId,e);
         }
      }
         GridRandom rand = generator.getGridProvider().getGrid(base.X, base.Y).getRandom();
         Cartesian3D base3 = new Cartesian3D(base.X << 4, blockY, base.Y << 4);
         // WorldEdit can drop the custom skull profile while setting blocks.
         // Reattach the original signed texture after the edit has flushed.
         // Apply only to the current paste slice; never load adjacent chunks.
         for (SkinnedHeadRestore head : texturedHeads) {
            Cartesian3D atHead = base3.add(head.local());
            SkinnedHeadPlacement.restore(world, atHead.X, atHead.Y, atHead.Z, head.skin());
         }
         if (PluginConfig.isChestRenaming()) {
            // Roll removal first. Only surviving chests define the vertical
            // gradient, so even low-rise schematics hit both configured endpoints.
            List<Cartesian3D> kept = new ArrayList<>();
            List<Integer> distances = new ArrayList<>();
            for(Cartesian3D c:chests) {
               Cartesian3D temp=base3.add(c);
               Block block=world.getBlockAt(temp.X,temp.Y,temp.Z);
               if(block.getType()!=Material.CHEST){Minions.d("Chest coordinates were wrong! ("+block+")");continue;}
               if(!rand.getChance(this.config.getChestOdds())){
                  block.setType(Material.AIR,false);
               }else {
                  kept.add(c);
                  distances.add(Math.abs(temp.Y-streetLevel));
               }
            }
            Map<Integer,Integer> levels=RankedChestLevels.levels(distances,
                  this.config.getLootMinLevel(),this.config.getLootMaxLevel());
            for(Cartesian3D c:kept) {
               Cartesian3D temp=base3.add(c);
               Block block=world.getBlockAt(temp.X,temp.Y,temp.Z);
               if(block.getType()!=Material.CHEST)continue;
               try {
                  Chest chest=(Chest)block.getState();
                  String name=DirtyHacks.getChestName(chest);
                  // Retain the chosen category and any existing special prefix;
                  // replace only the level. Existing valid digits aren't exempt.
                  name=this.validateChestName(rand,name,temp.Y);
                  int level=levels.getOrDefault(Math.abs(temp.Y-streetLevel),this.config.getLootMinLevel());
                  name=name.replaceFirst("_[1-5]$","_"+level);
                  this.nameChest(chest,name);
                  chest.update();
               }catch(NullPointerException ex){Minions.d("NPE while naming chest.");}
            }
         }

         if (PluginConfig.isSpawnerPlacing()) {
            for (Cartesian3D c : spawners) {
               Cartesian3D temp = base3.add(c);
               Block block = world.getBlockAt(temp.X, temp.Y, temp.Z);
               if (block.getType() == Material.SPONGE) {
                  if (!rand.getChance(this.config.getSpawnerOdds())) {
                     block.setType(Material.AIR, false);
                  } else {
                     block.setType(Material.SPAWNER, false);
                     if (block.getState() instanceof CreatureSpawner) {
                        CreatureSpawner spawner = (CreatureSpawner)block.getState();
                        spawner.setSpawnedType(this.getSpawnedEntity(rand));
                        spawner.update();
                     }
                  }
               } else {
                  Minions.w("Spawner coordinates were wrong!");
               }
            }
         }
         // R30ea marks every chunk touched by a restored source tile entity.
         // This is original metadata completion, not a cleanup/repaint pass.
         Set<Long> tileChunks = new HashSet<>();
         for (TileNbtRestore entry : tileNbt) {
            int x = ((base.X << 4) + entry.local().X) >> 4;
            int z = ((base.Y << 4) + entry.local().Z) >> 4;
            tileChunks.add(DeferredSchematicMetadata.key(x, z));
         }
         for (Long key : tileChunks) {
            LodCompletionSync.changed(world, (int)(key >> 32), key.intValue());
         }
   }

   @Override
   public Cartesian3D getSize() {
      int generation = cacheGeneration;
      Cartesian3D known = this.size;
      if (known != null && this.sizeGeneration == generation) {
         return known;
      }
      Region region = this.loadClipboard().getRegion();
      known = new Cartesian3D(region.getWidth(), region.getHeight(), region.getLength());
      this.size = known;
      this.sizeGeneration = generation;
      return known;
   }

   private record SkinnedHeadRestore(Cartesian3D local, SkinnedHeadPlacement.Skin skin) { }

   private void place(MetropolisGenerator generator, EditSession editSession, BlockVector3 pos,
                      List<Cartesian3D> chests, List<Cartesian3D> spawners,
                      List<SkinnedHeadRestore> texturedHeads, List<TileNbtRestore> tileNbt,
                      Cartesian2D base, Integer chunkX, Integer chunkZ) throws MaxChangedBlocksException {
      com.sk89q.worldedit.extent.clipboard.Clipboard clipboard = this.loadClipboard();
      Region region = clipboard.getRegion();
      // The clipboard's own coordinate space is NOT guaranteed to start at
      // (0,0,0) -- especially after the rotate/flip transforms applied at
      // import time (see ClipboardImporter.applyTransform()), which can
      // shift the region's bounding box. min is the actual origin corner
      // to read from; the 0-based (x,y,z) loop below is purely local
      // offset math for both the READ point (min + offset) and the PASTE
      // destination (pos + offset), matching how the old CuboidClipboard's
      // implicitly-0-based local array worked.
      BlockVector3 min = region.getMinimumPoint();
      int width = region.getWidth();
      int height = region.getHeight();
      int length = region.getLength();
      EnvironmentProvider natureDecay = generator.getNatureDecayProvider();

      final int firstX = chunkX == null ? 0 : Math.max(0, (chunkX - base.X) * 16);
      final int firstZ = chunkZ == null ? 0 : Math.max(0, (chunkZ - base.Y) * 16);
      final int lastX = chunkX == null ? width : Math.min(width, firstX + 16);
      final int lastZ = chunkZ == null ? length : Math.min(length, firstZ + 16);
      for (int x = firstX; x < lastX; x++) {
         for (int y = 0; y < height; y++) {
            for (int z = firstZ; z < lastZ; z++) {
               BlockVector3 offset = BlockVector3.at(x, y, z);
               BlockVector3 vec = offset.add(pos);
               Material decay = natureDecay.checkBlock(generator.getWorld(), vec.x(), vec.y(), vec.z());
               if (decay != null) {
                  // Deliberately not pasting: the decayed position keeps
                  // whatever the base terrain generator put there (the lava
                  // sea). The clipboard is cached and shared between pastes,
                  // so nothing here may modify it -- and BaseBlock is
                  // immutable now, which enforces that. (The old code built
                  // a throwaway BaseBlock for this case that nothing ever
                  // consumed; it was always effectively a skip.)
                  continue;
               }

               // getFullBlock(), not getBlock(): only the full block carries
               // tile-entity data (sign text, banners, heads...).
               BaseBlock block = clipboard.getFullBlock(min.add(offset));
               BlockType type = block.getBlockType();
               if (type == BlockTypes.PLAYER_HEAD || type == BlockTypes.PLAYER_WALL_HEAD) {
                  SkinnedHeadPlacement.Skin skin = SkinnedHeadPlacement.read(block);
                  if (skin == null) {
                     // Unknown/unskinned player-head textures must not turn
                     // into generic Steve heads. Leave legitimate mob skulls alone.
                     editSession.setBlock(vec, BlockTypes.AIR.getDefaultState());
                     continue;
                  }
                  texturedHeads.add(new SkinnedHeadRestore(new Cartesian3D(x, y, z), skin));
               }
               if (type == BlockTypes.CHEST) {
                  chests.add(new Cartesian3D(x, y, z));
               } else if (type == BlockTypes.SPONGE || type == BlockTypes.WET_SPONGE) {
                  spawners.add(new Cartesian3D(x, y, z));
               }

               if (block.hasNbtData()) {
                  tileNbt.add(new TileNbtRestore(new Cartesian3D(x, y, z), block));
               }
               // R13.3: WorldEdit on Paper 26.2 applies BaseBlock NBT at
               // EditSession.close(), *inside* chunk population. TileEntity
               // setChanged() then requests neighbour chunks synchronously;
               // while those chunks are generating the main thread can park
               // forever (see watchdog at [-8,226]). The existing durable
               // metadata queue already waits for and tickets that halo.
               // Paste the EXACT block state now, NBT only through that queue.
               editSession.setBlock(vec, block.toImmutableState());
            }
         }
      }
   }

   @Override
   public int getBottom(int streetLevel) {
      return streetLevel - this.config.getGroundLevelY();
   }

   @Override
   public SchematicConfig getConfig() {
      return this.config;
   }

   @Override
   public String getGroupId() {
      return this.groupId;
   }

   @Override
   public boolean isMirrored() {
      return this.mirrored;
   }

   private EditSession getEditSession(MetropolisGenerator generator) {
      // See ClipboardWE.getEditSession(): plain WorldEdit API now, no FAWE
      // fastmode builder. If FAWE's per-paste history setup causes
      // escalating stalls, disable history in FAWE's own config.
      EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(generator.getWorld()));
      // WorldEdit's DEFAULT side effects are all on: every pasted block updates
      // its neighbours (NEIGHBORS, UPDATE), re-validates, refreshes points of
      // interest, and so on. Over a whole building that is a flood of block
      // updates -- the server's "Too many chained neighbor updates" error, and
      // the main thread stuck for tens of seconds, came from exactly this. The
      // old FAWE fastmode skipped all of it. So: everything off, except the two
      // that are actually needed -- LIGHTING (a pasted building must be lit)
      // and NETWORK (players must be sent the change).
      session.setSideEffectApplier(SideEffectSet.none()
              .with(SideEffect.LIGHTING, SideEffect.State.ON)
              .with(SideEffect.NETWORK, SideEffect.State.ON));
      // No undo history. A plain session records every changed block (and reads
      // the block it replaces) so a player could //undo it; the watchdog stack
      // traces of the stuck paste were all inside ChangeSetExtent /
      // BlockOptimizedHistory, and a generated building is never undone.
      session.setTrackingHistory(false);
      // No reorder pass either: it exists to place torches, doors and the like
      // after the blocks they hang on, to survive the neighbour updates that
      // are switched off above. (ReorderMode is deprecated but is what turns
      // the post-edit simulation off; it does not touch the side-effect set.)
      session.setReorderMode(EditSession.ReorderMode.NONE);
      return session;
   }

   private EntityType getSpawnedEntity(GridRandom random) {
      if (this.config.getSpawners() == null) {
         if (this.globalConfig.getSpawners() == null) {
            return EntityType.ZOMBIE;
         }

         this.config.setSpawners(this.globalConfig.getSpawners());
      }

      return this.config.getRandomSpawnerEntity(random);
   }

   private void nameChest(Chest chest, String name) {
      DirtyHacks.setChestName(chest, name);
   }

   private String validateChestName(GridRandom rand, String name, int worldY) {
      char lastchar = name.charAt(name.length() - 1);

      boolean fail;
      try {
         int level = Integer.parseInt(String.valueOf(lastchar));
         fail = level > 5 || level < 1;
      } catch (NumberFormatException e) {
         fail = true;
      }

      if (fail) {
         if (lastchar == '_') {
            name = name + Integer.toString(this.randomChestLevel(rand, worldY));
         } else {
            name = this.getNameAndLevel(rand, worldY);
         }
      }

      // getNameAndLevel() already prefixes with the '\u00a7' + COLOR marker
      // ChestLootz's own validateChest() requires (name.startsWith("\u00a7")).
      // This early-return path -- the pre-existing name (set by an earlier
      // world-gen step, before this paste-time relabeling) already ends in
      // a valid level digit, e.g. "military_3" -- returned that name
      // completely unmodified, silently missing the same prefix. Any chest
      // that took this path was invisible to ChestLootz's name check and
      // never got any loot rolled into it, even though the name otherwise
      // looked entirely valid in the debug log.
      if (!name.startsWith("\u00a7")) {
         name = "\u00a7" + COLOR + name;
      }

      return name;
   }

   private String getNameAndLevel(GridRandom rand, int worldY) {
      StringBuffer buf = new StringBuffer();
      if (this.config.getLootCollections().length > 0) {
         buf.append('§').append(COLOR).append(this.config.getRandomLootCollection(rand).name).append('_').append(Integer.toString(this.randomChestLevel(rand, worldY)));
         return buf.toString();
      } else {
         return "";
      }
   }

   private int randomChestLevel(GridRandom random, int worldY) {
      int min = this.config.getLootMinLevel();
      int max = this.config.getLootMaxLevel();

      int[] weights = new int[5];
      for (int i = 0; i < weights.length; i++) {
         weights[i] = this.globalConfig.getChestLevelWeight(i);
      }

      // See the identical wiring (and the reasoning behind it) in
      // ClipboardWE.randomChestLevel() -- this method previously called
      // globalConfig.getRandomChestLevel(random, min, max) directly, which
      // has no idea what Y a chest is at. Since buildings are loaded through
      // FileClipboard (not ClipboardWE -- see ClipboardLoaderMkII.loadBuilds()
      // vs loadStreet()), that meant the vertical loot bias never actually
      // applied to any building chest, ground floor or penthouse alike.
      VerticalLootBias bias = new VerticalLootBias(
              PluginConfig.isVerticalLootBiasEnabled(),
              PluginConfig.getBuildHeight(),
              PluginConfig.getVerticalLootMaxDistance(),
              PluginConfig.getVerticalLootDelineation()
      );
      return bias.selectLootLevel(worldY, min, max, weights, random);
   }

   private int estimateStreetLevel() {
      com.sk89q.worldedit.extent.clipboard.Clipboard clipboard = this.loadClipboard();
      Region region = clipboard.getRegion();
      BlockVector3 min = region.getMinimumPoint();
      int height = region.getHeight();
      if (height - 2 < 0) {
         return 1;
      }

      for (int y = height - 2; y >= 0; y--) {
         BlockType type = clipboard.getBlock(min.add(0, y, 0)).getBlockType();
         if (!ClipboardBlocks.isAirOrGroundCover(type)) {
            return y + 1;
         }
      }

      return 1;
   }

   @Override
   public String toString() {
      return "Clipboard: " + this.config.getPath();
   }
}