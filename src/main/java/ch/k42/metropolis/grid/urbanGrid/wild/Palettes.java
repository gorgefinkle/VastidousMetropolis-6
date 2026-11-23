package ch.k42.metropolis.grid.urbanGrid.wild;

/**
 * The one place that decides which BlockPalette this server uses. That is Legacy112Palette on every version: its 1.12 ids are translated by LegacyBlocks.
 *
 * To support a newer Minecraft: write a class that implements BlockPalette with that version's block names (see PORTING.md for
 * the mapping of every WildBlock), and return it here when the new API is present. Nothing else in the wilderness changes.
 */
public final class Palettes {
   private static volatile BlockPalette current;

   private Palettes() {
   }

   public static BlockPalette current() {
      BlockPalette p = current;
      if (p == null) {
         synchronized (Palettes.class) {
            p = current;
            if (p == null) {
               p = new Legacy112Palette();
               current = p;
            }
         }
      }
      return p;
   }
}
