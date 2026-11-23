# Metropolis R14.10 DEV — Universal Biome Teleport

R14.10 adds one shared command for the named Metropolis wilderness biome catalog:

- `/metrobiome tp <biome>` — progressively searches outward from the player and teleports to the nearest verified safe landing in the requested biome.
- `/metrobiome list` — lists active, separately generated wilderness biomes.
- `/metrobiome cancel` — cancels the player's current search.

The command supports forest, mountains, desert, mesa, dryforest, rainforest, prairie, oldgrowth, deciduous, coastaldunes, karst and bloomlands. `karstlands` remains an accepted alias for `karst`. Hyphens and underscores are optional in multiword names. Wetlands is intentionally not offered as a separate biome: its configured share is retired and reassigned to ordinary forest.

Search is progressive and asynchronous, has no arbitrary radius or small fixed chunk cap, and is bounded by the world's border. Candidate selection uses the same deterministic wilderness picker and planner ownership as world generation. Only a candidate destination chunk is loaded/generated; the final teleport is withheld until the generated chunk contains a verified safe column of the requested biome.

The R14.9 durable deferred queue, Chunky memory backpressure, Morphomos natural-family behavior, elevated karst massif and open landbridge work are retained unchanged.

## Verification

CI runs the Java 25 Maven clean verify build, previous R14.8/R14.9 regression probes, and `QA/R14_10/run_metropolis_biome_target_probe.sh` to check catalog coverage, command aliases and retired wetlands handling.

This is a DEV build. CI compilation and deterministic probes do not replace a live Paper 26.2 test. The new command needs an in-server test in a world with wilderness biomes enabled, including a far-away Bloomlands and Karst search.


Build validation note: the universal biome target catalog and alias probe is also run on pull requests targeting the R14.10 integration branch.
