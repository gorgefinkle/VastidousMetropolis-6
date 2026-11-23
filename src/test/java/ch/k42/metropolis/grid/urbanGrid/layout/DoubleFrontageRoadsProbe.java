package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.HashSet;
import java.util.Set;

/** Pure planning-time regression: real R30d -82/-81, -95..-91 geometry translated/rotated. */
public final class DoubleFrontageRoadsProbe {
   static long key(int x,int z) {return ((long)x<<32) ^ (z&0xffffffffL);}
   static void set(WorldLayout.Domain d,int x,int z,int k) {d.kind[x-d.minX][z-d.minZ]=(byte)k;}
   static void must(boolean b,String why) {if (!b) throw new AssertionError(why);}
   static boolean isRoad(WorldLayout.Domain d,int x,int z){return d.kindAt(x,z)==WorldLayout.STREET || d.kindAt(x,z)==WorldLayout.LANE;}
   static WorldLayout.Domain fixture(int orientation, int offset, int length) {
      // Base: urban to the west, farmland east, two vertical road columns,
      // proper east-west joined streets at north and south ends.
      // 4x5 urban expands into 5x5 without moving the creek or farmland.
      int ox = -86 + offset, oz = -95 + offset;
      int xmin=ox-5,xmax=ox+15,zmin=oz-6,zmax=oz+length+6;
      if(orientation==1){xmin=ox-6;xmax=ox+length+6;zmin=oz-5;zmax=oz+15;}
      WorldLayout.Domain d=new WorldLayout.Domain(1,xmin,zmin,xmax,zmax,64);
      WorldLayout.Rect urban, farm;
      if(orientation==0){
         urban=new WorldLayout.Rect(ox,oz,ox+3,oz+length-1);
         farm=new WorldLayout.Rect(ox+6,oz,ox+12,oz+length-1);
      }else{
         urban=new WorldLayout.Rect(ox,oz,ox+length-1,oz+3);
         farm=new WorldLayout.Rect(ox,oz+6,ox+length-1,oz+12);
      }
      d.blocks.add(urban); d.contexts.put(urban,ContextType.RESIDENTIAL);
      d.blocks.add(farm); d.contexts.put(farm,ContextType.FARM);
      for(WorldLayout.Rect r: d.blocks){
         for(int x=r.minX;x<=r.maxX;x++) for(int z=r.minZ;z<=r.maxZ;z++)set(d,x,z,WorldLayout.BLOCK);
      }
      for(int t=0;t<length;t++) {
         int x=orientation==0 ? ox+4 : ox+t;
         int z=orientation==0 ? oz+t : oz+4;
         for(int shift=0;shift<2;shift++)set(d,x+(orientation==0?shift:0),z+(orientation==1?shift:0),WorldLayout.STREET);
      }
      // Coupled full-width road connections at each end; otherwise an
      // end could be disconnected when one lane is absorbed.
      for(int t: new int[]{-1,length}){
         int x=orientation==0?ox+4:ox+t;
         int z=orientation==0?oz+t:oz+4;
         for(int shift=0;shift<2;shift++)set(d,x+(orientation==0?shift:0),z+(orientation==1?shift:0),WorldLayout.STREET);
      }
      return d;
   }
   static void run(String label,WorldLayout.Domain d,int orientation,int offset,int length,boolean expected) {
      int ox=-86+offset,oz=-95+offset;
      int beforeBlocks=d.blocks.size();
      int removed=DoubleFrontageRoads.prevent(d,17);
      must(removed==(expected?length:0),label+" moved="+removed);
      must(d.blocks.size()==beforeBlocks,label+" block count changed");
      for(int t=0;t<length;t++) {
         int x=orientation==0?ox+4:ox+t;
         int z=orientation==0?oz+t:oz+4;
         int first=d.kindAt(x,z),second=d.kindAt(x+(orientation==0?1:0),z+(orientation==1?1:0));
         must(expected ? ((first==WorldLayout.BLOCK && second==WorldLayout.STREET)
                            || (first==WorldLayout.STREET && second==WorldLayout.BLOCK))
                       : (first==WorldLayout.STREET && second==WorldLayout.STREET),
                 label+" roadway pair invalid: "+first+"/"+second);
      }
      for(int t:new int[]{-1,length}){
         int x=orientation==0?ox+4:ox+t;
         int z=orientation==0?oz+t:oz+4;
         must(isRoad(d,x,z)&&isRoad(d,x+(orientation==0?1:0),z+(orientation==1?1:0)),label+" end join broken");
      }
      if(expected){
         for(WorldLayout.Rect r : d.blocks){
            must((orientation==0?r.width():r.depth())<=17,label+" illegal size");
            for(int x=r.minX;x<=r.maxX;x++)for(int z=r.minZ;z<=r.maxZ;z++){
               must(d.kindAt(x,z)==WorldLayout.BLOCK,label+" rectangle has hole");
            }
         }
         must(DoubleFrontageRoads.prevent(d,17)==0,label+" not idempotent");
      }
   }
   public static void main(String[]args){
      int cases=0;
      for(int orientation=0;orientation<2;orientation++)for(int offset:new int[]{-120,-17,0,43,261})for(int length:new int[]{3,5,7,9}){
         WorldLayout.Domain d=fixture(orientation,offset,length);
         run("valid/"+orientation+"/"+offset+"/"+length,d,orientation,offset,length,true); cases++;
         WorldLayout.Domain fixed=fixture(orientation,offset,length);
         int ox=-86+offset,oz=-95+offset;
         int px=orientation==0?ox+4:ox, pz=orientation==0?oz:oz+4;
         fixed.fixedRoadBeforeCarve=new boolean[fixed.kind.length][fixed.kind[0].length];
         fixed.fixedRoadBeforeCarve[px-fixed.minX][pz-fixed.minZ]=true;
         fixed.fixedRoadBeforeCarve[px+(orientation==0?1:0)-fixed.minX][pz+(orientation==1?1:0)-fixed.minZ]=true;
         run("fixed",fixed,orientation,offset,length,false); cases++;
         WorldLayout.Domain reserved=fixture(orientation,offset,length);
         reserved.urbanBoundaryRoads.add(key(px,pz));
         reserved.urbanBoundaryRoads.add(key(px+(orientation==0?1:0),pz+(orientation==1?1:0)));
         run("protected",reserved,orientation,offset,length,false); cases++;
         WorldLayout.Domain absent=fixture(orientation,offset,length);
         int x=orientation==0?ox+4:ox-1, z=orientation==0?oz-1:oz+4;
         set(absent,x,z,WorldLayout.UNCLAIMED);
         must(DoubleFrontageRoads.prevent(absent,17)==0,"missing join got collapsed"); cases++;
         WorldLayout.Domain highway=fixture(orientation,offset,length);
         set(highway,px,pz,WorldLayout.LANE);
         // This control deliberately changed the original near cell so
         // verify only that the algorithm declined any district expansion.
         must(DoubleFrontageRoads.prevent(highway,17)==0,"highway lane consumed");cases++;
         WorldLayout.Domain tooBig=fixture(orientation,offset,length);
         must(DoubleFrontageRoads.prevent(tooBig,4)==0,"max size ignored");cases++;
      }
      // Exact R30d two-column street footprint on the seed-derived X=-81
      // boundary. The far road is intentionally outside this domain: querying
      // a neighboring domain to detect it would make chunk order matter.
      for(int shift:new int[]{-198,-99,0,99,198}) {
         int outer=-81+shift;
         WorldLayout.Domain d=new WorldLayout.Domain(1,outer-12,-102,outer-1,-83,64);
         WorldLayout.Rect soccer=new WorldLayout.Rect(outer-5,-95,outer-2,-91);
         d.blocks.add(soccer);d.contexts.put(soccer,ContextType.PARK);
         for(int x=soccer.minX;x<=soccer.maxX;x++)for(int z=soccer.minZ;z<=soccer.maxZ;z++)set(d,x,z,WorldLayout.BLOCK);
         for(int z=-96;z<=-90;z++)set(d,outer-1,z,WorldLayout.STREET);
         DoubleFrontageRoads.FixedBoundary lattice=(x,z)->x==outer&&z>=-96&&z<=-90;
         must(DoubleFrontageRoads.prevent(d,17,lattice)==5,"live R30d fixed-boundary lane not absorbed shift="+shift);
         WorldLayout.Rect newBlock=d.blocks.get(0);
         must(newBlock.width()==5&&newBlock.depth()==5,"live R30d 5x5 not formed");
         for(int z=-95;z<=-91;z++)must(d.kindAt(outer-1,z)==WorldLayout.BLOCK,"live R30d site not rectangular");
         must(DoubleFrontageRoads.prevent(d,17,lattice)==0,"live R30d not idempotent");
         cases++;
         // A fixed lattice lane that has a gap in the middle is not a
         // continuous alternative, so the local road must not be deleted.
         WorldLayout.Domain gap=new WorldLayout.Domain(1,outer-12,-102,outer-1,-83,64);
         WorldLayout.Rect same=new WorldLayout.Rect(outer-5,-95,outer-2,-91);
         gap.blocks.add(same);gap.contexts.put(same,ContextType.PARK);
         for(int x=same.minX;x<=same.maxX;x++)for(int z=same.minZ;z<=same.maxZ;z++)set(gap,x,z,WorldLayout.BLOCK);
         for(int z=-96;z<=-90;z++)set(gap,outer-1,z,WorldLayout.STREET);
         DoubleFrontageRoads.FixedBoundary interrupted=(x,z)->x==outer&&z>=-96&&z<=-90&&z!=-93;
         must(DoubleFrontageRoads.prevent(gap,17,interrupted)==0,"broken neighboring lattice falsely accepted");
         cases++;
      }
      System.out.println("PASS double-frontage road prevention cases="+cases);
   }
}
