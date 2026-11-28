package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.generator.LodCompletionSync;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.District;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanProvider;
import ch.k42.metropolis.grid.urbanGrid.parcel.BridgeParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.OceanParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.RiverParcel;
import ch.k42.metropolis.grid.urbanGrid.parcel.StreetParcel;
import ch.k42.metropolis.minions.DecayOption;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.lang.ref.WeakReference;
import java.util.PriorityQueue;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.noise.SimplexOctaveGenerator;

public class DecayProviderNormal extends DecayProvider {
   private static final BlockFace[] HORIZONTAL_FACES = {
           BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
   };
   private static final BlockFace[] ALL_FACES = {
           BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
   };

   // The pre-1.13 list named GRASS (the grass BLOCK), LONG_GRASS (the ground
   // plants), DOUBLE_PLANT, LEAVES/LEAVES_2, LOG/LOG_2 and the STATIONARY_
   // copies of water and lava. Each of those is a family of materials now.
   private static Set<Material> unsupportingBlocks = ImmutableSet.<Material>builder()
           .addAll(Mats.LEAVES)
           .addAll(Mats.TALL_PLANTS)
           .add(Material.GRASS_BLOCK)
           .addAll(Mats.SHORT_PLANTS)
           .add(Material.VINE)
           .addAll(Mats.LOGS)
           .add(Material.WATER)
           .add(Material.LAVA)
           .build();
   private static Set<Material> invalidBlocks = ImmutableSet.<Material>builder().addAll(unsupportingBlocks).build();
   // Deliberately its OWN set, separate from unsupportingBlocks: a block
   // in here is still a perfectly normal isValid()/isSupporting() block --
   // it still decays under destroyWithin()'s noise pass and still hosts
   // vines exactly as before (see isVineSuitableSurface(), which has its
   // own, independent "GLASS" exclusion) -- it's ONLY excluded from being
   // counted as a branch/slime growth candidate. Read via
   // isBranchableSupport() below, which DecayProviderBranch.
   // collectRawGrowthCandidates() calls alongside its existing
   // isThinStandaloneSupport() filter.
   private static Set<Material> nonBranchSupportBlocks = ImmutableSet.<Material>builder()
           .add(Material.GLASS_PANE)
           .addAll(Mats.STAINED_GLASS_PANES)
           .build();

   static final class GNode {
      final int x, y, z;
      GNode(int x, int y, int z) {
         this.x = x;
         this.y = y;
         this.z = z;
      }
   }

   static final class Ctx {
      BranchSupportBuffer supportHits = new BranchSupportBuffer();
      int boundsMinX, boundsMaxX, boundsMinY, boundsMaxY, boundsMinZ, boundsMaxZ;
      int footprintChunks = 1;
      UrbanGrid grid;
   }

   private final DecayProviderBranch branchGrower;
   private int readyGrowthBuilds;
   private long retainedSupportHitCount;
   private long retainedSupportBufferBytes;
   private long growthTimeoutTick;
   private long growthTimeoutSequence;
   private BukkitTask growthTimeoutTicker;
   private final PriorityQueue<GrowthTimeout> growthTimeouts =
           new PriorityQueue<>(Comparator.comparingLong(GrowthTimeout::deadlineTick));
   private final BranchGrowthWaitIndex<Parcel> growthWaitIndex = new BranchGrowthWaitIndex<>();

   private record GrowthTimeout(long deadlineTick, WeakReference<Parcel> build, long generation) {}
   private record ParcelDepth(Parcel parcel, int depth) {}

   public DecayProviderNormal(MetropolisGenerator generator, Random random) {
      super(generator, random);
      this.branchGrower = new DecayProviderBranch(this, random);
   }

   @Override
   public int pendingGrowthWorkCount() {
      return this.pendingBuilds.size();
   }

   @Override
   public int pendingGrowthRunnableWorkCount() {
      return Math.max(0, this.readyGrowthBuilds);
   }

   private void markGrowthReady(PendingBuild pending) {
      if (pending != null && pending.growthReady && !pending.readyCounted) {
         pending.readyCounted = true;
         this.readyGrowthBuilds++;
      }
   }

   private void markGrowthWaiting(PendingBuild pending) {
      if (pending != null && pending.readyCounted) {
         pending.readyCounted = false;
         this.readyGrowthBuilds = Math.max(0, this.readyGrowthBuilds - 1);
      }
   }

   private void scheduleGrowthTimeout(Parcel build, PendingBuild pending, int delayTicks) {
      if (delayTicks <= 0 || pending.timeoutGeneration != 0L || !this.mayScheduleTimeout(build)) return;
      long generation = ++this.growthTimeoutSequence;
      pending.timeoutGeneration = generation;
      this.growthTimeouts.add(new GrowthTimeout(
              this.growthTimeoutTick + delayTicks, new WeakReference<>(build), generation));
      if (this.growthTimeoutTicker == null) {
         this.growthTimeoutTicker = Bukkit.getScheduler().runTaskTimer(
                 this.generator.getPlugin(), this::tickGrowthTimeouts, 1L, 1L);
      }
   }

   /** One scheduler services every pending build; queue entries use weak Parcel references. */
   private void tickGrowthTimeouts() {
      if (!this.pluginEnabled()) {
         this.stopGrowthTimeoutTicker();
         return;
      }
      this.growthTimeoutTick++;
      while (!this.growthTimeouts.isEmpty()
              && this.growthTimeouts.peek().deadlineTick() <= this.growthTimeoutTick) {
         GrowthTimeout timeout = this.growthTimeouts.poll();
         Parcel build = timeout.build().get();
         if (build == null) continue;
         PendingBuild pending = this.pendingBuilds.get(build);
         if (pending == null || pending.timeoutGeneration != timeout.generation()) continue;
         pending.timeoutGeneration = 0L;
         this.flushBuildOnTimeout(build);
      }
      if (this.growthTimeouts.isEmpty()) this.stopGrowthTimeoutTicker();
   }

   private void stopGrowthTimeoutTicker() {
      if (this.growthTimeoutTicker != null) {
         this.growthTimeoutTicker.cancel();
         this.growthTimeoutTicker = null;
      }
      this.growthTimeouts.clear();
   }

   private void clearParcelWait(Parcel build, PendingBuild pending) {
      this.growthWaitIndex.clear(build);
   }

   private void registerParcelWait(Parcel build, PendingBuild pending, Set<Long> waitingCells) {
      this.growthWaitIndex.register(build, waitingCells);
   }

   private void removePendingBuild(Parcel build, PendingBuild pending) {
      if (!this.pendingBuilds.remove(build, pending)) return;
      this.clearParcelWait(build, pending);
      this.clearGrowthWait(build, pending);
      pending.timeoutGeneration = 0L;
      this.markGrowthWaiting(pending);
      this.retainedSupportHitCount = Math.max(0L,
              this.retainedSupportHitCount - pending.supportHits.size());
      this.retainedSupportBufferBytes = Math.max(0L,
              this.retainedSupportBufferBytes - pending.supportHits.retainedBytes());
      pending.supportHits.releaseStorage();
      this.reportPendingGrowthWork();
   }

   private void reportPendingGrowthWork() {
      World world = this.generator != null ? this.generator.getWorld() : null;
      ch.k42.metropolis.generator.ChunkyMemoryValve.growthPending(world, this.pendingBuilds.size());
      ch.k42.metropolis.generator.ChunkyMemoryValve.growthRunnablePending(
              world, this.pendingGrowthRunnableWorkCount());
      ch.k42.metropolis.generator.ChunkyMemoryValve.growthStorage(
              world, this.retainedSupportHitCount, this.retainedSupportBufferBytes);
   }

   Block safeRelative(Block block, int dx, int dy, int dz) {
      int x = block.getX() + dx;
      int z = block.getZ() + dz;
      if (!block.getWorld().isChunkLoaded(x >> 4, z >> 4)) {
         return null;
      }
      return block.getRelative(dx, dy, dz);
   }

   Block safeRelative(Block block, BlockFace face) {
      return this.safeRelative(block, face.getModX(), face.getModY(), face.getModZ());
   }

   /**
    * generateGrowth() places blocks (branches sagging/chaining across
    * streets and gaps) well outside the single chunk whose postPopulate()
    * eventually triggered it -- that's the entire point of tile/district-
    * wide growth. Every OTHER write path in this plugin (ClipboardParcel,
    * RoadParcel, HighwayParcel, BridgeParcel) calls world.refreshChunk()
    * for its own chunk right after writing to it, so already-sent chunks
    * get resent to nearby clients. Growth had no equivalent: it runs
    * later, into chunks that were very often already populated (and
    * already sent to a nearby player) by the time the tile/district
    * finally completed or timed out. Without this, the branch blocks are
    * genuinely written server-side but silently never shown to anyone
    * already near them -- indistinguishable from "no branches generated"
    * during ordinary live testing.
    */
   private void refreshChunksInBounds(World world, Ctx ctx) {
      if (world == null) {
         return;
      }
      int chunkMinX = ctx.boundsMinX >> 4;
      int chunkMaxX = ctx.boundsMaxX >> 4;
      int chunkMinZ = ctx.boundsMinZ >> 4;
      int chunkMaxZ = ctx.boundsMaxZ >> 4;
      for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
         for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
            if (world.isChunkLoaded(cx, cz)) {
               world.refreshChunk(cx, cz);
               LodCompletionSync.changed(world, cx, cz);
            }
         }
      }
   }

   Block safeBlockAt(World world, int x, int y, int z) {
      if (!world.isChunkLoaded(x >> 4, z >> 4)) {
         return null;
      }
      return world.getBlockAt(x, y, z);
   }

   @Override
   public void destroyWithin(int x1, int x2, int y1, int y2, int z1, int z2, DecayOption options, UrbanGrid grid, District district) {
      Ctx ctx = new Ctx();
      ctx.grid = grid;

      if (y1 < 0) {
         y1 = 0;
      }

      if (y2 < 0) {
         y2 = 0;
      }

      int MAX = 254;
      if (y1 > MAX) {
         y1 = MAX;
      }

      if (y2 > MAX) {
         y2 = MAX;
      }

      int margin = PluginConfig.getBranchBoundsMargin();
      ctx.boundsMinX = x1 - margin;
      ctx.boundsMaxX = x2 + margin;
      ctx.boundsMinY = y1 - margin;
      ctx.boundsMaxY = y2 + margin;
      ctx.boundsMinZ = z1 - margin;
      ctx.boundsMaxZ = z2 + margin;

      int chunksX = (Math.max(1, x2 - x1) + 15) / 16;
      int chunksZ = (Math.max(1, z2 - z1) + 15) / 16;
      ctx.footprintChunks = Math.max(1, chunksX * chunksZ);

      double holeScale = options.getHoleScale();
      double fulldecay = options.getFullDecay();
      double partialdecay = options.getPartialDecay();
      World world = this.generator.getWorld();
      long seed = this.generator.getWorldSeed();
      SimplexOctaveGenerator simplexOctaveGenerator = this.decayNoise(seed);

      // Cached per chunk: the protection below is a property of the parcel
      // that owns the column, so it only needs looking up once per chunk
      // the volume passes through, not once per column.
      int protectedChunkX = Integer.MIN_VALUE;
      int protectedChunkZ = Integer.MIN_VALUE;
      int protectedTopY = Integer.MIN_VALUE;
      // Same per-chunk caching for the ocean water-line: destructive decay
      // (block -> air) is forbidden at or below this Y. See
      // oceanWaterlineTopY() for which chunks it applies to.
      int waterlineTopY = Integer.MIN_VALUE;

      for (int z = z1; z < z2; z++) {
         for (int x = x1; x < x2; x++) {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
               continue;
            }

            if ((x >> 4) != protectedChunkX || (z >> 4) != protectedChunkZ) {
               protectedChunkX = x >> 4;
               protectedChunkZ = z >> 4;
               protectedTopY = waterProtectionTopY(grid, protectedChunkX, protectedChunkZ);
               waterlineTopY = oceanWaterlineTopY(grid, protectedChunkX, protectedChunkZ);
            }

            for (int y = y1; y < y2; y++) {
               // Decay is never allowed to take anything out of the water:
               // see waterProtectionTopY() for which columns/heights this
               // covers.
               if (y <= protectedTopY) {
                  continue;
               }

               Block block = world.getBlockAt(x, y, z);
               if (this.isValid(block) && !block.isEmpty() && !options.getExceptions().contains(block.getType())) {
                  double noise = simplexOctaveGenerator.noise(x * holeScale, y * holeScale, z * holeScale, 0.3, 0.6, true);
                  // ...nor to open a hole INTO it: a block with water against
                  // it (bank, bed, a wall or pier at the waterline) is left
                  // alone, because removing it would let the water spill out.
                  if (noise > partialdecay && touchesWater(block)) {
                     continue;
                  }
                  // Under the ocean's water-line nothing may be DESTROYED
                  // (block -> air): that is what punches holes in hulls,
                  // quay walls and piers and hollows out submerged
                  // foundations, whether or not the block happens to touch
                  // water right now. Block-to-block transformation (cobble,
                  // moss, stairs) is not destructive and still applies.
                  boolean submerged = y <= waterlineTopY;
                  boolean decayed = noise > fulldecay;
                  if (decayed && submerged) {
                     continue;
                  }
                  if (decayed) {
                     block.setType(Material.AIR, false);
                  } else if (noise > partialdecay) {
                     this.applyPartialDecay(block, submerged);

                     // OLD pre-branch leaf methodology, opt-in and
                     // independent of branch/slime growth -- see
                     // addLegacyLeavesRec(). Deliberately gated the same
                     // way the original was: only for blocks that survived
                     // THIS partial-decay roll, not every supporting block
                     // destroyWithin() happens to find.
                     if (!submerged && PluginConfig.isLegacyLeafDecayEnabled() && this.isSupporting(block)) {
                        for (Block neighbour : this.getSixNeighbours(block)) {
                           this.addLegacyLeavesRec(neighbour, options, PluginConfig.getLegacyLeafDecayDepth());
                        }
                     }
                  }

                  // Nothing grows out of a submerged block (no vines, leaves
                  // or slime under the sea) -- same as the water-touching
                  // blocks skipped above.
                  if (!submerged && this.isSupporting(block)) {
                     ctx.supportHits.add(block.getX(), block.getY(), block.getZ(), decayed);
                  }
               }
            }
         }
      }

      Parcel build = grid != null ? grid.peekParcel(x1 >> 4, z1 >> 4) : null;
      if (build == null) {
         // No grid, or this cell somehow isn't registered as a parcel in
         // it (shouldn't happen -- destroyChunks() is only ever called
         // BY a parcel, for its own footprint -- but stay conservative
         // and match the old grid-less fallback exactly: run this decay
         // pass's own growth right now, nothing cached, nothing to wait
         // on).
         Minions.dNode("[BUILD][FALLBACK] no grid/parcel identity for this decay pass -- running its growth immediately instead of caching it.");
         this.branchGrower.generateGrowth(world, options, ctx);
         for (int i = 0; i < ctx.supportHits.size(); i++) {
             this.addVines(world.getBlockAt(ctx.supportHits.x(i), ctx.supportHits.y(i), ctx.supportHits.z(i)),
                     options, ctx.supportHits.decayed(i));
          }
         this.refreshChunksInBounds(world, ctx);
         return;
      }

      this.cacheBuildGrowth(build, ctx, world, options);
      this.tryFlushBuild(build);
   }

   /**
    * One build's (one Parcel's) own cached growth candidates, waiting on
    * its neighbourhood to finish placing before DecayProviderBranch
    * actually rolls anything. Keyed by Parcel identity, not chunk
    * coordinates or District, so a multi-chunk building's several
    * destroyChunks() calls (should it ever make more than one) merge into
    * the same entry rather than each flushing alone. Replaces
    * DistrictAccumulator/TileAccumulator -- same shape, but there's only
    * ever one of these per BUILD now, never per district or per tile.
    */
   private static final class PendingBuild {
      BranchSupportBuffer supportHits = new BranchSupportBuffer(0);
      int chunkArea = 0;
      World world;
      DecayOption options;
      UrbanGrid grid;
      int boundsMinX = Integer.MAX_VALUE, boundsMaxX = Integer.MIN_VALUE;
      int boundsMinY = Integer.MAX_VALUE, boundsMaxY = Integer.MIN_VALUE;
      int boundsMinZ = Integer.MAX_VALUE, boundsMaxZ = Integer.MIN_VALUE;
      long timeoutGeneration;
      boolean growthReady;
      boolean readyCounted;
      final Set<Long> waitingChunks = new HashSet<>();
   }

   // IdentityHashMap-backed: Parcel doesn't override equals()/hashCode(),
   // so a plain HashMap would behave the same today, but this makes the
   // "one entry per actual Parcel OBJECT, not per coordinate" intent
   // explicit -- see District.assignDistrictMembership()'s old note about
   // exactly this hazard with multi-chunk buildings.
   private final Map<Parcel, PendingBuild> pendingBuilds = new IdentityHashMap<>();
   // R30ea's reverse dependency index (still strongly retains pending builds;
   // NOT the future durable ledger). Only the unchanged R30ea semantics are
   // restored here; the world-key collision risk is tracked in R07 QA.
   private final Map<Long, Set<Parcel>> growthWaitingForChunk = new java.util.HashMap<>();

   private static long growthChunkKey(int chunkX, int chunkZ) {
      return ((long) chunkX << 32) ^ ((long) chunkZ & 0xffffffffL);
   }

   private void clearGrowthWait(Parcel build, PendingBuild pending) {
      for (long key : pending.waitingChunks) {
         Set<Parcel> waiting = this.growthWaitingForChunk.get(key);
         if (waiting == null) {
            continue;
         }
         waiting.remove(build);
         if (waiting.isEmpty()) {
            this.growthWaitingForChunk.remove(key);
         }
      }
      pending.waitingChunks.clear();
   }

   public boolean hasPendingGrowthAt(int chunkX, int chunkZ) {
      Set<Parcel> waiting = this.growthWaitingForChunk.get(growthChunkKey(chunkX, chunkZ));
      return waiting != null && !waiting.isEmpty();
   }

   public void onChunkLoaded(World world, int chunkX, int chunkZ) {
      Set<Parcel> waiting = this.growthWaitingForChunk.get(growthChunkKey(chunkX, chunkZ));
      if (waiting == null || waiting.isEmpty()) {
         return;
      }
      for (Parcel build : new ArrayList<>(waiting)) {
         PendingBuild pending = this.pendingBuilds.get(build);
         if (pending != null && pending.growthReady && pending.world == world) {
            this.flushBuild(build, pending);
         }
      }
      this.reportPendingGrowthWork();
   }

   /**
    * Caches this decay pass's growth candidates against its own build
    * (Parcel) rather than flushing them immediately. Never flushes by
    * itself -- the caller (destroyWithin()) always follows this with
    * tryFlushBuild(build) so a build whose neighbourhood already happens
    * to be ready doesn't sit cached for no reason.
    */
   private void cacheBuildGrowth(Parcel build, Ctx ctx, World world, DecayOption options) {
      if (ctx.supportHits.isEmpty()) return;
      PendingBuild acc = this.pendingBuilds.computeIfAbsent(build, b -> new PendingBuild());
      long oldBytes = acc.supportHits.retainedBytes();
      acc.supportHits.addAll(ctx.supportHits);
      this.retainedSupportHitCount += ctx.supportHits.size();
      this.retainedSupportBufferBytes += acc.supportHits.retainedBytes() - oldBytes;
      acc.chunkArea += ctx.footprintChunks;
      acc.world = world;
      acc.options = options;
      acc.grid = ctx.grid;
      acc.boundsMinX = Math.min(acc.boundsMinX, ctx.boundsMinX);
      acc.boundsMaxX = Math.max(acc.boundsMaxX, ctx.boundsMaxX);
      acc.boundsMinY = Math.min(acc.boundsMinY, ctx.boundsMinY);
      acc.boundsMaxY = Math.max(acc.boundsMaxY, ctx.boundsMaxY);
      acc.boundsMinZ = Math.min(acc.boundsMinZ, ctx.boundsMinZ);
      acc.boundsMaxZ = Math.max(acc.boundsMaxZ, ctx.boundsMaxZ);
      Minions.dNodeVerbose("[BUILD][CACHE] %s -- +%d compact supportHits (total=%d, buffer=%d bytes)",
              this.describeBuild(build), ctx.supportHits.size(), acc.supportHits.size(),
              acc.supportHits.retainedBytes());
      this.scheduleGrowthTimeout(build, acc, PluginConfig.getBranchFlushTimeoutTicks());
      this.reportPendingGrowthWork();
   }

   /**
    * Parcel readiness is event-driven through the exact coordinate index.
    * Only builds waiting on this parcel's footprint are reconsidered.
    */
   @Override
   public void onNeighbourPlaced(UrbanGrid grid, Parcel placed) {
      if (placed == null) return;
      Set<Parcel> affected = Collections.newSetFromMap(new IdentityHashMap<>());
      for (int x = placed.getChunkX(); x < placed.getChunkX() + placed.getChunkSizeX(); x++) {
         for (int z = placed.getChunkZ(); z < placed.getChunkZ() + placed.getChunkSizeZ(); z++) {
            affected.addAll(this.growthWaitIndex.waitingForCell(growthChunkKey(x, z)));
         }
      }
      for (Parcel build : affected) {
         PendingBuild pending = this.pendingBuilds.get(build);
         if (pending != null && !pending.growthReady) {
            this.clearParcelWait(build, pending);
            this.tryFlushBuild(build);
         }
      }
   }

   /** R30ea preserves cached candidates when a timeout fires before a parcel has placed. */
   private void flushBuildOnTimeout(Parcel build) {
      PendingBuild pending = this.pendingBuilds.get(build);
      if (pending == null || !this.pluginEnabled()) return;
      if (!build.isPopulated()) {
         this.scheduleGrowthTimeout(build, pending, 200);
         return;
      }
      this.clearParcelWait(build, pending);
      pending.growthReady = true;
      this.markGrowthReady(pending);
      this.reportPendingGrowthWork();
      Minions.dNode("[BUILD][TIMEOUT] %s -- readiness timeout; preserving packed candidates while support chunks load.",
              this.describeBuild(build));
      this.flushBuild(build, pending);
   }

   private boolean pluginEnabled() {
      return this.generator != null && this.generator.getPlugin() != null
              && this.generator.getPlugin().isEnabled();
   }

   private boolean mayScheduleTimeout(Parcel build) {
      return BranchGrowthTimeoutLifecyclePolicy.shouldScheduleTimeout(
              this.pluginEnabled(), this.pendingBuilds.containsKey(build));
   }

   private void tryFlushBuild(Parcel build) {
      PendingBuild pending = this.pendingBuilds.get(build);
      if (pending == null || !build.isPopulated()) return;
      if (!pending.growthReady) {
         if (pending.grid == null) return;
         Set<Long> waitingCells = new HashSet<>();
         this.collectNeighbourhoodWaitCells(build, pending.grid, waitingCells);
         if (!waitingCells.isEmpty()) {
            this.registerParcelWait(build, pending, waitingCells);
            return;
         }
         this.clearParcelWait(build, pending);
         pending.growthReady = true;
         pending.timeoutGeneration = 0L;
         this.markGrowthReady(pending);
         this.reportPendingGrowthWork();
      }
      this.flushBuild(build, pending);
   }

   /** R30ea delegates execution to the ticket-limited drain rather than running inline. */
   private void flushBuild(Parcel build, PendingBuild pending) {
      BranchGrowthDrain.enqueue(this, build, pending);
   }

   /** Called by BranchGrowthDrain through reflection; restored R30ea semantics. */
   private void flushBuildNow(Parcel build, PendingBuild pending) {
      if (!pending.growthReady || pending.world == null) return;
      Set<Long> missing = missingGrowthChunks(pending.world, pending.supportHits);
      if (!missing.isEmpty()) {
         this.markGrowthWaiting(pending);
         this.clearGrowthWait(build, pending);
         for (long chunkKey : missing) {
            pending.waitingChunks.add(chunkKey);
            this.growthWaitingForChunk.computeIfAbsent(chunkKey,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(build);
         }
         Minions.dNodeVerbose("[BUILD][DEFER] %s -- %d support chunks unloaded; preserving %d packed candidates.",
                 this.describeBuild(build), missing.size(), pending.supportHits.size());
         this.reportPendingGrowthWork();
         return;
      }
      this.clearGrowthWait(build, pending);
      this.markGrowthReady(pending);
      if (pending.supportHits.isEmpty()) {
         this.removePendingBuild(build, pending);
         return;
      }

      Ctx ctx = new Ctx();
      ctx.supportHits = pending.supportHits;
      ctx.footprintChunks = Math.max(1, pending.chunkArea);
      ctx.grid = pending.grid;
      ctx.boundsMinX = pending.boundsMinX;
      ctx.boundsMaxX = pending.boundsMaxX;
      ctx.boundsMinY = pending.boundsMinY;
      ctx.boundsMaxY = pending.boundsMaxY;
      ctx.boundsMinZ = pending.boundsMinZ;
      ctx.boundsMaxZ = pending.boundsMaxZ;
      Minions.dNode("[BUILD][FLUSH] %s -- compact supportHits=%d chunkArea=%d",
              this.describeBuild(build), ctx.supportHits.size(), pending.chunkArea);
      Ctx growthCtx = Parcel.isStreet(build) ? this.thinStreetGrowthCandidates(ctx) : ctx;
      this.branchGrower.generateGrowth(pending.world, pending.options, growthCtx);
      for (int i = 0; i < ctx.supportHits.size(); i++) {
         this.addVines(pending.world.getBlockAt(
                 ctx.supportHits.x(i), ctx.supportHits.y(i), ctx.supportHits.z(i)),
                 pending.options, ctx.supportHits.decayed(i));
      }
      this.refreshChunksInBounds(pending.world, ctx);
      this.removePendingBuild(build, pending);
   }

   static Set<Long> missingGrowthChunks(World world, BranchSupportBuffer supportHits) {
      Set<Long> missing = new HashSet<>();
      if (world == null) return missing;
      Set<Long> examined = new HashSet<>();
      for (int i = 0; i < supportHits.size(); i++) {
         int chunkX = supportHits.x(i) >> 4;
         int chunkZ = supportHits.z(i) >> 4;
         long key = growthChunkKey(chunkX, chunkZ);
         if (examined.add(key) && !world.isChunkLoaded(chunkX, chunkZ)) missing.add(key);
      }
      return missing;
   }



   /**
    * Roads/highways are registered parcels just like buildings (see
    * UrbanGrid.setParcel()), so without this they'd roll their OWN branch
    * growth exactly as densely as a building does, scattering branches
    * along the street itself. Returns a COPY of `source` whose
    * supportHits is a random PluginConfig.getStreetBranchSeedChancePercent()%
    * slice of the original -- generateGrowth() only ever reads
    * ctx.supportHits (via collectRawGrowthCandidates()), so shrinking just
    * this copy is enough to thin the street's own branch seeding without
    * touching the original ctx that flushBuild() goes on to use for vines
    * and chunk-refresh bounds. Bounds/grid/footprintChunks are copied
    * as-is -- those describe geometry, not candidate density, and
    * intentionally aren't affected. A branch from a NEIGHBOURING building
    * sagging out over this street is a completely separate mechanism
    * (isPastedNeighbourBuilding()) and never goes through this method at
    * all.
    */
   private Ctx thinStreetGrowthCandidates(Ctx source) {
      Ctx thinned = new Ctx();
      thinned.footprintChunks = source.footprintChunks;
      thinned.grid = source.grid;
      thinned.boundsMinX = source.boundsMinX;
      thinned.boundsMaxX = source.boundsMaxX;
      thinned.boundsMinY = source.boundsMinY;
      thinned.boundsMaxY = source.boundsMaxY;
      thinned.boundsMinZ = source.boundsMinZ;
      thinned.boundsMaxZ = source.boundsMaxZ;
      double keepChance = PluginConfig.getStreetBranchSeedChancePercent() / 100.0;
      if (keepChance >= 1.0) {
         thinned.supportHits.addAll(source.supportHits);
      } else if (keepChance > 0.0) {
         for (int i = 0; i < source.supportHits.size(); i++) {
            if (this.random.nextDouble() < keepChance) thinned.supportHits.addFrom(source.supportHits, i);
         }
      }
      return thinned;
   }

   private String describeBuild(Parcel build) {
      return String.format("build@(%d,%d)", build.getChunkX(), build.getChunkZ());
   }

   /**
    * True once every real building (not a street, not null) within
    * PluginConfig.getBranchNeighbourDepth() build-hops of `build`'s own
    * footprint has been pasted (Parcel.isPopulated()).
    *
    * "Build-hop" deliberately doesn't mean "chunk" or "grid cell" --
    * streets are walked straight through for free (see
    * collectPerimeterParcels()/the isStreet() check below), so depth
    * always counts actual buildings, never the road between them. This
    * is what makes a build's growth reach across a street exactly the
    * same way it already reaches across a shared lot line -- see
    * withinBounds()/isPastedNeighbourBuilding() below, which is the
    * block-level check that actually lets a branch land past this
    * build's own bounds once its neighbour is populated. All this method
    * does is decide WHEN it's worth rolling growth at all, by making
    * that landing spot likely to already exist on the two nearest rings
    * of real neighbours, on every side, before this build ever rolls.
    *
    * A null neighbour (an unloaded/ungenerated cell across a tile
    * boundary -- see UrbanGrid.peekParcel()) is treated as NOT ready:
    * this deliberately blocks the flush rather than guessing, and relies
    * on the per-build watchdog (PluginConfig.getBranchFlushTimeoutTicks())
    * to eventually flush anyway if that neighbour genuinely never
    * generates in this play session.
    */
   private void collectNeighbourhoodWaitCells(Parcel build, UrbanGrid grid, Set<Long> waitingCells) {
      Set<Parcel> visited = Collections.newSetFromMap(new IdentityHashMap<>());
      visited.add(build);
      Deque<ParcelDepth> frontier = new ArrayDeque<>();
      this.enqueueNeighbourhoodPerimeter(build, 0, grid, frontier, waitingCells, visited);
      int maxDepth = PluginConfig.getBranchNeighbourDepth();
      while (!frontier.isEmpty()) {
         ParcelDepth item = frontier.poll();
         Parcel parcel = item.parcel();
         int depth = item.depth();
         if (parcel == null || !visited.add(parcel)) continue;
         if (Parcel.isStreet(parcel)) {
            this.enqueueNeighbourhoodPerimeter(parcel, depth, grid, frontier, waitingCells, visited);
            continue;
         }
         int nextDepth = depth + 1;
         if (nextDepth < maxDepth) {
            this.enqueueNeighbourhoodPerimeter(parcel, nextDepth, grid, frontier, waitingCells, visited);
         }
      }
   }

   private void enqueueNeighbourhoodPerimeter(Parcel parcel, int depth, UrbanGrid grid,
                                               Deque<ParcelDepth> frontier,
                                               Set<Long> waitingCells, Set<Parcel> visited) {
      int x0 = parcel.getChunkX() - 1;
      int x1 = parcel.getChunkX() + parcel.getChunkSizeX();
      int z0 = parcel.getChunkZ() - 1;
      int z1 = parcel.getChunkZ() + parcel.getChunkSizeZ();
      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            boolean interior = x > x0 && x < x1 && z > z0 && z < z1;
            if (interior) continue;
            Parcel neighbour = grid.peekParcel(x, z);
            if (neighbour == null) {
               waitingCells.add(growthChunkKey(x, z));
            } else if (neighbour != parcel && !visited.contains(neighbour)) {
               if (Parcel.isStreet(neighbour)) {
                  frontier.add(new ParcelDepth(neighbour, depth));
               } else if (!neighbour.isPopulated()) {
                  // Coordinate keys survive planner replacement of a
                  // provisional Parcel before the final parcel is pasted.
                  waitingCells.add(growthChunkKey(x, z));
               } else {
                  frontier.add(new ParcelDepth(neighbour, depth));
               }
            }
         }
      }
   }

   private void enqueueNeighbourhoodPerimeter(Parcel parcel, int depth, UrbanGrid grid,
                                               Deque<ParcelDepth> frontier,
                                               Set<Parcel> dependencies, Set<Long> unknownCells,
                                               Set<Parcel> visited) {
      int x0 = parcel.getChunkX() - 1;
      int x1 = parcel.getChunkX() + parcel.getChunkSizeX();
      int z0 = parcel.getChunkZ() - 1;
      int z1 = parcel.getChunkZ() + parcel.getChunkSizeZ();
      for (int x = x0; x <= x1; x++) {
         for (int z = z0; z <= z1; z++) {
            boolean interior = x > x0 && x < x1 && z > z0 && z < z1;
            if (interior) continue;
            Parcel neighbour = grid.peekParcel(x, z);
            if (neighbour == null) {
               unknownCells.add(growthChunkKey(x, z));
            } else if (neighbour != parcel && !visited.contains(neighbour)) {
               if (Parcel.isStreet(neighbour)) {
                  frontier.add(new ParcelDepth(neighbour, depth));
               } else if (!neighbour.isPopulated()) {
                  dependencies.add(neighbour);
               } else {
                  frontier.add(new ParcelDepth(neighbour, depth));
               }
            }
         }
      }
   }

   /**
    * The collector above indexes exact world cells. Coordinate keys survive
    * replacement of provisional parcel objects before the final paste.
    */
   void trySeedSlime(Block anchor, DecayOption options, Ctx ctx) {
      if (anchor.isEmpty()) {
         this.placeLeaf(anchor);
      }

      BlockFace startDir = this.pickSlimeDirection(anchor, this.hasAdjacentSupport(anchor), 0);
      if (startDir == null) {
         return;
      }
      int thickness = this.randomInt(PluginConfig.getSlimeMinThickness(), PluginConfig.getSlimeMaxThicknessHorizontal());
      this.slimeWalk(anchor.getRelative(startDir), options, PluginConfig.getSlimeMaxLength(), startDir, 0, thickness, ctx);
   }

   private void slimeWalk(Block block, DecayOption options, int depth, BlockFace direction, int climbHeight, int thickness, Ctx ctx) {
      if (depth <= 0) {
         return;
      }
      if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
         return;
      }
      if (!this.withinBounds(new GNode(block.getX(), block.getY(), block.getZ()), ctx)) {
         return;
      }
      if (!block.isEmpty() || !this.isOutdoors(block)) {
         return;
      }
      boolean wallAdjacent = this.hasAdjacentSupport(block);
      if (!wallAdjacent) {
         return;
      }
      if (this.random.nextDouble() >= PluginConfig.getSlimeContinueChancePercent() / 100.0) {
         return;
      }

      this.placeLeaf(block);
      this.applySlimeThickness(block, thickness, direction);

      int nextClimb = (direction == BlockFace.UP) ? climbHeight + 1 : 0;
      BlockFace nextDir = direction;
      boolean mustRedirect = direction == BlockFace.UP && (!wallAdjacent || nextClimb > PluginConfig.getSlimeMaxClimbHeight());
      if (mustRedirect || this.random.nextDouble() < PluginConfig.getSlimeVeerChancePercent() / 100.0) {
         BlockFace picked = this.pickSlimeDirection(block, wallAdjacent, nextClimb);
         if (picked == null) {
            return;
         }
         nextDir = picked;
         if (nextDir != BlockFace.UP) {
            nextClimb = 0;
         }
      }

      int nextThickness = this.wiggleThickness(thickness, PluginConfig.getSlimeMinThickness(), PluginConfig.getSlimeMaxThicknessHorizontal(),
              PluginConfig.getSlimeThicknessChangeChancePercent());

      this.slimeWalk(block.getRelative(nextDir), options, depth - 1, nextDir, nextClimb, nextThickness, ctx);

      if (this.random.nextDouble() < PluginConfig.getSlimeBranchChancePercent() / 100.0) {
         BlockFace branchDir = this.pickSlimeDirection(block, wallAdjacent, 0);
         if (branchDir != null) {
            this.slimeWalk(block.getRelative(branchDir), options, depth / 2, branchDir, 0, thickness, ctx);
         }
      }
   }

   private BlockFace pickSlimeDirection(Block block, boolean wallAdjacent, int climbHeight) {
      List<BlockFace> candidates = new ArrayList<>();
      List<Integer> weights = new ArrayList<>();
      for (BlockFace face : HORIZONTAL_FACES) {
         if (this.staysInChunkBounds(block, face) && block.getRelative(face).isEmpty()) {
            candidates.add(face);
            weights.add(PluginConfig.getSlimeHorizontalWeight());
         }
      }
      if (wallAdjacent && climbHeight < PluginConfig.getSlimeMaxClimbHeight()
              && block.getY() < block.getWorld().getMaxHeight() - 1
              && block.getRelative(BlockFace.UP).isEmpty()) {
         candidates.add(BlockFace.UP);
         weights.add(PluginConfig.getSlimeUpWeight());
      }
      if (candidates.isEmpty()) {
         return null;
      }

      int total = 0;
      for (int w : weights) {
         total += w;
      }
      if (total <= 0) {
         return candidates.get(this.random.nextInt(candidates.size()));
      }
      int roll = this.random.nextInt(total);
      int cumulative = 0;
      for (int i = 0; i < candidates.size(); i++) {
         cumulative += weights.get(i);
         if (roll < cumulative) {
            return candidates.get(i);
         }
      }
      return candidates.get(candidates.size() - 1);
   }

   private boolean staysInChunkBounds(Block block, BlockFace face) {
      if (face == BlockFace.WEST && (block.getX() & 15) == 0) {
         return false;
      }
      if (face == BlockFace.EAST && (block.getX() & 15) == 15) {
         return false;
      }
      if (face == BlockFace.NORTH && (block.getZ() & 15) == 0) {
         return false;
      }
      if (face == BlockFace.SOUTH && (block.getZ() & 15) == 15) {
         return false;
      }
      return true;
   }

   private void applySlimeThickness(Block block, int thickness, BlockFace travelDirection) {
      if (thickness > 1) {
         List<BlockFace> perpendicular = new ArrayList<>();
         for (BlockFace face : HORIZONTAL_FACES) {
            if (face != travelDirection && face != travelDirection.getOppositeFace()) {
               perpendicular.add(face);
            }
         }
         Collections.shuffle(perpendicular, this.random);
         int extra = Math.min(thickness - 1, perpendicular.size());
         for (int i = 0; i < extra; i++) {
            Block n = this.safeRelative(block, perpendicular.get(i));
            if (n != null && n.isEmpty() && this.isOutdoors(n)) {
               this.placeLeaf(n);
            }
         }
      }

      if (PluginConfig.getSlimeMaxThicknessVertical() > 1 && this.random.nextDouble() < 0.15) {
         Block up = block.getRelative(BlockFace.UP);
         if (up.isEmpty() && this.isOutdoors(up)) {
            this.placeLeaf(up);
         }
      }
   }

   /**
    * The block-to-block half of decay: what a block that survived the
    * destroy roll but fell inside the partial-decay band turns into.
    *
    * This used to be one switch over the pre-1.13 materials, using the data
    * byte for the variant (WOOD's data picked the species of stair, SMOOTH_
    * BRICK's data picked stone/mossy/cracked, and so on). Every variant is
    * its own Material now, so the plank, door and stone-brick cases match
    * whole families rather than one constant. Behaviour is otherwise the
    * same, including the final fall-through: any material without a
    * transform of its own is removed -- which, being destructive, is the
    * one thing NOT allowed under the ocean water-line (see
    * oceanWaterlineTopY()).
    */
   private void applyPartialDecay(Block block, boolean submerged) {
      Material type = block.getType();
      if (Mats.STONE_FAMILY.contains(type)) {
         if (this.random.nextInt(100) > 40) {
            block.setType(this.random.nextBoolean() ? Material.COBBLESTONE : Material.MOSSY_COBBLESTONE, false);
         }
      } else if (Mats.SANDSTONE_FAMILY.contains(type)) {
         if (this.random.nextBoolean()) {
            Mats.setStairs(block, Material.SANDSTONE_STAIRS, this.random);
         }
      } else if (type == Material.BRICKS) {
         if (this.random.nextBoolean()) {
            Mats.setStairs(block, Material.BRICK_STAIRS, this.random);
         }
      } else if (type == Material.COBBLESTONE) {
         block.setType(Material.MOSSY_COBBLESTONE, false);
      } else if (Mats.STONE_BRICK_FAMILY.contains(type)) {
         block.setType(Mats.STONE_BRICK_LOOKS[this.random.nextInt(Mats.STONE_BRICK_LOOKS.length)], false);
      } else if (Mats.PLANKS.contains(type)) {
         if (!this.random.nextBoolean()) {
            Mats.setStairs(block, Mats.stairsFor(type), this.random);
         }
      } else if (Mats.WOODEN_DOORS.contains(type)) {
         // Only the bottom half, and only if a door actually sits above it
         // (so the top half is never "toggled" on its own).
         if (block.getRelative(0, 1, 0).getType() == type && this.random.nextInt(100) < 80) {
            Mats.toggleDoor(block);
         }
      } else if (!submerged) {
         block.setType(Material.AIR, false);
      }
   }

   private int wiggleThickness(int current, int min, int max, int changeChancePercent) {
      int next = current;
      if (this.random.nextDouble() < changeChancePercent / 100.0) {
         next += this.random.nextBoolean() ? 1 : -1;
      }
      return Math.max(min, Math.min(max, next));
   }

   private int randomInt(int min, int max) {
      if (max <= min) {
         return min;
      }
      return min + this.random.nextInt(max - min + 1);
   }

   boolean withinBounds(GNode point, Ctx ctx) {
      if (point.x >= ctx.boundsMinX && point.x <= ctx.boundsMaxX
              && point.y >= ctx.boundsMinY && point.y <= ctx.boundsMaxY
              && point.z >= ctx.boundsMinZ && point.z <= ctx.boundsMaxZ) {
         return true;
      }
      return this.isPastedNeighbourBuilding(ctx.grid, point.x, point.z);
   }

   private boolean isPastedNeighbourBuilding(UrbanGrid grid, int worldX, int worldZ) {
      if (grid == null) {
         return false;
      }
      Parcel neighbour = grid.peekParcel(worldX >> 4, worldZ >> 4);
      return neighbour != null && neighbour.isPopulated() && !Parcel.isStreet(neighbour);
   }

   void placeLeaf(Block block) {
      // A random species of leaf that never decays on its own (the
      // `persistent` property; it used to be the "not decayable" data bit).
      Mats.placeLeaf(block, this.random);
   }

   /**
    * The OLD pre-branch leaf-decay methodology, reproduced as an opt-in
    * toggle (PluginConfig.isLegacyLeafDecayEnabled()) independent of the
    * branch/slime growth systems: a simple probabilistic flood-fill.
    * Starting from a block adjacent to something that just survived a
    * partial-decay roll, each empty block along the walk gets a leaf with
    * probability leavesScale (scaled by legacyLeafDecayIntensity), and on
    * success recurses into its own 6 neighbours one depth lower. No sag,
    * no chaining, no anchoring -- just small random poofs of leaves
    * clinging to whatever solid material survived nearby.
    *
    * One deliberate change from the original: placeLeaf() below places
    * undecayable leaves (isDecayable=false), where the original placed
    * ordinary decayable ones that could disappear over time in vanilla.
    */
   private void addLegacyLeavesRec(Block block, DecayOption option, int depth) {
      if (depth <= 0 || !block.isEmpty()) {
         return;
      }
      double chance = option.getBaseLeavesScale() * (PluginConfig.getLegacyLeafDecayIntensity() / 100.0);
      if (this.random.nextDouble() < chance) {
         this.placeLeaf(block);
         for (Block neighbour : this.getSixNeighbours(block)) {
            this.addLegacyLeavesRec(neighbour, option, depth - 1);
         }
      }
   }

   /**
    * Full 6-directional neighbour walk (up, down, both horizontal axes),
    * bounds-checked at chunk edges and world height -- mirrors the
    * original getNeighbours(). Deliberately separate from
    * DecayProviderBranch's getOutwardNeighbours(), which is upward-only
    * and built for growth-candidate seeding, not a general flood-fill.
    */
   private List<Block> getSixNeighbours(Block block) {
      List<Block> neighbours = new ArrayList<>(6);
      if (block.getY() < block.getWorld().getMaxHeight() - 1) {
         neighbours.add(block.getRelative(0, 1, 0));
      }
      if (block.getY() > 0) {
         neighbours.add(block.getRelative(0, -1, 0));
      }
      if ((block.getX() & 15) != 0) {
         neighbours.add(block.getRelative(-1, 0, 0));
      }
      if ((block.getX() & 15) != 15) {
         neighbours.add(block.getRelative(1, 0, 0));
      }
      if ((block.getZ() & 15) != 0) {
         neighbours.add(block.getRelative(0, 0, -1));
      }
      if ((block.getZ() & 15) != 15) {
         neighbours.add(block.getRelative(0, 0, 1));
      }
      return neighbours;
   }

   private void addVines(Block supportingBlock, DecayOption option, boolean decayed) {
      double vineChance = option.getVineScale() * (PluginConfig.getMasterVineIntensity() / 100.0);
      for (BlockFace face : HORIZONTAL_FACES) {
         Block neighbour = this.safeRelative(supportingBlock, face);
         if (neighbour != null && neighbour.isEmpty() && this.isVineSuitableSurface(supportingBlock)
                 && (decayed || this.isOutdoors(neighbour))
                 && this.random.nextDouble() < vineChance) {
            this.growVineDown(neighbour, face.getOppositeFace(), vineChance, PluginConfig.getVineMaxLength(), decayed);
         }
      }
   }

   private void growVineDown(Block block, BlockFace attachFace, double vineChance, int remainingLength, boolean decayed) {
      if (remainingLength <= 0 || !block.isEmpty() || block.getY() <= 0) {
         return;
      }
      if (!decayed && !this.isOutdoors(block)) {
         return;
      }

      Block initialWall = this.safeRelative(block, attachFace);
      if (initialWall == null || !this.isVineSuitableSurface(initialWall)) {
         return;
      }

      Mats.placeVine(block, attachFace);

      Block below = block.getRelative(BlockFace.DOWN);
      Block wallBelow = this.safeRelative(below, attachFace);
      if (wallBelow != null && this.isVineSuitableSurface(wallBelow) && this.random.nextDouble() < vineChance) {
         this.growVineDown(below, attachFace, vineChance, remainingLength - 1, decayed);
      }
   }

   private boolean isOutdoors(Block block) {
      int checkDistance = PluginConfig.getExteriorCheckDistance();
      int maxY = Math.min(block.getWorld().getMaxHeight(), block.getY() + 1 + checkDistance);
      for (int y = block.getY() + 1; y < maxY; y++) {
         if (!block.getWorld().getBlockAt(block.getX(), y, block.getZ()).isEmpty()) {
            return false;
         }
      }
      return true;
   }

   boolean hasAdjacentSupport(Block block) {
      for (BlockFace face : ALL_FACES) {
         Block neighbour = this.safeRelative(block, face);
         if (neighbour != null && this.isSupporting(neighbour)) {
            return true;
         }
      }
      return false;
   }

   /** R30ea uses Bukkit's occlusion test, rather than an outdated name blacklist. */
   private boolean isVineSuitableSurface(Block wall) {
      return wall != null && this.isSupporting(wall) && wall.getType().isOccluding();
   }

   /**
    * How high, in this chunk, decay is forbidden from touching anything.
    * Integer.MIN_VALUE means nothing is protected (an ordinary parcel).
    *
    * - RiverParcel / OceanParcel: the whole column. These
    *   are nothing but water, bed and bank -- there is no structure in
    *   them for decay to legitimately act on.
    * - BridgeParcel: everything below the deck slab. A bridge's decay
    *   volume covers its entire 16x16 footprint (schematic bridges run
    *   destroyChunks() over the full chunk), and under the deck that
    *   footprint is river: water, bed and bank. The deck slab, lane and
    *   parapet (deckY-1 and up) are the bridge itself and still decay.
    */
   private static int waterProtectionTopY(UrbanGrid grid, int chunkX, int chunkZ) {
      if (grid == null) {
         return Integer.MIN_VALUE;
      }
      Parcel parcel = grid.peekParcel(chunkX, chunkZ);
      if (parcel instanceof RiverParcel || parcel instanceof OceanParcel) {
         return Integer.MAX_VALUE;
      }
      if (parcel instanceof BridgeParcel) {
         return StreetParcel.ROAD_SURFACE_Y - 2;
      }
      return Integer.MIN_VALUE;
   }

   /**
    * Highest Y, in this chunk, at which DESTRUCTIVE decay (block -> air) is
    * forbidden because the block is under the ocean's water-line. Returns
    * Integer.MIN_VALUE when nothing is protected: the feature is switched
    * off, the ocean is disabled, or the chunk isn't reached by the sea.
    *
    * waterProtectionTopY() only covers parcels that ARE water (ocean, river,
    * creek) and the underside of bridges. It says nothing about everything
    * else that stands in the sea -- reclaimed-land buildings and roads with
    * submerged foundations, quay walls, the road chunks on the ring beside
    * open water -- whose decay volumes reach below the surface. Those are
    * covered here, chunk by chunk, using the ocean's own geometry:
    *
    * - the chunk is in the ocean's domain (interior of an ocean tile,
    *   reclaimed ring space, ring between two ocean tiles), or
    * - any of a 3x3 sample of points across the chunk is water or beach
    *   (inside the coast, or within the bank margin of it) -- the same
    *   "is ocean here" test UrbanGrid.placeHighwayCell() uses, which also
    *   catches the ordinary road a ring cell gets downgraded to when dry
    *   land lies beyond the boundary.
    *
    * The level is OceanProvider.waterSurfaceY(), the Y of the top water
    * block, so the block AT the surface is protected too. A pure function
    * of the ocean's geometry, so it is safe to ask about a chunk in a
    * neighbouring tile whose UrbanGrid doesn't exist yet.
    */
   private static int oceanWaterlineTopY(UrbanGrid grid, int chunkX, int chunkZ) {
      if (grid == null || !PluginConfig.isDecayProtectOceanWaterline() || !OceanConfig.isOceanEnabled()) {
         return Integer.MIN_VALUE;
      }
      OceanProvider ocean = grid.getOceanProvider();
      if (ocean == null || !ocean.isEnabled()) {
         return Integer.MIN_VALUE;
      }
      if (ocean.inOceanDomainChunk(chunkX, chunkZ)) {
         return ocean.waterSurfaceY();
      }
      double bankMarginBlocks = OceanConfig.getOceanBankMargin() * 16.0;
      double baseX = chunkX * 16.0;
      double baseZ = chunkZ * 16.0;
      for (int i = 0; i <= 2; i++) {
         for (int j = 0; j <= 2; j++) {
            double d = ocean.oceanDistanceInsideShoreBlock(baseX + i * 8.0, baseZ + j * 8.0);
            if (!Double.isNaN(d) && d > -bankMarginBlocks) {
               return ocean.waterSurfaceY();
            }
         }
      }
      return Integer.MIN_VALUE;
   }

   // The decay noise is a pure function of the world seed, but destroyWithin() used to build a fresh generator (permutation
   // tables and all) for every parcel it decayed. Built once per seed instead; noise() keeps no state between calls.
   private SimplexOctaveGenerator decayNoiseGen;
   private long decayNoiseSeed;

   private synchronized SimplexOctaveGenerator decayNoise(long seed) {
      if (this.decayNoiseGen == null || this.decayNoiseSeed != seed) {
         this.decayNoiseGen = new SimplexOctaveGenerator(seed, 2);
         this.decayNoiseSeed = seed;
      }
      return this.decayNoiseGen;
   }

   /**
    * True if any of the block's six neighbours is water -- or holds water
    * (a waterlogged stair, slab or fence has no water BLOCK, but removing
    * what it leans on would still let it spill).
    */
   private static boolean touchesWater(Block block) {
      for (BlockFace face : ALL_FACES) {
         int nx = block.getX() + face.getModX();
         int nz = block.getZ() + face.getModZ();
         // A neighbour in ANOTHER chunk that is not loaded yet is skipped, not
         // asked about: reading it would load that chunk right here, in the
         // middle of this populate, and on a modern server a loaded chunk runs
         // its own populate -- see OrePopulator.createClump() for the cascade
         // that causes. (An unknown neighbour counts as "not water".)
         if (((nx >> 4) != (block.getX() >> 4) || (nz >> 4) != (block.getZ() >> 4))
                 && !block.getWorld().isChunkLoaded(nx >> 4, nz >> 4)) {
            continue;
         }
         if (Mats.holdsWater(block.getRelative(face))) {
            return true;
         }
      }
      return false;
   }

   @Override
   protected boolean isValid(Block block) {
      return !invalidBlocks.contains(block.getType());
   }

   @Override
   protected boolean isSupporting(Block block) {
      return !unsupportingBlocks.contains(block.getType()) && !block.isEmpty();
   }

   /**
    * True unless `block` is in nonBranchSupportBlocks -- see that field's
    * own javadoc. Independent of isSupporting(): a block can be a
    * perfectly normal SupportHit (decays, hosts vines) while still being
    * excluded from branch/slime growth specifically via this check.
    * Package-visible so DecayProviderBranch.collectRawGrowthCandidates()
    * (via its `owner` reference back to this instance) can filter on it
    * without this living in, or being keyed off, anything to do with
    * vines or decay.
    */
   boolean isBranchableSupport(Block block) {
      return !nonBranchSupportBlocks.contains(block.getType());
   }
}