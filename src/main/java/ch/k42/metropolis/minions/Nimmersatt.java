package ch.k42.metropolis.minions;

public class Nimmersatt {
   private static char SLASH = '/';
   private static char BSLASH = '\\';
   private static char STAR = '*';
   private static char QUOTE = '"';

   public static String friss(String text) {
      StringBuffer buf = new StringBuffer(text.length() >> 1);
      Nimmersatt.State state = Nimmersatt.State.TEXT;

      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         switch (state) {
            case TEXT:
               if (c == SLASH) {
                  state = Nimmersatt.State.START;
               } else {
                  if (c == QUOTE) {
                     state = Nimmersatt.State.STRING;
                  }

                  buf.append(c);
               }
               break;
            case STRING:
               if (c == BSLASH) {
                  state = Nimmersatt.State.ESC;
               } else if (c == QUOTE) {
                  state = Nimmersatt.State.TEXT;
               }

               buf.append(c);
               break;
            case START:
               if (c != SLASH) {
                  if (c == STAR) {
                     state = Nimmersatt.State.COMMENT;
                  } else {
                     state = Nimmersatt.State.TEXT;
                     buf.append('/');
                     buf.append(c);
                  }
               }
               break;
            case COMMENT:
               if (c == STAR) {
                  state = Nimmersatt.State.END;
               }
               break;
            case END:
               if (c == SLASH) {
                  state = Nimmersatt.State.TEXT;
               } else if (c != STAR) {
                  state = Nimmersatt.State.COMMENT;
               }
               break;
            case ESC:
               state = Nimmersatt.State.STRING;
               buf.append(c);
         }
      }

      return buf.toString();
   }

   private enum State {
      TEXT,
      START,
      COMMENT,
      STRING,
      END,
      ESC;
   }
}
