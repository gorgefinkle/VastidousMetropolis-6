package ch.k42.metropolis.grid.urbanGrid.provider;

import java.util.Arrays;

/**
 * Primitive, packed storage for pending branch-growth support hits.
 *
 * Each hit occupies one long (8 bytes): signed 26-bit X, signed 26-bit Z,
 * signed 11-bit Y and one decay flag. This covers the Minecraft horizontal
 * world-border coordinate range and vanilla/Paper build heights, without
 * retaining a Bukkit Block wrapper, a SupportHit object or a List reference.
 *
 * Block wrappers are recreated only when a build is actually flushed.
 */
final class BranchSupportBuffer {
    private static final int HORIZONTAL_BITS = 26;
    private static final int Y_BITS = 11;
    private static final long HORIZONTAL_MASK = (1L << HORIZONTAL_BITS) - 1L;
    private static final long Y_MASK = (1L << Y_BITS) - 1L;
    private static final int MIN_HORIZONTAL = -(1 << (HORIZONTAL_BITS - 1));
    private static final int MAX_HORIZONTAL = (1 << (HORIZONTAL_BITS - 1)) - 1;
    private static final int MIN_Y = -(1 << (Y_BITS - 1));
    private static final int MAX_Y = (1 << (Y_BITS - 1)) - 1;

    private long[] packed;
    private int size;

    BranchSupportBuffer() { this(16); }

    BranchSupportBuffer(int initialCapacity) {
        this.packed = new long[Math.max(0, initialCapacity)];
    }

    int size() { return this.size; }
    boolean isEmpty() { return this.size == 0; }

    int x(int index) { return (int) (this.entry(index) >> 38); }

    int z(int index) {
        int value = (int) ((this.entry(index) >>> 12) & HORIZONTAL_MASK);
        return (value & (1 << (HORIZONTAL_BITS - 1))) == 0
                ? value : value | ~((1 << HORIZONTAL_BITS) - 1);
    }

    int y(int index) {
        int value = (int) ((this.entry(index) >>> 1) & Y_MASK);
        return (value & (1 << (Y_BITS - 1))) == 0
                ? value : value | ~((1 << Y_BITS) - 1);
    }

    boolean decayed(int index) { return (this.entry(index) & 1L) != 0L; }

    void add(int x, int y, int z, boolean decayed) {
        checkCoordinates(x, y, z);
        this.ensureCapacity(this.size + 1);
        this.packed[this.size++] = pack(x, y, z, decayed);
    }

    void addAll(BranchSupportBuffer source) {
        if (source == null || source.size == 0) return;
        this.ensureCapacity(this.size + source.size);
        System.arraycopy(source.packed, 0, this.packed, this.size, source.size);
        this.size += source.size;
    }

    void addFrom(BranchSupportBuffer source, int index) {
        this.ensureCapacity(this.size + 1);
        this.packed[this.size++] = source.entry(index);
    }

    long retainedBytes() { return (long) this.packed.length * Long.BYTES; }
    int capacity() { return this.packed.length; }

    void clear() { this.size = 0; }

    /** Releases a completed build's potentially large backing array immediately. */
    void releaseStorage() {
        this.packed = new long[0];
        this.size = 0;
    }

    private long entry(int index) {
        if (index < 0 || index >= this.size) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + this.size);
        }
        return this.packed[index];
    }

    private void ensureCapacity(int required) {
        if (required <= this.packed.length) return;
        int grown = Math.max(required, Math.max(16, this.packed.length + (this.packed.length >> 1) + 1));
        this.packed = Arrays.copyOf(this.packed, grown);
    }

    private static long pack(int x, int y, int z, boolean decayed) {
        return ((long) x & HORIZONTAL_MASK) << 38
                | ((long) z & HORIZONTAL_MASK) << 12
                | ((long) y & Y_MASK) << 1
                | (decayed ? 1L : 0L);
    }

    private static void checkCoordinates(int x, int y, int z) {
        if (x < MIN_HORIZONTAL || x > MAX_HORIZONTAL
                || z < MIN_HORIZONTAL || z > MAX_HORIZONTAL) {
            throw new IllegalArgumentException("Support hit outside packed world coordinates: " + x + "," + z);
        }
        if (y < MIN_Y || y > MAX_Y) {
            throw new IllegalArgumentException("Support hit outside packed build height: " + y);
        }
    }
}
