package qa.metropolis;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Material;
import ch.k42.metropolis.plugin.MetropolisPlugin;
import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.parcel.*;
import ch.k42.metropolis.grid.urbanGrid.layout.*;
import java.util.*;
import java.nio.file.*;
public class RoadPlacementQA extends JavaPlugin {
 private final List<String> results = new ArrayList<>();
 void record(String text){ getLogger().info("[PLACED-ROAD-QA] "+text); results.add(text); }
 @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
   if(args.length<3){sender.sendMessage("/roadqa <seed> <chunkX> <chunkZ>");return true;}
   try {run(Long.parseLong(args[0]),Integer.parseInt(args[1]),Integer.parseInt(args[2]));}
   catch(Throwable ex){record("FATAL "+ex);getLogger().log(java.util.logging.Level.SEVERE,"QA failed",ex);}
   try {Files.createDirectories(getDataFolder().toPath());Files.writeString(getDataFolder().toPath().resolve("roadqa.log"),String.join("\n",results)+"\n", StandardOpenOption.CREATE,StandardOpenOption.APPEND);} catch(Exception ex){getLogger().warning("log write "+ex);}
   return true;
 }
 void run(long seed,int centerX,int centerZ) {
   MetropolisPlugin m=(MetropolisPlugin)Bukkit.getPluginManager().getPlugin("Metropolis");
   String worldname="RoadQA_R145_"+(seed<0?"m":"p")+Long.toUnsignedString(seed<0?-seed:seed);
   World world=Bukkit.getWorld(worldname);MetropolisGenerator gen;
   if(world==null){
     gen=(MetropolisGenerator)m.getDefaultWorldGenerator(worldname,"");
     record("CREATING_WORLD seed="+seed+" world="+worldname);
     world=new WorldCreator(worldname).seed(seed).generator(gen).generateStructures(false).createWorld();
   } else {
     gen=(MetropolisGenerator)world.getGenerator();
   }
   if(world==null || gen==null)throw new AssertionError("World creation or generator assignment failed");
   record("WORLD_LOADED seed="+world.getSeed()+" name="+world.getName()+" gen="+world.getGenerator().getClass().getSimpleName());
   if(world.getSeed()!=seed)throw new AssertionError("Wrong world seed");
   UrbanGrid grid=(UrbanGrid)gen.getGridProvider().getGrid(centerX,centerZ);
   WorldLayout layout=WorldLayouts.forSeed(seed);RoadShapes shapes=WorldLayouts.roadShapesFor(seed);WaterField water=WorldLayouts.waterFor(seed);
   if(layout==null||shapes==null||water==null)throw new AssertionError("Missing committed plan");
   int originX=Math.floorDiv(centerX,64)*64, originZ=Math.floorDiv(centerZ,64)*64;
   int intended=0,correct=0,wrong=0,unshaped=0,wet=0,liveParcels=0,notPlanned=0;
   List<int[]> knownRoads=new ArrayList<>();
   for(int z=originZ;z<originZ+64;z++)for(int x=originX;x<originX+64;x++){
     var p=grid.getParcel(x,z);
     if(p instanceof RoadParcel || p instanceof HighwayParcel || p instanceof BridgeParcel)liveParcels++;
     if(layout.kindAt(x,z)==WorldLayout.STREET && shapes.isRoadStreet(x,z)){
       intended++;
       if(water.kindAt(x,z)!=WaterField.Kind.NONE)wet++;
       if(p instanceof RoadParcel || p instanceof HighwayParcel || p instanceof BridgeParcel){
         correct++;if(p instanceof RoadParcel && ((RoadParcel)p).resolveRoadType()==null){
           unshaped++;if(unshaped<=12)record("UNSHAPED_PARCEL at="+x+","+z);
         }
         if(p instanceof RoadParcel && knownRoads.size()<8)knownRoads.add(new int[]{x,z});
       }else {wrong++;if(wrong<=24) record("MISSING_PLANNED_ROAD at="+x+","+z+" parcel="+(p==null?"null":p.getClass().getSimpleName())+" water="+water.kindAt(x,z));}
     } else if(p instanceof RoadParcel && (layout.kindAt(x,z)!=WorldLayout.STREET || !shapes.isRoadStreet(x,z)))notPlanned++;
   }
   record("TILE_PARCEL_PARITY seed="+seed+" origin="+originX+","+originZ+" intended="+intended+" correct="+correct+" missing="+wrong+" unshaped="+unshaped+" wet="+wet+" allRoadParcels="+liveParcels+" localCreekRoadParcels="+notPlanned);
   if(seed==13L && centerX==80 && centerZ==239) {
     int singleWidth=0;
     for(int z=235;z<=238;z++) {
       var inner=grid.getParcel(80,z); var outer=grid.getParcel(81,z);
       boolean innerRoad=inner instanceof RoadParcel || inner instanceof HighwayParcel || inner instanceof BridgeParcel;
       boolean outerRoad=outer instanceof RoadParcel || outer instanceof HighwayParcel || outer instanceof BridgeParcel;
       boolean innerBlock=layout.kindAt(80,z)==WorldLayout.BLOCK;
       boolean retained=shapes.isRoadStreet(81,z);
       // A planned STREET between two FOREST blocks is intentionally dissolved
       // by Morphomos. A road parcel there would instead be the defect.
       boolean legalForestSeam=layout.contextAt(80,z)==ch.k42.metropolis.grid.urbanGrid.enums.ContextType.FOREST
                   && layout.contextAt(82,z)==ch.k42.metropolis.grid.urbanGrid.enums.ContextType.FOREST
                   && shapes.regions().dissolvedAt(81,z)
                   && outer instanceof MorphomosParcel;
       boolean valid=innerBlock && !innerRoad && layout.kindAt(81,z)==WorldLayout.STREET
                   && (retained ? outerRoad : (!outerRoad && legalForestSeam));
       if(valid) {
         singleWidth++;
         record("SINGLE_WIDTH_ROW z="+z+" x80="+inner.getClass().getSimpleName()
                +" x81="+outer.getClass().getSimpleName()+" retainedRoad="+retained
                +" legalForestMorphomos="+legalForestSeam);
       } else record("FAILED_SINGLE_WIDTH at z="+z+" x80="+(inner==null?"null":inner.getClass().getSimpleName())+" x81="+(outer==null?"null":outer.getClass().getSimpleName())+" plan80="+layout.kindAt(80,z)+" plan81="+layout.kindAt(81,z)+" retained="+retained+" legalForestSeam="+legalForestSeam);
     }
     record("SINGLE_WIDTH_ASSERT seed=13 rows=4 correct="+singleWidth);
     if(singleWidth!=4)throw new AssertionError("Seed-13 double frontage persisted in parcel layer");
     var corner=grid.getParcel(80,239);
     boolean cornerRoad=corner instanceof RoadParcel || corner instanceof HighwayParcel || corner instanceof BridgeParcel;
     record("CORNER_80_239 retained="+shapes.isRoadStreet(80,239)+" parcel="+(corner==null?"null":corner.getClass().getSimpleName()));
     if(!shapes.isRoadStreet(80,239)||!cornerRoad)throw new AssertionError("Required corner at 80,239 missing from parcel layer");
   }

   // Prioritized known regression point, then a small sample of actual road parcels.
   LinkedHashSet<Long> sampleKeys=new LinkedHashSet<>();
   sampleKeys.add((((long)centerX)<<32)^(centerZ&0xffffffffL));
   if(seed==13L){sampleKeys.add((((long)80)<<32)^235L);sampleKeys.add((((long)81)<<32)^235L);}
   for(int[] pos:knownRoads)if(sampleKeys.size()<5)sampleKeys.add((((long)pos[0])<<32)^(pos[1]&0xffffffffL));
   for(long k:sampleKeys){
     int x=(int)(k>>32),z=(int)k;
     var p=grid.getParcel(x,z);
     record("CHUNK_LOADING at="+x+","+z+" parcel="+(p==null?"null":p.getClass().getSimpleName())+" roadShape="+shapes.roadTypeAt(x,z));
     Chunk chunk=world.getChunkAt(x,z);StringBuilder materials=new StringBuilder();
     for(int y:new int[]{61,62,63,64,65,66})materials.append(" y").append(y).append("=").append(chunk.getBlock(8,y,8).getType());
     int highest=chunk.getWorld().getHighestBlockYAt(x*16+8,z*16+8);
     Material top=chunk.getBlock(8,highest,8).getType();
     record("CHUNK_GENERATED at="+x+","+z+" loaded="+chunk.isLoaded()+" topY="+highest+" top="+top+" center:"+materials);
   }
   record("END seed="+seed+" missing="+wrong+" unshaped="+unshaped);
   if(wrong!=0 || unshaped!=0)throw new AssertionError("Parcel ownership/shape mismatch: "+wrong+" missing, "+unshaped+" unshaped");
 }
}
