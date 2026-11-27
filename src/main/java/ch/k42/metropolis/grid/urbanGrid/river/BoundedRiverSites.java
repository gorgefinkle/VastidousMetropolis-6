package ch.k42.metropolis.grid.urbanGrid.river;

import ch.k42.metropolis.grid.urbanGrid.wild.HydrologyConvergenceKey;

import ch.k42.metropolis.grid.urbanGrid.clipboard.Clipboard;
import ch.k42.metropolis.grid.urbanGrid.clipboard.ClipboardProvider;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.grid.urbanGrid.layout.PlanRegions;
import ch.k42.metropolis.grid.urbanGrid.layout.WorldLayout;
import ch.k42.metropolis.grid.urbanGrid.parcel.ClipboardParcel;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.*;

/**
 * Seed-stable, bounded-cache site selection for a creek originating in a park.
 * Recovered against the R30ea behavior reference; NOT a new generation algorithm.
 */
public final class BoundedRiverSites {
    private static final int TILE = 64;
    private static final int CACHE_LIMIT = 4000;
    private final long seed;
    private final RiverProvider westEast, northSouth;
    private final ClipboardProvider clipboards;
    private final Rules rules;
    private final Map<Long,Site> candidates = lru(), accepted = lru();
    private final Map<Long,List<Site>> tiles = lru();
    /** City key -> winning host-tile key. Selection is seed-stable, not query-order dependent. */
    private final Map<Long,Long> cityWinners = lru();
    private List<Variant> variants;

    public interface Rules {
        boolean parkAndRingAvailable(int x,int z,int w,int h,Clipboard clipboard,Direction direction);
        default int[] alignedParkBase(int x,int z,int w,int h) { return new int[]{x,z}; }
        boolean northSouthHighwayAt(int x,int z);
        boolean eastWestHighwayAt(int x,int z);
        default boolean bridgeableHighwayCrossingAt(int x,int z,BoundedRiverSegment river) { return false; }
        boolean existingWaterAt(int x,int z);
        boolean unrelatedWaterAt(int x,int z);
        default boolean unrelatedWaterAt(int x,int z,RiverProvider receiver) { return unrelatedWaterAt(x,z); }
        /** Highway city containing the origin park centre, or null outside all present city boxes. */
        default CityBounds cityAt(int chunkX,int chunkZ) { return null; }
    }

    /** Immutable city identity and inclusive box used to enumerate every potential origin tile. */
    public static final class CityBounds {
        public final long key;
        public final int minX,minZ,maxX,maxZ;
        public CityBounds(long key,int minX,int minZ,int maxX,int maxZ) {
            if(minX>maxX||minZ>maxZ)throw new IllegalArgumentException("Invalid city bounds");
            this.key=key;this.minX=minX;this.minZ=minZ;this.maxX=maxX;this.maxZ=maxZ;
        }
    }

    private static final class Variant {
        final Clipboard clipboard;
        final Direction rotation;
        Variant(Clipboard clipboard,Direction rotation) { this.clipboard=clipboard;this.rotation=rotation; }
    }

    public static final class Site {
        public final long seed;
        private final long carrierSeed;
        public final int hostTileX,hostTileZ,lotBaseX,lotBaseZ,lotSizeX,lotSizeZ;
        public final Clipboard clipboard;
        public final Direction direction;
        public final ClipboardParcel.CreekOrigin origin;
        public final BoundedRiverSegment river;
        public final BoundedRiverOwnershipPlan ownership;
        public final int dryCandidatesBeforeResolution;
        public final BoundedRiverReservation.Exit exit;
        public final int minX,minZ,maxX,maxZ;
        /** Null only when the origin park is outside all generated highway city boxes. */
        public final CityBounds city;
        private final String id;

        private Site(long seed,long carrierSeed,int tileX,int tileZ,int x,int z,int w,int h,
                     Clipboard clipboard,Direction direction,ClipboardParcel.CreekOrigin origin,
                     BoundedRiverSegment river,BoundedRiverOwnershipPlan ownership,int dry,
                     BoundedRiverReservation.Exit exit,CityBounds city) {
            this.seed=seed;this.carrierSeed=carrierSeed;
            this.hostTileX=tileX;this.hostTileZ=tileZ;
            this.lotBaseX=x;this.lotBaseZ=z;this.lotSizeX=w;this.lotSizeZ=h;
            this.clipboard=clipboard;this.direction=direction;this.origin=origin;
            this.river=river;this.ownership=ownership;
            this.dryCandidatesBeforeResolution=dry;this.exit=exit;this.city=city;
            int low=river.getAlongLoChunk(), high=river.getAlongHiChunk();
            int half=(int)Math.ceil(river.getEnvelopeHalfWidth())+2;
            int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
            for(int along=low;along<=high;along++) {
                double crossing=river.spineAcross(along);
                if(!Double.isFinite(crossing))continue;
                min=Math.min(min,(int)Math.floor(crossing)-half);
                max=Math.max(max,(int)Math.ceil(crossing)+half);
            }
            if(river.isZBound()){minX=min;maxX=max;minZ=low;maxZ=high;}
            else {minX=low;maxX=high;minZ=min;maxZ=max;}
            id=seed+":"+tileX+":"+tileZ+":"+x+":"+z;
        }
        public String id(){return id;}
        public BoundedRiverSegment newCarrier(){
            boolean zBound=river.isZBound();
            double ax=zBound?river.originAcrossBlock():river.originAlongBlock();
            double az=zBound?river.originAlongBlock():river.originAcrossBlock();
            double bx=zBound?river.endpointAcrossBlock():river.endpointAlongBlock();
            double bz=zBound?river.endpointAlongBlock():river.endpointAcrossBlock();
            return new BoundedRiverSegment(carrierSeed,zBound,ax,az,bx,bz,river.widthBucketBlocks(),river.bankMarginBlocks());
        }
        public boolean touchesTile(int tileX,int tileZ){
            int x=tileX*64,z=tileZ*64;
            boolean riverTouch=maxX>=x && minX<=x+63 && maxZ>=z && minZ<=z+63;
            boolean parkTouch=lotBaseX+lotSizeX>=x && lotBaseX-1<=x+63
                    && lotBaseZ+lotSizeZ>=z && lotBaseZ-1<=z+63;
            return riverTouch||parkTouch;
        }
        public boolean ownsWetCell(int x,int z){
            BoundedRiverOwnershipPlan.Owner owner=ownership.at(x,z);
            return owner==BoundedRiverOwnershipPlan.Owner.WATER
                    ||owner==BoundedRiverOwnershipPlan.Owner.PARK_BRIDGE;
        }
        public boolean ownsRiverCell(int x,int z){
            return ownsWetCell(x,z) || ownership.at(x,z)==BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE;
        }
        public BoundedRiverOwnershipPlan resolvedAgainst(WorldLayout layout){
            if(layout==null)throw new IllegalArgumentException("World layout required for reclamation");
            PlanRegions regions=new PlanRegions(layout);
            return ownership.resolvePlanned((x,z)->{
                int kind=layout.kindAt(x,z);
                return BoundedRiverDryHandoff.plannedUse(kind,layout.contextAt(x,z),
                        kind==2 && regions.dissolvedAt(x,z));
            });
        }
        @Override public boolean equals(Object obj){return obj instanceof Site site&&id.equals(site.id);}
        @Override public int hashCode(){return id.hashCode();}
    }

    public BoundedRiverSites(long seed,RiverProvider westEast,RiverProvider northSouth,
                             ClipboardProvider clipboards,Rules rules){
        if(westEast==null||rules==null)
            throw new IllegalArgumentException("Receiver and planning rules required");
        this.seed=seed;this.westEast=westEast;this.northSouth=northSouth;
        this.clipboards=clipboards;this.rules=rules;
    }
    private static long key(int x,int z){return ((long)x<<32)^((long)z&0xffffffffL);}
    private static <V> Map<Long,V> lru(){return new LinkedHashMap<Long,V>(256,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<Long,V> entry){return size()>CACHE_LIMIT;}
    };}
    private static long mix(long n){n=(n^(n>>>30))*-4658895280553007687L;n=(n^(n>>>27))*-7723592293110705685L;return n^(n>>>31);}
    private long siteRank(int tx,int tz){return mix(seed^((long)tx*-7046029254386353131L)^((long)tz*-4417276706812531889L));}
    private List<Variant> variants(){
        if(variants!=null)return variants;
        List<Variant> found=new ArrayList<>();
        if(clipboards!=null) for(Direction d:Direction.getDirections())
            for(Clipboard cp:clipboards.getAll(ContextType.LANDMARK,SchematicType.BUILD,d))
                if(cp!=null&&ClipboardParcel.hasCreekOrigin(cp)) found.add(new Variant(cp,d));
        found.sort(Comparator.comparing((Variant v)->String.valueOf(v.clipboard.getGroupId()))
                .thenComparingInt(v->v.rotation.ordinal())
                .thenComparing(v->v.clipboard.isMirrored()));
        return variants=Collections.unmodifiableList(found);
    }
    private Site candidate(int x,int z){
        long k=key(x,z);
        if(candidates.containsKey(k))return candidates.get(k);
        Site found=deriveCandidate(x,z);candidates.put(k,found);return found;
    }
    private Site deriveCandidate(int tx,int tz){
        if(!RiverConfig.isRiverEnabled()||RiverConfig.getCreekSiteChancePercent()<=0)return null;
        List<Variant> pool=variants();if(pool.isEmpty())return null;
        int left=tx*64,top=tz*64;
        Random rng=new Random(siteRank(tx,tz)^18667844570074444L);
        int attempts=Math.max(1,RiverConfig.getCreekSiteAttempts());
        for(int attempt=0;attempt<attempts;attempt++){
            Variant variant=pool.get(rng.nextInt(pool.size()));
            Clipboard clipboard=variant.clipboard;
            int w=clipboard.getSize().X>>4,h=clipboard.getSize().Z>>4;
            if(w<1||h<1||w>58||h>58)continue;
            int bx=left+1+rng.nextInt(63-w),bz=top+1+rng.nextInt(63-h);
            int[] aligned=rules.alignedParkBase(bx,bz,w,h);
            if(aligned==null||aligned.length!=2)continue;
            bx=aligned[0];bz=aligned[1];
            if(bx<left+3||bz<top+3||bx+w>left+60||bz+h>top+60)continue;
            List<ClipboardParcel.CreekOrigin> origins=ClipboardParcel.creekOriginsFor(clipboard,variant.rotation,bx,bz);
            if(origins.isEmpty())continue;
            ClipboardParcel.CreekOrigin origin=origins.get(0);
            BoundedRiverReservation.Exit exit=exitOf(origin.outward);
            if(exit==null||origin.widthBlocks<=0||origin.widthBlocks>64)continue;
            boolean zBound=exit==BoundedRiverReservation.Exit.NORTH||exit==BoundedRiverReservation.Exit.SOUTH;
            RiverProvider receiver=zBound?westEast:northSouth;
            if(receiver==null||!receiver.isEnabled())continue;
            double across=zBound?origin.acrossBlock:origin.alongBlock;
            double along=zBound?origin.alongBlock:origin.acrossBlock;
            double endpointX=zBound?across:receiver.spineXBlock(along);
            double endpointZ=zBound?receiver.spineZBlock(across):along;
            if(!Double.isFinite(endpointX)||!Double.isFinite(endpointZ))continue;
            double start=zBound?along:across,end=zBound?endpointZ:endpointX;
            int dir=(exit==BoundedRiverReservation.Exit.NORTH||exit==BoundedRiverReservation.Exit.WEST)?-1:1;
            if((end-start)*dir<16.0||Math.abs(end-start)>RiverConfig.getBoundedMaxLengthChunks()*16.0)continue;
            if(!rules.parkAndRingAvailable(bx,bz,w,h,clipboard,variant.rotation))continue;
            long riverSeed=mix(seed^((long)bx<<32)^bz);
            BoundedRiverSegment river;
            try{river=new BoundedRiverSegment(riverSeed,zBound,across,along,endpointX,endpointZ,
                    origin.widthBlocks,RiverConfig.getRiverBankMargin()*16.0);}
            catch(IllegalArgumentException ex){continue;}
            BoundedRiverReservation.ChunkOwnership chunkOwnership=new BoundedRiverReservation.ChunkOwnership(){
                public boolean northSouthRoadAt(int x,int z){return rules.northSouthHighwayAt(x,z);}
                public boolean eastWestRoadAt(int x,int z){return rules.eastWestHighwayAt(x,z);}
                public boolean protectedWaterAt(int x,int z){return rules.unrelatedWaterAt(x,z,receiver);}
                public boolean perpendicularBridgeableAt(int x,int z,boolean zb){return rules.bridgeableHighwayCrossingAt(x,z,river);}
            };
            BoundedRiverReservation reservation=new BoundedRiverReservation(river,bx,bz,bx+w-1,bz+h-1,exit,receiver,null,chunkOwnership);
            if(reservation.check()!=BoundedRiverReservation.Reject.NONE)continue;
            BoundedRiverOwnershipPlan ownership;
            int dry;
            try{
                ownership=reservation.ownershipProposal(new BoundedRiverOwnershipPlan.BaseWater(){
                    public boolean openWater(int x,int z){return rules.existingWaterAt(x,z);}
                    public boolean intendedReceiverAt(int x,int z){return receiver.isRiverOrBank(x,z);}
                });
                dry=ownership.count(BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE);
            }catch(IllegalArgumentException ex){continue;}
            if(!ownership.audit().isEmpty())continue;
            if(rng.nextInt(100)>=RiverConfig.getCreekSiteChancePercent())return null;
            CityBounds city=rules.cityAt(bx+w/2,bz+h/2);
            return new Site(seed,riverSeed,tx,tz,bx,bz,w,h,clipboard,variant.rotation,origin,river,ownership,dry,exit,city);
        }
        return null;
    }
    private static BoundedRiverReservation.Exit exitOf(Direction d){
        return switch(d){
            case NORTH -> BoundedRiverReservation.Exit.NORTH;
            case SOUTH -> BoundedRiverReservation.Exit.SOUTH;
            case WEST -> BoundedRiverReservation.Exit.WEST;
            case EAST -> BoundedRiverReservation.Exit.EAST;
            default -> null;
        };
    }
    /** Nearest accepted origin park within a bounded tile radius, even if not generated yet.
     * Visit complete rings and stop only when all unvisited rings are farther
     * than the best origin: no loaded-tile assumptions or legacy fallback. */
    public synchronized Site nearestSite(int chunkX,int chunkZ,int maxTileRings) {
        int tx=Math.floorDiv(chunkX,64),tz=Math.floorDiv(chunkZ,64);
        Site best=null;long bestDistance=Long.MAX_VALUE;
        for(int ring=0;ring<=Math.max(0,maxTileRings);ring++){
            for(int dx=-ring;dx<=ring;dx++)for(int dz=-ring;dz<=ring;dz++){
                if(ring>0&&Math.max(Math.abs(dx),Math.abs(dz))!=ring)continue;
                Site site=acceptedSite(tx+dx,tz+dz);
                if(site==null)continue;
                long ox=(long)site.origin.chunkX-chunkX;
                long oz=(long)site.origin.chunkZ-chunkZ;
                long distance=ox*ox+oz*oz;
                if(distance<bestDistance||(distance==bestDistance&&best!=null
                        &&site.id().compareTo(best.id())<0)){best=site;bestDistance=distance;}
            }
            long nearestNext=(long)ring*64+1L;
            if(best!=null&&nearestNext*nearestNext>bestDistance)break;
        }
        return best;
    }

    public synchronized Site acceptedSite(int x,int z){
        long k=key(x,z);
        if(accepted.containsKey(k))return accepted.get(k);
        Site choice=candidate(x,z);
        if(choice==null){accepted.put(k,null);return null;}
        // Creek-origin schematics are fixed landmarks, but unlike the ordinary
        // LandmarkPlanner they previously bypassed the one-per-city decision.
        // Resolve all viable candidate tiles in this city and keep exactly one
        // deterministic winner, regardless of which tile is requested first.
        if(choice.city!=null && !isCityWinner(x,z,choice)) {
            accepted.put(k,null);
            return null;
        }
        // A different city can be only a few chunks away on the far side of
        // its own boundary. Apply a second world-coordinate arbitration so
        // separate city winners still keep a 120-chunk berth from one another.
        if (!isSpacingWinner(x, z, choice)) {
            accepted.put(k, null);
            return null;
        }
        int radius=Math.max(1,Math.min(6,PluginConfig.getLandmarkCloneRadius()));
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            if(dx==0&&dz==0)continue;
            Site other=candidate(x+dx,z+dz);
            if(other==null||Long.compareUnsigned(siteRank(x,z),siteRank(x+dx,z+dz))<0)continue;
            if(rectOverlap(choice.minX,choice.minZ,choice.maxX,choice.maxZ,
                    other.minX,other.minZ,other.maxX,other.maxZ)
                    ||rectOverlap(choice.lotBaseX-1,choice.lotBaseZ-1,
                    choice.lotBaseX+choice.lotSizeX,choice.lotBaseZ+choice.lotSizeZ,
                    other.minX,other.minZ,other.maxX,other.maxZ)
                    ||rectOverlap(other.lotBaseX-1,other.lotBaseZ-1,
                    other.lotBaseX+other.lotSizeX,other.lotBaseZ+other.lotSizeZ,
                    choice.minX,choice.minZ,choice.maxX,choice.maxZ)){
                accepted.put(k,null);return null;
            }
        }
        accepted.put(k,choice);return choice;
    }

    /**
     * Only city winners participate in cross-city spacing. Each candidate
     * checks every 64-chunk host tile that could contain an origin inside the
     * configured radius; origin coordinates, not city boxes or tile centres,
     * determine the actual separation.
     */
    private boolean isSpacingWinner(int tileX, int tileZ, Site site) {
        int spacing = RiverConfig.getCreekOriginSpacingChunks();
        if (spacing <= 0) return true;
        int tileRadius = (spacing + TILE - 1) / TILE;
        long ownRank = siteRank(tileX, tileZ);
        long ownTileKey = CreekCityOriginPolicy.tileKey(tileX, tileZ);
        for (int dx = -tileRadius; dx <= tileRadius; dx++) {
            for (int dz = -tileRadius; dz <= tileRadius; dz++) {
                if (dx == 0 && dz == 0) continue;
                int otherTileX = tileX + dx;
                int otherTileZ = tileZ + dz;
                Site other = candidate(otherTileX, otherTileZ);
                if (other == null) continue;
                if (other.city != null && !isCityWinner(otherTileX, otherTileZ, other)) continue;
                long otherRank = siteRank(otherTileX, otherTileZ);
                long otherTileKey = CreekCityOriginPolicy.tileKey(otherTileX, otherTileZ);
                if (!CreekOriginSpacingPolicy.winsAgainst(
                        site.origin.chunkX, site.origin.chunkZ, ownRank, ownTileKey,
                        other.origin.chunkX, other.origin.chunkZ, otherRank, otherTileKey,
                        spacing)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean isCityWinner(int tileX,int tileZ,Site site) {
        long cityKey=site.city.key;
        Long winner=cityWinners.get(cityKey);
        if(winner==null && !cityWinners.containsKey(cityKey)) {
            List<Long> tileKeys=new ArrayList<>();
            List<Long> ranks=new ArrayList<>();
            List<Long> cityKeys=new ArrayList<>();
            int minTileX=Math.floorDiv(site.city.minX,TILE);
            int maxTileX=Math.floorDiv(site.city.maxX,TILE);
            int minTileZ=Math.floorDiv(site.city.minZ,TILE);
            int maxTileZ=Math.floorDiv(site.city.maxZ,TILE);
            for(int tx=minTileX;tx<=maxTileX;tx++) {
                for(int tz=minTileZ;tz<=maxTileZ;tz++) {
                    Site other=candidate(tx,tz);
                    if(other==null || other.city==null || other.city.key!=cityKey) continue;
                    tileKeys.add(key(tx,tz));
                    ranks.add(siteRank(tx,tz));
                    cityKeys.add(other.city.key);
                }
            }
            long[] tk=new long[tileKeys.size()],rk=new long[ranks.size()],ck=new long[cityKeys.size()];
            for(int i=0;i<tk.length;i++){tk[i]=tileKeys.get(i);rk[i]=ranks.get(i);ck[i]=cityKeys.get(i);}
            long selected=CreekCityOriginPolicy.winnerTileKey(cityKey,ck,rk,tk);
            cityWinners.put(cityKey,selected);
            winner=selected;
        }
        return winner!=null && winner.longValue()==key(tileX,tileZ);
    }

    private static boolean rectOverlap(int ax,int az,int bx,int bz,int cx,int cz,int dx,int dz){
        return ax<=dx&&cx<=bx&&az<=dz&&cz<=bz;
    }
    private int reachTiles(){return Math.max(1,(int)Math.ceil((RiverConfig.getBoundedMaxLengthChunks()+12)/64.0));}

    /**
     * Resolve every bounded-creek host-site list that can be read while the
     * layout solves this chunk area. A sitesCovering() miss otherwise performs
     * candidate arbitration from inside WaterField.zoneKind(), making creek
     * discovery depend on which water cell the layout happens to ask first.
     *
     * Each list is built here, in the explicit hydrology preparation phase.
     * Its reachTiles() halo also fills acceptedSite() for the centre tile, so
     * protectedDryParkAt() and parksIn() are reads of the same resolved set.
     */
    public synchronized void prepareForChunkArea(int minChunkX,int minChunkZ,int maxChunkX,int maxChunkZ) {
        if(minChunkX>maxChunkX || minChunkZ>maxChunkZ)
            throw new IllegalArgumentException("Invalid bounded-creek preparation area");
        int minTileX=Math.floorDiv(minChunkX,TILE), maxTileX=Math.floorDiv(maxChunkX,TILE);
        int minTileZ=Math.floorDiv(minChunkZ,TILE), maxTileZ=Math.floorDiv(maxChunkZ,TILE);
        for(int tx=minTileX;tx<=maxTileX;tx++)
            for(int tz=minTileZ;tz<=maxTileZ;tz++)
                sitesCovering(tx,tz);
    }

    public synchronized List<Site> sitesCovering(int tx,int tz){
        long k=key(tx,tz);if(tiles.containsKey(k))return tiles.get(k);
        List<Site> list=new ArrayList<>();int reach=reachTiles();
        for(int dx=-reach;dx<=reach;dx++)for(int dz=-reach;dz<=reach;dz++){
            Site site=acceptedSite(tx+dx,tz+dz);
            if(site!=null&&site.touchesTile(tx,tz))list.add(site);
        }
        list.sort(Comparator.comparing(Site::id));
        List<Site> result=Collections.unmodifiableList(list);tiles.put(k,result);return result;
    }
    public synchronized boolean wetAt(int x,int z){Site s=siteAt(x,z);return s!=null&&s.ownsWetCell(x,z);}
    public synchronized boolean reservedBankAt(int x,int z){
        Site s=siteAt(x,z);
        return s!=null&&(s.ownsWetCell(x,z)||s.ownership.at(x,z)==BoundedRiverOwnershipPlan.Owner.DRY_CANDIDATE);
    }
    public synchronized Site siteAt(int x,int z){
        for(Site s:sitesCovering(Math.floorDiv(x,64),Math.floorDiv(z,64)))
            if(s.ownsRiverCell(x,z))return s;
        return null;
    }
    public synchronized boolean protectedDryParkAt(int x,int z){
        Site s=acceptedSite(Math.floorDiv(x,64),Math.floorDiv(z,64));
        return s!=null&&x>=s.lotBaseX-1&&x<=s.lotBaseX+s.lotSizeX
                &&z>=s.lotBaseZ-1&&z<=s.lotBaseZ+s.lotSizeZ&&!s.ownsRiverCell(x,z);
    }
    /**
     * Exact nearest distance to the accepted creek banks near a world-block
     * point. Sites are enumerated by the tiles their actual bounded river and
     * origin park touch; no generated-chunk state or coarse water sampling is
     * involved. NaN means no accepted creek bank within the requested radius.
     */
    public synchronized double nearestBankDistanceBlocks(double blockX,double blockZ,double maxDistanceBlocks) {
        if(!Double.isFinite(blockX)||!Double.isFinite(blockZ)||!Double.isFinite(maxDistanceBlocks)||maxDistanceBlocks<0)
            return Double.NaN;
        int chunkX=(int)Math.floor(blockX/16.0), chunkZ=(int)Math.floor(blockZ/16.0);
        int tileX=Math.floorDiv(chunkX,TILE), tileZ=Math.floorDiv(chunkZ,TILE);
        int rings=Math.max(1,Math.min(8,(int)Math.ceil(maxDistanceBlocks/(TILE*16.0))+1));
        Set<Site> seen=new HashSet<>();
        double best=Double.POSITIVE_INFINITY;
        for(int dx=-rings;dx<=rings;dx++) for(int dz=-rings;dz<=rings;dz++) {
            for(Site site:sitesCovering(tileX+dx,tileZ+dz)) {
                if(!seen.add(site)) continue;
                double along=site.river.isZBound()?blockZ:blockX;
                double across=site.river.distanceFromCenterBlock(blockX,blockZ);
                double width=site.river.widthAtBlock(along);
                if(!Double.isFinite(across)||!Double.isFinite(width)) continue;
                double bankHalf=width*.5+site.river.getFallbackBankMarginBlocks();
                double distance=Math.max(0.0,Math.abs(across)-bankHalf);
                if(distance<best) best=distance;
            }
        }
        return best<=maxDistanceBlocks?best:Double.NaN;
    }

    /** Carrier lookup reads only the prepared tile snapshot and never arbitrates a new candidate. */
    public synchronized BoundedRiverSegment preparedCarrierAt(int chunkX,int chunkZ) {
        int tx=Math.floorDiv(chunkX,TILE),tz=Math.floorDiv(chunkZ,TILE);
        List<Site> prepared=tiles.get(key(tx,tz));
        if(prepared==null)return null;
        for(Site site:prepared)if(site.ownsWetCell(chunkX,chunkZ))return site.river;
        return null;
    }

    /** Read exact open-channel endpoints from already prepared accepted creek plans. */
    public synchronized List<HydrologyConvergenceKey> convergenceEndpointsIn(int minX,int minZ,int maxX,int maxZ) {
        if(minX>maxX||minZ>maxZ)throw new IllegalArgumentException("Invalid convergence query area");
        Map<String,Site> unique=new TreeMap<>();
        for(int tx=Math.floorDiv(minX,TILE);tx<=Math.floorDiv(maxX,TILE);tx++)
            for(int tz=Math.floorDiv(minZ,TILE);tz<=Math.floorDiv(maxZ,TILE);tz++) {
                List<Site> prepared=tiles.get(key(tx,tz));
                if(prepared!=null)for(Site site:prepared)unique.put(site.id(),site);
            }
        List<HydrologyConvergenceKey> result=new ArrayList<>();
        for(Site site:unique.values()) {
            BoundedRiverSegment river=site.river;
            long network=HydrologyConvergenceKey.stableId("bounded-creek:"+site.id());
            int low=river.getAlongLoChunk(),high=river.getAlongHiChunk();
            for(int along=low;along<=high;along+=2)appendConvergenceEndpoint(result,site,network,along,minX,minZ,maxX,maxZ);
            if((high-low)%2!=0)appendConvergenceEndpoint(result,site,network,high,minX,minZ,maxX,maxZ);
        }
        result.sort(HydrologyConvergenceKey::compareTo);
        return java.util.Collections.unmodifiableList(result);
    }
    private static void appendConvergenceEndpoint(List<HydrologyConvergenceKey> out,Site site,long network,
            int along,int minX,int minZ,int maxX,int maxZ) {
        BoundedRiverSegment river=site.river;
        double alongBlock=along*16.0+8.0,acrossBlock=river.spineBlock(alongBlock);
        if(!Double.isFinite(acrossBlock))return;
        double x=river.isZBound()?acrossBlock:alongBlock,z=river.isZBound()?alongBlock:acrossBlock;
        int cx=(int)Math.floor(x/16.0),cz=(int)Math.floor(z/16.0);
        if(cx<minX||cx>maxX||cz<minZ||cz>maxZ||!river.isRiver(cx,cz)
                ||site.ownership.at(cx,cz)!=BoundedRiverOwnershipPlan.Owner.WATER)return;
        out.add(new HydrologyConvergenceKey(HydrologyConvergenceKey.Receiver.CREEK,network,along,x,z));
    }

    public synchronized List<Site> parksIn(int minX,int minZ,int maxX,int maxZ){
        List<Site> parks=new ArrayList<>();
        for(int tx=Math.floorDiv(minX,64);tx<=Math.floorDiv(maxX,64);tx++)
            for(int tz=Math.floorDiv(minZ,64);tz<=Math.floorDiv(maxZ,64);tz++){
                Site s=acceptedSite(tx,tz);
                if(s!=null&&s.lotBaseX<=maxX&&s.lotBaseX+s.lotSizeX-1>=minX
                        &&s.lotBaseZ<=maxZ&&s.lotBaseZ+s.lotSizeZ-1>=minZ)parks.add(s);
            }
        return parks;
    }
}
