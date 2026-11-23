package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

/** Urban corners are protected together with their already-planned road sides. */
public final class R13_4_UrbanCornerProtectionProbe {
  static int checks;
  static void ok(boolean b,String desc){checks++;if(!b)throw new AssertionError(desc);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  public static void main(String[] args){
    for(int ox:new int[]{-100,45})for(int oz:new int[]{-107,82}){
      WorldLayout.Domain d=new WorldLayout.Domain(9,ox-5,oz-5,ox+13,oz+13,1);
      WorldLayout.Rect rect=new WorldLayout.Rect(ox,oz,ox+7,oz+7);
      d.blocks.add(rect);
      d.contexts.put(rect,ContextType.RESIDENTIAL);
      for(int x=ox-1;x<=ox+8;x++)for(int z=oz-1;z<=oz+8;z++){
        if(x==ox-1||x==ox+8||z==oz-1||z==oz+8)
          d.kind[x-d.minX][z-d.minZ]=WorldLayout.STREET;
        else d.kind[x-d.minX][z-d.minZ]=WorldLayout.BLOCK;
      }
      int protectedCount=UrbanRuralBoundaryRoads.protectExistingUrbanFrontage(d);
      ok(protectedCount==36,"all 36 ring cells must be protected");
      for(int x=ox-1;x<=ox+8;x++)for(int z=oz-1;z<=oz+8;z++){
        if(x==ox-1||x==ox+8||z==oz-1||z==oz+8)
          ok(d.urbanBoundaryRoads.contains(key(x,z)),"missing road owner "+x+","+z);
      }
      ok(UrbanRuralBoundaryRoads.protectExistingUrbanFrontage(d)==0,"idempotent protection");
      WorldLayout.Domain farm=new WorldLayout.Domain(10,ox-5,oz-5,ox+13,oz+13,1);
      farm.blocks.add(rect);
      farm.contexts.put(rect,ContextType.FARM);
      for(int x=ox-1;x<=ox+8;x++)for(int z=oz-1;z<=oz+8;z++){
        if(x==ox-1||x==ox+8||z==oz-1||z==oz+8)farm.kind[x-farm.minX][z-farm.minZ]=WorldLayout.STREET;
      }
      ok(UrbanRuralBoundaryRoads.protectExistingUrbanFrontage(farm)==0,"FARM/FARM stays mergable");
    }
    System.out.println("R13_4_UrbanCornerProtectionProbe PASS checks="+checks);
  }
}
