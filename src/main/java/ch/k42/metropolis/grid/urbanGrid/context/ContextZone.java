package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import com.google.gson.annotations.Expose;
import java.util.Arrays;

public class ContextZone {
   @Expose
   private ContextZone[] contextZones;
   @Expose
   private ContextType contextType;
   @Expose
   private double weight;
   private double totalWeight;

   public ContextZone(ContextType contextType, double weight) {
      this.contextType = contextType;
      this.weight = weight;
   }

   public ContextZone(String contextType, double weight) {
      this(ContextType.getByString(contextType), weight);
   }

   public ContextZone(ContextZone[] contextZones, double weight) {
      this.contextZones = contextZones;
      this.weight = weight;
   }

   public ContextType getContextType() {
      return this.contextType;
   }

   public ContextZone[] getContextZones() {
      return this.contextZones;
   }

   public void setContextZones(ContextZone[] contextZones) {
      this.contextZones = contextZones;
   }

   public void setTotalWeight(int totalWeight) {
      this.totalWeight = totalWeight;
   }

   public double getNormalizedWeight() {
      return this.weight / this.totalWeight;
   }

   public boolean hasChildren() {
      return this.contextZones != null && this.contextZones.length > 0;
   }

   public double getWeight() {
      return this.weight;
   }

   @Override
   public String toString() {
      return "ContextZone{contextZones="
         + Arrays.toString(this.contextZones)
         + ", contextType="
         + this.contextType
         + ", weight="
         + this.weight
         + ", totalWeight="
         + this.totalWeight
         + '}';
   }
}
