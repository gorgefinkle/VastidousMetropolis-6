package ch.k42.metropolis.generator;

/** R14.51 regression probe: branch work is independent from Chunky population flow. */
public final class ChunkyBranchQueueIsolationProbe {
    private static final long SECOND = 1_000_000_000L;
    private static final long MIB = 1024L * 1024L;
    private static final long GIB = 1024L * MIB;

    private ChunkyBranchQueueIsolationProbe() {}

    public static void main(String[] args) {
        int checks = 0;
        long now = -20L * SECOND;
        long stableSince = now - 10L * SECOND;
        long free1G = GIB;
        long free512M = 512L * MIB;

        require(ChunkyBackpressurePolicy.populationQueueSize(17) == 17,
                "population queue count changed"); checks++;
        require(ChunkyBackpressurePolicy.weightedActiveQueueSize(17, 9000) == 17,
                "branch growth was still charged against the population queue"); checks++;
        require(ChunkyBackpressurePolicy.queuesLowEnough(64, 9000),
                "parked branch jobs incorrectly blocked population queue recovery"); checks++;
        require(!ChunkyBackpressurePolicy.queuesLowEnough(65, 0),
                "population queue above its low-water mark was accepted"); checks++;

        require(!ChunkyBackpressurePolicy.shouldPause(0.40, 0, 9000),
                "large independent branch queue paused Chunky"); checks++;
        require(ChunkyBackpressurePolicy.shouldPause(0.40, 1024, 0),
                "population backlog pause threshold was lost"); checks++;
        require(!ChunkyBackpressurePolicy.shouldPause(0.73, 0, 0),
                "heap paused below the soft threshold"); checks++;
        require(ChunkyBackpressurePolicy.shouldPause(0.74, 0, 0),
                "74% soft heap pause threshold was lost"); checks++;

        require(!ChunkyBackpressurePolicy.canResume(32, 9000, 0.70, free1G,
                        stableSince, 10, false, 0L, now),
                "resume allowed while still above the 68% recovery band"); checks++;
        require(ChunkyBackpressurePolicy.canResume(32, 9000, 0.68, free1G,
                        stableSince, 10, false, 0L, now),
                "resume rejected at the lower hysteresis boundary"); checks++;
        require(ChunkyBackpressurePolicy.canResume(32, 9000, 0.50, free512M,
                        stableSince, 10, false, 0L, now),
                "exact 512 MiB free-memory floor was rejected"); checks++;
        require(!ChunkyBackpressurePolicy.canResume(32, 9000, 0.50, free512M - 1,
                        stableSince, 10, false, 0L, now),
                "resume passed below the absolute free-memory floor"); checks++;
        require(!ChunkyBackpressurePolicy.canResume(32, 9000, 0.68, free1G,
                        stableSince, 9, false, 0L, now),
                "resume accepted fewer than ten stable samples"); checks++;
        require(!ChunkyBackpressurePolicy.canResume(32, 9000, 0.68, free1G,
                        now - 9L * SECOND, 10, false, 0L, now),
                "resume accepted less than ten seconds in the recovery band"); checks++;

        require(ChunkyBackpressurePolicy.shouldApplyRapidRePauseCooldown(
                        true, 10L * SECOND, 22L * SECOND),
                "rapid re-pause was not recognized"); checks++;
        require(ChunkyBackpressurePolicy.RAPID_REPAUSE_COOLDOWN_NS == 5L * SECOND,
                "repeat-pause cooldown was not reduced to the short 5-second guard"); checks++;

        require(ChunkyBackpressurePolicy.queueRecoveryReady(32, 9000,
                        now - 10L * SECOND, 10, now),
                "negative monotonic clock origin broke queue recovery"); checks++;

        System.out.println("R14.51 ChunkyBranchQueueIsolationProbe: " + checks + "/" + checks + " PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
