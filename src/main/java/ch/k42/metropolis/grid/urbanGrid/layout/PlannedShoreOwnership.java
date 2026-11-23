package ch.k42.metropolis.grid.urbanGrid.layout;

/** Ownership classifier for planned shore reservations, reconstructed from R30ea. */
public final class PlannedShoreOwnership {
    private PlannedShoreOwnership() { }

    public static WaterField.Kind nearestKind(WaterField field, int x, int z) {
        double oceanDistance = field.oceanSignedDistanceAt(x, z);
        boolean deepOcean = !Double.isNaN(oceanDistance) && oceanDistance < -160.0;
        for (int radius = 1; radius <= 16; ++radius) {
            WaterField.Kind found = WaterField.Kind.NONE;
            for (int dx = -radius; dx <= radius; ++dx) {
                for (int dz = -radius; dz <= radius; ++dz) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    WaterField.Kind candidate = field.zoneKind(x + dx, z + dz);
                    if (candidate == WaterField.Kind.OCEAN) {
                        if (!deepOcean) return candidate;
                    } else if (candidate != WaterField.Kind.NONE) {
                        found = candidate;
                    }
                }
            }
            if (found != WaterField.Kind.NONE) return found;
        }
        return WaterField.Kind.NONE;
    }

    public static boolean shoreOwns(WorldLayout.Mask zone, int x, int z) {
        if (zone == null) return false;
        if (zone.at(x,z)) return true;
        if (zone instanceof WaterField) {
            WaterField field = (WaterField) zone;
            return field.zoneKind(x,z) != WaterField.Kind.NONE
                    || nearestKind(field,x,z) != WaterField.Kind.NONE;
        }
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                if (zone.at(x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    static final class Prepared {
        final WorldLayout.Mask zone;
        final int left;
        final int top;
        final int radius;
        final int[][] ocean;
        final int[][] other;

        Prepared(WorldLayout.Mask zone, int x0, int z0, int x1, int z1) {
            this.zone = zone;
            this.radius = 16;
            this.left = x0 - radius;
            this.top = z0 - radius;
            int width = x1 - x0 + 1 + radius * 2;
            int height = z1 - z0 + 1 + radius * 2;
            this.ocean = new int[width + 1][height + 1];
            this.other = new int[width + 1][height + 1];
            if (zone == null) return;
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < height; z++) {
                    int bx = left + x;
                    int bz = top + z;
                    int water = 0;
                    int land = 0;
                    if (zone instanceof WaterField) {
                        WaterField.Kind kind = ((WaterField)zone).zoneKind(bx,bz);
                        water = kind == WaterField.Kind.OCEAN ? 1 : 0;
                        land = kind != WaterField.Kind.NONE && kind != WaterField.Kind.OCEAN ? 1 : 0;
                    } else {
                        land = zone.at(bx,bz) ? 1 : 0;
                    }
                    ocean[x + 1][z + 1] = water + ocean[x][z + 1] + ocean[x + 1][z] - ocean[x][z];
                    other[x + 1][z + 1] = land + other[x][z + 1] + other[x + 1][z] - other[x][z];
                }
            }
        }

        private int count(int[][] integral, int bx, int bz) {
            int x = bx - left, z = bz - top;
            int a = x - radius, b = z - radius;
            int c = x + radius + 1, d = z + radius + 1;
            return integral[c][d] - integral[a][d] - integral[c][b] + integral[a][b];
        }

        boolean owns(int x, int z) {
            if (zone == null) return false;
            if (zone.at(x,z) || count(other,x,z) > 0) return true;
            if (zone instanceof WaterField) {
                double oceanDistance = ((WaterField)zone).oceanSignedDistanceAt(x,z);
                if (!Double.isNaN(oceanDistance) && oceanDistance < -160.0) return false;
            }
            return count(ocean,x,z) > 0;
        }
    }
}
