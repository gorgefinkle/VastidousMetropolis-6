//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//

package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.generator.MetropolisGenerator;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.plugin.PluginConfig;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.util.noise.SimplexOctaveGenerator;

public class ContextProviderSimplex implements ContextProvider {
   private MetropolisGenerator generator;
   private ContextZone[] contextZones;
   private SimplexOctaveGenerator gen1;
   private ContextConfig config;
   private DistrictContextAssigner districtAssigner;

   public ContextProviderSimplex(MetropolisGenerator generator, ContextConfig contextConfig) {
      this.generator = generator;
      this.config = contextConfig;
      this.gen1 = new SimplexOctaveGenerator(generator.getWorldSeed(), 2);
      // contexts.json is shared across every world this plugin instance
      // generates, so this filters into a FRESH array rather than pruning
      // contextConfig's own tree in place -- see stripDisabledTypes()'s
      // javadoc for why leaving it off has to mean "structurally absent",
      // not just "unlikely".
      ContextZone[] zones = PluginConfig.isDenseResidentialEnabled() && PluginConfig.isDistrictNeighborRulesEnabled()
              ? contextConfig.getContextZones()
              : stripDisabledTypes(contextConfig.getContextZones());
      this.contextZones = this.recurseWeights(zones);
      // Built from the ORIGINAL tree (contextConfig, not this.contextZones):
      // it only reads weights and structure, never the totalWeight values
      // recurseWeights() just wrote in place.
      this.districtAssigner = PluginConfig.isDistrictNeighborRulesEnabled()
              ? new DistrictContextAssigner(this, contextConfig)
              : null;
   }

   /**
    * Null when generator.districtNeighborRules is off. UrbanGrid checks
    * isUsable() on the result too, since a contexts.json with no ladder
    * contexts at all leaves nothing to assign.
    */
   public DistrictContextAssigner getDistrictAssigner() {
      return this.districtAssigner;
   }

   public long getWorldSeed() {
      return this.generator.getWorldSeed();
   }

   /**
    * The combined, clamped noise value getContext() partitions on, in
    * [-1, 1]. Exposed so DistrictContextAssigner can read the same field
    * the plain lookup does (same seed, same scale/scatter settings), just
    * mapped onto the neighbor ladder instead of contexts.json's order.
    */
   public double getZoneNoise(int chunkX, int chunkZ, int level) {
      double zone = this.gen1.noise((double)chunkX * this.config.getScale(level), (double)chunkZ * this.config.getScale(level), 0.3, 0.6, true);
      zone += this.gen1.noise((double)chunkX * this.config.getScatterScale(), (double)chunkZ * this.config.getScatterScale(), 0.3, 0.6, true) / this.config.getScatterAmount();
      return Math.max(-1.0, Math.min(1.0, zone));
   }

   private static boolean isDisabledType(ContextType type) {
      if (type == ContextType.DENSERESIDENTIAL && !PluginConfig.isDenseResidentialEnabled()) {
         return true;
      }
      return (type == ContextType.FOREST || type == ContextType.FARM) && !PluginConfig.isDistrictNeighborRulesEnabled();
   }

   /**
    * Returns a filtered copy of the given zone tree with every
    * DENSERESIDENTIAL leaf removed, recursively. Used only when
    * PluginConfig.isDenseResidentialEnabled() is false, so the noise-based
    * lookup in getContext() below never has a chance to land on
    * DENSERESIDENTIAL at all -- not merely deprioritized, but structurally
    * impossible to select, regardless of whatever weight contexts.json
    * happens to give it. A group whose children are entirely filtered away
    * is dropped from its own parent's list rather than kept as an empty,
    * unreachable node: recurseWeights()/getContext() were never written to
    * expect a childless group, and both misbehave on one -- a 0-length
    * getContextZones() lets no branch in getContext()'s loop ever match,
    * and the "fall back to the last zone" catch-all only ever inspects the
    * zones array actually being iterated, which is that same empty array,
    * so the noise range that childless group used to own would silently
    * resolve to ContextType.UNDEFINED forever.
    */
   private static ContextZone[] stripDisabledTypes(ContextZone[] zones) {
      List<ContextZone> kept = new ArrayList<>();
      for (ContextZone zone : zones) {
         if (zone.hasChildren()) {
            ContextZone[] filteredChildren = stripDisabledTypes(zone.getContextZones());
            if (filteredChildren.length > 0) {
               kept.add(new ContextZone(filteredChildren, zone.getWeight()));
            }
         } else if (!isDisabledType(zone.getContextType())) {
            kept.add(zone);
         }
      }
      return kept.toArray(new ContextZone[0]);
   }

   public ContextType getContext(ContextZone[] zones, int chunkX, int chunkZ, int level) {
      ContextType output = ContextType.UNDEFINED;
      // The partition math below assumes zone stays within [-1, 1] -- each
      // zone's threshold is only meaningful relative to that span. Without
      // clamping, a large scatter contribution (especially with a small
      // scatterAmount, since it's a divisor) could push zone past 1.0,
      // which the old per-iteration "zone > 1.0" check misread as "we've
      // overflowed the whole range" on the very FIRST zone checked --
      // always matching whichever zone is listed first (RESIDENTIAL),
      // regardless of what the noise actually indicated.
      double zone = this.getZoneNoise(chunkX, chunkZ, level);
      double previousWeight = (double)0.0F;

      // <= rather than strict <: the cumulative weightCheck of the LAST
      // zone in this array always computes out to exactly 1.0 once every
      // zone has been walked (previousWeight ends at 2*totalWeight/
      // totalWeight - length terms cancel to exactly 2.0, minus 1.0 per
      // zone -- it telescopes to 1.0). zone is clamped to a max of 1.0
      // above, so with a strict <, a noise value that clamps to EXACTLY
      // 1.0 could never satisfy ANY zone's check, no matter how the
      // weights were configured -- not just an unlucky edge case: with a
      // small scatterAmount (a divisor, so small values amplify the
      // scatter term) this saturates constantly, not rarely. That used to
      // fall through to a "grab zones[zones.length - 1]" fallback, which
      // is only correct if the last zone happens to be the intended
      // catch-all -- it silently picks whatever context is listed LAST in
      // contexts.json, at whatever weight, if it's positioned last for
      // any other reason (as DENSERESIDENTIAL's zero-weighted siblings
      // demonstrated: a weight-0 HIGHRISE listed last was chosen over the
      // fully-weighted group every time the noise saturated). <= closes
      // the gap directly -- the last zone's threshold is reachable, so
      // the loop always matches a real, correctly-weighted zone and the
      // fallback below is now unreachable dead code, kept only as a
      // final defensive guard.
      for(ContextZone contextZone : zones) {
         double normal = contextZone.getNormalizedWeight() * (double)2.0F;
         double weightCheck = normal - (double)1.0F;
         weightCheck += previousWeight;
         if (zone <= weightCheck) {
            if (contextZone.hasChildren()) {
               output = this.getContext(contextZone.getContextZones(), chunkX, chunkZ, level + 1);
            } else {
               output = contextZone.getContextType();
            }
            break;
         }

         previousWeight += normal;
      }

      // Unreachable in practice now that the loop above uses <= (see
      // comment there) -- kept only as a last-resort guard against a
      // totally empty/misconfigured zones array or a NaN weight (e.g. a
      // group whose total weight sums to 0) rather than ever returning
      // UNDEFINED and silently failing every schematic lookup for that
      // district.
      if (output == ContextType.UNDEFINED && zones.length > 0) {
         ContextZone last = zones[zones.length - 1];
         output = last.hasChildren()
                 ? this.getContext(last.getContextZones(), chunkX, chunkZ, level + 1)
                 : last.getContextType();
      }

      return output;
   }

   public ContextType getContext(Cartesian2D place) {
      return this.getContext(this.contextZones, place.X, place.Y, 1);
   }

   private ContextZone[] recurseWeights(ContextZone[] zones) {
      int totalWeights = 0;

      for(ContextZone contextZone : zones) {
         totalWeights = (int)((double)totalWeights + contextZone.getWeight());
      }

      for(ContextZone contextZone : zones) {
         contextZone.setTotalWeight(totalWeights);
         if (contextZone.hasChildren()) {
            contextZone.setContextZones(this.recurseWeights(contextZone.getContextZones()));
         }
      }

      return zones;
   }
}