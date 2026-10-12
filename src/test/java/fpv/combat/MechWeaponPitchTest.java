package fpv.combat;

import fpv.world.WorldCoordinates;

public final class MechWeaponPitchTest {
    public static void main(String[] args){
        assert MechWeaponPitch.clamp(-90)==-26&&MechWeaponPitch.clamp(90)==85;
        assert MechWeaponPitch.clamp(Double.NaN)==0;
        assert MechWeaponPitch.halfLength("flamethrower",1.25,.25)==50/64.0;
        assert MechWeaponPitch.halfLength("reign-weapon",3.75,11/8.0)==122/64.0;
        assert MechWeaponPitch.halfLength("modded",2,.1)==1;
        int checks=6;
        for(int yaw=0;yaw<360;yaw+=15)for(int pitch=-26;pitch<=85;pitch+=5){
            var d=MechWeaponPitch.direction(yaw,pitch);
            assert Math.abs(Math.sqrt(d.x()*d.x()+d.y()*d.y()+d.z()*d.z())-1)<1e-12;
            assert Math.abs(MechWeaponPitch.elevation(d)-pitch)<1e-10;
            var origin=new WorldCoordinates.Point(10,20,5);
            var muzzle=MechWeaponPitch.muzzle(origin,yaw,pitch,.3,1.7);
            assert Math.abs(muzzle.z()-5-d.z()*1.7)<1e-12;
            double lift=MechWeaponPitch.lift(pitch,1.8,.14,.03),p=Math.toRadians(pitch);
            assert .14+.03+lift-1.8*Math.abs(Math.sin(p))-.14*Math.abs(Math.cos(p))>=-1e-12;
            double rear=1.8*.75,rearLift=MechWeaponPitch.lift(pitch,1.8,.14,.03,rear);
            double centerUp=rearLift+rear*Math.sin(p);
            assert .14+.03+centerUp-1.8*Math.abs(Math.sin(p))-.14*Math.abs(Math.cos(p))>=-1e-12;
            checks+=5;
        }
        assert MechWeaponPitch.lift(0,2,.1,.02)==0;checks++;
        System.out.println("PASS: "+checks+" mech pitch clamps, normalized XYZ direction, native lateral muzzle offsets, hull clearance and neutral mount invariants");
    }
}
