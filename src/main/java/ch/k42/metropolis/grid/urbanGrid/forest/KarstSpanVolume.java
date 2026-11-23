package ch.k42.metropolis.grid.urbanGrid.forest;

/**
 * R30dq trial B: an ANATOMICALLY SWEPT, variable-gap limestone remnant.
 *
 * NOT an uplifted rectangular strip and NOT a heightfield with a tunnel cut
 * through it. Each bank pair is joined by a weathered, superelliptic rock
 * cross-section whose vertical and riverwise radii adapt to canyon width.
 * Its lower and upper *surfaces* are opposite halves of the same volumetric
 * mass. Occupancy therefore vanishes to a rounded edge instead of creating
 * rectangular cliff faces or a wave extruded through a wall.
 *
 * World-space pure functions are stable under asynchronous chunk ordering.
 * The pre-bridge ravine floor exists independently under the whole formation.
 */
public final class KarstSpanVolume {
    private KarstSpanVolume() {}
    private static final long A=0x9e3779b97f4a7c15L,B=0xc2b2ae3d27d4eb4fL;
    private static double clamp(double x){return Math.max(0,Math.min(1,x));}
    private static double smooth(double x){x=clamp(x);return x*x*(3-2*x);}
    private static double lerp(double a,double b,double u){return a+(b-a)*u;}
    private static double n(long seed,int x,int z,double scale,long salt){
        return 2*KarstLandforms.noise(seed,x,z,scale,salt)-1;
    }
    private static double hash(long s,int a,int b,int c,long salt){
        return 2*KarstLandforms.unit(KarstLandforms.mix(s^KarstLandforms.mix(
           (long)a*A+(long)b*B+(long)c*0x165667b19e3779f9L+salt)))-1;
    }
    /** Coherent 3D limestone joint field sampled on the actual underside. */
    private static double noise3(long seed,double x,double y,double z,double scale,long salt){
        double xx=x/scale,yy=y/scale,zz=z/scale;
        int ax=(int)Math.floor(xx),ay=(int)Math.floor(yy),az=(int)Math.floor(zz);
        double u=smooth(xx-ax),v=smooth(yy-ay),w=smooth(zz-az),res=0;
        for(int a=0;a<=1;a++)for(int b=0;b<=1;b++)for(int c=0;c<=1;c++)
            res+=lerp(1-u,u,a)*lerp(1-v,v,b)*lerp(1-w,w,c)*hash(seed,ax+a,ay+b,az+c,salt);
        return res;
    }
    private static boolean eligible(long seed,int x,int z){
        double av=KarstLandforms.along(seed,x,z)+axisWarp(seed,x,z);
        long lane=Math.round(KarstLandforms.cross(seed,x,z)/340.0);
        long section=Math.round(av/319.0);
        return KarstLandforms.unit(KarstLandforms.mix(seed^KarstLandforms.mix(lane*A+section*B)))>=.12;
    }
    private static double axisWarp(long seed,int x,int z){
        return 3*n(seed,x,z,220,0x5350414e41584953L);
    }
    private static double across(long seed,int x,int z){
        double a=KarstLandforms.along(seed,x,z)+axisWarp(seed,x,z);
        return a-Math.rint(a/319.0)*319.0;
    }
    private static double gapRatio(long seed,int x,int z){
        return KarstLandforms.distance(seed,x,z)/Math.max(1,KarstLandforms.width(seed,x,z));
    }
    private static double horizontalRadius(long seed,int x,int z,double r){
        double gap=KarstLandforms.width(seed,x,z);
        // Radius expands with the target gap and into the geologic abutments.
        double radius=(16.2+.075*gap)+(9.0+.075*gap)*smooth((r-.16)/.91);
        return radius+1.4*n(seed,x,z,81,0x5350414e52414449L);
    }
    private static double verticalRadius(long seed,int x,int z,double r){
        double gap=KarstLandforms.width(seed,x,z);
        // Thin keystone at midspan, thick natural rock that embeds in banks.
        double center=8.6+.058*gap;
        return center+(16.0+.12*gap)*smooth((r-.12)/.89)
              +1.2*n(seed,x,z,60,0x4c494d4552494242L);
    }
    private static double skew(long seed,int x,int z,double r){
        return 3.4*n(seed,x,z,112,0x4c41544552414c4cL)*(1-.3*smooth((r-.7)/.6));
    }
    private static double exponent(long seed,int x,int z){
        return 3.65+.44*n(seed,x,z,170,0x5355504552454c4cL);
    }
    private static double halfHeight(long seed,int x,int z,double r){
        double v=Math.abs(across(seed,x,z)+skew(seed,x,z,r));
        double radius=horizontalRadius(seed,x,z,r);
        double q=v/Math.max(1,radius);
        if(q>=.985)return 0; // no isolated one-block rock fins at the rim
        double fac=1-Math.pow(q,exponent(seed,x,z));
        double h=verticalRadius(seed,x,z,r)*Math.sqrt(Math.max(0,fac));
        return h>=3.35?h:0; // weathering eliminates paper-thin loose flakes
    }
    private static double crownDatum(long seed,int x,int z,double natural){
        // IMPORTANT: crest is tied to actual highland rock, NOT natural+depth.
        // At the gorge margins natural relief already contains much of the
        // upland altitude; adding the full canyon depth there created the
        // old gigantic artificial retaining wall.
        double upland=17*(KarstLandforms.noise(seed,x,z,240,0x55504c414e44L)-.5)
                     +10*(KarstLandforms.noise(seed,x,z,102,0x5249444745L)-.5)
                      +5*(KarstLandforms.noise(seed,x,z,36,0x42524f4bL)-.5);
        double broad=.8*n(seed,x,z,91,0x4445434b46414345L);
        return upland+2.0+broad;
    }
    private static double centerHeight(long seed,int x,int z,double natural,double r){
        return crownDatum(seed,x,z,natural)-verticalRadius(seed,x,z,r);
    }
    /** Exposed crown. No artificially raised vertical rock wall exists. */
    public static double upperRelief(long seed,int x,int z,double natural){
        if(!eligible(seed,x,z))return natural;
        double r=gapRatio(seed,x,z);
        if(r>=1.23)return natural;
        double hh=halfHeight(seed,x,z,r);
        if(hh<=0)return natural;
        // R30dr: the surviving limestone is an ERODED SHELF, not a hump
        // tracing the round underside. Its roof is the local highland datum,
        // continuous through the gap; the swept-volume section governs the
        // underside separately. This keeps the approved R30dq open vault while
        // making the view from above read as unbroken plateau geology.
        double q=Math.abs(across(seed,x,z)+skew(seed,x,z,r))
                 /Math.max(1,horizontalRadius(seed,x,z,r));
        double highland=crownDatum(seed,x,z,natural);
        // Round only the outside rim. The middle 78 percent of the span is
        // genuine shelf: no decorative elliptical hump over the opening.
        double rim=smooth((q-.78)/.205);
        double roundedDrop=Math.max(0,verticalRadius(seed,x,z,r)-hh);
        // R30ds: differential dissolution on the SHELF, not arch-shaped uplift.
        // Broad bedding undulations and short joint-fed dissolution depressions
        // are independent of the swept vault's cross-section. At the edges
        // they fade away so the shelf still joins its original rock banks.
        double shelfMask=smooth((.98-r)/.24)*(1-smooth((q-.70)/.21));
        double bedding=1.35*n(seed,x,z,53,0x5348454c46424544L)
                      +.48*n(seed,x,z,17,0x4a4f494e5453414cL);
        double pits=Math.max(0,n(seed,x,z,27,0x4b415252454e4eL)-.22)*.85;
        double top=highland-roundedDrop*rim+shelfMask*(bedding-pits);
        // Blend only where the shelf is physically seated in the banks, not
        // halfway across the ravine. This replaces the R30dq long approach
        // grade that gave the bridge an unmistakable arched top silhouette.
        double attachment=1-smooth((r-.84)/.39);
        return natural+Math.max(0,top-natural)*attachment;
    }
    public static double coverage(long seed,int x,int z){
        if(!eligible(seed,x,z))return 0;
        double r=gapRatio(seed,x,z);
        if(r>=1.23)return 0;
        double hh=halfHeight(seed,x,z,r);
        if(hh<3.35)return 0;
        double topRadius=verticalRadius(seed,x,z,r);
        return (1-smooth((r-1.02)/.21))*clamp(hh/topRadius);
    }
    /** World floor prior to growth; the ravine survives unchanged below. */
    public static int floor(long seed,int x,int z,int topY,double natural){
        return topY-(int)Math.round(Math.max(0,upperRelief(seed,x,z,natural)-natural));
    }
    /** Highest block removed from the natural uplift, calculated ONCE per
     * column rather than performing 3-D noise and canyon search on every Y.
     * The returned value is never below the original ravine floor.
     */
    public static int undercutCeiling(long seed,int x,int z,int topY,int floor){
        if(floor>=topY || !eligible(seed,x,z))return floor;
        double r=gapRatio(seed,x,z);
        if(r>=1.23)return floor;
        double hh=halfHeight(seed,x,z,r);
        if(hh<=0)return floor;
        double center=topY-hh;
        double nominalBottom=center-hh;
        double eroded=1.6*noise3(seed,x,nominalBottom,z,20,0x554e444552534944L)
                     +.75*noise3(seed,x,nominalBottom,z,8.8,0x524f434b4a4f494eL);
        return Math.max(floor,Math.min(topY,(int)Math.ceil(nominalBottom-eroded)-1));
    }
    /** Equivalent pointwise occupancy for geometry tests and debug views. */
    public static boolean retainRock(long seed,int x,int y,int z,int topY,int floor){
        if(y<=floor)return true;
        if(y>topY)return false;
        return y>undercutCeiling(seed,x,z,topY,floor);
    }
    /** Quick spatial gate evaluated once per column by the block painter. */
    public static boolean bankInRange(long seed,int x,int z){
        if(!eligible(seed,x,z))return false;
        double r=gapRatio(seed,x,z),v=Math.abs(across(seed,x,z));
        return r>=.59&&r<=1.32&&v>=19&&v<=90;
    }
    /**
     * Bank scallops formed by dissolution intersecting exposed limestone,
     * rather than random 1-block protrusions. Large overlapping scoops with
     * distinct elevation and offset on each bank alter the physical mass in
     * all XYZ dimensions; they fade entirely away from an active span.
     */
    public static boolean erodeBank(long seed,int x,int y,int z,int topY,int floor){
        if(!eligible(seed,x,z))return false;
        double r=gapRatio(seed,x,z),v=across(seed,x,z);
        if(r<.59||r>1.32||Math.abs(v)>90||Math.abs(v)<19)return false;
        if(y<=floor+8||y>=topY-3)return false;
        double a=KarstLandforms.cross(seed,x,z);
        double side=(a-Math.rint(a/340.0)*340.0)>=0?1:-1;
        double jitter=2.3*noise3(seed,x,y,z,29,0x4c494d455343414cL);
        double ty=topY-y;
        double p= (r-(.84+.07*side))/.265;
        double t= (Math.abs(v)-(37+4*side))/32.0;
        double h= (ty-(16+3*side+jitter))/19.0;
        double main=p*p+t*t+h*h;
        double p2=(r-(1.03-.08*side))/.24;
        double t2=(Math.abs(v)-(61-3*side))/26.0;
        double h2=(ty-(11-2*side+jitter))/15.0;
        return main<.83 || p2*p2+t2*t2+h2*h2<.76;
    }
}
