package ch.k42.metropolis.grid.urbanGrid.farm;

/** A modest rural farmhouse with an optional earth-sheltered produce cellar. */
final class FarmHouse extends FarmFeature {
    private final int x, z, h;
    private final long seed;
    private final boolean axisX, cellar;

    FarmHouse(int x, int z, int h, long seed, boolean axisX, boolean cellar) {
        super(x - 1, z - 1, x + 12, z + 10);
        this.x=x; this.z=z; this.h=h; this.seed=seed;
        this.axisX=axisX; this.cellar=cellar;
    }

    private void set(FarmPainter p,int u,int v,int dy,int id,int data) {
        int wx=axisX?x+u:x+v, wz=axisX?z+v:z+u;
        p.set(wx,h+dy,wz,id,data);
    }

    @Override void paint(FarmPainter p) {
        // 11 x 9 weatherboard house, raised stone footing and a small porch.
        for(int u=0;u<11;u++) for(int v=0;v<9;v++) {
            boolean edge=u==0||u==10||v==0||v==8;
            set(p,u,v,0,edge?FarmPainter.COBBLE:FarmPainter.PLANKS,edge?0:0);
            for(int dy=1;dy<=4;dy++) {
                if(edge) {
                    boolean door=(u==5&&v==0&&dy<=2);
                    boolean window=(dy==2&&((u==2||u==8)&&(v==0||v==8)));
                    if(door||window) set(p,u,v,dy,FarmPainter.AIR,0);
                    else set(p,u,v,dy,((u+v+dy)%7==0)?FarmPainter.TERRACOTTA:FarmPainter.PLANKS,
                            ((u+v)%3==0)?1:0);
                } else set(p,u,v,dy,FarmPainter.AIR,0);
            }
        }
        set(p,5,0,1,64,0);
        set(p,5,0,2,64,8);
        // Timber corners and a simple central chimney.
        for(int[] corner:new int[][]{{0,0},{0,8},{10,0},{10,8}})
            for(int dy=1;dy<=5;dy++) set(p,corner[0],corner[1],dy,FarmPainter.LOG,0);
        for(int dy=3;dy<=8;dy++) for(int du=0;du<2;du++) for(int dv=0;dv<2;dv++)
            set(p,7+du,5+dv,dy,FarmPainter.COBBLE,0);
        // Gabled roof with overhanging eaves; two roof slopes meet at a ridge.
        for(int rise=0;rise<=4;rise++) {
            int v0= -1+rise, v1=9-rise;
            for(int u=-1;u<=11;u++) {
                set(p,u,v0,5+rise,53,axisX?2:0);
                set(p,u,v1,5+rise,53,axisX?3:1);
            }
        }
        for(int u=0;u<11;u++) {
            set(p,u,4,9,FarmPainter.PLANKS,1);
            if((u%3)==1) set(p,u,4,10,FarmPainter.LOG,0);
        }
        // Porch and steps on the yard-facing end.
        for(int u=3;u<=7;u++) {
            set(p,u,-2,0,FarmPainter.PLANKS,0);
            set(p,u,-2,1,FarmPainter.FENCE,0);
        }
        set(p,4,-3,0,FarmPainter.COBBLE,0);
        set(p,5,-3,0,FarmPainter.COBBLE,0);
        set(p,6,-3,0,FarmPainter.COBBLE,0);
        // A lived-in interior: hearth, table, pantry and one possible chest.
        set(p,2,6,1,FarmPainter.COBBLE,0);
        set(p,2,6,2,61,0);
        set(p,3,5,1,FarmPainter.PLANKS,0);
        set(p,3,5,2,58,0);
        set(p,8,6,1,FarmPainter.CHEST,2);
        set(p,5,1,3,FarmPainter.TORCH,5);
        if(this.cellar) paintCellar(p);
    }

    /** A compact hobbit-like root cellar tucked beneath the back of the house. */
    private void paintCellar(FarmPainter p) {
        int cx=this.x+5, cz=this.z+6;
        int bottom=this.h-8, ceiling=this.h-2;
        for(int dx=-4;dx<=4;dx++) for(int dz=-3;dz<=3;dz++) {
            boolean wall=Math.abs(dx)==4||Math.abs(dz)==3;
            p.set(cx+dx,bottom,cz+dz,FarmPainter.COBBLE,0);
            p.set(cx+dx,ceiling,cz+dz,FarmPainter.LOG,0);
            for(int y=bottom+1;y<ceiling;y++)
                p.set(cx+dx,y,cz+dz,wall?FarmPainter.STONE_BRICK:FarmPainter.AIR,0);
            if(wall && dx%2==0 && dz==-3) p.set(cx+dx,bottom+2,cz+dz,FarmPainter.PLANKS,1);
        }
        // Shelves, preserves and root vegetables.
        for(int dx=-3;dx<=3;dx+=2) {
            p.set(cx+dx,bottom+2,cz-2,FarmPainter.PLANKS,1);
            p.set(cx+dx,bottom+3,cz-2,FarmPainter.CHEST,2);
            p.set(cx+dx,bottom+2,cz+2,FarmPainter.HAY,0);
            p.set(cx+dx,bottom+3,cz+2,FarmPainter.PUMPKIN,0);
        }
        // Narrow stair/ladder access hidden inside the pantry floor.
        for(int y=bottom+1;y<=this.h;y++) {
            p.set(cx+3,y,cz+1,FarmPainter.LADDER,4);
            if(y>=this.h-2) p.set(cx+3,y,cz+2,FarmPainter.AIR,0);
        }
        p.set(cx+3,this.h+1,cz+2,96,0); // closed trapdoor under the pantry rug
        // A grass-covered low earth mound and stone arch identify the cellar
        // only when approached closely; it is not a separate above-ground shed.
        for(int dx=-2;dx<=2;dx++) for(int dz=0;dz<=2;dz++) {
            int rise=3-Math.abs(dx)/2-dz/2;
            if(rise>0) for(int dy=1;dy<=rise;dy++)
                p.set(cx+dx,this.h+dy,cz+4+dz,dy==rise?FarmPainter.GRASS:FarmPainter.DIRT,0);
        }
        for(int side=-1;side<=1;side++) p.set(cx+side,this.h+1,cz+4,FarmPainter.STONE_BRICK,0);
        p.set(cx,this.h+1,cz+4,FarmPainter.AIR,0);
        p.set(cx,this.h+2,cz+4,FarmPainter.AIR,0);
        // The rounded mound doorway is a real second entrance: a narrow
        // ladder shaft drops behind the arch and opens through the cellar wall.
        for(int y=bottom+1;y<=this.h+1;y++) p.set(cx,y,cz+4,FarmPainter.AIR,0);
        for(int y=bottom+1;y<=this.h;y++) p.set(cx+1,y,cz+4,FarmPainter.LADDER,4);
        for(int y=bottom+1;y<=bottom+3;y++) p.set(cx,y,cz+3,FarmPainter.AIR,0);
    }
}
