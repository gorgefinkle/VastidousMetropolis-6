#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/target/r14-35-qa"
mkdir -p "$OUT"
javac --release 25 -d "$OUT" \
  "$ROOT/src/main/java/ch/k42/metropolis/generator/ChunkyBackpressurePolicy.java" \
  "$ROOT/QA/R14_35/ChunkyQueueFirstRecoveryProbe.java"
java -cp "$OUT" ch.k42.metropolis.generator.ChunkyQueueFirstRecoveryProbe
