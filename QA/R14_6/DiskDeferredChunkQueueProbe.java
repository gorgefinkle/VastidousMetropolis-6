import ch.k42.metropolis.generator.DiskDeferredChunkQueue;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class DiskDeferredChunkQueueProbe {
    static void verify(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("metropolis-disk-spool-");
        Path base = dir.resolve("pending");
        Path legacy = dir.resolve("deferred-populates.tsv");
        int total = 1200;
        int expectedTotal = total;
        Set<Long> expected = new HashSet<>();
        // Include multiple quadrants and the exact -1 boundary that used
        // to be mishandled by an unrelated planner sentinel.
        try (DiskDeferredChunkQueue q = new DiskDeferredChunkQueue(base, legacy)) {
            for (int i = 0; i < total; i++) {
                int x = i % 2 == 0 ? -(i + 1) : i;
                int z = 900 - i;
                long key = ((long)x << 32) | (z & 0xffffffffL);
                expected.add(key);
                verify(q.enqueue(x,z), "failed to queue new chunk " + i);
                verify(!q.enqueue(x,z), "duplicate created a second job " + i);
                verify(q.inMemory() <= DiskDeferredChunkQueue.MAX_IN_MEMORY, "window grew unbounded");
            }
            verify(q.pending() == total, "wrong initial queue count");
        }
        int processed = 0;
        // First restart: retry a job without acknowledgement, proving its
        // marker survives unsuccessful processing and another process open.
        try (DiskDeferredChunkQueue q = new DiskDeferredChunkQueue(base, legacy)) {
            verify(q.pending() == total, "startup missed pending jobs");
            var first = q.poll(10_000_000_000L);
            verify(first != null, "startup did not load pending jobs");
            // A nested populate can enqueue another chunk while this queue
            // already has one active job. The active slot must count toward
            // the same 128-entry retained-RAM limit.
            int nestedX = 9_876_543;
            int nestedZ = -1_234_567;
            long nestedKey = ((long) nestedX << 32) | (nestedZ & 0xffffffffL);
            expected.add(nestedKey);
            verify(q.enqueue(nestedX, nestedZ), "nested enqueue was not durably recorded");
            expectedTotal = total + 1;
            verify(q.inMemory() <= DiskDeferredChunkQueue.MAX_IN_MEMORY,
                    "active job plus nested enqueue exceeded the RAM window");
            q.retry(10_000_000_000L);
            verify(q.pending() == expectedTotal, "retry incorrectly acknowledged a job");
            verify(q.inMemory() <= DiskDeferredChunkQueue.MAX_IN_MEMORY,
                    "retry released active slot incorrectly");
            // Persisted retry hints use wall-clock time; wait out the one-second cooldown
            // so this probe remains deterministic across fast and slow CI hosts.
            Thread.sleep(1100L);
            long now = 20_000_000_000L;
            while (processed < 275) {
                var item = q.poll(now++);
                verify(item != null, "unexpected blocked spool");
                long key = ((long)item.x() << 32) | (item.z() & 0xffffffffL);
                verify(expected.remove(key), "duplicate/lost/invented marker");
                q.complete();
                processed++;
            }
            verify(q.pending() == expectedTotal - processed, "acknowledgement count incorrect");
            verify(q.inMemory() <= 128, "window overflow after drain");
        }
        // Migrate the old TSV format idempotently, including a duplicate.
        Files.writeString(legacy, "-1\t-1\n13\t-99\n13\t-99\n", StandardCharsets.UTF_8);
        expected.add(((-1L) << 32) | 0xffffffffL);
        expected.add((13L << 32) | (-99 & 0xffffffffL));
        try (DiskDeferredChunkQueue q = new DiskDeferredChunkQueue(base, legacy)) {
            verify(!Files.exists(legacy), "legacy file not removed after complete migration");
            verify(q.pending() == expectedTotal - processed + 2, "migration count incorrect");
            int remaining = (int)q.pending();
            for (int i = 0; i < remaining; i++) {
                var item = q.poll(25_000_000_000L + i);
                verify(item != null, "cannot make forward progress after migration");
                long key = ((long)item.x() << 32) | (item.z() & 0xffffffffL);
                verify(expected.remove(key), "unexpected or duplicate marker in drain");
                q.complete();
            }
            verify(expected.isEmpty(), "lost " + expected.size() + " expected jobs");
            verify(q.pending() == 0, "pending count was not zero");
            verify(q.poll(40_000_000_000L) == null, "spool returned phantom job");
        }
        // If legacy data is invalid, it must remain available for repair.
        Files.writeString(legacy, "invalid-input\n", StandardCharsets.UTF_8);
        boolean failClosed = false;
        try { new DiskDeferredChunkQueue(base, legacy).close(); }
        catch (java.io.IOException ex) { failClosed = true; }
        verify(failClosed && Files.exists(legacy), "corrupt legacy queue was discarded");
        System.out.println("PASS jobs=" + total + " nested-enqueue=PASS active-inclusive-window=128 restarts=2 retries=1 migrations=2 negative-coordinates=PASS no-loss=PASS fail-closed=PASS");
    }
}
