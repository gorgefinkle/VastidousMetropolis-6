package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.Map;

/** A naturalistic azalea/woodland tree for sheltered karst ravines.
 * Geometry and biome ownership are separate; caller enforces the already-decided
 * biome, natural site permissions, and chunk ownership. No trunk-top ellipsoids. */
public final class KarstGroveGeometry {
    public interface Sink { void set(int x,int y,int z,String blockState); }
    public interface Terrain { int groundAt(int x,int z); boolean allows(int x,int z); }
    private KarstGroveGeometry() {}
    private static long mix(long v){v^=v>>>30;v*=0xbf58476d1ce4e5b9L;v^=v>>>27;v*=0x94d049bb133111ebL;return v^(v>>>31);}
    private static double rnd(long n){return (mix(n)>>>11)*0x1.0p-53;}
    private static long h(long s,long n){return mix(s+0x9e3779b97f4a7c15L*(n+1));}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static class Tree {
        final Sink sink;final Terrain ground;final long seed;final int x0,x1,z0,z1;
        final Map<Long,String> blocks=new HashMap<>();
        Tree(Sink s,Terrain g,long seed,int x0,int x1,int z0,int z1){sink=s;ground=g;this.seed=seed;this.x0=x0;this.x1=x1;this.z0=z0;this.z1=z1;}
        static long key(int x,int y,int z){return (((long)x+1048576&0x1fffffL)<<42)|(((long)z+1048576&0x1fffffL)<<21)|((long)y+1048576&0x1fffffL);}
        void put(int x,int y,int z,boolean wood,String axis){
            if(x<x0||x>x1||z<z0||z>z1||!ground.allows(x,z)||y<=ground.groundAt(x,z))return;
            long k=key(x,y,z); if(wood||!blocks.containsKey(k))blocks.put(k,wood?"minecraft:oak_log[axis="+axis+"]":"minecraft:azalea_leaves[persistent=true]");
        }
        void tuft(double x,double y,double z,double rx,double ry,double rz,long salt){
            for(int xx=Math.max(x0,(int)Math.floor(x-rx));xx<=Math.min(x1,(int)Math.ceil(x+rx));xx++)
              for(int zz=Math.max(z0,(int)Math.floor(z-rz));zz<=Math.min(z1,(int)Math.ceil(z+rz));zz++)
                for(int yy=(int)Math.floor(y-ry);yy<=(int)Math.ceil(y+ry);yy++){
                    double d=Math.pow((xx-x)/rx,2)+Math.pow((zz-z)/rz,2)+Math.pow((yy-y)/ry,2);
                    if(d<.98+.10*(rnd(h(salt,xx*113L+yy*977L+zz*11371L))-.5) && rnd(h(salt,xx*37L+yy*2197L+zz*17L))>.14)
                        put(xx,yy,zz,false,"y");
                }
        }
        void line(double ax,double ay,double az,double bx,double by,double bz,double radiusA,double radiusB){
            int steps=(int)Math.ceil(Math.sqrt(Math.pow(bx-ax,2)+Math.pow(by-ay,2)+Math.pow(bz-az,2))*2.0);
            steps=Math.max(1,steps);
            String axis=Math.abs(by-ay)>Math.max(Math.abs(bx-ax),Math.abs(bz-az))?"y":Math.abs(bx-ax)>Math.abs(bz-az)?"x":"z";
            int priorX=(int)Math.round(ax),priorY=(int)Math.round(ay),priorZ=(int)Math.round(az);
            for(int i=0;i<=steps;i++){
                double t=i/(double)steps,px=lerp(ax,bx,t),py=lerp(ay,by,t),pz=lerp(az,bz,t),r=lerp(radiusA,radiusB,t);
                int xx=(int)Math.round(px),yy=(int)Math.round(py),zz=(int)Math.round(pz),n=(int)Math.ceil(r);
                for(int dx=-n;dx<=n;dx++)for(int dz=-n;dz<=n;dz++)for(int dy=-n;dy<=n;dy++){
                    if(dx*dx+dz*dz+dy*dy<=r*r)put(xx+dx,yy+dy,zz+dz,true,axis);
                }
                while(priorX!=xx){priorX+=Integer.signum(xx-priorX);put(priorX,priorY,priorZ,true,axis);}
                while(priorZ!=zz){priorZ+=Integer.signum(zz-priorZ);put(priorX,priorY,priorZ,true,axis);}
                while(priorY!=yy){priorY+=Integer.signum(yy-priorY);put(priorX,priorY,priorZ,true,axis);}
                put(xx,yy,zz,true,axis);
            }
        }
        void grow(int cx,int cz){
            double gy=ground.groundAt(cx,cz),theta=rnd(h(seed,1))*6.28318530718;
            int tall=13+(int)(rnd(h(seed,2))*11);
            double leanX=(rnd(h(seed,3))-.5)*5.5,leanZ=(rnd(h(seed,4))-.5)*5.5;
            // Leaning, crooked living bole with a few rooted basal supports.
            double oldX=cx,oldY=gy+1,oldZ=cz;
            for(int k=1;k<=8;k++){
                double t=k/8.0;double x=cx+leanX*t*t+Math.sin(t*4.1+theta)*1.25*t;
                double z=cz+leanZ*t*t+Math.cos(t*3.7+theta)*.9*t;
                double y=gy+1+t*tall;
                line(oldX,oldY,oldZ,x,y,z,(1-t)*2.65+.65,Math.max(.6,(1-t)*2.3+.6));
                oldX=x;oldY=y;oldZ=z;
            }
            for(int j=0;j<4;j++){
                long sh=h(seed,810+j),dir=sh;double a=theta+j*1.63+(rnd(h(sh,3))-.5)*.5;
                int attach=2+(int)(rnd(h(sh,4))*4);
                double at=attach/8.0, bx=cx+leanX*at*at+Math.sin(at*4.1+theta)*1.25*at;
                double bz=cz+leanZ*at*at+Math.cos(at*3.7+theta)*.9*at,by=gy+1+at*tall;
                double dist=5.5+rnd(h(sh,5))*6.5, rise=2+rnd(h(sh,6))*5;
                double mx=bx+Math.cos(a)*dist*.47,my=by+rise*.68,mz=bz+Math.sin(a)*dist*.47;
                double ex=bx+Math.cos(a)*dist,ey=by+rise+((j%3)==0?-2:1),ez=bz+Math.sin(a)*dist;
                line(bx,by,bz,mx,my,mz,1.5,.85);line(mx,my,mz,ex,ey,ez,.85,.49);
                // Smaller fork and broken-offset terminal clusters: no top-cap ellipsoid.
                double tipA=a+(.6+rnd(h(sh,7))*.6)*(j%2==0?1:-1);
                double tx=ex+Math.cos(tipA)*(2.2+rnd(h(sh,8))*2.8);
                double ty=ey+1.0+rnd(h(sh,9))*2.9,tz=ez+Math.sin(tipA)*(2.2+rnd(h(sh,8))*2.8);
                line(ex,ey,ez,tx,ty,tz,.65,.45);
                tuft(ex,ey+.8,ez,2.6+rnd(h(sh,11))*1.4,1.45+rnd(h(sh,12))*1.5,2.3+rnd(h(sh,13)),sh);
                tuft(tx,ty,tz,2.6+rnd(h(sh,14)),1.5+rnd(h(sh,15)),2.4+rnd(h(sh,16)),h(sh,102));
                if(j%2==0)tuft(mx,my+2,mz,2.2,1.5,1.8,h(sh,103));
            }
            // Multiple broken apexes instead of a spherical top.
            for(int j=0;j<3;j++){
                long sh=h(seed,920+j);double a=theta+j*2.4+(rnd(h(sh,3))-.5);
                double t=.74+j*.10,ax=cx+leanX*t*t,az=cz+leanZ*t*t,ay=gy+1+t*tall;
                double reach=3+rnd(h(sh,4))*4,ex=ax+Math.cos(a)*reach,ez=az+Math.sin(a)*reach,ey=ay+3+rnd(h(sh,5))*3;
                line(ax,ay,az,ex,ey,ez,1.1,.48);
                tuft(ex,ey,ez,2.6+rnd(h(sh,6))*1.4,2.0,2.3+rnd(h(sh,7)),sh);
            }
            for(var e:blocks.entrySet()){
                long k=e.getKey();int x=(int)(k>>>42&0x1fffffL)-1048576;
                int z=(int)(k>>>21&0x1fffffL)-1048576;int y=(int)(k&0x1fffffL)-1048576;
                sink.set(x,y,z,e.getValue());
            }
        }
    }
    public static void grow(Sink out,Terrain ground,int cx,int cz,long worldSeed){growBounded(out,ground,cx,cz,worldSeed,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE);}
    public static void growChunk(Sink out,Terrain ground,int cx,int cz,long worldSeed,int chunkX,int chunkZ){int x=chunkX<<4,z=chunkZ<<4;growBounded(out,ground,cx,cz,worldSeed,x,x+15,z,z+15);}
    private static void growBounded(Sink out,Terrain ground,int cx,int cz,long worldSeed,int x0,int x1,int z0,int z1){
        Tree t=new Tree(out,ground,mix(worldSeed^((long)cx*0x9e3779b97f4a7c15L)^((long)cz*0xc2b2ae3d27d4eb4fL)),x0,x1,z0,z1);t.grow(cx,cz);
    }
}
