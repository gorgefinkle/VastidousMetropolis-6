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
LAKE="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/parcel/LakeParcel.java"
FOREST="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/ForestGenerator.java"

# Placement is deterministic and accepts only the feature's actual footprint.
grep -Fq 'private static final int MOUNTAIN_CLEARANCE = 18' "$FEATURES"
grep -Fq 'private static final int MOUNTAIN_THRESHOLD = 2' "$FEATURES"
grep -Fq 'private static final int LAKE_THRESHOLD = 10' "$FEATURES"
grep -Fq 'this.inOuterCity(x, z)' "$FEATURES"
grep -Fq 'CityRingField.withinOuterCity' "$FEATURES" || grep -Fq 'this.cities.withinOuterCity' "$FEATURES"
grep -Fq 'r > 1.0' "$FEATURES"
grep -Fq 'highwayMask.at(x, z)' "$FEATURES"

# Streams avoid the outer city/farm belt, avoid other rivers and creeks,
# and cross only perpendicular to a highway for at most two lane chunks.
grep -Fq 'if (this.inOuterCity(nx, nz)) continue;' "$FEATURES"
grep -Fq 'if (axes != 0 && (axes & moveAxis) != 0) continue' "$FEATURES"
grep -Fq 'private static final int MAX_HIGHWAY_CROSSING_CHUNKS = 2' "$FEATURES"
grep -Fq 'if (run > MAX_HIGHWAY_CROSSING_CHUNKS) continue' "$FEATURES"
grep -Fq 'this.water.isReceivingWaterAt(node.x, node.z)' "$FEATURES"
! grep -Fq 'this.water.kindAt(nx, nz)' "$FEATURES"

# Separate lake and stream owners are installed before the first layout solve.
grep -Fq 'LAKE, STREAM' "$WATER"
grep -Fq 'setLakes(features)' "$FEATURES"
grep -Fq 'setStreams(features)' "$FEATURES"
grep -Fq 'WildernessFeatures.install(seed)' "$GRID"

# Lakes paint a real basin; stream outlets reuse RiverParcel and preserve the river/creek highway handoff.
grep -Fq 'Material.WATER' "$LAKE"
grep -Fq 'RiverParcel.carveColumnWithCreeks' "$LAKE"
grep -Fq 'new LakeParcel' "$GRID"
grep -Fq 'this.placeStreamAt(x, z)' "$GRID"
grep -Fq 'new RiverParcel(this, new Cartesian2D(chunkX, chunkZ), stream)' "$GRID"
grep -Fq 'markHighwayCrossingZ' "$GRID"
grep -Fq 'markHighwayBoundaryX' "$GRID"

# The alpine massif overlays the ordinary wilderness terrain only within its own footprint.
grep -Fq 'ctx.wildernessMountainAt(x, z) != null' "$FOREST"
grep -Fq 'namedMountainHeight(x, z, base, naturalHeight)' "$FOREST"
grep -Fq 'BiomeNames.snowyMountains()' "$FOREST"
echo "WildernessLandmarksProbe PASS: named alpine massif, bounded named lakes, deterministic stream outlets, city avoidance and perpendicular highway crossings"
