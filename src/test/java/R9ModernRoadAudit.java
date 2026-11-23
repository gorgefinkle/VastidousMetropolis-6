import ch.k42.metropolis.plugin.PluginConfig;
import ch.k42.metropolis.grid.urbanGrid.enums.*;
import ch.k42.metropolis.grid.urbanGrid.layout.*;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import java.lang.reflect.Field;
import java.util.*;

/** Live generator-plan audit, R8 RoadShapes + WorldLayout, no saved ASCII used. */
public class R9ModernRoadAudit {
 static final int[] DX={0,1,0,-1}, DZ={-1,0,1,0};
 static void set(String n,Object v)throws Exception {Field f=PluginConfig.class.getDeclaredField(n);f.setAccessible(true);if(v instanceof Boolean) f.setBoolean(null,(Boolean)v);else if(v instanceof Integer)f.setInt(null,(Integer)v);else if(v instanceof Double)f.setDouble(null,(Double)v); }
 static void setup()throws Exception {
  set("blockSize",14);set("blockSizeMin",7);set("blockSizeSlack",3);set("cityRings",true);set("highwayNetwork",true);
  Field oceanOn=OceanConfig.class.getDeclaredField("oceanEnabled");oceanOn.setAccessible(true);oceanOn.setBoolean(null,true);
  OceanConfig.setOceanSizeTiles(3.3);OceanConfig.setOceanMaxDistanceFromSpawn(30);OceanConfig.setOceanStretch(5.0);
  OceanConfig.setOceanWarpFraction(0.25);OceanConfig.setOceanBayFraction(0.26);OceanConfig.setOceanInletFraction(0.14);
  OceanConfig.setOceanIslandFraction(0.42);OceanConfig.setOceanCoastJitterBlocks(7.0);OceanConfig.setOceanCoastJitterFrequency(0.02);
  OceanConfig.setOceanEdgeNoiseFrequency(3.0);OceanConfig.setOceanEdgeNoiseOctaves(4);OceanConfig.setOceanBankMargin(3);
 }
 static int score(ContextType[] types,int x,int z){return 0;}
 public static void main(String[]args)throws Exception {
  long seed=Long.parseLong(args.length>0?args[0]:"4989598596633346380");int x0=Integer.parseInt(args.length>1?args[1]:"-128");int z0=Integer.parseInt(args.length>2?args[2]:"-192");int w=Integer.parseInt(args.length>3?args[3]:"256");int h=Integer.parseInt(args.length>4?args[4]:"256");String mode=args.length>5?args[5]:"varied";boolean reverse=args.length>6 && args[6].equals("reverse");
  setup(); WorldLayout layout=WorldLayouts.forSeed(seed);
  layout.setZoner((bx,bz,sx,sz,minX,minZ,maxX,maxZ)->{
   ContextType[] out=new ContextType[bx.length];
   for(int i=0;i<bx.length;i++){
    int cx=bx[i]+sx[i]/2,cz=bz[i]+sz[i]/2;
    if(mode.equals("urban")) out[i]=ContextType.RESIDENTIAL;
    else {double f=Math.sin(cx*.018)+Math.cos(cz*.013)+Math.sin((cx+cz)*.005);out[i]=f>1.0?ContextType.FOREST:(f>-.1?ContextType.FARM:ContextType.RESIDENTIAL);}
   }return out;
  });
  RoadShapes shapes=WorldLayouts.roadShapesFor(seed); int rows=h+2,cols=w+2; byte[][] kind=new byte[cols][rows];byte[][] sides=new byte[cols][rows];
  long start=System.nanoTime();
  for(int iz=0;iz<rows;iz++) { int z=reverse?z0+h-iz:z0-1+iz;
    for(int ix=0;ix<cols;ix++) {int x=reverse?x0+w-ix:x0-1+ix;
   int k=layout.kindAt(x,z);kind[x-(x0-1)][z-(z0-1)]=(byte)k;
   if(k==WorldLayout.STREET||k==WorldLayout.LANE) sides[x-(x0-1)][z-(z0-1)]=(byte)shapes.opennessAt(x,z);
  } if((iz+1)%64==0)System.out.println("ROW="+(iz+1)+" seconds="+((System.nanoTime()-start)/1e9));}
  int active=0,ends=0,invalid=0,unverified=0,tileSeams=0,restored=0;long checksum=0;
  ArrayList<String> samples=new ArrayList<>();
  for(int z=z0;z<z0+h;z++) for(int x=x0;x<x0+w;x++){
   int ix=x-x0+1,iz=z-z0+1,s=sides[ix][iz]&15,k=kind[ix][iz];
   if(k!=WorldLayout.STREET && k!=WorldLayout.LANE || s==0) continue;
   active++;
   long v=((long)x*0x9e3779b97f4a7c15L)^((long)z*0xbf58476d1ce4e5b9L)^((long)s*0x94d049bb133111ebL);
   checksum += v;
   int linked=0;
   for(int d=0;d<4;d++){
    int other=sides[ix+DX[d]][iz+DZ[d]]&15;boolean open=(s&(1<<d))!=0;boolean back=(other&(1<<((d+2)%4)))!=0;
    if(open&&back)linked++;
    if(open!=back) {invalid++;if(samples.size()<80)samples.add("MISMATCH x="+x+" z="+z+" d="+d+" k="+k+" neighbor="+kind[ix+DX[d]][iz+DZ[d]]+" op="+s+" nb="+other);}
    if((x%64==0 && d==3)||(x%64==63 && d==1)||(z%64==0&&d==0)||(z%64==63&&d==2)) if(open&&!back)tileSeams++;
   }
   if(linked<=1) {ends++;if(samples.size()<80)samples.add("DEAD_END x="+x+" z="+z+" k="+k+" opens="+s+" links="+linked+" water="+layout.isWater(x,z));}
  }
  System.out.println("FINAL seed="+seed+" mode="+mode+" window="+x0+","+z0+" size="+w+"x"+h+" active="+active+" DEAD_ENDS="+ends+" MISMATCHES="+invalid+" SEAM_MISMATCHES="+tileSeams+" restored="+restored+" checksum="+Long.toUnsignedString(checksum)+" elapsed="+((System.nanoTime()-start)/1e9));
  samples.forEach(System.out::println);
  for(String issue:samples) if(issue.startsWith("DEAD_END")) {
    String[] parts=issue.split(" ");int qx=Integer.parseInt(parts[1].split("=")[1]),qz=Integer.parseInt(parts[2].split("=")[1]);
    System.out.println("CONTEXT at bad endpoint="+layout.contextAt(qx,qz)+" near water="+layout.isWater(qx,qz));
    for(int zz=qz-6;zz<=qz+6;zz++){
       StringBuilder line=new StringBuilder(String.format("%4d: ",zz));
       for(int xx=qx-8;xx<=qx+8;xx++){
        int kk=layout.kindAt(xx,zz);int op=shapes.opennessAt(xx,zz);
        char ch=kk==WorldLayout.LANE?'H':kk==WorldLayout.ZONE?'~':kk==WorldLayout.STREET?(shapes.regions().dissolvedAt(xx,zz)?'d':op==0?'o':Integer.bitCount(op)==1?'!':'R'):kk==WorldLayout.BLOCK?layout.contextAt(xx,zz)==ContextType.FOREST?'F':layout.contextAt(xx,zz)==ContextType.FARM?'A':'U':'?';
        if(xx==qx&&zz==qz)ch='X';line.append(ch);
       }System.out.println(line);
    }
  }
  if(ends>0||invalid>0)System.exit(2);
 }
}
