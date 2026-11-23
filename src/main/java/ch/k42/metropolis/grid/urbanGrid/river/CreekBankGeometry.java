package ch.k42.metropolis.grid.urbanGrid.river;

/** Geometry calculations recovered from R30ea class methods. */
public final class CreekBankGeometry {
    private CreekBankGeometry() { }

    @FunctionalInterface
    public interface Owner {
        boolean creekBank(int chunkX, int chunkZ);
    }

    public static double rampEnd(double x, double z, boolean axisX, int side,
                                 double minimum, double reach, Owner owner) {
        if (side != -1 && side != 1) {
            throw new IllegalArgumentException("side must be -1 or +1");
        }
        if (reach <= minimum) return minimum + 0.5;
        double along = axisX ? x : z;
        double across = axisX ? z : x;
        int acrossChunk = Math.floorDiv((int) Math.floor(across), 16);
        int prevChunk = Integer.MIN_VALUE;
        for (int offset = 0; offset <= (int) Math.ceil(reach) + 17; offset++) {
            double cursor = along + side * offset;
            int chunk = Math.floorDiv((int) Math.floor(cursor), 16);
            if (chunk == prevChunk) continue;
            prevChunk = chunk;
            int cx = axisX ? chunk : acrossChunk;
            int cz = axisX ? acrossChunk : chunk;
            if (!owner.creekBank(cx, cz)) {
                double boundary = (side > 0 ? chunk : chunk + 1) * 16.0;
                return Math.max(minimum + 0.5, Math.min(reach, Math.abs(boundary - along)));
            }
        }
        return reach;
    }

    public static double[] slopeLimited(double[] values, double minimum, double step) {
        double[] result = values.clone();
        double limit = Math.max(0.1, step);
        for (int i = 1; i < result.length; ++i) {
            result[i] = Math.min(result[i], result[i - 1] + limit);
        }
        for (int i = result.length - 2; i >= 0; --i) {
            result[i] = Math.min(result[i], result[i + 1] + limit);
        }
        for (int i = 0; i < result.length; ++i) {
            result[i] = Math.max(minimum + 0.5, Math.min(result[i], values[i]));
        }
        return result;
    }
}
