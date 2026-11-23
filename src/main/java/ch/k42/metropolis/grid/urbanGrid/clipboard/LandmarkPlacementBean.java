package ch.k42.metropolis.grid.urbanGrid.clipboard;


/**
 * One row per successfully carved landmark district -- see
 * UrbanGrid.carveLandmark(). Purely a placement log for the
 * landmarkCloneRadius duplicate check (ClipboardDAO.findNearbyLandmarkGroupIds()):
 * unlike the ordinary cloneRadius hard block, which walks live in-memory
 * parcel data via UrbanGrid.getNeighbours(), this persists past tile-cache
 * eviction (PluginConfig.getMaxCachedGridTiles()) and server restarts, and
 * makes the check independent of which of two neighbouring tiles happens to
 * generate first.
 *
 * tileX/tileZ store the grid TILE's own origin (Grid.root -- always a
 * multiple of Grid.GRID_SIZE), not the landmark's exact chunk position:
 * landmarkCloneRadius is a tile-granularity radius ("neighbouring grids"),
 * so tile-level precision is all the duplicate check ever needs.
 */
public class LandmarkPlacementBean {
   Integer id;
   String worldName;
   String groupId;
   int tileX;
   int tileZ;

   public Integer getId() {
      return this.id;
   }

   public void setId(Integer id) {
      this.id = id;
   }

   public String getWorldName() {
      return this.worldName;
   }

   public void setWorldName(String worldName) {
      this.worldName = worldName;
   }

   public String getGroupId() {
      return this.groupId;
   }

   public void setGroupId(String groupId) {
      this.groupId = groupId;
   }

   public int getTileX() {
      return this.tileX;
   }

   public void setTileX(int tileX) {
      this.tileX = tileX;
   }

   public int getTileZ() {
      return this.tileZ;
   }

   public void setTileZ(int tileZ) {
      this.tileZ = tileZ;
   }
}
