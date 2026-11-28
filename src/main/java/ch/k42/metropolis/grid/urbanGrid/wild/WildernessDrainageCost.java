package ch.k42.metropolis.grid.urbanGrid.wild;

/** Terrain-aware A* movement cost for wilderness tributaries. */
public final class WildernessDrainageCost {
   private WildernessDrainageCost(){}

   /**
    * Existing relief strongly discourages climbs into ridges and massifs while
    * still allowing the channel to be carved through smaller surface changes.
    * The source lake's absolute elevation is not penalized: only movement from
    * one route cell to the next contributes uphill cost.
    */
   public static int movementCost(int fromY,int toY,int localMeanY,boolean land) {
      int rise=toY-fromY;
      int uphill=Math.max(0,rise);
      int uphillPenalty=uphill*7+Math.max(0,uphill-1)*2;
      int downhill=Math.max(0,-rise);
      int downhillReward=Math.min(6,downhill);
      int valleyPenalty=0;
      if(land) {
         int ridgeAboveValley=Math.max(0,toY-localMeanY);
         valleyPenalty=Math.min(18,ridgeAboveValley*2);
      }
      return Math.max(0,Math.min(48,uphillPenalty+valleyPenalty-downhillReward));
   }
}
