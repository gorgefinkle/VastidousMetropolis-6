package ch.k42.metropolis.grid.urbanGrid.vanilla;

import java.util.Collections;
import java.util.List;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;

/**
 * One chunk exactly as the server's own world generator made it (terrain, caves, ores, trees, everything it decorates a chunk
 * with), frozen so it can be read from anywhere without touching the world it came from. Made by VanillaSource.
 *
 * Blocks are read as modern block states, over the world's full height (which on 26.x starts below zero).
 */
public final class VanillaChunk {
   /** A mob spawner the generator placed (a dungeon's), whose mob lives in the tile entity and not in the block data. */
   public static final class Spawner {
      public final int lx;
      public final int y;
      public final int lz;
      public final EntityType type;

      Spawner(int lx, int y, int lz, EntityType type) {
         this.lx = lx;
         this.y = y;
         this.lz = lz;
         this.type = type;
      }
   }

   private final ChunkSnapshot snapshot;
   private final List<Spawner> spawners;

   VanillaChunk(ChunkSnapshot snapshot, List<Spawner> spawners) {
      this.snapshot = snapshot;
      this.spawners = Collections.unmodifiableList(spawners);
   }

   /** The full block state (facing, waterlogging, age, ...) at chunk-local (lx, lz) and world height y. */
   public BlockData block(int lx, int y, int lz) {
      return this.snapshot.getBlockData(lx, y, lz);
   }

   public Material type(int lx, int y, int lz) {
      return this.snapshot.getBlockType(lx, y, lz);
   }

   /**
    * The biome of the column's surface. Biomes are three-dimensional now (a cave under a forest has its own), so the column is
    * asked at its highest block, which is the biome the sky above it sees.
    */
   public Biome biome(int lx, int lz) {
      int y = this.snapshot.getHighestBlockYAt(lx, lz);
      return this.snapshot.getBiome(lx, y, lz);
   }

   public List<Spawner> spawners() {
      return this.spawners;
   }
}
