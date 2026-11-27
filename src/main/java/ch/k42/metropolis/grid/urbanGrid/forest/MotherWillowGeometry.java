package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.*;

/**
 * Mother Willow: deterministic reiterating sympodial branching model.
 * The existing biome owns seed, placement, terrain clearance and chunk ownership;
 * this class only grows one accepted tree. Poplar bark/logs and golden poplar foliage give the 26.3 tree a distinct willow palette.
 * Branch diameter follows a pipe-model power taper; reaction to gravity grows with
 * age/span, while young terminals are phototropic and subsequently pendulous.
 */
public final class MotherWillowGeometry {
  public interface Sink { void set(int x,int y,int z,String blockState); }
  public interface Terrain { int groundAt(int x,int z); boolean allows(int x,int z); }
  private MotherWillowGeometry() {}
  private static long mix(long z){z=(z^(z>>>30))*0xbf58476d1ce4e5b9L;z=(z^(z>>>27))*0x94d049bb133111ebL;return z^(z>>>31);}
  private static long hash(long s,long n){return mix(s+0x9e3779b97f4a7c15L*(n+1));}
  private static double rand(long s){return (mix(s)>>>11)*0x1.0p-53;}
  static final class P {
    final double x,y,z;
    P(double x,double y,double z){this.x=x;this.y=y;this.z=z;}
    P add(double a,double b,double c){return new P(x+a,y+b,z+c);}
  }
  private static P p(double x,double y,double z){return new P(x,y,z);}
  private static P lerp(P a,P b,double t){return p(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t,a.z+(b.z-a.z)*t);}
  /**
   * Curved take-off for the highest reiterated leader. Its initial tangent
   * blends the main bole with the outward azimuth, making the branch emerge
   * visibly from the trunk while preserving the existing mid point and crown.
   */
  static P leaderJunctionPoint(P start,P end,P trunkBefore,P trunkAfter,double t) {
    t=clamp(t,0.0,1.0);
    double tx=trunkAfter.x-trunkBefore.x,ty=trunkAfter.y-trunkBefore.y,tz=trunkAfter.z-trunkBefore.z;
    double length=Math.sqrt(tx*tx+ty*ty+tz*tz);
    if(length<1.0e-9){tx=0;ty=1;tz=0;length=1;}
    tx/=length;ty/=length;tz/=length;
    // The leader must leave the side of the bole, not travel up its centreline
    // where the first several blocks are completely hidden inside the trunk.
    // Blend the local bole tangent with the leader's outward azimuth so the
    // first visible section grows as a fused, oblique branch junction.
    double ox=end.x-start.x,oz=end.z-start.z;
    double outward=Math.hypot(ox,oz);
    if(outward>1.0e-9){ox/=outward;oz/=outward;} else {ox=0;oz=0;}
    double jx=tx*.78+ox*.62,jy=ty*.78,jz=tz*.78+oz*.62;
    double jl=Math.sqrt(jx*jx+jy*jy+jz*jz);
    if(jl>1.0e-9){jx/=jl;jy/=jl;jz/=jl;}
    double chord=Math.sqrt((end.x-start.x)*(end.x-start.x)
                         +(end.y-start.y)*(end.y-start.y)
                         +(end.z-start.z)*(end.z-start.z));
    double handle=Math.min(5.5,chord*.22);
    P c1=start.add(jx*handle,jy*handle,jz*handle);
    P c2=p(end.x-(end.x-start.x)*.24,
           end.y-(end.y-start.y)*.24,
           end.z-(end.z-start.z)*.24);
    double u=1.0-t;
    return p(u*u*u*start.x+3*u*u*t*c1.x+3*u*t*t*c2.x+t*t*t*end.x,
             u*u*u*start.y+3*u*u*t*c1.y+3*u*t*t*c2.y+t*t*t*end.y,
             u*u*u*start.z+3*u*u*t*c1.z+3*u*t*t*c2.z+t*t*t*end.z);
  }
  static P leaderSurfaceStart(P boleCenter,P end,double branchRadius) {
    double ox=end.x-boleCenter.x,oz=end.z-boleCenter.z;
    double outward=Math.hypot(ox,oz);
    if(outward<1.0e-9)return boleCenter;
    double offset=Math.max(1.0,branchRadius*.78);
    return boleCenter.add(ox/outward*offset,0.0,oz/outward*offset);
  }
  /**
   * Keep each reiterated leader subordinate to the local bole diameter.
   * The upper three leaders are deliberately reduced against the trunk's
   * own taper, rather than retaining the same near-constant branch radius.
   */
  static double leaderRootRadius(int leaderIndex,int attachNode) {
    double nominal=5.8-leaderIndex*.56;
    double trunkAtAttach=15.0*Math.pow(1.0-.80*(attachNode/19.0),.82);
    return Math.min(nominal,trunkAtAttach*(leaderIndex>=2?.34:.48));
  }
  static double leaderScale(int leaderIndex,int attachNode) {
    return leaderRootRadius(leaderIndex,attachNode)/(5.8-leaderIndex*.56);
  }
  private static double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
  static final class GrowthStats {
    int primaryForks;
    int secondaryForks;
    int terminalForks;
    int terminalBranchlets;
  }
  private static class Maker {
    final Sink out;final Terrain land;final long seed;final GrowthStats stats;
    final int x0,x1,z0,z1;
    final HashMap<Long,String> blocks=new HashMap<>();
    Maker(Sink out,Terrain land,long seed,int x0,int x1,int z0,int z1,GrowthStats stats){this.out=out;this.land=land;this.seed=seed;this.x0=x0;this.x1=x1;this.z0=z0;this.z1=z1;this.stats=stats;}
    static long key(int x,int y,int z){return ((((long)x+1048576)&0x1fffffL)<<42)|(((((long)z+1048576)&0x1fffffL)<<21))|(((long)y+1048576)&0x1fffffL);}
    void putState(int x,int y,int z,String state,boolean priority,boolean root) {
      if(x<x0||x>x1||z<z0||z>z1||!land.allows(x,z))return;
      int surface=land.groundAt(x,z);
      if(y<=surface && (!root || y<surface-24))return;
      long k=key(x,y,z);
      if(priority||!blocks.containsKey(k))blocks.put(k,state);
    }
    void put(int x,int y,int z,boolean wood,String axis,boolean root) {
      putState(x,y,z,wood?(axis.equals("none")?"minecraft:poplar_wood":"minecraft:poplar_log[axis="+axis+"]"):"minecraft:yellow_poplar_leaves[persistent=true]",wood,root);
    }
    void bulb(P c,double r,boolean wood,long salt,String axis) {bulb(c,r,wood,salt,axis,false);}
    void bulb(P c,double r,boolean wood,long salt,String axis,boolean root) {
      int ax=Math.max(x0,(int)Math.floor(c.x-r)),bx=Math.min(x1,(int)Math.ceil(c.x+r));
      int az=Math.max(z0,(int)Math.floor(c.z-r)),bz=Math.min(z1,(int)Math.ceil(c.z+r));
      if(ax>bx||az>bz)return;
      double rr=r*r;
      for(int x=ax;x<=bx;x++)for(int z=az;z<=bz;z++){
        double xx=x-c.x,zz=z-c.z;
        if(xx*xx+zz*zz>rr)continue;
        int yr=(int)Math.floor(Math.sqrt(rr-xx*xx-zz*zz));
        for(int y=(int)Math.round(c.y)-yr;y<=(int)Math.round(c.y)+yr;y++){
          if(!wood&&rand(hash(seed^salt,(((long)x)<<34)^(((long)y)<<17)^z))<.14)continue;
          put(x,y,z,wood,axis,root);
        }
      }
    }
    void segment(P a,P b,double radA,double radB){segmentInternal(a,b,radA,radB,false,false);}
    void branchSegment(P a,P b,double radA,double radB){segmentInternal(a,b,radA,radB,false,true);}
    void smoothLeaderJunction(P a,P b,P trunkBefore,P trunkAfter,double radA,double radB){
      // Start the leader at the bole's outer wood, not its hidden centreline.
      // The root radius overlaps the parent trunk so the two volumes fuse.
      P surface=leaderSurfaceStart(a,b,radA);
      bulb(surface,radA+.55,true,0,"none");
      P previous=surface;double previousT=0.0;
      for(int i=1;i<=14;i++){
        double t=i/14.0;P next=leaderJunctionPoint(surface,b,trunkBefore,trunkAfter,t);
        double r0=radA+(radB-radA)*previousT,r1=radA+(radB-radA)*t;
        branchSegment(previous,next,r0,r1);previous=next;previousT=t;
      }
    }
    void rootSegment(P a,P b,double radA,double radB){segmentInternal(a,b,radA,radB,true,false);}
    void segmentInternal(P a,P b,double radA,double radB,boolean root){segmentInternal(a,b,radA,radB,root,false);}
    void segmentInternal(P a,P b,double radA,double radB,boolean root,boolean directionless){
      String axis=directionless?"none":Math.abs(b.y-a.y)>=Math.max(Math.abs(b.x-a.x),Math.abs(b.z-a.z))?"y":Math.abs(b.x-a.x)>=Math.abs(b.z-a.z)?"x":"z";
      int ax=(int)Math.round(a.x),ay=(int)Math.round(a.y),az=(int)Math.round(a.z);
      int steps=Math.max(1,(int)Math.ceil(Math.hypot(Math.hypot(b.x-a.x,b.z-a.z),b.y-a.y)*2.2));
      for(int i=0;i<=steps;i++){
        double t=i/(double)steps;P q=lerp(a,b,t);
        int bx=(int)Math.round(q.x),by=(int)Math.round(q.y),bz=(int)Math.round(q.z);
        while(ax!=bx){ax+=Integer.signum(bx-ax);put(ax,ay,az,true,axis,root);}
        while(az!=bz){az+=Integer.signum(bz-az);put(ax,ay,az,true,axis,root);}
        while(ay!=by){ay+=Integer.signum(by-ay);put(ax,ay,az,true,axis,root);}
        // Pipe-model taper: radius^2.35 is the effective conducted area.
        double r=Math.pow((1-t)*Math.pow(radA,2.35)+t*Math.pow(radB,2.35),1/2.35);
        bulb(q,Math.max(.48,r),true,0,axis,root);
      }
    }
    void foliage(P from,long salt,double maturity) {
      // Sparse pendent leaf curtains grow from the outer woody branch tips.
      // The shoot axis remains wood; leaves are small, spaced clusters rather
      // than a continuous sleeve or a dense ball around the scaffold.
      int shoots=1+(int)(rand(hash(salt,1))*2);
      for(int j=0;j<shoots;j++){
        long sh=hash(salt,37+j);
        double a=rand(hash(sh,2))*Math.PI*2;
        int length=16+(int)(rand(hash(sh,3))*22*maturity)+(rand(hash(sh,14))>.91?8:0);
        double dx=(rand(hash(sh,5))-.5)*.16,dz=(rand(hash(sh,6))-.5)*.16;
        P at=from.add(Math.cos(a)*1.1,rand(hash(sh,11))*1.2,Math.sin(a)*1.1);
        P prev=from;
        for(int k=0;k<length;k++){
          double t=k/(double)length;
          double sway=Math.sin(k*.16+rand(hash(sh,7))*6.283)*(.22+1.25*t);
          P cur=p(at.x+dx*k+Math.cos(a)*sway,at.y-k*(.55+.42*t),at.z+dz*k+Math.sin(a)*sway);
          // Keep the entire twig woody to its fine tip.
          branchSegment(prev,cur,Math.max(.44,.68*(1.0-t*.52)),Math.max(.40,.68*(1.0-(t+.035)*.52)));
          if(k%4==0 || k==length-1)bulb(cur,.72+(1-t)*.38,false,sh+k,"y");
          if(k%9==3 && rand(hash(sh,k))>.42) {
            P bud=cur.add(Math.cos(a+1.4)*.95,.18,Math.sin(a+1.4)*.95);
            bulb(bud,.66+(1-t)*.25,false,sh+k+99,"y");
          }
          prev=cur;
        }
      }
    }
    /**
     * A sparse, long-lived woody scaffold. Primary arms remain continuous
     * wood through the secondary orders, then each outer terminal limb forks
     * into fine, pendulous branchlets. These forks stay at the distal crown,
     * avoiding a dense branching cap beside the trunk.
     */
    void branch(P origin,double azimuth,double initialSlope,double length,double radius,int order,long tag){
      int steps=Math.max(8,(int)Math.ceil(length/6.0));
      P last=origin;
      P[] pts=new P[steps+1];pts[0]=origin;
      double bend=(rand(hash(tag,19))-.5)*.34;
      double drift=(rand(hash(tag,20))-.5)*.38;
      double sag=order==0?.08+rand(hash(tag,23))*.16
              :order==1?.24+rand(hash(tag,24))*.18
              :order==2?.48+rand(hash(tag,25))*.18
              :.78+rand(hash(tag,26))*.16;
      for(int k=1;k<=steps;k++){
        double t=k/(double)steps;
        double bearing=azimuth+bend*t+drift*t*t+Math.sin(t*3.6+rand(hash(tag,32))*6.283)*.10*t;
        // The large limbs sweep outward first; each successive order hangs
        // lower, until the finest terminal branchlets fall almost vertically.
        double elev=initialSlope*(1-t)+.16*(1-t)*(1-t)-sag*t*t;
        double step=length/steps;
        P next=last.add(Math.cos(bearing)*step*Math.cos(elev),Math.sin(elev)*step,Math.sin(bearing)*step*Math.cos(elev));
        double rr=Math.max(.42,radius*Math.pow(1-.90*t,.82));
        branchSegment(last,next,Math.max(.44,radius*Math.pow(1-.90*(t-1.0/steps),.82)),rr);
        pts[k]=next;last=next;
      }

      if(order>=3){
        // Final branchlets carry the foliage, not the thick scaffold. Keep
        // their wood visible through the leaves, with the densest leaf detail
        // on the outermost hanging half.
        int first=(int)(steps*.42);
        for(int k=first;k<=steps;k+=Math.max(2,steps/6)){
          P tip=pts[k];
          if(rand(hash(tag,k+301))<.72)
            bulb(tip,.82+rand(hash(tag,k+302))*.40,false,tag+k,"y");
          // A second generation of pendant shoots hangs from the middle of
          // each outer branchlet as well as its split tip. This gives the
          // canopy its layered willow curtain without filling the inner bole.
          if(k>=steps*.55 && k<steps-1 && rand(hash(tag,k+303))<.72)
            foliage(tip,hash(tag,k),.72+rand(hash(tag,k+304))*.42);
          if(k>=steps-2 && rand(hash(tag,k+305))<.92)
            foliage(tip,hash(tag,k+1),.76+rand(hash(tag,k+306))*.42);
        }
        // Ensure each outer terminal twig ends in its own leaf-bearing pendant shoot.
        foliage(pts[steps],hash(tag,999001),.82+rand(hash(tag,999002))*.30);
        return;
      }

      if(order==2){
        // A real willow branch does not finish as a single sharpened spike.
        // Its distal end opens into a small fan of finer, strongly pendulous
        // branchlets. The fork sits at the outer tip, not beside the trunk,
        // avoiding the dense "branching cap" silhouette from earlier drafts.
        P fork=pts[steps];
        int branchlets=3+(rand(hash(tag,401))>.72?1:0);
        if(stats!=null){stats.terminalForks++;stats.terminalBranchlets+=branchlets;}
        double fan=(branchlets==3?.46:.62);
        for(int j=0;j<branchlets;j++){
          long child=hash(tag,4601+j);
          double t=branchlets==3?j-1.0:j-1.5;
          double angle=azimuth+t*fan+(rand(hash(child,4))-.5)*.16;
          double childLength=length*(.32+rand(hash(child,5))*.14);
          double childRadius=Math.max(.44,radius*(.34+rand(hash(child,6))*.08));
          // A slightly different initial downward angle gives the fork
          // irregular, overlapping curtains rather than a symmetric trident.
          double drop=-.24-rand(hash(child,7))*.22;
          branch(fork,angle,drop,childLength,childRadius,3,child);
        }
        return;
      }

      // A small number of lateral daughters emerge well out along each
      // parent. The parent remains continuous wood to its own terminal fork.
      int daughters=order==0?1+(int)(rand(hash(tag,12))*2):1;
      for(int j=0;j<daughters;j++){
        long child=hash(tag,j+8217);
        double along=.56+(j+.35+rand(hash(child,3))*.35)/(daughters+.65)*.28;
        int attach=Math.min(steps-3,Math.max(2,(int)Math.round(steps*along)));
        double side=(j%2==0?1:-1)*(order==0?.48:.72);
        double ang=azimuth+side+(rand(hash(child,5))-.5)*.28;
        double size=(order==0?.50:.55)+(rand(hash(child,6))-.5)*.08;
        double parentAtAttach=radius*Math.pow(1-.90*attach/(double)steps,.82);
        double r=Math.min(parentAtAttach*.62,radius*Math.pow(size,1.0/2.35)*.62);
        double len=length*(order==0?.48:.56)*(.82+rand(hash(child,7))*.30);
        double lift=order==0?.22:-.04;
        branch(pts[attach],ang,lift,len,Math.max(.46,r),order+1,child);
      }

      // The parent limb itself must split at its distal tip, rather than
      // reading as a single tapered spike with only lateral offshoots.
      if(order<2) {
        P fork=pts[steps];
        int branchlets=2+(rand(hash(tag,901+order))>(order==0?.62:.76)?1:0);
        if(stats!=null) {
          if(order==0) stats.primaryForks++;
          else stats.secondaryForks++;
        }
        double fan=order==0?.34:.48;
        for(int j=0;j<branchlets;j++) {
          long child=hash(tag,9301+j);
          double centred=j-(branchlets-1)*.5;
          double angle=azimuth+centred*fan+(rand(hash(child,4))-.5)*.14;
          double childLength=length*(order==0?.42:.38)*(.92+rand(hash(child,5))*.16);
          double childRadius=Math.max(.44,radius*(order==0?.43:.38)
                  +(rand(hash(child,6))-.5)*radius*.05);
          double drop=order==0?-.06-rand(hash(child,7))*.14
                  :-.22-rand(hash(child,7))*.18;
          branch(fork,angle,drop,childLength,childRadius,order+1,child);
        }
      }
    }
    void flush(){
      for(Map.Entry<Long,String> e:blocks.entrySet()){
        long key=e.getKey();int x=(int)((key>>>42)&0x1fffffL)-1048576;
        int z=(int)((key>>>21)&0x1fffffL)-1048576;
        int y=(int)(key&0x1fffffL)-1048576;out.set(x,y,z,e.getValue());
      }
    }
  }
  public static void grow(Sink out,Terrain land,int cx,int cz,long worldSeed){growBounded(out,land,cx,cz,worldSeed,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE);}
  public static void growChunk(Sink out,Terrain land,int cx,int cz,long worldSeed,int chunkX,int chunkZ){int x=chunkX<<4,z=chunkZ<<4;growBounded(out,land,cx,cz,worldSeed,x,x+15,z,z+15);}
  private static void growBounded(Sink out,Terrain land,int cx,int cz,long worldSeed,int x0,int x1,int z0,int z1){
    growBounded(out,land,cx,cz,worldSeed,x0,x1,z0,z1,null);
  }
  static GrowthStats growWithStats(Sink out,Terrain land,int cx,int cz,long worldSeed) {
    GrowthStats stats=new GrowthStats();
    growBounded(out,land,cx,cz,worldSeed,Integer.MIN_VALUE,Integer.MAX_VALUE,Integer.MIN_VALUE,Integer.MAX_VALUE,stats);
    return stats;
  }
  private static void growBounded(Sink out,Terrain land,int cx,int cz,long worldSeed,
                                  int x0,int x1,int z0,int z1,GrowthStats stats){
    long s=mix(worldSeed^((long)cx*0x9e3779b97f4a7c15L)^((long)cz*0xc2b2ae3d27d4eb4fL));
    Maker m=new Maker(out,land,s,x0,x1,z0,z1,stats);double ground=land.groundAt(cx,cz);
    P origin=p(cx,ground+1,cz);
    double wind=rand(hash(s,1))*6.283185307179586;
    // Fused old-growth boles: three reiterating leaders emerge at different
    // ages instead of eight limbs hung from a straight central coat hanger.
    P[] nodes=new P[20];nodes[0]=origin;
    P last=origin;
    for(int k=1;k<nodes.length;k++){
      double t=k/(double)(nodes.length-1);
      double h=180*t+12*Math.sin(t*1.8);
      P q=p(cx+Math.cos(wind)*14*t*t+Math.sin(k*.48)*3.0*t,ground+1+h,
            cz+Math.sin(wind)*12*t*t+Math.cos(k*.39)*2.2*t);
      m.segment(last,q,17*Math.pow(1-.82*(t-1.0/19),.82),17*Math.pow(1-.82*t,.82));
      nodes[k]=q;last=q;
    }
    // Willows do NOT make one enormous central taproot. Let the fused bole
    // meet a broad, shallow buttress/root plate; widely separated laterals
    // branch downward into several slim sinkers well AWAY from the trunk.
    // On sloping sites these act as distributed anchors on uphill/downhill
    // sides instead of a straight timber column sunk under the centre.
    // Mature willow root plate: irregular fused buttress webs follow ground
    // instead of eleven identically tapered spokes. Broad proximal flanges
    // split into low wandering anchor roots and short subordinate rootlets.
    // All remain part of one connected living woody skeleton.
    for(int r=0;r<14;r++){
      long rh=hash(s,420+r);
      double a=wind+2.399963*r+(rand(hash(rh,3))-.5)*1.12;
      double reach=28+rand(hash(rh,4))*41;
      double spread=reach*(.23+rand(hash(rh,15))*.10);
      P base=nodes[Math.min(5,1+r%4)];
      int fx=cx+(int)Math.round(Math.cos(a)*spread);
      int fz=cz+(int)Math.round(Math.sin(a)*spread);
      // The flare hugs the LOCAL slope instead of keeping the trunk elevation
      // and floating over downhill ground or vanishing into uphill rock.
      double flareY=land.groundAt(fx,fz)+3.8+rand(hash(rh,16))*2.2;
      P flare=p(cx+Math.cos(a)*spread,flareY,cz+Math.sin(a)*spread);
      double bend=(rand(hash(rh,17))-.5)*.58;
      P toe=p(cx+Math.cos(a+bend)*reach,
              land.groundAt(cx+(int)Math.round(Math.cos(a+bend)*reach),
                            cz+(int)Math.round(Math.sin(a+bend)*reach))+1.6,
              cz+Math.sin(a+bend)*reach);
      double baseRadius=8.5+rand(hash(rh,18))*3.2;
      m.rootSegment(base,flare,baseRadius,3.6+rand(hash(rh,19))*1.3);
      double curve=(rand(hash(rh,23))-.5)*.54;
      double midR=reach*(.61+rand(hash(rh,24))*.12);
      P knee=p(cx+Math.cos(a+curve)*midR,
               land.groundAt(cx+(int)Math.round(Math.cos(a+curve)*midR),
                             cz+(int)Math.round(Math.sin(a+curve)*midR))+2.8,
               cz+Math.sin(a+curve)*midR);
      m.rootSegment(flare,knee,3.6+rand(hash(rh,19))*1.3,2.15);
      m.rootSegment(knee,toe,2.15,.58);
      // Thin, widely spaced sinker roots cross the soil/rock interface 10-18
      // blocks beneath each LOCAL surface; no single centreline taproot.
      int tx=(int)Math.round(toe.x),tz=(int)Math.round(toe.z);
      double sinkDepth=10+rand(hash(rh,52))*8;
      P sinker=p(toe.x+Math.cos(a+bend)*2.5,
                 land.groundAt(tx,tz)-sinkDepth,
                 toe.z+Math.sin(a+bend)*2.5);
      m.rootSegment(knee,sinker,1.55,.48);
      // An independent, slimmer sinker emerges at an intermediate distance.
      // Every sinker is at least a third of the parent lateral reach from the
      // trunk; the deep volume is spread across hillside rock, not piled
      // into a central taproot or a fused subterranean log cylinder.
      double midAngle=a+curve+.14*(rand(hash(rh,59))-.5);
      double midDistance=reach*(.46+rand(hash(rh,60))*.13);
      int mx=cx+(int)Math.round(Math.cos(midAngle)*midDistance);
      int mz=cz+(int)Math.round(Math.sin(midAngle)*midDistance);
      P midSinker=p(mx+Math.cos(midAngle)*1.7,
                    land.groundAt(mx,mz)-(15+rand(hash(rh,61))*8),
                    mz+Math.sin(midAngle)*1.7);
      m.rootSegment(knee,midSinker,1.55,.53);
      // Rough, angled secondary roots grow from the main root wood and return
      // to the actual terrain rather than ending at a common radial radius.
      if(r%3!=2){
        double side=(r%2==0?1:-1)*(.31+rand(hash(rh,20))*.48);
        double sideReach=reach*(.55+rand(hash(rh,21))*.28);
        P foot=p(cx+Math.cos(a+side)*sideReach,
                 land.groundAt(cx+(int)Math.round(Math.cos(a+side)*sideReach),
                               cz+(int)Math.round(Math.sin(a+side)*sideReach))+1.35,
                 cz+Math.sin(a+side)*sideReach);
        m.rootSegment(flare,foot,2.3,.48);
        int sx=(int)Math.round(foot.x),sz=(int)Math.round(foot.z);
        P sideAnchor=p(foot.x+Math.cos(a+side)*2.0,
                       land.groundAt(sx,sz)-(7+rand(hash(rh,53))*8),
                       foot.z+Math.sin(a+side)*2.0);
        m.rootSegment(foot,sideAnchor,1.1,.5);
      }
    }
    // Reiterated leaders grow from living trunk nodes, competing for light.
    for(int i=0;i<4;i++){
      long sh=hash(s,900+i);int attach=7+i*3;
      P p0=nodes[attach];double a=wind+i*2.399963+(.5-rand(hash(sh,3)))*.50;
      P mid=p0.add(Math.cos(a)*(13+i*2),30-i*4,Math.sin(a)*(13+i*2));
      P top=mid.add(Math.cos(a+0.28)*(15+i*2),24+rand(hash(sh,4))*18,Math.sin(a+.28)*(15+i*2));
      double leaderRoot=leaderRootRadius(i,attach);
      double leaderMid=leaderRoot*(i>=2?.52:.638);
      double leaderTip=leaderMid*(i>=2?.26:.365);
      double leaderScale=leaderScale(i,attach);
      if(i==3) m.smoothLeaderJunction(p0,mid,nodes[attach-1],nodes[attach+1],leaderRoot,leaderMid);
      else m.branchSegment(p0,mid,leaderRoot,leaderMid);
      m.branchSegment(mid,top,leaderMid,leaderTip);
      // Primary scaffolds arise along the leaders at staggered heights;
      // asymmetric crowding and self-pruning prevent a symmetric umbrella.
      for(int j=0;j<3;j++){
        long bh=hash(sh,120+j);double angle=a+((j+.3)*2.399963)+(.5-rand(hash(bh,4)))*.83;
        double height=.28+j*.29+rand(hash(bh,5))*.10;
        P start=lerp(mid,top,height);
        double len=72+rand(hash(bh,6))*42-(j==0?8:0);
        double radius=(3.45+rand(hash(bh,9))*1.10)*leaderScale*(i>=2?.72:1.0);
        m.branch(start,angle,.08+rand(hash(bh,8))*.30,len,radius,0,bh);
      }
    }
    // Younger replacement shoots on the main crown, and a handful of
    // storm-broken stubs. Their positions vary by seed and have real parents.
    for(int j=0;j<3;j++){
      long bh=hash(s,81112+j);P start=nodes[9+j*3];
      double a=wind+j*2.399963+rand(hash(bh,2))*.33;
      if(j<2)m.branch(start,a,.48,28+rand(hash(bh,3))*22,2.2,1,bh);
      else m.branchSegment(start,start.add(Math.cos(a)*7,-2,Math.sin(a)*7),2.4,1.1);
    }
    m.flush();
  }
}
