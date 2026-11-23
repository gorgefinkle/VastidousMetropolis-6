# Metropolis R14.9 — queue progress, high-heap recovery, inland karst and open spans

R14.9 is a development build on the isolated `integration/morphomos-natural-family-bloomlands` branch. It preserves R14.8 Morphomos, Bloomlands and completed-BUILD exporter work.

## Deferred queue and flush lifecycle

- Deferred chunks remain disk-first durable `.job` markers; the ready working set is capped at 128 entries.
- Failed-job retry deadlines are separate atomic `.retry` sidecars. They never truncate the durable job marker.
- Retry-cooling jobs do not occupy the ready-work window, so failing chunks cannot consume the entire set of 128 ready slots.
- The drain has one scheduled token per world, a 25 ms main-thread budget, at least one job per run, and self-reschedules while work remains.
- Scheduler submission failures release the token. Unexpected runtime exceptions do not leave the queue permanently marked as scheduled.
- A one-second plugin watchdog re-requests draining for all generators, recovering from missed/rejected scheduler submissions without requiring a player action.
- The drain calculates the next retry wake-up instead of continuously scanning a large disk queue every tick while all remaining jobs are cooling down.
- A job is acknowledged only after population, post-population, city registration and LOD completion return successfully. Runtime/fatal failures retain the marker for at-least-once retry.
- Queue working memory remains bounded; the disk marker set can grow with the outstanding backlog. This is at-least-once scheduling, not a claim of exactly-once block placement after process crashes.

## Chunky memory backpressure

- Soft protective pause: 74% heap.
- Hard protective pause: 86% heap, including during recovery.
- Ordinary resume: at or below 68%, once the deferred queue is at or below 128.
- High-retained-heap recovery: after 30 seconds of stable readings at or below 82%, with at least 512 MiB and 8% of maximum heap free, and queue at or below 128.
- Successful high-heap recovery enters a stable operating band through 82%, so a heap baseline around 80% does not immediately trigger another 74% soft pause.
- A renewed rise above the 82% stable band, the 86% hard ceiling, or a queue of 1,024 still triggers protective pause.
- The valve does not force garbage collection and does not change the generation planner or world content.

## Karst geography and landbridge geometry

- Karst habitat is excluded from the ocean and the first 512 inland blocks. Suitability fades smoothly from 512 to 1,024 blocks inland and is fully restored beyond that.
- Karst landbridges now use a thin central keystone underside that thickens toward the span ends and natural abutments. The previous underside was derived from twice an ellipsoid half-height, making the rock mass too thick and allowing the bridge to fill much of its gorge.
- The untouched pre-bridge ravine floor is preserved. The lower gorge remains open beneath the deck and retains its normal floor bedding and plant ecology.
- Bridge and exclusion behavior are deterministic from world seed and block coordinates.

## QA run in CI

The R14.9 workflow runs:
- Maven clean verify on Java 25.
- Morphomos boundary-family probe.
- Rare-biome appearance probe.
- Completed-BUILD exporter durability and eligibility probe.
- Disk deferred queue probe (1,200 jobs, restart, retry, dedupe, TSV migration, negative coordinates, bounded working set, and fail-closed corrupt migration).
- Chunky backpressure policy probe (stable 80% recovery, queue/hard limits, and low-heap headroom).
- Karst coastal exclusion probe.
- Karst landbridge clearance probe (natural floor preserved and a genuine open gap beneath sampled bridge cores).

## Runtime test still required

CI probes and compilation do not replace a live Paper 26.2 + Chunky session. Test a disposable world with sustained generation, especially a restart/retry case and a long plateau near 80% used heap. Confirm the Chunky task becomes RUNNING after the stable recovery conditions are met, continues generating without immediate re-pause, and pauses again if the hard ceiling or queue high-water mark is reached.

The plugin cannot guarantee infinite generation yet: the wider world-plan, highway and placed-parcel reference structures still need separate bounded-retention protocols. Do not treat this build as an infinite-world certification.
