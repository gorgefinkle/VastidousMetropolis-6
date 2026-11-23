package ch.k42.metropolis.grid.urbanGrid.config;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.enums.Direction;
import ch.k42.metropolis.grid.urbanGrid.enums.LootType;
import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;
import ch.k42.metropolis.grid.urbanGrid.enums.SchematicType;
import ch.k42.metropolis.minions.DecayOption;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.Minions;
import ch.k42.metropolis.minions.Nimmersatt;
import ch.k42.metropolis.minions.StrictMaterialAdapter;
import ch.k42.metropolis.plugin.PluginConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.Material;

public class SchematicConfig extends AbstractSchematicConfig {
   private int groundLevelY = 0;
   private int oddsOfAppearanceInPercent = 100;
   private LootType[] lootCollections = new LootType[]{LootType.RESIDENTIAL};
   private int lootMinLevel = 1;
   private int lootMaxLevel = 5;
   private boolean rotate = true;
   private boolean roadFacing = true;
   // Opt-in: only ever place this schematic in a rotation that turns its
   // "river side" toward one of the two rivers -- see isRiverFacing() and
   // getRiverFacingSides(). The two rivers (east-west and north-south) split
   // the map into four quarters; in each quarter one river lies to the north
   // or south and the other to the east or west, so exactly two of the four
   // world sides face a river, and a river-facing schematic gets exactly the
   // rotations that put its river side on one of those two.
   private boolean riverFacing = false;
   // Landmarks and creek-site buildings only, and only when generator.landmarkZoneMatch is on: relaxes the district
   // neighbouring rule (see LandmarkZoneRule). A landmark that names zones in its CONFIG.json must stand in one of them AND be
   // bordered by districts that are also zones it names on enough of its four sides. ON BY DEFAULT: "enough" is then TWO sides
   // (LandmarkZoneRule.LAX_SIDES); set "districtTrueLax": false in a CONFIG.json to put that landmark back to THREE
   // (LandmarkZoneRule.MIN_SIDES). Its own centre zone must be one it names either way. Spelled "districttruelax" it works too.
   @SerializedName(value = "districtTrueLax", alternate = {"districttruelax", "DistrictTrueLax", "DISTRICTTRUELAX"})
   private boolean districtTrueLax = true;
   // Ocean depth tier -- gates ocean-based structures (boats, ships) to their
   // required water depth. Null means no depth restriction. For OCEAN context only.
   private String depthTier = null;
   // Optional per-schematic override of the ocean clone radius (chunks): no
   // exact repeat of this build within oceanCloneRadius, and a rising chance of
   // one out to oceanCloneRadiusSoftLimit. 0 = not set, use the placer's rule
   // (generator.cloneRadius, or generator.ocean.boats.bigCloneRadius* for big
   // builds). OCEAN context only.
   private int oceanCloneRadius = 0;
   private int oceanCloneRadiusSoftLimit = 0;
   private int chestOddsInPercent = 50;
   private int spawnerOddsInPercent = 50;
   private int decayIntensityInPercent = 100;
   private AbstractSchematicConfig.RoadCutout[] cutouts = new AbstractSchematicConfig.RoadCutout[0];
   // Per-side cutouts for landmarks -- unlike `cutouts` above, which
   // always applies to whichever single side this.direction ends up
   // pointing at (the one side chosen as road-facing for this
   // particular placement), these four each target one specific LOCAL
   // side of the schematic's own unrotated reference file: cutoutN is
   // the front side, cutoutE the right side, cutoutS the back side,
   // cutoutW the left side -- all as seen standing outside that
   // unrotated file looking at it. This only makes
   // sense for landmarks: they're the only schematic type guaranteed a
   // road on every side, so all four sides can have their own opening.
   //
   // Because each of these is a FIXED side of the source file rather
   // than "whichever side is road-facing", ClipboardParcel.rotate()
   // re-derives which world side each one actually lands on for any
   // given placement (the schematic gets pasted as one of 4 pre-rotated
   // NORTH/EAST/SOUTH/WEST variants depending on this.direction, same
   // as any other building) -- a landmark rotated to face EAST still
   // has cutoutN cutting whatever side was its front before rotation,
   // just now on the EAST side of the world footprint instead of NORTH.
   // A normal building should simply leave these four unset (they
   // default to empty and are then no-ops) and keep using `cutouts`
   // above as before.
   //
   // Counted the exact same way `cutouts` already is: from the far-left
   // block of that face, viewed from outside the building looking at it
   // -- just now once per face instead of once for whichever face
   // happens to be road-facing.
   //
   // No dedup with `cutouts`: cutoutN, specifically, ends up targeting
   // the exact same side `cutouts` already does (both are "the front
   // side", just under two different field names) -- setting both for
   // the same landmark just cuts that side twice (harmless, but
   // pointless). cutoutE/S/W don't overlap `cutouts` at all. Avoid
   // setting cutoutN alongside `cutouts` on the same config rather than
   // relying on this to catch it.
   private AbstractSchematicConfig.RoadCutout[] cutoutN = new AbstractSchematicConfig.RoadCutout[0];
   private AbstractSchematicConfig.RoadCutout[] cutoutE = new AbstractSchematicConfig.RoadCutout[0];
   private AbstractSchematicConfig.RoadCutout[] cutoutS = new AbstractSchematicConfig.RoadCutout[0];
   private AbstractSchematicConfig.RoadCutout[] cutoutW = new AbstractSchematicConfig.RoadCutout[0];
   // Per-side creek origins -- same per-side/rotation convention as
   // cutoutN/E/S/W immediately above (cutoutN is a special case of this
   // same idea: "a fixed point on one specific unrotated side"), but
   // these don't cut anything. Each entry marks a point along that side
   // where UrbanGrid may start tracing a creek outward from this
   // building until it reaches the main river (see
   // ClipboardParcel.getCreekOrigins() and UrbanGrid.placeCreeks()).
   // `length` is the creek's STARTING WIDTH IN BLOCKS, not a span --
   // unlike every RoadCutout field, where length is how much of the wall
   // gets cut. Left empty (the default) on every schematic except the
   // rare few specifically authored with a pond/spring feature meant to
   // feed a creek.
   private AbstractSchematicConfig.RiverStartBound[] riverStartBoundsN = new AbstractSchematicConfig.RiverStartBound[0];
   private AbstractSchematicConfig.RiverStartBound[] riverStartBoundsE = new AbstractSchematicConfig.RiverStartBound[0];
   private AbstractSchematicConfig.RiverStartBound[] riverStartBoundsS = new AbstractSchematicConfig.RiverStartBound[0];
   private AbstractSchematicConfig.RiverStartBound[] riverStartBoundsW = new AbstractSchematicConfig.RiverStartBound[0];
   // Opt-out for ClipboardImporter's mirrored-variant generation (see
   // rotateAndCacheSchematic() -- only runs the flip branch at all when
   // PluginConfig.isFlippedVariantsEnabled() is on). Some buildings have
   // large, readable text/signage baked directly into the block geometry
   // (not a sign block, actual formed-letter block art) that reads
   // backwards and wrong once mirrored -- CONFIG.json can set
   // "neverFlip": true on those specifically so the importer skips
   // generating their NORTH_M/EAST_M/SOUTH_M/WEST_M files entirely, while
   // every other schematic in the library keeps getting mirrored variants
   // as normal.
   private boolean neverFlip = false;
   private ContextType[] context = null;
   private RoadType roadType = RoadType.NONE;
   private Set<Material> decayExceptionMaterials = new HashSet<>();
   private String[] schematics = new String[0];
   private String path;
   // Content hash (MD5) of the CONFIG.json file itself, computed once in
   // fromFile() from the exact same bytes that get parsed. This -- not
   // `path` -- is what getVariantKey() uses: ClipboardImporter caches
   // each schematic FILE listed in one config's "schematics" array into
   // its OWN cache folder keyed by that individual .schematic file's MD5
   // (see rotateAndCacheSchematic()), copying CONFIG.json's bytes
   // verbatim into every one of those folders. That means `path`, when
   // re-derived from a cache folder at runtime, points to a different
   // folder per variant even though they all came from the exact same
   // source config -- useless for grouping. The copied bytes are
   // identical across all of them, though, so hashing content instead of
   // location gives every variant of one source config the same key,
   // while two genuinely different source configs (even ones sharing a
   // buildName, or with no buildName at all) still hash differently.
   private String originConfigHash;
   private SchematicType schematicType = null;
   // Optional tag a CONFIG.json can set to link it to OTHER, separately-
   // authored CONFIG.json files as variants of one house for
   // DENSERESIDENTIAL family-locking purposes (see
   // District.denseResidentialFamilyKey), when they don't already share a
   // buildName prefix that getDenseResidentialFamilyKey()'s auto-grouping
   // would catch on its own (e.g. two unrelated-looking buildNames that
   // are nonetheless meant to be the same house). Not consulted by
   // getVariantKey() itself -- general variant-spacing (clone radius,
   // landmark dedup, etc.) still goes purely by originConfigHash,
   // unaffected by this. Unset (the default) just means this config
   // relies on buildName-prefix auto-grouping instead; see
   // getDenseResidentialFamilyKey().
   private String variantGroup = null;
   // Nearly every CONFIG.json already carries this -- it's the human-facing
   // name of the building, e.g. "lab3x2". Used as the automatic variant
   // key, see getVariantKey().
   private String buildName = null;
   // R30ea registration gate. Explicitly marked landmark builds belong to
   // LANDMARK exclusively even when they have no directional cutouts.
   private boolean landmark = false;

   public SchematicType getSchematicType() {
      return this.schematicType;
   }

   public int getGroundLevelY() {
      return this.groundLevelY;
   }

   public int getLootMaxLevel() {
      return this.lootMaxLevel;
   }

   public int getLootMinLevel() {
      return this.lootMinLevel;
   }

   public String getPath() {
      return this.path;
   }

   public String getBuildName() {
      return this.buildName;
   }

   public boolean isLandmark() {
      return this.landmark;
   }

   public void setPath(String path) {
      this.path = path;
   }

   public String getOriginConfigHash() {
      return this.originConfigHash;
   }

   /**
    * The key District.getWeights()/UrbanGrid.carveLandmark() use to
    * decide whether two candidates are "variants" of each other for
    * spread-out placement: purely whether they share the same
    * originConfigHash, i.e. came from the exact same CONFIG.json content
    * -- see the field comment on originConfigHash for why content-hash
    * rather than buildName or path. Deliberately no other logic: no
    * buildName matching (two separately-authored buildings that happen to
    * share a name, or -- the actual bug this replaced -- one config's
    * "schematics" array listing variants at genuinely different sizes,
    * like a filler family spanning 1x1 through 6x6 under one buildName,
    * used to all collide into a single variant group and suppress each
    * other even though they serve completely different gap sizes), and no
    * variantGroup override for linking otherwise-unrelated configs.
    */
   public String getVariantKey() {
      return this.originConfigHash;
   }

   // Matches a trailing size suffix glued directly onto a buildName with
   // no separator -- optionally one pluralizing "s" (CapeCod's -> "s"),
   // then one or more NxN groups (3x3, 4x3x2, etc.) or a bare trailing
   // number. Used by getDenseResidentialFamilyKey() to strip e.g.
   // "CapeCods3x3"/"CapeCods4x4" and "BrownStone3x1"/"BrownStone5x4" down
   // to the shared "capecod"/"brownstone" they're auto-grouped under.
   private static final Pattern DENSE_FAMILY_SIZE_SUFFIX = Pattern.compile("s?\\d+(x\\d+)*$");

   /**
    * The key District.filterByDenseResidentialFamily()/
    * lockDenseResidentialFamily() use to decide whether two BUILD
    * schematics count as "the same house" for a DENSERESIDENTIAL
    * district's family lock. Three tiers, most specific wins:
    *
    * 1. This config's variantGroup, if it set one -- the explicit,
    *    author-controlled override (e.g. two configs with unrelated
    *    buildNames both setting "variantGroup": "mytag").
    * 2. Otherwise, buildName up to its first underscore, lowercased, with
    *    any directly-glued trailing size suffix stripped off (see
    *    DENSE_FAMILY_SIZE_SUFFIX) -- so "Bungalow", "Bungalow_3wide" and
    *    "Bungalow_4wide" all auto-group under "bungalow" via the
    *    underscore cut, AND "CapeCods3x3" through "CapeCods6x6" plus
    *    "Capecod" all auto-group under "capecod" via the suffix strip,
    *    with no config changes needed either way. "BungalowChasm" (no
    *    underscore, no trailing digits to strip) does NOT auto-group
    *    with the bungalows above -- give it a "Bungalow_chasm"-style
    *    buildName (or an explicit variantGroup) if it should.
    * 3. Otherwise (no buildName at all, or it's nothing but a size
    *    suffix), falls back to getVariantKey() (same CONFIG.json
    *    content), so this config is at least related to itself.
    *
    * Unlike getVariantKey(), this one DOES consult variantGroup/buildName
    * -- deliberately scoped to just this one caller, so ordinary
    * variant-spacing elsewhere (clone radius, landmark dedup) is
    * unaffected either way and still goes purely by originConfigHash.
    */
   public String getDenseResidentialFamilyKey() {
      if (this.variantGroup != null && !this.variantGroup.isEmpty()) {
         return this.variantGroup;
      }

      if (this.buildName != null && !this.buildName.isEmpty()) {
         String leadingWord = this.buildName.split("_", 2)[0].toLowerCase();
         leadingWord = DENSE_FAMILY_SIZE_SUFFIX.matcher(leadingWord).replaceFirst("");
         if (!leadingWord.isEmpty()) {
            return "name:" + leadingWord;
         }
      }

      return this.getVariantKey();
   }

   public int getOddsOfAppearance() {
      return this.oddsOfAppearanceInPercent;
   }

   public LootType[] getLootCollections() {
      return this.lootCollections;
   }

   public LootType getRandomLootCollection(GridRandom random) {
      // Gson turns any lootCollections entry that isn't a LootType constant
      // (e.g. "HIGHRISE"/"FARM", which are context types) into null. Picking
      // one of those used to NPE in the chest naming code, silently leaving
      // the chest unnamed and therefore empty. Only pick from real entries.
      int valid = 0;
      for (LootType t : this.lootCollections) {
         if (t != null) {
            valid++;
         }
      }
      if (valid == 0) {
         return LootType.RESIDENTIAL;
      }
      int pick = random.getRandomInt(valid);
      for (LootType t : this.lootCollections) {
         if (t != null && pick-- == 0) {
            return t;
         }
      }
      return LootType.RESIDENTIAL;
   }

   /**
    * Ocean depth tier for this schematic (SHORE/MID/DEEP), or null if no
    * depth restriction. Only meaningful for OCEAN context schematics.
    */
   public String getDepthTier() {
      return this.depthTier;
   }

   /** Per-schematic ocean clone radius in chunks, or 0 if not set. */
   public int getOceanCloneRadius() {
      return this.oceanCloneRadius;
   }

   /** Per-schematic ocean soft clone limit in chunks, or 0 if not set. */
   public int getOceanCloneRadiusSoftLimit() {
      return this.oceanCloneRadiusSoftLimit;
   }

   public int getChestOdds() {
      return this.chestOddsInPercent;
   }

   public int getSpawnerOdds() {
      return this.spawnerOddsInPercent;
   }

   public DecayOption getDecayOption() {
      double baseIntensity;
      if (PluginConfig.isDecayOverride()) {
         // Hard override: ignore this building's own decayIntensityInPercent
         // entirely -- both destructive and block-transform axes below are
         // then driven straight from their master values, not this base.
         baseIntensity = 1.0;
      } else {
         baseIntensity = this.decayIntensityInPercent / 100.0;
      }

      // Destructive decay (-> air/holes) and block-transform decay (->
      // cobblestone, stairs, moss, still-solid) are now two fully
      // independent axes, each its own master multiplier on top of the
      // same per-building base (or driven directly from the master value
      // alone, when decayOverride is on). Previously a single intensity
      // value drove both, with transform always trailing destruction by a
      // fixed offset.
      double destructIntensity = baseIntensity * (PluginConfig.getMasterDecayIntensity() / 100.0);
      double transformIntensity = baseIntensity * (PluginConfig.getMasterBlockDecayIntensity() / 100.0);
      if (destructIntensity > 1.0) {
         destructIntensity = 1.0;
      } else if (destructIntensity < 0.0) {
         destructIntensity = 0.0;
      }
      if (transformIntensity > 1.0) {
         transformIntensity = 1.0;
      } else if (transformIntensity < 0.0) {
         transformIntensity = 0.0;
      }

      DecayOption option = new DecayOption(destructIntensity, transformIntensity, this.decayExceptionMaterials);

      // Growth (leaves) and vines are both fully independent of decay
      // intensity (destructive OR transform) -- DecayOption always sets
      // leavesScale/leavesdecay/vineScale to a fixed baseline regardless
      // of either intensity value above. Each scaled here by its OWN
      // master multiplier: growth for leaves, vine intensity for vines --
      // vineScale is a genuinely separate baseline from leavesScale now,
      // so vine density no longer silently depends on leaf density either.
      double growthMultiplier = PluginConfig.getMasterGrowthIntensity() / 100.0;
      double vineMultiplier = PluginConfig.getMasterVineIntensity() / 100.0;
      double scaledLeavesScale = clamp01(option.getLeavesScale() * growthMultiplier);
      double scaledLeavesDecay = clamp01(option.getLeavesdecay() * growthMultiplier);
      double scaledVineScale = clamp01(option.getVineScale() * vineMultiplier);
      option.setLeavesScale(scaledLeavesScale);
      option.setLeavesdecay(scaledLeavesDecay);
      option.setVineScale(scaledVineScale);

      return option;
   }

   private static double clamp01(double value) {
      if (value < 0.0) {
         return 0.0;
      } else if (value > 1.0) {
         return 1.0;
      }
      return value;
   }

   public List<ContextType> getContext() {
      return Arrays.asList(this.context);
   }

   public Direction getDirection() {
      return this.rotate ? Direction.NORTH : Direction.NONE;
   }

   public void setGroundLevelY(int groundLevelY) {
      this.groundLevelY = groundLevelY;
   }

   public RoadType getRoadType() {
      return this.roadType;
   }

   public List<String> getSchematics() {
      return Arrays.asList(this.schematics);
   }

   /**
    * True if this schematic should only be placed turned toward a river. See
    * the riverFacing field's comment.
    */
   public boolean isRiverFacing() {
      return this.riverFacing;
   }

   /**
    * True (the default) if this landmark relaxes the district neighbouring rule: two of its four sides, not three, have to
    * border zones it names. A CONFIG.json sets "districtTrueLax": false to opt out. See the districtTrueLax field's comment
    * and LandmarkZoneRule.allowsFootprint().
    */
   public boolean isDistrictTrueLax() {
      return this.districtTrueLax;
   }

   /**
    * The LOCAL sides of this schematic (as seen on the unrotated file, the
    * same convention as cutoutN/E/S/W) that count as its "river side" for
    * riverFacing. A schematic authored with a creek -- any riverStartBounds
    * side set -- faces the river with THOSE sides, so its creek starts
    * pointing at the water it is meant to reach. Anything else faces the
    * river with its front (local NORTH, the side that ends up facing
    * whichever way the building is rotated to face).
    */
   public List<Direction> getRiverFacingSides() {
      java.util.ArrayList<Direction> sides = new java.util.ArrayList<Direction>(4);
      if (this.riverStartBoundsN.length > 0) {
         sides.add(Direction.NORTH);
      }
      if (this.riverStartBoundsE.length > 0) {
         sides.add(Direction.EAST);
      }
      if (this.riverStartBoundsS.length > 0) {
         sides.add(Direction.SOUTH);
      }
      if (this.riverStartBoundsW.length > 0) {
         sides.add(Direction.WEST);
      }
      if (sides.isEmpty()) {
         sides.add(Direction.NORTH);
      }
      return sides;
   }

   public boolean getRoadFacing() {
      return this.roadFacing;
   }

   public AbstractSchematicConfig.RoadCutout[] getCutouts() {
      return this.cutouts;
   }

   public AbstractSchematicConfig.RoadCutout[] getCutoutN() {
      return this.cutoutN;
   }

   public AbstractSchematicConfig.RoadCutout[] getCutoutE() {
      return this.cutoutE;
   }

   public AbstractSchematicConfig.RoadCutout[] getCutoutS() {
      return this.cutoutS;
   }

   public AbstractSchematicConfig.RoadCutout[] getCutoutW() {
      return this.cutoutW;
   }

   public AbstractSchematicConfig.RiverStartBound[] getRiverStartBoundsN() {
      return this.riverStartBoundsN;
   }

   public AbstractSchematicConfig.RiverStartBound[] getRiverStartBoundsE() {
      return this.riverStartBoundsE;
   }

   public AbstractSchematicConfig.RiverStartBound[] getRiverStartBoundsS() {
      return this.riverStartBoundsS;
   }

   public AbstractSchematicConfig.RiverStartBound[] getRiverStartBoundsW() {
      return this.riverStartBoundsW;
   }

   public boolean getNeverFlip() {
      return this.neverFlip;
   }

   public static SchematicConfig fromFile(File file) {
      // Strict about materials: an unknown or pre-1.13 name in
      // decayExceptionMaterials rejects this config (it used to become a
      // null entry and the exception was silently ignored).
      Gson gson = new GsonBuilder().registerTypeAdapter(Material.class, new StrictMaterialAdapter()).create();

      try {
         String json = new String(Files.readAllBytes(file.toPath()));
         json = Nimmersatt.friss(json);
         SchematicConfig config = gson.fromJson(json, SchematicConfig.class);
         config.setPath(file.getPath());
         config.originConfigHash = Minions.getMD5Checksum(file);
         return config;
      } catch (IOException e) {
         Bukkit.getLogger().throwing(SchematicConfig.class.getName(), "Can't load SchematicConfig: " + file.getName(), e);
      } catch (JsonSyntaxException e) {
         // Visible in the normal log: Logger.throwing() is FINER and never shows.
         Minions.w("REJECTED schematic config %s -- its schematic will not be loaded. %s", file.getPath(), e.getMessage());
         Bukkit.getLogger().throwing(SchematicConfig.class.getName(), "Can't parse SchematicConfig: " + file.getName(), e);
      } catch (NoSuchAlgorithmException e) {
         Bukkit.getLogger().throwing(SchematicConfig.class.getName(), "Can't hash SchematicConfig: " + file.getName(), e);
      }

      return null;
   }
}