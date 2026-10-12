package fpv.effects;

import fpv.world.World3DAdapter;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.entities.units.AIController;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.world.Tile;

/** Native damage regression fixtures. Run synchronously in the isolated lab; no game tick is advanced. */
public final class RadialExplosionSmokeChecks {
    private RadialExplosionSmokeChecks() {}

    public static String verify(World3DAdapter world) {
        require(Boolean.getBoolean("fpv.smoke"), "isolated smoke lab required");
        require(world != null && Vars.state.isGame() && Vars.player != null, "loaded physical world required");
        Tile site = findSite();
        require(site != null, "empty native fixture area");
        Team source = Vars.player.team(), enemyTeam = source == Team.crux ? Team.sharded : Team.crux;
        Building wall = null;
        Unit enemy = null, friend = null;
        RadialExplosion explosion = new RadialExplosion(world);
        var spec = new RadialExplosion.Spec(.2, 12, .2, .5, RadialExplosion.Style.sparks, false);
        try {
            Tile anchor = Vars.world.tile(site.x + 2, site.y + 2);
            anchor.setBlock(Blocks.copperWallLarge, enemyTeam, 0);
            wall = anchor.build;
            require(wall != null, "native large wall placed");
            double bx = wall.x / (double)Vars.tilesize, by = wall.y / (double)Vars.tilesize;
            double cornerX = bx + wall.block.size * .5 + .05, cornerY = by + wall.block.size * .5 + .05;
            require(Math.hypot(cornerX - bx, cornerY - by) > spec.radius() + wall.hitSize() / (2.0 * Vars.tilesize),
                "corner fixture lies outside native center-circle candidate filter");
            float wallHealth = wall.health;
            explosion.detonateXYZ(source, cornerX, cornerY, .3, spec);
            require(wall.health < wallHealth && !wall.dead, "low-radius XYZ blast reaches large block corner");
            wallHealth = wall.health;
            explosion.detonateXYZ(source, cornerX, cornerY, world.surfaceHeight(bx, by) + 2, spec);
            require(wall.health == wallHealth, "blast well above large block misses");

            enemy = spawn(enemyTeam, site.x + 8, site.y + 3);
            friend = spawn(source, site.x + 8, site.y + 3);
            var body = world.unitState(enemy);
            require(body != null && body.radius() > spec.radius() && body.halfHeight() > 0,
                "native flyer exposes physical volume");
            float enemyHealth = enemy.health, friendHealth = friend.health;
            explosion.detonateXYZ(source, body.x() + body.radius() + .1, body.y(), body.z(), spec);
            require(enemy.health < enemyHealth && !enemy.dead, "horizontal physical-volume graze outside center radius damages enemy");
            require(friend.health == friendHealth, "friendly body remains protected on horizontal graze");
            enemyHealth = enemy.health;
            explosion.detonateXYZ(source, body.x(), body.y(), body.z() + body.halfHeight() + .1, spec);
            require(enemy.health < enemyHealth && !enemy.dead, "vertical physical-volume graze damages enemy");
            require(friend.health == friendHealth, "friendly body remains protected on vertical graze");
            enemyHealth = enemy.health;
            explosion.detonateXYZ(source, body.x(), body.y(), body.z() + body.halfHeight() + .3, spec);
            require(enemy.health == enemyHealth, "same XY overflight outside vertical extent misses");
            require(friend.health == friendHealth, "friendly health unchanged throughout blast cases");
            return "PASS: native large-block corner blast, horizontal/vertical unit-volume grazes, altitude misses and friendly immunity";
        } finally {
            if (enemy != null && enemy.isAdded()) enemy.remove();
            if (friend != null && friend.isAdded()) friend.remove();
            if (wall != null && wall.tile.build == wall) { wall.noSleep(); wall.tile.setBlock(Blocks.air); }
            explosion.clear();
        }
    }

    private static Unit spawn(Team team, double x, double y) {
        Unit unit = UnitTypes.flare.create(team);
        unit.set((float)x * Vars.tilesize, (float)y * Vars.tilesize);
        unit.controller(new AIController() { @Override public void updateUnit() {} });
        unit.add();
        require(unit.isAdded() && !unit.dead, "native flyer fixture spawned");
        return unit;
    }

    private static Tile findSite() {
        for (int y = 1; y + 8 < Vars.world.height(); y++) for (int x = 1; x + 12 < Vars.world.width(); x++) {
            boolean empty = true;
            scan: for (int dy = 0; dy < 8; dy++) for (int dx = 0; dx < 12; dx++) {
                Tile tile = Vars.world.tile(x + dx, y + dy);
                if (tile == null || tile.build != null || tile.block() != Blocks.air) { empty = false; break scan; }
            }
            if (empty) return Vars.world.tile(x, y);
        }
        return null;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException("Radial explosion smoke: " + message);
    }
}
