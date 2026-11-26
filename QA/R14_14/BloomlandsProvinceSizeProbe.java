package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * Ensures supported wilderness resolution settings cannot shrink a Bloomlands
 * or Karst province below the physical footprint needed by giant landmarks.
 */
public final class BloomlandsProvinceSizeProbe {
   public static void main(String[] args) {
      double[] scales={40.0,80.0,200.0,520.0,1000.0};
      int[] zooms={3,4,5,6};
      for(double scale:scales)for(int zoom:zooms){
         double common=WildLayers.commonFinalCellBlocks(scale,zoom);
         int divisor=WildLayers.rareCellDivisor(common);
         double rare=WildLayers.configuredRareCellBlocks(scale,zoom);
         double province=rare*2.0;
         System.out.printf(java.util.Locale.ROOT,
                 "scaleChunks=%.0f zoomLevels=%d commonCell=%.2f rareDivisor=%d rareCell=%.2f province=%.2f%n",
                 scale,zoom,common,divisor,rare,province);
         if(rare<WildLayers.MIN_RARE_CELL_BLOCKS || province<1024.0)
            throw new AssertionError("Rare province below physical minimum at scale="+scale+" zoom="+zoom);
      }
      double min=WildLayers.configuredRareCellBlocks(40.0,6);
      double defaults=WildLayers.configuredRareCellBlocks(520.0,4);
      if(min!=520.0 || defaults!=676.0)
         throw new AssertionError("Unexpected province calibration: min="+min+" defaults="+defaults);
      System.out.println("BloomlandsProvinceSizeProbe: PASS (all supported scales/zooms preserve >=1,024-block provinces)");
   }
}
