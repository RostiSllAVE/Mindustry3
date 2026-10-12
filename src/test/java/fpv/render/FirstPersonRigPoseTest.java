package fpv.render;

/** View-only regression: bounded real recoil, smooth recovery and exact far-field shot positions. */
public final class FirstPersonRigPoseTest {
    private static int checks;
    public static void main(String[] args){
        double last=Double.POSITIVE_INFINITY;
        for(int i=0;i<=400;i++){
            double t=i/100.0,value=FirstPersonRigPose.falloff(t,4);
            require(value>=0&&value<=1&&value<=last,"muzzle correction smoothly decreases");last=value;
        }
        require(FirstPersonRigPose.falloff(0,4)==1&&FirstPersonRigPose.falloff(4,4)==0,"unchanged distant trajectory");
        require(FirstPersonRigPose.falloff(Double.NaN,4)==0,"invalid telemetry is harmless");
        for(double r:new double[]{-.2,0,.2,.5,1,2})for(double power:new double[]{.5,1,1.8,4})for(double size:new double[]{.01,.12,1,10}){
            double kick=FirstPersonRigPose.recoil(r,power,size);require(Double.isFinite(kick)&&kick>=0&&kick<=.24,"bounded native recoil");
        }
        require(FirstPersonRigPose.recoil(1,1.8,.2)>FirstPersonRigPose.recoil(.5,1.8,.2),"strong shot kick exceeds recovery");
        var spring=new FirstPersonRigPose.Spring();for(int i=0;i<30;i++)spring.update(.2,1/60.0);require(spring.value>.18&&spring.value<.21,"inertia follows kick");
        for(int i=0;i<180;i++)spring.update(0,1/60.0);require(Math.abs(spring.value)<.00001,"inertia returns to rest");
        spring.update(.2,1/60.0);double frozen=spring.value;spring.update(0,0);require(spring.value==frozen,"duplicate render does not advance animation");
        require(FirstPersonRigPose.lateral(-8,8)==-.64&&FirstPersonRigPose.lateral(8,8)==.64,"native mirrored mount order");
        for(double aspect:new double[]{.01,.2,1,10,100})require(FirstPersonRigPose.width(aspect)>=.24&&FirstPersonRigPose.width(aspect)<=.95,"readable bounded width");
        for(double width:new double[]{.24,.4,.7,.95})require(FirstPersonRigPose.thickness(width)>=.11&&FirstPersonRigPose.thickness(width)<=.23,"bounded positive viewmodel depth without changing the top plane");
        var shot=MuzzlePresentation.create(0,.1,5,0,0,5,0,0,1,0,1,0,0,.2,5,.5,true,true);
        require(shot.pilot()&&shot.drop()==.5,"near outgoing friendly muzzle lowered below eye");
        require(shot.strength(0,.1,5)==1&&shot.strength(0,4.1,5)==0,"spawn anchor survives camera movement and trigger release");
        var enemy=MuzzlePresentation.create(0,.1,5,0,0,5,0,0,1,0,1,0,0,.2,5,.5,true,false);
        require(!enemy.pilot(),"enemy projectile is not treated as pilot weapon");
        var incoming=MuzzlePresentation.create(0,.1,5,0,0,5,0,0,1,0,1,0,0,0,5,.5,true,true);
        require(!incoming.pilot(),"incoming allied projectile does not acquire pilot offset");
        var offAxis=MuzzlePresentation.create(3,.1,5,0,0,5,0,0,1,0,1,0,3,.2,5,.5,true,true);
        require(!offAxis.pilot(),"nearby ally outside pilot envelope stays world presentation");
        require(MuzzlePresentation.nearRadius(1.2,.2,true)<.12,"pilot muzzle flash cannot fill viewport");
        require(MuzzlePresentation.nearRadius(1.2,10,true)==1.2&&MuzzlePresentation.nearRadius(1.2,.2,false)==1.2,"distant and enemy visibility retained");
        System.out.println("PASS: "+checks+" first-person presentation checks; recoil/inertia, mirrored mounts, stable muzzle falloff, enemy exclusion, distant trajectory unchanged");
    }
    private static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
