#!/bin/sh
set -eu
SOURCE=src/main/java/ch/k42/metropolis/generator/MetropolisGenerator.java
grep -Fq 'getChunkAtAsync(job.x(), job.z(), false, false)' "$SOURCE"
grep -Fq 'finishDeferredLoad(job, loaded, loadFailure)' "$SOURCE"
if grep -Fq 'this.owner.getChunkAt(job.x(), job.z())' "$SOURCE"; then
  echo "FAIL: deferred-population drain still performs a synchronous chunk load" >&2
  exit 1
fi
echo "DeferredPopulationAsyncLoadProbe PASS: queued chunks are staged asynchronously without sync getChunkAt"
