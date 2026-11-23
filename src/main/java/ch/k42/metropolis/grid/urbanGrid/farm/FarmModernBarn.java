package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

/**
 * The big modern barn: a steel-framed free-stall cattle barn of the kind built in the last few decades, 52 to 68 blocks long and 27
 * or 31 wide, seven blocks to the eaves and twelve to the ridge. Abandoned, but INTACT -- the roof is on, the walls are up, the doors still run on their rails.
 * What time has done is cosmetic: panels faded and streaked with rust, the odd window gone, weeds coming up through the cracks
 * in the concrete, vines up the walls, cobwebs under the roof, dust and old hay everywhere.
 *
 * Built in the building's own frame: u along its length, v across it, dy above the ground block, the front doors at u = 0.
 * The front end has a nine-wide sliding door standing open; the back end's door is half shut; a roll-up door in one long wall has
 * stuck part-way. Inside, laid out the way such barns are: a wide concrete feed alley down the middle, a feed barrier of iron rails
 * either side of it, then a scraper alley, then the free-stall cubicles (iron-bar partitions every second block, open to the
 * alley), then a walkway along each outer wall; steel columns along the barrier every five blocks; a stack of old feed at the
 * back; and the farm office in the front corner by the entrance (windows onto the alley, bookshelf, workbench, filing, loot).
 * The roof is a shallow gable of stone and stone slab, a block of rise to every four of run, with a raised iron ridge vent and
 * glass daylighting strips along it.
 *
 * Loot is tiered by what is plausibly kept where (see FarmPainter.chest): feed-store scraps low, the workshop chest in the middle,
 * the office desk in the middle and its filing cabinet at the top of the configured range.
 *
 * Everything is a pure function of the seed and the position, so chunks come out the same in any order. Painting is limited to
 * the columns of the chunk being painted, so a barn this size costs no more per chunk than a small one.
 */
final class FarmModernBarn extends FarmFeature {
   private static final int CONCRETE = 251;       // data = dye colour: 0 white, 7 gray, 8 silver, 11 blue, 13 green, 14 red, 12 brown
   private static final int IRON_BLOCK = 42;
   private static final int GLASS = 20;
   private static final int BOOKSHELF = 47;
   private static final int CRAFTING_TABLE = 58;
   private static final int SLAB = 44;            // data 0 stone, +8 = top half

   /** Wall height (eaves). */
   private static final int T = 7;
   /** Big doors are this many blocks high; the lintel above them runs up to the eaves. */
   private static final int DOOR_H = 5;
   /** Two-tone siding: { panel colour, plinth/post colour }. */
   private static final int[][] SIDING = {{0, 7}, {8, 11}, {0, 13}, {8, 14}, {3, 7}, {0, 11}};

   private final boolean axisX;
   private final boolean mirror;       // the front (u = 0) is at the high end of the world axis
   private final int length;
   private final int width;
   private final int centre;           // v of the centre line
   private final int ox;               // world minimum corner of the footprint (without the eave overhang)
   private final int oz;
   private final int groundY;
   private final long seed;
   private final int[] siding;

   private FarmModernBarn(int ox, int oz, boolean axisX, boolean mirror, int variant, int groundY, long seed) {
      super(ox - 8, oz - 8,
              ox + (axisX ? lengthOf(variant) : widthOf(variant)) + 7,
              oz + (axisX ? widthOf(variant) : lengthOf(variant)) + 7);       // eaves, vines, and the apron in front of the doors
      this.ox = ox;
      this.oz = oz;
      this.axisX = axisX;
      this.mirror = mirror;
      this.length = lengthOf(variant);
      this.width = widthOf(variant);
      this.centre = (this.width - 1) / 2;
      this.groundY = groundY;
      this.seed = seed;
      this.siding = SIDING[(int) (FarmRandom.unit(seed, ox, oz, 2000) * SIDING.length) % SIDING.length];
   }

   static FarmModernBarn make(int ox, int oz, boolean axisX, boolean mirror, int variant, int groundY, long seed) {
      return new FarmModernBarn(ox, oz, axisX, mirror, variant, groundY, seed);
   }

   /** Variant 0..2: length of the barn. */
   static int lengthOf(int variant) {
      return 52 + 8 * Math.max(0, Math.min(2, variant));
   }

   static int widthOf(int variant) {
      return variant == 2 ? 31 : 27;
   }

   // ---------------------------------------------------------------------------------------------------------------- frame

   private int wx(int u, int v) { return this.axisX ? this.ox + (this.mirror ? this.length - 1 - u : u) : this.ox + v; }

   private int wz(int u, int v) { return this.axisX ? this.oz + v : this.oz + (this.mirror ? this.length - 1 - u : u); }

   private double rnd(int u, int v, int dy, int salt) {
      return FarmRandom.unit(this.seed, this.wx(u, v) * 131 + dy * 7, this.wz(u, v), salt);
   }

   private double column(int u, int salt) {
      return FarmRandom.unit(this.seed, this.wx(u, 0) * 17 + 3, this.wz(u, 0) * 13, salt);
   }

   private void put(FarmPainter p, int u, int v, int dy, int id, int data) {
      p.set(this.wx(u, v), this.groundY + dy, this.wz(u, v), id, data);
   }

   private void fillU(FarmPainter p, int u0, int u1, int v0, int v1, int dy0, int dy1, int id, int data) {
      for (int u = u0; u <= u1; u++) {
         for (int v = v0; v <= v1; v++) {
            for (int dy = dy0; dy <= dy1; dy++) {
               this.put(p, u, v, dy, id, data);
            }
         }
      }
   }

   /** World direction vector of +u and of +v. */
   private int[] uvec() { return this.axisX ? new int[]{this.mirror ? -1 : 1, 0} : new int[]{0, this.mirror ? -1 : 1}; }

   private int[] vvec() { return this.axisX ? new int[]{0, 1} : new int[]{1, 0}; }

   private static int dirData(int dx, int dz) {
      return dx > 0 ? 8 : dx < 0 ? 2 : dz > 0 ? 1 : 4;       // the face a vine clings to: east 8, west 2, south 1, north 4
   }

   /** Vine data for a vine beside a wall, the wall lying toward +v (or -v). */
   private int attachV(boolean wallPlusV) {
      int[] d = this.vvec();
      return wallPlusV ? dirData(d[0], d[1]) : dirData(-d[0], -d[1]);
   }

   private int attachU(boolean wallPlusU) {
      int[] d = this.uvec();
      return wallPlusU ? dirData(d[0], d[1]) : dirData(-d[0], -d[1]);
   }

   /** Chest data for a lid looking toward +u/-u/+v/-v: 2 north, 3 south, 4 west, 5 east. */
   private static int facing(int dx, int dz) {
      return dx > 0 ? 5 : dx < 0 ? 4 : dz > 0 ? 3 : 2;
   }

   private int facingV(boolean plusV) { int[] d = this.vvec(); return plusV ? facing(d[0], d[1]) : facing(-d[0], -d[1]); }

   private int facingU(boolean plusU) { int[] d = this.uvec(); return plusU ? facing(d[0], d[1]) : facing(-d[0], -d[1]); }

   /** Distance of column v from the nearest long wall: 0 over the wall, rising to the centre line. */
   private int roofM(int v) {
      return Math.min(v, this.width - 1 - v);
   }

   /**
    * Height (dy) of the roof piece over a column m blocks in from the wall: a pair of columns shares one height and each pair is half a
    * block above the last (a 1:4 pitch). Even pairs are full blocks, odd pairs bottom slabs one cell higher, so the surface climbs in
    * half-block steps. The lowest pair sits on top of the wall.
    */
   private int roofDy(int m) {
      int n = m / 2;
      return n % 2 == 0 ? T + 1 + n / 2 : T + 1 + (n + 1) / 2;
   }

   private boolean slabRow(int m) {
      return (m / 2) % 2 == 1;
   }

   // ---------------------------------------------------------------------------------------------------------------- paint

   @Override
   void paint(FarmPainter p) {
      int len = this.length;
      int w = this.width;
      // only the columns of this chunk, in the building's frame
      int uLo;
      int uHi;
      int vLo;
      int vHi;
      // map the painter's world box back to (u, v) ranges
      int a0, a1, b0, b1;
      if (this.axisX) {
         a0 = p.minX - this.ox; a1 = p.maxX - this.ox; b0 = p.minZ - this.oz; b1 = p.maxZ - this.oz;
         if (this.mirror) { int t0 = len - 1 - a1; int t1 = len - 1 - a0; a0 = t0; a1 = t1; }
         uLo = a0; uHi = a1; vLo = b0; vHi = b1;
      } else {
         b0 = p.minX - this.ox; b1 = p.maxX - this.ox; a0 = p.minZ - this.oz; a1 = p.maxZ - this.oz;
         if (this.mirror) { int t0 = len - 1 - a1; int t1 = len - 1 - a0; a0 = t0; a1 = t1; }
         uLo = a0; uHi = a1; vLo = b0; vHi = b1;
      }
      uLo = Math.max(uLo - 1, -8);          // a column places vines and eaves one block outside itself, so look one block past the chunk
      uHi = Math.min(uHi + 1, len + 7);
      vLo = Math.max(vLo - 1, -8);
      vHi = Math.min(vHi + 1, w + 7);

      for (int u = uLo; u <= uHi; u++) {
         for (int v = vLo; v <= vHi; v++) {
            this.paintColumn(p, u, v);
         }
      }
      this.interior(p, uLo, uHi, vLo, vHi);
   }

   /** Everything that is a function of one (u, v) column: apron, floor, walls, roof, vines. */
   private void paintColumn(FarmPainter p, int u, int v) {
      int len = this.length;
      int w = this.width;
      boolean inside = u >= 0 && u < len && v >= 0 && v < w;
      if (!inside) {
         this.outside(p, u, v);
         return;
      }
      boolean wallU = u == 0 || u == len - 1;
      boolean wallV = v == 0 || v == w - 1;

      // floor: silver concrete, oil-stained in places, cracked where something has pushed up through it
      double f = this.rnd(u, v, 0, 2010);
      this.put(p, u, v, 0, CONCRETE, f < 0.12 ? 7 : 8);
      if (!wallU && !wallV && f > 0.965) {
         this.put(p, u, v, 0, FarmPainter.DIRT, 1);
         this.put(p, u, v, 1, FarmPainter.TALL_GRASS, 1);
      }

      if (wallV || wallU) {
         this.wall(p, u, v, wallU);
      }
      this.roof(p, u, v);
   }

   // ------------------------------------------------------------------------------------------------------------ walls

   private void wall(FarmPainter p, int u, int v, boolean gable) {
      int top = gable ? this.roofDy(this.roofM(v)) - 1 : T;
      for (int dy = 1; dy <= top; dy++) {
         int[] cell = this.wallCell(u, v, dy, gable);
         if (cell != null) {
            this.put(p, u, v, dy, cell[0], cell[1]);
         }
      }
      // creeper up the outside, only where there is wall to hold it
      this.vineUp(p, u, v, gable);
   }

   /**
    * What the wall is made of at one cell, or null where there is none (a doorway, a window gap, a panel kicked in). A pure function
    * of the position, so the vines can ask whether the wall they would cling to is really there.
    */
   private int[] wallCell(int u, int v, int dy, boolean gable) {
      int len = this.length;
      int w = this.width;
      int c = this.centre;
      int m = this.roofM(v);
      int top = gable ? this.roofDy(m) - 1 : T;
      if (dy < 1 || dy > top) {
         return null;
      }
      boolean post = gable ? (Math.abs(v - c) % 5 == 0 || v == 0 || v == w - 1) : (u % 5 == 0 || u == len - 1);
      int panel = this.siding[0];
      int plinth = this.siding[1];
      boolean front = gable && u == 0;
      boolean back = gable && u == len - 1;
      int doorHalf = 4;
      boolean bigDoor = gable && Math.abs(v - c) <= doorHalf;             // nine wide, DOOR_H high
      boolean bigJamb = gable && Math.abs(v - c) == doorHalf + 1;
      boolean bay = !gable && Math.abs(u - len / 2) <= 3 && v == 0;       // a six-wide roll-up bay in the near long wall

      // lintels over the big doors
      if (gable && Math.abs(v - c) <= doorHalf + 1 && dy > DOOR_H && dy <= T) {
         return new int[]{CONCRETE, 7};
      }
      int id = CONCRETE;
      int data;
      if (dy <= 3 || post) {
         data = plinth;
      } else {
         data = panel;
         if (this.rnd(u, v, dy, 2020) < 0.22) {
            data = 8;                                                       // sun-faded
         }
      }
      // weather: rust runs down from the eaves and the seams, mud splashes up from the ground
      int rustLen = this.rustLength(gable ? v + 1000 : u);
      if (dy > top - rustLen && dy <= top && dy > 3 && !post && this.rnd(u, v, dy, 2021) < 0.8) {
         id = FarmPainter.TERRACOTTA;
         data = this.column(gable ? v + 7000 : u + 4000, 2022) < 0.5 ? 1 : 12;
      } else if (dy <= 2 && this.rnd(u, v, dy, 2023) < 0.18) {
         id = FarmPainter.TERRACOTTA;
         data = 12;
      }
      // the ribbon window under the eaves, between the posts
      if (!gable && dy == T - 1 && !post && u % 5 != 0) {
         return this.rnd(u, v, dy, 2024) < 0.30 ? null : new int[]{FarmPainter.GLASS_PANE, 0};
      }
      // doorways
      if (bigDoor && dy <= DOOR_H) {
         if (front) {
            return null;                                                    // standing open, rails and all
         }
         if (back) {
            if (v - c > 0) {
               return null;                                                 // half shut: the leaf covers only the low-v half
            }
            return new int[]{CONCRETE, dy % 2 == 0 ? 7 : 8};               // ribbed steel leaf
         }
      }
      if (bigJamb && dy <= DOOR_H) {
         id = CONCRETE;
         data = 7;
      }
      if (bay && dy <= 4) {
         if (dy <= this.rollupOpenTo()) {
            return null;                                                    // the shutter is stuck part way up
         }
         return new int[]{CONCRETE, dy % 2 == 0 ? 7 : 8};
      }
      if (gable && dy == top && dy > 4) {
         data = plinth;                                                     // the rake edge, picked out
      }
      // an occasional panel that has been kicked in at head height
      if (dy >= 4 && dy <= 5 && !post && this.rnd(u, v, dy, 2025) < 0.012) {
         return null;
      }
      return new int[]{id, data};
   }

   /** Can a vine cling to this? Only a full opaque block holds one; glass, panes and bars do not. */
   private static boolean holdsVine(int[] cell) {
      return cell != null && (cell[0] == CONCRETE || cell[0] == FarmPainter.TERRACOTTA);
   }

   /** How far down from the eaves a rust streak runs at this wall position, 0 for none. */
   private int rustLength(int pos) {
      double s = FarmRandom.unit(this.seed, pos * 29, pos * 7 + 1, 2030);
      if (s > 0.22) {
         return 0;
      }
      return 2 + (int) (s * 25);                                           // 2..7
   }

   /** How many blocks of the roll-up bay stand open: its shutter jammed two or three blocks up from the floor. */
   private int rollupOpenTo() {
      return FarmRandom.unit(this.seed, this.ox + this.oz, this.length, 2031) < 0.5 ? 2 : 3;
   }

   private void vineUp(FarmPainter p, int u, int v, boolean gable) {
      int len = this.length;
      int w = this.width;
      if (gable) {
         if (this.rnd(u, v, 0, 2040) >= 0.16) {
            return;
         }
         int h = 3 + (int) (this.rnd(u, v, 1, 2041) * 5);
         boolean frontEnd = u == 0;
         for (int dy = 1; dy <= h; dy++) {
            if (holdsVine(this.wallCell(u, v, dy, true))) {
               this.put(p, frontEnd ? -1 : len, v, dy, FarmPainter.VINE, this.attachU(frontEnd));
            }
         }
         return;
      }
      if (this.rnd(u, v, 0, 2042) >= 0.18) {
         return;
      }
      int h = 3 + (int) (this.rnd(u, v, 1, 2043) * 5);
      boolean low = v == 0;
      for (int dy = 1; dy <= h; dy++) {
         if (holdsVine(this.wallCell(u, v, dy, false))) {
            this.put(p, u, low ? -1 : w, dy, FarmPainter.VINE, this.attachV(low));
         }
      }
   }

   // ------------------------------------------------------------------------------------------------------------ roof

   private void roof(FarmPainter p, int u, int v) {
      int w = this.width;
      int c = this.centre;
      int m = this.roofM(v);
      int dy = this.roofDy(m);
      boolean slab = this.slabRow(m);
      boolean ridge = v == c;
      // daylighting strips: raised glass in segments, in the upper part of each slope
      boolean skylight = !ridge && m >= c - 8 && m <= c - 3 && (u % 10) >= 3 && (u % 10) <= 7 && u > 2 && u < this.length - 3
              && FarmRandom.unit(this.seed, this.wx(u / 10, 0) * 5 + (v < c ? 1 : 2), this.wz(u / 10, 0), 2051) < 0.8;
      int id;
      int data = 0;
      if (skylight) {
         if (this.rnd(u, v, 0, 2052) < 0.07) {
            return;                                                         // a pane out
         }
         id = GLASS;
      } else if (slab) {
         id = SLAB;                                                         // half-block steps: slabs on the odd pairs ...
      } else {
         id = FarmPainter.STONE;                                            // ... full blocks on the even ones
         if (this.rnd(u, v, 0, 2053) < 0.035) {
            id = FarmPainter.TERRACOTTA;                                    // a rust bloom on the sheeting
            data = 12;
         }
      }
      this.put(p, u, v, dy, id, data);
      if (ridge) {
         int cap = IRON_BLOCK;                                              // the ridge vent, standing a block proud of the roof
         int cdata = 0;
         if (this.rnd(u, v, 0, 2050) < 0.07) {
            cap = FarmPainter.TERRACOTTA;                                   // rusted through to orange
            cdata = 1;
         }
         this.put(p, u, v, dy + 1, cap, cdata);
      }
      // the overhang at the ends of the roof (u = -1 and u = length): the same profile one block out
      if (u == 0 || u == this.length - 1) {
         int uu = u == 0 ? -1 : this.length;
         if (!skylight) {
            this.put(p, uu, v, dy, id, data);
         }
      }
      // the eave, one block out past each long wall and a block lower
      if (v == 0) {
         this.put(p, u, -1, T, FarmPainter.STONE, 0);
      }
      if (v == w - 1) {
         this.put(p, u, w, T, FarmPainter.STONE, 0);
      }
      // cobwebs under the roof, in the corners where a roof meets a wall
      if (this.rnd(u, v, 0, 2054) < 0.025 && (v == 1 || v == w - 2) && u > 0 && u < this.length - 1) {
         this.put(p, u, v, T, FarmPainter.COBWEB, 0);
      }
   }

   // ------------------------------------------------------------------------------------------------------------ outside

   /** Apron in front of the doors, weeds in its cracks. Only reached for columns beyond the footprint. */
   private void outside(FarmPainter p, int u, int v) {
      int len = this.length;
      int w = this.width;
      int c = this.centre;
      boolean frontApron = u >= -6 && u < 0 && Math.abs(v - c) <= 7;
      boolean backApron = u >= len && u <= len + 3 && Math.abs(v - c) <= 6;
      boolean sideApron = v >= -4 && v < 0 && u >= len / 2 - 5 && u <= len / 2 + 4;
      if (frontApron || backApron || sideApron) {
         double f = this.rnd(u, v, 0, 2060);
         this.put(p, u, v, 0, CONCRETE, f < 0.15 ? 7 : 8);
         if (f > 0.93) {
            this.put(p, u, v, 0, FarmPainter.DIRT, 1);
            this.put(p, u, v, 1, FarmPainter.TALL_GRASS, 1);
         }
      }
   }

   // ------------------------------------------------------------------------------------------------------------ interior

   /**
    * The floor plan of a free-stall barn, from the centre line out (dist = blocks from it): the feed alley (0..2), the feed barrier of
    * iron rails with a steel column every fifth block (3), the scraper alley (4..6) with water troughs against the barrier, the
    * cubicles (7..9: partitions every second block, old bedding between), and a walkway along the outer wall (10 and beyond).
    * Cubicles are open to the scraper alley and to nothing else, as in life.
    */
   private void interior(FarmPainter p, int uLo, int uHi, int vLo, int vHi) {
      int len = this.length;
      int w = this.width;
      int c = this.centre;

      for (int u = Math.max(1, uLo); u <= Math.min(len - 2, uHi); u++) {
         for (int v = Math.max(1, vLo); v <= Math.min(w - 2, vHi); v++) {
            if (this.inOffice(u, v)) {
               continue;
            }
            int dist = Math.abs(v - c);
            // manure and wet on the floors the cows walked: the scraper alley and the outer walkway, in patches
            if (dist >= 4 && this.rnd(u, v, 0, 2069) < 0.09) {
               this.put(p, u, v, 0, CONCRETE, 12);
            }
            if (dist == 3) {
               if (u % 5 == 0) {
                  int under = this.roofDy(this.roofM(v)) - 1;                // a steel column up to the roof
                  for (int dy = 1; dy <= under; dy++) {
                     this.put(p, u, v, dy, CONCRETE, 7);
                  }
               } else if (u > 1 && u < len - 2 && this.rnd(u, v, 0, 2070) >= 0.14) {
                  this.put(p, u, v, 1, FarmPainter.IRON_BARS, 0);            // the feed barrier, a rail or two gone
                  if (this.rnd(u, v, 1, 2071) < 0.7) {
                     this.put(p, u, v, 2, FarmPainter.IRON_BARS, 0);
                  }
               }
               continue;
            }
            if (dist == 4 && u % 12 == 6) {
               this.put(p, u, v, 1, FarmPainter.CAULDRON, 3 - (int) (this.rnd(u, v, 0, 2075) * 4) % 4);   // a trough, dry or half full
               continue;
            }
            if (dist >= 7 && dist <= 9 && u > 1 && u < len - 2) {
               if (u % 2 == 0) {
                  if (this.rnd(u, v, 0, 2072) >= 0.10) {
                     this.put(p, u, v, 1, FarmPainter.IRON_BARS, 0);          // a cubicle partition
                     if (dist <= 8 && this.rnd(u, v, 1, 2077) < 0.5) {
                        this.put(p, u, v, 2, FarmPainter.IRON_BARS, 0);
                     }
                  }
               } else if (this.rnd(u, v, 0, 2073) < 0.07) {
                  this.put(p, u, v, 1, FarmPainter.HAY, 0);                  // old bedding
               } else if (this.rnd(u, v, 0, 2074) < 0.03) {
                  this.put(p, u, v, 1, FarmPainter.TALL_GRASS, 1);
               }
            }
         }
      }

      // a store of old feed at the back of the alley: a heap that has slumped, higher against the end wall
      for (int u = Math.max(len - 10, uLo); u <= Math.min(len - 3, uHi); u++) {
         for (int v = Math.max(c - 2, vLo); v <= Math.min(c + 2, vHi); v++) {
            int heap = 3 - Math.max(Math.abs(v - c), (len - 3 - u) / 2);
            for (int dy = 1; dy <= heap; dy++) {
               if (this.rnd(u, v, dy, 2076) < 0.92) {
                  this.put(p, u, v, dy, FarmPainter.HAY, (u + v + dy) % 3 == 0 ? 4 : 0);
               }
            }
         }
      }
      this.office(p, uLo, uHi, vLo, vHi);
      this.chests(p);
   }

   // ------------------------------------------------------------------------------------------------------------ office

   /** The farm office: a room 10 x 9 in the front corner on the high-v side, by the entrance where the farmer would have parked. */
   private boolean inOffice(int u, int v) {
      return u >= 3 && u <= 12 && v >= this.width - 10 && v <= this.width - 2;
   }

   private void office(FarmPainter p, int uLo, int uHi, int vLo, int vHi) {
      int w = this.width;
      int u0 = 3;
      int u1 = 12;
      int v0 = w - 10;
      int v1 = w - 2;
      for (int u = Math.max(u0, uLo); u <= Math.min(u1, uHi); u++) {
         for (int v = Math.max(v0, vLo); v <= Math.min(v1, vHi); v++) {
            boolean edge = u == u0 || u == u1 || v == v0 || v == v1;
            this.put(p, u, v, 0, FarmPainter.PLANKS, 1);                          // a wooden floor over the slab, buckled
            if (this.rnd(u, v, 0, 2080) < 0.08) {
               this.put(p, u, v, 0, FarmPainter.PLANKS, 5);
            }
            this.put(p, u, v, 5, SLAB, 0);                                         // a flat ceiling at head height
            if (!edge) {
               continue;
            }
            boolean viewWall = u == u0 || u == u1 || v == v0;                       // the walls that look out onto the barn
            for (int dy = 1; dy <= 4; dy++) {
               boolean doorway = v == v0 && u >= u0 + 4 && u <= u0 + 5 && dy <= 2;   // the door onto the alley, two high
               if (doorway) {
                  continue;
               }
               boolean window = viewWall && (dy == 3 || dy == 2) && (u - u0 + v - v0) % 3 != 0 && !(v == v0 && u >= u0 + 4 && u <= u0 + 5)
                       && u > u0 - 1 && !(u == u0 && v >= v1 - 1) && !(u == u1 && v >= v1 - 1);
               if (window) {
                  if (this.rnd(u, v, dy, 2081) < 0.40) {
                     continue;                                                       // broken
                  }
                  this.put(p, u, v, dy, FarmPainter.GLASS_PANE, 0);
                  continue;
               }
               this.put(p, u, v, dy, CONCRETE, dy <= 1 ? 7 : 0);
            }
         }
      }
      // fittings: files (bookshelves) along the back wall, a desk with a workbench beside it
      for (int u = u0 + 1; u <= u1 - 1; u++) {
         this.put(p, u, v1 - 1, 1, BOOKSHELF, 0);
         if (this.rnd(u, v1 - 1, 2, 2082) < 0.6) {
            this.put(p, u, v1 - 1, 2, BOOKSHELF, 0);
         }
      }
      this.put(p, u0 + 2, v1 - 2, 1, CRAFTING_TABLE, 0);
      this.put(p, u1 - 3, v1 - 2, 1, FarmPainter.CAULDRON, 0);
   }

   /**
    * The chests, tiered by what would be kept where. In the office the desk drawer holds the ordinary papers and petty cash (middle),
    * the filing cabinet the deeds and records worth most (top); the workshop corner by the roll-up door has the tool chest (middle);
    * what is left in the feed store at the back is the least (low).
    */
   private void chests(FarmPainter p) {
      int len = this.length;
      int w = this.width;
      int c = this.centre;
      int v1 = w - 2;
      p.chest(this.wx(5, v1 - 2), this.groundY + 1, this.wz(5, v1 - 2), this.facingV(false), 41, FarmPainter.TIER_MID);                // desk
      p.chest(this.wx(10, v1 - 2), this.groundY + 1, this.wz(10, v1 - 2), this.facingV(false), 42, FarmPainter.TIER_TOP);              // filing cabinet
      p.chest(this.wx(len / 2 + 5, 1), this.groundY + 1, this.wz(len / 2 + 5, 1), this.facingV(true), 44, FarmPainter.TIER_MID);       // workshop
      p.chest(this.wx(len - 2, c + 2), this.groundY + 1, this.wz(len - 2, c + 2), this.facingV(false), 43, FarmPainter.TIER_LOW);      // feed store
   }

   // ================================================================================================================= bunker

   /**
    * A silage bunker: a long concrete trench open at one end, three blocks deep in walls, floored with the black remains of
    * silage and old hay, a row of tyres still sitting on the heap, weeds grown over the lot.
    */
   static final class Bunker extends FarmFeature {
      private final boolean axisX;
      private final boolean mirror;
      private final int length;
      private final int width;
      private final int ox;
      private final int oz;
      private final int groundY;
      private final long seed;

      Bunker(int ox, int oz, boolean axisX, boolean mirror, int length, int width, int groundY, long seed) {
         super(ox, oz, ox + (axisX ? length : width) - 1, oz + (axisX ? width : length) - 1);
         this.ox = ox;
         this.oz = oz;
         this.axisX = axisX;
         this.mirror = mirror;
         this.length = length;
         this.width = width;
         this.groundY = groundY;
         this.seed = seed;
      }

      private int wx(int u, int v) { return this.axisX ? this.ox + (this.mirror ? this.length - 1 - u : u) : this.ox + v; }

      private int wz(int u, int v) { return this.axisX ? this.oz + v : this.oz + (this.mirror ? this.length - 1 - u : u); }

      private double rnd(int u, int v, int salt) {
         return FarmRandom.unit(this.seed, this.wx(u, v), this.wz(u, v), salt);
      }

      @Override
      void paint(FarmPainter p) {
         for (int u = 0; u < this.length; u++) {
            for (int v = 0; v < this.width; v++) {
               int x = this.wx(u, v);
               int z = this.wz(u, v);
               if (!p.in(x, z)) {
                  continue;
               }
               int gy = this.groundY;
               boolean wall = v == 0 || v == this.width - 1 || u == this.length - 1;
               p.set(x, gy, z, CONCRETE, this.rnd(u, v, 2100) < 0.2 ? 7 : 8);
               if (wall) {
                  int wh = this.rnd(u, v, 2101) < 0.06 ? 2 : 3;
                  for (int dy = 1; dy <= wh; dy++) {
                     p.set(x, gy + dy, z, CONCRETE, dy == 3 ? 8 : 7);
                  }
                  if (this.rnd(u, v, 2102) < 0.25) {
                     p.set(x, gy + 4, z, FarmPainter.TALL_GRASS, 1);
                  }
                  continue;
               }
               // the heap: highest in the middle and at the back, sloping off toward the open end
               double mound = 3.0 * Math.min(1.0, (this.length - 1 - u) / 8.0) * (1.0 - Math.abs(v - (this.width - 1) / 2.0) / (this.width / 2.0));
               int hh = (int) Math.round(mound);
               for (int dy = 1; dy <= hh; dy++) {
                  p.set(x, gy + dy, z, dy == hh ? FarmPainter.COAL_BLOCK : FarmPainter.HAY, 0);
                  if (dy == hh && this.rnd(u, v, 2103) < 0.55) {
                     p.set(x, gy + dy, z, FarmPainter.HAY, 0);                      // only some of the top is still covered by a tyre
                  }
               }
               if (hh == 0 || this.rnd(u, v, 2104) < 0.5) {
                  p.set(x, gy + hh + 1, z, FarmPainter.TALL_GRASS, 1);
               }
            }
         }
      }
   }
}
