#!/bin/sh
set -eu

SOURCE=src/main/java/ch/k42/metropolis/grid/urbanGrid/clipboard/DeferredSchematicMetadata.java

# A synchronous getChunkAtAsync failure must leave the current slot staged.
# Otherwise requeueOrReturn() drops it from the in-memory window and the
# 100-tick cooldown is lost when the disk job is rediscovered.
sync_failure=$(sed -n '/CompletableFuture<Chunk> future;/,/future.whenComplete/p' "$SOURCE")
printf '%s\n' "$sync_failure" | grep -Fq 'loading=false;loadGeneration++;slot.nextTry=ticks+100;'
if printf '%s\n' "$sync_failure" | grep -Fq 'release(slot)'; then
  echo "FAIL: synchronous load failure releases the staged slot and loses its cooldown" >&2
  exit 1
fi

# The caller must not overwrite the retry delay after loadLater() decides it.
missing_chunk=$(sed -n '/long missing=Long.MIN_VALUE;/,/Set<Long> acquired/p' "$SOURCE")
printf '%s\n' "$missing_chunk" | grep -Fq 'loadLater(slot,missing);requeueOrReturn(slot);continue;'
if printf '%s\n' "$missing_chunk" | grep -Fq 'slot.nextTry='; then
  echo "FAIL: caller overwrites the async-load retry cooldown" >&2
  exit 1
fi

# Pending requests need a watchdog, and late callbacks must not mutate new work.
watchdog=$(sed -n '/if(staging!=null&&ticks-stagingSince>100)/,/if(window.isEmpty()/p' "$SOURCE")
printf '%s\n' "$watchdog" | grep -Fq 'expired.nextTry=ticks+100;'
printf '%s\n' "$watchdog" | grep -Fq 'release(expired);'
printf '%s\n' "$SOURCE" | grep -Fq 'if(generation!=loadGeneration)return;'

echo "DeferredMetadataRetryProbe PASS: cooldown retained, caller does not overwrite it, timeouts retry, stale callbacks rejected"
