package ch.k42.metropolis.plugin;

import ch.k42.metropolis.grid.urbanGrid.wild.MountainKind;
import ch.k42.metropolis.grid.urbanGrid.wild.WildBiome;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import ch.k42.metropolis.grid.urbanGrid.river.RiverConfig;
import ch.k42.metropolis.grid.urbanGrid.ocean.OceanConfig;
import ch.k42.metropolis.minions.Minions;
import org.bukkit.configuration.file.FileConfiguration;

public class PluginConfig {

   private PluginConfig() {}

   // -------------------------------------------------------------------------
   // Console output / debug toggles
   // -------------------------------------------------------------------------

   private static final String pathDebug          = "consoleOutput.debug";
   private static final String pathDebugCutouts   = "consoleOutput.debugCutouts";
   private static final String pathDebugAsciiMap  = "consoleOutput.debugAsciiMap";
   private static final String pathDebugDistricts = "consoleOutput.debugDistricts";
   private static final String pathDebugDistrictsVerbose = "consoleOutput.debugDistrictsVerbose";
   private static final String pathDebugDecay     = "consoleOutput.debugDecay";
   private static final String pathDebugBranchNodes = "consoleOutput.debugBranchNodes";
   private static final String pathDebugBranchNodesVerbose = "consoleOutput.debugBranchNodesVerbose";
   private static final String pathDebugFillers = "consoleOutput.debugFillers";
   private static final String pathDebugFillerRolls = "consoleOutput.debugFillerRolls";
   private static final String pathDebugFillerQueries = "consoleOutput.debugFillerQueries";
   private static final String pathDebugFillerCandidates = "consoleOutput.debugFillerCandidates";
   private static final String pathDebugFillerRejections = "consoleOutput.debugFillerRejections";
   private static final String pathDebugFillerSelections = "consoleOutput.debugFillerSelections";
   private static final String pathDebugFillerFallbacks = "consoleOutput.debugFillerFallbacks";
   private static final String pathDebugFillerResults = "consoleOutput.debugFillerResults";
   private static final String pathDebugDenseResidentialFamilies = "consoleOutput.debugDenseResidentialFamilies";
   private static final String pathDebugRiver = "consoleOutput.debugRiver";
   private static final String pathDebugCreekPaintLifecycle = "consoleOutput.debugCreekPaintLifecycle";
   private static final String pathDebugCreekPhysicalPaint = "consoleOutput.debugCreekPhysicalPaint";
   private static final String pathDebugReclaimRoads = "consoleOutput.debugReclaimRoads";
   private static final String pathDebugOcean = "consoleOutput.debugOcean";
   private static final String pathDebugLandmarks = "consoleOutput.debugLandmarks";
   private static final String pathDebugShore = "consoleOutput.debugShore";

   private static boolean debugOutput   = true;
   private static boolean debugCutouts  = false;
   private static boolean debugAsciiMap = false;
   private static boolean debugDistricts = true;
   private static boolean debugDistrictsVerbose = false;
   private static boolean debugDecay    = false;
   private static boolean debugBranchNodes = false;
   // Separate from debugBranchNodes on purpose: the coarse toggle alone
   // already answers "did any branches build, and why did each one
   // succeed/fail" from a handful of lines per generation run. This
   // second toggle additionally turns on the per-candidate/per-attempt
   // tracing (every pool pop, every rejected pairing, every chain-hop
   // attempt) that made branch-nodes.log balloon -- keep it off unless
   // the coarse log doesn't answer the question.
   private static boolean debugBranchNodesVerbose = false;
   private static boolean debugFillers = false;
   private static boolean debugFillerRolls = false;
   private static boolean debugFillerQueries = false;
   private static boolean debugFillerCandidates = false;
   private static boolean debugFillerRejections = true;
   private static boolean debugFillerSelections = false;
   private static boolean debugFillerFallbacks = false;
   private static boolean debugFillerResults = false;
   private static boolean debugDenseResidentialFamilies = false;
   private static boolean debugRiver = false;
   private static boolean debugCreekPaintLifecycle = false;
   private static boolean debugCreekPhysicalPaint = false;
   // Separate from debugRiver -- debugRiver already covers the whole
   // river system (placement, corridor carving, reclaim rects, bridges),
   // which is too much noise when the only question is "why didn't a
   // road wrap this reclaimed district's corner/inside edge". This
   // channel is scoped to JUST reclaimRiverDistricts()'s own road
   // placement: which separator/water-frontage Rects got computed, which
   // cells they actually painted, and -- critically -- which edges were
   // deliberately left unpainted and why (relying on the corridor road,
   // the highway ring, or a neighbouring rect already covering that
   // side). See Minions.dReclaimRoads()'s own javadoc.
   private static boolean debugReclaimRoads = false;
   private static boolean debugOcean = false;
   // Dedicated landmark channel -- see Minions.dLandmark().
   private static boolean debugLandmarks = false;
   // Dedicated shore-relief channel (ForestGenerator.populateShore) -- see Minions.dShore(). Off when the key is missing.
   private static boolean debugShore = false;

   /** General verbose logging. */
   public static boolean isDebugEnabled()       { return debugOutput;    }

   /**
    * Cutout-placement tracing (per cutout, per building). Separate from
    * isDebugEnabled() -- fires very frequently on busy generation runs and
    * would drown out everything else if mixed into the general flag.
    */
   public static boolean isCutoutDebugEnabled()  { return debugCutouts;   }

   /**
    * Writes each tile's parcel map -- one cell per chunk, named like [Hs], [Ht n], [Hc ne], [Ri], [Pf] -- to
    * <plugin>/ascii/tile_X_Z.txt as soon as the tile is built (consoleOutput.debugAsciiMap).
    */
   public static boolean isAsciiMapDebugEnabled() { return debugAsciiMap; }

   /**
    * District partitioning tracing (parcel selection, splits, fallbacks).
    * The single noisiest debug category -- fires many times per district.
    */
   public static boolean isDistrictDebugEnabled() { return debugDistricts; }

   /** Opt-in recursive district trace, separately bounded to avoid log storms. */
   public static boolean isDistrictVerboseDebugEnabled() { return debugDistrictsVerbose; }

   /**
    * Decay/destruction pass tracing (roads, highways, buildings).
    * Fires once per footprint per chunk population.
    */
   public static boolean isDecayDebugEnabled()   { return debugDecay;     }

   /**
    * Branch node-selection tracing -- specifically the point a node is
    * CHOSEN (pool pop, or the synthesized chain-continuation aim), before
    * raytrace or sag ever touch it. Written to its own dedicated file
    * (see Minions.initBranchNodeLog()) rather than the shared console
    * logger, since it's meant to be read in isolation while diagnosing a
    * single generation run, not mixed in with every other debug category.
    */
   /** Additional legacy write-damage tracing uses the existing branch node debug channel. */
   public static boolean isBranchDamageDebugEnabled() { return debugBranchNodes; }
   public static boolean isBranchDamageWritesDebugEnabled() { return debugBranchNodesVerbose; }

   public static boolean isBranchNodeDebugEnabled() { return debugBranchNodes; }
   public static boolean isBranchNodeVerboseDebugEnabled() { return debugBranchNodesVerbose; }

   public static boolean isFillerDebugEnabled() { return debugFillers; }
   public static boolean isFillerRollDebugEnabled() { return debugFillers && debugFillerRolls; }
   public static boolean isFillerQueryDebugEnabled() { return debugFillers && debugFillerQueries; }
   public static boolean isFillerCandidateDebugEnabled() { return debugFillers && debugFillerCandidates; }
   public static boolean isFillerRejectionDebugEnabled() { return debugFillers && debugFillerRejections; }
   public static boolean isFillerSelectionDebugEnabled() { return debugFillers && debugFillerSelections; }
   public static boolean isFillerFallbackDebugEnabled() { return debugFillers && debugFillerFallbacks; }
   public static boolean isFillerResultDebugEnabled() { return debugFillers && debugFillerResults; }
   public static boolean isDenseResidentialFamilyDebugEnabled() { return debugDenseResidentialFamilies; }

   /**
    * Dedicated river-placement debug channel. Separate from every other
    * debug category (same reasoning as dCutout/dDistrict/dFiller above)
    * so river geometry can be traced -- where the seed placed the river's
    * baseline, whether a given tile's chunk range comes anywhere near it,
    * how tryPlaceRiverBand() resolved each rectangle, which chunks
    * actually resolved to RIVER -- without also turning on the much
    * noisier partition/filler tracing.
    */
   public static boolean isRiverDebugEnabled() { return debugRiver; }
   public static boolean isCreekPaintLifecycleDebugEnabled() { return debugCreekPaintLifecycle; }
   public static boolean isCreekPhysicalPaintDebugEnabled() { return debugCreekPhysicalPaint; }
   public static boolean isReclaimRoadsDebugEnabled() { return debugReclaimRoads; }
   public static boolean isOceanDebugEnabled() { return debugOcean; }
   public static boolean isLandmarkDebugEnabled() { return debugLandmarks; }
   public static boolean isShoreDebugEnabled() { return debugShore; }

   // -------------------------------------------------------------------------
   // Feature toggles
   // -------------------------------------------------------------------------

   private static final String pathChestRenaming          = "generator.enableChestRenaming";
   private static final String pathSpawnerPlacing         = "generator.enableSpawnerPlacing";
   private static final String pathCrossContextPlacing    = "generator.enableCrossContextPlacing";
   private static final String pathDirectionFallbackPlacing = "generator.enableDirectionFallbackPlacing";
   private static final String pathNoLaggRelighting       = "generator.noLaggRelighting";

   private static boolean chestRenaming          = true;
   private static boolean spawnerPlacing         = true;
   private static boolean crossContextPlacing    = false;
   private static boolean directionFallbackPlacing = false;
   private static boolean noLaggRelighting       = false;

   public static boolean isChestRenaming()             { return chestRenaming;           }
   public static boolean isSpawnerPlacing()            { return spawnerPlacing;          }
   public static boolean allowDirectionFallbackPlacing() { return directionFallbackPlacing; }
   public static boolean getNoLaggRelighting()         { return noLaggRelighting;        }

   private static final String pathEnableFlippedVariants = "generator.enableFlippedVariants";
   private static boolean enableFlippedVariants = false;

   /**
    * When true, ClipboardImporter.rotateAndCacheSchematic() also generates
    * a mirrored ("_M") counterpart of each of the 4 rotations, doubling
    * variety per schematic. Off by default -- the mirrored variants have
    * shown up in-game with the unflipped structure's blocks still present
    * underneath the flipped one, merged into a single paste. The toggle
    * exists so this can stay off in a live build while the underlying
    * cause is still being tracked down.
    */
   public static boolean isFlippedVariantsEnabled() { return enableFlippedVariants; }

   private static final String pathFlippedOnlyForTesting = "generator.flippedOnlyForTesting";
   private static boolean flippedOnlyForTesting = false;

   /**
    * Testing aid, not for live use: when true, ClipboardLoaderMkII.loadBuilds()
    * skips adding the 4 plain-rotation clips entirely and only registers the
    * "_M" mirrored ones, so every building placed during generation is a
    * mirrored variant. Makes it possible to eyeball mirrored placement in
    * isolation instead of it being diluted 1-in-8 (or absent) among the
    * plain rotations. Requires isFlippedVariantsEnabled() to have been on
    * at the time schematics were last imported -- if no "_M" files exist in
    * a building's cache folder, that building simply won't have any clips
    * to place at all with this on, which will surface as empty/filler
    * parcels rather than an error. Has no effect on the STREET.schematic
    * path (loadStreet()), which never had rotation/mirroring to begin
    * with. Turn back off before running for real.
    */
   public static boolean isFlippedOnlyForTesting() { return flippedOnlyForTesting; }

   // -------------------------------------------------------------------------
   // District / generation parameters
   // -------------------------------------------------------------------------

   private static final String pathBuildChance   = "generator.buildChance";
   private static final String pathFillerChance  = "generator.fillerChance";
   private static final String pathIterations    = "generator.iterations";
   private static final String pathDynamicIterations     = "generator.dynamicIterations";
   private static final String pathDynamicTargetCoverage = "generator.dynamicTargetCoverage";
   private static final String pathDynamicMaxIterations  = "generator.dynamicMaxIterations";
   private static final String pathBlockSize     = "generator.blockSize";
   private static final String pathBlockSizeMin  = "generator.blockSizeMin";
   private static final String pathBlockSizeSlack = "generator.blockSizeSlack";
   private static final String pathDistrictMergeBackRatio = "generator.districtMergeBackRatio";
   private static final String pathSigmaCut      = "generator.sigmaCut";
   private static final String pathCloneRadius   = "generator.cloneRadius";
   private static final String pathBuildHeight   = "generator.buildHeight";
   private static final String pathCaveChance    = "generator.caveChance";
   private static final String pathVariantRadius = "generator.variantRadius";
   private static final String pathVariantPenalty = "generator.variantPenalty";
   private static final String pathVariantHardBlock = "generator.variantHardBlock";
   private static final String pathVariantUniquePerDistrict = "generator.variantUniquePerDistrict";
   private static final String pathCloneRadiusSoftLimit = "generator.cloneRadiusSoftLimit";
   private static final String pathClonePenalty = "generator.clonePenalty";
   private static final String pathFillerExemptFromCloneRadius = "generator.fillerExemptFromCloneRadius";
   private static final String pathFillerLargestAreaTolerancePercent = "generator.fillerLargestAreaTolerancePercent";
   private static final String pathAllowPureResidentialBuildAsFiller = "generator.allowPureResidentialBuildAsFiller";
   private static final String pathEnableDenseResidential = "generator.enableDenseResidential";
   private static final String pathDistrictNeighborRules = "generator.districtNeighborRules";
   private static final String pathDistrictNeighborRulesCrossTile = "generator.districtNeighborRulesCrossTile";
   private static final String pathDistrictNeighborRulesRoadGap = "generator.districtNeighborRulesRoadGap";
   private static final String pathDenseResidentialCloneRadiusSoftLimit = "generator.denseResidentialCloneRadiusSoftLimit";
   private static final String pathDenseResidentialClonePenalty = "generator.denseResidentialClonePenalty";
   private static final String pathDenseResidentialBuildChance = "generator.denseResidentialBuildChance";
   private static final String pathDenseResidentialRoadBuildChance = "generator.denseResidentialRoadBuildChance";
   private static final String pathDenseResidentialBlockSize = "generator.denseResidentialBlockSize";
   private static final String pathDenseResidentialBlockSizeMin = "generator.denseResidentialBlockSizeMin";
   private static final String pathNormalizeOddsOfAppearance = "generator.normalizeOddsOfAppearance";
   private static final String pathFillerTerrainFallback = "generator.enableFillerTerrainFallback";
   private static final String pathMorphomos = "generator.morphomos";
   private static final String pathForestGenerate = "generator.forest.generate";
   private static final String pathForestReliefAmplitude = "generator.forest.reliefAmplitude";
   private static final String pathForestReliefBias = "generator.forest.reliefBias";
   private static final String pathForestEdgeBlend = "generator.forest.edgeBlend";
   private static final String pathForestMountains = "generator.forest.mountains.enabled";
   private static final String pathForestMountainMinCells = "generator.forest.mountains.minCells";
   private static final String pathForestMountainMinDepth = "generator.forest.mountains.minDepth";
   private static final String pathForestMountainHeight = "generator.forest.mountains.height";
   private static final String pathForestMountainRamp = "generator.forest.mountains.rampBlocks";
   private static final String pathForestMountainTreeLine = "generator.forest.mountains.treeLine";
   private static final String pathForestMountainSnowLine = "generator.forest.mountains.snowLine";
   private static final String pathForestMountainLowlands = "generator.forest.mountains.lowlands";
   private static final String pathWildBiomes = "generator.wilderness.biomes";
   private static final String pathWildHardBorders = "generator.wilderness.hardBorders";
   private static final String pathWildScale = "generator.wilderness.scaleChunks";
   private static final String pathWildZoomLevels = "generator.wilderness.zoomLevels";
   private static final String pathWildTransitionPasses = "generator.wilderness.transitionPasses";
   private static final String pathWildWarpPercent = "generator.wilderness.borderWarpPercent";
   private static final String pathWildBlend = "generator.wilderness.blend";
   private static final String pathWildMesaDesertMerge = "generator.wilderness.mesaDesertMerge";
   private static final String pathWildMesaFoot = "generator.wilderness.mesaFoot";
   private static final String pathWildSmoothBlocks = "generator.wilderness.smoothBlocks";
   private static final String pathWildDecorations = "generator.wilderness.decorations";
   private static final String pathWildSpacing = "generator.wilderness.spacing.";
   /** The kinds of wilderness piece in WildDecor.Prop order, as they are named under generator.wilderness.spacing. */
   private static final String[] wildSpacingNames = {"planes", "helicopters", "camps", "mines", "windFarms", "masts", "boulders", "hoodoos"};
   private static final String pathWildChestOdds = "generator.wilderness.chestOddsInPercent";
   private static final String pathWildLootMin = "generator.wilderness.lootMinLevel";
   private static final String pathWildLootMax = "generator.wilderness.lootMaxLevel";
   private static final String pathMountainKindPrefix = "generator.forest.mountains.kinds.";
   // Newer wilderness habitats use the stable WildBiome.ALL ordinal order.
   private static final String pathWildSharePrefix = "generator.wilderness.share.";
   private static final String pathWildAwayPrefix = "generator.wilderness.keepAway.";
   private static final String pathWildTransitions = "generator.wilderness.transitions";
   private static final String[] DEFAULT_WILD_TRANSITIONS = {
      "forest>desert=dryForest", "forest>mesa=dryForest",
      "dryForest>mesa=desert", "rainforest>desert=dryForest",
      "rainforest>mesa=dryForest"
   };
   private static final String pathWildAwayMerge = "generator.wilderness.keepAwayMerge";
   private static final String pathWildAwayVariance = "generator.wilderness.keepAwayVariance";
   private static final String pathWildMesaStep = "generator.wilderness.mesaTerraceHeight";
   private static final String pathWildDuneScale = "generator.wilderness.duneScale";
   private static final String pathWildMesaLevels = "generator.wilderness.mesaTerraces";
   private static final String pathWildVanilla = "generator.wilderness.vanilla.enabled";
   private static final String pathWildVanillaBlend = "generator.wilderness.vanilla.blendChunks";
   private static final String pathWildVanillaVerge = "generator.wilderness.vanilla.verge";
   private static final String pathWildVanillaStructures = "generator.wilderness.vanilla.structures";
   private static final String pathWildVanillaWorld = "generator.wilderness.vanilla.world";
   private static final String pathWildVanillaOceans = "generator.wilderness.vanilla.allowOceans";
   private static final String pathForestTypeScale = "generator.forest.typeScale";
   private static final String pathForestTreeDensity = "generator.forest.treeDensity";
   private static final String pathForestSurfaceOffset = "generator.forest.surfaceOffset";
   private static final String pathFarmGenerate = "generator.farm.generate";
   private static final String pathFarmRoadLinePercent = "generator.farm.roadLinePercent";
   private static final String pathFarmRuinPercent = "generator.farm.ruinPercent";
   private static final String pathFarmFenceCrops = "generator.farm.fenceCrops";
   private static final String pathFarmSurfaceOffset = "generator.farm.surfaceOffset";
   private static final String pathFarmFarmsteadPercent = "generator.farm.farmsteadPercent";
   private static final String pathFarmPastureShare = "generator.farm.pastureShare";
   private static final String pathFarmModernBarn = "generator.farm.modernBarnPercent";
   private static final String pathFarmGreenhouse = "generator.farm.greenhousePercent";
   private static final String pathFarmOrchardShare = "generator.farm.orchardShare";
   private static final String pathFarmRoundBarn = "generator.farm.roundBarnPercent";
   private static final String pathFarmOutbuildings = "generator.farm.outbuildingPercent";
   private static final String pathFarmTunnelSkin = "generator.farm.tunnelSkin";
   private static final String pathFarmChestOdds = "generator.farm.chestOddsInPercent";
   private static final String pathFarmLootMinLevel = "generator.farm.lootMinLevel";
   private static final String pathFarmLootMaxLevel = "generator.farm.lootMaxLevel";
   private static final String pathCityRings = "generator.cityRings.enabled";
   private static final String pathCityRingsRadius = "generator.cityRings.radius";
   private static final String pathCityRingsSpacing = "generator.cityRings.spacing";
   private static final String pathCityRingsSizeVariation = "generator.cityRings.sizeVariation";
   private static final String pathCityRingsEdgeWobble = "generator.cityRings.edgeWobble";
   private static final String pathCityRingsFarmBandWidth = "generator.cityRings.farmBandWidth";
   private static final String pathCityRingsFarmForestOverlap = "generator.cityRings.farmForestOverlap";
   private static final String pathHighwayNetwork = "generator.highwayNetwork.enabled";
   private static final String pathHighwayNetworkBoxScale = "generator.highwayNetwork.boxScale";
   private static final String pathHighwayNetworkExtraLinks = "generator.highwayNetwork.extraLinkPercent";
   private static final String pathHighwayNetworkOceanMarginMin = "generator.highwayNetwork.oceanMarginMin";
   private static final String pathHighwayNetworkOceanMarginMax = "generator.highwayNetwork.oceanMarginMax";
   private static final String pathHighwayNetworkSingleSeamStreet = "generator.highwayNetwork.singleSeamStreet";
   private static final String pathFillerTerrainNoiseIntensity = "generator.fillerTerrainNoiseIntensity";
   private static final String pathFillerTerrainMaxDeviation = "generator.fillerTerrainMaxDeviation";
   private static final String pathFillerTerrainStoneDepth = "generator.fillerTerrainStoneDepth";
   private static final String pathMaxCachedGridTiles = "generator.maxCachedGridTiles";
   private static final String pathExtraCutMode           = "generator.extraCutMode";
   private static final String pathExtraCutFillerThreshold = "generator.extraCutFillerThreshold";
   private static final String pathExtraCutBlockSize      = "generator.extraCutBlockSize";

   private static int buildChance    = 90;
   private static int fillerChance   = 15;
   private static int iterations     = 3;
   private static boolean dynamicIterations     = false;
   private static int     dynamicTargetCoverage = 80;
   private static int     dynamicMaxIterations  = 40;
   private static int blockSize      = 16;
   private static int blockSizeMin   = 9;
   private static int blockSizeSlack = 3;
   // Minimum allowed ratio of (skinny side : untouched side) before
   // UrbanGrid.wouldLeaveSkinnyDistrict() abandons a split rather than
   // carve off a long thin strip. 0 disables the check.
   private static double districtMergeBackRatio = 0.4;
   private static int sigmaCut       = 3;
   private static int cloneRadius    = 20;
   private static int buildHeight    = 63;
   private static int caveChance     = 20;
   private static int variantRadius  = 17;
   private static int variantPenalty = 60;
   private static boolean variantHardBlock = true;
   private static boolean variantUniquePerDistrict = true;
   private static int cloneRadiusSoftLimit = 45;
   private static int clonePenalty   = 20;
   private static boolean fillerExemptFromCloneRadius = true;
   private static int     fillerLargestAreaTolerancePercent = 50;
   private static boolean allowPureResidentialBuildAsFiller = false;
   private static boolean enableDenseResidential = true;
   private static boolean districtNeighborRules = true;
   private static boolean morphomos = true;
   private static boolean forestGenerate = true;
   private static double forestReliefAmplitude = 10.0;
   private static double forestReliefBias = 0.35;
   private static int forestEdgeBlend = 40;
   private static boolean forestMountains = false;
   private static int forestMountainMinCells = 300;
   private static int forestMountainMinDepth = 8;
   private static int forestMountainHeight = 90;
   private static int forestMountainRamp = 112;
   private static int forestMountainTreeLine = 45;
   private static int forestMountainSnowLine = 75;
   private static int forestMountainLowlands = 30;
   private static boolean wildBiomes = true;
   private static boolean wildHardBorders = true;
   private static int wildScale = 520;
   private static int wildZoomLevels = 4;
   private static int wildTransitionPasses = 1;
   private static int wildWarpPercent = 35;
   private static int wildBlend = 15;
   private static int wildMesaDesertMerge = 48;
   private static int wildMesaFoot = 60;
   private static int wildSmoothBlocks = 192;
   private static int wildDecorations = 100;
   private static int wildSpacingAll = 100;
   private static final int[] wildSpacing = {100, 100, 100, 100, 100, 100, 100, 100};
   private static int wildChestOdds = 75;
   private static int wildLootMin = 1;
   private static int wildLootMax = 4;
   private static int[] mountainKindShares = mountainKindDefaults();
   private static int[] wildShares = wildDefaults(true);
   private static int[] wildAways = wildDefaults(false);
   private static int wildWetlandReach = 12;
   private static int wildDuneReach = 420;
   private static int wildMinimumRegionBlocks = 480;
   private static int wildWetlandCoreStrength = 86;
   private static int wildDuneCoreStrength = 86;
   private static int wildVolcanicCoreStrength = 88;
   private static String[] wildTransitions = DEFAULT_WILD_TRANSITIONS;
   private static int wildAwayMerge = 16;
   private static int wildDuneScale = 200;
   private static int wildAwayVariance = 6;
   private static int wildMesaStep = 7;
   private static int wildMesaLevels = 4;
   private static boolean wildVanilla = false;
   private static int wildVanillaBlend = 2;
   private static int wildVanillaVerge = 6;
   private static boolean wildVanillaStructures = false;
   private static String wildVanillaWorld = "";
   private static boolean wildVanillaOceans = false;
   private static int forestTypeScale = 140;
   private static double forestTreeDensity = 1.0;
   private static int forestSurfaceOffset = 0;
   private static boolean farmGenerate = true;
   private static int farmRuinPercent = 65;
   private static boolean farmFenceCrops = true;
   private static int farmRoadLinePercent = 35;
   private static int farmSurfaceOffset = -2;   // with buildHeight 65 this is y=63, the paved road deck
   private static int farmFarmsteadPercent = 65;
   private static int farmPastureShare = 28;
   private static int farmModernBarn = 15;
   private static int farmGreenhouse = 38;
   private static int farmOrchardShare = 24;
   private static int farmRoundBarn = 8;
   private static int farmOutbuildings = 75;
   private static String farmTunnelSkin = "glass";
   private static int farmChestOdds = 90;
   private static int farmLootMinLevel = 1;
   private static int farmLootMaxLevel = 3;
   private static boolean cityRings = true;
   private static int cityRingsRadius = 68;
   private static int cityRingsSpacing = 230;
   private static int cityRingsSizeVariation = 50;
   private static int cityRingsEdgeWobble = 40;
   private static int cityRingsFarmBandWidth = 36;
   private static int cityRingsFarmForestOverlap = 27;
   private static boolean highwayNetwork = true;
   private static int highwayNetworkBoxScale = 100;
   private static int highwayNetworkExtraLinks = 0;
   private static int highwayNetworkOceanMarginMin = 8;
   private static int highwayNetworkOceanMarginMax = 24;
   private static boolean highwayNetworkSingleSeamStreet = true;
   private static boolean districtNeighborRulesCrossTile = true;
   private static int districtNeighborRulesRoadGap = 2;
   private static int denseResidentialCloneRadiusSoftLimit = 0;
   private static int denseResidentialClonePenalty = 80;
   private static int denseResidentialBuildChance = 100;
   private static int denseResidentialRoadBuildChance = 100;
   // 0 disables the DENSERESIDENTIAL-specific district-size cap.
   private static int denseResidentialBlockSize = 8;
   // District.getParcelMinSize()'s floor for DENSERESIDENTIAL specifically
   // -- see getDenseResidentialBlockSizeMin()'s javadoc for why this is a
   // separate key from blockSizeMin rather than sharing it.
   private static int denseResidentialBlockSizeMin = 6;
   private static boolean normalizeOddsOfAppearance = false;
   private static boolean fillerTerrainFallback = false;
   private static int fillerTerrainNoiseIntensity = 3;
   private static int fillerTerrainMaxDeviation = 12;
   private static int fillerTerrainStoneDepth = 6;
   private static int maxCachedGridTiles = 1000;
   private static boolean extraCutMode            = false;
   private static int     extraCutFillerThreshold = 50;
   private static int     extraCutBlockSize       = 14;

   public static int getBuildChance()   { return buildChance;   }
   public static int getFillerChance()  { return fillerChance;  }
   public static int getIterations()    { return iterations;    }
   public static boolean isDynamicIterationsEnabled() { return dynamicIterations; }
   /** Percent (1-100) of a district's area that BUILD parcels must cover before dynamic iterations stop. */
   public static int getDynamicTargetCoverage()       { return dynamicTargetCoverage; }
   public static int getDynamicMaxIterations()        { return dynamicMaxIterations; }
   public static int getBlockSize()     { return blockSize;     }

   /**
    * Lower limit on district size produced by ordinary blockSize-based
    * carving (see UrbanGrid.recSetDistricts()). blockSize alone only caps
    * how big a district can be before it gets split again; nothing stops
    * that split itself from leaving a sliver behind (a size-14 region can
    * legally cut into a 1x14 and a 12x14, say). blockSizeMin keeps both
    * sides of every such split at or above this value, so carving stops
    * (leaving an oversized leaf district) rather than producing anything
    * smaller. 1 (the default) reproduces the old behaviour exactly, since
    * makeCut() already guarantees at least 1 unit on each side. Doesn't
    * affect extraCutMode's re-cut path (see extraCutBlockSize) or
    * schematic-placement partitioning within a district -- both are
    * separate splitting mechanisms with their own floors.
    */
   public static int getBlockSizeMin()  { return blockSizeMin;  }

   /**
    * Flexible blocks. 0 (the default) keeps every block between blockSizeMin and blockSize, and a chunk no such block can use
    * is closed as ground. Above 0, a domain that would have to close chunks is solved again with the limits relaxed by 1,
    * then 2, up to this many chunks (blocks may then be that much smaller than blockSizeMin or larger than blockSize), and
    * the first relaxation that leaves nothing closed is kept: blocks vary by as little as it takes, so there are no pockets.
    */
   public static int getBlockSizeSlack() { return blockSizeSlack; }

   /**
    * UrbanGrid.wouldLeaveSkinnyDistrict()'s aspect-ratio floor: a split is
    * only allowed if BOTH children end up at least this fraction of the
    * untouched axis's length on the axis being cut. blockSizeMin alone
    * doesn't catch this -- it only ever checks the axis actually being
    * split, so a region that's long on the other axis can still get a
    * blockSizeMin-legal split that carves off a long, narrow strip (see
    * that method's javadoc for the full walkthrough). Set to 0 to disable
    * and go back to blockSizeMin being the only constraint on a split, as
    * before this existed. Default 0.4 means a would-be child narrower
    * than 40% of the untouched axis gets merged back into an unsplit
    * district instead of carved off.
    */
   public static double getDistrictMergeBackRatio() { return districtMergeBackRatio; }
   public static int getSigmaCut()      { return sigmaCut;      }
   public static int getCloneRadius()   { return cloneRadius;   }
   public static int getBuildHeight()   { return buildHeight;   }
   public static int getCaveChance()    { return caveChance;    }

   /**
    * Radius (in chunks) within which variant schematics sharing the same
    * config are penalised in selection weight. Soft penalty -- makes nearby
    * variants less likely but never prevents them. 0 = disabled.
    */
   public static int getVariantRadius()  { return variantRadius;  }

   /**
    * How aggressively each nearby variant divides the selection weight.
    * Weight = max(1, base / (1 + count * variantPenalty)).
    */
   public static int getVariantPenalty() { return variantPenalty; }

   /**
    * variantPenalty above is always soft: it can only shift selection
    * weight *between* candidates competing for the same exact
    * size/context/direction slot. When every candidate in that slot
    * shares the same variantKey -- e.g. a BUILD config is the only one
    * in the library at that footprint size for its context, so every
    * candidate getFit() returns is one of its own rotations/variant
    * files -- the penalty divides all of them by roughly the same
    * factor and changes nothing about which one gets picked. The
    * building still gets placed every time that slot recurs, no matter
    * how high variantPenalty is set, because there's no competing
    * variantKey for weight to shift toward.
    *
    * When this is true, District.placeRandomRec() hard-rejects a BUILD
    * candidate whose variantKey is already present among its
    * variantNeighbours (the same set/radius variantPenalty already
    * uses), stripping every clip sharing that variantKey from
    * consideration for this slot -- the same all-variants-at-once
    * stripping placeRandomRec() already does for exact groupId
    * duplicates, just keyed on variantKey instead. If that empties the
    * candidate list, this slot's BUILD attempt fails and falls through
    * to FILLER (or a smaller split), the same way it would if no BUILD
    * schematic fit here at all -- giving a size/context niche with only
    * one occupant an actual way to NOT place that occupant right next
    * to itself, rather than only being able to ask nicely via a penalty
    * that has nothing to compete against. Off by default: a niche with
    * only one occupant may be exactly what you want rendered densely
    * (e.g. a distinctive landmark-like BUILD that should dominate its
    * size class), and this makes that impossible within variantRadius.
    */
   public static boolean isVariantHardBlockEnabled() { return variantHardBlock; }

   /**
    * A different, independent shape of variant restriction from
    * variantHardBlock above: instead of a radius (variantRadius) around
    * the candidate, this looks at every parcel already placed earlier in
    * the SAME district-fill pass (District's `previous` set -- the
    * parcels this one district has placed so far while partitioning
    * itself, regardless of how far apart they ended up within it) and
    * rejects a BUILD candidate whose variantKey matches any of them,
    * full stop -- no distance falloff, no radius to tune, just "this
    * district instance has already used this variant family once, don't
    * use it again anywhere else in this same district." Two neighbouring
    * DISTRICTS can still each place their own copy of the same building
    * right at their shared border, since each district's `previous` set
    * only ever contains its own parcels -- this deliberately doesn't
    * reach across district boundaries the way variantHardBlock's
    * radius-based grid lookup does.
    *
    * Independent of variantHardBlock: either, both, or neither can be
    * enabled at once. If a rejection would apply under both, only one
    * strip happens (they strip the same variantKey from the same
    * candidate list), so enabling both isn't harmful, just redundant
    * outside districts near the edge of variantHardBlock's radius.
    */
   public static boolean isVariantUniquePerDistrictEnabled() { return variantUniquePerDistrict; }

   /**
    * cloneRadius (getCloneRadius()) is always a hard limit -- an exact
    * duplicate (same groupId) is never placed within it, no matter how
    * penalties are tuned. This adds an OPTIONAL wider radius, in the same
    * spirit as variantRadius extending beyond cloneRadius: when
    * cloneRadiusSoftLimit is configured larger than cloneRadius, the ring
    * between them gets a distance-weighted soft penalty (via clonePenalty)
    * instead of being left at full odds the instant you step outside the
    * hard zone -- so discouragement tapers off gradually rather than
    * cutting off sharply at the hard boundary. If cloneRadiusSoftLimit is 0
    * (default) or <= cloneRadius, it has no effect: the hard limit alone
    * governs, exactly like the original pre-toggle behaviour.
    */
   public static int getCloneRadiusSoftLimit() { return cloneRadiusSoftLimit; }

   /**
    * Only meaningful when cloneRadiusSoftLimit > cloneRadius. How
    * aggressively each nearby exact duplicate (same groupId) found within
    * cloneRadiusSoftLimit divides the selection weight, using the same
    * distance-decay formula as variantPenalty. Kept as a separate value
    * from variantPenalty since an exact duplicate (identical schematic
    * file) usually warrants stronger discouragement than a mere
    * same-family variant, and the two need to be tunable independently.
    */
   public static int getClonePenalty() { return clonePenalty; }

   /**
    * When true, FILLER schematics are completely exempt from the hard
    * cloneRadius duplicate block that BUILD schematics are always subject
    * to (see the hard-block check in District.placeRandomRec()). Fillers
    * are small, generic, tileable infill pieces (parking lots, plazas,
    * parks) that are normally fine to repeat nearby, unlike a unique
    * BUILD schematic. Without this, a cloneRadius set large relative to
    * blockSize (a smaller blockSize packs many more placement attempts
    * into the same radius window) combined with a filler library much
    * smaller than the build library can exhaust every available filler
    * groupId in a neighbourhood via repeated hard-rejection -- filler
    * fails for the same reason BUILD failed, and if
    * isFillerTerrainFallbackEnabled() is off, nothing gets placed at all,
    * leaving the raw pregenerated world (often a flat stone plateau)
    * showing through untouched. Off by default, preserving strict
    * duplicate-prevention for anyone relying on it; recommended on if
    * you're seeing unplaced/blank areas that scale with cloneRadius.
    */
   public static boolean isFillerExemptFromCloneRadius() { return fillerExemptFromCloneRadius; }

   /**
    * How far below the single largest fitting filler footprint (by chunk
    * area, as a % of that largest area) another candidate is still allowed
    * to compete for selection in placeLargestFiller() -- e.g. 50 means a
    * candidate covering at least half the area of the biggest one that
    * fits still qualifies, not just an exact tie for biggest. 0 reproduces
    * the original "largest footprint only" behaviour: every candidate not
    * exactly tied for largestArea is excluded before clonePenalty/
    * variantPenalty/normalizeOddsOfAppearance ever get a say, which is why
    * large open areas could keep picking the same one schematic no matter
    * how those were tuned -- widening this pool is what actually fixes
    * that, not the penalty/weighting settings.
    */
   public static int getFillerLargestAreaTolerancePercent() { return fillerLargestAreaTolerancePercent; }

   public static boolean isPureResidentialBuildAsFillerEnabled() { return allowPureResidentialBuildAsFiller; }

   /**
    * Master toggle for the DENSERESIDENTIAL context (a tight, repetitive
    * suburban-sprawl variant of RESIDENTIAL -- see District's CLONERADIUS
    * resolution). When false, DENSERESIDENTIAL is stripped out of the
    * context zone tree entirely before ContextProviderSimplex ever runs
    * its noise-based lookup (see ContextProviderSimplex's constructor) --
    * not merely "unlikely", but structurally impossible for
    * getContext() to ever return, regardless of what weight contexts.json
    * gives it. Any sibling zone(s) it would have shared a weight-split
    * with (e.g. plain RESIDENTIAL) simply reclaim that entire share, so
    * turning this off is equivalent to contexts.json never having listed
    * DENSERESIDENTIAL in the first place.
    */
   public static boolean isDenseResidentialEnabled() { return enableDenseResidential; }

   /**
    * Neighbor-aware district zoning (see DistrictContextAssigner). When on,
    * every district's context is assigned up front, per tile, so that two
    * touching districts are always on the same rung of the ladder or
    * directly next to it: HIGHRISE -> MIDRISE -> LOWRISE/INDUSTRIAL ->
    * RESIDENTIAL -> FOREST/FARM. contexts.json weights are read as roles
    * (rung share, then member share within a rung). When off, every district
    * samples the plain noise lookup at its own base, as before, and FOREST
    * and FARM are stripped from that lookup.
    */
   public static boolean isDistrictNeighborRulesEnabled() { return districtNeighborRules; }

   /**
    * Whether a tile also honours the rungs already recorded by neighbouring
    * tiles that have been generated before it (read-only, via peekGrid --
    * it never builds a neighbour). Off means each tile is solved entirely
    * on its own: deterministic no matter what order tiles are generated or
    * evicted in, but tile seams can then break the ladder.
    */
   public static boolean isDistrictNeighborRulesCrossTile() { return districtNeighborRulesCrossTile; }

   /**
    * Widest gap, in chunks, between two districts that still counts as
    * "touching". 2 covers an ordinary 1-chunk road and the 2-chunk seam
    * between two tiles' outer road rows.
    */
   public static int getDistrictNeighborRulesRoadGap() { return districtNeighborRulesRoadGap; }

   /**
    * DENSERESIDENTIAL's own soft clone radius, in the same spirit as
    * cloneRadiusSoftLimit above but read from a completely separate
    * config key -- DENSERESIDENTIAL never consults cloneRadiusSoftLimit
    * (or cloneRadius itself; see District.fillDistrict()) no matter what
    * either is set to, and vice versa: this value has no effect on any
    * other context. 0 (default) disables it, same convention as
    * cloneRadiusSoftLimit -- with no hard cloneRadius of its own either
    * (District always resolves CLONERADIUS to 0 for this context), that
    * means a DENSERESIDENTIAL district with this at 0 has NO
    * duplicate-discouragement at all, letting the same house repeat as
    * freely as a real tract-housing development would. Set this above 0
    * to taper that back in as a soft, distance-decayed preference for
    * variety instead, without ever hard-blocking a repeat the way the
    * default districts' cloneRadius does.
    */
   public static int getDenseResidentialCloneRadiusSoftLimit() { return denseResidentialCloneRadiusSoftLimit; }

   /**
    * How aggressively each nearby exact duplicate divides selection
    * weight within getDenseResidentialCloneRadiusSoftLimit(), using the
    * same distance-decay formula as clonePenalty -- kept as its own
    * config key for the same reason the radius above is: this context is
    * never allowed to inherit tuning meant for the default districts.
    * Only meaningful when getDenseResidentialCloneRadiusSoftLimit() > 0.
    */
   public static int getDenseResidentialClonePenalty() { return denseResidentialClonePenalty; }

   /**
    * DENSERESIDENTIAL's own buildChance -- applies to EVERY parcel in a
    * DENSERESIDENTIAL district, interior or road-adjacent alike (this
    * used to be road-adjacent-only; that was too narrow, since an
    * interior sub-rectangle settling for a single large FILLER match is
    * exactly what was flooding whole districts with filler -- clone
    * radius has no bearing on that at all, since FILLER never reaches a
    * clone/variant check if it's chosen here instead of BUILD in the
    * first place). Two differences from ordinary buildChance, not just a
    * separate number:
    *   1. It's the ONLY gate DENSERESIDENTIAL ever consults here --
    *      ordinary fillerChance is never rolled at all in this context.
    *   2. Failing this roll (or the BUILD attempt itself coming back with
    *      no fitting schematic at this size/direction) does NOT fall
    *      through to a FILLER attempt at this size the way ordinary
    *      buildChance failing does -- it returns null and lets
    *      nextRecPartition() split this parcel smaller and try BUILD again
    *      there instead. That's what actually delivers "almost every
    *      chunk is a house, not filler": a rectangle that doesn't match
    *      any house schematic at ITS size keeps getting subdivided until
    *      it reaches a size that does, rather than settling for filler at
    *      the first size that came up empty. Filler can still happen, but
    *      only as the genuine last resort once it's been subdivided all
    *      the way to 1x1 and even placeRandomBuildForSure()'s exhaustive
    *      BUILD search finds nothing that fits there.
    * Defaults to 100 -- always attempt BUILD first, everywhere in a
    * DENSERESIDENTIAL district. Lower it to let some parcels skip BUILD
    * outright, without disabling the split-instead-of-filler behavior for
    * the ones that do roll BUILD.
    */
   public static int getDenseResidentialBuildChance() { return denseResidentialBuildChance; }
   public static int getDenseResidentialBlockSize() { return denseResidentialBlockSize; }

   /**
    * District.getParcelMinSize()'s floor for DENSERESIDENTIAL, read from
    * its own config key rather than sharing blockSizeMin -- same
    * "never inherit tuning meant for the default districts" reasoning as
    * getDenseResidentialCloneRadiusSoftLimit() above. Used the same way
    * getBlockSizeMin() is for every other context: it's the minimum size
    * nextRecPartition() will leave on either side of a split once BUILD
    * has genuinely failed to fit anywhere in the current rectangle,
    * before giving up and splitting smaller anyway (see that method's
    * "nothing fit at this rectangle" branch). Defaults to 1, reproducing
    * the old hardcoded-to-1 behaviour (no floor at all -- DENSERESIDENTIAL
    * would split all the way down to individual 1x1 cells before
    * resorting to fillRemainingWithFiller()) until this is set higher.
    * Raise it to keep DENSERESIDENTIAL splits from going as fine-grained,
    * the same trade-off getBlockSizeMin() describes for ordinary
    * districts -- just tuned independently, since DENSERESIDENTIAL's
    * houses are typically much smaller than an ordinary district's
    * blockSize/blockSizeMin were ever tuned for.
    */
   public static int getDenseResidentialBlockSizeMin() { return denseResidentialBlockSizeMin; }

   /**
    * When true, each competing pool of schematics (same size/context/
    * direction slot) has its raw oddsOfAppearanceInPercent values rescaled
    * onto a consistent ~100-point scale before variantPenalty/clonePenalty
    * are applied -- e.g. a pool of [30, 30, 40] and a pool of [3000, 3000,
    * 4000] behave identically under the penalty once normalized, even
    * though they don't today. Never touches the values on disk, and has
    * NO effect on plain (no-penalty) selection odds -- weighted-random
    * selection already only cares about ratios, and scaling every
    * competitor in a pool by the same factor doesn't change ratios. It
    * only matters once a penalty is dividing by (1 + penaltyTerms), which
    * isn't ratio-invariant (the +1 is an absolute constant): without
    * normalization, a schematic authored with a very large raw odds value
    * can shrug off the same penalty that crushes a small-raw-value one to
    * the floor, purely because of what arbitrary absolute number its
    * CONFIG.json happens to use -- not because anyone intended it to be
    * more clone/variant-tolerant. Off by default, preserving existing
    * penalty-tuning behaviour for anyone already relying on it.
    */
   public static boolean isNormalizeOddsOfAppearance() { return normalizeOddsOfAppearance; }

   /**
    * When true, the single case where District.fillDistrict() finds
    * literally nothing placeable for a 1x1 gap -- not a BUILD, not a
    * context filler, not even a context-free filler, after every one of
    * those has genuinely been tried and failed -- generates smoothed raw
    * terrain there instead of leaving whatever the underlying world
    * generator produced untouched. See TerrainFallbackParcel for the
    * actual carving logic. Off by default; when off, behaviour is
    * unchanged from before this existed (the gap is logged and left alone).
    */
   public static boolean isFillerTerrainFallbackEnabled() { return fillerTerrainFallback; }

   /**
    * Morphomos contexts (see ContextType.isMorphomos(), currently FOREST
    * and FARM) get no roads between two districts of the same morphomos
    * context. Farm joins farm and forest joins forest, never each other.
    * Forest also reclaims the roads between itself and ocean, rivers and creeks.
    * Highways and roads bordering any other context are untouched. Needs
    * generator.districtNeighborRules on, since it runs once every
    * district in the tile has been zoned. Default on.
    */
   public static boolean isMorphomosEnabled() { return morphomos && districtNeighborRules; }

   /** Whether morphomos regions get terrain, ground cover and trees. Off leaves them as flat placeholders. */
   public static boolean isForestGenerationEnabled() { return forestGenerate; }

   /** Hill height in blocks at full strength, before each forest type's own multiplier. */
   public static double getForestReliefAmplitude() { return forestReliefAmplitude; }

   /** 0..1. How far relief leans toward hills rather than hollows (0 = symmetric about the build height). */
   public static double getForestReliefBias() { return forestReliefBias; }

   /**
    * Blocks over which terrain eases from the build height (exactly, on the
    * outermost ring of a region) to full relief.
    */
   public static int getForestEdgeBlend() { return forestEdgeBlend; }

   /**
    * When true, a forest region big enough to sit between cities becomes a
    * mountain range instead of rolling hills (see ForestMountains). Smaller
    * regions are unaffected. Default off.
    */
   public static boolean isForestMountainsEnabled() { return forestMountains; }

   /** A region needs at least this many chunk cells (its area) to become a range. */
   public static int getForestMountainMinCells() { return forestMountainMinCells; }

   /** ...and its deepest point must be at least this many chunks from every edge, so a long thin strip stays forest. */
   public static int getForestMountainMinDepth() { return forestMountainMinDepth; }

   /** Peak height in blocks above the build height, reached in the largest regions (the smallest qualifying ones get half). */
   public static int getForestMountainHeight() { return forestMountainHeight; }

   /** Blocks from a region's edge over which the range rises to full height. */
   public static int getForestMountainRamp() { return forestMountainRamp; }

   /** Blocks above the build height where trees stop and bare rock starts. */
   public static int getForestMountainTreeLine() { return forestMountainTreeLine; }

   /** Blocks above the build height where snow starts. 0 = no snow. */
   public static int getForestMountainSnowLine() { return forestMountainSnowLine; }

   /** true = the wilderness between the cities is a mix of forest, mountain, desert and mesa biomes with no region limits; false = forest only, mountains by region. */
   public static boolean isWildBiomesEnabled() { return wildBiomes; }
   /** True = every biome border (forest, mountain, desert, mesa) is one waved line, not a stipple of single columns. */
   public static boolean isWildHardBorders() { return wildHardBorders; }

   /** Roughly how many chunks across one wilderness biome patch is. */
   public static int getWildBiomeScale() { return wildScale; }
   public static int getWildBiomeZoomLevels() { return wildZoomLevels; }
   public static int getWildBiomeTransitionPasses() { return wildTransitionPasses; }
   public static int getWildBiomeWarpPercent() { return wildWarpPercent; }

   /** 0..1, how softly one biome grades into the next. */
   public static double getWildBiomeBlend() { return wildBlend / 100.0; }

   /** Percent of the original horizontal size of the desert dunes (100 = original, 200 = twice as wide and long). */
   public static int getWildDuneScale() { return wildDuneScale; }

   /** Blocks the border between mesa and desert waves in and out by; 0 = a clean line. */
   public static double getWildMesaDesertMerge() { return wildMesaDesertMerge; }
   /** 0..100: how far the mesa height carries on into the desert as an eroded slope (0 = off). */
   public static int getWildMesaFoot() { return wildMesaFoot; }
   /** Blocks the biome weights are averaged over so slivers and spots narrower than this vanish (0 = off). */
   public static int getWildSmoothBlocks() { return wildSmoothBlocks; }
   /** Percent of the default number of wilderness decorations (ruins, fallen logs, boulders, bones, hoodoos...); 0 = none. */
   public static int getWildDecorations() { return wildDecorations; }
   /**
    * Distance between wilderness pieces of one kind (WildDecor.Prop ordinal), in percent of the default: the kind's own setting times the
    * setting for all of them. 100 = default, 200 = twice as far apart (a quarter as many), 50 = half the distance.
    */
   public static int getWildSpacing(int prop) {
      if (prop < 0 || prop >= wildSpacing.length) {
         return wildSpacingAll;
      }
      return Math.max(10, Math.min(10000, wildSpacingAll * wildSpacing[prop] / 100));
   }
   /** Percent chance a planned chest at a wilderness crash site, camp or mine is really placed, and the loot level range (1..5) of those chests. */
   public static int getWildChestOdds() { return wildChestOdds; }
   public static int getWildLootMin() { return wildLootMin; }
   public static int getWildLootMax() { return wildLootMax; }

   /** Target percent of the land for each biome in the order forest, mountains, desert, mesa (normalised if they do not add up to 100). Mountains count 0 when forest.mountains.enabled is false. */
   /** Restore independently tuneable MASSIF/PEAKS/RANGE/PLATEAU/VOLCANO shares. */
   public static double[] getMountainKindShares() {
      double[] shares = new double[mountainKindShares.length];
      for (int i=0; i<shares.length; i++) shares[i] = mountainKindShares[i];
      return shares;
   }
   private static int[] mountainKindDefaults() {
      int[] shares = new int[MountainKind.ALL.length];
      for (MountainKind k : MountainKind.ALL) shares[k.ordinal()] = k.defaultShare();
      return shares;
   }

   public static int getWildWetlandReachChunks() { return wildWetlandReach; }
   public static int getWildDuneInlandBlocks() { return wildDuneReach; }
   public static int getWildMinimumRegionBlocks() { return wildMinimumRegionBlocks; }
   public static int getWildWetlandCoreStrength() { return wildWetlandCoreStrength; }
   public static int getWildDuneCoreStrength() { return wildDuneCoreStrength; }
   public static int getWildVolcanicCoreStrength() { return wildVolcanicCoreStrength; }
   public static double[] getWildBiomeShares() {
      double[] result = new double[WildBiome.ALL.length];
      for (int i = 0; i < result.length; ++i) {
         result[i] = WildBiome.ALL[i].is(WildBiome.MOUNTAINS) && !forestMountains ? 0 : wildShares[i];
      }
      return result;
   }
   public static String[] getWildTransitions() { return wildTransitions.clone(); }
   private static int[] wildDefaults(boolean share) {
      int[] values = new int[WildBiome.ALL.length];
      for (int i = 0; i < values.length; ++i) {
         values[i] = share ? WildBiome.ALL[i].defaultShare() : WildBiome.ALL[i].defaultAway();
      }
      return values;
   }

   public static int getWildKeepAwayMerge() { return wildAwayMerge; }

   /** Chunks the keep-away distance wanders by, so a kept-away biome does not follow the farm at a set distance. */
   public static int getWildKeepAwayVariance() { return wildAwayVariance; }

   /**
    * Chunks from the nearest farm or city block at which each biome reaches full strength, in the order forest, mountains, desert,
    * mesa: a biome grows in from nothing keepAwayMerge chunks short of its distance. 0 = it may touch the cities (the forest always does).
    */
   public static int[] getWildKeepAwayChunks() {
      return wildAways.clone();
   }

   public static int getWildMesaTerraceHeight() { return wildMesaStep; }

   public static int getWildMesaTerraces() { return wildMesaLevels; }

   /** true = the wilderness is the server's own vanilla generation (eased to the build height at its edges) instead of the Metropolis forest / mountain / desert / mesa biomes. */
   public static boolean isWildVanillaEnabled() { return wildVanilla; }

   /** Chunks, from any street, farm, city, highway or water edge, over which vanilla ground is eased to the build height (1..4). */
   public static int getWildVanillaBlendChunks() { return wildVanillaBlend; }

   /** Blocks from such an edge that are kept free of trees, so a canopy is not cut off by the chunk beside it (0..16). */
   public static int getWildVanillaVergeBlocks() { return wildVanillaVerge; }

   /** Whether the vanilla generator may place its structures (villages, temples, mineshafts, strongholds). */
   public static boolean isWildVanillaStructures() { return wildVanillaStructures; }

   /** Name of the companion world vanilla chunks are read from; empty = "<world>_vanilla". */
   public static String getWildVanillaWorld() { return wildVanillaWorld; }

   /** false = vanilla's oceans (and any other sea-level pool that is not a river) are drained into low grassland instead of being copied. */
   public static boolean isWildVanillaOceans() { return wildVanillaOceans; }

   /**
    * Roughly how much of a range region's interior stays ordinary hilly forest
    * (with its meadows and glades) instead of rising into mountains, in percent.
    * Lowlands are likelier toward the region's edges, so the mountains gather in
    * the core with foothills around them. 0 = mountains everywhere.
    */
   public static int getForestMountainLowlands() { return forestMountainLowlands; }

   /** Rough width in blocks of one forest-type patch. */
   public static int getForestTypeScale() { return forestTypeScale; }

   /** Multiplier on every forest type's tree density. */
   public static double getForestTreeDensity() { return forestTreeDensity; }

   /** Added to the build height for the region's surface, if the street/lot ground sits above or below it. */
   public static int getForestSurfaceOffset() { return forestSurfaceOffset; }

   /** Whether farm regions get fields, fences, barns, silos and loot. Off leaves them as flat placeholders. */
   public static boolean isFarmGenerationEnabled() { return farmGenerate; }

   /** How wrecked the farms look, 0..100: broken fences, holes in barn roofs and walls, dead crops, weeds. Each building and field varies around this. */
   public static int getFarmRuinPercent() { return farmRuinPercent; }

   /** Whether crop and hay fields get a (broken) fence like pastures always do. */
   public static boolean getFarmFenceCrops() { return farmFenceCrops; }

   /** Percent of the farm grid lines (one every 256 blocks) that carry a dirt road, only where it leads to a paved road. */
   public static int getFarmRoadLinePercent() { return farmRoadLinePercent; }

   /** Added to the build height for the farm surface. -2 puts it on the paved road deck (y=63) when buildHeight is 65, so dirt roads meet paved roads flush. */
   public static int getFarmSurfaceOffset() { return farmSurfaceOffset; }

   /** Percent of farm cells (256 x 256 blocks) that get a farmstead (barn, silos, yard) in a corner. */
   public static int getFarmFarmsteadPercent() { return farmFarmsteadPercent; }

   /** Percent of fields that are fenced pasture rather than abandoned cropland. */
   public static int getFarmPastureShare() { return farmPastureShare; }

   /** Chance in percent that each planned barn/silo chest is actually placed. */

   public static int getFarmModernBarn() { return farmModernBarn; }
   public static int getFarmGreenhouse() { return farmGreenhouse; }
   public static int getFarmOrchardShare() { return farmOrchardShare; }
   public static int getFarmRoundBarn() { return farmRoundBarn; }
   public static int getFarmOutbuildings() { return farmOutbuildings; }
   public static int getFarmChestOddsPercent() { return farmChestOdds; }

   /** Loot level range (1..5) for farm chests, drawn from the residential collection. */
   /** Greenhouse tunnel covering: glass (default), pane or wool. */
   public static String getFarmTunnelSkin() { return farmTunnelSkin; }

   public static int getFarmLootMinLevel() { return farmLootMinLevel; }
   public static int getFarmLootMaxLevel() { return farmLootMaxLevel; }

   /** City-rings district placement. Needs districtNeighborRules (the assigner it plugs into). */
   public static boolean isCityRingsEnabled() { return cityRings && districtNeighborRules; }

   /** Chunks from a city centre to its residential edge, before per-city variation and wobble. */
   public static int getCityRingsRadius() { return cityRingsRadius; }

   /** Chunks between city sites on the jittered grid. */
   public static int getCityRingsSpacing() { return cityRingsSpacing; }

   /** Percent each city's radius may vary from the base radius, either way. */
   public static int getCityRingsSizeVariation() { return cityRingsSizeVariation; }

   /** Percent of noise distortion applied to city outlines. */
   public static int getCityRingsEdgeWobble() { return cityRingsEdgeWobble; }

   /** Farm ring width beyond the residential edge, as a percent of the city radius. Forest is everything beyond it. */
   public static int getCityRingsFarmBandWidth() { return cityRingsFarmBandWidth; }

   /** How far the farm/forest edge is blurred by noise, in percent of a city radius. */
   public static int getCityRingsFarmForestOverlap() { return cityRingsFarmForestOverlap; }

   /**
    * City highway network (generator.highwayNetwork.enabled): each city is boxed in a square highway ring with two
    * highways crossing it, and neighbouring cities are joined by as few highways as possible, replacing the
    * ring around every 64x64 tile. It follows the cities, so it needs city rings.
    */
   public static boolean isHighwayNetworkEnabled() { return highwayNetwork && isCityRingsEnabled(); }

   /** Half the width of a city box, as a percent of that city's own radius. */
   public static int getHighwayNetworkBoxScale() { return highwayNetworkBoxScale; }

   /** Percent chance that a link which would be pruned to keep the network sparse is kept anyway, for loops. */
   public static int getHighwayNetworkExtraLinkPercent() { return highwayNetworkExtraLinks; }

   /** Fewest chunks between the tiles the ocean owns and the highway ring wrapped around them. */
   public static int getHighwayNetworkOceanMarginMin() { return highwayNetworkOceanMarginMin; }

   /** Most chunks between the ocean's tiles and its highway ring; every side of the ring draws its own distance in between. */
   public static int getHighwayNetworkOceanMarginMax() { return highwayNetworkOceanMarginMax; }

   /** One street per tile seam instead of one on each side (generator.highwayNetwork.singleSeamStreet). */
   public static boolean isHighwayNetworkSingleSeamStreet() { return highwayNetworkSingleSeamStreet; }

   /**
    * Max blocks of noise-based height variation TerrainFallbackParcel adds
    * on top of the sampled edge height, fading to 0 at the chunk's outer
    * ring so it always still lines up with real neighbours there. 0
    * disables noise entirely -- a flat plateau at edgeHeight.
    */
   public static int getFillerTerrainNoiseIntensity() { return fillerTerrainNoiseIntensity; }

   /**
    * Clamps how far TerrainFallbackParcel's sampled edge height is allowed
    * to drift from buildHeight in either direction, so a gap chunk that
    * happens to border open ocean or an unrelated ravine can't produce a
    * bizarre tower or pit -- it gets pulled back toward the city's actual
    * street level instead.
    */
   public static int getFillerTerrainMaxDeviation() { return fillerTerrainMaxDeviation; }

   /**
    * How many blocks of STONE TerrainFallbackParcel fills below its 3
    * blocks of DIRT (which sit below 1 block of GRASS on top). Doesn't
    * dig any deeper than this -- whatever was already below that depth is
    * left untouched.
    */
   public static int getFillerTerrainStoneDepth() { return fillerTerrainStoneDepth; }

   /**
    * GridProvider caches one full UrbanGrid (a Parcel[64][64] array, plus
    * every District object carved for that 64x64-chunk tile) per grid
    * tile a player has ever loaded a chunk in -- with NO eviction, ever.
    * On a long-running server with a meaningfully explored map, that's an
    * unbounded, permanently-growing memory leak: a 20x20-tile explored
    * area alone is ~1.6 million retained Parcel references, none of which
    * can ever be freed for the lifetime of the process. This caps how
    * many grid tiles stay resident at once -- least-recently-used tiles
    * get evicted once the limit is exceeded, and simply get recomputed
    * (deterministically, from the same world seed) if a player wanders
    * back into that area later.
    */
   public static int getMaxCachedGridTiles() { return maxCachedGridTiles; }

   /**
    * When true, a finished district is re-cut if too much of its area is
    * FILLER rather than BUILD schematics, down to extraCutBlockSize.
    */
   public static boolean isExtraCutModeEnabled()    { return extraCutMode;            }
   public static int     getExtraCutFillerThreshold() { return extraCutFillerThreshold; }
   public static int     getExtraCutBlockSize()     { return extraCutBlockSize;       }

   // -------------------------------------------------------------------------
   // Landmark districts (schematics larger than blockSize)
   // -------------------------------------------------------------------------

   private static final String pathEnableLandmarkDistricts   = "generator.enableLandmarkDistricts";
   private static final String pathLandmarkAttemptChancePercent = "generator.landmarkAttemptChancePercent";
   private static final String pathLandmarkMaxPerGrid        = "generator.landmarkMaxPerGrid";
   private static final String pathLandmarkCloneRadius       = "generator.landmarkCloneRadius";
   private static final String pathLandmarkMinSpacing        = "generator.landmarkMinSpacing";
   private static final String pathLandmarkZoneMatch         = "generator.landmarkZoneMatch";
   private static final String pathPriorityLandmarkGroups = "generator.priorityLandmarkGroups";

   private static boolean enableLandmarkDistricts      = true;
   private static int     landmarkAttemptChancePercent = 100;
   private static int     landmarkMaxPerGrid            = 1;
   private static int     landmarkCloneRadius           = 3;
   private static int     landmarkMinSpacing            = 12;
   private static boolean landmarkZoneMatch             = false;
   private static List<String> priorityLandmarkGroups = Arrays.asList("CentralPark", "MegaMall");

   /**
    * When true, grid carving (UrbanGrid.recSetDistricts()) actively looks
    * for schematics whose own chunk footprint exceeds blockSize and, when
    * one is found that fits, carves a dedicated district sized exactly to
    * it -- rather than blockSize capping every district and leaving such
    * schematics permanently unplaceable. Unlike extraCutMode (which
    * generates a district normally, then detects and re-cuts it after the
    * fact), a landmark district is carved once, directly, with no
    * iteration or re-placement -- see UrbanGrid.tryPlaceLandmark().
    */
   public static boolean isLandmarkDistrictsEnabled() { return enableLandmarkDistricts; }

   /**
    * Percent chance, rolled at each eligible point during grid carving,
    * that a landmark placement is even attempted there. Keeps landmarks
    * from always claiming the very first region large enough to hold one,
    * spreading them out organically across a grid tile instead. Does not
    * affect whether a grid tile tries landmarks at all -- see
    * landmarkMaxPerGrid.
    */
   public static int getLandmarkAttemptChancePercent() { return landmarkAttemptChancePercent; }

   /**
    * Hard cap on how many landmark schematics a single 64x64 grid tile
    * will place. Decremented each time one succeeds; once it hits 0, grid
    * carving falls back to normal blockSize-capped behaviour for the rest
    * of that tile.
    */
   public static int getLandmarkMaxPerGrid()          { return landmarkMaxPerGrid;          }

   /**
    * Radius, in grid TILES (64x64-chunk UrbanGrid tiles -- see Grid.GRID_SIZE),
    * within which a landmark schematic is never duplicated. Deliberately a
    * separate value from the ordinary cloneRadius above rather than reusing
    * it: cloneRadius is a chunk-scale radius sized for ordinary city blocks,
    * while landmarks are rare, sparse, and meant to be unique across a much
    * wider area spanning several grid tiles -- a chunk-sized radius around a
    * landmark's own (often already large) footprint would barely reach past
    * its own edges. Distance is measured with the infinity norm between
    * grid-tile origins, same convention as cloneRadius, just one grid tile
    * "step" at a time instead of one chunk at a time -- a value of 2 checks
    * the landmark's own tile plus the 2 tiles out in every direction (a 5x5
    * tile neighbourhood).
    *
    * Checked against a small persistent placement log (see
    * ClipboardProvider.findNearbyLandmarkGroupIds()) rather than scanning
    * as It's also far cheaper than a live scan
    */
   public static int getLandmarkCloneRadius() { return landmarkCloneRadius; }

   /**
    * Minimum clear gap, in chunks, kept between the footprints of any two landmarks (see LandmarkPlans). This is the plan-based
    * stand-in for the spacing the old tile carve gave for free (at most landmarkMaxPerGrid per tile, each carved inside its own
    * region): two landmarks never stand closer than this, whichever tile asks first. landmarkCloneRadius is separate and only
    * stops the SAME schematic repeating within that many tiles. 0 = only keep their footprints from overlapping.
    */
   public static int getLandmarkMinSpacing() { return landmarkMinSpacing; }

   /**
    * When true, a landmark -- and the building a creek site is built around
    * (BoundedRiverSites) -- is only placed in a district of a zone its own
    * CONFIG.json names. The explicit landmark pool is normally tried
    * regardless of the district's zone, so e.g. a schematic tagged
    * ["HIGHRISE", "LANDMARK"] can otherwise end up in a forest. A schematic
    * with a directional cutout counts too: it goes to the landmark pool only,
    * but the zones in its context array are still the ones it is held to.
    * A landmark is bigger than most bands are wide, so it is not asked to
    * fit inside one: its own centre zone must be one it names, and at least
    * three of its four sides must be bordered by zones it names. A schematic
    * naming no zone (["LANDMARK"] only) stays unrestricted. The landmark's
    * district is also pinned to the matching zone when districts are zoned
    * (see DistrictContextAssigner), so neighbour rules cannot move it to a
    * different one. See LandmarkZoneRule. Default off (the original behaviour).
    */
   public static boolean isLandmarkZoneMatchEnabled() { return landmarkZoneMatch; }
   public static List<String> getPriorityLandmarkGroups() { return Collections.unmodifiableList(priorityLandmarkGroups); }

   // -------------------------------------------------------------------------
   // Decay parameters
   // -------------------------------------------------------------------------

   private static final String pathDecayOverride             = "generator.decayOverride";
   private static final String pathMasterDecayIntensity      = "generator.masterDecayIntensity";
   private static final String pathMasterBlockDecayIntensity = "generator.masterBlockDecayIntensity";
   private static final String pathMasterGrowthIntensity     = "generator.masterGrowthIntensity";
   private static final String pathMasterBranchIntensity     = "generator.masterBranchIntensity";
   private static final String pathMasterVineIntensity       = "generator.masterVineIntensity";
   private static final String pathVineMaxLength             = "generator.vineMaxLength";
   private static final String pathExteriorCheckDistance     = "generator.exteriorCheckDistance";

   // --- Legacy leaf decay (the OLD pre-branch leaf methodology): a simple
   // probabilistic flood-fill seeded from every supporting block that
   // survives a partial-decay roll -- see
   // DecayProviderNormal.addLegacyLeavesRec(). Independent of and runs
   // alongside, not instead of, branch/slime/vine growth; toggled off by
   // default so it doesn't change behavior until explicitly enabled.
   private static final String pathLegacyLeafDecayEnabled    = "generator.legacyLeafDecayEnabled";
   private static final String pathDecayProtectOceanWaterline = "generator.decayProtectOceanWaterline";
   private static final String pathVanillaUnderworld = "generator.vanillaUnderworld";
   private static final String pathLegacyLeafDecayIntensity  = "generator.legacyLeafDecayIntensity";
   private static final String pathLegacyLeafDecayDepth      = "generator.legacyLeafDecayDepth";

   // --- Branch growth (the drooping arch between a sky-exposed high node
   // and a lower node/collision point -- see DecayProviderNormal) ---
   private static final String pathBranchMinCount            = "generator.branchMinCount";
   private static final String pathBranchMaxCount             = "generator.branchMaxCount";
   private static final String pathBranchMinThickness         = "generator.branchMinThickness";
   private static final String pathBranchMaxThickness         = "generator.branchMaxThickness";
   private static final String pathBranchMaxHeight            = "generator.branchMaxHeight";
   private static final String pathBranchMinSpan              = "generator.branchMinSpan";
   private static final String pathBranchMaxSpan              = "generator.branchMaxSpan";
   private static final String pathBranchMaxHorizontalSpan    = "generator.branchMaxHorizontalSpan";
   private static final String pathBranchSagRatio             = "generator.branchSagRatio";
   private static final String pathBranchHangChancePercent    = "generator.branchHangChancePercent";
   private static final String pathBranchHangMinLength        = "generator.branchHangMinLength";
   private static final String pathBranchHangMaxLength        = "generator.branchHangMaxLength";
   private static final String pathBranchNodeReseedAttempts   = "generator.branchNodeReseedAttempts";
   private static final String pathBranchBoundsMargin         = "generator.branchBoundsMargin";
   private static final String pathBranchNeighbourDepth       = "generator.branchNeighbourDepth";
   private static final String pathBranchFlushTimeoutTicks    = "generator.branchFlushTimeoutTicks";
   private static final String pathStreetBranchSeedChancePercent = "generator.streetBranchSeedChancePercent";
   private static final String pathBranchConstrainToOwnLot    = "generator.branchConstrainToOwnLot";
   private static final String pathBranchMaxVerticalToHorizontalRatio = "generator.branchMaxVerticalToHorizontalRatio";
   private static final String pathBranchGroundConnectDistance       = "generator.branchGroundConnectDistance";
   private static final String pathBranchMaxChainLength              = "generator.branchMaxChainLength";
   private static final String pathBranchChainAttempts               = "generator.branchChainAttempts";

   // --- Slime growth (the low, snaking, ground-hugging/wall-climbing
   // directional growth seeded from each branch's two anchor points) ---
   private static final String pathSlimeMaxLength                     = "generator.slimeMaxLength";
   private static final String pathSlimeContinueChancePercent         = "generator.slimeContinueChancePercent";
   private static final String pathSlimeBranchChancePercent           = "generator.slimeBranchChancePercent";
   private static final String pathSlimeVeerChancePercent             = "generator.slimeVeerChancePercent";
   private static final String pathSlimeUpWeight                      = "generator.slimeUpWeight";
   private static final String pathSlimeHorizontalWeight              = "generator.slimeHorizontalWeight";
   private static final String pathSlimeMinThickness                  = "generator.slimeMinThickness";
   private static final String pathSlimeMaxThicknessHorizontal        = "generator.slimeMaxThicknessHorizontal";
   private static final String pathSlimeMaxThicknessVertical          = "generator.slimeMaxThicknessVertical";
   private static final String pathSlimeThicknessChangeChancePercent  = "generator.slimeThicknessChangeChancePercent";
   private static final String pathSlimeMaxClimbHeight                = "generator.slimeMaxClimbHeight";

   // --- River / bridges. State lives in RiverConfig itself, not here --
   // see that class's own javadoc for why (this is exactly the wiring it
   // asks for: read config.yml, push the values into its setters). ---
   private static final String pathRiverEnabled          = "generator.river.enabled";
   private static final String pathRiverBuildHeight      = "generator.river.buildHeight";
   private static final String pathRiverReclaimBuildHeight = "generator.river.reclaim.buildHeight";
   private static final String pathRiverStraight         = "generator.river.straight";
   private static final String pathRiverBlockSize        = "generator.river.blockSize";
   private static final String pathRiverBankMargin       = "generator.river.bankMargin";
   private static final String pathRiverDepth            = "generator.river.depth";
   private static final String pathRiverBridgeClearance  = "generator.river.bridgeClearance";
   private static final String pathRiverWidthNarrow      = "generator.river.widthNarrow";
   private static final String pathRiverWidthMedium      = "generator.river.widthMedium";
   private static final String pathRiverWidthWide        = "generator.river.widthWide";
   private static final String pathCreekMaxTraceChunks   = "generator.river.creekMaxTraceChunks";
   private static final String pathCreekSiteChancePercent = "generator.river.creekSiteChancePercent";
   private static final String pathCreekSiteAttempts      = "generator.river.creekSiteAttempts";
   private static final String pathRiverPeelMaxDimension = "generator.river.peelMaxDimension";
   private static final String pathRiverReclaimEnabled     = "generator.river.reclaim.enabled";
   private static final String pathRiverReclaimBerthChunks = "generator.river.reclaim.berthChunks";
   private static final String pathRiverReclaimMinDepth    = "generator.river.reclaim.minDepth";
   private static final String pathRiverReclaimMinWidth    = "generator.river.reclaim.minWidth";
   private static final String pathRiverReclaimMaxWidth    = "generator.river.reclaim.maxWidth";
   private static final String pathRiverBankMaxRunChunks   = "generator.river.bankMaxRunChunks";
   private static final String pathRiverMeanderSlowFrequency = "generator.river.meander.slowFrequency";
   private static final String pathRiverMeanderSlowAmplitude = "generator.river.meander.slowAmplitude";
   private static final String pathRiverMeanderFastFrequency = "generator.river.meander.fastFrequency";
   private static final String pathRiverMeanderFastAmplitude = "generator.river.meander.fastAmplitude";
   private static final String pathRiverMeanderIntensityFrequency = "generator.river.meander.intensityFrequency";
   private static final String pathRiverMeanderIntensityMin       = "generator.river.meander.intensityMin";
   private static final String pathRiverWaterColumnDepth          = "generator.river.waterColumnDepth";
   private static final String pathRiverTreesEnabled          = "generator.river.trees.enabled";
   private static final String pathRiverTreesChancePercent    = "generator.river.trees.chancePercent";
   private static final String pathRiverVegetationEnabled       = "generator.river.vegetation.enabled";
   private static final String pathRiverVegetationChancePercent = "generator.river.vegetation.chancePercent";

   private static final String pathOceanEnabled          = "generator.ocean.enabled";
   private static final String pathOceanBuildHeight      = "generator.ocean.buildHeight";
   private static final String pathOceanDepth            = "generator.ocean.depth";
   private static final String pathOceanSizeTiles        = "generator.ocean.sizeTiles";
   private static final String pathOceanBankMargin       = "generator.ocean.bankMargin";
   private static final String pathOceanBankRunBlocks    = "generator.ocean.bankRunBlocks";
   private static final String pathOceanEnvelopeMargin   = "generator.ocean.envelopeMargin";
   private static final String pathOceanMaxDistanceFromSpawn = "generator.ocean.maxDistanceFromSpawn";
   private static final String pathOceanEdgeNoiseAmplitudeFraction = "generator.ocean.edgeNoiseAmplitudeFraction";
   private static final String pathOceanEdgeNoiseFrequency = "generator.ocean.edgeNoiseFrequency";
   private static final String pathOceanEdgeNoiseOctaves = "generator.ocean.edgeNoiseOctaves";
   private static final String pathOceanCoastJitterBlocks = "generator.ocean.coastJitterBlocks";
   private static final String pathOceanCoastJitterFrequency = "generator.ocean.coastJitterFrequency";
   private static final String pathOceanShoreRampBlocks  = "generator.ocean.shoreRampBlocks";
   private static final String pathOceanMaxWaterColumnDepth = "generator.ocean.maxWaterColumnDepth";
   private static final String pathOceanStretch              = "generator.ocean.stretch";
   private static final String pathOceanWarpFraction         = "generator.ocean.warpFraction";
   private static final String pathOceanBayFraction          = "generator.ocean.bayFraction";
   private static final String pathOceanInletFraction        = "generator.ocean.inletFraction";
   private static final String pathOceanIslandFraction       = "generator.ocean.islandFraction";
   private static final String pathOceanSeabedReliefFraction = "generator.ocean.seabedReliefFraction";
   private static final String pathOceanSeabedDetailsEnabled = "generator.ocean.seabed.enabled";
   private static final String pathOceanSeabedPatch = "generator.ocean.seabed.patchSizeBlocks";
   private static final String pathOceanSeabedRock = "generator.ocean.seabed.rockPercent";
   private static final String pathOceanSeabedGrass = "generator.ocean.seabed.seagrassPercent";
   private static final String pathOceanSeabedKelp = "generator.ocean.seabed.kelpPercent";
   private static final String pathOceanSeabedShelfWaves = "generator.ocean.seabed.shelfVariationBlocks";
   private static final String pathOceanSeabedMidWaves = "generator.ocean.seabed.midVariationBlocks";
   private static final String pathOceanSeabedBedformSize = "generator.ocean.seabed.bedformScaleBlocks";
   private static final String pathOceanMinNavigableDepth = "generator.ocean.seabed.minimumNavigableDepthBlocks";
   private static final String pathOceanMouthFlare           = "generator.ocean.mouthFlare";
   private static final String pathOceanRiverHandoffBlocks   = "generator.ocean.riverHandoffBlocks";
   private static final String pathOceanMouthZoneBlocks      = "generator.ocean.mouthZoneBlocks";
   private static final String pathOceanMouthFilletBlocks    = "generator.ocean.mouthFilletBlocks";
   private static final String pathOceanTreesEnabled              = "generator.ocean.trees.enabled";
   private static final String pathOceanTreesChancePercent        = "generator.ocean.trees.chancePercent";
   private static final String pathOceanVegetationEnabled         = "generator.ocean.vegetation.enabled";
   private static final String pathOceanVegetationChancePercent   = "generator.ocean.vegetation.chancePercent";
   private static final String pathOceanBoatsEnabled              = "generator.ocean.boats.enabled";
   private static final String pathOceanShallowDepth          = "generator.ocean.shallowDepth";
   private static final String pathOceanDeepDepth             = "generator.ocean.deepDepth";
   private static final String pathOceanShallowEndBlocks      = "generator.ocean.shallowEndBlocks";
   private static final String pathOceanShallowToMidBlocks    = "generator.ocean.shallowToMidBlocks";
   private static final String pathOceanDeepStartBlocks       = "generator.ocean.deepStartBlocks";
   private static final String pathOceanMidToDeepBlocks       = "generator.ocean.midToDeepBlocks";
   private static final String pathOceanZoneBoundaryNoise     = "generator.ocean.zoneBoundaryNoiseFraction";
   private static final String pathOceanShallowReliefScale    = "generator.ocean.shallowReliefScale";
   private static final String pathOceanDeepReliefScale       = "generator.ocean.deepReliefScale";
   private static final String pathOceanBoatsBigCloneRadius          = "generator.ocean.boats.bigCloneRadius";
   private static final String pathOceanBoatsBigCloneRadiusSoftLimit = "generator.ocean.boats.bigCloneRadiusSoftLimit";
   private static final String pathOceanBoatsBigMinChunks            = "generator.ocean.boats.bigMinChunks";
   private static final String pathOceanBoatsSpacingBlocks        = "generator.ocean.boats.spacingBlocks";
   private static final String pathBoundedMaxLengthChunks = "generator.river.boundedMaxLengthChunks";
   private static final String pathRiverConvergeOnOcean      = "generator.river.convergeOnOcean";
   private static final String pathRiverZBoundEnabled        = "generator.river.zRiverEnabled";

   private static boolean decayOverride             = true;
   private static int     masterDecayIntensity      = 10;
   private static int     masterBlockDecayIntensity = 100;
   private static int     masterGrowthIntensity     = 200;
   private static int     masterBranchIntensity     = 100;
   private static int     masterVineIntensity       = 100;
   private static int     vineMaxLength             = 5;
   private static int     exteriorCheckDistance     = 6;

   private static boolean legacyLeafDecayEnabled    = true;
   private static boolean decayProtectOceanWaterline = true;
   private static boolean vanillaUnderworld = true;
   private static int     legacyLeafDecayIntensity  = 100;
   private static int     legacyLeafDecayDepth      = 2;

   private static int     branchMinCount            = 4;
   private static int     branchMaxCount            = 40;
   private static int     branchMinThickness        = 1;
   private static int     branchMaxThickness        = 4;
   private static int     branchMaxHeight           = 50;
   private static int     branchMinSpan             = 10;
   private static int     branchMaxSpan             = 60;
   private static int     branchMaxHorizontalSpan   = 50;
   private static double  branchSagRatio            = 0.45;
   private static int     branchHangChancePercent   = 17;
   private static int     branchHangMinLength       = 2;
   private static int     branchHangMaxLength       = 5;
   private static int     branchNodeReseedAttempts  = 8;
   private static int     branchBoundsMargin        = 10;
   // How many rings of actual NEIGHBOURING BUILDS (not raw chunks, and not
   // counting streets -- see DecayProviderNormal.neighbourhoodReady()) must
   // already be pasted, on every side, before a build's own cached growth
   // is allowed to fire. This is what replaced tile/district-wide growth:
   // instead of pooling every building in a whole block or tile into one
   // shared roll, each building still rolls and flushes its OWN growth
   // alone, just deliberately held back until enough of its immediate
   // neighbourhood exists for branches to have somewhere real to land
   // across a lot line (see the withinBounds() fallback) -- exactly the
   // same webbing tile/district-wide growth gave, without ever waiting on
   // (or flushing) more than one building at a time.
   private static int     branchNeighbourDepth      = 2;
   // Ticks of INACTIVITY before a build's cached growth flushes anyway,
   // even if its neighbourhood (branchNeighbourDepth rings) never fully
   // populated -- see DecayProviderNormal's per-build watchdog. A build
   // near the edge of a tile nobody ever finishes visiting would otherwise
   // wait forever, exactly the same problem UrbanGrid's old tile-wide
   // watchdog existed to solve, just scoped to one building instead of an
   // entire 64x64 tile -- so it only ever needs to be a matter of seconds,
   // not minutes. 0 disables the watchdog and waits forever.
   private static int     branchFlushTimeoutTicks   = 1200; // 1 minute
   // See DecayProviderNormal.thinStreetGrowthCandidates() -- what % of a
   // street/highway parcel's own supportHits are kept as branch/slime
   // seed candidates when it flushes. 100 seeds a street exactly as
   // densely as a building would (no thinning); 0 lets a street host
   // vines as normal but never seeds branch/slime growth of its own.
   // Purely about a street's OWN seeding -- a neighbouring building's
   // branch sagging out over the street is unaffected (see
   // isPastedNeighbourBuilding()).
   private static int     streetBranchSeedChancePercent = 100;
   private static boolean branchConstrainToOwnLot   = false;
   private static double  branchMaxVerticalToHorizontalRatio = 3.0;
   private static int     branchGroundConnectDistance        = 4;
   private static int     branchMaxChainLength               = 10;
   private static int     branchChainAttempts                = 90;

   private static int     slimeMaxLength                    = 33;
   private static int     slimeContinueChancePercent        = 98;
   private static int     slimeBranchChancePercent          = 12;
   private static int     slimeVeerChancePercent            = 18;
   private static int     slimeUpWeight                     = 15;
   private static int     slimeHorizontalWeight              = 5;
   private static int     slimeMinThickness                 = 1;
   private static int     slimeMaxThicknessHorizontal       = 2;
   private static int     slimeMaxThicknessVertical         = 2;
   private static int     slimeThicknessChangeChancePercent = 40;
   private static int     slimeMaxClimbHeight               = 12;

   /**
    * When true, every building's destruction intensity is forced to
    * masterDecayIntensity, ignoring the schematic's own setting entirely.
    */
   public static boolean isDecayOverride() { return decayOverride; }

   /**
    * Master multiplier (0-100+%) for DESTRUCTIVE decay (blocks → air/holes).
    * Multiplied on top of each building's own decayIntensityInPercent.
    * Independent of masterBlockDecayIntensity.
    */
   public static int getMasterDecayIntensity()      { return masterDecayIntensity;      }

   /**
    * Master multiplier (0-100+%) for BLOCK-TRANSFORM decay (blocks → other
    * solid blocks: cobblestone, mossy variants, stairs).
    * Independent of masterDecayIntensity.
    */
   public static int getMasterBlockDecayIntensity() { return masterBlockDecayIntensity; }

   /**
    * Master multiplier (0-100+%) for LEAF coverage specifically (applied
    * on top of each building's own leavesScale/leavesdecay -- see
    * SchematicConfig.getDecayOption()). Does NOT control whether branches
    * or slime trails themselves generate, or how many -- see
    * masterBranchIntensity for that.
    */
   public static int getMasterGrowthIntensity()     { return masterGrowthIntensity;     }

   /**
    * Master multiplier (0-100+%) for branch/slime STRUCTURAL density --
    * how many branches (and therefore slime trails, each seeded from a
    * branch anchor) a building attempts, scaled on top of
    * branchMinCount/branchMaxCount. 0 turns branch/slime growth off
    * entirely, independent of masterGrowthIntensity (leaf coverage) and
    * masterVineIntensity (vine density), both of which keep working
    * normally even if this is 0.
    */
   public static int getMasterBranchIntensity()     { return masterBranchIntensity;     }

   /**
    * Master multiplier (0-100+%) for vine density specifically.
    * Independent of masterGrowthIntensity/masterBranchIntensity so vines
    * can be tuned separately from leaf coverage and branch/slime density.
    */
   public static int getMasterVineIntensity()       { return masterVineIntensity;       }

   /** Hard cap on how many blocks a single vine can hang down. */
   public static int getVineMaxLength()             { return vineMaxLength;             }

   /**
    * Toggles the OLD pre-branch leaf-decay methodology: a simple
    * probabilistic flood-fill of leaves seeded from every supporting
    * block that survives a partial-decay roll, up to getLegacyLeafDecayDepth()
    * hops outward, each hop independently gated by that call's own
    * DecayOption.getLeavesScale() (scaled by getLegacyLeafDecayIntensity()).
    * Runs directly out of destroyWithin()'s own per-block loop -- entirely
    * independent of generateGrowth()/DecayProviderBranch, so it still
    * works with branch growth (and masterGrowthIntensity) turned off, and
    * keeps working alongside it when both are on. Off by default. Unlike
    * the original, leaves placed this way are undecayable (see
    * DecayProviderNormal.placeLeaf()).
    */
   public static boolean isLegacyLeafDecayEnabled()  { return legacyLeafDecayEnabled; }

   /**
    * When on (the default), DESTRUCTIVE decay (block -> air) is never applied
    * at or below the ocean's water surface in any chunk the ocean reaches --
    * ships' hulls, quay walls, piers, submerged foundations and everything
    * else that is under the water-line. Block-transform decay (cobble/moss/
    * stairs) and growth are unaffected. See
    * DecayProviderNormal.oceanWaterlineTopY().
    */
   public static boolean isDecayProtectOceanWaterline() { return decayProtectOceanWaterline; }

   /**
    * When on (the default), everything below Y=0 in a NORMAL-environment world
    * is generated the vanilla way -- vanilla noise (stone, noise caves, aquifers,
    * ore veins), deepslate, a bedrock floor at the world's true bottom, and
    * vanilla ore/feature placement -- while everything from Y=0 up stays
    * Metropolis's own. When off, the world is empty void below Y=0 and the
    * bedrock floor sits at Y=0..3, exactly as in the original 1.8 layout.
    * See MetropolisGenerator.generateNoise().
    */
   public static boolean isVanillaUnderworld() { return vanillaUnderworld; }

   /** Master multiplier (0-100+%) applied on top of each call's own DecayOption.getLeavesScale() for legacy leaf decay specifically. */
   public static int getLegacyLeafDecayIntensity()   { return legacyLeafDecayIntensity; }

   /** How many recursive hops the legacy leaf flood-fill spreads outward from each supporting block. Matches the original hardcoded value of 2. */
   public static int getLegacyLeafDecayDepth()       { return legacyLeafDecayDepth; }

   // -------------------------------------------------------------------------
   // Branch growth: a sky-exposed high node and a lower node/collision
   // point are found first, then a sagging arch of leaves is placed
   // between them (see DecayProviderNormal.generateBranches()).
   // -------------------------------------------------------------------------

   /** Fewest branches attempted per structure (subject to node availability). */
   public static int getBranchMinCount() { return branchMinCount; }

   /**
    * Most branches attempted per structure. Also drives how many candidate
    * nodes get seeded in the first place: DecayProviderNormal seeds
    * 2 * getBranchMaxCount() sky-exposed candidates up front (cheap --
    * sky-exposure is the only check run on the full candidate set), so
    * raising this both allows more branches AND gives the pairing step a
    * bigger pool to pick compatible upper/lower nodes from.
    */
   public static int getBranchMaxCount() { return branchMaxCount; }

   /** Floor of the per-branch thickness range -- see getBranchMaxThickness(). */
   public static int getBranchMinThickness() { return branchMinThickness; }

   /**
    * Ceiling of the per-branch thickness range. Each branch rolls its own
    * thickness once, within [getBranchMinThickness(), getBranchMaxThickness()],
    * and keeps roughly that girth along its whole span.
    */
   public static int getBranchMaxThickness() { return branchMaxThickness; }

   /**
    * Hard cap on the vertical span of a single branch (upper node Y minus
    * lower node Y). Keeps the arch from spanning an entire skyscraper --
    * pairing simply skips any upper/lower combination that would exceed
    * this, rather than clamping or scaling the result.
    */
   public static int getBranchMaxHeight() { return branchMaxHeight; }

   /** Shortest allowed 3D distance between a branch's two anchors. Pairs shorter than this (including after a wall collision shortens the span) are rejected. */
   public static int getBranchMinSpan() { return branchMinSpan; }

   /**
    * NOTE: no longer enforced as a diagonal ceiling in
    * DecayProviderNormal.generateGrowth()'s pairing loop -- combining it
    * with getBranchMaxVerticalToHorizontalRatio()'s horizontal floor made
    * it silently cap vertical drop far short of getBranchMaxHeight() for
    * any branch that also had to travel sideways, which fought the
    * intent of getBranchMaxHeight() being a pure Y-only limit. The
    * diagonal is bounded anyway, just implicitly, by
    * getBranchMaxHeight() and getBranchMaxHorizontalSpan() each capping
    * their own axis. Kept only for getBranchMinSpan()'s companion/floor
    * role and any external callers that may still reference it.
    */
   public static int getBranchMaxSpan() { return branchMaxSpan; }

   /**
    * Longest allowed horizontal (X/Z-only) offset for a single branch or
    * ground-chain segment -- independent of getBranchMaxHeight() (its Y-
    * only counterpart). Without its own ceiling, a segment satisfying
    * getBranchMaxVerticalToHorizontalRatio()'s horizontal floor for a
    * large vertical drop had no way to be reached at all except by
    * borrowing getBranchMaxSpan()'s diagonal budget -- silently making
    * getBranchMaxHeight() unreachable rather than actually bounding
    * sideways reach on its own terms (see getBranchMaxSpan()'s own note).
    */
   public static int getBranchMaxHorizontalSpan() { return branchMaxHorizontalSpan; }

   /**
    * How deep the branch sags below the straight line between its two
    * anchors, as a fraction of the horizontal distance between them (a
    * cheap parabolic approximation of a catenary droop, not a true
    * catenary solve -- see DecayProviderNormal.buildBranch()). 0 would be
    * a straight line; higher values read as a slacker, heavier hang.
    */
   public static double getBranchSagRatio() { return branchSagRatio; }

   /** Chance per step in the middle of a branch's sag to also drop a short hanging cluster of leaves -- see getBranchHangMinLength()/getBranchHangMaxLength(). */
   public static int getBranchHangChancePercent() { return branchHangChancePercent; }

   /** Shortest a branch's hanging leaf clusters can drop. */
   public static int getBranchHangMinLength() { return branchHangMinLength; }

   /** Longest a branch's hanging leaf clusters can drop -- deliberately short by default so they read as texture, not as their own vines. */
   public static int getBranchHangMaxLength() { return branchHangMaxLength; }

   /**
    * How many extra rounds of node seeding to try if every currently
    * available upper node fails to pair with any lower node (no line of
    * sight, out of span/height range). Each round seeds another
    * 2 * getBranchMaxCount() candidates and folds them into the existing
    * pools rather than starting over. 0 means give up with fewer branches
    * than targeted rather than reseed.
    */
   public static int getBranchNodeReseedAttempts() { return branchNodeReseedAttempts; }

   /**
    * How many blocks past a structure's own bounding box branch/slime
    * growth is allowed to wander -- same reasoning as the old
    * leavesBoundsMargin: a small allowance for natural overhang, not an
    * invitation to cross onto a neighbouring, not-yet-pasted lot.
    */
   public static int getBranchBoundsMargin() { return branchBoundsMargin; }
   public static boolean isBranchConstrainToOwnLot() { return branchConstrainToOwnLot; }

   /**
    * How many rings of actual neighbouring builds must already be placed
    * before a build's own cached growth is allowed to flush -- see
    * branchNeighbourDepth's field javadoc for the full reasoning and
    * DecayProviderNormal.neighbourhoodReady() for the implementation.
    *
    * Every build (every Parcel) still rolls and places its OWN full
    * growth budget independently -- nothing pools multiple buildings'
    * candidates into one shared roll. What this setting controls is
    * purely WHEN that roll is allowed to fire: waiting for a couple of
    * rings of real neighbours to already be pasted is what lets a
    * branch's target actually land on finished geometry across a lot
    * line or a street (see the withinBounds() fallback in
    * DecayProviderNormal), instead of every building's growth simply
    * clipping at its own bounds because its neighbours hadn't been
    * placed yet when it happened to flush.
    */
   public static int getBranchNeighbourDepth() { return branchNeighbourDepth; }

   /**
    * See branchFlushTimeoutTicks' field javadoc -- the per-build watchdog
    * timeout for the "wait for my own neighbourhood" gate in live play,
    * where a build near a tile edge or an unvisited corner of the map may
    * never see every one of its neighbours populate.
    */
   public static int getBranchFlushTimeoutTicks() { return branchFlushTimeoutTicks; }

   /**
    * See streetBranchSeedChancePercent's field javadoc -- the % of a
    * street parcel's own supportHits kept as branch/slime seed
    * candidates when DecayProviderNormal.thinStreetGrowthCandidates()
    * thins it.
    */
   public static int getStreetBranchSeedChancePercent() { return streetBranchSeedChancePercent; }

   /**
    * How much farther a branch/chain segment must travel horizontally
    * than it does vertically -- a segment dropping `dy` blocks must cover
    * at least `dy / ratio` blocks sideways, so a ratio of 3 means "at
    * least a third of the height difference in horizontal distance"
    * (higher ratio = more sideways, less straight-down). 0 disables the
    * check entirely (any drop is allowed, however steep). Enforced both
    * for normal branch/aim pairing and for chainBranchToGround()'s
    * descent segments -- see DecayProviderNormal.generateGrowth().
    */
   public static double getBranchMaxVerticalToHorizontalRatio() { return branchMaxVerticalToHorizontalRatio; }

   /**
    * How close (in blocks, straight down) a branch's lowest anchor needs
    * to land to solid ground before chainBranchToGround() stops adding
    * more descending segments. 0 disables ground-chaining entirely --
    * branches stop exactly where their own pairing/raytrace left them, as
    * before this feature existed.
    */
   public static int getBranchGroundConnectDistance() { return branchGroundConnectDistance; }

   /**
    * Hard cap on how many extra descending segments chainBranchToGround()
    * will add to a single branch in pursuit of the ground -- keeps a
    * branch seeded high above an unreachable spot (open sky, a chasm)
    * from recursing indefinitely. This is a hard stop, not a "keep trying
    * until it succeeds" budget: if the chain still hasn't reached the
    * ground once this cap is hit, it ends there anyway, still hanging --
    * see getBranchChainAttempts() for the (separate) per-segment retry
    * budget, and chainBranchToGround()'s own javadoc for why the chain
    * as a whole is deliberately allowed to fall short.
    */
   public static int getBranchMaxChainLength() { return branchMaxChainLength; }

   /**
    * How many different directions/distances chainBranchToGround() will
    * try for a SINGLE descending segment before giving up on that
    * segment (and therefore the rest of the chain) -- mirrors how the
    * top-level branch pairing loop tries a whole pool of candidate aims
    * rather than betting everything on one roll. Does not extend how
    * many segments the chain as a whole is allowed -- see
    * getBranchMaxChainLength().
    */
   public static int getBranchChainAttempts() { return branchChainAttempts; }

   // -------------------------------------------------------------------------
   // Slime growth: the low, directional, snaking spread seeded from each
   // branch's two anchor points (or standalone, wherever a branch could
   // not be formed) -- see DecayProviderNormal.trySeedSlime().
   // -------------------------------------------------------------------------

   /** Hard cap on how many blocks a single slime trail can extend. */
   public static int getSlimeMaxLength() { return slimeMaxLength; }

   /** Chance a slime trail survives to its next block, once already started. Kept high by default -- the blob-vs-snake shape comes from branch/veer chance, not from trails dying early. */
   public static int getSlimeContinueChancePercent() { return slimeContinueChancePercent; }

   /** Chance each step to also split off a shorter side-trail. */
   public static int getSlimeBranchChancePercent() { return slimeBranchChancePercent; }

   /** Chance each step to pick a new direction instead of continuing straight, giving the trail its snaking (rather than dead-straight) look. */
   public static int getSlimeVeerChancePercent() { return slimeVeerChancePercent; }

   /**
    * Relative weight BlockFace.UP gets when a slime trail is adjacent to a
    * climbable wall (see hasAdjacentSupport()) and still under
    * getSlimeMaxClimbHeight(). UP is never even a candidate direction
    * without a wall present -- slime doesn't grow upward through open air.
    */
   public static int getSlimeUpWeight() { return slimeUpWeight; }

   /** Relative weight each horizontal direction gets when a slime trail picks or re-picks a direction. Compared against getSlimeUpWeight(). */
   public static int getSlimeHorizontalWeight() { return slimeHorizontalWeight; }

   /** Floor of the slime thickness range. */
   public static int getSlimeMinThickness() { return slimeMinThickness; }

   /** Ceiling of how wide (horizontally, perpendicular to travel) a slime trail can thicken. */
   public static int getSlimeMaxThicknessHorizontal() { return slimeMaxThicknessHorizontal; }

   /**
    * Ceiling of how tall a slime trail can thicken upward. Deliberately
    * kept low (default 1, meaning effectively no vertical thickening) so
    * the mass reads as wider than it is tall -- ground cover, not a bush.
    */
   public static int getSlimeMaxThicknessVertical() { return slimeMaxThicknessVertical; }

   /** Chance per trail step that thickness randomly winds up or down by 1 within its configured range. */
   public static int getSlimeThicknessChangeChancePercent() { return slimeThicknessChangeChancePercent; }

   /**
    * How many consecutive UP steps a slime trail may take while climbing a
    * wall before it's forced back to a horizontal direction. Only relevant
    * while a wall is actually present (see getSlimeUpWeight()) -- this is
    * what stops a climb from turning into an unbounded flagpole.
    */
   public static int getSlimeMaxClimbHeight() { return slimeMaxClimbHeight; }

   /**
    * How far (blocks) the exterior check looks upward before treating a
    * position as outdoors. A shorter bound tolerates normal building
    * overhangs while still excluding genuinely interior spaces.
    */
   public static int getExteriorCheckDistance()     { return exteriorCheckDistance;     }

   // -------------------------------------------------------------------------
   // Loot parameters
   // -------------------------------------------------------------------------

   private static final String pathEnableVerticalLootBias  = "generator.enableVerticalLootBias";
   private static final String pathVerticalLootMaxDistance = "generator.verticalLootMaxDistance";
   private static final String pathVerticalLootDelineation = "generator.verticalLootDelineation";

   private static boolean enableVerticalLootBias  = true;
   private static int     verticalLootMaxDistance = 140;
   private static double  verticalLootDelineation = 0.5;

   /**
    * When true, chests further from buildHeight are biased toward rolling
    * lootMaxLevel, and (as of verticalLootDelineation) chests AT street
    * level are correspondingly biased toward lootMinLevel instead of using
    * unbiased odds -- see VerticalLootBias for why the old formula let
    * lootMaxLevel loot leak all the way down to ground floor. Linear blend
    * from full lootMinLevel bias at street level to full lootMaxLevel bias
    * at verticalLootMaxDistance blocks away.
    */
   public static boolean isVerticalLootBiasEnabled() { return enableVerticalLootBias;  }
   public static int     getVerticalLootMaxDistance() { return verticalLootMaxDistance; }

   /**
    * Half-width, in levels, of the triangular kernel VerticalLootBias uses
    * to weight loot levels around the "ideal" level for a chest's height.
    * Smaller = sharper delineation between floors (adjacent floors barely
    * share any probability); larger = smoother blending across more
    * levels at once. 1.0 means only the target level and its immediate
    * neighbours get meaningful weight -- a chest whose ideal level is 2
    * has essentially no chance of rolling level 4 or 5.
    */
   public static double  getVerticalLootDelineation() { return verticalLootDelineation; }

   // -------------------------------------------------------------------------
   // Street/highway variant filtering (purely aesthetic -- roads/highways
   // place correctly either way; this just controls which look gets used)
   // -------------------------------------------------------------------------

   private static final String pathClearStreetsOnly = "generator.clearStreetsOnly";
   private static boolean clearStreetsOnly = false;

   /**
    * When true, street/highway schematics whose buildName or file path
    * contains "OBSTRUCT" (case-insensitive) are skipped at import time
    * entirely -- never cached, never a selectable candidate. Purely a
    * visual preference between damaged/rubble-strewn "obstruct" variants
    * and clean "clear" ones; not tied to any placement bug, since both
    * kinds place correctly.
    */
   public static boolean isClearStreetsOnly() {
      return clearStreetsOnly;
   }

   // -------------------------------------------------------------------------
   // New City mode (master toggle -- see the override block at the end of
   // loadFromFile() for what this actually forces)
   // -------------------------------------------------------------------------

   private static final String pathNewCityMode = "generator.newCityMode";
   private static boolean newCityMode = false;

   /**
    * Master toggle for a fully pristine, undecayed "new city" look.
    * Overrides several otherwise-independent settings at once -- applied
    * as the LAST step of loadFromFile(), after every individual value
    * below has already been read from config.yml, so it always wins
    * regardless of what those individual keys are separately set to:
    *
    *   - decayOverride forced true (master intensities below drive every
    *     building's decay, ignoring any per-schematic decayIntensityInPercent)
    *   - masterDecayIntensity, masterBlockDecayIntensity,
    *     masterGrowthIntensity, masterBranchIntensity, masterVineIntensity
    *     all forced to 0 --
    *     each is a 0-100 multiplier applied on top of a building's own
    *     base intensity (see SchematicConfig.getDecayOption()), so 0
    *     zeroes out destructive decay, block-transform decay, leaf
    *     growth, and vine growth completely regardless of any building's
    *     individual settings, without needing to touch anything else
    *     (branchMaxCount, slimeMaxLength, vineMaxLength, the various chance percentages,
    *     etc. -- all become moot once their governing scale is 0).
    *   - clearStreetsOnly forced true (only clean, non-"OBSTRUCT" street/
    *     highway variants get imported)
    *
    * Individual config.yml values for all of the above are left
    * unmodified on disk -- this only overrides what ends up in memory at
    * runtime, so turning newCityMode back off later restores whatever
    * those keys were actually set to.
    */
   public static boolean isNewCityMode() {
      return newCityMode;
   }

   // -------------------------------------------------------------------------
   // Load from file
   // -------------------------------------------------------------------------

   public static void loadFromFile(FileConfiguration cfg) {
      // Console output
      debugOutput   = cfg.getBoolean(pathDebug,          debugOutput);
      debugCutouts  = cfg.getBoolean(pathDebugCutouts,   debugCutouts);
      debugAsciiMap = cfg.getBoolean(pathDebugAsciiMap,  debugAsciiMap);
      debugDistricts = cfg.getBoolean(pathDebugDistricts, debugDistricts);
      debugDistrictsVerbose = cfg.getBoolean(pathDebugDistrictsVerbose, false);
      debugDecay    = cfg.getBoolean(pathDebugDecay,     debugDecay);
      debugBranchNodes = cfg.getBoolean(pathDebugBranchNodes, debugBranchNodes);
      debugBranchNodesVerbose = cfg.getBoolean(pathDebugBranchNodesVerbose, debugBranchNodesVerbose);
      debugFillers = cfg.getBoolean(pathDebugFillers, debugFillers);
      debugFillerRolls = cfg.getBoolean(pathDebugFillerRolls, debugFillerRolls);
      debugFillerQueries = cfg.getBoolean(pathDebugFillerQueries, debugFillerQueries);
      debugFillerCandidates = cfg.getBoolean(pathDebugFillerCandidates, debugFillerCandidates);
      debugFillerRejections = cfg.getBoolean(pathDebugFillerRejections, debugFillerRejections);
      debugFillerSelections = cfg.getBoolean(pathDebugFillerSelections, debugFillerSelections);
      debugFillerFallbacks = cfg.getBoolean(pathDebugFillerFallbacks, debugFillerFallbacks);
      debugFillerResults = cfg.getBoolean(pathDebugFillerResults, debugFillerResults);
      debugDenseResidentialFamilies = cfg.getBoolean(pathDebugDenseResidentialFamilies, debugDenseResidentialFamilies);
      debugRiver = cfg.getBoolean(pathDebugRiver, debugRiver);
      debugCreekPaintLifecycle = cfg.getBoolean(pathDebugCreekPaintLifecycle, false);
      debugCreekPhysicalPaint = cfg.getBoolean(pathDebugCreekPhysicalPaint, false);
      debugReclaimRoads = cfg.getBoolean(pathDebugReclaimRoads, debugReclaimRoads);
      debugOcean = cfg.getBoolean(pathDebugOcean, debugOcean);
      debugLandmarks = cfg.getBoolean(pathDebugLandmarks, debugLandmarks);
      debugShore = cfg.getBoolean(pathDebugShore, debugShore);

      // Feature toggles
      chestRenaming          = cfg.getBoolean(pathChestRenaming,          chestRenaming);
      spawnerPlacing         = cfg.getBoolean(pathSpawnerPlacing,         spawnerPlacing);
      crossContextPlacing    = cfg.getBoolean(pathCrossContextPlacing,    crossContextPlacing);
      directionFallbackPlacing = cfg.getBoolean(pathDirectionFallbackPlacing, directionFallbackPlacing);
      noLaggRelighting       = cfg.getBoolean(pathNoLaggRelighting,       noLaggRelighting);
      enableFlippedVariants  = cfg.getBoolean(pathEnableFlippedVariants,  enableFlippedVariants);
      flippedOnlyForTesting  = cfg.getBoolean(pathFlippedOnlyForTesting,  flippedOnlyForTesting);

      // District / generation
      buildChance    = cfg.getInt(pathBuildChance,    buildChance);
      fillerChance   = cfg.getInt(pathFillerChance,   fillerChance);
      iterations     = cfg.getInt(pathIterations,     iterations);
      dynamicIterations     = cfg.getBoolean(pathDynamicIterations, dynamicIterations);
      dynamicTargetCoverage = Math.max(1, Math.min(100, cfg.getInt(pathDynamicTargetCoverage, dynamicTargetCoverage)));
      dynamicMaxIterations  = Math.max(1, cfg.getInt(pathDynamicMaxIterations, dynamicMaxIterations));
      blockSize      = cfg.getInt(pathBlockSize,       blockSize);
      blockSizeMin   = Math.max(1, cfg.getInt(pathBlockSizeMin, blockSizeMin));
      blockSizeSlack = Math.max(0, cfg.getInt(pathBlockSizeSlack, blockSizeSlack));
      districtMergeBackRatio = Math.max(0.0, cfg.getDouble(pathDistrictMergeBackRatio, districtMergeBackRatio));
      sigmaCut       = cfg.getInt(pathSigmaCut,        sigmaCut);
      cloneRadius    = cfg.getInt(pathCloneRadius,     cloneRadius);
      buildHeight    = cfg.getInt(pathBuildHeight,     buildHeight);
      caveChance     = cfg.getInt(pathCaveChance,      caveChance);
      variantRadius  = cfg.getInt(pathVariantRadius,   variantRadius);
      variantPenalty = cfg.getInt(pathVariantPenalty,  variantPenalty);
      variantHardBlock = cfg.getBoolean(pathVariantHardBlock, variantHardBlock);
      variantUniquePerDistrict = cfg.getBoolean(pathVariantUniquePerDistrict, variantUniquePerDistrict);
      cloneRadiusSoftLimit = cfg.getInt(pathCloneRadiusSoftLimit, cloneRadiusSoftLimit);
      clonePenalty    = cfg.getInt(pathClonePenalty,        clonePenalty);
      fillerExemptFromCloneRadius = cfg.getBoolean(pathFillerExemptFromCloneRadius, fillerExemptFromCloneRadius);
      fillerLargestAreaTolerancePercent = clampPercent(cfg.getInt(pathFillerLargestAreaTolerancePercent, fillerLargestAreaTolerancePercent));
      allowPureResidentialBuildAsFiller = cfg.getBoolean(pathAllowPureResidentialBuildAsFiller, allowPureResidentialBuildAsFiller);
      enableDenseResidential = cfg.getBoolean(pathEnableDenseResidential, enableDenseResidential);
      districtNeighborRules = cfg.getBoolean(pathDistrictNeighborRules, districtNeighborRules);
      morphomos = cfg.getBoolean(pathMorphomos, morphomos);
      forestGenerate = cfg.getBoolean(pathForestGenerate, forestGenerate);
      forestReliefAmplitude = Math.max(0.0, cfg.getDouble(pathForestReliefAmplitude, forestReliefAmplitude));
      forestReliefBias = Math.max(0.0, Math.min(1.0, cfg.getDouble(pathForestReliefBias, forestReliefBias)));
      forestEdgeBlend = Math.max(4, cfg.getInt(pathForestEdgeBlend, forestEdgeBlend));
      forestMountains = cfg.getBoolean(pathForestMountains, forestMountains);
      forestMountainMinCells = Math.max(1, cfg.getInt(pathForestMountainMinCells, forestMountainMinCells));
      forestMountainMinDepth = Math.max(1, cfg.getInt(pathForestMountainMinDepth, forestMountainMinDepth));
      forestMountainHeight = Math.max(0, Math.min(170, cfg.getInt(pathForestMountainHeight, forestMountainHeight)));
      forestMountainRamp = Math.max(16, cfg.getInt(pathForestMountainRamp, forestMountainRamp));
      forestMountainTreeLine = Math.max(0, cfg.getInt(pathForestMountainTreeLine, forestMountainTreeLine));
      forestMountainSnowLine = Math.max(0, cfg.getInt(pathForestMountainSnowLine, forestMountainSnowLine));
      forestMountainLowlands = Math.max(0, Math.min(90, cfg.getInt(pathForestMountainLowlands, forestMountainLowlands)));
      wildBiomes = cfg.getBoolean(pathWildBiomes, wildBiomes);
      wildHardBorders = cfg.getBoolean(pathWildHardBorders, wildHardBorders);
      wildScale = Math.max(40, cfg.getInt(pathWildScale, wildScale));
      wildZoomLevels = Math.max(3, Math.min(6, cfg.getInt(pathWildZoomLevels, wildZoomLevels)));
      wildTransitionPasses = Math.max(0, Math.min(2, cfg.getInt(pathWildTransitionPasses, wildTransitionPasses)));
      wildWarpPercent = Math.max(0, Math.min(100, cfg.getInt(pathWildWarpPercent, wildWarpPercent)));
      wildBlend = Math.max(0, Math.min(100, cfg.getInt(pathWildBlend, wildBlend)));
      wildMesaDesertMerge = Math.max(0, Math.min(512, cfg.getInt(pathWildMesaDesertMerge, wildMesaDesertMerge)));
      wildMesaFoot = Math.max(0, Math.min(100, cfg.getInt(pathWildMesaFoot, wildMesaFoot)));
      wildSmoothBlocks = Math.max(0, Math.min(512, cfg.getInt(pathWildSmoothBlocks, wildSmoothBlocks)));
      wildDecorations = Math.max(0, Math.min(400, cfg.getInt(pathWildDecorations, wildDecorations)));
      wildSpacingAll = Math.max(10, Math.min(1000, cfg.getInt(pathWildSpacing + "all", wildSpacingAll)));
      for (int i = 0; i < wildSpacing.length; i++) {
         wildSpacing[i] = Math.max(10, Math.min(1000, cfg.getInt(pathWildSpacing + wildSpacingNames[i], wildSpacing[i])));
      }
      wildChestOdds = Math.max(0, Math.min(100, cfg.getInt(pathWildChestOdds, wildChestOdds)));
      wildLootMin = Math.max(1, Math.min(5, cfg.getInt(pathWildLootMin, wildLootMin)));
      wildLootMax = Math.max(wildLootMin, Math.min(5, cfg.getInt(pathWildLootMax, wildLootMax)));
      for (WildBiome biome : WildBiome.ALL) {
         int configuredShare = Math.max(0, cfg.getInt(pathWildSharePrefix + biome.key(), biome.defaultShare()));
         // Keep old configs from silently retaining shares too small for the
         // rare-habitat pipeline. A configured zero remains an explicit disable.
         if (configuredShare > 0 && biome == WildBiome.VOLCANIC_BARRENS)
            configuredShare = Math.max(4, configuredShare);
         if (configuredShare > 0 && biome == WildBiome.BLOOMLANDS)
            configuredShare = Math.max(2, configuredShare);
         wildShares[biome.ordinal()] = configuredShare;
         int configuredAway = Math.max(0, cfg.getInt(pathWildAwayPrefix + biome.key(), biome.defaultAway()));
         // Migrate only the two historical defaults. Operator-tuned values
         // and explicit zero keep-away settings remain untouched.
         if (biome == WildBiome.VOLCANIC_BARRENS && configuredAway == 8) configuredAway = 6;
         if (biome == WildBiome.BLOOMLANDS && configuredAway == 12) configuredAway = 8;
         wildAways[biome.ordinal()] = configuredAway;
      }
      wildWetlandReach = Math.max(1, Math.min(24, cfg.getInt("generator.wilderness.geography.riverWetlandReachChunks", 12)));
      wildDuneReach = Math.max(32, Math.min(1024, cfg.getInt("generator.wilderness.geography.duneInlandBlocks", 420)));
      wildMinimumRegionBlocks = Math.max(0, Math.min(2048, cfg.getInt("generator.wilderness.geography.minimumRegionBlocks", 480)));
      wildWetlandCoreStrength = Math.max(0, Math.min(92, cfg.getInt("generator.wilderness.geography.wetlandCoreStrengthPercent", 86)));
      wildDuneCoreStrength = Math.max(0, Math.min(92, cfg.getInt("generator.wilderness.geography.duneCoreStrengthPercent", 86)));
      wildVolcanicCoreStrength = Math.max(0, Math.min(92, cfg.getInt("generator.wilderness.geography.volcanicCoreStrengthPercent", 88)));
      wildTransitions = cfg.isSet(pathWildTransitions)
              ? cfg.getStringList(pathWildTransitions).toArray(new String[0])
              : DEFAULT_WILD_TRANSITIONS;
      wildAwayMerge = Math.max(0, Math.min(200, cfg.getInt(pathWildAwayMerge, wildAwayMerge)));
      wildDuneScale = Math.max(25, Math.min(1000, cfg.getInt(pathWildDuneScale, wildDuneScale)));
      wildAwayVariance = Math.max(0, Math.min(100, cfg.getInt(pathWildAwayVariance, wildAwayVariance)));      wildMesaStep = Math.max(2, Math.min(16, cfg.getInt(pathWildMesaStep, wildMesaStep)));
      wildMesaLevels = Math.max(1, Math.min(8, cfg.getInt(pathWildMesaLevels, wildMesaLevels)));
      wildVanilla = cfg.getBoolean(pathWildVanilla, wildVanilla);
      if (!cfg.isSet(pathWildVanilla)) {
         Minions.i("config.yml has no generator.wilderness.vanilla section, so the vanilla wilderness is off. Bukkit never adds new keys to an existing "
                 + "config.yml: copy the section from the one inside the jar to switch it on.");
      }
      wildVanillaBlend = Math.max(1, Math.min(4, cfg.getInt(pathWildVanillaBlend, wildVanillaBlend)));
      wildVanillaVerge = Math.max(0, Math.min(16, cfg.getInt(pathWildVanillaVerge, wildVanillaVerge)));
      wildVanillaStructures = cfg.getBoolean(pathWildVanillaStructures, wildVanillaStructures);
      wildVanillaWorld = cfg.getString(pathWildVanillaWorld, wildVanillaWorld);
      wildVanillaOceans = cfg.getBoolean(pathWildVanillaOceans, wildVanillaOceans);
      forestTypeScale = Math.max(32, cfg.getInt(pathForestTypeScale, forestTypeScale));
      forestTreeDensity = Math.max(0.0, cfg.getDouble(pathForestTreeDensity, forestTreeDensity));
      forestSurfaceOffset = cfg.getInt(pathForestSurfaceOffset, forestSurfaceOffset);
      farmGenerate = cfg.getBoolean(pathFarmGenerate, farmGenerate);
      farmRuinPercent = Math.max(0, Math.min(100, cfg.getInt(pathFarmRuinPercent, farmRuinPercent)));
      farmFenceCrops = cfg.getBoolean(pathFarmFenceCrops, farmFenceCrops);
      farmRoadLinePercent = Math.max(0, Math.min(100, cfg.getInt(pathFarmRoadLinePercent, farmRoadLinePercent)));
      farmSurfaceOffset = cfg.getInt(pathFarmSurfaceOffset, farmSurfaceOffset);
      farmFarmsteadPercent = Math.max(0, Math.min(100, cfg.getInt(pathFarmFarmsteadPercent, farmFarmsteadPercent)));
      farmPastureShare = Math.max(0, Math.min(100, cfg.getInt(pathFarmPastureShare, farmPastureShare)));
      farmModernBarn = Math.max(0, Math.min(100, cfg.getInt(pathFarmModernBarn, farmModernBarn)));
      farmGreenhouse = Math.max(0, Math.min(100, cfg.getInt(pathFarmGreenhouse, farmGreenhouse)));
      farmOrchardShare = Math.max(0, Math.min(100, cfg.getInt(pathFarmOrchardShare, farmOrchardShare)));
      farmRoundBarn = Math.max(0, Math.min(100, cfg.getInt(pathFarmRoundBarn, farmRoundBarn)));
      farmOutbuildings = Math.max(0, Math.min(100, cfg.getInt(pathFarmOutbuildings, farmOutbuildings)));
      String skin = String.valueOf(cfg.getString(pathFarmTunnelSkin, farmTunnelSkin)).trim().toLowerCase();
      farmTunnelSkin = skin.equals("pane") || skin.equals("wool") ? skin : "glass";
      farmChestOdds = Math.max(0, Math.min(100, cfg.getInt(pathFarmChestOdds, farmChestOdds)));
      farmLootMinLevel = Math.max(1, Math.min(5, cfg.getInt(pathFarmLootMinLevel, farmLootMinLevel)));
      farmLootMaxLevel = Math.max(farmLootMinLevel, Math.min(5, cfg.getInt(pathFarmLootMaxLevel, farmLootMaxLevel)));
      cityRings = cfg.getBoolean(pathCityRings, cityRings);
      cityRingsRadius = Math.max(8, cfg.getInt(pathCityRingsRadius, cityRingsRadius));
      cityRingsSpacing = Math.max(16, cfg.getInt(pathCityRingsSpacing, cityRingsSpacing));
      cityRingsSizeVariation = Math.max(0, Math.min(90, cfg.getInt(pathCityRingsSizeVariation, cityRingsSizeVariation)));
      cityRingsEdgeWobble = Math.max(0, Math.min(100, cfg.getInt(pathCityRingsEdgeWobble, cityRingsEdgeWobble)));
      cityRingsFarmBandWidth = Math.max(0, cfg.getInt(pathCityRingsFarmBandWidth, cityRingsFarmBandWidth));
      cityRingsFarmForestOverlap = Math.max(0, cfg.getInt(pathCityRingsFarmForestOverlap, cityRingsFarmForestOverlap));
      highwayNetwork = cfg.getBoolean(pathHighwayNetwork, highwayNetwork);
      highwayNetworkBoxScale = Math.max(30, Math.min(100, cfg.getInt(pathHighwayNetworkBoxScale, highwayNetworkBoxScale)));
      highwayNetworkExtraLinks = Math.max(0, Math.min(100, cfg.getInt(pathHighwayNetworkExtraLinks, highwayNetworkExtraLinks)));
      highwayNetworkOceanMarginMin = Math.max(4, Math.min(200, cfg.getInt(pathHighwayNetworkOceanMarginMin, highwayNetworkOceanMarginMin)));
      highwayNetworkOceanMarginMax = Math.max(highwayNetworkOceanMarginMin, Math.min(200, cfg.getInt(pathHighwayNetworkOceanMarginMax, highwayNetworkOceanMarginMax)));
      highwayNetworkSingleSeamStreet = cfg.getBoolean(pathHighwayNetworkSingleSeamStreet, highwayNetworkSingleSeamStreet);
      districtNeighborRulesCrossTile = cfg.getBoolean(pathDistrictNeighborRulesCrossTile, districtNeighborRulesCrossTile);
      districtNeighborRulesRoadGap = Math.max(1, cfg.getInt(pathDistrictNeighborRulesRoadGap, districtNeighborRulesRoadGap));
      denseResidentialCloneRadiusSoftLimit = Math.max(0, cfg.getInt(pathDenseResidentialCloneRadiusSoftLimit, denseResidentialCloneRadiusSoftLimit));
      denseResidentialClonePenalty = cfg.getInt(pathDenseResidentialClonePenalty, denseResidentialClonePenalty);
      denseResidentialBuildChance = cfg.getInt(pathDenseResidentialBuildChance, denseResidentialBuildChance);
      denseResidentialBlockSize = Math.max(0, cfg.getInt(pathDenseResidentialBlockSize, denseResidentialBlockSize));
      denseResidentialBlockSizeMin = Math.max(1, cfg.getInt(pathDenseResidentialBlockSizeMin, denseResidentialBlockSizeMin));
      normalizeOddsOfAppearance = cfg.getBoolean(pathNormalizeOddsOfAppearance, normalizeOddsOfAppearance);
      fillerTerrainFallback = cfg.getBoolean(pathFillerTerrainFallback, fillerTerrainFallback);
      fillerTerrainNoiseIntensity = cfg.getInt(pathFillerTerrainNoiseIntensity, fillerTerrainNoiseIntensity);
      fillerTerrainMaxDeviation = cfg.getInt(pathFillerTerrainMaxDeviation, fillerTerrainMaxDeviation);
      fillerTerrainStoneDepth = cfg.getInt(pathFillerTerrainStoneDepth, fillerTerrainStoneDepth);
      maxCachedGridTiles = cfg.getInt(pathMaxCachedGridTiles, maxCachedGridTiles);
      extraCutMode            = cfg.getBoolean(pathExtraCutMode,            extraCutMode);
      extraCutFillerThreshold = cfg.getInt(pathExtraCutFillerThreshold,     extraCutFillerThreshold);
      extraCutBlockSize       = Math.max(1, cfg.getInt(pathExtraCutBlockSize, extraCutBlockSize));
      enableLandmarkDistricts      = cfg.getBoolean(pathEnableLandmarkDistricts, enableLandmarkDistricts);
      landmarkAttemptChancePercent = cfg.getInt(pathLandmarkAttemptChancePercent, landmarkAttemptChancePercent);
      landmarkMaxPerGrid           = Math.max(0, cfg.getInt(pathLandmarkMaxPerGrid, landmarkMaxPerGrid));
      landmarkCloneRadius          = Math.max(0, cfg.getInt(pathLandmarkCloneRadius, landmarkCloneRadius));
      landmarkMinSpacing           = Math.max(0, cfg.getInt(pathLandmarkMinSpacing, landmarkMinSpacing));
      landmarkZoneMatch            = cfg.getBoolean(pathLandmarkZoneMatch, landmarkZoneMatch);
      priorityLandmarkGroups = new ArrayList<>(cfg.getStringList(pathPriorityLandmarkGroups));
      if (!cfg.contains(pathPriorityLandmarkGroups)) {
         priorityLandmarkGroups.add("CentralPark");
         priorityLandmarkGroups.add("MegaMall");
      }

      // Decay
      decayOverride             = cfg.getBoolean(pathDecayOverride,             decayOverride);
      masterDecayIntensity      = cfg.getInt(pathMasterDecayIntensity,          masterDecayIntensity);
      masterBlockDecayIntensity = cfg.getInt(pathMasterBlockDecayIntensity,     masterBlockDecayIntensity);
      masterGrowthIntensity     = cfg.getInt(pathMasterGrowthIntensity,         masterGrowthIntensity);
      masterBranchIntensity     = cfg.getInt(pathMasterBranchIntensity,         masterBranchIntensity);
      masterVineIntensity       = cfg.getInt(pathMasterVineIntensity,           masterVineIntensity);
      vineMaxLength             = cfg.getInt(pathVineMaxLength,                 vineMaxLength);
      exteriorCheckDistance     = cfg.getInt(pathExteriorCheckDistance,         exteriorCheckDistance);

      legacyLeafDecayEnabled    = cfg.getBoolean(pathLegacyLeafDecayEnabled,    legacyLeafDecayEnabled);
      decayProtectOceanWaterline = cfg.getBoolean(pathDecayProtectOceanWaterline, decayProtectOceanWaterline);
      vanillaUnderworld = cfg.getBoolean(pathVanillaUnderworld, vanillaUnderworld);
      legacyLeafDecayIntensity  = Math.max(0, cfg.getInt(pathLegacyLeafDecayIntensity, legacyLeafDecayIntensity));
      legacyLeafDecayDepth      = Math.max(0, cfg.getInt(pathLegacyLeafDecayDepth,     legacyLeafDecayDepth));

      branchMinCount     = Math.max(0, cfg.getInt(pathBranchMinCount, branchMinCount));
      branchMaxCount     = Math.max(branchMinCount, cfg.getInt(pathBranchMaxCount, branchMaxCount));
      branchMinThickness = Math.max(1, cfg.getInt(pathBranchMinThickness, branchMinThickness));
      branchMaxThickness = Math.max(branchMinThickness, cfg.getInt(pathBranchMaxThickness, branchMaxThickness));
      branchMaxHeight    = Math.max(1, cfg.getInt(pathBranchMaxHeight, branchMaxHeight));
      branchMinSpan      = Math.max(1, cfg.getInt(pathBranchMinSpan, branchMinSpan));
      branchMaxSpan      = Math.max(branchMinSpan, cfg.getInt(pathBranchMaxSpan, branchMaxSpan));
      branchMaxHorizontalSpan = Math.max(1, cfg.getInt(pathBranchMaxHorizontalSpan, branchMaxHorizontalSpan));
      branchSagRatio     = Math.max(0.0, cfg.getDouble(pathBranchSagRatio, branchSagRatio));
      branchHangChancePercent = clampPercent(cfg.getInt(pathBranchHangChancePercent, branchHangChancePercent));
      branchHangMinLength = Math.max(0, cfg.getInt(pathBranchHangMinLength, branchHangMinLength));
      branchHangMaxLength = Math.max(branchHangMinLength, cfg.getInt(pathBranchHangMaxLength, branchHangMaxLength));
      branchNodeReseedAttempts = Math.max(0, cfg.getInt(pathBranchNodeReseedAttempts, branchNodeReseedAttempts));
      branchBoundsMargin = Math.max(0, cfg.getInt(pathBranchBoundsMargin, branchBoundsMargin));
      branchNeighbourDepth = Math.max(0, cfg.getInt(pathBranchNeighbourDepth, branchNeighbourDepth));
      branchFlushTimeoutTicks = Math.max(0, cfg.getInt(pathBranchFlushTimeoutTicks, branchFlushTimeoutTicks));
      streetBranchSeedChancePercent = clampPercent(cfg.getInt(pathStreetBranchSeedChancePercent, streetBranchSeedChancePercent));
      branchConstrainToOwnLot = cfg.getBoolean(pathBranchConstrainToOwnLot, branchConstrainToOwnLot);
      branchMaxVerticalToHorizontalRatio = Math.max(0.0, cfg.getDouble(pathBranchMaxVerticalToHorizontalRatio, branchMaxVerticalToHorizontalRatio));
      branchGroundConnectDistance = Math.max(0, cfg.getInt(pathBranchGroundConnectDistance, branchGroundConnectDistance));
      branchMaxChainLength = Math.max(0, cfg.getInt(pathBranchMaxChainLength, branchMaxChainLength));
      branchChainAttempts = Math.max(1, cfg.getInt(pathBranchChainAttempts, branchChainAttempts));

      slimeMaxLength             = Math.max(0, cfg.getInt(pathSlimeMaxLength, slimeMaxLength));
      slimeContinueChancePercent = clampPercent(cfg.getInt(pathSlimeContinueChancePercent, slimeContinueChancePercent));
      slimeBranchChancePercent   = clampPercent(cfg.getInt(pathSlimeBranchChancePercent, slimeBranchChancePercent));
      slimeVeerChancePercent     = clampPercent(cfg.getInt(pathSlimeVeerChancePercent, slimeVeerChancePercent));
      slimeUpWeight              = Math.max(0, cfg.getInt(pathSlimeUpWeight, slimeUpWeight));
      slimeHorizontalWeight      = Math.max(0, cfg.getInt(pathSlimeHorizontalWeight, slimeHorizontalWeight));
      slimeMinThickness          = Math.max(1, cfg.getInt(pathSlimeMinThickness, slimeMinThickness));
      slimeMaxThicknessHorizontal = Math.max(slimeMinThickness, cfg.getInt(pathSlimeMaxThicknessHorizontal, slimeMaxThicknessHorizontal));
      slimeMaxThicknessVertical  = Math.max(1, cfg.getInt(pathSlimeMaxThicknessVertical, slimeMaxThicknessVertical));
      slimeThicknessChangeChancePercent = clampPercent(cfg.getInt(pathSlimeThicknessChangeChancePercent, slimeThicknessChangeChancePercent));
      slimeMaxClimbHeight        = Math.max(0, cfg.getInt(pathSlimeMaxClimbHeight, slimeMaxClimbHeight));

      // River / bridges -- pushed straight into RiverConfig, which owns
      // the actual state (see its javadoc). Every getX() falls back to
      // RiverConfig's own current value -- rather than a value duplicated
      // here -- so RiverConfig's hardcoded defaults stay the single
      // source of truth if a key is missing from config.yml.
      RiverConfig.setRiverEnabled(cfg.getBoolean(pathRiverEnabled, RiverConfig.isRiverEnabled()));
      RiverConfig.setRiverBuildHeight(cfg.getInt(pathRiverBuildHeight, RiverConfig.getRiverBuildHeight()));
      RiverConfig.setRiverReclaimBuildHeight(cfg.getInt(pathRiverReclaimBuildHeight, RiverConfig.getRiverReclaimBuildHeight()));
      RiverConfig.setRiverStraight(cfg.getBoolean(pathRiverStraight, RiverConfig.isRiverStraight()));
      RiverConfig.setRiverBlockSize(Math.max(1, cfg.getInt(pathRiverBlockSize, RiverConfig.getRiverBlockSize())));
      RiverConfig.setRiverBankMargin(Math.max(0, cfg.getInt(pathRiverBankMargin, RiverConfig.getRiverBankMargin())));
      RiverConfig.setRiverDepth(Math.max(1, cfg.getInt(pathRiverDepth, RiverConfig.getRiverDepth())));
      RiverConfig.setRiverBridgeClearance(Math.max(0, cfg.getInt(pathRiverBridgeClearance, RiverConfig.getRiverBridgeClearance())));
      RiverConfig.setRiverWidthNarrow(Math.max(1, cfg.getInt(pathRiverWidthNarrow, RiverConfig.getRiverWidthNarrow())));
      RiverConfig.setRiverWidthMedium(Math.max(1, cfg.getInt(pathRiverWidthMedium, RiverConfig.getRiverWidthMedium())));
      RiverConfig.setRiverWidthWide(Math.max(1, cfg.getInt(pathRiverWidthWide, RiverConfig.getRiverWidthWide())));
      RiverConfig.setCreekMaxTraceChunks(Math.max(1, cfg.getInt(pathCreekMaxTraceChunks, RiverConfig.getCreekMaxTraceChunks())));
      RiverConfig.setCreekSiteChancePercent(Math.max(0, Math.min(100, cfg.getInt(pathCreekSiteChancePercent, RiverConfig.getCreekSiteChancePercent()))));
      RiverConfig.setCreekSiteAttempts(Math.max(1, cfg.getInt(pathCreekSiteAttempts, RiverConfig.getCreekSiteAttempts())));
      RiverConfig.setRiverPeelMaxDimension(Math.max(1, cfg.getInt(pathRiverPeelMaxDimension, RiverConfig.getRiverPeelMaxDimension())));
      RiverConfig.setRiverReclaimEnabled(cfg.getBoolean(pathRiverReclaimEnabled, RiverConfig.isRiverReclaimEnabled()));
      // NOT clamped to 0 here, unlike most of the knobs above: the berth's
      // own setter floors it at one whole chunk, because that clearance is
      // the only thing standing between a reclaimed district and the
      // waterline. Anything lower is silently raised rather than honoured.
      RiverConfig.setRiverReclaimBerthChunks(cfg.getInt(pathRiverReclaimBerthChunks, RiverConfig.getRiverReclaimBerthChunks()));
      RiverConfig.setRiverReclaimMinDepth(Math.max(1, cfg.getInt(pathRiverReclaimMinDepth, RiverConfig.getRiverReclaimMinDepth())));
      RiverConfig.setRiverReclaimMinWidth(Math.max(1, cfg.getInt(pathRiverReclaimMinWidth, RiverConfig.getRiverReclaimMinWidth())));
      RiverConfig.setRiverReclaimMaxWidth(Math.max(1, cfg.getInt(pathRiverReclaimMaxWidth, RiverConfig.getRiverReclaimMaxWidth())));
      RiverConfig.setRiverBankMaxRunChunks(Math.max(0, cfg.getInt(pathRiverBankMaxRunChunks, RiverConfig.getRiverBankMaxRunChunks())));
      RiverConfig.setRiverMeanderSlowFrequency(cfg.getDouble(pathRiverMeanderSlowFrequency, RiverConfig.getRiverMeanderSlowFrequency()));
      RiverConfig.setRiverMeanderSlowAmplitude(cfg.getDouble(pathRiverMeanderSlowAmplitude, RiverConfig.getRiverMeanderSlowAmplitude()));
      RiverConfig.setRiverMeanderFastFrequency(cfg.getDouble(pathRiverMeanderFastFrequency, RiverConfig.getRiverMeanderFastFrequency()));
      RiverConfig.setRiverMeanderFastAmplitude(cfg.getDouble(pathRiverMeanderFastAmplitude, RiverConfig.getRiverMeanderFastAmplitude()));
      RiverConfig.setRiverMeanderIntensityFrequency(cfg.getDouble(pathRiverMeanderIntensityFrequency, RiverConfig.getRiverMeanderIntensityFrequency()));
      RiverConfig.setRiverMeanderIntensityMin(cfg.getDouble(pathRiverMeanderIntensityMin, RiverConfig.getRiverMeanderIntensityMin()));
      RiverConfig.setBoundedMaxLengthChunks(cfg.getInt(pathBoundedMaxLengthChunks, 192));
      RiverConfig.setRiverWaterColumnDepth(Math.max(1, cfg.getInt(pathRiverWaterColumnDepth, RiverConfig.getRiverWaterColumnDepth())));
      // Riverside trees / ground cover (RiverParcel.populateVegetation()).
      // Both off unless the config turns them on; chances are clamped to
      // 0..100 by RiverConfig's own setters.
      RiverConfig.setRiverTreesEnabled(cfg.getBoolean(pathRiverTreesEnabled, RiverConfig.isRiverTreesEnabled()));
      RiverConfig.setRiverTreesChancePercent(cfg.getInt(pathRiverTreesChancePercent, RiverConfig.getRiverTreesChancePercent()));
      RiverConfig.setRiverVegetationEnabled(cfg.getBoolean(pathRiverVegetationEnabled, RiverConfig.isRiverVegetationEnabled()));
      RiverConfig.setRiverVegetationChancePercent(cfg.getInt(pathRiverVegetationChancePercent, RiverConfig.getRiverVegetationChancePercent()));

      OceanConfig.setOceanEnabled(cfg.getBoolean(pathOceanEnabled, OceanConfig.isOceanEnabled()));
      OceanConfig.setOceanPortsEnabled(cfg.getBoolean("generator.ocean.ports.enabled", OceanConfig.isOceanPortsEnabled()));
      OceanConfig.setOceanPortsDebug(cfg.getBoolean("generator.ocean.ports.debug", OceanConfig.isOceanPortsDebug()));
      OceanConfig.setOceanPortsChancePercent(cfg.getInt("generator.ocean.ports.chancePercent", OceanConfig.getOceanPortsChancePercent()));
      OceanConfig.setOceanBuildHeight(cfg.getInt(pathOceanBuildHeight, OceanConfig.getOceanBuildHeight()));
      OceanConfig.setOceanDepth(Math.max(1, cfg.getInt(pathOceanDepth, OceanConfig.getOceanDepth())));
      OceanConfig.setOceanSizeTiles(cfg.getDouble(pathOceanSizeTiles, OceanConfig.getOceanSizeTiles()));
      OceanConfig.setOceanBankMargin(Math.max(0, cfg.getInt(pathOceanBankMargin, OceanConfig.getOceanBankMargin())));
      OceanConfig.setOceanBankRunBlocks(Math.max(0, cfg.getInt(pathOceanBankRunBlocks, OceanConfig.getOceanBankRunBlocks())));
      OceanConfig.setOceanEnvelopeMargin(Math.max(0, cfg.getInt(pathOceanEnvelopeMargin, OceanConfig.getOceanEnvelopeMargin())));
      OceanConfig.setOceanMaxDistanceFromSpawn(Math.max(1, cfg.getInt(pathOceanMaxDistanceFromSpawn, OceanConfig.getOceanMaxDistanceFromSpawn())));
      OceanConfig.setOceanEdgeNoiseAmplitudeFraction(cfg.getDouble(pathOceanEdgeNoiseAmplitudeFraction, OceanConfig.getOceanEdgeNoiseAmplitudeFraction()));
      OceanConfig.setOceanEdgeNoiseFrequency(cfg.getDouble(pathOceanEdgeNoiseFrequency, OceanConfig.getOceanEdgeNoiseFrequency()));
      OceanConfig.setOceanEdgeNoiseOctaves(Math.max(1, cfg.getInt(pathOceanEdgeNoiseOctaves, OceanConfig.getOceanEdgeNoiseOctaves())));
      OceanConfig.setOceanCoastJitterBlocks(Math.max(0, cfg.getDouble(pathOceanCoastJitterBlocks, OceanConfig.getOceanCoastJitterBlocks())));
      OceanConfig.setOceanCoastJitterFrequency(cfg.getDouble(pathOceanCoastJitterFrequency, OceanConfig.getOceanCoastJitterFrequency()));
      OceanConfig.setOceanShoreRampBlocks(Math.max(1, cfg.getInt(pathOceanShoreRampBlocks, OceanConfig.getOceanShoreRampBlocks())));
      OceanConfig.setOceanMaxWaterColumnDepth(Math.max(1, cfg.getInt(pathOceanMaxWaterColumnDepth, OceanConfig.getOceanMaxWaterColumnDepth())));
      OceanConfig.setOceanShallowDepth(cfg.getInt(pathOceanShallowDepth, OceanConfig.getOceanShallowDepth()));
      OceanConfig.setOceanDeepDepth(cfg.getInt(pathOceanDeepDepth, OceanConfig.getOceanDeepDepth()));
      OceanConfig.setOceanShallowEndBlocks(cfg.getInt(pathOceanShallowEndBlocks, OceanConfig.getOceanShallowEndBlocks()));
      OceanConfig.setOceanShallowToMidBlocks(cfg.getInt(pathOceanShallowToMidBlocks, OceanConfig.getOceanShallowToMidBlocks()));
      OceanConfig.setOceanDeepStartBlocks(cfg.getInt(pathOceanDeepStartBlocks, OceanConfig.getOceanDeepStartBlocks()));
      OceanConfig.setOceanMidToDeepBlocks(cfg.getInt(pathOceanMidToDeepBlocks, OceanConfig.getOceanMidToDeepBlocks()));
      OceanConfig.setOceanZoneBoundaryNoiseFraction(cfg.getDouble(pathOceanZoneBoundaryNoise, OceanConfig.getOceanZoneBoundaryNoiseFraction()));
      OceanConfig.setOceanShallowReliefScale(cfg.getDouble(pathOceanShallowReliefScale, OceanConfig.getOceanShallowReliefScale()));
      OceanConfig.setOceanDeepReliefScale(cfg.getDouble(pathOceanDeepReliefScale, OceanConfig.getOceanDeepReliefScale()));
      OceanConfig.setOceanStretch(Math.max(1.0, cfg.getDouble(pathOceanStretch, OceanConfig.getOceanStretch())));
      OceanConfig.setOceanWarpFraction(Math.max(0.0, cfg.getDouble(pathOceanWarpFraction, OceanConfig.getOceanWarpFraction())));
      OceanConfig.setOceanBayFraction(Math.max(0.0, cfg.getDouble(pathOceanBayFraction, OceanConfig.getOceanBayFraction())));
      OceanConfig.setOceanInletFraction(Math.max(0.0, cfg.getDouble(pathOceanInletFraction, OceanConfig.getOceanInletFraction())));
      OceanConfig.setOceanIslandFraction(Math.max(0.0, cfg.getDouble(pathOceanIslandFraction, OceanConfig.getOceanIslandFraction())));
      OceanConfig.setOceanSeabedReliefFraction(Math.max(0.0, cfg.getDouble(pathOceanSeabedReliefFraction, OceanConfig.getOceanSeabedReliefFraction())));
      OceanConfig.setOceanSeabedDetailsEnabled(cfg.getBoolean(pathOceanSeabedDetailsEnabled, OceanConfig.isOceanSeabedDetailsEnabled()));
      OceanConfig.setOceanSeabedPatchSizeBlocks(cfg.getInt(pathOceanSeabedPatch, OceanConfig.getOceanSeabedPatchSizeBlocks()));
      OceanConfig.setOceanSeabedRockPercent(cfg.getInt(pathOceanSeabedRock, OceanConfig.getOceanSeabedRockPercent()));
      OceanConfig.setOceanSeabedSeagrassPercent(cfg.getInt(pathOceanSeabedGrass, OceanConfig.getOceanSeabedSeagrassPercent()));
      OceanConfig.setOceanSeabedKelpPercent(cfg.getInt(pathOceanSeabedKelp, OceanConfig.getOceanSeabedKelpPercent()));
      OceanConfig.setOceanSeabedRippleBlocks(cfg.getDouble(pathOceanSeabedShelfWaves, OceanConfig.getOceanSeabedRippleBlocks()));
      OceanConfig.setOceanSeabedMidVariationBlocks(cfg.getDouble(pathOceanSeabedMidWaves, OceanConfig.getOceanSeabedMidVariationBlocks()));
      OceanConfig.setOceanSeabedBedformScaleBlocks(cfg.getDouble(pathOceanSeabedBedformSize, OceanConfig.getOceanSeabedBedformScaleBlocks()));
      OceanConfig.setOceanMinimumNavigableDepthBlocks(cfg.getInt(pathOceanMinNavigableDepth, OceanConfig.getOceanMinimumNavigableDepthBlocks()));
      OceanConfig.setOceanRiverHandoffBlocks(Math.max(0.0, cfg.getDouble(pathOceanRiverHandoffBlocks, OceanConfig.getOceanRiverHandoffBlocks())));
      OceanConfig.setOceanMouthFlare(Math.max(1.0, cfg.getDouble(pathOceanMouthFlare, OceanConfig.getOceanMouthFlare())));
      OceanConfig.setOceanMouthZoneBlocks(Math.max(0.0, cfg.getDouble(pathOceanMouthZoneBlocks, OceanConfig.getOceanMouthZoneBlocks())));
      OceanConfig.setOceanMouthFilletBlocks(Math.max(0.0, cfg.getDouble(pathOceanMouthFilletBlocks, OceanConfig.getOceanMouthFilletBlocks())));
      OceanConfig.setOceanTreesEnabled(cfg.getBoolean(pathOceanTreesEnabled, OceanConfig.isOceanTreesEnabled()));
      OceanConfig.setOceanTreesChancePercent(cfg.getInt(pathOceanTreesChancePercent, OceanConfig.getOceanTreesChancePercent()));
      OceanConfig.setOceanVegetationEnabled(cfg.getBoolean(pathOceanVegetationEnabled, OceanConfig.isOceanVegetationEnabled()));
      OceanConfig.setOceanVegetationChancePercent(cfg.getInt(pathOceanVegetationChancePercent, OceanConfig.getOceanVegetationChancePercent()));
      OceanConfig.setOceanBoatsEnabled(cfg.getBoolean(pathOceanBoatsEnabled, OceanConfig.isOceanBoatsEnabled()));
      OceanConfig.setOceanBoatsBigCloneRadius(cfg.getInt(pathOceanBoatsBigCloneRadius, OceanConfig.getOceanBoatsBigCloneRadius()));
      OceanConfig.setOceanBoatsBigCloneRadiusSoftLimit(cfg.getInt(pathOceanBoatsBigCloneRadiusSoftLimit, OceanConfig.getOceanBoatsBigCloneRadiusSoftLimit()));
      OceanConfig.setOceanBoatsBigMinChunks(cfg.getInt(pathOceanBoatsBigMinChunks, OceanConfig.getOceanBoatsBigMinChunks()));
      OceanConfig.setOceanBoatsSpacingBlocks(cfg.getInt(pathOceanBoatsSpacingBlocks, OceanConfig.getOceanBoatsSpacingBlocks()));
      RiverConfig.setRiverConvergeOnOcean(cfg.getBoolean(pathRiverConvergeOnOcean, RiverConfig.isRiverConvergeOnOcean()));
      RiverConfig.setRiverZBoundEnabled(cfg.getBoolean(pathRiverZBoundEnabled, RiverConfig.isRiverZBoundEnabled()));

      // getBoolean(path, default) above silently falls back to the built-in default (ocean OFF)
      // when the key is absent from the config.yml on disk -- and that file is never refreshed
      // from the jar's copy once it exists -- so say out loud which of the two happened.
      if (cfg.isSet(pathOceanEnabled)) {
         Minions.i("Ocean config: generator.ocean.enabled=%s (read from config.yml)", OceanConfig.isOceanEnabled());
      } else {
         Minions.i("Ocean config: generator.ocean.* is MISSING from config.yml -- ocean stays at its built-in default (enabled=%s). "
                         + "The config.yml in the plugin folder isn't updated from the jar's; add the generator.ocean block by hand or delete the file to regenerate it.",
                 OceanConfig.isOceanEnabled());
      }

      // Loot
      enableVerticalLootBias  = cfg.getBoolean(pathEnableVerticalLootBias,  enableVerticalLootBias);
      verticalLootMaxDistance = cfg.getInt(pathVerticalLootMaxDistance,      verticalLootMaxDistance);
      verticalLootDelineation = cfg.getDouble(pathVerticalLootDelineation,   verticalLootDelineation);
      clearStreetsOnly = cfg.getBoolean(pathClearStreetsOnly, clearStreetsOnly);

      newCityMode = cfg.getBoolean(pathNewCityMode, newCityMode);
      if (newCityMode) {
         // Deliberately applied last, after every value above has already
         // been read from config.yml, so this always wins regardless of
         // what those individual keys were separately set to. See
         // isNewCityMode()'s javadoc for exactly what and why.
         decayOverride = true;
         masterDecayIntensity = 0;
         masterBlockDecayIntensity = 0;
         masterGrowthIntensity = 0;
         masterBranchIntensity = 0;
         masterVineIntensity = 0;
         clearStreetsOnly = true;
         Minions.i("newCityMode enabled -- decay/growth/vines forced to 0 and clearStreetsOnly forced on for a pristine, undecayed city.");
      }
   }

   /**
    * Clamps a config value meant to be read as a percent (compared against
    * random.nextDouble() as value/100.0) into [0, 100]. Without this, a
    * value above 100 doesn't error or warn -- it silently satisfies
    * `random.nextDouble() < value/100.0` unconditionally, since
    * nextDouble() never reaches 1.0. That's exactly what happened with
    * leavesThicknessChancePercent: 120 in config.yml: it read as "always
    * true", firing on every single trail step with no way to tell from
    * behaviour alone that the number itself was out of range.
    */
   private static int clampPercent(int value) {
      return Math.max(0, Math.min(100, value));
   }
}