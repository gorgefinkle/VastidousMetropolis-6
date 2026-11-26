package ch.k42.metropolis.grid.urbanGrid.provider;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.districts.District;
import ch.k42.metropolis.grid.urbanGrid.parcel.Parcel;
import ch.k42.metropolis.minions.Cartesian3D;
import ch.k42.metropolis.minions.DecayOption;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.minions.Minions;
import com.google.common.collect.ImmutableSet;
import java.util.Random;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

public abstract class DecayProvider {
   protected MetropolisGenerator generator;
   protected Random random;
   private static Set<Material> invalidBlocks = ImmutableSet.<Material>builder()
           .add(Material.GRASS_BLOCK).addAll(Mats.LEAVES).addAll(Mats.LOGS).build();
   private static Set<Material> unsupportingBlocks = ImmutableSet.<Material>builder()
           .add(Material.VINE).addAll(Mats.LEAVES).addAll(Mats.LOGS).build();

   public DecayProvider(MetropolisGenerator generator, Random random) {
      this.generator = generator;
      this.random = random;
   }

   /** Pending cache entries that must be drained before generation can be considered caught up. */
   public int pendingGrowthWorkCount() {
      return 0;
   }

   public void destroyWithin(int x1, int x2, int y1, int y2, int z1, int z2) {
      this.destroyWithin(x1, x2, y1, y2, z1, z2, DecayOption.getDefaultDecayOptions());
   }

   public void destroyChunks(int chunkX, int chunkZ, int chunkSizeX, int chunkSizeZ, int bottom, int height, DecayOption options) {
      this.destroyChunks(chunkX, chunkZ, chunkSizeX, chunkSizeZ, bottom, height, options, null);
   }

   /**
    * Same as the grid-less overload, but also passes along the UrbanGrid
    * this structure belongs to. Providers that support it (see
    * DecayProviderNormal) use it to peek at NEIGHBOURING parcels --
    * whether they've already been pasted, whether they're a road -- when
    * deciding how far growth is allowed to reach. grid may be null (older
    * call sites, or a decay pass with no real grid context); providers
    * must treat that conservatively, i.e. exactly like today's
    * lot-only behaviour, not as "anything goes".
    */
   public void destroyChunks(int chunkX, int chunkZ, int chunkSizeX, int chunkSizeZ, int bottom, int height, DecayOption options, UrbanGrid grid) {
      this.destroyChunks(chunkX, chunkZ, chunkSizeX, chunkSizeZ, bottom, height, options, grid, null);
   }

   /**
    * Same as the grid-aware overload, but also passes along the District
    * (city block) this structure is a member of, or null if it isn't one
    * (a road, a landmark, anything UrbanGrid places outside a District).
    * District membership plays no part in growth today -- growth now
    * caches and flushes per BUILD (Parcel), gated purely on its own
    * neighbourhood being placed (see DecayProviderNormal), which already
    * crosses district/block boundaries just as freely as it crosses lot
    * lines within one. This parameter is kept for callers that still
    * track district membership for other reasons (see Parcel.getDistrict()).
    */
   public void destroyChunks(int chunkX, int chunkZ, int chunkSizeX, int chunkSizeZ, int bottom, int height, DecayOption options, UrbanGrid grid, District district) {
      int x1 = chunkX << 4;
      int x2 = x1 + (chunkSizeX << 4);
      int z1 = chunkZ << 4;
      int z2 = z1 + (chunkSizeZ << 4);
      int y1 = bottom;
      int y2 = bottom + height;
      Minions.dDecay("destroyChunks chunk=[%d,%d] size=%dx%d bounds=(%d,%d,%d)-(%d,%d,%d) provider=%s",
              chunkX, chunkZ, chunkSizeX, chunkSizeZ, x1, y1, z1, x2, y2, z2, this.getClass().getSimpleName());
      this.destroyWithin(x1, x2, y1, y2, z1, z2, options, grid, district);
   }

   public void destroyWithin(Cartesian3D start, Cartesian3D end, DecayOption options) {
      this.destroyWithin(start.X, end.X, start.Y, end.Y, start.Z, end.Z, options);
   }

   /**
    * Base case of the destroyWithin() fallback chain -- deliberately a
    * no-op, exactly as it always was before this class grew grid/
    * district awareness. Nothing outside DecayProvider ever calls
    * destroyWithin() directly (every real entry point is destroyChunks(),
    * which always lands on the most-specific 9-arg overload below and
    * falls through toward this one only if nothing more specific is
    * overridden) -- so this only needs to exist as the one true
    * terminus a provider that overrides just THIS old signature (see
    * DecayProviderNether) can still be reached by, via ordinary virtual
    * dispatch. It must never call back UP to a more-specific overload:
    * an earlier version of this chain had the 8-arg and 9-arg defaults
    * calling each other (8-arg -> 9-arg with a null district -> 8-arg
    * again, forever), which produced an immediate StackOverflowError
    * for any provider -- like DecayProviderNether -- that overrides
    * neither of them. The chain below is strictly one-directional
    * (9-arg falls to 8-arg falls to this) specifically to make that
    * class of bug structurally impossible to reintroduce.
    */
   @Deprecated
   public void destroyWithin(int x1, int x2, int y1, int y2, int z1, int z2, DecayOption options) {
   }

   /** Grid-aware version of destroyWithin() -- see destroyChunks(..., UrbanGrid) above. Default implementation ignores grid and falls through toward the plain version above -- see that method's javadoc for why this direction, and only this direction, is safe. */
   public void destroyWithin(int x1, int x2, int y1, int y2, int z1, int z2, DecayOption options, UrbanGrid grid) {
      this.destroyWithin(x1, x2, y1, y2, z1, z2, options);
   }

   /** District-aware version of destroyWithin() -- see destroyChunks(..., UrbanGrid, District) above. Default implementation ignores district and falls through toward the grid-only version above -- see that method's javadoc for why this direction, and only this direction, is safe. */
   public void destroyWithin(int x1, int x2, int y1, int y2, int z1, int z2, DecayOption options, UrbanGrid grid, District district) {
      this.destroyWithin(x1, x2, y1, y2, z1, z2, options, grid);
   }

   /**
    * Called once, by UrbanGrid.notifyParcelPopulated(), every time ANY
    * parcel anywhere in that grid tile gets its schematic pasted (see
    * Parcel.markPopulated()). A provider that caches growth per BUILD
    * (see DecayProviderNormal) uses this as its cue to re-check whether
    * this new placement is what some OTHER, already-decayed build nearby
    * was waiting on before flushing its own cached growth -- see
    * DecayProviderNormal.neighbourhoodReady(). No-op by default; a
    * provider that doesn't cache anything never needs this to do
    * anything, and every parcel calls it unconditionally on placement
    * regardless of whether anyone's listening.
    */
   public void onNeighbourPlaced(UrbanGrid grid, Parcel parcel) {
   }

   /** Optional cross-chunk growth resume hook; providers without cached growth do nothing. */
   public void onChunkLoaded(World world, int chunkX, int chunkZ) {
   }

   /** Fast check before scheduling a deferred loaded-chunk growth flush. */
   public boolean hasPendingGrowthAt(int chunkX, int chunkZ) {
      return false;
   }

   protected boolean isValid(Block block) {
      return !invalidBlocks.contains(block.getType());
   }

   protected boolean isSupporting(Block block) {
      return !unsupportingBlocks.contains(block.getType()) && !block.isEmpty();
   }
}