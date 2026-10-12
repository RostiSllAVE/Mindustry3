package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.content.UnitTypes;
import mindustry.entities.Puddles;
import mindustry.gen.Building;
import mindustry.gen.Puddle;
import mindustry.gen.Unit;
import mindustry.type.weapons.RepairBeamWeapon;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.units.RepairTower;
import mindustry.world.blocks.units.RepairTurret;

import java.util.ArrayList;

/** Native transfers/healing and actual completed rendering; owns only fixtures in the isolated lab. */
public final class NativeSupportEffectsSmokeChecks {
    private NativeSupportEffectsSmokeChecks(){}
    public static String verify(FpvWorldRenderer renderer,UnitAltitudeSystem altitudes){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"loaded isolated lab required");
        Tile site=findSite();require(site!=null,"empty dry 18 by 14 support fixture area");
        var owned=new ArrayList<Building>();var units=new ArrayList<Unit>();
        Tile lake=Vars.world.tile(site.x+15,site.y+1);var originalFloor=lake.floor();
        float delta=Time.delta;
        try{
            Time.delta=1;
            Conduit.ConduitBuild leaking=(Conduit.ConduitBuild)place(site,1,1,Blocks.conduit,owned);
            Conduit.ConduitBuild armored=(Conduit.ConduitBuild)place(site,4,1,Blocks.platedConduit,owned);
            Conduit.ConduitBuild connected=(Conduit.ConduitBuild)place(site,7,1,Blocks.conduit,owned);
            Conduit.ConduitBuild receiver=(Conduit.ConduitBuild)place(site,8,1,Blocks.conduit,owned);
            Conduit.ConduitBuild blocked=(Conduit.ConduitBuild)place(site,11,1,Blocks.conduit,owned);
            place(site,12,1,Blocks.copperWall,owned);
            Conduit.ConduitBuild lakePipe=(Conduit.ConduitBuild)place(site,14,1,Blocks.conduit,owned);lake.setFloor(Blocks.water.asFloor());
            for(var pipe:new Conduit.ConduitBuild[]{leaking,armored,connected,blocked,lakePipe}){
                pipe.liquids.add(Liquids.water,pipe.block.liquidCapacity*.8f);
                pipe.smoothLiquid=.8f;
                pipe.moveLiquidForward(((Conduit)pipe.block).leaks,Liquids.water);
            }
            Puddle pool=Puddles.get(leaking.tile.nearby(0));
            require(pool!=null&&pool.liquid==Liquids.water&&pool.amount>0,"native open conduit deposits real water");
            require(Puddles.get(armored.tile.nearby(0))==null,"native armored conduit retains liquid");
            require(receiver.liquids.get(Liquids.water)>0&&Puddles.get(connected.tile.nearby(0))==null,"native connected conduit transfers into receiver");
            require(Puddles.get(blocked.tile.nearby(0))==null,"native solid front blocks leak");
            require(Puddles.get(lake)==null&&lakePipe.liquids.currentAmount()<lakePipe.block.liquidCapacity*.4f,"native water-floor outflow consumes liquid without fabricating a puddle");

            RepairTurret.RepairPointBuild point=(RepairTurret.RepairPointBuild)place(site,2,5,Blocks.repairPoint,owned);
            RepairTurret.RepairPointBuild turret=(RepairTurret.RepairPointBuild)place(site,7,5,Blocks.repairTurret,owned);
            RepairTower.RepairTowerBuild tower=(RepairTower.RepairTowerBuild)place(site,13,5,Blocks.unitRepairTower,owned);
            Unit one=unit(site,3,8,UnitTypes.zenith,units),two=unit(site,8,8,UnitTypes.zenith,units),three=unit(site,14,9,UnitTypes.zenith,units);
            for(Unit target:new Unit[]{one,two,three})target.health=50;
            point.target=one;turret.target=two;
            for(var repair:new RepairTurret.RepairPointBuild[]{point,turret}){
                repair.rotation=repair.angleTo(repair.target);repair.enabled=true;repair.efficiency=repair.potentialEfficiency=1;
                repair.timer.reset(((RepairTurret)repair.block).timerTarget,0);
                float before=repair.target.health;
                for(int i=0;i<12;i++)repair.updateTile();
                require(repair.target!=null&&repair.target.health>before&&repair.strength>.1,"native "+repair.block.name+" heals actual unit");
            }
            tower.enabled=true;tower.efficiency=tower.potentialEfficiency=1;tower.targets.add(three);tower.refresh=-999;
            float towerBefore=three.health;
            for(int i=0;i<12;i++)tower.updateTile();
            require(three.health>towerBefore&&tower.warmup>.1,"native area repair tower heals actual target");

            Unit support=unit(site,12,11,UnitTypes.retusa,units);support.rotation=0;
            RepairBeamWeapon.HealBeamMount heal=null;
            for(var mount:support.mounts)if(mount instanceof RepairBeamWeapon.HealBeamMount found){heal=found;break;}
            require(heal!=null,"native Retusa provides repair-beam mount");
            RepairBeamWeapon weapon=(RepairBeamWeapon)heal.weapon;
            heal.target=three;heal.shoot=true;heal.aimX=three.x;heal.aimY=three.y;
            support.rotation=support.angleTo(three);heal.rotation=0;heal.reload=0;heal.retarget=999;
            float unitBefore=three.health;
            for(int i=0;i<24;i++)weapon.update(support,heal);
            require(heal.target!=null&&heal.strength>.1&&heal.shoot&&three.health>unitBefore,"native repair-beam weapon heals target");
            var serverState=new SupportEffectsState();var snapshot=serverState.snapshot();
            require(snapshot.stream().anyMatch(e->e.kind()==SupportEffectsState.TURRET&&e.sourceRef()==point.pos()&&e.targetRef()==point.target.id),"server captures real native repair target identity");
            require(snapshot.stream().anyMatch(e->e.kind()==SupportEffectsState.UNIT&&e.sourceRef()==support.id),"server captures native unit repair mount");
            require(snapshot.stream().anyMatch(e->e.kind()==SupportEffectsState.TOWER&&e.sourceRef()==tower.pos()),"server captures active area repair pulse");
            require(SupportEffectsCodec.decode(SupportEffectsCodec.encode(snapshot)).equals(snapshot),"server healing state survives exact bounded wire roundtrip");
            serverState.applySnapshot(snapshot);require(serverState.remote().equals(snapshot),"client visual state accepts immutable server snapshot");
            serverState.applySnapshot(java.util.List.of());require(serverState.remote().isEmpty(),"empty snapshot retires stale healing effects");

            Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());
            camera.position.set(site.x+25,24,-site.y+20);camera.up.set(0,1,0);camera.lookAt(site.x+8,3,-site.y-6);
            camera.fov=48;camera.near=.05f;camera.far=1000;camera.update();
            float pointHp=one.health,turretHp=two.health,otherHp=three.health,water=leaking.liquids.currentAmount(),poolAmount=pool.amount;
            float strength=point.strength,unitStrength=heal.strength;
            renderer.render(camera);
            SupportEffectsRenderer effects=renderer.supportEffects();
            require(effects.wasLeakSubmitted(leaking)&&effects.stats().puddles()>0,"actual GPU outflow and native puddle submission");
            require(effects.wasLeakSubmitted(lakePipe),"outflow into native water floor renders without requiring a ground puddle");
            require(!effects.wasLeakSubmitted(armored)&&!effects.wasLeakSubmitted(connected)&&!effects.wasLeakSubmitted(blocked),"armored/connected/blocked ends cannot emit phantom streams");
            require(effects.wasHealingSubmitted(point)&&effects.wasHealingSubmitted(turret),"both native repair building families render healing beams");
            require(effects.wasHealingSubmitted(support)&&effects.wasTowerSubmitted(tower),"unit healing beam and area repair pulse render");
            var endpoint=effects.healingBeam(point);var actual=altitudes.unitState(point.target);
            require(endpoint!=null&&actual!=null&&Math.abs(endpoint.bz()-actual.z())<1e-8&&endpoint.bz()>endpoint.az()+.5,"repair beam reaches actual flying target altitude");
            require(one.health==pointHp&&two.health==turretHp&&three.health==otherHp&&water==leaking.liquids.currentAmount()&&pool.amount==poolAmount&&point.strength==strength&&heal.strength==unitStrength,
                "support rendering leaves native HP/liquid/puddle and beam strength unchanged");
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child("support-effects.png"));
            camera.position.set(site.x+5,4,-site.y+3);camera.lookAt(site.x+1.5f,.2f,-site.y-1);camera.fov=45;camera.update();
            renderer.render(camera);
            require(effects.wasLeakSubmitted(leaking),"close FPV outflow remains submitted");
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child("liquid-outflow.png"));
            camera.position.set(site.x+25,24,-site.y+20);camera.lookAt(site.x+8,3,-site.y-6);camera.fov=48;camera.update();

            point.efficiency=0;turret.target=null;heal.shoot=false;tower.efficiency=0;
            leaking.liquids.clear();renderer.render(camera);
            require(!effects.wasHealingSubmitted(point)&&!effects.wasHealingSubmitted(turret)&&!effects.wasHealingSubmitted(support)&&!effects.wasTowerSubmitted(tower),"inactive or missing target removes support visuals");
            require(!effects.wasLeakSubmitted(leaking)&&effects.stats().puddles()>0,"drained conduit stops jet but real residual pool remains");
            leaking.tile.setBlock(Blocks.air);pool.remove();renderer.render(camera);
            require(!effects.wasLeakSubmitted(leaking)&&effects.stats().puddles()==0,"native removal invalidates cached conduit and puddle identities");
            return "PASS: native water transfer/leak and armored/connected/blocked exclusions; residual pool/removal; RepairPoint, RepairTurret, RepairTower and Retusa actual healing; XYZ beam GPU submissions and immutable native state";
        }finally{
            Time.delta=delta;
            for(Unit unit:units)if(unit.isAdded())unit.remove();
            for(Building build:owned)if(build.tile!=null&&build.tile.build==build){build.noSleep();build.tile.setBlock(Blocks.air);}
            lake.setFloor(originalFloor);
            for(int y=0;y<14;y++)for(int x=0;x<18;x++){Puddle p=Puddles.get(Vars.world.tile(site.x+x,site.y+y));if(p!=null)p.remove();}
            renderer.invalidate();
        }
    }
    private static Building place(Tile site,int dx,int dy,Block type,ArrayList<Building> owned){
        Tile tile=Vars.world.tile(site.x+dx,site.y+dy);tile.setBlock(type,Vars.player.team(),0);owned.add(tile.build);return tile.build;
    }
    private static Unit unit(Tile site,int dx,int dy,mindustry.type.UnitType type,ArrayList<Unit> owned){
        Unit unit=type.create(Vars.player.team());unit.set((site.x+dx)*Vars.tilesize,(site.y+dy)*Vars.tilesize);unit.add();owned.add(unit);return unit;
    }
    private static Tile findSite(){
        for(int y=1;y+14<Vars.world.height();y++)for(int x=1;x+18<Vars.world.width();x++){
            boolean clear=true;
            scan:for(int dy=0;dy<14;dy++)for(int dx=0;dx<18;dx++){
                Tile tile=Vars.world.tile(x+dx,y+dy);
                if(tile==null||tile.build!=null||tile.block()!=Blocks.air||tile.floor().solid||tile.floor().isLiquid||Puddles.get(tile)!=null){clear=false;break scan;}
            }
            if(clear)return Vars.world.tile(x,y);
        }
        return null;
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Support effects smoke: "+message);}
}
