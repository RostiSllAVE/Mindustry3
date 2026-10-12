package fpv.defense;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import fpv.combat.Combat3DSystem;
import fpv.render.FpvWorldRenderer;
import fpv.units.UnitAltitudeSystem;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;
import java.util.ArrayList;
import java.util.HashMap;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.gen.Bullet;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.bullet.RailBulletType;
import mindustry.entities.bullet.ContinuousFlameBulletType;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.Door;

/** Own native fixtures only. Exercises the production wall interface, real base HP and GPU path. */
public final class ProjectedWallSmokeChecks {
    private ProjectedWallSmokeChecks(){}
    public static String verify(ProjectedWallSystem walls,FpvWorldRenderer renderer,World3DAdapter world,UnitAltitudeSystem altitudes,Combat3DSystem combat){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"isolated loaded lab required");
        Tile site=findSite();require(site!=null,"empty wall fixture area");
        String[] keys={"fpv-wall-hp-percent","fpv-wall-segments","fpv-wall-regen-delay","fpv-wall-regen-permille","fpv-leg-step-percent"};
        int[] defaults={85,5,8,25,150};var previous=new HashMap<String,Object>();
        boolean hadBarrier=Core.settings.has("fpv-wall-unit-collision");Object oldBarrier=Core.settings.get("fpv-wall-unit-collision",null);
        Core.settings.put("fpv-wall-unit-collision",false);
        for(int i=0;i<keys.length;i++){if(Core.settings.has(keys[i]))previous.put(keys[i],Core.settings.get(keys[i],null));Core.settings.put(keys[i],defaults[i]);}
        var owned=new ArrayList<Building>();Unit leg=null,ordinary=null;
        try{
            Building small=place(owned,site.x+3,site.y+4,Blocks.copperWall),large=place(owned,site.x+8,site.y+4,Blocks.copperWallLarge);
            Building absorber=place(owned,site.x+17,site.y+4,Blocks.plastaniumWallLarge);
            Building tough=place(owned,site.x+13,site.y+4,Blocks.surgeWallLarge),door=place(owned,site.x+3,site.y+9,Blocks.door);
            walls.update(0);var initial=walls.volume(small);var big=walls.volume(large);float baseHp=small.health;
            require(initial!=null&&Math.abs(initial.maxHp()-small.maxHealth*.85)<.01,"native wall metadata gives separate85% pool");
            require(big.maxHp()>initial.maxHp()&&big.halfWidth()>initial.halfWidth()&&Math.abs(big.baseZ()-fpv.construction.LayerCoordinates.DEFAULT.plane(1))<1e-6&&Math.abs(big.baseZ()-initial.baseZ())<1e-6,"large wall footprint/HP scale; ground wall tops share the aligned Z1 foundation plane");
            require(Math.abs(initial.fullTopZ()-initial.baseZ()*6)<.001,"five native-height projected segments");
            small.enabled=false;small.efficiency=0;small.sleep();walls.update(0);
            require(walls.volume(small)!=null,"disabled/idle native wall remains a projection anchor");
            double x=initial.x(),y=initial.y(),middle=(initial.topZ()+initial.baseZ())*.5;
            var contact=walls.trace(Team.crux,x-2,y,middle,x+2,y,middle,0);
            require(contact!=null,"enemy segment hits actual upper XYZ volume");
            require(walls.trace(small.team,x-2,y,middle,x+2,y,middle,0)==null,"allied outward projectile passes");
            require(walls.trace(Team.crux,x-2,y,initial.topZ()+1,x+2,y,initial.topZ()+1,0)==null,"true above-wall projectile passes");
            require(walls.trace(Team.crux,x-2,y,initial.baseZ()*.5,x+2,y,initial.baseZ()*.5,0)==null,"base is separate native building target");
            Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.fov=58;
            camera.position.set(site.x+23,18,-site.y+12);camera.up.set(0,1,0);camera.lookAt(site.x+8,4,-site.y-5);camera.far=1000;camera.update();
            renderer.render(camera);for(Building b:owned)require(renderer.wasProjectionSubmitted(b),"full native wall projection submitted: "+b.block.name);capture("walls-full.png");
            require(walls.hit(contact,null,initial.maxHp()*.5f),"enemy impact reduces single sidecar pool");
            var half=walls.volume(small);require(Math.abs(half.hp()-initial.maxHp()*.5)<.01&&Math.abs(half.topZ()-(initial.baseZ()+initial.fullTopZ())*.5)<.001,"half health collapses upper half");
            require(small.health==baseHp,"projection hit preserves native base HP");
            renderer.render(camera);require(renderer.wasProjectionSubmitted(small),"half upper geometry remains visible");capture("walls-half.png");
            var low=walls.trace(Team.crux,x-2,y,initial.baseZ()+.2,x+2,y,initial.baseZ()+.2,0);
            require(walls.hit(low,null,initial.maxHp()),"remaining pool accepts lethal projection damage");
            require(walls.volume(small).hp()==0&&small.health==baseHp,"pool depleted without destroying native base");
            renderer.render(camera);require(!renderer.wasProjectionSubmitted(small)&&renderer.wasBuildingSubmitted(small),"collapsed upper volume hidden, base still submitted");capture("walls-collapsed.png");
            require(!walls.hit(contact,null,1),"stale collapsed hit token rejected");
            walls.update(0);require(walls.volume(small).hp()==0,"pause does not regenerate");
            walls.update(7);require(walls.volume(small).hp()==0,"damage delay protects collapse");walls.update(2);
            require(Math.abs(walls.volume(small).hp()-initial.maxHp()*.025)<.02,"partial timestep regens only after delay");
            var doorBuild=(Door.DoorBuild)door;doorBuild.open=true;walls.update(0);
            var dv=walls.volume(door);require(walls.trace(Team.crux,dv.x()-2,dv.y(),dv.baseZ()+.2,dv.x()+2,dv.y(),dv.baseZ()+.2,0)==null,"open native door projection disappears");
            renderer.render(camera);require(!renderer.wasProjectionSubmitted(door),"open door has no ghost upper mesh");doorBuild.open=false;
            try(var replica=new ProjectedWallSystem(world,()->false)){
                replica.applyReplication(walls.replication());var copy=replica.volume(small);
                require(copy!=null&&copy.hp()==walls.volume(small).hp()&&Math.abs(copy.topZ()-walls.volume(small).topZ())<1e-5,"sparse client replica carries physical pool/height within float wire precision");
                replica.update(10);require(replica.volume(small).hp()==copy.hp(),"client does not regenerate independently");
                require(!replica.hit(replica.trace(Team.crux,x-2,y,initial.baseZ()+.01,x+2,y,initial.baseZ()+.01,0),null,10),"client cannot mutate shield damage");
            }
            combatChecks(walls,combat,tough,absorber);
            leg=UnitTypes.cleroi.create(Vars.player.team());leg.set(large.x,large.y);leg.add();
            ordinary=UnitTypes.dagger.create(Vars.player.team());ordinary.set(large.x+32,large.y+32);ordinary.add();
            require(LegTraversal.capable(leg)&&!LegTraversal.capable(ordinary),"native capability distinguishes legs from ordinary ground");
            require(walls.sweepUnit(ordinary,big.x()-3,big.y(),big.baseZ()+1,big.x()+3,big.y(),big.baseZ()+1,.2,.1)==null,"unit barrier disabled independently by default");
            Core.settings.put("fpv-wall-unit-collision",true);walls.update(0);
            var barrier=walls.sweepUnit(ordinary,big.x()-3,big.y(),big.baseZ()+1,big.x()+3,big.y(),big.baseZ()+1,.2,.1);
            require(barrier!=null&&barrier.nx()==-1,"enabled barrier returns exact contact normal for pilot response");
            require(walls.sweepUnit(leg,big.x()-3,big.y(),big.baseZ()+1,big.x()+3,big.y(),big.baseZ()+1,.2,.1)==null,"unmodified native leg family still bypasses projection when pilot barrier enabled");
            require(walls.sweepUnit(ordinary,big.x()-3,big.y(),big.topZ()+.3,big.x()+3,big.y(),big.topZ()+.3,1,.1)==null,"barrier uses vertical half-height separately from broad horizontal radius");
            Core.settings.put("fpv-wall-unit-collision",false);walls.update(0);
            LegTraversal support=new LegTraversal(world);
            var raised=support.contact(leg,big.x(),big.y(),0,.1,.2);
            require(raised.centerHeight()>=big.baseZ()+.19,"native leg support clears actual large wall base");
            var plain=support.contact(ordinary,big.x(),big.y(),0,.1,.2);
            require(plain.centerHeight()<big.baseZ(),"ordinary ground never gains phantom wall stepping");
            altitudes.update(1.0/60);require(altitudes.unitState(leg).z()>=big.baseZ(),"shared authoritative native leg altitude uses wall support");
            leg.set((site.x+17)*Vars.tilesize,(site.y+9)*Vars.tilesize);altitudes.update(1.0/60);
            var legProfile=fpv.units.ground.GroundUnitProfiles.profile(leg);
            require(!altitudes.unitState(leg).flying()&&legProfile!=null&&altitudes.groundFamily().pose(leg)!=null,
                "leg unit remains grounded while smoothly adopting the new stable walker stance");
            require(small.block==Blocks.copperWall&&large.block==Blocks.copperWallLarge&&small.health==baseHp,"native map blocks/health remain original");
            var old=walls.trace(Team.crux,big.x()-3,big.y(),big.baseZ()+.1,big.x()+3,big.y(),big.baseZ()+.1,0);
            large.tile.setBlock(Blocks.air);walls.update(0);require(!walls.hit(old,null,50)&&walls.volume(large)==null,"removed native anchor invalidates old contact");
            return "PASS: native small/large wall pools and five-height bounds, actual managed projectile stop/allied pass/overflight and native ordinary/plastanium projected laser piercing and Rail/flame exceptions, projection-only damage preserves base HP while native base beams retain damage, idle/open-door lifecycle, top-down collapse/regen/pause, sparse authority, leg physical support/return, pilot barrier geometry, GPU full/half/collapsed captures";
        }finally{
            if(leg!=null&&leg.isAdded())leg.remove();if(ordinary!=null&&ordinary.isAdded())ordinary.remove();
            for(Building b:owned)if(b.tile.build==b){b.noSleep();b.tile.setBlock(Blocks.air);}
            for(String key:keys)if(previous.containsKey(key))Core.settings.put(key,previous.get(key));else Core.settings.remove(key);
            if(hadBarrier)Core.settings.put("fpv-wall-unit-collision",oldBarrier);else Core.settings.remove("fpv-wall-unit-collision");
            walls.update(0);renderer.invalidate();
        }
    }
    private static void combatChecks(ProjectedWallSystem walls,Combat3DSystem combat,Building wall,Building absorber){
        require(combat.authoritative(),"actual combat adapter is authoritative");
        float delta=Time.delta;var owned=new ArrayList<Bullet>();
        try{
            Time.delta=1;var v=walls.volume(wall);double x=v.x(),y=v.y(),z=(v.baseZ()+v.topZ())*.5;
            var direction=new WorldCoordinates.Point(1,0,0);var muzzle=new WorldCoordinates.Point(x-3,y,z);
            Team enemy=wall.team==Team.crux?Team.sharded:Team.crux;
            BasicBulletType type=new BasicBulletType(8,17);type.lifetime=30;type.keepVelocity=false;type.init();type.load();
            float hp=v.hp(),baseHp=wall.health;
            Bullet hit=combat.launchPhysical(type,null,enemy,muzzle,direction,-1);owned.add(hit);
            require(hit!=null&&combat.managed(hit),"real projectile enters managed XYZ path");advance(hit,6);
            require(!hit.isAdded()&&!combat.managed(hit)&&walls.volume(wall).hp()<hp,"enemy native Bullet.update stops and damages upper pool");
            require(wall.health==baseHp,"actual projectile shield hit leaves physical base HP unchanged");
            hp=walls.volume(wall).hp();
            Bullet friendly=combat.launchPhysical(type,null,wall.team,muzzle,direction,-1);owned.add(friendly);advance(friendly,6);
            require(friendly.isAdded()&&combat.projectileState(friendly).x()>x+v.halfWidth()&&walls.volume(wall).hp()==hp,"actual allied native projectile crosses upper volume harmlessly");
            friendly.remove();
            Bullet above=combat.launchPhysical(type,null,enemy,new WorldCoordinates.Point(x-3,y,v.fullTopZ()+2),direction,-1);owned.add(above);advance(above,6);
            require(above.isAdded()&&combat.projectileState(above).x()>x+v.halfWidth()&&walls.volume(wall).hp()==hp,"actual hostile high-altitude projectile overflies projection");
            above.remove();
            var av=walls.volume(absorber);double laserZ=(Math.max(v.baseZ(),av.baseZ())+Math.min(v.fullTopZ(),av.fullTopZ()))*.5;
            var beamMuzzle=new WorldCoordinates.Point(x-3,y,laserZ);
            require(walls.traceLaser(enemy,x-2,y,laserZ,x+2,y,laserZ,0)==null,"ordinary projected wall lets laser through");
            float absorberHp=av.hp(),absorberBaseHp=absorber.health;
            LaserBulletType laser=new LaserBulletType(19);laser.length=(float)(av.x()-x+6)*Vars.tilesize;laser.init();
            Bullet beam=combat.launchPhysical(laser,null,enemy,beamMuzzle,direction,-1);owned.add(beam);
            require(beam!=null&&walls.volume(wall).hp()==hp&&wall.health==baseHp,
                "actual native beam pierces ordinary projection without consuming its shield pool");
            require(walls.volume(absorber).hp()<absorberHp&&absorber.health==absorberBaseHp,
                "actual native beam stops and damages the rear Plastanium upper projection");
            absorberHp=walls.volume(absorber).hp();
            Bullet alliedBeam=combat.launchPhysical(laser,null,wall.team,beamMuzzle,direction,-1);owned.add(alliedBeam);
            require(walls.volume(absorber).hp()==absorberHp&&walls.volume(wall).hp()==hp,"allied laser passes both native projection kinds");
            Bullet highBeam=combat.launchPhysical(laser,null,enemy,new WorldCoordinates.Point(x-3,y,Math.max(v.fullTopZ(),av.fullTopZ())+3),direction,-1);owned.add(highBeam);
            require(walls.volume(absorber).hp()==absorberHp&&walls.volume(wall).hp()==hp,"laser above both projection tops causes no shield damage");
            require(walls.traceLaser(enemy,x-3,y,laserZ,av.x()+3,y,laserZ,0,false)==null,"native nonabsorbing family bypasses all projected walls");
            RailBulletType rail=new RailBulletType();rail.damage=19;rail.length=laser.length;rail.init();
            require(rail.laserAbsorb,"native Rail inherited absorption flag intentionally differs from its collideLine behavior");
            Bullet railShot=combat.launchPhysical(rail,null,enemy,beamMuzzle,direction,-1);owned.add(railShot);
            require(railShot!=null&&railShot.fdata>(av.x()-beamMuzzle.x()+2)*Vars.tilesize&&walls.volume(absorber).hp()==absorberHp&&walls.volume(wall).hp()==hp,
                "actual native Rail crosses both ordinary and Plastanium projections without shield damage");
            ContinuousFlameBulletType flame=new ContinuousFlameBulletType(13);flame.length=laser.length;flame.init();
            require(!flame.laserAbsorb,"native continuous flame disables laser wall absorption");
            Bullet flameShot=combat.launchPhysical(flame,null,enemy,beamMuzzle,direction,-1);owned.add(flameShot);
            flameShot.time=flameShot.lifetime*.5f;flameShot.timer.reset(1,flame.damageInterval);flameShot.update();
            require(flameShot.fdata>(av.x()-beamMuzzle.x()+2)*Vars.tilesize&&walls.volume(absorber).hp()==absorberHp&&walls.volume(wall).hp()==hp,
                "actual native flame pulse retains length through ordinary and Plastanium upper projections");
            flameShot.remove();
            // The same family policy must apply to native physical bases, not only their upper
            // projections. Native pierceDamageFactor is zero for these small fixture bullets.
            var lowMuzzle=new WorldCoordinates.Point(x-3,y,Math.min(v.baseZ(),av.baseZ())*.5);
            float beforeOrdinary=wall.health,beforeAbsorber=absorber.health;
            Bullet railBase=combat.launchPhysical(rail,null,enemy,lowMuzzle,direction,-1);owned.add(railBase);
            require(railBase!=null&&railBase.fdata>(av.x()-lowMuzzle.x()+2)*Vars.tilesize&&wall.health<beforeOrdinary&&absorber.health<beforeAbsorber,
                "native Rail damages and pierces both ordinary and Plastanium physical bases");
            beforeOrdinary=wall.health;beforeAbsorber=absorber.health;
            Bullet flameBase=combat.launchPhysical(flame,null,enemy,lowMuzzle,direction,-1);owned.add(flameBase);
            flameBase.time=flameBase.lifetime*.5f;flameBase.timer.reset(1,flame.damageInterval);flameBase.update();
            require(flameBase.fdata>(av.x()-lowMuzzle.x()+2)*Vars.tilesize&&wall.health<beforeOrdinary&&absorber.health<beforeAbsorber,
                "native flame damages and pierces both physical bases with laserAbsorb disabled");flameBase.remove();

        }finally{
            Time.delta=delta;for(Bullet bullet:owned)if(bullet!=null&&bullet.isAdded())bullet.remove();
        }
    }
    private static void advance(Bullet bullet,int frames){for(int i=0;i<frames&&bullet!=null&&bullet.isAdded();i++)bullet.update();}
    private static Building place(ArrayList<Building> owned,int x,int y,mindustry.world.Block block){Tile t=Vars.world.tile(x,y);t.setBlock(block,Vars.player.team(),0);owned.add(t.build);return t.build;}
    private static Tile findSite(){for(int y=2;y+15<Vars.world.height();y++)for(int x=2;x+21<Vars.world.width();x++){
        boolean empty=true;scan:for(int dy=0;dy<15;dy++)for(int dx=0;dx<21;dx++){Tile t=Vars.world.tile(x+dx,y+dy);if(t==null||t.build!=null||t.block()!=Blocks.air||t.floor().solid){empty=false;break scan;}}
        if(empty)return Vars.world.tile(x,y);}return null;}
    private static void capture(String name){arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void require(boolean b,String label){if(!b)throw new IllegalStateException("Projected wall smoke: "+label);}
}
