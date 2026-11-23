package ch.k42.metropolis.grid.urbanGrid.districts;

import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.minions.Cartesian2D;

public interface IDistrict {
   void initDistrict(Cartesian2D var1, Cartesian2D var2, UrbanGrid var3);

   /**
    * Initialises this district as a landmark district -- its entire
    * base/size footprint is committed up front to a single, already-chosen
    * clipboard rather than being filled via the normal random partition.
    * fillDistrict() places it directly with no iteration. See
    * UrbanGrid.tryPlaceLandmark() for how base/size/clip are chosen.
    *
    * direction is passed in rather than recomputed here deliberately: by
    * the time this is called, base/size ARE this district's own footprint,
    * so re-deriving direction by comparing them against themselves (the
    * same way District.findRoad() compares a placement against its
    * parent district) would trivially treat every side as road-adjacent,
    * including sides that were actually cut into an unroaded remainder
    * region. The caller already determined the real road-adjacent sides
    * while carving, and already confirmed clip matches that exact
    * direction via getFit() -- that same direction must be reused here,
    * not recomputed.
    */
   void initForcedDistrict(Cartesian2D base, Cartesian2D size, UrbanGrid grid, Clipboard clip, Direction direction);

   void fillDistrict();

   Cartesian2D getBase();

   Cartesian2D getSize();

   /**
    * The context this district was zoned with: the one fillDistrict()
    * resolved, or (before that) the one assignContext() was given. Null
    * while neither has happened yet.
    */
   ContextType getContext();

   /**
    * Pre-assigns this district's context so fillDistrict() uses it instead
    * of sampling the noise lookup at its own base. See
    * DistrictContextAssigner.
    */
   void assignContext(ContextType context);
}