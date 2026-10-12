package fpv.render;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Gl;
import arc.graphics.g3d.Camera3D;
import arc.util.ScreenUtils;
import fpv.units.UnitAltitudeSystem;
import fpv.units.ground.GroundUnitProfiles;
import fpv.units.ground.NativeLegLayout;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.UnitController;
import mindustry.gen.Legsc;
import mindustry.gen.Mechc;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Tile;
import java.util.ArrayList;

/** Isolated native GPU fixture: all actual walker capabilities, original resolved art, separate real frames. */
public final class GroundFamilyVisualChecks implements AutoCloseable {
    /** Native generated map in the explicitly isolated lab only; never load, modify or write a user save. */
    public static void loadFixtureWorld(){
        require(Boolean.getBoolean("fpv.smoke"),"explicit isolated lab mode");
        // Match ordinary asynchronous map entry; do not replace the world halfway through the controller update.
        Core.app.post(GroundFamilyVisualChecks::generateFixtureWorld);
    }
    private static void generateFixtureWorld(){
        Vars.control.saves.resetSave();Vars.logic.reset();
        Vars.state.rules.waves=false;Vars.state.rules.canGameOver=false;Vars.state.rules.fog=false;Vars.state.rules.staticFog=false;
        Vars.state.rules.unitCap=1000;Vars.state.rules.defaultTeam=mindustry.game.Team.sharded;
        Vars.state.map=new mindustry.maps.Map(arc.struct.StringMap.of("name","FPV isolated native walker artwork fixture"));
        Vars.world.loadGenerator(128,128,tiles->{
            for(int y=0;y<128;y++)for(int x=0;x<128;x++)tiles.set(x,y,new Tile(x,y,Blocks.sand,Blocks.air,Blocks.air));
            tiles.get(16,16).setBlock(Blocks.coreShard,mindustry.game.Team.sharded,0);
            // The native MinerAI ore index is populated on WorldLoad, not when a lab
            // adds an overlay later. Seed only the isolated layer/mining fixture.
            if(Boolean.getBoolean("fpv.layers.smoke")){
                tiles.get(62,60).setOverlay(Blocks.oreCopper);
                tiles.get(62,61).setOverlay(Blocks.oreLead);
            }
        });
        Vars.player.team(mindustry.game.Team.sharded);Vars.logic.play();
    }
    private final FpvWorldRenderer renderer;
    private final GroundFamilyRenderer family;
    private final UnitAltitudeSystem altitudes;
    private final Camera3D camera=new Camera3D();
    private final ArrayList<UnitType> types=new ArrayList<>();
    private final ArrayList<mindustry.gen.Building> supports=new ArrayList<>();
    private final StringBuilder report=new StringBuilder("# Native walker GPU artwork verification\n\nResolved installed atlas fields; no copied assets. Mechc has two complete mirrored leg sprites, not invented joints.\n\n");
    private final Object tiles;
    private final Tile site;
    private Unit current,flyer;
    private int index,warmFrames,leggedCount,mechCount,completed;
    private boolean extensionPrepared,allDone;
    private int velaWalkFrames;
    private boolean velaWalking;
    private float velaStartX,velaStartY,velaStartYaw;
    private float velaForwardX,velaForwardY,velaLeftYaw;
    private final boolean[] velaSwung=new boolean[3];
    private java.util.function.Consumer<Unit> velaControlCheck;
    public void setVelaControlCheck(java.util.function.Consumer<Unit> check){velaControlCheck=check;}
    private java.util.function.Consumer<Unit> maceControlCheck;
    public void setMaceControlCheck(java.util.function.Consumer<Unit> check){maceControlCheck=check;}
    private java.util.function.Consumer<Unit> fortressControlCheck;
    public void setFortressControlCheck(java.util.function.Consumer<Unit> check){fortressControlCheck=check;}
    private java.util.function.Consumer<Unit> scepterControlCheck;
    public void setScepterControlCheck(java.util.function.Consumer<Unit> check){scepterControlCheck=check;}
    private java.util.function.Consumer<Unit> reignControlCheck;
    public void setReignControlCheck(java.util.function.Consumer<Unit> check){reignControlCheck=check;}
    private java.util.function.Consumer<Unit> greenControlCheck;
    public void setGreenControlCheck(java.util.function.Consumer<Unit> check){greenControlCheck=check;}
    public GroundFamilyVisualChecks(FpvWorldRenderer renderer,GroundFamilyRenderer family,UnitAltitudeSystem altitudes){
        require(Boolean.getBoolean("fpv.smoke"),"explicit isolated lab mode");
        this.renderer=renderer;this.family=family;this.altitudes=altitudes;tiles=Vars.world.tiles;
        site=findSite();require(site!=null,"clear dry27x27 native fixture area");
        types.add(UnitTypes.crawler);
        for(UnitType type:Vars.content.units()){
            if(type==UnitTypes.crawler||type.flying)continue;
            Unit probe=type.create(Vars.player.team());
            if(probe instanceof Legsc||probe instanceof Mechc)types.add(type);
        }
        require(types.size()>10,"capability coverage extends beyond original purple family");
        flyer=UnitTypes.flare.create(Vars.player.team());flyer.set((site.x+23)*Vars.tilesize,(site.y+23)*Vars.tilesize);
        flyer.controller(new Stationary());flyer.add();altitudes.pose(flyer);
        camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.far=2000;spawn(types.get(0));
    }
    /** Each body mesh builds on a real frame; stance settles through the production solver. */
    public void warm(){
        if(allDone&&!extensionPrepared)return;
        view(0);renderer.render(camera);warmFrames++;
        if(extensionPrepared)return;
        var profile=GroundUnitProfiles.profile(current);var physical=altitudes.groundFamily().pose(current);
        boolean settled=profile==null||!profile.legged()||physical!=null&&Math.abs(physical.z()-physical.desiredZ())<.08;
        if(current.type==UnitTypes.vela&&settled&&warmFrames>=24&&!velaWalking){
            current.controller(new VelaWalking());capture("vela-moving-start.png");
            velaWalking=true;velaStartX=current.x;velaStartY=current.y;velaStartYaw=current.rotation;
        }
        if(current.type==UnitTypes.vela&&velaWalking){
            velaWalkFrames++;int planted=0;
            require(family.visualLegCount(current)==3,"three render-only Vela feet");
            for(int leg=0;leg<3;leg++){if(family.footPlanted(current,leg))planted++;else velaSwung[leg]=true;}
            require(planted>=2,"Vela tripod swings only one foot on real frames");
            if(velaWalkFrames==90){
                require(Math.hypot(current.x-velaStartX,current.y-velaStartY)>.05,"native Vela moved forward");
                velaForwardX=current.x;velaForwardY=current.y;capture("vela-tripod-forward.png");
            }else if(velaWalkFrames==180){
                require(Math.hypot(current.x-velaForwardX,current.y-velaForwardY)>.05,"native Vela moved backward");capture("vela-tripod-backward.png");
            }else if(velaWalkFrames==270){
                require(current.rotation-velaStartYaw>20,"native Vela turned left");velaLeftYaw=current.rotation;capture("vela-tripod-left.png");
            }else if(velaWalkFrames==360){
                require(velaLeftYaw-current.rotation>20,"native Vela turned right");capture("vela-tripod-right.png");
            }
        }
        if((current.type==UnitTypes.vela?velaWalkFrames>=360:warmFrames>=24)&&settled&&family.wasSubmitted(current)){
            verifyCurrent();completed++;
            if(++index==types.size())allDone=true;else spawn(types.get(index));
        }
        if(warmFrames>900)throw new IllegalStateException("Native walker did not settle/render: "+current.type.name);
    }
    public boolean ready(){return allDone;}
    public String verifyAndCapture(){
        require(allDone&&completed==types.size(),"every native walker captured");
        require(leggedCount>=10&&mechCount>=5,"purple, green, Erekir articulated families and whole-leg mechs covered");
        require(GroundUnitProfiles.profile(flyer)==null&&!GroundFamilyRenderer.supportsVisual(flyer),"Flare outside ground visual system");
        var pose=altitudes.unitState(flyer);camera.position.set((float)pose.x()+4,(float)pose.z()+3,(float)-pose.y()+4);
        camera.lookAt((float)pose.x(),(float)pose.z(),(float)-pose.y());camera.update();renderer.render(camera);
        require(renderer.wasUnitSubmitted(flyer)&&!family.wasSubmitted(flyer),"Flare retains ordinary native extrusion");capture("walker-flare-unchanged.png");
        require(family.nativeLimbStats().cacheBytes()<=48L*1024*1024,"bounded48MiB cached native limb geometry");
        require(Gl.getError()==0,"native limb shader and affine palette produce no GL errors");
        report.append("\nValidated ").append(completed).append(" native walkers: ").append(leggedCount).append(" Legsc types, ").append(mechCount).append(" Mechc types, and Crawler wheel override.\n")
            .append("Limb cache: ").append(family.nativeLimbStats()).append("\n");
        output().child("native-walker-assets.md").writeString(report.toString());
        return "PASS: "+completed+" native walkers; "+leggedCount+" textured articulated types; "+mechCount+" original whole-leg mechs; Crawler4wheels; front/side/back/top GPU captures; immutable map/body/HP; Flare unchanged";
    }
    private void verifyCurrent(){
        Unit unit=current;long before=fingerprint();float nativeX=unit.x,nativeY=unit.y,hp=unit.health,elevation=unit.elevation;
        if(unit.type==UnitTypes.vela)for(int leg=0;leg<3;leg++)require(velaSwung[leg],"each Vela leg really stepped on native frames: "+leg+" travel="+family.visualTravel(unit)+" XYZ="+altitudes.unitState(unit)+" fold="+family.velaFold(unit)+" native="+unit.elevation);
        var profile=GroundUnitProfiles.profile(unit);var pose=altitudes.unitState(unit);require(pose!=null&&!pose.flying(),unit.type.name+" remains grounded");
        int count=profile!=null?profile.legCount():2;
        if(unit.type==UnitTypes.crawler)require(!profile.legged()&&profile.wheelCount()==4,"Crawler keeps four intentional wheels");
        else if(unit instanceof Legsc){
            leggedCount++;require(profile!=null&&profile.legged(),"every actual Legsc has grounded profile");
            require(count==intentionalCount(unit.type),"native or intentional leg count preserved");
            for(int leg=0;leg<count;leg++)require(Double.isFinite(family.footHeight(unit,leg)),"finite terrain foot pose");
        }else{mechCount++;require(unit instanceof Mechc&&(unit.type==UnitTypes.vela?profile!=null&&profile.legCount()==2:profile==null),"whole-leg mech adapter preserves ordinary movement; Vela has two stance feet");
            if(profile!=null)for(int leg=0;leg<2;leg++)require(Double.isFinite(family.footHeight(unit,leg)),"Vela shared bilateral physical foot pose");
        }
        report.append("- ").append(NativeLimbRenderer.assetSummary(unit)).append("; legs=").append(count).append("; bodyHalfDepth=").append(GroundFamilyRenderer.bodyHalfDepth(unit));
        if(unit instanceof Legsc)report.append("; nativeLengthTiles=").append(unit.type.legLength/Vars.tilesize).append("; footprintRadius=").append(NativeLegLayout.spread(unit.type,count)).append("; bodyZ=").append(pose.z());
        report.append('\n');
        for(int pass=0;pass<2;pass++){
            long builds=family.nativeLimbStats().builds();String[] names={"side","front","back","top"};
            for(int angle=0;angle<names.length;angle++){
                view(angle);renderer.render(camera);require(family.wasSubmitted(unit),unit.type.name+" visible "+names[angle]);
                if(pass==0)capture("walker-"+unit.type.name+"-"+names[angle]+".png");
            }
            if(pass==1)require(builds==family.nativeLimbStats().builds(),"native limb meshes reused across identical views");
        }
        require(unit.x==nativeX&&unit.y==nativeY&&unit.health==hp&&unit.elevation==elevation,"render leaves native unit state unchanged");
        require(fingerprint()==before,"render leaves block/floor/overlay unchanged");
        if(unit.type==UnitTypes.atrax||unit.type==UnitTypes.spiroct||unit.type==UnitTypes.arkyid||unit.type==UnitTypes.toxopid||unit.type==UnitTypes.corvus)
            report.append(LegPlateVisualChecks.run(renderer,altitudes,unit));
        if(unit.type==UnitTypes.vela&&velaControlCheck!=null)velaControlCheck.accept(unit);
        if(unit.type==UnitTypes.dagger)report.append("\n").append(DaggerModelVisualChecks.run(renderer,altitudes,unit)).append("\n\n");
        if(unit.type==UnitTypes.mace)report.append("\n").append(MaceModelVisualChecks.run(renderer,altitudes,unit)).append("\n\n");
        if(unit.type==UnitTypes.mace&&maceControlCheck!=null)maceControlCheck.accept(unit);
        if(unit.type==UnitTypes.fortress)report.append("\n").append(FortressModelVisualChecks.run(renderer,altitudes,unit)).append("\n\n");
        if(unit.type==UnitTypes.fortress&&fortressControlCheck!=null)fortressControlCheck.accept(unit);
        if(unit.type==UnitTypes.scepter)report.append("\n").append(ScepterModelVisualChecks.run(renderer,altitudes,unit)).append("\n\n");
        if(unit.type==UnitTypes.scepter&&scepterControlCheck!=null)scepterControlCheck.accept(unit);
        if(unit.type==UnitTypes.reign)report.append("\n").append(ReignModelVisualChecks.run(renderer,altitudes,unit)).append("\n\n");
        if(unit.type==UnitTypes.reign&&reignControlCheck!=null)reignControlCheck.accept(unit);
        if(ProceduralUnitModelProfile.greenVisual(unit.type)){
            report.append("\n").append(GreenModelVisualChecks.run(renderer,altitudes,unit)).append("\n\n");
            if(greenControlCheck!=null)greenControlCheck.accept(unit);
        }
    }
    /** Real solids under alternating feet; authoritative stance and planted foot state settle on native frames. */
    public void prepareExtension(){
        require(allDone&&!extensionPrepared,"all-family captures complete");spawn(UnitTypes.toxopid);current.rotation=150;
        var profile=GroundUnitProfiles.profile(current);
        for(int i=0;i<profile.legCount();i+=2){
            double x=current.x/(double)Vars.tilesize+NativeLegLayout.footX(current.type,i,profile.legCount(),current.rotation);
            double y=current.y/(double)Vars.tilesize+NativeLegLayout.footY(current.type,i,profile.legCount(),current.rotation);
            Tile tile=Vars.world.tile((int)Math.floor(x+.5),(int)Math.floor(y+.5));require(tile!=null&&tile.block()==Blocks.air,"owned empty foot support site");
            tile.setBlock(Blocks.copperWall,Vars.player.team(),0);supports.add(tile.build);
        }
        extensionPrepared=true;warmFrames=0;family.clear();renderer.invalidate();
    }
    public boolean extendedReady(){
        if(!extensionPrepared||warmFrames<90)return false;
        var pose=altitudes.groundFamily().pose(current);return pose!=null&&Math.abs(pose.z()-pose.desiredZ())<.08&&family.wasSubmitted(current);
    }
    public String verifyExtendedAndCapture(){
        require(extendedReady(),"terrain fixture settles through authoritative solver");long before=fingerprint();
        var pose=altitudes.unitState(current);var profile=GroundUnitProfiles.profile(current);require(!pose.flying(),"long extension remains grounded");
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(int i=0;i<profile.legCount();i++){double foot=family.footHeight(current,i);low=Math.min(low,foot);high=Math.max(high,foot);}
        require(Double.isFinite(low+high)&&high>low+.15&&pose.z()>high+.2,"native feet plant separately on actual wall tops and ground below tall body");
        for(int angle=0;angle<3;angle++){view(angle);renderer.render(camera);require(family.wasSubmitted(current),"extended rig submitted");capture("walker-toxopid-terrain-"+angle+".png");}
        require(fingerprint()==before,"extended render leaves support map unchanged");
        return "PASS: Toxopid authoritative tall stance, eight textured native chains, turned body, alternating physical wall/terrain support heights; no render-only body offset";
    }
    private void spawn(UnitType type){
        if(current!=null&&current.isAdded())current.remove();
        current=type.create(Vars.player.team());current.set((site.x+13)*Vars.tilesize,(site.y+13)*Vars.tilesize);
        current.rotation=80;current.controller(new Stationary());current.add();altitudes.pose(current);warmFrames=0;velaWalkFrames=0;velaWalking=false;java.util.Arrays.fill(velaSwung,false);
    }
    private void view(int angle){
        var pose=altitudes.unitState(current);require(pose!=null,"canonical native body pose");
        double span=Math.max(1.2,Math.max(SpriteExtrusionRenderer.bodyRadius(current),GroundFamilyRenderer.horizontalRadius(current)/1.4));
        double center=Math.max(.2,pose.z()*.52),distance=Math.max(span*2.7+pose.z()*.65,pose.z()*1.8+span),yaw=Math.toRadians(current.rotation+(angle==0?90:angle==2?180:0));
        camera.up.set(0,1,0);camera.fov=44;
        if(angle==3)camera.position.set((float)pose.x(),(float)(pose.z()+span*3.15+2),(float)-pose.y()+.01f);
        else camera.position.set((float)(pose.x()+Math.cos(yaw)*distance),(float)(center+span*.5+pose.z()*.1),(float)(-pose.y()-Math.sin(yaw)*distance));
        camera.lookAt((float)pose.x(),(float)(angle==3?pose.z():center),(float)-pose.y());camera.update();
    }
    private static int intentionalCount(UnitType type){return switch(type.name){case "atrax"->4;case "spiroct","arkyid"->6;case "toxopid"->8;default->type.legCount;};}
    private Fi output(){return new Fi(System.getProperty("fpv.smoke.output"));}
    private void capture(String name){ScreenUtils.saveScreenshot(output().child(name));}
    private long fingerprint(){long value=17;for(Tile tile:Vars.world.tiles)value=value*31+tile.blockID()*71L+tile.floorID()*17L+tile.overlayID();return value;}
    private static Tile findSite(){
        for(int y=44;y+27<Vars.world.height();y++)for(int x=44;x+27<Vars.world.width();x++){
            boolean okay=true;
            scan:for(int dy=0;dy<27;dy++)for(int dx=0;dx<27;dx++){Tile tile=Vars.world.tile(x+dx,y+dy);
                if(tile==null||tile.build!=null||tile.block()!=Blocks.air||tile.floor().solid||tile.floor().isLiquid){okay=false;break scan;}}
            if(okay)return Vars.world.tile(x,y);
        }
        return null;
    }
    @Override public void close(){
        if(tiles==Vars.world.tiles){
            if(current!=null&&current.isAdded())current.remove();if(flyer!=null&&flyer.isAdded())flyer.remove();
            for(var support:supports)if(support.tile!=null&&support.tile.build==support)support.tile.setBlock(Blocks.air);
        }
        supports.clear();family.clear();renderer.invalidate();
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Native walker visual smoke: "+message);}
    private static final class VelaWalking implements UnitController{
        private int ticks;
        private Unit unit;public Unit unit(){return unit;}public void unit(Unit value){unit=value;}
        public void updateUnit(){
            unit.elevation=0; // This fixture exercises grounded tripod steps, not the native obstacle boost.
            int phase=ticks++/90;
            if(phase==0)unit.vel.trns(unit.rotation,unit.speed());else if(phase==1)unit.vel.trns(unit.rotation,-unit.speed());
            else{unit.vel.setZero();unit.rotation+=phase==2?.60f:-.60f;}
            ((Mechc)unit).baseRotation(unit.rotation);unit.controlWeapons(false,false);
        }
    }
    private static final class Stationary implements UnitController{
        private Unit unit;public Unit unit(){return unit;}public void unit(Unit value){unit=value;}
        public void updateUnit(){unit.vel.setZero();unit.controlWeapons(false,false);}
    }
}

