#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
GSON=$(find "$HOME/.m2/repository/com/google/code/gson/gson" -name 'gson-*.jar' | sort | tail -n 1)
test -n "$GSON"
PKG="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/parcel"
javac -cp "$GSON" -d "$TMP" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/enums/ContextType.java" \
  "$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/enums/SchematicType.java" \
  "$PKG/MetropolisBuildFlagExporter.java" \
  "$ROOT/QA/R14_8/BuildFlagExporterProbe.java"
java -cp "$TMP:$GSON" ch.k42.metropolis.grid.urbanGrid.parcel.BuildFlagExporterProbe
