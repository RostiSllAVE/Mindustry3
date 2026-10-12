package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.math.geom.Vec3;
import mindustry.Vars;
import mindustry.entities.units.WeaponMount;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Unit;
import mindustry.type.Weapon;
import mindustry.world.blocks.defense.turrets.Turret;

import java.util.IdentityHashMap;

/** Native alpha-extruded weapon art in a dedicated camera-space pass; no gameplay transforms. */
public final class FirstPersonRigRenderer implements AutoCloseable {
    private static final int MAX_WEAPONS=12;
    private static final String VERTEX="attribute vec3 a_position;attribute vec4 a_color;attribute vec2 a_texCoord0;uniform mat4 u_combined;varying vec4 v_color;varying vec2 v_uv;void main(){v_color=a_color;v_uv=a_texCoord0;gl_Position=u_combined*vec4(a_position,1.0);}";
    private static final String FRAGMENT="uniform sampler2D u_texture;uniform float u_ambient;varying vec4 v_color;varying vec2 v_uv;void main(){vec4 t=texture2D(u_texture,v_uv);if(t.a<.12)discard;gl_FragColor=vec4(t.rgb*v_color.rgb*u_ambient,1.0);}";
    private final NativeAtlasMesh.Builder mesh=new NativeAtlasMesh.Builder(true);
    private final FirstPersonWeaponMeshes volumes=new FirstPersonWeaponMeshes();
    private final AircraftWeaponRenderer aircraft=new AircraftWeaponRenderer();
    public void setAircraftAim(fpv.combat.AircraftWeapons value){aircraft.setAim(value);}
    private final FirstPersonViewmodelPass viewmodelPass=new FirstPersonViewmodelPass();
    private final Vec3 center=new Vec3(),modelRight=new Vec3(),modelUp=new Vec3(),modelBack=new Vec3();
    private final IdentityHashMap<Weapon,TextureRegion> integrated=new IdentityHashMap<>();
    private final FirstPersonRigPose.Spring[] recoil=new FirstPersonRigPose.Spring[MAX_WEAPONS];
    private final FirstPersonRigPose.Spring swayX=new FirstPersonRigPose.Spring(),swayY=new FirstPersonRigPose.Spring();
    private final Vec3 right=new Vec3(),up=new Vec3(),forward=new Vec3(),oldForward=new Vec3();
    private final float[] corners=new float[12];
    private Shader shader;
    private Object atlas;
    private Unit owner;
    private long updated=Long.MIN_VALUE;
    private int weapons,cropped;
    private double maximumRecoil;
    private double weaponHeight;
    private float ambientForPass;
    private boolean closed;
    private fpv.combat.MechWeaponAim3D mechAim;
    private fpv.combat.NavalWeaponAim3D navalAim;
    public void setNavalAim(fpv.combat.NavalWeaponAim3D value){navalAim=value;}
    private double mechPitch;
    public void setMechAim(fpv.combat.MechWeaponAim3D value){mechAim=value;}
    public double mechPitch(){return mechPitch;}
    public double submittedElevation(){return Math.toDegrees(Math.atan2(modelBack.y,Math.hypot(modelBack.x,modelBack.z)));}
    public record Stats(int weapons,int integratedArt,double maximumRecoil,int streamMeshes,int cachedModels,long meshBuilds,long meshBytes,int sideFaces,long targetBytes,int targetFramebuffer){}
    public Stats stats(){return new Stats(weapons,cropped,maximumRecoil,mesh.streamMeshCount(),volumes.entries()+aircraft.models(),volumes.builds()+aircraft.builds(),volumes.bytes()+aircraft.bytes(),volumes.sides()+aircraft.sides(),viewmodelPass.bytes(),viewmodelPass.framebuffer());}
    public FirstPersonRigRenderer(){for(int i=0;i<recoil.length;i++)recoil[i]=new FirstPersonRigPose.Spring();}
    public void clear(){owner=null;integrated.clear();swayX.reset();swayY.reset();for(var spring:recoil)spring.reset();updated=Long.MIN_VALUE;weapons=cropped=0;maximumRecoil=0;}
    public void render(Camera3D camera,Unit unit,float ambient,boolean active){
        weapons=cropped=0;maximumRecoil=0;mechPitch=0;weaponHeight=0;
        if(closed||!active||unit==null||unit.dead||!unit.isAdded()||camera==null||Core.atlas==null){if(owner!=null)clear();return;}
        if(owner!=unit||atlas!=Core.atlas){clear();owner=unit;atlas=Core.atlas;oldForward.set(camera.direction);}
        forward.set(camera.direction).nor();right.set(forward).crs(camera.up).nor();up.set(right).crs(forward).nor();
        long frame=Core.graphics.getFrameId();double dt=updated==frame?0:Math.min(.05,Math.max(0,Core.graphics.getDeltaTime()));updated=frame;
        double turnX=oldForward.dot(right),turnY=oldForward.dot(up);
        if(dt>0){swayX.update(FirstPersonRigPose.clamp(turnX*2,-.06,.06),dt);swayY.update(FirstPersonRigPose.clamp(turnY*2,-.045,.045),dt);oldForward.set(forward);}
        ambientForPass=ambient;mesh.clear();volumes.begin();viewmodelPass.begin();
        try{
        if(shader==null)shader=new Shader(VERTEX,FRAGMENT);
        shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);shader.setUniformf("u_ambient",Math.max(.48f,ambient));mesh.overflowShader=shader;
        boolean aircraftPass=aircraft.supports(unit);
        if(aircraftPass){
            aircraft.begin();aircraft.recoil=(index,kick)->kick*.8+recoil[Math.min(recoil.length-1,(int)index)].update(kick,dt)*.5;aircraft.render(unit,camera,ambient,true);weapons=aircraft.draws;cropped=aircraft.integratedArt();maximumRecoil=aircraft.maximumRecoil;
        }
        if(unit instanceof BlockUnitc blockUnit&&blockUnit.tile() instanceof Turret.TurretBuild turret){
            double kick=FirstPersonRigPose.clamp(turret.recoilOffset.len()/Vars.tilesize,.0,.24);
            submit(camera,turret.block.region,null,unit,0,0,kick,0,false,dt,null);
        }else if(unit.mounts!=null){
            double maxX=0;for(var mount:unit.mounts)if(eligible(mount))maxX=Math.max(maxX,Math.abs(mount.weapon.x));
            for(var mount:unit.mounts){
                if(weapons>=MAX_WEAPONS)break;if(!eligible(mount)||aircraftPass&&fpv.combat.AircraftWeapons.dual(mount.weapon))continue;
                Weapon weapon=mount.weapon;TextureRegion art=weapon.region;boolean embedded=!NativeAtlasMesh.valid(art);
                if(embedded){art=integrated.computeIfAbsent(weapon,w->integratedArt(unit,w));if(!NativeAtlasMesh.valid(art))continue;cropped++;}
                double kick=FirstPersonRigPose.recoil(mount.recoil,weapon.recoilPow,weapon.recoil/Vars.tilesize);
                // Keep the native left/right mounting order. Non-controllable turrets retain their traverse.
                double swivel=weapon.controllable?0:FirstPersonRigPose.clamp(weapon.rotate?mount.rotation:weapon.baseRotation,-40,40);
                double lateral=FirstPersonRigPose.lateral(weapon.x,maxX),fore=FirstPersonRigPose.clamp(weapon.y/Vars.tilesize*.05,-.12,.12);
                var aimed=mechAim==null?null:mechAim.visual(unit,mount);if(aimed!=null)mechPitch=aimed.pitch();
                var shipAim=navalAim==null||!NavalUnitRenderer.supports(unit)?null:navalAim.visual(unit,mount,NavalHullProfile.forType(unit.type));
                weaponHeight=fpv.combat.WeaponHeightStagger.offset(unit,mount);
                submit(camera,art,embedded?null:weapon.cellRegion,unit,lateral,fore,kick,swivel,weapon.flipSprite,dt,aimed!=null?aimed.direction():shipAim==null?null:shipAim.direction());
            }
        }
        mesh.drawAndClear(shader);
        }finally{viewmodelPass.end();}
    }
    private boolean eligible(WeaponMount mount){return mount!=null&&mount.weapon!=null&&!mount.weapon.shootOnDeath&&mount.weapon.bullet!=null&&!mount.weapon.bullet.killShooter;}
    private TextureRegion integratedArt(Unit unit,Weapon weapon){
        TextureRegion body=unit.type.region;if(!NativeAtlasMesh.valid(body)||body.width<4||body.height<4)return null;
        // Flare/Alpha and other built-in guns have no separate Weapon.region. Reuse only the
        // nose/mount patch of their own atlas art, not an unrelated weapon or full body overlay.
        int width=Math.max(2,(int)(body.width*.44)),height=Math.max(2,(int)(body.height*.48));
        double pixelsPerUnit=1/Math.max(.001,body.scl());
        int x=(int)Math.round(body.width*.5+weapon.x*pixelsPerUnit-width*.5);
        int y=(int)Math.round(body.height*.32-weapon.y*pixelsPerUnit-height*.5);
        x=Math.max(0,Math.min(body.width-width,x));y=Math.max(0,Math.min(body.height-height,y));
        return new TextureRegion(body,x,y,width,height);
    }
    private void submit(Camera3D camera,TextureRegion art,TextureRegion cell,Unit unit,double lateral,double fore,double nativeKick,double swivel,boolean flip,double dt,fpv.world.WorldCoordinates.Point gunDirection){
        if(!NativeAtlasMesh.valid(art)||weapons>=MAX_WEAPONS)return;
        double lag=recoil[weapons].update(nativeKick,dt),kick=nativeKick*.8+lag*.5;maximumRecoil=Math.max(maximumRecoil,kick);
        double width=FirstPersonRigPose.width(art.width/(double)Math.max(1,art.height)),length=.98;
        // Camera-space metres are transformed into world XYZ before the ordinary perspective matrix.
        // Front and rear corners have different eye distances, so the barrel foreshortens naturally.
        double centerX=lateral+swayX.value,centerY=-.46+weaponHeight+swayY.value-kick*.12,centerZ=1.34+fore-kick;
        double angle=Math.toRadians(swivel),c=Math.cos(angle),s=Math.sin(angle),pitch=.16+kick*.24;
        center.set(camera.position).add(right.x*(float)centerX+up.x*(float)centerY+forward.x*(float)centerZ,
            right.y*(float)centerX+up.y*(float)centerY+forward.y*(float)centerZ,right.z*(float)centerX+up.z*(float)centerY+forward.z*(float)centerZ);
        float mirror=flip?-1:1;
        modelRight.set(right).scl((float)(c*width*mirror)).add(forward.x*(float)(-s*width*mirror),forward.y*(float)(-s*width*mirror),forward.z*(float)(-s*width*mirror));
        modelBack.set(right).scl((float)(s*length)).add(up.x*(float)(pitch*length)+forward.x*(float)(c*length),
            up.y*(float)(pitch*length)+forward.y*(float)(c*length),up.z*(float)(pitch*length)+forward.z*(float)(c*length));
        modelUp.set(up).scl((float)FirstPersonRigPose.thickness(width));
        if(gunDirection!=null){
            // Keep the private-depth viewmodel placement/recoil, but use the real trunnion orientation.
            // At pitch limits the gun visibly disagrees with an unclamped camera rather than lying about its shot.
            modelBack.set((float)gunDirection.x(),(float)gunDirection.z(),(float)-gunDirection.y()).nor();
            modelRight.set(modelBack).crs(Vec3.Y).nor();modelUp.set(modelRight).crs(modelBack).nor().scl((float)FirstPersonRigPose.thickness(width));
            modelRight.scl((float)(width*mirror));modelBack.scl((float)length);
        }
        if(volumes.render(camera,art,cell,unit,center,modelRight,modelUp,modelBack,Math.max(.48f,ambientForPass))){weapons++;return;}
        for(int i=0;i<4;i++){
            double lx=(i==0||i==3?-.5:.5)*width*(flip?-1:1),lz=(i<2?.5:-.5)*length;
            double px=centerX+lx*c+lz*s,py=centerY+lz*pitch,pz=centerZ-lx*s+lz*c;
            int o=i*3;corners[o]=(float)(camera.position.x+right.x*px+up.x*py+forward.x*pz);
            corners[o+1]=(float)(camera.position.y+right.y*px+up.y*py+forward.y*pz);
            corners[o+2]=(float)(camera.position.z+right.z*px+up.z*py+forward.z*pz);
            if(gunDirection!=null){
                float sx=i==0||i==3?-.5f:.5f,sz=i<2?.5f:-.5f;
                corners[o]=center.x+modelRight.x*sx+modelBack.x*sz;
                corners[o+1]=center.y+modelRight.y*sx+modelBack.y*sz;
                corners[o+2]=center.z+modelRight.z*sx+modelBack.z*sz;
            }
        }
        face(art,1,1,1,0);
        if(NativeAtlasMesh.valid(cell)){var color=unit.type.cellColor(unit);face(cell,color.r,color.g,color.b,.003f);}
        weapons++;
    }
    private void face(TextureRegion region,float r,float g,float b,float offset){
        float dx=up.x*offset,dy=up.y*offset,dz=up.z*offset;
        mesh.projectionFace(region,corners[0]+dx,corners[1]+dy,corners[2]+dz,corners[3]+dx,corners[4]+dy,corners[5]+dz,
            corners[6]+dx,corners[7]+dy,corners[8]+dz,corners[9]+dx,corners[10]+dy,corners[11]+dz,r,g,b,1);
    }
    @Override public void close(){if(closed)return;closed=true;clear();mesh.dispose();volumes.close();aircraft.close();viewmodelPass.close();if(shader!=null)shader.dispose();shader=null;}
}
