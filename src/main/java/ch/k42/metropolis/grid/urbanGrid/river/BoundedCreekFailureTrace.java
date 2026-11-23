package ch.k42.metropolis.grid.urbanGrid.river;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Diagnostic trace of ownership and generated-chunk paint changes. */
public final class BoundedCreekFailureTrace {
    private static final int EXAMPLES_PER_REASON=12;
    private final int tileX,tileZ;
    private final Consumer<String> log;
    private final Map<Long,Cell> cells=new HashMap<>();
    private final Map<String,Integer> counts=new LinkedHashMap<>();
    private final Map<String,Integer> emitted=new HashMap<>();
    private final Map<Long,Long> postFingerprints=new HashMap<>();
    private final Map<Long,int[]> postBlockSamples=new HashMap<>();
    private final Map<Long,Integer> postBlockSampleBaseY=new HashMap<>();
    private String phase="REGISTER";
    private int proposals,parcelWrites,replacements,refused,commits,sampledPopulates,postCompleted,repaintAlarms,populatedCity,populatedRoad;

    public static final class Cell {
        final String site;
        final BoundedRiverOwnershipPlan.Owner proposed;
        BoundedRiverOwnershipPlan.Owner committed;
        String last="null",firstWriter="none",lastWriter="none";
        int writes;
        boolean morphomosRewriteAuthorized;
        String morphomosRewriteRule="none";
        Cell(String site,BoundedRiverOwnershipPlan.Owner proposed){this.site=site;this.proposed=proposed;}
    }

    public BoundedCreekFailureTrace(int tileX,int tileZ,Consumer<String> log) {
        if(log==null)throw new IllegalArgumentException("log required");
        this.tileX=tileX;this.tileZ=tileZ;this.log=log;
    }
    public static long key(int x,int z){return ((long)x<<32)^((long)z&0xffffffffL);}
    public static boolean isNatural(String name) {return name.equals("RiverParcel")||name.equals("OceanParcel")||name.equals("MorphomosParcel")||name.equals("TerrainFallbackParcel");}
    private static boolean isInfrastructure(String name) {return name.equals("RoadParcel")||name.equals("HighwayParcel")||name.equals("BridgeParcel")||name.equals("ClipboardParcel");}
    private static boolean isRoad(String name) {return name.equals("RoadParcel")||name.equals("HighwayParcel")||name.equals("BridgeParcel");}
    private void count(String reason){counts.merge(reason,1,Integer::sum);}
    private void report(String reason,String site,int x,int z,String detail){
        count(reason);
        int previous=emitted.getOrDefault(reason,0);emitted.put(reason,previous+1);
        if(previous<EXAMPLES_PER_REASON)log.accept("[R14-CREEK-FAILURE] "+reason+" site="+site+" chunk=("+x+","+z+") phase="+phase+" "+detail);
        else if(previous==EXAMPLES_PER_REASON)log.accept("[R14-CREEK-FAILURE] "+reason+" further examples suppressed; totals printed in SUMMARY");
    }
    public void phase(String next){if(next==null)throw new IllegalArgumentException("phase"); phase=next;}
    public void proposal(String site,int x,int z,BoundedRiverOwnershipPlan.Owner owner) {
        if(owner==BoundedRiverOwnershipPlan.Owner.OUTSIDE)return;
        Cell cell=new Cell(site,owner);
        Cell old=cells.putIfAbsent(key(x,z),cell);
        if(old!=null && !old.site.equals(site)) report("DOUBLE_PROSPECTIVE_OWNER",site,x,z,"other="+old.site);
        else if(old==null) proposals++;
    }
    public void assignment(int x,int z,String before,String after,String writer){assignment(x,z,before,after,writer,false);}
    public void assignment(int x,int z,String before,String after,String writer,boolean replaced) {
        Cell cell=cells.get(key(x,z));if(cell==null)return;
        parcelWrites++;cell.writes++;
        String old=before==null?"null":before;
        String next=after==null?"null":after;
        if(cell.writes==1) {
            cell.firstWriter=writer;
            report("FIRST_PARCEL_WRITER",cell.site,x,z,"proposed="+cell.proposed+" before="+old+" after="+next+" writer="+writer);
        }
        boolean authorized=next.equals("MorphomosParcel")&&cell.morphomosRewriteAuthorized;
        if(!old.equals("null")&&(replaced||!old.equals(next))) {
            report("PARCEL_OVERWRITE",cell.site,x,z,"owner="+cell.proposed+" committed="+cell.committed+" "+old+" -> "+next+" initialWriter="+cell.firstWriter+" previousWriter="+cell.lastWriter+" overwriteWriter="+writer);
            replacements++;
            if(old.equals(next))report("SAME_CLASS_PARCEL_REPLACED",cell.site,x,z,"owner="+cell.committed+" class="+old+" writer="+writer+" -- changed parcel instance, not just a repaint");
            if(isInfrastructure(old)&&isNatural(next)) {
                if(authorized)report("MORPHOMOS_EXECUTED_AUTHORIZED_REPAINT",cell.site,x,z,"rule="+cell.morphomosRewriteRule+" "+old+" -> "+next+" writer="+writer);
                else report(cell.committed==null?"EARLY_STRUCTURE_TO_TERRAIN":"POST_COMMIT_STRUCTURE_TO_TERRAIN",cell.site,x,z,"owner="+cell.proposed+" final="+cell.committed+" "+old+" -> "+next+" writer="+writer);
            }
            if((cell.committed==BoundedRiverOwnershipPlan.Owner.CITY && !next.equals("ClipboardParcel")) ||
               (cell.committed==BoundedRiverOwnershipPlan.Owner.CITY_ROAD && !isRoad(next)))
                report("COMMITTED_PARCEL_REPLACED",cell.site,x,z,"final="+cell.committed+" "+old+" -> "+next+" writer="+writer);
            else if(cell.proposed==BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE)
                report("DRY_CANDIDATE_REASSIGNED",cell.site,x,z,old+" -> "+next+" writer="+writer);
        }
        if(old.equals("null")&&(cell.proposed==BoundedRiverOwnershipPlan.Owner.WATER||cell.proposed==BoundedRiverOwnershipPlan.Owner.PARK_BRIDGE)&&
           (next.equals("RiverParcel")||next.equals("BridgeParcel")))
            report("CREEK_WATER_PARCEL_ASSIGNED",cell.site,x,z,"owner="+cell.proposed+" parcel="+next+" writer="+writer);
        if(old.equals("null")&&cell.proposed==BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE&&isInfrastructure(next))
            report("DRY_CANDIDATE_INITIAL_INFRASTRUCTURE",cell.site,x,z,"parcel="+next+" writer="+writer);
        if(next.equals("MorphomosParcel")){cell.morphomosRewriteAuthorized=false;cell.morphomosRewriteRule="none";}
        cell.last=next;cell.lastWriter=writer;
    }
    public void morphomosDecision(int x,int z,String rule,String prior,boolean allowed,String context){
        Cell cell=cells.get(key(x,z));if(cell==null)return;
        boolean protectedCreek=cell.proposed==BoundedRiverOwnershipPlan.Owner.WATER||
            cell.proposed==BoundedRiverOwnershipPlan.Owner.RECEIVING_WATER||cell.proposed==BoundedRiverOwnershipPlan.Owner.PARK||
            cell.proposed==BoundedRiverOwnershipPlan.Owner.PARK_ROAD||cell.proposed==BoundedRiverOwnershipPlan.Owner.PARK_BRIDGE||
            cell.committed==BoundedRiverOwnershipPlan.Owner.CITY||cell.committed==BoundedRiverOwnershipPlan.Owner.CITY_ROAD;
        cell.morphomosRewriteAuthorized=!protectedCreek&&allowed;
        cell.morphomosRewriteRule=rule;
        if(protectedCreek||!allowed)report("MORPHOMOS_RULE_VIOLATION",cell.site,x,z,"rule="+rule+" planAllowed="+allowed+" protectedCreek="+protectedCreek+" prior="+prior+" "+context);
        else report("MORPHOMOS_AUTHORIZED_REPAINT",cell.site,x,z,"rule="+rule+" prior="+prior+" "+context);
    }
    public void morphomosStreetOutcome(int x,int z,boolean dissolved,boolean hasRegion,boolean enabled,String actual,String regionContext){
        Cell c=cells.get(key(x,z));if(c==null)return;
        String detail="planDissolved="+dissolved+" regionPresent="+hasRegion+" actual="+actual+" morphomosEnabled="+enabled+" regionContext="+regionContext+
           " finalOwner="+c.committed+" firstWriter="+c.firstWriter+" lastWriter="+c.lastWriter;
        if(dissolved){
            if(!enabled)count("MORPHOMOS_DISABLED_NO_REPAINT_EXPECTED");
            else if(!hasRegion)report("MORPHOMOS_DISSOLVED_STREET_NO_REGION",c.site,x,z,detail);
            else if(!"MorphomosParcel".equals(actual))report("MORPHOMOS_EXPECTED_REPAINT_MISSING",c.site,x,z,detail);
            else if(c.committed==BoundedRiverOwnershipPlan.Owner.CITY_ROAD)report("MORPHOMOS_DISSOLVED_STREET_PROTECTED_AS_ROAD",c.site,x,z,detail);
            else count("MORPHOMOS_VALID_STREET_REPAINT");
        }else if("MorphomosParcel".equals(actual))report("MORPHOMOS_KEPT_STREET_REPAINTED",c.site,x,z,detail);
        else count("MORPHOMOS_VALID_STREET_RETAINED");
    }
    public void morphomosBlockedByRoadGuard(int x,int z,String current,String writer){
        Cell c=cells.get(key(x,z));if(c!=null) report("MORPHOMOS_ELIGIBLE_BUT_ROAD_GUARD_BLOCKED",c.site,x,z,"current="+current+" committed="+c.committed+" writer="+writer);
    }
    public void refused(int x,int z,String existing,String attempted,String writer){
        Cell c=cells.get(key(x,z));if(c==null)return;
        refused++;
        report("PROTECTED_LATE_WRITER_BLOCKED",c.site,x,z,"committed="+c.committed+" existing="+existing+" attempted="+attempted+" writer="+writer);
    }
    public void commit(int x,int z,BoundedRiverOwnershipPlan.Owner owner,String reason,String actual){
        Cell c=cells.get(key(x,z));if(c==null)return;
        c.committed=owner;commits++;
        if(c.proposed!=BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE)return;
        count("DRY_FINAL_"+owner);
        if(owner==BoundedRiverOwnershipPlan.Owner.VEGETATED_BANK)count("RECLAIM_NATURAL_REASON_"+reason);
        else if(owner==BoundedRiverOwnershipPlan.Owner.CITY&&!"ClipboardParcel".equals(actual))
            report("RECLAIM_CITY_NO_SCHEMATIC_PARCEL",c.site,x,z,"reason="+reason+" actual="+actual+" firstWriter="+c.firstWriter+" previousWriter="+c.lastWriter);
        else if(owner==BoundedRiverOwnershipPlan.Owner.CITY_ROAD&&!isRoad(actual))
            report("RECLAIM_ROAD_NO_ROAD_PARCEL",c.site,x,z,"reason="+reason+" actual="+actual+" firstWriter="+c.firstWriter+" previousWriter="+c.lastWriter);
    }
    public boolean tracks(int x,int z){return cells.containsKey(key(x,z));}
    public void population(int x,int z,String step,String actual,long hash){population(x,z,step,actual,hash,null,54);}
    public void population(int x,int z,String step,String actual,long hash,int[] sample,int sampleBaseY){
        long k=key(x,z);Cell c=cells.get(k);if(c==null)return;
        sampledPopulates++;
        if(step.equals("POST_AFTER")){
            postFingerprints.put(k,hash);
            if(sample!=null){postBlockSamples.put(k,Arrays.copyOf(sample,sample.length));postBlockSampleBaseY.put(k,sampleBaseY);}
            postCompleted++;
            if(c.committed==BoundedRiverOwnershipPlan.Owner.CITY&&"ClipboardParcel".equals(actual))populatedCity++;
            if(c.committed==BoundedRiverOwnershipPlan.Owner.CITY_ROAD&&isRoad(actual))populatedRoad++;
            if(postCompleted%64==0)log.accept("[R14-CREEK-PAINT] POPULATION_PROGRESS tile=("+tileX+","+tileZ+") completed="+postCompleted+" developedCity="+populatedCity+" developedRoad="+populatedRoad+" repaintAlarms="+repaintAlarms);
        }
        if(c.committed==BoundedRiverOwnershipPlan.Owner.CITY&&!"ClipboardParcel".equals(actual))
            report("CITY_POPULATION_WRONG_PARCEL",c.site,x,z,"step="+step+" parcel="+actual);
        else if(c.committed==BoundedRiverOwnershipPlan.Owner.CITY_ROAD&&!isRoad(actual))
            report("ROAD_POPULATION_WRONG_PARCEL",c.site,x,z,"step="+step+" parcel="+actual);
        String reason="PAINT_"+step+"_"+(c.committed==null?c.proposed:c.committed);
        int count=emitted.getOrDefault(reason,0);emitted.put(reason,count+1);
        if(count<4)log.accept("[R14-CREEK-PAINT] "+reason+" site="+c.site+" chunk=("+x+","+z+") parcel="+actual+" lastSetter="+c.lastWriter);
    }
    public boolean hasPostSample(int x,int z){return postFingerprints.containsKey(key(x,z));}
    public void recheckSurface(int x,int z,long hash,int afterX,int afterZ){recheckSurface(x,z,hash,afterX,afterZ,null,54,null);}
    public void recheckSurface(int x,int z,long hash,int afterX,int afterZ,int[] sample,int baseY,IntFunction<String> describe){
        long k=key(x,z);Long previous=postFingerprints.get(k);
        if(previous==null||previous==hash)return;
        Cell c=cells.get(k);repaintAlarms++;
        if(c!=null)report("POST_POPULATION_SURFACE_OVERWRITE",c.site,x,z,"owner="+c.committed+" parcel="+c.last+" previousHash="+previous+" currentHash="+hash+" noticedAfterChunk=("+afterX+","+afterZ+") writerNotDirectlyObserved=true");
        int[] old=postBlockSamples.get(k);Integer oldBase=postBlockSampleBaseY.get(k);
        if(old!=null&&sample!=null&&old.length==sample.length&&oldBase!=null&&oldBase==baseY){
            int changed=0,examples=0;StringBuilder locations=new StringBuilder();int[] axis={1,4,7,10,12,15};
            for(int i=0;i<old.length;i++) if(old[i]!=sample[i]) {
                changed++;
                if(examples++<8) {
                    int xx=axis[(i/(axis.length*9))%axis.length];
                    int zz=axis[(i/9)%axis.length];
                    int y=baseY+2*(i%9);
                    if(locations.length()>0)locations.append(';');
                    locations.append('(').append(x*16+xx).append(',').append(y).append(',').append(z*16+zz).append("):"+
                        (describe==null?old[i]:describe.apply(old[i]))+"->"+(describe==null?sample[i]:describe.apply(sample[i])));
                }
            }
            if(c!=null&&changed>0)report("REPAINT_MATERIAL_DIFF",c.site,x,z,"sampledChanged="+changed+"/"+old.length+" examples="+locations+" noticedAfterChunk=("+afterX+","+afterZ+")");
        }
        if(sample!=null){postBlockSamples.put(k,Arrays.copyOf(sample,sample.length));postBlockSampleBaseY.put(k,baseY);}
        postFingerprints.put(k,hash);
    }
    public void note(String reason,String site,int x,int z,String detail){report(reason,site,x,z,detail);}
    public void finish(){
        int dry=0,city=0,roads=0,natural=0;
        for(Cell c:cells.values()) if(c.proposed==BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE) {
            dry++;
            if(c.committed==BoundedRiverOwnershipPlan.Owner.CITY)city++;
            else if(c.committed==BoundedRiverOwnershipPlan.Owner.CITY_ROAD)roads++;
            else if(c.committed==BoundedRiverOwnershipPlan.Owner.VEGETATED_BANK)natural++;
        }
        if(dry==0)report("RECLAIM_NO_DRY_CANDIDATES","tile",tileX,tileZ,"no prospective dry cells in tile");
        else if(city==0&&roads==0)report("RECLAIM_ALL_CANDIDATES_NATURAL","tile",tileX,tileZ,"dry="+dry+" natural="+natural);
        if(dry>city+roads+natural)report("RECLAIM_HANDOFF_NOT_REACHED","tile",tileX,tileZ,"unresolved="+(dry-city-roads-natural));
        log.accept("[R14-CREEK-FAILURE] PLAN_SUMMARY tile=("+tileX+","+tileZ+") proposed="+proposals+" dry="+dry+" city="+city+" roads="+roads+" natural="+natural+" writes="+parcelWrites+" replacements="+replacements+" blocked="+refused+" commits="+commits+" populationSamples="+sampledPopulates+" reasons="+counts);
        log.accept("[R14-CREEK-FAILURE] PLAN_GATE tile=("+tileX+","+tileZ+") status="+(city==0?"NO_CITY_RECLAIM_YET":"CITY_DESIGNATED_NOT_PROVEN_POPULATED")+" -- actual population and repaint checks continue after plan closure");
    }
}
