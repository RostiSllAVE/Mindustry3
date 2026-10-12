package fpv;

import arc.Core;
import arc.util.Time;
import fpv.construction.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import java.util.*;

/** Disposable actual SDL/native content fixture, driven through production configure/build APIs. */
final class XyzPowerSmokeChecks {
    private final FpvMod mod;
    private int checks;
    private final ArrayList<Integer> ground=new ArrayList<>();
    private final ArrayList<Unit> enemies=new ArrayList<>();
    private XyzPowerSmokeChecks(FpvMod mod){this.mod=mod;}
    static String run(FpvMod mod)throws Exception{
        var t=new XyzPowerSmokeChecks(mod);boolean free=Core.settings.getBool(LayerRuntime.FREE_PERMISSION),infinite=Vars.state.rules.infiniteResources,editor=Vars.state.rules.editor,fog=Vars.state.rules.fog;boolean enabled=mod.enabled,buildMode=mod.buildMode;float delta=Time.delta;Unit pilot=Vars.player.unit();float px=pilot.x,py=pilot.y;double pz=mod.world3d.unitState(pilot).z();
        try{
            Core.settings.put(LayerRuntime.FREE_PERMISSION,true);Vars.state.rules.editor=true;Vars.state.rules.fog=false;Time.delta=1;
            pilot.set(50*8,40*8);mod.unitAltitudes.initializePayloadRelease(pilot,.6);mod.resetPose();t.scenarios();
            return "PASS: "+t.checks+" scripted actual SDL checks: fueled vanilla RTG Z0 powers firing Lancer Z2 and consumer Z5, multiple producers/consumers, cross-floor battery charge/discharge, disconnect/destroy/reconnect, native Z0 graphs, XYZ limits, mouse preview/link/unlink, layered save roundtrip, cached topology. No manual hardware gameplay test.";
        }finally{t.clean();Core.settings.put(LayerRuntime.FREE_PERMISSION,free);Vars.state.rules.infiniteResources=infinite;Vars.state.rules.editor=editor;Vars.state.rules.fog=fog;Time.delta=delta;Unit current=Vars.player.unit();current.set(px,py);mod.unitAltitudes.initializePayloadRelease(current,pz);mod.setEnabled(enabled);if(enabled)mod.setBuildMode(buildMode);mod.resetPose();}
    }
    private void scenarios()throws Exception{
        clean();Building generator=ground(39,40,Blocks.rtgGenerator),lower=ground(42,40,Blocks.powerNodeLarge);
        generator.items.add(Items.thorium,20);lower.configureAny(generator.pos());
        long node=upper(43,40,2,Blocks.powerNodeLarge),gun=upper(46,40,2,Blocks.lancer),battery=upper(43,43,2,Blocks.battery);
        Vars.state.rules.infiniteResources=false;
        int copper=Vars.player.core().items.get(Items.copper),lead=Vars.player.core().items.get(Items.lead);
        var source=hit(LayerVerticalTransfers.groundId(42,40));var target=hit(node);
        var preview=MouseConnection.preview(mod.construction,source,target,null);check(preview.valid()&&preview.action()!=null,"direct cross-floor green connection preview: "+preview.reason());
        check(MouseConnection.submit(mod.construction,source,target,null).accepted(),"direct 3D mouse link accepted");
        check(Vars.player.core().items.get(Items.copper)==copper&&Vars.player.core().items.get(Items.lead)==lead,"ordinary power wire has no extra riser price");
        tick(1200);check(mod.construction.building(battery).power.status>0,"real Z0 RTG charges upper battery before turret combat");
        var victim=UnitTypes.dagger.create(Team.crux);victim.set(55*8,40*8);victim.health=victim.maxHealth=10000;victim.add();enemies.add(victim);Vars.state.teams.updateTeamStats();float hp=victim.health;
        tick(800);
        var turret=(Turret.TurretBuild)mod.construction.building(gun);Building stored=mod.construction.building(battery);
        check(generator.power.graph==turret.power.graph,"Scenario A generator Z0 -> node Z0 -> node Z2 -> actual turret Z2");
        check(turret.totalShots>0&&victim.health<hp,"elevated Lancer actually fires and damages using supplied electricity");
        check(turret.power.graph.getLastPowerNeeded()>0,"native turret consumption is accounted");
        check(generator.items.get(Items.thorium)<20,"legitimate generator consumed actual fuel");
        long high=upper(44,44,5,Blocks.powerNodeLarge),consumer=upper(46,44,5,Blocks.illuminator);
        Vars.state.rules.infiniteResources=false;
        check(mod.construction.requestLink(LayerVerticalTransfers.groundId(42,40),high,LayerVerticalTransfers.Kind.POWER).accepted(),"Scenario B valid diagonal Z0 to Z5");tick(50);
        check(mod.construction.building(consumer).power.status>.1&&generator.power.graph==mod.construction.building(consumer).power.graph,"legitimate Z5 consumer powered");
        Building upperGenerator=mod.construction.building(upper(40,44,5,Blocks.rtgGenerator));upperGenerator.items.add(Items.thorium,8);Vars.state.rules.infiniteResources=false;tick(40);
        check(generator.power.graph.producers.size==2,"two producers counted once across floors");
        double expected=generator.getPowerProduction()*generator.delta()+upperGenerator.getPowerProduction()*upperGenerator.delta();check(Math.abs(generator.power.graph.getLastPowerProduced()-expected)<.001,"native aggregate generation not duplicated");
        check(generator.power.graph.consumers.size>=2,"multiple native consumers share unified graph");
        long warm=mod.construction.nativeBuildings().powerRebuilds();tick(40);check(mod.construction.nativeBuildings().powerRebuilds()==warm,"no topology rebuild across unchanged steady-state frames");
        long far=upper(42,40,24,Blocks.powerNodeLarge);Vars.state.rules.infiniteResources=true;
        preview=MouseConnection.preview(mod.construction,hit(LayerVerticalTransfers.groundId(42,40)),hit(far),null);
        check(!preview.valid()&&preview.reason().contains("XYZ"),"red over-height preview explains native XYZ range");
        check(!mod.construction.requestLink(LayerVerticalTransfers.groundId(42,40),far,LayerVerticalTransfers.Kind.POWER).accepted(),"authority rejects same-XY beyond range");
        Vars.state.rules.infiniteResources=false;
        var snapshot=LayerRuntime.class.getDeclaredMethod("snapshotNative");snapshot.setAccessible(true);snapshot.invoke(mod.construction);byte[] saved=mod.construction.replication();
        float charge=stored.power.status;int fuel=upperGenerator.items.get(Items.thorium);var restore=LayerRuntime.class.getDeclaredMethod("restore",byte[].class,boolean.class);restore.setAccessible(true);restore.invoke(mod.construction,saved,false);
        check(mod.construction.nativeBuildings().linkedPower(LayerVerticalTransfers.groundId(42,40),node)&&mod.construction.nativeBuildings().linkedPower(LayerVerticalTransfers.groundId(42,40),high),"layered save restores both ground/elevated connections");
        stored=mod.construction.building(battery);check(Math.abs(stored.power.status-charge)<1e-6,"native saved battery energy retained");
        upperGenerator=mod.construction.nativeBuildings().entries().stream().map(LayerBuildingAdapter.Entry::building).filter(b->b.block==Blocks.rtgGenerator).findFirst().orElseThrow();check(upperGenerator.items.get(Items.thorium)==fuel,"native upper generator fuel state retained");tick(5);
        check(generator.power.graph==mod.construction.building(consumer).power.graph,"restored graph rejoins native Z0 source");
        check(MouseConnection.preview(mod.construction,hit(node),hit(LayerVerticalTransfers.groundId(42,40)),null).reason().contains("disconnect"),"linked pair offers disconnect regardless selection direction");
        check(MouseConnection.submit(mod.construction,hit(node),hit(LayerVerticalTransfers.groundId(42,40)),null).accepted(),"reverse mouse disconnect");tick(3);
        check(generator.power.graph!=mod.construction.building(gun).power.graph,"Scenario C disconnected Z2 graph splits");
        turret=(Turret.TurretBuild)mod.construction.building(gun);stored=mod.construction.building(battery);float before=stored.power.status;tick(120);
        check(before>0&&stored.power.status<before,"energy previously charged by actual generator discharges into isolated native consumers");
        turret.target=null;stored.power.status=0;tick(30);
        check(turret.power.status==0,"no alternate path/empty battery means elevated turret has no power");
        check(MouseConnection.submit(mod.construction,hit(LayerVerticalTransfers.groundId(42,40)),hit(node),null).accepted(),"mouse reconnect");tick(10);
        check(mod.construction.building(gun).power.graph==generator.power.graph,"reconnect restores normal supply");
        Vars.world.tile(42,40).setAir();snapshot.invoke(mod.construction);check(mod.construction.transfers().links().isEmpty(),"saving immediately after destruction prunes dangling power endpoints without simulating extra energy");tick(20);
        check(mod.construction.transfers().links().isEmpty(),"destroyed transmitting node removes both saved wires");
        check(mod.construction.building(gun).power.graph!=generator.power.graph,"destruction separates ground generator from elevated consumers");
        // A disconnection must remain disconnected after restore; creation auto-links cannot resurrect it.
        var a=mod.construction.nativeBuildings().powerLinks().stream().filter(l->!l.vertical()).findFirst().orElseThrow();
        check(mod.construction.requestUnlink(a.first(),a.second(),LayerVerticalTransfers.Kind.POWER).accepted(),"manual removal of same-floor automatic power link");snapshot.invoke(mod.construction);saved=mod.construction.replication();restore.invoke(mod.construction,saved,false);
        check(!mod.construction.nativeBuildings().linkedPower(a.first(),a.second()),"explicit missing link preserved after save/load");
        clean();Building base=ground(60,40,Blocks.rtgGenerator),n=ground(63,40,Blocks.powerNodeLarge),b=ground(63,43,Blocks.battery);base.items.add(Items.thorium,5);n.configureAny(base.pos());n.configureAny(b.pos());Vars.state.rules.infiniteResources=false;tick(20);
        check(base.power.graph==b.power.graph&&b.power.status>0,"ordinary Z0-only power unchanged");
        Vars.player.unit().set(33*8,45*8);mod.resetPose();Vars.player.core().items.add(Items.copper,1000);Vars.player.core().items.add(Items.lead,1000);
        long lo=upper(30,45,1,Blocks.solarPanel),hi=upper(30,45,3,Blocks.battery);Vars.state.rules.infiniteResources=false;var riser=mod.construction.requestLink(lo,hi,LayerVerticalTransfers.Kind.POWER);check(riser.accepted(),"legacy aligned upper power riser accepted: "+riser.reason());tick(15);check(mod.construction.building(lo).power.graph==mod.construction.building(hi).power.graph,"legacy riser joins same native electrical solver");
        legacyMigration(lo,hi);nativeSave();
    }
    private void legacyMigration(long lo,long hi)throws Exception{
        long lower=upper(32,45,1,Blocks.powerNode),far=upper(32,45,12,Blocks.powerNode);Vars.state.rules.infiniteResources=false;
        var snapshot=LayerRuntime.class.getDeclaredMethod("snapshotNative");snapshot.setAccessible(true);snapshot.invoke(mod.construction);byte[] source=mod.construction.replication();
        java.nio.ByteBuffer header=java.nio.ByteBuffer.wrap(source);int gridLength=header.getInt(9),linkOffset=13+gridLength+8+4,linkCount=header.getInt(linkOffset);
        check(header.getInt(13+gridLength+8)==0,"legacy migration fixture has no falling debris");
        int tail=source.length-4-17*mod.construction.nativeBuildings().powerLinks().size();
        var buffer=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(buffer);out.writeInt(4);out.write(source,4,linkOffset-4);out.writeInt(linkCount+1);out.write(source,linkOffset+4,linkCount*17);out.writeLong(lower);out.writeLong(far);out.writeByte(LayerVerticalTransfers.Kind.POWER.ordinal());out.write(source,linkOffset+4+linkCount*17,tail-linkOffset-4-linkCount*17);
        var restore=LayerRuntime.class.getDeclaredMethod("restore",byte[].class,boolean.class);restore.setAccessible(true);restore.invoke(mod.construction,buffer.toByteArray(),false);
        check(mod.construction.grid().get(lower)!=null&&mod.construction.grid().get(far)!=null&&!mod.construction.nativeBuildings().linkedPower(lower,far),"out-of-range legacy wire removed without losing old saved structures");
        check(mod.construction.nativeBuildings().linkedPower(lo,hi),"valid legacy riser retained on format4 migration");
    }
    private void nativeSave()throws Exception{
        long node=upper(63,40,2,Blocks.powerNodeLarge),consumer=upper(66,40,2,Blocks.illuminator); // Retain the real ground RTG, node and battery fixture.
        check(mod.construction.requestLink(LayerVerticalTransfers.groundId(63,40),node,LayerVerticalTransfers.Kind.POWER).accepted(),"native save scene power link");Vars.state.rules.infiniteResources=false;tick(20);
        Building upperGenerator=mod.construction.building(upper(60,40,2,Blocks.rtgGenerator));upperGenerator.items.add(Items.thorium,6);
        Building lowerGenerator=Vars.world.tile(60,40).build,lowerBattery=Vars.world.tile(63,43).build;lowerGenerator.enabled=false;lowerBattery.power.status=0;Vars.state.rules.infiniteResources=false;tick(30);
        check(lowerGenerator.getPowerProduction()==0&&upperGenerator.getPowerProduction()>0&&lowerBattery.power.status>0,"sole elevated fuel generator charges native lower-floor battery");
        var camera=new arc.graphics.g3d.Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.fov=65;camera.near=.05f;camera.far=500;camera.position.set(73,15,-30);camera.lookAt(63,3,-41);camera.update();mod.renderer3d.render(camera);
        check(arc.graphics.Gl.getError()==0,"real XYZ power connection renderer has no GL errors");
        var output=new arc.files.Fi(System.getProperty("fpv.smoke.output"));arc.util.ScreenUtils.saveScreenshot(output.child("xyz-power-network.png"));
        var file=output.child("xyz-power-roundtrip.msav");mindustry.io.SaveIO.write(file);check(mindustry.io.SaveIO.isSaveValid(file),"real full native save with XYZ power links");
        float energy=Vars.world.tile(63,43).build.power.status;
        mindustry.io.SaveIO.load(file);Vars.state.set(mindustry.core.GameState.State.playing);
        check(mod.construction.nativeBuildings().linkedPower(LayerVerticalTransfers.groundId(63,40),node),"full native file load restores ground-to-upper wire");
        check(Math.abs(Vars.world.tile(63,43).build.power.status-energy)<1e-6,"full native file load preserves ground battery energy");tick(5);
        check(mod.construction.building(consumer).power.status>.5&&mod.construction.building(consumer).power.graph==Vars.world.tile(60,40).build.power.graph,"full native file load restores actual electrical operation");
    }
    private long upper(int x,int y,int floor,Block block){Vars.state.rules.infiniteResources=true;var r=mod.construction.requestPlace(x,y,floor,block.name,false,0,LayerComponent.Mode.FREE);check(r.accepted(),"upper fixture "+block.name+" Z"+floor+": "+r.reason());return r.componentId();}
    private Building ground(int x,int y,Block block){Vars.world.tile(x,y).setBlock(block,Team.sharded,0);ground.add(Vars.world.tile(x,y).pos());return Vars.world.tile(x,y).build;}
    private BuildingMousePicker.Hit hit(long id){Building b=mod.construction.building(id);int layer=id>0?mod.construction.grid().get(id).layer():0;double z=mod.construction.planeHeight(layer),x=b.x/8d,y=b.y/8d,h=b.block.size*.5;return new BuildingMousePicker.Hit(id,b,layer,x,y,z+.3,new LayerCoordinates.Bounds(x-h,y-h,z,x+h,y+h,z+.6),false);}
    private void tick(int frames){for(int i=0;i<frames;i++){Time.update();mod.combat.update(1d/60);for(int pos:ground){var b=Vars.world.build(pos);if(b!=null)b.update();}for(var graph:Groups.powerGraph.copy())graph.update();mod.construction.update(1d/60);for(var bullet:Groups.bullet.copy())if(bullet.isAdded())bullet.update();}}
    private void clean(){for(Unit u:enemies)u.remove();enemies.clear();for(var b:Groups.bullet.copy())b.remove();mod.construction.reset();for(int pos:ground){var t=Vars.world.tile(pos);if(t!=null&&t.build!=null)t.setAir();}ground.clear();}
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}
