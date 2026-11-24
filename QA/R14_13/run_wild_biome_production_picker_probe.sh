#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
mvn -B -ntp -q dependency:build-classpath -DincludeScope=compile -Dmdep.outputFile="$TMP/classpath"
CP="$ROOT/target/classes:$(cat "$TMP/classpath")"
javac -cp "$CP" -d "$TMP" "$ROOT/QA/R14_13/WildBiomeProductionPickerProbe.java"
java -Xmx768m -cp "$TMP:$CP" ch.k42.metropolis.grid.urbanGrid.forest.WildBiomeProductionPickerProbe
