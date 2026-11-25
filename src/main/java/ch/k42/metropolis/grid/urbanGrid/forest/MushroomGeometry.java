package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    private record StalkAnchor(int baseX,int baseZ,int capX,int capZ,int capEndY) {}

    static long mix(long v){ v^=v>>>30;v*= -4658895280553007687L; v^=v>>>27;v*= -7723592293110705685L;return v^(v>>>31); }
    static double unit(long v) {return (mix(v)>>>11)*0x1.0p-53;}
    public static String species(long seed) {return unit(seed ^ 131L)<.52?"minecraft:red_mushroom_block":"minecraft:brown_mushroom_block";}

    private static long columnKey(int x,int z) {
        return ((long)x<<32) ^ (z & 0xffffffffL);
    }

    /**
     * Draw a continuous support stalk from the underside of a cap down toward
     * its own footing. Horizontal drift eases outward as it rises, so grouped
     * caps have visibly leaning stems rather than unsupported crowns.
     */
    private static void addCapStalk(Set<P> output,Terrain terrain,
                                    Map<Long,Integer> capBottomByColumn,
                                    StalkAnchor anchor) {
        int baseY=terrain.groundAt(anchor.baseX(),anchor.baseZ())+1;
        int capX=anchor.capX(),capZ=anchor.capZ();
        Integer underside=capBottomByColumn.get(columnKey(capX,capZ));
        if(underside==null)return;
        int endY=Math.min(anchor.capEndY(),underside-1);
        if(endY<baseY)return;

        int dx=capX-anchor.baseX(),dz=capZ-anchor.baseZ();
        int dy=endY-baseY;
        int steps=Math.max(1,Math.max(Math.max(Math.abs(dx),Math.abs(dz)),dy)*3);
        int px=anchor.baseX(),pz=anchor.baseZ(),py=baseY;
        addStalkCell(output,terrain,capBottomByColumn,px,py,pz);
        for(int i=1;i<=steps;i++) {
            double t=i/(double)steps;
            double eased=t*t*(3.0-2.0*t);
            int tx=(int)Math.round(anchor.baseX()+dx*eased);
            int tz=(int)Math.round(anchor.baseZ()+dz*eased);
            int ty=(int)Math.round(baseY+dy*t);
            ty=Math.max(ty,terrain.groundAt(tx,tz)+1);
            Integer localBottom=capBottomByColumn.get(columnKey(tx,tz));
            if(localBottom!=null)ty=Math.min(ty,localBottom-1);

            while(px!=tx) {
                px+=Integer.signum(tx-px);
                addStalkCell(output,terrain,capBottomByColumn,px,py,pz);
            }
            while(pz!=tz) {
                pz+=Integer.signum(tz-pz);
                addStalkCell(output,terrain,capBottomByColumn,px,py,pz);
            }
            while(py!=ty) {
                py+=Integer.signum(ty-py);
                addStalkCell(output,terrain,capBottomByColumn,px,py,pz);
            }
        }
    }

    private static void addStalkCell(Set<P> output,Terrain terrain,
                                     Map<Long,Integer> capBottomByColumn,
                                     int x,int y,int z) {
        if(!terrain.allows(x,z)||y<=terrain.groundAt(x,z))return;
        Integer underside=capBottomByColumn.get(columnKey(x,z));
        if(underside!=null&&y>=underside)return;
        output.add(new P(x,y,z));
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
        Set<P> crown=new HashSet<>(), trunk=new HashSet<>(), capStems=new HashSet<>();
        java.util.List<StalkAnchor> groupedCapAnchors=new java.util.ArrayList<>();
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
                // Each grouped cap gets its own leaning stalk, rooted near the
                // main bole and rising all the way to the cap's lower face.
                int baseX=(int)Math.round(x+Math.cos(angle)*lean*fraction*.30+Math.cos(theta)*1.25);
                int baseZ=(int)Math.round(z+Math.sin(angle)*lean*fraction*.30+Math.sin(theta)*1.25);
                groupedCapAnchors.add(new StalkAnchor(baseX,baseZ,(int)Math.round(bx),(int)Math.round(bz),by));
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
        // Build the primary leaning stalk down from the centre cap, not
        // merely upward from the first ground-contact voxel. This closes any
        // small gap where the curved bole meets the cap underside.
        int primaryCapX=(int)Math.round(centerX),primaryCapZ=(int)Math.round(centerZ);
        Integer primaryCapBottom=capBottomByColumn.get(columnKey(primaryCapX,primaryCapZ));
        if(primaryCapBottom!=null) {
            addCapStalk(capStems,terrain,capBottomByColumn,
                new StalkAnchor(x,z,primaryCapX,primaryCapZ,primaryCapBottom-1));
        }
        // The upper grouped crowns are independent cap masses. Give every
        // one a descending stalk; the highest three must not be left floating
        // just because the older painter only supplied the lower side stems.
        for(StalkAnchor anchor:groupedCapAnchors)
            addCapStalk(capStems,terrain,capBottomByColumn,anchor);

        // First retain the intended stalk ceiling in each column. Then root
        // every stalk column at its own true terrain height. This matters on
        // valley walls: a single centre-ground anchor leaves the uphill side
        // of a broad stalk visibly suspended.
        Map<Long,Integer> trunkTopByColumn=new HashMap<>();
        for(P q:trunk) {
            Integer capBottom=capBottomByColumn.get(columnKey(q.x,q.z));
            if(capBottom!=null&&q.y>=capBottom)continue;
            long key=columnKey(q.x,q.z);
            Integer old=trunkTopByColumn.get(key);
            if(old==null||q.y>old)trunkTopByColumn.put(key,q.y);
        }
        for(Map.Entry<Long,Integer> entry:trunkTopByColumn.entrySet()) {
            int tx=(int)(entry.getKey()>>32),tz=(int)(long)entry.getKey();
            if(!terrain.allows(tx,tz))continue;
            int floor=terrain.groundAt(tx,tz)+1;
            for(int ty=floor;ty<=entry.getValue();ty++)sink.set(tx,ty,tz,STEM);
        }
        for(P q:capStems)if(terrain.allows(q.x,q.z)&&q.y>terrain.groundAt(q.x,q.z))
            sink.set(q.x,q.y,q.z,STEM);
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
