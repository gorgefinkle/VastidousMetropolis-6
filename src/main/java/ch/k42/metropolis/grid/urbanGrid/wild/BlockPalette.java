package ch.k42.metropolis.grid.urbanGrid.wild;

import org.bukkit.Chunk;
import org.bukkit.block.Block;

/**
 * How a WildBlock is written to, and recognised in, the world on this server version. The wilderness generator only talks to
 * this interface, so supporting a newer Minecraft is one new implementation (see Palettes), not a hunt through the generator.
 */
public interface BlockPalette {
   /** Puts the block at chunk-local (lx, lz) and height y. Does not trigger physics. */
   void set(Chunk chunk, int lx, int y, int lz, WildBlock block);

   /** Whether the world block already is this kind (e.g. "is the surface grass"). */
   boolean is(Block block, WildBlock kind);
}
