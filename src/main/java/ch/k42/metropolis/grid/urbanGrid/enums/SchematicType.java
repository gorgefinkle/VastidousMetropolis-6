package ch.k42.metropolis.grid.urbanGrid.enums;

public enum SchematicType {
   BUILD,
   ROAD,
   HIGHWAY,
   FILLER,
   // RiverParcel's tag -- procedurally carved (bed/bank/water), not
   // clipboard-based, but still tagged for consistency with the other
   // types and as a hook for a future schematic-based bank treatment.
   RIVER,
   // OceanParcel's tag -- same reasoning as RIVER above, kept distinct so
   // debug logging/future stats can tell the two water features apart.
   OCEAN,
   // BridgeParcel's tag -- a RoadParcel/HighwayParcel cell that a river
   // pushed through underneath it. Kept distinct from ROAD/HIGHWAY so
   // debug logging and any future stats can tell a bridge span apart
   // from ordinary pavement.
   BRIDGE;
}