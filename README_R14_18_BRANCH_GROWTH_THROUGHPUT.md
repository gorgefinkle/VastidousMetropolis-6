# R14.18 — Branch Growth Throughput

## Problem

The R14.17 drain serialized deferred branch growth behind one globally exclusive staging job. It allowed at most one build flush and one missing-chunk load per server tick. At 20 TPS, those fixed ceilings meant at most 20 build flushes and 20 staged chunk loads per second, regardless of spare server capacity.

## Changes

- Round-robin processing allows up to 24 successful build flushes per tick, with a 12 ms soft scheduler work budget.
- The queue scans up to 256 jobs per tick so completed/retired jobs and ready work are not hidden behind a small scan window.
- Up to 16 missing support chunks can be loaded per tick, distributed across queued jobs.
- Multiple jobs may stage simultaneously; one large build no longer owns a global staging lock.
- Plugin chunk tickets are reference-counted by world and chunk because Bukkit tickets are plugin-scoped. A build can release its reference without unpinning a chunk still needed by another build.
- A build is eligible to flush only when every required support chunk is both loaded and retained.
- A staging ticket lease expires after 600 ticks to prevent indefinite chunk retention; the build remains queued and retries.
- Flush exceptions retain the pending accumulator for retry.
- The pending accumulator is retired only after branch generation, vine placement, and chunk visibility refresh complete successfully.

## Threading and limits

All Bukkit world, chunk, block, and ticket operations remain on the server thread. This change does not attempt unsafe asynchronous block placement. The 12 ms budget is a soft batch budget: an individual generateGrowth() call is not preemptible and may itself exceed that budget. If one build is exceptionally expensive, it can still cause a long tick; the next optimization would need to make branch generation incremental rather than merely scheduling more builds.

The per-build simultaneous support-chunk ticket cap remains 128. Builds needing more than that wait for natural chunk availability rather than exceeding the configured retention ceiling.

## Validation status

The Java 25 Maven build and inherited QA were started by GitHub Actions. A successful CI run validates compilation and existing world-generation regressions, but does not measure live branch throughput. Live TPS, queue-depth slope, and visible branch placement still require a fresh-world server test under generation load.
