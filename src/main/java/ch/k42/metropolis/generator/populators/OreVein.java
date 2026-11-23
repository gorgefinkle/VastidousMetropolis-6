package ch.k42.metropolis.generator.populators;

import ch.k42.metropolis.minions.Mats;
import org.bukkit.Material;

public class OreVein {
   private Material ore;
   private int amount;
   private int num;
   private double range;
   private double offset;

   public OreVein(Material ore, int amount, int num, double range, double offset) {
      this.ore = ore;
      this.amount = amount;
      this.num = num;
      this.range = range;
      this.offset = offset;
   }

   public OreVein(String ore, int amount, int num, double range, double offset) {
      // Strict: an unknown name throws here instead of storing a null ore.
      this(Mats.require(ore, "in the ore list"), amount, num, range, offset);
   }

   public int getNum() {
      return this.num;
   }

   public Material getOre() {
      return this.ore;
   }

   public int getAmount() {
      return this.amount;
   }

   public int getRange() {
      return (int)(62.0 * this.range);
   }

   public int getOffset() {
      return (int)(62.0 * this.offset);
   }

   @Override
   public String toString() {
      return "OreVein{ore=" + this.ore + ", amount=" + this.amount + ", num=" + this.num + ", range=" + this.range + ", offset=" + this.offset + '}';
   }
}
