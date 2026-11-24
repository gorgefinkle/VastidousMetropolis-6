#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
DRAIN="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/provider/BranchGrowthDrain.java"
NORMAL="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/provider/DecayProviderNormal.java"
LAYERS="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/WildLayers.java"
FOREST="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/forest/ForestGenerator.java"
! grep -q 'new ArrayList<>(this.pendingBuilds.keySet())' "$NORMAL"
! grep -q 'final Block block;' "$NORMAL"
grep -q 'pendingBuildsByGrid' "$NORMAL"
grep -q 'MAX_BUILDS_PER_TICK' "$DRAIN"
grep -q 'GENERATED_CHUNK_LOADS_PER_TICK' "$DRAIN"
grep -q 'placeKarstGroupsWithMountainRing' "$LAYERS"
grep -q 'VOLCANIC_BARRENS' "$LAYERS"
grep -q 'awayBlocks\[i\] = 0.0' "$FOREST"
printf '%s\n' 'R14.18 source contract probe: PASS'
