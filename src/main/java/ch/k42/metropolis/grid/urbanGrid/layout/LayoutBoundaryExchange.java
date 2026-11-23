package ch.k42.metropolis.grid.urbanGrid.layout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayDeque;

/**
 * Repairs district/frontage ownership by exchanging edges of neighboring
 * rectangles. All proposed cell moves remain temporary until the complete
 * rectangle, road-separation and road-connectivity checks pass.
 *
 * Source reconstructed from the R30ea bytecode; this is not a new planner.
 */
final class LayoutBoundaryExchange {
    private static final int BLOCK=3, STREET=2, ZONE=4, LANE=1;
    private static final int[][] DIR={{0,-1},{1,0},{0,1},{-1,0}};
    private LayoutBoundaryExchange() { }

    static int repair(WorldLayout.Domain domain, WorldLayout.Mask water, int min, int max) {
        int reclaimed=0;
        for (int round=0;round<12;round++) {
            Plan best=null;
            int bestGain=0;
            for(int a=0;a<domain.blocks.size();a++) {
                WorldLayout.Rect r=domain.blocks.get(a);
                if(domain.landmarks.containsKey(r))continue;
                for(int side=0;side<4;side++) {
                    Plan candidate=roadSlide(domain,water,min,max,a,side);
                    int gain=candidate==null?0:candidate.gain();
                    if(candidate!=null && gain>=bestGain && candidate.valid() &&
                       (gain>bestGain || best==null || candidate.changes.size()<best.changes.size())) {
                        best=candidate;bestGain=gain;
                    }
                }
                for(int side=0;side<4;side++)for(int end=0;end<2;end++) {
                    Plan candidate=pivot(domain,water,min,max,a,side,end);
                    int gain=candidate==null?0:candidate.gain();
                    if(candidate!=null&&gain>=bestGain&&candidate.valid()&&
                       (gain>bestGain || best==null || candidate.changes.size()<best.changes.size())) {
                        best=candidate;bestGain=gain;
                    }
                    // The original bytecode only explores an auxiliary parallel
                    // street absorption if this pivot already outranks the best.
                    if(candidate!=null&&gain>bestGain) {
                        for(int b=0;b<domain.blocks.size();b++) {
                            if(b==a||domain.landmarks.containsKey(domain.blocks.get(b)))continue;
                            Plan probe=candidate.copy();
                            if(probe.absorbParallelStreet(b)&&probe.gain()>bestGain&&probe.valid()) {
                                best=probe;bestGain=probe.gain();
                            }
                        }
                    }
                }
            }
            if(best==null)break;
            best.commit();reclaimed+=bestGain;
        }
        return reclaimed;
    }

    /** Reproduces R30ea's first-valid, atomic multi-district dry-gap exchange. */
    static int repairDryGapPerimeters(WorldLayout.Domain domain,WorldLayout.Mask water,int min,int max) {
        int changed=0;
        for(int round=0;round<24;round++) {
            Plan chosen=null;
            search: for(WorldLayout.Rect base:domain.blocks) for(int side=0;side<4;side++) {
                int nx=DIR[side][0],nz=DIR[side][1];
                int count=(side&1)==0?base.width():base.depth();
                for(int t=0;t<count;t++) {
                    int x=(side&1)==0?base.minX+t:side==1?base.maxX:base.minX;
                    int z=(side&1)==1?base.minZ+t:side==2?base.maxZ:base.minZ;
                    int gapX=x+nx,gapZ=z+nz;
                    if(domain.kindAt(gapX,gapZ)!=ZONE || domain.kindAt(x+2*nx,z+2*nz)!=STREET ||
                       (water!=null&&water.at(gapX,gapZ)))continue;
                    int contactX=x+3*nx,contactZ=z+3*nz;
                    for(int b=0;b<domain.blocks.size();b++) {
                        WorldLayout.Rect facing=domain.blocks.get(b);
                        if(facing==base||domain.landmarks.containsKey(facing) ||
                           !(contactX>=facing.minX&&contactX<=facing.maxX&&contactZ>=facing.minZ&&contactZ<=facing.maxZ) ||
                           (side==0&&facing.maxZ!=contactZ) ||
                           (side==2&&facing.minZ!=contactZ) ||
                           (side==1&&facing.minX!=contactX) ||
                           (side==3&&facing.maxX!=contactX))continue;
                        int opposite=(side+2)%4;
                        Plan plan=new Plan(domain,water,min,max);
                        if(!plan.replace(b,expand(facing,opposite)))continue;
                        boolean valid=true;
                        int edgeCount=(opposite&1)==0?facing.width():facing.depth();
                        for(int j=0;j<edgeCount;j++) {
                            int fx=(opposite&1)==0?facing.minX+j:opposite==1?facing.maxX:facing.minX;
                            int fz=(opposite&1)==1?facing.minZ+j:opposite==2?facing.maxZ:facing.minZ;
                            int xx=fx+2*DIR[opposite][0],zz=fz+2*DIR[opposite][1];
                            int type=domain.kindAt(xx,zz);
                            if(type==STREET||type==LANE)continue;
                            if(plan.dryPocket(xx,zz)) {
                                if(!plan.move(xx,zz,STREET)){valid=false;break;}
                                continue;
                            }
                            if(type!=BLOCK){valid=false;break;}
                            int owner=-1;
                            for(int c=0;c<domain.blocks.size();c++) {
                                WorldLayout.Rect other=domain.blocks.get(c);
                                if(c==b || domain.landmarks.containsKey(other) || !(xx>=other.minX&&xx<=other.maxX&&zz>=other.minZ&&zz<=other.maxZ) ||
                                   (opposite==3&&other.maxX!=xx) ||
                                   (opposite==1&&other.minX!=xx) ||
                                   (opposite==0&&other.maxZ!=zz) ||
                                   (opposite==2&&other.minZ!=zz))continue;
                                owner=c;break;
                            }
                            if(owner<0){valid=false;break;}
                            if(plan.rects.containsKey(owner)) {
                                if(plan.get(xx,zz)!=STREET){valid=false;break;}
                                continue;
                            }
                            WorldLayout.Rect old=domain.blocks.get(owner);
                            WorldLayout.Rect shrunk=switch(opposite) {
                                case 0 -> new WorldLayout.Rect(old.minX,old.minZ,old.maxX,old.maxZ-1);
                                case 1 -> new WorldLayout.Rect(old.minX+1,old.minZ,old.maxX,old.maxZ);
                                case 2 -> new WorldLayout.Rect(old.minX,old.minZ+1,old.maxX,old.maxZ);
                                default -> new WorldLayout.Rect(old.minX,old.minZ,old.maxX-1,old.maxZ);
                            };
                            if(!plan.replace(owner,shrunk)){valid=false;break;}
                        }
                        if(valid && plan.valid()) {chosen=plan;break search;}
                    }
                }
            }
            if(chosen==null)break;
            changed+=chosen.gain();
            chosen.commit();
        }
        return changed;
    }

    private static WorldLayout.Rect expand(WorldLayout.Rect r,int side) {
        return switch(side) {
            case 0 -> new WorldLayout.Rect(r.minX,r.minZ-1,r.maxX,r.maxZ);
            case 1 -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX+1,r.maxZ);
            case 2 -> new WorldLayout.Rect(r.minX,r.minZ,r.maxX,r.maxZ+1);
            default -> new WorldLayout.Rect(r.minX-1,r.minZ,r.maxX,r.maxZ);
        };
    }
    private static WorldLayout.Rect pivotRect(WorldLayout.Rect r,int side,int end) {
        return switch(side) {
            case 0 -> new WorldLayout.Rect(r.minX+(end==0?1:0),r.minZ-1,r.maxX-(end==1?1:0),r.maxZ);
            case 1 -> new WorldLayout.Rect(r.minX,r.minZ+(end==0?1:0),r.maxX+1,r.maxZ-(end==1?1:0));
            case 2 -> new WorldLayout.Rect(r.minX+(end==0?1:0),r.minZ,r.maxX-(end==1?1:0),r.maxZ+1);
            default -> new WorldLayout.Rect(r.minX-1,r.minZ+(end==0?1:0),r.maxX,r.maxZ-(end==1?1:0));
        };
    }
    private static Plan roadSlide(WorldLayout.Domain d,WorldLayout.Mask water,int min,int max,int i,int side) {
        WorldLayout.Rect r=d.blocks.get(i); Plan p=new Plan(d,water,min,max);
        int n=(side&1)==0?r.width():r.depth();
        for(int t=0;t<n;t++) {
            int x=side==1?r.maxX:side==3?r.minX:r.minX+t;
            int z=side==0?r.minZ:side==2?r.maxZ:r.minZ+t;
            int nx=x+DIR[side][0],nz=z+DIR[side][1];
            int nx2=nx+DIR[side][0],nz2=nz+DIR[side][1];
            if(d.kindAt(nx,nz)!=STREET||!p.dryPocket(nx2,nz2)||!p.move(nx2,nz2,STREET))return null;
        }
        return p.replace(i,expand(r,side))?p:null;
    }
    private static Plan pivot(WorldLayout.Domain d,WorldLayout.Mask water,int min,int max,int i,int side,int end) {
        Plan p=new Plan(d,water,min,max);
        if(!p.replace(i,pivotRect(d.blocks.get(i),side,end)))return null;
        return p.gain()>=2 && p.countMovedBlockToStreet()>=2 ? p : null;
    }

    static final class Plan {
        private static boolean inside(WorldLayout.Rect r,int x,int z){return x>=r.minX&&x<=r.maxX&&z>=r.minZ&&z<=r.maxZ;}
        final WorldLayout.Domain d;
        final WorldLayout.Mask water;
        final int min,max;
        final Map<Long,Integer> changes=new HashMap<>();
        final Map<Integer,WorldLayout.Rect> rects=new HashMap<>();
        Plan(WorldLayout.Domain d,WorldLayout.Mask water,int min,int max) {this.d=d;this.water=water;this.min=min;this.max=max;}
        Plan copy() {
            Plan p=new Plan(d,water,min,max);p.changes.putAll(changes);p.rects.putAll(rects);return p;
        }
        static long key(int x,int z) {return ((long)x<<32)^(z&0xffffffffL);}
        int original(int x,int z) {return d.kindAt(x,z);}
        int get(int x,int z) {return changes.getOrDefault(key(x,z),original(x,z));}
        boolean in(int x,int z) {return x>=d.minX&&x<=d.maxX&&z>=d.minZ&&z<=d.maxZ;}
        boolean dryPocket(int x,int z) {return in(x,z)&&original(x,z)==ZONE&&(water==null||!water.at(x,z));}
        boolean protectedCell(int x,int z) {
            if(!in(x,z))return true;
            int ix=x-d.minX,iz=z-d.minZ;
            return (d.fixedRoadBeforeCarve!=null&&d.fixedRoadBeforeCarve[ix][iz]) ||
                (d.shoreRoad!=null&&d.shoreRoad[ix][iz]) || d.creekOutletRing.containsKey(key(x,z)) ||
                d.urbanBoundaryRoads.contains(key(x,z));
        }
        boolean move(int x,int z,int k) {
            if(!in(x,z))return false;
            int orig=original(x,z);
            if(orig==LANE || orig==ZONE&&!dryPocket(x,z))return false;
            if(orig==STREET&&protectedCell(x,z))return false;
            Integer old=changes.get(key(x,z));
            if(old!=null&&old!=k)return false;
            if(old==null&&orig!=k)changes.put(key(x,z),k);
            return true;
        }
        boolean replace(int index,WorldLayout.Rect next) {
            if(rects.containsKey(index))return false;
            WorldLayout.Rect current=d.blocks.get(index);
            if(d.landmarks.containsKey(current))return false;
            if(next.width()<min||next.depth()<min||next.width()>max||next.depth()>max)return false;
            for(int x=current.minX;x<=current.maxX;x++)for(int z=current.minZ;z<=current.maxZ;z++)
                if(!inside(next,x,z)&&!move(x,z,STREET))return false;
            for(int x=next.minX;x<=next.maxX;x++)for(int z=next.minZ;z<=next.maxZ;z++)
                if(!inside(current,x,z)) {
                    int old=original(x,z);
                    if(old!=STREET&&!dryPocket(x,z))return false;
                    if(!move(x,z,BLOCK))return false;
                }
            rects.put(index,next);return true;
        }
        int gain() {
            int count=0;
            for(Map.Entry<Long,Integer> e:changes.entrySet()) {
                int x=(int)(e.getKey()>>32),z=(int)(long)e.getKey();
                if(original(x,z)==ZONE&&e.getValue()!=ZONE)count++;
            }
            return count;
        }
        int countMovedBlockToStreet() {
            int count=0;
            for(Map.Entry<Long,Integer> e:changes.entrySet()) {
                int x=(int)(e.getKey()>>32),z=(int)(long)e.getKey();
                if(original(x,z)==BLOCK&&e.getValue()==STREET)count++;
            }
            return count;
        }
        boolean absorbParallelStreet(int index) {
            WorldLayout.Rect r=d.blocks.get(index);
            if(rects.containsKey(index))return false;
            for(int side=0;side<4;side++) {
                WorldLayout.Rect next=expand(r,side);
                if(next.width()>max||next.depth()>max)continue;
                boolean adjacent=false,allowed=true;
                for(int x=next.minX;x<=next.maxX;x++)for(int z=next.minZ;z<=next.maxZ;z++) {
                    if(inside(r,x,z))continue;
                    if(original(x,z)!=STREET||protectedCell(x,z))allowed=false;
                    for(int[]dir:DIR)if(original(x+dir[0],z+dir[1])==BLOCK&&get(x+dir[0],z+dir[1])==STREET)adjacent=true;
                }
                if(allowed&&adjacent)return replace(index,next);
            }
            return false;
        }
        boolean valid() {
            if(gain()<2)return false;
            for(Map.Entry<Long,Integer> e:changes.entrySet()) {
                int x=(int)(e.getKey()>>32),z=(int)(long)e.getKey();
                for(int a=x-1;a<=x;a++)for(int b=z-1;b<=z;b++) {
                    if(road(a,b,false)&&road(a+1,b,false)&&road(a,b+1,false)&&road(a+1,b+1,false))continue;
                    if(road(a,b,true)&&road(a+1,b,true)&&road(a,b+1,true)&&road(a+1,b+1,true))return false;
                }
                if(e.getValue()==STREET) {
                    boolean neighbor=false;
                    for(int[]dir:DIR)if(road(x+dir[0],z+dir[1],true))neighbor=true;
                    if(!neighbor)return false;
                }
            }
            return preservesBlockSeparators()&&preservesRoadConnectivity();
        }
        boolean preservesBlockSeparators() {
            for(int i=0;i<d.blocks.size();i++) {
                WorldLayout.Rect orig=d.blocks.get(i),r=rects.getOrDefault(i,orig);
                boolean moved=rects.containsKey(i);
                for(int side=0;side<4;side++) {
                    int len=(side&1)==0?r.width():r.depth();
                    for(int n=0;n<len;n++) {
                        int x=(side&1)==0?r.minX+n:side==1?r.maxX+1:r.minX-1;
                        int z=(side&1)==1?r.minZ+n:side==0?r.minZ-1:r.maxZ+1;
                        if(moved&&!road(x,z,true))return false;
                        if(!moved&&road(x,z,false)&&!road(x,z,true))return false;
                    }
                }
            }
            return true;
        }
        boolean road(int x,int z,boolean newState) {int k=newState?get(x,z):original(x,z);return k==STREET||k==LANE;}
        boolean preservesRoadConnectivity() {
            int[][] before=roadLabels(false),after=roadLabels(true);
            int nb=0,na=0;
            for(int[] a:before)for(int v:a)nb=Math.max(nb,v);
            for(int[] a:after)for(int v:a)na=Math.max(na,v);
            if(na>nb)return false;
            int[] mapping=new int[nb+1];boolean[] mapped=new boolean[na+1];
            for(int x=0;x<before.length;x++)for(int z=0;z<before[x].length;z++) {
                int b=before[x][z],a=after[x][z];
                if(b==0||a==0)continue;
                if(mapping[b]!=0&&mapping[b]!=a)return false;
                mapping[b]=a;mapped[a]=true;
            }
            for(int i=1;i<=na;i++)if(!mapped[i])return false;
            return true;
        }
        int[][] roadLabels(boolean revised) {
            int width=d.kind.length,depth=d.kind[0].length,label=0;
            int[][] groups=new int[width][depth];int[] queue=new int[width*depth];
            for(int x=0;x<width;x++)for(int z=0;z<depth;z++) {
                if(groups[x][z]!=0||!road(d.minX+x,d.minZ+z,revised))continue;
                int front=0,back=0;groups[x][z]=++label;queue[back++]=x*depth+z;
                while(front<back) {
                    int pos=queue[front++],px=pos/depth,pz=pos%depth;
                    for(int[]dir:DIR) {
                        int nx=px+dir[0],nz=pz+dir[1];
                        if(nx<0||nx>=width||nz<0||nz>=depth||groups[nx][nz]!=0||!road(d.minX+nx,d.minZ+nz,revised))continue;
                        groups[nx][nz]=label;queue[back++]=nx*depth+nz;
                    }
                }
            }
            return groups;
        }
        void commit() {
            for(Map.Entry<Long,Integer> e:changes.entrySet()) {
                int x=(int)(e.getKey()>>32),z=(int)(long)e.getKey();
                d.kind[x-d.minX][z-d.minZ]=e.getValue().byteValue();
            }
            for(Map.Entry<Integer,WorldLayout.Rect>e:rects.entrySet())d.blocks.set(e.getKey(),e.getValue());
        }
    }
}
