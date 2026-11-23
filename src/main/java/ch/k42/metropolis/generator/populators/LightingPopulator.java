package ch.k42.metropolis.generator.populators;

/**
 * Retired. This used to call FAWE's //fixlighting on every populated chunk
 * (com.boydti.fawe.FaweAPI), a pre-1.13 workaround for pastes leaving stale
 * light. The modern lighting engine updates as blocks are placed, and
 * WorldEdit's pastes apply light updates themselves, so nothing replaces it.
 *
 * Nothing references this class any more; it is an empty stub only so that
 * the file can be dropped over the old one without leaving broken FAWE
 * imports behind. Safe to delete.
 */
@Deprecated
public final class LightingPopulator {
   private LightingPopulator() {
   }
}
