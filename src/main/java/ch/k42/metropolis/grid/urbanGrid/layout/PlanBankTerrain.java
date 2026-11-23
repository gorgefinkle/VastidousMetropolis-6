package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;

/**
 * BankSlope.Terrain over the real plan, for one river: water is what WaterField calls RIVER (water and bank footprint),
 * shore is ZONE ground that WaterField hands to the river's own parcel (the same test UrbanGrid.placeFromPlan uses), and
 * the column's water chunk is the river's spine.
 */
public final class PlanBankTerrain implements BankSlope.Terrain {
   private final WorldLayout layout;
   private final WaterField water;
   private final RiverProvider river;

   public PlanBankTerrain(WorldLayout layout, WaterField water, RiverProvider river) {
      this.layout = layout;
      this.water = water;
      this.river = river;
   }

   @Override
   public boolean water(int x, int z) {
      return this.water.kindAt(x, z) == WaterField.Kind.RIVER;
   }

   @Override
   public boolean shore(int x, int z) {
      return this.layout.kindAt(x, z) == WorldLayout.ZONE && this.water.zoneKind(x, z) == WaterField.Kind.RIVER;
   }

   @Override
   public boolean other(int x, int z) {
      return this.water.kindAt(x, z) == WaterField.Kind.OCEAN
              || (this.layout.kindAt(x, z) == WorldLayout.ZONE && this.water.zoneKind(x, z) == WaterField.Kind.OCEAN);
   }

   @Override
   public int waterAt(boolean alongX, int u) {
      double spineBlock = alongX ? this.river.spineZBlock(u * 16.0 + 8.0) : this.river.spineXBlock(u * 16.0 + 8.0);
      return Math.floorDiv((int) Math.round(spineBlock), 16);
   }
}
