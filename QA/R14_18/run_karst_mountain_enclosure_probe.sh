#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
OUT="$ROOT/target/qa-r14-18-karst"
mkdir -p "$OUT"
javac -d "$OUT" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/WildBiome.java" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/Noise2D.java" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild/WildLayers.java" \
  "$ROOT/QA/R14_18/KarstMountainEnclosureProbe.java"
java -cp "$OUT" ch.k42.metropolis.grid.urbanGrid.wild.KarstMountainEnclosureProbe
