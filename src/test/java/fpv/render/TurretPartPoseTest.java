package fpv.render;

public final class TurretPartPoseTest {
    public static void main(String[] args){
        int checks=0;double[] assembly=new double[9],part=new double[9],nativeFlat=new double[9];
        for(int yaw=0;yaw<360;yaw+=15)for(int pitch=-80;pitch<=80;pitch+=20)for(int delta=-90;delta<=90;delta+=15){
            SpriteExtrusionGeometry.basis(yaw,pitch,0,assembly);TurretPartPose.basis(assembly,delta,part);
            for(int axis=0;axis<3;axis++){require(Math.abs(part[axis+3]-assembly[axis+3])<1e-10,"part remains on same pitched assembly");checks++;}
            for(int axis=0;axis<3;axis++){double n=0;for(int i=0;i<3;i++)n+=part[axis*3+i]*part[axis*3+i];require(Math.abs(n-1)<1e-10,"orthonormal articulated geometry");checks++;}
            if(pitch==0){SpriteExtrusionGeometry.basis(yaw+delta,0,0,nativeFlat);for(int i=0;i<9;i++){require(Math.abs(part[i]-nativeFlat[i])<1e-10,"zero pitch matches native part yaw");checks++;}}
        }
        for(var profile:new TurretModelProfile[]{TurretModelProfile.CONVENTIONAL,TurretModelProfile.ARTILLERY,TurretModelProfile.ENERGY,TurretModelProfile.LIQUID})for(int size=1;size<15;size++){
            require(profile.thickness(size)>=profile.minimumThickness()&&profile.thickness(size)<=profile.maximumThickness(),"bounded unit-proportional visual thickness");checks++;
        }
        System.out.println("TurretPartPoseTest: "+checks+" assembly pitch/local articulation/native zero-pitch yaw/profile bounds checks passed");
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
