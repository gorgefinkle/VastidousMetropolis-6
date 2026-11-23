package ch.k42.metropolis.grid.urbanGrid.clipboard;

import com.sk89q.worldedit.world.block.BlockType;

/**
 * Block-type tests shared by FileClipboard and ClipboardWE, in WorldEdit's own
 * terms (BlockType), replacing the old numeric-ID comparisons such as
 * {@code block.getId() == Material.CHEST.getId()}.
 */
final class ClipboardBlocks {
   private ClipboardBlocks() {
   }

   /**
    * True for air and for the two ground-cover plants the old
    * estimateStreetLevel() skipped over (LONG_GRASS and YELLOW_FLOWER, now
    * short grass -- "grass" before 1.20.3 -- and dandelion). Compared by
    * namespaced id rather than via BlockTypes.SHORT_GRASS etc. so this
    * compiles and behaves the same on any WorldEdit build regardless of
    * which of those constants it happens to define.
    */
   static boolean isAirOrGroundCover(BlockType type) {
      if (type.getMaterial().isAir()) {
         return true;
      }
      String id = type.id();
      return id.equals("minecraft:short_grass") || id.equals("minecraft:grass") || id.equals("minecraft:dandelion");
   }
}
