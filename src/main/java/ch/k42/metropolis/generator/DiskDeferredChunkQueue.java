package ch.k42.metropolis.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Disk-first, bounded-RAM pending population queue. Each pending chunk is a
 * durable, uniquely named marker in one of 256 directories. No historical
 * coordinate set or full queue snapshot is retained in memory; the only live
 * work window contains at most 128 chunk keys. The marker is removed ONLY
 * after the caller reports a successful population.
 *
 * This is deliberately independent of Bukkit, parcel ownership and the
 * generation plan; a pending coordinate is not a cached Parcel or a new
 * planning owner. Its single responsibility is at-least-once scheduling.
 *
 * At-least-once recovery means an interrupted population may be attempted
 * again after a crash. Exactly-once block placement still requires the normal
 * parcel lifecycle to be idempotent; this class must not claim that guarantee.
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

    public record Chunk(int x, int z) {}
    private static final class Entry {
        final long key;
        long retryAfter;
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
                        // Invalid names are kept on disk and cause a loud failure.
                        parseName(file);
                        pending++;
                    }
                }
            }
        }
        // Migration is idempotent: a partially imported TSV is still present
        // on restart; unique marker names prevent duplicates.
        if (legacyTsv != null && Files.isRegularFile(legacyTsv)) {
            try (BufferedReader lines = Files.newBufferedReader(legacyTsv, StandardCharsets.UTF_8)) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.isBlank()) continue;
                    String[] coords = line.trim().split("\\t", -1);
                    if (coords.length != 2) throw new IOException("Invalid legacy deferred-populates line: " + line);
                    try {
                        enqueue(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]));
                    } catch (NumberFormatException ex) {
                        throw new IOException("Invalid legacy deferred-populates coordinates: " + line, ex);
                    }
                }
            }
            // Do not delete the legacy queue until ALL its jobs have markers.
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
    private int shard(long key) { return (int)(mix(key) >>> 56); }
    private Path shardDir(int shard) { return base.resolve(String.format(Locale.ROOT, "%02x", shard)); }
    private Path marker(long key) {
        return shardDir(shard(key)).resolve(String.format(Locale.ROOT, "%016x.job", key));
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
        try {
            Files.createFile(record); // atomic CREATE_NEW: filename is the full record
        } catch (java.nio.file.FileAlreadyExistsException duplicate) {
            return false;
        }
        // On Linux a directory fsync makes the new marker durable even after
        // a sudden host failure; unsupported filesystems keep the regular
        // filesystem durability model rather than silently dropping jobs.
        forceDirectory(record.getParent());
        pending++;
        addToWindow(key);
        return true;
    }

    private static void forceDirectory(Path path) {
        try (FileChannel dir = FileChannel.open(path, StandardOpenOption.READ)) {
            dir.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not all filesystems allow opening directory handles.
        }
    }

    private void addToWindow(long key) {
        if (window.size() >= MAX_IN_MEMORY || !windowKeys.add(key)) return;
        window.addLast(new Entry(key));
    }

    /** Refill at most MAX_IN_MEMORY markers. All directory iteration is streaming. */
    private void refill() throws IOException {
        if (pending <= window.size() + (active == null ? 0 : 1)) return;
        // Only one directory sweep per refill. Resetting this counter when
        // any shard contains files would loop forever with fewer than 128
        // pending records (or when those records are already in the window).
        int visitedShards = 0;
        while (window.size() < MAX_IN_MEMORY && visitedShards < SHARDS) {
            if (iterator != null && iterator.hasNext()) {
                Path candidate = iterator.next();
                long found = parseName(candidate);
                // A failed job is cooled down on disk, not left occupying one
                // of the 128 ready-work slots. Otherwise one poison chunk (or
                // 128 simultaneous failures) can starve every later marker.
                if ((active == null || active != found) && !windowKeys.contains(found)
                        && retryAfter(candidate) <= System.currentTimeMillis())
                    addToWindow(found);
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
    }

    /** Returns null when pending work is temporarily cooling down or absent. */
    public synchronized Chunk poll(long nowNanos) throws IOException {
        checkOpen();
        if (active != null) throw new IllegalStateException("A pending population is still in flight");
        // Refill whenever there is room, not only when the window is empty.
        // Delayed retries are skipped by refill, so they cannot head-of-line
        // block ready work elsewhere in the disk spool.
        if (window.size() < MAX_IN_MEMORY) refill();
        int trials = window.size();
        for (int i = 0; i < trials; i++) {
            Entry entry = window.removeFirst();
            if (entry.retryAfter > nowNanos) {
                window.addLast(entry);
                continue;
            }
            windowKeys.remove(entry.key);
            if (!Files.exists(marker(entry.key))) {
                // External deletion is a data integrity failure, not success.
                throw new IOException("Pending population disappeared from disk: " + entry.key);
            }
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
        if (!Files.deleteIfExists(record))
            throw new IOException("Cannot acknowledge missing pending population: " + record);
        forceDirectory(record.getParent());
        active = null;
        pending--;
    }

    /** Retain the durable job after a failed or nested population. */
    public synchronized void retry(long nowNanos) {
        checkOpen();
        if (active == null) throw new IllegalStateException("No population in flight");
        long key = active;
        Path record = marker(key);
        // Persist a wall-clock retry deadline in the marker. On restart the
        // cooldown survives, while the failed job does not occupy a RAM slot.
        // If writing the hint fails, retain a bounded in-memory cooldown; the
        // durable empty marker still guarantees recovery after restart.
        long retryAt = System.currentTimeMillis() + Math.max(1L, RETRY_NS / 1_000_000L);
        boolean persisted = false;
        try (FileChannel channel = FileChannel.open(record, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            java.nio.ByteBuffer bytes = StandardCharsets.UTF_8.encode(Long.toString(retryAt));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
            persisted = true;
        } catch (IOException ignored) {
            // Keep the original durable marker. Do not drop or acknowledge it.
        }
        active = null;
        if (!persisted && windowKeys.add(key) && window.size() < MAX_IN_MEMORY) {
            Entry retry = new Entry(key);
            retry.retryAfter = nowNanos + RETRY_NS;
            window.addLast(retry);
        }
    }

    private static long retryAfter(Path marker) throws IOException {
        if (Files.size(marker) == 0L) return 0L; // never failed
        String value = Files.readString(marker, StandardCharsets.UTF_8).trim();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid deferred-populate retry deadline: " + marker, ex);
        }
    }

    public synchronized long pending() { return pending; }
    public synchronized int inMemory() { return window.size() + (active == null ? 0 : 1); }
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
