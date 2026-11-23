package ch.k42.metropolis.minions;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;

public class DecayOption {
   private static final double defaultHoleScale = 0.05;
   private static final double defaultLeavesScale = 0.1;
   private static final double defaultFullDecay = 0.5;
   private static final double defaultPartialDecay = 0.3;
   private static final double defaultLeavesDecay = 0.1;
   private static final double defaultDecayIntensity = 1.0;
   private static final DecayOption defaultOptions = new DecayOption(0.05, 0.1, 0.5, 0.3, 0.1);
   private double holeScale;
   private double leavesScale;
   private double vineScale;
   private double fulldecay;
   private double partialdecay;
   private double leavesdecay;
   // Fixed baseline leaves scale, captured once at construction and never
   // touched by setLeavesScale()/masterGrowthIntensity scaling. Legacy leaf
   // decay reads this instead of getLeavesScale() so it stays independent
   // of branch/slime growth, as documented in
   // PluginConfig.isLegacyLeafDecayEnabled().
   private double baseLeavesScale;
   private Set<Material> exceptions = new HashSet<>();

   public static DecayOption getDefaultDecayOptions() {
      return defaultOptions;
   }

   public DecayOption(double holeScale, double leavesScale, double fulldecay, double partialdecay, double leavesdecay) {
      this.holeScale = holeScale;
      this.leavesScale = leavesScale;
      this.baseLeavesScale = leavesScale;
      this.vineScale = leavesScale;
      this.fulldecay = fulldecay;
      this.partialdecay = partialdecay;
      this.leavesdecay = leavesdecay;
   }

   public DecayOption(double intensity) {
      // leavesScale/vineScale/leavesdecay are set UNCONDITIONALLY below,
      // regardless of which branch intensity falls into -- growth is
      // meant to be fully independent of destruction intensity, but
      // previously only got set inside the intensity>0 branch, meaning a
      // building with decayIntensityInPercent=0 (destruction fully off)
      // silently also got zero growth, no matter how high any
      // growth/vine multiplier was set afterward.
      if (intensity == 0.0) {
         this.fulldecay = 1.0;
         this.partialdecay = 1.0;
      } else if (intensity > 0.0 && intensity <= 1.0) {
         this.fulldecay = 1.0 - 0.5 * intensity;
         if (this.fulldecay < -0.8) {
            this.fulldecay = -0.8;
         }

         this.partialdecay = this.fulldecay - 0.2;
      }

      this.holeScale = 0.05;
      this.leavesScale = 0.1;
      this.baseLeavesScale = 0.1;
      this.vineScale = 0.1;
      this.leavesdecay = 0.1;
   }

   public DecayOption(double intensity, Set<Material> exceptions) {
      this.exceptions = exceptions;
      // See the single-arg constructor above for why leavesScale/
      // vineScale/leavesdecay are set unconditionally here rather than
      // inside the intensity branches.
      if (intensity == 0.0) {
         this.fulldecay = 1.0;
         this.partialdecay = 1.0;
      } else if (intensity > 0.0 && intensity <= 1.0) {
         this.fulldecay = 1.0 - 0.5 * intensity;
         if (this.fulldecay < -0.8) {
            this.fulldecay = -0.8;
         }

         this.partialdecay = this.fulldecay - 0.2;
      }

      this.holeScale = 0.05;
      this.leavesScale = 0.1;
      this.baseLeavesScale = 0.1;
      this.vineScale = 0.1;
      this.leavesdecay = 0.1;
   }

   /**
    * destructIntensity and transformIntensity are independent (0.0-1.0):
    * destructIntensity alone drives fulldecay (full destruction -> air),
    * transformIntensity alone drives the GAP between fulldecay and
    * partialdecay (block-to-block transformation -- cobblestone, stairs,
    * moss, etc). Previously partialdecay was always fulldecay - 0.2, a
    * fixed offset with no independent control. partialdecay is clamped so
    * it can never exceed fulldecay, which would invert the two branches'
    * intended order (destruction should always require more extreme noise
    * than transformation).
    */
   public DecayOption(double destructIntensity, double transformIntensity, Set<Material> exceptions) {
      this.exceptions = exceptions;
      if (destructIntensity <= 0.0) {
         this.fulldecay = 1.0;
      } else {
         if (destructIntensity > 1.0) {
            destructIntensity = 1.0;
         }
         this.fulldecay = 1.0 - 0.5 * destructIntensity;
         if (this.fulldecay < -0.8) {
            this.fulldecay = -0.8;
         }
      }

      if (transformIntensity < 0.0) {
         transformIntensity = 0.0;
      } else if (transformIntensity > 1.0) {
         transformIntensity = 1.0;
      }
      this.partialdecay = this.fulldecay - 0.4 * transformIntensity;

      this.holeScale = 0.05;
      this.leavesScale = 0.1;
      this.baseLeavesScale = 0.1;
      this.vineScale = 0.1;
      this.leavesdecay = 0.1;
   }

   public double getHoleScale() {
      return this.holeScale;
   }

   public DecayOption setHoleScale(double holeScale) {
      this.holeScale = holeScale;
      return this;
   }

   public double getLeavesScale() {
      return this.leavesScale;
   }

   public DecayOption setLeavesScale(double leavesScale) {
      this.leavesScale = leavesScale;
      return this;
   }

   /**
    * Fixed baseline leaves scale from construction, unaffected by
    * setLeavesScale() (which SchematicConfig.getDecayOption() uses to bake
    * in masterGrowthIntensity for branch/slime growth). Legacy leaf decay
    * uses this instead, so it stays independent of masterGrowthIntensity
    * as documented in PluginConfig.isLegacyLeafDecayEnabled().
    */
   public double getBaseLeavesScale() {
      return this.baseLeavesScale;
   }

   public double getVineScale() {
      return this.vineScale;
   }

   public DecayOption setVineScale(double vineScale) {
      this.vineScale = vineScale;
      return this;
   }

   public double getFullDecay() {
      return this.fulldecay;
   }

   public DecayOption setFulldecay(double fulldecay) {
      this.fulldecay = fulldecay;
      return this;
   }

   public double getPartialDecay() {
      return this.partialdecay;
   }

   public DecayOption setPartialdecay(double partialdecay) {
      this.partialdecay = partialdecay;
      return this;
   }

   public double getLeavesdecay() {
      return this.leavesdecay;
   }

   public DecayOption setLeavesdecay(double leavesdecay) {
      this.leavesdecay = leavesdecay;
      return this;
   }

   public static double getDefaultDecayIntensity() {
      return 1.0;
   }

   public Set<Material> getExceptions() {
      return this.exceptions;
   }

   public void setExceptions(Set<Material> exceptions) {
      this.exceptions = exceptions;
   }
}