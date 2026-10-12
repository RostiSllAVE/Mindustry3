package fpv.defense;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import fpv.combat.Combat3DSystem;
import fpv.render.FpvWorldRenderer;
import fpv.world.World3DAdapter;
import fpv.world.WorldCoordinates;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.entities.bullet.BasicBulletType;
import mindustry.entities.bullet.LaserBulletType;
import mindustry.entities.bullet.ContinuousLaserBulletType;
import mindustry.entities.bullet.RailBulletType;
import mindustry.entities.bullet.ContinuousFlameBulletType;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.ForceProjector;

/** Real native building lifecycle and production projectile/laser path in the explicitly isolated SDL lab. */
public final class ForceProjectorSmokeChecks {
    private ForceProjectorSmokeChecks(){}
    public static String verify(ForceProjectorSystem fields,FpvWorldRenderer renderer,World3DAdapter world,Combat3DSystem combat){
        require(Boolean.getBoolean("fpv.smoke")&&Vars.state.isGame(),"isolated loaded lab required");
        Tile site=findSite();require(site!=null,"empty projector fixture area");
        Tile anchor=Vars.world.tile(site.x+10,site.y+8);var owned=new ArrayList<Bullet>();float oldDelta=Time.delta;
        anchor.setBlock(Blocks.forceProjector,Vars.player.team(),0);var b=(ForceProjector.ForceBuild)anchor.build;var block=(ForceProjector)b.block;
        try{
            Time.delta=1;b.broken=false;b.buildup=0;b.radscl=.45f;b.warmup=1;b.efficiency=1;b.phaseHeat=0;b.hit=0;
            fields.update(0);var v=fields.volume(b);require(v!=null&&Math.abs(v.radius()-b.realRadius()/Vars.tilesize)<1e-6,"native actual radius determines finite XYZ shell");
            require(v.sides()==block.sides&&Math.abs(v.top()-v.bottom()-v.radius())<1e-8,"same native polygon and finite protective height");
            double x=v.x(),y=v.y(),z=v.bottom()+Math.min(.5,(v.top()-v.bottom())*.3),radius=v.radius();
            Team enemy=b.team==Team.crux?Team.sharded:Team.crux;
            var contact=fields.trace(enemy,x-radius-2,y,z,x+radius+2,y,z,0);
            require(contact!=null,"hostile enters polygon side");
            require(fields.trace(b.team,x-radius-2,y,z,x+radius+2,y,z,0)==null,"allied projectile crosses both sides");
            require(fields.trace(enemy,x-radius-2,y,v.top()+1,x+radius+2,y,v.top()+1,0)==null,"enemy overflight passes finite altitude");
            require(fields.trace(enemy,x,y,z,x+radius+2,y,z,0).fraction()==0,"enemy born inside is intercepted before hitting protected target");
            require(fields.trace(enemy,x,y,v.top()+2,x,y,v.bottom(),0)!=null,"downward laser hits roof");
            float health=b.health;
            BasicBulletType shot=new BasicBulletType(8,13);shot.lifetime=60;shot.keepVelocity=false;shot.shieldDamageMultiplier=2;shot.init();shot.load();
            var direction=new WorldCoordinates.Point(1,0,0);var muzzle=new WorldCoordinates.Point(x-radius-2,y,z);
            Bullet hit=combat.launchPhysical(shot,null,enemy,muzzle,direction,-1);owned.add(hit);advance(hit,6);
            require(hit!=null&&!hit.isAdded()&&b.buildup==26,"managed native bullet absorbed with original shield damage multiplier");
            require(b.health==health,"shield collision protects original building HP");
            float buildup=b.buildup;
            Bullet friendly=combat.launchPhysical(shot,null,b.team,muzzle,direction,-1);owned.add(friendly);advance(friendly,6);
            require(friendly.isAdded()&&combat.managed(friendly)&&b.buildup==buildup,"actual allied managed projectile is not absorbed");friendly.remove();
            Bullet high=combat.launchPhysical(shot,null,enemy,new WorldCoordinates.Point(x-radius-2,y,v.top()+1),direction,-1);owned.add(high);advance(high,6);
            require(high.isAdded()&&combat.managed(high)&&b.buildup==buildup,"actual hostile above roof is not absorbed");high.remove();
            LaserBulletType laser=new LaserBulletType(19);laser.length=(float)((radius*2+4)*Vars.tilesize);laser.init();
            Bullet beam=combat.launchPhysical(laser,null,enemy,muzzle,direction,-1);owned.add(beam);
            require(beam!=null&&b.buildup>buildup&&b.health==health,"instantaneous managed laser stops on same shield pool");buildup=b.buildup;
            ContinuousLaserBulletType continuous=new ContinuousLaserBulletType(8);continuous.length=laser.length;continuous.init();
            Bullet ray=combat.launchPhysical(continuous,null,enemy,muzzle,direction,-1);owned.add(ray);advance(ray,8);
            require(ray!=null&&b.buildup>buildup&&b.health==health,"continuous managed laser also absorbed at true volume");ray.remove();
            buildup=b.buildup;RailBulletType rail=new RailBulletType();rail.damage=13;rail.length=laser.length;rail.init();
            Bullet railShot=combat.launchPhysical(rail,null,enemy,muzzle,direction,-1);owned.add(railShot);
            require(railShot!=null&&b.buildup>buildup&&b.health==health,"Rail wall exception does not bypass ForceProjector field");
            buildup=b.buildup;ContinuousFlameBulletType flame=new ContinuousFlameBulletType(9);flame.length=laser.length;flame.init();
            Bullet flameShot=combat.launchPhysical(flame,null,enemy,muzzle,direction,-1);owned.add(flameShot);
            flameShot.time=flameShot.lifetime*.5f;flameShot.timer.reset(1,flame.damageInterval);flameShot.update();
            require(b.buildup>buildup&&b.health==health,"laserAbsorb=false flame remains intercepted by ForceProjector field");flameShot.remove();
            Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.05f;camera.fov=65;
            camera.position.set((float)x+15,12,(float)-y+15);camera.up.set(0,1,0);camera.lookAt((float)x,3,(float)-y);camera.far=1000;camera.update();
            renderer.render(camera);require(renderer.wasForceProjectorSubmitted(b),"native projector shell submitted to real GPU world pass");capture("force-projector-active.png");
            var codec=ForceProjectorCodec.decode(ForceProjectorCodec.encode(fields.snapshot(),Vars.world.width()*Vars.world.height()),Vars.world.width()*Vars.world.height());
            try(var replica=new ForceProjectorSystem(world,()->false)){
                replica.applySnapshot(codec);require(replica.volume(b)!=null,"client accepts native lifecycle replica");float before=b.buildup;
                require(!replica.hit(replica.trace(enemy,x-radius-2,y,z,x+radius+2,y,z,0),null,1000)&&b.buildup==before,"client cannot apply shield damage");
                replica.applySnapshot(java.util.List.of());require(replica.volume(b)==null,"complete replica deletion removes ghost shell");
            }
            // A shield-breaking hit must consume the projectile payload rather than emit fragments through the collapsed field.
            BasicBulletType fragment=new BasicBulletType(8,100);fragment.lifetime=30;fragment.init();fragment.load();
            BasicBulletType cluster=new BasicBulletType(8,20);cluster.lifetime=60;cluster.keepVelocity=false;cluster.fragBullet=fragment;cluster.fragBullets=7;
            cluster.fragOnHit=true;cluster.fragOnDespawn=true;cluster.splashDamage=100;cluster.splashDamageRadius=200;cluster.init();cluster.load();
            var prior=new java.util.HashSet<Integer>();for(Bullet bullet:mindustry.gen.Groups.bullet)prior.add(bullet.id);
            b.buildup=block.shieldHealth-10;Bullet breaking=combat.launchPhysical(cluster,null,enemy,muzzle,direction,-1);owned.add(breaking);advance(breaking,6);
            require(breaking!=null&&!breaking.isAdded(),"breaking cluster shot absorbed");
            for(Bullet bullet:mindustry.gen.Groups.bullet)require(prior.contains(bullet.id),"absorption emits no native or managed fragment child");
            require(b.health==health,"breaking absorption suppresses splash payload");
            require(b.broken&&b.buildup==block.shieldHealth&&fields.volume(b)==null,"native broken and buildup state applied immediately");
            require(!fields.hit(contact,null,10)&&fields.trace(enemy,x-radius-2,y,z,x+radius+2,y,z,0)==null,"broken shell no longer blocks and stale token rejected");
            renderer.render(camera);require(!renderer.wasForceProjectorSubmitted(b)&&renderer.wasBuildingSubmitted(b),"broken shell disappears while native block stays");capture("force-projector-broken.png");
            b.buildup=1;for(int i=0;i<10;i++)b.updateTile();require(!b.broken,"native cooldown recovers original projector");
            b.buildup=100;b.liquids.clear();float before=b.buildup;b.updateTile();float ordinaryCooling=before-b.buildup;
            b.liquids.add(Liquids.water,10);before=b.buildup;float coolant=b.liquids.get(Liquids.water);b.updateTile();
            require(before-b.buildup>ordinaryCooling&&b.liquids.get(Liquids.water)<coolant,"native coolant accelerates regeneration and is consumed");
            b.buildup=0;b.phaseHeat=0;b.radscl=.45f;b.warmup=1;b.efficiency=1;double plain=fields.volume(b).radius();
            b.phaseHeat=1;require(fields.volume(b).radius()>plain,"phase boost scales protective radius and height together");
            b.phaseHeat=0;b.items.clear();b.efficiency=0;for(int i=0;i<200;i++)b.updateTile();
            require(fields.volume(b)==null||fields.volume(b).radius()<.01,"native power loss fades shield to zero");
            b.efficiency=1;for(int i=0;i<120;i++)b.updateTile();require(fields.volume(b)!=null&&fields.volume(b).radius()>plain,"native power restore grows actual protective shell");
            renderer.render(camera);require(renderer.wasForceProjectorSubmitted(b),"recovered shield visible again");capture("force-projector-recovered.png");
            require(b.health==health,"all shield tests preserve base HP and native block");
            var removed=fields.trace(enemy,x-block.radius/8-2,y,z,x+block.radius/8+2,y,z,0);anchor.setBlock(Blocks.air);fields.update(0);
            require(fields.volume(b)==null&&!fields.hit(removed,null,1),"removed projector invalidates contact and mesh source");
            return "PASS: native projector finite XYZ polygon, real managed bullet/instant laser/continuous laser/Rail/flame absorption, shield damage multiplier, friendly pass/overflight/roof/inside contact, native break/coolant/power/phase/recovery, client read-only snapshots, removed-anchor safety, real GPU active/broken/recovered captures";
        }finally{
            Time.delta=oldDelta;for(Bullet bullet:owned)if(bullet!=null&&bullet.isAdded())bullet.remove();
            if(anchor.build==b)anchor.setBlock(Blocks.air);fields.update(0);renderer.invalidate();
        }
    }
    private static void advance(Bullet b,int frames){for(int i=0;i<frames&&b!=null&&b.isAdded();i++)b.update();}
    private static Tile findSite(){for(int y=2;y+19<Vars.world.height();y++)for(int x=2;x+21<Vars.world.width();x++){
        boolean empty=true;scan:for(int dy=0;dy<19;dy++)for(int dx=0;dx<21;dx++){Tile t=Vars.world.tile(x+dx,y+dy);if(t==null||t.build!=null||t.block()!=Blocks.air||t.floor().solid){empty=false;break scan;}}
        if(empty)return Vars.world.tile(x,y);}return null;}
    private static void capture(String name){arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child(name));}
    private static void require(boolean b,String label){if(!b)throw new IllegalStateException("Force projector smoke: "+label);}
}
