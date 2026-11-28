# Metropolis R14.6 – Bounded disk-backed deferred population (DEV checkpoint)

## Baseline and scope

Branched from the independently tested R14.5 road-corner-ordering source. The
world-plan / Morphomos / creek owner / terrain painter code has **not** been
changed. The only production changes are `MetropolisGenerator.java`,
`MetropolisPlugin.java`, and the added `DiskDeferredChunkQueue.java`.

**Do not replace R14.5 while the user tests the road changes.** This is an
experimental buffer-source milestone, not a certified unlimited-generation
release. The current Minecraft/Paper coordinate and storage limits still apply.

## Identified problems in R14.5

- `MetropolisBlockPopulator.deferred` was an unbounded `ConcurrentLinkedQueue`
  of chunk coordinates, with periodic whole-queue `toArray()` + `StringBuilder`
  snapshots. Large backlogs consumed both live RAM and transient RAM on saves.
- ChunkyMemoryValve had QUEUE_PAUSE=1024 and QUEUE_RESUME=128 but
  `ChunkyMemoryValve.pending(World,count)` was never called by the generator;
  queue-based backpressure was not wired into the actual deferred queue.
- The previous drain removed a job from the queue *before* population and
  swallowed errors, risking permanent incomplete chunks.
- Remaining major unbounded retention: WorldLayout.byCell/conflicts/byId,
  HighwayNetwork.cities/candidates/candidateKnown/survives/baseSurvives,
  PlacedParcels.finished/cut/finishedPorts/connectedPorts. Without safe eviction
  and durable reload protocols those prevent sustained unlimited exploration.
  The deferred metadata spool is *already* bounded in memory and should not be
  replaced blindly.

## Changes in R14.6

- Disk-first deferred jobs stored as sharded, unique coordinate marker files
  under `<world>/metropolis/deferred-populates/`, with a 128-entry total retained-RAM cap, including the active job.
  New enqueues are written to disk *before* the deferring callback returns.
- Streaming startup scan and idempotent migration of the existing
  `deferred-populates.tsv`; old TSV deleted only after every record has been
  transferred. Invalid lines abort migration and leave the old file for repair.
- A job is acknowledged (marker removed) only after `grid.populate`,
  `grid.postPopulate`, city completion and LOD notifications finish without
  throwing. Failed work stays on disk and is retried later.
- Count is now reported to ChunkyMemoryValve on startup, enqueue, requeue and
  acknowledgement, so its existing queue high/low watermarks can operate.
- Deferred resume is postponed until the plugin is enabled, including a
  world-load and plugin-enable resume path.
- No generation radius, total-chunk or world-size cap. Pausing Chunky is
  cooperative rate limiting, not terminating the world task.

## Validation accomplished

- Java 25 production build against genuine supplied Paper 26.2 / WorldEdit /
  Guava / Gson libraries: 254 production Java sources, 534 class files.
- Deterministic standalone QA: 1200 queued chunks, two shutdown/restart
  cycles, one retry, legacy queue migration, deduplication, negative coords,
  corrupt legacy fail-closed, and memory-window <=128 including an active job and nested enqueue; PASS.
- Identical class bytes for 524 R14.5 compiled classes; changed classes are
  restricted to the generator and plugin wiring. All independent planner and
  road-shape classes are byte-identical.

## Not yet validated / deliberately excluded

1. Live Paper + Chunky 12-hour / 400k-chunk stability and pause/resume tests.
2. WorldPlan domain offload with deterministic restoration, and bounded
   HighwayNetwork caches without changing chosen roads.
3. PlacedParcels disk-backed completion record with save-aware acknowledgements
   and safe delayed road/building cutouts; never evict parcel references before
   this protocol is proved.
4. Sudden-process-kill test while paste is in flight: the queue offers
   **at-least-once** execution, not exactly-once building paste semantics.
5. Disk-full and filesystem-error recovery; the queue fails loudly and keeps
   existing markers rather than silently dropping work.
6. I/O benchmarking for huge pending backlogs (one file per outstanding chunk
   is a correctness-first prototype, not necessarily the final throughput
   design). A segmented journal or embedded on-disk index may be preferable.

The next version must prove a flat post-GC retained heap and eventual Chunky
progress across more chunks than the prior R30ea 422,109-chunk/18.74% stall.
This R14.6 source *does not yet establish infinite-generation readiness*.
