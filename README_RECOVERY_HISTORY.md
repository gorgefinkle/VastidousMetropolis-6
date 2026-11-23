# R09.93 source-inventory status (supersedes older migration counts below)

**This recovery snapshot contains 249/249 R30ea top-level production Java source files**, not the 189 files described by the historical October 31 Maven migration note below. The files from R30ea that were absent as textual Java have been reconstructed from reference bytecode into editable source. This does **not** certify a complete working build or exact runtime parity: the existing older Java classes and resources still need reconciliation, and the installed environment has only JDK 21 while the project targets Java 25. Do **not** deploy this package as a plugin. The R30ea JAR is a comparator only, not a dependency for building production source. Verification: `bash QA/run_r09_93_checks.sh`.

---

# Metropolis 26.2 — Maven source project

This project packages the **Pass 05 parity migration source** in a standard Maven layout. It is **not yet a proven compiling or runtime-tested port**. The current source still has potential Paper/WorldEdit 26.2 compatibility issues independent of this Maven conversion.

## Requirements

- JDK 25 (not Java 8 or Java 21)
- Maven 3.9+
- Network access to the PaperMC and EngineHub Maven repositories (or dependencies cached in `~/.m2`)

## Build

```bash
java -version  # should say 25
mvn -U clean package
```

Expected JAR path **after a successful build**: `target/Metropolis-26.2.jar`. There is no prebuilt JAR in this source ZIP.

## Project layout

- `pom.xml`: pinned Paper `26.2.build.129-stable`, WorldEdit `7.3.17`, Gson and Guava; Java release 25; provided dependencies (no server libraries bundled)
- `src/main/java`: 189 active Java source files; same migration contents as the prior source archive
- `src/main/resources/plugin.yml`: plugin metadata and registered commands
- `src/main/resources/config.yml`: original migration config, unchanged
- `legacy-uncompiled/`: `EBeanServerUtil.java` retained as legacy reference, deliberately outside compiler inputs (unused Ebean/SQLite API)
- `converter/`: legacy schematic converter/verification scripts
- `audit/`: migration/fidelity findings and static audit
- `migration-reference/`: original Gradle build files for comparison, not used by Maven

## Schematics

Converted `.schem` assets are in the previously delivered `Metropolis26_Converted_Schematics_And_Configs.zip`, **not in this Maven plugin JAR**. Deploy those in the plugin's data folder as specified by its importer. Keep original legacy `.schematic` files unchanged as references. Some multiblock and tile-entity edge cases remain flagged; successful packaging alone does not verify visual fidelity.

## Important migration limitations

- This source tree has **not** passed a complete Paper 26.2 compile or server-generation run.
- WorldEdit 7.3.17 is a migration dependency baseline, not proof of compatibility with Paper 26.2. You may need a newer WorldEdit version for runtime.
- `plugin.yml` uses `softdepend: [WorldEdit]` from the source; schematic loading still functionally requires WorldEdit.
- The config preserves `enableFillerTerrainFallback=false`. Normal schematic filler should remain available; no custom-terrain substitute for filler lots is intended.
- Do not replace the working 1.12.2 server with this project before verifying map generation, schematic rotations, legacy block variants, creek shoreline, roads and districts, and ports.

This ZIP changes the build structure; it **does not claim to repair** outstanding API errors or complete feature parity.

## Current source correction (October 31, 2026)

- Final district fill now tries normal context-free filler schematics when no context-matched filler fits a leftover rectangle. This uses the existing schematic provider/DAO and preserves road-facing selection, instead of installing fallback terrain.
- Context-free `getAll(null, FILLER, direction)` is now explicitly a wildcard-context lookup; non-null context lookups retain their filtering semantics.
- Missing 1x1 normal filler is logged with context, coordinates, and road direction. `generator.enableFillerTerrainFallback=false` stays unchanged.
- Static integration check passed. **No Maven/Paper compile or in-server visual test has been completed.** This source ZIP is for continued integration/build attempts, not a certified production JAR.

---

## R10.1 status notice (November 19, 2026)

See `README_R10_1_AUDIT.md` for the latest recovery state. R10.1 has 249 production Java files and restores R30ea's 13-biome configuration/selector through the existing generator, with exact isolated differential checks. **This is not a full Maven build or deployable replacement.** The Maven instructions above describe how to attempt a build once JDK 25 and full API reconciliation are available; do not infer that those steps have passed.
