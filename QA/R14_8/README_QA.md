# R14.8 integration QA

Run after Maven has populated the local Gson dependency:

```sh
sh QA/R14_8/run_boundary_family_probe.sh
sh QA/R14_8/run_wild_biome_appearance_probe.sh
sh QA/R14_8/run_build_flag_exporter_probe.sh
```

The wilderness probe samples three known development seeds over large negative and positive coordinate ranges and asserts that Bloomlands and karst both survive the discrete biome field, with Bloomlands remaining rarer.

The build-flag probe checks the JSONL schema, deterministic building identity, duplicate suppression, restart indexing and interrupted final-line recovery.

These are deterministic source-level gates, not a substitute for the disposable Paper worldgen acceptance test (with Dead Streets absent).
