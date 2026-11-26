#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
PKG="$ROOT/src/main/java/ch/k42/metropolis/generator"
javac -d "$TMP" \
  "$PKG/ChunkyBackpressurePolicy.java" \
  "$ROOT/QA/R14_8/ChunkyBackpressurePolicyProbe.java"
java -cp "$TMP" ch.k42.metropolis.generator.ChunkyBackpressurePolicyProbe
