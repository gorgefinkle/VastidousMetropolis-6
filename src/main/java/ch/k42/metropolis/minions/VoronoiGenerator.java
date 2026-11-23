package ch.k42.metropolis.minions;

import java.util.Random;

public class VoronoiGenerator {
   private static final double SQRT_2 = 1.4142135623730951;
   private static final double SQRT_3 = 1.7320508075688772;
   private long seed;
   private short distanceMethod;

   public VoronoiGenerator(long seed, short distanceMethod) {
      this.seed = seed;
      this.distanceMethod = distanceMethod;
   }

   private double getDistance(double xDist, double zDist) {
      switch (this.distanceMethod) {
         case 0:
            return Math.sqrt(xDist * xDist + zDist * zDist) / 1.4142135623730951;
         case 1:
            return xDist + zDist;
         case 2:
            return Math.pow(Math.E, Math.sqrt(xDist * xDist + zDist * zDist) / 1.4142135623730951) / Math.E;
         default:
            return 1.0;
      }
   }

   private double getDistance(double xDist, double yDist, double zDist) {
      switch (this.distanceMethod) {
         case 0:
            return Math.sqrt(xDist * xDist + yDist * yDist + zDist * zDist) / 1.7320508075688772;
         case 1:
            return xDist + yDist + zDist;
         default:
            return 1.0;
      }
   }

   public short getDistanceMethod() {
      return this.distanceMethod;
   }

   public long getSeed() {
      return this.seed;
   }

   public double noise(double x, double z, double frequency) {
      x *= frequency;
      z *= frequency;
      int xInt = x > 0.0 ? (int)x : (int)x - 1;
      int zInt = z > 0.0 ? (int)z : (int)z - 1;
      double minDist = 3.2E7;
      double xCandidate1 = 0.0;
      double zCandidate1 = 0.0;
      double xCandidate2 = 0.0;
      double zCandidate2 = 0.0;

      for (int zCur = zInt - 2; zCur <= zInt + 2; zCur++) {
         for (int xCur = xInt - 2; xCur <= xInt + 2; xCur++) {
            double xPos = xCur + valueNoise2D(xCur, zCur, this.seed);
            double zPos = zCur + valueNoise2D(xCur, zCur, new Random(this.seed).nextLong());
            double xDist = xPos - x;
            double zDist = zPos - z;
            double dist = xDist * xDist + zDist * zDist;
            if (dist < minDist) {
               xCandidate2 = xCandidate1;
               zCandidate2 = zCandidate1;
               xCandidate1 = xPos;
               zCandidate1 = zPos;
               minDist = dist;
            }
         }
      }

      return this.getDistance(xCandidate2 - x, zCandidate2 - z) - this.getDistance(xCandidate1 - x, zCandidate1 - z);
   }

   public double noise(double x, double y, double z, double frequency) {
      x *= frequency;
      y *= frequency;
      z *= frequency;
      int xInt = x > 0.0 ? (int)x : (int)x - 1;
      int yInt = y > 0.0 ? (int)y : (int)y - 1;
      int zInt = z > 0.0 ? (int)z : (int)z - 1;
      double minDist = 3.2E7;
      double xCandidate = 0.0;
      double yCandidate = 0.0;
      double zCandidate = 0.0;
      Random rand = new Random(this.seed);

      for (int zCur = zInt - 2; zCur <= zInt + 2; zCur++) {
         for (int yCur = yInt - 2; yCur <= yInt + 2; yCur++) {
            for (int xCur = xInt - 2; xCur <= xInt + 2; xCur++) {
               double xPos = xCur + valueNoise3D(xCur, yCur, zCur, this.seed);
               double yPos = yCur + valueNoise3D(xCur, yCur, zCur, rand.nextLong());
               double zPos = zCur + valueNoise3D(xCur, yCur, zCur, rand.nextLong());
               double xDist = xPos - x;
               double yDist = yPos - y;
               double zDist = zPos - z;
               double dist = xDist * xDist + yDist * yDist + zDist * zDist;
               if (dist < minDist) {
                  minDist = dist;
                  xCandidate = xPos;
                  yCandidate = yPos;
                  zCandidate = zPos;
               }
            }
         }
      }

      double xDist = xCandidate - x;
      double yDist = yCandidate - y;
      double zDist = zCandidate - z;
      return this.getDistance(xDist, yDist, zDist);
   }

   public void setDistanceMethod(short distanceMethod) {
      this.distanceMethod = distanceMethod;
   }

   public void setSeed(long seed) {
      this.seed = seed;
   }

   public static double valueNoise2D(int x, int z, long seed) {
      long n = 1619 * x + 6971 * z + 1013L * seed & 2147483647L;
      n = n >> 13 ^ n;
      return 1.0 - (n * (n * n * 60493L + 19990303L) + 1376312589L & 2147483647L) / 1.0737418E9F;
   }

   public static double valueNoise3D(int x, int y, int z, long seed) {
      long n = 1619 * x + 31337 * y + 6971 * z + 1013L * seed & 2147483647L;
      n = n >> 13 ^ n;
      return 1.0 - (n * (n * n * 60493L + 19990303L) + 1376312589L & 2147483647L) / 1.0737418E9F;
   }
}
