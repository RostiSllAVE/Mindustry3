package fpv.render;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.graphics.g2d.ScreenQuad;
import arc.graphics.g3d.Camera3D;
import arc.graphics.gl.Shader;
import arc.math.Mathf;
import arc.math.geom.Vec3;
import fpv.generated.EnvironmentConfig;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.type.Planet;

import java.util.Locale;

/** Read-only sky clock and decorative environment outside the native grid. */
public final class EnvironmentRenderer {
    private static final String SKY_VERTEX = "attribute vec2 a_position; attribute vec2 a_texCoord0; varying vec2 v_uv; void main(){v_uv=a_texCoord0;gl_Position=vec4(a_position,0.0,1.0);}";
    private static final String SKY_FRAGMENT = """
        varying vec2 v_uv;
        uniform vec3 u_forward, u_right, u_up, u_sun, u_planet;
        uniform vec3 u_planetColor, u_atmosphere;
        uniform vec2 u_fov;
        uniform float u_day, u_seconds, u_radius, u_starGrid, u_starThreshold;
        float hash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
        float noise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);return mix(mix(hash(i),hash(i+vec2(1,0)),f.x),mix(hash(i+vec2(0,1)),hash(i+vec2(1,1)),f.x),f.y);}
        float fbm(vec2 p){return .54*noise(p)+.27*noise(p*2.03+13.2)+.13*noise(p*4.07+31.8)+.06*noise(p*8.13+7.1);}
        void main(){
            vec2 xy=v_uv*2.0-1.0;
            vec3 ray=normalize(u_forward+u_right*xy.x*u_fov.x+u_up*xy.y*u_fov.y);
            float h=clamp(ray.y,0.0,1.0);
            vec3 night=mix(vec3(.065,.075,.15),vec3(.005,.009,.027),pow(h,.45));
            vec3 day=mix(vec3(.66,.72,.78),vec3(.12,.32,.58),pow(h,.55));
            day=mix(day,day*u_atmosphere*1.8,.09);
            vec3 col=mix(night,day,u_day);
            float dusk=(1.0-smoothstep(.0,.30,abs(u_sun.y)))*pow(1.0-h,5.0);
            float glow=pow(max(dot(ray,u_sun),0.0),6.0);
            col+=vec3(.48,.15,.045)*dusk*(.2+glow*.8);
            float sun=dot(ray,u_sun);
            col+=vec3(1.0,.88,.58)*smoothstep(.99962,.99988,sun)*2.0;
            col+=vec3(.34,.24,.12)*pow(max(sun,0.0),180.0)*u_day;
            vec2 st=vec2(atan(ray.z,ray.x),asin(clamp(ray.y,-1.0,1.0)))*u_starGrid;
            vec2 cell=floor(st), p=fract(st)-vec2(.5);
            float seed=hash(cell), radius=mix(.055,.18,hash(cell+17.0));
            float stars=step(u_starThreshold,seed)*(1.0-smoothstep(radius*.25,radius,length(p)));
            float nightAmount=pow(1.0-u_day,2.0)*smoothstep(-.03,.15,ray.y);
            col+=mix(vec3(.62,.76,1.0),vec3(1.0,.86,.68),hash(cell+9.0))*stars*nightAmount;
            float band=pow(max(0.0,1.0-abs(dot(ray,normalize(vec3(.25,.8,-.47))))),20.0);
            col+=vec3(.025,.03,.065)*band*nightAmount*(.4+.6*hash(floor(st*.4)));
            vec3 pr=normalize(cross(u_planet,vec3(0.0,1.0,0.0)));
            vec3 pu=normalize(cross(pr,u_planet));
            vec2 pp=vec2(dot(ray,pr),dot(ray,pu))/u_radius;
            float rr=dot(pp,pp);
            if(dot(ray,u_planet)>0.0){
                float halo=exp(-abs(rr-1.0)*13.0)*.15;
                col+=u_planetColor*halo;
                if(rr<1.0){
                    vec3 n=vec3(pp,sqrt(max(0.0,1.0-rr)));
                    vec2 spherical=vec2(atan(n.x,n.z),asin(n.y));
                    vec2 surface=spherical*3.4+vec2(u_seconds*.0002,7.0);
                    float terrain=fbm(surface+vec2(fbm(surface*1.9),fbm(surface*1.3+11.0)));
                    float land=smoothstep(.38,.69,terrain);
                    vec3 body=mix(u_planetColor*.32,u_planetColor*1.18,land);
                    float ridges=abs(noise(surface*10.0)-.5)*.15;
                    body*=.88+ridges;
                    float clouds=smoothstep(.58,.78,fbm(spherical*7.0+vec2(u_seconds*.0004,19.0)));
                    body=mix(body,vec3(.85,.85,.84),clouds*.55);
                    float illumination=.12+.88*max(0.0,dot(n,normalize(vec3(-.55,.3,.78))));
                    col=body*illumination+u_planetColor*pow(1.0-n.z,5.0)*.5;
                }
            }
            gl_FragColor=vec4(col,1.0);
        }
        """;
    private static final String LAND_VERTEX = "attribute vec3 a_position;attribute vec4 a_color;uniform mat4 u_combined;varying vec4 v_color;void main(){v_color=a_color;gl_Position=u_combined*vec4(a_position,1.0);}";
    private static final String LAND_FRAGMENT = "varying vec4 v_color;uniform float u_ambient;void main(){gl_FragColor=vec4(v_color.rgb*u_ambient,1.0);}";
    private final Vec3 sun = new Vec3(), right = new Vec3(), up = new Vec3(), north = new Vec3();
    private final Vec3 normal = new Vec3(), east = new Vec3(), globalSun = new Vec3(), companion = new Vec3();
    private final Color skyColor = new Color(), companionColor = new Color(), atmosphere = new Color();
    private Shader skyShader, landShader;
    private ScreenQuad quad;
    private Mesh mountains;
    private Object cachedTiles;
    private int cachedWidth, cachedHeight, cachedPlanet = -1;
    private float day = 1f, illumination = 1f, hour = 12f;
    private double seconds;
    private float previewPhase = Float.NaN;
    private boolean nativeClock, polarClock;
    private boolean disposed;

    public void update(Camera3D camera) {
        Planet planet = planet();
        var sector = Vars.state.hasSector() ? Vars.state.getSector() : null;
        nativeClock = sector != null && Core.settings.getInt("fpv-sky-clock",0)==0;
        polarClock=false;
        seconds = nativeClock ? Vars.universe.secondsf() : Vars.state.tick / 60.0;
        double cycleSeconds=Math.max(120,Core.settings.getInt("fpv-day-minutes",(int)(EnvironmentConfig.CYCLE_SECONDS/60))*60.0);
        float phase = (float)((seconds / cycleSeconds + EnvironmentConfig.START_PHASE) % 1.0);
        if (Float.isFinite(previewPhase)) phase = previewPhase%1f;
        if (nativeClock && !Float.isFinite(previewPhase)) {
            normal.set(sector.tile.v).rotate(Vec3.Y, -planet.getRotation()).nor();
            east.set(Vec3.Y).crs(normal);
            if(east.len2()<.00001f)east.set(1,0,0);else east.nor();
            north.set(normal).crs(east).nor();
            globalSun.set(planet.solarSystem.position).sub(planet.position).nor();
            sun.set(globalSun.dot(east),globalSun.dot(normal),-globalSun.dot(north)).nor();
            float equatorialLength=(float)Math.hypot(normal.x,normal.z);
            polarClock=equatorialLength<.0001f;
            float meridian=polarClock?0:(globalSun.x*normal.x+globalSun.z*normal.z)/equatorialLength;
            hour=polarClock?12f:(float)((12.0-Math.atan2(sun.x,meridian)*12.0/Math.PI+24.0)%24.0);
        } else {
            float a=(phase-.25f)*Mathf.PI2;
            sun.set(Mathf.cos(a),Mathf.sin(a),-.24f).nor();
            hour=phase*24f;
        }
        float t=Mathf.clamp((sun.y-(float)EnvironmentConfig.DAY_START_SUN_Y)/
            (float)(EnvironmentConfig.DAY_FULL_SUN_Y-EnvironmentConfig.DAY_START_SUN_Y));
        day=t*t*(3-2*t);
        illumination=Mathf.lerp((float)EnvironmentConfig.MIN_AMBIENT,(float)EnvironmentConfig.MAX_AMBIENT,day);
        skyColor.set(.065f,.075f,.15f,1).lerp(.66f,.72f,.78f,1,day);
        Planet other=planet==Planets.erekir?Planets.serpulo:Planets.erekir;
        companionColor.set(other.iconColor).lerp(other.atmosphereColor,.3f);
        atmosphere.set(planet.atmosphereColor).lerp(Color.white,.7f);
        float az=(float)EnvironmentConfig.PLANET_AZIMUTH_DEGREES;
        float el=(float)EnvironmentConfig.PLANET_ELEVATION_DEGREES;
        companion.set(Mathf.sinDeg(az)*Mathf.cosDeg(el),Mathf.sinDeg(el),-Mathf.cosDeg(az)*Mathf.cosDeg(el));
        right.set(camera.direction).crs(camera.up).nor();
        up.set(right).crs(camera.direction).nor();
    }

    public void renderBackground(Camera3D camera) {
        if(disposed)return;
        if(skyShader==null){skyShader=new Shader(SKY_VERTEX,SKY_FRAGMENT);landShader=new Shader(LAND_VERTEX,LAND_FRAGMENT);quad=new ScreenQuad();}
        Gl.disable(Gl.depthTest);Gl.depthMask(false);
        skyShader.bind();
        skyShader.setUniformf("u_forward",camera.direction);
        skyShader.setUniformf("u_right",right);skyShader.setUniformf("u_up",up);
        skyShader.setUniformf("u_sun",sun);skyShader.setUniformf("u_planet",companion);
        skyShader.setUniformf("u_planetColor",companionColor.r,companionColor.g,companionColor.b);
        skyShader.setUniformf("u_atmosphere",atmosphere.r,atmosphere.g,atmosphere.b);
        float fy=(float)Math.tan(camera.fov*Mathf.degRad*.5f);
        skyShader.setUniformf("u_fov",fy*Core.graphics.getWidth()/Core.graphics.getHeight(),fy);
        skyShader.setUniformf("u_day",day);skyShader.setUniformf("u_seconds",(float)(seconds%100000));
        skyShader.setUniformf("u_radius",(float)EnvironmentConfig.PLANET_ANGULAR_RADIUS);
        skyShader.setUniformf("u_starGrid",(float)EnvironmentConfig.STAR_GRID);
        skyShader.setUniformf("u_starThreshold",(float)EnvironmentConfig.STAR_THRESHOLD);
        quad.render(skyShader);
        Gl.enable(Gl.depthTest);Gl.depthMask(true);
        if(cachedTiles!=Vars.world.tiles||cachedWidth!=Vars.world.width()||cachedHeight!=Vars.world.height()||cachedPlanet!=planet().id)rebuildMountains();
        landShader.bind();landShader.setUniformMatrix4("u_combined",camera.combined.val);landShader.setUniformf("u_ambient",illumination);
        if(mountains!=null)mountains.render(landShader,Gl.triangles);
    }

    private void rebuildMountains(){
        if(mountains!=null)mountains.dispose();
        cachedTiles=Vars.world.tiles;cachedWidth=Vars.world.width();cachedHeight=Vars.world.height();cachedPlanet=planet().id;
        int segments=(int)EnvironmentConfig.MOUNTAIN_SEGMENTS;
        int rings=5;
        float[] data=new float[segments*(rings-1)*6*7];int index=0;
        float diag=(float)Math.hypot(cachedWidth,cachedHeight),cx=(cachedWidth-1)*.5f,cz=-(cachedHeight-1)*.5f;
        Color base=planet()==Planets.erekir?new Color(.40f,.30f,.23f,1):new Color(.36f,.36f,.42f,1);
        for(int ring=0;ring<rings-1;ring++)for(int i=0;i<segments;i++){
            float a=i*Mathf.PI2/segments,b=(i+1)*Mathf.PI2/segments;
            float[] p=landPoint(a,ring,diag,cx,cz),q=landPoint(b,ring,diag,cx,cz);
            float[] r=landPoint(b,ring+1,diag,cx,cz),s=landPoint(a,ring+1,diag,cx,cz);
            float shade=1f-ring*.12f+Mathf.sin(i*.72f)*.035f;
            for(float[] v:new float[][]{p,q,r,p,r,s}){
                data[index++]=v[0];data[index++]=v[1];data[index++]=v[2];
                data[index++]=base.r*shade;data[index++]=base.g*shade;data[index++]=base.b*shade;data[index++]=1;
            }
        }
        mountains=new Mesh(true,index/7,0,VertexAttribute.position3,new VertexAttribute(4,"a_color"));
        mountains.setVertices(data,0,index);
    }

    private float[] landPoint(float angle,int ring,float diag,float cx,float cz){
        float dx=Mathf.cos(angle),dz=Mathf.sin(angle);
        float edge=1f/Math.max(Math.abs(dx)/(cachedWidth*.5f),Math.abs(dz)/(cachedHeight*.5f));
        float[] offsets={.02f,diag*.055f,diag*.14f,diag*.31f,diag*.72f};
        float r=edge+offsets[ring];
        float peaks=.56f+.2f*Mathf.sin(angle*9+cachedPlanet)+.15f*Mathf.cos(angle*17+2)+.09f*Mathf.sin(angle*31);
        float h=switch(ring){case 0 -> -.08f;case 1 -> diag*.006f;case 2 -> diag*(float)EnvironmentConfig.MOUNTAIN_HEIGHT_DIAGONAL*peaks;case 3 -> diag*.065f*(.7f+.3f*Mathf.sin(angle*13+2));default -> -diag*.06f;};
        return new float[]{cx+dx*r,h,cz+dz*r};
    }

    private Planet planet(){Planet p=Vars.state.getPlanet();return p==null?Planets.serpulo:p;}
    public float ambient(){return illumination;}
    public Color skyColor(){return skyColor;}
    public float farPlane(){return (float)(Math.hypot(Vars.world.width(),Vars.world.height())*EnvironmentConfig.FAR_DIAGONAL_MULTIPLIER+EnvironmentConfig.FAR_MARGIN);}
    /** Includes altitude and chase offset; the enclosing sphere covers every decorative mountain ring. */
    public float farPlane(Camera3D camera){
        double diagonal=Math.hypot(Vars.world.width(),Vars.world.height());
        double dx=camera.position.x-(Vars.world.width()-1)*.5,dz=camera.position.z+(Vars.world.height()-1)*.5;
        double centerDistance=Math.sqrt(dx*dx+camera.position.y*(double)camera.position.y+dz*dz);
        return (float)Math.max(farPlane(),centerDistance+diagonal*EnvironmentConfig.BACKGROUND_BOUND_DIAGONAL+EnvironmentConfig.FAR_MARGIN);
    }
    public double seconds(){return seconds;}
    public float dayAmount(){return day;}
    public String clockLabel(){if(polarClock)return "POLAR SOL";int minutes=(int)(hour*60);return String.format(Locale.ROOT,"%02d:%02d SOL",minutes/60,minutes%60);}
    public String phaseLabel(){return day<.18f?"NIGHT":day>.88f?"DAY":"TWILIGHT";}
    public String clockSource(){return nativeClock?"native Universe/Sector":"state.tick visual cycle";}
    public String companionName(){return (planet()==Planets.erekir?Planets.serpulo:Planets.erekir).name;}
    public void setPreviewPhase(float phase){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("Sky preview is restricted to isolated smoke checks");
        if(Float.isFinite(phase)&&(phase<0||phase>1))throw new IllegalArgumentException("phase must be0..1");
        previewPhase=phase;
    }
    public void dispose(){if(disposed)return;disposed=true;if(skyShader!=null)skyShader.dispose();if(landShader!=null)landShader.dispose();if(quad!=null)quad.dispose();if(mountains!=null)mountains.dispose();}
}
