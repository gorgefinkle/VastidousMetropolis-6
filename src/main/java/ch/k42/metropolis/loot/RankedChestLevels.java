package ch.k42.metropolis.loot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Restored from the R30ea member/bytecode reference; not an October 31 source. */
public final class RankedChestLevels {
    private RankedChestLevels() {}

    public static Map<Integer, Integer> levels(List<Integer> chestIds, int low, int high) {
        int min = Math.max(1, Math.min(5, low));
        int max = Math.max(min, Math.min(5, high));
        List<Integer> ids = new ArrayList<>();
        for (int id : chestIds) if (!ids.contains(id)) ids.add(id);
        Collections.sort(ids);
        Map<Integer, Integer> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            int level = ids.size() == 1 ? min
                : min + (int) Math.round((double) i * (max - min) / (ids.size() - 1));
            result.put(ids.get(i), level);
        }
        return result;
    }
}
