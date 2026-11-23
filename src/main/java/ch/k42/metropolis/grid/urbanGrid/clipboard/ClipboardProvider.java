package ch.k42.metropolis.grid.urbanGrid.clipboard;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Set;

public interface ClipboardProvider {
   void loadClips(MetropolisPlugin var1) throws FileNotFoundException;

   @Deprecated
   List<Clipboard> getRoadFit(RoadType var1);

   List<Clipboard> getRoadFit(RoadType var1, ContextType var2);

   List<Clipboard> getFit(Cartesian2D var1, ContextType var2, SchematicType var3, Direction var4);

   List<Clipboard> getFit(Cartesian2D var1, SchematicType var2, Direction var3);

   /** Returns all schematics for the context/type/direction without size filtering. */
   List<Clipboard> getAll(ContextType var1, SchematicType var2, Direction var3);

   /**
    * Ocean-specific lookup: schematics for a given size, depth tier, type,
    * and direction. Filters by depth tier in config (SHORE/MID/DEEP) to match
    * the actual water depth at the placement location.
    */
   List<Clipboard> getFitForOcean(Cartesian2D var1, String depthTier, SchematicType var2, Direction var3);

   /**
    * Candidate chunk sizes for landmark districts -- see
    * ClipboardDAO.findLandmarkSizes() for the full explanation. Returns
    * raw sizes (deduplication is the caller's responsibility) for schematics
    * whose footprint exceeds minChunkSize in at least one dimension but
    * still fits within maxFit.
    */
   List<Cartesian2D> getLandmarkSizes(int minChunkSize, ContextType context, SchematicType schematicType, Cartesian2D maxFit);

   /**
    * See ClipboardDAO.findNearbyLandmarkGroupIds() -- one batched lookup of
    * every groupId already placed as a landmark within landmarkCloneRadius
    * grid tiles of (tileX, tileZ) in worldName, for the caller to filter
    * candidates against.
    */
   Set<String> findNearbyLandmarkGroupIds(String worldName, int tileX, int tileZ, int landmarkCloneRadius);

   /**
    * See ClipboardDAO.storeLandmarkPlacement() -- logs a landmark that was
    * just successfully carved, for future findNearbyLandmarkGroupIds() calls
    * (from this tile or any neighbour).
    */
   void storeLandmarkPlacement(String worldName, String groupId, int tileX, int tileZ);
}
