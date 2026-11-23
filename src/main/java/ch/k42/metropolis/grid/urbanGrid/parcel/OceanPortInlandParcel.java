package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.minions.Cartesian2D;
import org.bukkit.Chunk;

/** Reserved inland shore cell of a port. Only the anchor paints it, before constructing anything. */
public final class OceanPortInlandParcel extends OceanParcel {
    private boolean carved;

    public OceanPortInlandParcel(UrbanGrid grid, Cartesian2D base) {
        super(grid, base);
    }

    @Override
    public void populate(MetropolisGenerator generator, Chunk chunk) {
        // Do not allow a late ocean carve to erase the pier or its supports.
    }

    void carveForPort(MetropolisGenerator generator) {
        if (!carved) {
            carved = true;
            super.carveChunk(generator);
        }
    }
}
