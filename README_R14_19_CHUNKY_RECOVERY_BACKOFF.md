# Metropolis R14.19 – Chunky recovery backoff (DEV)

## Base and scope

Based on `feature/r14-18-branch-growth-throughput`. This change is limited to
Chunky memory back-pressure. It does not change world planning, roads, Morphomos,
biome generation, creek/river ownership, terrain painting, deferred job storage,
or branch-growth throughput.

## Recovery behavior

- The former `usage <= 68%` fast path no longer resumes immediately.
- Every resume requires at least 30 seconds continuously inside the safe
  recovery band, at least 10 healthy samples, sufficient free-memory headroom,
  and a combined deferred/growth queue at or below 128.
- A meaningful heap movement greater than 3 percentage points restarts the
  stable window. This is important when the first post-GC reading drops sharply:
  the stability clock starts after that drop, not when Chunky was first paused.
- The normal 68% target still distinguishes a normal resume from a guarded
  high-heap recovery probe. Stable recoveries above 68% and at or below 82%
  retain the existing bounded run-band behavior.
- If Chunky trips a new protective pause within 15 seconds of a resume, the
  next resume is blocked for at least 60 seconds, in addition to satisfying
  the normal stable-window and queue conditions.
- Existing 74% soft pause, 86% hard pause, 512 MiB / 8% headroom, queue
  watermarks, task-stop confirmation, and durable queue behavior are retained.

## Regression probe

`QA/R14_19/ChunkyBackpressurePolicyProbe.java` covers the low-heap fast-path
regression, stable sample count, 30-second minimum, high-heap stable recovery,
82% ceiling, queue low-water mark, free-headroom limits, rapid re-pause
cooldown, and combined queue accounting.

Run the standalone probe by compiling it together with
`ChunkyBackpressurePolicy.java` and executing
`ch.k42.metropolis.generator.ChunkyBackpressurePolicyProbe`.

## Validation status

The standalone policy regression probe is included. A Java 25 full Maven build
and Paper + Chunky runtime validation remain required before treating this
branch as a testable replacement JAR. In particular, verify that the first
resume follows a full stable window and that a rapid re-pause produces a
60-second cooldown without losing deferred jobs or slowing the durable queue
drain itself.
