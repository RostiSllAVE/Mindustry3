package fpv.physics;

import arc.Core;
import fpv.effects.RadialExplosion;
import fpv.flight.FlightController;
import fpv.flight.FlightProfile;
import fpv.flight.FlightSettings;
import fpv.world.World3DAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Tile;

/** Own native bodies/structures: outgoing caps, directional order, actual death paths and custom AoE. */
public final class RamCapSmokeChecks {
    private RamCapSmokeChecks(){}
    private record PairLoss(double core,double ordinary,double neighbor){}
    public static String verify(World3DAdapter world){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"isolated loaded lab required");
        Tile site=site();require(site!=null,"empty native ram-cap area");
        String[] keys={"fpv-ram-strength","fpv-blast-damage","fpv-blast-radius","fpv-blast-sound","fpv-ram-cap-units","fpv-ram-cap-alpha","fpv-ram-cap-beta","fpv-ram-cap-gamma"};
        var old=new HashMap<String,Object>();for(String key:keys)if(Core.settings.has(key))old.put(key,Core.settings.get(key,null));
        Core.settings.put(keys[0],100);Core.settings.put(keys[1],100);Core.settings.put(keys[2],60);Core.settings.put(keys[3],false);
        Core.settings.put("fpv-ram-cap-units",3000);
        for(String key:new String[]{"fpv-ram-cap-alpha","fpv-ram-cap-beta","fpv-ram-cap-gamma"})Core.settings.put(key,0);
        int cases=0;
        try{
            for(UnitType type:new UnitType[]{UnitTypes.alpha,UnitTypes.beta,UnitTypes.gamma}){
                String key="fpv-ram-cap-"+type.name;
                var zero=pair(world,site,type,false,true,12);
                require(zero.ordinary==0&&zero.neighbor==0,"default "+type.name+" direct/custom/native-death outgoing zero");cases++;
                building(world,site,type,0);cases++;
                Core.settings.put(key,120);
                var limited=pair(world,site,type,false,true,12);
                require(limited.ordinary>0&&limited.ordinary<=120.02&&limited.neighbor>0&&limited.neighbor<=120.02,
                    type.name+" custom direct+blast <=120 per victim, real nearby AoE remains bounded");cases++;
                building(world,site,type,120);cases++;
                var forward=pair(world,site,type,false,false,12);
                var reverse=pair(world,site,type,true,false,12);
                require(Math.abs(forward.core-reverse.core)<.02&&Math.abs(forward.ordinary-reverse.ordinary)<.02,
                    type.name+" enemy-pair receiver caps and native armor independent of claiming solver");cases+=2;
                var weak=pair(world,site,type,false,false,2);
                require(weak.ordinary>0&&weak.ordinary<120,"weak "+type.name+" collision is not promoted to flat cap damage");cases++;
                Core.settings.put(key,0);
            }
            Core.settings.put("fpv-ram-cap-units",0);
            building(world,site,UnitTypes.flare,0);building(world,site,UnitTypes.crawler,0);cases+=2;
            require(UnitTypes.crawler.weapons.first().shootOnDeath,"native shared Crawler death weapon remains unchanged");
            Core.settings.put("fpv-ram-cap-units",120);building(world,site,UnitTypes.flare,120);cases++;
            return "PASS: "+cases+" actual native cases; Alpha/Beta/Gamma default0 and custom120 vs units/structures, shared direct+XYZ burst budget/nearby victims, cargo/wreck and Crawler death-shot suppression, weak impacts preserved, both solver orders agree; no live/shared type mutations";
        }finally{for(String key:keys)if(old.containsKey(key))Core.settings.put(key,old.get(key));else Core.settings.remove(key);}
    }
    private static PairLoss pair(World3DAdapter world,Tile site,UnitType coreType,boolean reverse,boolean die,double speed){
        ArrayList<Unit> owned=new ArrayList<>();
        Unit core=spawn(owned,coreType,Team.sharded,site.x+4,site.y+7),normal=spawn(owned,UnitTypes.eclipse,Team.crux,site.x+13,site.y+7);
        Unit source=reverse?normal:core,target=reverse?core:normal;
        normal.health=100000;core.health=die?.01f:100000;
        source.stack.item=Items.blastCompound;source.stack.amount=source.type.itemCapacity;
        Unit neighbor=null;
        if(die){neighbor=spawn(owned,UnitTypes.eclipse,Team.crux,normal.x/Vars.tilesize,normal.y/Vars.tilesize);neighbor.health=100000;}
        Groups.unit.tree().insert(target);
        try{
            var targetPose=world.unitState(target);var ownPose=world.unitState(source);
            double radius=Math.min(8,Math.max(.22,ownPose.radius()));
            var state=new FlightController.State();state.x=targetPose.x()-radius-targetPose.radius()-.01;
            state.y=targetPose.z();state.z=-targetPose.y();state.vx=speed;state.vy=targetPose.vz();
            source.set((float)state.x*Vars.tilesize,(float)-state.z*Vars.tilesize);
            double x=state.x,y=state.y,z=state.z;state.x+=speed*.1;state.y+=state.vy*.1;
            var effects=new RadialExplosion(world);var crash=new CrashSystem((a,b)->-100,effects,4);crash.setWorld(world);
            crash.setControlledMass(FlightProfile.baseMassKg(FlightSettings.metadata(source)));
            float beforeCore=core.health,beforeNormal=normal.health,beforeNeighbor=neighbor==null?0:neighbor.health;
            boolean oldFlag=source.spawnedByCore;var oldMounts=source.mounts;
            require(crash.resolve(source,state,x,y,z,.1,true).hit(),"real continuous pair contact");
            source.set((float)state.x*Vars.tilesize,(float)-state.z*Vars.tilesize);crash.applyPending();
            if(die){
                require(core.dead&&!effects.bursts().isEmpty(),"collision creates its own bounded death burst");
                require(effects.bursts().get(0).spec.damage()<=RamDamageSettings.cap(source),"custom burst obeys source cap");
                require(core.spawnedByCore,"collision-fatal core suppresses deferred native cargo/wreck damage");
                float health=normal.health,near=neighbor.health;if(core.isAdded())core.destroy();
                require(normal.health==health&&neighbor.health==near,"native destruction cannot add a second unbounded damage path");
            }else require(!source.dead&&source.spawnedByCore==oldFlag&&source.mounts==oldMounts,"surviving unit instance flags/mounts stay unchanged");
            return new PairLoss(beforeCore-Math.max(0,core.health),beforeNormal-normal.health,neighbor==null?0:beforeNeighbor-neighbor.health);
        }finally{Groups.unit.tree().remove(target);for(Unit unit:owned){Groups.unit.tree().remove(unit);if(unit.isAdded())unit.remove();}}
    }
    private static void building(World3DAdapter world,Tile site,UnitType type,double cap){
        Tile tile=Vars.world.tile(site.x+11,site.y+7);tile.setBlock(Blocks.titaniumWallLarge,Team.crux,0);var building=tile.build;
        ArrayList<Unit> owned=new ArrayList<>();Unit source=spawn(owned,type,Team.sharded,site.x+3,site.y+7);
        source.health=.01f;source.stack.item=Items.blastCompound;source.stack.amount=source.type.itemCapacity;
        Unit nearby=spawn(owned,UnitTypes.dagger,Team.crux,site.x+11,site.y+9);nearby.health=100000;
        var oldNeighborMounts=nearby.mounts;boolean oldNeighborFlag=nearby.spawnedByCore;
        try{
            double wx=building.x/Vars.tilesize,wy=building.y/Vars.tilesize;
            var state=new FlightController.State();state.x=wx+4;state.y=.65;state.z=-wy;state.vx=80;
            var effects=new RadialExplosion(world);var crash=new CrashSystem((a,b)->-100,effects,4);crash.setWorld(world);
            crash.setControlledMass(FlightProfile.baseMassKg(FlightSettings.metadata(source)));
            float before=building.health,beforeNear=nearby.health;
            require(crash.resolve(source,state,wx-4,.65,-wy,.1,true).hit(),"real native structure sweep: "+type.name);
            source.set((float)state.x*Vars.tilesize,(float)-state.z*Vars.tilesize);crash.applyPending();
            require(source.dead&&!effects.bursts().isEmpty(),"actual native collision death against structure");
            require(before-building.health<=cap+.02&&beforeNear-nearby.health<=cap+.02,"structure and nearby unit each bounded by outgoing cap");
            if(cap==0)require(building.health==before&&nearby.health==beforeNear,"zero cap prevents direct/custom damage to units and buildings");
            else require(building.health<before,"custom nonzero cap allows bounded structure trauma");
            require(source.spawnedByCore,"all collision-fatal types suppress cargo/wreck XY loophole");
            for(var mount:source.mounts)require(!mount.weapon.shootOnDeath,"fatal instance has no unbounded death weapon");
            float after=building.health,afterNear=nearby.health;if(source.isAdded())source.destroy();
            require(building.health==after&&nearby.health==afterNear,"deferred native destruction adds no damage beyond cap");
            require(nearby.spawnedByCore==oldNeighborFlag&&nearby.mounts==oldNeighborMounts,"surviving blast victim metadata preserved");
        }finally{
            for(Unit unit:owned){Groups.unit.tree().remove(unit);if(unit.isAdded())unit.remove();}
            if(tile.build==building)tile.setBlock(Blocks.air);
        }
    }
    private static Unit spawn(ArrayList<Unit> owned,UnitType type,Team team,double x,double y){
        Unit unit=type.create(team);unit.set((float)x*Vars.tilesize,(float)y*Vars.tilesize);
        unit.controller(new AIController(){@Override public void updateUnit(){}});unit.add();owned.add(unit);return unit;
    }
    private static Tile site(){for(int y=2;y+17<Vars.world.height();y++)for(int x=2;x+26<Vars.world.width();x++){
        boolean empty=true;scan:for(int dy=0;dy<17;dy++)for(int dx=0;dx<26;dx++)if(Vars.world.tile(x+dx,y+dy).block()!=Blocks.air){empty=false;break scan;}
        if(empty)return Vars.world.tile(x,y);}return null;}
    private static void require(boolean value,String label){if(!value)throw new IllegalStateException("Ram cap native smoke: "+label);}
}
