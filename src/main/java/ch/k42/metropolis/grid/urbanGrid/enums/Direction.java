package ch.k42.metropolis.grid.urbanGrid.enums;

import ch.k42.metropolis.minions.GridRandom;
import java.util.ArrayList;
import java.util.List;

public enum Direction {
   NORTH((byte)2),
   WEST((byte)4),
   EAST((byte)5),
   SOUTH((byte)3),
   NONE((byte)0);

   public final byte data;

   Direction(byte data) {
      this.data = data;
   }

   public static Direction getByData(byte data) {
      for (Direction d : values()) {
         if (d.data == data) {
            return d;
         }
      }

      return NONE;
   }

   public static Direction getRandomDirection(GridRandom random) {
      Direction[] dirs = getDirections();
      return dirs[random.getRandomInt(dirs.length)];
   }

   public static Direction getRandomDirection(GridRandom random, boolean north, boolean south, boolean east, boolean west) {
      List<Direction> dirs = getDirections(north, south, east, west);
      return !north && !east && !south && !west ? NONE : dirs.get(random.getRandomInt(dirs.size()));
   }

   public static Direction[] getDirections() {
      return new Direction[]{NORTH, EAST, SOUTH, WEST};
   }

   public static List<Direction> getDirections(boolean north, boolean south, boolean east, boolean west) {
      ArrayList<Direction> dirs = new ArrayList<>();
      if (north) {
         dirs.add(NORTH);
      }

      if (south) {
         dirs.add(SOUTH);
      }

      if (east) {
         dirs.add(EAST);
      }

      if (west) {
         dirs.add(WEST);
      }

      return dirs;
   }
}
