package ch.k42.metropolis.generator;

/** Regression tests for R14.35's queue-first, branch-growth-priority policy. */
public final class ChunkyQueueFirstRecoveryProbe {
    private static final long SECOND = 1_000_000_000L;
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private ChunkyQueueFirstRecoveryProbe() {}

    public static void main(String[] args) {
        int checks = 0;
        long now = 120L * SECOND;
        long stableSince = 110L * SECOND;
        long free1G = GIB;
        long free512M = 512L * MIB;

        require(ChunkyBackpressurePolicy.weightedActiveQueueSize(30, 20) == 70,
                "ready branch growth was not weighted twice"); checks++;
        require(ChunkyBackpressurePolicy.weightedActiveQueueSize(Integer.MAX_VALUE, Integer.MAX_VALUE)
                        == Integer.MAX_VALUE,
                "weighted queue did not saturate safely"); checks++;

        require(ChunkyBackpressurePolicy.shouldPause(0.40, 0, 128),
                "ready branch-growth backlog did not trigger priority pause"); checks++;
        require(ChunkyBackpressurePolicy.shouldPause(0.40, 1024, 0),
                "large deferred queue did not trigger protective pause"); checks++;
        require(!ChunkyBackpressurePolicy.shouldPause(0.40, 500, 0),
                "moderate deferred backlog paused too early"); checks++;
        require(!ChunkyBackpressurePolicy.shouldPause(0.40, 0, 127),
                "branch pause threshold fired below its boundary"); checks++;

        require(!ChunkyBackpressurePolicy.queuesLowEnough(65, 0),
                "deferred queue above low-water mark was accepted"); checks++;
        require(!ChunkyBackpressurePolicy.queuesLowEnough(32, 17),
                "branch queue above its own low-water mark was accepted"); checks++;
        require(!ChunkyBackpressurePolicy.queuesLowEnough(33, 16),
                "weighted combined queue above low-water mark was accepted"); checks++;
        require(ChunkyBackpressurePolicy.queuesLowEnough(32, 16),
                "low deferred and branch-growth queues were rejected"); checks++;
        require(ChunkyBackpressurePolicy.queuesLowEnough(64, 0),
                "deferred-only queue at its boundary was rejected"); checks++;

        require(!ready(32, 16, 0.80, free1G, stableSince, 9, false, 0L, now),
                "recovery accepted fewer than ten queue-stability samples"); checks++;
        require(!ready(32, 16, 0.80, free1G, now - 9L * SECOND, 10, false, 0L, now),
                "recovery accepted less than ten seconds at low queue depth"); checks++;
        require(ready(32, 16, 0.80, free1G, stableSince, 10, false, 0L, now),
                "queue-first recovery blocked a low backlog with safe emergency headroom"); checks++;

        // Heap percentage is no longer the recovery target. The former 82%
        // post-GC ceiling and 8%-of-max free-space test are deliberately absent.
        require(ready(32, 16, 0.85, free1G, stableSince, 10, false, 0L, now),
                "high-but-below-hard current heap use blocked queue-first recovery"); checks++;
        require(ChunkyBackpressurePolicy.hasEmergencyHeadroom(0.80, free512M),
                "exact 512 MiB absolute free-memory floor was rejected"); checks++;
        require(!ready(32, 16, 0.80, free512M - 1L, stableSince, 10, false, 0L, now),
                "recovery passed below the absolute 512 MiB safety floor"); checks++;
        require(!ready(32, 16, 0.86, free1G, stableSince, 10, false, 0L, now),
                "recovery crossed the emergency hard-pause ceiling"); checks++;

        require(!ready(32, 16, 0.50, free1G, stableSince, 10, true,
                        now + SECOND, now),
                "active repeat-pause cooldown was ignored"); checks++;
        require(ready(32, 16, 0.50, free1G, stableSince, 10, true, now, now),
                "recovery remained blocked after cooldown expiry"); checks++;

        require(ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        true, 10L * SECOND, 22L * SECOND),
                "12-second rapid re-pause did not trigger cooldown"); checks++;
        require(ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        true, 10L * SECOND, 25L * SECOND),
                "15-second rapid re-pause boundary did not trigger cooldown"); checks++;
        require(!ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        true, 10L * SECOND, 25L * SECOND + 1L),
                "pause outside repeat window incorrectly triggered cooldown"); checks++;
        require(!ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        false, 10L * SECOND, 11L * SECOND),
                "first pause incorrectly triggered repeat cooldown"); checks++;

        long negativeNow = -60L * SECOND;
        long negativeStableSince = negativeNow - 10L * SECOND;
        require(ChunkyBackpressurePolicy.queueRecoveryReady(32, 16,
                        negativeStableSince, 10, negativeNow),
                "negative monotonic clock origin disabled queue recovery"); checks++;

        require(!ChunkyBackpressurePolicy.queueRecoveryReady(128, 0,
                        stableSince, 10, now),
                "ready growth backlog was incorrectly treated as drained"); checks++;

        System.out.println("R14.35 ChunkyQueueFirstRecoveryProbe: " + checks + "/" + checks + " PASS");
    }

    private static boolean ready(int deferred, int growth, double usage, long remaining,
                                 long stableSince, int stableSamples,
                                 boolean cooldownActive, long cooldownUntil, long now) {
        return ChunkyBackpressurePolicy.canResume(deferred, growth, usage, remaining,
                stableSince, stableSamples, cooldownActive, cooldownUntil, now);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
