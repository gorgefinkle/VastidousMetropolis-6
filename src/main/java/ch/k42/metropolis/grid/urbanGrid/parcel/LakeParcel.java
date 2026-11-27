package ch.k42.metropolis.grid.urbanGrid.parcel;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.UrbanGrid;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.grid.urbanGrid.wild.WildernessFeatures;
import ch.k42.metropolis.grid.urbanGrid.wild.HydrologySample;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;

/**
 * A chunk of a deterministic freshwater lake. The signed coast and continuous
 * basin depth are shared by every chunk, so terrain does not depend on load order.
 * Open water uses the RIVER biome; surrounding land keeps its original biome.
 */
public final class LakeParcel extends Parcel {
   private static final Cartesian2D SIZE=new Cartesian2D(1,1);
   private final WildernessFeatures.LakeSite lake;
   public LakeParcel(UrbanGrid grid,Cartesian2D base,WildernessFeatures.LakeSite lake){
      super(base,SIZE,ContextType.RIVER,SchematicType.RIVER,grid);
      if(lake==null)throw new IllegalArgumentException("Lake site is required");
      this.lake=lake;grid.fillParcels(this.chunkX,this.chunkZ,this);
   }
   public WildernessFeatures.LakeSite getLake(){return lake;}

   @Override public void populate(MetropolisGenerator generator,Chunk chunk){
      if(chunk.getX()!=chunkX||chunk.getZ()!=chunkZ)return;
      World world=generator.getWorld();if(world==null)return;
      WildernessFeatures.announceLake(lake,world.getName());
      int baseX=chunkX<<4,baseZ=chunkZ<<4;
      int minY=Math.max(world.getMinHeight()+1,1),maxY=world.getMaxHeight()-1;
      GridRandom rand=grid.getRandom();
      ch.k42.metropolis.grid.urbanGrid.layout.WaterField hydrology=grid.planWater();
      RiverProvider stream=hydrology.streamCarrierAt(chunkX,chunkZ);
      for(int lx=0;lx<16;lx++)for(int lz=0;lz<16;lz++){
         int x=baseX+lx,z=baseZ+lz;
         HydrologySample sample=hydrology.hydrologySampleAt(x,z);
         // Geometry and ownership come from the shared city-aware biome layer,
         // not from a private LakeSite ellipse re-evaluated by this parcel.
         if(sample.feature()!=HydrologySample.Feature.LAKE)continue;
         int waterY=Math.min(maxY-4,Math.max(minY+4,sample.waterY()));
         double distance=sample.signedDistanceBlocks();
         if(distance<=0.0&&stream!=null){
            double signed=stream.distanceFromCenterBlock(x,z);
            double reach=stream.getHalfWidthBlocks()+stream.getFallbackBankMarginBlocks();
            if(Double.isFinite(signed)&&Math.abs(signed)<=reach){
               RiverParcel.carveColumnWithCreeks(world,stream,grid,rand,x,z);
               world.setBiome(x,z,Biome.RIVER);continue;
            }
         }
         if(distance>0.0){
            // A rooted tree column becomes a natural wooded island, not a
            // severed trunk or a canopy suspended over newly carved water.
            if(containsTreeBlock(world,x,z,minY,maxY))continue;
            paintWaterColumn(world,x,z,minY,maxY,waterY,sample.depth(),lake);
            world.setBiome(x,z,Biome.RIVER);
         }else{
            double shoreDistance=-distance;
            if(shoreDistance<=WildernessFeatures.LakeSite.SHORE_WIDTH_BLOCKS
                    &&!containsTreeBlock(world,x,z,minY,maxY)){
               paintShoreColumn(world,x,z,minY,maxY,waterY,shoreDistance,lake);
               // Keep the original terrestrial biome; do not stamp FOREST or OCEAN.
            }
         }
      }
      this.markPopulated();
   }

   private static void paintWaterColumn(World world,int x,int z,int minY,int maxY,
                                        int waterY,int depth,WildernessFeatures.LakeSite lake){
      int floorY=Math.max(minY+2,waterY-Math.max(1,depth));
      int currentTop=Math.min(maxY,world.getHighestBlockYAt(x,z));
      clearColumn(world,x,z,floorY+1,Math.max(currentTop,waterY+1));
      for(int y=Math.max(minY,floorY-3);y<floorY;y++)world.getBlockAt(x,y,z).setType(Material.STONE,false);
      world.getBlockAt(x,floorY,z).setType(bedMaterial(lake,x,z),false);
      for(int y=floorY+1;y<=waterY;y++)world.getBlockAt(x,y,z).setType(Material.WATER,false);
   }

   private static void paintShoreColumn(World world,int x,int z,int minY,int maxY,
                                        int waterY,double shoreDistance,WildernessFeatures.LakeSite lake){
      int currentTop=Math.min(maxY,world.getHighestBlockYAt(x,z));
      int groundY=Math.max(minY+1,currentTop);
      double t=Math.max(0.0,Math.min(1.0,shoreDistance/WildernessFeatures.LakeSite.SHORE_WIDTH_BLOCKS));
      t=smooth(t);
      int targetY=(int)Math.round(waterY+1.0+(groundY-(waterY+1.0))*t);
      targetY=Math.max(minY+1,Math.min(maxY-2,targetY));
      if(targetY<groundY)clearColumn(world,x,z,targetY+1,currentTop);
      else if(targetY>groundY)for(int y=groundY+1;y<targetY;y++)world.getBlockAt(x,y,z).setType(Material.DIRT,false);

      // A single smooth beach grade: sandy/gravelly nearest the water,
      // progressively returning to the original ground material toward land.
      double noise=lake.shoreMaterialNoiseAt(x+0.5,z+0.5);
      Material original=world.getBlockAt(x,groundY,z).getType();
      Material top;
      if(t<0.28)top=noise>0.12?Material.SAND:Material.GRAVEL;
      else if(t<0.58)top=noise<-0.32?Material.GRAVEL:Material.SAND;
      else top=(original.isSolid()&&!original.isAir())?original:Material.GRASS_BLOCK;
      world.getBlockAt(x,targetY,z).setType(top,false);
      Material under=(top==Material.SAND||top==Material.GRAVEL)?top:Material.DIRT;
      for(int y=Math.max(minY,targetY-3);y<targetY;y++)world.getBlockAt(x,y,z).setType(under,false);
   }

   private static Material bedMaterial(WildernessFeatures.LakeSite lake,int x,int z){
      double noise=lake.bedMaterialNoiseAt(x+0.5,z+0.5);
      if(noise<-0.34)return Material.CLAY;
      if(noise>0.36)return Material.GRAVEL;
      return Material.SAND;
   }
   private static boolean containsTreeBlock(World world,int x,int z,int minY,int maxY){
      int top=Math.min(maxY,world.getHighestBlockYAt(x,z)),floor=Math.max(minY,top-48);
      for(int y=top;y>=floor;y--){
         Material m=world.getBlockAt(x,y,z).getType();
         if(Parcel.isTreeBlock(m))return true;
         if(!m.isAir()&&m.isSolid())return false;
      }
      return false;
   }
   private static void clearColumn(World world,int x,int z,int fromY,int toY){
      for(int y=Math.max(1,fromY);y<=toY;y++)world.getBlockAt(x,y,z).setType(Material.AIR,false);
   }
   private static double smooth(double value){
      double t=Math.max(0.0,Math.min(1.0,value));return t*t*(3.0-2.0*t);
   }
   @Override public void postPopulate(MetropolisGenerator generator,Chunk chunk){}
}
