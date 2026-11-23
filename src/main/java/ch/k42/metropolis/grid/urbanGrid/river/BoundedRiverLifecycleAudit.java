package ch.k42.metropolis.grid.urbanGrid.river;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Diagnostic lifecycle ledger for bounded river ownership and parcel painting.
 * Recovered from R30ea's binary behavior as editable source. This is not a
 * persistence mechanism and must not be used as an execution acknowledgement.
 */
public final class BoundedRiverLifecycleAudit {
    private static final int MAX_DETAIL = 400;
    public enum Stage { PLAN, ASSIGN, POPULATE_BEFORE, POPULATE_AFTER, POST_BEFORE, POST_AFTER }
    static final class Cell {
        final String site;
        final BoundedRiverOwnershipPlan.Owner owner;
        String lastAssigned = "null";
        Stage stage = Stage.PLAN;
        long nanos = System.nanoTime();
        long postFingerprint = Long.MIN_VALUE;
        Cell(String site, BoundedRiverOwnershipPlan.Owner owner) {
            this.site = site;
            this.owner = owner;
        }
    }

    private final int tileX;
    private final int tileZ;
    private final Consumer<String> output;
    private final Map<Long,Cell> cells = new HashMap<>();
    private final Map<String,int[]> sites = new LinkedHashMap<>();
    private final List<String> issues = new ArrayList<>();
    private int details;
    private int suppressed;
    private int assignments;
    private int completed;
    private int sampledBanks;
    private int sampledBankSoil;
    private int sampledBankVegetation;
    private int sampledWater;
    private int sampledWaterBlocks;
    private int sampledCity;
    private boolean planClosed;

    public BoundedRiverLifecycleAudit(int tileX,int tileZ,Consumer<String> output) {
        if(output==null) throw new IllegalArgumentException("output required");
        this.tileX=tileX;
        this.tileZ=tileZ;
        this.output=output;
    }
    public static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}

    public void register(String site, BoundedRiverOwnershipPlan plan,int dryCandidates) {
        if(planClosed) throw new IllegalStateException("Cannot register after plan close");
        if(site==null||plan==null) throw new IllegalArgumentException("site/plan required");
        int[] stats={dryCandidates,plan.count(BoundedRiverOwnershipPlan.Owner.CITY),
                plan.count(BoundedRiverOwnershipPlan.Owner.VEGETATED_BANK),0};
        sites.put(site,stats);
        for(int x=tileX;x<tileX+64;x++)for(int z=tileZ;z<tileZ+64;z++){
            BoundedRiverOwnershipPlan.Owner owner=plan.at(x,z);
            if(owner==BoundedRiverOwnershipPlan.Owner.OUTSIDE) continue;
            Cell previous=cells.putIfAbsent(key(x,z),new Cell(site,owner));
            if(previous!=null && (!previous.site.equals(site)||previous.owner!=owner)) {
                issue("DOUBLE_OWNER",site,x,z,"previous="+previous.site+":"+previous.owner+" incoming="+owner);
            }
            if(previous==null) stats[3]++;
        }
        if(!plan.isResolved() || plan.count(BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE)>0)
            issue("UNRESOLVED_OWNERSHIP",site,tileX,tileZ,"dry candidates remain");
        if(dryCandidates>0 && stats[1]==0)
            issue("RECLAIM_ZERO_ACCEPTED",site,tileX,tileZ,"dryCandidates="+dryCandidates+" all handed to banks");
        detail("SITE_ACCEPTED site="+site+" tile="+tileX+","+tileZ+" dryCandidates="+dryCandidates+
               " city="+stats[1]+" naturalBank="+stats[2]+" trackedCells="+stats[3]);
    }
    public void assignment(int x,int z,String current) {
        Cell c=cells.get(key(x,z));
        if(c==null)return;
        assignments++;
        String old=c.lastAssigned;
        if(planClosed && !"null".equals(old) && !old.equals(current)) {
            if(c.owner==BoundedRiverOwnershipPlan.Owner.CITY && isNatural(current))
                issue("RECLAIM_OVERWRITTEN",c.site,x,z,old+" -> "+current);
            else detail("PARCEL_REASSIGNED site="+c.site+" at="+x+","+z+" "+old+" -> "+current);
        }
        c.lastAssigned=current;
        if(c.stage.ordinal()<=Stage.ASSIGN.ordinal()) c.stage=Stage.ASSIGN;
        c.nanos=System.nanoTime();
    }
    public void finishPlan(BiFunction<Integer,Integer,String> parcelAt) {
        if(parcelAt==null)throw new IllegalArgumentException("final parcel lookup required");
        planClosed=true;
        int bad=0,missing=0;
        for(Map.Entry<Long,Cell> entry:cells.entrySet()) {
            int x=(int)(entry.getKey()>>32),z=(int)(long)entry.getKey();
            Cell cell=entry.getValue();
            String value=parcelAt.apply(x,z);
            if(value==null)value="null";
            cell.lastAssigned=value;
            if("null".equals(value)) {
                missing++;bad++;
                issue("MISSING_PARCEL",cell.site,x,z,"owner="+cell.owner);
            } else if(!compatible(cell.owner,value)) {
                bad++;
                issue("PAINT_OWNER_MISMATCH",cell.site,x,z,"expected="+cell.owner+" actual="+value);
            }
        }
        detail("PLAN_CLOSED tile="+tileX+","+tileZ+" cells="+cells.size()+" assignments="+assignments+
               " mismatches="+bad+" nulls="+missing+" issues="+issues.size());
    }
    public void stage(int x,int z,Stage next,long surfaceHash) {
        Cell c=cells.get(key(x,z));
        if(c==null)return;
        if(!planClosed)issue("POPULATE_BEFORE_PLAN",c.site,x,z,"stage="+next);
        if(next.ordinal()<Stage.POPULATE_BEFORE.ordinal())throw new IllegalArgumentException("not a populate stage");
        if(next.ordinal()<c.stage.ordinal()) issue("STAGE_BACKWARDS",c.site,x,z,"from="+c.stage+" to="+next);
        else if(next.ordinal()>c.stage.ordinal()+2) issue("STAGE_SKIPPED",c.site,x,z,"from="+c.stage+" to="+next);
        long now=System.nanoTime();
        detail("PHASE site="+c.site+" chunk="+x+","+z+" owner="+c.owner+
               " stage="+next+" parcel="+c.lastAssigned+" deltaUs="+(now-c.nanos)/1000+" surfaceHash="+surfaceHash);
        c.nanos=now;
        if(next.ordinal()>c.stage.ordinal())c.stage=next;
        if(next==Stage.POST_AFTER) {
            c.postFingerprint=surfaceHash;
            completed++;
        }
    }
    public boolean tracks(int x,int z){return cells.containsKey(key(x,z));}
    public boolean hasFinalSample(int x,int z){Cell c=cells.get(key(x,z));return c!=null && c.postFingerprint!=Long.MIN_VALUE;}
    public void verifySurvival(int x,int z,long surfaceHash,int observedX,int observedZ) {
        Cell c=cells.get(key(x,z));
        if(c==null||c.postFingerprint==Long.MIN_VALUE)return;
        if(c.postFingerprint!=surfaceHash) {
            issue("AFTER_POST_MUTATION",c.site,x,z,"owner="+c.owner+" oldHash="+c.postFingerprint+
               " newHash="+surfaceHash+" detectedAfter="+observedX+","+observedZ);
            c.postFingerprint=surfaceHash;
        }
    }
    public void terrainEvidence(int x,int z,int water,int soil,int vegetation,int structure) {
        Cell c=cells.get(key(x,z));
        if(c==null)return;
        if(c.owner==BoundedRiverOwnershipPlan.Owner.VEGETATED_BANK){
            sampledBanks++;
            sampledBankSoil+=soil;
            sampledBankVegetation+=vegetation;
            if(sampledBanks==24 && sampledBankVegetation==0)
                issue("BANK_VEGETATION_NOT_OBSERVED",c.site,x,z,
                    "24 post-populated bank chunks sampled, no plant/tree blocks in samples; inspect full chunks");
        }
        if(c.owner==BoundedRiverOwnershipPlan.Owner.WATER){sampledWater++;sampledWaterBlocks+=water;}
        if(c.owner==BoundedRiverOwnershipPlan.Owner.CITY)sampledCity++;
        detail("MATERIAL_SAMPLE site="+c.site+" chunk="+x+","+z+" owner="+c.owner+
               " water="+water+" soil="+soil+" vegetation="+vegetation+" structure="+structure);
        if(completed%64==0)output.accept("[R14-CREEK-LIFECYCLE] PAINT_PROGRESS tile="+tileX+","+tileZ+
                " postCompleted="+completed+" bankChunks="+sampledBanks+" bankSoil="+sampledBankSoil+
                " bankPlants="+sampledBankVegetation+" waterChunks="+sampledWater+
                " sampledWater="+sampledWaterBlocks+" cityChunks="+sampledCity);
    }
    public void summary() {
        output.accept("[R14-CREEK-LIFECYCLE] PLAN_SUMMARY tile="+tileX+","+tileZ+
            " sites="+sites.size()+" cells="+cells.size()+" assignments="+assignments+
            " postCompleted="+completed+" issues="+issues.size()+" suppressedDetails="+suppressed);
    }
    public int issueCount(){return issues.size();}
    public List<String> issues(){return List.copyOf(issues);}
    public int trackedCells(){return cells.size();}
    private void issue(String reason,String site,int x,int z,String message) {
        String line="[R14-CREEK-LIFECYCLE] ISSUE="+reason+" site="+site+" chunk="+x+","+z+" "+message;
        issues.add(line);
        if(issues.size()<=100)output.accept(line);
        else if(issues.size()==101)output.accept("[R14-CREEK-LIFECYCLE] Further issues suppressed in tile "+tileX+","+tileZ);
    }
    private void detail(String line){
        if(details++<MAX_DETAIL)output.accept("[R14-CREEK-LIFECYCLE] "+line);
        else suppressed++;
    }
    private static boolean isNatural(String s) {
        return s.equals("RiverParcel")||s.equals("OceanParcel")||s.equals("MorphomosParcel")
                ||s.equals("TerrainFallbackParcel");
    }
    public static boolean compatible(BoundedRiverOwnershipPlan.Owner owner,String parcel) {
        if(owner==BoundedRiverOwnershipPlan.Owner.OUTSIDE)return true;
        if(parcel==null||"null".equals(parcel))return false;
        return switch(owner) {
            case WATER -> parcel.equals("RiverParcel")||parcel.equals("BridgeParcel");
            case RECEIVING_WATER -> parcel.equals("RiverParcel")||parcel.equals("OceanParcel")||parcel.equals("BridgeParcel");
            case VEGETATED_BANK -> parcel.equals("RiverParcel")||parcel.equals("MorphomosParcel");
            case PARK_BRIDGE -> parcel.equals("BridgeParcel");
            case PARK_ROAD,CITY_ROAD -> parcel.equals("RoadParcel")||parcel.equals("HighwayParcel")||parcel.equals("BridgeParcel");
            case PARK,CITY -> parcel.equals("ClipboardParcel");
            case DRY_CANDIDATE -> false;
            default -> true;
        };
    }
}
