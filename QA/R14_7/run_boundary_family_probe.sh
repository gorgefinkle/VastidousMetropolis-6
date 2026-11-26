#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/enums/ContextType.java" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/layout/MorphomosBoundaryFamily.java" \
  "$ROOT/QA/R14_7/MorphomosBoundaryFamilyProbe.java"
java -cp "$TMP" ch.k42.metropolis.grid.urbanGrid.layout.MorphomosBoundaryFamilyProbe
