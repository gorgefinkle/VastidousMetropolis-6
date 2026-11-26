# R14.7 Morphomos + Bloomlands integration (DEV)

This branch is based on the R14.6 disk-deferred-queue development source. It preserves that experimental queue implementation and layers in the requested Morphomos natural-boundary policy and Bloomlands inspection command.

## Morphomos boundary policy

- FOREST, RIVER, OCEAN, CREEK-owned ZONE cells, and NATURAL wilderness cells share the natural boundary family.
- FARM is its own family and dissolves only FARM/FARM shared roads.
- FARM/natural boundaries retain their planned road.
- Natural/natural boundaries may dissolve before road emission, through the immutable world-plan decision in PlanRegions.
- Boundary family is deliberately not terrain ownership. Water parcels remain river/ocean owned; creek sites retain creek/water ownership; forest/wilderness painting remains forest-owned. No new cleanup or post-generation road repair pass is added.
- The existing accepted creek-local-road ownership handoff remains in place.

The policy is centralized in `MorphomosBoundaryFamily`; `PlanRegions` uses it for both the local reciprocal-family gate and the eight-neighbor decision. The new standalone probe covers every FOREST/RIVER/OCEAN pairing, FARM isolation, reciprocal compatibility, and natural exterior seams.

## Bloomlands

Bloomlands was already present in the R14.6 source as a real `WildBiome` with a nonzero default share (2), a 12-chunk keep-away distance, terrain/relief handling, mossy/fungal ground, and mushroom / companion-tree / Mother Willow ecology. This integration does not invent a parallel biome selector or change its generation seed. The locator asks the same `WildField.pick()` used by the terrain painter and only accepts the actual FOREST-owned region, including the local synthetic creek/interfluve woodland owner.

Commands:

- `/bloomlands [radiusChunks]` — find the nearest candidate and verify a safe landing in its generated chunk.
- `/bloomlands tp [radiusChunks]` — do the same verification, then teleport.
- Default radius: 64 chunks; maximum: 256 chunks.

The search is incremental across ticks. It checks a deterministic 32-block sample lattice, scores the local Bloomlands core, asynchronously generates/loads a candidate chunk, then scans its 256 columns for a safe two-block landing. If a candidate fails generation or landing verification, it tries the next one.

## Build and QA

- Target runtime/API: Paper 26.2
- Java target: 25
- Maven: `mvn -B -ntp clean verify`
- Pure policy probe: `QA/R14_7/run_boundary_family_probe.sh`

The GitHub Actions workflow builds the Maven project, runs the standalone probe, and publishes the JAR and complete source ZIP as workflow artifacts.

## Not yet certified

This DEV integration has not been visually verified in a live Paper world. The build and deterministic QA are gates, not a claim that a full multi-seed terrain test or live Bloomlands spawn/teleport test has passed. The R14.6 disk-queue runtime limitations remain unchanged, including long-running Paper/Chunky throughput and crash/disk-full recovery tests.
