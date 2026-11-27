package ch.k42.metropolis.grid.urbanGrid.wild;

import ch.k42.metropolis.grid.urbanGrid.layout.WaterField;
import ch.k42.metropolis.grid.urbanGrid.layout.WaterFields;
import ch.k42.metropolis.grid.urbanGrid.river.RiverProvider;
import ch.k42.metropolis.plugin.PluginConfig;

/** Exercises planned basins and their real shared WaterField ownership, not only command lookup. */
public final class LakeStreamCoverageProbe {
    public static void main(String[] args) {
        long[] seeds={
            -1482741523194359869L,
            1750414970189483479L,
            1414199025253064524L
        };
        for(long seed:seeds) {
            WildernessFeatures features=WildernessFeatures.forSeed(seed);
            WaterField water=WaterFields.forSeed(seed,PluginConfig.getBlockSizeMin()+2);
            int lakeCount=0,streamCount=0;
            boolean diagnosed=false;
            for(int gx=-2;gx<=2;gx++) {
                for(int gz=-2;gz<=2;gz++) {
                    WildernessFeatures.LakeSite lake=features.lakeForCellProbe(gx,gz);
                    if(lake==null) continue;
                    lakeCount++;
                    int cx=(int)Math.floor(lake.centerX/16.0);
                    int cz=(int)Math.floor(lake.centerZ/16.0);
                    if(features.lakeAt(cx,cz)==null || !water.isLake(cx,cz)
                            || water.kindAt(cx,cz)!=WaterField.Kind.LAKE) {
                        throw new AssertionError("Lake basin has no actual shared WaterField owner: seed="+seed
                                +" chunk=("+cx+","+cz+")");
                    }
                    if(lake.stream==null) {
                        if(!diagnosed) {
                            int[] diag=features.outletDiagnosticsForProbe(lake);
                            System.out.println("OUTLET-DIAG seed="+seed+" cell=("+gx+","+gz+") lakeChunk=("+cx+","+cz
                                    +") rawReceiving="+diag[0]+" safeTargets="+diag[1]+" routePoints="+diag[2]
                                    +" routeExpansions="+diag[3]+" frontier="+diag[4]);
                            diagnosed=true;
                        }
                        continue;
                    }
                    int point=Math.max(1,Math.min(lake.stream.pointCount()-2,lake.stream.pointCount()/2));
                    int sx=(int)Math.floor(lake.stream.pointXBlock(point)/16.0);
                    int sz=(int)Math.floor(lake.stream.pointZBlock(point)/16.0);
                    if(!lake.stream.isRiverOrBank(sx,sz))
                        throw new AssertionError("Outlet polyline fails to own its own route chunk");
                    RiverProvider carrier=features.streamCarrierAt(sx,sz);
                    if(!(carrier instanceof WildernessStreamCourse))
                        throw new AssertionError("Shared feature plan lost its continuous stream carrier");
                    if(!water.isStream(sx,sz) || water.streamCarrierAt(sx,sz)==null)
                        throw new AssertionError("Shared WaterField does not expose the outlet as STREAM");
                    streamCount++;
                }
            }
            if(lakeCount==0)
                throw new AssertionError("No planned lake basin appears in the 5x5 nearest feature-cell window for seed "+seed);
            if(streamCount==0)
                throw new AssertionError("No connected outlet stream appears in the same 5x5 window for seed "+seed);
            System.out.println("LakeStreamCoverageProbe PASS seed="+seed
                    +" visibleLakePlans="+lakeCount+" connectedStreamPlans="+streamCount);
        }
    }
}