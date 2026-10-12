package fpv.world;

import fpv.flight.FlightController;

/** Pure XYZ foundations: transforms, distance, immutable state and continuous height segments. */
public final class World3DTest {
    private static int checks;
    public static void main(String[] args){
        coordinatesAndControlledPose();
        physicalDistanceAndProjectiles();
        continuousHeightSegments();
        invalidInputs();
        System.out.println("World3D: "+checks+" assertions passed (XYZ foundation; native weapons not migrated).");
    }
    private static void coordinatesAndControlledPose(){
        var render=WorldCoordinates.toRender(4,7,12);
        equal(render.x(),4,0,"render X");equal(render.y(),12,0,"render altitude Y");equal(render.z(),-7,0,"render north is negative Z");
        var back=WorldCoordinates.fromRender(render.x(),render.y(),render.z());
        equal(back.x(),4,0,"inverse X");equal(back.y(),7,0,"inverse Y");equal(back.z(),12,0,"inverse height Z");
        var flight=new FlightController.State();flight.x=4;flight.y=12;flight.z=-7;flight.vx=3;flight.vy=2;flight.vz=-5;
        var unit=Unit3DState.fromRenderFlight(4,2,1,flight,.7,.2);
        equal(unit.x(),4,0,"controlled physical X");equal(unit.y(),7,0,"controlled physical Y");equal(unit.z(),12,0,"controlled physical Z");
        equal(unit.vx(),3,0,"controlled physical VX");equal(unit.vy(),5,0,"controlled physical VY");equal(unit.vz(),2,0,"controlled physical VZ");
        equal(unit.yaw(),90,1e-10,"identity FPV quaternion faces native north");equal(unit.halfHeight(),.2,1e-10,"level body extent");
        flight.y=40;equal(unit.z(),12,0,"immutable snapshot survives subsequent controller updates");
        flight.qx=Math.sin(Math.toRadians(30)/2);flight.qw=Math.cos(Math.toRadians(30)/2);
        unit=Unit3DState.fromRenderFlight(4,2,1,flight,.7,.2);
        equal(unit.pitch(),30,1e-10,"quaternion pitch preserved");equal(unit.roll(),0,1e-10,"pitch does not invent bank");
        flight.qx=0;flight.qz=-Math.sin(Math.toRadians(25)/2);flight.qw=Math.cos(Math.toRadians(25)/2);
        unit=Unit3DState.fromRenderFlight(4,2,1,flight,.7,.2);
        equal(unit.roll(),25,1e-10,"FPV positive right bank preserved");
        yes(unit.halfHeight()>.4,"bank expands physical vertical envelope");
    }
    private static void physicalDistanceAndProjectiles(){
        var low=unit(0,0,2);var high=unit(0,0,12);
        equal(low.distanceSquared(high),100,0,"same native XY still has real vertical distance");
        equal(World3DAdapter.distance(low,high),10,0,"target distance is XYZ");
        var projectile=new Projectile3DState(1,2,0,0,12,0,0,-5,.1,20,2);
        yes(!projectile.overlaps(low),"projectile above a unit misses at identical native XY");
        yes(projectile.overlaps(high),"projectile at body altitude can hit");
        var moved=projectile.advance(.5);
        equal(moved.z(),9.5,0,"projectile continuous Z velocity");equal(moved.remainingSeconds(),1.5,0,"projectile lifetime");
        equal(projectile.z(),12,0,"projectile previous segment start remains immutable");
        var ending=new Projectile3DState(2,2,1,2,3,10,20,30,.1,20,.01).advance(.5);
        equal(ending.x(),1.1,1e-12,"expired projectile only moves through remaining lifetime");
        equal(ending.z(),3.3,1e-12,"lifetime bounds altitude integration");equal(ending.remainingSeconds(),0,0,"expiry clamps at zero");
    }
    private static void continuousHeightSegments(){
        HeightRaycast.Surface floor=(x,y,c)->0;
        var hit=HeightRaycast.trace(floor,2,3,10,2,3,-10,0);
        yes(hit.hit(),"vertical segment hits ground");equal(hit.fraction(),.5,1e-12,"continuous ground crossing fraction");
        equal(hit.z(),0,1e-12,"actual ground contact Z");equal(hit.nz(),1,1e-12,"upward ground normal");
        yes(!HeightRaycast.trace(floor,-100,5,1,100,5,1,0).hit(),"long clear line stays clear");
        HeightRaycast.Surface thinWall=(x,y,c)->x==2?5:0;
        hit=HeightRaycast.trace(thinWall,-200,0,2,200,0,2,0);
        yes(hit.hit()&&hit.tileX()==2,"high-speed ray cannot skip a single tile wall");
        equal(hit.x(),1.5,1e-9,"wall side entry at exact tile boundary");equal(hit.z(),2,1e-9,"wall impact uses actual flight altitude");
        equal(hit.nx(),-1,1e-9,"wall side normal");
        yes(!HeightRaycast.trace(thinWall,-200,0,6,200,0,6,0).hit(),"line passes over a wall in XYZ");
        hit=HeightRaycast.trace(thinWall,5,0,2,-5,0,2,0);
        equal(hit.x(),2.5,1e-9,"negative-direction tile traversal");equal(hit.nx(),1,1e-9,"opposite cliff normal");
        HeightRaycast.Surface ramp=(x,y,c)->{
            double px=x+(c==1||c==2?.5:-.5),py=y+(c>=2?.5:-.5);return .25*px+.5*py;
        };
        hit=HeightRaycast.trace(ramp,0,0,4,8,4,0,0);
        yes(hit.hit(),"sloped triangle ray hit");equal(hit.fraction(),.5,1e-12,"linear ramp intersection exact");
        equal(hit.z(),2,1e-12,"ramp contact altitude");
        HeightRaycast.Surface folded=(x,y,c)->x==0&&y==0?(c==1?4:0):0;
        hit=HeightRaycast.trace(folded,-.4,-.4,1,.4,-.4,1,0);
        yes(hit.hit(),"noncoplanar tile triangle hit");equal(hit.x(),-.15,1e-12,"triangle shape is not bilinear approximation");
        yes(!HeightRaycast.trace(folded,-.4,.4,1,.4,.4,1,0).hit(),"other triangle remains flat");
        hit=HeightRaycast.trace(floor,0,0,1,0,0,-1,.2);
        equal(hit.fraction(),.4,1e-12,"finite projectile radius contacts earlier");
        hit=HeightRaycast.trace(thinWall,-3,0,2,3,0,2,.2);
        equal(hit.fraction(),(1.3+3)/6,1e-9,"sampled footprint expands horizontal collision");
    }
    private static void invalidInputs(){
        rejects(()->new Projectile3DState(1,2,0,0,0,Double.NaN,0,0,.1,2,1),"nonfinite projectile velocity");
        rejects(()->new Projectile3DState(1,2,0,0,0,0,0,0,.1,Double.POSITIVE_INFINITY,1),"nonfinite projectile damage");
        rejects(()->HeightRaycast.trace((x,y,c)->0,0,0,1,1,1,1,-1),"negative query radius");
    }
    private static Unit3DState unit(double x,double y,double z){return new Unit3DState(1,1,1,x,y,z,0,0,0,0,0,0,z,.5,.2,true);}
    private static void equal(double actual,double expected,double tolerance,String label){yes(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,label+" actual="+actual+" expected="+expected);}
    private static void yes(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
    private static void rejects(Runnable run,String label){try{run.run();}catch(IllegalArgumentException expected){yes(true,label);return;}yes(false,label);}
}
