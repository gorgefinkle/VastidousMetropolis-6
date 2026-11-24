#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PKG="$ROOT/src/main/java/ch/k42/metropolis/grid/urbanGrid/river"
javac -d "$TMP" "$PKG/CreekCityOriginPolicy.java" "$ROOT/QA/R14_15/CreekCityOriginPolicyProbe.java"
java -Xmx128m -cp "$TMP" ch.k42.metropolis.grid.urbanGrid.river.CreekCityOriginPolicyProbe
