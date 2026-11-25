#!/bin/sh
set -eu

python3 - <<'PY'
from pathlib import Path

source = Path("src/main/java/ch/k42/metropolis/grid/urbanGrid/clipboard/DeferredSchematicMetadata.java").read_text()

def section(start, end):
    a = source.index(start)
    b = source.index(end, a)
    return source[a:b]

requeue = section("private void requeueOrReturn", "private void release")
assert "ticks<slot.nextTry" in requeue, "released slot can bypass its retry cooldown"
assert "window.addLast(slot)" in requeue, "cooldown slot is not retained in bounded window"

sync_failure = section("CompletableFuture<Chunk> future;", "future.whenComplete")
assert "release(slot);slot.nextTry=ticks+100;" in sync_failure, "synchronous async-load failure does not release gate and preserve cooldown"

missing_chunk = section("long missing=Long.MIN_VALUE;", "Set<Long> acquired")
assert "loadLater(slot,missing);requeueOrReturn(slot);continue;" in missing_chunk, "missing-chunk path does not requeue through cooldown-aware path"
assert "slot.nextTry=" not in missing_chunk, "caller overwrites loadLater retry cooldown"

watchdog = section("if(staging!=null&&ticks-stagingSince>100)", "if(window.isEmpty()")
assert "expired.nextTry=ticks+100;" in watchdog, "timeout does not preserve retry delay"
assert "release(expired);" in watchdog, "timeout does not release staging slot"
assert "if(generation!=loadGeneration)return;" in source, "late async callback can mutate newer load"

print("DeferredMetadataRetryProbe PASS: cooldown survives staging release, synchronous failures and timeouts retry, caller preserves delay, stale callbacks rejected")
PY
