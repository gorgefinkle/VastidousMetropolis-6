package ch.k42.metropolis.grid.urbanGrid.statistics;

import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.minions.Incr;
import java.util.HashMap;
import java.util.Map;

public class AthmosStat implements GridStatistics {
   private Map<Clipboard, Incr> statsByName = new HashMap<>();
   private Map<AthmosStat.Size, Incr> statsBySize = new HashMap<>();
   private Incr count = new Incr();

   private void logByName(Clipboard p) {
      if (this.statsByName.containsKey(p)) {
         this.statsByName.get(p).incr();
      } else {
         Incr i = new Incr();
         i.incr();
         this.statsByName.put(p, i);
      }
   }

   private void logBySize(Clipboard p) {
      AthmosStat.Size s = new AthmosStat.Size(p.getSize().X, p.getSize().Z);
      if (this.statsBySize.containsKey(s)) {
         this.statsBySize.get(s).incr();
      } else {
         Incr i = new Incr();
         i.incr();
         this.statsBySize.put(s, i);
      }
   }

   @Override
   public void logSchematic(Clipboard p) {
      if (p != null) {
         this.logByName(p);
         this.logBySize(p);
         this.count.incr();
      }
   }

   @Override
   public int getClipboardCount(Clipboard p) {
      return this.statsByName.containsKey(p) ? this.statsByName.get(p).val() : 0;
   }

   @Override
   public double getRelativeClipboardCount(Clipboard p) {
      int gcount = this.count.val();
      if (gcount == 0) {
         return 0.0;
      }

      double pcount = this.getClipboardCount(p);
      return pcount / gcount;
   }

   @Override
   public String printStatistics() {
      StringBuilder sb = new StringBuilder();
      sb.append("- Statistics -----------\n");
      sb.append(" global count: ").append(this.count).append('\n');
      sb.append("---# By Name: \n");

      for (Clipboard c : this.statsByName.keySet()) {
         sb.append(" ").append(String.format("%-10s", c.toString())).append(" : ").append(this.statsByName.get(c).val()).append('\n');
      }

      sb.append("---# By Size: \n");

      for (AthmosStat.Size s : this.statsBySize.keySet()) {
         sb.append(" ").append(String.format("%-10s", s.toString())).append(" : ").append(this.statsBySize.get(s).val()).append('\n');
      }

      return sb.toString();
   }

   private static class Size {
      private int x;
      private int z;

      private Size(int x, int z) {
         if (x > z) {
            this.x = x;
            this.z = z;
         } else {
            this.x = z;
            this.z = x;
         }
      }

      @Override
      public String toString() {
         return " s: " + this.x + "/" + this.z;
      }

      @Override
      public boolean equals(Object o) {
         if (this == o) {
            return true;
         } else if (o != null && this.getClass() == o.getClass()) {
            AthmosStat.Size size = (AthmosStat.Size)o;
            return this.x == size.x && this.z == size.z;
         } else {
            return false;
         }
      }

      @Override
      public int hashCode() {
         return this.x << 16 | this.z;
      }
   }
}
