package ch.k42.metropolis.grid.urbanGrid.wild;

/** Pure deterministic profile checks for the named wilderness massif and lake basin. */
public final class WildernessLandmarksProbe {
    public static void main(String[] args) {
        long seed = 0x4D4554524F504F4CL;
        WildernessFeatures.MountainSite mountainA = new WildernessFeatures.MountainSite(
                seed, -17, 29, 3200.0, -6400.0, 720.0, 640.0, 0.37, 24);
        WildernessFeatures.MountainSite mountainB = new WildernessFeatures.MountainSite(
                seed, -17, 29, 3200.0, -6400.0, 720.0, 640.0, 0.37, 24);

        int baseY = 64, maxHeight = 320;
        int summitA = mountainA.heightAt(3200, -6400, baseY, maxHeight);
        int summitB = mountainB.heightAt(3200, -6400, baseY, maxHeight);
        int summitRise=summitA-baseY;
        if (summitRise<80 || summitRise>89 || summitA>maxHeight-mountainA.peakClearance || summitA!=summitB) {
            throw new AssertionError("named massif must attain a deterministic 80-89 block summit with clearance: "+summitA);
        }
        if (mountainA.heightAt(3200 + 720, -6400, baseY, maxHeight) != baseY) {
            throw new AssertionError("named massif footprint does not fade to the surrounding ground at its edge");
        }
        double c=Math.cos(0.37),s=Math.sin(0.37);
        int steepMid=mountainA.heightAt((int)Math.round(3200+c*360),(int)Math.round(-6400-s*320),baseY,maxHeight);
        int smoothMid=mountainA.heightAt((int)Math.round(3200-c*360),(int)Math.round(-6400+s*320),baseY,maxHeight);
        if(steepMid<=smoothMid+8)
            throw new AssertionError("massif does not have a high shoulder on one side and a longer descent on the other");
        int steepShoulder=mountainA.heightAt((int)Math.round(3200+c*540),(int)Math.round(-6400-s*480),baseY,maxHeight);
        int steepFoot=mountainA.heightAt((int)Math.round(3200+c*648),(int)Math.round(-6400-s*576),baseY,maxHeight);
        int smoothShoulder=mountainA.heightAt((int)Math.round(3200-c*540),(int)Math.round(-6400+s*480),baseY,maxHeight);
        int smoothFoot=mountainA.heightAt((int)Math.round(3200-c*648),(int)Math.round(-6400+s*576),baseY,maxHeight);
        if(steepShoulder-steepFoot<=smoothShoulder-smoothFoot)
            throw new AssertionError("massif escarpment flank is not steeper than the opposite side");
        int rimMin=Integer.MAX_VALUE,rimMax=Integer.MIN_VALUE;
        for(int i=-5;i<=5;i++) {
            double a=0.37+i*0.036;
            int y=mountainA.heightAt((int)Math.round(3200+Math.cos(a)*720*.84),
                    (int)Math.round(-6400-Math.sin(a)*640*.84),baseY,maxHeight);
            rimMin=Math.min(rimMin,y);rimMax=Math.max(rimMax,y);
        }
        if(rimMax-rimMin<3)
            throw new AssertionError("massif rim lacks irregular relief and gullies");
        if (!mountainA.snowy(summitA, baseY, maxHeight)
                || !mountainA.treeless(summitA, baseY, maxHeight)) {
            throw new AssertionError("summit should be alpine, snowy and above the treeline");
        }
        if (!mountainA.name.equals(mountainB.name)) {
            throw new AssertionError("mountain name is not stable for the same world seed and site");
        }

        // A bent outlet is one continuous course. Test both legs, the
        // corner itself, local highway-crossing axes and the legacy bank envelope.
        WildernessStreamCourse stream = new WildernessStreamCourse(seed,
                new double[]{8.0, 24.0, 24.0},
                new double[]{8.0, 8.0, 24.0}, 11.0, 8.0);
        if (!stream.isEnabled()) {
            throw new AssertionError("river-enabled test configuration unexpectedly disabled wilderness outlet carving");
        }
        if (stream.pointCount() != 3
                || Math.abs(stream.distanceFromCenterBlock(16.0, 8.0)) > 1.0e-9
                || Math.abs(stream.distanceFromCenterBlock(24.0, 16.0)) > 1.0e-9
                || Math.abs(stream.distanceFromCenterBlock(24.0, 8.0)) > 1.0e-9) {
            throw new AssertionError("polyline stream broke at its 90-degree bend");
        }
        if (!stream.isRiverOrBank(1, 0) || !stream.isRiverOrBank(1, 1)) {
            throw new AssertionError("stream course does not reserve both sides of its bend");
        }
        if (stream.getHalfWidthBlocks() * 0.75 < 3.0) {
            throw new AssertionError("stream is too narrow for the smallest explicit highway crossing bore");
        }
        if (stream.isZBoundAt(1, 0) || !stream.isZBoundAt(1, 1)
                || stream.forChunk(1, 0).isZBound() || !stream.forChunk(1, 1).isZBound()) {
            throw new AssertionError("local stream axis is not preserved at the bend");
        }
        if (Math.abs(stream.spineZBlock(16.0) - 8.0) > 1.0e-9
                || Math.abs(stream.spineXBlock(16.0) - 24.0) > 1.0e-9) {
            throw new AssertionError("local highway crossing spine query does not follow the correct stream leg");
        }
        if (!Double.isNaN(stream.distanceFromCenterBlock(1000.0, 1000.0))) {
            throw new AssertionError("stream course claims terrain beyond its bank envelope");
        }

        WildernessFeatures.LakeSite lakeA = new WildernessFeatures.LakeSite(
                seed, 11, -8, 1800.0, 2400.0, 288.0, 238.0, 0.61, 63);
        WildernessFeatures.LakeSite lakeB = new WildernessFeatures.LakeSite(
                seed, 11, -8, 1800.0, 2400.0, 288.0, 238.0, 0.61, 63);
        if(!lakeA.waterAt(1800,2400)||lakeA.depthAt(1800,2400)<20)
            throw new AssertionError("lake centre must contain deep open freshwater");
        if(!lakeA.intersectsChunk(Math.floorDiv(1800,16),Math.floorDiv(2400,16)))
            throw new AssertionError("lake footprint does not claim its centre chunk");
        if(lakeA.intersectsChunk(10000,10000))
            throw new AssertionError("lake footprint claims an unrelated chunk");
        if(!lakeA.name.equals(lakeB.name))throw new AssertionError("lake name is not stable");
        double centreA=lakeA.distanceInsideShoreBlock(1800.0,2400.0);
        double centreB=lakeB.distanceInsideShoreBlock(1800.0,2400.0);
        if(Math.abs(centreA-centreB)>1.0e-9)throw new AssertionError("lake field is not deterministic");

        // Sample from the outer basin toward the centre. Depth must increase
        // continuously over these broad intervals; no shelf/plateau bands.
        double[] depths=new double[6];
        double ux=Math.cos(0.61),uz=Math.sin(0.61);
        for(int i=0;i<depths.length;i++){
            double fraction=0.78-i*0.10;
            int x=(int)Math.round(1800.0-ux*lakeA.radiusX*fraction);
            int z=(int)Math.round(2400.0-uz*lakeA.radiusX*fraction);
            depths[i]=lakeA.depthAt(x,z);
        }
        int risingSteps=0;
        for(int i=1;i<depths.length;i++){
            if(depths[i]<depths[i-1])
                throw new AssertionError("lake floor gets shallower toward its centre: "
                        +java.util.Arrays.toString(depths));
            if(depths[i]>depths[i-1])risingSteps++;
        }
        if(risingSteps<4 || depths[depths.length-1]-depths[0]<10.0)
            throw new AssertionError("lake floor has an extended depth plateau instead of a continuous basin: "
                    +java.util.Arrays.toString(depths));

        // The irregular field should create broad bays/headlands, not a small
        // circular/radial stamp.
        double minCoast=Double.POSITIVE_INFINITY,maxCoast=Double.NEGATIVE_INFINITY;
        for(int i=0;i<24;i++){
            double a=i*Math.PI/12.0,lo=0.0,hi=400.0;
            for(int n=0;n<28;n++){
                double mid=(lo+hi)*0.5;
                double x=lakeA.centerX+Math.cos(a)*mid,z=lakeA.centerZ+Math.sin(a)*mid;
                if(lakeA.distanceInsideShoreBlock(x,z)>0.0)lo=mid;else hi=mid;
            }
            minCoast=Math.min(minCoast,hi);maxCoast=Math.max(maxCoast,hi);
        }
        if(maxCoast-minCoast<28.0)
            throw new AssertionError("lake coastline lacks broad bays and headlands");
        double beyond=lakeA.distanceInsideShoreBlock(
                1800.0+Math.cos(0.61)*288.0*1.55,
                2400.0+Math.sin(0.61)*288.0*1.55);
        if(beyond>=-WildernessFeatures.LakeSite.SHORE_WIDTH_BLOCKS)
            throw new AssertionError("lake beach ownership extends beyond its finite bank");

        System.out.println("WildernessLandmarksProbe PASS: summit rise="+summitRise+" blocks; irregular freshwater coast; shelf-free continuous depth; finite bank; deterministic field");
    }
}
