package ch.k42.metropolis.grid.urbanGrid.provider;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reverse index for builds waiting on exact world chunk cells to become
 * populated. Keys are world chunk coordinates, not Parcel identities: a
 * planner may replace a provisional Parcel before its final owner is pasted.
 *
 * Waiters use identity semantics because Parcel itself has identity semantics
 * and independent builds must never coalesce through value equality.
 */
final class BranchGrowthWaitIndex<T> {
    private final Map<Long, Set<T>> waitersByCell = new HashMap<>();
    private final IdentityHashMap<T, Set<Long>> registrations = new IdentityHashMap<>();

    void register(T waiter, Set<Long> cells) {
        this.clear(waiter);
        Set<Long> copy = new java.util.HashSet<>(cells);
        this.registrations.put(waiter, copy);
        for (long key : copy) {
            this.waitersByCell.computeIfAbsent(key, ignored ->
                    Collections.newSetFromMap(new IdentityHashMap<>())).add(waiter);
        }
    }

    void clear(T waiter) {
        Set<Long> cells = this.registrations.remove(waiter);
        if (cells == null) return;
        for (long key : cells) {
            Set<T> waiters = this.waitersByCell.get(key);
            if (waiters == null) continue;
            waiters.remove(waiter);
            if (waiters.isEmpty()) this.waitersByCell.remove(key);
        }
    }

    Set<T> waitingForCell(long key) {
        Set<T> found = this.waitersByCell.get(key);
        if (found == null) return Set.of();
        Set<T> copy = Collections.newSetFromMap(new IdentityHashMap<>());
        copy.addAll(found);
        return copy;
    }

    int edgeCount() {
        int count = 0;
        for (Set<Long> cells : this.registrations.values()) count += cells.size();
        return count;
    }

    int waiterCount() {
        return this.registrations.size();
    }
}
