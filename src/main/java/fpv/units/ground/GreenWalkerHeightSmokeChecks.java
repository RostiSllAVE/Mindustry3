package fpv.units.ground;

import arc.Core;
import arc.util.Time;
import mindustry.content.Blocks;
import mindustry.world.Tile;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.Unit;
import mindustry.type.UnitType;

/** Actual native green T4/T5 bodies, stance controls and physical support in the isolated empty lab. */
public final class GreenWalkerHeightSmokeChecks {
    private GreenWalkerHeightSmokeChecks(){}
    public static String run(World3DAdapter world,UnitAltitudeSystem altitudes){
        require(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active(),"isolated native lab required");
        String key="fpv-green-walker-height-percent";boolean had=Core.settings.has(key);Object saved=Core.settings.get(key,null);
        var previous=GroundUnitProfiles.setNativeStepMultiplierSupplier(()->1.5);
        Unit unit=null;Tile wall=null;float oldDelta=Time.delta;Time.delta=1;
        Unit playerUnit=Vars.player.unit();boolean playerBoosting=Vars.player.boosting;
        try{
            Unit untouched=UnitTypes.quasar.create(Vars.player.team());require(GroundUnitProfiles.profile(untouched)==null,"lower-tier green mech unchanged");
            Unit cler=UnitTypes.cleroi.create(Vars.player.team());
            Core.settings.put(key,100);GroundUnitProfiles.refresh();double unrelated=GroundUnitProfiles.profile(cler).maximumClearance();
            for(UnitType type:new UnitType[]{UnitTypes.vela,UnitTypes.corvus}){
                unit=type.create(Vars.player.team());unit.set(62*Vars.tilesize,34*Vars.tilesize);unit.rotation=0;unit.add();
                Core.settings.put(key,100);GroundUnitProfiles.refresh();var baseline=GroundUnitProfiles.profile(unit);
                require(baseline!=null&&baseline.legCount()==(type==UnitTypes.vela?2:4),"native leg capability retained for "+type.name);
                Core.settings.put(key,300);GroundUnitProfiles.refresh();var tall=GroundUnitProfiles.profile(unit);
                require(Math.abs(tall.maximumClearance()-baseline.maximumClearance()*3)<1e-9,"exact threefold total maximum for "+type.name);
                require(tall.normalStance()==baseline.normalStance()&&tall.thicknessMultiplier()==baseline.thicknessMultiplier(),"unextended stance and body hull unchanged");
                for(int i=0;i<150;i++){altitudes.setGroundStanceControl(unit,true,1,.1);altitudes.update(.1);}
                var body=altitudes.unitState(unit);var pose=altitudes.groundFamily().pose(unit);var stance=altitudes.groundFamily().stance(unit);
                require(!body.flying()&&!unit.isFlying(),"manual height never grants flight");
                require(Math.abs(stance.desired(tall)-tall.maximumClearance())<1e-6,"Q input stops at new maximum");
                require(Math.abs(body.z()-body.halfHeight()-pose.supportHeight()-tall.maximumClearance())<.08,"actual body reaches supported maximum");
                require(Math.abs(pose.speedFraction()-tall.minSpeedFraction())<.001,"full extension still slows native displacement");
                double before=unit.x;unit.set(unit.x+Vars.tilesize,unit.y);altitudes.update(.1);
                require(unit.x-before<Vars.tilesize*.4,"extension penalty applies to actual XY movement");
                for(int i=0;i<tall.legCount();i++){
                    double fx=GroundUnitProfiles.extensibleMech(unit)?NativeMechLayout.footX(type,i,unit.rotation):NativeLegLayout.footX(type,i,tall.legCount(),unit.rotation);
                    double fy=GroundUnitProfiles.extensibleMech(unit)?NativeMechLayout.footY(type,i,unit.rotation):NativeLegLayout.footY(type,i,tall.legCount(),unit.rotation);
                    require(Math.abs(world.surfaceHeight(body.x()+fx,body.y()+fy)-pose.supportHeight())<.01,"feet remain on real local support");
                }
                if(type==UnitTypes.vela){
                    wall=Vars.world.tile(68,34);wall.setBlock(Blocks.copperWall,Vars.player.team(),0);
                    for(int tick=0;tick<1800&&unit.x/Vars.tilesize<71;tick++){
                        unit.vel.set(.7f,0);altitudes.captureGroundIntent(1d/60);unit.update();altitudes.update(1d/60);
                        require(!unit.isFlying()&&!altitudes.unitState(unit).flying(),"supported wall step cannot turn Vela into an aircraft");
                    }
                    require(unit.x/Vars.tilesize>70,"real native Vela updates traverse a solid wall while retaining grounded height (x="+(unit.x/Vars.tilesize)+", y="+(unit.y/Vars.tilesize)+", elevation="+unit.elevation+", controller="+unit.controller().getClass().getSimpleName()+", bottom="+(altitudes.unitState(unit).z()-altitudes.unitState(unit).halfHeight())+", blocked="+altitudes.groundFamily().pose(unit).blocked()+")");
                    unit.set(62*Vars.tilesize,34*Vars.tilesize);altitudes.seedGroundFromAir(unit,body.z());unit.elevation=0;
                    Vars.player.unit(unit);Vars.player.boosting=false;
                    for(int tick=0;tick<1800&&unit.x/Vars.tilesize<71;tick++){
                        unit.vel.set(.7f,0);altitudes.captureGroundIntent(1d/60);Vars.player.update();unit.update();altitudes.update(1d/60);
                        require(unit.elevation<=.001f&&!altitudes.unitState(unit).flying(),"real native player auto-rise cannot convert a supported Vela into flight");
                    }
                    require(unit.x/Vars.tilesize>70,"real Player.update and Unit.update retain grounded wall traversal");
                    Vars.player.unit(playerUnit);Vars.player.boosting=playerBoosting;
                    unit.set(wall.worldx(),wall.worldy());altitudes.seedGroundFromAir(unit,body.z());unit.elevation=0;unit.vel.setZero();
                    altitudes.captureGroundIntent(1d/60);unit.update();
                    require(unit.elevation>=.999f,"stationary native Vela really attempts solid-tile automatic boost");
                    altitudes.update(1d/60);
                    require(!unit.isFlying()&&!altitudes.unitState(unit).flying(),"physical supported stance cancels only native automatic boost");
                    verifySupportedWeapon(unit,altitudes);
                    var command=new mindustry.ai.types.CommandAI();unit.controller(command);command.command(mindustry.ai.UnitCommand.moveCommand);command.stances.set(mindustry.ai.UnitStance.boost.id);
                    altitudes.captureGroundIntent(1d/60);unit.update();altitudes.update(1d/60);
                    require(unit.isFlying()&&altitudes.unitState(unit).flying(),"explicit native boost remains available");
                    unit.elevation=0;wall.setBlock(Blocks.air);wall=null;
                }
                unit.remove();unit=null;
            }
            require(GroundUnitProfiles.profile(cler).maximumClearance()==unrelated,"unrelated articulated walkers do not gain multiplier");
            require(GroundUnitProfiles.profile(UnitTypes.flare.create(Vars.player.team()))==null,"flyers unchanged");
            return "PASS: Vela native two-foot mech and Corvus four-leg walker use exactly 3x configured total clearance, unchanged hull/minimum stance, physical ground feet, manual clamp, real movement slowdown, native AI/player solid-wall traversal without false auto-boost, actual charged Vela beam over a wall, explicit boost and its firing restriction preserved, other tiers/flyers excluded";
        }finally{if(Vars.player.unit()!=playerUnit)Vars.player.unit(playerUnit);Vars.player.boosting=playerBoosting;if(unit!=null&&unit.isAdded())unit.remove();if(wall!=null)wall.setBlock(Blocks.air);Time.delta=oldDelta;if(had)Core.settings.put(key,saved);else Core.settings.remove(key);GroundUnitProfiles.setNativeStepMultiplierSupplier(previous);GroundUnitProfiles.refresh();}
    }
    private static void verifySupportedWeapon(Unit unit,UnitAltitudeSystem altitudes){
        Unit prior=Vars.player.unit();boolean boost=Vars.player.boosting,shoot=Vars.player.shooting;float time=Time.time;
        try{
            Vars.player.unit(unit);Vars.player.boosting=false;unit.elevation=0;boolean beam=false;
            for(int tick=0;tick<240&&!beam;tick++){
                unit.vel.setZero();unit.rotation=0;unit.aim(unit.x+120,unit.y);unit.controlWeapons(true,true);Vars.player.shooting=true;
                altitudes.captureGroundIntent(1d/60);Vars.player.update();unit.update();altitudes.update(1d/60);Time.update();
                for(var bullet:mindustry.gen.Groups.bullet)if(bullet.owner==unit&&bullet.isAdded())beam=true;
                require(!unit.isFlying()&&!altitudes.unitState(unit).flying(),"supported weapon update retains grounded body");
            }
            require(beam&&unit.mounts[0].totalShots>0,"actual native charged Vela beam fires while standing above a solid wall");
            removeShots(unit);var mount=unit.mounts[0];mount.bullet=null;mount.reload=0;mount.warmup=1;int count=mount.totalShots;
            Vars.player.boosting=true;unit.elevation=1;
            for(int tick=0;tick<15;tick++){
                unit.vel.setZero();unit.aim(unit.x+120,unit.y);unit.controlWeapons(true,true);
                altitudes.captureGroundIntent(1d/60);Vars.player.update();unit.update();altitudes.update(1d/60);Time.update();
            }
            require(unit.isFlying()&&mount.totalShots==count,"explicitly boosted Vela retains native firing prohibition");
        }finally{removeShots(unit);Vars.player.unit(prior);Vars.player.boosting=boost;Vars.player.shooting=shoot;unit.elevation=0;Time.time=time;}
    }
    private static void removeShots(Unit unit){
        var own=new java.util.ArrayList<mindustry.gen.Bullet>();for(var bullet:mindustry.gen.Groups.bullet)if(bullet.owner==unit)own.add(bullet);
        for(var bullet:own)bullet.remove();
    }
    private static void require(boolean value,String label){if(!value)throw new AssertionError("Green walker height: "+label);}
}
