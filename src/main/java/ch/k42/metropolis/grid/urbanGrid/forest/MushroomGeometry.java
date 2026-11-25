package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Giant fungal growth geometry. The cap and trunk have coherent material
 * identities; the caller owns site choice and terrain permissions.
 * Stem height is clipped against assembled cap columns so it cannot pierce the upper surface.
 */
public final class MushroomGeometry {
    public static final String STEM = "minecraft:mushroom_stem[down=true,east=true,north=true,south=true,up=true,west=true]";
    static final int[][] DIR={{0,1,0},{0,-1,0},{0,0,-1},{0,0,1},{1,0,0},{-1,0,0}};
    static final String[] FACES={"up","down","north","south","east","west"};
    private MushroomGeometry() {}
    public interface Sink { void set(int x,int y,int z,String blockState); }
    public interface Terrain { int groundAt(int x,int z); boolean allows(int x,int z); }
    static final class P {
        final int x,y,z;
        P(int x,int y,int z) {this.x=x;this.y=y;this.z=z;}
        @Override public int hashCode(){return x*9176 ^ z*1709 ^ y*19307;}
        @Override public boolean equals(Object o){return o instanceof P p && p.x==x&&p.y==y&&p.z==z;}
    }
    private record GroupedCapAnchor(int capX,int capZ,int capBaseY,double boleX,double boleZ,int boleY) {}
    static long mix(long v){ v^=v>>>30;v*= -4658895280553007687L; v^=v>>>27;v*= -7723592293110705685L;return v^(v>>>31); }
    static double unit(long v) {return (mix(v)>>>11)*0x1.0p-53;}
    public static String species(long seed) {return unit(seed ^ 131L)<.52?"minecraft:red_mushroom_block":"minecraft:brown_mushroom_block";}

    private static long columnKey(int x,int z) {
        return ((long)x<<32) ^ (z & 0xffffffffL);
    }

    /**
     * Emit the original leaning bole unchanged. Terrain extensions are allowed
     * only under columns that were already occupied on its original first row.
     * Higher, laterally-shifted bole columns are never filled down to ground.
     */
    static Set<P> emitTrunkStems(Sink sink,Terrain terrain,Set<P> trunk,
                                 Map<Long,Integer> capBottomByColumn,int rootY) {
        Set<Long> originalFootprint=new HashSet<>();
        Set<P> emitted=new HashSet<>();
        for(P q:trunk) {
            long key=columnKey(q.x,q.z);
            Integer capBottom=capBottomByColumn.get(key);
            if(capBottom!=null&&q.y>=capBottom)continue;
            if(!terrain.allows(q.x,q.z))continue;
            int groundY=terrain.groundAt(q.x,q.z);
            if(q.y<=groundY)continue;
            sink.set(q.x,q.y,q.z,STEM);
            emitted.add(q);
            if(q.y==rootY)originalFootprint.add(key);
        }
        for(long key:originalFootprint) {
            int tx=(int)(key>>32),tz=(int)key;
            int floor=terrain.groundAt(tx,tz)+1;
            for(int y=floor;y<rootY;y++) {
                P root=new P(tx,y,tz);
                sink.set(tx,y,tz,STEM);
                emitted.add(root);
            }
        }
        return emitted;
    }

    /**
     * Cap clipping can split the authored bole into several visible pieces.
     * Only the component physically rooted at the local terrain is a valid
     * anchor for grouped-crown branches.
     */
    private static Set<P> groundConnectedBole(Set<P> emitted,Terrain terrain) {
        Set<P> connected=new HashSet<>();
        java.util.ArrayDeque<P> queue=new java.util.ArrayDeque<>();
        for(P q:emitted) {
            if(q.y==terrain.groundAt(q.x,q.z)+1 && connected.add(q))queue.addLast(q);
        }
        while(!queue.isEmpty()) {
            P current=queue.removeFirst();
            for(int[] d:DIR) {
                P next=new P(current.x+d[0],current.y+d[1],current.z+d[2]);
                if(emitted.contains(next)&&connected.add(next))queue.addLast(next);
            }
        }
        return connected;
    }

    private static P nearestEmittedBoleBlock(GroupedCapAnchor anchor,Set<P> emittedMainBole) {
        P best=null;
        double bestDistance=Double.POSITIVE_INFINITY;
        for(P q:emittedMainBole) {
            double dx=q.x-anchor.boleX(),dy=q.y-anchor.boleY(),dz=q.z-anchor.boleZ();
            double distance=dx*dx+dy*dy+dz*dz;
            if(distance<bestDistance) {
                bestDistance=distance;
                best=q;
            }
        }
        return best;
    }

    /**
     * A side crown is a branch of the leaning bole, not a separate tree.
     * Build a short, tapered, rising stem from the bole into the cap underside.
     */
    private static void addGroupedCapBranch(Set<P> output,GroupedCapAnchor anchor,
                                            Set<P> emittedMainBole,Set<P> crown) {
        P actualBole=nearestEmittedBoleBlock(anchor,emittedMainBole);
        if(actualBole==null)return;
        double sx=actualBole.x,sy=actualBole.y,sz=actualBole.z;
        double ex=anchor.capX(),ey=anchor.capBaseY()-1,ez=anchor.capZ();
        double dx=ex-sx,dy=ey-sy,dz=ez-sz;
        double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
        int steps=Math.max(1,(int)Math.ceil(length*3.0));
        for(int i=0;i<=steps;i++) {
            double t=(double)i/steps;
            double cx=sx+dx*t,cy=sy+dy*t,cz=sz+dz*t;
            double radius=2.05-.70*t;
            int reach=(int)Math.ceil(radius);
            int ix=(int)Math.round(cx),iy=(int)Math.round(cy),iz=(int)Math.round(cz);
            for(int ox=-reach;ox<=reach;ox++)
                for(int oy=-reach;oy<=reach;oy++)
                    for(int oz=-reach;oz<=reach;oz++) {
                        if(ox*ox+oy*oy+oz*oz<=radius*radius+.30) {
                            P voxel=new P(ix+ox,iy+oy,iz+oz);
                            if(voxel.y<anchor.capBaseY()&&!crown.contains(voxel))output.add(voxel);
                        }
                    }
        }
    }


    public static void grow(Sink sink,Terrain terrain,int x,int z,long worldSeed,int variant) {
        long seed=mix(worldSeed ^ mix(x*-7046029254386353131L + z*-4417276706812531889L));
        int mode=Math.floorMod(variant,6), ground=terrain.groundAt(x,z);
        int height=(mode==5?29:24)+(int)(unit(seed+2)*21);
        int spread=(mode==5?10:12)+(int)(unit(seed+3)*14);
        int thick=mode==1?7:mode==3?5:3;
        String cap=species(seed);
        double angle=unit(seed+11)*6.28318530718;
        double lean=3.0+unit(seed+15)*10.0;
        double centerX=x+Math.cos(angle)*lean,centerZ=z+Math.sin(angle)*lean;
        Set<P> crown=new HashSet<>(), trunk=new HashSet<>();
        List<GroupedCapAnchor> groupedCapAnchors=new ArrayList<>();
        for(int y=ground+1;y<=ground+height+1;y++) {
            double t=(double)(y-ground)/(double)height;
            double ease=t*t*(3.0-2.0*t);
            double cx=x+Math.cos(angle)*lean*ease+Math.sin(t*4.1+seed%37)*t*1.3;
            double cz=z+Math.sin(angle)*lean*ease+Math.cos(t*3.5+seed%29)*t*1.5;
            double rad=(mode==1?3.6:2.2)+(1.0-t)*(mode==1?3.1:2.7)+2.0*Math.pow(1.0-t,5.0);
            if(mode==4)rad+=.6*Math.sin(t*15.0+seed%100);
            for(int px=(int)(cx-rad-1);px<=cx+rad+1;px++)for(int pz=(int)(cz-rad-1);pz<=cz+rad+1;pz++) {
                double dist=Math.hypot(px-cx,pz-cz);
                if(dist<rad*(.94+.08*unit(mix(seed ^ px*257L ^ pz*433L))))trunk.add(new P(px,y,pz));
            }
        }
        // Snapshot only the original leaning bole. Later auxiliary stems are
        // kept separate so grouped caps cannot silently anchor to a ground spur.
        Set<P> primaryBole=new HashSet<>(trunk);
        for(int px=(int)(centerX-spread-4);px<=centerX+spread+4;px++)for(int pz=(int)(centerZ-spread-4);pz<=centerZ+spread+4;pz++) {
            double offsetX=px-centerX,offsetZ=pz-centerZ;
            double a=Math.atan2(offsetZ,offsetX), dist=Math.hypot(offsetX,offsetZ);
            double ripples=.08*Math.sin(a*5.0+(seed%43)*.1)+.065*Math.sin(a*9.0+(seed%31)*.13);
            double radius=spread*(1.0+ripples)+2.3*(unit(mix(seed ^ px*233L ^ pz*347L))-.5);
            double t=dist/Math.max(1.0,radius);
            if(t>1.0)continue;
            if(mode==4&&t>.48&&Math.sin(a*3.1+seed%100)>.68)continue;
            double top;
            switch(mode) {
                case 0 -> top=4.8-6.4*Math.pow(t,1.5);
                case 1 -> top=10.5*Math.sqrt(Math.max(0.0,1.0-t*t))-2.3*t;
                case 2 -> top=-2.0+7.5*t*t;
                case 3 -> top=9.5-11.5*Math.pow(t,1.25);
                case 4 -> top=4.0-4.8*t+2.3*Math.sin(a*3.1+t*7.0);
                default -> top=2.0+1.7*Math.sin(t*3.1416)-6.0*Math.pow(t,4.0);
            }
            top+=1.3*(unit(mix(seed ^ px*73L ^ pz*71L))-.5);
            double thickness=thick+2.5*(1.0-t)*(mode==1?1.3:1.0);
            int upper=(int)Math.floor(ground+height+top);
            int lower=(int)Math.ceil(ground+height+top-thickness);
            for(int py=lower;py<=upper;py++)crown.add(new P(px,py,pz));
        }
        if(mode==5||(mode==4&&unit(seed+55)>.4)) {
            int branches=mode==5?5:3;
            for(int j=0;j<branches;j++) {
                double theta=j*2.399963+unit(seed+j*421)*.7;
                double fraction=.3+j*.11;
                int by=ground+(int)(height*fraction);
                double bx=x+Math.cos(angle)*lean*fraction+Math.cos(theta)*6.0;
                double bz=z+Math.sin(angle)*lean*fraction+Math.sin(theta)*6.0;
                int capX=(int)Math.round(bx),capZ=(int)Math.round(bz);
                int boleY=Math.max(ground+1,by-8);
                double boleT=(double)(boleY-ground)/(double)height;
                double boleEase=boleT*boleT*(3.0-2.0*boleT);
                double boleX=x+Math.cos(angle)*lean*boleEase
                        +Math.sin(boleT*4.1+seed%37)*boleT*1.3;
                double boleZ=z+Math.sin(angle)*lean*boleEase
                        +Math.cos(boleT*3.5+seed%29)*boleT*1.5;
                groupedCapAnchors.add(new GroupedCapAnchor(capX,capZ,by+1,boleX,boleZ,boleY));
                int radius=5+(int)(unit(seed+j*199)*5.0);
                for(int px=(int)bx-radius;px<=bx+radius;px++)for(int pz=(int)bz-radius;pz<=bz+radius;pz++) {
                    double dist=Math.hypot(px-bx,pz-bz),theta2=Math.atan2(pz-bz,px-bx);
                    if(dist>radius*(1.0+.14*Math.sin(theta2*5.0+j)))continue;
                    int top=by+3-(int)((dist/radius)*4.0);
                    for(int py=top-2;py<=top;py++)crown.add(new P(px,py,pz));
                }
            }
        }
        if(mode==1||mode==5) {
            for(int branch=0;branch<3;branch++) {
                double theta=branch*2.399963+angle+.5;
                int bx=x+(int)Math.round(Math.cos(theta)*(spread*.65+4.0));
                int bz=z+(int)Math.round(Math.sin(theta)*(spread*.65+4.0));
                int groundY=terrain.groundAt(bx,bz);
                int stemHeight=6+branch*3;
                int r=3+branch;
                for(int py=groundY+1;py<groundY+stemHeight;py++)trunk.add(new P(bx,py,bz));
                for(int px=bx-r;px<=bx+r;px++)for(int pz=bz-r;pz<=bz+r;pz++) {
                    double dist=Math.hypot(px-bx,pz-bz);
                    if(dist>r)continue;
                    int top=groundY+stemHeight+2-(int)(dist/r*2.0);
                    for(int py=top-2;py<=top;py++)crown.add(new P(px,py,pz));
                }
            }
        }
        // Determine the underside of the completed cap (including secondary
        // crowns) per X/Z column. The older fixed-height trunk ignored the
        // downward-curving cap profiles and could continue through the cap.
        Map<Long,Integer> capBottomByColumn=new HashMap<>();
        for(P q:crown) {
            long key=columnKey(q.x,q.z);
            Integer previous=capBottomByColumn.get(key);
            if(previous==null||q.y<previous)capBottomByColumn.put(key,q.y);
        }
        Set<P> emittedMainBole=emitTrunkStems(sink,terrain,primaryBole,
                capBottomByColumn,ground+1);
        Set<P> rootedMainBole=groundConnectedBole(emittedMainBole,terrain);
        // Mode-specific satellite stalks keep their authored shape and are
        // emitted separately; only the primary leaning bole gets base-row
        // terrain extension and only it may anchor stacked crown branches.
        for(P q:trunk) {
            if(primaryBole.contains(q))continue;
            Integer capBottom=capBottomByColumn.get(columnKey(q.x,q.z));
            if(capBottom!=null&&q.y>=capBottom)continue;
            if(terrain.allows(q.x,q.z)&&q.y>terrain.groundAt(q.x,q.z))
                sink.set(q.x,q.y,q.z,STEM);
        }
        Set<P> groupedCapStems=new HashSet<>();
        for(GroupedCapAnchor anchor:groupedCapAnchors)
            addGroupedCapBranch(groupedCapStems,anchor,rootedMainBole,crown);
        for(P q:groupedCapStems) {
            if(terrain.allows(q.x,q.z)&&q.y>terrain.groundAt(q.x,q.z))
                sink.set(q.x,q.y,q.z,STEM);
        }
        for(P q:crown)if(terrain.allows(q.x,q.z)&&q.y>terrain.groundAt(q.x,q.z)) {
            StringBuilder state=new StringBuilder(cap).append('[');
            for(int i=0;i<6;i++) {
                P neighbor=new P(q.x+DIR[i][0],q.y+DIR[i][1],q.z+DIR[i][2]);
                boolean visible=!crown.contains(neighbor);
                boolean face=i==1?false:visible;
                if(i>0)state.append(',');
                state.append(FACES[i]).append('=').append(face);
            }
            sink.set(q.x,q.y,q.z,state.append(']').toString());
        }
    }
}
