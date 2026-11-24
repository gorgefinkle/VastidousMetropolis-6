# R14.18 — Branch Growth Throughput and Karst Mountain Enclosure

R14.18 addresses the live branch-growth backlog and cache-retention problem in the R14.17 line. It also changes Karstlands placement so each Karstlands province sits inside a complete mountain range.

## Branch-growth queue and cache

- Removes the fixed one-build-per-tick and one-generated-chunk-load-per-tick throttles. The rotating drain can advance up to 64 jobs per tick, scan up to 256 queued jobs, and request up to 8 generated chunk loads per tick, within an 8 ms soft work budget and explicit bounds.
- Visits each queued job at most once per tick. Failed or unavailable chunk loads do not get polled every tick; retries cool down, and chunk-load events wake the job when its waiting chunks become resident.
- Processes large footprints through 128-chunk ticket windows. Once a loaded batch is available, the cache advances and releases its tickets so the next batch can proceed; a build no longer needs every support chunk resident simultaneously.
- Stores cached support positions as packed primitive coordinates rather than retaining thousands of Bukkit `Block` objects. The optional source-order array preserves the original candidate ordering needed for whole-building sampling.
- Scans support chunks in bounded batches, but does not run branch generation per chunk. All raw candidates for the building are assembled first; then the existing per-building connected-component, height-band fairness and branch-budget logic runs once for that complete building. Vines are handled in a second bounded pass.
- Readiness notifications inspect only pending builds owned by the affected `UrbanGrid`, rather than copying the world-wide pending-build map on every parcel placement.
- Chunky backpressure counts both deferred-populate jobs and pending branch-growth builds. The existing pause/resume thresholds now cover both queues.

## Karstlands geography

Each Karstlands rare province is placed as a 2×2 core on the existing large physical province lattice. The eight surrounding 2×2 provinces are atomically reserved as `MOUNTAINS`, creating a full mountain enclosure on the north, south, east, west and all four diagonal sides. The mountain ring is roughly 1,352 blocks across at the default settings. If a complete ring cannot be reserved, that candidate is rejected rather than emitting an exposed or partial Karstlands basin.

Karstlands no longer use the generic city-distance keep-away field. The enclosing mountain range supplies the natural buffer from cities; the separate inland/watercourse suitability rule remains in place. `keepAway.karstlands` is set to zero in the shipped config and ignored for Karstlands by the production field, including older configurations.

## Validation

The R14.18 workflow runs the Java 25 Maven clean verify, inherited world-generation regression probes, packed-coordinate and drain-throughput probes, 128-chunk batching probes, source-contract checks, and the multi-seed Karst enclosure probe. The enclosure probe checks every accepted Karst province in 25 macro tiles for each of five seeds and requires mountains in all eight neighbouring provinces.

These deterministic probes validate the placement field and scheduling policies. They do not replace live-server testing. Test branch growth under a sustained Chunky generation workload and inspect Karstlands in **fresh, ungenerated chunks**; generated terrain is not repainted or repaired by this release. No post-generation cleanup pass is introduced.

## Build and package

- Target: Java 25
- Paper: 26.2 API `26.2.build.129-stable`
- Build: `mvn -B -ntp clean verify`
- Delivery archive: one current R14.18 JAR and one complete version-specific source ZIP only.
