# R14.4 deterministic road probes

`ActualRoadRuleProbeR144.java` is the strict source test. It uses the same planner setup as R14.3 `ActualRoadRuleProbe` but exits nonzero for missing required road frames, missing urban corners, malformed street shapes, or one-ended roads; seed `13` additionally asserts dry corner `(80,239)` is a committed street and survives Morphomos.

`run_regression.sh` captures the exact five-seed local runs with the current user's `plugins.zip` asset metadata and local Java/Paper library paths. It uses paths rooted at `/mnt/data` and is *not portable unchanged*. The asset reconstruction is included as `QA/seed_assets/` in the source ZIP; use Java 25 and a classpath with this plugin's full compiled classes, Paper API, WorldEdit, Gson, and the probe's supporting QA classes to rerun elsewhere.

`assert_planner_logs.py` is the independent fail-on-error output gate. It reads the exact logs and checks all five invocations ended `rc=0`, every expected window covered enough rectangles, and every required violation count was zero. The audit intentionally does **not** count a disconnected group of complete road rectangles as a violation.

`RoadIsolateProbe.java` prints actual source method decisions around rejected same-forest seam `(x=-12,z=243/260)`, and `RoadHoleDetailedProbe.java` shows the forest/water corner geometry around `(x=-10,z=135/170)`.

The five accepted sample logs are `seed_<seed>_final.log`. The direct seed-13 corner test is `strict_corner_13.log` (look for `DRY_CORNER_ASSERT`). The rejected rule's counterexample is `seed_987654321_rejected_overretain.log`. The Python gate reports `PASS: 5 seed windows` on these final logs.
