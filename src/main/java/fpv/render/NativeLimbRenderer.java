package fpv.render;

import arc.Core;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.Texture;
import arc.graphics.VertexAttribute;
import arc.graphics.g2d.TextureRegion;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.struct.IntMap;
import fpv.units.ground.NativeLegLayout;
import mindustry.Vars;
import mindustry.gen.Mechc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

import java.util.ArrayList;
import java.util.Arrays;

/** Native resolved limb art, alpha-extruded once; draw-time work is only small affine palettes and IK. */
public final class NativeLimbRenderer implements AutoCloseable {
    private static final int PARTS_PER_BATCH=32,MAX_PARTS=81,STRIDE=9;
    private static final long CACHE_LIMIT=48L*1024*1024;
    private static final String VERTEX="""
        attribute vec3 a_position,a_normal;
        attribute vec2 a_texCoord0;
        attribute float a_part;
        uniform mat4 u_combined;
        uniform vec4 u_rows[96];
        varying vec2 v_uv;
        varying vec3 v_normal;
        void main(){
            int i=int(a_part)*3;
            vec4 r0=u_rows[i],r1=u_rows[i+1],r2=u_rows[i+2];vec4 p=vec4(a_position,1.0);
            vec3 x=vec3(r0.x,r1.x,r2.x),y=vec3(r0.y,r1.y,r2.y),z=vec3(r0.z,r1.z,r2.z);
            v_normal=x*a_normal.x/max(dot(x,x),.000001)+y*a_normal.y/max(dot(y,y),.000001)+z*a_normal.z/max(dot(z,z),.000001);
            v_uv=a_texCoord0;gl_Position=u_combined*vec4(dot(r0,p),dot(r1,p),dot(r2,p),1.0);
        }
        """;
    private static final String FRAGMENT="""
        uniform sampler2D u_texture;uniform float u_ambient;
        varying vec2 v_uv;varying vec3 v_normal;
        void main(){vec4 tex=texture2D(u_texture,v_uv);if(tex.a<.12)discard;
            float light=.58+.42*max(0.0,dot(normalize(v_normal),normalize(vec3(.35,.8,-.4))));
            gl_FragColor=vec4(tex.rgb*light*u_ambient,1.0);}
        """;
    private final IntMap<TypeCache> cache=new IntMap<>();
    private final float[] rows=new float[MAX_PARTS*12];
    private final double[] knee=new double[3],basis=new double[9];
    private static final class VelaPose {
        final Unit unit;final BoostLegPose fold;double groundedDepth=1.1;long frame=Long.MIN_VALUE;
        VelaPose(Unit unit,boolean airborne){this.unit=unit;fold=new BoostLegPose(airborne);}
    }
    private final IntMap<VelaPose> velaPoses=new IntMap<>();
    public double velaFold(Unit unit){var pose=unit==null?null:velaPoses.get(unit.id);return pose!=null&&pose.unit==unit?pose.fold.amount():0;}
    private Shader shader;private Object atlas;private long bytes,builds,draws;private boolean closed;
    public record Stats(int types,long cacheBytes,long builds,long draws){}
    public Stats stats(){return new Stats(cache.size,bytes,builds,draws);}
    public int geometryVertices(UnitType type){
        TypeCache owner=cache.get(type.id);if(owner==null||owner.type!=type||owner.entries[0]==null)return 0;
        int vertices=0;for(Batch batch:owner.entries[0].batches)vertices+=batch.mesh.getNumVertices();return vertices;
    }
    public int geometryBatches(UnitType type){
        TypeCache owner=cache.get(type.id);return owner==null||owner.type!=type||owner.entries[0]==null?0:owner.entries[0].batches.size();
    }
    public NativeLegLayout.Config layout(UnitType type){return typeCache(type).layout;}
    public boolean renderLegs(Unit unit,int count,GroundGait gait,double x,double h,double z,double yaw,double pitch,double roll,double halfDepth,
                              Camera3D camera,float ambient,boolean folded){
        return renderLegs(unit,count,gait,x,h,z,yaw,pitch,roll,halfDepth,camera,ambient,folded,true);
    }
    public boolean renderLegs(Unit unit,int count,GroundGait gait,double x,double h,double z,double yaw,double pitch,double roll,double halfDepth,
                              Camera3D camera,float ambient,boolean folded,boolean includeBase){
        if(closed||count<2||count>16||gait==null)return false;
        boolean coarse=coarse(camera,x,h,z,Math.max(1,NativeLegLayout.spread(layout(unit.type),count)));
        Entry entry=entry(unit.type,count,false,coarse);if(entry==null)return false;
        NativeLegLayout.Config geometry=layout(unit.type);double bodyRadius=Math.max(.15,unit.hitSize/(double)Vars.tilesize*.5);
        double hipZ=h-halfDepth*.55;
        boolean polished=LegPlatePose.enabled();
        var hull=unit.type==mindustry.content.UnitTypes.corvus?ProceduralUnitModelProfile.forType(unit.type):null;
        double underside=LegPlatePose.underside(h,halfDepth,hull==null?0:hull.groundClearance(),Math.max(bodyRadius,SpriteExtrusionRenderer.bodyRadius(unit)),folded?0:pitch,folded?0:roll);
        double upperDepth=polished?LegPlatePose.depth(entry.depths[0]):entry.depths[0];
        double plateHip=polished?LegPlatePose.centerBelow(underside,Math.max(upperDepth,entry.depths[4]*.65)):hipZ;
        for(int i=0;i<count;i++){
            double a=NativeLegLayout.angle(geometry,i,count,yaw),ox=Math.cos(a),oy=Math.sin(a);
            double hx=x+NativeLegLayout.hipX(geometry,i,count,yaw),hy=-z+NativeLegLayout.hipY(geometry,i,count,yaw);
            double fx=folded?hx+ox*Math.min(geometry.length()*.3,bodyRadius*.95):gait.x(i);
            double fy=folded?hy+oy*Math.min(geometry.length()*.3,bodyRadius*.95):gait.y(i);
            double fz=folded?hipZ-bodyRadius*.45:gait.z(i)+.025;
            ArticulatedLegIK.knee(hx,hy,hipZ,fx,fy,fz,folded?Math.min(geometry.length(),bodyRadius*2.1):geometry.length(),ox,oy,NativeLegLayout.bendSide(geometry,i,count),knee);
            double kz=folded?Math.min(hipZ+bodyRadius*.15,knee[2]):knee[2];
            if(polished)kz=Math.min(kz,LegPlatePose.centerBelow(underside,Math.max(upperDepth,entry.depths[2])));
            int p=i*5,flip=i>=count/2?1:-1;
            limb(p,hx,plateHip,-hy,knee[0],kz,-knee[1],polished?LegPlatePose.width(entry.widths[0]):entry.widths[0],upperDepth,flip);
            double dx=knee[0]-fx,dy=knee[1]-fy,dz=kz-fz,len=Math.max(1e-6,Math.sqrt(dx*dx+dy*dy+dz*dz));
            // Negative native extension trims overlapping coplanar art. In articulated XYZ it opens
            // a real hole (Toxopid/Arkyid have no joint sprite), so lower links always reach the knee.
            // Types without a separate knee sprite use socket-shaped ends in the original artwork.
            // Seat the lower native mesh into the upper one, with a bounded width-sized overlap.
            double socket=entry.depths[2]>0?0:Math.min(len*.2,Math.min(entry.widths[0],entry.widths[1])*.55);
            double ext=Math.max(socket,Math.max(0,Math.min(len*.35,geometry.extension())));
            limb(p+1,knee[0]+dx/len*ext,kz+dz/len*ext,-knee[1]-dy/len*ext,fx,fz,-fy,entry.widths[1],entry.depths[1],flip);
            horizontal(p+2,knee[0],kz,-knee[1],entry.widths[2],entry.heights[2],entry.depths[2],0,1);
            horizontal(p+3,fx,fz,-fy,entry.widths[3],entry.heights[3],entry.depths[3],Math.toDegrees(Math.atan2(fy-hy,fx-hx)),1);
            horizontal(p+4,hx,plateHip,-hy,entry.widths[4]*(polished?LegPlatePose.SOCKET_SCALE:1),entry.heights[4]*(polished?LegPlatePose.SOCKET_SCALE:1),entry.depths[4]*(polished?.65:1),yaw,1);
        }
        double baseDepth=entry.depths[5]*(polished?.65:1),baseHeight=polished?LegPlatePose.centerBelow(underside,baseDepth):hipZ+.02;
        if(includeBase)horizontal(count*5,x,baseHeight,z,entry.widths[5]*(polished?.85:1),entry.heights[5]*(polished?.85:1),baseDepth,yaw-90,1);
        else affine(count*5,x,hipZ,z,0,0,0,0,0,0,0,0,0);
        if(folded)tilt(entry.parts,x,h,z,yaw,yaw,pitch,roll);
        return draw(entry,camera,ambient);
    }
    /** Native Mechc has two mirrored whole-leg sprites, not separate upper/lower/joint artwork. */
    public boolean renderMech(Unit unit,double x,double h,double z,double yaw,double pitch,double roll,double halfDepth,
            Camera3D camera,float ambient,boolean folded,GroundGait gait,java.util.function.DoubleBinaryOperator surface){
        return renderMech(unit,x,h,z,yaw,pitch,roll,halfDepth,camera,ambient,folded,gait,surface,true);
    }
    public boolean renderMech(Unit unit,double x,double h,double z,double yaw,double pitch,double roll,double halfDepth,
            Camera3D camera,float ambient,boolean folded,GroundGait gait,java.util.function.DoubleBinaryOperator surface,boolean includeBase){
        return renderMech(unit,x,h,z,yaw,pitch,roll,halfDepth,camera,ambient,folded,gait,surface,includeBase,1,Double.NaN);
    }
    public boolean renderMech(Unit unit,double x,double h,double z,double yaw,double pitch,double roll,double halfDepth,
            Camera3D camera,float ambient,boolean folded,GroundGait gait,java.util.function.DoubleBinaryOperator surface,boolean includeBase,
            double chassisScale,double chassisTopOffset){
        if(closed||!(unit instanceof Mechc mech))return false;
        Entry entry=entry(unit.type,"vela".equals(unit.type.name)?3:2,true,coarse(camera,x,h,z,unit.hitSize/(double)Vars.tilesize));if(entry==null)return false;
        if(unit.type.name.equals("vela"))return renderVela(unit,entry,gait,x,h,z,yaw,pitch,roll,halfDepth,camera,ambient,folded,surface,includeBase,chassisScale,chassisTopOffset);
        double baseYaw=mech.baseRotation(),angle=Math.toRadians(baseYaw),extension=folded?0:mech.walkExtend(false)/Vars.tilesize;
        double phase=folded?0:Math.sin(mech.walkExtend(true)/(2/Math.PI));
        double legDepth=GroundModelAlignment.limbDepth(entry.depths[0],halfDepth);
        for(int i=0;i<2;i++){
            int side=i==0?-1:1;double shift=extension*side;
            double lift=folded?0:Math.max(0,-phase*side)*Math.max(.04,unit.hitSize/(double)Vars.tilesize*.065);
            double px=x+Math.cos(angle)*shift,pz=z-Math.sin(angle)*shift;
            double support=folded?h-halfDepth*2:surface.applyAsDouble(px,-pz);
            double supportedDepth=GroundModelAlignment.supportedLimbDepth(entry.depths[0],halfDepth,h,support);
            if(supportedDepth<=0){affine(i,px,support,pz,0,0,0,0,0,0,0,0,0);continue;}
            double center=GroundModelAlignment.limbCenter(h,halfDepth,supportedDepth,support,lift);
            horizontal(i,px,center,pz,entry.widths[0],entry.heights[0]*(1-Math.max(-phase*side,0)*.25),supportedDepth,baseYaw-90+(folded?side*30:0),side);
        }
        double baseDepth=GroundModelAlignment.baseDepth(entry.depths[1],halfDepth);
        double baseCenter=GroundModelAlignment.baseCenter(h,halfDepth,baseDepth);
        if(Double.isFinite(chassisTopOffset)){
            double bottom=baseCenter-baseDepth*.5,top=Math.max(baseCenter+baseDepth*.5,h+chassisTopOffset);
            baseDepth=top-bottom;baseCenter=(top+bottom)*.5;
        }
        if(includeBase)horizontal(2,x,baseCenter,z,entry.widths[1]*chassisScale,entry.heights[1]*chassisScale,baseDepth,baseYaw-90,1);
        else affine(2,x,h,z,0,0,0,0,0,0,0,0,0);
        if(folded)tilt(entry.parts,x,h,z,baseYaw,yaw,pitch,roll);
        return draw(entry,camera,ambient);
    }
    /** Vela alone uses three visual articulated native-art chains; Corvus and ordinary whole-sprite mechs are unchanged. */
    private boolean renderVela(Unit unit,Entry entry,GroundGait gait,double x,double h,double z,double yaw,double pitch,double roll,
            double halfDepth,Camera3D camera,float ambient,boolean folded,java.util.function.DoubleBinaryOperator surface,boolean includeBase,double chassisScale,double chassisTopOffset){
        double a=Math.toRadians(yaw),radius=unit.hitSize/(double)Vars.tilesize*.5;
        double hipHeight=h-halfDepth*.45;
        VelaPose pose=velaPoses.get(unit.id);
        if(pose==null||pose.unit!=unit){
            if(velaPoses.size>=16384){var it=velaPoses.entries();while(it.hasNext())if(!it.next().value.unit.isAdded())it.remove();}
            if(velaPoses.size>=16384)return false;
            pose=new VelaPose(unit,folded);velaPoses.put(unit.id,pose);
        }
        if(pose.frame!=Core.graphics.getFrameId()){
            pose.fold.advance(folded,Vars.state.isPaused()?0:Math.max(0,arc.util.Time.delta/60d));pose.frame=Core.graphics.getFrameId();
        }
        double blend=pose.fold.eased();
        for(int i=0;i<VelaTripodLayout.LEGS;i++){
            int side=i==0?-1:1,p=i*4;
            double hx=x+VelaTripodLayout.hipX(i,a,radius),hy=-z+VelaTripodLayout.hipY(i,a,radius);
            double tucked=i==2?radius*.30:radius*.60;
            double groundX=gait!=null&&!folded?gait.x(i):x+VelaTripodLayout.footX(i,a,fpv.units.ground.NativeMechLayout.spread(unit.type));
            double groundY=gait!=null&&!folded?gait.y(i):-z+VelaTripodLayout.footY(i,a,fpv.units.ground.NativeMechLayout.spread(unit.type));
            double footDepth=entry.depths[3];
            double groundZ=folded?hipHeight-pose.groundedDepth:(gait==null?surface.applyAsDouble(groundX,groundY):gait.z(i))+footDepth*.5;
            if(!folded&&i==0)pose.groundedDepth=Math.max(.1,hipHeight-groundZ);
            double fx=groundX+(hx+VelaTripodLayout.footX(i,a,tucked)-groundX)*blend;
            double fy=groundY+(hy+VelaTripodLayout.footY(i,a,tucked)-groundY)*blend;
            double fz=groundZ+(hipHeight-radius*.50-groundZ)*blend;
            double reach=Math.max(radius,fpv.units.ground.NativeMechLayout.reach(unit.type)),legYaw=i==2?yaw+90:yaw;
            if(i==2)VelaLegGeometry.rearKnee(hx,hy,hipHeight,fx,fy,fz,reach,yaw,knee);
            else VelaLegGeometry.knee(hx,hy,hipHeight,fx,fy,fz,reach,yaw,side,knee);
            double width=Math.min(entry.widths[0],radius*.90);
            velaLink(p,hx,hipHeight,-hy,knee[0],knee[2],-knee[1],width,entry.depths[0],legYaw,side);
            velaLink(p+1,knee[0],knee[2],-knee[1],fx,fz,-fy,Math.min(entry.widths[1],width*.95),entry.depths[1],legYaw,side);
            // The original socket crop hides both link ends. A horizontal native toe plants on the actual foot surface.
            velaLink(p+2,knee[0],knee[2]-.16,-knee[1],knee[0],knee[2]+.16,-knee[1],width*.90,entry.depths[2],legYaw,side);
            horizontal(p+3,fx,fz,-fy,Math.min(entry.widths[3]*1.10,width),entry.heights[3]*1.15,footDepth,legYaw-90,side);
        }
        double baseDepth=GroundModelAlignment.baseDepth(entry.depths[4],halfDepth);
        double baseCenter=GroundModelAlignment.baseCenter(h,halfDepth,baseDepth);
        if(Double.isFinite(chassisTopOffset)){
            double bottom=baseCenter-baseDepth*.5,top=Math.max(baseCenter+baseDepth*.5,h+chassisTopOffset);
            baseDepth=top-bottom;baseCenter=(top+bottom)*.5;
        }
        if(includeBase)horizontal(12,x,baseCenter,z,entry.widths[4]*chassisScale,entry.heights[4]*chassisScale,baseDepth,yaw-90,1);
        else affine(12,x,h,z,0,0,0,0,0,0,0,0,0);
        if(folded)tilt(entry.parts,x,h,z,yaw,yaw,pitch,roll);
        return draw(entry,camera,ambient);
    }
    /** Native cropped artwork runs along local Z, with local X across the armour face. */
    private void velaLink(int part,double ax,double ay,double az,double bx,double by,double bz,double width,double depth,double yaw,int side){
        double dx=bx-ax,dy=by-ay,dz=bz-az,len=Math.max(1e-8,Math.sqrt(dx*dx+dy*dy+dz*dz));
        double ux=dx/len,uy=dy/len,uz=dz/len,a=Math.toRadians(yaw);
        double wx=Math.cos(a),wy=0,wz=-Math.sin(a),dot=wx*ux+wz*uz;
        wx-=dot*ux;wy-=dot*uy;wz-=dot*uz;
        double n=Math.sqrt(wx*wx+wy*wy+wz*wz);
        if(n<1e-6){wx=-Math.sin(a);wy=0;wz=-Math.cos(a);n=1;}
        wx/=n;wy/=n;wz/=n;
        double nx=wy*uz-wz*uy,ny=wz*ux-wx*uz,nz=wx*uy-wy*ux;
        double socket=Math.min(.08,len*.08),stretch=1+socket/len;
        affine(part,(ax+bx)*.5,(ay+by)*.5,(az+bz)*.5,wx*width*side,wy*width*side,wz*width*side,
            nx*depth,ny*depth,nz*depth,dx*stretch,dy*stretch,dz*stretch);
    }
    private Entry buildVela(UnitType type,boolean coarse){
        if(!valid(type.legRegion)||!valid(type.baseRegion))return null;
        Entry result=new Entry(3,true,13);var builders=new ArrayList<Builder>();
        var pixels=Core.atlas.getPixmap((AtlasRegion)type.legRegion);byte[] alpha=new byte[pixels.width*pixels.height];
        for(int y=0;y<pixels.height;y++)for(int x=0;x<pixels.width;x++)alpha[x+y*pixels.width]=(byte)pixels.getA(x,y);
        var pieces=VelaLegGeometry.split(alpha,pixels.width,pixels.height,coarse);
        for(int kind=0;kind<4;kind++){
            var piece=pieces[kind];double scale=type.legRegion.scl()/Vars.tilesize;
            result.widths[kind]=piece.width()*scale;result.heights[kind]=piece.height()*scale;
            result.depths[kind]=kind==3?.28:Math.max(.40,Math.min(.64,result.widths[kind]*.48));
            for(int leg=0;leg<VelaTripodLayout.LEGS;leg++)add(builders,piece.vertices(),type.legRegion,leg*4+kind);
        }
        var base=Core.atlas.getPixmap((AtlasRegion)type.baseRegion);byte[] baseAlpha=new byte[base.width*base.height];
        for(int y=0;y<base.height;y++)for(int x=0;x<base.width;x++)baseAlpha[x+y*base.width]=(byte)base.getA(x,y);
        var shape=SpriteExtrusionGeometry.generate(baseAlpha,base.width,base.height,coarse?12:28,6,32,.13,4096);
        result.widths[4]=type.baseRegion.width*type.baseRegion.scl()/Vars.tilesize;
        result.heights[4]=type.baseRegion.height*type.baseRegion.scl()/Vars.tilesize;
        result.depths[4]=Math.max(.035,Math.min(.32,Math.min(result.widths[4],result.heights[4])*.32));
        add(builders,shape.vertices(),type.baseRegion,12);
        try{
            for(Builder b:builders){
                Mesh mesh=new Mesh(true,b.size/STRIDE,0,VertexAttribute.position3,VertexAttribute.normal,VertexAttribute.texCoords,new VertexAttribute(1,"a_part"));
                try{mesh.setVertices(b.data,0,b.size);}catch(RuntimeException e){mesh.dispose();throw e;}
                result.batches.add(new Batch(mesh,b.texture,b.start));result.bytes+=(long)b.size*4*2;
            }
            return result;
        }catch(RuntimeException e){for(Batch b:result.batches)b.mesh.dispose();throw e;}
    }
    private static void add(ArrayList<Builder> builders,float[] vertices,TextureRegion region,int part){
        int start=part/PARTS_PER_BATCH*PARTS_PER_BATCH;Builder builder=null;
        for(Builder b:builders)if(b.texture==region.texture&&b.start==start){builder=b;break;}
        if(builder==null){builder=new Builder(region.texture,start);builders.add(builder);}
        builder.add(vertices,region,part-start);
    }
    private void tilt(int parts,double x,double y,double z,double baseYaw,double yaw,double pitch,double roll){
        SpriteExtrusionGeometry.basis(yaw,pitch,roll,basis);double a=Math.toRadians(baseYaw),s=Math.sin(a),c=Math.cos(a);
        for(int p=0;p<parts;p++){
            int o=p*12;
            for(int column=0;column<4;column++){
                double vx=rows[o+column]-(column==3?x:0),vy=rows[o+4+column]-(column==3?y:0),vz=rows[o+8+column]-(column==3?z:0);
                double right=vx*s+vz*c,back=-vx*c+vz*s;
                rows[o+column]=(float)(basis[0]*right+basis[3]*vy+basis[6]*back+(column==3?x:0));
                rows[o+4+column]=(float)(basis[1]*right+basis[4]*vy+basis[7]*back+(column==3?y:0));
                rows[o+8+column]=(float)(basis[2]*right+basis[5]*vy+basis[8]*back+(column==3?z:0));
            }
        }
    }
    public static String assetSummary(Unit unit){
        UnitType type=unit.type;TextureRegion[] assets=unit instanceof Mechc?new TextureRegion[]{type.legRegion,type.baseRegion}:
            new TextureRegion[]{type.legRegion,type.legBaseRegion,type.jointRegion,type.footRegion,type.baseJointRegion,type.baseRegion};
        StringBuilder out=new StringBuilder(type.name).append(':');for(TextureRegion r:assets)if(valid(r))out.append(' ').append(((AtlasRegion)r).name).append('[').append(r.width).append('x').append(r.height).append(']');return out.toString();
    }
    private boolean draw(Entry entry,Camera3D camera,float ambient){
        if(shader==null)shader=new Shader(VERTEX,FRAGMENT);
        Gl.enable(Gl.depthTest);Gl.depthMask(true);Gl.disable(Gl.cullFace);Gl.disable(Gl.blend);
        shader.bind();shader.setUniformMatrix4("u_combined",camera.combined.val);shader.setUniformi("u_texture",0);shader.setUniformf("u_ambient",Math.max(0,Math.min(1,ambient)));
        for(Batch batch:entry.batches){batch.texture.bind(0);shader.setUniform4fv("u_rows[0]",rows,batch.start*12,Math.min(PARTS_PER_BATCH,entry.parts-batch.start)*12);batch.mesh.render(shader,Gl.triangles);draws++;}
        return !entry.batches.isEmpty();
    }
    private TypeCache typeCache(UnitType type){
        if(atlas!=Core.atlas){clearCache();atlas=Core.atlas;}
        TypeCache entry=cache.get(type.id);
        if(entry==null||entry.type!=type){if(entry!=null)remove(entry);entry=new TypeCache(type);cache.put(type.id,entry);}
        return entry;
    }
    private Entry entry(UnitType type,int count,boolean mech,boolean coarse){
        TypeCache owner=typeCache(type);int lod=coarse?1:0;
        if(owner.entries[lod]!=null&&(owner.entries[lod].count!=count||owner.entries[lod].mech!=mech)){dispose(owner.entries[lod]);owner.entries[lod]=null;}
        if(owner.entries[lod]==null){
            Entry built=build(type,count,mech,coarse);if(built==null)return null;
            while((cache.size>64||bytes+built.bytes>CACHE_LIMIT)&&cache.size>1){TypeCache victim=null;for(TypeCache c:cache.values())if(c!=owner){victim=c;break;}if(victim==null)break;cache.remove(victim.type.id);remove(victim);}
            owner.entries[lod]=built;bytes+=built.bytes;builds++;
        }
        return owner.entries[lod];
    }
    private Entry build(UnitType type,int count,boolean mech,boolean coarse){
        if(mech&&type.name.equals("vela"))return buildVela(type,coarse);
        TextureRegion[] assets=mech?new TextureRegion[]{type.legRegion,type.baseRegion}:new TextureRegion[]{type.legRegion,type.legBaseRegion,type.jointRegion,type.footRegion,type.baseJointRegion,type.baseRegion};
        if(!valid(assets[0])||!mech&&!valid(assets[1]))return null;
        Entry result=new Entry(count,mech,mech?3:count*5+1);
        ArrayList<Builder> builders=new ArrayList<>();
        try{
            for(int kind=0;kind<assets.length;kind++){
                TextureRegion region=assets[kind];if(!valid(region))continue;
                var pixels=Core.atlas.getPixmap((AtlasRegion)region);byte[] alpha=new byte[pixels.width*pixels.height];
                for(int yy=0;yy<pixels.height;yy++)for(int xx=0;xx<pixels.width;xx++)alpha[xx+yy*pixels.width]=(byte)pixels.getA(xx,yy);
                var shape=SpriteExtrusionGeometry.generate(alpha,pixels.width,pixels.height,coarse?12:28,6,32,.13,4096);
                result.zMin[kind]=Double.POSITIVE_INFINITY;result.zMax[kind]=Double.NEGATIVE_INFINITY;
                for(int v=2;v<shape.vertices().length;v+=SpriteExtrusionGeometry.STRIDE){result.zMin[kind]=Math.min(result.zMin[kind],shape.vertices()[v]);result.zMax[kind]=Math.max(result.zMax[kind],shape.vertices()[v]);}
                float scale=region.scl()/Vars.tilesize;
                result.widths[kind]=region.width*scale;result.heights[kind]=region.height*scale;
                result.depths[kind]=Math.max(.035,Math.min(.32,Math.min(result.widths[kind],result.heights[kind])*.32));
                if(!mech&&kind<2)result.widths[kind]=region.height*type.legRegion.scl()/Vars.tilesize;
                int copies=mech?(kind==0?2:1):(kind<5?count:1);
                for(int leg=0;leg<copies;leg++){
                    int part=mech?(kind==0?leg:2):(kind<5?leg*5+kind:count*5),start=part/PARTS_PER_BATCH*PARTS_PER_BATCH;
                    Builder builder=null;for(Builder b:builders)if(b.texture==region.texture&&b.start==start){builder=b;break;}
                    if(builder==null){builder=new Builder(region.texture,start);builders.add(builder);}
                    builder.add(shape.vertices(),region,part-start);
                }
            }
            for(Builder b:builders){
                Mesh mesh=new Mesh(true,b.size/STRIDE,0,VertexAttribute.position3,VertexAttribute.normal,VertexAttribute.texCoords,new VertexAttribute(1,"a_part"));
                try{mesh.setVertices(b.data,0,b.size);}catch(RuntimeException e){mesh.dispose();throw e;}
                result.batches.add(new Batch(mesh,b.texture,b.start));result.bytes+=(long)b.size*4*2;
            }
            return result;
        }catch(RuntimeException failure){for(Batch b:result.batches)b.mesh.dispose();throw failure;}
    }
    private void limb(int part,double ax,double ay,double az,double bx,double by,double bz,double width,double depth,int flip){
        double dx=bx-ax,dy=by-ay,dz=bz-az,length=Math.max(1e-8,Math.sqrt(dx*dx+dy*dy+dz*dz));
        double ux=dx/length,uy=dy/length,uz=dz/length;
        double nx=-ux*uy,ny=1-uy*uy,nz=-uz*uy,norm=Math.sqrt(nx*nx+ny*ny+nz*nz);
        if(norm<1e-6){nx=1;ny=0;nz=0;norm=1;}
        nx/=norm;ny/=norm;nz/=norm;
        double wx=uy*nz-uz*ny,wy=uz*nx-ux*nz,wz=ux*ny-uy*nx;
        affine(part,(ax+bx)*.5,(ay+by)*.5,(az+bz)*.5,dx,dy,dz,nx*depth,ny*depth,nz*depth,wx*width*flip,wy*width*flip,wz*width*flip);
    }
    private void horizontal(int part,double x,double y,double z,double width,double height,double depth,double degrees,int flip){
        double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a);affine(part,x,y,z,c*width*flip,0,-s*width*flip,0,depth,0,s*height,0,c*height);
    }
    private void affine(int p,double x,double y,double z,double ax,double ay,double az,double bx,double by,double bz,double cx,double cy,double cz){int o=p*12;
        rows[o]=(float)ax;rows[o+1]=(float)bx;rows[o+2]=(float)cx;rows[o+3]=(float)x;
        rows[o+4]=(float)ay;rows[o+5]=(float)by;rows[o+6]=(float)cy;rows[o+7]=(float)y;
        rows[o+8]=(float)az;rows[o+9]=(float)bz;rows[o+10]=(float)cz;rows[o+11]=(float)z;}
    private static boolean coarse(Camera3D camera,double x,double y,double z,double radius){double dx=x-camera.position.x,dy=y-camera.position.y,dz=z-camera.position.z,d=Math.max(35,radius*22);return dx*dx+dy*dy+dz*dz>d*d;}
    private static boolean valid(TextureRegion region){return region instanceof AtlasRegion ar&&!ar.rotate&&!region.isFlipX()&&!region.isFlipY()&&region.found()&&region.texture!=null&&region.width>0&&region.height>0;}
    private void remove(TypeCache owner){for(Entry entry:owner.entries)if(entry!=null)dispose(entry);}
    private void dispose(Entry entry){for(Batch batch:entry.batches)batch.mesh.dispose();bytes-=entry.bytes;}
    private void clearCache(){for(TypeCache owner:cache.values())remove(owner);cache.clear();velaPoses.clear();bytes=0;}
    @Override public void close(){if(closed)return;closed=true;clearCache();if(shader!=null)shader.dispose();shader=null;}
    private static final class TypeCache{final UnitType type;final NativeLegLayout.Config layout;final Entry[] entries=new Entry[2];TypeCache(UnitType t){type=t;layout=NativeLegLayout.config(t);}}
    private static final class Entry{final int count,parts;final boolean mech;final double[] widths=new double[6],heights=new double[6],depths=new double[6],zMin=new double[6],zMax=new double[6];final ArrayList<Batch> batches=new ArrayList<>();long bytes;Entry(int count,boolean mech,int parts){this.count=count;this.mech=mech;this.parts=parts;}}
    private record Batch(Mesh mesh,Texture texture,int start){}
    private static final class Builder{final Texture texture;final int start;float[] data=new float[8192];int size;Builder(Texture texture,int start){this.texture=texture;this.start=start;}
        void add(float[] vertices,TextureRegion region,int part){int needed=size+vertices.length/8*STRIDE;if(needed>data.length)data=Arrays.copyOf(data,Math.max(needed,data.length*2));
            float u0=region.u+.5f/region.texture.width,u1=region.u2-.5f/region.texture.width,v0=region.v+.5f/region.texture.height,v1=region.v2-.5f/region.texture.height;
            for(int i=0;i<vertices.length;i+=8){for(int j=0;j<6;j++)data[size++]=vertices[i+j];data[size++]=u0+(u1-u0)*vertices[i+6];data[size++]=v0+(v1-v0)*vertices[i+7];data[size++]=part;}}
    }
}
