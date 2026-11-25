#!/bin/sh
set -eu

SOURCE=src/main/java/ch/k42/metropolis/grid/urbanGrid/clipboard/DeferredSchematicMetadata.java

# Retry cooldowns must survive releasing a staging slot. The slot remains in
# the bounded in-memory window until nextTry, instead of being rediscovered
# from disk as a fresh Slot with nextTry=0.
requeue=$(sed -n '/private void requeueOrReturn/,/private void release/p' "$SOURCE")
printf '%s\n' "$requeue" | grep -Fq 'ticks<slot.nextTry'
printf '%s\n' "$requeue" | grep -Fq 'window.addLast(slot)'

# A synchronous async-load failure releases the global staging gate, records
# a cooldown, and leaves the durable job to be retried later.
sync_failure=$(sed -n '/CompletableFuture<Chunk> future;/,/future.whenComplete/p' "$SOURCE")
printf '%s\n' "$sync_failure" | grep -Fq 'release(slot);slot.nextTry=ticks+100;'

# The caller must not overwrite a failure delay after loadLater() decides it.
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

echo "DeferredMetadataRetryProbe PASS: retry cooldowns survive release, caller preserves failure delay, timeouts retry, stale callbacks rejected"
