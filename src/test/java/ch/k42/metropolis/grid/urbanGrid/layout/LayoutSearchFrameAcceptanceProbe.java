package ch.k42.metropolis.grid.urbanGrid.layout;

import java.util.concurrent.atomic.AtomicInteger;
/**
 * R11.1 reference-correct acceptance timing.
 *
 * The historical version of this probe expected the recursive exact tiler to
 * donate a street row after the acceptance callback rejected an unwrapped
 * rectangle. R30ea does not do that: the candidate is rejected atomically,
 * and frame reservation is evaluated on a complete candidate by the planner.
 * Both outcomes below were checked against R30ea's actual LayoutSearch class.
 */
public final class LayoutSearchFrameAcceptanceProbe {
    static int checks;
    static void ok(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    public static void main(String[] args) {
        for(int x0:new int[]{-91,7})for(int z0:new int[]{-43,21}) {
            boolean[][] free=new boolean[6][6];for(boolean[] row:free)java.util.Arrays.fill(row,true);
            int wetZ=z0+6;
            AtomicInteger tries=new AtomicInteger();
            LayoutSearch.Result rejected=LayoutSearch.solve(free,x0,z0,4,9,98171L,4,
                candidate->{
                    tries.incrementAndGet();
                    for(WorldLayout.Rect r:candidate.blocks)if(r.maxZ+1>=wetZ)return null;
                    return candidate;
                },(x,z)->z!=wetZ);
            ok(rejected==null,"a rejected frame must not be published "+x0+","+z0);
            ok(tries.get()>=1,"the full rectangle was tested for acceptance");
            for(boolean[] row:free)for(boolean cell:row)ok(cell,"failed candidate did not mutate free source");

            LayoutSearch.Result accepted=LayoutSearch.solve(free,x0,z0,4,9,98171L,4,
                candidate->candidate,(x,z)->true);
            ok(accepted!=null,"valid complete candidate is accepted");
            ok(accepted.blocks.size()==1,"accepted candidate retains its one rectangle");
            WorldLayout.Rect b=accepted.blocks.get(0);
            ok(b.minX==x0&&b.minZ==z0&&b.maxX==x0+5&&b.maxZ==z0+5,
                    "exact fit is not shortened for an unneeded road donation");
            for(int x=0;x<6;x++)for(int z=0;z<6;z++)
                ok(!accepted.street[x][z],"no unplanned internal street "+x+","+z);

            boolean[][] small=new boolean[4][4];for(boolean[] row:small)java.util.Arrays.fill(row,true);
            LayoutSearch.Result no=LayoutSearch.solve(small,x0,z0,4,9,98171L,3,
                cand->{
                    for(WorldLayout.Rect r:cand.blocks)if(r.maxZ==z0+3)return null;
                    return cand;
                },(x,z)->z!=z0+4);
            ok(no==null,"rejected minimum-size rectangle is not published");
        }
        System.out.println("LayoutSearchFrameAcceptanceProbe PASS checks="+checks);
    }
}
