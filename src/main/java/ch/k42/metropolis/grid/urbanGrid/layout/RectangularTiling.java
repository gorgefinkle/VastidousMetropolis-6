package ch.k42.metropolis.grid.urbanGrid.layout;

import java.util.ArrayList;
import java.util.List;

/** Recovered November 18 rectangular-component tiler. All coordinates are grid-local. */
final class RectangularTiling {
    private RectangularTiling() { }

    static int[] partition(int length, int min, int max) {
        if (length < 0 || min < 1 || max < min) return null;
        if (length == 0) return new int[0];
        int[] best = new int[length + 1];
        for (int n = 1; n <= length; n++) {
            int chosen = Math.min(max, n);
            while (chosen >= min) {
                if (n == chosen || (n > chosen && n - chosen - 1 >= 0
                        && best[n - chosen - 1] != 0)) {
                    best[n] = chosen;
                    break;
                }
                chosen--;
            }
        }
        if (best[length] == 0) return null;
        List<Integer> parts = new ArrayList<>();
        for (int remaining = length; remaining > 0; ) {
            int size = best[remaining];
            parts.add(size);
            remaining -= size;
            if (remaining > 0) remaining--;
        }
        return parts.stream().mapToInt(Integer::intValue).toArray();
    }

    static LayoutSearch.Result solve(boolean[][] free, int offX, int offZ, int min, int max) {
        int width = free.length;
        int depth = free[0].length;
        boolean[][] visited = new boolean[width][depth];
        boolean[][] streets = new boolean[width][depth];
        List<WorldLayout.Rect> blocks = new ArrayList<>();
        for (int x = 0; x < width; ++x) {
            for (int z = 0; z < depth; ++z) {
                if (!free[x][z] || visited[x][z]) continue;
                int[] queue = new int[width * depth];
                int head = 0, tail = 0;
                queue[tail++] = x * depth + z;
                visited[x][z] = true;
                int minX = x, maxX = x, minZ = z, maxZ = z;
                while (head < tail) {
                    int index = queue[head++];
                    int cx = index / depth, cz = index % depth;
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minZ = Math.min(minZ, cz);
                    maxZ = Math.max(maxZ, cz);
                    if (cx > 0 && free[cx - 1][cz] && !visited[cx - 1][cz]) {
                        visited[cx - 1][cz] = true;
                        queue[tail++] = (cx - 1) * depth + cz;
                    }
                    if (cx + 1 < width && free[cx + 1][cz] && !visited[cx + 1][cz]) {
                        visited[cx + 1][cz] = true;
                        queue[tail++] = (cx + 1) * depth + cz;
                    }
                    if (cz > 0 && free[cx][cz - 1] && !visited[cx][cz - 1]) {
                        visited[cx][cz - 1] = true;
                        queue[tail++] = cx * depth + cz - 1;
                    }
                    if (cz + 1 < depth && free[cx][cz + 1] && !visited[cx][cz + 1]) {
                        visited[cx][cz + 1] = true;
                        queue[tail++] = cx * depth + cz + 1;
                    }
                }
                if (tail != (maxX - minX + 1) * (maxZ - minZ + 1)) return null;
                int[] xParts = partition(maxX - minX + 1, min, max);
                int[] zParts = partition(maxZ - minZ + 1, min, max);
                if (xParts == null || zParts == null) return null;
                int cursorX = minX;
                for (int xi = 0; xi < xParts.length; ++xi) {
                    int cursorZ = minZ;
                    for (int zi = 0; zi < zParts.length; ++zi) {
                        blocks.add(new WorldLayout.Rect(
                                offX + cursorX, offZ + cursorZ,
                                offX + cursorX + xParts[xi] - 1,
                                offZ + cursorZ + zParts[zi] - 1));
                        cursorZ += zParts[zi];
                        if (zi + 1 < zParts.length) {
                            for (int rx = minX; rx <= maxX; rx++) streets[rx][cursorZ] = true;
                            cursorZ++;
                        }
                    }
                    cursorX += xParts[xi];
                    if (xi + 1 < xParts.length) {
                        for (int rz = minZ; rz <= maxZ; rz++) streets[cursorX][rz] = true;
                        cursorX++;
                    }
                }
            }
        }
        return new LayoutSearch.Result(blocks, streets, new boolean[width][depth]);
    }
}
