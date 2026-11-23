package ch.k42.metropolis.grid.urbanGrid.layout;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.highway.HighwayNetwork;

/** Read completed neighboring ownership rather than counting outside cells as valid. */
public class R30ayDomainFrameProbe {
   public static void main(String[] args) {
      long seed=Long.parseLong(args[0]); int shape=Integer.parseInt(args[1]);
      WorldLayout.Mask mask=(x,z)->{
         double n=shape==0?9*Math.sin(x/8.0)+4*Math.sin(x/3.9)
               :shape==1?13*Math.sin(x/11.0)+4*Math.cos(x/4.7)
               :10*Math.sin(x/15.0)+6*Math.cos(x/3.4);
         return z>40+n;
      };
      HighwayNetwork net=new HighwayNetwork(seed,230,68,.5,1,0,20,null,9);
      WorldLayout layout=new WorldLayout(net,seed,7,14,99,18,27,mask);
      layout.setBlockSlack(3);
      layout.setZoner((bx,bz,sx,sz,minx,minz,maxx,maxz)->{
         ContextType[] out=new ContextType[bx.length];
         for(int i=0;i<out.length;i++)out[i]=(bx[i]/25%3==0)?ContextType.FOREST:ContextType.RESIDENTIAL;
         return out;
      });
      WorldLayout.Domain d=layout.domainAt(32,38);
      int missing=0,checked=0,foreign=0;StringBuilder sample=new StringBuilder();
      for(WorldLayout.Rect r:d.blocks) {
         ContextType c=d.contexts.get(r);
         if(c==null||c.isMorphomos())continue;
         for(int side=0;side<4;side++) for(int i=0;i<((side&1)==0?r.width():r.depth());i++) {
            int x=(side&1)==0?r.minX+i:(side==1?r.maxX+1:r.minX-1);
            int z=(side&1)==1?r.minZ+i:(side==0?r.minZ-1:r.maxZ+1);
            int k=d.kindAt(x,z);
            if(k==WorldLayout.UNCLAIMED && (x<d.minX||x>d.maxX||z<d.minZ||z>d.maxZ)){
               foreign++;
               k=layout.kindAt(x,z);
            }
            checked++;
            if(k!=WorldLayout.STREET&&k!=WorldLayout.LANE){
                if(sample.length()<2000)sample.append("missing ").append(r).append(" @").append(x).append(",").append(z).append(" kind=").append(k).append("\n");
                missing++;
            }
         }
      }
      System.out.println("seed="+seed+" shape="+shape+" developedFramesChecked="+checked+" foreign="+foreign+" missing="+missing);
      System.out.print(sample);
      if(missing>0)throw new AssertionError("unwrapped developed districts="+missing);
   }
}
