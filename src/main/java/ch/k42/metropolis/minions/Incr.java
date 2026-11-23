package ch.k42.metropolis.minions;

public class Incr {
   private int value = 0;

   public Incr incr() {
      this.value++;
      return this;
   }

   public void decr() {
      this.value--;
   }

   public int val() {
      return this.value;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         Incr inc = (Incr)o;
         return this.value == inc.value;
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return this.value;
   }

   @Override
   public String toString() {
      return Integer.toString(this.val());
   }
}
