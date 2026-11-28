package ch.k42.metropolis.grid.urbanGrid.provider;

/** Exact round-trip and memory-footprint checks for the packed growth ledger. */
public final class BranchSupportBufferProbe {
    public static void main(String[] args) {
        BranchSupportBuffer buffer = new BranchSupportBuffer(1);
        buffer.add(-30_000_000, -64, 30_000_000, false);
        buffer.add(Integer.MIN_VALUE / 64, 319, Integer.MAX_VALUE / 64, true);
        buffer.add(12345678, -512, -987654321 / 64, true);
        buffer.add(-(1 << 25), -1024, (1 << 25) - 1, true);
        buffer.add((1 << 25) - 1, 1023, -(1 << 25), false);

        require(buffer.size() == 5, "candidate count mismatch");
        check(buffer, 0, -30_000_000, -64, 30_000_000, false);
        check(buffer, 1, Integer.MIN_VALUE / 64, 319, Integer.MAX_VALUE / 64, true);
        check(buffer, 2, 12345678, -512, -987654321 / 64, true);
        check(buffer, 3, -(1 << 25), -1024, (1 << 25) - 1, true);
        check(buffer, 4, (1 << 25) - 1, 1023, -(1 << 25), false);

        BranchSupportBuffer merged = new BranchSupportBuffer(0);
        merged.addAll(buffer);
        require(merged.size() == buffer.size(), "bulk append lost entries");
        for (int i = 0; i < buffer.size(); i++) {
            check(merged, i, buffer.x(i), buffer.y(i), buffer.z(i), buffer.decayed(i));
        }

        BranchSupportBuffer sampled = new BranchSupportBuffer();
        sampled.addFrom(buffer, 2);
        check(sampled, 0, buffer.x(2), buffer.y(2), buffer.z(2), buffer.decayed(2));

        require(buffer.capacity() >= buffer.size(), "capacity below logical size");
        require(buffer.retainedBytes() == (long) buffer.capacity() * Long.BYTES,
                "retained-byte accounting mismatch");
        require(buffer.retainedBytes() == (long) buffer.capacity() * 8L,
                "each candidate should occupy exactly eight backing-array bytes");

        expectIllegal(() -> buffer.add(1 << 25, 0, 0, false), "X overflow accepted");
        expectIllegal(() -> buffer.add(0, 1024, 0, false), "Y overflow accepted");
        expectIllegal(() -> buffer.add(0, 0, -(1 << 25) - 1, false), "Z overflow accepted");

        long oldCapacityBytes = buffer.retainedBytes();
        buffer.releaseStorage();
        require(buffer.isEmpty() && buffer.size() == 0, "release did not clear logical entries");
        require(buffer.capacity() == 0 && buffer.retainedBytes() == 0,
                "release retained the completed candidate array");
        require(oldCapacityBytes > 0, "test did not exercise allocated storage");

        System.out.println("R14.6 BranchSupportBufferProbe: PASS");
    }

    private static void check(BranchSupportBuffer b, int i, int x, int y, int z, boolean decayed) {
        require(b.x(i) == x, "x mismatch at " + i);
        require(b.y(i) == y, "y mismatch at " + i);
        require(b.z(i) == z, "z mismatch at " + i);
        require(b.decayed(i) == decayed, "decay flag mismatch at " + i);
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected bounds guard.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
