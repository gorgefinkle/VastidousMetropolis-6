# R14.22 — Preserve the pre-paint smoothstone safety cap

## Purpose

The normal-overworld Y=64 smoothstone layer is intentional. It is written
during base chunk generation, before any parcel or district populates. It
protects unpopulated chunk caps from exposing a grass-cap surface and must not
be moved into parcel painting, because chunks that never receive a parcel
would never get it.

## Seam handling

- Keep the pre-paint SMOOTH_STONE cap at Y=64 in normal-overworld chunks.
- Parcel painters may replace the cap when they paint their own water or land.
- When the regular forest painter raises a column above Y=64, the cap is now
  buried substrate; normalize only that buried block to ordinary stone. This
  removes the internal seam while retaining exposed safety caps in chunks
  that have not been raised and populated.
- Do not remove the base-generation cap as a seam fix.

## Validation

`SmoothstoneSeamProbe` verifies the overworld Y=64 cap is emitted as part of
base generation while ordinary fallback stone and non-overworld overrides
remain unchanged. `BuriedFallbackCapProbe` separately verifies that only a
buried legacy cap is normalized during raised forest-column painting.
