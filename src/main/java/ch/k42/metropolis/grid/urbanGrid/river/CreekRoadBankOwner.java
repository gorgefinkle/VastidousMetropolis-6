package ch.k42.metropolis.grid.urbanGrid.river;

/** Restored from the R30ea member/bytecode reference; not an October 31 source. */
public final class CreekRoadBankOwner {
    private CreekRoadBankOwner() {}

    public interface Cells {
        boolean dryBankCandidate(int x, int z);
        boolean realRoad(int x, int z);
        boolean sameCreekBank(int x, int z);
    }

    public static boolean belongs(int x, int z, boolean alongX, Cells cells) {
        if (cells == null || !cells.dryBankCandidate(x, z)) return false;
        int dx = alongX ? 1 : 0;
        int dz = alongX ? 0 : 1;
        for (int direction = -1; direction <= 1; direction += 2) {
            int stepX = direction * dx;
            int stepZ = direction * dz;
            for (int distance = 1; distance <= 3; distance++) {
                int nx = x - distance * stepX;
                int nz = z - distance * stepZ;
                if (!cells.sameCreekBank(nx, nz)) {
                    if (!cells.dryBankCandidate(nx, nz)) break;
                    continue;
                }
                for (int outward = 1; distance + outward - 1 <= 3; outward++) {
                    int rx = x + outward * stepX;
                    int rz = z + outward * stepZ;
                    if (cells.realRoad(rx, rz)) return true;
                    if (!cells.dryBankCandidate(rx, rz)) break;
                }
                break;
            }
        }
        return false;
    }
}
