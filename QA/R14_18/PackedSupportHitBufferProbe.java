package ch.k42.metropolis.grid.urbanGrid.provider;

/** Checks lossless coordinate/decay packing and dynamic capacity growth. */
public final class PackedSupportHitBufferProbe {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        PackedSupportHitBuffer buffer = new PackedSupportHitBuffer();
        int count = 8193;
        for (int i = 0; i < count; i++) {
            int x = (i % 2 == 0 ? -1 : 1) * (30_000_000 - i * 17);
            int z = (i % 3 == 0 ? -1 : 1) * (29_000_000 - i * 13);
            int y = i % 255;
            boolean decayed = (i & 1) == 0;
            buffer.add(x, y, z, decayed);
            int at = buffer.size() - 1;
            check(buffer.x(at) == x, "X changed during packed round trip at " + i);
            check(buffer.y(at) == y, "Y changed during packed round trip at " + i);
            check(buffer.z(at) == z, "Z changed during packed round trip at " + i);
            check(buffer.decayed(at) == decayed, "decayed flag changed at " + i);
        }
        check(buffer.size() == count, "buffer lost entries during growth");
        check(!buffer.isEmpty(), "buffer incorrectly reports empty");

        PackedSupportHitBuffer ordered = new PackedSupportHitBuffer(4, true);
        for (int i = 0; i < count; i++) ordered.add(i - 4096, i % 321, 4096 - i, (i & 3) == 0, count - i);
        for (int i = 0; i < count; i++) {
            check(ordered.sourceOrder(i) == count - i, "source order changed at " + i);
        }
        System.out.println("PackedSupportHitBufferProbe: PASS (" + count + " packed positions and source-order tags round-tripped)");
    }
}
