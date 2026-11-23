package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import java.util.ArrayList;
import java.util.List;

public class ContextProviderVoroni implements ContextProvider {
   private static final double CONTEXT_DIAMETER = 20.0;
   private static final int DIAMETER_STDDEVIATION = 5;
   private static final int GRID_SIZE = 64;
   private static List<ContextType> CONTEXTS = new ArrayList<>(5);
   private List<ContextProviderVoroni.VoronoiVertex> voronoiVertices = new ArrayList<>(9);

   private static final int limit(int x) {
      if (x < 0) {
         return 0;
      } else {
         return x >= 64 ? 63 : x;
      }
   }

   public ContextProviderVoroni(GridRandom random) {
      int numPoints = (int)Math.round(3.15);

      for (int i = 0; i < numPoints; i++) {
         for (int j = 0; j < numPoints; j++) {
            int x = (int)(i * 20.0 + 10.0);
            int y = (int)(j * 20.0 + 10.0);
            x = (int)(x + random.getRandomGaussian() * 5.0);
            y = (int)(y + random.getRandomGaussian() * 5.0);
            x = limit(x);
            y = limit(y);
            this.voronoiVertices.add(new ContextProviderVoroni.VoronoiVertex(new Cartesian2D(x, y), getRandomContext(random)));
         }
      }
   }

   private static final ContextType getRandomContext(GridRandom random) {
      return CONTEXTS.get(random.getRandomInt(CONTEXTS.size()));
   }

   @Override
   public ContextType getContext(ContextZone[] zones, int chunkX, int chunkZ, int level) {
      return this.getContext(new Cartesian2D(chunkX, chunkZ));
   }

   @Override
   public ContextType getContext(Cartesian2D vertex) {
      ContextType context = ContextType.HIGHRISE;
      int minDistance = Integer.MAX_VALUE;

      for (ContextProviderVoroni.VoronoiVertex voronoi : this.voronoiVertices) {
         int distance = voronoi.vertex.manhattanDistance(vertex);
         if (distance < minDistance) {
            minDistance = distance;
            context = voronoi.context;
         }
      }

      return context;
   }

   static {
      CONTEXTS.add(ContextType.RESIDENTIAL);
      CONTEXTS.add(ContextType.LOWRISE);
      CONTEXTS.add(ContextType.MIDRISE);
      CONTEXTS.add(ContextType.HIGHRISE);
      CONTEXTS.add(ContextType.INDUSTRIAL);
   }

   private static final class VoronoiVertex {
      private Cartesian2D vertex;
      private ContextType context;

      private VoronoiVertex(Cartesian2D vertex, ContextType context) {
         this.vertex = vertex;
         this.context = context;
      }
   }
}
