package ch.k42.metropolis.grid.urbanGrid.statistics;

import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;

public interface GridStatistics {
   void logSchematic(Clipboard var1);

   int getClipboardCount(Clipboard var1);

   double getRelativeClipboardCount(Clipboard var1);

   String printStatistics();
}
