# R14.7 QA

Run from the repository root:

```sh
sh QA/R14_7/run_boundary_family_probe.sh
```

The probe compiles only the pure policy classes and needs a JDK. It checks:
- FOREST, RIVER and OCEAN share the natural boundary family.
- FARM remains a separate family.
- Every natural-context pairing is reciprocal.
- FARM/FARM qualifies while FARM/natural does not.
- A natural block may dissolve against a natural ZONE/NATURAL exterior.\n- Natural-exterior-to-natural-exterior seams dissolve even when no natural BLOCK is adjacent.
- Unknown/urban contexts never qualify.

The GitHub Actions workflow additionally performs a full Java 25 Maven clean verify and packages the complete source tree with the resulting plugin JAR.

This probe does not replace the planned full-world regression matrix for negative coordinates, tile-boundary reciprocity, creek ownership, multi-seed road geometry, or live Paper worldgen. Those remain required before a production-ready release.
