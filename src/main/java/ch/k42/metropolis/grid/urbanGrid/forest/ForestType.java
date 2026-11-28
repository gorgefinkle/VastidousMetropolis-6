package ch.k42.metropolis.grid.urbanGrid.forest;

import org.bukkit.block.Biome;

/**
 * The forest flavours a morphomos region shifts between. Each sits at a point
 * on a temperature/moisture plane; ForestTerrain blends them by distance on
 * that plane, so neighbouring flavours fade into each other instead of
 * switching at a hard line.
 */
public enum ForestType {
   //            temp   moist  relief  tree     biome (grass/foliage tint)
   //                           mul     density
   OAK_WOODLAND(  0.10,  0.25, 1.00,   0.55,   "FOREST"),
   BIRCH_GROVE(   0.45, -0.55, 0.80,   0.50,   "BIRCH_FOREST"),
   SPRUCE_TAIGA( -0.65,  0.00, 1.30,   0.60,   "TAIGA"),
   DARK_FOREST(   0.00,  0.85, 0.70,   0.85,   "DARK_FOREST"),
   MEADOW_WOODS(  0.75,  0.00, 0.60,   0.12,   "PLAINS"),
   // The rainforest sits far off the temperature/moisture plane on purpose: the climate noise never reaches it. It is chosen by the
   // wilderness biome of the same name (see ForestTerrain.setRainfall), never by chance.
   RAINFOREST(    3.00,  3.00, 1.35,   0.90,   "JUNGLE");

   public final double temp;
   public final double moist;
   /** Multiplies the configured relief amplitude. */
   public final double reliefMul;
   /** Chance a candidate tree site becomes a tree (before clearings and the global multiplier). */
   public final double treeDensity;
   private final String biomeName;

   ForestType(double temp,double moist,double reliefMul,double treeDensity,String biomeName) {
      this.temp=temp;
      this.moist=moist;
      this.reliefMul=reliefMul;
      this.treeDensity=treeDensity;
      this.biomeName=biomeName;
   }

   /** Bukkit biome lookup is deferred until the live server's registry is available. */
   public Biome biome() { return BiomeRefs.ALL[ordinal()]; }
   public String biomeName() { return biomeName; }

   private static final class BiomeRefs {
      private static final Biome[] ALL={
         Biome.FOREST,Biome.BIRCH_FOREST,Biome.TAIGA,
         Biome.DARK_FOREST,Biome.PLAINS,Biome.JUNGLE
      };
   }

   public static final ForestType[] ALL = values();
}
