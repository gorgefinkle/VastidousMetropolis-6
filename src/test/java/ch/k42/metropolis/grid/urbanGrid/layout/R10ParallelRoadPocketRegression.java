package ch.k42.metropolis.grid.urbanGrid.layout;

import java.lang.reflect.Method;

/** Regression for R9's dry-pocket pass widening an existing street into two parallel rows. */
public final class R10ParallelRoadPocketRegression {
   private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
   private static void test(int width, int height, boolean vertical) throws Exception {
      WorldLayout.Domain d = new WorldLayout.Domain(7,0,0,width-1,height-1,1);
      for (int z=4; z<height-4; z++) for (int x=4; x<width-4; x++) {
         // Keep dry ground unclaimed except for this road and its leftover one-cell strip.
         d.kind[x][z] = WorldLayout.BLOCK;
      }
      int end=(vertical?height:width)-5;
      for (int t=5;t<end;t++) {
         int x=vertical?10:t, z=vertical?t:10;
         d.kind[x][z]=WorldLayout.STREET;
         d.kind[x+(vertical?1:0)][z+(vertical?0:1)]=WorldLayout.ZONE;
      }
      WorldLayout layout = new WorldLayout(null,1234L,7,14,99);
      Method absorb=WorldLayout.class.getDeclaredMethod("absorbPockets",WorldLayout.Domain.class);
      absorb.setAccessible(true); absorb.invoke(layout,d);
      for(int t=6;t<end-1;t++) {
         int x=vertical?11:t, z=vertical?t:11;
         check(d.kind[x][z]!=WorldLayout.STREET,"Pocket became a parallel road at " +x+","+z);
      }
   }
   public static void main(String[] args) throws Exception {
      test(27,27,true);test(27,27,false);
      System.out.println("PASS: dry pocket beside a planned street cannot become a second road (both axes)");
   }
}
