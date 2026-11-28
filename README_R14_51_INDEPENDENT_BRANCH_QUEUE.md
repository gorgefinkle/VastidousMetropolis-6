# R14.51 — Independent branch-growth queue and Chunky heap hysteresis

## Why this revision exists

The R14.50 logs showed Chunky resuming while current heap use was still above the threshold that had paused it. The old policy paused at 74% but allowed recovery below 86%. That overlap allowed a resumed task to cross 74% again almost immediately, and the 60-second repeat-pause cooldown magnified the oscillation.

Branch growth also had its own drain, but waiting jobs remained in its round-robin scan. With thousands of support-chunk dependencies, the drain repeatedly revisited work that could not run yet.

## Queue model

- Chunk population remains in the deferred-population queue and is the only queue charged against Chunky's population backlog thresholds.
- Branch growth uses its own ready deque. Newly ready jobs are serviced under the existing 24-build / 12 ms per-tick budget.
- When a build discovers unloaded support chunks, its job is parked outside the ready deque.
- The provider's reverse dependency index wakes only jobs that depend on a chunk when that chunk loads. Woken jobs are placed at the front of the branch-ready deque.
- Parked jobs have a bounded staging lease. Expiry releases their retained tickets and requeues them for a fresh staging attempt.
- Async support-chunk requests are bounded to 16 new requests per tick, 16 per job, and 256 globally in flight. Retained chunk tickets are capped at 128 per build and 4,096 unique world/chunk tickets globally. The branch drain never generates missing chunks; normal population remains responsible for producing them.

## Heap back-pressure

- Chunky pauses at 74% heap use (or the existing deferred-population queue limit).
- Recovery now requires heap use at or below 68%, at least 512 MiB free, a low deferred-population queue, and ten stable one-second samples.
- The rapid repeat-pause cooldown is reduced from 60 seconds to 5 seconds. The lower recovery band and stability window provide the primary hysteresis.
- Branch-growth ready/waiting counts remain diagnostic metrics but do not pause Chunky or consume population queue capacity. The dedicated branch drain continues while Chunky is paused or running.

This addresses the log's specific resume-above-pause-threshold defect without disabling heap protection or making branch work compete for population slots.

## Validation

The Java 25 build and inherited generation probes remain required. R14.51 adds a policy probe for queue isolation, 74%/68% hysteresis, the 512 MiB reserve, stable recovery, and the shortened cooldown, plus source checks for event-driven parking/wakeup and bounded asynchronous loads.

A successful CI run does not replace a fresh-world pre-generation test. The key live check is that a pause is followed by a sustained low-heap recovery, with no resume while the heap remains in the 68–74% band.
