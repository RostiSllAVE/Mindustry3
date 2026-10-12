package fpv.control;

import arc.util.Time;
import fpv.net.NetworkControls;
import fpv.units.UnitAltitudeSystem;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.gen.*;

/** Caller performs real SaveIO write/load in a disposable profile, not a simulated codec roundtrip. */
public final class NativeBoostSaveSmokeChecks {
    private NativeBoostSaveSmokeChecks(){}
    public static CreatorFlightSystem.Saved prepare(NativeBoostSystem boost,UnitAltitudeSystem alt){
        if(!Boolean.getBoolean("fpv.smoke")||Vars.net.active())throw new IllegalStateException("Isolated native save test required");
        Unit old=Vars.player.unit();var controller=old.controller();float delta=Time.delta;
        try{
            mindustry.world.Tile site=null;
            scan:for(var tile:Vars.world.tiles){if(tile.x<5||tile.y<5||tile.x>Vars.world.width()-6||tile.y>Vars.world.height()-6)continue;
                for(int y=-3;y<=3;y++)for(int x=-3;x<=3;x++){var t=Vars.world.tile(tile.x+x,tile.y+y);if(t.block().solid||t.floor().isDeep()||t.build!=null)continue scan;}site=tile;break;}
            if(site==null)throw new AssertionError("Open native boost save site");
            var u=UnitTypes.quasar.create(old.team);u.set(site.worldx(),site.worldy());u.add();Vars.player.unit(u);Time.delta=1;
            var c=new NetworkControls(true,1,0,0,0,0,.1,0,.3,0,0,0,1,0,false,false,true);
            for(int i=0;i<45;i++){boost.control(Vars.player,u,c);boost.before(1d/60);u.update();boost.after();alt.update(1d/60);}
            if(!boost.active(u)||boost.state(u).z()<.5)throw new AssertionError("Actual native boost before SaveIO");
            return boost.snapshot().stream().filter(v->v.id()==u.id).findFirst().orElseThrow();
        }finally{Vars.player.unit(old);old.controller(controller);Time.delta=delta;}
    }
    public static String verify(NativeBoostSystem boost,UnitAltitudeSystem alt,CreatorFlightSystem.Saved saved){
        var u=Groups.unit.getByID(saved.id());var body=u==null?null:boost.state(u);
        if(body==null||!boost.landing(u)||Math.abs(body.z()-saved.y())>1e-8||Math.abs(body.vz()-saved.vy())>1e-8)throw new AssertionError("Native SaveIO boost XYZ/momentum/forced landing restored");
        float delta=Time.delta;Time.delta=1;
        try{for(int i=0;i<2400&&boost.active(u);i++){boost.before(1d/60);u.update();boost.after();alt.update(1d/60);}
            if(boost.active(u)||u.dead||u.isFlying()||!u.canShoot())throw new AssertionError("Loaded boosted Quasar safely grounds and restores native fire");
            return "PASS: actual native SaveIO preserves boosted Quasar XYZ/momentum, safely descends on load and restores native ground firing";
        }finally{if(u.isAdded())u.remove();boost.after();Time.delta=delta;}
    }
}
