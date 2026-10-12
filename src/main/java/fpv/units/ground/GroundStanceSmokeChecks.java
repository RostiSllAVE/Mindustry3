package fpv.units.ground;

import fpv.control.CreatorFlightSystem;
import fpv.units.UnitAltitudeSystem;
import fpv.world.Unit3DState;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.gen.Groups;
import mindustry.gen.Legsc;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/** Isolated native assertions; prepare/verifySaved surround the harness's actual SaveIO write/load. */
public final class GroundStanceSmokeChecks {
    public record Saved(int unitId,String typeName,double offset,double clearance,double desired){}
    private GroundStanceSmokeChecks(){}
    public static String verify(UnitAltitudeSystem altitudes,CreatorFlightSystem creator){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"isolated lab required");
        int legged=0,ordinary=0;
        Unit nativeProbe=UnitTypes.cleroi.create(Vars.player.team());
        int previousPreference=arc.Core.settings.getInt("fpv-leg-step-percent",150);
        var previousSupplier=GroundUnitProfiles.setNativeStepMultiplierSupplier(()->2);
        try{
            arc.Core.settings.put("fpv-leg-step-percent",50);double low=GroundUnitProfiles.profile(nativeProbe).maxExtension();
            arc.Core.settings.put("fpv-leg-step-percent",300);double high=GroundUnitProfiles.profile(nativeProbe).maxExtension();
            require(low==high,"replicated native leg-step supplier overrides differing client preferences");
            require(Math.abs(high-Math.min(fpv.generated.DefenseConfig.MAX_LEG_STEP_HEIGHT,nativeProbe.type.legLength*nativeProbe.type.legLengthScl/Vars.tilesize*2))<1e-6,"native reach multiplier is applied exactly once");
            nativeProbe.elevation=1;require(GroundUnitProfiles.profile(nativeProbe).maxExtension()==high,"temporary creator elevation cannot change native reach");
        }finally{arc.Core.settings.put("fpv-leg-step-percent",previousPreference);GroundUnitProfiles.setNativeStepMultiplierSupplier(previousSupplier);}

        for(var type:Vars.content.units()){
            Unit probe=type.create(Vars.player.team());var profile=GroundUnitProfiles.profile(probe);
            if(probe instanceof Legsc&&!type.flying){require(profile!=null&&profile.legged(),"native leg capability registered: "+type.name);legged++;}
            else if(type==UnitTypes.vela){require(profile!=null&&profile.legCount()==2,"Vela explicitly gains two-foot native-mech stance");}
            else if(!type.name.equals("crawler")){require(profile==null,"ordinary/flying unit does not gain spider legs: "+type.name);ordinary++;}
        }
        require(legged>=10&&ordinary>20,"all native leg families inspected");
        Unit unit=spawn();
        try{
            altitudes.unitState(unit);var p=GroundUnitProfiles.profile(unit);var family=altitudes.groundFamily();
            double first=altitudes.unitState(unit).z(),offset=family.stance(unit).randomOffset();
            altitudes.update(1.0/60);double after=altitudes.unitState(unit).z();
            require(after>=first&&after-first<=.201,"native initial stance smooth rise");
            for(int i=0;i<120;i++)altitudes.update(1.0/60);
            require(Math.abs(family.pose(unit).clearance(altitudes.unitState(unit).halfHeight())-family.stance(unit).defaultHeight(p))<.08,"native body reaches local-ground default clearance");
            for(int i=0;i<35;i++){altitudes.setGroundStanceControl(unit,true,-1,1.0/60);altitudes.update(1.0/60);}
            double manual=family.stance(unit).desired(p);
            for(int i=0;i<90;i++){altitudes.setGroundStanceControl(unit,true,0,1.0/60);altitudes.update(1.0/60);}
            require(Math.abs(family.stance(unit).desired(p)-manual)<1e-9,"manual ownership does not fight AI default");
            require(family.stance(unit).randomOffset()==offset,"control switches never resample stance");
            double low=altitudes.unitState(unit).z();altitudes.setGroundStanceControl(unit,false,0,1.0/60);altitudes.update(1.0/60);
            require(altitudes.unitState(unit).z()>low&&altitudes.unitState(unit).z()-low<=.201,"AI release returns upward smoothly");
            Unit3DState ground=altitudes.unitState(unit);
            Unit3DState flying=new Unit3DState(unit.id,unit.type.id,unit.team.id,ground.x(),ground.y(),ground.z()+6,0,0,0,0,0,0,ground.z()+6,ground.radius(),ground.halfHeight(),true);
            unit.elevation=1;altitudes.setRuntimeStateProvider(u->u==unit?flying:creator.state(u));
            for(int i=0;i<90;i++){altitudes.captureGroundIntent(1.0/60);altitudes.update(1.0/60);}
            require(altitudes.unitState(unit)==flying&&family.stance(unit).randomOffset()==offset,"runtime provider owns height while grounded stance remains stable");
            unit.elevation=0;altitudes.setRuntimeStateProvider(creator::state);altitudes.seedGroundFromAir(unit,ground.z()+.5);
            require(Math.abs(altitudes.unitState(unit).z()-ground.z()-.5)<1e-6,"ground handoff starts at actual landing body height");
            require(family.stance(unit).randomOffset()==offset&&!altitudes.unitState(unit).flying(),"handoff restores grounded identity without random reset");
            return "PASS: "+legged+" native legged types registered, "+ordinary+" other types excluded, smooth stable default clearance, Q/E/manual hold/AI release, runtime provider owns body without duplicate ground movement, stance retained across creator handoff";
        }finally{unit.elevation=0;altitudes.setRuntimeStateProvider(creator::state);if(unit.isAdded())unit.remove();}
    }
    /** Call immediately before the existing native save write. This creates one owned roundtrip unit. */
    public static Saved prepareSave(UnitAltitudeSystem altitudes){
        require(Boolean.getBoolean("fpv.smoke"),"isolated save fixture required");Unit unit=spawn();
        for(int i=0;i<150;i++)altitudes.update(1.0/60);
        var family=altitudes.groundFamily();var pose=altitudes.unitState(unit);var stance=family.stance(unit);var profile=GroundUnitProfiles.profile(unit);
        return new Saved(unit.id,unit.type.name,stance.randomOffset(),family.pose(unit).clearance(pose.halfHeight()),stance.desired(profile));
    }
    /** Call immediately after SaveIO.load. IDs and values came through the real mod-specific save chunk. */
    public static String verifySaved(UnitAltitudeSystem altitudes,Saved saved){
        Unit unit=Groups.unit.getByID(saved.unitId());require(unit!=null&&unit.type.name.equals(saved.typeName()),"native SaveIO restored fixture unit identity");
        try{
            var body=altitudes.unitState(unit);var family=altitudes.groundFamily();var stance=family.stance(unit);
            require(Math.abs(stance.randomOffset()-saved.offset())<1e-12,"actual SaveIO roundtrip preserves chosen stance offset");
            require(Math.abs(family.pose(unit).clearance(body.halfHeight())-saved.clearance())<.08,"actual SaveIO roundtrip restores current clearance without upward reset");
            require(Math.abs(stance.desired(GroundUnitProfiles.profile(unit))-saved.desired())<1e-9,"actual SaveIO roundtrip preserves stable desired stance");
            return "PASS: actual SaveIO custom chunk restores native unit identity, stable 1..3 tile offset, current supporting-ground clearance and desired stance";
        }finally{unit.remove();}
    }
    private static Unit spawn(){Tile site=site();require(site!=null,"valid walker fixture location");Unit unit=UnitTypes.atrax.create(Vars.player.team());unit.set(site.worldx(),site.worldy());unit.add();return unit;}
    private static Tile site(){
        for(int x=4;x<Vars.world.width()-4;x++)for(int y=4;y<Vars.world.height()-4;y++){
            boolean clear=true;for(int dx=-2;dx<=2&&clear;dx++)for(int dy=-2;dy<=2;dy++){Tile t=Vars.world.tile(x+dx,y+dy);if(t.block()!=Blocks.air||t.floor().isLiquid){clear=false;break;}}
            if(clear)return Vars.world.tile(x,y);
        }return null;
    }
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError("Walker stance: "+label);}
}
