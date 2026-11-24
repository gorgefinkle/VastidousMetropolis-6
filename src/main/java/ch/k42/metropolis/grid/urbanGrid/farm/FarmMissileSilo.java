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
        // The central missile body is suspended on a service cradle, not an
        // open pit or a decorative surface rocket. It remains entirely sealed.
        for(int y=bottom+4;y<=this.h-8;y++) {
            int radius=y>this.h-14?1:2;
            for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                boolean edge=Math.abs(dx)==radius||Math.abs(dz)==radius;
                p.set(this.cx+dx,y,this.cz+dz,edge?FarmPainter.IRON_BARS:FarmPainter.STONE_BRICK,0);
            }
            if(y%6==0) for(int dx=-3;dx<=3;dx++) {
                p.set(this.cx+dx,y,this.cz-3,FarmPainter.IRON_BARS,0);
                p.set(this.cx+dx,y,this.cz+3,FarmPainter.IRON_BARS,0);
            }
        }
        // Rounded nose and engine skirt, all inside the shaft.
        for(int y=this.h-7;y<=this.h-3;y++) {
            int radius=y>=this.h-5?1:2;
            for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++)
                if(dx*dx+dz*dz<=radius*radius+1)
                    p.set(this.cx+dx,y,this.cz+dz,FarmPainter.TERRACOTTA,y>=this.h-5?14:8);
        }
        for(int dx=-3;dx<=3;dx++) for(int dz=-3;dz<=3;dz++) {
            if(Math.max(Math.abs(dx),Math.abs(dz))==3) {
                p.set(this.cx+dx,this.h-2,this.cz+dz,FarmPainter.STONE_BRICK,0);
            }
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
        p.set(this.cx+7,this.h,this.cz,71,0);
        p.set(this.cx+7,this.h+1,this.cz,71,8);
    }
}
