package fpv.construction;

import fpv.physics.SweptBodies;
import fpv.construction.LayerCoordinates.Bounds;
import java.util.*;

public final class LayerNavigationTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static LayerNavigationAdapter.Passage geometry(Bounds... solids){return (ax,ay,az,bx,by,bz,r,h)->{
        for(Bounds b:solids){Bounds hull=new Bounds(bx-r,by-r,bz-h,bx+r,by+r,bz+h);if(hull.overlaps(b))return false;
            if(SweptBodies.box(ax,ay,az,bx-ax,by-ay,bz-az,b.minX()-r,b.minY()-r,b.minZ()-h,b.maxX()+r,b.maxY()+r,b.maxZ()+h)!=null)return false;}
        return true;
    };}
    public static void main(String[] args){
        var wall=new Bounds(2.5,-2.5,0,3.5,2.5,4);var clear=geometry(wall);
        var detour=LayerNavigationAdapter.plan(0,0,1,6,0,.3,.3,12,1024,clear);check(detour.found(),"local alternate route around physical wall");check(detour.points().stream().anyMatch(p->Math.abs(p.y())>=3),"route actually leaves blocked corridor");
        double x=0,y=0;for(var point:detour.points()){check(clear.clear(x,y,1,point.x(),point.y(),point.z(),.3,.3),"every edge has swept body clearance");x=point.x();y=point.y();}check(x==6&&y==0,"original forward goal preserved");
        var replay=LayerNavigationAdapter.plan(0,0,1,6,0,.3,.3,12,1024,clear);check(detour.equals(replay),"deterministic cached waypoint generation");
        var opening=geometry(new Bounds(2.5,-20,0,3.5,-.7,4),new Bounds(2.5,.7,0,3.5,20,4));
        var small=LayerNavigationAdapter.plan(0,0,1,6,0,.3,.3,8,1024,opening);var large=LayerNavigationAdapter.plan(0,0,1,6,0,.8,.3,8,1024,opening);
        check(small.found(),"small body fits real opening");check(!large.found(),"wide body cannot fit opening or invent clearance");
        var roof=geometry(new Bounds(2.5,-20,1.4,3.5,20,3));check(LayerNavigationAdapter.plan(0,0,.8,6,0,.3,.2,8,1024,roof).found(),"short body can pass under roof");check(!LayerNavigationAdapter.plan(0,0,.8,6,0,.3,.7,8,1024,roof).found(),"tall stance blocked by roof");
        check(LayerNavigationAdapter.plan(0,0,5,6,0,.3,.3,8,1024,clear).found(),"different altitude does not inherit XY wall obstruction");
        var budget=LayerNavigationAdapter.plan(0,0,1,6,0,.8,.3,16,12,opening);check(!budget.found()&&budget.visited()<=12,"search work bounded when route unavailable");
        check(!LayerNavigationAdapter.plan(0,0,1,40,0,.3,.3,8,1024,clear).found(),"local radius bound enforced");
        boolean invalid=false;try{LayerNavigationAdapter.plan(0,0,Double.NaN,1,1,.3,.3,8,1024,clear);}catch(IllegalArgumentException expected){invalid=true;}check(invalid,"nonfinite navigation rejected");
        System.out.println("LayerNavigationTest: "+checks+" checks passed");
    }
}
