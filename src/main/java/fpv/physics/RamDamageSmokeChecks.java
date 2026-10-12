package fpv.physics;

import arc.Core;
import fpv.effects.RadialExplosion;
import fpv.flight.FlightController;
import fpv.flight.FlightProfile;
import fpv.flight.FlightSettings;
import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/** Actual native health/shield calls and death ordering; owned fixtures only, no formula mirror. */
public final class RamDamageSmokeChecks {
    private RamDamageSmokeChecks() {}
    public static String verify(World3DAdapter world){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"loaded isolated lab required");
        Tile site=site();require(site!=null,"empty native ram fixture area");
        Object strength=Core.settings.get("fpv-ram-strength",null),blast=Core.settings.get("fpv-blast-damage",null);
        boolean hadStrength=Core.settings.has("fpv-ram-strength"),hadBlast=Core.settings.has("fpv-blast-damage");
        Core.settings.put("fpv-ram-strength",100);Core.settings.put("fpv-blast-damage",0);
        try{
            var enemy=contact(world,site,0);var deferred=contact(world,site,1);var shield=contact(world,site,2);
            contact(world,site,3);contact(world,site,4);contact(world,site,5);var killed=contact(world,site,6);
            return "PASS: native enemy HP "+enemy.healthBefore()+" -> "+enemy.healthAfter()+
                ", source-dead deferred HP "+deferred.healthBefore()+" -> "+deferred.healthAfter()+
                ", absorbed shield "+shield.shieldBefore()+" -> "+shield.shieldAfter()+
                ", enemy killed="+killed.killed()+", safe/friendly/altitude protections; actual feedback deltas";
        }finally{
            if(hadStrength)Core.settings.put("fpv-ram-strength",strength);else Core.settings.remove("fpv-ram-strength");
            if(hadBlast)Core.settings.put("fpv-blast-damage",blast);else Core.settings.remove("fpv-blast-damage");
        }
    }
    private static CrashSystem.ImpactFeedback contact(World3DAdapter world,Tile site,int kind){
        Unit target=(kind==6?UnitTypes.flare:UnitTypes.eclipse).create(kind==3?Vars.player.team():Team.crux);
        Unit probe=UnitTypes.flare.create(Vars.player.team());
        target.controller(new AIController(){@Override public void updateUnit(){}});
        probe.controller(new AIController(){@Override public void updateUnit(){}});
        target.set((site.x+9)*Vars.tilesize,(site.y+6)*Vars.tilesize);target.add();
        probe.set((site.x+2)*Vars.tilesize,target.y);probe.add();
        // An ordinary group addition is indexed on the next native frame. This synchronous fixture
        // inserts only its own target into that same real native index, then removes it in finally.
        Groups.unit.tree().insert(target);
        try{
            if(kind==2)target.shield=100000;
            if(kind==3||kind==4||kind==5)probe.health=100000;
            var targetPose=world.unitState(target);var probePose=world.unitState(probe);
            require(targetPose!=null&&probePose!=null,"native physical body snapshots");
            var effects=new RadialExplosion(world);var crash=new CrashSystem((x,z)->-100,effects,4);
            crash.setWorld(world);crash.setControlledMass(FlightProfile.baseMassKg(FlightSettings.metadata(probe)));
            var state=new FlightController.State();
            state.x=targetPose.x()-Math.min(8,Math.max(.22,probePose.radius()))-targetPose.radius()-.01;
            state.y=targetPose.z()+(kind==5?targetPose.halfHeight()+probePose.halfHeight()+8:0);
            state.z=-targetPose.y();state.vx=kind==4?.5:12;state.vy=targetPose.vz();
            probe.set((float)state.x*Vars.tilesize,(float)-state.z*Vars.tilesize);
            double ox=state.x,oy=state.y,oz=state.z,seconds=.1;
            state.x+=state.vx*seconds;state.y+=state.vy*seconds;
            float before=target.health,beforeShield=target.shield;
            var hit=crash.resolve(probe,state,ox,oy,oz,seconds,true);
            if(kind==5){require(!hit.hit(),"physical altitude miss");crash.applyPending();require(target.health==before&&crash.lastFeedback()==null,"altitude miss cannot damage or invent feedback");return null;}
            require(hit.hit(),"native indexed body sweep: case "+kind);
            if(kind==1){probe.damagePierce(Float.MAX_VALUE/4);require(probe.dead,"native source death before deferred commit");before=target.health;}
            boolean crashDeath=crash.applyPending();var feedback=crash.lastFeedback();
            if(kind==4){require(target.health==before&&feedback==null,"safe contact cannot invent trauma");return null;}
            require(feedback!=null&&feedback.targetId()==target.id&&feedback.targetTeam()==target.team.id,"feedback identifies actual native target");
            require(feedback.healthBefore()==before&&feedback.healthAfter()==target.health&&feedback.shieldAfter()==target.shield,"feedback equals observed native state");
            if(kind==3){require(feedback.friendlyProtected()&&feedback.healthLoss()==0&&target.health==before,"native friendly target protection");}
            else if(kind==2){require(target.health==before&&target.shield<beforeShield&&feedback.shieldLoss()>0,"real native shield absorbs ram without fabricated HP loss");}
            else require(target.health<before&&feedback.healthLoss()>0,"real native enemy health decreases");
            if(kind==1)require(!crashDeath&&effects.bursts().isEmpty(),"already-dead source gets no duplicate custom death burst");
            if(kind==6)require(target.dead&&feedback.killed(),"native enemy kill recorded");
            crash.reset();require(crash.lastFeedback()==null,"reset clears transient feedback");
            return feedback;
        }finally{
            Groups.unit.tree().remove(target);
            if(probe.isAdded())probe.remove();if(target.isAdded())target.remove();
        }
    }
    private static Tile site(){
        for(int y=1;y+14<Vars.world.height();y++)for(int x=1;x+18<Vars.world.width();x++){
            boolean empty=true;
            scan:for(int dy=0;dy<14;dy++)for(int dx=0;dx<18;dx++)if(Vars.world.tile(x+dx,y+dy).block()!=Blocks.air){empty=false;break scan;}
            if(empty)return Vars.world.tile(x,y);
        }
        return null;
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException("Ram native damage smoke: "+message);}
}
