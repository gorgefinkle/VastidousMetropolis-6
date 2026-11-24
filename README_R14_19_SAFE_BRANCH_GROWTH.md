# R14.19 — Safe Branch Growth Staging

## Why R14.18 had to change

The live R14.18 watchdog dump showed the server thread inside
`BranchGrowthDrain.advanceStaging()`, calling Paper's synchronous
`CraftWorld.loadChunk()`. That request re-entered Metropolis's BlockPopulator
while the branch drain staged support chunks, causing recursive terrain
generation and repeated watchdog stalls.

## Changes

- The drain requests disk-backed support chunks through Paper's
  `getChunkAtAsync(x,z,false,false)`. It does not ask the drain to generate
  missing chunks.
- Outstanding async requests are capped per tick and per build. Every build
  waits until all support chunks are loaded and pinned before flushing.
  Missing chunks are left for normal scheduling / Chunky pre-generation.
- Plugin chunk tickets are added only after `isChunkLoaded` succeeds.
- Biome decoration memoizes `allows(x,z)` and `groundAt(x,z)` per column
  during one chunk's decoration pass. Companion trees, giant mushrooms, and
  Mother Willow revisit columns while emitting geometry.
- Wilderness and Karst caches use mixed coordinate keys and prune to lower
  watermarks rather than clearing accumulated samples at once.

## Expected behavior

Already-generated support chunks can be loaded from disk without blocking the
drain on a synchronous load. A support chunk that has never been generated is
left to normal generation / Chunky. Once available, it is retained and the
branch build flushes when its full support footprint is ready. This removes the
recursive main-thread generation path seen in R14.18.

## Validation

Java 25 build, inherited world-generation probes, a static no-sync-load check,
and a spatial-key/pruning probe run in CI. R14.19 still requires a live server
test to confirm queue stability and visible branch placement under Chunky.
