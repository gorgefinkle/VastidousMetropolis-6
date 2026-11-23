#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PKG="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/forest"
javac -d "$TMP" \
  "$PKG/KarstLandforms.java" \
  "$PKG/KarstSpanVolume.java" \
  "$ROOT/QA/R14_9/KarstMountainProfileProbe.java"
java -cp "$TMP" ch.k42.metropolis.grid.urbanGrid.forest.KarstMountainProfileProbe
