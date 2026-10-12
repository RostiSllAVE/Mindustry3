package fpv.units.ground;

import arc.Core;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.gen.Unit;
import mindustry.type.UnitType;
import mindustry.world.Tile;

/** Real native units and walls in the isolated smoke world; never runs in a player's normal profile. */
public final class GroundFamilySmokeChecks {
    private GroundFamilySmokeChecks(){}
    public static String verify(World3DAdapter world,UnitAltitudeSystem altitudes){return verify(world,altitudes,null);}
    public static String verify(World3DAdapter world,UnitAltitudeSystem altitudes,fpv.defense.ProjectedWallSystem walls){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"isolated native lab required");
        require(altitudes.groundFamily()!=null,"production family locomotion enabled");
        Tile site=findSite();require(site!=null,"empty family traversal test site");
        String[] keys={"fpv-ground-atrax-extension","fpv-ground-spiroct-extension","fpv-ground-arkyid-extension","fpv-ground-toxopid-extension","fpv-ground-min-speed-percent","fpv-wall-unit-collision"};
        var prior=new HashMap<String,Object>();for(String key:keys)if(Core.settings.has(key))prior.put(key,Core.settings.get(key,null));
        var defaults=GroundUnitProfiles.defaults();
        Core.settings.put(keys[0],(int)Math.round(defaults.atraxExtension()*10));Core.settings.put(keys[1],(int)Math.round(defaults.spiroctExtension()*10));
        Core.settings.put(keys[2],(int)Math.round(defaults.arkyidExtension()*10));Core.settings.put(keys[3],(int)Math.round(defaults.toxopidExtension()*10));
        Core.settings.put(keys[4],25);Core.settings.put(keys[5],false);GroundUnitProfiles.refresh();
        var owned=new ArrayList<Unit>();Tile wallTile=null;
        try{
            UnitType[] types={UnitTypes.crawler,UnitTypes.atrax,UnitTypes.spiroct,UnitTypes.arkyid,UnitTypes.toxopid};
            double lastExtension=-1;
            for(UnitType type:types){
                Unit unit=spawn(owned,type,site.x+5,site.y+5);
                altitudes.update(1.0/60);var state=altitudes.unitState(unit);var profile=GroundUnitProfiles.profile(unit);
                require(state!=null&&!state.flying()&&!unit.isFlying(),type.name+" stays grounded in canonical XYZ");
                require(state.z()>world.terrainHeight(state.x(),state.y()),type.name+" has body height above support");
                require(profile.maxExtension()>lastExtension,type.name+" has progressing leg reach");lastExtension=profile.maxExtension();
                require(type==UnitTypes.crawler?profile.wheelCount()==4&&profile.legCount()==0:profile.thicknessMultiplier()==2,type.name+" profile identity retained");
                float health=unit.health;double start=unit.x/8.0,expectedDistance=0;
                for(int i=0;i<30;i++){expectedDistance+=altitudes.groundFamily().pose(unit).speedFraction()/60;unit.set(unit.x+8f/60,unit.y);altitudes.update(1.0/60);}
                require(Math.abs(unit.x/8.0-start-expectedDistance)<.015,type.name+" applies existing extension speed penalty once");
                require(unit.health==health&&unit.type==type,type.name+" native health/type untouched");unit.remove();
            }
            Unit flyer=spawn(owned,UnitTypes.flare,site.x+5,site.y+5);altitudes.update(1.0/60);
            require(GroundUnitProfiles.profile(flyer)==null&&altitudes.unitState(flyer).flying(),"existing flying altitude remains flying");flyer.remove();
            wallTile=Vars.world.tile(site.x+10,site.y+9);wallTile.setBlock(Blocks.titaniumWallLarge,Vars.player.team(),0);
            Unit climber=spawn(owned,UnitTypes.atrax,site.x+4,site.y+9);altitudes.update(1.0/60);
            double beforeZ=altitudes.unitState(climber).z(),peak=beforeZ,slowest=1;boolean wait=false;
            for(int i=0;i<1800&&climber.x/8.0<site.x+14;i++){
                climber.set(climber.x+16f/60,climber.y);altitudes.update(1.0/60);
                var pose=altitudes.groundFamily().pose(climber);peak=Math.max(peak,pose.z());slowest=Math.min(slowest,pose.speedFraction());wait|=pose.blocked();
            }
            require(climber.x/8.0>site.x+12,"native Atrax crosses physical large wall within tier reach");
            require(peak>beforeZ+.8&&slowest<.99,"actual supported traversal extends and slows with raised default stance");
            require(Math.abs(altitudes.unitState(climber).z()-altitudes.groundFamily().pose(climber).z())<1e-6,"combat/render snapshot shares actual body height");
            climber.remove();
            Core.settings.put(keys[0],0);GroundUnitProfiles.refresh();
            Unit limited=spawn(owned,UnitTypes.atrax,site.x+4,site.y+9);altitudes.update(1.0/60);
            for(int i=0;i<240;i++){limited.set(limited.x+16f/60,limited.y);altitudes.update(1.0/60);}
            require(limited.x/8.0<site.x+9,"lower configured reach actually stops native XY at the wall");
            limited.remove();
            if(walls!=null){
                Core.settings.put(keys[0],(int)Math.round(defaults.atraxExtension()*10));Core.settings.put(keys[5],true);
                GroundUnitProfiles.refresh();walls.update(0);
                var volume=walls.volume(wallTile.build);require(volume!=null&&volume.topZ()>defaults.atraxExtension(),"live tall projection fixture");
                require(Math.abs(altitudes.groundFamily().physicalSupport(volume.x(),volume.y())-volume.baseZ())<.01,"projection top never becomes a physical foot platform");
                Unit low=spawn(owned,UnitTypes.atrax,site.x+5,site.y+9);altitudes.update(1.0/60);
                for(int i=0;i<1000;i++){low.set(low.x+16f/60,low.y);altitudes.update(1.0/60);}
                require(low.x/8.0<site.x+9,"Atrax is blocked by enabled projection above its configured reach");low.remove();
                Unit high=spawn(owned,UnitTypes.toxopid,site.x+5,site.y+9);altitudes.update(1.0/60);double shieldPeak=altitudes.unitState(high).z();
                for(int i=0;i<1400&&high.x/8.0<site.x+15;i++){
                    high.set(high.x+16f/60,high.y);altitudes.update(1.0/60);shieldPeak=Math.max(shieldPeak,altitudes.unitState(high).z());
                }
                require(high.x/8.0>site.x+13&&shieldPeak>volume.topZ(),"Toxopid physically clears enabled projection within its higher reach");high.remove();
                Core.settings.put(keys[5],false);walls.update(0);
                Unit bypass=spawn(owned,UnitTypes.atrax,site.x+5,site.y+9);altitudes.update(1.0/60);
                for(int i=0;i<1800&&bypass.x/8.0<site.x+14;i++){bypass.set(bypass.x+16f/60,bypass.y);altitudes.update(1.0/60);}
                require(bypass.x/8.0>site.x+12,"disabled projected unit barrier allows normal physical-base traversal");bypass.remove();
            }
            require(wallTile.build!=null&&wallTile.build.block==Blocks.titaniumWallLarge&&wallTile.build.health==wallTile.build.maxHealth,"walking leaves native wall simulation unchanged");
            return "PASS: all five native types keep health/content, four-wheel Crawler and 4/6/6/8-leg profiles, tier reach, grounded canonical XYZ, single application of stance speed penalty and unchanged flyers, real large-wall ascent/slowdown, configured low reach blocks native XY, physical height shared with combat, enabled projected barrier enforces tier reach while feet use only physical support, disabled barrier bypass, wall data unchanged";
        }finally{
            for(Unit unit:owned)if(unit.isAdded())unit.remove();
            if(wallTile!=null&&wallTile.build!=null)wallTile.setBlock(Blocks.air);
            for(String key:keys)if(prior.containsKey(key))Core.settings.put(key,prior.get(key));else Core.settings.remove(key);
            GroundUnitProfiles.refresh();altitudes.groundFamily().clear();if(walls!=null)walls.update(0);
        }
    }
    private static Unit spawn(ArrayList<Unit> owned,UnitType type,double x,double y){Unit u=type.create(Vars.player.team());u.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));u.add();owned.add(u);return u;}
    private static Tile findSite(){
        for(int x=3;x+19<Vars.world.width();x+=3)for(int y=3;y+19<Vars.world.height();y+=3){boolean clear=true;
            for(int dx=0;dx<19&&clear;dx++)for(int dy=0;dy<19;dy++){
                Tile t=Vars.world.tile(x+dx,y+dy);if(t==null||t.block()!=Blocks.air||t.floor().isLiquid){clear=false;break;}
            }
            if(clear)return Vars.world.tile(x,y);
        }
        return null;
    }
    private static void require(boolean ok,String text){if(!ok)throw new AssertionError("Ground family: "+text);}
}
