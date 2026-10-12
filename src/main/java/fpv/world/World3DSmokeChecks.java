package fpv.world;

import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.world.Tile;
import mindustry.content.UnitTypes;
import mindustry.game.Team;

/** Isolated native lab queries plus disposable-unit impulses; no map, rule, save or player mutation. */
public final class World3DSmokeChecks {
    private World3DSmokeChecks(){}
    public static void run(World3DAdapter world){
        if(!Boolean.getBoolean("fpv.smoke"))throw new IllegalStateException("World checks require isolated smoke launch");
        int tested=0;
        int stride=Math.max(1,Vars.world.width()/13);
        for(int y=0;y<Vars.world.height()&&tested<24;y+=stride){
            for(int x=0;x<Vars.world.width()&&tested<24;x+=stride){
                Tile tile=Vars.world.tile(x,y);
                if(tile==null)continue;
                double px=x+.13,py=y-.17;
                double height=world.surfaceHeight(px,py);
                var hit=world.traceSegment(px,py,height+2,px,py,height-1,0);
                require(hit.hit()&&Math.abs(hit.z()-height)<1e-5,"segment top agrees with physical terrain/structure query");
                require(Math.abs(hit.fraction()-2.0/3)<1e-5,"vertical surface crossing is continuous");
                require(world.lineOfSight(px,py,height+1,px,py,height+2),"clear vertical LOS above surface");
                require(!world.lineOfSight(px,py,height+1,px,py,height-.2),"surface blocks XYZ LOS");
                tested++;
            }
        }
        require(tested>0,"loaded map has physical query samples");
        Unit player=Vars.player==null?null:Vars.player.unit();
        if(player!=null&&!player.dead&&player.isAdded()){
            var physical=world.unitState(player);
            require(physical!=null&&physical.unitId()==player.id&&physical.teamId()==player.team.id,"player physical snapshot available");
            require(physical.radius()>0&&physical.halfHeight()>0,"native/mesh physical bounds available");
            var above=new Projectile3DState(1,player.team.id,physical.x(),physical.y(),physical.z()+physical.halfHeight()+3,
                0,0,0,.1,1,1);
            require(!above.overlaps(physical),"projectile above player misses despite identical XY");
        }
        impulses(world);
    }

    private static void impulses(World3DAdapter world){
        Unit flyer=UnitTypes.flare.create(Team.sharded),ground=UnitTypes.dagger.create(Team.sharded);
        try{
            float x=Vars.world.width()*Vars.tilesize*.5f,y=Vars.world.height()*Vars.tilesize*.5f;
            flyer.set(x,y);flyer.add();ground.set(x,y);ground.add();
            flyer.vel.setZero();ground.vel.setZero();
            require(world.addVelocity(flyer,3,4,2),"XYZ impulse accepted for native flyer");
            var state=world.unitState(flyer);
            require(Math.abs(state.vx()-3)<1e-5&&Math.abs(state.vy()-4)<1e-5&&Math.abs(state.vz()-2)<1e-5,
                "XYZ impulse converts native mass/game units/ticks exactly once");
            world.addVelocity(flyer,300,400,0);
            var clamped=world.unitState(flyer);
            require(Math.abs(Math.hypot(clamped.vx()-state.vx(),clamped.vy()-state.vy())-200)<1e-3,"horizontal impulse magnitude bounded");
            world.addVelocity(flyer,0,0,100000);
            require(Math.abs(world.unitState(flyer).vz())<=30,"vertical impulse bounded");
            require(!world.addVelocity(ground,0,0,10)&&!world.unitState(ground).flying(),"ground body cannot gain free flight from impulse");
            flyer.dead=true;float vx=flyer.vel.x,vy=flyer.vel.y;
            require(!world.addVelocity(flyer,10,20,30)&&flyer.vel.x==vx&&flyer.vel.y==vy,"dead unit impulse ignored");
        }finally{
            if(flyer.isAdded())flyer.remove();if(ground.isAdded())ground.remove();
        }
    }
    private static void require(boolean pass,String label){if(!pass)throw new IllegalStateException("World3D smoke failed: "+label);}
}
