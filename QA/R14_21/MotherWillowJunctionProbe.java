package ch.k42.metropolis.grid.urbanGrid.forest;

public final class MotherWillowJunctionProbe {
    private static double dot(double ax,double ay,double az,double bx,double by,double bz) {
        return ax*bx+ay*by+az*bz;
    }
    private static double length(double x,double y,double z) {
        return Math.sqrt(x*x+y*y+z*z);
    }
    public static void main(String[] args) {
        MotherWillowGeometry.P start=new MotherWillowGeometry.P(0,0,0);
        MotherWillowGeometry.P end=new MotherWillowGeometry.P(16,18,16);
        MotherWillowGeometry.P before=new MotherWillowGeometry.P(-.2,-4,-.1);
        MotherWillowGeometry.P after=new MotherWillowGeometry.P(.3,4,.2);
        MotherWillowGeometry.P surface=MotherWillowGeometry.leaderSurfaceStart(start,end,4.12);
        MotherWillowGeometry.P p0=MotherWillowGeometry.leaderJunctionPoint(surface,end,before,after,0);
        MotherWillowGeometry.P p1=MotherWillowGeometry.leaderJunctionPoint(surface,end,before,after,.001);
        MotherWillowGeometry.P pEnd=MotherWillowGeometry.leaderJunctionPoint(surface,end,before,after,1);
        double offset=Math.hypot(surface.x-start.x,surface.z-start.z);
        if(offset<3.1)throw new AssertionError("top leader still begins inside the bole instead of at its outer wood: "+offset);
        double tx=after.x-before.x,ty=after.y-before.y,tz=after.z-before.z;
        double lt=length(tx,ty,tz),dx=p1.x-p0.x,dy=p1.y-p0.y,dz=p1.z-p0.z,ld=length(dx,dy,dz);
        double alignment=dot(tx/lt,ty/lt,tz/lt,dx/ld,dy/ld,dz/ld);
        if(Math.abs(p0.x-surface.x)>1e-9||Math.abs(p0.y-surface.y)>1e-9||Math.abs(p0.z-surface.z)>1e-9)
            throw new AssertionError("leader curve does not start at the bole's outer surface");
        if(Math.abs(pEnd.x-end.x)>1e-9||Math.abs(pEnd.y-end.y)>1e-9||Math.abs(pEnd.z-end.z)>1e-9)
            throw new AssertionError("leader curve moved its existing mid point");
        double outwardX=end.x-start.x,outwardZ=end.z-start.z;
        double outwardLength=Math.hypot(outwardX,outwardZ);
        double outwardAlignment=(dx*outwardX+dz*outwardZ)/(ld*outwardLength);
        if(alignment<.70||alignment>.92)
            throw new AssertionError("leader must retain bole continuity while visibly leaving its side: "+alignment);
        if(outwardAlignment<.30)
            throw new AssertionError("leader take-off remains hidden along the bole: "+outwardAlignment);
        double previousScale=Double.POSITIVE_INFINITY;
        for(int i=0;i<4;i++) {
            int attach=7+i*3;
            double root=MotherWillowGeometry.leaderRootRadius(i,attach);
            double trunk=15.0*Math.pow(1.0-.80*(attach/19.0),.82);
            double scale=MotherWillowGeometry.leaderScale(i,attach);
            if(root>trunk*.5800001)
                throw new AssertionError("leader "+i+" exceeds its local bole taper: "+root+" > "+trunk);
            if(scale>previousScale+1.0e-9)
                throw new AssertionError("upper leader scaffold did not taper progressively with the bole");
            if(i>0 && scale>=.999)
                throw new AssertionError("upper leader "+i+" was not reduced to fit the narrower trunk tip");
            previousScale=scale;
        }
        System.out.println("MotherWillowJunctionProbe PASS: bole continuity="+alignment
                +", outward emergence="+outwardAlignment+", surface offset="+offset+"; endpoint unchanged");
    }
}
