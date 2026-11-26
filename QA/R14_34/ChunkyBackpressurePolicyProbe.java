package ch.k42.metropolis.generator;

/** Regression tests for R14.34's R14.33-compatible Chunky recovery gate. */
public final class ChunkyBackpressurePolicyProbe {
    private static final long SECOND = 1_000_000_000L;
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;
    private static final long MAX_HEAP = 10L * GIB;

    private ChunkyBackpressurePolicyProbe() {}

    public static void main(String[] args) {
        int checks = 0;
        long now = 120L * SECOND;
        long stableSince = 90L * SECOND;
        long free40 = 6L * GIB;
        long free67 = (long) (MAX_HEAP * 0.33);
        long free80 = 2L * GIB;
        long free83 = (long) (MAX_HEAP * 0.17);

        // A single healthy low-heap sample must not bypass the stable window.
        require(!ready(0, 0.40, free40, stableSince, 1, false, 0L, now),
                "low heap with one healthy sample resumed too early"); checks++;

        // The dwell time is insufficient without ten consecutive healthy samples.
        require(!ready(0, 0.40, free40, stableSince, 9, false, 0L, now),
                "recovery resumed with fewer than ten healthy samples"); checks++;

        // Samples alone do not bypass the full 30-second dwell period.
        require(!ready(0, 0.40, free40, now - 29L * SECOND, 10, false, 0L, now),
                "recovery resumed before the full dwell period"); checks++;

        // A low heap can resume after both the dwell period and sample count pass.
        require(ready(0, 0.40, free40, stableSince, 10, false, 0L, now),
                "stable 40% recovery was incorrectly blocked"); checks++;

        // The former 68% fast path is also required to complete the stable window.
        require(!ready(0, 0.67, free67, stableSince, 1, false, 0L, now),
                "former 68% shortcut bypassed stable recovery"); checks++;

        // A stable high retained set remains eligible when there is adequate headroom.
        require(ready(0, 0.80, free80, stableSince, 10, false, 0L, now),
                "stable 80% retained heap with 20% free headroom was blocked"); checks++;

        // Above the 82% retained-heap ceiling is never resumable.
        require(!ready(0, 0.83, free83, stableSince, 20, false, 0L, now),
                "83% retained heap incorrectly passed the safety ceiling"); checks++;

        // The combined deferred/growth queue must be at or below 128.
        require(!ready(129, 0.40, free40, stableSince, 20, false, 0L, now),
                "queue above low-water mark did not block recovery"); checks++;

        // An active repeat-pause cooldown blocks otherwise healthy recovery.
        require(!ready(0, 0.40, free40, stableSince, 20, true, now + SECOND, now),
                "repeat-pause cooldown was ignored"); checks++;
        require(ready(0, 0.40, free40, stableSince, 20, true, now, now),
                "recovery remained blocked after cooldown expiry"); checks++;

        // Both absolute and percentage headroom guards remain active.
        require(!ready(0, 0.80, 700L * MIB, stableSince, 20, false, 0L, now),
                "recovery passed with less than 8% free headroom"); checks++;
        long smallMax = 2L * GIB;
        require(!ChunkyBackpressurePolicy.canResume(0, 0.80, 400L * MIB, smallMax,
                        stableSince, 20, false, 0L, now),
                "recovery passed with less than 512 MiB free"); checks++;

        // The repeat-pause window is 15 seconds, with a 60-second backoff.
        require(ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        true, 10L * SECOND, 22L * SECOND),
                "12-second rapid re-pause did not trigger cooldown"); checks++;
        require(ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        true, 10L * SECOND, 25L * SECOND),
                "15-second boundary should trigger cooldown"); checks++;
        require(!ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        true, 10L * SECOND, 25L * SECOND + 1L),
                "pause outside the repeat window incorrectly triggered cooldown"); checks++;
        require(!ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        false, 10L * SECOND, 11L * SECOND),
                "first-ever pause incorrectly triggered repeat cooldown"); checks++;

        // The sample counter, not a timestamp sentinel, supports negative nanoTime origins.
        long negativeNow = -60L * SECOND;
        long negativeStableSince = negativeNow - 30L * SECOND;
        require(ChunkyBackpressurePolicy.stableRecoveryReady(0.70, free80, MAX_HEAP,
                        negativeStableSince, 10, negativeNow),
                "negative monotonic clock origin disabled stable recovery"); checks++;

        require(ChunkyBackpressurePolicy.combinedQueueSize(70, 80) == 150,
                "deferred and growth queue counts were not combined"); checks++;
        require(ChunkyBackpressurePolicy.combinedQueueSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                        == Integer.MAX_VALUE,
                "combined queue count did not saturate safely"); checks++;

        require(ChunkyBackpressurePolicy.shouldKeepStableMode(0.80, free80, MAX_HEAP),
                "healthy retained heap should keep bounded stable mode"); checks++;
        require(ChunkyBackpressurePolicy.shouldPause(0.91, 0.80, free80, MAX_HEAP,
                        0, true, false) == false,
                "temporary high instantaneous usage should not eject a safe retained-heap mode"); checks++;
        require(ChunkyBackpressurePolicy.shouldPause(0.75, 0.70, (long)(MAX_HEAP * 0.25),
                        MAX_HEAP, 0, false, false),
                "ordinary 74% soft pause changed outside stable mode"); checks++;
        require(ChunkyBackpressurePolicy.shouldPause(0.86, 0.70, (long)(MAX_HEAP * 0.14),
                        MAX_HEAP, 0, false, false),
                "86% hard pause changed outside stable mode"); checks++;

        System.out.println("R14.34 ChunkyBackpressurePolicyProbe: " + checks + "/" + checks + " PASS");
    }

    private static boolean ready(int pending, double retainedUsage, long remaining,
                                 long stableSince, int stableSamples,
                                 boolean cooldownActive, long cooldownUntil, long now) {
        return ChunkyBackpressurePolicy.canResume(pending, retainedUsage, remaining, MAX_HEAP,
                stableSince, stableSamples, cooldownActive, cooldownUntil, now);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
