package ch.k42.metropolis.grid.urbanGrid.provider;

import java.util.Arrays;

/**
 * Primitive, dynamically sized storage for cached support coordinates.
 * Retains eight bytes per support hit, plus an optional four-byte source-order
 * index when whole-building candidate sampling needs stable insertion order.
 * Never retains Bukkit Block wrappers, chunk objects or world objects while a
 * build waits in the growth cache.
 */
final class PackedSupportHitBuffer {
    private static final long COORD_MASK = (1L << 26) - 1L;
    private long[] values;
    private int[] sourceOrder;
    private int size;

    PackedSupportHitBuffer() {
        this(256, false);
    }

    PackedSupportHitBuffer(int initialCapacity) {
        this(initialCapacity, false);
    }

    PackedSupportHitBuffer(int initialCapacity, boolean trackSourceOrder) {
        this.values = new long[Math.max(4, initialCapacity)];
        this.sourceOrder = trackSourceOrder ? new int[this.values.length] : null;
    }

    void add(int x, int y, int z, boolean decayed) {
        this.add(x, y, z, decayed, this.size);
    }

    void add(int x, int y, int z, boolean decayed, int originalSupportOrder) {
        if (this.size == this.values.length) {
            int nextCapacity = this.values.length * 2;
            this.values = Arrays.copyOf(this.values, nextCapacity);
            if (this.sourceOrder != null) this.sourceOrder = Arrays.copyOf(this.sourceOrder, nextCapacity);
        }
        // Minecraft's world border fits signed 26-bit X/Z. Eleven signed
        // Y bits cover the full modern build range (and negative neighbour
        // probes); the low bit stores the original decay result.
        this.values[this.size] = (((long) x & COORD_MASK) << 38)
                | (((long) z & COORD_MASK) << 12)
                | (((long) y & 0x7ffL) << 1)
                | (decayed ? 1L : 0L);
        if (this.sourceOrder != null) this.sourceOrder[this.size] = originalSupportOrder;
        this.size++;
    }

    int size() { return this.size; }
    int sourceOrder(int index) { return this.sourceOrder == null ? index : this.sourceOrder[index]; }
    boolean isEmpty() { return this.size == 0; }
    void clear() { this.size = 0; }

    private long at(int index) {
        if (index < 0 || index >= this.size) throw new IndexOutOfBoundsException(index);
        return this.values[index];
    }

    int x(int index) {
        return signExtend26((int) (this.at(index) >>> 38) & (int) COORD_MASK);
    }

    int z(int index) {
        return signExtend26((int) (this.at(index) >>> 12) & (int) COORD_MASK);
    }

    int y(int index) {
        int value = (int) ((this.at(index) >>> 1) & 0x7ffL);
        return (value << 21) >> 21; // sign-extend the packed 11-bit Y value
    }
    boolean decayed(int index) { return (this.at(index) & 1L) != 0L; }

    private static int signExtend26(int coordinate) {
        return (coordinate << 6) >> 6;
    }
}
