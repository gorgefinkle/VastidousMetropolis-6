package ch.k42.metropolis.minions;

public class XYZ {
   public int x;
   public int y;
   public int z;

   public XYZ(int x, int y, int z) {
      this.x = x;
      this.y = y;
      this.z = z;
   }

   public XYZ() {
   }

   @Override
   public int hashCode() {
      int prime = 31;
      int result = 1;
      result = 31 * result + this.x;
      result = 31 * result + this.y;
      return 31 * result + this.z;
   }

   @Override
   public boolean equals(Object obj) {
      if (this == obj) {
         return true;
      } else if (obj == null) {
         return false;
      } else if (!(obj instanceof XYZ)) {
         return false;
      } else {
         XYZ other = (XYZ)obj;
         if (this.x != other.x) {
            return false;
         } else {
            return this.y != other.y ? false : this.z == other.z;
         }
      }
   }
}
