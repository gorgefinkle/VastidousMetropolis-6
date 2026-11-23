import ch.k42.metropolis.plugin.PluginConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.*;
import ch.k42.metropolis.grid.urbanGrid.layout.*;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import java.lang.reflect.Field;
import java.util.*;
/** Integrated deterministic road/district audit. Not a claim about in-world schematic painting. */
public final class R11RoadMatrixAudit {
  static void set(String n,Object v)throws Exception {Field f=PluginConfig.class.getDeclaredField(n); f.setAccessible(true); if(v instanceof Boolean)f.setBoolean(null,(Boolean)v);else if(v instanceof Integer)f.setInt(null,(Integer)v);else if(v instanceof Double)f.setDouble(null,(Double)v);}
  static void setup()throws Exception {
    set("blockSize",14);set("blockSizeMin",7);set("blockSizeSlack",3);set("cityRings",true);set("highwayNetwork",true);
    Field o=OceanConfig.class.getDeclaredField("oceanEnabled");o.setAccessible(true);o.setBoolean(null,true);
    OceanConfig.setOceanSizeTiles(3.3);OceanConfig.setOceanMaxDistanceFromSpawn(30);OceanConfig.setOceanStretch(5.0);
    OceanConfig.setOceanWarpFraction(.25);OceanConfig.setOceanBayFraction(.26);OceanConfig.setOceanInletFraction(.14);
    OceanConfig.setOceanIslandFraction(.42);OceanConfig.setOceanCoastJitterBlocks(7.0);OceanConfig.setOceanCoastJitterFrequency(.02);
    OceanConfig.setOceanEdgeNoiseFrequency(3.0);OceanConfig.setOceanEdgeNoiseOctaves(4);OceanConfig.setOceanBankMargin(3);
  }
  static boolean road(int k){return k==WorldLayout.STREET||k==WorldLayout.LANE;}
  static boolean builtRoad(WorldLayout l, RoadShapes s, int x,int z){return (l.kindAt(x,z)==WorldLayout.STREET||l.kindAt(x,z)==WorldLayout.LANE)&&s.opennessAt(x,z)!=0;}
  static int activeRows, activeCols, starts, misses, crossing, cities, frontage0, totalBlocks, streetDoubles, mixedDoubles, highwayDoubles, disconnected, deadEnds, mismatch;
  static ArrayList<String> samples=new ArrayList<>();
  static void sample(String s){if(samples.size()<50)samples.add(s);}
  static void run(long seed,int x0,int z0,int w,int h,String mode, boolean reverse) throws Exception {
    WorldLayout l=WorldLayouts.forSeed(seed);
    l.setZoner((bx,bz,sx,sz,minX,minZ,maxX,maxZ)->{
      ContextType[] o=new ContextType[bx.length];
      for(int i=0;i<o.length;i++) {
        if(mode.equals("urban"))o[i]=ContextType.RESIDENTIAL;
        else {int x=bx[i]+sx[i]/2,z=bz[i]+sz[i]/2;double v=Math.sin(x*.018)+Math.cos(z*.013)+Math.sin((x+z)*.005);
          o[i]=v>1?ContextType.FOREST:(v>-.1?ContextType.FARM:ContextType.RESIDENTIAL);}
      }
      return o;
    });
    RoadShapes s=WorldLayouts.roadShapesFor(seed);
    final int W=w+2,H=h+2; byte[][] kind=new byte[W][H], open=new byte[W][H];
    for(int zz=0;zz<H;zz++)for(int xx=0;xx<W;xx++){
      int x=reverse?(x0+w-xx):(x0+xx-1),z=reverse?(z0+h-zz):(z0+zz-1);
      int i=x-x0+1,j=z-z0+1;
      kind[i][j]=(byte)l.kindAt(x,z);
      if(road(kind[i][j]))open[i][j]=(byte)s.opennessAt(x,z);
    }
    int ss=0,mm=0,hh=0, ends=0,mismatch0=0, active=0;
    for(int z=1;z<=h;z++)for(int x=1;x<=w;x++){
      int k=kind[x][z], xx=x+x0-1, zz=z+z0-1;
      if(road(k)&&open[x][z]!=0){
        active++;
        int links=0;
        int[] dx={0,1,0,-1},dz={-1,0,1,0};
        for(int a=0;a<4;a++){
          boolean f=(open[x][z]&(1<<a))!=0,back=(open[x+dx[a]][z+dz[a]]&(1<<((a+2)&3)))!=0;
          if(f&&back)links++;
          if(f!=back){mismatch0++;sample("MISMATCH "+xx+","+zz+" dir="+a+" kind="+k);}
        }
        if(links<2){ends++;sample("END "+xx+","+zz+" road="+k+" open="+open[x][z]);}
      }
      if(!road(k)||!road(kind[x+1][z])||!road(kind[x][z+1])||!road(kind[x+1][z+1]))continue;
      int lane=(k==WorldLayout.LANE?1:0)+(kind[x+1][z]==WorldLayout.LANE?1:0)+(kind[x][z+1]==WorldLayout.LANE?1:0)+(kind[x+1][z+1]==WorldLayout.LANE?1:0);
      if(lane==4)hh++;else if(lane==0){ss++;sample("4x STREET at "+xx+","+zz);}else{mm++;sample("4x MIX at "+xx+","+zz+" lanes="+lane);}
    }
    int blocks=0, noFrontage=0, urban=0, rural=0;
    for(WorldLayout.Rect b:l.blocksIn(x0,z0,x0+w-1,z0+h-1)) {
      if(!b.intersects(x0,z0,x0+w-1,z0+h-1))continue;
      blocks++;
      ContextType t=l.contextAt(b.minX,b.minZ);
      if(t==ContextType.RESIDENTIAL)urban++;else rural++;
      boolean front=false;
      for(int x=b.minX;x<=b.maxX&&!front;x++){
        front=builtRoad(l,s,x,b.minZ-1)||builtRoad(l,s,x,b.maxZ+1);
      }
      for(int z=b.minZ;z<=b.maxZ&&!front;z++){
        front=builtRoad(l,s,b.minX-1,z)||builtRoad(l,s,b.maxX+1,z);
      }
      if(!front&&t==ContextType.RESIDENTIAL){noFrontage++;sample("NO_URBAN_FRONT "+b);}
    }
    System.out.printf("AUDIT seed=%d mode=%s region=%d,%d %dx%d reverse=%s active=%d dead=%d mismatch=%d street2x2=%d mixed2x2=%d highway2x2=%d blocks=%d urban=%d nonUrban=%d noUrbanFront=%d%n",seed,mode,x0,z0,w,h,reverse,active,ends,mismatch0,ss,mm,hh,blocks,urban,rural,noFrontage);
    for(String q:samples)System.out.println(q);
    if(ends>0||mismatch0>0||ss>0||mm>0||noFrontage>0)System.exit(2);
  }
  public static void main(String[] args)throws Exception {
    setup();long seed=Long.parseLong(args[0]);int x=Integer.parseInt(args[1]),z=Integer.parseInt(args[2]),w=Integer.parseInt(args[3]),h=Integer.parseInt(args[4]);String mode=args.length>5?args[5]:"urban";boolean reverse=args.length>6&&Boolean.parseBoolean(args[6]);
    run(seed,x,z,w,h,mode,reverse);
  }
}
