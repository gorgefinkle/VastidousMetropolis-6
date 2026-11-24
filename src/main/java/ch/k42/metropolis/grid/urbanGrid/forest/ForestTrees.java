package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.minions.LegacyBlocks;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.Random;

/**
 * Tree shapes. Each one is built from an origin, a ground height per trunk
 * column and a Random seeded from the origin alone, so the same tree comes out
 * identical no matter which chunk is drawing its share. Every shape always
 * makes the same Random calls whether or not a block lands inside the chunk
 * being written -- only the final write is filtered -- so the sequence never
 * depends on chunk bounds.
 *
 * Rules that keep overlapping trees order-independent: leaves only go into
 * air, logs may replace air, leaves and ground plants.
 */
public final class ForestTrees {
   /** Largest horizontal reach of any shape from its origin, used to widen the candidate window. */
   // A 9-block emergent limb with a 6-block crown can extend 15 blocks.
   // Search farther so the entire tree (including logs at chunk seams) is drawn.
   public static final int MAX_REACH = 18;

   // Legacy ids: LOG 17 (0 oak, 1 spruce, 2 birch), LEAVES 18 (same), LOG_2 162 / LEAVES_2 161 (1 dark oak).
   private static final int LOG = 17;
   private static final int LEAVES = 18;
   private static final int LOG2 = 162;
   private static final int LEAVES2 = 161;
   /** Leaf data bit 4 = persistent, so they never decay away. */
   private static final int PERSIST = 4;

   public interface GroundLookup {
      /** Surface block height (the grass block's y) at a world column. */
      int groundAt(int x, int z);
      /** Same deterministic world-position test used for the forest/shore site. */
      default boolean allowsAt(int x, int z) { return true; }
   }

   private final Chunk chunk;
   private final int minX;
   private final int minZ;

   public ForestTrees(Chunk chunk) {
      this.chunk = chunk;
      this.minX = chunk.getX() << 4;
      this.minZ = chunk.getZ() << 4;
   }

   /** Distance an origin must keep from the region edge so the whole tree stays inside it. */
   public static int margin(ForestType type, boolean bush) {
      if (bush) {
         return 2;
      }
      switch (type) {
         case DARK_FOREST:
            return 6;
         case SPRUCE_TAIGA:
            return 4;
         case RAINFOREST:
            return 4;
         default:
            return 3;
      }
   }

   /** As above, for the tree a site will actually grow: a rainforest site's class (and so its room) comes from its hash. */
   public static int margin(ForestType type, boolean bush, long hash) {
      if (type == ForestType.RAINFOREST && !bush) {
         switch (rainClass(hash)) {
            case RAIN_GIANT:
               return 18;
            case RAIN_CANOPY:
               return 10;
            case RAIN_FALLEN:
               return 15;
            default:
               return 4;
         }
      }
      return margin(type, bush);
   }

   public void place(ForestType type, boolean bush, int ox, int oz, long hash, GroundLookup ground) {
      Random r = new Random(hash);
      int oy = ground.groundAt(ox, oz);
      if (bush) {
         bush(ox, oy, oz, r, type == ForestType.RAINFOREST ? JUNGLE : 0);
         return;
      }
      switch (type) {
         case RAINFOREST:
            rainforest(ox, oy, oz, r, ground, hash);
            break;
         case BIRCH_GROVE:
            broadleaf(ox, oy, oz, r, 2, 5 + r.nextInt(3));
            break;
         case SPRUCE_TAIGA:
            spruce(ox, oy, oz, r);
            break;
         case DARK_FOREST:
            darkOak(ox, oz, r, ground);
            break;
         default:
            broadleaf(ox, oy, oz, r, 0, 4 + r.nextInt(3) + (r.nextInt(8) == 0 ? 3 : 0));
            break;
      }
   }

   private void broadleaf(int ox, int oy, int oz, Random r, int data, int h) {
      int top = oy + h;
      for (int ly = top - 2; ly <= top + 1; ly++) {
         int rad = ly >= top ? 1 : 2;
         for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
               boolean corner = Math.abs(dx) == rad && Math.abs(dz) == rad;
               boolean trim = corner && (ly >= top || r.nextInt(2) == 0);
               if (!trim) {
                  leaf(ox + dx, ly, oz + dz, LEAVES, data);
               }
            }
         }
      }
      for (int y = oy + 1; y <= top; y++) {
         log(ox, y, oz, LOG, data);
      }
   }

   private void spruce(int ox, int oy, int oz, Random r) {
      int h = 7 + r.nextInt(5);
      int top = oy + h;
      int crown = h - 2 - r.nextInt(2);
      int cap = 2 + r.nextInt(2);
      int radius = r.nextInt(2);
      int maxRadius = 1;
      int minRadius = 0;
      for (int layer = 0; layer <= crown; layer++) {
         int y = top + 1 - layer;
         for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
               boolean corner = radius > 0 && Math.abs(dx) == radius && Math.abs(dz) == radius;
               if (!corner) {
                  leaf(ox + dx, y, oz + dz, LEAVES, 1);
               }
            }
         }
         if (radius >= maxRadius) {
            radius = minRadius;
            minRadius = 1;
            if (++maxRadius > cap) {
               maxRadius = cap;
            }
         } else {
            radius++;
         }
      }
      for (int y = oy + 1; y <= top; y++) {
         log(ox, y, oz, LOG, 1);
      }
   }

   private void darkOak(int ox, int oz, Random r, GroundLookup ground) {
      int h = 6 + r.nextInt(4);
      int lowest = Math.min(Math.min(ground.groundAt(ox, oz), ground.groundAt(ox + 1, oz)),
              Math.min(ground.groundAt(ox, oz + 1), ground.groundAt(ox + 1, oz + 1)));
      int top = lowest + h;
      double cx = ox + 0.5;
      double cz = oz + 0.5;
      double cy = top - 0.5;
      double rx = 3.4 + r.nextDouble() * 0.8;
      double rz = 3.4 + r.nextDouble() * 0.8;
      double ry = 2.4 + r.nextDouble() * 0.6;
      for (int dx = -5; dx <= 5; dx++) {
         for (int dy = -3; dy <= 3; dy++) {
            for (int dz = -5; dz <= 5; dz++) {
               double x = ox + dx;
               double y = top + dy;
               double z = oz + dz;
               double fx = (x - cx) / rx;
               double fy = (y - cy) / ry;
               double fz = (z - cz) / rz;
               double wobble = r.nextDouble() * 0.35;
               if (fx * fx + fy * fy + fz * fz <= 1.0 - wobble * 0.5) {
                  leaf(ox + dx, top + dy, oz + dz, LEAVES2, 1);
               }
            }
         }
      }
      for (int tx = 0; tx <= 1; tx++) {
         for (int tz = 0; tz <= 1; tz++) {
            int g = ground.groundAt(ox + tx, oz + tz);
            for (int y = g + 1; y <= top; y++) {
               log(ox + tx, y, oz + tz, LOG2, 1);
            }
         }
      }
   }

   private void bush(int ox, int oy, int oz, Random r, int data) {
      log(ox, oy + 1, oz, LOG, data);
      for (int dx = -1; dx <= 1; dx++) {
         for (int dy = 0; dy <= 2; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
               boolean far = Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 1) > 2;
               boolean skip = r.nextInt(4) == 0;
               if (!far && !skip && !(dx == 0 && dz == 0 && dy == 0)) {
                  leaf(ox + dx, oy + 1 + dy, oz + dz, LEAVES, data);
               }
            }
         }
      }
   }

   // ---------------------------------------------------------------------------------------------------------------------------
   // Rainforest. Four kinds of tree share one forest type, chosen from the site's hash: a few emergent giants that stand clear of
   // everything else, a continuous canopy under them, a thick understory, and the odd fallen trunk. All of it is jungle wood.
   // ---------------------------------------------------------------------------------------------------------------------------

   /** Jungle variant of logs and leaves. */
   private static final int JUNGLE = 3;
   /** A jungle log with bark on all six sides (3 + 12): a trunk that reads as thick bark, not rings. */
   private static final int JUNGLE_BARK = 15;
   private static final int VINE = 106;

   static final int RAIN_UNDERSTORY = 0;
   static final int RAIN_CANOPY = 1;
   static final int RAIN_GIANT = 2;
   static final int RAIN_FALLEN = 3;

   /** The four horizontal directions, and the vine side bit for a vine standing at (leaf + direction) so that it clings to the leaf. */
   private static final int[] DX = {1, -1, 0, 0};
   private static final int[] DZ = {0, 0, 1, -1};
   private static final int[] CLING = {2, 8, 4, 1};

   /** Which kind of rainforest tree a site grows. A pure function of the hash, so margin() can ask before the tree is built. */
   static int rainClass(long hash) {
      double u = (hash >>> 11) * (1.0 / 9007199254740992.0);
      if (u < 0.007) {
         return RAIN_GIANT;
      }
      if (u < 0.115) {
         return RAIN_CANOPY;
      }
      if (u < 0.135) {
         return RAIN_FALLEN;
      }
      return RAIN_UNDERSTORY;
   }

   private void rainforest(int ox, int oy, int oz, Random r, GroundLookup ground, long hash) {
      switch (rainClass(hash)) {
         case RAIN_GIANT:
            giant(ox, oy, oz, r, ground);
            break;
         case RAIN_CANOPY:
            canopy(ox, oy, oz, r, ground);
            break;
         case RAIN_FALLEN:
            fallen(ox, oy, oz, r, ground);
            break;
         default:
            understory(ox, oy, oz, r);
            break;
      }
   }

   /**
    * An emergent giant, 44 to 63 blocks tall: a 3x3 trunk that narrows to 2x2, buttress roots fanning out from its foot, limbs that carry
    * broad flat crowns well clear of the canopy below, long curtains of vines, and aerial roots dropping from the limbs to the ground.
    */
   private void giant(int ox, int oy, int oz, Random r, GroundLookup ground) {
      int h = 44 + r.nextInt(20);
      int top = oy + h;
      int arms = 4 + r.nextInt(3);
      double armPhase = r.nextDouble() * Math.PI * 2.0;
      int armLen = 5 + r.nextInt(4);
      int armHeight = 7 + r.nextInt(5);
      int limbs = 5 + r.nextInt(3);
      double limbPhase = r.nextDouble() * Math.PI * 2.0;

      // the trunk stands on whatever ground there is: fill down under it where the slope falls away
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            int g = Math.max(ground.groundAt(ox + dx, oz + dz), oy - 10);
            for (int y = g + 1; y <= oy; y++) {
               log(ox + dx, y, oz + dz, LOG, JUNGLE_BARK);
            }
         }
      }
      int wideTo = oy + (int) (h * 0.55);
      for (int y = oy + 1; y <= top; y++) {
         int lo = y <= wideTo ? -1 : 0;
         int hi = 1;
         for (int dx = lo; dx <= hi; dx++) {
            for (int dz = lo; dz <= hi; dz++) {
               log(ox + dx, y, oz + dz, LOG, JUNGLE_BARK);
            }
         }
      }

      // buttress roots: thin fins that start tall at the trunk and taper out along the ground
      for (int a = 0; a < arms; a++) {
         double ang = armPhase + a * (Math.PI * 2.0 / arms) + (r.nextDouble() - 0.5) * 0.5;
         int len = armLen + r.nextInt(3) - 1;
         int fin = armHeight + r.nextInt(3) - 1;
         double cx = Math.cos(ang);
         double cz = Math.sin(ang);
         // Draw the first root cell too, so a steep angled buttress cannot
         // start one whole block away from the trunk.
         for (int d = 1; d <= len + 1; d++) {
            int bx = ox + (int) Math.round(cx * d);
            int bz = oz + (int) Math.round(cz * d);
            int fh = (int) Math.round(fin * Math.pow(1.0 - (double) (d - 1) / (len + 1), 1.8));
            // A high intervening bank can interrupt a root. In that case
            // end it here; do not resume beyond the bank as a detached fin.
            if (!ground.allowsAt(bx, bz)) break;
            int g = ground.groundAt(bx, bz);
            if (g >= oy + fh) break;
            for (int y = g + 1; y <= oy + fh; y++) {
               log(bx, y, bz, LOG, JUNGLE_BARK);
            }
         }
      }

      // trunk vines: a few long strands down the sides of the lower trunk
      int strands = 6 + r.nextInt(5);
      for (int i = 0; i < strands; i++) {
         int face = r.nextInt(4);
         int off = r.nextInt(3) - 1;
         int y0 = oy + 8 + r.nextInt(Math.max(1, wideTo - oy - 8));
         int len = 6 + r.nextInt(12);
         hangBeside(ox + DX[face] * 2 + (DZ[face] != 0 ? off : 0), y0, oz + DZ[face] * 2 + (DX[face] != 0 ? off : 0), face, len);
      }

      // limbs, each ending in a flat crown; some drop an aerial root
      for (int l = 0; l < limbs; l++) {
         double ang = limbPhase + l * (Math.PI * 2.0 / limbs) + (r.nextDouble() - 0.5) * 0.6;
         int reach = 6 + r.nextInt(4);
         int rise = 1 + r.nextInt(4);
         int start = top - 3 - r.nextInt(4);
         double rx = 4.0 + r.nextDouble() * 1.2;
         double ry = 2.3 + r.nextDouble() * 0.6;
         boolean root = r.nextInt(2) == 0;
         int ex = ox + (int) Math.round(Math.cos(ang) * reach);
         int ez = oz + (int) Math.round(Math.sin(ang) * reach);
         int ey = start + rise;
         line(ox, start, oz, ex, ey, ez, LOG, JUNGLE_BARK);
         blob(ex, ey + 1, ez, rx, ry, rx, r, 16);
         if (root) {
            // Anchor directly to a voxel on the EXISTING discretized limb.
            // Estimating the position from cos(angle)*reach sometimes chose a
            // column beside the limb, leaving a visibly floating log column.
            int[] anchor = limbAnchor(ox, start, oz, ex, ey, ez);
            int mx = anchor[0], by = anchor[1], mz = anchor[2];
            if (ground.allowsAt(mx, mz)) {
               int g = ground.groundAt(mx, mz);
               // Starts immediately under the limb block, and reaches soil.
               if (g < by) for (int y = by - 1; y > g; y--) {
                  log(mx, y, mz, LOG, JUNGLE);
               }
            }
         }
      }
      blob(ox, top + 1, oz, 5.5 + r.nextDouble() * 1.0, 3.0, 5.5 + r.nextDouble() * 1.0, r, 14);
   }

   /** A canopy tree, 24 to 34 blocks: a 2x2 trunk with small buttresses and a broad dome crown on a few limbs. */
   private void canopy(int ox, int oy, int oz, Random r, GroundLookup ground) {
      int h = 24 + r.nextInt(11);
      int top = oy + h;
      double armPhase = r.nextDouble() * Math.PI * 2.0;
      int limbs = 3 + r.nextInt(2);
      double limbPhase = r.nextDouble() * Math.PI * 2.0;

      for (int dx = 0; dx <= 1; dx++) {
         for (int dz = 0; dz <= 1; dz++) {
            int g = Math.max(ground.groundAt(ox + dx, oz + dz), oy - 8);
            for (int y = g + 1; y <= top; y++) {
               log(ox + dx, y, oz + dz, LOG, JUNGLE);
            }
         }
      }
      for (int a = 0; a < 4; a++) {
         double ang = armPhase + a * (Math.PI / 2.0) + (r.nextDouble() - 0.5) * 0.5;
         double cx = Math.cos(ang);
         double cz = Math.sin(ang);
         for (int d = 1; d <= 3; d++) {
            int bx = ox + (int) Math.round(cx * d) + (cx > 0 ? 1 : 0);
            int bz = oz + (int) Math.round(cz * d) + (cz > 0 ? 1 : 0);
            if (!ground.allowsAt(bx, bz)) break;
            int g = ground.groundAt(bx, bz);
            int topRoot = oy + 4 - d;
            // Don't restart a root past an unsuitable slope as loose logs.
            if (g >= topRoot) break;
            for (int y = g + 1; y <= topRoot; y++) {
               log(bx, y, bz, LOG, JUNGLE);
            }
         }
      }
      for (int l = 0; l < limbs; l++) {
         double ang = limbPhase + l * (Math.PI * 2.0 / limbs) + (r.nextDouble() - 0.5) * 0.6;
         int reach = 4 + r.nextInt(3);
         int rise = r.nextInt(3);
         int start = top - 2 - r.nextInt(3);
         int ex = ox + (int) Math.round(Math.cos(ang) * reach);
         int ez = oz + (int) Math.round(Math.sin(ang) * reach);
         line(ox, start, oz, ex, start + rise, ez, LOG, JUNGLE);
         blob(ex, start + rise + 1, ez, 3.0 + r.nextDouble() * 0.8, 2.0, 3.0 + r.nextDouble() * 0.8, r, 18);
      }
      blob(ox, top + 1, oz, 4.4 + r.nextDouble() * 0.8, 2.5, 4.4 + r.nextDouble() * 0.8, r, 16);
   }

   /** A slender understory tree, 9 to 15 blocks, with a small umbrella crown and the odd vine. */
   private void understory(int ox, int oy, int oz, Random r) {
      int h = 9 + r.nextInt(7);
      int top = oy + h;
      for (int ly = top - 2; ly <= top + 1; ly++) {
         int rad = ly == top + 1 ? 1 : (ly == top ? 2 : (ly == top - 1 ? 3 : 2));
         for (int dx = -rad; dx <= rad; dx++) {
            for (int dz = -rad; dz <= rad; dz++) {
               boolean corner = Math.abs(dx) == rad && Math.abs(dz) == rad;
               boolean trim = corner && (rad >= 3 || r.nextInt(2) == 0);
               int vine = r.nextInt(10);
               int dir = r.nextInt(4);
               int len = 2 + r.nextInt(6);
               if (!trim) {
                  leaf(ox + dx, ly, oz + dz, LEAVES, JUNGLE);
                  if (vine == 0 && ly <= top - 1) {
                     hangOn(ox + dx, ly, oz + dz, dir, len);
                  }
               }
            }
         }
      }
      for (int y = oy + 1; y <= top; y++) {
         log(ox, y, oz, LOG, JUNGLE);
      }
   }

   /** Accept only ground-hugging fallen trunks, with no unsupported bridges or water overlap. */
   static boolean safeFallenPath(GroundLookup ground, int ox, int oz, int len, boolean alongX) {
      int previous = ground.groundAt(ox, oz);
      for (int i = 0; i < len; i++) {
         int x = ox + (alongX ? i : 0);
         int z = oz + (alongX ? 0 : i);
         if (!ground.allowsAt(x, z)) return false;
         int h = ground.groundAt(x, z);
         // Adjacent logs can touch at a corner, as intended; an elevation
         // jump larger than one would produce a suspended bridge or a gap.
         if (i != 0 && Math.abs(h - previous) > 1) return false;
         previous = h;
      }
      return true;
   }

   /** A fallen trunk lying along the terrain, 6 to 14 blocks. */
   private void fallen(int ox, int oy, int oz, Random r, GroundLookup ground) {
      int len = 6 + r.nextInt(9);
      boolean alongX = r.nextBoolean();
      if (!safeFallenPath(ground, ox, oz, len, alongX)) {
         // Cliffs, rivers and steep scree get a rooted understory tree
         // instead of a discontinuous collection of logs.
         understory(ox, oy, oz, r);
         return;
      }
      int data = JUNGLE | (alongX ? 4 : 8);
      for (int i = 0; i < len; i++) {
         int x = ox + (alongX ? i : 0);
         int z = oz + (alongX ? 0 : i);
         log(x, ground.groundAt(x, z) + 1, z, LOG, data);
      }
   }

   /** A rough ellipsoid of leaves with an uneven edge; leaves on its lower half may drop vines (1 in vineOdds). */
   private void blob(int cx, int cy, int cz, double rx, double ry, double rz, Random r, int vineOdds) {
      int ix = (int) Math.ceil(rx);
      int iy = (int) Math.ceil(ry);
      int iz = (int) Math.ceil(rz);
      for (int dy = -iy; dy <= iy; dy++) {
         for (int dx = -ix; dx <= ix; dx++) {
            for (int dz = -iz; dz <= iz; dz++) {
               double fx = dx / rx;
               double fy = dy / ry;
               double fz = dz / rz;
               double wobble = r.nextDouble() * 0.35;
               int vine = r.nextInt(vineOdds);
               int dir = r.nextInt(4);
               int len = 3 + r.nextInt(9);
               if (fx * fx + fy * fy + fz * fz <= 1.0 - wobble * 0.5) {
                  leaf(cx + dx, cy + dy, cz + dz, LEAVES, JUNGLE);
                  if (vine == 0 && dy <= 0) {
                     hangOn(cx + dx, cy + dy, cz + dz, dir, len);
                  }
               }
            }
         }
      }
   }

   /** Select a voxel actually drawn by line(), rather than a separately
    * rounded estimate that can miss a diagonal branch by a whole block. */
   static int[] limbAnchor(int x0, int y0, int z0, int x1, int y1, int z1) {
      int steps = Math.max(Math.abs(x1 - x0), Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
      int index = (int) Math.round(steps * 0.70);
      double t = steps == 0 ? 0.0 : (double) index / steps;
      return new int[]{(int)Math.round(x0 + (x1-x0)*t),
              (int)Math.round(y0 + (y1-y0)*t), (int)Math.round(z0 + (z1-z0)*t)};
   }

   /** A straight run of blocks between two points. */
   private void line(int x0, int y0, int z0, int x1, int y1, int z1, int id, int data) {
      int steps = Math.max(Math.abs(x1 - x0), Math.max(Math.abs(y1 - y0), Math.abs(z1 - z0)));
      for (int i = 0; i <= steps; i++) {
         double t = steps == 0 ? 0.0 : (double) i / steps;
         log((int) Math.round(x0 + (x1 - x0) * t), (int) Math.round(y0 + (y1 - y0) * t), (int) Math.round(z0 + (z1 - z0) * t), id, data);
      }
   }

   /** A vine strand on the side (direction dir) of the block at (x, y, z), hanging down len blocks. */
   private void hangOn(int x, int y, int z, int dir, int len) {
      hangBeside(x + DX[dir], y, z + DZ[dir], dir, len);
   }

   /**
    * A vine strand in the column (vx, vz), clinging to the block one step back against dir, hanging down from y. Every block of the
    * strand carries the same side bit, which is what keeps the lower ones up. It stops at the first block that is not air; that
    * is a property of the column, which lies in one chunk, so every chunk agrees.
    */
   private void hangBeside(int vx, int y, int vz, int dir, int len) {
      for (int i = 0; i < len; i++) {
         if (!vine(vx, y - i, vz, CLING[dir])) {
            return;
         }
      }
   }

   private boolean vine(int x, int y, int z, int meta) {
      if (!inChunk(x, z) || y <= this.chunk.getWorld().getMinHeight() || y >= this.chunk.getWorld().getMaxHeight() - 2) {
         return true;
      }
      Block b = this.chunk.getBlock(x - this.minX, y, z - this.minZ);
      if (b.getType().isAir()) {
         LegacyBlocks.set(b, VINE, meta);
         return true;
      }
      return false;
   }

   private boolean inChunk(int x, int z) {
      return x >= this.minX && x < this.minX + 16 && z >= this.minZ && z < this.minZ + 16;
   }

   private void leaf(int x, int y, int z, int id, int data) {
      if (!inChunk(x, z) || y <= this.chunk.getWorld().getMinHeight() || y >= this.chunk.getWorld().getMaxHeight() - 2) {
         return;
      }
      Block b = this.chunk.getBlock(x - this.minX, y, z - this.minZ);
      if (b.getType().isAir()) {
         LegacyBlocks.set(b, id, (data | PERSIST));
      }
   }

   private void log(int x, int y, int z, int id, int data) {
      if (!inChunk(x, z) || y <= this.chunk.getWorld().getMinHeight() || y >= this.chunk.getWorld().getMaxHeight() - 2) {
         return;
      }
      Block b = this.chunk.getBlock(x - this.minX, y, z - this.minZ);
      Material t = b.getType();
      // air, either leaf block (18/161), the grass/fern/dead-bush plants (31), both flower blocks (37/38) and both mushrooms
      if (LegacyBlocks.is(t, 0) || LegacyBlocks.is(t, 18) || LegacyBlocks.is(t, 161) || LegacyBlocks.is(t, 31)
              || LegacyBlocks.is(t, 37) || LegacyBlocks.is(t, 38) || t == Material.BROWN_MUSHROOM
              || t == Material.RED_MUSHROOM) {
         LegacyBlocks.set(b, id, data);
      }
   }
}
