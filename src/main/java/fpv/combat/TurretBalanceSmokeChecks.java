package fpv.combat;

import arc.util.Log;
import arc.util.Time;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.Tile;
import mindustry.world.blocks.defense.turrets.*;
import fpv.world.World3DAdapter;

/** Actual native build updates verify acquisition, ammo consumption and true XYZ emission together. */
public final class TurretBalanceSmokeChecks {
    private TurretBalanceSmokeChecks() {}
    public static String run(World3DAdapter world,Combat3DSystem combat){
        require(Boolean.getBoolean("fpv.smoke"),"isolated laboratory only");
        float delta=Time.delta;var owned=new ArrayList<Unit>();var placed=new ArrayList<Tile>();
        try{
            combat.clear();int slots=0;
            for(var type:Vars.content.units())for(var weapon:type.weapons){
                require(weapon.bullet!=combat.originalType(weapon.bullet),"native unit slot has safe XYZ interception: "+type.name+"/"+weapon.name);slots++;
            }
            Tile site=findSite();require(site!=null,"clear AA fixture site");
            Tile fuseTile=Vars.world.tile(site.x+3,site.y+4);fuseTile.setBlock(Blocks.fuse,Team.sharded,0);placed.add(fuseTile);
            var fuse=(ItemTurret.ItemTurretBuild)fuseTile.build;var type=(ItemTurret)Blocks.fuse;
            for(int i=0;i<10;i++)fuse.handleItem(fuse,Items.thorium);
            Unit target=UnitTypes.eclipse.create(Team.crux);target.spawnedByCore=true;target.set((site.x+11)*Vars.tilesize,(site.y+4)*Vars.tilesize);
            target.controller(new AIController(){@Override public void updateUnit(){}});target.add();target.health=100000;owned.add(target);
            Vars.state.teams.updateTeamStats();
            var body=world.unitState(target);double muzzleZ=world.surfaceHeight(fuse.x/Vars.tilesize,fuse.y/Vars.tilesize)+.4;
            require(body.z()>muzzleZ+2,"AA target has real altitude above the turret");
            Time.delta=1;fuse.efficiency=fuse.potentialEfficiency=1;fuse.reloadCounter=type.reload;fuse.shootWarmup=1;
            fuse.rotation=0;fuse.timer.reset(type.timerTarget,100);int ammo=fuse.totalAmmo;float health=target.health;
            fuse.updateTile();
            require(fuse.target==target,"actual Fuse build acquires physical aerial target");
            require(fuse.totalAmmo<ammo,"native turret update consumes original ammo");
            require(target.health<health,"actual turret-fired XYZ beam reaches aerial target");
            require(combat.visuals().stream().anyMatch(v->v.kind()==CombatVisual.Kind.BEAM&&v.bz()>v.az()+1),"actual turret emission contains a rising 3D beam");
            combat.clear();
            // Hail's own targetAir=false must survive the new selection adapter.
            Tile hailTile=Vars.world.tile(site.x+3,site.y+9);hailTile.setBlock(Blocks.hail,Team.sharded,0);placed.add(hailTile);
            var hail=(ItemTurret.ItemTurretBuild)hailTile.build;var hailType=(ItemTurret)Blocks.hail;
            hail.handleItem(hail,Items.copper);hail.efficiency=hail.potentialEfficiency=1;hail.timer.reset(hailType.timerTarget,100);hail.updateTile();
            require(hail.target!=target,"native ground-only Hail cannot acquire an aerial target");
            return "passed: "+slots+" native unit slots bound; native Fuse acquisition/ammo/XYZ hit and ground-only Hail exclusion";
        }finally{combat.clear();Time.delta=delta;for(Unit unit:owned)if(unit.isAdded())unit.remove();for(Tile tile:placed)tile.setBlock(Blocks.air);Vars.state.teams.updateTeamStats();}
    }
    private static Tile findSite(){
        for(int y=6;y<Vars.world.height()-20;y+=2)for(int x=6;x<Vars.world.width()-25;x+=2){boolean clear=true;
            for(int oy=0;oy<15&&clear;oy++)for(int ox=0;ox<21;ox++){Tile tile=Vars.world.tile(x+ox,y+oy);if(tile.block()!=Blocks.air||tile.build!=null){clear=false;break;}}
            if(clear)return Vars.world.tile(x,y);
        }return null;
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Turret balance smoke: "+message);Log.info("[FPV-AA] OK: @",message);}
}
