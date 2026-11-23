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
public final class RoadWidthRootProbe {
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
   if(seed==13L){
      System.out.println("WIDTH_ROOT begin seed="+seed);
      for(int z=232;z<=241;z++){
        StringBuilder o=new StringBuilder("z="+z+" ");
        for(int x=78;x<=82;x++)o.append(x+"="+layout.kindAt(x,z)+" ");
        System.out.println(o);
      }
      var domain=layout.domainOf(80,239);
      System.out.println("DOMAIN blocks="+domain.blocks.size()+" extent="+domain.minX+","+domain.minZ+".."+domain.maxX+","+domain.maxZ);
      for(var r:domain.blocks)if(r.maxX>=75 && r.minX<=84 && r.maxZ>=228 && r.minZ<=245)
        System.out.println("RECT context="+domain.contexts.get(r)+" x="+r.minX+".."+r.maxX+" z="+r.minZ+".."+r.maxZ);
      for(int z:new int[]{233,234,235,237,238,239}) {
         for(int x=78;x<=84;x++) {
            var dm=layout.domainOf(x,z);
            System.out.println("DOMAIN_CELL x="+x+" z="+z+" global="+layout.kindAt(x,z)+" local="+dm.kindAt(x,z)+" ctx="+layout.contextAt(x,z)+" domainId="+dm.id+" mainLocal="+domain.kindAt(x,z)+" mainOwns="+layout.ownedByDomain(domain,x,z)+" preFixed="+(dm.fixedRoadBeforeCarve!=null&&dm.fixedRoadBeforeCarve[x-dm.minX][z-dm.minZ]));
         }
      }
      try {
         var lm=WorldLayout.class.getDeclaredMethod("plannedFixedBoundaryRoadAt",int.class,int.class);
         lm.setAccessible(true);
         for(int z=234;z<=239;z++)for(int x:new int[]{80,81,82})
             System.out.println("PLANNED_FIXED "+x+","+z+"="+lm.invoke(layout,x,z));
         var validator=DoubleFrontageRoads.class.getDeclaredMethod("valid",WorldLayout.Domain.class,WorldLayout.Rect.class,ContextType.class,int.class,int.class,DoubleFrontageRoads.FixedBoundary.class);
         validator.setAccessible(true);
         for(var r:domain.blocks)if(r.minX<=79&&r.maxX>=79&&r.minZ<=235&&r.maxZ>=238) {
            DoubleFrontageRoads.FixedBoundary fx=new DoubleFrontageRoads.FixedBoundary() {
               public boolean at(int x,int z) {try {return (boolean)lm.invoke(layout,x,z);}catch(Exception e){throw new RuntimeException(e);}}
               public boolean shoreAt(int x,int z){return layout.physicalWaterWithoutDomainLookup(x,z);}
            };
            System.out.println("ROAD_FACE_VALID "+r+" ctx="+domain.contexts.get(r)+" east="+validator.invoke(null,domain,r,domain.contexts.get(r),3,17,fx));
         }
      }catch(Exception e){e.printStackTrace();}
      for(int z=233;z<=240;z++)for(int x=79;x<=82;x++)if(layout.kindAt(x,z)==WorldLayout.STREET){
        StringBuilder d=new StringBuilder("DEF "+x+","+z+" urban="+layout.urbanBoundaryRoadAt(x,z)+" rural="+layout.ruralBoundaryRoadAt(x,z));
        for(String name:new String[]{"ruleDissolves","protectedCountrysideWaterFace","protectedCountrysideDryExteriorFace","protectedTwoAnchorJoin","protectedShortPlannedBoundaryTurn","protectedUrbanCorner","protectedWaterfrontContinuation","mixed","separatesKinds"})try{
           Method m=PlanRegions.class.getDeclaredMethod(name,int.class,int.class);m.setAccessible(true);d.append(" "+name+"="+m.invoke(regions,x,z));
        } catch(Exception e){d.append(" ERR:"+e.getClass());}
        d.append(" alive="+roads.isRoadStreet(x,z)); System.out.println(d);
      }
   }
   if(seed==13L) return;
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
   if(seed==1750414970189483479L) {
     for(int z:new int[]{108,109,110,116,117,118}) {
       int x=-10;
       StringBuilder sb=new StringBuilder("HOLE_DIAG x="+x+" z="+z+" kind="+layout.kindAt(x,z)+" planType="+roads.roadTypeAt(x,z)+" water="+water.kindAt(x,z)+" zoneKind="+water.zoneKind(x,z));
       for(int[] v:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}) {
         int nx=x+v[0],nz=z+v[1];
         sb.append(" side(").append(v[0]).append(",").append(v[1]).append(")=").append(layout.kindAt(nx,nz))
           .append("/").append(layout.contextAt(nx,nz)).append("/w=").append(layout.isWater(nx,nz))
           .append("/zone=").append(water.zoneKind(nx,nz)).append("/road=").append(roads.roadTypeAt(nx,nz));
       }
       for(String method:new String[]{"ruleDissolves","mixed","separatesKinds","protectedCountrysideWaterFace","structuralFarmExterior"}) {
         try{Method m=PlanRegions.class.getDeclaredMethod(method,int.class,int.class);m.setAccessible(true);sb.append(" ").append(method).append("=").append(m.invoke(regions,x,z));}
         catch(Exception ignored){}
       }
       for(int pass=0;pass<=3;pass++) {
         try{Method m=PlanRegions.class.getDeclaredMethod("kept",int.class,int.class,int.class);m.setAccessible(true);sb.append(" kept").append(pass).append("=").append(m.invoke(regions,x,z,pass));}
         catch(Exception ignored){}
       }
       System.out.println(sb);
     }
   }
   System.out.printf("REGION_ADJACENCY legalLike=%d unlikeOrUnowned=%d adjacentLiveRoad=%d%n",adjacentDissolvedLike,adjacentDissolvedUnlike,otherRoadLive);
   System.out.printf("ROAD_RULES seed=%d sample=[%d..%d,%d..%d] rects=%d missingTotal=%d urbanBad=%d ruralNonLikeBad=%d urbanMissingCorners=%d missingPlan=%d erasedByMorphomos=%d plannedWetStreet=%d kept=%d dissolved=%d unshaped=%d degree1=%d%n",seed,minX,maxX,minZ,maxZ,seen.size(),missingTotal,urbanBad,ruralMixedBad,missingCorner,missingPlan,erasedByMorphomos,waterRoads,kept,dissolved,unshaped,deadEnd);
   for(String s:examples)System.out.println(s);
   if(seed==13L){
      int cornerKind=layout.kindAt(80,239);
      boolean cornerRoad=roads.isRoadStreet(80,239);
      System.out.printf("DRY_CORNER_ASSERT seed=13 x=80 z=239 plannedKind=%d retained=%s%n",cornerKind,cornerRoad);
      if(cornerKind!=WorldLayout.STREET || !cornerRoad)
         throw new AssertionError("Required dry district corner (80,239) was not committed as a road");
   }
   if(missingTotal!=0 || urbanBad!=0 || ruralMixedBad!=0 || missingCorner!=0
          || unshaped!=0 || deadEnd!=0 || adjacentDissolvedUnlike!=0)
      throw new AssertionError("Required road frame or topology regression on seed="+seed
           +" missing="+missingTotal+" corners="+missingCorner
           +" unshaped="+unshaped+" oneEnded="+deadEnd);
 }
}
