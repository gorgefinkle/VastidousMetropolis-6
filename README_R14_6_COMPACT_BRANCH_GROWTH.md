# R14.6 — Compact Branch Growth

## Two separate memory-pressure paths

R14.6 contains **two distinct deferred-work mechanisms**; do not conflate them:

1. **Deferred chunk population is disk-backed.** `DiskDeferredChunkQueue` is integrated into `MetropolisGenerator`'s BlockPopulator. Outstanding chunk coordinates are durable marker files, with a 128-entry total retained-RAM cap (including the active in-flight job), retry hints, legacy TSV migration, async loading of already-generated chunks, and acknowledgement only after the full population/post-population/city/LOD path returns successfully. The QA probe also verifies nested enqueue while a job is active. The plugin resumes it on enable/world-load and closes it on shutdown. This queue is at-least-once, not exactly-once, and its new restart/retry/migration probe is part of CI.
2. **Pending branch-growth candidates remain in RAM.** R14.6 packs each support hit into an eight-byte primitive record and removes per-candidate Bukkit `Block` wrappers and `SupportHit` objects. It does not spool these branch candidate arrays to disk. Their retained-byte metric is reported separately from deferred population queue depth.

The disk-backed chunk queue prevents the deferred populate backlog itself from consuming one object per outstanding chunk. The compact branch buffer reduces the memory cost of the separate tree/branch growth backlog. Neither should be treated as a substitute for profiling the remaining world-plan, parcel, highway, or schematic metadata caches.


**Version:** `0.1.28-R14.6-Compact-Branch-Growth-DEV`  
**Target:** Paper 26.2 / Java 25  
**Parent:** R14.51 `901a6a4677a3ee247195a1b77c1cdc209b3fd173`

## What changed

### Compact pending support candidates

A pending support candidate is now encoded into one primitive `long`:

- signed 26-bit X
- signed 26-bit Z
- signed 11-bit Y
- one bit recording whether the support block decayed

The retained payload is exactly eight bytes per candidate in the backing array. A pending candidate no longer retains a Bukkit `Block` wrapper, a `SupportHit` object, or a per-entry object reference. Bukkit blocks are resolved from their coordinates when a build is actually flushed.

The stored coordinates cover the Minecraft horizontal world-border range. The vertical range is -1024 through 1023; Metropolis' decay pass currently clamps the top of its scan to Y=254.

The pending buffer starts with zero capacity and grows only as candidates are appended. On a successful flush or empty-build completion, the array is released. The flush path aliases the pending buffer rather than making a second full candidate copy. Street candidate thinning copies packed values while keeping the configured random retention behavior.

Empty candidate lists do not create pending-build entries.

### Exact parcel-readiness wakeups

The old placement callback walked the full pending-build key set and rechecked each eligible build. R14.6 records the exact world chunk cells that prevented a build from becoming ready. A parcel-populated callback looks up only the cells covered by that parcel and wakes their waiters.

The reverse index is coordinate-based, not keyed by provisional `Parcel` identity. That is intentional: if planning replaces a provisional parcel before the final parcel is pasted, the final parcel still wakes the waiting build.

The neighborhood traversal still follows the existing rules: eight-directional perimeter contact, streets are transparent and consume no building-depth hop, and the configured neighborhood depth remains in force. No candidate is dropped because a dependency is missing.

### Bounded scheduler metadata

The previous per-build Bukkit timeout tasks are replaced with one shared timeout ticker and a priority queue of deadlines. Queue entries use weak references and generation tokens so expired entries cannot keep completed parcels alive or revive stale work.

The runnable-growth diagnostic is now an O(1) maintained counter instead of scanning every pending build. The periodic heap audit also reports:

- `branchSupportHits`: the number of candidates currently retained
- `branchSupportBuffer`: the exact backing-array payload allocated for those candidates

These metrics describe the packed candidate arrays, not every object in the pending-build map, wait index, or branch drain.

### Branch-drain lifecycle

Async support-load completion now posts a lightweight signal to the main-thread drain. The drain wakes the matching parked job directly, honors retry deadlines, and gives a re-parked job a fresh staging-ticket lease. Staging-expiry queue entries hold weak references so a completed job is not retained until an old lease expires.

The existing bounds remain in place for per-tick build work, async load requests, in-flight loads, and retained chunk tickets. The drain does not generate absent support chunks.

## What this does not claim

This revision materially reduces the retained representation of pending candidate pools and removes the all-pending-build placement sweep. It does **not** serialize candidate pools to disk. Candidate coordinates and per-build metadata remain in memory until the build can be flushed. The new byte metric covers the packed candidate arrays only; it is not a measurement of the total branch-growth subsystem heap.

For that reason, R14.6 is a structural memory reduction, not proof that memory growth is completely solved. The same large Chunky pre-generation workload still needs a live run. Compare heap behavior with `branchGrowth`, `branchGrowthReady`, `branchSupportHits`, and `branchSupportBuffer` over time. If total heap continues to climb materially while packed candidate bytes remain low, the next investigation should target the remaining per-build metadata, dependency registrations, deferred schematic metadata, and other retained queues rather than raising memory thresholds.

## QA

The workflow runs focused standalone probes for:

- packed coordinate/flag round trips, boundary validation, bulk copies, and exact storage size
- wait-index identity behavior, shared cell wakeups, re-registration, and stale-edge cleanup
- source-level checks that candidate objects, per-build timeout tasks, and full pending-build placement sweeps are gone
- Java 25 Maven package build and the existing world-generation regression suite

