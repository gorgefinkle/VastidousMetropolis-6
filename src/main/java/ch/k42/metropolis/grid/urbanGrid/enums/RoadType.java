package ch.k42.metropolis.grid.urbanGrid.enums;

public enum RoadType {
   STREET_X,
   STREET_I_NS,
   STREET_I_EW,
   STREET_T_W,
   STREET_T_E,
   STREET_T_N,
   STREET_T_S,
   STREET_C_SE,
   STREET_C_SW,
   STREET_C_NW,
   STREET_C_NE,
   STREET_D_N,
   STREET_D_S,
   STREET_D_E,
   STREET_D_W,
   HIGHWAY_C_NE,
   HIGHWAY_C_NW,
   HIGHWAY_C_SE,
   HIGHWAY_C_SW,
   HIGHWAY_SIDE_N,
   HIGHWAY_SIDE_E,
   HIGHWAY_SIDE_S,
   HIGHWAY_SIDE_W,
   HIGHWAY_T_N,
   HIGHWAY_T_E,
   HIGHWAY_T_S,
   HIGHWAY_T_W,
   NONE,
   // Two-neighbour bend of the city highway network (see HighwayNetwork.pieceFor()): a lane that
   // turns a corner with its seam on the INSIDE of the turn. The name is the pair of sides the
   // lane connects, like the HIGHWAY_C_* pieces. Appended after NONE so the ordinals of every
   // existing constant stay exactly as they were.
   HIGHWAY_TURN_NE,
   HIGHWAY_TURN_SE,
   HIGHWAY_TURN_SW,
   HIGHWAY_TURN_NW,
   // A tee whose back faces a parcel, not another lane: a highway that joins a highway (a ring meeting the highway that
   // crosses its city), seen from the lane beside the junction's C cells. Same openness as HIGHWAY_T_* (the name is the
   // turn-off), but the back is a sidewalk where HIGHWAY_T_* has a median. Appended last so no ordinal moves.
   HIGHWAY_TIN_N,
   HIGHWAY_TIN_E,
   HIGHWAY_TIN_S,
   HIGHWAY_TIN_W,
   // The outer corner of a plain bend (no street joining it): same openness and name as HIGHWAY_TURN_*, but its own
   // schematic, whose sidewalks line up with the Tin straights beside it. Appended last so no ordinal moves. A server
   // without these schematics falls back to HIGHWAY_TURN_* of the same corner (see HighwayParcel.getFits()).
   HIGHWAY_TURNOUT_NE,
   HIGHWAY_TURNOUT_SE,
   HIGHWAY_TURNOUT_SW,
   HIGHWAY_TURNOUT_NW,
   // The mirror image of HIGHWAY_TIN_*: the same tee, but with the wide and the narrow corner swapped. The two straights of
   // a bend are mirror images of each other, so one of them takes this piece. Same openness and name as HIGHWAY_TIN_*.
   // Appended last so no ordinal moves; a server without these schematics falls back to HIGHWAY_TIN_*.
   HIGHWAY_TINM_N,
   HIGHWAY_TINM_E,
   HIGHWAY_TINM_S,
   HIGHWAY_TINM_W;
}
