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
    static record GroupedCapAnchor(double capX,double capZ,int capY,int radius,int branch) {}
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
    /**
     * Preserve the authored leaning bole. Extend only its original bottom
     * footprint down to local terrain, and extend each existing stem column's
     * topmost layer upward to the cap underside. Never fill beneath upper,
     * laterally-shifted trunk columns.
     */
    static Set<P> emitTrunkStems(Sink sink,Terrain terrain,Set<P> trunk,
                                 Map<Long,Integer> capBottomByColumn,int rootY) {
        Set<Long> originalBottomFootprint=new HashSet<>();
        Set<P> emitted=new HashSet<>();
        for(P q:trunk) {
            long key=columnKey(q.x,q.z);
            // Capture only the authored lowest footprint before terrain
            // clipping, so its roots can be extended down over uneven ground.
            if(q.y==rootY&&terrain.allows(q.x,q.z))originalBottomFootprint.add(key);
            Integer capBottom=capBottomByColumn.get(key);
            if(capBottom!=null&&q.y>=capBottom)continue;
            if(!terrain.allows(q.x,q.z))continue;
            if(q.y<=terrain.groundAt(q.x,q.z))continue;
            sink.set(q.x,q.y,q.z,STEM);
            emitted.add(q);
        }
        // Bottom-only correction: extend the original base footprint down
        // to local terrain. Never extend leaning bole columns upward; those
        // vertical fills erase the authored taper, curve, and trunk silhouette.
        for(long key:originalBottomFootprint) {
            int x=(int)(key>>32),z=(int)key;
            int groundY=terrain.groundAt(x,z);
            for(int y=groundY+1;y<rootY;y++) {
                P q=new P(x,y,z);
                sink.set(x,y,z,STEM);
                emitted.add(q);
            }
        }
        return emitted;
    }

    /**
     * Give every secondary grouped cap its own narrow stalk. The stalk is
     * anchored at the cap's central stem column: its authored top block is
     * extended upward to the cap underside and downward to local terrain.
     * This does not alter or straighten the main leaning bole.
     */
    private static void emitGroupedCapStems(Sink sink,Terrain terrain,Set<P> stems,
                                             Set<P> crown,List<GroupedCapAnchor> anchors) {
        for(GroupedCapAnchor anchor:anchors) {
            int x=(int)Math.round(anchor.capX());
            int z=(int)Math.round(anchor.capZ());
            if(!terrain.allows(x,z))continue;
            double dist=Math.hypot(x-anchor.capX(),z-anchor.capZ());
            double theta=Math.atan2(z-anchor.capZ(),x-anchor.capX());
            if(dist>anchor.radius()*(1.0+.14*Math.sin(theta*5.0+anchor.branch())))continue;
            int capTop=anchor.capY()+3-(int)((dist/anchor.radius())*4.0);
            int underside=capTop-2;
            if(!crown.contains(new P(x,underside,z)))continue;
            int ground=terrain.groundAt(x,z);
            // The cap's own underside is the upper endpoint. Do not fill
            // through cap blocks, and root only this stalk's own column.
            for(int y=ground+1;y<underside;y++) {
                P q=new P(x,y,z);
                // A grouped stalk may pass through the underside of a lower
                // crown. The stalk owns that one-block column so the support
                // remains continuous; surrounding cap geometry is unchanged.
                crown.remove(q);
                sink.set(x,y,z,STEM);
                stems.add(q);
            }
        }
    }

    /**
     * For each grouped crown, continue only stem columns already present under
     * that crown. Fill the vertical air gap from the column's topmost stem block
     * to that crown's own underside; do not create ground-to-cap support pillars.
     */
    private static void connectGroupedCapTops(Sink sink,Terrain terrain,Set<P> stems,
                                                Set<P> crown,List<GroupedCapAnchor> anchors) {
        Map<Long,Integer> highestByColumn=new HashMap<>();
        for(P q:stems)highestByColumn.merge(columnKey(q.x,q.z),q.y,Math::max);
        for(GroupedCapAnchor anchor:anchors) {
            int radius=anchor.radius();
            int minX=(int)Math.floor(anchor.capX()-radius-1);
            int maxX=(int)Math.ceil(anchor.capX()+radius+1);
            int minZ=(int)Math.floor(anchor.capZ()-radius-1);
            int maxZ=(int)Math.ceil(anchor.capZ()+radius+1);
            for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++) {
                long key=columnKey(x,z);
                Integer top=highestByColumn.get(key);
                if(top==null||!terrain.allows(x,z))continue;
                double dx=x-anchor.capX(),dz=z-anchor.capZ();
                double dist=Math.hypot(dx,dz);
                double theta=Math.atan2(dz,dx);
                if(dist>radius*(1.0+.14*Math.sin(theta*5.0+anchor.branch())))continue;
                int capTop=anchor.capY()+3-(int)((dist/radius)*4.0);
                int underside=capTop-2;
                if(!crown.contains(new P(x,underside,z))||top>=underside-1)continue;
                boolean clear=true;
                for(int y=top+1;y<underside;y++) {
                    if(crown.contains(new P(x,y,z))||y<=terrain.groundAt(x,z)) {
                        clear=false;
                        break;
                    }
                }
                if(!clear)continue;
                for(int y=top+1;y<underside;y++) {
                    P q=new P(x,y,z);
                    sink.set(x,y,z,STEM);
                    stems.add(q);
                }
                highestByColumn.put(key,underside-1);
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
                int radius=5+(int)(unit(seed+j*199)*5.0);
                groupedCapAnchors.add(new GroupedCapAnchor(bx,bz,by,radius,j));
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
        Set<P> emittedStems=emitTrunkStems(sink,terrain,trunk,capBottomByColumn,ground+1);
        emitGroupedCapStems(sink,terrain,emittedStems,crown,groupedCapAnchors);
        connectGroupedCapTops(sink,terrain,emittedStems,crown,groupedCapAnchors);
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
