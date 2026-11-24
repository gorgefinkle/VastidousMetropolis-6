package ch.k42.metropolis.grid.urbanGrid.forest;

/**
 * Compact, deterministic woodland trees for the Bloomlands understory.
 * Biome and parcel decisions remain owned by the caller.
 */
public final class BloomUnderstoryGeometry {
    public interface Sink { void set(int x,int y,int z,String blockState); }
    public interface Terrain { int groundAt(int x,int z); boolean allows(int x,int z); }
    private record P(double x,double y,double z) {}
    private BloomUnderstoryGeometry() {}

    private static long mix(long v) {
        v ^= v >>> 30; v *= 0xbf58476d1ce4e5b9L;
        v ^= v >>> 27; v *= 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }
    private static long hash(long seed,long value) { return mix(seed+0x9e3779b97f4a7c15L*(value+1)); }
    private static double unit(long value) { return (mix(value)>>>11)*0x1.0p-53; }
    private static P lerp(P a,P b,double t) {
        return new P(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t);
    }

    private static final class Maker {
        final Sink sink; final Terrain terrain; final long seed;
        final String wood,leaves;
        Maker(Sink sink,Terrain terrain,long seed,int species) {
            this.sink=sink;this.terrain=terrain;this.seed=seed;
            String name=species==1?"spruce":species==2?"birch":"oak";
            wood="minecraft:"+name+"_log";
            leaves="minecraft:"+name+"_leaves[persistent=true]";
        }
        void put(int x,int y,int z,boolean log,String axis) {
            if(!terrain.allows(x,z)||y<=terrain.groundAt(x,z))return;
            sink.set(x,y,z,log?wood+"[axis="+axis+"]":leaves);
        }
        void tube(P a,P b,double startRadius,double endRadius) {
            double dx=b.x-a.x,dy=b.y-a.y,dz=b.z-a.z;
            int steps=Math.max(1,(int)Math.ceil(Math.sqrt(dx*dx+dy*dy+dz*dz)*2.2));
            String axis=Math.abs(dy)>=Math.max(Math.abs(dx),Math.abs(dz))?"y"
                    :Math.abs(dx)>=Math.abs(dz)?"x":"z";
            for(int i=0;i<=steps;i++) {
                double t=i/(double)steps;P q=lerp(a,b,t);
                double radius=startRadius+(endRadius-startRadius)*t;
                int r=(int)Math.ceil(radius);
                for(int ox=-r;ox<=r;ox++)for(int oy=-r;oy<=r;oy++)for(int oz=-r;oz<=r;oz++) {
                    if(ox*ox+oy*oy+oz*oz>radius*radius+.25)continue;
                    put((int)Math.round(q.x)+ox,(int)Math.round(q.y)+oy,
                        (int)Math.round(q.z)+oz,true,axis);
                }
            }
        }
        void tuft(P center,double rx,double ry,double rz,long salt) {
            for(int x=(int)Math.floor(center.x-rx);x<=(int)Math.ceil(center.x+rx);x++)
            for(int z=(int)Math.floor(center.z-rz);z<=(int)Math.ceil(center.z+rz);z++)
            for(int y=(int)Math.floor(center.y-ry);y<=(int)Math.ceil(center.y+ry);y++) {
                double dx=(x-center.x)/rx,dy=(y-center.y)/ry,dz=(z-center.z)/rz;
                double shape=dx*dx+dy*dy+dz*dz;
                if(shape>1.04+.10*(unit(hash(salt,x*137L+y*977L+z*7919L))-.5))continue;
                if(unit(hash(salt+3,x*31L+y*211L+z*1237L))<.075)continue;
                put(x,y,z,false,"y");
            }
        }
        void grow(int x,int z) {
            int ground=terrain.groundAt(x,z);
            double angle=unit(hash(seed,1))*Math.PI*2;
            int height=9+(int)(unit(hash(seed,2))*8);
            double lean=.7+unit(hash(seed,3))*1.8;
            P base=new P(x,ground+1,z);
            P top=new P(x+Math.cos(angle)*lean,ground+height,z+Math.sin(angle)*lean);
            P previous=base;
            for(int i=1;i<=7;i++) {
                double t=i/7.0;
                P next=new P(x+Math.cos(angle)*lean*t*t+Math.sin(t*4.2+angle)*.42*t,
                        ground+1+(height-1)*t,
                        z+Math.sin(angle)*lean*t*t+Math.cos(t*3.7+angle)*.36*t);
                tube(previous,next,1.25-.67*t,.58);
                previous=next;
            }
            tuft(top,3.3,2.25,3.0,hash(seed,91));
            for(int level=0;level<3;level++) {
                double t=.38+level*.20;
                P joint=lerp(base,top,t);
                int count=level==0?3:4;
                for(int j=0;j<count;j++) {
                    long h=hash(seed,120+level*13+j);
                    double a=angle+j*(Math.PI*2/count)+(unit(hash(h,4))-.5)*.64;
                    double length=3.0+unit(hash(h,5))*3.1;
                    double rise=.35+unit(hash(h,6))*1.65;
                    P middle=new P(joint.x+Math.cos(a)*length*.55,
                            joint.y+rise*.90,joint.z+Math.sin(a)*length*.55);
                    P end=new P(joint.x+Math.cos(a)*length,
                            joint.y+rise,joint.z+Math.sin(a)*length);
                    tube(joint,middle,.82,.57);
                    tube(middle,end,.57,.40);
                    tuft(end,2.05+unit(hash(h,7))*1.0,1.45+unit(hash(h,8))*.65,
                            2.0+unit(hash(h,9))*1.0,h+2);
                    if(j%2==0) {
                        P side=new P(middle.x+Math.cos(a+1.1)*1.8,
                                middle.y+1.0,middle.z+Math.sin(a+1.1)*1.8);
                        tube(middle,side,.48,.32);
                        tuft(side,1.65,1.25,1.7,h+4);
                    }
                }
            }
            for(int j=0;j<3;j++) {
                long h=hash(seed,220+j);
                double a=angle+j*2.399963+(unit(hash(h,2))-.5)*.5;
                P joint=lerp(base,top,.30);
                P end=new P(joint.x+Math.cos(a)*3.3,joint.y+1.3,
                           joint.z+Math.sin(a)*3.3);
                tube(joint,end,.66,.38);
                tuft(end,1.9,1.35,1.9,h+1);
            }
        }
    }
    public static void grow(Sink sink,Terrain terrain,int x,int z,long seed,int variant) {
        new Maker(sink,terrain,mix(seed ^ hash(seed,(long)x*31+z)),Math.floorMod(variant,3))
                .grow(x,z);
    }
}
