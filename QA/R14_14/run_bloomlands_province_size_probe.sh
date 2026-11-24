#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PKG="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild"
javac -d "$TMP" "$PKG/WildBiome.java" "$PKG/Noise2D.java" "$PKG/WildLayers.java" "$PKG/WildBiomes.java" "$ROOT/QA/R14_14/BloomlandsProvinceSizeProbe.java"
java -Xmx256m -cp "$TMP" ch.k42.metropolis.grid.urbanGrid.wild.BloomlandsProvinceSizeProbe
