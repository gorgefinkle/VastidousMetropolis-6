package ch.k42.metropolis.grid.urbanGrid.farm;

/** Three materially and spatially distinct concealed rural survival shelters. */
final class FarmBunker extends FarmFeature {
    private final int cx,cz,h,variant;
    private final long seed;

    FarmBunker(int cx,int cz,int h,long seed,int variant) {
        super(cx-10,cz-8,cx+10,cz+8);
        this.cx=cx;this.cz=cz;this.h=h;this.seed=seed;
        this.variant=Math.floorMod(variant,3);
    }

    @Override void paint(FarmPainter p) {
        int width=this.variant==0?9:this.variant==1?15:19;
        int length=this.variant==0?9:this.variant==1?11:15;
        int depth=this.variant==0?6:this.variant==1?8:11;
        int x0=this.cx-width/2,z0=this.cz-length/2;
        int floor=this.h-depth,roof=this.h-2;
        int wall=this.variant==2?FarmPainter.STONE_BRICK:FarmPainter.COBBLE;
        int trim=this.variant==2?FarmPainter.STONE_BRICK:FarmPainter.MOSSY_COBBLE;
        for(int x=x0-1;x<=x0+width;x++) for(int z=z0-1;z<=z0+length;z++) {
            boolean edge=x==x0-1||x==x0+width||z==z0-1||z==z0+length;
            p.set(x,floor,z,wall,0);
            p.set(x,roof,z,trim,0);
            for(int y=floor+1;y<roof;y++) {
                int weathered=this.variant==2?wall
                        :(FarmRandom.unit(this.seed,x,z,y+870)<.12?FarmPainter.MOSSY_COBBLE:wall);
                p.set(x,y,z,edge?weathered:FarmPainter.AIR,0);
            }
        }
        // The middle shelter is a two-room refuge; the deep shelter has an
        // airlock, stores and sleeping bay separated by reinforced partitions.
        if(this.variant==1) {
            for(int z=z0+1;z<z0+length-1;z++) p.set(this.cx+1,floor+1,z,wall,0);
            p.set(this.cx+1,floor+2,this.cz,71,0);
            p.set(this.cx+1,floor+3,this.cz,71,8);
        } else if(this.variant==2) {
            for(int z=z0+1;z<z0+length-1;z++) {
                p.set(this.cx-3,floor+1,z,wall,0);
                p.set(this.cx+3,floor+1,z,wall,0);
            }
            p.set(this.cx-3,floor+2,this.cz,71,0);
            p.set(this.cx-3,floor+3,this.cz,71,8);
            p.set(this.cx+3,floor+2,this.cz,71,0);
            p.set(this.cx+3,floor+3,this.cz,71,8);
        }
        // Concealed vertical access: the hay bale is the only surface clue.
        int hatchX=this.cx-width/2+2,hatchZ=this.cz;
        for(int y=floor+1;y<=this.h;y++) {
            p.set(hatchX,y,hatchZ,FarmPainter.AIR,0);
            if(y<roof) p.set(hatchX+1,y,hatchZ,FarmPainter.AIR,0);
        }
        for(int y=floor+1;y<=this.h-1;y++) p.set(hatchX,y,hatchZ,FarmPainter.LADDER,4);
        p.set(hatchX,this.h,hatchZ,96,0);
        p.set(hatchX,this.h+1,hatchZ,FarmPainter.HAY,0);
        p.set(hatchX+1,this.h+1,hatchZ,FarmPainter.HAY,0);
        // Storage and life-support details differ by shelter type.
        if(this.variant==0) {
            p.set(this.cx+2,floor+1,this.cz-2,FarmPainter.CHEST,2);
            p.set(this.cx-2,floor+1,this.cz+2,61,0);
            p.set(this.cx+1,floor+1,this.cz+2,26,0);
            p.set(this.cx,floor+3,this.cz-3,FarmPainter.TORCH,5);
        } else if(this.variant==1) {
            for(int z=z0+2;z<z0+length-2;z+=3) {
                p.set(x0+2,floor+1,z,FarmPainter.CHEST,2);
                p.set(x0+width-2,floor+1,z,FarmPainter.HAY,0);
            }
            p.set(this.cx+4,floor+1,this.cz+2,61,0);
            p.set(this.cx+5,floor+1,this.cz+2,58,0);
            p.set(this.cx-4,floor+1,this.cz+2,26,0);
        } else {
            for(int z=z0+2;z<z0+length-2;z+=3) {
                p.set(x0+2,floor+1,z,FarmPainter.CHEST,2);
                p.set(x0+width-2,floor+1,z,FarmPainter.CHEST,3);
            }
            p.set(this.cx,floor+1,this.cz+5,61,0);
            p.set(this.cx+1,floor+1,this.cz+5,58,0);
            p.set(this.cx,floor+3,this.cz-5,FarmPainter.TORCH,5);
        }
        // A shallow earth cap hides the roof and prevents the farm cover pass
        // from turning the concealed hatch into an obvious open hole.
        for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
            if(dx*dx+dz*dz<=5) p.set(this.cx+dx,this.h,this.cz+dz,FarmPainter.DIRT,0);
        }
        p.set(hatchX,this.h, hatchZ,96,0);
        p.set(hatchX,this.h+1,hatchZ,FarmPainter.HAY,0);
    }
}
