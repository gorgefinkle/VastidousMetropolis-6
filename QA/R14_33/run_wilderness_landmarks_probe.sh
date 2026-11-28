#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

# The standalone probe exercises deterministic massif and lake geometry.
mvn -B -ntp -q dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile="$TMP/classpath"
CP="$ROOT/target/classes:$(cat "$TMP/classpath")"
javac -cp "$CP" -d "$TMP" "$ROOT/QA/R14_33/WildernessLandmarksProbe.java"
java -Xmx768m -cp "$TMP:$CP" ch.k42.metropolis.grid.urbanGrid.wild.WildernessLandmarksProbe

FEATURES="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/WildernessFeatures.java"
WATER="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/layout/WaterField.java"
GRID="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/UrbanGrid.java"
WATER_FIELDS="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/layout/WaterFields.java"
LAKE="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/parcel/LakeParcel.java"
FOREST="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/ForestGenerator.java"
PROFILE="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/WildernessTerrainProfile.java"

# Placement is deterministic and accepts only the feature's actual footprint.
grep -Fq 'int footprint=(int)Math.ceil((Math.max(candidate.radiusX,candidate.radiusZ)*1.13' "$FEATURES"
grep -Fq 'private static final int MOUNTAIN_CLEARANCE = 18' "$FEATURES"
grep -Fq 'private static final int MOUNTAIN_THRESHOLD = 2' "$FEATURES"
grep -Fq 'private static final int LAKE_THRESHOLD = 60' "$FEATURES"
grep -Fq 'this.cellSize = 192' "$FEATURES"
grep -Fq 'int radius = 4 +' "$FEATURES"
grep -Fq 'if (kind >= LAKE_THRESHOLD) return FeatureCell.EMPTY;' "$FEATURES"
grep -Fq 'int lakeReach = Math.max(24, this.cellSize / 2 - 44);' "$FEATURES"
grep -Fq 'final double[] scales={0.72,0.62,0.52,0.42,0.32};' "$PROFILE"
grep -Fq 'int side=29;' "$PROFILE"
grep -Fq 'candidateLimit=Math.min(scored.size(),Math.max(24,maximum*2))' "$PROFILE"
grep -Fq 'SHORE_WIDTH_BLOCKS = 32.0' "$FEATURES"
grep -Fq 'distanceInsideShoreBlock(double x,double z)' "$FEATURES"
grep -Fq 'Math.pow(t,1.28)' "$FEATURES"
grep -Fq 'continuous+relief' "$FEATURES"
grep -Fq 'world.setBiome(x,z,Biome.RIVER)' "$LAKE"
! grep -Fq 'Biome.OCEAN' "$LAKE"
! grep -Fq 'Biome.FOREST' "$LAKE"
! grep -Fq 'shelfEnd' "$FEATURES"
grep -Fq 'this.inOuterCity(x, z)' "$FEATURES"
grep -Fq 'this.cities.withinOuterCity' "$FEATURES"
grep -Fq 'r > 1.0' "$FEATURES"
grep -Fq 'highwayMask.at(x, z)' "$FEATURES"

# Streams avoid the outer city/farm belt, avoid other rivers and creeks,
# and cross only perpendicular to a highway for at most two lane chunks.
grep -Fq 'if(this.inOuterCity(nx,nz)){cityBlocks++;continue;}' "$FEATURES"
grep -Fq 'if(axes!=0&&(axes&moveAxis)!=0){highwayParallelBlocks++;continue;}' "$FEATURES"
grep -Fq 'private static final int MAX_HIGHWAY_CROSSING_CHUNKS = 2' "$FEATURES"
grep -Fq 'if(run>MAX_HIGHWAY_CROSSING_CHUNKS){highwayWidthBlocks++;continue;}' "$FEATURES"
grep -Fq 'if(node.highwayRun>0&&direction!=node.direction){highwayTurnBlocks++;continue;}' "$FEATURES"
grep -Fq 'WaterTarget endpoint=goals.get(cellKey(node.x,node.z))' "$FEATURES"
grep -Fq 'this.plannedCreekAt(nx,nz)&&!exactCreekGoal' "$FEATURES"
grep -Fq 'blockX[last]=confluence.blockX()' "$FEATURES"
grep -Fq 'this.convergenceRegistry.register(routed.target.key)' "$FEATURES"
grep -Fq 'HydrologyConvergenceKey.Receiver.OCEAN' "$FEATURES"
grep -Fq 'HydrologyConvergenceKey.Receiver.RIVER' "$FEATURES"
grep -Fq 'HydrologyConvergenceKey.Receiver.CREEK' "$FEATURES"
! grep -Fq 'this.water.kindAt(nx, nz)' "$FEATURES"

# One city-aware hydrology layer owns lake/stream geometry before the first layout solve.
grep -Fq 'LAKE, STREAM' "$WATER"
grep -Fq 'setHydrologyLayer(features)' "$FEATURES"
grep -Fq 'public interface HydrologyLayer' "$WATER"
grep -Fq 'HydrologySample sampleAt(WaterField water,int blockX,int blockZ)' "$WATER"
grep -Fq 'installCreeks(generator);' "$GRID"
grep -Fq 'WildernessFeatures.install(seed)' "$GRID"
grep -Fq 'WildernessFeatures.prepareForTile(seed, this.root.X, this.root.Y);' "$GRID"
grep -Fq 'sites.prepareForChunkArea(this.root.X-halo' "$GRID"
grep -Fq 'public synchronized void prepareForChunkArea' "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/river/BoundedRiverSites.java"

# Lake and stream renderers consume the unified hydrology ownership sample; stream highway handoff remains intact.
grep -Fq 'Material.WATER' "$LAKE"
grep -Fq 'HydrologySample sample=hydrology.hydrologySampleAt(x,z)' "$LAKE"
grep -Fq 'if(sample.feature()!=HydrologySample.Feature.LAKE)continue' "$LAKE"
grep -Fq 'sample.signedDistanceBlocks()' "$LAKE"
grep -Fq 'sample.depth()' "$LAKE"
grep -Fq 'sample.waterY()' "$LAKE"
grep -Fq 'new LakeParcel' "$GRID"
grep -Fq 'this.placeStreamAt(x, z)' "$GRID"
grep -Fq 'new RiverParcel(this, new Cartesian2D(chunkX, chunkZ), stream)' "$GRID"
grep -Fq 'sample.feature()!=HydrologySample.Feature.STREAM' "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/parcel/RiverParcel.java"
grep -Fq 'return new WildernessStreamCourse' "$FEATURES"
grep -Fq 'terrainProfile.findNearbyBasins' "$FEATURES"
grep -Fq 'this.waterY-terrainY' "$FEATURES"
grep -Fq 'this.terrainProfile.surfaceHeight' "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/WildernessFeatures.java"
grep -Fq 'convergenceKey()' "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/HydrologySample.java"
grep -Fq 'WildernessStreamCourse carrier = lake.stream' "$FEATURES"
grep -Fq 'forChunk(chunkX, chunkZ)' "$GRID"
grep -Fq 'markHighwayCrossingZ' "$GRID"
grep -Fq 'markHighwayBoundaryX' "$GRID"

# The alpine massif overlays the ordinary wilderness terrain only within its own footprint.
grep -Fq 'ctx.wildernessMountainAt(x, z) != null' "$FOREST"
grep -Fq 'namedMountainHeight(x, z, base, naturalHeight)' "$FOREST"
grep -Fq 'BiomeNames.snowyMountains()' "$FOREST"
echo "WildernessLandmarksProbe PASS: named alpine massif, irregular freshwater coast, shelf-free continuous depth, finite bank, deterministic outlets and highway crossings"
