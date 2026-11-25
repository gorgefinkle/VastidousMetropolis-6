package ch.k42.metropolis.generator;

/** Regression tests for retained-heap recovery and instantaneous headroom guards. */
public final class ChunkyBackpressurePolicyProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long max = 12L * 1024L * 1024L * 1024L;
        long now = 120_000_000_000L;
        long stableSince = now - ChunkyBackpressurePolicy.STABILITY_NS;

        // Log-shaped case: the instantaneous heap is high, but the post-GC
        // retained set is healthy and at least 8% of max remains free.
        double current91 = 0.91;
        double retained70 = 0.70;
        long free9 = (long) (max * 0.09);
        check(ChunkyBackpressurePolicy.canResume(128, current91, retained70,
                free9, max, stableSince, now),
                "stable 70% retained heap must recover at 91% instantaneous use with 9% free");
        check(ChunkyBackpressurePolicy.shouldKeepStableMode(retained70, free9, max),
                "healthy retained heap and headroom must enter stable operating band");
        check(!ChunkyBackpressurePolicy.shouldPause(current91, retained70,
                free9, max, 0, true, false),
                "temporary 91% use must not immediately re-pause a safe stable run");

        check(!ChunkyBackpressurePolicy.canResume(129, current91, retained70,
                free9, max, stableSince, now), "queue above 128 must remain paused");
        check(!ChunkyBackpressurePolicy.canResume(128, current91, 0.821,
                free9, max, stableSince, now), "retained heap above 82% must not recover");
        check(!ChunkyBackpressurePolicy.canResume(128, current91, retained70,
                (long)(max * 0.079), max, stableSince, now),
                "less than 8% free headroom must not recover");
        check(!ChunkyBackpressurePolicy.canResume(128, current91, retained70,
                free9, max, now - ChunkyBackpressurePolicy.STABILITY_NS + 1, now),
                "retained heap must be stable for the full dwell period");

        // System.nanoTime() has an arbitrary origin and may be negative.
        long negativeNow = -60_000_000_000L;
        long negativeStableSince = negativeNow - ChunkyBackpressurePolicy.STABILITY_NS;
        check(ChunkyBackpressurePolicy.stableRecoveryReady(retained70, free9, max,
                negativeStableSince, negativeNow),
                "a negative monotonic clock value must not disable stable recovery");
        check(!ChunkyBackpressurePolicy.stableRecoveryReady(retained70, free9, max,
                0L, negativeNow),
                "the zero sentinel must still mean that stability tracking has not started");

        // Stable mode is bounded by retained heap, free headroom, and queue size.
        check(ChunkyBackpressurePolicy.shouldPause(current91, retained70,
                (long)(max * 0.079), max, 0, true, false),
                "stable mode must pause below the 8% free-headroom floor");
        check(ChunkyBackpressurePolicy.shouldPause(current91, retained70,
                free9, max, 1024, true, false),
                "queue high-water mark must override stable mode");
        check(ChunkyBackpressurePolicy.shouldPause(0.75, 0.70,
                (long)(max * .25), max, 0, false, false),
                "ordinary soft pause must apply outside stable mode");
        check(ChunkyBackpressurePolicy.shouldPause(0.86, 0.70,
                (long)(max * .14), max, 0, false, false),
                "ordinary hard pause must apply outside stable mode");
        check(!ChunkyBackpressurePolicy.shouldPause(0.75, 0.70,
                (long)(max * .25), max, 0, false, true),
                "a recovery probe must not immediately re-pause at the soft threshold");

        // Normal low-heap recovery remains immediate.
        check(ChunkyBackpressurePolicy.canResume(128, 0.68, 0.68,
                (long)(max * .32), max, 0L, now),
                "normal <=68% recovery must be immediate");

        // Absolute free memory protects small heaps even when their percentage
        // looks healthy.
        long smallMax = 2L * 1024L * 1024L * 1024L;
        long smallFree = (long)(smallMax * .20);
        check(!ChunkyBackpressurePolicy.canResume(0, .80, .80,
                smallFree, smallMax, stableSince, now),
                "80% of a 2 GiB heap leaves less than 512 MiB free");

        long largeMax = 8L * 1024L * 1024L * 1024L;
        long largeFree = (long)(largeMax * .20);
        check(ChunkyBackpressurePolicy.hasStableHeadroom(.80, largeFree, largeMax),
                "80% retained use of an 8 GiB heap qualifies with 20% free");

        System.out.println("ChunkyBackpressurePolicyProbe: PASS (post-GC recovery, headroom floor, queue/hard guards)");
    }
}
