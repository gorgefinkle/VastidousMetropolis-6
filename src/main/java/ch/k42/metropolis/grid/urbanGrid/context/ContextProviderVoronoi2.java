package ch.k42.metropolis.grid.urbanGrid.context;

import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;
import ch.k42.metropolis.minions.Cartesian2D;
import ch.k42.metropolis.minions.GridRandom;
import ch.k42.metropolis.minions.voronoi.Pnt;
import ch.k42.metropolis.minions.voronoi.Triangle;
import ch.k42.metropolis.minions.voronoi.Triangulation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ContextProviderVoronoi2 implements ContextProvider {
   private static final double CONTEXT_DIAMETER = 20.0;
   private static final int DIAMETER_STDDEVIATION = 5;
   private static final int GRID_SIZE = 64;
   private static List<ContextType> CONTEXTS = new ArrayList<>(5);
   private Set<Cartesian2D> delauneyNodes = new HashSet<>();
   private Triangulation dt;
   private GridRandom random;

   public ContextProviderVoronoi2(GridRandom random) {
      this.random = random;
      this.initDT();
   }

   private void initDT() {
      Pnt[] initPnt = new Pnt[]{new Pnt(-96.0, -96.0), new Pnt(160.0, -96.0), new Pnt(32.0, 160.0)};
      Triangle init = new Triangle(initPnt);
      this.dt = new Triangulation(init);
      int numPoints = (int)Math.round(3.15);

      for (int i = 0; i < numPoints; i++) {
         for (int j = 0; j < numPoints; j++) {
            int x = (int)(i * 20.0 + 10.0);
            int y = (int)(j * 20.0 + 10.0);
            x = (int)(x + this.random.getRandomGaussian() * 5.0);
            y = (int)(y + this.random.getRandomGaussian() * 5.0);
            x = limit(x);
            y = limit(y);
            this.dt.delaunayPlace(new Pnt(x, y));
         }
      }
   }

   private static final int limit(int x) {
      if (x < 0) {
         return 0;
      } else {
         return x >= 64 ? 63 : x;
      }
   }

   @Override
   public ContextType getContext(ContextZone[] zones, int chunkX, int chunkZ, int level) {
      return this.getContext(new Cartesian2D(chunkX, chunkZ));
   }

   @Override
   public ContextType getContext(Cartesian2D place) {
      return null;
   }

   static {
      CONTEXTS.add(ContextType.RESIDENTIAL);
      CONTEXTS.add(ContextType.LOWRISE);
      CONTEXTS.add(ContextType.MIDRISE);
      CONTEXTS.add(ContextType.HIGHRISE);
      CONTEXTS.add(ContextType.INDUSTRIAL);
   }
}
