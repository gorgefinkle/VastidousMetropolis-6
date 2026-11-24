# R14.22 — Remove the buried Smooth Stone seam

## Root cause

The base generator wrote a special `SMOOTH_STONE` block at Y=64 in every
normal-overworld chunk, while all surrounding base-fill layers were ordinary
stone. Raised biome terrain could bury this cap: the forest column painter
resets buried substrate from Y=65 upward, leaving the single Y=64 smooth-stone
band inside hills and other raised biome surfaces.

## Fix

- Base terrain now uses the same environment-provided material/fallback-stone
  rule at every generated Y level. The normal overworld has no environment
  override, so Y=64 is ordinary stone like Y=63 and the rest of the substrate.
- During the existing per-column forest paint, a legacy smooth-stone cap at
  Y=64 is converted to stone only when it is below that column's surface.
  This handles chunks generated with the previous code when they are naturally
  repainted, without moving the surface or adding a cleanup pass.
- R14.21's mushroom contact, Mother Willow junction, Bloomlands understory,
  and safe asynchronous branch-growth changes remain intact.

## Validation

The R14.22 probes verify the uniform base fill, environment overrides, and
selective conversion of only a buried legacy Y=64 cap. The complete inherited
Java 25 and world-generation suite runs alongside them.
