package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Deterministic companion canopy. Reconstructed from the shipped R30ea implementation. */
public final class CompanionTreeGeometry {
    private CompanionTreeGeometry() {}

    public interface Sink { void set(int x, int y, int z, String block); }
    public interface Terrain { int groundAt(int x, int z); boolean allows(int x, int z); }

    static final class P {
        final double x, y, z;
        P(double x, double y, double z) { this.x=x; this.y=y; this.z=z; }
    }
    static record Pos(int x,int y,int z) { }

    static long mix(long n) {
        n ^= n >>> 30;
        n *= -4658895280553007687L;
        n ^= n >>> 27;
        n *= -7723592293110705685L;
        return n ^ n >>> 31;
    }
    static double unit(long n) { return (mix(n) >>> 11) * 0x1.0p-53; }
    static P p(double x, double y, double z) { return new P(x,y,z); }
    static P lerp(P a, P b, double t) {return p(a.x+(b.x-a.x)*t, a.y+(b.y-a.y)*t, a.z+(b.z-a.z)*t);}
    static P bez(P a,P b,P c,double t) {
        double q=1-t;
        return p(a.x*q*q+2*b.x*q*t+c.x*t*t,
                 a.y*q*q+2*b.y*q*t+c.y*t*t,
                 a.z*q*q+2*b.z*q*t+c.z*t*t);
    }

    static final class Maker {
        final Terrain land;
        final String wood, bark, leaves;
        final long seed;
        final Map<Pos,String> vox = new LinkedHashMap<>();
        Maker(Terrain land, boolean spruce, long seed) {
            this.land=land;
            this.seed=seed;
            String species=spruce ? "spruce" : "dark_oak";
            wood="minecraft:"+species+"_log";
            bark="minecraft:"+species+"_wood";
            leaves="minecraft:"+species+"_leaves[persistent=true]";
        }
        void put(int x,int y,int z,boolean trunk,String axis) {
            if (!land.allows(x,z) || y<=land.groundAt(x,z)) return;
            Pos key=new Pos(x,y,z);
            if (trunk || !vox.containsKey(key)) vox.put(key,trunk?(axis.equals("none")?bark:bark+"[axis="+axis+"]"):leaves);
        }
        void sphere(P center,double radius,boolean trunk,long salt,String axis) {
            for(int x=(int)Math.floor(center.x-radius);x<=(int)Math.ceil(center.x+radius);x++)
            for(int z=(int)Math.floor(center.z-radius);z<=(int)Math.ceil(center.z+radius);z++)
            for(int y=(int)Math.floor(center.y-radius);y<=(int)Math.ceil(center.y+radius);y++) {
                double dx=x-center.x,dy=y-center.y,dz=z-center.z;
                double irregularity=trunk ? 0.0 : (unit(seed ^ mix(x*3719L+z*7919L+y*9221L+salt))-0.5)*0.47;
                if (dx*dx+dy*dy+dz*dz > (radius+irregularity)*(radius+irregularity)) continue;
                if (!trunk && unit(seed ^ mix(x*127L+y*1009L+z*7187L+salt))<0.13) continue;
                put(x,y,z,trunk,axis);
            }
        }
        void limb(P from,P to,double startRadius,double endRadius,boolean directionless) {
            double dx=to.x-from.x,dy=to.y-from.y,dz=to.z-from.z;
            int steps=(int)Math.ceil(Math.sqrt(dx*dx+dy*dy+dz*dz)*2.5);
            String axis=directionless?"none":(Math.abs(dy)>Math.max(Math.abs(dx),Math.abs(dz))) ? "y" : Math.abs(dx)>=Math.abs(dz)?"x":"z";
            int x=(int)Math.round(from.x),y=(int)Math.round(from.y),z=(int)Math.round(from.z);
            put(x,y,z,true,axis);
            for(int i=0;i<=steps;i++) {
                double t=(double)i/Math.max(1,steps);
                P pt=lerp(from,to,t);
                int xx=(int)Math.round(pt.x), yy=(int)Math.round(pt.y), zz=(int)Math.round(pt.z);
                while (x!=xx) { x+=Integer.signum(xx-x);put(x,y,z,true,"x"); }
                while (z!=zz) { z+=Integer.signum(zz-z);put(x,y,z,true,"z"); }
                while (y!=yy) { y+=Integer.signum(yy-y);put(x,y,z,true,"y"); }
                sphere(pt,startRadius+(endRadius-startRadius)*t,true,0L,axis);
            }
        }
        void curved(P a,P b,P c,double radius0,double radius1,boolean directionless) {
            P previous=a;
            for(int i=1;i<=22;i++) {
                double t=i/22.0;
                P next=bez(a,b,c,t);
                limb(previous,next,radius0+(radius1-radius0)*(t-1.0/22.0),radius0+(radius1-radius0)*t,directionless);
                previous=next;
            }
        }
        void spray(P center,long salt,double radius,boolean spruce) {
            sphere(center,radius*(spruce?0.78:1.05),false,salt,"y");
            if (!spruce && unit(salt+13)>0.35) {
                sphere(p(center.x+(unit(salt+19)-0.5)*radius*1.5,
                         center.y-1.1,
                         center.z+(unit(salt+31)-0.5)*radius*1.2),radius*0.67,false,salt+1,"y");
            }
        }
        void flush(Sink target) {vox.forEach((pos,block)->target.set(pos.x(),pos.y(),pos.z(),block));}
    }

    public static void grow(Sink sink,Terrain terrain,int x,int z,long seed) {
        long s=mix(seed ^ mix(x*-7046029254386353131L+z*-4417276706812531889L));
        Random global=new Random(s);
        boolean spruce=unit(s+91)<0.31;
        Maker maker=new Maker(terrain,spruce,s);
        int ground=terrain.groundAt(x,z);
        double angle=global.nextDouble()*2.0*Math.PI;
        double reach=5.0+global.nextDouble()*12.0;
        int height=(spruce?36:39)+global.nextInt(spruce?16:19);
        P bottom=p(x,ground+1,z);
        P mid=p(x+Math.cos(angle)*reach*0.3,ground+height*0.5,z+Math.sin(angle)*reach*0.3);
        P top=p(x+Math.cos(angle)*reach,ground+height,z+Math.sin(angle)*reach);
        maker.curved(bottom,p(mid.x+1.8,mid.y+3.0,mid.z-2.1),top,spruce?2.5:3.3,1.05,false);
        if(!spruce || unit(s+3)>0.68) {
            double a=angle+1.55;
            P end=p(mid.x+Math.cos(a)*10.0,mid.y+9.0,mid.z+Math.sin(a)*10.0);
            maker.curved(mid,p(mid.x+Math.cos(a)*4.0,mid.y+9.0,mid.z+Math.sin(a)*4.0),end,2.15,0.85,true);
            maker.spray(end,s+32,2.2,spruce);
        }
        int count=spruce?15:9+global.nextInt(4);
        for(int i=0;i<count;i++) {
            long branchSeed=mix(s+i*-7723592293110705685L);
            Random random=new Random(branchSeed);
            double t=spruce?0.27+(double)i/count*0.67:0.32+random.nextDouble()*0.58;
            P joint=bez(bottom,mid,top,t);
            double a=i*2.399963229728653+(random.nextDouble()-0.5)*0.8+angle*0.15;
            double r=spruce?11.0+(1-t)*16.0+random.nextDouble()*5.0:17.0+random.nextDouble()*19.0;
            P end=p(joint.x+Math.cos(a)*r,joint.y+(spruce?-3.0+random.nextDouble()*5.0:1.0+random.nextDouble()*8.0),joint.z+Math.sin(a)*r);
            P midBranch=p(joint.x+Math.cos(a)*r*0.48,joint.y+3.0+random.nextDouble()*5.0,joint.z+Math.sin(a)*r*0.48);
            maker.curved(joint,midBranch,end,spruce?1.3:1.8,0.65,true);
            int tips=spruce?4:3+random.nextInt(3);
            for(int j=0;j<tips;j++) {
                double frac=0.4+(double)(j+1)/(tips+2)*0.46;
                P node=bez(joint,midBranch,end,Math.min(0.93,frac));
                double angleTip=a+(j%2==0?1:-1)*(0.5+random.nextDouble()*0.85);
                double distance=3.0+random.nextDouble()*(spruce?7:10);
                P tip=p(node.x+Math.cos(angleTip)*distance,node.y+(spruce?-2:0)+random.nextDouble()*4.0,node.z+Math.sin(angleTip)*distance);
                maker.curved(node,p((node.x+tip.x)*0.5,node.y+1.3+random.nextDouble()*2.0,(node.z+tip.z)*0.5),tip,0.78,0.45,true);
                maker.spray(tip,branchSeed+j*213L,1.7+random.nextDouble()*1.8,spruce);
            }
            for(int j=1;j<=5;j++) {
                if(random.nextDouble()<0.3) continue;
                P node=bez(joint,midBranch,end,j/6.0);
                maker.spray(p(node.x,node.y+0.7,node.z),branchSeed+j*37L,1.65+random.nextDouble()*2.1,spruce);
            }
            maker.spray(end,branchSeed+991,2.4+random.nextDouble()*1.8,spruce);
        }
        maker.spray(top,s+123,spruce?2.1:2.8,spruce);
        maker.flush(sink);
    }
}
