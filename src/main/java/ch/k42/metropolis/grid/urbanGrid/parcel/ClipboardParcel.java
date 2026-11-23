package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.config.AbstractSchematicConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public class ClipboardParcel extends Parcel {
   private Clipboard clipboard;
   private Direction direction;
   private boolean overflowRegistered;
   private int outstandingOverflow;
   private final BitSet pastedSlices = new BitSet();
   private final BitSet finishedSlices = new BitSet();
   private final Set<Long> interiorScannedChunks = new HashSet<>();
   private final List<MetropolisBuildFlagExporter.InteriorCandidate> interiorCandidates = new ArrayList<>();
   private boolean completionExported;
   private static final int cutoutDepth = 8;
   private static final int cutoutHeight = 11;

   /** The placement direction of this building's schematic (which of its four variants was pasted). */
   public Direction getDirection() {
      return this.direction;
   }

   public ClipboardParcel(UrbanGrid grid, Cartesian2D base, Cartesian2D size, Clipboard clipboard, ContextType contextType, SchematicType schematicType, Direction direction) {
      super(base, size, contextType, schematicType, grid);
      this.clipboard = clipboard;
      this.direction = direction;
      if (!isQuiet()) {
         grid.getStatistics().logSchematic(clipboard);
      }
   }

   // Working a block's buildings out on paper (see BlockPlans) builds parcels that never reach the world: they are not counted.
   private static final ThreadLocal<Boolean> QUIET = new ThreadLocal<Boolean>();

   public static boolean isQuiet() {
      return Boolean.TRUE.equals(QUIET.get());
   }

   public static void setQuiet(boolean quiet) {
      QUIET.set(quiet);
   }

   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (!(o instanceof ClipboardParcel)) {
         return false;
      } else {
         ClipboardParcel that = (ClipboardParcel)o;
         if (this.clipboard == null || that.clipboard == null) {
            // A "no schematic found" placeholder parcel is never a
            // duplicate of anything -- there's nothing to compare.
            return false;
         }
         return this.clipboard.getGroupId().equals(that.clipboard.getGroupId());
      }
   }

   public int hashCode() {
      if (this.clipboard == null || this.clipboard.getGroupId() == null) {
         // Previously this logged a null warning and then dereferenced
         // the null clipboard anyway on the next line, guaranteeing an
         // NPE the moment a placeholder (no-schematic-found) parcel was
         // inserted into any HashSet -- which aborted whichever other
         // districts in that grid hadn't been filled yet, leaving them
         // as unfilled default flat stone.
         return System.identityHashCode(this);
      }

      return this.clipboard.getGroupId().hashCode();
   }

   /** Every actual FileClipboard uses chunk-owned slices, regardless of size. */
   private boolean isChunkSliced() { return this.clipboard instanceof ch.k42.metropolis.grid.urbanGrid.clipboard.FileClipboard; }

   private boolean isLargeSchematic() {
      return this.isChunkSliced() && (long) this.chunkSizeX * this.chunkSizeZ >= 64L;
   }

   private int sliceIndex(int chunkX, int chunkZ) {
      if (chunkX < this.chunkX || chunkX >= this.chunkX + this.chunkSizeX ||
          chunkZ < this.chunkZ || chunkZ >= this.chunkZ + this.chunkSizeZ) return -1;
      return (chunkZ - this.chunkZ) * this.chunkSizeX + (chunkX - this.chunkX);
   }

   private synchronized void reportSliceFinished(MetropolisGenerator generator) {
      if (this.finishedSlices.cardinality() >= this.chunkSizeX * this.chunkSizeZ
              && this.outstandingOverflow == 0) {
         if (!this.isPopulated()) this.markPopulated();
         this.exportCompletedBuild(generator);
      }
   }

   /** Overflow is a separate set of slices; the building is complete only after both finish. */
   synchronized void overflowCompleted(int chunkX, int chunkZ, MetropolisGenerator generator) {
      if (outstandingOverflow <= 0) throw new IllegalStateException(
              "Unexpected overflow completion at " + chunkX + "," + chunkZ);
      try {
         // SchematicOverflowDrain holds a plugin chunk ticket until this callback
         // returns, so inspect this overflow slice without requesting a chunk load.
         if (generator.getWorld() != null && generator.getWorld().isChunkLoaded(chunkX, chunkZ)) {
            collectInteriorCandidates(generator, generator.getWorld().getChunkAt(chunkX, chunkZ));
         }
      } catch (RuntimeException failure) {
         Minions.w("[BUILD-FLAGS] Could not inspect overflow slice (%d,%d) for %s: %s",
                 chunkX, chunkZ, this.clipboard == null ? "<null>" : this.clipboard.getGroupId(), failure.toString());
      } finally {
         // A candidate-inspection failure must not strand the logical BUILD
         // in an outstanding-overflow state forever.
         --outstandingOverflow;
         try {
            PlacedParcels.of(generator).afterOverflow(this, chunkX, chunkZ, generator);
         } finally {
            reportSliceFinished(generator);
         }
      }
   }

   @Override
   public void populate(MetropolisGenerator generator, Chunk chunk) {
      if (isChunkSliced()) {
         int i = sliceIndex(chunk.getX(),chunk.getZ());
         if (i < 0 || pastedSlices.get(i)) return;
         ((ch.k42.metropolis.grid.urbanGrid.clipboard.FileClipboard) this.clipboard).pasteChunk(
                 generator,new Cartesian2D(this.chunkX,this.chunkZ),effectiveBuildHeight(),
                 chunk.getX(),chunk.getZ());
         pastedSlices.set(i);
         if (!overflowRegistered) {
            overflowRegistered=true;
            outstandingOverflow=SchematicOverflowDrain.register(this,generator);
         }
         return;
      }
      if (chunk.getX() == this.chunkX && chunk.getZ() == this.chunkZ) {
         if (this.clipboard == null) { this.markPopulated(); return; }
         this.clipboard.paste(generator, new Cartesian2D(this.chunkX, this.chunkZ), this.effectiveBuildHeight());
         this.markPopulated();
      }
   }

   /**
    * Inspect only the already-loaded slice that has just completed. Four
    * deterministic sample columns per chunk (two by two) keep generation
    * overhead bounded while still finding interiors on multiple floors.
    */
   private synchronized void collectInteriorCandidates(MetropolisGenerator generator, Chunk chunk) {
      if (this.getSchematicType() != SchematicType.BUILD || this.clipboard == null
              || generator == null || generator.getWorld() == null || chunk == null) return;
      long key = ((long)chunk.getX() << 32) ^ (chunk.getZ() & 0xffffffffL);
      if (!this.interiorScannedChunks.add(key)) return;

      Cartesian3D dimensions = this.clipboard.getSize();
      int minX = this.chunkX << 4, minZ = this.chunkZ << 4;
      int maxX = minX + dimensions.X - 1, maxZ = minZ + dimensions.Z - 1;
      int chunkMinX = chunk.getX() << 4, chunkMinZ = chunk.getZ() << 4;
      if (chunkMinX > maxX || chunkMinX + 15 < minX
              || chunkMinZ > maxZ || chunkMinZ + 15 < minZ) return;

      World world = generator.getWorld();
      int bottomY = this.clipboard.getBottom(this.effectiveBuildHeight());
      int firstFloor = Math.max(world.getMinHeight() + 1, bottomY);
      int lastFloor = Math.min(world.getMaxHeight() - 5, bottomY + dimensions.Y - 4);
      int localMinX = Math.max(1, minX - chunkMinX);
      int localMaxX = Math.min(14, maxX - chunkMinX);
      int localMinZ = Math.max(1, minZ - chunkMinZ);
      int localMaxZ = Math.min(14, maxZ - chunkMinZ);
      if (localMinX > localMaxX || localMinZ > localMaxZ) return;

      int x1 = localMinX + Math.max(0, (localMaxX - localMinX) / 3);
      int x2 = localMinX + Math.max(0, (2 * (localMaxX - localMinX)) / 3);
      int z1 = localMinZ + Math.max(0, (localMaxZ - localMinZ) / 3);
      int z2 = localMinZ + Math.max(0, (2 * (localMaxZ - localMinZ)) / 3);
      int[][] samples = {{x1,z1},{x2,z1},{x1,z2},{x2,z2}};
      int added = 0;
      for (int[] sample : samples) {
         if (added >= 8) break;
         int lx = sample[0], lz = sample[1];
         for (int floorY = firstFloor; floorY <= lastFloor && added < 8; floorY++) {
            if (isVerifiedInteriorColumn(chunk, lx, floorY, lz)) {
               this.interiorCandidates.add(new MetropolisBuildFlagExporter.InteriorCandidate(
                       chunkMinX + lx, floorY, chunkMinZ + lz, true, true));
               added++;
               // Do not record adjacent blocks in the same room as separate
               // candidate floors; the next floor is still searched.
               floorY += 2;
            }
         }
      }
   }

   /** Non-sliced legacy clips are inspected only in chunks already loaded by worldgen. */
   private void collectLoadedFootprintInteriors(MetropolisGenerator generator) {
      if (this.getSchematicType() != SchematicType.BUILD || this.clipboard == null
              || generator == null || generator.getWorld() == null) return;
      if (this.isChunkSliced()) {
         World world = generator.getWorld();
         if (world.isChunkLoaded(this.chunkX, this.chunkZ))
            collectInteriorCandidates(generator, world.getChunkAt(this.chunkX, this.chunkZ));
         return;
      }
      Cartesian3D size = this.clipboard.getSize();
      int chunksX = Math.floorDiv(size.X + 15, 16);
      int chunksZ = Math.floorDiv(size.Z + 15, 16);
      World world = generator.getWorld();
      for (int dx = 0; dx < chunksX; dx++) for (int dz = 0; dz < chunksZ; dz++) {
         int cx = this.chunkX + dx, cz = this.chunkZ + dz;
         if (world.isChunkLoaded(cx, cz)) collectInteriorCandidates(generator, world.getChunkAt(cx, cz));
      }
   }

   private static boolean isVerifiedInteriorColumn(Chunk chunk, int x, int floorY, int z) {
      org.bukkit.block.Block floor = chunk.getBlock(x, floorY, z);
      Material floorType = floor.getType();
      if (!floorType.isSolid() || isUnsafeStandingMaterial(floorType)) return false;

      org.bukkit.block.Block feet = chunk.getBlock(x, floorY + 1, z);
      org.bukkit.block.Block head = chunk.getBlock(x, floorY + 2, z);
      if (!isClearStandingBlock(feet) || !isClearStandingBlock(head)) return false;

      Material ceiling3 = chunk.getBlock(x, floorY + 3, z).getType();
      Material ceiling4 = chunk.getBlock(x, floorY + 4, z).getType();
      if (!ceiling3.isSolid() && !ceiling4.isSolid()) return false;

      int walls = 0;
      int[][] directions = {{1,0},{-1,0},{0,1},{0,-1}};
      for (int[] direction : directions) {
         Material atFeet = chunk.getBlock(x + direction[0], floorY + 1, z + direction[1]).getType();
         Material atHead = chunk.getBlock(x + direction[0], floorY + 2, z + direction[1]).getType();
         if ((atFeet.isSolid() && !isUnsafeStandingMaterial(atFeet))
                 || (atHead.isSolid() && !isUnsafeStandingMaterial(atHead))) walls++;
      }
      // Three enclosing sides plus a ceiling distinguishes rooms from
      // exposed courtyards, rooftops and ordinary balconies.
      return walls >= 3;
   }

   private static boolean isClearStandingBlock(org.bukkit.block.Block block) {
      Material type = block.getType();
      return block.isPassable() && !block.isLiquid() && !isUnsafeStandingMaterial(type);
   }

   private static boolean isUnsafeStandingMaterial(Material type) {
      return type == Material.MAGMA_BLOCK || type == Material.CACTUS || type == Material.FIRE
              || type == Material.SOUL_FIRE || type == Material.CAMPFIRE
              || type == Material.SOUL_CAMPFIRE || type == Material.POWDER_SNOW
              || type == Material.COBWEB || type == Material.SWEET_BERRY_BUSH
              || type == Material.WITHER_ROSE || type == Material.POINTED_DRIPSTONE;
   }

   private synchronized void exportCompletedBuild(MetropolisGenerator generator) {
      if (this.completionExported || this.getSchematicType() != SchematicType.BUILD
              || this.clipboard == null || generator == null || generator.getWorld() == null
              || generator.getPlugin() == null) return;
      ch.k42.metropolis.plugin.MetropolisPlugin plugin = generator.getPlugin();
      MetropolisBuildFlagExporter exporter = plugin.getBuildFlagExporter();
      if (exporter == null) return;

      // The parcel's actual assigned district is authoritative. Schematic
      // names, filenames and group IDs are never used to infer context.
      ch.k42.metropolis.grid.urbanGrid.districts.District district = this.getDistrict();
      ch.k42.metropolis.grid.urbanGrid.enums.ContextType context =
              district == null ? this.getContextType() : district.getContext();
      if (context == null) return;

      Cartesian3D dimensions = this.clipboard.getSize();
      int minX = this.chunkX << 4, minZ = this.chunkZ << 4;
      int minY = this.clipboard.getBottom(this.effectiveBuildHeight());
      int maxX = minX + dimensions.X - 1;
      int maxY = minY + dimensions.Y - 1;
      int maxZ = minZ + dimensions.Z - 1;
      MetropolisBuildFlagExporter.Footprint footprint = new MetropolisBuildFlagExporter.Footprint(
              minX, maxX, minY, maxY, minZ, maxZ);
      int centerX = minX + (dimensions.X - 1) / 2;
      int centerZ = minZ + (dimensions.Z - 1) / 2;
      String schematicId = this.clipboard.getGroupId() == null
              ? this.clipboard.getClass().getName() : this.clipboard.getGroupId();
      String id = MetropolisBuildFlagExporter.buildingId(generator.getWorld().getUID(),
              context.name(), schematicId, footprint);

      List<MetropolisBuildFlagExporter.InteriorCandidate> candidates =
              new ArrayList<>(this.interiorCandidates);
      candidates.sort(Comparator
              .comparingInt(MetropolisBuildFlagExporter.InteriorCandidate::z)
              .thenComparingInt(MetropolisBuildFlagExporter.InteriorCandidate::x)
              .thenComparingInt(MetropolisBuildFlagExporter.InteriorCandidate::floorY));
      this.completionExported = true;
      exporter.export(generator.getWorld().getUID(), id, context.name(), footprint,
              new MetropolisBuildFlagExporter.Reference(centerX, centerZ), candidates);
      Minions.dDistrict("[BUILD-FLAGS] queued completed BUILD id=%s context=%s footprint=(%d..%d,%d..%d,%d..%d) interiorCandidates=%d",
              id, context.name(), minX, maxX, minY, maxY, minZ, maxZ, candidates.size());
   }

   public Clipboard getClipboard() {
      return this.clipboard;
   }

   public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
      if (isChunkSliced()) {
         int i = sliceIndex(chunk.getX(),chunk.getZ());
         if (i < 0 || !pastedSlices.get(i) || finishedSlices.get(i)) return;
         if (!isLargeSchematic()) {
            try {
               generator.getDecayProvider().destroyChunks(chunk.getX(),chunk.getZ(),1,1,
                       this.clipboard.getBottom(effectiveBuildHeight()),
                       this.clipboard.getSize().Y, this.clipboard.getConfig().getDecayOption(),
                       this.grid,this.getDistrict());
            } catch (Exception ex) {
               Minions.e(ex);
               return;  // Do not acknowledge a failed decay completion.
            }
         }
         PlacedParcels.of(generator).buildingChunkFinished(this,chunk.getX(),chunk.getZ(),generator);
         collectInteriorCandidates(generator, chunk);
         finishedSlices.set(i);
         if (generator.getWorld() != null && generator.getWorld().isChunkLoaded(chunk.getX(),chunk.getZ()))
             generator.getWorld().refreshChunk(chunk.getX(),chunk.getZ());
         reportSliceFinished(generator);
         return;
      }
      if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) {
         return;
      }

      if (this.clipboard != null) {
         boolean decayComplete = true;
         // Note: cutouts toward roads are NOT applied here, other than for a road that was already finished (above). The road's
         // postPopulate() calls notifyNeighboursToRecut() as its final
         // step, which triggers makeCutoutsToward() on this building after
         // the road's paste + decay + sidewalk are fully done. Applying
         // cutouts here (before the road has run) would cut into raw
         // pregenerated stone terrain that the road later pastes over.
         try {
            generator.getDecayProvider().destroyChunks(this.chunkX, this.chunkZ, this.chunkSizeX, this.chunkSizeZ, this.clipboard.getBottom(this.effectiveBuildHeight()), this.clipboard.getSize().Y, this.clipboard.getConfig().getDecayOption(), this.grid, this.getDistrict());
         } catch (Exception e) {
            decayComplete = false;
            Minions.d("CLIPBOARD PARCEL DECAY DIDNT WORK HERES SOME INFO");
            Minions.d("Generator null? : " + (generator == null));
            Minions.d("Generator decay provider null? : " + (generator.getDecayProvider() == null));
            Minions.d("clipboard group ID : " + this.clipboard.getGroupId());
            Minions.d("clipboard.hashCode null? : " + this.clipboard.hashCode());
            Minions.d("clipboard.getConfig null? : " + (this.clipboard.getConfig() == null));
            Minions.d("clipboard.getConfig.getDecayOption null? : " + (this.clipboard.getConfig().getDecayOption() == null));
            e.printStackTrace();
         }

         // destroyChunks() above caches this building's own growth
         // candidates and immediately checks whether its neighbourhood is
         // already placed -- see DecayProviderNormal.cacheBuildGrowth()/
         // neighbourhoodReady(). Nothing else needs to happen here: there
         // is no district/tile-wide pool left to report into. A "no
         // schematic found" placeholder parcel (this.clipboard == null)
         // never calls destroyChunks() at all -- it has no growth of its
         // own to cache, and it still counts as a placed (if empty)
         // neighbour for anyone next to it once its populate() runs.

         PlacedParcels.of(generator).buildingFinished(this, generator);   // after the decay pass: cuts toward roads that finished first
         if (decayComplete) {
            collectLoadedFootprintInteriors(generator);
            this.exportCompletedBuild(generator);
         }

         if (generator.getWorld() != null) {
            generator.getWorld().refreshChunk(chunk.getX(), chunk.getZ());
         }
      }
   }

   /**
    * Called by a StreetParcel neighbour from its postPopulate(), after
    * that road's paste + decay + sidewalk passes are all fully complete.
    * Runs only the cutout faces of this building that point toward the
    * given road chunk -- skipping any faces that point in other directions.
    * This is what guarantees the cutout always lands on top of the finished
    * road rather than into raw pregenerated terrain: the road drives the
    * timing, the building owns the geometry.
    */
   public void makeCutoutsToward(StreetParcel road, MetropolisGenerator generator) {
      if (this.clipboard == null) {
         return;
      }

      // Cutouts begin directly above the finished road surface; do not
      // accidentally use the building's paste-bottom or a separately
      // hardcoded constant that can drift from the road's own Y datum.
      Cartesian3D base = new Cartesian3D(this.chunkX << 4, StreetParcel.ROAD_SURFACE_Y + 1, this.chunkZ << 4);
      int sizeX = this.clipboard.getSize().X;
      int sizeZ = this.clipboard.getSize().Z;

      // Per-side landmark cutouts (see the field comments on
      // SchematicConfig.cutoutN/E/S/W): cutoutN/E/S/W are authored against
      // the schematic's own UNROTATED reference file -- the one
      // ClipboardImporter.rotateAndCacheSchematic() calls "north" -- so
      // cutoutN is literally "the front side", cutoutE "the right side",
      // cutoutS "the back side", cutoutW
      // "the left side", all as seen standing outside that unrotated file
      // looking at it.
      //
      // This parcel, though, was pasted using whichever of the 4 rotated
      // variants (NORTH/EAST/SOUTH/WEST.schematic) matches this.direction
      // -- rotateAndCacheSchematic() generates those by chaining
      // rotateY(-90) north -> east -> south -> west, a rigid rotation that
      // carries every local side around by the same number of steps. So a
      // local side's actual world-facing direction isn't fixed -- it's
      // rotated() below by however many steps this.direction represents
      // in that exact same north->east->south->west cycle. (cutoutN always
      // works out to exactly this.direction itself, zero steps added to
      // its own index -- which is exactly why the ordinary single
      // `cutouts` case further down, which has always used this.direction
      // directly, was already correct: it's the same side as cutoutN by
      // this same convention, just under the old single-sided field name.)
      //
      // A normal (non-landmark) build simply leaves cutoutN/E/S/W unset,
      // so all four calls below are no-ops for it (see the cuts ==
      // null/empty guard inside applyCutout()).
      this.applyCutout(this.rotate(Direction.NORTH, this.direction), this.clipboard.getConfig().getCutoutN(), road, generator, base, sizeX, sizeZ);
      this.applyCutout(this.rotate(Direction.EAST, this.direction), this.clipboard.getConfig().getCutoutE(), road, generator, base, sizeX, sizeZ);
      this.applyCutout(this.rotate(Direction.SOUTH, this.direction), this.clipboard.getConfig().getCutoutS(), road, generator, base, sizeX, sizeZ);
      this.applyCutout(this.rotate(Direction.WEST, this.direction), this.clipboard.getConfig().getCutoutW(), road, generator, base, sizeX, sizeZ);

      // Ordinary single-side cutouts: relative to whichever side
      // this.direction actually points at (the one side that was chosen
      // as road-facing for this building) -- see the comment above for why
      // that's already the correctly-rotated side with no further
      // adjustment needed, so only that one side is ever checked.
      this.applyCutout(this.direction, this.clipboard.getConfig().getCutouts(), road, generator, base, sizeX, sizeZ);
   }

   /**
    * Composes a cutout's local side (defined against the unrotated
    * reference schematic -- NORTH meaning "the front side", per the
    * comment on makeCutoutsToward() above) with this parcel's actual
    * placement direction, returning which world side that local side
    * ended up on.
    *
    * Matches ClipboardImporter.rotateAndCacheSchematic()'s rotation chain
    * exactly: north -> east -> south -> west -> (north), each step one
    * rotateY(-90). placementDirection's position in that same cycle (0
    * for NORTH, 1 for EAST, 2 for SOUTH, 3 for WEST) is how many steps
    * forward localSide gets carried around the same cycle.
    * placementDirection == NONE (no rotation applied at all -- see
    * SchematicConfig.getDirection()) leaves localSide unchanged.
    */
   private Direction rotate(Direction localSide, Direction placementDirection) {
      return rotateSide(localSide, placementDirection);
   }

   /**
    * Static, side-effect-free core of rotate() above -- pulled out so
    * anything that needs to know "which world side will this local side
    * end up on" WITHOUT owning (or wanting to construct) a whole
    * ClipboardParcel can reuse the exact same rotation math instead of
    * re-deriving it. District's creek-orientation gate (see
    * District.filterCreekCandidatesByRiverProximity()) is the motivating
    * caller: it needs to know, for a CANDIDATE clip that hasn't been
    * placed yet, whether its configured riverStartBounds side would face
    * the river once rotated into this lot's (road-forced) direction.
    */
   public static Direction rotateSide(Direction localSide, Direction placementDirection) {
      Direction[] cycle = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
      int localIndex = -1;
      int steps = -1;

      for (int i = 0; i < cycle.length; i++) {
         if (cycle[i] == localSide) {
            localIndex = i;
         }
         if (cycle[i] == placementDirection) {
            steps = i;
         }
      }

      if (localIndex == -1 || steps == -1) {
         // placementDirection == NONE (or an otherwise-unrecognised
         // localSide, which shouldn't happen given the 4 call sites
         // above) -- no rotation applied, side stays where it is.
         return localSide;
      }

      return cycle[(localIndex + steps) % cycle.length];
   }

   /**
    * Computes every creek-start point this building declares via
    * riverStartBoundsN/E/S/W, in WORLD CHUNK coordinates, for
    * UrbanGrid.placeCreeks() to trace from. Reuses the exact same
    * per-side rotation (rotate() above) and per-side position formulas
    * makeCutoutsToward()/applyCutout() use for cutoutN/E/S/W -- the
    * geometry is identical (a fixed point along one specific unrotated
    * side of the schematic, translated through this placement's actual
    * rotation/mirror) -- just returning the resulting position instead
    * of carving a cutout there, and with no road-chunk gating, since a
    * creek origin doesn't wait on any particular neighbour the way a
    * cutout waits for its road.
    */
   public List<CreekOrigin> getCreekOrigins() {
      if (this.clipboard == null) {
         return new ArrayList<>();
      }
      return creekOriginsFor(this.clipboard, this.direction, this.chunkX, this.chunkZ);
   }

   /**
    * The pure, placement-free core of getCreekOrigins(): every creek start this clip declares
    * when placed with its base at chunk (chunkX, chunkZ) in the given placement direction. A pure
    * function of its arguments, so a creek site can be derived from the seed BEFORE any building
    * exists, and be exactly the origin the building will later report.
    */
   public static List<CreekOrigin> creekOriginsFor(Clipboard clipboard, Direction direction, int chunkX, int chunkZ) {
      List<CreekOrigin> origins = new ArrayList<>();
      if (clipboard == null || clipboard.getConfig() == null) {
         return origins;
      }
      int sizeX = clipboard.getSize().X;
      int sizeZ = clipboard.getSize().Z;
      boolean mirrored = clipboard.isMirrored();
      collectCreekOrigins(origins, rotateSide(Direction.NORTH, direction), clipboard.getConfig().getRiverStartBoundsN(), sizeX, sizeZ, chunkX, chunkZ, mirrored);
      collectCreekOrigins(origins, rotateSide(Direction.EAST, direction), clipboard.getConfig().getRiverStartBoundsE(), sizeX, sizeZ, chunkX, chunkZ, mirrored);
      collectCreekOrigins(origins, rotateSide(Direction.SOUTH, direction), clipboard.getConfig().getRiverStartBoundsS(), sizeX, sizeZ, chunkX, chunkZ, mirrored);
      collectCreekOrigins(origins, rotateSide(Direction.WEST, direction), clipboard.getConfig().getRiverStartBoundsW(), sizeX, sizeZ, chunkX, chunkZ, mirrored);
      return origins;
   }

   /** True if this clip declares at least one creek start on any side. */
   public static boolean hasCreekOrigin(Clipboard clipboard) {
      if (clipboard == null || clipboard.getConfig() == null) {
         return false;
      }
      return nonEmpty(clipboard.getConfig().getRiverStartBoundsN()) || nonEmpty(clipboard.getConfig().getRiverStartBoundsE())
              || nonEmpty(clipboard.getConfig().getRiverStartBoundsS()) || nonEmpty(clipboard.getConfig().getRiverStartBoundsW());
   }

   private static boolean nonEmpty(Object[] a) {
      return a != null && a.length > 0;
   }

   private static int reflectStart(int edgeLength, int startPoint, int length) {
      return edgeLength - startPoint - length + 2;
   }

   /**
    * One side's creek starts. Same per-side position formulas applyCutout() uses for cutouts (a
    * fixed span along one wall, carried through this placement's rotation and mirror), returning
    * the origin chunk (just outside the wall), the outward direction, and -- new -- the EXACT
    * block the water is centred on across the wall plus the block on the along axis where the
    * wall sits, so the creek can be anchored on the water itself instead of on a chunk centre.
    * Block spans follow the cutout convention: startPoint is 1-indexed, positive-size cutouts run
    * up from the offset, negative-size ones run down from it.
    */
   private static void collectCreekOrigins(List<CreekOrigin> origins, Direction side, AbstractSchematicConfig.RiverStartBound[] bounds,
                                           int sizeX, int sizeZ, int chunkX, int chunkZ, boolean mirrored) {
      if (bounds == null || bounds.length == 0) {
         return;
      }
      int baseX = chunkX << 4;
      int baseZ = chunkZ << 4;
      for (AbstractSchematicConfig.RiverStartBound bound : bounds) {
         switch (side) {
            case NORTH: {
               int esp = mirrored ? reflectStart(sizeX, bound.startPoint, bound.length) : bound.startPoint;
               int originChunkX = chunkX + ((sizeX - esp) >> 4);
               int originChunkZ = chunkZ - 1;
               double across = baseX + (sizeX - esp) + 1 - bound.length / 2.0;
               origins.add(new CreekOrigin(originChunkX, originChunkZ, Direction.NORTH, bound.length, across, (originChunkZ + 1) * 16.0));
               break;
            }
            case EAST: {
               int esp = mirrored ? reflectStart(sizeZ, bound.startPoint, bound.length) : bound.startPoint;
               int originChunkX = chunkX + (sizeX >> 4);
               int originChunkZ = chunkZ + ((sizeZ - esp) >> 4);
               double across = baseZ + (sizeZ - esp) + 1 - bound.length / 2.0;
               origins.add(new CreekOrigin(originChunkX, originChunkZ, Direction.EAST, bound.length, across, originChunkX * 16.0));
               break;
            }
            case SOUTH: {
               int esp = mirrored ? reflectStart(sizeX, bound.startPoint, bound.length) : bound.startPoint;
               int originChunkX = chunkX + ((esp - 1) >> 4);
               int originChunkZ = chunkZ + (sizeZ >> 4);
               double across = baseX + (esp - 1) + bound.length / 2.0;
               origins.add(new CreekOrigin(originChunkX, originChunkZ, Direction.SOUTH, bound.length, across, originChunkZ * 16.0));
               break;
            }
            case WEST: {
               int esp = mirrored ? reflectStart(sizeZ, bound.startPoint, bound.length) : bound.startPoint;
               int originChunkX = chunkX - 1;
               int originChunkZ = chunkZ + ((esp - 1) >> 4);
               double across = baseZ + (esp - 1) + bound.length / 2.0;
               origins.add(new CreekOrigin(originChunkX, originChunkZ, Direction.WEST, bound.length, across, (originChunkX + 1) * 16.0));
               break;
            }
            default:
               break;
         }
      }
   }

   /** One creek starting point: world chunk coordinates just outside the wall, and the direction pointing away from the building. */
   public static final class CreekOrigin {
      public final int chunkX;
      public final int chunkZ;
      public final Direction outward;
      /**
       * The origin's authored starting width in BLOCKS (RiverStartBound.
       * length); 0 if the schematic didn't set one. This IS the creek's
       * own rendered width where it starts -- half of it becomes
       * halfWidthBlocks, unclamped, in UrbanGrid.creekHalfWidthBlocks() --
       * since this bound represents real water already painted into the
       * building's own schematic.
       */
      public final int widthBlocks;
      /** World BLOCK coordinate, across the creek's axis, of the centre of the water where it leaves the wall. */
      public final double acrossBlock;
      /** World BLOCK coordinate, along the creek's axis, of the wall's edge -- where the creek starts. */
      public final double alongBlock;

      public CreekOrigin(int chunkX, int chunkZ, Direction outward, int widthBlocks, double acrossBlock, double alongBlock) {
         this.chunkX = chunkX;
         this.chunkZ = chunkZ;
         this.outward = outward;
         this.widthBlocks = widthBlocks;
         this.acrossBlock = acrossBlock;
         this.alongBlock = alongBlock;
      }
   }

   /**
    * Applies one cutout array to one specific, fixed side of this
    * building's bounding box -- side selects which side's
    * geometry formula to use (see the case comments below), completely
    * independent of this.direction. makeCutoutsToward() is what decides,
    * per caller, whether side is fixed per-side (the four
    * landmark cutoutN/E/S/W calls) or tied to this.direction (the
    * ordinary single `cutouts` call) -- this method itself doesn't care
    * which.
    *
    * No-op if cuts is null/empty (an unset landmark side, or a normal
    * build with no `cutouts` at all). Otherwise, for each cut, only
    * actually carves blocks if the given road's chunk coordinates are the
    * ones that specific cut's position on this side falls into -- a wide
    * side whose cutouts span multiple road chunk columns/rows only fires
    * the ones over the road chunk that's actually calling in right now,
    * same as before this was split out into its own method.
    */
   private void applyCutout(Direction side, AbstractSchematicConfig.RoadCutout[] cuts, StreetParcel road, MetropolisGenerator generator, Cartesian3D base, int sizeX, int sizeZ) {
      if (cuts == null || cuts.length == 0) {
         return;
      }

      int roadX = road.getChunkX();
      int roadZ = road.getChunkZ();

      for (AbstractSchematicConfig.RoadCutout cut : cuts) {
         switch (side) {
            case NORTH: {
               // NORTH face is at chunkZ - 1. startPoint is measured from
               // the east (high-X) end of the face going west, so the
               // cutout's world X position is base.X + (sizeX - startPoint).
               // Compute which road chunk column that X falls in, and only
               // fire when that specific road chunk triggers this call --
               // otherwise a wide building whose cutouts span multiple road
               // chunk columns would only ever get the one at column chunkX
               // applied (the old condition was this.chunkX == roadX for all
               // cutouts regardless of their actual position along the face).
               //
               // Mirrored variants (see Clipboard.isMirrored()) reflect
               // which side of the face the door/cutout sits on, without
               // changing which face is road-facing -- reflectStartPoint()
               // remaps startPoint to where the feature actually ended up
               // after the mirror, so the cutout lands under the real
               // entrance instead of the un-mirrored position.
               int effectiveStartPoint = this.clipboard.isMirrored()
                       ? this.reflectStartPoint(sizeX, cut.startPoint, cut.length)
                       : cut.startPoint;
               if (this.chunkZ - 1 == roadZ) {
                  { // Each block is clipped to the calling road chunk below.
                     Cartesian3D offset = new Cartesian3D(sizeX - effectiveStartPoint, 0, -1);
                     Cartesian3D size = new Cartesian3D(-cut.length, 11, -8);
                     Minions.dCutout("makeCutoutsToward: NORTH cut at (" + base.add(offset).X + "," + base.add(offset).Z + ") for road [" + roadX + "," + roadZ + "]" + (this.clipboard.isMirrored() ? " (mirrored)" : ""));
                     this.cutoutBlocks(generator, base.add(offset), size, Material.STONE, roadX, roadZ);
                  }
               }
               break;
            }
            case EAST: {
               // EAST face is at chunkX + (sizeX>>4). startPoint is measured
               // from the south (high-Z) end going north, so the cutout's
               // world Z position is base.Z + (sizeZ - startPoint). Compute
               // which road chunk row that Z falls in.
               int effectiveStartPoint = this.clipboard.isMirrored()
                       ? this.reflectStartPoint(sizeZ, cut.startPoint, cut.length)
                       : cut.startPoint;
               int eastRoadX = this.chunkX + (sizeX >> 4);
               if (eastRoadX == roadX) {
                  { // Each block is clipped to the calling road chunk below.
                     Cartesian3D offset = new Cartesian3D(sizeX, 0, sizeZ - effectiveStartPoint);
                     Cartesian3D size = new Cartesian3D(8, 11, -cut.length);
                     Minions.dCutout("makeCutoutsToward: EAST cut at (" + base.add(offset).X + "," + base.add(offset).Z + ") for road [" + roadX + "," + roadZ + "]" + (this.clipboard.isMirrored() ? " (mirrored)" : ""));
                     this.cutoutBlocks(generator, base.add(offset), size, Material.STONE, roadX, roadZ);
                  }
               }
               break;
            }
            case SOUTH: {
               // SOUTH face is at chunkZ + (sizeZ>>4). startPoint is measured
               // from the west (low-X) end going east, so the cutout's world
               // X position is base.X + (startPoint - 1). Compute which road
               // chunk column that X falls in.
               int effectiveStartPoint = this.clipboard.isMirrored()
                       ? this.reflectStartPoint(sizeX, cut.startPoint, cut.length)
                       : cut.startPoint;
               int southRoadZ = this.chunkZ + (sizeZ >> 4);
               if (southRoadZ == roadZ) {
                  { // Each block is clipped to the calling road chunk below.
                     Cartesian3D offset = new Cartesian3D(effectiveStartPoint - 1, 0, sizeZ);
                     Cartesian3D size = new Cartesian3D(cut.length, 11, 8);
                     Minions.dCutout("makeCutoutsToward: SOUTH cut at (" + base.add(offset).X + "," + base.add(offset).Z + ") for road [" + roadX + "," + roadZ + "]" + (this.clipboard.isMirrored() ? " (mirrored)" : ""));
                     this.cutoutBlocks(generator, base.add(offset), size, Material.STONE, roadX, roadZ);
                  }
               }
               break;
            }
            case WEST: {
               // WEST face is at chunkX - 1. startPoint is measured from
               // the north (low-Z) end going south, so the cutout's world
               // Z position is base.Z + (startPoint - 1). Compute which road
               // chunk row that Z falls in.
               int effectiveStartPoint = this.clipboard.isMirrored()
                       ? this.reflectStartPoint(sizeZ, cut.startPoint, cut.length)
                       : cut.startPoint;
               if (this.chunkX - 1 == roadX) {
                  { // Each block is clipped to the calling road chunk below.
                     Cartesian3D offset = new Cartesian3D(-1, 0, effectiveStartPoint - 1);
                     Cartesian3D size = new Cartesian3D(-8, 11, cut.length);
                     Minions.dCutout("makeCutoutsToward: WEST cut at (" + base.add(offset).X + "," + base.add(offset).Z + ") for road [" + roadX + "," + roadZ + "]" + (this.clipboard.isMirrored() ? " (mirrored)" : ""));
                     this.cutoutBlocks(generator, base.add(offset), size, Material.STONE, roadX, roadZ);
                  }
               }
               break;
            }
         }
      }
   }

   /**
    * Reflects a cutout's startPoint to where it actually ends up after a
    * WEST_EAST mirror (see ClipboardImporter.rotateAndCacheSchematic() and
    * Clipboard.isMirrored()). A mirror reverses column order along the
    * road-facing edge without changing which edge is road-facing, so a
    * feature originally occupying local columns [startPoint,
    * startPoint+length-1] (1-indexed, matching how startPoint is already
    * used in every offset formula above) occupies columns
    * [edgeLength-startPoint-length+2, edgeLength-startPoint+1] afterward.
    * The lower bound of that reflected range is the new effective
    * startPoint -- substituting it directly into the same unmirrored
    * formulas above reproduces the correct, reflected cutout position
    * without needing separate mirrored-case geometry.
    */
   private int reflectStartPoint(int edgeLength, int startPoint, int length) {
      return reflectStart(edgeLength, startPoint, length);
   }

   /**
    * Package-visible and static (it only uses its arguments) so other parcels, like the farm's dirt roads, can reuse the same sidewalk cutout.
    * A null floor clears the space only and does not re-floor the street under it.
    */
   static void cutoutBlocks(MetropolisGenerator generator, Cartesian3D v, Cartesian3D s, Material floor) {
      cutoutBlocks(generator,v,s,floor,Integer.MIN_VALUE,Integer.MIN_VALUE);
   }

   private static void cutoutBlocks(MetropolisGenerator generator, Cartesian3D v, Cartesian3D s,
                                    Material floor,int onlyChunkX,int onlyChunkZ) {
      int xdir = s.X < 0 ? -1 : 1;
      int ydir = s.Y < 0 ? -1 : 1;
      int zdir = s.Z < 0 ? -1 : 1;
      World world = generator.getWorld();

      for(int x = 0; Math.abs(x) < Math.abs(s.X); x += xdir) {
         for(int z = 0; Math.abs(z) < Math.abs(s.Z); z += zdir) {
            // An opening may straddle two adjacent road chunks. Each completed
            // road owns exactly its portion of the opening, so no clipping
            // depends on which neighbouring road generated first.
            if (onlyChunkX != Integer.MIN_VALUE && (((v.X + x) >> 4) != onlyChunkX
                  || ((v.Z + z) >> 4) != onlyChunkZ)) continue;
            // Road cutouts reach into the NEIGHBOURING parcel (see the
            // grid.getParcel(...) calls in makeCutouts above) whose chunk
            // may not be generated yet -- same synchronous-generation/
            // recursion risk as the decay providers, guarded the same way.
            if (!world.isChunkLoaded((v.X + x) >> 4, (v.Z + z) >> 4)) {
               continue;
            }

            for(int y = 0; Math.abs(y) < Math.abs(s.Y); y += ydir) {
               generator.getWorld().getBlockAt(v.X + x, v.Y + y, v.Z + z).setType(Material.AIR, false);
            }

            // floor == null: only clear what stands above the street (kerb, sidewalk, posts)
            // and leave the street surface itself exactly as the road pasted it
            if (floor != null) {
               Block below = generator.getWorld().getBlockAt(v.X + x, v.Y - 1, v.Z + z);
               if (!below.isEmpty()) {
                  generator.getWorld().getBlockAt(v.X + x, v.Y - 1, v.Z + z).setType(floor, false);
               }
            }
         }
      }
   }

   public String toString() {
      return this.clipboard == null ? "ClipboardParcel: No clip found" : "ClipboardParcel: " + this.clipboard.toString();
   }
}