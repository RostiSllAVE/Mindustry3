package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.struct.IntMap;
import arc.util.Log;
import arc.util.Time;
import fpv.units.ground.GroundUnit3DModelProfile;
import fpv.units.ground.GroundUnitProfiles;
import fpv.generated.UnitsConfig;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.gen.Mechc;

import java.util.function.DoubleBinaryOperator;

/** Cached family rig plus native silhouette. It consumes body height; it never invents or changes physical altitude. */
public final class GroundFamilyRenderer implements AutoCloseable {
    private static final int MAX_TRACKED=4096;
    // 33 affine transforms x3vec4=99 vectors. Together with view and lighting this stays below128.
    private static final String VERTEX="""
        attribute vec3 a_position,a_normal,a_color;
        attribute float a_part;
        uniform mat4 u_combined;
        uniform vec4 u_rows[99];
        varying vec3 v_color,v_normal;
        void main(){
            int i=int(a_part)*3;
            vec4 r0=u_rows[i],r1=u_rows[i+1],r2=u_rows[i+2];
            vec4 p=vec4(a_position,1.0);
            vec3 world=vec3(dot(r0,p),dot(r1,p),dot(r2,p));
            vec3 x=vec3(r0.x,r1.x,r2.x),y=vec3(r0.y,r1.y,r2.y),z=vec3(r0.z,r1.z,r2.z);
            v_normal=x*a_normal.x/max(dot(x,x),.000001)+y*a_normal.y/max(dot(y,y),.000001)+z*a_normal.z/max(dot(z,z),.000001);
            v_color=a_color;
            gl_Position=u_combined*vec4(world,1.0);
        }
        """;
    private static final String FRAGMENT="""
        uniform float u_ambient;
        varying vec3 v_color,v_normal;
        void main(){
            float lighting=.55+.45*max(0.0,dot(normalize(v_normal),normalize(vec3(.35,.8,-.4))));
            gl_FragColor=vec4(v_color*lighting*u_ambient,1.0);
        }
        """;
    private final IntMap<Cached> meshes=new IntMap<>();
    private final IntMap<Tracked> states=new IntMap<>();
    private final IntMap<Unit> submitted=new IntMap<>();
    private final NativeLimbRenderer nativeLimbs=new NativeLimbRenderer();
    private fpv.combat.MechWeaponAim3D mechAim;
    private final MechWeaponSaddle saddles=new MechWeaponSaddle();
    public void setMechAim(fpv.combat.MechWeaponAim3D value){mechAim=value;}
    private java.util.function.Predicate<Unit> creatorFlight=unit->false;
    public void setCreatorFlightPredicate(java.util.function.Predicate<Unit> value){creatorFlight=value==null?unit->false:value;}
    public NativeLimbRenderer.Stats nativeLimbStats(){return nativeLimbs.stats();}
    public int nativeLimbVertices(mindustry.type.UnitType type){return nativeLimbs.geometryVertices(type);}
    public int nativeLimbBatches(mindustry.type.UnitType type){return nativeLimbs.geometryBatches(type);}
    public double velaFold(Unit unit){return nativeLimbs.velaFold(unit);}
    public double visualTravel(Unit unit){var t=unit==null?null:states.get(unit.id);return t==null||t.gait==null?0:t.gait.traveled();}
    public static boolean supportsVisual(Unit unit){return unit!=null&&!unit.type.flying&&(GroundUnitProfiles.profile(unit)!=null||unit instanceof Mechc);}
    private final float[] rows=new float[GroundRigGeometry.MAX_PARTS*12];
    private final double[] basis=new double[9];
    private Shader shader;
    private long frame,builds,draws,vertices;
    private int frameDraws,frameWheels,frameLegs,previousProgram;
    private boolean previousDepth,previousWrite,previousCull,previousBlend,batch,failed,closed;
    private Object world;
    private boolean prototypeEnabled=true;
    private long prototypeDraws,weaponDraws;
    /** Isolated A/B gallery control; ordinary rendering always enables the Dagger-only profile. */
    void prototypeEnabled(boolean enabled){prototypeEnabled=enabled;}
    public long prototypeDraws(){return prototypeDraws;}
    public long prototypeWeaponDraws(){return weaponDraws;}
    public record Stats(int cachedMeshes,int trackedUnits,long builds,long drawCalls,long vertices,int frameUnits,int wheels,int legs){}
    public Stats stats(){return new Stats(meshes.size,states.size,builds,draws,vertices,frameDraws,frameWheels,frameLegs);}
    public boolean wasSubmitted(Unit unit){return unit!=null&&submitted.get(unit.id)==unit;}
    public double footHeight(Unit unit,int index){Tracked state=unit==null?null:states.get(unit.id);return state==null||state.gait==null||index<0||index>=state.gait.count()?Double.NaN:state.gait.z(index);}
    public int visualLegCount(Unit unit){Tracked state=unit==null?null:states.get(unit.id);return state==null||state.gait==null?0:state.gait.count();}
    public boolean footPlanted(Unit unit,int index){Tracked state=unit==null?null:states.get(unit.id);return state!=null&&state.gait!=null&&index>=0&&index<state.gait.count()&&state.gait.planted(index);}
    public static double horizontalRadius(Unit unit){
        var profile=GroundUnitProfiles.profile(unit);
        return profile==null?(unit instanceof Mechc?SpriteExtrusionRenderer.bodyRadius(unit)*1.35:0):unit.hitSize/(double)Vars.tilesize*.5*profile.footprintScale()*1.4;
    }
    /** Exactly the existing body envelope, shared only to attach native limbs and exhaust at its underside. */
    public static double bodyHalfDepth(Unit unit){
        double depth=SpriteExtrusionRenderer.halfDepth(unit);var profile=GroundUnitProfiles.profile(unit);
        if(profile==null)return depth;
        double scaled=depth*profile.thicknessMultiplier();
        if(Vars.net!=null&&Vars.net.active())scaled=Math.min(scaled,Math.min(UnitsConfig.MAX_BODY_OFFSET,
            Math.max(UnitsConfig.MIN_BODY_HALF_DEPTH,unit.hitSize/(double)Vars.tilesize*UnitsConfig.BODY_HALF_DEPTH_PER_SIZE))*profile.thicknessMultiplier());
        return scaled;
    }
    public static double profileTop(Unit unit,double pitch,double roll){
        return profileTop(unit,pitch,roll,false);
    }
    public static double cameraTop(Unit unit,double pitch,double roll){return profileTop(unit,pitch,roll,true);}
    private static double profileTop(Unit unit,double pitch,double roll,boolean camera){
        var profile=ProceduralUnitModelProfile.forType(unit.type);if(profile==null)return 0;
        double size=unit.hitSize/(double)Vars.tilesize;
        double support=Math.max(SpriteExtrusionRenderer.halfDepth(unit),Math.min(UnitsConfig.MAX_BODY_OFFSET,
            Math.max(UnitsConfig.MIN_BODY_HALF_DEPTH,size*UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
        var region=unit.type.region;if(region==null)return 0;
        double width=region.width*region.scl()/Vars.tilesize,length=region.height*region.scl()/Vars.tilesize;
        return camera?profile.cameraTopOffset(support,width,length,pitch,roll):profile.topOffset(support,width,length,pitch,roll);
    }
    /** Call inside the extrusion pass, after extrusion.begin(), and end in finally. */
    public void beginFrame(){
        if(closed)return;
        if(batch)throw new IllegalStateException("Ground family batch already active");
        Object current=Vars.world==null?null:Vars.world.tiles;
        if(world!=current){clear();world=current;}
        frame++;frameDraws=frameWheels=frameLegs=0;submitted.clear();
        previousProgram=Gl.getInt(Gl.currentProgram);previousDepth=Gl.isEnabled(Gl.depthTest);
        previousWrite=Gl.getInt(Gl.depthWritemask)!=0;previousCull=Gl.isEnabled(Gl.cullFace);previousBlend=Gl.isEnabled(Gl.blend);
        batch=true;
    }
    public void endFrame(){
        if(!batch)return;
        Gl.useProgram(previousProgram);enabled(Gl.depthTest,previousDepth);Gl.depthMask(previousWrite);
        enabled(Gl.cullFace,previousCull);enabled(Gl.blend,previousBlend);batch=false;
        if(frame%128==0){var entries=states.entries();while(entries.hasNext()){var entry=entries.next();if(entry.value.seen<frame-120||!entry.value.unit.isAdded())entries.remove();}}
    }
    /** Canonical surface callback takes native tile X/Y; render Z is negative nativeY. */
    public boolean render(Unit unit,float x,float height,float renderZ,float yaw,float pitch,float roll,
            Camera3D camera,float ambient,SpriteExtrusionRenderer body,DoubleBinaryOperator surface){
        return render(unit,x,height,renderZ,yaw,pitch,roll,camera,ambient,body,surface,true);
    }
    /** First-person uses the same articulated feet and stance, with only the enclosing torso omitted. */
    public boolean renderLimbs(Unit unit,float x,float height,float renderZ,float yaw,float pitch,float roll,
            Camera3D camera,float ambient,SpriteExtrusionRenderer body,DoubleBinaryOperator surface){
        return render(unit,x,height,renderZ,yaw,pitch,roll,camera,ambient,body,surface,false);
    }
    private boolean render(Unit unit,float x,float height,float renderZ,float yaw,float pitch,float roll,
            Camera3D camera,float ambient,SpriteExtrusionRenderer body,DoubleBinaryOperator surface,boolean renderBody){
        GroundUnit3DModelProfile profile=GroundUnitProfiles.profile(unit);
        if((profile==null||profile.legged())&&unit instanceof Mechc&&!unit.type.flying)return renderMech(unit,x,height,renderZ,yaw,pitch,roll,camera,ambient,body,surface,profile,renderBody);
        if(profile==null||failed||closed||camera==null||body==null||surface==null)return false;
        boolean own=!batch;if(own)beginFrame();
        try{
            double spriteDepth=SpriteExtrusionRenderer.halfDepth(unit),depthScale=profile.thicknessMultiplier();
            if(spriteDepth>0&&Vars.net!=null&&Vars.net.active()){
                // Matches UnitAltitudeSystem.bodyHalfDepth's headless-safe metadata envelope.
                double canonical=Math.min(UnitsConfig.MAX_BODY_OFFSET,Math.max(UnitsConfig.MIN_BODY_HALF_DEPTH,
                    unit.hitSize/(double)Vars.tilesize*UnitsConfig.BODY_HALF_DEPTH_PER_SIZE))*profile.thicknessMultiplier();
                depthScale=GroundRigGeometry.depthScale(spriteDepth,depthScale,canonical);
            }
            // Only Corvus adds a hull here; all native Legsc gait, hip and foot transforms retain halfDepth.
            var hull=prototypeEnabled&&unit.type==mindustry.content.UnitTypes.corvus?ProceduralUnitModelProfile.forType(unit.type):null;
            boolean bodyDrawn=!renderBody;
            if(renderBody&&hull!=null){
                bodyDrawn=body.renderProfile(unit,x,height,renderZ,yaw,pitch,roll,camera,ambient,hull,(float)(spriteDepth*depthScale));
                if(bodyDrawn){prototypeDraws++;renderWeapons(unit,x,height,renderZ,yaw,pitch,roll,camera,ambient,body,hull);}
            }
            if(renderBody&&!bodyDrawn)bodyDrawn=body.render(unit,x,height,renderZ,yaw,pitch,roll,camera,ambient,(float)depthScale);
            if(!bodyDrawn)return false;
            double radius=Math.max(.12,unit.hitSize/(double)Vars.tilesize*.5),halfDepth=spriteDepth*depthScale;
            if(profile.legged()){
                Tracked tracked=states.get(unit.id);
                if(tracked==null||tracked.unit!=unit||tracked.type!=unit.type||tracked.legCount!=profile.legCount()){
                    if(states.size>=MAX_TRACKED)prune();if(states.size>=MAX_TRACKED)return true;
                    tracked=new Tracked(unit,profile.legCount());states.put(unit.id,tracked);
                }
                if(tracked.seen<frame-30){tracked.gait.reset();tracked.updated=Long.MIN_VALUE;}
                tracked.seen=frame;boolean folded=creatorFlight.test(unit);long nativeFrame=Core.graphics.getFrameId();
                if(folded){tracked.gait.reset();tracked.updated=Long.MIN_VALUE;}
                else if(tracked.updated!=nativeFrame){
                    double seconds=Vars.state.isPaused()?0:Math.max(0,Time.delta/60.0);
                    tracked.gait.update(x,-renderZ,Math.toRadians(yaw),radius*profile.footprintScale(),radius,seconds,surface,nativeLimbs.layout(unit.type));tracked.updated=nativeFrame;
                }
                if(nativeLimbs.renderLegs(unit,profile.legCount(),tracked.gait,x,height,renderZ,yaw,pitch,roll,halfDepth,camera,ambient,folded,renderBody)){
                    frameDraws++;frameLegs+=profile.legCount();submitted.put(unit.id,unit);
                }
                return true;
            }
            double dx=x-camera.position.x,dy=height-camera.position.y,dz=renderZ-camera.position.z;
            boolean coarse=(dx*dx+dy*dy+dz*dz)>Math.pow(Math.max(22,radius*45),2);
            int key=coarse?1:0;
            Cached cached=meshes.get(key);
            if(cached==null){
                var geometry=GroundRigGeometry.generate(true,0,coarse);
                Mesh mesh=new Mesh(true,geometry.vertexCount(),0,VertexAttribute.position3,VertexAttribute.normal,
                    new VertexAttribute(3,"a_color"),new VertexAttribute(1,"a_part"));
                try{mesh.setVertices(geometry.vertices());}catch(RuntimeException error){mesh.dispose();throw error;}
                cached=new Cached(mesh,geometry.parts(),geometry.vertexCount());meshes.put(key,cached);builds++;
            }
            Tracked tracked=states.get(unit.id);
            if(tracked==null||tracked.unit!=unit||tracked.type!=unit.type||tracked.legCount!=profile.legCount()){
                if(states.size>=MAX_TRACKED)prune();
                if(states.size>=MAX_TRACKED)return true;
                tracked=new Tracked(unit,profile.legCount());states.put(unit.id,tracked);
            }
            if(tracked.seen<frame-30){
                if(tracked.gait!=null)tracked.gait.reset();tracked.updated=Long.MIN_VALUE;tracked.hasWheelPosition=false;
            }
            tracked.seen=frame;
            SpriteExtrusionGeometry.basis(yaw,pitch,roll,basis);
            double nodeY=height-halfDepth*.65;
            oriented(0,x,nodeY,renderZ,radius*.34,radius*.26,radius*.38,basis);
            wheels(tracked,x,height,renderZ,yaw,radius,surface,creatorFlight.test(unit));frameWheels+=4;
            if(shader==null)shader=new Shader(VERTEX,FRAGMENT);
            Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.disable(Gl.cullFace);Gl.disable(Gl.blend);
            shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);
            shader.setUniform4fv("u_rows[0]",rows,0,cached.parts*12);
            shader.setUniformf("u_ambient",Math.max(.05f,Math.min(1,ambient)));
            cached.mesh.render(shader,Gl.triangles);
            draws++;vertices+=cached.vertices;frameDraws++;submitted.put(unit.id,unit);return true;
        }catch(RuntimeException failure){
            failed=true;Log.err("[FPV] Ground family rig disabled; retaining native sprite extrusion.",failure);return false;
        }finally{if(own)endFrame();}
    }
    private boolean renderMech(Unit unit,float x,float y,float z,float yaw,float pitch,float roll,Camera3D camera,float ambient,SpriteExtrusionRenderer body,
            DoubleBinaryOperator surface,GroundUnit3DModelProfile profile,boolean renderBody){
        if(closed||failed||camera==null||body==null||surface==null)return false;boolean own=!batch;if(own)beginFrame();
        try{
            double halfDepth=bodyHalfDepth(unit),spriteHalf=SpriteExtrusionRenderer.halfDepth(unit);
            var prototype=prototypeEnabled?ProceduralUnitModelProfile.forType(unit.type):null;
            if(prototype!=null){
                double physical=Math.max(spriteHalf,Math.min(UnitsConfig.MAX_BODY_OFFSET,
                    Math.max(UnitsConfig.MIN_BODY_HALF_DEPTH,unit.hitSize/(double)Vars.tilesize*UnitsConfig.BODY_HALF_DEPTH_PER_SIZE)));
                halfDepth=physical; // Preserve the existing native limb/weapon attachment frame.
                if(renderBody){
                    if(body.renderProfile(unit,x,y,z,yaw,pitch,roll,camera,ambient,prototype,(float)halfDepth)){
                        prototypeDraws++;renderWeapons(unit,x,y,z,yaw,pitch,roll,camera,ambient,body,prototype);
                    }else{
                        // Deferred/missing atlas generation retains exactly the previous body and attachments.
                        prototype=null;halfDepth=bodyHalfDepth(unit);
                        if(!body.render(unit,x,y,z,yaw,pitch,roll,camera,ambient,(float)(halfDepth/Math.max(.001,spriteHalf))))return false;
                    }
                }
            }else if(renderBody&&!body.render(unit,x,y,z,yaw,pitch,roll,camera,ambient,(float)(halfDepth/Math.max(.001,spriteHalf))))return false;
            if(renderBody&&prototype==null&&mechAim!=null&&fpv.combat.MechWeaponAim3D.supports(unit))
                renderWeapons(unit,x,y,z,yaw,pitch,roll,camera,ambient,body,null);
            boolean folded=creatorFlight.test(unit);GroundGait gait=null;
            if(profile!=null&&profile.legged()){
                int visualLegs="vela".equals(unit.type.name)?VelaTripodLayout.LEGS:2;
                Tracked tracked=states.get(unit.id);
                if(tracked==null||tracked.unit!=unit||tracked.type!=unit.type||tracked.legCount!=visualLegs){
                    if(states.size>=MAX_TRACKED)prune();if(states.size>=MAX_TRACKED)return true;
                    tracked=new Tracked(unit,visualLegs);states.put(unit.id,tracked);
                }
                if(tracked.seen<frame-30){tracked.gait.reset();tracked.updated=Long.MIN_VALUE;}
                tracked.seen=frame;gait=tracked.gait;
                if(folded){gait.reset();tracked.updated=Long.MIN_VALUE;}
                else if(tracked.updated!=Core.graphics.getFrameId()){
                    double radius=Math.max(.12,unit.hitSize/(double)Vars.tilesize*.5),seconds=Vars.state.isPaused()?0:Math.max(0,Time.delta/60.0);
                    gait.update(x,-z,Math.toRadians(yaw)+(visualLegs==3?0:Math.PI),fpv.units.ground.NativeMechLayout.spread(unit.type),radius,seconds,surface);
                    tracked.updated=Core.graphics.getFrameId();
                }
            }
            float limbY=y+(prototype==null?0:prototype.movementUpOffset());
            double chassisScale=prototype==null||prototype.hull()==null?1:prototype.hull().chassisScale();
            double chassisTop=prototype==null||prototype.hull()==null?Double.NaN:prototype.hull().chassisTopOffset(halfDepth,prototype.groundClearance(),pitch,roll);
            if(nativeLimbs.renderMech(unit,x,limbY,z,yaw,pitch,roll,halfDepth,camera,ambient,folded,gait,surface,renderBody,chassisScale,chassisTop)){frameDraws++;frameLegs+="vela".equals(unit.type.name)?3:2;submitted.put(unit.id,unit);}
            return true;
        }catch(RuntimeException error){Log.err("[FPV] Native mech limbs unavailable; retaining body.",error);return false;}
        finally{if(own)endFrame();}
    }
    private void renderWeapons(Unit unit,float x,float y,float z,float yaw,float pitch,float roll,Camera3D camera,float ambient,
            SpriteExtrusionRenderer extrusion,ProceduralUnitModelProfile profile){
        if(unit.mounts==null)return;
        SpriteExtrusionGeometry.basis(yaw,pitch,roll,basis);
        for(var mount:unit.mounts){
            var weapon=mount.weapon;
            if(weapon==null||!NativeAtlasMesh.valid(weapon.region)||weapon.shootOnDeath)continue;
            double weaponYaw=ProceduralUnitModelProfile.weaponYaw(yaw,weapon.rotate,mount.rotation,weapon.baseRotation);
            double recoil=Math.pow(mount.recoil,weapon.recoilPow)*weapon.recoil/Vars.tilesize;
            double relative=Math.toRadians(weaponYaw-yaw);
            double right=weapon.x/(double)Vars.tilesize+(profile==null?0:profile.weaponRightOffset())+Math.sin(relative)*recoil;
            double forward=weapon.y/(double)Vars.tilesize+(profile==null?0:profile.weaponForwardOffset())-Math.cos(relative)*recoil;
            double up=profile==null?bodyHalfDepth(unit)+.1:profile.weaponUpOffset();
            up+=fpv.combat.WeaponHeightStagger.offset(unit,mount);
            float wx=(float)(x+basis[0]*right-basis[6]*forward+basis[3]*up);
            float wy=(float)(y+basis[1]*right-basis[7]*forward+basis[4]*up);
            float wz=(float)(z+basis[2]*right-basis[8]*forward+basis[5]*up);
            var aimed=mechAim==null?null:mechAim.visual(unit,mount);float gunPitch=pitch,gunRoll=roll;
            if(aimed!=null){
                gunPitch=(float)aimed.pitch();gunRoll=0;
                var pivot=aimed.pivot();var center=aimed.center();var pose=mechAim.origin(unit);
                wx=(float)(x+center.x()-pose.x()-Math.cos(Math.toRadians(weaponYaw))*Math.cos(Math.toRadians(gunPitch))*recoil);
                wy=(float)(y+center.z()-pose.z()-Math.sin(Math.toRadians(gunPitch))*recoil);
                wz=(float)(z-center.y()+pose.y()+Math.sin(Math.toRadians(weaponYaw))*Math.cos(Math.toRadians(gunPitch))*recoil);
                saddles.render(camera,ambient,x+pivot.x()-pose.x(),y+pivot.z()-pose.z()-aimed.depth()*.5-aimed.lift()*.5,
                    z-pivot.y()+pose.y(),weaponYaw,Math.max(.10,unit.hitSize/Vars.tilesize*.12),aimed.lift(),Math.max(.14,unit.hitSize/Vars.tilesize*.18));
            }
            if(extrusion.renderComponent(unit,weapon.region,wx,wy,wz,(float)weaponYaw,gunPitch,gunRoll,camera,ambient,
                profile==null?.14f:profile.weaponThickness(),weapon.flipSprite,profile==null?.95f:profile.sideShade()))weaponDraws++;
        }
    }
    private void wheels(Tracked tracked,double x,double height,double z,double yaw,double radius,DoubleBinaryOperator surface,boolean folded){
        double angle=Math.toRadians(yaw),forwardX=Math.cos(angle),forwardZ=-Math.sin(angle),rightX=Math.sin(angle),rightZ=Math.cos(angle);
        double distance=tracked.hasWheelPosition?Math.hypot(x-tracked.wheelX,z-tracked.wheelZ):0;
        if(distance<radius*10)tracked.wheelSpin+=distance/(radius*.30);
        tracked.wheelX=x;tracked.wheelZ=z;tracked.hasWheelPosition=true;
        double sn=Math.sin(tracked.wheelSpin),cs=Math.cos(tracked.wheelSpin);
        for(int i=0;i<4;i++){
            int side=i%2==0?-1:1,fore=i<2?-1:1;
            if(folded){
                double right=side*radius*.78,forward=fore*radius*.58,up=-radius*.25;
                affine(i+1,x+basis[0]*right-basis[6]*forward+basis[3]*up,height+basis[1]*right-basis[7]*forward+basis[4]*up,z+basis[2]*right-basis[8]*forward+basis[5]*up,
                    (-basis[6]*cs+basis[3]*sn)*radius*.30,(-basis[7]*cs+basis[4]*sn)*radius*.30,(-basis[8]*cs+basis[5]*sn)*radius*.30,
                    basis[0]*radius*.26,basis[1]*radius*.26,basis[2]*radius*.26,
                    (basis[6]*sn+basis[3]*cs)*radius*.30,(basis[7]*sn+basis[4]*cs)*radius*.30,(basis[8]*sn+basis[5]*cs)*radius*.30);
                continue;
            }
            double wx=x+rightX*side*radius*.78+forwardX*fore*radius*.58,wz=z+rightZ*side*radius*.78+forwardZ*fore*radius*.58;
            double wy=surface.applyAsDouble(wx,-wz)+radius*.30;
            affine(i+1,wx,wy,wz,forwardX*cs*radius*.30,sn*radius*.30,forwardZ*cs*radius*.30,
                rightX*radius*.26,0,rightZ*radius*.26,-forwardX*sn*radius*.30,cs*radius*.30,-forwardZ*sn*radius*.30);
        }
    }
    private void prune(){var entries=states.entries();while(entries.hasNext()){var entry=entries.next();if(entry.value.seen<frame-1||!entry.value.unit.isAdded())entries.remove();}}
    private void oriented(int p,double x,double y,double z,double sx,double sy,double sz,double[] b){
        affine(p,x,y,z,b[0]*sx,b[1]*sx,b[2]*sx,b[3]*sy,b[4]*sy,b[5]*sy,b[6]*sz,b[7]*sz,b[8]*sz);
    }
    private void affine(int p,double x,double y,double z,double ax,double ay,double az,double bx,double by,double bz,double cx,double cy,double cz){
        int o=p*12;
        rows[o]=(float)ax;rows[o+1]=(float)bx;rows[o+2]=(float)cx;rows[o+3]=(float)x;
        rows[o+4]=(float)ay;rows[o+5]=(float)by;rows[o+6]=(float)cy;rows[o+7]=(float)y;
        rows[o+8]=(float)az;rows[o+9]=(float)bz;rows[o+10]=(float)cz;rows[o+11]=(float)z;
    }
    public void clear(){states.clear();submitted.clear();frameDraws=frameWheels=frameLegs=0;}
    @Override public void close(){if(closed)return;endFrame();closed=true;clear();nativeLimbs.close();saddles.close();for(Cached mesh:meshes.values())mesh.mesh.dispose();meshes.clear();if(shader!=null)shader.dispose();shader=null;}
    private static void enabled(int value,boolean on){if(on)Gl.enable(value);else Gl.disable(value);}
    private record Cached(Mesh mesh,int parts,int vertices){}
    private static final class Tracked{
        final Unit unit;final Object type;final int legCount;final GroundGait gait;long seen,updated=Long.MIN_VALUE;
        double wheelSpin,wheelX,wheelZ;boolean hasWheelPosition;
        Tracked(Unit unit,int legs){this.unit=unit;type=unit.type;legCount=legs;gait=legs==3&&"vela".equals(unit.type.name)?GroundGait.velaTripod():legs>0?new GroundGait(legs):null;}
    }
}
