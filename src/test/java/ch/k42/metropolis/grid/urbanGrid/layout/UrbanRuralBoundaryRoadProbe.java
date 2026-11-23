package ch.k42.metropolis.grid.urbanGrid.layout;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.*;
public final class UrbanRuralBoundaryRoadProbe {
 public static void main(String[] args) {
  int passed=0;
  for(int mode=0;mode<4;mode++) for(int size=5;size<=14;size++) for(int origin=-40;origin<=40;origin+=40) {
    WorldLayout.Domain d=new WorldLayout.Domain(1,origin-4,origin-4,origin+size+6,origin+size+18,64);
    // Relative geometry: urban along north edge, countryside immediately south of it.
    WorldLayout.Rect city=new WorldLayout.Rect(origin,origin,origin+size-1,origin+6);
    WorldLayout.Rect farm=new WorldLayout.Rect(origin,origin+7,origin+size-1,origin+size+13);
    d.blocks.add(city);d.blocks.add(farm);d.contexts.put(city,ContextType.RESIDENTIAL);d.contexts.put(farm,ContextType.FARM);
    for(WorldLayout.Rect r:d.blocks) for(int x=r.minX;x<=r.maxX;x++)for(int z=r.minZ;z<=r.maxZ;z++)d.kind[x-d.minX][z-d.minZ]=(byte)WorldLayout.BLOCK;
    int y=origin+7,x0=origin-1,x1=origin+size;
    d.kind[x0-d.minX][y-d.minZ]=(byte)WorldLayout.STREET;
    d.kind[x1-d.minX][y-d.minZ]=(byte)WorldLayout.STREET;
    d.kind[x0-d.minX][y-1-d.minZ]=(byte)WorldLayout.STREET;
    d.kind[x1-d.minX][y-1-d.minZ]=(byte)WorldLayout.STREET;
    // no repair if no end connection, or no urban contact, or too-thin farm
    if(mode==1)d.kind[x1-d.minX][y-d.minZ]=(byte)WorldLayout.BLOCK;
    if(mode==2)d.contexts.put(city,ContextType.FARM);
    if(mode==3)d.kind[x0-d.minX][y-1-d.minZ]=(byte)WorldLayout.BLOCK;
    int changed=UrbanRuralBoundaryRoads.repair(d,4);
    if(mode==0) {
       if(changed!=size || d.blocks.contains(farm) || d.kindAt(origin,y)!=WorldLayout.STREET || d.kindAt(origin,y+1)!=WorldLayout.BLOCK || !d.urbanBoundaryRoads.contains(((long)x1<<32)^(y&0xffffffffL))) throw new AssertionError("bad repair "+size+" "+origin+" changed="+changed);
       for(int x=origin;x<origin+size;x++)if(d.kindAt(x,y)!=WorldLayout.STREET)throw new AssertionError("incomplete road");
    } else if (changed!=0 || d.blocks.size()!=2) throw new AssertionError("invalid candidate accepted mode="+mode);
    passed++;
  }
  System.out.println("PASS road boundary repairs and invalid-candidate guards: "+passed);
 }
}
