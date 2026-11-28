package ch.k42.metropolis.grid.urbanGrid.wild;

/** Verifies that local channel carving is allowed but massif crossings are costly. */
public final class WildernessDrainageCostProbe {
   public static void main(String[] args) {
      int flat=WildernessDrainageCost.movementCost(100,100,100,true);
      int gentle=WildernessDrainageCost.movementCost(100,101,101,true);
      int shoulder=WildernessDrainageCost.movementCost(100,104,104,true);
      int massif=WildernessDrainageCost.movementCost(100,145,145,true);
      int ridge=WildernessDrainageCost.movementCost(100,125,100,true);
      int downhill=WildernessDrainageCost.movementCost(145,100,110,true);
      if(flat!=0)throw new AssertionError("Flat valley floor should have no extra drainage cost");
      if(gentle>=massif/4)
         throw new AssertionError("Small local variation is being treated like a massif: gentle="+gentle+" massif="+massif);
      if(shoulder>=massif)
         throw new AssertionError("Massif barrier is not costlier than an ordinary shoulder");
      if(massif<45||ridge<30)
         throw new AssertionError("Major terrain relief is not strongly discouraged: massif="+massif+" ridge="+ridge);
      if(downhill!=0)
         throw new AssertionError("A downhill route should not pay uphill or ridge cost: "+downhill);
      StreamWaterProfile.Profile highLake=StreamWaterProfile.create(
              new double[]{0,900},new double[]{0,0},160,72,0x484947484C414B45L,(x,z)->130);
      if(highLake.waterY()[0]!=160||highLake.waterY()[highLake.pointCount()-1]!=72
              ||highLake.totalDrop()!=88.0||highLake.waterY()[1]>=160.0)
         throw new AssertionError("High lake source was not allowed to descend continuously");
      System.out.printf(java.util.Locale.ROOT,
              "WildernessDrainageCostProbe PASS gentle=%d shoulder=%d massif=%d ridge=%d highLake=%.1f->%.1f%n",
              gentle,shoulder,massif,ridge,highLake.waterY()[0],highLake.waterY()[highLake.pointCount()-1]);
   }
}
