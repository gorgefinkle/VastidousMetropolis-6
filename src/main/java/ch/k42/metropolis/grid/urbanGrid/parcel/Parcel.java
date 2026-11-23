package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.District;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.river.RiverReclaim;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;

public abstract class Parcel {
   protected int chunkX;
   protected int chunkZ;
   protected int chunkSizeX;
   protected int chunkSizeZ;
   protected ContextType contextType;
   protected UrbanGrid grid;
   protected SchematicType schematicType;

   /**
    * True once this parcel's own schematic (if any) has actually been
    * pasted -- populate() has run, decay/growth on it may not have yet.
    * Set by populate() itself (see ClipboardParcel), read by a
    * NEIGHBOURING parcel's growth pass (DecayProviderNormal) deciding
    * whether it's safe to let a branch/vine/slime reach across the lot
    * line onto this parcel: once pasted, nothing will ever overwrite
    * that geometry again, so growth landing on it is permanent rather
    * than doomed to be silently erased by a late paste.
    */
   private boolean populated = false;

   public boolean isPopulated() {
      return this.populated;
   }

   /**
    * A building that straddles a tile edge exists in both tiles; only the tile that holds its base chunk pastes it. The other
    * tile's copy has nothing to paste, so it counts as done from the start (a neighbour waiting for it to be "placed" must not
    * wait for a populate() that never comes).
    */
   public void markHandledByOwner() {
      this.populated = true;
   }

   protected void markPopulated() {
      this.populated = true;
      // Tell the grid so DecayProviderNormal can re-check whether this
      // placement is what some OTHER, already-decayed build nearby was
      // waiting on before flushing its own cached growth -- see
      // UrbanGrid.notifyParcelPopulated()/DecayProviderNormal.onNeighbourPlaced().
      if (this.grid != null) {
         this.grid.notifyParcelPopulated(this);
      }
   }

   /**
    * True for the blocks World.generateTree() writes -- trunk and canopy of
    * the TREE/BIRCH/REDWOOD types the river and ocean vegetation passes use
    * (any log or leaf block, whatever the wood).
    */
   public static boolean isTreeBlock(Material material) {
      return Mats.LOGS.contains(material) || Mats.LEAVES.contains(material);
   }

   /**
    * Sets every block in [fromY, toY] of one column to air, EXCEPT trunk and
    * leaf blocks. This is the "clear whatever the raw generator left above
    * the surface" pass the river/ocean/fallback painters all run when they
    * carve a chunk -- and it must leave trees alone: a tree planted near a
    * chunk edge by the NEIGHBOURING chunk's vegetation pass writes its
    * canopy into this chunk before this chunk has been carved (chunks
    * populate in whatever order they load). Clearing that canopy here is
    * what used to slice trees in half along the chunk border. The raw
    * generator never produces logs or leaves of its own, so nothing that
    * needs clearing is spared.
    */
   public static void clearAboveKeepingTrees(World world, int x, int z, int fromY, int toY) {
      for (int y = fromY; y <= toY; y++) {
         if (!isTreeBlock(world.getBlockAt(x, y, z).getType())) {
            world.getBlockAt(x, y, z).setType(Material.AIR, false);
         }
      }
   }

   /**
    * The District (city block) this parcel is a member of, or null for
    * anything not placed by a District -- roads/highways and anything
    * else UrbanGrid places directly. Set once, by District.fillDistrict()
    * itself, after it has settled this district's FINAL parcel set
    * (including any extraCutMode re-cut). Pure metadata today -- growth
    * no longer batches by district (see DecayProviderNormal's per-build
    * cache/neighbourhood-trigger instead) -- kept because it's still a
    * meaningful "which city block is this building part of" fact.
    */
   private District district;

   public District getDistrict() {
      return this.district;
   }

   public void setDistrict(District district) {
      this.district = district;
   }


   protected Parcel(Cartesian2D base, Cartesian2D size, ContextType contextType, SchematicType schematicType, UrbanGrid grid) {
      this.chunkX = base.X;
      this.chunkZ = base.Y;
      this.chunkSizeX = size.X;
      this.chunkSizeZ = size.Y;
      this.contextType = contextType;
      this.schematicType = schematicType;
      this.grid = grid;
   }

   public SchematicType getSchematicType() {
      return this.schematicType;
   }

   public abstract void populate(MetropolisGenerator var1, Chunk var2);

   public abstract void postPopulate(MetropolisGenerator var1, Chunk var2);

   public ContextType getContextType() {
      return this.contextType;
   }

   public void setContextType(ContextType contextType) {
      this.contextType = contextType;
   }

   public int getChunkX() {
      return this.chunkX;
   }

   public void setChunkX(int chunkX) {
      this.chunkX = chunkX;
   }

   public int getChunkZ() {
      return this.chunkZ;
   }

   public void setChunkZ(int chunkZ) {
      this.chunkZ = chunkZ;
   }

   public int getChunkSizeX() {
      return this.chunkSizeX;
   }

   public void setChunkSizeX(int chunkSizeX) {
      this.chunkSizeX = chunkSizeX;
   }

   public int getChunkSizeZ() {
      return this.chunkSizeZ;
   }

   public void setChunkSizeZ(int chunkSizeZ) {
      this.chunkSizeZ = chunkSizeZ;
   }

   public Cartesian2D getChunkSize() {
      return new Cartesian2D(this.chunkX, this.chunkZ);
   }

   @Override
   public String toString() {
      return "Parcel{chunkSizeX="
              + this.chunkX
              + ", chunkSizeZ="
              + this.chunkZ
              + ", chunkSizeX="
              + this.chunkSizeX
              + ", chunkSizeZ="
              + this.chunkSizeZ
              + ", contextType="
              + this.contextType
              + '}';
   }

   /**
    * PluginConfig.getBuildHeight(), except when this parcel's chunk is
    * inside a river-reclaimed district, where it returns
    * RiverConfig.getRiverReclaimBuildHeight() instead.
    *
    * WHY A SEPARATE HEIGHT AT ALL: a reclaimed district's land is carved
    * by RiverParcel.carveColumn() out to its water-facing edge at
    * whatever height this method returns for that chunk (see
    * RiverReclaim/RiverParcel's javadoc) -- that's deliberate, so the
    * bank's slope and the flat ground beyond it read as one continuous
    * elevation. Anything pasted on top of that ground at a DIFFERENT
    * height would float or sink relative to the ground it's supposedly
    * standing on, even though the exact same mismatch is an accepted
    * tradeoff one step further out, where the corridor's riverside road
    * meets this land (that road deliberately stays at the general height;
    * see RiverParcel.carveColumn()'s javadoc for why that one step is
    * kept).
    *
    * WHY riverReclaimBuildHeight RATHER THAN riverBuildHeight: the latter
    * is water-relative (RiverParcel/CreekParcel key waterY off it, so
    * raising it raises the river too), and has nothing to do with what
    * height a district's buildings should sit at. Reusing it here was a
    * shortcut that happened to work when both were left at their shared
    * default of 63; the two are free to diverge now that reclaimed
    * districts have their own knob.
    *
    * Every parcel type that pastes or clears terrain at "build height" --
    * ClipboardParcel, TerrainFallbackParcel -- should read it from here
    * rather than calling PluginConfig.getBuildHeight() directly, so this
    * stays the one place that decides.
    */
   protected int effectiveBuildHeight() {
      if (this.grid != null) {
         if (this.grid.isRiverReclaimedChunk(this.chunkX, this.chunkZ)) {
            return RiverConfig.getRiverReclaimBuildHeight();
         }
      }
      return PluginConfig.getBuildHeight();
   }

   public static boolean isStreet(Parcel p) {
      if (p == null) {
         return false;
      }

      ContextType context = p.getContextType();
      return context.equals(ContextType.STREET) || context.equals(ContextType.HIGHWAY);
   }

   /**
    * Direction-aware companion to isStreet(Parcel). A plain isStreet(p)
    * treats a neighbouring BridgeParcel as a street connection on every
    * side, because BridgeParcel keeps ContextType.STREET/HIGHWAY on all
    * four faces so unrelated lookups (decay, growth) still see it as
    * road network regardless of which side they touch it from. But a
    * bridge only actually continues the road at its two span ends -- the
    * other two sides are the parapet running down the length of the
    * deck, not a real junction. Callers deciding whether to grow a
    * connector TOWARD a specific neighbour -- RoadParcel and
    * HighwayParcel picking their own shape -- need that distinction, or
    * a road running alongside a bridge grows a T-junction stub into its
    * side. direction is the axis being probed (the direction the caller
    * used to reach this neighbour); see BridgeParcel.connectsOnSide()
    * for why either direction of a pair (EAST/WEST, NORTH/SOUTH) is
    * equivalent here.
    */
   public static boolean isStreet(Parcel p, Direction direction) {
      if (p instanceof BridgeParcel) {
         return ((BridgeParcel) p).connectsOnSide(direction);
      }
      return isStreet(p);
   }
}