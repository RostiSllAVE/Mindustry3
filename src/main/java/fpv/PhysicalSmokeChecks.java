package fpv;

import arc.Core;
import arc.util.Time;
import fpv.effects.RadialExplosion;
import fpv.flight.FlightController;
import fpv.flight.FlightProfile;
import fpv.flight.FlightSettings;
import fpv.flight.UnitMotionBridge;
import fpv.physics.CrashSystem;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import java.util.ArrayList;

/** Owned native fixtures for continuous XYZ ramming, isolated from the original play profile. */
final class PhysicalSmokeChecks implements AutoCloseable {
    private final FpvMod mod;
    private final ArrayList<Unit> owned=new ArrayList<>();
    private Tile wall;
    private Unit enemy,friend;
    PhysicalSmokeChecks(FpvMod mod){this.mod=mod;require(Boolean.getBoolean("fpv.smoke"),"lab only");}
    void setup(){
        var core=Vars.player.core();
        search:for(int dy=-15;dy<15;dy++)for(int dx=10;dx<25;dx++){
            var tile=Vars.world.tile(core.tileX()+dx,core.tileY()+dy);
            if(tile!=null&&tile.block()==Blocks.air&&tile.build==null){wall=tile;break search;}
        }
        require(wall!=null,"ram fixture site");wall.setBlock(Blocks.copperWall,Team.crux,0);
        enemy=spawn(UnitTypes.eclipse,Team.crux,wall.x+12,wall.y+12);
        friend=spawn(UnitTypes.flare,Vars.player.team(),wall.x-12,wall.y+12);
    }
    String run(){
        Object damage=Core.settings.get("fpv-blast-damage",null);boolean existed=Core.settings.has("fpv-blast-damage");
        Core.settings.put("fpv-blast-damage",0);
        try{
            arc.util.Log.info("[FPV-PHYSICAL] @",fpv.physics.CollisionResponseSmokeChecks.verify(mod.world3d,friend));
            var ep=mod.world3d.unitState(enemy);require(ep!=null&&ep.z()>0,"enemy has physical altitude");
            var effects=new RadialExplosion(mod.world3d);
            var crash=new CrashSystem((x,z)->0,effects,mod.flightProfile.metersPerTile);crash.setWorld(mod.world3d);
            double span=ep.radius()+3;
            Unit small=spawn(UnitTypes.flare,Vars.player.team(),ep.x()-span,ep.y());
            crash.setControlledMass(FlightProfile.baseMassKg(FlightSettings.metadata(small)));
            var state=state(small,ep.z()+30);state.x=ep.x()+span;state.vx=span*20;
            var miss=crash.resolve(small,state,ep.x()-span,ep.z()+30,-ep.y(),.1,true);
            require(!miss.hit()&&!crash.applyPending(),"same XY path above target misses in Z");
            state=state(small,ep.z());state.x=ep.x()+span;state.vx=span*20;
            float enemyBefore=enemy.health,selfBefore=small.health;
            crash.reset();var hit=crash.resolve(small,state,ep.x()-span,ep.z(),-ep.y(),.1,true);
            require(hit.hit()&&hit.normalSpeed()>20,"fast continuous airborne ram detected");
            reconcile(small,state);crash.applyPending();
            require(small.health<selfBefore&&enemy.health<enemyBefore,"ram damages both enemy participants");
            require(!effects.bursts().isEmpty(),"violent small-unit impact creates scaled original burst");
            double smallBlast=effects.bursts().get(0).spec.radius();

            var fp=mod.world3d.unitState(friend);span=fp.radius()+3;
            Unit friendlyProbe=spawn(UnitTypes.flare,Vars.player.team(),fp.x()-span,fp.y());
            state=state(friendlyProbe,fp.z());state.x=fp.x()+span;state.vx=span*20;
            float before=friend.health;crash.reset();
            require(crash.resolve(friendlyProbe,state,fp.x()-span,fp.z(),-fp.y(),.1,true).hit(),"friendly body remains physical");
            reconcile(friendlyProbe,state);crash.applyPending();
            require(friend.health==before,"outgoing ram respects friendly team protection");

            // Directly hit the real sleeping/non-updating building at high speed, not a fake height map.
            Unit probe=spawn(UnitTypes.flare,Vars.player.team(),wall.x-4,wall.y);
            state=state(probe,.65);state.x=wall.x+4;state.vx=80;
            crash.reset();float buildingBefore=wall.build.health;
            require(crash.resolve(probe,state,wall.x-4,.65,-wall.y,.1,true).hit(),"fast continuous placed-building ram detected");
            reconcile(probe,state);crash.applyPending();
            require(wall.build==null||wall.build.health<buildingBefore,"ram damages real enemy structure");

            Unit huge=spawn(UnitTypes.eclipse,Vars.player.team(),wall.x,wall.y);
            var bigEffects=new RadialExplosion(mod.world3d);
            var groundCrash=new CrashSystem((x,z)->0,bigEffects,mod.flightProfile.metersPerTile);
            groundCrash.setControlledMass(FlightProfile.baseMassKg(FlightSettings.metadata(huge)));
            state=state(huge,.1);state.vy=-200;
            groundCrash.resolve(huge,state,state.x,30,state.z,.1,true);reconcile(huge,state);groundCrash.applyPending();
            require(huge.dead&&!bigEffects.bursts().isEmpty(),"huge high-energy terrain impact is destructive");
            var blast=bigEffects.bursts().get(0).spec;
            require(blast.radius()>smallBlast&&blast.radius()<=40&&blast.damage()<=12000,"huge blast scales but remains bounded");
            return "passed: native airborne/structure swept rams, altitude miss, both-participant damage, friendly protection, small/huge bounded crash bursts";
        }finally{if(existed)Core.settings.put("fpv-blast-damage",damage);else Core.settings.remove("fpv-blast-damage");}
    }
    private Unit spawn(mindustry.type.UnitType type,Team team,double x,double y){
        Unit unit=type.create(team);unit.set((float)x*Vars.tilesize,(float)y*Vars.tilesize);
        unit.controller(new AIController(){@Override public void updateUnit(){}});unit.add();owned.add(unit);return unit;
    }
    private static FlightController.State state(Unit u,double height){var s=new FlightController.State();s.x=u.x/Vars.tilesize;s.z=-u.y/Vars.tilesize;s.y=height;return s;}
    private static void reconcile(Unit unit,FlightController.State desired){
        var motion=new UnitMotionBridge();var current=new FlightController.State();motion.syncFromSimulation(unit,current);
        float delta=Time.delta;
        try{Time.delta=1;motion.beforeSimulation(unit,desired,1);unit.update();motion.afterSimulation(unit,desired);}
        finally{Time.delta=delta;}
    }
    @Override public void close(){for(var unit:owned)if(unit.isAdded())unit.remove();owned.clear();if(wall!=null&&wall.block()==Blocks.copperWall)wall.setBlock(Blocks.air);}
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException("Physical smoke: "+label);arc.util.Log.info("[FPV-PHYSICAL] OK: @",label);}
}
