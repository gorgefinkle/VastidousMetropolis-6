package ch.k42.metropolis.generator;

/**
 * Standalone regression probe for R14.19 Chunky recovery policy.
 * Run with ChunkyBackpressurePolicy.java on the class path.
 */
public final class ChunkyBackpressurePolicyProbe {
    private static final long SECOND = 1_000_000_000L;

    private ChunkyBackpressurePolicyProbe() {}

    public static void main(String[] args) {
        int checks = 0;

        // A low one-off reading must not bypass the stability window.
        require(!ChunkyBackpressurePolicy.canResume(
                0, 0.40, 6_000, 10_000, 1 * SECOND,
                1, 0L, 31 * SECOND),
                "40% heap with one healthy sample resumed too early");
        checks++;

        // Elapsed time alone is insufficient without enough consecutive samples.
        require(!ChunkyBackpressurePolicy.canResume(
                0, 0.40, 6_000, 10_000, 1 * SECOND,
                ChunkyBackpressurePolicy.MIN_STABLE_SAMPLES - 1, 0L, 31 * SECOND),
                "recovery resumed without the required healthy samples");
        checks++;

        // A full stable window with enough samples can resume at the normal target.
        require(ChunkyBackpressurePolicy.canResume(
                0, 0.40, 6_000, 10_000, 1 * SECOND,
                ChunkyBackpressurePolicy.MIN_STABLE_SAMPLES, 0L, 31 * SECOND),
                "healthy 40% recovery was incorrectly blocked");
        checks++;

        // The normal 68% target no longer bypasses stability.
        require(!ChunkyBackpressurePolicy.canResume(
                0, 0.67, 2_000, 10_000, 30 * SECOND,
                1, 0L, 31 * SECOND),
                "68% threshold bypassed the stable window");
        checks++;

        // High-but-safe recovery remains allowed after the same stable window.
        require(ChunkyBackpressurePolicy.canResume(
                0, 0.80, 1_000, 10_000, 1 * SECOND,
                ChunkyBackpressurePolicy.MIN_STABLE_SAMPLES, 0L, 31 * SECOND),
                "stable 80% recovery with 10% free headroom was blocked");
        checks++;

        // A high reading outside the 82% band is not eligible.
        require(!ChunkyBackpressurePolicy.canResume(
                0, 0.83, 1_700, 10_000, 1 * SECOND,
                40, 0L, 50 * SECOND),
                "83% heap incorrectly passed the stable recovery band");
        checks++;

        // A queue above the low-water mark blocks resume even with healthy memory.
        require(!ChunkyBackpressurePolicy.canResume(
                ChunkyBackpressurePolicy.QUEUE_RESUME + 1,
                0.40, 6_000, 10_000, 1 * SECOND,
                40, 0L, 50 * SECOND),
                "pending queue above low-water mark did not block resume");
        checks++;

        // The recovery cooldown blocks an otherwise healthy, stable heap.
        require(!ChunkyBackpressurePolicy.canResume(
                0, 0.40, 6_000, 10_000, 1 * SECOND,
                40, 90 * SECOND, 50 * SECOND),
                "recovery cooldown was ignored");
        checks++;

        // Both the absolute and percentage free-headroom gates remain active.
        require(!ChunkyBackpressurePolicy.canResume(
                0, 0.80, 700, 10_000, 1 * SECOND,
                40, 0L, 50 * SECOND),
                "recovery passed with less than 8% free headroom");
        checks++;

        // A pause within 15 seconds of resume creates a 60-second cooldown.
        long rapidCooldown = ChunkyBackpressurePolicy.rapidRePauseCooldownUntil(
                10 * SECOND, 22 * SECOND);
        require(rapidCooldown == 82 * SECOND,
                "rapid repeat pause did not create the expected 60-second cooldown");
        checks++;

        // A repeat pause after the 15-second window does not add cooldown.
        require(ChunkyBackpressurePolicy.rapidRePauseCooldownUntil(
                10 * SECOND, 26 * SECOND) == 0L,
                "late pause incorrectly triggered rapid-repeat cooldown");
        checks++;

        // Existing combined queue accounting remains overflow-safe.
        require(ChunkyBackpressurePolicy.combinedQueueSize(70, 80) == 150,
                "combined queue accounting changed");
        checks++;

        System.out.println("R14.19 ChunkyBackpressurePolicyProbe: " + checks + "/" + checks + " PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
