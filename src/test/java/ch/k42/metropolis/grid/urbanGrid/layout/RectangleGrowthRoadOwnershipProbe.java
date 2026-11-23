package ch.k42.metropolis.grid.urbanGrid.layout;
import java.lang.reflect.Method;
import java.util.Arrays;
/** An accepted rectangle may grow across a dry sliver ONLY to a real road. */
public final class RectangleGrowthRoadOwnershipProbe {
    private static int checks;
    private static void check(boolean ok, String info) {
        checks++; if (!ok) throw new AssertionError(info);
    }
    public static void main(String[] args) throws Exception {
        WorldLayout layout = new WorldLayout(null, 93445L, 4, 9, 99);
        Method grow=WorldLayout.class.getDeclaredMethod("grown",WorldLayout.Domain.class,
                WorldLayout.Rect.class,int.class,int.class);
        grow.setAccessible(true);
        for(int side=0;side<4;side++)for(int ox:new int[]{-119,22})for(int oz:new int[]{-39,54}) {
            WorldLayout.Domain d=new WorldLayout.Domain(1,ox-5,oz-5,ox+12,oz+12,1);
            for(byte[] row:d.kind) Arrays.fill(row,(byte)WorldLayout.ZONE);
            WorldLayout.Rect r=new WorldLayout.Rect(ox,oz,ox+5,oz+5);
            d.blocks.add(r);
            for(int x=r.minX;x<=r.maxX;x++)for(int z=r.minZ;z<=r.maxZ;z++)
                d.kind[x-d.minX][z-d.minZ]=(byte)WorldLayout.BLOCK;
            int len=(side&1)==0?r.width():r.depth();
            int dx=side==1?1:side==3?-1:0,dz=side==0?-1:side==2?1:0;
            for(int t=0;t<len;t++) {
                int x=(side&1)==0?r.minX+t:side==1?r.maxX:r.minX;
                int z=(side&1)==1?r.minZ+t:side==0?r.minZ:r.maxZ;
                // All cells immediately outside remain dry ZONE (the gap).
                // The second row controls whether there is a complete wrapper.
                d.kind[x+2*dx-d.minX][z+2*dz-d.minZ]=(byte)WorldLayout.ZONE;
            }
            check(grow.invoke(layout,d,r,side,9)==null,"must not grow into bare land "+side);
            for(int t=0;t<len;t++) {
                int x=(side&1)==0?r.minX+t:side==1?r.maxX:r.minX;
                int z=(side&1)==1?r.minZ+t:side==0?r.minZ:r.maxZ;
                d.kind[x+2*dx-d.minX][z+2*dz-d.minZ]=(byte)WorldLayout.STREET;
            }
            check(grow.invoke(layout,d,r,side,9)!=null,"reuse complete existing road "+side);
            int t=len/2;
            int x=(side&1)==0?r.minX+t:side==1?r.maxX:r.minX;
            int z=(side&1)==1?r.minZ+t:side==0?r.minZ:r.maxZ;
            d.kind[x+2*dx-d.minX][z+2*dz-d.minZ]=(byte)WorldLayout.ZONE;
            check(grow.invoke(layout,d,r,side,9)==null,"partial side cannot count as wrapper "+side);
        }
        System.out.println("RectangleGrowthRoadOwnershipProbe PASS checks="+checks);
    }
}
