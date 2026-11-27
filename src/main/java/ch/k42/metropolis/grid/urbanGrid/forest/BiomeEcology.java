package ch.k42.metropolis.grid.urbanGrid.forest;

import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import ch.k42.metropolis.grid.urbanGrid.wild.WildRelief;
import java.lang.reflect.Method;
import java.util.HashMap;
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

    /** Memoizes repeated pure terrain/ownership queries within one chunk's decoration pass. */
    private static final class CachedTreeSite implements ForestGenerator.TreeSite {
        private final ForestGenerator.TreeSite delegate;
        private final Map<Long, Boolean> allowed = new HashMap<>();
        private final Map<Long, Integer> ground = new HashMap<>();
        CachedTreeSite(ForestGenerator.TreeSite delegate) { this.delegate = delegate; }
        private static long key(int x, int z) {
            return ch.k42.metropolis.grid.urbanGrid.wild.SpatialCache.key(x, z);
        }
        @Override public boolean allows(int x, int z) {
            long key = key(x, z);
            Boolean cached = allowed.get(key);
            if (cached != null) return cached;
            boolean result = delegate.allows(x, z);
            allowed.put(key, result);
            return result;
        }
        @Override public int groundAt(int x, int z) {
            long key = key(x, z);
            Integer cached = ground.get(key);
            if (cached != null) return cached;
            int result = delegate.groundAt(x, z);
            ground.put(key, result);
            return result;
        }
        private final Map<Long, Boolean> bloomlands = new HashMap<>();
        boolean bloomlands(Object wild, int x, int z, long seed) {
            long key = key(x, z);
            Boolean cached = bloomlands.get(key);
            if (cached != null) return cached;
            boolean result = allows(x, z) && pick(wild, x, z, seed) == WildBiome.BLOOMLANDS;
            bloomlands.put(key, result);
            return result;
        }
        @Override public double fade(int x, int z) { return delegate.fade(x, z); }
        @Override public double edgeDistance(int x, int z, int reachChunks) {
            return delegate.edgeDistance(x, z, reachChunks);
        }
    }
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
    // Use the immutable world seed supplied to WildRelief. Do not infer it
    // from private implementation fields: the relief noise layout can change
    // without changing the world-coordinate seed shared by biome painters.
    private static long reliefSeed(WildRelief prior) {
        return prior.getWorldSeed();
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
        return relief(original,weight,x,z,prior,false);
    }

    /** Karst arches are permitted only when the owning forest region has
     * confirmed the entire span footprint. Ordinary karst relief remains
     * continuous when that ownership test refuses a bridge. */
    public static double relief(double original, double[] weight, int x, int z,
                                WildRelief prior, boolean allowKarstBridge) {
        double karstW=weight[WildBiome.VOLCANIC_BARRENS.ordinal()];
        double bloomW=weight[WildBiome.BLOOMLANDS.ordinal()];
        if(karstW==0 && bloomW==0)return original;
        long seed=reliefSeed(prior);
        if (karstW>0) {
            // Match ForestGenerator's non-volcanic Karstlands baseline. The
            // feature's own limestone relief is added below by KarstLandforms.
            double old=prior.mountains(x,z);
            boolean bridge = allowKarstBridge && karstW >= 0.96;
            // A bridge top and its undercut must use the SAME absolute surface.
            // Blending the full arch height by a partial biome weight made the
            // painter's reconstructed floor disagree with the actual top, so
            // the clearance gate rejected every cut and left a solid block mass.
            if (bridge) return KarstLandforms.relief(seed,x,z,false,true);
            // Ordinary Karst relief remains softly blended at biome margins.
            original+=karstW*(KarstLandforms.relief(seed,x,z,false,false)-old);
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
    public static void paintGround(Chunk chunk,int lx,int lz,int x,int z,int h,long seed,
                                   WildBiome b,boolean plants,boolean allowKarstBridge,int baseY) {
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
        boolean bridgeCrown=karst && allowKarstBridge && KarstLandforms.archWeight(terrainSeed,x,z)>.10;
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
            boolean bridgeCliff=allowKarstBridge && KarstLandforms.bridgeApproachActive(terrainSeed,x,z);
            int textureDepth=bridgeCliff?144:(valley>.40?128:104);
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
                Block stratum=chunk.getBlock(lx,y,lz);
                if(!stratum.getType().name().endsWith("_ORE")) stratum.setType(rock,false);
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
        // All vertical carving is tied to the true build datum and to the
        // already-decided forest parcel owner. A bridge is never allowed to
        // infer its floor from a partially faded or mixed-biome surface.
        if(h >= chunk.getWorld().getSeaLevel() + (karst ? 4 : 17)) {
            if(karst) {
                // Independent small cliff cavities remain part of the Karst
                // fracture system. They are not part of a landbridge and do
                // not touch the floor of a bridge.
                if(h >= chunk.getWorld().getSeaLevel() + 17
                   && KarstLandforms.archWeight(terrainSeed,x,z) < .055) {
                    int caveTop=KarstLandforms.caveCeiling(terrainSeed,x,z,h);
                    if(caveTop>=0) {
                        int caveFloor=Math.max(chunk.getWorld().getSeaLevel()+4,
                                KarstLandforms.caveFloor(terrainSeed,x,z,h));
                        for(int cy=caveFloor;cy<=caveTop;cy++) {
                            Block rock=chunk.getBlock(lx,cy,lz);
                            Material m=rock.getType();
                            if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE
                                    ||m==Material.DIORITE||m==Material.ANDESITE) {
                                rock.setType(Material.AIR,false);
                            }
                        }
                    }
                }

                // Strict ownership check must have been made by ForestGenerator
                // before this method is called. Refuse the bridge completely
                // on any mixed, shore, city, road, empty or fallback footprint.
                if(allowKarstBridge) {
                    int minimumFloor=Math.max(chunk.getWorld().getMinHeight()+16,baseY-6);
                    int floor=KarstLandforms.bridgeFloor(
                            terrainSeed,x,z,h,baseY,minimumFloor);
                    if(floor>=minimumFloor && h-floor>=48) {
                        int upperCut=KarstSpanVolume.undercutCeiling(
                                terrainSeed,x,z,h,floor);
                        // A real arch needs a generous open void and a
                        // substantial limestone keystone. Do not hollow out
                        // a mountain if either condition is not satisfied.
                        if(upperCut-floor>=30 && upperCut<h-8) {
                            for(int cy=floor+1;cy<=upperCut;cy++) {
                                Block rock=chunk.getBlock(lx,cy,lz);
                                Material m=rock.getType();
                                if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE
                                        ||m==Material.DIORITE||m==Material.ANDESITE
                                        ||m==Material.GRANITE||m==Material.DIRT
                                        ||m==Material.MOSS_BLOCK||m==Material.ROOTED_DIRT
                                        ||m==Material.GRASS_BLOCK||m==Material.PODZOL
                                        ||m==Material.MYCELIUM) {
                                    rock.setType(Material.AIR,false);
                                }
                            }

                            // Keep the natural ravine floor continuous beneath
                            // the span, with its ordinary Karst ecology.
                            if(floor>=chunk.getWorld().getSeaLevel() && floor>minimumFloor) {
                                Block footing=chunk.getBlock(lx,floor,lz);
                                Material m=footing.getType();
                                if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE
                                        ||m==Material.DIORITE||m==Material.ANDESITE) {
                                    Material bedding=karstFloorMaterial(naturalHabitat,nearby,broad);
                                    footing.setType(bedding,false);
                                    Block growth=chunk.getBlock(lx,floor+1,lz);
                                    Block clearance=chunk.getBlock(lx,floor+2,lz);
                                    if(plants && growth.getType().isAir() && clearance.getType().isAir()) {
                                        Material plant=karstFloorPlant(naturalHabitat,bedding,fine);
                                        if(plant!=null) growth.setType(plant,false);
                                    }

                                    double shade=smooth((h-floor-13.0)/32.0)
                                            *smooth(KarstSpanVolume.coverage(terrainSeed,x,z)/.60);
                                    double moisture=.53*naturalHabitat
                                            +.30*field(terrainSeed,x,z,32,0x574554504f434bL)
                                            +.17*field(terrainSeed,x,z,11,0x4645524e434f4c4cL);
                                    if(plants && shade>.62 && moisture>.61
                                            && upperCut>=floor+6 && upperCut<h-3) {
                                        double colony=field(terrainSeed,x,z,29,0x53504f5245504f43L);
                                        double fineCeiling=unit(hash(seed,x,z,0x53504f52454b4559L));
                                        if(colony>.65 && fineCeiling<.13) {
                                            Block support=chunk.getBlock(lx,upperCut+1,lz);
                                            Block hanging=chunk.getBlock(lx,upperCut,lz);
                                            Material supportType=support.getType();
                                            if((supportType==Material.STONE||supportType==Material.TUFF
                                                    ||supportType==Material.CALCITE||supportType==Material.ANDESITE
                                                    ||supportType==Material.DIORITE)
                                                    && hanging.getType().isAir()) {
                                                hanging.setType(Material.SPORE_BLOSSOM,false);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Bloomlands retains its independent, older crevasse geometry.
                int ceiling=KarstLandforms.ceiling(terrainSeed,x,z,h);
                if(ceiling>=0) {
                    int floor=KarstLandforms.floor(terrainSeed,x,z,h);
                    for(int y=Math.max(chunk.getWorld().getMinHeight()+8,floor);y<=ceiling;y++) {
                        Block rock=chunk.getBlock(lx,y,lz);
                        Material m=rock.getType();
                        if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE
                                ||m==Material.DIORITE||m==Material.DIRT||m==Material.GRANITE
                                ||m==Material.ANDESITE) {
                            rock.setType(Material.AIR,false);
                        }
                    }
                }
            }
        }
        if(karst && allowKarstBridge && h>chunk.getWorld().getSeaLevel()+17
           && KarstSpanVolume.bankInRange(terrainSeed,x,z)){
            // The new span body and adjoining natural gorge are weathered by
            // one XYZ field. Unlike the rejected haunch cuts, this never acts
            // on Bloomlands or on the agreed Willow geometry.
            int natural=baseY+(int)Math.round(KarstLandforms.unbridgedRelief(terrainSeed,x,z));
            int minimumFloor=Math.max(chunk.getWorld().getMinHeight()+16,baseY-8);
            if(natural>=minimumFloor){
            for(int cy=Math.max(chunk.getWorld().getSeaLevel()+6,natural+6);cy<h-6;cy++){
                if(!KarstSpanVolume.erodeBank(terrainSeed,x,cy,z,h,natural))continue;
                Block rock=chunk.getBlock(lx,cy,lz);
                Material m=rock.getType();
                if(m==Material.STONE||m==Material.TUFF||m==Material.CALCITE||
                   m==Material.DIORITE||m==Material.ANDESITE)
                    rock.setType(Material.AIR,false);
            }
            }
        }
    }
    public static Biome karstBiome() { return ch.k42.metropolis.grid.urbanGrid.wild.BiomeNames.resolve(Biome.FOREST, "FOREST", "LUSH_CAVES"); }
    static double pickerRoll(long worldSeed,int x,int z) {
        long terrainSeed=worldSeed ^ ForestTerrain.WORLD_SEED_SALT;
        return ForestTerrain.unit(ForestTerrain.hash(terrainSeed,x,z,41));
    }
    static WildBiome pick(Object wild,int x,int z,long worldSeed) {
        try {
            Method m=PICK.computeIfAbsent(wild.getClass(), c -> {
                try {Method found=c.getDeclaredMethod("pick",int.class,int.class,double.class);found.setAccessible(true);return found;}
                catch (ReflectiveOperationException e) {throw new IllegalStateException(e);}
            });
            return (WildBiome)m.invoke(wild,x,z,pickerRoll(worldSeed,x,z));
        } catch (ReflectiveOperationException e) {throw new IllegalStateException("Cannot read existing wilderness biome placement",e);}
    }
    static int decorationCellX(long seed,int gx,int gz,int spacing) {
        long h=hash(seed,gx,gz,spacing);
        return gx*spacing+12+(int)(unit(h)*Math.max(1,spacing-24));
    }
    static int decorationCellZ(long seed,int gx,int gz,int spacing) {
        long h=hash(seed,gx,gz,spacing);
        return gz*spacing+12+(int)(unit(h+1)*Math.max(1,spacing-24));
    }
    static boolean decorationCellChance(long seed,int gx,int gz,int spacing,int salt,double chance) {
        return unit(hash(seed,gx,gz,spacing)+salt)<chance;
    }
    static boolean hasGiantMushroomFootprint(Object wild,int x,int z,long seed) {
        if(pick(wild,x,z,seed)!=WildBiome.BLOOMLANDS)return false;
        for(int a=0;a<8;a++){
            double angle=a*Math.PI/4;
            int tx=x+(int)Math.round(Math.cos(angle)*38),tz=z+(int)Math.round(Math.sin(angle)*38);
            if(pick(wild,tx,tz,seed)!=WildBiome.BLOOMLANDS)return false;
        }
        return true;
    }
    static boolean hasMotherWillowFootprint(Object wild,int x,int z,long seed) {
        if(pick(wild,x,z,seed)!=WildBiome.BLOOMLANDS)return false;
        for(int a=0;a<16;a++){
            double angle=a*Math.PI/8;
            int tx=x+(int)Math.round(Math.cos(angle)*190),tz=z+(int)Math.round(Math.sin(angle)*190);
            if(pick(wild,tx,tz,seed)!=WildBiome.BLOOMLANDS)return false;
        }
        return true;
    }
    private static boolean inChunk(Chunk c,int x,int z) {
        return (x>>4)==c.getX() && (z>>4)==c.getZ();
    }
    private static boolean validVegetationY(int y,int minHeight,int maxHeight) {
        return VegetationHeightPolicy.allows(y,minHeight,maxHeight);
    }
    /**
     * Natural ground cover is allowed to yield to trunks and stems. Keep the
     * list explicit: mushrooms must not overwrite soil/rock, but the forest's
     * own cover() painter can put any of these single-block plants in the
     * mushroom's first stem cell. Previously those blocks blocked placement
     * and left the visible stalk floating one block above the ground.
     */
    static boolean replaceable(Material m) {
        // Explicit enum comparisons work in offline QA without Bukkit RegistryAccess.
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR
                || m == Material.SHORT_GRASS
                || m == Material.TALL_GRASS
                || m == Material.FERN
                || m == Material.DEAD_BUSH
                || m == Material.DANDELION
                || m == Material.POPPY
                || m == Material.BLUE_ORCHID
                || m == Material.ALLIUM
                || m == Material.AZURE_BLUET
                || m == Material.RED_TULIP
                || m == Material.ORANGE_TULIP
                || m == Material.WHITE_TULIP
                || m == Material.PINK_TULIP
                || m == Material.OXEYE_DAISY
                || m == Material.RED_MUSHROOM
                || m == Material.BROWN_MUSHROOM
                || m == Material.VINE
                || m == Material.MOSS_CARPET
                || m == Material.AZALEA
                || m == Material.FLOWERING_AZALEA
                || m == Material.LEAF_LITTER;
    }
    private static void put(Chunk c,ForestGenerator.TreeSite site,int x,int y,int z,Material mat) {
        if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
        Block b=c.getBlock(x&15,y,z&15);
        if(!replaceable(b.getType())) return;
        b.setType(mat,false);
        if(b.getBlockData() instanceof Leaves) {
            if(b.getBlockData() instanceof Leaves d) {d.setPersistent(true);b.setBlockData(d,false);}
        }
    }
    private static void log(Chunk c,ForestGenerator.TreeSite site,int x,int y,int z,Material mat,Axis axis) {
        if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
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
        if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
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
        if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
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
            if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
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
    /** Small multi-stem trees in the Bloomlands understory. */
    private static void bloomUnderstory(Chunk c, CachedTreeSite site, Object wild,
                                        int cx, int cz, long worldSeed, long treeSeed, int variant) {
        BloomUnderstoryGeometry.Terrain land = new BloomUnderstoryGeometry.Terrain() {
            @Override public int groundAt(int x, int z) { return site.groundAt(x, z); }
            @Override public boolean allows(int x, int z) { return site.bloomlands(wild, x, z, worldSeed); }
        };
        BloomUnderstoryGeometry.grow((x, y, z, state) -> {
            if (!inChunk(c, x, z) || !validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())
                    || !land.allows(x, z)) return;
            Block at = c.getBlock(x & 15, y, z & 15);
            if (!replaceable(at.getType())) return;
            if (state.contains("_log[")) {
                Material wood = state.startsWith("minecraft:spruce_") ? Material.SPRUCE_LOG
                        : state.startsWith("minecraft:birch_") ? Material.BIRCH_LOG : Material.OAK_LOG;
                Axis axis = state.endsWith("axis=x]") ? Axis.X
                        : state.endsWith("axis=z]") ? Axis.Z : Axis.Y;
                log(c, site, x, y, z, wood, axis);
            } else {
                Material leaves = state.startsWith("minecraft:spruce_") ? Material.SPRUCE_LEAVES
                        : state.startsWith("minecraft:birch_") ? Material.BIRCH_LEAVES : Material.OAK_LEAVES;
                put(c, site, x, y, z, leaves);
            }
        }, land, cx, cz, treeSeed, variant);
    }

    /** A loose woodland skirt around each giant mushroom keeps the fungal landmark
     * grounded in a surrounding forest while leaving its immediate stem clear. */
    private static void bloomMushroomWoodland(Chunk c, CachedTreeSite site, Object wild,
                                               int cx, int cz, long seed, long cellHash) {
        for (int i = 0; i < 9; i++) {
            long h = hash(seed, cx * 31L + i, cz * 47L - i, 0x554e444552L);
            double angle = i * 2.399963229728653 + (unit(h) - .5) * .34;
            double distance = 19.0 + unit(h + 1) * 14.0;
            int tx = cx + (int) Math.round(Math.cos(angle) * distance);
            int tz = cz + (int) Math.round(Math.sin(angle) * distance);
            if (!treeNearChunk(c, tx, tz, 9) || !understoryFootprint(site, wild, tx, tz, seed)) continue;
            bloomUnderstory(c, site, wild, tx, tz, seed, mix(h ^ cellHash), (int) (unit(h + 2) * 3));
        }
    }

    private static boolean treeNearChunk(Chunk c, int x, int z, int radius) {
        int x0 = c.getX() << 4, z0 = c.getZ() << 4;
        return x + radius >= x0 && x - radius <= x0 + 15
                && z + radius >= z0 && z - radius <= z0 + 15;
    }

    private static boolean understoryFootprint(CachedTreeSite site, Object wild,
                                                int x, int z, long seed) {
        if (!site.bloomlands(wild, x, z, seed)) return false;
        for (int a = 0; a < 8; a++) {
            double angle = a * Math.PI / 4.0;
            int tx = x + (int) Math.round(Math.cos(angle) * 6.0);
            int tz = z + (int) Math.round(Math.sin(angle) * 6.0);
            if (!site.bloomlands(wild, tx, tz, seed)) return false;
        }
        return true;
    }

    /** Keep scattered understory trunks from touching the basal stalk of a giant mushroom. */
    private static boolean nearGiantMushroom(Object wild, long seed, int x, int z) {
        int gx = Math.floorDiv(x, 72), gz = Math.floorDiv(z, 72);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int mxCell = gx + dx, mzCell = gz + dz;
            if (!decorationCellChance(seed, mxCell, mzCell, 72, 3, .72)) continue;
            int mx = decorationCellX(seed, mxCell, mzCell, 72);
            int mz = decorationCellZ(seed, mxCell, mzCell, 72);
            int ddx = x - mx, ddz = z - mz;
            if (ddx * ddx + ddz * ddz < 15 * 15
                    && hasGiantMushroomFootprint(wild, mx, mz, seed)) return true;
        }
        return false;
    }

    private static void willow(Chunk c,ForestGenerator.TreeSite site,int cx,int cz,long seed) {
        MotherWillowGeometry.growChunk((x,y,z,state)->{
            if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
            // MotherWillowGeometry emits poplar_log/poplar_wood states.
            // Treating only the obsolete oak_log prefix as wood silently
            // converted every trunk and branch voxel into OAK_LEAVES.
            if(MotherWillowBlockState.isWood(state)) {
                Axis axis=state.endsWith("axis=x]")?Axis.X:state.endsWith("axis=z]")?Axis.Z:Axis.Y;
                if(y<=site.groundAt(x,z))willowRoot(c,site,x,y,z,axis);
                else if(MotherWillowBlockState.isBarkWood(state))
                    log(c,site,x,y,z,Material.OAK_WOOD,Axis.Y);
                else log(c,site,x,y,z,Material.OAK_LOG,axis);
            } else if(MotherWillowBlockState.isLeaves(state)) {
                put(c,site,x,y,z,Material.OAK_LEAVES);
            }
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
            if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
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
        // Natural Karst groves may occupy the massif and ravine; there is no
        // artificial bridge crest or bridge-shaped dry-shadow corridor.
        if(KarstLandforms.habitat(seed,cx,cz)<.68 || !site.allows(cx,cz))return;
        long h=hash(seed,cx,cz,0x47524f5645L);
        if(unit(h)<.45)return;
        KarstGroveGeometry.growChunk((x,y,z,state)->{
            if(!inChunk(c,x,z)||!validVegetationY(y,c.getWorld().getMinHeight(),c.getWorld().getMaxHeight())||!site.allows(x,z))return;
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
        if(wild==null || site==null)return;
        CachedTreeSite cachedSite = new CachedTreeSite(site);
        site = cachedSite;
        int bx=chunk.getX()<<4,bz=chunk.getZ()<<4;
        // Each spatial cell owns at most one landmark, and each chunk clips its own voxels.
        for(int spacing:new int[]{72,224,112,48,32,28}) {
            int reach=spacing==72?43:spacing==224?190:spacing==112?52:spacing==48?10:spacing==32?26:20;
            int minCX=Math.floorDiv(bx-reach,spacing),maxCX=Math.floorDiv(bx+15+reach,spacing);
            int minCZ=Math.floorDiv(bz-reach,spacing),maxCZ=Math.floorDiv(bz+15+reach,spacing);
            for(int gx=minCX;gx<=maxCX;gx++)for(int gz=minCZ;gz<=maxCZ;gz++) {
                long h=hash(seed,gx,gz,spacing);
                int x=decorationCellX(seed,gx,gz,spacing);
                int z=decorationCellZ(seed,gx,gz,spacing);
                if(Math.abs(x-(bx+8))>reach+9||Math.abs(z-(bz+8))>reach+9)continue;
                if(!site.allows(x,z))continue;
                WildBiome b=pick(wild,x,z,seed);
                if(spacing==72 && b==WildBiome.BLOOMLANDS
                        && decorationCellChance(seed,gx,gz,spacing,3,.72)) {
                    boolean room=hasGiantMushroomFootprint(wild,x,z,seed);
                    for(int a=0;a<8&&room;a++){
                        double angle=a*Math.PI/4;
                        int tx=x+(int)Math.round(Math.cos(angle)*38),tz=z+(int)Math.round(Math.sin(angle)*38);
                        if(!site.allows(tx,tz))room=false;
                    }
                    if(room) {
                        mushroom(chunk,site,x,z,seed,(int)(unit(h+9)*6));
                        bloomMushroomWoodland(chunk,cachedSite,wild,x,z,seed,h);
                    }
                }
                if(spacing==224 && b==WildBiome.BLOOMLANDS
                        && decorationCellChance(seed,gx,gz,spacing,3,.28)) {
                    boolean room=site.groundAt(x,z)+190<chunk.getWorld().getMaxHeight()-3
                            && hasMotherWillowFootprint(wild,x,z,seed);
                    for(int a=0;a<16&&room;a++){
                        double angle=a*Math.PI/8;
                        int tx=x+(int)Math.round(Math.cos(angle)*190),tz=z+(int)Math.round(Math.sin(angle)*190);
                        if(!site.allows(tx,tz))room=false;
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
                if(spacing==28 && b==WildBiome.BLOOMLANDS && unit(h+3)<.70
                        && treeNearChunk(chunk,x,z,9)
                        && !nearGiantMushroom(wild,seed,x,z)
                        && understoryFootprint(cachedSite,wild,x,z,seed)) {
                    bloomUnderstory(chunk,cachedSite,wild,x,z,seed,mix(seed ^ h),(int)(unit(h+8)*3));
                }
                if(spacing==48 && b==WildBiome.VOLCANIC_BARRENS)
                    karstBoulder(chunk,site,x,z,seed);
                if(spacing==32 && b==WildBiome.VOLCANIC_BARRENS && unit(h+3)<.59)
                    karstGroves(chunk,site,x,z,seed);
            }
        }
    }
}
