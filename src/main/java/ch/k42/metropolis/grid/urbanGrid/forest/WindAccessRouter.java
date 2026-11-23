package ch.k42.metropolis.grid.urbanGrid.forest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Deterministic, bounded countryside road access search for wind-farm sites. */
public final class WindAccessRouter {
   private static final int[] DX={1,0,-1,0};
   private static final int[] DZ={0,1,0,-1};
   private static final int MAX_EXPANSIONS=3200;

   private WindAccessRouter() { }
   public interface Terrain {
      boolean wilderness(int chunkX,int chunkZ);
      boolean pavedStreet(int chunkX,int chunkZ);
      boolean clearSegment(int x0,int z0,int x1,int z1,boolean finalSegment);
   }
   private static final class Node {
      final int cx,cz,heading,steps,cost;
      final Node parent;
      Node(int cx,int cz,int heading,int steps,int cost,Node parent) {
         this.cx=cx;this.cz=cz;this.heading=heading;this.steps=steps;this.cost=cost;this.parent=parent;
      }
   }
   private record State(int cx,int cz,int heading) { }

   public static int[] find(int x,int z,int alongBlocks,int direction,int maxRadius,Terrain terrain) {
      int axis=direction&3;
      int originX=x+DX[axis]*alongBlocks,originZ=z+DZ[axis]*alongBlocks;
      int startX=originX+DX[axis]*24,startZ=originZ+DZ[axis]*24;
      if(!terrain.clearSegment(originX,originZ,startX,startZ,false))return null;
      final Map<Long,Boolean> cachedWilderness=new HashMap<>(),cachedPaved=new HashMap<>();
      Terrain memo=new Terrain() {
         private long cell(int cx,int cz){return ((long)cx<<32)^((long)cz&0xffffffffL);}
         @Override public boolean wilderness(int cx,int cz){return cachedWilderness.computeIfAbsent(cell(cx,cz),k->terrain.wilderness(cx,cz));}
         @Override public boolean pavedStreet(int cx,int cz){return cachedPaved.computeIfAbsent(cell(cx,cz),k->terrain.pavedStreet(cx,cz));}
         @Override public boolean clearSegment(int a,int b,int c,int d,boolean e){return terrain.clearSegment(a,b,c,d,e);}
      };
      int startCx=startX>>4,startCz=startZ>>4;
      if(!memo.wilderness(startCx,startCz))return null;
      PriorityQueue<Node> ready=new PriorityQueue<>(Comparator.comparingInt((Node n)->n.cost)
                  .thenComparingInt(n->n.steps).thenComparingInt(n->n.cx)
                  .thenComparingInt(n->n.cz).thenComparingInt(n->n.heading));
      Map<State,Integer> best=new HashMap<>();
      Node root=new Node(startCx,startCz,axis,0,0,null);
      ready.add(root);best.put(new State(startCx,startCz,axis),0);
      int anchorCx=x>>4,anchorCz=z>>4;
      int expansions=0;
      while(!ready.isEmpty() && expansions++<MAX_EXPANSIONS) {
         Node n=ready.poll();
         if(n.cost!=best.getOrDefault(new State(n.cx,n.cz,n.heading),Integer.MAX_VALUE))continue;
         int centerX=(n.cx<<4)+8,centerZ=(n.cz<<4)+8;
         for(int nextDir=0;nextDir<4;nextDir++) {
            int nextCx=n.cx+DX[nextDir],nextCz=n.cz+DZ[nextDir];
            if(Math.max(Math.abs(nextCx-anchorCx),Math.abs(nextCz-anchorCz))>maxRadius)continue;
            int projection=((nextCx<<4)+8-x)*DX[axis]+((nextCz<<4)+8-z)*DZ[axis];
            if(projection<alongBlocks-8)continue;
            if(memo.pavedStreet(nextCx,nextCz)) {
               int endX=nextDir==0?nextCx<<4:nextDir==2?(nextCx<<4)+15:(nextCx<<4)+8;
               int endZ=nextDir==1?nextCz<<4:nextDir==3?(nextCz<<4)+15:(nextCz<<4)+8;
               List<Node> pathNodes=new ArrayList<>();
               for(Node at=n;at!=null;at=at.parent)pathNodes.add(at);
               Collections.reverse(pathNodes);
               List<Integer> coords=new ArrayList<>();
               coords.add(originX);coords.add(originZ);coords.add(startX);coords.add(startZ);
               for(Node step:pathNodes) {
                  int px=(step.cx<<4)+8,pz=(step.cz<<4)+8;
                  if(coords.get(coords.size()-2)!=px || coords.get(coords.size()-1)!=pz){coords.add(px);coords.add(pz);}
               }
               coords.add(endX);coords.add(endZ);
               boolean clear=true;
               for(int i=2;i<coords.size();i+=2) {
                  if(!terrain.clearSegment(coords.get(i-2),coords.get(i-1),coords.get(i),coords.get(i+1),i==coords.size()-2)) {
                     clear=false;break;
                  }
               }
               if(!clear)continue;
               int[] answer=new int[3+coords.size()];
               answer[0]=axis;answer[1]=endX;answer[2]=endZ;
               for(int i=0;i<coords.size();i++)answer[3+i]=coords.get(i);
               return answer;
            }
            if(n.steps>=maxRadius*2 || !memo.wilderness(nextCx,nextCz))continue;
            int turn=n.heading==nextDir?0:n.heading==((nextDir+2)&3)?9:4;
            int cost=n.cost+10+turn;
            State s=new State(nextCx,nextCz,nextDir);
            if(cost>=best.getOrDefault(s,Integer.MAX_VALUE))continue;
            best.put(s,cost);
            ready.add(new Node(nextCx,nextCz,nextDir,n.steps+1,cost,n));
         }
      }
      return null;
   }
}
