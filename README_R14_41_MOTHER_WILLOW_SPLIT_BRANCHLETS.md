# Metropolis R14.41 — Mother Willow Split Branchlets

## Reference silhouette

The target is a monumental, broad, rounded weeping-willow crown rather than an umbrella or a solid leaf ball. Real mature Salix babylonica references show a wide crown with thin pendulous branches sweeping outward and down, and finer branchlets hanging like a curtain. The tree therefore has:

- A visible, fused, woody trunk and main scaffold.
- Long primary arms that travel outward before turning downward.
- Secondary and terminal branches that become progressively finer and more pendulous.
- Actual forks at the end of terminal scaffold limbs: each splits into three or four slender hanging branchlets instead of ending as one tapered spike.
- Several separate leaf-bearing shoots extend from each outer fork, with leaf clusters spaced along their hanging length. Leaves stay concentrated on the outer branchlets; the inner crown stays woody and open rather than becoming a solid ball.

Reference sources:
- Oregon State University Landscape Plants — mature tree has a broad, rounded crown and thin pendulous branches: https://landscapeplants.oregonstate.edu/plants/salix-babylonica
- Purdue Arboretum Explorer — branchlets hang downward like a curtain: https://www.arboretum.purdue.edu/explorer/plants/22456/
- Virginia Tech — twigs are slender, long, pendulous and rope-like: https://web.uri.edu/wp-content/uploads/sites/2078/Salix-babylonica.pdf

## Corrected live block painter

MotherWillowGeometry emits internal poplar_log, poplar_wood, and yellow_poplar_leaves states. The old BiomeEcology adapter tested only for the obsolete oak_log prefix, so every wood state was misread and painted as leaves. The adapter now explicitly recognizes the actual geometry vocabulary: directional logs become logs, bark-sided wood becomes wood, and only leaf states become leaves.

## Regression coverage

QA/R14_40/MotherWillowBlockPainterProbe.java runs the production geometry through the same state classifier used by the live painter. It checks that wood and leaves are not cross-classified, both wood block forms are present, the woody scaffold is not leaf-dominated, and terminal limbs have a minimum number of real three- or four-way forks, a minimum volume of outer foliage, and a cap on leaves in the inner crown.

The tree remains based on the already approved deep, distributed lateral-root architecture. No central taproot, post-generation tree repair, or separate decoration pass was added.

## Scope inherited from R14.39

R14.39 lake-provider activation, unbounded /lake search and the independent /massif and /massif tp commands remain included. Named massifs remain separate from /metrobiome tp mountains.

## Verification limitation

The automated geometry/painter probe and complete Java 25 Maven build can verify source and block-state mapping, but do not replace the final in-game visual inspection. R14.41 is a test candidate, not a claim of user approval.