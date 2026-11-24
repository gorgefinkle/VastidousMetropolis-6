package ch.k42.metropolis.grid.urbanGrid.farm;

/**
 * A sealed, abandoned missile silo: a 10-block internal shaft, roughly
 * thirty-four blocks deep, with a closed heavy hatch and the missile still inside.
 */
final class FarmMissileSilo extends FarmFeature {
    private final int cx,cz,h;
    private final long seed;

    FarmMissileSilo(int cx,int cz,int h,long seed) {
        super(cx-9,cz-9,cx+9,cz+9);
        this.cx=cx;this.cz=cz;this.h=h;this.seed=seed;
    }

    @Override void paint(FarmPainter p) {
        int bottom=this.h-34, top=this.h-1;
        for(int dx=-7;dx<=7;dx++) for(int dz=-7;dz<=7;dz++) {
            double d=Math.hypot(dx,dz);
            if(d>7.2) continue;
            boolean outer=d>=6.0, inner=d>=5.0&&d<6.0;
            for(int y=bottom;y<=top;y++) {
                if(outer) {
                    int material=((y-bottom)%7==0 || FarmRandom.unit(this.seed,this.cx+dx,this.cz+dz,y+900)<.035)?FarmPainter.MOSSY_COBBLE:FarmPainter.STONE_BRICK;
                    p.set(this.cx+dx,y,this.cz+dz,material,0);
                } else if(inner) {
                    p.set(this.cx+dx,y,this.cz+dz,FarmPainter.COBBLE,0);
                } else {
                    p.set(this.cx+dx,y,this.cz+dz,FarmPainter.AIR,0);
                }
            }
            p.set(this.cx+dx,bottom-1,this.cz+dz,FarmPainter.STONE,0);
        }
        // A suspended three-block-diameter missile leaves a generous
        // maintenance annulus inside the ten-block shaft. The fuselage is a
        // narrow circular section, not a square pillar filling the chamber.
        for(int y=bottom+7;y<=this.h-10;y++) {
            p.set(this.cx,y,this.cz,FarmPainter.TERRACOTTA,8);
            p.set(this.cx+1,y,this.cz,FarmPainter.TERRACOTTA,8);
            p.set(this.cx-1,y,this.cz,FarmPainter.TERRACOTTA,8);
            p.set(this.cx,y,this.cz+1,FarmPainter.TERRACOTTA,8);
            p.set(this.cx,y,this.cz-1,FarmPainter.TERRACOTTA,8);
        }
        // Four restrained stabilizer fins and an engine skirt sit low in the
        // shaft, well clear of the silo wall and service cradle.
        for(int y=bottom+5;y<=bottom+8;y++) {
            for(int dx=-2;dx<=2;dx++) {
                p.set(this.cx+dx,y,this.cz,FarmPainter.TERRACOTTA,7);
                p.set(this.cx,y,this.cz+dx,FarmPainter.TERRACOTTA,7);
            }
        }
        // Tapered nose cone, kept below the closed upper hatch.
        for(int y=this.h-9;y<=this.h-4;y++) {
            int radius=y>=this.h-6?0:1;
            p.set(this.cx,y,this.cz,FarmPainter.TERRACOTTA,14);
            if(radius==1) {
                p.set(this.cx+1,y,this.cz,FarmPainter.TERRACOTTA,14);
                p.set(this.cx-1,y,this.cz,FarmPainter.TERRACOTTA,14);
                p.set(this.cx,y,this.cz+1,FarmPainter.TERRACOTTA,14);
                p.set(this.cx,y,this.cz-1,FarmPainter.TERRACOTTA,14);
            }
        }
        // Narrow service cradle cross-members are structural, not part of the
        // missile silhouette.
        for(int y=bottom+4;y<=bottom+5;y++) for(int dx=-3;dx<=3;dx+=2) {
            p.set(this.cx+dx,y,this.cz-3,FarmPainter.IRON_BARS,0);
            p.set(this.cx+dx,y,this.cz+3,FarmPainter.IRON_BARS,0);
        }
        // Heavy, closed surface hatch flush with a weathered concrete pad.
        for(int dx=-6;dx<=6;dx++) for(int dz=-6;dz<=6;dz++) {
            if(dx*dx+dz*dz<=42 && (dx!=0||dz!=0))
                p.set(this.cx+dx,this.h,this.cz+dz,FarmPainter.COBBLE,0);
        }
        // Raised reinforced collar surrounds the flush, closed hatch.
        for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++)
            if(Math.max(Math.abs(dx),Math.abs(dz))==3)
                p.set(this.cx+dx,this.h+1,this.cz+dz,FarmPainter.STONE_BRICK,0);
        p.set(this.cx,this.h,this.cz,167,0); // closed iron trapdoor directly over the shaft
        p.set(this.cx-5,this.h+1,this.cz-5,FarmPainter.IRON_BARS,0);
        p.set(this.cx+5,this.h+1,this.cz+5,FarmPainter.IRON_BARS,0);
        // Two capped ventilation risers and a sealed service access door.
        for(int y=this.h+1;y<=this.h+3;y++) {
            p.set(this.cx-6,y,this.cz+2,FarmPainter.STONE_BRICK,0);
            p.set(this.cx+6,y,this.cz-2,FarmPainter.STONE_BRICK,0);
        }
        p.set(this.cx-6,this.h+4,this.cz+2,FarmPainter.SLAB_STONE,5);
        p.set(this.cx+6,this.h+4,this.cz-2,FarmPainter.SLAB_STONE,5);

    }
}
