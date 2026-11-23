package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.minions.Cartesian2D;
import org.bukkit.Chunk;

/**
 * A chunk covered by a multi-chunk boat but not its anchor. It is still an
 * OceanParcel (water, depth tier, etc. all work), but it never carves on its
 * own: the anchor's OceanBoatParcel carves it right before pasting, so a
 * later carve of this chunk can't erase part of the boat.
 */
public class OceanBoatTailParcel extends OceanParcel {

    private boolean carved = false;

    public OceanBoatTailParcel(UrbanGrid grid, Cartesian2D base) {
        super(grid, base);
    }

    @Override
    public void populate(MetropolisGenerator generator, Chunk chunk) {
        // Intentionally empty: carved by the anchor boat parcel.
    }

    /** Carves this chunk once, on behalf of the anchor. */
    void carveForBoat(MetropolisGenerator generator) {
        if (!this.carved) {
            this.carved = true;
            carveChunk(generator);
        }
    }
}
