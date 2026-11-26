package ch.k42.metropolis.generator;

/** Regression tests for the high-retained-heap resume path. */
public final class ChunkyBackpressurePolicyProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        long max = 8L * 1024L * 1024L * 1024L;
        long now = 120_000_000_000L;
        long stableSince = now - ChunkyBackpressurePolicy.STABILITY_NS;

        // The exact reported failure case: post-GC live heap stays at 80%.
        double usage80 = 0.80;
        long free80 = (long) (max * (1.0 - usage80));
        check(ChunkyBackpressurePolicy.canResume(128, usage80, free80, max,
                stableSince, now), "stable 80% heap must recover when queue is <=128");
        check(ChunkyBackpressurePolicy.shouldKeepStableMode(usage80, free80, max),
                "80% stable recovery must enter stable operating band");
        check(!ChunkyBackpressurePolicy.shouldPause(usage80, 0, true, false),
                "stable 80% operating band must not immediately pause again");

        check(!ChunkyBackpressurePolicy.canResume(129, usage80, free80, max,
                stableSince, now), "queue above 128 must remain paused");
        check(!ChunkyBackpressurePolicy.canResume(128, usage80, free80, max,
                now - ChunkyBackpressurePolicy.STABILITY_NS + 1, now),
                "high heap must be stable for the full dwell period");
        check(!ChunkyBackpressurePolicy.canResume(128, 0.821, (long)(max * .179), max,
                stableSince, now), "heap above 82% must not enter stable recovery band");

        // Stable band is protective, not a free pass: the hard heap ceiling and
        // queue ceiling continue to pause generation.
        check(ChunkyBackpressurePolicy.shouldPause(0.86, 0, true, false),
                "86% hard pause must override stable mode");
        check(ChunkyBackpressurePolicy.shouldPause(0.80, 1024, true, false),
                "queue high-water mark must override stable mode");
        check(ChunkyBackpressurePolicy.shouldPause(0.75, 0, false, false),
                "ordinary soft pause must apply outside stable mode");
        check(!ChunkyBackpressurePolicy.shouldPause(0.75, 0, false, true),
                "a 30-second recovery probe must not immediately re-pause at 75%");

        int combinedAtHighWater = ChunkyBackpressurePolicy.combinedQueueSize(700, 324);
        check(combinedAtHighWater == ChunkyBackpressurePolicy.QUEUE_PAUSE,
                "deferred populate and branch-growth cache queues must share the 1,024 pause threshold");
        check(ChunkyBackpressurePolicy.shouldPause(0.50, combinedAtHighWater, false, false),
                "combined work backlog must pause Chunky even with low heap usage");
        check(ChunkyBackpressurePolicy.combinedQueueSize(80, 48) == ChunkyBackpressurePolicy.QUEUE_RESUME,
                "combined work backlog must be allowed to resume at 128");
        check(ChunkyBackpressurePolicy.combinedQueueSize(-10, Integer.MAX_VALUE) == Integer.MAX_VALUE,
                "combined backlog must clamp negative input and saturate without overflow");

        // Ordinary recovery below 68% does not wait for the 30-second dwell.
        check(ChunkyBackpressurePolicy.canResume(128, 0.68, (long)(max * .32), max,
                0L, now), "normal <=68% recovery must be immediate");

        // A small heap must not be allowed to resume at 80% with inadequate
        // absolute free memory, even if its percentage looks like a large heap.
        long smallMax = 2L * 1024L * 1024L * 1024L;
        long smallFree = (long)(smallMax * .20);
        check(!ChunkyBackpressurePolicy.canResume(0, .80, smallFree, smallMax,
                stableSince, now), "80% of a 2 GiB heap leaves insufficient free headroom");

        // At 80% of an 8 GiB heap there is sufficient headroom to resume.
        check(ChunkyBackpressurePolicy.hasStableHeadroom(.80, free80, max),
                "8 GiB heap at 80% must qualify for guarded recovery");
        System.out.println("ChunkyBackpressurePolicyProbe: PASS (stable 80% resumes; queue/hard ceilings still pause)");
    }
}
