# R14.15 — One Creek Origin per City

R14.15 adds the missing city-level uniqueness gate for creek-origin landmark buildings.

Creek origins were already registered in the world layout as fixed landmarks, but they used a separate FixedPlanner and therefore did not inherit the ordinary LandmarkPlanner's site limits. The bounded creek site planner only prevented overlapping origin/river footprints and nearby clone conflicts; those checks do not guarantee one origin per city.

The planner now resolves the highway city containing each origin park. It enumerates all candidate 64x64 host tiles inside that city's actual box and selects one deterministic winner by the existing unsigned site-rank ordering. The winner is independent of which city tile happens to be queried first. Other creek-origin candidates in that same city are rejected before their fixed park and creek are exposed to the world layout.

Creek origins outside a present highway-city box retain their previous behavior; this change specifically enforces one origin per actual generated city. Ordinary landmark spacing, park/ring collision checks, receiver-water validation, and bounded creek ownership remain in place.

QA/R14_15/run_creek_city_origin_policy_probe.sh verifies that each city has exactly one deterministic winner, that unsigned ranking is enumeration-order independent, and that unrelated cities arbitrate independently.
