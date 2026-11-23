package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * Large mechanised agribusiness equipment compound. Unlike a reskinned
 * farmhouse these are purpose-built structures with distinct footprints:
 * a grain handling elevator and bin battery, or a wide service/harvester depot.
 * Their placement is reserved by FarmLayout.findSpot, so roofs, aprons and
 * loading positions never intrude into an adjacent field or farm structure.
 * Every generated block is clipped by FarmPainter to its owning chunk.
 */
final class FarmIndustrial extends FarmFeature {
   static final int ELEVATOR = 0;
   static final int DEPOT = 1;
   private final int ox, oz, y, kind;
   private final boolean axisX;
   private final long seed;
   private static final int STONE = FarmPainter.STONE;
   private static final int WALL = FarmPainter.STONE_BRICK;
   private static final int COBBLE = FarmPainter.COBBLE;
   private static final int GRAVEL = FarmPainter.GRAVEL;
   private static final int GLASS = 20;
   private static final int IRON = 42;
   private static final int PLANKS = FarmPainter.PLANKS;
   private static final int FENCE = FarmPainter.FENCE;
   private static final int BARS = FarmPainter.IRON_BARS;
   private static final int WHITE = FarmPainter.TERRACOTTA;

   static int length(int style) { return style == ELEVATOR ? 27 : 29; }
   static int width(int style) { return style == ELEVATOR ? 25 : 23; }

   FarmIndustrial(int x, int z, boolean axisX, int style, int ground, long seed) {
      super(x, z, x + (axisX ? length(style) : width(style)) - 1,
                  z + (axisX ? width(style) : length(style)) - 1);
      this.ox=x; this.oz=z; this.y=ground; this.seed=seed; this.axisX=axisX; this.kind=style;
   }

   private int x(int u, int v) { return axisX ? ox+u : ox+v; }
   private int z(int u, int v) { return axisX ? oz+v : oz+u; }
   private void put(FarmPainter p, int u, int h, int v, int id, int data) { p.set(x(u,v), y+h, z(u,v), id, data); }
   private double rnd(int u, int v, int h, int salt) {
      return FarmRandom.unit(seed, x(u,v)*31 + h*13, z(u,v), salt);
   }
   private void box(FarmPainter p, int u0,int u1,int v0,int v1,int h0,int h1,int id,int data) {
      for (int u=u0;u<=u1;u++) for(int v=v0;v<=v1;v++) for(int h=h0;h<=h1;h++) put(p,u,h,v,id,data);
   }
   private void slab(FarmPainter p, int length, int width) {
      for (int u=0;u<length;u++) for (int v=0;v<width;v++) {
         int id = rnd(u,v,0,61)<0.12 ? GRAVEL : rnd(u,v,0,62)<0.1 ? WALL : STONE;
         put(p,u,0,v,id,0);
         for (int h=1;h<=3;h++) put(p,u,h,v,FarmPainter.AIR,0);
      }
   }

   @Override void paint(FarmPainter p) {
      if (kind == ELEVATOR) elevator(p); else depot(p);
   }

   /** Four different-height storage bins, a tall central elevator head,
    * conveyor gallery, road-facing weighbridge and a roofed loading hopper. */
   private void elevator(FarmPainter p) {
      slab(p,27,25);
      // Access lane through the plant, with a painted concrete weighbridge.
      for (int u=0;u<19;u++) for(int v=9;v<=15;v++) {
         put(p,u,0,v,rnd(u,v,0,70)<0.12 ? GRAVEL : WALL,0);
         if ((v==9||v==15) && u>=3 && u<=13) put(p,u,1,v,BARS,0);
      }
      // Sorting pit and heavy elevator house; upper head overhangs the lower shaft.
      for (int u=17;u<=25;u++) for (int v=8;v<=17;v++) {
         for (int h=1;h<=28;h++) {
            boolean boundary = u==17||u==25||v==8||v==17;
            if (h==28||h==1||boundary) {
               boolean port = (u==17 && v>=11 && v<=14 && h<=6)
                   || (u==25 && v>=10 && v<=13 && h>=19 && h<=22);
               if (!port) {
                  int id = h==28 ? IRON : (h<=3 ? WALL : rnd(u,v,h,71)<0.10 ? WALL : WHITE);
                  int data = id==WHITE ? (rnd(u,v,h,72)<0.10 ? 8 : 0) : 0;
                  put(p,u,h,v,id,data);
               }
            }
         }
      }
      for (int h=8;h<=25;h+=4) {
         for (int v=9;v<=16;v++) {
            put(p,20,h,v,PLANKS,1);
            put(p,21,h,v,PLANKS,1);
         }
      }
      for(int h=2;h<=25;h++) {
         put(p,24,h,10,FarmPainter.LADDER,2);
      }
      // Paired bins, offset to avoid a repetitious perfect grid.
      int[][] bins={{5,4,15},{12,4,19},{5,21,17},{12,21,14}};
      for (int[] b : bins) {
         int cu=b[0], cv=b[1], ht=b[2];
         for(int u=cu-4;u<=cu+4;u++) for(int v=cv-3;v<=cv+3;v++) {
            double radius = (u-cu)*(u-cu)/16.0+(v-cv)*(v-cv)/9.0;
            for(int h=1;h<=ht;h++) {
               if (radius>0.53 && radius<=1.15) {
                  int id = h%5==0 ? IRON : (rnd(u,v,h,73)<0.12 ? WALL : WHITE);
                  put(p,u,h,v,id,id==WHITE ? 8 : 0);
               }
               if (h==ht+1 && radius<=1.15) put(p,u,h,v,IRON,0);
            }
         }
         put(p,cu,ht+2,cv,IRON,0);
         for(int v=cv-2;v<=cv+2;v++) put(p,cu,2,v,GRAVEL,0);
      }
      // Overhead auger catwalk with structural railings; passes above vehicles.
      for (int u=6;u<=24;u++) {
         for(int v=10;v<=12;v++) put(p,u,21,v,IRON,0);
         if(u%3==0) {
            put(p,u,22,10,BARS,0);
            put(p,u,22,12,BARS,0);
         }
      }
      // Loading canopy beside elevator; visible from road, not a generic house.
      for(int u=13;u<=17;u++) for(int v=8;v<=17;v++) {
         if (u==13 && (v==8||v==17)) for(int h=1;h<=8;h++) put(p,u,h,v,IRON,0);
         put(p,u,9,v,IRON,0);
      }
      p.chest(x(22,12),y+2,z(22,12),4,92,FarmPainter.TIER_TOP);
   }

   /** Mechanised workshop and combine shelter: three full-height loading bays,
    * shallow ribbed roof, inspection pit, crane rail and parts-storage mezzanine. */
   private void depot(FarmPainter p) {
      slab(p,29,23);
      for(int u=2;u<=27;u++) for(int v=2;v<=20;v++) {
         boolean front=u==2, back=u==27, side=v==2||v==20;
         for(int h=1;h<=13;h++) {
            if (h==1 && !front) put(p,u,h,v,WALL,0);
            else if(front||back||side) {
               boolean entry=front && v>=4 && v<=18 && ((v-4)%6)<5 && h<=8;
               boolean window=(side && u>4 && u<26 && u%7<=3 && h>=6 && h<=8);
               if(!entry) put(p,u,h,v,window ? GLASS : rnd(u,v,h,82)<0.13 ? WHITE : WALL, window?0:8);
            }
         }
         // Corrugated roof: narrow ridges and taller side gutters.
         int roofH=14 + Math.max(0,3 - Math.abs(v-11)/3);
         put(p,u,roofH,v,(v%3==0 ? IRON : WHITE),8);
         if (u%5==2 && (v==3 || v==19)) for(int h=1;h<roofH;h++) put(p,u,h,v,IRON,0);
      }
      // Three heavy rolling door headers with one jammed half closed.
      for(int v=4;v<=18;v++) {
         if((v-4)%6<=4) {
            put(p,2,10,v,IRON,0);
            if(v>=10 && v<=14) for (int h=8;h<=9;h++) put(p,2,h,v,WHITE,8);
         }
      }
      // Lifting beam and suspended hook above the central service bay.
      for(int u=5;u<=25;u++) {
         for(int v=10;v<=11;v++) put(p,u,11,v,IRON,0);
      }
      for(int h=7;h<=10;h++) put(p,16,h,10,BARS,0);
      // Stained mechanic's pit, with ladder and an open floor near the hoist.
      for(int u=9;u<=18;u++) for(int v=8;v<=13;v++) {
         put(p,u,0,v,GRAVEL,0);
         if(v==8||v==13) put(p,u,1,v,IRON,0);
      }
      // Pallet racks on a raised rear mezzanine with rails.
      for(int u=22;u<=26;u++) for(int v=4;v<=18;v++) {
         put(p,u,7,v,PLANKS,1);
         if(v==4||v==18) put(p,u,8,v,FENCE,0);
         if((u+v)%4==0) for(int h=8;h<=10;h++) put(p,u,h,v,PLANKS,1);
      }
      for(int u=4;u<=8;u++) for(int v=4;v<=7;v++) {
         if(u==4||u==8||v==4||v==7) for(int h=1;h<=3;h++) put(p,u,h,v,PLANKS,1);
      }
      p.chest(x(25,5),y+8,z(25,5),4,94,FarmPainter.TIER_TOP);
      // Parked machinery footprints are fully *inside* the service shed.
      for(int off=0;off<2;off++) {
         int v=5+off*11;
         for(int u=9;u<=15;u++) for(int t=v;t<=v+3;t++) {
            if(u==9||u==15) put(p,u,1,t,STONE,0);
            else if(u>=11 && u<=13) put(p,u,2,t,IRON,0);
         }
         for(int u : new int[]{10,14}) for(int t : new int[]{v,v+3}) put(p,u,1,t,FarmPainter.COAL_BLOCK,0);
      }
   }
}
