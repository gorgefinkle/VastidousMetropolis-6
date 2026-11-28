package ch.k42.metropolis.grid.urbanGrid.provider;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Reverse dependency lookup/cleanup probe for the coordinate-based parcel wait index. */
public final class BranchGrowthWaitIndexProbe {
    public static void main(String[] args) {
        BranchGrowthWaitIndex<Token> index = new BranchGrowthWaitIndex<>();
        Token buildA = new Token("build");
        Token buildB = new Token("build"); // value-equal, but distinct identity
        Set<Long> cellsA = Set.of(100L, 101L);
        Set<Long> cellsB = Set.of(101L, 102L);

        index.register(buildA, cellsA);
        index.register(buildB, cellsB);

        require(index.waiterCount() == 2, "registration count mismatch");
        require(index.edgeCount() == 4, "reverse edge count mismatch");
        require(index.waitingForCell(101L).size() == 2, "shared cell did not wake both builds");
        require(index.waitingForCell(100L).contains(buildA), "unique cell waiter missing");
        require(!index.waitingForCell(100L).contains(buildB), "unexpected identity alias");
        require(index.waitingForCell(102L).contains(buildB), "second unique cell waiter missing");
        require(index.waitingForCell(999L).isEmpty(), "unknown key unexpectedly has waiters");

        index.clear(buildA);
        require(index.waiterCount() == 1, "clear did not remove a registration");
        require(index.waitingForCell(100L).isEmpty(), "clear left stale cell reverse edge");
        require(index.waitingForCell(101L).size() == 1, "clear removed another build's shared edge");
        require(index.waitingForCell(101L).contains(buildB), "remaining waiter has wrong identity");

        // Re-register the same build after a fresh readiness scan. Old cell
        // edges must disappear even where they overlap the other build.
        index.register(buildA, Set.of(103L));
        require(index.waitingForCell(101L).size() == 1, "re-register retained stale edge");
        require(index.waitingForCell(103L).contains(buildA), "re-register new edge missing");
        index.clear(buildA);
        index.clear(buildB);
        require(index.waiterCount() == 0 && index.edgeCount() == 0,
                "index did not return to empty state");

        System.out.println("R14.6 BranchGrowthWaitIndexProbe: PASS");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private record Token(String value) {}
}
