# Metropolis R14.35 — queue-first Chunky recovery and branch-growth priority

## Baseline

Built directly from R14.34 commit `45a87e2ce1dc48a6f51ff9c92255cf3f15bca0cf` on
`fix/r14-34-chunky-recovery-gate`. The R14.34/R14.33 generation code remains
the baseline; this revision does not switch to the earlier R14.19 line.

## Queue policy

- Ready-to-flush branch-growth builds are given priority. Each counts twice
  against the active-work queue, and 128 ready builds trigger a protective
  Chunky pause even before the general weighted queue limit is reached.
- Recovery requires no more than 16 ready branch-growth builds, no more than
  64 deferred populate jobs, and a weighted active queue of at most 64.
  Those low-water conditions must hold for ten monitor samples over at least
  ten seconds.
- The branch-growth queue metric distinguishes work that can run now from
  work that cannot. Builds still waiting for neighboring parcel placement,
  or for support chunks that have not yet loaded, are reported separately and
  do not hold Chunky paused. Chunky may need to generate those future chunks
  before that growth work becomes runnable; gating on the total pending-build
  count would risk a circular wait.
- While Chunky is paused, the existing dedicated, fair, ticket-limited
  `BranchGrowthDrain` continues flushing its ready queue under its existing
  12 ms / 24-build per-tick budget. The revised recovery gate gives that work
  room to catch up before feeding another large wave of chunks; it does not
  increase main-thread work per tick.

## Memory safety

- Post-GC retained heap percentage is diagnostic only; it no longer decides
  when a paused Chunky task may resume.
- Resume still requires at least 512 MiB of absolute free heap and current
  usage below the 86% hard emergency ceiling. The former 82% retained-heap
  ceiling and 8%-of-max free-memory gate are removed from recovery.
- Existing protective pauses at 74% soft and 86% hard remain, as does the
  60-second cooldown after a rapid repeat-pause.

## Scope

Changes are limited to the Chunky queue policy/valve, branch-growth queue
readiness reporting, regression QA, version metadata, and the build workflow.
No district, road, Morphomos, creek, river, terrain, schematic, landmark,
farm, biome, or other world-generation geometry is changed.

## QA

Run `QA/R14_35/run_chunky_queue_first_recovery_probe.sh` with Java 25.
The probe covers branch-growth weighting and pause priority, deferred/ready
queue low-water thresholds, sustained queue recovery, the absolute memory
safety floor, emergency heap ceiling, cooldown, and monotonic-clock behavior.
