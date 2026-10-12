package fpv.render;

import arc.Core;
import arc.graphics.g3d.Camera3D;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Conveyor;

import java.util.ArrayList;

/** Real native placement/lifecycle plus completed GPU-submission checks, confined to the smoke lab. */
public final class BuildingVisibilitySmokeChecks {
    private BuildingVisibilitySmokeChecks() {}

    /** Run synchronously on the loaded game's GL thread. The next normal frame restores its camera view. */
    public static String verify(FpvWorldRenderer renderer) {
        require(Boolean.getBoolean("fpv.smoke"), "isolated smoke lab required");
        require(Vars.state != null && Vars.state.isGame() && Vars.player != null, "loaded native world required");
        Block[] types = { Blocks.conveyor, Blocks.router, Blocks.mechanicalDrill, Blocks.graphitePress,
            Blocks.powerNode, Blocks.battery, Blocks.duo, Blocks.copperWallLarge };
        Tile site = findSite();
        require(site != null, "empty 18 by 10 tile lab fixture area");
        var owned = new ArrayList<Building>();
        var fixtures = new ArrayList<Building>();
        float previousDelta = Time.delta;
        Camera3D camera = new Camera3D();
        camera.resize(Math.max(2, Core.graphics.getWidth()), Math.max(2, Core.graphics.getHeight()));
        camera.near = .05f;
        camera.far = 1000f;
        camera.position.set(site.x + 8.5f, 32, -site.y - 4.5f);
        camera.direction.set(0, -1, 0);
        camera.up.set(0, 0, -1);
        camera.update();
        int checks = 0;
        try {
            for (int i = 0; i < types.length; i++) {
                Tile anchor = Vars.world.tile(site.x + 2 + (i % 4) * 4, site.y + 2 + (i / 4) * 4);
                anchor.setBlock(types[i], Vars.player.team(), 0);
                require(anchor.build != null, "native fixture placement: " + types[i].name);
                owned.add(anchor.build);
                fixtures.add(anchor.build);
            }
            Building largeWall = fixtures.get(7);
            require(!largeWall.block.update && !inUpdater(largeWall), "non-updating wall starts outside updater group");
            // Bootstrap must discover pre-existing placement, including structures never added to Groups.build.
            renderer.invalidate();
            checks += submit(renderer, camera, fixtures, "placed/empty");
            int initialCount = renderer.placedBuildingCount();

            for (Building building : fixtures) { building.enabled = false; building.efficiency = 0; }
            checks += submit(renderer, camera, fixtures, "logic-disabled");
            for (Building building : fixtures) {
                building.enabled = true;
                if (building.power != null) building.power.status = 0;
                building.efficiency = 0;
            }
            checks += submit(renderer, camera, fixtures, "idle/unpowered");

            // Invoke the native sleep transition itself; no fake registry manipulation or remove() substitute.
            Time.delta = 61f;
            for (Building building : fixtures) {
                building.noSleep();
                building.sleep();
                require(!inUpdater(building), "native sleep removed updater: " + building.block.name);
                require(building.tile.build == building && building.isValid(), "sleep retained Tile.build: " + building.block.name);
            }
            Time.delta = previousDelta;
            checks += submit(renderer, camera, fixtures, "native-sleep");
            arc.util.ScreenUtils.saveScreenshot(new arc.files.Fi(System.getProperty("fpv.smoke.output")).child("inactive-blocks.png"));
            require(renderer.placedBuildingCount() == initialCount, "sleep cannot change placed count");

            camera.position.y = 120f;
            camera.update();
            checks += submit(renderer, camera, fixtures, "sleep/distant-LOD");
            camera.position.y = 32f;
            camera.update();
            for (Building building : fixtures) building.noSleep();
            checks += submit(renderer, camera, fixtures, "native-wake");

            Conveyor.ConveyorBuild belt = (Conveyor.ConveyorBuild)fixtures.get(0);
            belt.efficiency = 1;
            belt.handleStack(Items.copper, 1, null);
            float cargoPosition = belt.ys[0];
            Time.delta = 1;
            belt.updateTile();
            Time.delta = previousDelta;
            require(belt.len == 1 && belt.ys[0] > cargoPosition, "native active conveyor advances its cargo");
            checks += submit(renderer, camera, fixtures, "native-active-cargo");

            Tile anchor = largeWall.tile;
            Tile linkedEdge = Vars.world.tile(anchor.x + 1, anchor.y + 1);
            require(linkedEdge != anchor && linkedEdge.build == largeWall, "real multiblock linked edge");
            linkedEdge.setBlock(Blocks.air);
            fixtures.remove(largeWall);
            checks += submit(renderer, camera, fixtures, "linked-edge-removal");
            require(!renderer.hasPlacedBuilding(largeWall) && !renderer.wasBuildingSubmitted(largeWall), "removed multiblock anchor cannot survive in render index");
            require(renderer.placedBuildingCount() == initialCount - 1, "multiblock footprint counted once");

            anchor.setBlock(Blocks.copperWallLarge, Vars.player.team(), 0);
            Building replacement = anchor.build;
            require(replacement != null && replacement != largeWall, "new native replacement identity");
            owned.add(replacement);
            fixtures.add(replacement);
            checks += submit(renderer, camera, fixtures, "replacement");
            require(!renderer.wasBuildingSubmitted(largeWall) && renderer.placedBuildingCount() == initialCount,
                "replacement cannot reuse detached building reference or duplicate footprint");
            return "PASS: 8 native block categories, " + checks +
                " completed-frame submissions; idle/disabled/unpowered/sleep/wake/distant LOD, active cargo, non-updating wall, multiblock edge removal/replacement";
        } finally {
            Time.delta = previousDelta;
            for (Building building : owned) {
                if (building.tile != null && building.tile.build == building) {
                    // Balance native sleepingEntities before deleting a fixture after a failed sleep assertion.
                    building.noSleep();
                    building.tile.setBlock(Blocks.air);
                }
            }
            renderer.invalidate();
        }
    }

    private static int submit(FpvWorldRenderer renderer, Camera3D camera, ArrayList<Building> fixtures, String phase) {
        boolean[] added = new boolean[fixtures.size()];
        for (int i = 0; i < fixtures.size(); i++) added[i] = inUpdater(fixtures.get(i));
        renderer.render(camera);
        for (int i = 0; i < fixtures.size(); i++) {
            Building building = fixtures.get(i);
            require(renderer.hasPlacedBuilding(building), phase + " indexed " + building.block.name);
            require(renderer.wasBuildingSubmitted(building), phase + " GPU-submitted " + building.block.name);
            require(inUpdater(building) == added[i], "render must not wake/change native simulation membership");
        }
        return fixtures.size();
    }

    private static boolean inUpdater(Building building) {
        return Groups.build.contains(candidate -> candidate == building);
    }

    private static Tile findSite() {
        for (int y = 1; y + 10 < Vars.world.height(); y++) {
            for (int x = 1; x + 18 < Vars.world.width(); x++) {
                boolean empty = true;
                scan: for (int dy = 0; dy < 10; dy++) for (int dx = 0; dx < 18; dx++) {
                    Tile tile = Vars.world.tile(x + dx, y + dy);
                    if (tile == null || tile.block() != Blocks.air || tile.build != null) { empty = false; break scan; }
                }
                if (empty) return Vars.world.tile(x, y);
            }
        }
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Building visibility smoke: " + message);
    }
}
