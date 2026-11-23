# R14.8 Morphomos, rare biomes and BUILD export (DEV)

This branch is based on the R14.6 disk-deferred-queue development source. It combines the R14.7 natural-boundary Morphomos policy and Bloomlands locator with two additional integrations: reliable rare-habitat selection and the file-only completed-BUILD export for Dead Streets.

## Morphomos boundary policy

- FOREST, RIVER, OCEAN, CREEK-owned ZONE cells, and NATURAL wilderness cells share the natural boundary family.
- FARM is its own family and dissolves only FARM/FARM shared roads.
- FARM/natural boundaries retain their planned road.
- Natural/natural boundaries may dissolve before road emission, through the immutable world-plan decision in PlanRegions.
- Boundary family is deliberately not terrain ownership. Water parcels remain river/ocean owned; creek sites retain creek/water ownership; forest/wilderness painting remains forest-owned. No cleanup or post-generation road repair pass is added.
- The existing accepted creek-local-road ownership handoff remains in place.

The policy is centralized in `MorphomosBoundaryFamily`; `PlanRegions` uses it for the reciprocal family gate and the eight-neighbor decision.

## Bloomlands and karst

Bloomlands was already a real `WildBiome` with a complete terrain/ecology painter, including mushrooms, companion trees and Mother Willow. The previous default selection pipeline could erase its small regions during the layer smoothing and 3x3 majority stages. Its sharpen threshold was also the same 10% threshold as common biomes, and the later 192-block wilderness-weight blur could wash out the remaining rare cores.

R14.8 now:
- Preserves coherent Bloomlands and karst core labels through the zoom smoothing / majority selection stages, then carries their center weights through the broad wilderness blur so their last surviving cores remain selectable.
- Uses lower core thresholds for those two deliberately rare biomes without changing the common-biome threshold.
- Keeps Bloomlands at the smaller default share (2) and karst at 4, so Bloomlands remains the rarer one.
- Migrates legacy default keep-away values (Bloomlands 12 to 8 chunks; karst 8 to 6) while preserving operator overrides and explicit zeroes.
- Bases karst suitability on Metropolis' existing deterministic fractured-limestone field (`KarstLandforms`), rather than the unrelated volcanic-cone suitability mask. The 480-block minimum-region averaging no longer erases the elongated fracture habitat. This changes the karst distribution, not the volcanic mountain-kind profiles; no volcano-generation boost is added.

The locator uses the same canonical `WildField.pick()` as worldgen:
- `/bloomlands [radiusChunks]` — find a nearby patch and verify a safe landing.
- `/bloomlands tp [radiusChunks]` — repeat the same verification, then teleport.
- Default radius 64 chunks; maximum 256 chunks.

The search is incremental across ticks; candidate chunks are asynchronously loaded and only accepted after a safe two-block landing is found.

## Metropolis → Dead Streets completed-BUILD export

Metropolis now exports completed logical BUILD parcels without depending on Dead Streets, spawning entities or changing the world-generation plan.

Output:
`plugins/Metropolis/deadstreets-build-flags-v1/<world-uuid>/buildings.jsonl`

Each JSONL record contains:
- schema version, deterministic building ID and world UUID;
- the assigned district type (not inferred from schematic name);
- the full world-space block footprint, including X/Y/Z extents;
- center/reference coordinates;
- verified interior candidate points with floor Y and standing-clearance flags.

The hook is connected to the successful parcel-completion path. File-sliced schematics export once only after every declared slice has finished and all overflow slices have completed. Non-BUILD schematic types are ignored. Interior sampling inspects only chunks that are already loaded by generation; it never force-loads chunks for this export.

A dedicated single-thread writer handles serialization and disk work away from generation callbacks. It uses deterministic IDs, a restart-built duplicate index, newline-terminated records, file-channel force, retry-on-write-failure, and recovery of an interrupted final JSONL line. Dead Streets owns population assignment and all physical zombie lifecycle behavior.

## Build and QA

- Target runtime/API: Paper 26.2
- Java target: 25
- Maven: `mvn -B -ntp clean verify`
- Morphomos matrix: `QA/R14_8/run_boundary_family_probe.sh`
- Three-seed rare-biome field check: `QA/R14_8/run_wild_biome_appearance_probe.sh`
- BUILD JSONL/restart/partial-tail check: `QA/R14_8/run_build_flag_exporter_probe.sh`

The GitHub Actions workflow runs the complete Java 25 Maven build and all three probes, then publishes one current JAR and the complete source ZIP.

## Not yet certified

This DEV integration has not yet been visually verified in a live Paper world. The automated rare-biome probe tests the deterministic selection field, not a full populated world or the final visible look of every patch. The Dead Streets acceptance test must still be run in a disposable Metropolis world with Dead Streets absent: confirm unique records for complete BUILDs (including multi-slice/overflow buildings), correct district/footprint data, plausible interior candidates, and no records for non-BUILD parcels.

The R14.6 disk-queue runtime limitations also remain: long-running Paper/Chunky throughput, process-kill semantics, and disk-full/filesystem recovery are not certified by this branch's compile tests.
