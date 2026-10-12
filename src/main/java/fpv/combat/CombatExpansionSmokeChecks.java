package fpv.combat;

import arc.util.Log;
import arc.util.Time;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.entities.bullet.*;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import fpv.world.World3DAdapter;

/** Native, isolated-lab coverage for the ordinary weapon families excluded by the first migration. */
public final class CombatExpansionSmokeChecks {
    private CombatExpansionSmokeChecks() {}
    public static String run(World3DAdapter world,Combat3DSystem combat){
        require(Boolean.getBoolean("fpv.smoke"),"isolated lab only");
        var owned=new ArrayList<Unit>();var walls=new ArrayList<Tile>();float oldDelta=Time.delta;
        try{
            combat.clear();Tile site=findSite();require(site!=null,"ordinary projectile fixture site");
            double x=site.x+2,y=site.y+2;
            Unit ground=spawn(owned,false,Team.sharded,x,y),air=spawn(owned,true,Team.crux,x+7,y);
            Unit reverseGround=spawn(owned,false,Team.crux,x,y+3),reverseAir=spawn(owned,true,Team.sharded,x+7,y+3);
            Vars.state.teams.updateTeamStats();
            BulletType reign=UnitTypes.reign.weapons.first().bullet;
            BulletType eclipse=null;for(var weapon:UnitTypes.eclipse.weapons)if(combat.originalType(weapon.bullet) instanceof FlakBulletType){eclipse=weapon.bullet;break;}
            BulletType missile=UnitTypes.zenith.weapons.first().bullet,artillery=UnitTypes.fortress.weapons.first().bullet,bomb=UnitTypes.horizon.weapons.first().bullet;
            BulletType spectre=((ItemTurret)Blocks.spectre).ammoTypes.get(Items.pyratite),scepter=UnitTypes.scepter.weapons.first().bullet;
            BulletType[] nativeTypes={reign,eclipse,missile,artillery,bomb,spectre,scepter};
            for(BulletType type:nativeTypes)require(type!=null&&type!=combat.originalType(type),"actual native ordinary family is bound: "+(type==null?"missing":combat.originalType(type).getClass().getSimpleName()));

            float before=air.health;Bullet reignShot=create(reign,ground,air);advance(reignShot,60);
            require(air.health<before,"actual Reign fragment/splash shot aims and hits physical altitude");
            require(combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.PROJECTILE&&v.az()>1),"Reign fragments retain aerial contact altitude");
            combat.clear();before=reverseAir.health;advance(create(reign,reverseGround,reverseAir),60);
            require(reverseAir.health<before,"enemy Reign ordinary projectile uses the same XYZ path");
            combat.clear();before=air.health;advance(create(missile,ground,air),180);
            require(air.health<before,"actual Zenith homing/splash missile reaches 3D target");
            combat.clear();before=air.health;advance(create(eclipse,ground,air),120);
            require(air.health<before,"actual Eclipse flak uses XYZ proximity/contact and area damage");
            combat.clear();
            before=air.health;Bullet electric=create(scepter,ground,air);advance(electric,90);
            require(air.health<before,"actual Scepter main cannon and interval lightning use physical XYZ");
            require(combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.BEAM&&Math.abs(v.bz()-v.az())>.01),"Scepter retains true XYZ lightning segments");
            final boolean[] leaked={false};Groups.bullet.each(b->{if(b.owner==ground&&!combat.managed(b))leaked[0]=true;});
            require(!leaked[0],"managed Scepter cannot emit native stationary XY lightning bullets");
            combat.clear();
            for(int row=-1;row<=1;row++){Tile tile=Vars.world.tile((int)x+2,(int)y+row);tile.setBlock(Blocks.plastaniumWall,Team.crux,0);walls.add(tile);}
            float[] wallHealth=new float[walls.size()];for(int i=0;i<walls.size();i++)wallHealth[i]=walls.get(i).build.health;
            LightningBulletType airOnly=new LightningBulletType();airOnly.damage=25;airOnly.collidesGround=false;airOnly.lightningLength=8;airOnly.lightningLengthRand=0;airOnly.init();
            create(combat.adapt(airOnly),ground,null);
            for(int i=0;i<walls.size();i++)require(walls.get(i).build.health==wallHealth[i],"air-only lightning is blocked by insulation without damaging a ground building");
            require(combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.BEAM&&v.bx()>x+1&&v.bx()<x+2),"insulated wall clips the actual XYZ lightning segment");
            combat.clear();for(Tile tile:walls)tile.setBlock(Blocks.air);walls.clear();

            // Small blast detonates against an aerial body over a ground victim: XY overlap is insufficient.
            reverseGround.set(air.x,air.y);Vars.state.teams.updateTeamStats();
            BasicBulletType area=new BasicBulletType(8,0);area.keepVelocity=false;area.lifetime=20;area.splashDamage=50;area.splashDamageRadius=Vars.tilesize;area.init();
            float groundBefore=reverseGround.health,airBefore=air.health;
            Unit upper=UnitTypes.eclipse.create(Team.sharded);upper.spawnedByCore=true;upper.set(air.x,air.y);
            upper.controller(new AIController(){@Override public void updateUnit(){}});upper.add();upper.health=10000;owned.add(upper);
            Vars.state.teams.updateTeamStats();require(world.unitState(upper).z()>world.unitState(air).z()+1,"large native flyer supplies a genuinely elevated blast shooter");
            // A vertical top contact lies inside the unscaled one-tile center-distance blast.
            Bullet highBlast=create(combat.adapt(area),upper,air);advance(highBlast,120);
            require(air.health<airBefore&&reverseGround.health==groundBefore,"aerial splash damages its aerial target but cannot damage an enemy ground body merely sharing XY");
            combat.clear();upper.remove();Vars.state.teams.updateTeamStats();
            reverseAir.set(reverseGround.x,reverseGround.y);Vars.state.teams.updateTeamStats();
            LightningBulletType shortArc=new LightningBulletType();shortArc.damage=12;shortArc.lightningLength=2;shortArc.lightningLengthRand=0;shortArc.init();
            groundBefore=reverseGround.health;create(combat.adapt(shortArc),reverseAir,reverseGround);
            require(reverseGround.health==groundBefore,"aerial lightning origin cannot damage ground at the same XY beyond its 3D length budget");
            combat.clear();

            // A deliberately short powered range ends in a damaging physical fall, not disappearance.
            reverseGround.set((float)((x+15)*Vars.tilesize),(float)((y+3)*Vars.tilesize));
            reverseAir.set((float)(x*Vars.tilesize),(float)((y+3)*Vars.tilesize));Vars.state.teams.updateTeamStats();
            BasicBulletType fallingType=new BasicBulletType(.5f,15);fallingType.keepVelocity=false;fallingType.lifetime=64;fallingType.init();
            // A low physical emitter allows ground contact within the new damaging 1..1.5R tail.
            // A four-tick range from high cruise altitude would correctly expire before it could land.
            Bullet falling=combat.launchPhysical(fallingType,reverseAir,reverseAir.team,
                new fpv.world.WorldCoordinates.Point(x,y+3,world.terrainHeight(x,y+3)+1),new fpv.world.WorldCoordinates.Point(1,0,0),-1);
            double launchZ=combat.projectileState(falling).z();float fullDamage=falling.damage;advance(falling,70);
            var state=combat.projectileState(falling);
            require(falling.isAdded()&&state!=null&&state.falling()&&state.z()<launchZ&&state.vz()<0&&state.traveled()>state.poweredRange()
                &&state.x()-x<state.poweredRange()*1.5&&falling.damage>0&&falling.damage<fullDamage,"past-range projectile descends with diminishing damage inside the 1..1.5R tail");
            reverseGround.set((float)((x+5.3)*Vars.tilesize),(float)((y+3)*Vars.tilesize));
            Vars.state.teams.updateTeamStats();float fallingBefore=reverseGround.health;
            advance(falling,240);require(reverseGround.health<fallingBefore,"spent falling projectile can still damage a later ground contact");
            combat.clear();

            // Artillery rises above the source/target chord; bombs accelerate downward immediately.
            ground.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));reverseGround.set((float)((x+10)*Vars.tilesize),(float)(y*Vars.tilesize));
            Vars.state.teams.updateTeamStats();Bullet arc=create(artillery,ground,reverseGround);
            var initialArc=combat.projectileState(arc);advance(arc,6);var laterArc=combat.projectileState(arc);
            require(initialArc!=null&&laterArc!=null&&laterArc.z()>initialArc.z(),"native Fortress artillery has a real rising Z arc");
            combat.clear();Bullet dropped=create(bomb,reverseAir,null);var initialBomb=combat.projectileState(dropped);advance(dropped,6);var laterBomb=combat.projectileState(dropped);
            require(initialBomb!=null&&laterBomb!=null&&laterBomb.vz()<initialBomb.vz()&&laterBomb.z()<initialBomb.z(),"native Horizon bomb falls continuously in XYZ");
            combat.clear();
            return "passed: native Reign/Eclipse/Zenith/Fortress/Horizon/Spectre/Scepter coverage, both-team XYZ contacts, fragment altitude, physical lightning, aerial area miss, damaging beyond-range fall, artillery arc and bomb gravity; "+combat.coverage()+", fallback="+combat.fallbackReasons();
        }finally{combat.clear();Time.delta=oldDelta;for(Unit unit:owned)if(unit.isAdded())unit.remove();for(Tile tile:walls)tile.setBlock(Blocks.air);Vars.state.teams.updateTeamStats();}
    }
    private static Tile findSite(){
        for(int y=6;y<Vars.world.height()-12;y+=2)for(int x=6;x<Vars.world.width()-22;x+=2){
            boolean clear=true;for(int oy=0;oy<8&&clear;oy++)for(int ox=0;ox<19;ox++){var tile=Vars.world.tile(x+ox,y+oy);if(tile.block()!=Blocks.air||tile.build!=null){clear=false;break;}}
            if(clear)return Vars.world.tile(x,y);
        }return null;
    }
    private static Unit spawn(ArrayList<Unit> owned,boolean flying,Team team,double x,double y){
        Unit unit=(flying?UnitTypes.flare:UnitTypes.dagger).create(team);unit.spawnedByCore=true;unit.set((float)(x*Vars.tilesize),(float)(y*Vars.tilesize));
        unit.controller(new AIController(){@Override public void updateUnit(){}});unit.add();unit.health=10000;owned.add(unit);return unit;
    }
    private static Bullet create(BulletType type,Unit owner,Unit target){
        float angle=target==null?0:(float)Math.toDegrees(Math.atan2(target.y-owner.y,target.x-owner.x));
        return type.create(owner,owner,owner.team,owner.x,owner.y,angle,-1,1,1,null,null,target==null?-1:target.x,target==null?-1:target.y,target);
    }
    private static void advance(Bullet bullet,int ticks){Time.delta=1;for(int i=0;i<ticks&&bullet.isAdded();i++)bullet.update();}
    private static void require(boolean condition,String label){if(!condition)throw new IllegalStateException("Combat expansion smoke: "+label);Log.info("[FPV-COMBAT-EXPANSION] OK: @",label);}
}
