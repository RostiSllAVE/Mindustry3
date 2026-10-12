package fpv.control;

import arc.Core;
import arc.util.Time;
import fpv.net.NetworkControls;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.*;

/** Real native SaveIO caller owns write/load; these fixtures verify the restored live authority. */
public final class CreatorSaveSmokeChecks {
    private CreatorSaveSmokeChecks(){}
    public static CreatorFlightSystem.Saved prepare(CreatorFlightSystem creator,UnitAltitudeSystem altitudes){
        require(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active(),"isolated creator save fixture required");
        Unit original=Vars.player.unit();var originalController=original.controller();
        boolean permission=Core.settings.getBool(CreatorFlightSystem.SETTING,false);float delta=Time.delta;
        Unit unit=null;
        try{
            var tile=findSite();require(tile!=null,"open creator save fixture site");
            unit=UnitTypes.dagger.create(Vars.player.team());unit.set(tile.worldx(),tile.worldy());unit.add();Vars.player.unit(unit);
            double initial=altitudes.unitState(unit).z();
            Time.delta=1;Core.settings.put(CreatorFlightSystem.SETTING,true);
            var control=new NetworkControls(true,3,0,0,0,0,0,0,.2,0,0,0,1,0,false,true);
            for(int i=0;i<45;i++){creator.control(Vars.player,unit,control);creator.before(1d/60);unit.update();creator.after();altitudes.update(1d/60);}
            require(creator.active(unit)&&creator.state(unit).z()>initial+.3,"creator fixture physically airborne before SaveIO");
            int id=unit.id;return creator.snapshot().stream().filter(s->s.id()==id).findFirst().orElseThrow();
        }finally{Vars.player.unit(original);original.controller(originalController);Core.settings.put(CreatorFlightSystem.SETTING,permission);Time.delta=delta;}
    }
    public static String verify(CreatorFlightSystem creator,UnitAltitudeSystem altitudes,CreatorFlightSystem.Saved saved){
        Unit unit=Groups.unit.getByID(saved.id());require(unit!=null&&unit.type.name.equals(saved.typeName()),"native SaveIO restored creator identity");
        var body=creator.state(unit);require(body!=null&&creator.active(unit)&&creator.landing(unit),"saved creator resumes forced landing even without permission/input");
        require(Math.abs(body.x()-saved.x())<1e-5&&Math.abs(body.y()+saved.z())<1e-5&&Math.abs(body.z()-saved.y())<1e-8,"saved actual XYZ retained before first frame");
        require(Math.abs(body.vx()-saved.vx())<1e-8&&Math.abs(body.vy()+saved.vz())<1e-8&&Math.abs(body.vz()-saved.vy())<1e-8,"saved momentum retained before safe descent");
        float delta=Time.delta;boolean permission=Core.settings.getBool(CreatorFlightSystem.SETTING,false);
        try{
            Time.delta=1;Core.settings.put(CreatorFlightSystem.SETTING,false);
            int steps=0;while(creator.active(unit)&&!unit.dead&&steps++<2400){creator.before(1d/60);unit.update();creator.after();altitudes.update(1d/60);}
            require(!creator.active(unit)&&!unit.isFlying()&&!unit.dead&&unit.canPassOn(),"saved creator completes safe native ground handoff alive");
            require(!altitudes.unitState(unit).flying(),"post-load handoff has canonical grounded combat/render body");
            return "PASS: native SaveIO preserves creator XYZ/momentum, forces descent, restores grounded native elevation and physical authority";
        }finally{if(unit.isAdded())unit.remove();creator.after();Core.settings.put(CreatorFlightSystem.SETTING,permission);Time.delta=delta;}
    }
    /** Same real collision and death ordering as normal FPV, through CreatorFlightSystem.after. */
    public static String verifyDeferredDeath(CreatorFlightSystem creator,fpv.world.World3DAdapter world){
        require(Boolean.getBoolean("fpv.smoke")&&!Vars.net.active()&&creator.snapshot().isEmpty(),"isolated empty creator registry required");
        var site=findRamSite();require(site!=null,"creator ram fixture site");
        Unit source=UnitTypes.dagger.create(Vars.player.team()),target=UnitTypes.eclipse.create(mindustry.game.Team.crux);
        source.set(site.worldx(),site.worldy());source.add();target.set((site.x+8)*Vars.tilesize,site.worldy());target.add();target.health=100000;
        Object strength=Core.settings.get("fpv-ram-strength",null),cap=Core.settings.get("fpv-ram-cap-units",null);
        boolean hadStrength=Core.settings.has("fpv-ram-strength"),hadCap=Core.settings.has("fpv-ram-cap-units");
        Core.settings.put("fpv-ram-strength",100);Core.settings.put("fpv-ram-cap-units",3000);
        Groups.unit.tree().insert(target);
        try{
            var targetBody=world.unitState(target);double radius=Math.max(.3,source.hitSize/(double)Vars.tilesize*.5);
            double x=targetBody.x()-radius-targetBody.radius()-.01,z=-targetBody.y(),height=targetBody.z();
            source.set((float)(x*Vars.tilesize),(float)(-z*Vars.tilesize));
            creator.restoreSaved(java.util.List.of(new CreatorFlightSystem.Saved(source.id,source.type.name,source.team.id,source.x/(double)Vars.tilesize,source.y/(double)Vars.tilesize,0,
                radius,.4,x,height,z,12,0,0,0,0,0,1,0,0,0)));
            var state=new fpv.flight.FlightController.State();state.x=x+1.2;state.y=height;state.z=z;state.vx=12;
            var crash=creator.crashForSmoke(source);crash.setControlledMass(fpv.flight.FlightProfile.baseMassKg(fpv.flight.FlightSettings.metadata(source)));
            require(crash.resolve(source,state,x,height,z,.1,true).hit(),"real creator unit contact queued before native death");
            source.damagePierce(Float.MAX_VALUE/4);require(source.dead,"native damage killed creator before commit");
            float before=target.health;creator.after();
            require(target.health<before,"creator pending ram target damage survives source death in same native tick");
            require(!creator.active(source)&&creator.state(source)==null,"dead creator authority is removed after contact settlement");
            return "PASS: actual swept creator contact still damages enemy after native source death, then releases body authority";
        }finally{
            creator.clear();Groups.unit.tree().remove(target);if(source.isAdded())source.remove();if(target.isAdded())target.remove();
            if(hadStrength)Core.settings.put("fpv-ram-strength",strength);else Core.settings.remove("fpv-ram-strength");
            if(hadCap)Core.settings.put("fpv-ram-cap-units",cap);else Core.settings.remove("fpv-ram-cap-units");
        }
    }
    private static mindustry.world.Tile findRamSite(){
        for(int y=2;y+17<Vars.world.height();y++)for(int x=2;x+26<Vars.world.width();x++){
            boolean clear=true;scan:for(int oy=0;oy<17;oy++)for(int ox=0;ox<26;ox++)
                if(Vars.world.tile(x+ox,y+oy).block()!=mindustry.content.Blocks.air){clear=false;break scan;}
            if(clear)return Vars.world.tile(x+8,y+8);
        }
        return null;
    }
    private static mindustry.world.Tile findSite(){
        for(int y=4;y<Vars.world.height()-4;y++)for(int x=4;x<Vars.world.width()-4;x++){
            boolean ok=true;for(int oy=-3;oy<=3&&ok;oy++)for(int ox=-3;ox<=3;ox++){var t=Vars.world.tile(x+ox,y+oy);if(t.block().solid||t.build!=null||t.floor().isDeep()){ok=false;break;}}
            if(ok)return Vars.world.tile(x,y);
        }
        return null;
    }
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
