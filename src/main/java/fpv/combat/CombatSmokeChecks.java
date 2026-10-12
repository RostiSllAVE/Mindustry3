package fpv.combat;

import arc.util.Log;
import arc.util.Time;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.content.Items;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.bullet.ContinuousLaserBulletType;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.Fires;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.PowerTurret;
import fpv.world.World3DAdapter;

/** Owned isolated-lab fixtures exercising actual native Bullet.update and synchronous laser init. */
public final class CombatSmokeChecks {
    private CombatSmokeChecks() {}
    public static String run(World3DAdapter world,Combat3DSystem combat){
        require(Boolean.getBoolean("fpv.smoke"),"isolated lab only");
        require(combat.coverage().active(),"3D combat proxy references active");
        ArrayList<Unit> units=new ArrayList<>();ArrayList<Tile> walls=new ArrayList<>();
        float oldDelta=Time.delta;
        int[] callbacks={0};
        try{
            Tile site=findSite();require(site!=null,"open fixture corridor");
            double x=site.x+2,y=site.y+2;
            Unit source=spawn(units,false,Team.sharded,x,y,null);
            Unit target=spawn(units,true,Team.crux,x+7,y,callbacks);
            Unit reverseSource=spawn(units,false,Team.crux,x,y+3,null);
            Unit reverseTarget=spawn(units,true,Team.sharded,x+7,y+3,null);
            Vars.state.teams.updateTeamStats();
            require(world.unitState(target).z()>world.unitState(source).z()+2,"native same-XY projection has distinct physical altitude");

            BasicBulletType original=new BasicBulletType(8,7);original.lifetime=15;original.keepVelocity=false;original.init();original.load();
            BulletType projectile=combat.adapt(original);
            require(projectile!=original&&original.collides&&original.collidesTiles&&original.hittable,"proxy leaves original collision flags unchanged");
            float targetBefore=target.health;
            Bullet miss=create(projectile,source,null);
            Time.delta=1;float startX=miss.x;miss.update();
            require(miss.x==startX&&miss.time==1,"actual native first update holds muzzle and advances lifetime once");
            miss.update();require(Math.abs(miss.x-startX-8)<.0001,"second native update advances once, without double XY motion");
            advance(miss,20);
            require(target.health==targetBefore&&callbacks[0]==0,"projectile projected across enemy XY misses above/below body");

            Bullet hit=create(projectile,source,target);advance(hit,20);
            require(target.health<targetBefore&&callbacks[0]==1,"aimed XYZ projectile damages once and informs native AI controller");
            float reverseBefore=reverseTarget.health;
            advance(create(projectile,reverseSource,reverseTarget),20);
            require(reverseTarget.health<reverseBefore,"enemy projectile also uses physical XYZ damage");

            BulletType realFlare=UnitTypes.flare.weapons.first().bullet;
            BulletType realDuo=((ItemTurret)Blocks.duo).ammoTypes.get(Items.copper);
            require(realFlare!=combat.originalType(realFlare)&&realDuo!=combat.originalType(realDuo)&&combat.coverage().boundSlots()>0,
                "actual native Flare weapon and Duo copper ammo reference adapters");
            reverseBefore=reverseTarget.health;advance(create(realFlare,target,reverseTarget),80);
            require(reverseTarget.health<reverseBefore,"actual native Flare projectile reaches physical target");
            float groundBefore=reverseSource.health;advance(create(realDuo,source,reverseSource),80);
            require(reverseSource.health<groundBefore,"actual native Duo copper projectile damages through XYZ path");

            Bullet paused=create(projectile,source,null);float pausedX=paused.x,pausedTime=paused.time;
            Time.delta=0;paused.update();combat.update(0);
            require(paused.x==pausedX&&paused.time==pausedTime,"paused native update does not move or age projectile");
            Time.delta=1;advance(paused,180);require(!paused.isAdded()&&!combat.managed(paused),"powered expiry followed by ground contact cleans sidecar");

            LaserBulletType nativeLaser=new LaserBulletType(6);nativeLaser.length=14*Vars.tilesize;nativeLaser.init();
            BulletType laser=combat.adapt(nativeLaser);
            float beforeLaser=target.health;int beforeCallbacks=callbacks[0];
            remove(create(laser,source,null));
            require(target.health==beforeLaser&&callbacks[0]==beforeCallbacks,"instant laser init cannot leak native XY damage through altitude miss");
            remove(create(laser,source,target));
            require(target.health<beforeLaser&&callbacks[0]==beforeCallbacks+1,"instant laser applies one XYZ contact synchronously");

            ContinuousLaserBulletType nativeContinuous=new ContinuousLaserBulletType(3);nativeContinuous.length=14*Vars.tilesize;nativeContinuous.damageInterval=5;nativeContinuous.init();
            BulletType continuous=combat.adapt(nativeContinuous);
            Bullet sustained=create(continuous,source,target);float continuousBefore=target.health;
            sustained.timer.reset(1,5);sustained.update();float afterPulse=target.health;
            require(afterPulse<continuousBefore,"continuous laser real native update deals a 3D pulse");
            sustained.update();require(target.health==afterPulse,"continuous pulse interval prevents duplicate frame damage");
            sustained.timer.reset(1,5);sustained.update();require(target.health<afterPulse,"continuous laser next interval damages again");remove(sustained);

            int invalidBefore=combat.invalidAimEvents();float healthyBefore=target.health;
            Bullet poisoned=create(continuous,source,null);poisoned.aimX=Float.NaN;poisoned.aimY=Float.POSITIVE_INFINITY;
            poisoned.update();
            require(!poisoned.isAdded()&&!combat.managed(poisoned)&&combat.invalidAimEvents()==invalidBefore+1,
                "poisoned native continuous aim is cancelled with diagnostics instead of crashing");
            require(target.health==healthyBefore,"invalid beam cancellation cannot apply phantom damage");
            Unit vanishing=spawn(units,true,Team.crux,x+5,y+5,null);
            Bullet orphan=create(continuous,source,vanishing);vanishing.remove();orphan.update();
            require(combat.invalidAimEvents()==invalidBefore+1,"removed target safely releases live aim without invalid coordinates");remove(orphan);
            Bullet invalidMotion=create(continuous,source,null);invalidMotion.vel.x=Float.NaN;invalidMotion.update();
            require(!invalidMotion.isAdded()&&combat.invalidAimEvents()==invalidBefore+2,"invalid native muzzle motion is cancelled safely");

            BulletType realMeltdown=((PowerTurret)Blocks.meltdown).shootType;
            require(realMeltdown!=combat.originalType(realMeltdown)&&combat.originalType(realMeltdown) instanceof ContinuousLaserBulletType,
                "actual native Meltdown continuous laser is adapted with incendiary metadata");
            Bullet nativeBeam=realMeltdown.create(source,source,source.team,source.x,source.y,0,1,1,1,null,null,target.x,target.y,target);
            float beforeNativeBeam=target.health;nativeBeam.timer.reset(1,5);nativeBeam.update();
            require(target.health<beforeNativeBeam,"actual native Meltdown pulse damages in physical XYZ");remove(nativeBeam);
            ContinuousLaserBulletType guaranteedFire=new ContinuousLaserBulletType(.01f);guaranteedFire.length=14*Vars.tilesize;
            guaranteedFire.incendChance=1;guaranteedFire.incendAmount=8;guaranteedFire.init();
            int fireCount=countFires(site);Bullet highFire=create(combat.adapt(guaranteedFire),source,target);
            highFire.timer.reset(1,5);highFire.update();remove(highFire);
            require(countFires(site)==fireCount,"guaranteed incendiary aerial hit cannot ignite distant ground at matching XY");

            Tile first=Vars.world.tile((int)x+4,(int)y),second=Vars.world.tile((int)x+8,(int)y);
            first.setBlock(Blocks.copperWall,Team.crux,0);second.setBlock(Blocks.copperWall,Team.crux,0);walls.add(first);walls.add(second);
            float firstHealth=first.build.health,secondHealth=second.build.health;
            remove(create(laser,source,null));
            require(first.build.health<firstHealth&&second.build.health<secondHealth,"native beam piercing reaches two ordinary buildings");
            first.setBlock(Blocks.plastaniumWall,Team.crux,0);secondHealth=second.build.health;
            remove(create(laser,source,null));
            require(second.build.health==secondHealth,"native laser absorber stops XYZ beam before rear building");
            first.setBlock(Blocks.copperWall,Team.sharded,0);firstHealth=first.build.health;secondHealth=second.build.health;
            remove(create(laser,source,null));
            require(first.build.health==firstHealth&&second.build.health<secondHealth,"friendly building pass-through retains native team protection");
            for(Tile tile:walls)tile.setBlock(Blocks.air);walls.clear();

            first.setBlock(Blocks.conveyor,Team.crux,0);walls.add(first);
            float conveyorHealth=first.build.health;
            Bullet lowShot=realDuo.create(source,source,source.team,source.x,source.y,0,-1,1,1,null,null,
                first.worldx(),first.worldy(),first.build);
            advance(lowShot,80);
            require(first.build.health<conveyorHealth,"explicitly targeted low conveyor remains hittable above terrain clearance");
            first.setBlock(Blocks.air);walls.clear();

            Tile peak=findPeak(world);require(peak!=null,"natural mountain fixture located");
            source.set(peak.worldx(),peak.worldy());
            BasicBulletType terrainType=new BasicBulletType(8,0);terrainType.lifetime=15;terrainType.keepVelocity=false;terrainType.init();terrainType.load();
            Bullet down=combat.adapt(terrainType).create(source,source,source.team,source.x,source.y,0,-1,1,1,null,null,source.x,source.y,null);
            Time.delta=1;down.update();down.update();
            require(!down.isAdded(),"actual downward projectile stops at natural generated terrain");
            source.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));

            var weapon=UnitTypes.flare.weapons.first();BulletType wrapped=weapon.bullet;float reload=weapon.reload;var shoot=weapon.shoot;
            combat.close();require(weapon.bullet!=wrapped&&weapon.bullet.collides,"2D mode restores original weapon bullet identity and collision flags");
            combat.install();require(weapon.bullet==wrapped&&weapon.reload==reload&&weapon.shoot==shoot,"reactivation retains native cooldown and firing pattern metadata");
            require(combat.coverage().activeShots()==0,"world/mode cleanup retires all managed shots");

            // Renderer evidence: retain only harmless projectiles/beams, for both teams, including vertical.
            BasicBulletType visualBullet=new BasicBulletType(5,0);visualBullet.lifetime=30;visualBullet.keepVelocity=false;visualBullet.init();visualBullet.load();
            LaserBulletType visualLaser=new LaserBulletType(0);visualLaser.length=14*Vars.tilesize;visualLaser.lifetime=30;visualLaser.init();
            target.set(source.x,source.y);reverseTarget.set(reverseSource.x,reverseSource.y);
            Vars.state.teams.updateTeamStats();
            create(combat.adapt(visualBullet),source,target);create(combat.adapt(visualBullet),reverseSource,reverseTarget);
            create(combat.adapt(visualLaser),source,target);create(combat.adapt(visualLaser),reverseSource,reverseTarget);
            var coverage=combat.coverage();
            require(combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.BEAM&&Math.hypot(v.bx()-v.ax(),v.by()-v.ay())<1e-4&&Math.abs(v.bz()-v.az())>.1),"managed exactly vertical beam retained for renderer");
            return "passed: actual Bullet.update, both-team XYZ hits/altitude misses, native AI callback, instant/continuous lasers, building piercing/absorber/team rules, terrain, pause/lifetime/restore; adaptedTypes="+coverage.adaptedTypes()+", boundSlots="+coverage.boundSlots()+", fallbackTypes="+coverage.fallbackTypes()+", fallbackReasons="+combat.fallbackReasons();
        }finally{
            Time.delta=oldDelta;
            for(Unit unit:units)if(unit.isAdded())unit.remove();
            for(Tile tile:walls)if(tile.build!=null)tile.setBlock(Blocks.air);
            Vars.state.teams.updateTeamStats();
        }
    }
    private static Tile findSite(){
        for(int y=6;y<Vars.world.height()-12;y+=2)for(int x=6;x<Vars.world.width()-22;x+=2){
            boolean clear=true;
            for(int oy=0;oy<8&&clear;oy++)for(int ox=0;ox<19;ox++){
                var tile=Vars.world.tile(x+ox,y+oy);
                if(tile.block()!=Blocks.air||tile.build!=null){clear=false;break;}
            }
            if(clear)return Vars.world.tile(x,y);
        }
        return null;
    }
    private static Tile findPeak(World3DAdapter world){
        for(int y=2;y<Vars.world.height()-2;y+=3)for(int x=2;x<Vars.world.width()-2;x+=3)
            if(world.terrainHeight(x,y)>3)return Vars.world.tile(x,y);
        return null;
    }
    private static int countFires(Tile site){
        int count=0;
        for(int y=site.y;y<site.y+8;y++)for(int x=site.x;x<site.x+19;x++)
            if(Fires.get(Vars.world.tile(x,y))!=null)count++;
        return count;
    }
    private static Unit spawn(ArrayList<Unit> owned,boolean flying,Team team,double x,double y,int[] hits){
        Unit unit=(flying?UnitTypes.flare:UnitTypes.dagger).create(team);unit.spawnedByCore=true;
        unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));
        unit.controller(new AIController(){@Override public void updateUnit(){}@Override public void hit(Bullet bullet){if(hits!=null)hits[0]++;}});
        unit.add();owned.add(unit);return unit;
    }
    private static Bullet create(BulletType type,Unit owner,Unit target){
        float angle=target==null?0:(float)Math.toDegrees(Math.atan2(target.y-owner.y,target.x-owner.x));
        return type.create(owner,owner,owner.team,owner.x,owner.y,angle,-1,1,1,null,null,target==null?-1:target.x,target==null?-1:target.y,target);
    }
    private static void advance(Bullet bullet,int ticks){Time.delta=1;for(int i=0;i<ticks&&bullet.isAdded();i++)bullet.update();}
    private static void remove(Bullet bullet){if(bullet!=null&&bullet.isAdded()){bullet.hit=true;bullet.remove();}}
    private static void require(boolean condition,String label){if(!condition)throw new IllegalStateException("Combat smoke: "+label);Log.info("[FPV-COMBAT] OK: @",label);}
}
