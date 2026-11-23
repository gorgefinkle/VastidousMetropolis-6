package ch.k42.metropolis.grid.urbanGrid.enums;

public enum LootType {
   INDUSTRIAL("industrial"),
   RESIDENTIAL("residential"),
   OFFICE("office"),
   MILITARY("military"),
   MEDICAL("medical"),
   STORE("store"),
   GRAVE("grave"),
   HIDDEN("hidden");

   public final String name;

   LootType(String name) {
      this.name = name;
   }
}
