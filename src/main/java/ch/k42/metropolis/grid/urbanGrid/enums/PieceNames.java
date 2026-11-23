package ch.k42.metropolis.grid.urbanGrid.enums;

/**
 * The names the ASCII debug map uses for road and highway pieces. Pure, so it can be checked on its own.
 *
 * <pre>
 *   Hs x    straight highway lane, x = the side its outer edge faces (it runs along the other axis)
 *   Hb xy   plain highway bend, xy = the two sides it connects
 *   Ht x    highway tee, x = the turn-off  Hc xy   highway cell open on all four sides, xy = the two sides
 *                                                  where a road or highway joins (crosswalks)
 *   Ri ns   street straight (ns or ew)     Rx      street crossing
 *   Rt x    street tee, x = the turn-off   Rc xy   street corner, xy = the two sides it connects
 *   Rd x    street dead end, x = open side
 * </pre>
 * x and y are n, e, s or w. Patterns rotate; they are never mirrored.
 */
public final class PieceNames {
   private PieceNames() {
   }

   /** The name of a road or highway piece, or null for NONE. */
   public static String of(RoadType type) {
      if (type == null) {
         return null;
      }
      String n = type.name();
      if (n.startsWith("HIGHWAY_SIDE_")) {
         return "Hs " + suffix(n, "HIGHWAY_SIDE_");
      }
      if (n.startsWith("HIGHWAY_TURNOUT_")) {
         return "Ho " + suffix(n, "HIGHWAY_TURNOUT_");
      }
      if (n.startsWith("HIGHWAY_TURN_")) {
         return "Hb " + suffix(n, "HIGHWAY_TURN_");
      }
      if (n.startsWith("HIGHWAY_TINM_")) {
         return "Hm " + suffix(n, "HIGHWAY_TINM_");
      }
      if (n.startsWith("HIGHWAY_TIN_")) {
         return "Hn " + suffix(n, "HIGHWAY_TIN_");
      }
      if (n.startsWith("HIGHWAY_T_")) {
         return "Ht " + suffix(n, "HIGHWAY_T_");
      }
      if (n.startsWith("HIGHWAY_C_")) {
         return "Hc " + suffix(n, "HIGHWAY_C_");
      }
      if (n.equals("STREET_X")) {
         return "Rx";
      }
      if (n.startsWith("STREET_I_")) {
         return "Ri " + suffix(n, "STREET_I_");
      }
      if (n.startsWith("STREET_T_")) {
         return "Rt " + suffix(n, "STREET_T_");
      }
      if (n.startsWith("STREET_C_")) {
         return "Rc " + suffix(n, "STREET_C_");
      }
      if (n.startsWith("STREET_D_")) {
         return "Rd " + suffix(n, "STREET_D_");
      }
      return null;
   }

   private static String suffix(String name, String prefix) {
      return name.substring(prefix.length()).toLowerCase();
   }

   /** "[Hs]" style cell, padded with spaces on the right so that every cell of a map has the same width. */
   public static String cell(String name, int width) {
      StringBuilder sb = new StringBuilder("[").append(name).append(']');
      while (sb.length() < width) {
         sb.append(' ');
      }
      return sb.toString();
   }
}
