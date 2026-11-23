package ch.k42.metropolis.grid.urbanGrid.forest;

/** Continuous fractured limestone terrain. Biome placement is owned by WildBiome,
 * not by this content field. All habitat, bridges and cave windows are evaluated
 * in the same world-seed coordinate system as terrain relief. */
public final class KarstLandforms {
  private KarstLandforms() {}
  static long mix(long v){v^=v>>>30;v*=0xbf58476d1ce4e5b9L;v^=v>>>27;v*=0x94d049bb133111ebL;return v^(v>>>31);}
  static double unit(long v){return (mix(v)>>>11)*0x1.0p-53;}
  static double clamp(double a){return Math.max(0,Math.min(1,a));}
  static double smooth(double a){a=clamp(a);return a*a*(3-2*a);}
  public static double noise(long seed,double x,double z,double scale,long salt){
    double u=x/scale,v=z/scale;long a=(long)Math.floor(u),b=(long)Math.floor(v);double fx=smooth(u-a),fz=smooth(v-b);
    double aa=unit(mix(seed^mix(a*0x9e3779b97f4a7c15L+b*0xc2b2ae3d27d4eb4fL+salt)));
    double ba=unit(mix(seed^mix((a+1)*0x9e3779b97f4a7c15L+b*0xc2b2ae3d27d4eb4fL+salt)));
    double ab=unit(mix(seed^mix(a*0x9e3779b97f4a7c15L+(b+1)*0xc2b2ae3d27d4eb4fL+salt)));
    double bb=unit(mix(seed^mix((a+1)*0x9e3779b97f4a7c15L+(b+1)*0xc2b2ae3d27d4eb4fL+salt)));
    return (aa+(ba-aa)*fx)*(1-fz)+(ab+(bb-ab)*fx)*fz;
  }
  static double orientation(long seed){return .28+unit(seed^0x51caee21L)*1.15;}
  static double nearest(double v,double period){return Math.abs(v-Math.rint(v/period)*period);}
  static double along(long seed,double x,double z){double a=orientation(seed);return -x*Math.sin(a)+z*Math.cos(a);}
  static double cross(long seed,double x,double z){double a=orientation(seed);return x*Math.cos(a)+z*Math.sin(a);}
  /**
   * Dendritic dissolution valleys follow long warp-meandering drainage axes.
   * Tributaries fork obliquely from those valleys; no repeating right-angle
   * fracture grid or radial impact-bowl primitives. A common distance field
   * drives the relief, floor habitat, cave mouths and natural bridge acceptance.
   */
  public static double distance(long seed,int x,int z){
    double u=cross(seed,x,z),v=along(seed,x,z);
    double warp=46*(noise(seed,v,13,315,0x4d41494eL)-.5)
               +27*(noise(seed,v,29,123,0x4d41494fL)-.5)
               +11*(noise(seed,x,z,56,0x53484452L)-.5);
    double lane=(u+warp)/340.0;
    long laneN=Math.round(lane);
    double major=Math.abs((u+warp)-laneN*340);
    // Side tributaries: branching, curved, finite valley arms from each
    // nearby main drainage. Different ages and lengths break regular spacing.
    double tributary=9999;
    for(int di=-1;di<=1;di++){
      long n=laneN+di;
      double base=n*340.0-warp;
      long slot=Math.round((v+37*n)/250.0);
      for(int ds=-1;ds<=1;ds++){
        long k=slot+ds;
        long h=mix(seed^mix(n*0x9e3779b97f4a7c15L+k*0xc2b2ae3d27d4eb4fL));
        double v0=k*250-37*n+(unit(h)-.5)*58;
        double length=105+unit(h+3)*106;
        double p=(v-v0)/length;
        if(p<-.12||p>1.13)continue;
        // Arc starts embedded in the main canyon then wanders to a shallow
        // dying cleft in the uplands. Side alternates by section and seed.
        double sign=(h&1L)==0?1:-1;
        double bend=(unit(h+5)-.5)*.28;
        double t=clamp(p);
        double lateral=sign*(153*Math.sin(t*1.27)+18*Math.sin(t*4.6+bend));
        double channelU=base+lateral;
        double weight=smooth((p+.12)/.23)*smooth((1.13-p)/.27);
        // Anisotropic distance converts the curved channel into a natural
        // tributary with tapering width toward its dead head.
        double duDv=sign*(153*1.27*Math.cos(t*1.27)+82.8*Math.cos(t*4.6+bend))/length;
        // Fade finite tributaries into the main fracture field at BOTH
        // endpoints. Dropping a branch with a hard p cutoff created a
        // discontinuous distance jump: one neighboring column could be at
        // the ravine floor while the next snapped back to upland height.
        double branchDistance=Math.abs(u-channelU)/Math.sqrt(1+duDv*duDv);
        double d=major+(branchDistance-major)*weight;
        if(d<tributary)tributary=d;
      }
    }
    // Minor, non-periodic shear cracks fringe the main sides but do not
    // themselves create broad circular depressions or crater rings.
    double small= Math.abs(u+warp-laneN*340-94*(noise(seed,x,z,165,0x4c494d45L)-.5));
    // Minor shear cracks are also a smooth field contribution, not a binary
    // noise threshold. The former >.69 switch could change the nearest
    // fracture distance by dozens of blocks across adjacent columns.
    double combined=Math.min(major,tributary);
    double shearWeight=smooth((noise(seed,x,z,230,0x5348454152L)-.60)/.16);
    double withShear=Math.min(combined,small+26);
    return combined+(withShear-combined)*shearWeight;
  }
  /** Canyon cross-sections are broad on the floor and broken at the shoulder. */
  public static double width(long seed,int x,int z){
    return 45+28*noise(seed,x,z,182,0x5749445448L)
             +12*(noise(seed,x,z,67,0x43524f5353L)-.5);
  }
  public static double valley(long seed,int x,int z){
    double d=distance(seed,x,z),w=width(seed,x,z);
    // Broad, nearly flat valley floor, then long graded limestone shoulders. The ramp is deliberately wider than the old narrow escarpment band.
    double interior=1-smooth((d-w*.22)/(w*3.60));
    double ledge=.075*(smooth((d/w-.48)/.035)-smooth((d/w-.59)/.06));
    return clamp(interior-ledge);
  }
  public static double habitat(long seed,int x,int z,double valley){
    double patch=(noise(seed,x,z,39,0x4b415253544cL)-.5)*.085;
    return smooth((valley+patch-.23)/.66);
  }
  public static double habitat(long seed,int x,int z){return habitat(seed,x,z,valley(seed,x,z));}
  /** Broad highland mass: long, low-gradient benches suitable for building,
   * with progressively finer relief that never reduces the massif to narrow fins. */
  static double massifUpland(long seed,int x,int z){
    return 28*(noise(seed,x,z,1800,0x4d415353494631L)-.5)
          +18*(noise(seed,x,z,700,0x4d415353494632L)-.5)
           +8*(noise(seed,x,z,250,0x4d415353494633L)-.5)
           +2.5*(noise(seed,x,z,80,0x4d415353494634L)-.5);
  }
  public static double depth(long seed,int x,int z){
    double v=valley(seed,x,z);
    // Deep dissolution remains mountain-scale, but the lowered floor now grades
    // across broad shoulders rather than dropping through a narrow cliff band.
    return v*(90+8*noise(seed,x,z,220,0x4445505448L));
  }
  /** A bridge is a remnant of the SAME limestone upland on both banks.
   * Its top-view footprint is a constricted, gently crooked waist over the
   * gorge, fanning into broad rocky shoulders in both cliff banks. The
   * previous equal-width strip was a dam-like wall when viewed downstream.
   */
  private static double bridgeOffset(long seed,int x,int z){
    return 2.5*(noise(seed,x,z,355,151)-.5);
  }
  private static double bridgeLong(long seed,int x,int z){
    return nearest(along(seed,x,z)+bridgeOffset(seed,x,z),319);
  }
  private static double bridgeBankRatio(long seed,int x,int z){
    return distance(seed,x,z)/width(seed,x,z);
  }
  /** One natural rock span, with substantial, uneven shoulders embedded in the
   * adjoining valley sides. Width evolves with the canyon walls rather than
   * ending in two straight slab edges. */
  private static double bridgeHalfWidth(double lateral){
    return 17.4+17.5*smooth((lateral-.17)/.88);
  }
  private static double bridgeDepth(long seed,int x,int z,double lateral){
    return bridgeLong(seed,x,z)/bridgeHalfWidth(lateral);
  }
  /** Erosion envelope reaches beyond the arch proper into the original cliff
   * sides. It is seed-gated exactly like the bridge, so it cannot leave a
   * repeating row of groomed cliff formations where bridges are absent. */
  private static double bridgeErosionEnvelope(long seed,int x,int z){
    double v=along(seed,x,z)+bridgeOffset(seed,x,z);
    long lane=Math.round(cross(seed,x,z)/340.0);
    long section=Math.round(v/319.0);
    if(unit(mix(seed^mix(lane*0x9e3779b97f4a7c15L+section*0xc2b2ae3d27d4eb4fL)))<.12)return 0;
    return 1-smooth((nearest(v,319)-22.0)/41.0);
  }
  /** Irregular scallops on the real limestone banks. These are coherent
   * world-space erosional ledges, not sinusoidal rings around the opening.
   * They modify the UNBRIDGED and bridged terrain alike so the ravine grade
   * continues unaltered beneath the span. */
  private static double approachErosion(long seed,int x,int z,double r){
    double envelope=bridgeErosionEnvelope(seed,x,z);
    if(envelope<=0)return 0;
    double cliff=smooth((r-.39)/.29)*(1-smooth((r-1.42)/.37));
    double large=(noise(seed,x,z,43,0x524f434b53484cL)-.5)*6.2;
    double gullies=(noise(seed,x,z,18,0x47554c4c494553L)-.5)*3.8;
    double broken=(noise(seed,x,z,8.5,0x53484f554c4452L)-.5)*1.5;
    // A talus toe with irregular height instead of the old uniformly steep
    // vertical bank face. The rim remains rugged, not planed into a ramp.
    double toe=smooth((r-.37)/.20)*(1-smooth((r-.79)/.31));
    return envelope*(cliff*(large+gullies+broken)+toe*(3.2+2.1*noise(seed,x,z,31,0x544f4552494dL)));
  }
  public static double archWeight(long seed,int x,int z){
    return KarstSpanVolume.coverage(seed,x,z);
  }
  private static double oldKarstArchWeight(long seed,int x,int z){
    double lateral=bridgeBankRatio(seed,x,z);
    if(lateral>1.10)return 0;
    double v=along(seed,x,z)+bridgeOffset(seed,x,z);
    double span=bridgeHalfWidth(lateral);
    double center=nearest(v,319);
    long lane=Math.round(cross(seed,x,z)/340.0);
    long section=Math.round(v/319.0);
    if(center>=span||unit(mix(seed^mix(lane*0x9e3779b97f4a7c15L+section*0xc2b2ae3d27d4eb4fL)))<.12)return 0;
    double shoulder=smooth((1.10-lateral)/.25);
    double taper=smooth((span-center)/(span*.40));
    return shoulder*taper;
  }
  /** Frozen R30dh dissolution window used by Bloomlands legacy crevasses.
   * Karst bridge redesign MUST NOT silently reshape the approved Bloomlands. */
  private static double bloomArchWeight(long seed,int x,int z){
    double d=distance(seed,x,z),w=width(seed,x,z);if(d>w*1.08)return 0;
    double v=along(seed,x,z),offset=19*(noise(seed,x,z,410,151)-.5);
    double center=nearest(v+offset,319);
    long lane=Math.round(cross(seed,x,z)/340.0);
    long section=Math.round((v+offset)/319.0);
    if(center>13.5||unit(mix(seed^mix(lane*0x9e3779b97f4a7c15L+section*0xc2b2ae3d27d4eb4fL)))<.12)return 0;
    return smooth((13.5-center)/5.5)*smooth((w*1.08-d)/(w*.20));
  }
  /** Wide rock shoulders extend into an old, irregularly eroded valley lip
   * rather than meeting an unmodified high cliff at a crisp perpendicular
   * joint. Both sides use their OWN coordinate-dependent bank relief; this
   * changes the ravine bed on both sides of the crossing consistently. */
  private static double shoulderDepth(long seed,int x,int z,double r){
    double e=bridgeErosionEnvelope(seed,x,z);
    if(e<=0)return 0;
    double valleyOriginal=valley(seed,x,z);
    double gentler=1-smooth((r-.27)/.99);
    double shoulderMask=smooth((r-.37)/.20)*(1-smooth((r-1.22)/.35));
    double irregular=.67+.25*noise(seed,x,z,67,0x534849465453L);
    double amount=e*shoulderMask*irregular;
    return (gentler-valleyOriginal)*(43+23*noise(seed,x,z,220,0x4445505448L))*amount;
  }
  /** Broad differential weathering around a NATURAL landbridge.
   * Previous revisions only chiseled below an unchanged upper elevation,
   * producing a featureless hanging wall. This changes the bedrock MASS:
   * old cliff lip is beveled down into the valley, with asymmetric benches,
   * spur outcrops, and joint-fed collapse scars that fade into upland. */
  private static double bridgeRockMass(long seed,int x,int z,double r){
    double v=bridgeLong(seed,x,z);
    double e=bridgeErosionEnvelope(seed,x,z);
    if(e<=0)return 0;
    double cliff=smooth((r-.51)/.23)*(1-smooth((r-1.41)/.42));
    // A long weathered slope starting where the central opening meets the
    // rock-bearing haunch, not only beyond the bridge's outer footprint.
    double bench=smooth((r-.54)/.15)*(1-smooth((r-1.12)/.31));
    double ridges=(noise(seed,x,z,45,0x524944474552L)-.50)*10
                 +(noise(seed,x,z,17,0x45524f444544L)-.50)*6;
    double collapse=15.5+6.0*noise(seed,x,z,61,0x524f434b4d415353L);
    // A differently angled, finite fracture across ONE shoulder avoids a
    // mirror-symmetric arch with equally perfect sidewalls.
    double broken=5.0*(noise(seed,x,z,33,0x5348454152464cL)-.5);
    // Retain a naturally connected upper passage through the middle.
    // The adjoining exposed HAUNCHES can erode more deeply than the narrow
    // walking spine. This avoids an artificial impassable shelf at v=0.
    double spine=smooth((v-3.0)/27.0);
    return -e*spine*(cliff*(8.0+ridges+broken)+bench*collapse);
  }
  public static double unbridgedRelief(long seed,int x,int z){
    return naturalKarstRelief(seed,x,z);
  }
  public static double relief(long seed,int x,int z,boolean bloom){
    return relief(seed,x,z,bloom,true);
  }
  /** The same karst mass field with its bridge remnant independently gated
   * by the owning forest region. Shore and incomplete parcel footprints pass
   * bridge=false while retaining the ordinary massif and drainage relief. */
  public static double relief(long seed,int x,int z,boolean bloom,boolean bridge){
    return reliefInternal(seed,x,z,bloom,bridge);
  }
  /** Original fractured-valley surface, with NO remnants of the rejected
   * raised-strip, haunch cuts or shoulder relief modifications. */
  private static double naturalKarstRelief(long seed,int x,int z){
    double r=distance(seed,x,z)/width(seed,x,z);
    double upland=massifUpland(seed,x,z);
    double ledge=5.2*(smooth((r-.52)/.08)-smooth((r-.67)/.11))
                 *(.6+noise(seed,x,z,63,0x4c454447L));
    double shoulder=5.1*(smooth((r-.84)/.07)-smooth((r-1.13)/.22));
    double terrace=smooth((r-.53)/.12)*(1-smooth((r-.91)/.18))
                 *(noise(seed,x,z,73,0x42524f4b454eL)-.35)*4.5;
    double talus=smooth((r-.81)/.17)*(1-smooth((r-1.46)/.32))
                 *(noise(seed,x,z,33,0x54414c55534dL)-.5)*4.6;
    double rubble=(noise(seed,x,z,22,0x43484950L)-.5)*6*(1-smooth((r-1.27)/.38));
    double old=upland+ledge+shoulder+terrace+talus+rubble-depth(seed,x,z);
    // R30dq: grade the NATURAL bedrock at each abutment as a broad, differentially
    // weathered valley shoulder. The old narrow heightmap escarpment produced
    // near-vertical faceless walls beside every bridge, regardless of the 3D span.
    // This is a finite structural bank transition, not a random texture overlay.
    double tt=along(seed,x,z)+3*(noise(seed,x,z,220,0x5350414e41584953L)*2-1);
    double offset=nearest(tt,319);
    double envelope=1-smooth((offset-25)/83.0);
    double bank=smooth((r-.10)/.65)*(1-smooth((r-1.18)/1.10));
    double broad=1-smooth((r-.18)/2.20);
    double ledges=1.9*(noise(seed,x,z,54,0x53484f554c4445L)-.5)
                 +2.5*(noise(seed,x,z,26,0x4c454447455aL)-.5);
    double grade=upland+ledge*.60+shoulder*.25+terrace*.5+talus*.55+rubble*.35
                  -broad*(90+8*noise(seed,x,z,220,0x4445505448L))+ledges;
    // Karst is a mountain range, not a lowland pockmark field. Lift the
    // entire limestone massif first; the broad, deep dissolution channels
    // then cut back down toward the ordinary terrain datum. The upland noise
    // remains deliberately low-frequency so the crests read as broad,
    // gently rolling tablelands rather than isolated round hills.
    return 90.0 + old+(grade-old)*envelope*bank*.87;
  }
  /** Crucially, the underpass floor uses the terrain BEFORE the uplift,
   * rather than guessing its elevation from the current bridge thickness.
   * This makes the lower ravine genuinely continuous through the span. */
  private static double reliefInternal(long seed,int x,int z,boolean bloom,boolean bridge){
    if(!bloom) {
      double natural=naturalKarstRelief(seed,x,z);
      return bridge?KarstSpanVolume.upperRelief(seed,x,z,natural):natural;
    }
    double d=distance(seed,x,z),w=width(seed,x,z),v=valley(seed,x,z);
    double upland=17*(noise(seed,x,z,240,0x55504c414e44L)-.5)
                 +10*(noise(seed,x,z,102,0x5249444745L)-.5)
                 +5*(noise(seed,x,z,36,0x42524f4bL)-.5);
    double r=d/w;
    double ledge=5.2*(smooth((r-.52)/.08)-smooth((r-.67)/.11))
                 *(0.6+noise(seed,x,z,63,0x4c454447L));
    double shoulder=5.1*(smooth((r-.84)/.07)-smooth((r-1.13)/.22));
    double terraceMask=smooth((r-.53)/.12)*(1-smooth((r-.91)/.18));
    double terrace=terraceMask*(noise(seed,x,z,73,0x42524f4b454eL)-.35)*4.5;
    double talusMask=smooth((r-.81)/.17)*(1-smooth((r-1.46)/.32));
    double talus=talusMask*(noise(seed,x,z,33,0x54414c55534dL)-.50)*4.6;
    double rubble=(noise(seed,x,z,22,0x43484950L)-.5)*6*(1-smooth((r-1.27)/.38));
    double arch=bridge?(bloom?bloomArchWeight(seed,x,z):archWeight(seed,x,z)):0;
    // A FLAT-TOPPED limestone remnant: preserve the surrounding high-ground
    // datum, suppress only short-wavelength scarps over its middle, and do
    // not pile a domed, eight-block hump on top of an otherwise level span.
    // The junctions gradually recover the surrounding broken cliff relief.
    double bearing=smooth((arch-.18)/.72);
    double rockIrregularity=bloom?1.0:1-.72*bearing;
    double topCrown=(!bloom && bridge)?1.25*arch*smooth((.94-r)/.78):0;
    return upland+(ledge+shoulder+terrace+talus+rubble)*rockIrregularity
           -(depth(seed,x,z)+(bloom?0:shoulderDepth(seed,x,z,r)))*(1-arch)
           +(bloom?2.0:3.0)*arch+topCrown
           +(bloom?0:approachErosion(seed,x,z,r)*(1-smooth((arch-.71)/.26)))
           +(bloom?0:bridgeRockMass(seed,x,z,r));
  }
  /** Absolute pre-bridge ground height (including all ordinary limestone
   * noise), reconstructed by subtracting the exact local artificial uplift.
   * No artificial sill, detached lower gallery, or vegetation on the deck. */
  public static int bridgeFloor(long seed,int x,int z,int topY,int baseY,int minimumFloorY){
    if(archWeight(seed,x,z)<.10)return -1;

    double natural=naturalKarstRelief(seed,x,z);
    double raised=KarstSpanVolume.upperRelief(seed,x,z,natural);
    int expectedTop=baseY+(int)Math.round(raised);

    // The arch floor belongs at the ORIGINAL build-height datum plus the
    // unbridged drainage relief. Never infer it from an arbitrarily faded or
    // partially selected biome column: that was the source of cuts extending
    // down to Y=4 in mixed/edge columns.
    if(Math.abs(topY-expectedTop)>8)return -1;
    int floor=baseY+(int)Math.round(natural);
    if(floor<minimumFloorY || expectedTop-floor<40)return -1;
    return floor;
  }

  /** Legacy diagnostic overload uses the historical default base datum. */
  public static int bridgeFloor(long seed,int x,int z,int topY){
    return bridgeFloor(seed,x,z,topY,65,57);
  }
  /** R30dl: ONE GENTLY CURVED 3-D VAULT, WITHOUT A WAVY UNDERSIDE.
   *
   * The primary section is a single cosine-eased thickness curve across
   * the gorge, referenced from the nearly-level top surface instead of the
   * natural floor (which rises sharply at each bank). The underside slopes
   * consistently down from its broad crown into the load-bearing rock, and
   * rolls only gently through the bridge depth with a broad quadratic sweep.
   * Independent sinusoids, scallops and high-frequency noise are excluded.
   *
   * The floor is always reconstructed from the untouched valley field.
   * World-space, pure functions keep adjacent chunks deterministic.
   */
  private static int bridgeCeilingRaw(long seed,int x,int z,int topY){
    double weight=archWeight(seed,x,z);
    if(weight<.008)return -1;
    double lateral=bridgeBankRatio(seed,x,z);
    if(lateral>=.88)return -1; // massive, natural stone haunches
    int floor=bridgeFloor(seed,x,z,topY);
    int uplift=topY-floor;
    if(uplift<6)return -1;
    double depth=bridgeDepth(seed,x,z,lateral);
    // Completely remove the low-mass fringe that would otherwise become
    // hanging piers; preserve the stable, central rocky roof and crossing.
    if(lateral<.82 && (weight<.97 || (lateral>=.45 && weight<.986)))return topY;
    // A one-direction, cosine-eased ROCK-THICKNESS curve.  The intrados
    // follows the reasonably level upland datum above, NOT the deeply
    // varying floor below.  The floor determines clearance only.  This
    // avoids the old ceiling bobbing down/up again where gorge banks rise.
    // Unlike a fixed-width extruded hole, each cross section still changes
    // gently over the bridge depth via the broad squared depth factor.
    double t=clamp(lateral/.79);
    double bankBlend=.5-.5*Math.cos(Math.PI*t);
    double roofThickness=10.0+23.5*bankBlend+1.9*depth*depth;
    double rawU=cross(seed,x,z);
    double signedU=(rawU-Math.rint(rawU/340.)*340.)/width(seed,x,z);
    double rawV=along(seed,x,z)+bridgeOffset(seed,x,z);
    double signedV=rawV-Math.rint(rawV/319.)*319.;
    // Two asymmetrical concave weathering pockets, fading into one vault.
    double pockets=7.5*Math.exp(-Math.pow((signedU-.45)/.28,2)-Math.pow((signedV+4)/15.,2))
                  +6.0*Math.exp(-Math.pow((signedU+.42)/.26,2)-Math.pow((signedV-9)/13.,2));
    double rough=0.0;
    // Taper the intrados into the portal rather than ending a full-thickness
    // roof at one isolated column. The six-block bearing lip stays mineable
    // and the central rock roof recovers its full natural thickness.
    int ceiling=topY-(int)Math.ceil(Math.max(8.0,roofThickness-pockets+rough));
    if(ceiling-floor<4)return -1; // solid shoulder, no floating rock sill
    return ceiling;
  }
  /** A one-cell stalactite at the open-portal seam is erased without
   * opening the rest of the load-bearing bridge. Raw field samples, not
   * recursive neighbor repairs, keep it seed/chunk-order deterministic. */
  public static int bridgeCeiling(long seed,int x,int z,int topY){
    int floor=bridgeFloor(seed,x,z,topY);
    if(floor<0)return -1;
    int undercut=KarstSpanVolume.undercutCeiling(seed,x,z,topY,floor);
    return undercut>floor?undercut:-1;
  }
  private static int oldKarstBridgeCeiling(long seed,int x,int z,int topY){
    int y=bridgeCeilingRaw(seed,x,z,topY);
    if(y<0||y>=topY-6)return y;
    double w=archWeight(seed,x,z);
    if(w<.97||w>.994)return y; // only the weathered opening seam
    double datum=relief(seed,x,z,false);
    int high=0,veryHigh=0;
    for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
      int nx=x+d[0],nz=z+d[1];
      int nh=topY+(int)Math.round(relief(seed,nx,nz,false)-datum);
      int n=bridgeCeilingRaw(seed,nx,nz,nh);
      if(n>=y+3)high++;
      if(n>=y+7)veryHigh++;
    }
    return (high>=3||(veryHigh>=2 && w<.985))?Math.min(topY-6,y+4):y;
  }
  /** The cliff-face sculpting continues OUTSIDE the central rock opening;
   * otherwise a beautiful underside still meets a raw vertical heightmap wall.
   * Erosion shares the same candidate/seed gate as the landbridge. */
  public static boolean bridgeApproachActive(long seed,int x,int z){
    double r=bridgeBankRatio(seed,x,z);
    return r>=.48&&r<=1.25&&bridgeErosionEnvelope(seed,x,z)>.23;
  }
  /** Genuine XYZ abrasion of the rock shoulder and exposed canyon wall.
   * At low elevations the cavity tapers OUT into the cliff, forming eroded
   * buttresses. At roof elevation the rock remains thick and load-bearing.
   * Noise is coherent in all three spatial directions. */
  public static boolean erodeBridgeShoulder(long seed,int x,int y,int z,int topY,int floor,int ceiling){
    if(y<=68||y>=topY-8)return false;
    double r=bridgeBankRatio(seed,x,z);
    if(r<.48||r>1.25)return false;
    double nearby=bridgeErosionEnvelope(seed,x,z);
    if(nearby<.24)return false;
    // The cavity expands into the lower bank, not at the bridge deck.
    double descent=smooth((topY-y-11.0)/34.0);
    double target=.72+.27*descent;
    // Abrasion weakens toward the outer ends and along the valley bends.
    target-=.085*(1-nearby);
    // Large, coherent fracture pockets on the actual supporting rock flank,
    // concentrated below (not through) the walkable crossing. The threshold
    // varies independently with Y so the side is not a vertical extrusion.
    double v=bridgeLong(seed,x,z);
    double front=1-smooth((v-19)/29.0);
    double below=topY-y;
    double niche=smooth((below-9)/10.0)*(1-smooth((below-35)/18.0));
    double fissure=noise(seed,x+y*.32,z-y*.23,17.0,0x504f434b455453L);
    target+=(.12+.19*fissure)*front*niche;
    double basin=noise(seed,x+y*.33,z-y*.29,20,0x5349444557414c4cL);
    double cracks=noise(seed,x-y*.17,z+y*.53,9.5,0x4c494d45504f434bL);
    target+=.11*(basin-.5)*2+.045*(cracks-.5)*2;
    if(r>=target)return false;
    // Protect the whole natural lower ground, not an artificial platform.
    // In the cliff beyond the raised bridge the original ravine has a higher
    // surface; keep a basal rock foot rather than a through-going tunnel.
    int safeBase=floor>=0?floor:Math.max(65,topY-34);
    if(y<=safeBase+6)return false;
    if(ceiling>=0 && ceiling<topY && y>ceiling+9)return false;
    return true;
  }
  public static int ceiling(long seed,int x,int z,int topY){
    if(bloomArchWeight(seed,x,z)<.87)return -1;
    double inset=distance(seed,x,z)/width(seed,x,z);
    if(inset>=.85)return -1;
    double curve=Math.sqrt(Math.max(0,1-inset*inset/.85/.85));
    return topY-7-(int)Math.round((1-curve)*16);
  }
  public static int floor(long seed,int x,int z,int topY){
    return topY-(int)Math.round(26+7*noise(seed,x,z,178,187));
  }
  /** Short knobbly undercut cave mouths inside cliff faces. Do not carve the
   * upper surface, flood a valley, or make a long straight cave system. */
  public static int caveCeiling(long seed,int x,int z,int topY){
    double r=distance(seed,x,z)/width(seed,x,z);
    if(r<.52||r>1.07)return -1;
    double along=along(seed,x,z)+13*(noise(seed,x,z,70,223)-.5);
    double center=nearest(along+19*noise(seed,x,z,210,224),181);
    if(center>7.5||noise(seed,x,z,280,225)<.44)return -1;
    double mouth=smooth((7.5-center)/3.5);
    if(mouth<.48)return -1;
    return topY-5-(int)Math.round((1-mouth)*4);
  }
  public static int caveFloor(long seed,int x,int z,int topY){
    return topY-11-(int)Math.round(2*noise(seed,x,z,30,226));
  }
}
