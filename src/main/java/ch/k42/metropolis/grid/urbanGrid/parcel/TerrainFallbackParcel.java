package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Mats;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.plugin.PluginConfig;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.util.noise.SimplexOctaveGenerator;

/**
 * Toggleable last-resort fallback for the single case District.fillDistrict()
 * previously just logged and walked away from: a 1x1 chunk where NOTHING --
 * not a BUILD, not a context filler, not even a context-free filler --
 * could be found to place there at all. Left alone, that chunk keeps
 * whatever the underlying world generator produced before Metropolis ever
 * touched it: raw pregenerated terrain sitting right next to buildings
 * pasted at a flat PluginConfig.getBuildHeight(), which reads as an abrupt
 * cliff or hole rather than a deliberate gap.
 *
 * Only enabled via PluginConfig.isFillerTerrainFallbackEnabled() -- when
 * off, District.fillDistrict() keeps its original behaviour (log and leave
 * the chunk untouched) exactly as before this existed.
 *
 * IMPORTANT ARCHITECTURAL NOTE: District.fillDistrict() runs at district-
 * carving time, inside UrbanGrid's constructor, BEFORE any chunk has
 * actually been populated by Bukkit -- there is no World/Chunk to write
 * blocks into yet at that point, only the grid's own bookkeeping. That's
 * why this can't just be a few extra lines where the old "can't find
 * anything" log line was: it has to be a real Parcel, registered into the
 * grid the same way ClipboardParcel is, so its actual terrain carving runs
 * later in populate() -- the same lifecycle point ClipboardParcel pastes
 * its schematic -- once a real Chunk/World exists to sample and write to.
 */
public class TerrainFallbackParcel extends Parcel {

    public TerrainFallbackParcel(Cartesian2D base, ContextType contextType, UrbanGrid grid) {
        super(base, new Cartesian2D(1, 1), contextType, SchematicType.FILLER, grid);
    }

    @Override
    public void populate(MetropolisGenerator generator, Chunk chunk) {
        if (chunk.getX() != this.chunkX || chunk.getZ() != this.chunkZ) {
            return;
        }

        World world = generator.getWorld();
        if (world == null) {
            return;
        }

        int baseX = this.chunkX << 4;
        int baseZ = this.chunkZ << 4;
        // Same river-reclaim-aware height ClipboardParcel pastes at (see
        // Parcel.effectiveBuildHeight()) -- a gap chunk that happens to
        // fall inside a reclaimed district should settle at the same
        // elevation as the reclaimed ground and buildings around it, not
        // the general buildHeight they've deliberately been pulled away
        // from.
        int buildHeight = this.effectiveBuildHeight();

        // Sample the real, already-there height at each of the four edges
        // bordering this gap -- one column each, just outside the chunk on
        // its north/south/east/west side. getHighestBlockYAt() works whether
        // that neighbour is an already-pasted building (reads its roof/street
        // level) or a not-yet-touched chunk (reads the vanilla pregenerated
        // terrain that will still be there when THAT chunk populates), so
        // this responds to the actual surroundings rather than assuming
        // every neighbour sits at a flat buildHeight.
        // firstAirAbove(), not getHighestBlockYAt(): this is averaged into a
        // target height, so it must keep the old "first free cell above the
        // surface" value the clamp and buildHeight were tuned against. See
        // Mats.firstAirAbove().
        // A neighbour that is not loaded yet counts as sitting at buildHeight
        // (what the raw terrain it would have read is tuned to match) --
        // asking for its height would force it to generate and populate from
        // here, and a row of gap chunks would cascade outward forever. See
        // Mats.firstAirAboveIfLoaded().
        int north = Mats.firstAirAboveIfLoaded(world, baseX + 8, baseZ - 1, buildHeight);
        int south = Mats.firstAirAboveIfLoaded(world, baseX + 8, baseZ + 16, buildHeight);
        int east = Mats.firstAirAboveIfLoaded(world, baseX + 16, baseZ + 8, buildHeight);
        int west = Mats.firstAirAboveIfLoaded(world, baseX - 1, baseZ + 8, buildHeight);
        int edgeHeight = Math.round((north + south + east + west) / 4.0f);

        // Sanity-clamp: a lone gap chunk surrounded by nothing but raw ocean
        // or a deep pre-existing ravine could pull this number somewhere
        // wildly off from the city's actual street level. Keep it within a
        // reasonable band of buildHeight so a single bad sample can't produce
        // a bizarre tower or pit that reads just as jarring as the cliff this
        // is supposed to fix.
        int maxDeviation = PluginConfig.getFillerTerrainMaxDeviation();
        edgeHeight = Math.max(buildHeight - maxDeviation, Math.min(buildHeight + maxDeviation, edgeHeight));

        int noiseIntensity = PluginConfig.getFillerTerrainNoiseIntensity();
        long seed = ((long) this.chunkX << 32) ^ (this.chunkZ & 0xffffffffL) ^ generator.getWorld().getSeed();
        SimplexOctaveGenerator noiseGen = noiseIntensity > 0 ? new SimplexOctaveGenerator(seed, 2) : null;
        if (noiseGen != null) {
            noiseGen.setScale(1.0 / 16.0);
        }

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = baseX + lx;
                int z = baseZ + lz;

                // Distance (0.0 at the chunk's outer ring, 1.0 at its centre)
                // used to fade noise-based variation in from the edges, so the
                // very columns bordering a real neighbour land close to the
                // sampled edge height (matching it, not creating a new seam)
                // while the interior is free to vary naturally.
                double edgeDistance = Math.min(Math.min(lx, 15 - lx), Math.min(lz, 15 - lz)) / 7.5;
                double edgeFactor = Math.min(1.0, edgeDistance);

                int noise = 0;
                if (noiseGen != null) {
                    noise = (int) Math.round(noiseGen.noise(x, z, 0.5, 0.5) * noiseIntensity * edgeFactor);
                }

                int targetHeight = edgeHeight + noise;

                // Clear anything the underlying world generator already put
                // above the target -- otherwise raw pregenerated terrain taller
                // than the surrounding street level would poke up through the
                // smoothed surface.
                int currentTop = world.getHighestBlockYAt(x, z);
                clearAboveKeepingTrees(world, x, z, targetHeight + 1, currentTop);

                world.getBlockAt(x, targetHeight, z).setType(Material.GRASS_BLOCK, false);
                for (int y = targetHeight - 1; y >= targetHeight - 3 && y >= 1; y--) {
                    world.getBlockAt(x, y, z).setType(Material.DIRT, false);
                }
                int stoneFloor = Math.max(1, targetHeight - PluginConfig.getFillerTerrainStoneDepth());
                for (int y = targetHeight - 4; y >= stoneFloor; y--) {
                    world.getBlockAt(x, y, z).setType(Material.STONE, false);
                }
            }
        }

        Minions.dDistrict("TERRAIN FALLBACK PLACED context='%s' chunk=(%d,%d) edgeHeight=%d (n=%d s=%d e=%d w=%d) noiseIntensity=%d",
                this.contextType, this.chunkX, this.chunkZ, edgeHeight, north, south, east, west, noiseIntensity);

        // Geometry here is finalised the instant this runs (raw carved
        // terrain, never touched again) -- mark it the same way
        // ClipboardParcel/RiverParcel/CreekParcel do, so a neighbouring
        // build's own growth-readiness check (see
        // DecayProviderNormal.neighbourhoodReady()) doesn't wait forever
        // on a fallback tile that was never going to report in any other
        // way.
        this.markPopulated();
    }

    @Override
    public void postPopulate(MetropolisGenerator generator, Chunk chunk) {
        // No decay, no cutouts, no growth to cache -- this is raw terrain,
        // not a schematic. Roads that later border this chunk still cut
        // into it the same way they cut into any other pre-existing
        // terrain. Nothing left to report -- populate() already marked
        // this parcel populated (see its own comment), which is all any
        // neighbour's growth-readiness check needs from it.
    }
}