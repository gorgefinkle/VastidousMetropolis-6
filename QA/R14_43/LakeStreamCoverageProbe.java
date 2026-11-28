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
            WildernessFeatures.install(seed);
            int lakeCount=0,streamCount=0,elevatedOutletCount=0,cascadingOutletCount=0,maxOutletDrop=0;
            double nearestLakeBlocks=Double.POSITIVE_INFINITY;
            boolean diagnosed=false;
            StringBuilder lakePositions=new StringBuilder();
            for(int gx=-2;gx<=2;gx++) {
                for(int gz=-2;gz<=2;gz++) {
                    WildernessFeatures.LakeSite lake=features.lakeForCellProbe(gx,gz);
                    if(lake==null) continue;
                    lakeCount++;
                    nearestLakeBlocks=Math.min(nearestLakeBlocks,Math.hypot(lake.centerX,lake.centerZ));
                    if(lakePositions.length()>0)lakePositions.append("; ");
                    lakePositions.append("cell=(").append(gx).append(',').append(gz).append(") center=(")
                            .append(Math.round(lake.centerX)).append(',').append(Math.round(lake.centerZ))
                            .append(") radius=(").append(Math.round(lake.radiusX)).append(',')
                            .append(Math.round(lake.radiusZ)).append(")");
                    int cx=(int)Math.floor(lake.centerX/16.0);
                    int cz=(int)Math.floor(lake.centerZ/16.0);
                    if(features.lakeAt(cx,cz)==null || !water.isLake(cx,cz)
                            || water.kindAt(cx,cz)!=WaterField.Kind.LAKE) {
                        throw new AssertionError("Lake basin has no actual shared WaterField owner: seed="+seed
                                +" chunk=("+cx+","+cz+")");
                    }
                    HydrologySample lakeSample=water.hydrologySampleAt((int)Math.floor(lake.centerX),
                            (int)Math.floor(lake.centerZ));
                    if(lakeSample.feature()!=HydrologySample.Feature.LAKE||!lakeSample.openWater()
                            ||lakeSample.depth()<1||lakeSample.waterY()!=lake.waterY)
                        throw new AssertionError("Lake column does not resolve through the unified freshwater biome layer: feature="+lakeSample.feature()+" open="+lakeSample.openWater()+" signed="+lakeSample.signedDistanceBlocks()+" depth="+lakeSample.depth()+" sampleY="+lakeSample.waterY()+" siteY="+lake.waterY);
                    if(lake.stream==null) {
                        if(!diagnosed) {
                            int[] diag=features.outletDiagnosticsForProbe(lake);
                            // The no-target diagnostic intentionally returns a short tuple;
                            // print it as a whole rather than assuming the routed tuple width.
                            System.out.println("OUTLET-DIAG seed="+seed+" cell=("+gx+","+gz+") lakeChunk=("+cx+","+cz
                                    +") values="+java.util.Arrays.toString(diag));
                            diagnosed=true;
                        }
                        continue;
                    }
                    int point=Math.max(1,Math.min(lake.stream.pointCount()-2,lake.stream.pointCount()/2));
                    int sx=(int)Math.floor(lake.stream.pointXBlock(point)/16.0);
                    int sz=(int)Math.floor(lake.stream.pointZBlock(point)/16.0);
                    if(!lake.stream.isRiverOrBank(sx,sz))
                        throw new AssertionError("Outlet polyline fails to own its own route chunk");
                    HydrologyConvergenceKey endpoint=lake.stream.convergenceKey();
                    if(endpoint==null)throw new AssertionError("Outlet has no keyed confluence");
                    int last=lake.stream.pointCount()-1;
                    if(Math.abs(lake.stream.pointXBlock(last)-endpoint.blockX())>1.0e-7
                            ||Math.abs(lake.stream.pointZBlock(last)-endpoint.blockZ())>1.0e-7)
                        throw new AssertionError("Stream does not terminate at exact convergence-key coordinates");
                    boolean receiverOwned=switch(endpoint.receiver()) {
                        case OCEAN -> water.baseKindAt(endpoint.chunkX(),endpoint.chunkZ())==WaterField.Kind.OCEAN;
                        case RIVER -> water.baseKindAt(endpoint.chunkX(),endpoint.chunkZ())==WaterField.Kind.RIVER;
                        case CREEK -> water.isCreek(endpoint.chunkX(),endpoint.chunkZ());
                    };
                    if(!receiverOwned)throw new AssertionError("Convergence key does not match receiver ownership: "+endpoint);
                    HydrologySample receiverSample=water.hydrologySampleAt(
                            (int)Math.floor(endpoint.blockX()),(int)Math.floor(endpoint.blockZ()));
                    HydrologySample.Feature expectedReceiver=switch(endpoint.receiver()) {
                        case OCEAN -> HydrologySample.Feature.OCEAN;
                        case RIVER -> HydrologySample.Feature.RIVER;
                        case CREEK -> HydrologySample.Feature.CREEK;
                    };
                    if(lake.stream.receiverWaterY()!=receiverSample.waterY())
                        throw new AssertionError("Outlet water surface does not meet receiver water level: course="
                                +lake.stream.receiverWaterY()+" receiver="+receiverSample.waterY());
                    if(receiverSample.feature()!=expectedReceiver
                            ||!endpoint.equals(receiverSample.convergenceKey()))
                        throw new AssertionError("Receiver biome layer does not expose the same convergence key: expected="
                                +endpoint+" actual="+receiverSample.convergenceKey()+" feature="+receiverSample.feature());
                    int resolvedBeforeLookup=features.resolvedFeatureCellCountForProbe();
                    RiverProvider carrier=features.streamCarrierAt(sx,sz);
                    int resolvedAfterLookup=features.resolvedFeatureCellCountForProbe();
                    if(resolvedAfterLookup!=resolvedBeforeLookup)
                        throw new AssertionError("WaterField stream lookup lazily resolved more feature cells");
                    if(!(carrier instanceof WildernessStreamCourse))
                        throw new AssertionError("Shared feature plan lost its continuous stream carrier");
                    WildernessStreamCourse course=(WildernessStreamCourse)carrier;
                    double sinuosity=course.lengthBlocks()/Math.max(1.0,course.directLengthBlocks());
                    if(course.pointCount()<4||sinuosity<1.005)
                        throw new AssertionError("Outlet is too straight for a natural wilderness stream: points="
                                +course.pointCount()+" sinuosity="+sinuosity);
                    if(course.sourceWaterY()!=lake.waterY||course.receiverWaterY()>course.sourceWaterY())
                        throw new AssertionError("Outlet water surface does not descend from its source lake: source="
                                +course.sourceWaterY()+" receiver="+course.receiverWaterY());
                    int outletDrop=course.sourceWaterY()-course.receiverWaterY();
                    maxOutletDrop=Math.max(maxOutletDrop,outletDrop);
                    if(outletDrop>=6)elevatedOutletCount++;
                    if(course.cascadeCount()>0)cascadingOutletCount++;
                    if(course.sourceWaterY()-course.receiverWaterY()>=12
                            &&course.directLengthBlocks()>=72.0&&course.cascadeCount()<1)
                        throw new AssertionError("Elevated source lake did not create a multi-level cascade: drop="
                                +(course.sourceWaterY()-course.receiverWaterY())+" cascadeCount="+course.cascadeCount());
                    int previousWaterY=course.sourceWaterY();
                    for(int p=1;p<course.pointCount();p++) {
                        int level=course.waterYAt(course.pointXBlock(p),course.pointZBlock(p));
                        if(level>previousWaterY)
                            throw new AssertionError("Stream water surface rises downstream at course point "+p);
                        previousWaterY=level;
                    }
                    if(!water.isStream(sx,sz) || water.streamCarrierAt(sx,sz)==null)
                        throw new AssertionError("Shared WaterField does not expose the outlet as STREAM");
                    boolean hasLayerOwnedStreamSample=false;
                    for(int p=1;p<lake.stream.pointCount()-1;p++) {
                        int bx=(int)Math.floor(lake.stream.pointXBlock(p));
                        int bz=(int)Math.floor(lake.stream.pointZBlock(p));
                        HydrologySample streamSample=water.hydrologySampleAt(bx,bz);
                        if(streamSample.feature()==HydrologySample.Feature.STREAM
                                &&endpoint.equals(streamSample.convergenceKey())) {
                            hasLayerOwnedStreamSample=true;break;
                        }
                    }
                    if(!hasLayerOwnedStreamSample)
                        throw new AssertionError("No outlet column is represented by the unified stream biome layer");
                    streamCount++;
                }
            }
            if(lakeCount==0) {
                System.out.println("BASIN-SEARCH-DIAG seed="+seed+" "+features.basinSearchDiagnosticForProbe());
                throw new AssertionError("No planned lake basin appears in the 5x5 nearest feature-cell window for seed "+seed);
            }
            if(nearestLakeBlocks>8000.0)
                throw new AssertionError("Nearest planned lake is too remote for ordinary exploration: seed="+seed
                        +" distance="+Math.round(nearestLakeBlocks)+" blocks locations=["+lakePositions+"] "
                        +" latestBasinSearch={"+features.basinSearchDiagnosticForProbe()+"}");
            if(streamCount==0)
                throw new AssertionError("No connected outlet stream appears in the same 5x5 window for seed "+seed);
            System.out.println("LakeStreamCoverageProbe PASS seed="+seed
                    +" visibleLakePlans="+lakeCount+" connectedStreamPlans="+streamCount
                    +" registeredConvergenceEndpoints="+features.registeredConvergenceEndpointCountForProbe()
                    +" elevatedLakeOutlets="+elevatedOutletCount
                    +" cascadingOutlets="+cascadingOutletCount
                    +" maxOutletDrop="+maxOutletDrop
                    +" nearestLakeBlocks="+Math.round(nearestLakeBlocks));
        }
    }
}