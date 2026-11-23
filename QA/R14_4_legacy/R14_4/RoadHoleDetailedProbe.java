package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.context.ContextConfig;
import ch.k42.metropolis.grid.urbanGrid.context.ContextProviderSimplex;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.plugin.PluginConfig;
import com.google.gson.Gson;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Diagnostic only: inspect actual production world-plan and post-Morphomos road shapes. */
public final class RoadHoleDetailedProbe {
 static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
 static boolean road(WorldLayout l,RoadShapes s,int x,int z) {
   int k=l.kindAt(x,z);return k==WorldLayout.LANE || (k==WorldLayout.STREET && s.isRoadStreet(x,z));
 }
 static boolean same(ContextType a,ContextType b){return a!=null && a==b && a.isMorphomos();}
 static String neighbors(WorldLayout l,RoadShapes roads,int x,int z){StringBuilder b=new StringBuilder();
   for(int[] d:new int[][]{{0,-2},{0,-1},{1,0},{2,0},{0,1},{0,2},{-1,0},{-2,0}}){int px=x+d[0],pz=z+d[1];
     b.append("(").append(px).append(",").append(pz).append(":").append(l.kindAt(px,pz))
      .append("/").append(l.contextAt(px,pz)).append("/").append(road(l,roads,px,pz)?"R":"-").append(")");
   }return b.toString();}

 public static void main(String[]args)throws Exception {
   long seed=args.length>2?Long.parseLong(args[2]):-1482741523194359869L;
   org.bukkit.Server mock=(org.bukkit.Server)Proxy.newProxyInstance(org.bukkit.Server.class.getClassLoader(),new Class[]{org.bukkit.Server.class},
     (p,m,a)->m.getName().equals("getLogger")?java.util.logging.Logger.getLogger("RoadProbe"):null);
   Field b=org.bukkit.Bukkit.class.getDeclaredField("server");b.setAccessible(true);b.set(null,mock);
   PluginConfig.loadFromFile(YamlConfiguration.loadConfiguration(new File(args[0]+"/config.yml")));
   ContextConfig cfg;try(Reader r=new FileReader(args[0]+"/contexts.json")){cfg=new Gson().fromJson(r,ContextConfig.class);}
   CreekForestSeedProbe.Provider clips=new CreekForestSeedProbe.Provider(args[0],args[1]);
   MetropolisGenerator g=new MetropolisGenerator(null,"world",clips);
   CreekForestSeedProbe.setField(g,"worldSeed",seed);
   CreekForestSeedProbe.setField(g,"contextProvider",new ContextProviderSimplex(g,cfg));
   UrbanGrid.prepareWorldPlan(g);
   WorldLayout layout=WorldLayouts.forSeed(seed);
   WaterField water=WorldLayouts.waterFor(seed);
   RoadShapes roads=WorldLayouts.roadShapesFor(seed);
   Set<WorldLayout.Rect> seen=Collections.newSetFromMap(new IdentityHashMap<>());
   int minX=args.length>3?Integer.parseInt(args[3]):-40,maxX=args.length>4?Integer.parseInt(args[4]):90,minZ=args.length>5?Integer.parseInt(args[5]):192,maxZ=args.length>6?Integer.parseInt(args[6]):320;
   for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++){
     if(layout.kindAt(x,z)==WorldLayout.BLOCK){WorldLayout.Rect r=layout.blockAt(x,z);if(r!=null) seen.add(r);}
   }
   PlanRegions regions=roads.regions();
   int adjacentDissolvedLike=0, adjacentDissolvedUnlike=0, otherRoadLive=0;
   int urbanBad=0,ruralMixedBad=0,missingTotal=0,waterRoads=0,kept=0,dissolved=0,missingCorner=0,missingPlan=0,erasedByMorphomos=0,unshaped=0,deadEnd=0;
   List<String> examples=new ArrayList<>();
   for(WorldLayout.Rect r:seen){
     ContextType inside=layout.contextOf(r);
     if(inside==null)continue;
     for(int side=0;side<4;side++){
       for(int t=(side<2?r.minX:r.minZ);t<=(side<2?r.maxX:r.maxZ);t++){
         int x=(side<2?t:side==2?r.minX-1:r.maxX+1);
         int z=(side<2?(side==0?r.minZ-1:r.maxZ+1):t);
         boolean live=road(layout,roads,x,z);
         int ox=x+(side==2?-1:side==3?1:0), oz=z+(side==0?-1:side==1?1:0);
         ContextType other=layout.contextAt(ox,oz);
         boolean like=inside.isMorphomos() && same(inside,other);
         // A missing road is only excusable when it lies between like-context countryside blocks.
         if(!live&&!like){
           if(layout.kindAt(ox,oz)==WorldLayout.STREET) {
             if(roads.isRoadStreet(ox,oz))otherRoadLive++;
             else {
               var otherRegion=regions.regionFor(ox,oz);
               if(otherRegion!=null && otherRegion.getContext()==inside) {adjacentDissolvedLike++;continue;}
               else adjacentDissolvedUnlike++;
             }
           }
           missingTotal++;if(layout.kindAt(x,z)==WorldLayout.STREET)erasedByMorphomos++;else missingPlan++;if(!inside.isMorphomos())urbanBad++;else ruralMixedBad++;
           if(examples.size()<20)examples.add("MISSING edge="+x+","+z+" rect="+r.minX+","+r.minZ+".."+r.maxX+","+r.maxZ+" side="+side+" inside="+inside+" outside="+other+" outsideKind="+layout.kindAt(ox,oz)+" edgeKind="+layout.kindAt(x,z)+" water="+water.kindAt(x,z)+" nearRegion="+Arrays.toString(regions.regionAt(x+(side==2?1:side==3?-1:0),z+(side==0?1:side==1?-1:0)))+" beyondRegion="+Arrays.toString(regions.regionAt(ox,oz))+" roadRegion="+Arrays.toString(regions.regionAt(x,z)));
         }
       }
     }
     // Urban corner counts separately because they must participate in the actual turn.
     if(!inside.isMorphomos())for(int x:new int[]{r.minX-1,r.maxX+1})for(int z:new int[]{r.minZ-1,r.maxZ+1})if(!road(layout,roads,x,z))missingCorner++;
   }
   for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++) {
     if(layout.kindAt(x,z)==WorldLayout.STREET){
       if(roads.isRoadStreet(x,z)){
         kept++;
         if(roads.roadTypeAt(x,z)==null){unshaped++;if(examples.size()<75)examples.add("UNSHAPED_ROAD "+x+","+z+" neighbors="+neighbors(layout,roads,x,z));}
         int degree=0;
         for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})if(road(layout,roads,x+d[0],z+d[1]))degree++;
         if(degree==1){deadEnd++;if(examples.size()<75)examples.add("DEAD_END "+x+","+z+" roadType="+roads.roadTypeAt(x,z)+" neighbors="+neighbors(layout,roads,x,z));}
       }else dissolved++;
       if(water.kindAt(x,z)!=WaterField.Kind.NONE){waterRoads++;if(examples.size()<75)examples.add("WET_STREET "+x+","+z+" water="+water.kindAt(x,z)+" creekOutlet="+layout.creekOutletRingAxisAt(x,z)+" type="+roads.roadTypeAt(x,z));}
     }
   }
   if(true) {
     for(int centerZ:seed==13?new int[]{239,333}:seed==42?new int[]{116,342}:new int[]{109,117,135,170}) {
       System.out.println("MAP_AT_Z="+centerZ);
       for(int z=centerZ-5;z<=centerZ+5;z++) {
         StringBuilder sb=new StringBuilder(String.format("%4d ",z));
         for(int x=(centerZ==239?70:centerZ==342?-25:-25);x<=(centerZ==239?88:centerZ==342?0:5);x++) {
           int k=layout.kindAt(x,z);
           char c=k==WorldLayout.BLOCK?(layout.contextAt(x,z)==ContextType.FARM?'F':'f'):
                    k==WorldLayout.STREET?(roads.isRoadStreet(x,z)?'R':'-'):
                    k==WorldLayout.ZONE?(layout.isWater(x,z)?'~':'z'):
                    k==WorldLayout.LANE?'H': k==WorldLayout.UNCLAIMED?'?':'.';
           sb.append(c);
         }
         System.out.println(sb);
       }
     }
     for(int[] v:new int[][]{{79,239},{79,240},{-10,331},{-9,333},{-10,333},{-10,116},{-10,117},{-9,116},{-10,118},{-10,341},{-10,342},{-8,342},{-10,108},{-10,109},{-10,110},{-10,116},{-10,117},{-10,118},{-10,134},{-10,135},{-10,136},{-9,135},{-1,135},{-1,136},{-2,135},{-10,169},{-10,170},{-10,171},{-9,170},{-1,169},{-1,170},{-1,171}}) {
       int x=v[0],z=v[1];
       StringBuilder sb=new StringBuilder("HOLE_DIAG x="+x+" z="+z+" kind="+layout.kindAt(x,z)+" planType="+roads.roadTypeAt(x,z));
       for(int[] d:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
         int nx=x+d[0],nz=z+d[1];
         sb.append(" side("+d[0]+","+d[1]+")="+layout.kindAt(nx,nz)+"/"+layout.contextAt(nx,nz)+"/road="+roads.roadTypeAt(nx,nz));
       }
       for(String method:new String[]{"ruleDissolves","mixed","separatesKinds","protectedCountrysideWaterFace","protectedWaterfrontContinuation","structuralFarmExterior","protectedEnd"}){
         try {Method m=PlanRegions.class.getDeclaredMethod(method,int.class,int.class);m.setAccessible(true);sb.append(" "+method+"="+m.invoke(regions,x,z));}catch(Exception ignore){}
       }
       for(int pass=0;pass<=3;pass++) {
         try {Method m=PlanRegions.class.getDeclaredMethod("kept",int.class,int.class,int.class);m.setAccessible(true);sb.append(" kept"+pass+"="+m.invoke(regions,x,z,pass));}catch(Exception ignore){}
       }
       System.out.println(sb);
     }
   }
   System.out.printf("REGION_ADJACENCY legalLike=%d unlikeOrUnowned=%d adjacentLiveRoad=%d%n",adjacentDissolvedLike,adjacentDissolvedUnlike,otherRoadLive);
   System.out.printf("ROAD_RULES seed=%d sample=[%d..%d,%d..%d] rects=%d missingTotal=%d urbanBad=%d ruralNonLikeBad=%d urbanMissingCorners=%d missingPlan=%d erasedByMorphomos=%d plannedWetStreet=%d kept=%d dissolved=%d unshaped=%d degree1=%d%n",seed,minX,maxX,minZ,maxZ,seen.size(),missingTotal,urbanBad,ruralMixedBad,missingCorner,missingPlan,erasedByMorphomos,waterRoads,kept,dissolved,unshaped,deadEnd);
   for(String s:examples)System.out.println(s);
 }
}
