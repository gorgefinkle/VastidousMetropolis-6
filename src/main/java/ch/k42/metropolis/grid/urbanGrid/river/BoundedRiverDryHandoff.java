package ch.k42.metropolis.grid.urbanGrid.river;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverOwnershipPlan.DryUse;
import ch.k42.metropolis.grid.urbanGrid.river.BoundedRiverOwnershipPlan.Owner;

/** Hand off dry cells to the correct district owner, retaining riverside vegetation. */
public final class BoundedRiverDryHandoff {
    private BoundedRiverDryHandoff() { }
    public static DryUse plannedUse(int kind) { return plannedUse(kind,null); }
    public static DryUse plannedUse(int kind, ContextType context) { return plannedUse(kind,context,false); }
    public static DryUse plannedUse(int kind, ContextType context, boolean naturalBankOverride) {
        if (kind == 2 && naturalBankOverride) return DryUse.NATURAL_BANK;
        if (kind == 3) {
            if (context != null && context.isMorphomos()) return DryUse.NATURAL_BANK;
            return DryUse.CITY_BLOCK;
        }
        if (kind == 2 || kind == 1) return DryUse.PUBLIC_ROAD;
        return DryUse.NATURAL_BANK;
    }
    public static Owner resolveCell(Owner original,int kind) { return resolveCell(original,kind,null); }
    public static Owner resolveCell(Owner original,int kind,ContextType context) {
        return resolveCell(original,kind,context,false);
    }
    public static Owner resolveCell(Owner original,int kind,ContextType context,boolean naturalBankOverride) {
        if(original != Owner.DRY_CANDIDATE) return original;
        DryUse use=plannedUse(kind,context,naturalBankOverride);
        return switch(use) {
            case CITY_BLOCK -> Owner.CITY;
            case PUBLIC_ROAD -> Owner.CITY_ROAD;
            case NATURAL_BANK -> Owner.VEGETATED_BANK;
        };
    }
    public static boolean naturalShoreInsteadOfCarrier(boolean naturalShore,boolean carrierRoad) {
        return naturalShore && !carrierRoad;
    }
    public static boolean allowGenericBridge(boolean bridgeCandidate,boolean reservedForAnotherCrossing) {
        return !bridgeCandidate || !reservedForAnotherCrossing;
    }
    public static boolean mayPlaceRiver(Owner owner) {
        return owner==Owner.WATER || owner==Owner.VEGETATED_BANK || owner==Owner.RECEIVING_WATER;
    }
}
