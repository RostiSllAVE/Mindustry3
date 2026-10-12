package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.world.blocks.units.RepairTurret;

/** Used only by the existing guarded localhost lab; no ordinary player-profile entrypoint. */
public final class NetworkSupportEffectsChecks {
    private final SupportEffectsState state;
    private RepairTurret.RepairPointBuild serverRepair;
    private Unit serverTarget;
    private float initialHealth;
    private boolean clientVerified;
    public NetworkSupportEffectsChecks(SupportEffectsState state){this.state=state;}
    public void serverSetup(){
        if(!Vars.headless||!Vars.net.server()||!System.getProperty("fpv.netSmokeRole","").equals("server"))throw new IllegalStateException("Dedicated isolated lab required");
        Vars.world.tile(20,65).setNet(Blocks.repairPoint,Team.sharded,0);
        Vars.world.tile(20,66).setNet(Blocks.powerSource,Team.sharded,0);
        serverRepair=(RepairTurret.RepairPointBuild)Vars.world.build(20,65);
        // Native power adjacency/graph supplies native healing consumption.
        Vars.world.build(20,66).configureAny(serverRepair.pos());
        serverTarget=UnitTypes.zenith.create(Team.sharded);serverTarget.set(24*Vars.tilesize,65*Vars.tilesize);
        serverTarget.controller(new AIController(){@Override public void updateMovement(){}});
        initialHealth=serverTarget.health=50;serverTarget.add();
        serverRepair.target=serverTarget;serverRepair.rotation=0;
    }
    public boolean serverReady(){
        if(serverRepair==null||serverTarget==null||serverTarget.dead||serverRepair.target!=serverTarget||serverRepair.strength<.2||serverTarget.health<=initialHealth)return false;
        return state.snapshot().stream().anyMatch(e->e.kind()==SupportEffectsState.TURRET&&e.sourceRef()==serverRepair.pos()&&e.targetRef()==serverTarget.id);
    }
    public boolean clientReady(FpvWorldRenderer renderer){
        if(clientVerified)return true;
        if(renderer==null||!Vars.net.client()||!(Vars.world.build(20,65) instanceof RepairTurret.RepairPointBuild repair))return false;
        SupportEffectsState.Effect effect=null;
        for(var e:state.remote())if(e.kind()==SupportEffectsState.TURRET&&e.sourceRef()==repair.pos()){effect=e;break;}
        if(effect==null||effect.strength()<.1)return false;
        Unit target=Groups.unit.getByID(effect.targetRef());if(target==null||target.type.id!=effect.targetType())return false;
        Unit originalTarget=repair.target;float originalStrength=repair.strength,originalEfficiency=repair.efficiency;
        float health=target.health;
        try{
            // Client native selection is deliberately absent. Only the server snapshot can produce this beam.
            repair.target=null;repair.strength=0;repair.efficiency=0;
            Camera3D camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());
            camera.position.set(26,13,-56);camera.lookAt(22,3,-65);camera.near=.05f;camera.far=1000;camera.fov=52;camera.update();
            renderer.render(camera);
            require(renderer.supportEffects().wasHealingSubmitted(repair),"authoritative healing renders while local target/strength absent");
            var beam=renderer.supportEffects().healingBeam(repair);
            require(beam!=null&&beam.targetId()==target.id&&Math.abs(beam.bx()-target.x/Vars.tilesize)<.2&&Math.abs(beam.by()-target.y/Vars.tilesize)<.2&&beam.bz()>beam.az()+.5,
                "replicated repair beam reaches synchronized flying target XYZ");
            require(target.health==health&&repair.target==null&&repair.strength==0&&repair.efficiency==0,"replica drawing never mutates native heal state");
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.netSmokeRoot")).child(System.getProperty("fpv.netSmokeRole")+"-support-effects.png"));
            clientVerified=true;return true;
        }finally{repair.target=originalTarget;repair.strength=originalStrength;repair.efficiency=originalEfficiency;}
    }
    private static void require(boolean value,String message){if(!value)throw new IllegalStateException("Network support smoke: "+message);}
}
