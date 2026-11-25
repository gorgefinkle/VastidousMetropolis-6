package ch.k42.metropolis.grid.urbanGrid.wild;

/** Pure deterministic profile checks for the named wilderness massif and lake basin. */
public final class WildernessLandmarksProbe {
    public static void main(String[] args) {
        long seed = 0x4D4554524F504F4CL;
        WildernessFeatures.MountainSite mountainA = new WildernessFeatures.MountainSite(
                seed, -17, 29, 3200.0, -6400.0, 720.0, 640.0, 0.37, 24);
        WildernessFeatures.MountainSite mountainB = new WildernessFeatures.MountainSite(
                seed, -17, 29, 3200.0, -6400.0, 720.0, 640.0, 0.37, 24);

        int baseY = 64, maxHeight = 320;
        int summitA = mountainA.heightAt(3200, -6400, baseY, maxHeight);
        int summitB = mountainB.heightAt(3200, -6400, baseY, maxHeight);
        if (summitA != maxHeight - mountainA.peakClearance || summitA != summitB) {
            throw new AssertionError("named massif does not reach a deterministic near-build-height summit: " + summitA);
        }
        if (mountainA.heightAt(3200 + 720, -6400, baseY, maxHeight) != baseY) {
            throw new AssertionError("named massif footprint does not fade to the surrounding ground at its edge");
        }
        if (mountainA.heightAt(3200, -6400, baseY, maxHeight) - baseY < 200) {
            throw new AssertionError("named massif is not tall enough to dominate the landscape");
        }
        if (!mountainA.snowy(summitA, baseY, maxHeight)
                || !mountainA.treeless(summitA, baseY, maxHeight)) {
            throw new AssertionError("summit should be alpine, snowy and above the treeline");
        }
        if (!mountainA.name.equals(mountainB.name)) {
            throw new AssertionError("mountain name is not stable for the same world seed and site");
        }

        // A bent outlet is one continuous course. Test both legs, the
        // corner itself, local highway-crossing axes and the legacy bank envelope.
        WildernessStreamCourse stream = new WildernessStreamCourse(seed,
                new double[]{8.0, 24.0, 24.0},
                new double[]{8.0, 8.0, 24.0}, 11.0, 8.0);
        if (!stream.isEnabled()) {
            throw new AssertionError("river-enabled test configuration unexpectedly disabled wilderness outlet carving");
        }
        if (stream.pointCount() != 3
                || Math.abs(stream.distanceFromCenterBlock(16.0, 8.0)) > 1.0e-9
                || Math.abs(stream.distanceFromCenterBlock(24.0, 16.0)) > 1.0e-9
                || Math.abs(stream.distanceFromCenterBlock(24.0, 8.0)) > 1.0e-9) {
            throw new AssertionError("polyline stream broke at its 90-degree bend");
        }
        if (!stream.isRiverOrBank(1, 0) || !stream.isRiverOrBank(1, 1)) {
            throw new AssertionError("stream course does not reserve both sides of its bend");
        }
        if (stream.getHalfWidthBlocks() * 0.75 < 3.0) {
            throw new AssertionError("stream is too narrow for the smallest explicit highway crossing bore");
        }
        if (stream.isZBoundAt(1, 0) || !stream.isZBoundAt(1, 1)
                || stream.forChunk(1, 0).isZBound() || !stream.forChunk(1, 1).isZBound()) {
            throw new AssertionError("local stream axis is not preserved at the bend");
        }
        if (Math.abs(stream.spineZBlock(16.0) - 8.0) > 1.0e-9
                || Math.abs(stream.spineXBlock(16.0) - 24.0) > 1.0e-9) {
            throw new AssertionError("local highway crossing spine query does not follow the correct stream leg");
        }
        if (!Double.isNaN(stream.distanceFromCenterBlock(1000.0, 1000.0))) {
            throw new AssertionError("stream course claims terrain beyond its bank envelope");
        }

        WildernessFeatures.LakeSite lakeA = new WildernessFeatures.LakeSite(
                seed, 11, -8, 1800.0, 2400.0, 288.0, 238.0, 0.61, 63);
        WildernessFeatures.LakeSite lakeB = new WildernessFeatures.LakeSite(
                seed, 11, -8, 1800.0, 2400.0, 288.0, 238.0, 0.61, 63);
        if (!lakeA.waterAt(1800, 2400) || lakeA.depthAt(1800, 2400) < 10) {
            throw new AssertionError("named lake basin lacks a substantial central water column");
        }
        if (lakeA.waterAt(1800 + 280, 2400)) {
            throw new AssertionError("named lake water extends beyond its shoreline edge");
        }
        if (!lakeA.intersectsChunk(Math.floorDiv(1800, 16), Math.floorDiv(2400, 16))) {
            throw new AssertionError("lake footprint does not claim its center chunk");
        }
        if (lakeA.intersectsChunk(10000, 10000)) {
            throw new AssertionError("lake footprint claims an unrelated chunk");
        }
        if (!lakeA.name.equals(lakeB.name)) {
            throw new AssertionError("lake name is not stable for the same world seed and site");
        }

        System.out.println("WildernessLandmarksProbe PASS: deterministic named massif reaches "
                + summitA + "/" + maxHeight + ", alpine treeline/snowline, bounded lake basin and stable names");
    }
}
