package fpv.units;

import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Unit;
import fpv.flight.FlightController;

/** Isolated lab-only checks using real native units; does not change map tiles or rules. */
public final class UnitAltitudeSmokeChecks {
    private UnitAltitudeSmokeChecks() {}

    public static void run() {
        if (!Boolean.getBoolean("fpv.smoke")) throw new IllegalStateException("Altitude smoke requires isolated smoke mode");
        Unit flyer = UnitTypes.flare.create(Team.sharded);
        Unit ground = UnitTypes.dagger.create(Team.sharded);
        Unit excluded = UnitTypes.flare.create(Team.sharded);
        double[] terrainHeight = {0};
        UnitAltitudeSystem system = new UnitAltitudeSystem((x, z) -> terrainHeight[0], () -> excluded);
        try {
            float x = Vars.world.width() * Vars.tilesize * 0.5f;
            float y = Vars.world.height() * Vars.tilesize * 0.5f;
            flyer.set(x, y); flyer.add();
            ground.set(x + Vars.tilesize, y); ground.add();
            excluded.set(x, y + Vars.tilesize); excluded.add();
            float originalX = flyer.x, originalY = flyer.y, elevation = flyer.elevation, rotation = flyer.rotation;
            float groundElevation = ground.elevation;
            system.update(1.0 / 60);
            require(system.pose(flyer) != null && system.pose(flyer).flying(), "native flyer gets altitude");
            require(system.pose(ground) != null && !system.pose(ground).flying(), "native ground remains ground");
            require(system.pose(excluded) == null, "FPV player exclusion");
            FlightController.State controlled=new FlightController.State();
            controlled.x=9;controlled.y=12;controlled.z=-7;controlled.vx=3;controlled.vy=2;controlled.vz=-4;
            system.setControlledStateProvider(()->controlled);
            var physical=system.unitState(excluded);
            require(physical!=null&&physical.x()==9&&physical.y()==7&&physical.z()==12,"controlled XYZ uses actual flight state");
            require(physical.vx()==3&&physical.vy()==4&&physical.vz()==2,"controlled XYZ velocity axes");
            require(system.addVerticalVelocity(excluded,3)&&controlled.vy==5,"controlled vertical impulse reaches actual controller");
            require(physical.vz()==2,"physical snapshot stays immutable after controller changes");
            require(excluded.x==x&&excluded.y==y+Vars.tilesize,"controlled query does not move native unit");
            double initialHeight = system.pose(flyer).height();
            system.update(0);
            require(system.pose(flyer).height() == initialHeight, "paused adapter height");
            terrainHeight[0] = 18;
            system.update(1.0 / 60);
            require(system.pose(flyer).height() > 18, "native flyer clears regenerated mountain");
            require(system.pose(ground).height() > 18, "ground sidecar follows terrain");
            var flyerPhysical=system.unitState(flyer);
            require(flyerPhysical.z()==system.pose(flyer).height()&&flyerPhysical.x()==flyer.x/(double)Vars.tilesize,
                "renderer and physical XYZ share one altitude authority");
            double vz=flyerPhysical.vz();
            require(system.addVerticalVelocity(flyer,2)&&system.unitState(flyer).vz()>vz,"native flyer receives physical Z impulse");
            require(!system.addVerticalVelocity(ground,2),"native ground unit remains attached after vertical impulse request");
            double[] slopeOffset={0};
            UnitAltitudeSystem slopeSystem=new UnitAltitudeSystem((rx,rz)->.25*rx-.125*rz+slopeOffset[0]);
            try{
                slopeSystem.update(1.0/60);
                var slopePose=slopeSystem.pose(ground);
                require(slopePose!=null&&(Math.abs(slopePose.pitch())>.1||Math.abs(slopePose.roll())>.1),"native ground slope orientation");
                require(slopeSystem.unitState(ground).z()==slopePose.height(),"slope contact is physical Z, not independent render offset");
                ground.set(ground.x+Vars.tilesize,ground.y);
                slopeSystem.update(.5);
                require(Math.abs(slopeSystem.unitState(ground).vz()-.5)<1e-6,"walking uphill publishes actual physical support VZ");
                slopeOffset[0]=10;
                slopeSystem.update(0);
                slopeSystem.update(.5);
                require(slopeSystem.unitState(ground).vz()==0,"terrain recovery after pause invents no vertical impact velocity");
                ground.set(ground.x+Vars.tilesize*40,ground.y);
                slopeSystem.update(.5);
                require(slopeSystem.unitState(ground).vz()==0,"native teleport resets physical ground VZ");
            }finally{slopeSystem.dispose();}
            require(flyer.x == originalX && flyer.y == originalY && flyer.elevation == elevation
                && flyer.rotation == rotation && ground.elevation == groundElevation, "native pose and elevation remain unchanged");
            int beforeRemoval = system.trackedCount();
            flyer.remove();
            system.update(1.0 / 60);
            require(system.pose(flyer) == null && system.trackedCount() == beforeRemoval - 1, "removed unit state pruned");
            ground.dead = true;
            system.update(1.0 / 60);
            require(system.pose(ground) == null, "dead unit state pruned");
            system.clear();
            require(system.trackedCount() == 0, "world cleanup clears sidecar");
        } finally {
            if (flyer.isAdded()) flyer.remove();
            if (ground.isAdded()) ground.remove();
            if (excluded.isAdded()) excluded.remove();
            system.dispose();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Unit altitude smoke failed: " + message);
    }
}
