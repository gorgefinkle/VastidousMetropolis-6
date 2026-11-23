#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PKG="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/wild"
javac -d "$TMP" \
  "$PKG/KarstCoastalExclusion.java" \
  "$ROOT/QA/R14_8/KarstCoastalExclusionProbe.java"
java -cp "$TMP" ch.k42.metropolis.grid.urbanGrid.wild.KarstCoastalExclusionProbe
