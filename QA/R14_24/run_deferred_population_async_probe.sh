#!/bin/sh
set -eu
SOURCE=src/main/java/ch/k42/metropolis/generator/MetropolisGenerator.java
grep -Fq 'getChunkAtAsync(job.x(), job.z(), false, false)' "$SOURCE"
grep -Fq 'finishDeferredLoad(job, loaded, loadFailure)' "$SOURCE"
if sed -n '/private void scheduleDrain/,/private void finishDeferredLoad/p' "$SOURCE" | grep -E 'getChunkAt\\('; then
  echo "FAIL: deferred-population drain still performs a synchronous chunk load" >&2
  exit 1
fi
echo "DeferredPopulationAsyncLoadProbe PASS: queued chunks are staged asynchronously without sync getChunkAt"
