package ch.k42.metropolis.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Disk-first, bounded-RAM pending population queue. Each outstanding chunk is
 * a durable uniquely named marker in one of 256 directories. At most 128
 * chunk keys are retained in the ready window. A marker is removed only after
 * the caller reports complete population.
 *
 * The retry deadline is stored in a separate sidecar and atomically replaced;
 * the job marker itself is never truncated or rewritten. A corrupt/missing
 * retry hint therefore cannot destroy the durable work record.
 *
 * Scheduling is at-least-once. Exactly-once block placement still depends on
 * the normal parcel lifecycle being idempotent after a process interruption.
 */
public final class DiskDeferredChunkQueue implements AutoCloseable {
    public static final int MAX_IN_MEMORY = 128;
    private static final int SHARDS = 256;
    private static final long RETRY_NS = 1_000_000_000L;

    private final Path base;
    private final ArrayDeque<Entry> window = new ArrayDeque<>();
    private final Set<Long> windowKeys = new HashSet<>();
    private long pending;
    private int scanShard;
    private DirectoryStream<Path> listing;
    private Iterator<Path> iterator;
    private Long active;
    private boolean closed;

    // A completed sweep lets us sleep until a known retry deadline rather than
    // rescanning an arbitrarily large disk backlog every server tick.
    private long lastCompleteScanAtMillis;
    private long earliestRetryAtMillis;
    // Only used when the filesystem refuses to persist an optional retry hint.
    // The transient map is independently capped and never grows with the backlog.
    private final Map<Long, Long> transientRetries = new HashMap<>();

    public record Chunk(int x, int z) {}
    private static final class Entry {
        final long key;
        Entry(long key) { this.key = key; }
    }

    public DiskDeferredChunkQueue(Path base, Path legacyTsv) throws IOException {
        this.base = base;
        // Stream all outstanding records instead of readAllLines or toArray.
        // Startup is O(number of pending files) in I/O, not in Java heap.
        if (Files.isDirectory(base)) {
            for (int i = 0; i < SHARDS; i++) {
                Path folder = shardDir(i);
                if (!Files.isDirectory(folder)) continue;
                try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.job")) {
                    for (Path file : files) {
                        parseName(file);
                        pending++;
                    }
                }
            }
        }
        // Migration is idempotent: unique markers prevent duplicate imports.
        // Keep the legacy TSV until every line has been imported successfully.
        if (legacyTsv != null && Files.isRegularFile(legacyTsv)) {
            try (BufferedReader lines = Files.newBufferedReader(legacyTsv, StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] coords = line.trim().split("\\t", -1);
                    if (coords.length != 2)
                        throw new IOException("Invalid legacy deferred-populates line: " + line);
                    try {
                        enqueue(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
                    } catch (NumberFormatException ex) {
                        throw new IOException("Invalid legacy deferred-populates coordinates: " + line, ex);
                    }
                }
            }
            Files.delete(legacyTsv);
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
    private static int x(long key) { return (int) (key >> 32); }
    private static int z(long key) { return (int) key; }
    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }
    private int shard(long key) { return (int) (mix(key) >>> 56); }
    private Path shardDir(int shard) {
        return base.resolve(String.format(Locale.ROOT, "%02x", shard));
    }
    private Path marker(long key) {
        return shardDir(shard(key)).resolve(String.format(Locale.ROOT, "%016x.job", key));
    }
    private static Path retryMarker(Path job) {
        return job.resolveSibling(job.getFileName().toString() + ".retry");
    }
    private long parseName(Path path) throws IOException {
        String name = path.getFileName().toString();
        if (!name.matches("[0-9a-f]{16}\\.job"))
            throw new IOException("Invalid deferred-populate marker: " + path);
        long result = Long.parseUnsignedLong(name.substring(0, 16), 16);
        if (!path.getParent().equals(shardDir(shard(result))))
            throw new IOException("Deferred-populate marker in wrong shard: " + path);
        return result;
    }

    /** Returns false if this coordinate was already pending. */
    public synchronized boolean enqueue(int x, int z) throws IOException {
        checkOpen();
        long key = key(x, z);
        Path record = marker(key);
        if (Files.exists(record)) return false;
        Files.createDirectories(record.getParent());
        // Remove an orphan retry sidecar left by a previous acknowledged job.
        Files.deleteIfExists(retryMarker(record));
        try {
            Files.createFile(record);
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            return false;
        }
        // Publish the in-memory accounting before the directory force. If
        // the filesystem rejects the force, the durable marker is still
        // visible to the watchdog/recovery scan and must not be orphaned from
        // this process's pending count.
        pending++;
        lastCompleteScanAtMillis = 0L; // invalidate an earlier disk sweep
        earliestRetryAtMillis = 0L;
        addToWindow(key);
        forceDirectory(record.getParent());
        return true;
    }

    private static void forceDirectory(Path path) throws IOException {
        try (FileChannel dir = FileChannel.open(path, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (UnsupportedOperationException unsupported) {
            // Some filesystems do not expose directory handles. In that case
            // the job file itself is still force-written by its creator and
            // the platform's filesystem durability model applies.
        }
    }

    private void addToWindow(long key) {
        // The active in-flight chunk is also a retained queue entry. Nested
        // populate callbacks may enqueue while it is active, so reserve that
        // slot instead of allowing 128 ready entries plus one active entry.
        int activeSlots = this.active == null ? 0 : 1;
        if (window.size() + transientRetries.size() + activeSlots >= MAX_IN_MEMORY
                || !windowKeys.add(key)) return;
        window.addLast(new Entry(key));
    }

    /** Read an optional retry hint. Invalid hints fail open: the job remains ready. */
    private static long retryAfterMillis(Path job) {
        Path hint = retryMarker(job);
        if (!Files.isRegularFile(hint)) return 0L;
        try {
            return Long.parseLong(Files.readString(hint, StandardCharsets.UTF_8).trim());
        } catch (IOException | NumberFormatException ignored) {
            return 0L;
        }
    }

    /** Atomically replace a retry hint without ever truncating the job marker. */
    private static boolean writeRetryAfter(Path job, long deadlineMillis) {
        Path hint = retryMarker(job);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(job.getParent(), hint.getFileName().toString(), ".tmp");
            byte[] bytes = Long.toString(deadlineMillis).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, hint, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, hint, StandardCopyOption.REPLACE_EXISTING);
            }
            forceDirectory(job.getParent());
            return true;
        } catch (IOException ex) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            }
            return false;
        }
    }

    /** Fill the ready window from disk, skipping jobs whose durable retry hint is not due. */
    private void refill() throws IOException {
        if (pending <= window.size() + (active == null ? 0 : 1)) return;
        long nowMillis = System.currentTimeMillis();
        if (lastCompleteScanAtMillis != 0L && earliestRetryAtMillis > nowMillis) return;
        if (lastCompleteScanAtMillis != 0L && earliestRetryAtMillis <= nowMillis) {
            lastCompleteScanAtMillis = 0L;
            earliestRetryAtMillis = 0L;
        }

        int visitedShards = 0;
        while (window.size() + transientRetries.size() < MAX_IN_MEMORY && visitedShards < SHARDS) {
            if (iterator != null && iterator.hasNext()) {
                Path candidate = iterator.next();
                long found = parseName(candidate);
                if ((active == null || active != found) && !windowKeys.contains(found)) {
                    Long transientAt = transientRetries.get(found);
                    if (transientAt != null) {
                        if (transientAt > nowMillis) {
                            if (earliestRetryAtMillis == 0L || transientAt < earliestRetryAtMillis)
                                earliestRetryAtMillis = transientAt;
                            continue;
                        }
                        transientRetries.remove(found);
                    }
                    long retryAt = retryAfterMillis(candidate);
                    if (retryAt <= nowMillis) {
                        addToWindow(found);
                    } else if (earliestRetryAtMillis == 0L || retryAt < earliestRetryAtMillis) {
                        earliestRetryAtMillis = retryAt;
                    }
                }
                continue;
            }

            closeListing();
            int n = scanShard;
            scanShard = (scanShard + 1) % SHARDS;
            visitedShards++;
            Path folder = shardDir(n);
            if (Files.isDirectory(folder)) {
                listing = Files.newDirectoryStream(folder, "*.job");
                iterator = listing.iterator();
            }
        }
        if (visitedShards == SHARDS) lastCompleteScanAtMillis = nowMillis;
    }

    /** Returns null when pending work is temporarily cooling down or absent. */
    public synchronized Chunk poll(long nowNanos) throws IOException {
        checkOpen();
        if (active != null) throw new IllegalStateException("A pending population is still in flight");
        if (window.size() < MAX_IN_MEMORY) refill();

        int trials = window.size();
        for (int i = 0; i < trials; i++) {
            Entry entry = window.removeFirst();
            windowKeys.remove(entry.key);
            if (!Files.exists(marker(entry.key)))
                throw new IOException("Pending population disappeared from disk: " + entry.key);
            active = entry.key;
            return new Chunk(x(entry.key), z(entry.key));
        }
        return null;
    }

    /** Acknowledge only after the complete population path has succeeded. */
    public synchronized void complete() throws IOException {
        checkOpen();
        if (active == null) throw new IllegalStateException("No population in flight");
        Path record = marker(active);
        // Delete the hint first. If job deletion fails, the still-durable job
        // becomes immediately eligible rather than being hidden by stale metadata.
        Files.deleteIfExists(retryMarker(record));
        if (!Files.deleteIfExists(record))
            throw new IOException("Cannot acknowledge missing pending population: " + record);
        try {
            // The acknowledgement is not complete until the directory entry
            // removal is durable. If the directory force fails, restore the
            // job marker before releasing the active in-flight slot.
            forceDirectory(record.getParent());
        } catch (IOException forceFailure) {
            try {
                if (!Files.exists(record)) {
                    Files.createFile(record);
                    forceDirectory(record.getParent());
                }
            } catch (IOException restoreFailure) {
                forceFailure.addSuppressed(restoreFailure);
            }
            throw forceFailure;
        }
        transientRetries.remove(active);
        active = null;
        pending--;
    }

    /** Retain the durable job after a failed or nested population. */
    public synchronized void retry(long nowNanos) {
        checkOpen();
        if (active == null) throw new IllegalStateException("No population in flight");
        long key = active;
        Path record = marker(key);
        long retryAtMillis = System.currentTimeMillis() + Math.max(1L, RETRY_NS / 1_000_000L);
        active = null;
        if (writeRetryAfter(record, retryAtMillis)) {
            transientRetries.remove(key);
            if (earliestRetryAtMillis == 0L || retryAtMillis < earliestRetryAtMillis)
                earliestRetryAtMillis = retryAtMillis;
        } else {
            // Filesystem trouble writing the optional hint must not lose the job.
            // Keep at most 128 transient deadlines; any overflow remains durable
            // and immediately eligible rather than consuming the RAM ready window.
            if (transientRetries.containsKey(key)
                    || window.size() + transientRetries.size() < MAX_IN_MEMORY) {
                transientRetries.put(key, retryAtMillis);
                if (earliestRetryAtMillis == 0L || retryAtMillis < earliestRetryAtMillis)
                    earliestRetryAtMillis = retryAtMillis;
            }
        }
    }

    /** Delay the next empty-queue wakeup to the earliest known retry deadline. */
    public synchronized long nextRetryDelayMillis() {
        if (pending <= 0L) return 0L;
        long nowMillis = System.currentTimeMillis();
        long delay = earliestRetryAtMillis > nowMillis ? earliestRetryAtMillis - nowMillis : 0L;
        // Ready work or an unfinished shard sweep should be polled next tick.
        if (!window.isEmpty() || lastCompleteScanAtMillis == 0L) return 0L;
        return delay;
    }

    public synchronized long pending() { return pending; }
    public synchronized int inMemory() {
        return window.size() + transientRetries.size() + (active == null ? 0 : 1);
    }
    public synchronized boolean hasPending() { return pending > 0; }

    private void closeListing() throws IOException {
        if (listing != null) listing.close();
        listing = null;
        iterator = null;
    }
    private void checkOpen() {
        if (closed) throw new IllegalStateException("Deferred population queue is closed");
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (active != null) throw new IllegalStateException("Cannot close an active population without completing/retrying it");
        closeListing();
        closed = true;
    }
}
