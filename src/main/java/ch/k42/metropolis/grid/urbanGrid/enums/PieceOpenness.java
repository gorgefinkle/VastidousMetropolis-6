package ch.k42.metropolis.grid.urbanGrid.enums;

/**
 * Which of its four sides a road or highway piece is open on, i.e. where it carries on into the next chunk.
 * Pure, so the rules can be tested without a server. Two neighbouring cells are connected exactly when each is open
 * toward the other; a piece open toward a cell that is not open back, or the reverse, is a mismatch (the debug map
 * marks those, see UrbanGrid.asciiMap()).
 *
 * <pre>
 *   street   I_NS n+s   I_EW e+w   X all four   T_x all but the side opposite x   C_xy x and y   D_x x only
 *   highway  SIDE_x     the two sides along the lane: east+west for SIDE_N / SIDE_S, north+south for SIDE_E / SIDE_W
 *            T_x        all but the side opposite x (x is the turn-off, the back faces the side where nothing joins)
 *            C_xy       all four (open cell; xy are the sides with a crosswalk)
 *            TURN_xy    x and y (TURNOUT_xy, the outer corner of a bend, the same)
 * </pre>
 */
public final class PieceOpenness {
   public static final int N = 1;
   public static final int E = 2;
   public static final int S = 4;
   public static final int W = 8;
   public static final int ALL = N | E | S | W;

   private PieceOpenness() {
   }

   /** The open sides as a bit mask of N/E/S/W; 0 for NONE and null. */
   public static int sides(RoadType type) {
      if (type == null) {
         return 0;
      }
      String n = type.name();
      if (n.equals("STREET_X")) {
         return ALL;
      }
      if (n.equals("STREET_I_NS")) {
         return N | S;
      }
      if (n.equals("STREET_I_EW")) {
         return E | W;
      }
      if (n.startsWith("STREET_T_")) {
         return ALL & ~opposite(bit(n.charAt(n.length() - 1)));
      }
      if (n.startsWith("STREET_C_")) {
         return bit(n.charAt(n.length() - 2)) | bit(n.charAt(n.length() - 1));
      }
      if (n.startsWith("STREET_D_")) {
         return bit(n.charAt(n.length() - 1));
      }
      if (n.startsWith("HIGHWAY_SIDE_")) {
         char side = n.charAt(n.length() - 1);
         return side == 'N' || side == 'S' ? E | W : N | S;
      }
      if (n.startsWith("HIGHWAY_T_") || n.startsWith("HIGHWAY_TIN_") || n.startsWith("HIGHWAY_TINM_")) {
         return ALL & ~opposite(bit(n.charAt(n.length() - 1)));
      }
      if (n.startsWith("HIGHWAY_C_")) {
         return ALL;
      }
      if (n.startsWith("HIGHWAY_TURN_") || n.startsWith("HIGHWAY_TURNOUT_")) {
         return bit(n.charAt(n.length() - 2)) | bit(n.charAt(n.length() - 1));
      }
      return 0;
   }

   public static boolean opens(RoadType type, int side) {
      return (sides(type) & side) != 0;
   }

   public static int opposite(int side) {
      switch (side) {
         case N:
            return S;
         case S:
            return N;
         case E:
            return W;
         case W:
            return E;
         default:
            return 0;
      }
   }

   private static int bit(char c) {
      switch (Character.toUpperCase(c)) {
         case 'N':
            return N;
         case 'E':
            return E;
         case 'S':
            return S;
         case 'W':
            return W;
         default:
            return 0;
      }
   }

   /** "ns", "ew", "nesw": the sides of a mask as letters, in n e s w order. */
   public static String letters(int mask) {
      StringBuilder sb = new StringBuilder();
      if ((mask & N) != 0) {
         sb.append('n');
      }
      if ((mask & E) != 0) {
         sb.append('e');
      }
      if ((mask & S) != 0) {
         sb.append('s');
      }
      if ((mask & W) != 0) {
         sb.append('w');
      }
      return sb.toString();
   }
}
