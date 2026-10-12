package fpv.render;

public final class NavalWeaponPoseTest {
    public static void main(String[] args){
        int checks=0;
        for(double length:new double[]{.25,.6,1.5,3})for(double pitch:new double[]{-15,-12,0,35,55,60})for(double yaw:new double[]{0,90,180,270}){
            double rear=length*.65,depth=.12,gap=.025;
            var p=NavalWeaponPose.of(yaw,pitch,length,depth,gap,rear,0);
            double angle=Math.toRadians(pitch),lowest=p.up()-length*Math.abs(Math.sin(angle))-depth*Math.abs(Math.cos(angle));
            if(lowest< -depth-gap-1e-8)throw new AssertionError("Barrel clips the supporting deck");checks++;
            // The same rear trunnion remains fixed in XY; only the minimal solid saddle rises.
            double a=Math.toRadians(yaw),hingeX=p.x()-Math.cos(a)*rear*Math.cos(angle),hingeY=p.y()-Math.sin(a)*rear*Math.cos(angle);
            if(Math.abs(hingeX+Math.cos(a)*rear)>1e-8||Math.abs(hingeY+Math.sin(a)*rear)>1e-8)throw new AssertionError("Detached trunnion");checks++;
            var r=NavalWeaponPose.of(yaw,pitch,length,depth,gap,rear,.1);
            if(Math.abs(r.up()-p.up()+.1*Math.sin(angle))>1e-8||Math.abs(Math.hypot(r.x()-p.x(),r.y()-p.y())-.1*Math.cos(angle))>1e-8)throw new AssertionError("Recoil not along barrel");checks++;
            if(pitch==0&&(p.x()!=0||p.y()!=0||p.up()!=0||p.lift()!=0))throw new AssertionError("Neutral mount changed");checks++;
        }
        System.out.println("NavalWeaponPoseTest: "+checks+" deck clearance/fixed trunnion/pitched native recoil/neutral mount checks passed");
    }
}
