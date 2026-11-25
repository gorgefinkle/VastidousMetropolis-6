package ch.k42.metropolis.grid.urbanGrid.forest;

/** Existing configs with the old 100% value must still receive the wider camp spacing. */
public final class WildDecorCampSpacingProbe {
    public static void main(String[] args) {
        if (WildDecorSpacingPolicy.campSpacing(100) != 250)
            throw new AssertionError("legacy/default 100% camp spacing was not raised to 250%");
        if (WildDecorSpacingPolicy.campSpacing(250) != 250)
            throw new AssertionError("new 250% camp default changed unexpectedly");
        if (WildDecorSpacingPolicy.campSpacing(400) != 400)
            throw new AssertionError("a higher configured camp spacing must remain respected");
        if (WildDecorSpacingPolicy.campSpacing(0) != 250)
            throw new AssertionError("disabled/invalid lower values must not restore frequent camps");
        System.out.println("WildDecorCampSpacingProbe: PASS legacy floor=250%, higher overrides preserved");
    }
}
