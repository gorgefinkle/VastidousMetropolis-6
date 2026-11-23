package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import java.util.ArrayList;

/** One-cell district inset when an external perimeter can be completed safely. */
final class UrbanPerimeterInset {
   private static final int[][] DELTA={{0,-1},{1,0},{0,1},{-1,0}};
   private UrbanPerimeterInset() { }

   static int repair(WorldLayout.Domain domain,int minimum) {
      int repaired=0;
      for(WorldLayout.Rect rect:new ArrayList<>(domain.blocks)) {
         ContextType context=domain.contexts.get(rect);
         if(context==null || context.isMorphomos() || domain.landmarks.containsKey(rect)) continue;
         WorldLayout.Rect current=rect;
         for(int side=0;side<4;side++) {
            WorldLayout.Rect next=propose(domain,current,side,minimum);
            if(next==null)continue;
            for(int i=0;i<length(current,side);i++) {
               int x=edgeX(current,side,i), z=edgeZ(current,side,i);
               domain.kind[x-domain.minX][z-domain.minZ]=2;
               domain.urbanBoundaryRoads.add(key(x,z));
               repaired++;
            }
            int index=domain.blocks.indexOf(current);
            if(index<0)throw new IllegalStateException("Inset lost original rectangle");
            domain.blocks.set(index,next);
            domain.contexts.remove(current);
            domain.contexts.put(next,context);
            current=next;
         }
      }
      return repaired;
   }

   private static WorldLayout.Rect propose(WorldLayout.Domain d,WorldLayout.Rect r,int side,int minimum) {
      int length=length(r,side), dx=DELTA[side][0],dz=DELTA[side][1];
      if(((side&1)==0?r.depth():r.width())<=minimum)return null;
      for(int i=0;i<length;i++) {
         int x=edgeX(r,side,i),z=edgeZ(r,side,i);
         if(!in(d,x+dx,z+dz))return null;
         int kind=d.kindAt(x+dx,z+dz);
         if(kind==2||kind==1||kind==3)return null;
         if(d.kindAt(x,z)!=3)return null;
         if(d.creekOutletRing.containsKey(key(x,z))||d.urbanBoundaryRoads.contains(key(x,z)))return null;
      }
      int firstX=edgeX(r,side,0), firstZ=edgeZ(r,side,0);
      int lastX=edgeX(r,side,length-1),lastZ=edgeZ(r,side,length-1);
      int perpX=(side%2==0)?1:0,perpZ=(side%2==1)?1:0;
      if(!road(d,firstX-perpX,firstZ-perpZ)||!road(d,lastX+perpX,lastZ+perpZ))return null;
      int outRoads=0;
      for(int i=0;i<length;i++) {
         int x=edgeX(r,side,i),z=edgeZ(r,side,i);
         if(road(d,x+2*dx,z+2*dz))outRoads++;
      }
      if(outRoads>=2)return null;
      for(int i=0;i<length;i++) {
         int x=edgeX(r,side,i),z=edgeZ(r,side,i);
         for(int bx=x-1;bx<=x;bx++)for(int bz=z-1;bz<=z;bz++) {
            boolean allPermissible=true,allRoads=true;
            for(int sx=0;sx<2;sx++)for(int sz=0;sz<2;sz++) {
               int px=bx+sx,pz=bz+sz;
               boolean isRoad=road(d,px,pz);
               allRoads &= isRoad;
               boolean onEdge=((side&1)==0)
                       ? (pz==firstZ && px>=r.minX && px<=r.maxX)
                       : (px==firstX && pz>=r.minZ && pz<=r.maxZ);
               allPermissible &= isRoad || onEdge;
            }
            if(allPermissible&&!allRoads)return null;
         }
      }
      switch(side) {
         case 0:return new WorldLayout.Rect(r.minX,r.minZ+1,r.maxX,r.maxZ);
         case 1:return new WorldLayout.Rect(r.minX,r.minZ,r.maxX-1,r.maxZ);
         case 2:return new WorldLayout.Rect(r.minX,r.minZ,r.maxX,r.maxZ-1);
         default:return new WorldLayout.Rect(r.minX+1,r.minZ,r.maxX,r.maxZ);
      }
   }

   private static int length(WorldLayout.Rect r,int side) {return (side&1)==0?r.width():r.depth();}
   private static int edgeX(WorldLayout.Rect r,int side,int i) {return (side&1)==0?r.minX+i:side==1?r.maxX:r.minX;}
   private static int edgeZ(WorldLayout.Rect r,int side,int i) {return (side&1)==1?r.minZ+i:side==0?r.minZ:r.maxZ;}
   private static boolean in(WorldLayout.Domain d,int x,int z) {return x>=d.minX&&x<=d.maxX&&z>=d.minZ&&z<=d.maxZ;}
   private static boolean road(WorldLayout.Domain d,int x,int z) {int kind=d.kindAt(x,z);return kind==2||kind==1;}
   private static long key(int x,int z) {return ((long)x<<32)^((long)z&0xffffffffL);}
}
