package ch.k42.metropolis.grid.urbanGrid.layout;

import ch.k42.metropolis.grid.urbanGrid.districts.MorphomosRegion;
import ch.k42.metropolis.grid.urbanGrid.enums.ContextType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * Which street chunks dissolve into the farm or forest around them, and which blocks form one farm or forest region,
 * decided from the world layout alone. A street between two chunks of countryside of the same kind is no street: it
 * is part of the region. This used to be decided per tile, from the districts that tile happened to hold (and with
 * a special rule for the tile's edge); here it is a function of the chunk and nothing else, so it is the same for
 * every tile, in any order, and no tile edge exists in it.
 *
 * Rule for a street chunk, over its eight neighbours: roads and highway lanes are ignored; a block that is not FARM or
 * FOREST, ground, or shore that is not water, keeps the street; water counts as water. The street dissolves if
 * all blocks around it are one countryside context, and at least two different blocks touch it (one if it also borders
 * water, unless that context is FOREST, where a street at the water stays).
 *
 * A street with different countryside on its two sides (farm | street | forest) never dissolves, by the rule or by the
 * dead-chain pass: it is the boundary between the two.
 *
 * A region is the set of countryside blocks of ONE domain joined by dissolved streets. A domain is bounded by highway
 * lanes and water, so a region is bounded too; two regions never merge across a lane, a river or a domain edge.
 */
public final class PlanRegions {
   private final WorldLayout layout;
   private final Map<Long, Boolean> dissolved = new HashMap<Long, Boolean>();
   private final Map<Integer, Map<WorldLayout.Rect, WorldLayout.Rect>> rootsByDomain = new HashMap<Integer, Map<WorldLayout.Rect, WorldLayout.Rect>>();

   private final Map<Integer, Map<WorldLayout.Rect, MorphomosRegion>> regionsByDomain = new HashMap<Integer, Map<WorldLayout.Rect, MorphomosRegion>>();

   public PlanRegions(WorldLayout layout) {
      this.layout = layout;
   }

   private static long key(int a, int b) {
      return ((long) a << 32) ^ (b & 0xffffffffL);
   }

   // Boundary families are broader than MorphomosRegion ownership. FARM is
   // its own family; FOREST/RIVER/OCEAN blocks plus ZONE (water/creek/coast)
   // and NATURAL (wilderness) are one natural family. Only FOREST/FARM still
   // create MorphomosRegions and retain their existing terrain painters.
   private static int boundaryFamily(ContextType context) {
      return MorphomosBoundaryFamily.of(context);
   }

   private int boundaryFamilyAt(int x, int z) {
      int kind = this.layout.kindAt(x, z);
      if (kind == WorldLayout.BLOCK) {
         WorldLayout.Rect block = this.layout.blockAt(x, z);
         return boundaryFamily(block == null ? null : this.layout.contextOf(block));
      }
      if (kind == WorldLayout.ZONE || kind == WorldLayout.NATURAL) return 2;
      return 0;
   }

   private boolean farmAt(int x, int z) {
      return this.layout.kindAt(x, z) == WorldLayout.BLOCK
              && this.boundaryFamilyAt(x, z) == 1;
   }

   private boolean naturalBlockAndExteriorNear(int x, int z) {
      boolean naturalBlock = false, naturalExterior = false;
      for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
         if (dx == 0 && dz == 0) continue;
         int kind = this.layout.kindAt(x + dx, z + dz);
         if (kind == WorldLayout.BLOCK && this.boundaryFamilyAt(x + dx, z + dz) == 2)
            naturalBlock = true;
         else if (kind == WorldLayout.ZONE || kind == WorldLayout.NATURAL)
            naturalExterior = true;
      }
      return naturalBlock && naturalExterior;
   }

   private static final int PASSES = 3;
   private static final int CHAIN = 256;                      // a dangling chain longer than this is taken to be anchored
   private static final int[] DX = {0, 1, 0, -1}, DZ = {-1, 0, 1, 0};
   private final Map<Long, Boolean> ruled = new HashMap<Long, Boolean>();
   private final Map<Long, Boolean> mixedCache = new HashMap<Long, Boolean>();
   // Exact structural FARM exterior road frames; calculated from the immutable
   // domain block plan, never inferred from current tile population.
   private final Map<Integer, Set<Long>> exteriorFarmFrameByDomain = new HashMap<Integer, Set<Long>>();
   @SuppressWarnings("unchecked")
   private final Map<Long, Boolean>[] keptCache = new Map[PASSES + 1];

   {
      for (int i = 0; i < this.keptCache.length; i++) {
         this.keptCache[i] = new HashMap<Long, Boolean>();
      }
   }

   /**
    * Is the street at (x, z) dissolved into a farm or forest? False for every chunk that is not a street.
    *
    * First the rule (ruleDissolves). Dissolving by the rule can strand what was left: the last chunk of a street
    * whose continuation dissolved. That leaves a dead end in the middle of the countryside, or a lone street chunk with
    * no road beside it. So the dead chains are dissolved too (see dead()), except a street with a farm on one side and a
    * forest on the other, which never dissolves (see separatesKinds()). Both parts are a function of the plan alone.
    */
   public synchronized boolean dissolvedAt(int x, int z) {
      Long k = key(x, z);
      // An urban frontage road is mandatory even if its other neighbours
      // happen to be FARM/FARM: pruning it would sever the district perimeter.
      if (this.layout.urbanBoundaryRoadAt(x,z)
              || this.layout.ruralBoundaryRoadAt(x,z)
              || this.protectedCountrysideWaterFace(x,z)
              || this.protectedCountrysideDryExteriorFace(x,z)
              || this.protectedTwoAnchorJoin(x,z)
              || this.protectedShortPlannedBoundaryTurn(x,z)
              || this.protectedUrbanCorner(x,z)
              || this.protectedWaterfrontContinuation(x,z)) return false;
      // This is a boundary-family decision, not a region-owner decision.
      // Same-family natural faces (including FOREST/water/creek) dissolve;
      // FARM only dissolves against FARM. Roads between FARM and any natural
      // family member remain intact. STREET/LANE neighbours are not owners.
      int family = 0;
      for (int i = 0; i < 4; i++) {
         int nx = x + DX[i], nz = z + DZ[i];
         int kind = this.layout.kindAt(nx, nz);
         if (kind == WorldLayout.STREET || kind == WorldLayout.LANE) continue;
         int neighbourFamily = this.boundaryFamilyAt(nx, nz);
         if (!MorphomosBoundaryFamily.compatible(family, neighbourFamily)) return false;
         family = neighbourFamily;
      }
      // A street can meet its natural blocks only diagonally at a planned
      // corner; ruleDissolves() still evaluates the full eight-cell frame.
      Boolean known = this.dissolved.get(k);
      if (known != null) {
         return known;
      }
      boolean result = this.layout.kindAt(x, z) == WorldLayout.STREET && (this.ruleDissolves(x, z) || !this.kept(x, z, PASSES));
      this.dissolved.put(k, result);
      return result;
   }

   /** A public road between a FARM rectangle and real river/creek/ocean
    * ownership is a mixed boundary. Compare opposing CARDINAL faces, not
    * diagonals: a lone forest in the northwest must not preserve a street
    * that runs strictly between two farms. This applies even when the
    * roadside frame is incomplete; otherwise dead-chain pruning can erase
    * the only mandatory shoreline boundary road before it is completed.
    */
   private boolean protectedCountrysideWaterFace(int x,int z) {
      if(this.layout.kindAt(x,z)!=WorldLayout.STREET) return false;
      return farmAt(x-1,z) && waterAt(x+1,z)
            || farmAt(x+1,z) && waterAt(x-1,z)
            || farmAt(x,z-1) && waterAt(x,z+1)
            || farmAt(x,z+1) && waterAt(x,z-1);
   }

   /** Keep required road frontage against explicitly unclaimed or dry-natural
    * countryside exterior; it is not an interior like-context seam. */
   private boolean protectedCountrysideDryExteriorFace(int x,int z) {
      if(this.layout.kindAt(x,z)!=WorldLayout.STREET)return false;
      return farmAt(x-1,z)&&dryExteriorAt(x+1,z)
          || farmAt(x+1,z)&&dryExteriorAt(x-1,z)
          || farmAt(x,z-1)&&dryExteriorAt(x,z+1)
          || farmAt(x,z+1)&&dryExteriorAt(x,z-1);
   }
   private boolean dryExteriorAt(int x,int z) {
      int kind=this.layout.kindAt(x,z);
      return kind==WorldLayout.UNCLAIMED || kind==WorldLayout.NATURAL || kind==WorldLayout.ZONE && !this.layout.isWater(x,z);
   }

   /** Retain the existing planned street junction of two separately protected
    * cardinal frontage edges. Never create a street or dissolve like seams. */
   private boolean protectedTwoAnchorJoin(int x,int z) {
      if(this.layout.kindAt(x,z)!=WorldLayout.STREET || this.ruleDissolves(x,z))return false;
      int anchors=0;
      for(int i=0;i<4;i++) {
         int px=x+DX[i],pz=z+DZ[i];
         if(this.layout.kindAt(px,pz)!=WorldLayout.STREET)continue;
         if(this.layout.urbanBoundaryRoadAt(px,pz)
             || this.layout.ruralBoundaryRoadAt(px,pz)
             || protectedCountrysideWaterFace(px,pz)
             || protectedCountrysideDryExteriorFace(px,pz)) anchors++;
      }
      return anchors>=2;
   }
   private boolean protectedUrbanCorner(int x,int z) {
      if(this.layout.kindAt(x,z)!=WorldLayout.STREET)return false;
      for(int xi=0;xi<2;xi++)for(int zi=0;zi<2;zi++) {
         int dx=xi==0?-1:1,dz=zi==0?-1:1;
         int px=x+dx,pz=z+dz;
         if(this.layout.kindAt(px,pz)!=WorldLayout.BLOCK)continue;
         WorldLayout.Rect r=this.layout.blockAt(px,pz);
         if(r==null)continue;
         ContextType c=this.layout.contextOf(r);
         if(c==null || boundaryFamily(c)!=0)continue;
         if((x==r.minX-1||x==r.maxX+1) && (z==r.minZ-1||z==r.maxZ+1)) return true;
      }
      return false;
   }

   /** Protect only an already-planned, fully supported short road elbow:
    * two cardinal sides must lead (within two street cells) to independently
    * mandatory countryside/urban frontages. This is a shape decision, not a
    * post-plan road constructor; it never claims block/zone/water cells. */
   private boolean protectedShortPlannedBoundaryTurn(int x,int z) {
      // Never resurrect an interior same-context Morphomos seam merely
      // because two mandatory frontages exist somewhere beyond it.
      if(this.layout.kindAt(x,z)!=WorldLayout.STREET || this.ruleDissolves(x,z))return false;
      if(requiredPlannedCorner(x,z))return true;
      // Protect the straight approach between a strong frontage and an
      // established right-angle corner. In particular this covers two-cell
      // staggered river/forest wrappers with the elbow at the far end.
      for(int i=0;i<4;i++) {
         int dx=DX[i],dz=DZ[i];
         for(int toCorner=1;toCorner<=2;toCorner++) {
            int cx=x+dx*toCorner,cz=z+dz*toCorner;
            if(!plannedStreetRun(x,z,cx,cz) || !requiredPlannedCorner(cx,cz))continue;
            for(int toAnchor=1;toAnchor<=2;toAnchor++) {
               int ax=x-dx*toAnchor,az=z-dz*toAnchor;
               if(plannedStreetRun(ax,az,x,z) && boundaryAnchor(ax,az))return true;
            }
         }
      }
      return false;
   }

   private boolean requiredPlannedCorner(int x,int z) {
      if(this.layout.kindAt(x,z)!=WorldLayout.STREET)return false;
      for(int i=0;i<4;i++) {
         int j=(i+1)&3;
         for(int a=1;a<=2;a++) for(int b=1;b<=2;b++) {
            int ax=x+DX[i]*a,az=z+DZ[i]*a;
            int bx=x+DX[j]*b,bz=z+DZ[j]*b;
            if(boundaryAnchor(ax,az) && boundaryAnchor(bx,bz)
                   && plannedStreetRun(x,z,ax,az)
                   && plannedStreetRun(x,z,bx,bz))return true;
         }
      }
      return false;
   }

   private boolean boundaryAnchor(int x,int z) {
      return this.layout.kindAt(x,z)==WorldLayout.STREET && (
           this.layout.urbanBoundaryRoadAt(x,z)
           || this.layout.ruralBoundaryRoadAt(x,z)
           || protectedCountrysideWaterFace(x,z)
           || protectedCountrysideDryExteriorFace(x,z));
   }

   private boolean plannedStreetRun(int x,int z,int xx,int zz) {
      if(x!=xx && z!=zz)return false;
      int dx=Integer.compare(xx,x),dz=Integer.compare(zz,z);
      for(;;) {
         if(this.layout.kindAt(x,z)!=WorldLayout.STREET)return false;
         if(x==xx&&z==zz)return true;
         x+=dx;z+=dz;
      }
   }

   /** A dry public street must not be pruned halfway between two already
    * mandatory water-facing countryside frontage cells.  The connecting
    * section may have STREET (instead of BLOCK) on its inland side where
    * staggered rectangular faces meet; pointwise mixed-face tests cannot
    * recognize it, and dead-chain trimming produced a single-cell hole.
    * Only existing planned STREET cells are eligible. No road is minted.
    */
   private boolean protectedWaterfrontContinuation(int x,int z) {
      if(this.layout.kindAt(x,z)!=WorldLayout.STREET)return false;
      // A shoreline street between natural land and water is not a road
      // frontage: both sides belong to the shared natural boundary family.
      if (naturalBlockAndExteriorNear(x,z)) return false;
      // If water lies east/west, preserve the N/S street between protected landings.
      if((waterAt(x+1,z) || waterAt(x-1,z))
              && waterfrontAnchored(x,z,0,-1,1,0)
              && waterfrontAnchored(x,z,0,1,1,0))return true;
      if((waterAt(x+1,z) || waterAt(x-1,z))
              && waterfrontAnchored(x,z,0,-1,-1,0)
              && waterfrontAnchored(x,z,0,1,-1,0))return true;
      // Mirror for N/S shore frontage.
      if((waterAt(x,z+1) || waterAt(x,z-1))
              && waterfrontAnchored(x,z,-1,0,0,1)
              && waterfrontAnchored(x,z,1,0,0,1))return true;
      return (waterAt(x,z+1) || waterAt(x,z-1))
              && waterfrontAnchored(x,z,-1,0,0,-1)
              && waterfrontAnchored(x,z,1,0,0,-1);
   }

   private boolean waterfrontAnchored(int x,int z,int advanceX,int advanceZ,int bankX,int bankZ) {
      // Bounded by the existing dead-chain lookahead: this test never
      // constructs a connection or searches across a non-street cell.
      for(int i=0;i<CHAIN;i++) {
         x+=advanceX;z+=advanceZ;
         if(this.layout.kindAt(x,z)!=WorldLayout.STREET || !waterAt(x+bankX,z+bankZ))return false;
         if(this.protectedCountrysideWaterFace(x,z))return true;
      }
      return false;
   }

   private boolean countrysideAt(int x, int z) {
      return this.layout.kindAt(x,z)==WorldLayout.BLOCK && this.boundaryFamilyAt(x,z)!=0;
   }

   private boolean waterAt(int x,int z) {
      return this.layout.kindAt(x,z)==WorldLayout.ZONE
              && this.layout.isWater(x,z);
   }

   private static boolean road(int kind) {
      return kind == WorldLayout.STREET || kind == WorldLayout.LANE;
   }

   private static boolean completeStreetFrame(WorldLayout.Domain domain, WorldLayout.Rect block) {
      for (int x=block.minX-1;x<=block.maxX+1;x++) {
         if (!road(domain.kindAt(x,block.minZ-1)) || !road(domain.kindAt(x,block.maxZ+1))) return false;
      }
      for (int z=block.minZ;z<=block.maxZ;z++) {
         if (!road(domain.kindAt(block.minX-1,z)) || !road(domain.kindAt(block.maxX+1,z))) return false;
      }
      return true;
   }

   /** Preserve a true FARM ring only when its complete established frame faces
    * planned countryside/shore, not every provisional road beside a farm. */
   private boolean structuralFarmExterior(int x, int z) {
      WorldLayout.Domain domain=this.layout.domainOf(x,z);
      if(domain==null)return false;
      Set<Long> frame=this.exteriorFarmFrameByDomain.get(domain.id);
      if(frame==null) {
         frame=new HashSet<Long>();
         for(WorldLayout.Rect block:domain.blocks) {
            if(domain.contexts.get(block)!=ContextType.FARM || !completeStreetFrame(domain,block)) continue;
            for(int bx=block.minX;bx<=block.maxX;bx++) {
               final int north=block.minZ-1,south=block.maxZ+1;
               if(domain.kindAt(bx,north-1)==WorldLayout.ZONE) {
                  frame.add(key(bx,north));
                  if(bx==block.minX)frame.add(key(block.minX-1,north));
                  if(bx==block.maxX)frame.add(key(block.maxX+1,north));
               }
               if(domain.kindAt(bx,south+1)==WorldLayout.ZONE) {
                  frame.add(key(bx,south));
                  if(bx==block.minX)frame.add(key(block.minX-1,south));
                  if(bx==block.maxX)frame.add(key(block.maxX+1,south));
               }
            }
            for(int bz=block.minZ;bz<=block.maxZ;bz++) {
               final int west=block.minX-1,east=block.maxX+1;
               if(domain.kindAt(west-1,bz)==WorldLayout.ZONE) {
                  frame.add(key(west,bz));
                  if(bz==block.minZ)frame.add(key(west,block.minZ-1));
                  if(bz==block.maxZ)frame.add(key(west,block.maxZ+1));
               }
               if(domain.kindAt(east+1,bz)==WorldLayout.ZONE) {
                  frame.add(key(east,bz));
                  if(bz==block.minZ)frame.add(key(east,block.minZ-1));
                  if(bz==block.maxZ)frame.add(key(east,block.maxZ+1));
               }
            }
         }
         this.exteriorFarmFrameByDomain.put(domain.id,frame);
      }
      return domain.kindAt(x,z)==WorldLayout.STREET && frame.contains(key(x,z));
   }

   private boolean ruleDissolves(int x, int z) {
      Long k = key(x, z);
      Boolean known = this.ruled.get(k);
      if (known == null) {
         known = this.decide(x, z);
         this.ruled.put(k, known);
      }
      return known;
   }

   /**
    * A street with one kind of countryside on one side and another on the other (farm | street | forest). It is the
    * boundary between the two, so it never dissolves, whatever the dead-chain pass would otherwise say: only a street
    * between the same kind on both sides (farm | street | farm, forest | street | forest) goes.
    */
   private boolean mixed(int x, int z) {
      Long k = key(x, z);
      Boolean known = this.mixedCache.get(k);
      if (known == null) {
         int first = 0;
         boolean different = false;
         for (int dx = -1; dx <= 1 && !different; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (dx == 0 && dz == 0 || this.layout.kindAt(x + dx, z + dz) != WorldLayout.BLOCK) continue;
               int family = this.boundaryFamilyAt(x + dx, z + dz);
               if (family == 0) continue;
               if (first == 0) first = family;
               else if (first != family) { different = true; break; }
            }
         }
         known = different;
         this.mixedCache.put(k, known);
      }
      return known;
   }

   /** Is the chunk still a built street after `pass` rounds of removing dead chains? */
   private boolean kept(int x, int z, int pass) {
      if (this.layout.kindAt(x, z) != WorldLayout.STREET) {
         return false;
      }
      if (this.mixed(x, z) || (this.separatesKinds(x, z) && this.structuralFarmExterior(x,z))) {
         return true;
      }
      if (pass == 0) {
         return !this.ruleDissolves(x, z);
      }
      Long k = key(x, z);
      Boolean c = this.keptCache[pass].get(k);
      if (c == null) {
         // a street with a farm on one side and a forest on the other is never taken out, dead end or not
         // Even a boundary against dry shore ground is not a reason to
         // retain a dangling road spur. Truly mixed FARM/FOREST boundaries
         // were already retained by mixed() above; urban and water landings
         // are protected by dead() -> protectedEnd().
         c = this.kept(x, z, pass - 1) && !this.dead(x, z, pass - 1);
         this.keptCache[pass].put(k, c);
      }
      return c;
   }

   /**
    * Does this street have anything beside it other than one kind of countryside? A farm on one side and a forest on the other, a
    * farm and a river's shore, a block of the city, water, unclaimed land: then it is a boundary between two different things and
    * stays a street. Neither the rule nor the dead-chain pass may dissolve it. Only a street with the same countryside on every side
    * (farm | road | farm, forest | road | forest) can dissolve.
    */
   private boolean separatesKinds(int x, int z) {
      int first = 0;
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            int nx = x + dx, nz = z + dz;
            int kind = this.layout.kindAt(nx, nz);
            if (kind == WorldLayout.STREET || kind == WorldLayout.LANE) continue;
            int family = this.boundaryFamilyAt(nx, nz);
            if (family == 0) return true;
            if (first == 0) first = family;
            else if (first != family) return true;
         }
      }
      return false;
   }

   /** Road neighbours of a built chunk at the given pass: built streets and highway lanes, on the four sides. */
   private int degree(int x, int z, int pass) {
      int n = 0;
      for (int i = 0; i < 4; i++) {
         int nx = x + DX[i], nz = z + DZ[i];
         if (this.layout.kindAt(nx, nz) == WorldLayout.LANE || this.kept(nx, nz, pass)) {
            n++;
         }
      }
      return n;
   }

   /** A street may stop here: it touches water, unclaimed land, or a block that is not countryside. */
   private boolean protectedEnd(int x, int z, int pass) {
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) {
               continue;
            }
            int k = this.layout.kindAt(x + dx, z + dz);
            // Existing waterfront landings and genuine external domain edges
            // may terminate a road. This is distinct from a dry ZONE pocket:
            // the dead-chain pass removes those meaningless spurs.
            if ((dx == 0 || dz == 0) && ((k == WorldLayout.ZONE && this.layout.isWater(x + dx, z + dz)) || k == WorldLayout.UNCLAIMED)) {
               int oppositeX=x-dx,oppositeZ=z-dz;
               if (this.layout.kindAt(oppositeX,oppositeZ)==WorldLayout.LANE || this.kept(oppositeX,oppositeZ,pass)) return true;
            }
            if (k == WorldLayout.BLOCK) {
               WorldLayout.Rect r = this.layout.blockAt(x + dx, z + dz);
               ContextType c = r == null ? null : this.layout.contextOf(r);
               if (c == null || boundaryFamily(c) == 0) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   /**
    * Is the built chunk (at this pass) on a chain that ends in a dead end? A chain is a run of built chunks with two road
    * neighbours each. Walking it either way it ends at a junction (three or more road neighbours), a highway lane, a
    * protected end, or a dead end; it is dead if either way ends in a dead end. Every chunk of a chain walks the same
    * chain, so the whole chain decides alike whichever chunk is asked. A chunk nothing leads to is dead whatever it touches.
    */
   private boolean dead(int x, int z, int pass) {
      int deg = this.degree(x, z, pass);
      if (deg == 0) {
         return true;
      }
      if (this.protectedEnd(x, z, pass) || deg >= 3) {
         return false;
      }
      if (deg == 1) {
         return true;
      }
      for (int start = 0; start < 4; start++) {
         int nx = x + DX[start], nz = z + DZ[start];
         if (!(this.layout.kindAt(nx, nz) == WorldLayout.LANE || this.kept(nx, nz, pass))) {
            continue;
         }
         if (this.walkEndsDead(x, z, start, pass)) {
            return true;
         }
      }
      return false;
   }

   private boolean walkEndsDead(int x, int z, int dir, int pass) {
      int px = x, pz = z, cx = x + DX[dir], cz = z + DZ[dir];
      for (int step = 0; step < CHAIN; step++) {
         if (this.layout.kindAt(cx, cz) == WorldLayout.LANE || (cx == x && cz == z) || this.protectedEnd(cx, cz, pass)) {
            return false;
         }
         int deg = this.degree(cx, cz, pass);
         if (deg >= 3) {
            return false;
         }
         if (deg <= 1) {
            return true;
         }
         int nx = 0, nz = 0;
         boolean foundNext = false;
         for (int i = 0; i < 4; i++) {
            int tx = cx + DX[i], tz = cz + DZ[i];
            if ((tx == px && tz == pz) || !(this.layout.kindAt(tx, tz) == WorldLayout.LANE || this.kept(tx, tz, pass))) {
               continue;
            }
            nx = tx;
            nz = tz;
            foundNext = true;
         }
         // -1 is a valid world coordinate, not a missing-neighbour sentinel.
         if (!foundNext) {
            return true;
         }
         px = cx;
         pz = cz;
         cx = nx;
         cz = nz;
      }
      return false;
   }

   private boolean decide(int x, int z) {
      if (this.layout.kindAt(x, z) != WorldLayout.STREET) return false;
      List<WorldLayout.Rect> seen = new ArrayList<WorldLayout.Rect>(4);
      int family = 0;
      boolean naturalExterior = false;
      for (int dx = -1; dx <= 1; dx++) {
         for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            int nx = x + dx, nz = z + dz;
            int kind = this.layout.kindAt(nx, nz);
            if (kind == WorldLayout.STREET || kind == WorldLayout.LANE) continue;
            if (kind == WorldLayout.BLOCK) {
               WorldLayout.Rect block = this.layout.blockAt(nx, nz);
               ContextType context = block == null ? null : this.layout.contextOf(block);
               int neighbourFamily = boundaryFamily(context);
               if (neighbourFamily == 0 || (family != 0 && family != neighbourFamily)) return false;
               family = neighbourFamily;
               if (!seen.contains(block)) seen.add(block);
            } else if (kind == WorldLayout.ZONE || kind == WorldLayout.NATURAL) {
               if (family == 1) return false;
               family = 2;
               naturalExterior = true;
            } else {
               return false;
            }
         }
      }
      // FARM remains separate: only two or more FARM rectangles dissolve
      // their shared road. Natural land/water/creek seams dissolve when at
      // least one natural block owns the seam; no painter ownership is merged.
      return MorphomosBoundaryFamily.shouldDissolve(family, seen.size(), naturalExterior);
   }

   /**
    * The region of the countryside block or dissolved street at (x, z): the id of the region (the corner of its
    * first block, so the same whichever chunk asks) or null if the chunk is neither.
    */
   public synchronized long[] regionAt(int x, int z) {
      WorldLayout.Rect root = this.rootAt(x, z);
      if (root != null) {
         return new long[]{root.minX, root.minZ};
      }
      return this.layout.kindAt(x, z) == WorldLayout.STREET && this.dissolvedAt(x, z) ? new long[]{x, z} : null;
   }

   /** The first block of the region of (x, z) (its block with the smallest corner), or null if the chunk is in no region. */
   private WorldLayout.Rect rootAt(int x, int z) {
      int k = this.layout.kindAt(x, z);
      if (k != WorldLayout.BLOCK && !(k == WorldLayout.STREET && this.dissolvedAt(x, z))) return null;
      WorldLayout.Domain d = this.layout.domainOf(x, z);
      return this.rootWithin(d, this.rootsOf(d), x, z, k);
   }

   /** Resolve a street against the SAME domain and union-find table that will own its region. */
   private WorldLayout.Rect rootWithin(WorldLayout.Domain d,
           Map<WorldLayout.Rect, WorldLayout.Rect> roots, int x, int z, int kind) {
      if (kind == WorldLayout.BLOCK) {
         WorldLayout.Rect r = this.layout.blockAt(x, z);
         return r == null ? null : roots.get(r);
      }
      if (kind != WorldLayout.STREET) return null;
      // Prefer forest for forest/shore seams, as in the original implementation.
      for (int pass = 0; pass < 2; pass++) {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
               if (d.kindAt(x + dx, z + dz) != WorldLayout.BLOCK) continue;
               WorldLayout.Rect r = this.layout.blockAt(x + dx, z + dz);
               if (r != null && roots.containsKey(r) &&
                   (pass == 1 || this.layout.contextOf(r) == ContextType.FOREST)) {
                  return roots.get(r);
               }
            }
         }
      }
      return null;
   }

   /**
    * The region object of the countryside block or dissolved street at (x, z), or null. The same object for every chunk
    * of the region and for every caller, in any order, so generators can compare regions by identity and no tile edge
    * cuts one. A dissolved street that no block of its own domain touches is in no region (it is not a street either:
    * the caller makes ground of it).
    */
   public synchronized MorphomosRegion regionFor(int x, int z) {
      WorldLayout.Rect root = this.rootAt(x, z);
      if (root == null) {
         return null;
      }
      return this.regionsOf(this.layout.domainOf(x, z)).get(root);
   }

   /** The region objects of a domain, built once: blocks as rectangles, dissolved streets as bridge cells. */
   private Map<WorldLayout.Rect, MorphomosRegion> regionsOf(WorldLayout.Domain d) {
      Map<WorldLayout.Rect, MorphomosRegion> done = this.regionsByDomain.get(d.id);
      if (done != null) {
         return done;
      }
      Map<WorldLayout.Rect, WorldLayout.Rect> roots = this.rootsOf(d);
      Map<WorldLayout.Rect, MorphomosRegion> out = new java.util.IdentityHashMap<WorldLayout.Rect, MorphomosRegion>();
      for (Map.Entry<WorldLayout.Rect, WorldLayout.Rect> e : roots.entrySet()) {
         WorldLayout.Rect root = e.getValue();
         MorphomosRegion region = out.get(root);
         if (region == null) {
            region = new MorphomosRegion(root.minX * 31 + root.minZ, this.layout.contextOf(root));
            out.put(root, region);
         }
         WorldLayout.Rect r = e.getKey();
         region.addRect(r.minX, r.minZ, r.maxX, r.maxZ);
      }
      for (int x = d.minX; x <= d.maxX; x++) {
         for (int z = d.minZ; z <= d.maxZ; z++) {
            // Domain bounding boxes may contain super-cells owned by another
            // nonrectangular domain. Never mix their union-find roots.
            if (!this.layout.ownedByDomain(d, x, z) ||
                d.kindAt(x, z) != WorldLayout.STREET || !this.dissolvedAt(x, z)) {
               continue;
            }
            WorldLayout.Rect root = this.rootWithin(d, roots, x, z, WorldLayout.STREET);
            if (root == null) continue; // no countryside block within this domain: natural ground
            MorphomosRegion region = out.get(root);
            if (region == null) {
               throw new IllegalStateException("Missing local Morphomos root in domain " + d.id
                   + " at (" + x + "," + z + ") root=(" + root.minX + "," + root.minZ + ")");
            }
            region.addBridgeCell(x, z);
         }
      }
      this.regionsByDomain.put(d.id, out);
      return out;
   }

   /** The region of a countryside block of the domain, by union-find over the domain's blocks joined by dissolved streets. */
   private Map<WorldLayout.Rect, WorldLayout.Rect> rootsOf(WorldLayout.Domain d) {
      Map<WorldLayout.Rect, WorldLayout.Rect> done = this.rootsByDomain.get(d.id);
      if (done != null) {
         return done;
      }
      final Map<WorldLayout.Rect, WorldLayout.Rect> parent = new java.util.IdentityHashMap<WorldLayout.Rect, WorldLayout.Rect>();
      for (WorldLayout.Rect r : d.blocks()) {
         ContextType c = this.layout.contextOf(r);
         if (c != null && c.isMorphomos()) {
            parent.put(r, r);
         }
      }
      for (int x = d.minX; x <= d.maxX; x++) {
         for (int z = d.minZ; z <= d.maxZ; z++) {
            if (d.kindAt(x, z) != WorldLayout.STREET || !this.dissolvedAt(x, z)) {
               continue;
            }
            WorldLayout.Rect a = null;
            for (int dx = -1; dx <= 1; dx++) {
               for (int dz = -1; dz <= 1; dz++) {
                  if (d.kindAt(x + dx, z + dz) != WorldLayout.BLOCK) {
                     continue;
                  }
                  WorldLayout.Rect r = this.layout.blockAt(x + dx, z + dz);
                  if (r == null || !parent.containsKey(r)) {
                     continue;
                  }
                  if (a == null) {
                     a = r;
                  } else if (this.layout.contextOf(a) == this.layout.contextOf(r)) {
                     this.union(parent, a, r);        // a dissolved dead chain can touch a forest and a farm: never one region
                  }
               }
            }
         }
      }
      // the representative of a region is its block with the smallest corner, so it does not depend on the order of the search
      Map<WorldLayout.Rect, WorldLayout.Rect> best = new java.util.IdentityHashMap<WorldLayout.Rect, WorldLayout.Rect>();
      for (WorldLayout.Rect r : parent.keySet()) {
         WorldLayout.Rect root = find(parent, r);
         WorldLayout.Rect cur = best.get(root);
         if (cur == null || r.minX < cur.minX || (r.minX == cur.minX && r.minZ < cur.minZ)) {
            best.put(root, r);
         }
      }
      Map<WorldLayout.Rect, WorldLayout.Rect> out = new java.util.IdentityHashMap<WorldLayout.Rect, WorldLayout.Rect>();
      for (WorldLayout.Rect r : parent.keySet()) {
         out.put(r, best.get(find(parent, r)));
      }
      this.rootsByDomain.put(d.id, out);
      return out;
   }

   private static WorldLayout.Rect find(Map<WorldLayout.Rect, WorldLayout.Rect> parent, WorldLayout.Rect r) {
      while (parent.get(r) != r) {
         r = parent.get(r);
      }
      return r;
   }

   private void union(Map<WorldLayout.Rect, WorldLayout.Rect> parent, WorldLayout.Rect a, WorldLayout.Rect b) {
      WorldLayout.Rect ra = find(parent, a), rb = find(parent, b);
      if (ra != rb) {
         parent.put(rb, ra);
      }
   }
}