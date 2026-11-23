package ch.k42.metropolis.grid.urbanGrid.highway;

import ch.k42.metropolis.grid.urbanGrid.enums.RoadType;

/**
 * Which piece a highway lane chunk places, from the piece the network gave it, its place in a bend, and the streets
 * around it. Pure, so it is checked against the written rules without a server (see the example grids in the tests).
 *
 * <pre>
 *   Hs      a straight lane, open along its length only
 *   Hb      the plain bend (HIGHWAY_TURN), open on two perpendicular sides
 *   Ht x    a tee, open on three sides; x is the turn-off, the back faces the side where nothing joins
 *   Hn x    a tee like Ht, but its back faces a parcel, not a lane: a sidewalk where Ht has a median (rule 2)
 *   Hc xy   open on all four sides, crosswalks on the two sides x and y where a road or highway joins
 *
 *   1. A street joins a straight lane: that lane becomes Ht toward the street. Every other lane stays Hs.
 *   2. A lane beside a C (its side-by-side partner is a C) becomes Hn toward that C, a C being open on every side. Its turn-off
 *      points into the band, so its back is the outer side, which faces a parcel: that is why it is not Ht, whose back is the
 *      buddy lane (rule 1, a street joining) and has a median.
 *      If a street also joins that lane, it is open on all four sides (along its length, to the street, to the C), so it
 *      is itself a C: Hc facing the street and the way its band carries on.
 *   3. A bend is a 2x2 block, decided cell by cell:
 *        the inner turn (where the two arms point into) is always Hc, facing the two arm directions;
 *        the two straights beside the outer turn are Hn toward that C (their back faces a parcel, so Tin, not Ht); with a
 *          street on a straight's open side it is an Hc facing its arm and the street;
 *        the outer turn is Ho (HIGHWAY_TURNOUT, its own schematic; falls back to Hb); one street joining makes it an Hn (back faces the other outward side, a parcel),
 *          two make it an Hc facing both streets.
 *   4. Crossings and T junctions use the network's own C quadrants; rule 2 then turns the lanes next to them into Ts.
 * </pre>
 * Patterns rotate, they are never mirrored. Directions are indices: 0 north, 1 east, 2 south, 3 west.
 */
public final class HighwayPieces {
   private static final int[] DX = {0, 1, 0, -1};
   private static final int[] DZ = {-1, 0, 1, 0};

   /** Which straight of a bend takes the mirrored Tin: false = the one in the second arm (b), true = the one in the first (a). */
   private static final boolean MIRROR_FIRST_ARM = false;

   private HighwayPieces() {
   }

   /** What the resolver needs to know about the world around a lane. */
   public interface Surroundings {
      /** The piece the network gave the lane at (x, z), or null if there is no lane there. */
      RoadType baseAt(int x, int z);

      /** Does a street join the lane at (x, z) from the neighbour on side dir? A street running alongside does not. */
      boolean streetJoins(int x, int z, int dir);
   }

   /**
    * @param base the piece the network gave this lane
    * @param bend {role, ox, oz, a, b} from HighwayNetwork.bendInfo(), or null if the lane is not part of a bend
    */
   public static RoadType resolve(Surroundings s, int x, int z, RoadType base, int[] bend) {
      if (base == null) {
         return null;
      }
      if (bend != null) {
         RoadType inBend = resolveBend(s, x, z, base, bend);
         if (inBend != null) {
            return inBend;
         }
      }
      int out = outwardSide(base);
      if (out >= 0) {
         boolean street = s.streetJoins(x, z, out);
         int inward = (out + 2) % 4;
         RoadType partner = s.baseAt(x + DX[inward], z + DZ[inward]);
         boolean cPartner = partner != null && partner.name().startsWith("HIGHWAY_C_");
         if (street && cPartner) {
            // open on four sides: a C, named for the street and for the side its band carries on, which is
            // the side its partner C names besides the one facing this lane
            int carry = otherLetter(partner, inward);
            if (carry >= 0) {
               return cPiece(out, carry);
            }
         }
         // rule 1: a street joins this lane
         if (street) {
            return tPiece(out);
         }
         // rule 2: the lane next to it across the band is a C
         if (cPartner) {
            return tinPiece(inward);
         }
      }
      return base;
   }

   private static RoadType resolveBend(Surroundings s, int x, int z, RoadType base, int[] bend) {
      int role = bend[0];
      int ox = bend[1];
      int oz = bend[2];
      int a = bend[3];
      int b = bend[4];
      int outA = (a + 2) % 4;   // the outward sides are away from the arms
      int outB = (b + 2) % 4;
      if (role == HighwayNetwork.BEND_INNER) {
         return cPiece(a, b);
      }
      if (role == HighwayNetwork.BEND_OUTER) {
         boolean roadA = s.streetJoins(x, z, outA);
         boolean roadB = s.streetJoins(x, z, outB);
         if (roadA && roadB) {
            return cPiece(outA, outB);
         }
         if (roadA) {
            return tinPiece(b);   // the back faces outB, so the turn-off is the side opposite it; that back faces a parcel: Tin
         }
         if (roadB) {
            return tinPiece(a);
         }
         return turnoutPiece(base);
      }
      if (role == HighwayNetwork.BEND_STRAIGHT) {
         int out = outwardSide(base);
         if (out < 0) {
            return null;
         }
         // the straight beside the outer turn on side d lies in the arm that leaves in direction d
         int arm = -1;
         for (int d = 0; d < 4; d++) {
            if (x == ox + DX[d] && z == oz + DZ[d] && (d == a || d == b)) {
               arm = d;
            }
         }
         if (arm < 0) {
            return null;
         }
         if (s.streetJoins(x, z, out)) {
            return cPiece(arm, out);
         }
         // toward the inner turn, which is a C; the back faces a parcel: Tin. The two straights of a bend are mirror images of each
         // other: the one in the first arm of the pair (a) uses the Tin as drawn, the one in the second (b) its mirror image.
         // MIRROR_FIRST_ARM swaps the two if the Tin was drawn for the other one.
         boolean mirror = MIRROR_FIRST_ARM ? arm == a : arm == b;
         return mirror ? tinMirrorPiece((out + 2) % 4) : tinPiece((out + 2) % 4);
      }
      return null;
   }

   /** The direction named by the other letter of a C piece's corner, given one of its two letters; -1 if it does not name that one. */
   private static int otherLetter(RoadType c, int known) {
      String name = c.name();
      int first = dirOf(name.charAt(name.length() - 2));
      int second = dirOf(name.charAt(name.length() - 1));
      if (first == known) {
         return second;
      }
      if (second == known) {
         return first;
      }
      return -1;
   }

   private static int dirOf(char letter) {
      switch (letter) {
         case 'N':
            return 0;
         case 'E':
            return 1;
         case 'S':
            return 2;
         case 'W':
            return 3;
         default:
            return -1;
      }
   }

   /** The side a straight lane faces outward on (the side its piece names), or -1 if the piece is not a straight. */
   private static int outwardSide(RoadType t) {
      switch (t) {
         case HIGHWAY_SIDE_N:
            return 0;
         case HIGHWAY_SIDE_E:
            return 1;
         case HIGHWAY_SIDE_S:
            return 2;
         case HIGHWAY_SIDE_W:
            return 3;
         default:
            return -1;
      }
   }

   /** The bend's outer corner for a plain turn: HIGHWAY_TURN_xy becomes HIGHWAY_TURNOUT_xy; anything else is returned as it is. */
   public static RoadType turnoutPiece(RoadType turn) {
      if (turn == null || !turn.name().startsWith("HIGHWAY_TURN_")) {
         return turn;
      }
      return RoadType.valueOf("HIGHWAY_TURNOUT_" + turn.name().substring("HIGHWAY_TURN_".length()));
   }

   /** The tee whose turn-off is on side d. */
   public static RoadType tPiece(int d) {
      switch (d) {
         case 0:
            return RoadType.HIGHWAY_T_N;
         case 1:
            return RoadType.HIGHWAY_T_E;
         case 2:
            return RoadType.HIGHWAY_T_S;
         default:
            return RoadType.HIGHWAY_T_W;
      }
   }

   /** The tee whose turn-off is on side d and whose back faces a parcel (a highway joining a highway): sidewalk, not median. */
   public static RoadType tinPiece(int d) {
      switch (d) {
         case 0:
            return RoadType.HIGHWAY_TIN_N;
         case 1:
            return RoadType.HIGHWAY_TIN_E;
         case 2:
            return RoadType.HIGHWAY_TIN_S;
         default:
            return RoadType.HIGHWAY_TIN_W;
      }
   }

   /** The mirror image of tinPiece(d): the same tee with the wide and narrow corners swapped. */
   public static RoadType tinMirrorPiece(int d) {
      switch (d) {
         case 0:
            return RoadType.HIGHWAY_TINM_N;
         case 1:
            return RoadType.HIGHWAY_TINM_E;
         case 2:
            return RoadType.HIGHWAY_TINM_S;
         default:
            return RoadType.HIGHWAY_TINM_W;
      }
   }

   /** The open cell with crosswalks on the two perpendicular sides d1 and d2. */
   public static RoadType cPiece(int d1, int d2) {
      boolean north = d1 == 0 || d2 == 0;
      boolean east = d1 == 1 || d2 == 1;
      if (north) {
         return east ? RoadType.HIGHWAY_C_NE : RoadType.HIGHWAY_C_NW;
      }
      return east ? RoadType.HIGHWAY_C_SE : RoadType.HIGHWAY_C_SW;
   }
}
