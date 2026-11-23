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
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Set;
import java.io.File;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;

public class ClipboardWE implements Clipboard {
   // WorldEdit's modern com.sk89q.worldedit.extent.clipboard.Clipboard,
   // referenced fully-qualified throughout this file rather than imported
   // -- it shares its simple name with THIS class's own Clipboard interface
   // (ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard, same package,
   // implemented by this class), so importing WorldEdit's would shadow
   // that and silently break `implements Clipboard` below. Same pattern
   // as FileClipboard.
   //
   // Unlike FileClipboard, this one is loaded once up front by
   // ClipboardLoaderMkII.loadStreet() and handed in already-parsed --
   // STREET schematics aren't lazily re-loaded/cached per path the way
   // building schematics are, so there's no equivalent of loadClipboard()
   // here, just this field.
   private com.sk89q.worldedit.extent.clipboard.Clipboard clipboard;
   private SchematicConfig config;
   private GlobalSchematicConfig globalConfig;
   // chests / spawners are NOT fields: one Clipboard is shared by every paste of it, and a paste can re-enter itself (see paste()).
   private int blockCount;
   private String groupId;
   private final String metadataSource;
   private static final char COLOR = ChatColor.GREEN.getChar();

   public ClipboardWE(com.sk89q.worldedit.extent.clipboard.Clipboard clipboard, SchematicConfig config, GlobalSchematicConfig globalConfig, String groupId) {
      this(clipboard, config, globalConfig, groupId, null);
   }

   public ClipboardWE(com.sk89q.worldedit.extent.clipboard.Clipboard clipboard, SchematicConfig config, GlobalSchematicConfig globalConfig, String groupId, File sourceFile) {
      this.metadataSource = sourceFile == null ? null : sourceFile.getAbsolutePath();
      this.clipboard = clipboard;
      this.config = config;
      this.globalConfig = globalConfig;
      Region region = clipboard.getRegion();
      this.blockCount = region.getWidth() * region.getHeight() * region.getLength();
      this.groupId = groupId;
      if (config.getGroundLevelY() == 0) {
         config.setGroundLevelY(this.estimateStreetLevel());
      }
   }

   @Override
   public void paste(MetropolisGenerator generator, Cartesian2D base, int streetLevel) {
      int blockY = this.getBottom(streetLevel);
      BlockVector3 at = BlockVector3.at(base.X << 4, blockY, base.Y << 4);

      try {
         // place() only QUEUES block changes via editSession.setBlock() --
         // they aren't guaranteed to actually be applied to the world until
         // the session is flushed. Closing it (the try-with-resources below;
         // this replaces the old flushQueue() call, which no longer exists)
         // is what flushes it. Without this, the road's own blocks are
         // still sitting in the queue (not yet in the world) when
         // RoadParcel/HighwayParcel.postPopulate() goes on to run decay and
         // then notifyNeighboursToRecut() -> makeCutoutsToward(). The cutout
         // itself writes directly and immediately, so it visibly "finishes"
         // before the road's own paste has actually landed -- and whenever
         // this queue does eventually flush, it repaints the solid schematic
         // straight back over the hole that was just cut, since the queue
         // has no idea a cutout happened in the meantime. See the identical
         // fix (and explanation) in FileClipboard.paste().
         // Per-paste lists. place() used to fill shared fields, and the session flush / getBlockAt() below can load a neighbouring chunk,
         // whose populate pastes this same clipboard again (place() clearing and refilling the list under this loop): a
         // ConcurrentModificationException, or the wrong chests. Locals cannot be touched by a nested paste.
         List<Cartesian3D> chests = new ArrayList<>();
         List<Cartesian3D> spawners = new ArrayList<>();
         List<Cartesian3D> tileNbt = new ArrayList<>();
         List<Cartesian3D> heads = new ArrayList<>();
         try (EditSession editSession = this.getEditSession(generator)) {
            this.place(generator, editSession, at, chests, spawners, tileNbt, heads);
         }
         // R13.3: STREET metadata must not be restored while a road chunk is
         // populating. A source-authored sign, chest or other block entity can
         // request an ungenerated neighbour while WorldEdit flushes its NBT.
         // Reuse R30ea's persistent metadata spool, with the STREET.schem file
         // registered as a FileClipboard restoration source on import.
         if (!tileNbt.isEmpty() || !heads.isEmpty() || !chests.isEmpty() || !spawners.isEmpty()) {
            if (metadataSource == null)
               throw new IllegalStateException("STREET metadata source not registered: " + this.groupId);
            List<int[]> columns = new ArrayList<>();
            for (Cartesian3D c : tileNbt) columns.add(new int[] {(base.X << 4) + c.X, (base.Y << 4) + c.Z});
            for (Cartesian3D c : heads) columns.add(new int[] {(base.X << 4) + c.X, (base.Y << 4) + c.Z});
            for (Cartesian3D c : chests) columns.add(new int[] {(base.X << 4) + c.X, (base.Y << 4) + c.Z});
            for (Cartesian3D c : spawners) columns.add(new int[] {(base.X << 4) + c.X, (base.Y << 4) + c.Z});
            Set<Long> required = DeferredSchematicMetadata.requiredChunks(columns);
            long[] halo = new long[required.size()];
            int h = 0;
            for (Long id : required) halo[h++] = id.longValue();
            Arrays.sort(halo);
            DeferredSchematicMetadata.enqueue(generator, new DeferredSchematicMetadata.Placement(
                  metadataSource, base.X, base.Y, streetLevel, blockY,
                  triples(tileNbt), triples(heads), triples(chests), triples(spawners), halo));
         }
         World world = generator.getWorld();
         Region region = this.clipboard.getRegion();
         LodCompletionSync.changedFootprint(world, base.X, base.Y, region.getWidth(), region.getLength());
      } catch (WorldEditException e) {
         Minions.w("Placing schematic failed. WorldEdit fucked up.");
         Minions.e(e);
      }
   }

   @Override
   public Cartesian3D getSize() {
      Region region = this.clipboard.getRegion();
      return new Cartesian3D(region.getWidth(), region.getHeight(), region.getLength());
   }

   private static int[] triples(List<Cartesian3D> values) {
      int[] coords = new int[values.size() * 3];
      int i = 0;
      for (Cartesian3D c : values) {
         coords[i++] = c.X;
         coords[i++] = c.Y;
         coords[i++] = c.Z;
      }
      return coords;
   }

   private void place(MetropolisGenerator generator, EditSession editSession, BlockVector3 pos,
                      List<Cartesian3D> chests, List<Cartesian3D> spawners,
                      List<Cartesian3D> tileNbt, List<Cartesian3D> heads) throws MaxChangedBlocksException {
      Region region = this.clipboard.getRegion();
      // The clipboard's own coordinate space is NOT guaranteed to start at
      // (0,0,0) -- min is the actual origin corner to read from; the
      // 0-based (x,y,z) loop below is purely local offset math for both
      // the READ point (min + offset) and the PASTE destination
      // (pos + offset). Matches FileClipboard.place()'s identical pattern.
      BlockVector3 min = region.getMinimumPoint();
      int width = region.getWidth();
      int height = region.getHeight();
      int length = region.getLength();
      EnvironmentProvider natureDecay = generator.getNatureDecayProvider();

      for (int x = 0; x < width; x++) {
         for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
               BlockVector3 offset = BlockVector3.at(x, y, z);
               BlockVector3 vec = offset.add(pos);
               Material decay = natureDecay.checkBlock(generator.getWorld(), vec.x(), vec.y(), vec.z());
               if (decay != null) {
                  // Deliberately not pasting here: the decayed position is
                  // left as whatever the base terrain generator already put
                  // there (the lava sea) rather than pasting over it. (This
                  // used to build a throwaway BaseBlock just to avoid
                  // mutating the shared instance the clipboard hands back;
                  // BaseBlock is immutable now and nothing consumed that
                  // result anyway, so the branch is simply a skip.)
                  continue;
               }

               // getFullBlock(), not getBlock(): only the full block carries
               // tile-entity data (sign text, banners, heads...).
               BaseBlock block = this.clipboard.getFullBlock(min.add(offset));
               BlockType type = block.getBlockType();
               if (type == BlockTypes.PLAYER_HEAD || type == BlockTypes.PLAYER_WALL_HEAD) {
                  if (SkinnedHeadPlacement.read(block) == null) {
                     editSession.setBlock(vec, BlockTypes.AIR.getDefaultState());
                     continue;
                  }
                  heads.add(new Cartesian3D(x, y, z));
               }
               if (type == BlockTypes.CHEST) {
                  chests.add(new Cartesian3D(x, y, z));
               } else if (type == BlockTypes.SPONGE || type == BlockTypes.WET_SPONGE) {
                  spawners.add(new Cartesian3D(x, y, z));
               }
               if (block.hasNbtData()) tileNbt.add(new Cartesian3D(x, y, z));
               // Only the state is safe during chunk population. Source NBT
               // is restored by DeferredSchematicMetadata after halo load.
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
      // ClipboardWE is only ever used for STREET-type schematics (see
      // ClipboardLoaderMkII.loadStreet()) -- streets don't go through the
      // rotate-and-mirror caching ClipboardImporter does for buildings, so
      // there's no mirrored variant concept here at all.
      return false;
   }

   private EditSession getEditSession(MetropolisGenerator generator) {
      // Plain WorldEdit API now: the FAWE-only EditSessionBuilder.fastmode()
      // is gone, and so is the reason it was there. The old comment here
      // explained how FAWE's undo-history setup (a disk scan that grew
      // slower with every paste) made per-paste sessions progressively
      // slower. If you run FAWE and see the same escalating stalls on this
      // call, turn FAWE's history off in its own config (history.use-disk /
      // the per-session history limits) rather than looking for an API
      // switch here -- there is no portable one, and world-gen pastes never
      // need undo.
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

      return name;
   }

   private String getNameAndLevel(GridRandom rand, int worldY) {
      StringBuffer buf = new StringBuffer();
      if (this.config.getLootCollections().length > 0) {
         buf.append('§').append(COLOR).append(this.config.getRandomLootCollection(rand).name).append('_').append(Integer.toString(this.randomChestLevel(rand, worldY)));
         return buf.toString();
      } else {
         return "LENGTH!>0";
      }
   }

   private int randomChestLevel(GridRandom random, int worldY) {
      int min = this.config.getLootMinLevel();
      int max = this.config.getLootMaxLevel();

      int[] weights = new int[5];
      for (int i = 0; i < weights.length; i++) {
         weights[i] = this.globalConfig.getChestLevelWeight(i);
      }

      VerticalLootBias bias = new VerticalLootBias(
              PluginConfig.isVerticalLootBiasEnabled(),
              PluginConfig.getBuildHeight(),
              PluginConfig.getVerticalLootMaxDistance(),
              PluginConfig.getVerticalLootDelineation()
      );
      return bias.selectLootLevel(worldY, min, max, weights, random);
   }

   private int estimateStreetLevel() {
      Region region = this.clipboard.getRegion();
      BlockVector3 min = region.getMinimumPoint();
      int height = region.getHeight();
      if (height - 2 < 0) {
         return 1;
      }

      for (int y = height - 2; y >= 0; y--) {
         BlockType type = this.clipboard.getBlock(min.add(0, y, 0)).getBlockType();
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