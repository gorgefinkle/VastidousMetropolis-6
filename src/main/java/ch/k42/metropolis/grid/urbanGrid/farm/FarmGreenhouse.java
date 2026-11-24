package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

/**
 * An abandoned greenhouse, standing but neglected: glass gone green, panes missing, the plants inside grown wild.
 *
 * Two kinds:
 *  - GABLED: a glasshouse. A low stone-brick plinth (mossy, cracked), glass walls between white quartz posts, a 45 degree glass
 *    roof on quartz rafters with a ridge beam. A wide doorway at one end with no door left in it. Raised beds either side of a
 *    gravel path, a water barrel by the door, a chest at the far end.
 *  - BAYS: the large commercial glasshouse: three or four gabled bays side by side under one roof, thirty-six to forty-eight long.
 *    The roof runs in ridges and furrows (a gutter beam along each furrow, quartz posts at the gutters every fourth block, no walls
 *    between the bays), a wide door into every bay at the front end, the back end shut. Each bay is planted with ONE crop in
 *    unbroken rows, like the tunnels, and is as broken as a glasshouse is.
 *  - HOOP: a bank of long polytunnels side by side: steel hoops every third block (the hoops line up across the whole bank) under a
 *    skin of milky plastic sheeting (white stained glass, since that is the nearest a block gets), open at one end and closed at the
 *    other. Laid out the way a grower runs them: every tunnel the same length with its ends flush, a gravel path down each, two beds
 *    either side, and each tunnel planted with ONE crop in unbroken rows (wheat, carrots, potatoes or beetroot, gone over and dead),
 *    weeds creeping in from the closed end where nobody went. A rain barrel stands at the open end between every other pair. The
 *    sheeting is torn away in long strips along the tunnel, as it goes when the wind gets under a loose edge, so far more of it is
 *    gone than of a glasshouse's glass.
 *
 * Fragile things go first, so more of the glass is gone than of a barn's boards (it is broken in clusters, a pane or a few at a
 * time, never a random scatter), and what grows inside has had years: tall grass and ferns pushing out of the beds, dead wheat,
 * pumpkins gone to seed, vines up the glass inside and out. Everything is a pure function of the seed and the block position,
 * so chunks come out the same in any order.
 */
final class FarmGreenhouse extends FarmFeature {
   static final int GABLED = 0;
   static final int HOOP = 1;
   static final int BAYS = 2;
   static final int STANDARD = 0;
   static final int PROPAGATION = 1;
   static final int RESEARCH = 2;
   static final int COLLAPSED_WING = 3;
   /** A bay of the commercial glasshouse: eight blocks from furrow to furrow (the gutter column is shared with the next bay). */
   private static final int BAY_W = 8;

   private static final int GLASS = 20;
   private static final int PLANKS = FarmPainter.PLANKS;
   private static final int WALL = FarmPainter.STONE_BRICK;

   private static final int QUARTZ = FarmPainter.TERRACOTTA; // white structural infill; avoids a quartz-heavy survival cost
   private static final int STONE_BRICK_B = 98;      // data 1 mossy, 2 cracked
   private static final int FARMLAND_B = 60;

   /** Arch of a hoop tunnel, column by column: the skin runs from HOOP_LOW up to HOOP_TOP above the floor, two blocks thick so it is continuous. */
   private static final int[] HOOP_TOP = {2, 3, 4, 5, 4, 3, 2};
   private static final int[] HOOP_LOW = {1, 2, 3, 4, 3, 2, 1};
   private static final int HOOP_W = 7;
   private static final int HOOP_GAP = 2;

   private final int style;
   private final int purpose;
   private final boolean axisX;      // the long axis runs along x
   private final boolean mirror;     // the open/entrance end faces the high-coordinate end
   private final int length;         // along the long axis
   private final int width;          // across it (for HOOP, all the tunnels together)
   private final int tunnels;        // HOOP only
   private final int groundY;
   private final long seed;
   private final int ox;
   private final int oz;
   private final double breakage;    // 0..1 how much of the glass is gone
   private final int skinId;         // what a polytunnel's ARCH is covered with (generator.farm.tunnelSkin)
   private final int skinData;
   private final int endId;          // what its closed end is: a vertical wall, the one place a pane suits
   private final int endData;

   private FarmGreenhouse(int ox, int oz, boolean axisX, boolean mirror, int style, int purpose, int length, int width, int tunnels, int groundY, long seed) {
      super(ox - 1, oz - 1, ox + (axisX ? length : width), oz + (axisX ? width : length));   // one block of margin: vines grow on the outside
      this.ox = ox;
      this.oz = oz;
      this.axisX = axisX;
      this.mirror = mirror;
      this.style = style;
      this.purpose = purpose;
      this.length = length;
      this.width = width;
      this.tunnels = tunnels;
      this.groundY = groundY;
      this.seed = seed;
      double ruin = Math.max(0, Math.min(100, PluginConfig.getFarmRuinPercent())) / 100.0;
      this.breakage = Math.min(0.55, (style == HOOP ? 0.26 : 0.18) + ruin * 0.8);
      String skin = PluginConfig.getFarmTunnelSkin();
      // A pane is a thin vertical plate that joins its neighbours sideways only, so on a curved roof it is a lattice of ribs with the
      // weather coming through, not a skin. The arch therefore wants whole blocks; panes stay for the end wall, which is vertical.
      if ("pane".equals(skin)) {
         this.skinId = 160;                      // white stained glass PANES everywhere (a lattice on the arch; kept as an option)
         this.skinData = 0;
         this.endId = 160;
         this.endData = 0;
      } else if ("wool".equals(skin)) {
         this.skinId = 35;                       // white wool: shade cloth
         this.skinData = 0;
         this.endId = 35;
         this.endData = 0;
      } else {
         this.skinId = 95;                       // white stained glass BLOCKS on the arch: a continuous skin (the default)
         this.skinData = 0;
         this.endId = 160;                       // and panes on the closed end wall
         this.endData = 0;
      }
   }

   // ---- sizes the layout needs before it places one

   /** Variant 0..2: how long the building is. */
   static int lengthOf(int style, int variant) {
      int v = Math.max(0, Math.min(2, variant));
      return style == GABLED ? 21 + 6 * v : (style == BAYS ? 36 + 6 * v : 24 + 6 * v);
   }

   /** Width of a GABLED house of this variant, or of a HOOP group of `tunnels` tunnels. */
   static int widthOf(int style, int variant, int tunnels) {
      if (style == GABLED) {
         return variant == 0 ? 9 : 11;
      }
      if (style == BAYS) {
         return tunnels * BAY_W + 1;             // `tunnels` is the number of bays here
      }
      return tunnels * HOOP_W + (tunnels - 1) * HOOP_GAP;
   }

   static FarmGreenhouse gabled(int ox, int oz, boolean axisX, int variant, int groundY, long seed) {
      return gabled(ox, oz, axisX, false, variant, groundY, seed);
   }

   static FarmGreenhouse gabled(int ox, int oz, boolean axisX, boolean mirror, int variant, int groundY, long seed) {
      return gabled(ox, oz, axisX, mirror, variant, STANDARD, groundY, seed);
   }

   static FarmGreenhouse gabled(int ox, int oz, boolean axisX, boolean mirror, int variant, int purpose, int groundY, long seed) {
      return new FarmGreenhouse(ox, oz, axisX, mirror, GABLED, purpose,
              lengthOf(GABLED, variant), widthOf(GABLED, variant, 0), 0, groundY, seed);
   }

   /** The commercial glasshouse: `bays` gabled bays (3 or 4) under one roof, length by variant 0..2 (36, 42, 48). */
   static FarmGreenhouse bays(int ox, int oz, boolean axisX, int variant, int bays, int groundY, long seed) {
      return bays(ox, oz, axisX, false, variant, bays, groundY, seed);
   }

   static FarmGreenhouse bays(int ox, int oz, boolean axisX, boolean mirror, int variant, int bays, int groundY, long seed) {
      return bays(ox, oz, axisX, mirror, variant, bays, STANDARD, groundY, seed);
   }

   static FarmGreenhouse bays(int ox, int oz, boolean axisX, boolean mirror, int variant, int bays, int purpose, int groundY, long seed) {
      return new FarmGreenhouse(ox, oz, axisX, mirror, BAYS, purpose,
              lengthOf(BAYS, variant), widthOf(BAYS, variant, bays), bays, groundY, seed);
   }

   static FarmGreenhouse hoops(int ox, int oz, boolean axisX, int variant, int tunnels, int groundY, long seed) {
      return hoops(ox, oz, axisX, false, variant, tunnels, groundY, seed);
   }

   static FarmGreenhouse hoops(int ox, int oz, boolean axisX, boolean mirror, int variant, int tunnels, int groundY, long seed) {
      return new FarmGreenhouse(ox, oz, axisX, mirror, HOOP, STANDARD, lengthOf(HOOP, variant), widthOf(HOOP, variant, tunnels), tunnels, groundY, seed);
   }

   // ---- helpers in the building's own frame: u along the long axis, v across it, dy above the ground block

   private int wx(int u, int v) {
      return this.axisX ? this.ox + (this.mirror ? this.length - 1 - u : u) : this.ox + v;
   }

   private int wz(int u, int v) {
      return this.axisX ? this.oz + v : this.oz + (this.mirror ? this.length - 1 - u : u);
   }

   private double rnd(int u, int v, int dy, int salt) {
      return FarmRandom.unit(this.seed, this.wx(u, v) * 131 + dy * 7, this.wz(u, v), salt);
   }

   /** Glass is lost in clusters: a coarse cell is either damaged or not, and inside a damaged cell most panes go. */
   private boolean broken(int u, int v, int dy) {
      // sheeting tears in long strips running along the tunnel; glass breaks in squarer patches
      int sx = this.style == HOOP && this.axisX ? 2 : 1;
      int sz = this.style == HOOP && !this.axisX ? 2 : 1;
      double coarse = FarmRandom.unit(this.seed, (this.wx(u, v) >> sx) * 977 + (dy >> 1), this.wz(u, v) >> sz, 1701);
      if (coarse >= this.breakage) {
         return this.rnd(u, v, dy, 1702) < 0.015;          // the odd lone pane
      }
      return this.rnd(u, v, dy, 1703) < 0.8;
   }

   private void put(FarmPainter p, int u, int v, int dy, int id, int data) {
      p.set(this.wx(u, v), this.groundY + dy, this.wz(u, v), id, data);
   }

   private void glass(FarmPainter p, int u, int v, int dy) {
      boolean collapsedWing = this.style == BAYS && this.purpose == COLLAPSED_WING
              && u >= (this.length * 2) / 3
              && v >= Math.max(1, (this.tunnels - 1) * BAY_W + 1)
              && dy >= 5;
      if (!collapsedWing && !this.broken(u, v, dy)) {
         if (this.style == HOOP) {
            this.put(p, u, v, dy, this.skinId, this.skinData);
         } else {
            this.put(p, u, v, dy, GLASS, 0);
         }
      }
   }

   /** Vine data for a vine standing beside a wall: the face it clings to is the wall's. wallTowardPlusV = the wall is on the +v side of it. */
   private int attach(boolean wallTowardPlusV) {
      if (this.axisX) {
         return wallTowardPlusV ? 1 : 4;          // +v is +z (south face), -v is -z (north face)
      }
      return wallTowardPlusV ? 8 : 2;             // +v is +x (east face), -v is -x (west face)
   }

   @Override
   void paint(FarmPainter p) {
      if (this.style == GABLED) {
         this.paintGabled(p);
      } else if (this.style == BAYS) {
         this.paintBays(p);
      } else {
         this.paintHoops(p);
      }
   }

   // ------------------------------------------------------------------------------------------------------------ gabled glasshouse

   private void paintGabled(FarmPainter p) {
      int len = this.length;
      int w = this.width;
      int half = (w - 1) / 2;
      final int T = 3;                                         // wall height: plinth at 1, glass at 2..3

      // floor, bare earth under the path and beds, a gravel path down the middle
      for (int u = 0; u < len; u++) {
         for (int v = 0; v < w; v++) {
            this.put(p, u, v, 0, FarmPainter.DIRT, 0);
            if (v == half) {
               this.put(p, u, v, 0, FarmPainter.GRAVEL, 0);
            }
         }
      }

      // walls: plinth, quartz posts every four, glass between
      for (int u = 0; u < len; u++) {
         for (int side = 0; side < 2; side++) {
            int v = side == 0 ? 0 : w - 1;
            boolean post = u % 4 == 0 || u == len - 1;
            double m = this.rnd(u, v, 1, 1710);
            this.put(p, u, v, 1, STONE_BRICK_B, m < 0.40 ? 1 : (m < 0.60 ? 2 : 0));
            for (int dy = 2; dy <= T; dy++) {
               if (post) {
                  this.put(p, u, v, dy, QUARTZ, 0);
               } else if (this.purpose == RESEARCH && u >= Math.max(7, len - 8)) {
                  this.put(p, u, v, dy, WALL, 0);
               } else {
                  this.glass(p, u, v, dy);
               }
            }
         }
      }

      // the roof: a 45 degree slope, each column two glass blocks tall so no diagonal gap lets the weather through
      for (int u = 0; u < len; u++) {
         boolean rafter = u % 4 == 0 || u == len - 1;
         for (int k = 0; k <= half; k++) {
            for (int side = 0; side < 2; side++) {
               int v = side == 0 ? k : w - 1 - k;
               if (k == 0) {
                  continue;                                    // that is the top of the wall
               }
               for (int dy = T + k - 1; dy <= T + k; dy++) {
                  boolean ridge = k == half;
                  if (ridge && dy == T + k) {
                     this.put(p, u, v, dy, QUARTZ, 0);          // the ridge beam
                  } else if (rafter && dy == T + k) {
                     this.put(p, u, v, dy, QUARTZ, 0);
                  } else if (this.purpose == RESEARCH && u >= Math.max(7, len - 8)) {
                     this.put(p, u, v, dy, PLANKS, 1);
                  } else {
                     this.glass(p, u, v, dy);
                  }
               }
            }
         }
      }

      // end walls: glass in the gable, a quartz frame, and a wide doorway with nothing left in it at the entrance end
      for (int end = 0; end < 2; end++) {
         int u = end == 0 ? 0 : len - 1;
         for (int v = 1; v <= w - 2; v++) {
            int k = Math.min(v, w - 1 - v);
            int top = T + k - 2;                               // up to just under the roof
            boolean door = end == 0 && Math.abs(v - half) <= 1;
            boolean solidLabEnd = this.purpose == RESEARCH && end == 1;
            for (int dy = 1; dy <= top; dy++) {
               if (door && dy <= 3) continue;
               boolean frame = v == half || dy == top || (door && dy == 4);
               if (frame || (dy == 1 && !door) || solidLabEnd) {
                  this.put(p, u, v, dy, solidLabEnd ? WALL : (dy == 1 && !frame ? STONE_BRICK_B : QUARTZ), 0);
               } else {
                  this.glass(p, u, v, dy);
               }
            }
         }
      }

      // Propagation beds and research trials have their own internal layout.
      // Research keeps the last eight blocks as an enclosed rear laboratory.
      int plantingEnd = this.purpose == RESEARCH ? Math.max(7, len - 8) : len - 1;
      for (int u = 2; u < plantingEnd; u++) {
         for (int v = 1; v <= w - 2; v++) {
            if (v == half || v == half - 1 || v == half + 1) continue;
            this.bed(p, u, v);
         }
      }
      if (this.purpose == RESEARCH) this.paintResearchRoom(p, Math.max(7, len - 8), len, w, half);
      // creeper, on the outside and the inside. A vine cannot cling to glass, so it climbs what is solid: three blocks up the quartz
      // posts, and only the one block of plinth between them.
      for (int u = 0; u < len; u++) {
         boolean post = u % 4 == 0 || u == len - 1;
         for (int side = 0; side < 2; side++) {
            for (int inside = 0; inside < 2; inside++) {
               int v = inside == 0 ? (side == 0 ? -1 : w) : (side == 0 ? 1 : w - 2);
               if (this.rnd(u, v, 0, 1720 + inside) >= (post ? 0.45 : 0.10)) {
                  continue;
               }
               int top = post ? 1 + (int) (this.rnd(u, v, 1, 1723) * 3) : 1;
               boolean wallPlusV = inside == 0 ? side == 0 : side == 1;     // which way the wall lies from the vine
               for (int dy = 1; dy <= top; dy++) {
                  this.put(p, u, v, dy, FarmPainter.VINE, this.attach(wallPlusV));
               }
            }
         }
      }

      // a water barrel by the door, and a chest at the far end among the pots
      this.put(p, 2, 1, 1, FarmPainter.CAULDRON, 3);
      this.put(p, 2, w - 2, 1, FarmPainter.CAULDRON, 0);
      int chestFacing = this.axisX ? (this.mirror ? 5 : 4) : (this.mirror ? 3 : 2); // the lid looks back toward the door
      p.chest(this.wx(len - 2, half), this.groundY + 1, this.wz(len - 2, half), chestFacing, 31, FarmPainter.TIER_LOW);   // seed packets and trowels
   }

   // ------------------------------------------------------------------------------------------------------------ commercial glasshouse

   /**
    * Three or four gabled bays under one roof. A bay is BAY_W wide from furrow to furrow; the furrow columns are shared, so the
    * building is bays * BAY_W + 1 wide. Walls four high (plinth, then glass), roof at 45 degrees two glass blocks thick, ridge at
    * the middle of each bay, a gutter beam along every furrow carried on quartz posts every fourth block, rafters on the same
    * pitch. No walls between the bays. Doors: three wide, into every bay, at the u = 0 end; the far end is shut.
    */
   private void paintBays(FarmPainter p) {
      int len = this.length;
      int w = this.width;
      final int T = 4;
      int bays = this.tunnels;
      // floor: gravel paths down the middle of each bay, bare earth elsewhere
      for (int u = 0; u < len; u++) {
         for (int v = 0; v < w; v++) {
            this.put(p, u, v, 0, (v % BAY_W) == BAY_W / 2 ? FarmPainter.GRAVEL : FarmPainter.DIRT, 0);
         }
      }
      // outer long walls: plinth, quartz posts every four, glass between
      for (int u = 0; u < len; u++) {
         boolean post = u % 4 == 0 || u == len - 1;
         for (int side = 0; side < 2; side++) {
            int v = side == 0 ? 0 : w - 1;
            double m = this.rnd(u, v, 1, 1810);
            this.put(p, u, v, 1, STONE_BRICK_B, m < 0.40 ? 1 : (m < 0.60 ? 2 : 0));
            for (int dy = 2; dy <= T; dy++) {
               if (post) {
                  this.put(p, u, v, dy, QUARTZ, 0);
               } else {
                  this.glass(p, u, v, dy);
               }
            }
         }
      }
      // the furrows: a gutter beam along each one, on posts every fourth block (the bays are open to each other between them)
      for (int b = 1; b < bays; b++) {
         int v = b * BAY_W;
         for (int u = 0; u < len; u++) {
            this.put(p, u, v, T, QUARTZ, 0);
            if (u % 4 == 0 || u == len - 1) {
               for (int dy = 1; dy < T; dy++) {
                  this.put(p, u, v, dy, QUARTZ, 0);
               }
            }
         }
      }
      // the roof, bay by bay: 45 degrees up from each furrow to the ridge, two glass blocks thick so no diagonal gap lets weather in
      for (int u = 0; u < len; u++) {
         boolean rafter = u % 4 == 0 || u == len - 1;
         for (int b = 0; b < bays; b++) {
            for (int k = 1; k <= BAY_W / 2; k++) {
               for (int side = 0; side < 2; side++) {
                  int v = b * BAY_W + (side == 0 ? k : BAY_W - k);
                  boolean ridge = k == BAY_W / 2;
                  for (int dy = T + k - 1; dy <= T + k; dy++) {
                     if (ridge && dy == T + k) {
                        this.put(p, u, v, dy, QUARTZ, 0);                       // the ridge beam
                     } else if (rafter && dy == T + k) {
                        this.put(p, u, v, dy, QUARTZ, 0);
                     } else {
                        this.glass(p, u, v, dy);
                     }
                  }
               }
            }
         }
      }
      // end walls: glass up to the roof's underside, a quartz frame; a three-wide door into every bay at the front end
      for (int end = 0; end < 2; end++) {
         int u = end == 0 ? 0 : len - 1;
         for (int v = 1; v <= w - 2; v++) {
            int vib = v % BAY_W;
            int k = Math.max(1, Math.min(vib, BAY_W - vib));
            int top = T + k - 2;
            boolean door = end == 0 && Math.abs(vib - BAY_W / 2) <= 1;
            boolean furrow = vib == 0;
            for (int dy = 1; dy <= top; dy++) {
               if (door && dy <= 3) {
                  continue;
               }
               boolean frame = vib == BAY_W / 2 || furrow || dy == top || (door && dy == 4);
               if (frame) {
                  this.put(p, u, v, dy, QUARTZ, 0);
               } else if (dy == 1) {
                  this.put(p, u, v, dy, STONE_BRICK_B, 0);
               } else {
                  this.glass(p, u, v, dy);
               }
            }
         }
      }
      // each bay planted with one crop in unbroken rows, the way the tunnels are
      for (int b = 0; b < bays; b++) {
         for (int u = 2; u < len - 1; u++) {
            for (int c = 1; c <= BAY_W - 1; c++) {
               if (c >= BAY_W / 2 - 1 && c <= BAY_W / 2 + 1) {
                  continue;                                                     // the path and its verge
               }
               this.hoopBed(p, u, b * BAY_W + c, b);
            }
         }
      }
      // creeper only where there is something solid for it: up the quartz posts outside, and the plinth
      for (int u = 0; u < len; u++) {
         boolean post = u % 4 == 0 || u == len - 1;
         for (int side = 0; side < 2; side++) {
            int v = side == 0 ? -1 : w;
            if (this.rnd(u, v, 0, 1820) >= (post ? 0.40 : 0.08)) {
               continue;
            }
            int top = post ? 1 + (int) (this.rnd(u, v, 1, 1821) * 3) : 1;
            for (int dy = 1; dy <= top; dy++) {
               this.put(p, u, v, dy, FarmPainter.VINE, this.attach(side == 0));
            }
         }
      }
      // rain barrels at the corners by the doors, and a chest of seed and tools at the back
      this.put(p, 1, 1, 1, FarmPainter.CAULDRON, 3);
      this.put(p, 1, w - 2, 1, FarmPainter.CAULDRON, 3);
      int mid = (bays / 2) * BAY_W + (bays % 2 == 0 ? 0 : BAY_W / 2);
      p.chest(this.wx(len - 2, mid), this.groundY + 1, this.wz(len - 2, mid), this.axisX ? 4 : 2, 71, FarmPainter.TIER_LOW);   // seed and trowels
      if (this.purpose == COLLAPSED_WING) this.paintCollapsedWing(p, len, w, bays);
   }

   /**
    * Propagation houses use raised, accessible seedling benches rather than
    * ordinary field rows. Two long bench lines flank the central service aisle.
    */
   private void propagationBed(FarmPainter p, int u, int v) {
      if (v != 1 && v != this.width - 2) {
         // Narrow auxiliary trays between the main benches.
         if (u % 3 == 1 && (v & 1) == 0) {
            this.put(p, u, v, 1, PLANKS, 1);
            this.put(p, u, v, 2, FARMLAND_B, 7);
            if (this.rnd(u, v, 1, 1761) < 0.78) {
               this.put(p, u, v, 3, (v & 2) == 0 ? FarmPainter.CARROTS : FarmPainter.WHEAT, 1);
            }
         }
         return;
      }
      if (u % 5 == 0) this.put(p, u, v, 1, FarmPainter.FENCE, 0);
      this.put(p, u, v, 2, PLANKS, 1);
      this.put(p, u, v, 3, FARMLAND_B, 7);
      double seedling = this.rnd(u, v, 1, 1762);
      if (seedling < 0.84) {
         int crop = (v == 1) ? FarmPainter.CARROTS : FarmPainter.WHEAT;
         this.put(p, u, v, 4, crop, 1);
      } else if (seedling < 0.94) {
         this.put(p, u, v, 4, FarmPainter.TALL_GRASS, 1);
      }
   }

   /** Separate trial beds with crop rows and occasional sample-marker posts. */
   private void researchBed(FarmPainter p, int u, int v) {
      this.put(p, u, v, 0, FARMLAND_B, 7);
      int trial = Math.floorMod(v + u / 5, 3);
      int crop = trial == 0 ? FarmPainter.CARROTS
              : (trial == 1 ? FarmPainter.POTATOES : FarmPainter.BEETROOTS);
      if (u % 6 == 0 && v % 2 == 1) {
         this.put(p, u, v, 1, FarmPainter.FENCE, 0);
         this.put(p, u, v, 2, FarmPainter.TERRACOTTA, trial);
      } else if (this.rnd(u, v, 1, 1763) < 0.76) {
         this.put(p, u, v, 1, crop, 1);
      }
   }

   /**
    * Enclosed lab/service room in the rear eight blocks of the research
    * greenhouse. The exterior envelope is already closed by paintGabled;
    * this builds an internal divider, work counters and sample storage.
    */
   private void paintResearchRoom(FarmPainter p, int start, int len, int width, int half) {
      int divider = start;
      for (int v = 1; v < width - 1; v++) {
         if (Math.abs(v - half) <= 1) continue; // internal double-width access door
         for (int dy = 1; dy <= 3; dy++) this.put(p, divider, v, dy, WALL, 0);
      }
      // Durable work counters along both sides, with sample cabinets and a
      // clear central aisle. All materials are common blocks.
      for (int u = start + 1; u < len - 1; u++) {
         this.put(p, u, 1, 1, PLANKS, 1);
         this.put(p, u, 1, width - 2, PLANKS, 1);
         if (u % 3 == 0) {
            this.put(p, u, 2, 1, 58, 0);
            this.put(p, u, 2, width - 2, 47, 0);
         }
      }
      for (int v = half - 2; v <= half + 2; v++) {
         if (v == half) continue;
         this.put(p, start + 2, v, 1, WALL, 0);
      }
      this.put(p, start + 3, 1, half, FarmPainter.CAULDRON, 3);
      this.put(p, start + 5, 1, half, FarmPainter.TORCH, 5);
   }

   /**
    * Deliberately collapsed rear wing of the last commercial bay. Missing
    * glazing is confined by glass(); this adds fallen rafters and clustered
    * shards without damaging the two still-operational bays.
    */
   private void paintCollapsedWing(FarmPainter p, int len, int width, int bays) {
      int start = (len * 2) / 3;
      int bayStart = Math.max(1, (bays - 1) * BAY_W + 1);
      int bayEnd = Math.min(width - 2, bays * BAY_W - 1);
      for (int u = start; u < len - 1; u += 3) {
         int v = bayStart + Math.floorMod(u / 3, Math.max(1, bayEnd - bayStart + 1));
         // One fallen roof member lies diagonally across the crop rows.
         for (int step = 0; step < 4 && v + step <= bayEnd; step++) {
            this.put(p, u + Math.min(1, step / 2), v + step, 1 + step / 2, PLANKS, 1);
         }
         for (int step = 0; step < 3 && v + step <= bayEnd; step++) {
            if (this.rnd(u, v + step, 0, 1841) < 0.75) {
               this.put(p, u + 1, v + step, 1, GLASS, 0);
            }
         }
      }
      // Broken roof edges hang down at the transition from the intact bays.
      for (int v = bayStart; v <= bayEnd; v += 3) {
         this.put(p, start, v, 5, PLANKS, 1);
         if ((v & 1) == 0) this.put(p, start + 1, v, 4, PLANKS, 1);
      }
   }

   // ------------------------------------------------------------------------------------------------------------ hoop houses

   private void paintHoops(FarmPainter p) {
      int len = this.length;
      for (int t = 0; t < this.tunnels; t++) {
         int v0 = t * (HOOP_W + HOOP_GAP);
         // floor and a path down the middle of the tunnel
         for (int u = 0; u < len; u++) {
            for (int c = 0; c < HOOP_W; c++) {
               this.put(p, u, v0 + c, 0, FarmPainter.DIRT, 0);
            }
            this.put(p, u, v0 + 3, 0, FarmPainter.GRAVEL, 0);
         }
         // the skin: sheeting over the arch, a common fence frame every third block and sparse metal reinforcement
         for (int u = 0; u < len; u++) {
            boolean hoop = u % 3 == 0 || u == len - 1;                 // steel hoops every third block
            for (int c = 0; c < HOOP_W; c++) {
               for (int dy = HOOP_LOW[c]; dy <= HOOP_TOP[c]; dy++) {
                  if (hoop) {
                     // Common fence framing carries most arches; a sparse
                     // iron-bar rib at the major frames keeps the industrial
                     // silhouette without making each tunnel an iron sink.
                     int frame = (u % 12 == 0 || u == len - 1) ? FarmPainter.IRON_BARS : FarmPainter.FENCE;
                     this.put(p, u, v0 + c, dy, frame, 0);
                  } else {
                     this.glass(p, u, v0 + c, dy);
                  }
               }
            }
         }
         // the far end is closed under the arch (a vertical wall, so panes suit it); the near end stands open
         for (int c = 1; c < HOOP_W - 1; c++) {
            for (int dy = 1; dy < HOOP_LOW[c]; dy++) {
               if (!this.broken(len - 1, v0 + c, dy)) {
                  this.put(p, len - 1, v0 + c, dy, this.endId, this.endData);
               }
            }
         }
         // beds either side of the path, two wide, planted the way the tunnel was: one crop, in unbroken rows
         for (int u = 1; u < len - 1; u++) {
            for (int c = 1; c < HOOP_W - 1; c++) {
               if (c != 3) {
                  this.hoopBed(p, u, v0 + c, t);
               }
            }
         }
         // a rain barrel at the open end in the gap between every other pair of tunnels, where the water is wanted
         if (t < this.tunnels - 1 && t % 2 == 0) {
            this.put(p, 0, v0 + HOOP_W, 0, FarmPainter.DIRT, 0);
            this.put(p, 0, v0 + HOOP_W, 1, FarmPainter.CAULDRON, 3);
         }
      }
   }

   /** What each tunnel was planted with: one crop per tunnel, fixed by the tunnel's place, so the whole row runs the length unbroken. */
   private int[] cropOf(int tunnel) {
      int[][] crops = {{FarmPainter.WHEAT, 7}, {FarmPainter.CARROTS, 7}, {FarmPainter.POTATOES, 7}, {FarmPainter.BEETROOTS, 3}};
      return crops[(int) (FarmRandom.unit(this.seed, this.ox * 31 + tunnel, this.oz, 1752) * crops.length) % crops.length];
   }

   /**
    * One bed column in a tunnel: farmland carrying the tunnel's crop, dead and brown but still standing in its rows. The weeds are the
    * only disorder: a few everywhere, thickening toward the closed end, and a bare gap here and there where a plant died.
    */
   private void hoopBed(FarmPainter p, int u, int v, int tunnel) {
      double along = (double) u / Math.max(1, this.length - 1);
      double weed = this.rnd(u, v, 0, 1750);
      this.put(p, u, v, 0, FARMLAND_B, 7);
      if (weed < 0.05 + 0.22 * along) {
         double kind = this.rnd(u, v, 1, 1751);
         this.put(p, u, v, 0, FarmPainter.GRASS, 0);
         this.put(p, u, v, 1, kind < 0.70 ? FarmPainter.TALL_GRASS : (kind < 0.92 ? FarmPainter.TALL_GRASS : FarmPainter.DEAD_BUSH), kind < 0.70 ? 1 : (kind < 0.92 ? 2 : 0));
      } else if (this.rnd(u, v, 2, 1753) < 0.04) {
         this.put(p, u, v, 0, FarmPainter.DIRT, 1);                       // a plant that died, a bare patch in the row
      } else {
         int[] crop = this.cropOf(tunnel);
         this.put(p, u, v, 1, crop[0], crop[1]);
      }
   }

   // ------------------------------------------------------------------------------------------------------------ shared

   /** One bed column, left to itself: dead crops, grass and ferns pushing up, the odd pumpkin or flower. */
   private void bed(FarmPainter p, int u, int v) {
      if (this.purpose == PROPAGATION) {
         this.propagationBed(p, u, v);
         return;
      }
      if (this.purpose == RESEARCH) {
         this.researchBed(p, u, v);
         return;
      }
      double r = this.rnd(u, v, 0, 1730);
      if (r < 0.30) {
         this.put(p, u, v, 0, FARMLAND_B, 7);
         this.put(p, u, v, 1, FarmPainter.WHEAT, 7);
      } else if (r < 0.48) {
         this.put(p, u, v, 0, FARMLAND_B, 3);
         this.put(p, u, v, 1, FarmPainter.TALL_GRASS, 1);
      } else if (r < 0.58) {
         this.put(p, u, v, 0, FarmPainter.GRASS, 0);
         this.put(p, u, v, 1, FarmPainter.TALL_GRASS, 2);
      } else if (r < 0.66) {
         this.put(p, u, v, 0, FarmPainter.DIRT, 1);
         this.put(p, u, v, 1, FarmPainter.DEAD_BUSH, 0);
      } else if (r < 0.72) {
         this.put(p, u, v, 0, FARMLAND_B, 3);
         this.put(p, u, v, 1, FarmPainter.PUMPKIN, 0);
      } else if (r < 0.80) {
         this.put(p, u, v, 0, FarmPainter.GRASS, 0);
         this.put(p, u, v, 1, this.rnd(u, v, 1, 1731) < 0.5 ? FarmPainter.DANDELION : FarmPainter.RED_ROSE, 0);
      } else if (r < 0.90) {
         this.put(p, u, v, 0, FarmPainter.GRASS, 0);
      } else {
         this.put(p, u, v, 0, FARMLAND_B, 0);                  // dry bare soil
      }
   }
}
