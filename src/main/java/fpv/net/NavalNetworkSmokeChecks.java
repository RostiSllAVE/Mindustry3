package fpv.net;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import fpv.render.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.UnitController;
import mindustry.game.Team;
import mindustry.gen.*;
import java.io.*;

/** Uses the existing lab-ground fixture payload; production naval angles use the existing pitch channel. */
final class NavalNetworkSmokeChecks {
    private final FpvNetwork network;
    private final Unit[] ships=new Unit[10];private final int[] ids=new int[10];private final double[] angles=new double[10];
    private final java.util.LinkedHashMap<Long,double[]> published=new java.util.LinkedHashMap<>();
    private long revision,receivedRevision,verifiedRevision;private double[] observed;
    NavalNetworkSmokeChecks(FpvNetwork network){this.network=network;}
    void setup(){
        // The network fixture reloads to 104x96 (the visual gallery is 128x128).
        for(int y=41;y<73;y++)for(int x=11;x<101;x++)Vars.world.tile(x,y).setFloor(Blocks.water.asFloor());
        for(int i=0;i<ships.length;i++){
            var ship=NavalHullProfile.types().get(i).create(Team.sharded);ship.set((18+i%5*18)*8,(48+i/5*17)*8);ship.rotation=0;
            // Instance-only lab isolation: area abilities must not kill the shared aiming fixtures.
            ship.abilities=new mindustry.entities.abilities.Ability[0];
            ship.apply(mindustry.content.StatusEffects.disarmed,36000); // angle-only fixture; no automatic laser fire
            var target=mindustry.content.UnitTypes.flare.create(Team.crux);target.set(ship.x+6*8,ship.y);target.controller(new Still(null));target.add();
            ship.controller(new Still(target));ship.add();ships[i]=ship;
        }
    }
    boolean ready(){for(var ship:ships)if(ship==null||network.fixtureCombat().navalWeapons().visual(ship,ship.mounts[0],NavalHullProfile.forType(ship.type))==null)return false;return true;}
    void plan(DataOutputStream out)throws IOException{
        double[] sample=new double[ships.length];out.writeLong(++revision);
        for(int i=0;i<ships.length;i++){var ship=ships[i];sample[i]=network.fixtureCombat().navalWeapons().visual(ship,ship.mounts[0],NavalHullProfile.forType(ship.type)).pitch();out.writeInt(ship.id);out.writeDouble(sample[i]);}
        published.put(revision,sample);if(published.size()>512)published.remove(published.keySet().iterator().next());
    }
    void accept(DataInputStream in)throws IOException{receivedRevision=in.readLong();if(receivedRevision<=0)throw new IOException("Naval fixture revision");for(int i=0;i<ships.length;i++){ids[i]=in.readInt();angles[i]=in.readDouble();if(ids[i]<0||!Double.isFinite(angles[i]))throw new IOException("Naval fixture identity/angle");}}
    boolean clientReady(FpvWorldRenderer renderer){
        for(int i=0;i<ids.length;i++){
            var ship=Groups.unit.getByID(ids[i]);if(ship==null||ship.type!=NavalHullProfile.types().get(i))return false;
            var remote=network.fixtureCombat().navalWeapons().remotePitch(ship,ship.mounts[0]);
            var gun=network.fixtureCombat().navalWeapons().visual(ship,ship.mounts[0],NavalHullProfile.forType(ship.type));
            if(remote==null||gun==null||Math.abs(remote-angles[i])>.15||Math.abs(gun.pitch()-remote)>.001)return false;
        }
        var camera=new Camera3D();camera.resize(Core.graphics.getWidth(),Core.graphics.getHeight());camera.near=.025f;camera.far=1000;camera.fov=60;
        camera.position.set(54,45,-12);camera.lookAt(54,0,-58);camera.update();renderer.render(camera);
        for(int id:ids){var ship=Groups.unit.getByID(id);if(!renderer.wasUnitSubmitted(ship)||renderer.unitGeometry(ship.type.region)==null)return false;}
        verifiedRevision=receivedRevision;observed=new double[ids.length];for(int i=0;i<ids.length;i++){var ship=Groups.unit.getByID(ids[i]);observed[i]=network.fixtureCombat().navalWeapons().visual(ship,ship.mounts[0],NavalHullProfile.forType(ship.type)).pitch();}
        arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.netSmokeRoot")).child(System.getProperty("fpv.netSmokeRole")+"-naval-pitch.png"));return true;
    }
    void report(DataOutputStream out)throws IOException{out.writeLong(verifiedRevision);for(int i=0;i<ids.length;i++){out.writeInt(ids[i]);out.writeDouble(observed[i]);}}
    void verify(DataInputStream in,boolean current)throws IOException{
        long sampleId=in.readLong();double[] sample=published.get(sampleId);
        for(int i=0;i<ships.length;i++){int id=in.readInt();double angle=in.readDouble();if(current&&(sample==null||id!=ships[i].id||!Double.isFinite(angle)||Math.abs(angle-sample[i])>.15))
            throw new IOException("Observer naval pitch disagrees with published sample "+sampleId+" "+ships[i].type.name+": "+angle+" / "+(sample==null?"expired":sample[i]));}
    }
    private static final class Still implements UnitController {
        private Unit unit;private final Unit target;Still(Unit target){this.target=target;}public Unit unit(){return unit;}public void unit(Unit value){unit=value;}
        public void updateUnit(){unit.vel.setZero();unit.controlWeapons(false,false);if(target!=null)for(var mount:unit.mounts){mount.target=target;mount.aimX=target.x;mount.aimY=target.y;}}
    }
}
