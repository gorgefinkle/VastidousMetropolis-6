# Metropolis R14.5 — full buildable source (Paper 26.2)

This checkpoint contains **all 253 production Java source files**, 20 Java
regression-probe sources, `pom.xml`, `plugin.yml` and `config.yml`. It contains
no substitute Guava classes or other API stubs in the plugin source or JAR.

## Build

Required: JDK 25, Maven 3.9+, Paper 26.2 API, WorldEdit Bukkit 7.4.5.

```bash
java -version
mvn -U clean package
```

Expected Maven artifact: `target/Metropolis_R14_5_RoadCornerOrder_DEV.jar`.
The source was independently compiled using JDK 25 `javac` against the actual
Paper 26.2 runtime libraries (including Gson 2.14.0 and Guava 33.6.0), and
WorldEdit 7.4.5: 253 sources, 531 generated class files. The 20 test/probe
sources also compiled cleanly. **An independent Maven repository-resolution
run is not claimed.** The separate distributed JAR was built from the real
compiled production classes and the two `src/main/resources` files only.

## Running

The plugin requires a matching Paper 26.2 server, Java 25, and installed
WorldEdit. Keep the current `plugins/Metropolis` assets (from the current
`plugins.zip` upload), including schematics/config. Back up worlds before
switching DEV builds. This package does not claim all gameplay or terrain
regressions are fixed.

## What changed

See `README_R14_5_ROAD_CORNER_ORDER.md`. Relative to R14.4 only
`WorldLayout.java` (timing of existing dry corner reservation) and the plugin
version metadata have changed in production. No creek/tree ownership change.
The development probes and logs are in `QA/`.

`README_RECOVERY_HISTORY.md` retains older, superseded source-recovery notes
for provenance; they do **not** describe the R14.5 build status.
