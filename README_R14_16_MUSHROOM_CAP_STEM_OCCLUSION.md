# R14.16 — Mushroom Cap / Stem Occlusion

R14.16 fixes giant mushrooms whose trunk could pass through the top of their cap.

The generated trunk used a fixed terminal height, while the six cap profiles have different upper and lower surfaces. The downward-curving profile could leave multiple exposed stem blocks above the cap. The renderer was correctly drawing the supplied block states; the geometry itself was wrong.

After assembling the entire cap, including secondary crown caps, the generator now finds the lowest cap block in each X/Z column. It emits stem only below that underside. The trunk remains attached to the cap, but cannot occupy or emerge above it. Cap shape, mushroom species, terrain permissions, and placement cadence remain unchanged.

QA runs all six variants across 96 deterministic seed/site combinations and verifies that any stem column shared with the cap terminates below the cap's underside.
