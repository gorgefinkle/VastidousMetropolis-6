package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import ch.k42.metropolis.grid.urbanGrid.wild.WildRelief;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import org.bukkit.block.BlockFace;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Axis;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Biome;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Leaves;

/**
 * Biome-local CONTENT ONLY. WildBiome/WildBiomes continue to own definition, shares,
 * edges, transitions and placement. All stamping is deterministic from seed and
 * world coordinates. It writes only to the supplied chunk and only on natural
 * wilderness cells, so generating chunks in any order yields the same features.
 */
public final class BiomeEcology {
    private BiomeEcology() {}
    private static final Map<Class<?>, Method> PICK = new ConcurrentHashMap<>();
    private static long mix(long v) {
        v ^= v >>> 30; v *= 0xbf58476d1ce4e5b9L;
        v ^= v >>> 27; v *= 0x94d049bb133111ebL;
        return v ^ (v >>> 31);
    }
    private static double unit(long v) { return (mix(v) >>> 11) * 0x1.0p-53; }
    private static long hash(long seed, long x, long z, long salt) {
        return mix(seed ^ mix(x * 0x9e3779b97f4a7c15L + z * 0xc2b2ae3d27d4eb4fL + salt));
    }
    private static double smooth(double v) { v = Math.max(0, Math.min(1, v)); return v*v*(3-2*v); }
    private static double field(long seed, double x, double z, double scale, long salt) {
        double u=x/scale, v=z/scale; long a=(long)Math.floor(u), b=(long)Math.floor(v);
        double fx=smooth(u-a),fz=smooth(v-b);
        double k0=unit(hash(seed,a,b,salt))*(1-fx)+unit(hash(seed,a+1,b,salt))*fx;
        double k1=unit(hash(seed,a,b+1,salt))*(1-fx)+unit(hash(seed,a+1,b+1,salt))*fx;
        return k0*(1-fz)+k1*fz;
    }
    // WildRelief derives coneSeed from world seed by two known XOR salts. Read the
    // per-world value rather than using world-independent fixed fractures.
    private static final Field WORLD_SEED_FIELD;
    static {
        try { WORLD_SEED_FIELD=WildRelief.class.getDeclaredField("coneSeed");WORLD_SEED_FIELD.setAccessible(true); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private static long reliefSeed(WildRelief prior) {
        try {return WORLD_SEED_FIELD.getLong(prior)^24568914886411596L^1129270853L;}
        catch (IllegalAccessException e) {throw new IllegalStateException("WildRelief seed inaccessible",e);}
    }
    // ForestTerrain intentionally salts the world seed before exposing getSeed().
    // All KarstLandforms relief, surface habitat, and large groves MUST use the
    // SAME unsalted world seed. The R30cz paintGround call received the forest
    // seed, whereas relief() recovered the world seed from WildRelief.
    static final long FOREST_TERRAIN_SEED_SALT = 77306496635732L;
    static long worldSeedFromForest(long forestTerrainSeed) {
        return forestTerrainSeed ^ FOREST_TERRAIN_SEED_SALT;
    }
    /** Replaces the old volcanic HEIGHT contribution, not the existing placement. */
    public static double relief(double original, double[] weight, int x, int z, WildRelief prior) {
        double karstW=weight[WildBiome.VOLCANIC_BARRENS.ordinal()];
        double bloomW=weight[WildBiome.BLOOMLANDS.ordinal()];
        if(karstW==0 && bloomW==0)return original;
        long seed=reliefSeed(prior);
        if (karstW>0) {
            double old=.55*prior.mountains(x,z)+.45*prior.profile(ch.k42.metropolis.grid.urbanGrid.wild.MountainKind.VOLCANO,x,z);
            // Long fractured valleys and their rock-ridge remnants are one terrain field.
            original+=karstW*(KarstLandforms.relief(seed,x,z,false)-old);
        }
        if (bloomW>0) {
            // The bloom has modest ridges and occasional deep, moss-filled crevasses.
            double ridge=7.0*(field(seed,x,z,110,71)-.5);
            original+=bloomW*(ridge+.67*KarstLandforms.relief(seed^0x626c6f6f6dL,x,z,true));
        }
        return original;
    }
    /** One continuous Karst floor ecology in world coordinates. The same
     * bedding and plant decisions are used by exposed ravines and the original
     * terrain preserved below a naturally occurring limestone span. Nothing
     * about a bridge footprint reduces moisture, clears a central corridor,
     * or reserves a dry ring beneath the arch. */
    static Material karstFloorMaterial(double habitat,double nearby,double broad) {
        if(habitat>.66) return nearby<.44?Material.MOSS_BLOCK:
            nearby<.74?Material.ROOTED_DIRT:Material.GRASS_BLOCK;
        if(habitat>.23) return nearby<.29?Material.MOSS_BLOCK:
            nearby<.61?Material.TUFF:nearby<.81?Material.STONE:Material.ROOTED_DIRT;
        return broad>.84 && nearby>.5?Material.GRASS_BLOCK:
            nearby<.19?Material.CALCITE:nearby<.32?Material.DIORITE:
            nearby<.70?Material.STONE:Material.TUFF;
    }
    static Material karstFloorPlant(double habitat,Material soil,double fine) {
        // Matches the wild ravine's regular flora. Any viable air column on
        // the original ground may grow plants regardless of overhead cover.
        if(habitat>.66) {
            if(fine<.27)return Material.FERN;
            if(fine<.43)return Material.SHORT_GRASS;
            if(fine<.52)return Material.AZALEA;
            if(fine<.56)return Material.FLOWERING_AZALEA;
            if(fine<.62 && soil==Material.MOSS_BLOCK)return Material.MOSS_CARPET;
        } else if(habitat>.23) {
            if(soil==Material.MOSS_BLOCK && fine<.21)return Material.FERN;
            if(soil==Material.ROOTED_DIRT && fine<.13)return Material.AZALEA;
        } else if(soil==Material.GRASS_BLOCK && fine<.23)
            return fine<.10?Material.SHORT_GRASS:Material.FERN;
        return null;
    }
    /** Topsoil and rock face palette depend on true fracture depth, not decorative circles.
     *  Growth is deterministic and uses vanilla survival blocks with valid supports. */
    public static void paintGround(Chunk chunk,int lx,int lz,int x,int z,int h,long seed,WildBiome b,boolean plants) {
        if(b!=WildBiome.VOLCANIC_BARRENS && b!=WildBiome.BLOOMLANDS) return;
        if (h<=3 || h>=chunk.getWorld().getMaxHeight()-4) return;
        boolean karst=b==WildBiome.VOLCANIC_BARRENS;
        // `seed` is ForestTerrain.getSeed(), NOT the world seed used by
        // the relief and the groves. Decode it before sampling a fault.
        long worldSeed=worldSeedFromForest(seed);
        long terrainSeed=karst?worldSeed:worldSeed^0x626c6f6f6dL;
        double valley=KarstLandforms.valley(terrainSeed,x,z);
        double naturalHabitat=karst?KarstLandforms.habitat(terrainSeed,x,z,valley):0;
        // Only the exposed rock crest has an upland palette. The ravine
        // beneath retains naturalHabitat WITHOUT a bridge-shaped dry shadow.
        boolean bridgeCrown=karst && KarstLandforms.archWeight(terrainSeed,x,z)>.10;
        double habitat=bridgeCrown?0:naturalHabitat;
        double nearby=field(seed,x,z,19,0x535542535452L);
        double broad=field(seed,x,z,97,0x47524153534cL);
        double fine=unit(hash(seed,x,z,0x46494e45L));
        Material top;
        if(karst) {
            top=karstFloorMaterial(habitat,nearby,broad);
            // Bridge decks are exposed limestone. Moisture/lushness belongs
            // on the valley floor under the arch, not the bridge top.
            if(bridgeCrown){
                // Real exposed shelf variation: coherent bedding lenses and
                // small weathered soil pockets, NOT independent random blocks
                // or lush green paint over the arch. These spatial fields are
                // independent of the arch/river axes, so they do not create
                // repeating stripes or a secondary visible arch on the roof.
                double bed=field(terrainSeed,x,z,31,0x5348454c464c454eL);
                double joint=field(terrainSeed,x+z*.23,z-x*.19,13,0x4b4152524a4f494eL);
                double pocket=field(terrainSeed,x,z,18,0x534f494c504f434bL);
                if(pocket>.90 && bed>.70 && joint>.64)top=Material.ROOTED_DIRT;
                else if(bed>.79 && joint>.58)top=Material.CALCITE;
                else if(bed<.35 && joint<.66)top=Material.TUFF;
                else if(joint>.72 && bed<.62)top=Material.DIORITE;
                else if(joint<.31 && bed>.33)top=Material.ANDESITE;
                else top=Material.STONE;
            }
            // Weathered limestone lenses, not an evenly ruled mural of
            // calcite/tuff stripes. The older painter colored almost entire
            // horizontal strata and made canyon walls look manufactured.
            // Here rare fractured lenses form short, offset, discontinuous
            // patches controlled by 3-D noise and warped elevation.
            boolean bridgeCliff=KarstLandforms.bridgeApproachActive(terrainSeed,x,z);
            int textureDepth=bridgeCliff?36:(valley>.40?13:8);
            for(int y=Math.max(2,h-textureDepth);y<h;y++){
                double warped=y/9.6
                    +1.7*(field(terrainSeed,x,z,44,0x535452415441L)-.5)
                    +.83*(field(terrainSeed,x+y*.18,z-y*.12,17,0x465241435455L)-.5);
                double phase=warped-Math.floor(warped);
                double patch=field(terrainSeed,x+y*.67,z-y*.51,19,0x4c494d45524fL);
                double fossil=field(terrainSeed,x-y*.53,z+y*.62,28,0x464f5353494cL);
                double breakage=field(terrainSeed,x+y*.86,z-y*.47,13,0x464c494e5453L);
                Material rock;
                if(bridgeCliff && y<h-12){
                    if(fossil>.67 && patch>.43)rock=Material.CALCITE;
                    else if(fossil<.32 && patch<.51)rock=Material.TUFF;
                    else if(breakage>.77 && patch>.55)rock=Material.DIORITE;
                    else if(breakage<.16 && patch<.47)rock=Material.ANDESITE;
                    else rock=Material.STONE;
                }
                else if(phase<.18 && fossil>.58 && patch<.53)rock=Material.CALCITE;
                else if(phase>.75 && fossil<.43 && patch>.57)rock=Material.TUFF;
                else if(patch<.14 && breakage>.52)rock=Material.DIORITE;
                else if(patch>.89 && breakage<.48)rock=Material.ANDESITE;
                else rock=Material.STONE;
                chunk.getBlock(lx,y,lz).setType(rock,false);
            }
        } else {
            top=nearby<.23?Material.ROOTED_DIRT:nearby<.51?Material.MYCELIUM:nearby<.79?Material.PODZOL:Material.MOSS_BLOCK;
            if(valley>.73 && nearby<.55)top=Material.MUD;
            for(int y=Math.max(2,h-3);y<h;y++)chunk.getBlock(lx,y,lz).setType(y==h-1&&top==Material.MUD?Material.MUD:Material.DIRT,false);
        }
        chunk.getBlock(lx,h,lz).setType(top,false);
        Block above=chunk.getBlock(lx,h+1,lz);
        Material old=above.getType();
        if(old==Material.FERN||old==Material.SHORT_GRASS||old==Material.TALL_GRASS||old==Material.DANDELION||old==Material.POPPY||old==Material.RED_MUSHROOM||old==Material.BROWN_MUSHROOM||old==Material.DEAD_BUSH||old==Material.AZURE_BLUET||old==Material.ALLIUM||old==Material.OXEYE_DAISY)
            above.setType(Material.AIR,false);
        if(plants && above.getType()==Material.AIR) {
            if(karst) {
                Material plant=karstFloorPlant(habitat,top,fine);
                if(plant!=null) above.setType(plant,false);
            } else {
                if(nearby<.75 && fine<.095 && top!=Material.MUD)above.setType(fine<.058?Material.BROWN_MUSHROOM:Material.RED_MUSHROOM,false);
                else if(nearby>.27 && fine<.35 && top!=Material.MUD)above.setType(Material.FERN,false);
                else if(fine<.39 && top==Material.MOSS_BLOCK)above.setType(Material.MOSS_CARPET,false);
                else if(fine<.42 && top==Material.PODZOL)above.setType(Material.AZALEA,false);
                else if(fine<.445 && top==Material.ROOTED_DIRT)above.setType(Material.LEAF_LITTER,false);
            }
        }
        // UNDERCUT is evaluated from exactly the same continuous ridge/fault model
        // as the relief above. It removes ONLY the rock below a preexisting ridge
        // remnant; both ends of that ridge remain naturally joined to land.
        // The dry ravine approaches can be substantially below the old 17-block
        // ceiling guard. Keep Bloomlands' original gate, but allow shallow
        // Karst portal toes may sit just four blocks above sea level. The old
        // +8 gate created solid, impassable lips wherever a natural grade
        // dropped to 67..70 even though the floor itself remained dry.
        if(h >= chunk.getWorld().getSeaLevel() + (karst?4:17)) {
            // Short water-eroded cliff cavities are part of the same fracture
            // field, only on exposed rock walls; never underneath boat water.
            if(karst && h>=chunk.getWorld().getSeaLevel()+17
               && KarstLandforms.archWeight(terrainSeed,x,z)<.055) {
                // Independent cliff caves must not erode the continuous lower
                // surface or load-bearing piers of a natural landbridge.
                int caveTop=KarstLandforms.caveCeiling(terrainSeed,x,z,h);
                if(caveTop>=0){
                    for(int cy=Math.max(chunk.getWorld().getSeaLevel()+4,KarstLandforms.caveFloor(terrainSeed,x,z,h));cy<=caveTop;cy++){
                        Block rock=chunk.getBlock(lx,cy,lz);
                        Material m=rock.getType();
                        if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE||m==Material.DIORITE||m==Material.ANDESITE)rock.setType(Material.AIR,false);
                    }
                }
            }
            if(karst){
                int floor=KarstLandforms.bridgeFloor(terrainSeed,x,z,h);
                if(floor>=0 && h-floor>2){
                    int upperCut=KarstSpanVolume.undercutCeiling(terrainSeed,x,z,h,floor);
                    for(int cy=Math.max(4,floor+1);cy<=upperCut;cy++){
                        Block rock=chunk.getBlock(lx,cy,lz);
                        Material m=rock.getType();
                        if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE
                            ||m==Material.DIORITE||m==Material.ANDESITE
                            ||m==Material.GRANITE||m==Material.DIRT||m==Material.MOSS_BLOCK
                            ||m==Material.ROOTED_DIRT||m==Material.GRASS_BLOCK
                            ||m==Material.PODZOL||m==Material.MYCELIUM)
                            rock.setType(Material.AIR,false);
                    }
                    // R30dt: the ravine continues its NORMAL wild vegetation
                    // right through the opening. This uses exactly the same
                    // soil/plant fields as the unbridged ravine on either side;
                    // no shaded dry spot, low-density interior, or cleared trail.
                    // At exactly sea level the floor is still naturally dry
                    // ground with air above it: older +3/+1 sea-level gates
                    // left a continuous bare-rock strip along the deepest
                    // part of the ravine, directly under the bridge. Paint it
                    // like the adjoining ravine, but never replace water or
                    // plants in a liquid column (the air/support checks guard
                    // natural inundation).
                    if(floor>=chunk.getWorld().getSeaLevel() && floor>4){
                        Block footing=chunk.getBlock(lx,floor,lz);
                        Material m=footing.getType();
                        if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE
                          ||m==Material.DIORITE||m==Material.ANDESITE){
                            Material bedding=karstFloorMaterial(naturalHabitat,nearby,broad);
                            footing.setType(bedding,false);
                            Block growth=chunk.getBlock(lx,floor+1,lz);
                            Block clearance=chunk.getBlock(lx,floor+2,lz);
                            if(plants && growth.getType().isAir() && clearance.getType().isAir()){
                                Material plant=karstFloorPlant(naturalHabitat,bedding,fine);
                                if(plant!=null)growth.setType(plant,false);
                            }
                            // Optional overhead flora follows sheltered moisture
                            // already present in the NATURAL valley, never a
                            // separate patch that consumes the floor vegetation.
                            double shade=smooth((h-floor-13.0)/32.0)
                                *smooth(KarstSpanVolume.coverage(terrainSeed,x,z)/.60);
                            double moisture=.53*naturalHabitat
                                +.30*field(terrainSeed,x,z,32,0x574554504f434bL)
                                +.17*field(terrainSeed,x,z,11,0x4645524e434f4c4cL);
                            // Lush cave flowers are attached to the actual
                            // remaining roof, not spawned in midair. A slow,
                            // coherent colony field groups them into a few
                            // sheltered ceiling pockets rather than grids.
                            // Never attach below ocean level or at open portals.
                            if(plants && shade>.62 && moisture>.61
                               && upperCut>=floor+6 && upperCut<h-3){
                                double ceilingColony=field(terrainSeed,x,z,29,0x53504f5245504f43L);
                                double ceilingFine=unit(hash(seed,x,z,0x53504f52454b4559L));
                                if(ceilingColony>.65 && ceilingFine<.13){
                                    Block support=chunk.getBlock(lx,upperCut+1,lz);
                                    Block hanging=chunk.getBlock(lx,upperCut,lz);
                                    Material supportType=support.getType();
                                    if((supportType==Material.STONE || supportType==Material.TUFF
                                        || supportType==Material.CALCITE || supportType==Material.ANDESITE
                                        || supportType==Material.DIORITE)
                                        && hanging.getType().isAir())
                                        hanging.setType(Material.SPORE_BLOSSOM,false);
                                }
                            }
                        }
                    }
                }
            } else {
            int ceiling=KarstLandforms.ceiling(terrainSeed,x,z,h);
            if(ceiling>=0){
                int floor=KarstLandforms.floor(terrainSeed,x,z,h);
                // The bridge is a true two-level landform. Preserve the lower
                // natural ravine at the EXACT height it would have without
                // the bridge, and leave a mineable roof spanning the cliffs.
                // Low end-portals cut to sky, smoothly joining the exposed
                // ravine on each side without an impassable rock threshold.
                boolean portal=karst && ceiling>=h;
                for(int y=Math.max(4,karst?floor+1:floor);y<=ceiling;y++){
                    Block rock=chunk.getBlock(lx,y,lz);
                    Material m=rock.getType();
                    if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE
                       ||m==Material.DIORITE||m==Material.DIRT||m==Material.GRANITE
                       ||m==Material.ANDESITE||(portal&&(m==Material.MOSS_BLOCK
                       ||m==Material.ROOTED_DIRT||m==Material.GRASS_BLOCK
                       ||m==Material.PODZOL||m==Material.MYCELIUM)))rock.setType(Material.AIR,false);
                }
                // Sheltered growth belongs on the original ravine floor, never
                // the exposed upper deck. Do not invent an underwater garden.
                if(karst && floor>chunk.getWorld().getSeaLevel()+3 && floor>4){
                    Block floorRock=chunk.getBlock(lx,floor,lz);
                    Material existing=floorRock.getType();
                    if(existing==Material.STONE||existing==Material.TUFF||existing==Material.CALCITE
                       ||existing==Material.DIORITE||existing==Material.ANDESITE){
                        Material bedding=nearby<.39?Material.MOSS_BLOCK:nearby<.71?Material.ROOTED_DIRT:Material.GRASS_BLOCK;
                        floorRock.setType(bedding,false);
                        Block growth=chunk.getBlock(lx,floor+1,lz);
                        if(plants && growth.getType().isAir()){
                            if(fine<.25)growth.setType(Material.FERN,false);
                            else if(fine<.32)growth.setType(Material.SHORT_GRASS,false);
                            else if(fine<.35 && bedding==Material.MOSS_BLOCK)growth.setType(Material.MOSS_CARPET,false);
                        }
                    }
                }
            }
            }
        }
        if(karst && h>chunk.getWorld().getSeaLevel()+17
           && KarstSpanVolume.bankInRange(terrainSeed,x,z)){
            // The new span body and adjoining natural gorge are weathered by
            // one XYZ field. Unlike the rejected haunch cuts, this never acts
            // on Bloomlands or on the agreed Willow geometry.
            int natural=KarstSpanVolume.floor(terrainSeed,x,z,h,
                                  KarstLandforms.unbridgedRelief(terrainSeed,x,z));
            for(int cy=Math.max(chunk.getWorld().getSeaLevel()+6,Math.max(4,natural+6));cy<h-6;cy++){
                if(!KarstSpanVolume.erodeBank(terrainSeed,x,cy,z,h,natural))continue;
                Block rock=chunk.getBlock(lx,cy,lz);
                Material m=rock.getType();
                if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE||
                   m==Material.DIORITE||m==Material.ANDESITE)
                    rock.setType(Material.AIR,false);
            }
        }
    }
    public static Biome karstBiome() { return ch.k42.metropolis.grid.urbanGrid.wild.BiomeNames.resolve(Biome.FOREST, "FOREST", "LUSH_CAVES"); }
    private static WildBiome pick(Object wild,int x,int z,long seed) {
        try {
            Method m=PICK.computeIfAbsent(wild.getClass(), c -> {
                try {Method found=c.getDeclaredMethod("pick",int.class,int.class,double.class);found.setAccessible(true);return found;}
                catch (ReflectiveOperationException e) {throw new IllegalStateException(e);}
            });
            return (WildBiome)m.invoke(wild,x,z,ForestTerrain.unit(ForestTerrain.hash(seed,x,z,41)));
        } catch (ReflectiveOperationException e) {throw new IllegalStateException("Cannot read existing wilderness biome placement",e);}
    }
    private static boolean inChunk(Chunk c,int x,int z) {
        return (x>>4)==c.getX() && (z>>4)==c.getZ();
    }
    private static boolean replaceable(Material m) {
        return m.isAir() || m==Material.SHORT_GRASS || m==Material.FERN || m==Material.RED_MUSHROOM || m==Material.BROWN_MUSHROOM || m==Material.VINE || m==Material.MOSS_CARPET || m==Material.AZALEA || m==Material.FLOWERING_AZALEA || m==Material.LEAF_LITTER;
    }
    private static void put(Chunk c,ForestGenerator.TreeSite site,int x,int y,int z,Material mat) {
        if(!inChunk(c,x,z)||y<=2||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
        Block b=c.getBlock(x&15,y,z&15);
        if(!replaceable(b.getType())) return;
        b.setType(mat,false);
        if(b.getBlockData() instanceof Leaves) {
            if(b.getBlockData() instanceof Leaves d) {d.setPersistent(true);b.setBlockData(d,false);}
        }
    }
    private static void log(Chunk c,ForestGenerator.TreeSite site,int x,int y,int z,Material mat,Axis axis) {
        if(!inChunk(c,x,z)||y<=2||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
        Block b=c.getBlock(x&15,y,z&15);
        // The separately stamped willow limbs must pass through their OWN canopy.
        if(!replaceable(b.getType()) && b.getType()!=Material.OAK_LEAVES
            && b.getType()!=Material.DARK_OAK_LEAVES && b.getType()!=Material.SPRUCE_LEAVES) return;
        b.setType(mat,false);
        if(b.getBlockData() instanceof Orientable d) {d.setAxis(axis);b.setBlockData(d,false);}
    }
    /** Buried Willow anchors may displace only natural soil or survival-mineable
     * bedrock-family stone. Never tunnel through authored structures, roadways,
     * liquids or another world's edited blocks; TreeSite also owns cell access. */
    private static void willowRoot(Chunk c,ForestGenerator.TreeSite site,int x,int y,int z,Axis axis) {
        if(!inChunk(c,x,z)||y<=3||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
        if(y>site.groundAt(x,z) || y<site.groundAt(x,z)-24)return;
        Block b=c.getBlock(x&15,y,z&15);
        Material existing=b.getType();
        if(existing!=Material.STONE && existing!=Material.DIORITE && existing!=Material.ANDESITE
           && existing!=Material.GRANITE && existing!=Material.CALCITE && existing!=Material.TUFF
           && existing!=Material.DIRT && existing!=Material.GRASS_BLOCK && existing!=Material.ROOTED_DIRT
           && existing!=Material.MOSS_BLOCK && existing!=Material.PODZOL && existing!=Material.MUD
           && existing!=Material.MYCELIUM) return;
        b.setType(Material.OAK_LOG,false);
        if(b.getBlockData() instanceof Orientable d){d.setAxis(axis);b.setBlockData(d,false);}
    }
    private static void fungalStem(Chunk c,ForestGenerator.TreeSite site,int x,int y,int z) {
        if(!inChunk(c,x,z)||y<=2||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
        Block b=c.getBlock(x&15,y,z&15);
        if(!replaceable(b.getType()))return;
        b.setType(Material.MUSHROOM_STEM,false);
        // All visible side faces have stem texture; adjacent invisible faces can be culled by rendering.
        if(b.getBlockData() instanceof MultipleFacing faces) {
            for(org.bukkit.block.BlockFace face:faces.getAllowedFaces()) faces.setFace(face,true);
            b.setBlockData(faces,false);
        }
    }
    private static void mushroom(Chunk c,ForestGenerator.TreeSite site,int cx,int cz,long seed,int family) {
        MushroomGeometry.grow((x,y,z,state)->{
            if(!inChunk(c,x,z)||y<=2||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
            Block at=c.getBlock(x&15,y,z&15);
            if(!replaceable(at.getType()))return;
            boolean stem=state.startsWith("minecraft:mushroom_stem");
            Material mat=stem?Material.MUSHROOM_STEM:state.startsWith("minecraft:red_")?Material.RED_MUSHROOM_BLOCK:Material.BROWN_MUSHROOM_BLOCK;
            at.setType(mat,false);
            if(at.getBlockData() instanceof MultipleFacing faces) {
                for(BlockFace f:faces.getAllowedFaces()){
                    String face=f.name().toLowerCase(java.util.Locale.ROOT);
                    int pos=state.indexOf(face+"=");
                    faces.setFace(f,pos>=0 && state.startsWith("true",pos+face.length()+1));
                }
                at.setBlockData(faces,false);
            }
        },new MushroomGeometry.Terrain(){
            public int groundAt(int x,int z){return site.groundAt(x,z);}
            public boolean allows(int x,int z){return site.allows(x,z);}
        },cx,cz,seed,family);
    }
    private static void willow(Chunk c,ForestGenerator.TreeSite site,int cx,int cz,long seed) {
        MotherWillowGeometry.growChunk((x,y,z,state)->{
            if(!inChunk(c,x,z)||y<=2||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
            boolean isWood=state.startsWith("minecraft:oak_log");
            if(isWood) {
                Axis axis=state.endsWith("axis=x]")?Axis.X:state.endsWith("axis=z]")?Axis.Z:Axis.Y;
                if(y<=site.groundAt(x,z))willowRoot(c,site,x,y,z,axis);
                else log(c,site,x,y,z,Material.OAK_LOG,axis);
            } else put(c,site,x,y,z,Material.OAK_LEAVES);
        },new MotherWillowGeometry.Terrain(){
            public int groundAt(int x,int z){return site.groundAt(x,z);}
            public boolean allows(int x,int z){return site.allows(x,z);}
        },cx,cz,seed,c.getX(),c.getZ());
    }
    /** Varied limestone/tuff boulders anchored on exposed upper rock, never
     * detached decorative props and never inside a protected district. */
    private static void karstBoulder(Chunk c,ForestGenerator.TreeSite site,int cx,int cz,long seed){
        if(!site.allows(cx,cz) || KarstLandforms.habitat(seed,cx,cz)>.20)return;
        long h=hash(seed,cx,cz,0x424f554c444552L);
        if(unit(h)>.60)return;
        int rx=2+(int)(unit(h+2)*4),rz=2+(int)(unit(h+3)*4);
        int tall=2+(int)(unit(h+5)*4);
        Material base=unit(h+8)<.20?Material.TUFF:unit(h+8)<.34?Material.CALCITE:Material.STONE;
        for(int dx=-rx;dx<=rx;dx++)for(int dz=-rz;dz<=rz;dz++){
            double shape=(dx*dx/(double)(rx*rx)+dz*dz/(double)(rz*rz))
               +.17*(unit(hash(seed,cx+dx,cz+dz,0x42524f4bL))-.5);
            if(shape>=1.0)continue;
            int gh=site.groundAt(cx+dx,cz+dz);
            int rise=Math.max(1,(int)Math.round(tall*Math.sqrt(Math.max(0,1-shape))));
            for(int dy=1;dy<=rise;dy++){
                Material m=dy==rise&&base==Material.STONE&&unit(hash(seed,cx+dx,cz+dz,dy))>.90
                    ?Material.ANDESITE:base;
                put(c,site,cx+dx,gh+dy,cz+dz,m);
            }
        }
    }
    private static void companion(Chunk c,ForestGenerator.TreeSite site,int cx,int cz,long seed){
        CompanionTreeGeometry.grow((x,y,z,state)->{
            if(!inChunk(c,x,z)||y<=2||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
            boolean isLog=state.contains("_log[");
            Material mat=state.startsWith("minecraft:spruce")
                ?(isLog?Material.SPRUCE_LOG:Material.SPRUCE_LEAVES)
                :(isLog?Material.DARK_OAK_LOG:Material.DARK_OAK_LEAVES);
            if(isLog)log(c,site,x,y,z,mat,state.endsWith("axis=x]")?Axis.X:state.endsWith("axis=z]")?Axis.Z:Axis.Y);
            else put(c,site,x,y,z,mat);
        },new CompanionTreeGeometry.Terrain(){
            public int groundAt(int x,int z){return site.groundAt(x,z);}
            public boolean allows(int x,int z){return site.allows(x,z);}
        },cx,cz,seed);
    }
    private static void karstGroves(Chunk c,ForestGenerator.TreeSite site,int cx,int cz,long seed){
        // Leave the weather-exposed bridge crest bare; trees belong to the
        // sheltered ravine/toe habitat rather than the vaulted roof.
        if(KarstLandforms.archWeight(seed,cx,cz)>.20)return;
        if(KarstLandforms.habitat(seed,cx,cz)<.68 || !site.allows(cx,cz))return;
        long h=hash(seed,cx,cz,0x47524f5645L);
        if(unit(h)<.45)return;
        KarstGroveGeometry.growChunk((x,y,z,state)->{
            if(!inChunk(c,x,z)||y<=2||y>=c.getWorld().getMaxHeight()-2||!site.allows(x,z))return;
            if(state.startsWith("minecraft:oak_log"))
                log(c,site,x,y,z,Material.OAK_LOG,state.endsWith("axis=x]")?Axis.X:state.endsWith("axis=z]")?Axis.Z:Axis.Y);
            else put(c,site,x,y,z,Material.AZALEA_LEAVES);
        },new KarstGroveGeometry.Terrain(){
            public int groundAt(int x,int z){return site.groundAt(x,z);}
            public boolean allows(int x,int z){return site.allows(x,z);}
        },cx,cz,seed,c.getX(),c.getZ());
    }
    /** Uses the already-decided wild biome, never registers or reallocates regions. */
    public static void decorate(Chunk chunk,ForestGenerator.TreeSite site,Object wild,long seed) {
        if(wild==null)return;
        int bx=chunk.getX()<<4,bz=chunk.getZ()<<4;
        // Each spatial cell owns at most one landmark, and each chunk clips its own voxels.
        for(int spacing:new int[]{72,224,112,48,32}) {
            int reach=spacing==72?43:spacing==224?190:spacing==112?52:spacing==48?10:26;
            int minCX=Math.floorDiv(bx-reach,spacing),maxCX=Math.floorDiv(bx+15+reach,spacing);
            int minCZ=Math.floorDiv(bz-reach,spacing),maxCZ=Math.floorDiv(bz+15+reach,spacing);
            for(int gx=minCX;gx<=maxCX;gx++)for(int gz=minCZ;gz<=maxCZ;gz++) {
                long h=hash(seed,gx,gz,spacing);
                int x=gx*spacing+12+(int)(unit(h)*Math.max(1,spacing-24));
                int z=gz*spacing+12+(int)(unit(h+1)*Math.max(1,spacing-24));
                if(Math.abs(x-(bx+8))>reach+9||Math.abs(z-(bz+8))>reach+9)continue;
                if(!site.allows(x,z))continue;
                WildBiome b=pick(wild,x,z,seed);
                if(spacing==72 && b==WildBiome.BLOOMLANDS && unit(h+3)<.72) {
                    // Do not cut monuments abruptly at established biome/urban limits.
                    boolean room=true;
                    for(int a=0;a<8;a++){
                        double angle=a*Math.PI/4;
                        int tx=x+(int)Math.round(Math.cos(angle)*38),tz=z+(int)Math.round(Math.sin(angle)*38);
                        if(!site.allows(tx,tz)||pick(wild,tx,tz,seed)!=b){room=false;break;}
                    }
                    if(room)mushroom(chunk,site,x,z,seed,(int)(unit(h+9)*6));
                }
                if(spacing==224 && b==WildBiome.BLOOMLANDS && unit(h+3)<.28) {
                    // Reject clipped crowns near the build ceiling. A rare
                    // Mother Willow must remain a complete, living organism.
                    boolean room=site.groundAt(x,z)+190<chunk.getWorld().getMaxHeight()-3;
                    for(int a=0;a<16&&room;a++){
                        double angle=a*Math.PI/8;
                        int tx=x+(int)Math.round(Math.cos(angle)*190),tz=z+(int)Math.round(Math.sin(angle)*190);
                        if(!site.allows(tx,tz)||pick(wild,tx,tz,seed)!=b){room=false;break;}
                    }
                    if(room)willow(chunk,site,x,z,seed);
                }
                if(spacing==112 && b==WildBiome.BLOOMLANDS && unit(h+3)<.36){
                    boolean room=true;
                    for(int a=0;a<8;a++){
                        double angle=a*Math.PI/4;
                        int tx=x+(int)Math.round(Math.cos(angle)*46),tz=z+(int)Math.round(Math.sin(angle)*46);
                        if(!site.allows(tx,tz)||pick(wild,tx,tz,seed)!=b){room=false;break;}
                    }
                    if(room)companion(chunk,site,x,z,seed);
                }
                if(spacing==48 && b==WildBiome.VOLCANIC_BARRENS)
                    karstBoulder(chunk,site,x,z,seed);
                if(spacing==32 && b==WildBiome.VOLCANIC_BARRENS && unit(h+3)<.59)
                    karstGroves(chunk,site,x,z,seed);
            }
        }
    }
}
