package ch.k42.metropolis.grid.urbanGrid.wild;

import java.util.Random;

/**
 * The coloured layers of a mesa: hardened clay with bands of orange, yellow, brown, red, white and silver, in a fixed
 * pattern per world, repeating every PERIOD blocks of height so tall mesas keep striping.
 */
public final class MesaBands {
   public static final int PERIOD = 64;
   private final WildBlock[] bands = new WildBlock[PERIOD];

   public MesaBands(long worldSeed) {
      Random r = new Random(worldSeed ^ 0x4D455341L);
      for (int i = 0; i < PERIOD; i++) {
         this.bands[i] = WildBlock.HARDENED_CLAY;
      }
      WildBlock[] colours = {WildBlock.CLAY_ORANGE, WildBlock.CLAY_YELLOW, WildBlock.CLAY_BROWN, WildBlock.CLAY_RED, WildBlock.CLAY_WHITE, WildBlock.CLAY_SILVER};
      int y = r.nextInt(4);
      while (y < PERIOD) {
         WildBlock c = colours[r.nextInt(colours.length)];
         int thick = 1 + r.nextInt(3);
         for (int k = 0; k < thick && y + k < PERIOD; k++) {
            this.bands[y + k] = c;
         }
         y += thick + 1 + r.nextInt(4);
      }
      // a thin white seam, as in vanilla mesas
      this.bands[8 + r.nextInt(10)] = WildBlock.CLAY_WHITE;
   }

   public WildBlock at(int y) {
      return this.bands[Math.floorMod(y, PERIOD)];
   }
}
