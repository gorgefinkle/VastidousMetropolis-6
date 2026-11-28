package ch.k42.metropolis.grid.urbanGrid.wild;

/** Verifies that the planner's route guide creates long, smooth winding courses. */
public final class StreamMeanderGuideProbe {
   public static void main(String[] args) {
      StreamMeanderGuide east=new StreamMeanderGuide(0x52495645524D4541L,0,0,300,0);
      StreamMeanderGuide repeat=new StreamMeanderGuide(0x52495645524D4541L,0,0,300,0);
      double maxOffset=0.0;
      int signChanges=0,lastSign=0;
      double previous=east.lateralOffsetChunks(0.0);
      double totalVariation=0.0;
      for(int i=1;i<=600;i++) {
         double t=i/600.0;
         double offset=east.lateralOffsetChunks(t);
         maxOffset=Math.max(maxOffset,Math.abs(offset));
         totalVariation+=Math.abs(offset-previous);
         previous=offset;
         int sign=Math.abs(offset)<0.20?0:(offset>0.0?1:-1);
         if(sign!=0&&lastSign!=0&&sign!=lastSign)signChanges++;
         if(sign!=0)lastSign=sign;
         if(Math.abs(offset-repeat.lateralOffsetChunks(t))>1.0e-12)
            throw new AssertionError("Meander guide is not deterministic");
         double x=east.centerXAt(t),z=east.centerZAt(t);
         if(Math.abs(east.lateralErrorChunks(x,z))>1.0e-8)
            throw new AssertionError("Guide centreline does not resolve to zero lateral error");
      }
      if(Math.abs(east.lateralOffsetChunks(0.0))>1.0e-9
              ||Math.abs(east.lateralOffsetChunks(1.0))>1.0e-9)
         throw new AssertionError("Meander must join both lake and receiver endpoints exactly");
      if(maxOffset<7.0||signChanges<3||totalVariation<22.0)
         throw new AssertionError("Stream guide is too straight or has too few broad bends: max="
                 +maxOffset+" signChanges="+signChanges+" variation="+totalVariation);
      if(east.stepCost((int)Math.round(east.centerXAt(0.5)),
              (int)Math.round(east.centerZAt(0.5)))>2)
         throw new AssertionError("A* guide gives excessive cost to its own centreline");
      if(east.stepCost(150,14)<10)
         throw new AssertionError("A* guide does not meaningfully discourage straight-shot deviation");

      StreamMeanderGuide north=new StreamMeanderGuide(0x52495645524D4541L,0,0,0,300);
      for(int i=0;i<=100;i++) {
         double t=i/100.0;
         if(Math.abs(east.lateralOffsetChunks(t)-north.lateralOffsetChunks(t))>1.0e-9)
            throw new AssertionError("Rotated courses should retain the same natural lateral profile");
         if(Math.abs(north.centerXAt(t)+east.centerZAt(t))>1.0e-8
                 ||Math.abs(north.centerZAt(t)-east.centerXAt(t))>1.0e-8)
            throw new AssertionError("Meander guide did not rotate with its endpoint axis");
      }
      StreamMeanderGuide another=new StreamMeanderGuide(0x414E4F5448455253L,0,0,300,0);
      boolean differs=false;
      for(int i=0;i<=100;i++) {
         double t=i/100.0;
         if(Math.abs(east.lateralOffsetChunks(t)-another.lateralOffsetChunks(t))>1.0e-5){differs=true;break;}
      }
      if(!differs)throw new AssertionError("Different streams unexpectedly share the same meander shape");
      System.out.printf(java.util.Locale.ROOT,
              "StreamMeanderGuideProbe PASS maxLateral=%.2f chunks signChanges=%d variation=%.2f%n",
              maxOffset,signChanges,totalVariation);
   }
}
