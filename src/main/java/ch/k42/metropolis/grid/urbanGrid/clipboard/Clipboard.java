package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.config.SchematicConfig;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.Cartesian3D;

public interface Clipboard {
   void paste(MetropolisGenerator var1, Cartesian2D var2, int var3);

   Cartesian3D getSize();

   int getBottom(int var1);

   SchematicConfig getConfig();

   String getGroupId();

   /**
    * True if this clip is a mirrored variant (see ClipboardImporter's
    * "_M" cache files) rather than a plain rotation. A mirror keeps the
    * same road-facing edge as its unmirrored counterpart but reflects
    * which side of that edge features (the door/cutout point) sit on --
    * ClipboardParcel.makeCutoutsToward() needs this to compute the
    * correct, reflected cutout offset instead of carving into the wrong
    * side of the road.
    */
   boolean isMirrored();
}