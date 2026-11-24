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
        MotherWillowGeometry.P p0=MotherWillowGeometry.leaderJunctionPoint(start,end,before,after,0);
        MotherWillowGeometry.P p1=MotherWillowGeometry.leaderJunctionPoint(start,end,before,after,.001);
        MotherWillowGeometry.P pEnd=MotherWillowGeometry.leaderJunctionPoint(start,end,before,after,1);
        double tx=after.x-before.x,ty=after.y-before.y,tz=after.z-before.z;
        double lt=length(tx,ty,tz),dx=p1.x-p0.x,dy=p1.y-p0.y,dz=p1.z-p0.z,ld=length(dx,dy,dz);
        double alignment=dot(tx/lt,ty/lt,tz/lt,dx/ld,dy/ld,dz/ld);
        if(Math.abs(p0.x-start.x)>1e-9||Math.abs(p0.y-start.y)>1e-9||Math.abs(p0.z-start.z)>1e-9)
            throw new AssertionError("leader curve does not start on the trunk node");
        if(Math.abs(pEnd.x-end.x)>1e-9||Math.abs(pEnd.y-end.y)>1e-9||Math.abs(pEnd.z-end.z)>1e-9)
            throw new AssertionError("leader curve moved its existing mid point");
        if(alignment<.995)throw new AssertionError("leader junction tangent is not trunk-aligned: "+alignment);
        System.out.println("MotherWillowJunctionProbe PASS: tangent alignment="+alignment+"; endpoint unchanged");
    }
}
