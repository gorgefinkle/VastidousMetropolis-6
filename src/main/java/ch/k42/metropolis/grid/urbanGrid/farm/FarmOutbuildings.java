package ch.k42.metropolis.grid.urbanGrid.farm;

import ch.k42.metropolis.plugin.PluginConfig;

/**
 * The smaller things a working farm collects and an abandoned one keeps: an open pole barn, three kinds of shed, a water-pumping
 * windmill, a rusted pickup. Each is a FarmFeature in its own frame (u along its length, v across, dy above the ground block);
 * everything is a pure function of the seed and the position, so chunks come out the same in any order, and each paints only its own
 * chunk.
 *
 * Damage follows the farm's ruin setting (generator.farm.ruinPercent) and comes in clusters, as on the barns: a patch of roof sheets
 * goes, not one block here and there.
 */
final class FarmOutbuildings {
   private FarmOutbuildings() {
   }

   /** The shared frame: origin, orientation, helpers for positions, damage, vines and the roof profile of small buildings. */
   abstract static class Oriented extends FarmFeature {
      final int ox;
      final int oz;
      final boolean axisX;
      final int h;
      final long seed;
      final double ruin;

      Oriented(int minX, int minZ, int maxX, int maxZ, int ox, int oz, boolean axisX, int h, long seed, int salt) {
         super(minX, minZ, maxX, maxZ);
         this.ox = ox;
         this.oz = oz;
         this.axisX = axisX;
         this.h = h;
         this.seed = seed;
         double base = PluginConfig.getFarmRuinPercent() / 100.0;
         this.ruin = Math.max(0.0, Math.min(0.95, base * (0.55 + 0.9 * FarmRandom.unit(seed, ox, oz, salt))));
      }

      int wx(int u, int v) { return this.axisX ? this.ox + u : this.ox + v; }

      int wz(int u, int v) { return this.axisX ? this.oz + v : this.oz + u; }

      double rnd(int u, int v, int dy, int salt) {
         return FarmRandom.unit(this.seed, this.wx(u, v) * 7 + dy * 1013, this.wz(u, v), salt);
      }

      void put(FarmPainter p, int u, int v, int dy, int id, int data) {
         p.set(this.wx(u, v), this.h + dy, this.wz(u, v), id, data);
      }

      /** Damage comes in patches four blocks across. */
      boolean hole(int u, int v, double share, int salt) {
         return FarmRandom.unit(this.seed, Math.floorDiv(this.wx(u, v), 4), Math.floorDiv(this.wz(u, v), 4), salt) < this.ruin * share;
      }

      /** Vine data for a vine beside a wall: the face it clings to. wall toward +v / -v / +u / -u. */
      int clingV(boolean wallPlusV) { return this.axisX ? (wallPlusV ? 1 : 4) : (wallPlusV ? 8 : 2); }

      int clingU(boolean wallPlusU) { return this.axisX ? (wallPlusU ? 8 : 2) : (wallPlusU ? 1 : 4); }

      /** Chest data: lid toward +u, -u, +v, -v. 2 north, 3 south, 4 west, 5 east. */
      int faceU(boolean plus) { return this.axisX ? (plus ? 5 : 4) : (plus ? 3 : 2); }

      int faceV(boolean plus) { return this.axisX ? (plus ? 3 : 2) : (plus ? 5 : 4); }

      /**
       * A shallow gable over a small building: k counts columns in from the outside edge of the eave (k = 0 is the overhang, one
       * block outside the wall). Even k is a full block at wallTop + k/2, odd k a bottom slab at wallTop + (k+1)/2, so the surface
       * climbs half a block per column and the first column over the wall clears the wall's top.
       */
      static int roofDy(int wallTop, int k) { return k % 2 == 0 ? wallTop + k / 2 : wallTop + (k + 1) / 2; }
   }

   // ================================================================================================================ pole barn

   /**
    * An open-sided pole barn: a row of posts down each long side under a low roof of rusty sheeting, one side closed in with boards,
    * the gable ends boarded up in the peak. The front half is empty floor with an old hay wagon; the back half has hay stacked
    * against the closed wall. A chest in the hay holds what was left (low loot).
    */
   static final class PoleBarn extends Oriented {
      private final int length;
      private final int width = 11;
      private final int T = 5;
      private final boolean closedPlusV;
      private final int sheet;       // roof sheeting: acacia planks (orange rust) or dark oak (brown)

      static int lengthOf(int variant) { return 20 + 6 * Math.max(0, Math.min(2, variant)); }

      PoleBarn(int ox, int oz, boolean axisX, int variant, int h, long seed) {
         super(ox - 1, oz - 1, ox + (axisX ? lengthOf(variant) : 11), oz + (axisX ? 11 : lengthOf(variant)), ox, oz, axisX, h, seed, 1400);
         this.length = lengthOf(variant);
         this.closedPlusV = FarmRandom.unit(seed, ox, oz, 1401) < 0.5;
         this.sheet = FarmRandom.unit(seed, ox, oz, 1402) < 0.6 ? 4 : 5;
      }

      @Override
      void paint(FarmPainter p) {
         int len = this.length;
         int w = this.width;
         int c = (w - 1) / 2;
         int wallV = this.closedPlusV ? w - 1 : 0;
         for (int u = -1; u <= len; u++) {
            for (int v = -1; v <= w; v++) {
               boolean inside = u >= 0 && u < len && v >= 0 && v < w;
               if (inside) {
                  double f = this.rnd(u, v, 0, 1410);
                  this.put(p, u, v, 0, f < 0.28 ? FarmPainter.DIRT : (f < 0.40 ? FarmPainter.GRAVEL : FarmPainter.DIRT), f < 0.28 ? 1 : 0);
               }
               // roof: over every column from the overhang on one side to the overhang on the other
               int k = Math.min(v + 1, w - v);
               int dy = roofDy(this.T, k);
               boolean slab = k % 2 == 1;
               if (!this.hole(u, v, 0.55, 1411) && this.rnd(u, v, 0, 1412) > 0.03) {
                  this.put(p, u, v, dy, slab ? FarmPainter.SLAB_WOOD : FarmPainter.PLANKS, this.sheet);
               }
               if (v == c && !this.hole(u, v, 0.55, 1411)) {
                  this.put(p, u, v, dy + 1, FarmPainter.SLAB_WOOD, 5);                // a ridge capping
               }
            }
         }
         // posts down both long sides, with a tie beam across the building on each pair
         for (int u = 0; u < len; u++) {
            boolean postLine = u % 5 == 0 || u == len - 1;
            if (!postLine) {
               continue;
            }
            for (int side = 0; side < 2; side++) {
               int v = side == 0 ? 0 : w - 1;
               for (int dy = 1; dy <= this.T; dy++) {
                  this.put(p, u, v, dy, FarmPainter.LOG, this.rnd(u, v, dy, 1420) < 0.15 ? 1 : 0);
               }
               if (this.rnd(u, v, 0, 1421) < 0.2) {
                  for (int dy = 1; dy <= 3; dy++) {
                     this.put(p, u, side == 0 ? -1 : w, dy, FarmPainter.VINE, this.clingV(side == 0));
                  }
               }
            }
            for (int v = 1; v < w - 1; v++) {
               this.put(p, u, v, this.T, FarmPainter.LOG, this.axisX ? 8 : 4);           // tie beam, across
            }
         }
         // the closed side: boards between the posts, a third of them gone
         for (int u = 0; u < len; u++) {
            for (int dy = 1; dy <= this.T; dy++) {
               if (this.rnd(u, wallV, dy, 1430) < 0.30 || this.hole(u, wallV, 0.5, 1431) && dy > 2) {
                  continue;
               }
               if (u % 5 != 0 && u != len - 1) {
                  this.put(p, u, wallV, dy, FarmPainter.PLANKS, 1);
               }
            }
         }
         // the gable ends boarded up in the peak, as far as the roof's underside
         for (int end = 0; end < 2; end++) {
            int u = end == 0 ? 0 : len - 1;
            for (int v = 1; v <= w - 2; v++) {
               int k = Math.min(v + 1, w - v);
               int top = roofDy(this.T, k) - 1;
               for (int dy = this.T + 1; dy <= top; dy++) {
                  if (this.rnd(u, v, dy, 1432) >= 0.25) {
                     this.put(p, u, v, dy, FarmPainter.PLANKS, 1);
                  }
               }
            }
         }
         // hay stacked against the closed wall in the back half: highest at the wall, thinning out toward the open side
         for (int u = len / 2 + 1; u < len - 1; u++) {
            for (int depth = 1; depth <= 4; depth++) {
               int v = this.closedPlusV ? w - 1 - depth : depth;
               int stack = 4 - depth;
               for (int dy = 1; dy <= stack; dy++) {
                  if (this.rnd(u, v, dy, 1440) < 0.88) {
                     this.put(p, u, v, dy, FarmPainter.HAY, (u + dy) % 3 == 0 ? 4 : 0);
                  }
               }
            }
         }
         // an old hay wagon in the front half: a plank bed on four wheels with a few bales left on it
         int wv = this.closedPlusV ? 2 : w - 5;
         for (int u = 4; u <= 9; u++) {
            for (int v = wv; v <= wv + 2; v++) {
               this.put(p, u, v, 2, FarmPainter.PLANKS, 1);
            }
         }
         for (int u : new int[]{5, 8}) {
            for (int v : new int[]{wv, wv + 2}) {
               this.put(p, u, v, 1, FarmPainter.COAL_BLOCK, 0);
            }
         }
         for (int u = 6; u <= 9; u++) {
            for (int v = wv; v <= wv + 2; v++) {
               if (this.rnd(u, v, 3, 1441) < 0.55) {
                  this.put(p, u, v, 3, FarmPainter.HAY, 0);
               }
            }
         }
         // what was left in the hay: a chest on the floor at the end wall just in front of the stack (the fifth block out from the closed
         // wall), its lid toward the open side
         int cv = this.closedPlusV ? w - 1 - 5 : 5;
         p.chest(this.wx(len - 2, cv), this.h + 1, this.wz(len - 2, cv), this.faceV(!this.closedPlusV), 51, FarmPainter.TIER_LOW);
      }
   }

   // ================================================================================================================ sheds

   /**
    * A small outbuilding. kind 0: a chicken coop on stilts with a wire run beside it; kind 1: a slatted corn crib on stone piers;
    * kind 2: a tool shed with a workbench and a chest of rusty tools. Boards weathered to one of four timbers.
    */
   static final class Shed extends Oriented {
      static final int COOP = 0;
      static final int CRIB = 1;
      static final int TOOL = 2;

      private final int kind;
      private final int length;
      private final int width;
      private final int plank;       // 5 data: oak, spruce, acacia, dark oak
      private final int roofData;

      static int lengthOf(int kind) { return kind == COOP ? 9 : (kind == CRIB ? 11 : 7); }

      static int widthOf(int kind) { return kind == COOP ? 5 : (kind == CRIB ? 5 : 5); }

      Shed(int ox, int oz, boolean axisX, int kind, int h, long seed) {
         super(ox - 1, oz - 1, ox + (axisX ? lengthOf(kind) : widthOf(kind)), oz + (axisX ? widthOf(kind) : lengthOf(kind)),
                 ox, oz, axisX, h, seed, 1500);
         this.kind = kind;
         this.length = lengthOf(kind);
         this.width = widthOf(kind);
         int[] timbers = {0, 1, 4, 5};
         this.plank = timbers[(int) (FarmRandom.unit(seed, ox, oz, 1501) * 4) % 4];
         this.roofData = this.plank == 5 ? 5 : (this.plank == 1 ? 1 : 0);
      }

      @Override
      void paint(FarmPainter p) {
         if (this.kind == COOP) {
            this.coop(p);
         } else if (this.kind == CRIB) {
            this.crib(p);
         } else {
            this.tool(p);
         }
      }

      /** A gable roof over a w-wide building whose walls are wallTop high, from u0 to u1, with the half-block steps of roofDy(). */
      private void gable(FarmPainter p, int u0, int u1, int wallTop, int salt) {
         int w = this.width;
         for (int u = u0 - 1; u <= u1 + 1; u++) {
            for (int v = -1; v <= w; v++) {
               int k = Math.min(v + 1, w - v);
               boolean slab = k % 2 == 1;
               if (this.hole(u, v, 0.6, salt) || this.rnd(u, v, 0, salt + 1) < 0.02) {
                  continue;
               }
               this.put(p, u, v, roofDy(wallTop, k), slab ? FarmPainter.SLAB_WOOD : FarmPainter.PLANKS, this.roofData);
            }
         }
      }

      private void walls(FarmPainter p, int u0, int u1, int v0, int v1, int dy0, int dy1, int salt, double missing) {
         for (int u = u0; u <= u1; u++) {
            for (int v = v0; v <= v1; v++) {
               if (u != u0 && u != u1 && v != v0 && v != v1) {
                  continue;
               }
               for (int dy = dy0; dy <= dy1; dy++) {
                  boolean corner = (u == u0 || u == u1) && (v == v0 || v == v1);
                  if (!corner && (this.rnd(u, v, dy, salt) < missing || this.hole(u, v, 0.35, salt + 2) && dy >= dy0 + 1)) {
                     continue;
                  }
                  this.put(p, u, v, dy, corner ? FarmPainter.LOG : FarmPainter.PLANKS, corner ? 0 : this.plank);
               }
            }
         }
      }

      // ---- a chicken coop with its run

      private void coop(FarmPainter p) {
         int w = this.width;
         int c = (w - 1) / 2;
         // the house, u 0..3, raised one block on posts
         for (int v = 0; v < w; v += w - 1) {
            for (int u = 0; u <= 3; u += 3) {
               this.put(p, u, v, 1, FarmPainter.LOG, 0);
            }
         }
         for (int u = 0; u <= 3; u++) {
            for (int v = 0; v < w; v++) {
               this.put(p, u, v, 2, FarmPainter.PLANKS, this.plank);                          // the floor
            }
         }
         this.walls(p, 0, 3, 0, w - 1, 3, 4, 1510, 0.12);
         for (int v = 1; v <= w - 2; v++) {
            this.put(p, 0, v, 3, FarmPainter.AIR, 0);
         }
         this.put(p, 0, c, 3, FarmPainter.AIR, 0);                                              // the pop door
         this.put(p, 0, c, 2, FarmPainter.LADDER, this.axisX ? 5 : 3);                           // and its ramp, a ladder lying against it
         this.gable(p, 0, 3, 4, 1512);
         this.put(p, 2, 1, 3, FarmPainter.HAY, 0);                                              // a nest box
         this.put(p, 2, w - 2, 3, FarmPainter.HAY, 0);
         // the run: a fence of wire (iron bars) two high round bare earth, with a gap where the gate was
         for (int u = 4; u <= 8; u++) {
            for (int v = 0; v < w; v++) {
               this.put(p, u, v, 0, FarmPainter.DIRT, 1);
               boolean edge = u == 8 || v == 0 || v == w - 1;
               if (edge && !(u == 8 && v == c)) {
                  if (this.rnd(u, v, 0, 1513) >= 0.15) {
                     this.put(p, u, v, 1, FarmPainter.FENCE, 0);
                     if (this.rnd(u, v, 1, 1514) < 0.6) {
                        this.put(p, u, v, 2, FarmPainter.IRON_BARS, 0);
                     }
                  }
               }
            }
         }
         this.put(p, 6, c, 1, FarmPainter.CAULDRON, 0);                                         // a dry feeder
      }

      // ---- a slatted corn crib on piers

      private void crib(FarmPainter p) {
         int len = this.length;
         int w = this.width;
         for (int u = 0; u < len; u += 3) {
            for (int v = 0; v < w; v += w - 1) {
               this.put(p, u, v, 1, FarmPainter.STONE_BRICK, this.rnd(u, v, 1, 1520) < 0.4 ? 1 : 0);    // piers
            }
         }
         for (int u = 0; u < len; u++) {
            for (int v = 0; v < w; v++) {
               this.put(p, u, v, 2, FarmPainter.PLANKS, this.plank);                          // the floor
            }
         }
         // slatted sides: the gaps between the slats are what lets the corn dry, and what lets the rain through
         for (int u = 0; u < len; u++) {
            for (int v = 0; v < w; v++) {
               if (u != 0 && u != len - 1 && v != 0 && v != w - 1) {
                  continue;
               }
               for (int dy = 3; dy <= 5; dy++) {
                  boolean post = ((u == 0 || u == len - 1) && (v == 0 || v == w - 1));
                  if (post) {
                     this.put(p, u, v, dy, FarmPainter.LOG, 0);
                  } else if (this.rnd(u, v, dy, 1521) >= 0.14 && !(this.hole(u, v, 0.35, 1522) && dy == 5)) {
                     this.put(p, u, v, dy, FarmPainter.FENCE, 0);
                  }
               }
            }
         }
         this.gable(p, 0, len - 1, 5, 1523);
         // what is left of the last crop, rotten and black, heaped against one end
         for (int u = 1; u <= 3; u++) {
            for (int v = 1; v < w - 1; v++) {
               if (this.rnd(u, v, 3, 1524) < 0.7) {
                  this.put(p, u, v, 3, FarmPainter.HAY, 0);
               }
            }
         }
      }

      // ---- a tool shed

      private void tool(FarmPainter p) {
         int len = this.length;
         int w = this.width;
         int c = (w - 1) / 2;
         for (int u = 0; u < len; u++) {
            for (int v = 0; v < w; v++) {
               this.put(p, u, v, 0, FarmPainter.DIRT, 0);
               if (this.rnd(u, v, 0, 1530) < 0.25) {
                  this.put(p, u, v, 0, FarmPainter.PLANKS, this.plank);
               }
            }
         }
         this.walls(p, 0, len - 1, 0, w - 1, 1, 3, 1531, 0.10);
         for (int dy = 1; dy <= 2; dy++) {
            this.put(p, 0, c, dy, FarmPainter.AIR, 0);                                           // the door, long gone
         }
         if (this.rnd(0, 0, 0, 1532) < 0.8) {
            this.put(p, len / 2, 0, 2, FarmPainter.GLASS_PANE, 0);                               // a window, if the glass survived
         }
         this.gable(p, 0, len - 1, 3, 1533);
         this.put(p, len - 2, 1, 1, 58, 0);                                                      // workbench
         this.put(p, len - 2, w - 2, 1, FarmPainter.CAULDRON, 0);
         this.put(p, len - 2, c, 1, 61, 0);                                                      // an old stove for the winter
         p.chest(this.wx(len - 3, 1), this.h + 1, this.wz(len - 3, 1), this.faceV(true), 52, FarmPainter.TIER_LOW);
      }
   }

   // ================================================================================================================ windmill

   /**
    * A water-pumping windmill: a four-legged lattice tower that narrows as it rises, a platform with the gearbox, a rotor of eight
    * iron-bar vanes facing into the wind, a tail vane behind it, and at the foot a stone-brick cistern with a trough. Some vanes
    * are missing and the steel is rust-streaked, but it is standing.
    */
   static final class Windmill extends Oriented {
      private final boolean faceMinus;      // which way the rotor looks along u

      Windmill(int cx, int cz, boolean axisX, int h, long seed) {
         super(cx - 6, cz - 6, cx + 6, cz + 6, cx - 3, cz - 3, axisX, h, seed, 1600);      // tower, rotor, tail and cistern all lie within six of the middle
         this.faceMinus = FarmRandom.unit(seed, cx, cz, 1601) < 0.5;
      }

      @Override
      void paint(FarmPainter p) {
         // local frame centred on the tower: (u, v) in -3..+3 from the origin corner, centre at (3, 3)
         int cu = 3;
         int cv = 3;
         // four legs, leaning in once, with a ring of cross braces at each stage
         for (int dy = 1; dy <= 14; dy++) {
            int r = dy <= 6 ? 2 : (dy <= 11 ? 1 : 1);
            for (int su = -1; su <= 1; su += 2) {
               for (int sv = -1; sv <= 1; sv += 2) {
                  boolean rusted = this.rnd(cu + su * r, cv + sv * r, dy, 1610) < 0.15;
                  this.put(p, cu + su * r, cv + sv * r, dy, rusted ? FarmPainter.FENCE : FarmPainter.IRON_BARS, 0);
               }
            }
            boolean brace = dy == 3 || dy == 6 || dy == 9 || dy == 12;
            if (brace) {
               for (int d = -r; d <= r; d++) {
                  for (int e = -r; e <= r; e += 2 * r) {
                     if (this.rnd(cu + d, cv + e, dy, 1611) < 0.9) {
                        this.put(p, cu + d, cv + e, dy, FarmPainter.IRON_BARS, 0);
                        this.put(p, cu + e, cv + d, dy, FarmPainter.IRON_BARS, 0);
                     }
                  }
               }
            }
         }
         // an inner shaft down the middle (the pump rod), a ladder up one leg face
         for (int dy = 1; dy <= 14; dy++) {
            this.put(p, cu, cv, dy, FarmPainter.FENCE, 0);
         }
         // the platform and gearbox
         for (int du = -1; du <= 1; du++) {
            for (int dv = -1; dv <= 1; dv++) {
               this.put(p, cu + du, cv + dv, 15, FarmPainter.SLAB_STONE, 0);
            }
         }
         this.put(p, cu, cv, 16, 42, 0);                                                   // iron gearbox
         // the rotor: a wheel of eight vanes in the vertical plane across the wind, two blocks in front of the gearbox
         int face = this.faceMinus ? -1 : 1;
         int ru = cu + 2 * face;
         int cy = 16;
         int[][] spokes = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
         for (int s = 0; s < spokes.length; s++) {
            for (int r = 1; r <= 4; r++) {
               if (this.rnd(s, r, 0, 1620 + s) < 0.10 && r >= 3) {
                  break;                                                                     // a vane snapped off
               }
               this.put(p, ru, cv + spokes[s][0] * r, cy + spokes[s][1] * r, FarmPainter.IRON_BARS, 0);
            }
         }
         this.put(p, ru, cv, cy, 42, 0);                                                      // the hub
         this.put(p, cu + face, cv, cy, FarmPainter.IRON_BARS, 0);                           // the shaft to it
         // the tail: a boom away from the rotor and a vane at its end
         for (int k = 1; k <= 4; k++) {
            this.put(p, cu - k * face, cv, cy, FarmPainter.IRON_BARS, 0);
         }
         for (int dy = cy; dy <= cy + 2; dy++) {
            this.put(p, cu - 4 * face, cv, dy, FarmPainter.IRON_BARS, 0);
         }
         // the cistern and trough at the foot, off to one side so the tower stands clear
         for (int du = 0; du < 4; du++) {
            for (int dv = 0; dv < 3; dv++) {
               int u = cu - 6 + du;
               int v = cv + 4 + dv;
               this.put(p, u, v, 1, FarmPainter.STONE_BRICK, this.rnd(u, v, 1, 1630) < 0.4 ? 1 : 0);
               if (du == 0 || du == 3 || dv == 0 || dv == 2) {
                  this.put(p, u, v, 2, FarmPainter.STONE_BRICK, this.rnd(u, v, 2, 1631) < 0.4 ? 1 : 0);
               } else {
                  this.put(p, u, v, 2, FarmPainter.WATER, 0);
               }
            }
         }
         this.put(p, cu - 4, cv + 3, 3, FarmPainter.IRON_BARS, 0);                           // the pipe down from the pump
      }
   }

   // ================================================================================================================ pickup

   }
