# Metropolis R14.34 — Chunky recovery gate on the R14.33 baseline

## Baseline

This release is built from the exact R14.33 source head on
`feature/r14-23-farm-access-and-diversity` (commit
`27fcb086712033074de8a58ec28cdd8d41200407`). It preserves the R14.33 named
wilderness massifs, lakes, tributaries, farm access, terrain, and generation
systems. It does not replace them with the R14.18 or R14.19 branch.

## Chunky recovery changes

- Every automatic resume requires a complete 30-second stable post-GC recovery
  window and at least 10 healthy samples. The previous immediate <=68% resume
  shortcut is removed.
- A movement greater than 3 percentage points in either direction restarts the
  stability window. The window begins only after the paused task has stopped
  and a healthy headroom sample has been observed.
- Recovery remains bounded by the <=82% retained-heap ceiling, >=512 MiB and
  >=8% instantaneous free-memory headroom, and a combined deferred-populate /
  branch-growth queue low-water mark of 128.
- R14.33's post-GC heap measurement, stable high-heap operating mode, Chunky
  task-stop confirmation, protective pause thresholds, and diagnostics remain.
- A protective pause within 15 seconds of a successful resume imposes an
  additional 60-second recovery cooldown.
- Queue reporting now includes both R14.33's durable deferred-populate backlog
  and the cached branch-growth builds.

## Scope

No world planner, district, road, Morphomos, creek, river, terrain, schematic,
landmark, farm, massifs, lake, or vegetation generation behavior is changed.
The edits are limited to the Chunky memory policy, the memory valve, and the
existing deferred/growth queue count reporting.

## QA

Run `QA/R14_34/run_chunky_backpressure_policy_probe.sh` with Java 25.
The probe covers low-heap early-resume prevention, stable samples and dwell
time, high-retention recovery, queue limits, headroom floors, cooldown timing,
negative nanoTime origins, and queue arithmetic.
