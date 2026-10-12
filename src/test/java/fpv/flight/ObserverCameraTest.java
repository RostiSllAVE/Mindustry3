package fpv.flight;

import arc.graphics.g3d.Camera3D;
import arc.math.geom.Vec3;
import fpv.ui.CameraView;

/** Presentation geometry and input policy, independent of native movement/combat. */
public final class ObserverCameraTest {
    public static void main(String[] args){
        assert ObserverCamera.distance(.4,.2,25)>=.9;
        assert ObserverCamera.distance(5,3,400)>ObserverCamera.distance(.4,.2,400)*5;
        assert ObserverCamera.distance(5,3,25)>=8;
        assert CameraView.zoomAllowed(false,false,false,false,CameraView.FRONT,true);
        assert !CameraView.zoomAllowed(true,false,false,false,CameraView.FRONT,true);
        assert !CameraView.zoomAllowed(false,true,false,false,CameraView.FRONT,true);
        assert !CameraView.zoomAllowed(false,false,true,false,CameraView.FRONT,true);
        assert !CameraView.zoomAllowed(false,false,false,true,CameraView.FRONT,true);
        assert !CameraView.zoomAllowed(false,false,false,false,CameraView.FIRST,true);
        assert !CameraView.zoomAllowed(false,false,false,false,CameraView.REAR,false);
        var boom=new ObserverCamera();var camera=new Camera3D();var eye=new Vec3(0,10,0);var focus=new Vec3(0,9,0);
        for(int i=0;i<180;i++){pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,100,1/60.0,true,(a,b,r)->1);}
        float near=camera.position.dst(eye);assert near>2;
        pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,400,1/60.0,true,(a,b,r)->1);
        assert camera.position.dst(eye)<near+1:"zoom eases instead of snapping";
        for(int i=0;i<180;i++){pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,400,1/60.0,true,(a,b,r)->1);}
        assert camera.position.dst(eye)>near*3;
        pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,400,1/60.0,true,(a,b,r)->.1);
        assert camera.position.dst(eye)<1:"collision retracts immediately";
        pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,400,1/60.0,true,(a,b,r)->1);
        assert camera.position.dst(eye)<2:"collision recovery eases";
        for(int mode:new int[]{CameraView.FRONT,CameraView.REAR,CameraView.FIRST}){for(int i=0;i<180;i++){
            pose(camera,eye);boom.apply(camera,focus,.5,.2,mode,100,1/60.0,true,(a,b,r)->1);
            assert Float.isFinite(camera.position.x)&&Float.isFinite(camera.direction.x);
            assert Math.abs(camera.direction.len()-1)<1e-4;
            assert Math.abs(camera.direction.dot(camera.up))<1e-4;
        }
            assert mode==CameraView.FRONT?camera.direction.z>0:camera.direction.z<0:"observer actually faces its focus";
        }
        assert camera.position.dst(eye)<.001;
        boom.reset();assert !boom.active();assert boom.actualDistance()==0;
        for(boolean smoothing:new boolean[]{false,true}){
            for(int i=0;i<180;i++){pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,100,1/60.0,smoothing,(a,b,r)->1);}
            var previous=camera.position.cpy();eye.add(3,0,2);focus.add(3,0,2);pose(camera,eye);
            boom.apply(camera,focus,.5,.2,CameraView.REAR,100,1/60.0,smoothing,(a,b,r)->1);
            assert camera.position.dst(previous.x+3,previous.y,previous.z+2)<.001:"unit translation cannot lag";
            assert focus.cpy().sub(camera.position).nor().dot(camera.direction)>.99999:"no independently lagging rotation";
            pose(camera,eye);camera.direction.set(1,0,0);boom.apply(camera,focus,.5,.2,CameraView.REAR,100,1/60.0,smoothing,(a,b,r)->1);
            assert camera.position.x<eye.x:"turning tracks the current pilot frame";
            assert focus.cpy().sub(camera.position).nor().dot(camera.direction)>.99999:"turn immediately looks at owner";
            if(smoothing){pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.FRONT,100,1/60.0,true,(a,b,r)->1);
                assert focus.cpy().sub(camera.position).nor().dot(camera.direction)>.99999:"orbit position and direction agree in every frame";}
            pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.FRONT,400,0,false,(a,b,r)->1);
            assert camera.position.z<eye.z&&camera.position.dst(eye)>9:"OFF snaps mode/zoom at zero dt, including mid-animation";
            assert focus.cpy().sub(camera.position).nor().dot(camera.direction)>.99999;
            pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,400,0,false,(a,b,r)->.1);
            assert camera.position.dst(eye)<1:"OFF still respects collision";
            pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.REAR,400,0,false,(a,b,r)->1);
            assert camera.position.dst(eye)>9:"OFF recovery has no animation";
            pose(camera,eye);boom.apply(camera,focus,.5,.2,CameraView.FIRST,100,0,false,(a,b,r)->1);
            assert camera.position.dst(eye)==0&&camera.direction.z==-1&&!boom.active():"OFF FIRST has no residual pose";
        }
        var groundEye=new GroundEyeHeight();assert groundEye.snap(3)==3;assert groundEye.constrain(.8)==.8;assert groundEye.snap(2)==2;
        System.out.println("ObserverCameraTest passed: current-frame translation/turning, centered animated orbit, immediate OFF modes/zoom/recovery/eye, collision, adaptive ranges and input gates");
    }
    private static void pose(Camera3D c,Vec3 eye){c.position.set(eye);c.direction.set(0,0,-1);c.up.set(0,1,0);}
}
