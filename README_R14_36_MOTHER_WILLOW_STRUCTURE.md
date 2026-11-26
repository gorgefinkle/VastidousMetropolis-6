# Metropolis R14.36 — Mother Willow structure rebuild

## Baseline and scope

Built from R14.35 commit `a429942d20a8d31cc438e0f0ae6dd858e287aa7d`.
This revision changes only the Mother Willow geometry/material palette and
adds its focused geometry regression. Missile silo work is explicitly deferred
until the user reviews this tree. Bloomlands remains a single coordinated
generation pass; no post-generation repair or cleanup pass is introduced.

## Mother Willow

- Retains the mature, broad lateral root plate and distributed deep sinkers;
  there is no central taproot.
- Extends the fused old-growth bole to a substantially larger scale, with
  staggered reiterated leaders and a broken, irregular crown rather than a
  flat umbrella roofline.
- Uses the 26.3 poplar wood set for the trunk, roots, scaffold limbs and
  exposed finger branches. Branches remain wood all the way to their tips.
- Uses persistent yellow poplar leaves for a coherent golden willow canopy.
  Pendulous shoots are longer, fuller, and layered around woody twigs; fence
  blocks are not used as foliage.
- Keeps the existing deterministic seed derivation, terrain ownership,
  chunk-bounded writes, and caller-controlled placement.

## Validation

The R14.36 workflow runs a focused geometry probe for old-growth height,
root depth, woody scaffold volume, persistent poplar foliage, and the absence
of fence/legacy oak blocks, then performs the complete Java 25 Maven build
and packages the current JAR with the full source tree and checksums.
