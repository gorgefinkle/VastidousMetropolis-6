package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * Purpose-built agricultural infrastructure. Each style has its own operating
 * silhouette and footprint: grain handling, machinery maintenance, produce
 * packing, grain drying, irrigation, poultry production, livestock handling,
 * or organic recovery.
 *
 * These are site facilities rather than recoloured warehouses. FarmLayout
 * reserves the complete footprint and a separate access driveway, so the
 * service aprons and loading areas do not invade neighbouring fields.
 */
final class FarmIndustrial extends FarmFeature {
   static final int ELEVATOR = 0;
   static final int DEPOT = 1;
   static final int PACKHOUSE = 2;
   static final int GRAIN_DRYER = 3;
   static final int PUMPWORKS = 4;
   static final int POULTRY_HALL = 5;
   static final int LIVESTOCK_WORKS = 6;
   static final int ORGANIC_RECOVERY = 7;

   private final int ox, oz, y, kind;
   private final boolean axisX;
   private final long seed;

   private static final int STONE = FarmPainter.STONE;
   private static final int WALL = FarmPainter.STONE_BRICK;
   private static final int COBBLE = FarmPainter.COBBLE;
   private static final int GRAVEL = FarmPainter.GRAVEL;
   private static final int GLASS = 20;
   // Industrial structure is built from common stone/terracotta. Iron bars
   // are limited to railings and small machine details; no iron blocks.
   private static final int FRAME = FarmPainter.STONE_BRICK;
   private static final int PLANKS = FarmPainter.PLANKS;
   private static final int FENCE = FarmPainter.FENCE;
   private static final int BARS = FarmPainter.IRON_BARS;
   private static final int WHITE = FarmPainter.TERRACOTTA;
   private static final int DARK = FarmPainter.COAL_BLOCK;

   static int length(int style) {
      switch (style) {
         case ELEVATOR: return 27;
         case DEPOT: return 29;
         case PACKHOUSE: return 33;
         case GRAIN_DRYER: return 27;
         case PUMPWORKS: return 27;
         case POULTRY_HALL: return 39;
         case LIVESTOCK_WORKS: return 31;
         case ORGANIC_RECOVERY: return 29;
         default: return 29;
      }
   }

   static int width(int style) {
      switch (style) {
         case ELEVATOR: return 25;
         case DEPOT: return 23;
         case PACKHOUSE: return 25;
         case GRAIN_DRYER: return 23;
         case PUMPWORKS: return 25;
         case POULTRY_HALL: return 17;
         case LIVESTOCK_WORKS: return 27;
         case ORGANIC_RECOVERY: return 25;
         default: return 23;
      }
   }

   FarmIndustrial(int x, int z, boolean axisX, int style, int ground, long seed) {
      super(x, z, x + (axisX ? length(style) : width(style)) - 1,
                  z + (axisX ? width(style) : length(style)) - 1);
      this.ox = x;
      this.oz = z;
      this.y = ground;
      this.seed = seed;
      this.axisX = axisX;
      this.kind = style;
   }

   private int x(int u, int v) { return this.axisX ? this.ox + u : this.ox + v; }
   private int z(int u, int v) { return this.axisX ? this.oz + v : this.oz + u; }
   private void put(FarmPainter p, int u, int h, int v, int id, int data) {
      p.set(this.x(u, v), this.y + h, this.z(u, v), id, data);
   }
   private double rnd(int u, int v, int h, int salt) {
      return FarmRandom.unit(this.seed, this.x(u, v) * 31 + h * 13, this.z(u, v), salt);
   }
   private void box(FarmPainter p, int u0, int u1, int v0, int v1, int h0, int h1, int id, int data) {
      for (int u = u0; u <= u1; u++)
         for (int v = v0; v <= v1; v++)
            for (int h = h0; h <= h1; h++) this.put(p, u, h, v, id, data);
   }
   private void slab(FarmPainter p, int length, int width) {
      for (int u = 0; u < length; u++) {
         for (int v = 0; v < width; v++) {
            int id = this.rnd(u, v, 0, 61) < 0.12 ? GRAVEL
                    : this.rnd(u, v, 0, 62) < 0.10 ? WALL : STONE;
            this.put(p, u, 0, v, id, 0);
            for (int h = 1; h <= 3; h++) this.put(p, u, h, v, FarmPainter.AIR, 0);
         }
      }
   }

   @Override
   void paint(FarmPainter p) {
      switch (this.kind) {
         case ELEVATOR: this.elevator(p); break;
         case DEPOT: this.depot(p); break;
         case PACKHOUSE: this.packhouse(p); break;
         case GRAIN_DRYER: this.grainDryer(p); break;
         case PUMPWORKS: this.pumpworks(p); break;
         case POULTRY_HALL: this.poultryHall(p); break;
         case LIVESTOCK_WORKS: this.livestockWorks(p); break;
         case ORGANIC_RECOVERY: this.organicRecovery(p); break;
         default: this.depot(p);
      }
   }

   /** Grain receiving elevator, offset bin battery, weighbridge and auger catwalk. */
   private void elevator(FarmPainter p) {
      this.slab(p, 27, 25);
      for (int u = 0; u < 19; u++) for (int v = 9; v <= 15; v++) {
         this.put(p, u, 0, v, this.rnd(u, v, 0, 70) < 0.12 ? GRAVEL : WALL, 0);
         if ((v == 9 || v == 15) && u >= 3 && u <= 13) this.put(p, u, 1, v, BARS, 0);
      }
      for (int u = 17; u <= 25; u++) for (int v = 8; v <= 17; v++) {
         for (int h = 1; h <= 28; h++) {
            boolean boundary = u == 17 || u == 25 || v == 8 || v == 17;
            if (h == 28 || h == 1 || boundary) {
               boolean port = (u == 17 && v >= 11 && v <= 14 && h <= 6)
                       || (u == 25 && v >= 10 && v <= 13 && h >= 19 && h <= 22);
               if (!port) {
                  int id = h == 28 ? FRAME : (h <= 3 ? WALL : this.rnd(u, v, h, 71) < 0.10 ? WALL : WHITE);
                  int data = id == WHITE ? (this.rnd(u, v, h, 72) < 0.10 ? 8 : 0) : 0;
                  this.put(p, u, h, v, id, data);
               }
            }
         }
      }
      for (int h = 8; h <= 25; h += 4) for (int v = 9; v <= 16; v++) {
         this.put(p, 20, h, v, PLANKS, 1);
         this.put(p, 21, h, v, PLANKS, 1);
      }
      for (int h = 2; h <= 25; h++) this.put(p, 24, h, 10, FarmPainter.LADDER, 2);

      int[][] bins = {{5, 4, 15}, {12, 4, 19}, {5, 21, 17}, {12, 21, 14}};
      for (int[] bin : bins) {
         int cu = bin[0], cv = bin[1], ht = bin[2];
         for (int u = cu - 4; u <= cu + 4; u++) for (int v = cv - 3; v <= cv + 3; v++) {
            double radius = (u - cu) * (u - cu) / 16.0 + (v - cv) * (v - cv) / 9.0;
            for (int h = 1; h <= ht; h++) if (radius > 0.53 && radius <= 1.15) {
               int id = h % 5 == 0 ? FRAME : (this.rnd(u, v, h, 73) < 0.12 ? WALL : WHITE);
               this.put(p, u, h, v, id, id == WHITE ? 8 : 0);
            }
            if (radius <= 1.15) this.put(p, u, ht + 1, v, WALL, 0);
         }
         this.put(p, cu, ht + 2, cv, WALL, 0);
         for (int v = cv - 2; v <= cv + 2; v++) this.put(p, cu, 2, v, GRAVEL, 0);
      }
      for (int u = 6; u <= 24; u++) {
         for (int v = 10; v <= 12; v++) this.put(p, u, 21, v, FRAME, 0);
         if (u % 4 == 0) {
            this.put(p, u, 22, 10, BARS, 0);
            this.put(p, u, 22, 12, BARS, 0);
         }
      }
      for (int u = 13; u <= 17; u++) for (int v = 8; v <= 17; v++) {
         if (u == 13 && (v == 8 || v == 17)) for (int h = 1; h <= 8; h++) this.put(p, u, h, v, FRAME, 0);
         this.put(p, u, 9, v, FRAME, 0);
      }
      p.chest(this.x(22, 12), this.y + 2, this.z(22, 12), 4, 92, FarmPainter.TIER_TOP);
   }

   /** Three-bay combine workshop with service hoist, inspection pit and mezzanine. */
   private void depot(FarmPainter p) {
      this.slab(p, 29, 23);
      for (int u = 2; u <= 27; u++) for (int v = 2; v <= 20; v++) {
         boolean front = u == 2, back = u == 27, side = v == 2 || v == 20;
         for (int h = 1; h <= 13; h++) {
            if (h == 1 && !front) this.put(p, u, h, v, WALL, 0);
            else if (front || back || side) {
               boolean entry = front && v >= 4 && v <= 18 && ((v - 4) % 6) < 5 && h <= 8;
               boolean window = side && u > 4 && u < 26 && u % 7 <= 3 && h >= 6 && h <= 8;
               if (!entry) this.put(p, u, h, v, window ? GLASS : (this.rnd(u, v, h, 82) < 0.13 ? WHITE : WALL), window ? 0 : 8);
            }
         }
         int roofH = 14 + Math.max(0, 3 - Math.abs(v - 11) / 3);
         this.put(p, u, roofH, v, (v % 3 == 0 ? FRAME : WHITE), 8);
         if (u % 5 == 2 && (v == 3 || v == 19)) for (int h = 1; h < roofH; h++) this.put(p, u, h, v, FRAME, 0);
      }
      for (int v = 4; v <= 18; v++) if ((v - 4) % 6 <= 4) {
         this.put(p, 2, 10, v, FRAME, 0);
         if (v >= 10 && v <= 14) for (int h = 8; h <= 9; h++) this.put(p, 2, h, v, WHITE, 8);
      }
      for (int u = 5; u <= 25; u++) for (int v = 10; v <= 11; v++) this.put(p, u, 11, v, FRAME, 0);
      for (int h = 7; h <= 10; h++) this.put(p, 16, h, 10, BARS, 0);
      for (int u = 9; u <= 18; u++) for (int v = 8; v <= 13; v++) {
         this.put(p, u, 0, v, GRAVEL, 0);
         if (v == 8 || v == 13) this.put(p, u, 1, v, WALL, 0);
      }
      for (int u = 22; u <= 26; u++) for (int v = 4; v <= 18; v++) {
         this.put(p, u, 7, v, PLANKS, 1);
         if (v == 4 || v == 18) this.put(p, u, 8, v, FENCE, 0);
         if ((u + v) % 4 == 0) for (int h = 8; h <= 10; h++) this.put(p, u, h, v, PLANKS, 1);
      }
      for (int u = 4; u <= 8; u++) for (int v = 4; v <= 7; v++) {
         if (u == 4 || u == 8 || v == 4 || v == 7) for (int h = 1; h <= 3; h++) this.put(p, u, h, v, PLANKS, 1);
      }
      p.chest(this.x(25, 5), this.y + 8, this.z(25, 5), 4, 94, FarmPainter.TIER_TOP);
      for (int off = 0; off < 2; off++) {
         int v = 5 + off * 11;
         for (int u = 9; u <= 15; u++) for (int t = v; t <= v + 3; t++) {
            if (u == 9 || u == 15) this.put(p, u, 1, t, STONE, 0);
            else if (u >= 11 && u <= 13) this.put(p, u, 2, t, FRAME, 0);
         }
         for (int u : new int[]{10, 14}) for (int t : new int[]{v, v + 3}) this.put(p, u, 1, t, FarmPainter.COAL_BLOCK, 0);
      }
   }

   /** Produce grading, packing and cold-storage plant with two loading docks. */
   private void packhouse(FarmPainter p) {
      this.slab(p, 33, 25);
      for (int u = 2; u <= 30; u++) for (int v = 2; v <= 22; v++) {
         boolean wall = u == 2 || u == 30 || v == 2 || v == 22;
         if (wall) for (int h = 1; h <= 8; h++) {
            boolean dock = u == 2 && ((v >= 5 && v <= 9) || (v >= 15 && v <= 19)) && h <= 6;
            boolean window = (v == 2 || v == 22) && u % 5 <= 2 && h >= 4 && h <= 6;
            if (!dock) this.put(p, u, h, v, window ? GLASS : (h == 1 ? WALL : WHITE), 0);
         }
         int roofH = 9 + (v >= 11 && v <= 13 ? 1 : 0);
         this.put(p, u, roofH, v, v % 4 == 0 ? WALL : FarmPainter.SLAB_STONE, 0);
      }
      // Roof clerestory and the refrigeration plant room at the far end.
      this.box(p, 23, 29, 4, 20, 1, 7, WALL, 0);
      this.box(p, 24, 28, 5, 19, 2, 6, WHITE, 8);
      for (int v = 8; v <= 16; v += 4) {
         this.put(p, 23, 7, v, GLASS, 0);
         this.put(p, 23, 8, v, WALL, 0);
      }
      for (int u = 5; u <= 19; u++) {
         this.put(p, u, 2, 7, PLANKS, 1);
         this.put(p, u, 2, 17, PLANKS, 1);
         this.put(p, u, 3, 7, FENCE, 0);
         this.put(p, u, 3, 17, FENCE, 0);
      }
      // Three grading belts and produce sorting tables.
      for (int v : new int[]{8, 12, 16}) {
         for (int u = 6; u <= 21; u++) {
            this.put(p, u, 1, v, FRAME, 0);
            if (u % 4 == 0) this.put(p, u, 2, v, GLASS, 0);
         }
         for (int u = 8; u <= 20; u += 4) this.put(p, u, 1, v - 1, FarmPainter.CAULDRON, 0);
      }
      // Loading apron, canopy posts, pallet stacks and two receiving ramps.
      for (int v0 : new int[]{5, 15}) {
         for (int u = 0; u <= 5; u++) for (int v = v0; v <= v0 + 4; v++) {
            this.put(p, u, 0, v, GRAVEL, 0);
         }
         for (int v = v0; v <= v0 + 4; v += 4) for (int h = 1; h <= 6; h++) this.put(p, 5, h, v, FRAME, 0);
         for (int v = v0; v <= v0 + 4; v++) this.put(p, 6, 7, v, FarmPainter.SLAB_STONE, 0);
      }
      for (int u = 10; u <= 18; u += 4) for (int v = 4; v <= 6; v++) {
         this.put(p, u, 1, v, PLANKS, 1);
         this.put(p, u, 2, v, PLANKS, 1);
      }
      p.chest(this.x(26, 7), this.y + 1, this.z(26, 7), 4, 95, FarmPainter.TIER_TOP);
   }

   /** Grain dryer and cleaner: twin drying towers, intake hopper and elevated transfer bridge. */
   private void grainDryer(FarmPainter p) {
      this.slab(p, 27, 23);
      // Twin dryer columns on a shared receiving floor.
      int[][] towers = {{7, 6, 16}, {16, 6, 19}};
      for (int[] t : towers) {
         int cu = t[0], cv = t[1], ht = t[2];
         this.box(p, cu - 3, cu + 3, cv - 3, cv + 3, 1, 3, WALL, 0);
         for (int h = 4; h <= ht; h++) for (int u = cu - 3; u <= cu + 3; u++) for (int v = cv - 3; v <= cv + 3; v++) {
            boolean edge = u == cu - 3 || u == cu + 3 || v == cv - 3 || v == cv + 3;
            if (edge && !(h % 4 == 0 && (u == cu - 3 || u == cu + 3))) this.put(p, u, h, v, h % 5 == 0 ? WALL : WHITE, 8);
         }
         for (int u = cu - 2; u <= cu + 2; u++) for (int v = cv - 2; v <= cv + 2; v++) {
            this.put(p, u, ht + 1, v, FarmPainter.SLAB_STONE, 0);
         }
         this.put(p, cu, ht + 2, cv, WALL, 0);
         this.put(p, cu, ht + 3, cv, FRAME, 0);
      }
      // Transfer bridge and bucket elevator leg.
      for (int u = 9; u <= 17; u++) for (int v = 10; v <= 12; v++) this.put(p, u, 18, v, PLANKS, 1);
      for (int h = 2; h <= 18; h++) this.put(p, 24, h, 10, WALL, 0);
      for (int h = 4; h <= 16; h += 3) this.put(p, 23, h, 10, GLASS, 0);
      // Low intake hopper with a loading throat facing the service apron.
      this.box(p, 18, 24, 2, 7, 1, 3, COBBLE, 0);
      for (int u = 19; u <= 23; u++) for (int v = 3; v <= 6; v++) this.put(p, u, 4, v, FRAME, 0);
      for (int u = 20; u <= 22; u++) for (int v = 4; v <= 5; v++) this.put(p, u, 5, v, WHITE, 8);
      for (int u = 0; u <= 15; u++) this.put(p, u, 0, 19, GRAVEL, 0);
      p.chest(this.x(20, 9), this.y + 2, this.z(20, 9), 4, 96, FarmPainter.TIER_TOP);
   }

   /** Irrigation pump and filtration works: pump hall, settling basins and outlet manifold. */
   private void pumpworks(FarmPainter p) {
      this.slab(p, 27, 25);
      // Pump hall.
      for (int u = 3; u <= 17; u++) for (int v = 6; v <= 19; v++) {
         boolean edge = u == 3 || u == 17 || v == 6 || v == 19;
         if (edge) for (int h = 1; h <= 6; h++) {
            boolean door = u == 3 && v >= 11 && v <= 14 && h <= 4;
            if (!door) this.put(p, u, h, v, (v == 6 || v == 19) && h >= 3 ? GLASS : WALL, 0);
         }
         this.put(p, u, 7, v, FarmPainter.SLAB_STONE, 0);
      }
      // Two lined settling basins with water, screens and walkways.
      for (int basin = 0; basin < 2; basin++) {
         int u0 = 19 + basin * 4;
         for (int u = u0; u <= u0 + 3; u++) for (int v = 4; v <= 10; v++) {
            boolean edge = u == u0 || u == u0 + 3 || v == 4 || v == 10;
            this.put(p, u, 0, v, edge ? COBBLE : FarmPainter.WATER, 0);
            if (edge) for (int h = 1; h <= 3; h++) this.put(p, u, h, v, WALL, 0);
         }
         for (int v = 5; v <= 9; v++) this.put(p, u0 + 1, 4, v, BARS, 0);
      }
      // Filter drums and pump units.
      for (int u : new int[]{7, 12}) {
         for (int v = 9; v <= 16; v++) {
            this.put(p, u, 1, v, FRAME, 0);
            this.put(p, u, 2, v, GRAVEL, 0);
         }
         this.put(p, u, 3, 12, GLASS, 0);
         this.put(p, u, 4, 12, WALL, 0);
      }
      // Buried main and two raised irrigation outlets, represented with common masonry.
      for (int u = 0; u <= 24; u++) {
         this.put(p, u, 1, 21, WALL, 0);
         if (u % 4 == 0) this.put(p, u, 2, 21, FarmPainter.SLAB_STONE, 0);
      }
      for (int v = 21; v <= 23; v++) for (int h = 1; h <= 3; h++) this.put(p, 21, h, v, FRAME, 0);
      p.chest(this.x(15, 16), this.y + 1, this.z(15, 16), 4, 97, FarmPainter.TIER_TOP);
   }

   /** Long ventilated poultry production house, with feed lines and fan wall. */
   private void poultryHall(FarmPainter p) {
      this.slab(p, 39, 17);
      for (int u = 2; u <= 36; u++) for (int v = 2; v <= 14; v++) {
         boolean edge = u == 2 || u == 36 || v == 2 || v == 14;
         if (edge) for (int h = 1; h <= 5; h++) {
            boolean entry = u == 2 && ((v >= 5 && v <= 7) || (v >= 10 && v <= 12)) && h <= 3;
            if (!entry) this.put(p, u, h, v, (h == 4 && u % 5 <= 2) ? GLASS : WHITE, 8);
         }
         this.put(p, u, 6, v, FarmPainter.SLAB_STONE, 0);
      }
      // Roof ventilation spine with repeated short exhaust stacks.
      for (int u = 3; u <= 35; u++) {
         this.put(p, u, 7, 8, WALL, 0);
         if (u % 6 == 0) {
            this.put(p, u, 8, 8, BARS, 0);
            this.put(p, u, 9, 8, WALL, 0);
         }
      }
      // Interior feed and drink lines.
      for (int v : new int[]{5, 8, 11}) {
         for (int u = 5; u <= 33; u++) {
            this.put(p, u, 1, v, FRAME, 0);
            if (u % 4 == 0) this.put(p, u, 2, v, FarmPainter.CAULDRON, 0);
         }
      }
      // Large extraction fans on the rear end wall.
      for (int v : new int[]{5, 8, 11}) {
         for (int du = -1; du <= 1; du++) for (int dh = -1; dh <= 1; dh++) {
            this.put(p, 36, 3 + dh, v + du, DARK, 0);
         }
         this.put(p, 36, 3, v, BARS, 0);
      }
      // Feed-bin pads and a low service vestibule.
      for (int v0 : new int[]{3, 11}) {
         for (int u = 30; u <= 35; u++) this.put(p, u, 0, v0, GRAVEL, 0);
         for (int h = 1; h <= 4; h++) this.put(p, 33, h, v0 + 1, WALL, 0);
      }
      p.chest(this.x(5, 12), this.y + 1, this.z(5, 12), 4, 98, FarmPainter.TIER_MID);
   }

   /** Livestock handling works: sorting pens, race, covered crush, weigh station and loading ramp. */
   private void livestockWorks(FarmPainter p) {
      this.slab(p, 31, 27);
      // Holding pens.
      for (int pen = 0; pen < 3; pen++) {
         int u0 = 3 + pen * 8;
         for (int u = u0; u <= u0 + 7; u++) for (int v = 3; v <= 12; v++) {
            if (u == u0 || u == u0 + 7 || v == 3 || v == 12) {
               if (!(u == u0 + 3 && v == 3)) this.put(p, u, 1, v, FENCE, 0);
               if ((u + v) % 5 == 0 && u != u0 && u != u0 + 7) this.put(p, u, 2, v, FENCE, 0);
            }
         }
         for (int u = u0 + 1; u <= u0 + 6; u++) for (int v = 4; v <= 11; v++) {
            if (this.rnd(u, v, 0, 1101) < 0.10) this.put(p, u, 0, v, GRAVEL, 0);
         }
      }
      // Narrow race that funnels from the pens to a covered handling crush.
      for (int u = 5; u <= 25; u++) {
         this.put(p, u, 1, 16, FENCE, 0);
         this.put(p, u, 1, 20, FENCE, 0);
         if (u % 4 == 0) {
            this.put(p, u, 2, 16, FENCE, 0);
            this.put(p, u, 2, 20, FENCE, 0);
         }
      }
      for (int u = 5; u <= 25; u++) for (int v = 17; v <= 19; v++) this.put(p, u, 0, v, GRAVEL, 0);
      // Roofed crush/weigh station.
      for (int u = 20; u <= 28; u++) for (int v = 14; v <= 22; v++) {
         if ((u == 20 || u == 28) && (v == 14 || v == 22)) for (int h = 1; h <= 7; h++) this.put(p, u, h, v, FRAME, 0);
         this.put(p, u, 8, v, FarmPainter.SLAB_STONE, 0);
      }
      this.box(p, 23, 25, 16, 20, 1, 4, WALL, 0);
      for (int v = 16; v <= 20; v++) this.put(p, 24, 5, v, BARS, 0);
      // Raised loading ramp at the far side.
      for (int u = 26; u <= 30; u++) for (int v = 16; v <= 20; v++) {
         this.put(p, u, u - 24, v, (u == 30 ? WALL : GRAVEL), 0);
      }
      p.chest(this.x(26, 7), this.y + 1, this.z(26, 7), 4, 99, FarmPainter.TIER_MID);
   }

   /** Compost bays and anaerobic digesters with a compact control kiosk. */
   private void organicRecovery(FarmPainter p) {
      this.slab(p, 29, 25);
      // Three concrete-walled compost bays with open loading fronts.
      for (int bay = 0; bay < 3; bay++) {
         int u0 = 2 + bay * 8;
         for (int u = u0; u <= u0 + 7; u++) for (int v = 3; v <= 12; v++) {
            boolean wall = u == u0 || u == u0 + 7 || v == 12;
            if (wall) for (int h = 1; h <= 4; h++) this.put(p, u, h, v, h == 1 ? COBBLE : WALL, 0);
            if (!wall && v >= 5 && this.rnd(u, v, 0, 1201) < 0.55) this.put(p, u, 1, v, FarmPainter.DIRT, 1);
         }
      }
      // Two digesters, built as blocky tanks with domed caps and accessible walkways.
      int[][] tanks = {{9, 19}, {19, 19}};
      for (int[] t : tanks) {
         int cu = t[0], cv = t[1];
         for (int u = cu - 4; u <= cu + 4; u++) for (int v = cv - 4; v <= cv + 4; v++) {
            double d = (u - cu) * (u - cu) + (v - cv) * (v - cv);
            if (d <= 16 && d >= 10) for (int h = 1; h <= 7; h++) this.put(p, u, h, v, h == 1 ? COBBLE : WHITE, 8);
            if (d <= 16 && hCap(d)) this.put(p, u, 8, v, FarmPainter.SLAB_STONE, 0);
         }
         this.put(p, cu, 9, cv, FRAME, 0);
         for (int u = cu - 5; u <= cu + 5; u++) this.put(p, u, 1, cv + 5, WALL, 0);
      }
      // Gas manifold to a modest flare stack and a service/control hut.
      for (int u = 12; u <= 24; u++) {
         this.put(p, u, 8, 16, FRAME, 0);
         if (u % 3 == 0) this.put(p, u, 9, 16, BARS, 0);
      }
      this.box(p, 22, 27, 2, 7, 1, 4, WALL, 0);
      for (int u = 23; u <= 26; u++) {
         this.put(p, u, 5, 4, GLASS, 0);
         this.put(p, u, 6, 4, FarmPainter.SLAB_STONE, 0);
      }
      for (int h = 5; h <= 10; h++) this.put(p, 25, h, 3, FRAME, 0);
      this.put(p, 25, 11, 3, DARK, 0);
      p.chest(this.x(23, 6), this.y + 1, this.z(23, 6), 4, 100, FarmPainter.TIER_TOP);
   }

   private static boolean hCap(double d) {
      return d <= 8;
   }
}
