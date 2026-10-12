package fpv.input;

import arc.Core;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.core.World;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Unit;
import mindustry.input.Binding;
import mindustry.input.InputHandler;
import mindustry.input.PlaceMode;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.lang.reflect.Method;

/** Native API checks for the isolated smoke game. Does not synthesize OS or Scene input events. */
public final class InputSmokeChecks {
    private InputSmokeChecks() { }

    public static String run(FpvDesktopInput input) {
        require(Boolean.getBoolean("fpv.smoke"), "Input checks require the isolated smoke launch");
        require(input != null && Vars.player != null && !Vars.player.dead(), "A live native player is required");
        require(Vars.world.width() >= 8 && Vars.world.height() >= 8, "Loaded map is too small for checks");
        Snapshot saved = new Snapshot(input);
        Element temporaryFocus = new Element();
        try {
            input.setSuspended(false);
            input.setFpvEnabled(true);
            input.setBuildMode(true);
            input.mode = PlaceMode.none;
            int x = Vars.world.width() / 2;
            int y = Vars.world.height() / 2;
            float worldX = x * Vars.tilesize;
            float worldY = y * Vars.tilesize;

            input.block = Blocks.conveyor;
            input.setAimWorld(worldX, worldY, true);
            require(input.pickedTile() == Vars.world.tile(x, y), "Single-tile perspective anchor mismatch");
            require(Core.input.mouseWorldX() == worldX && Core.input.mouseWorldY() == worldY,
                "Global world projection does not use the perspective hit");
            require(Core.input.mouseX() == input.originalInput().mouseX()
                && Core.input.mouseY() == input.originalInput().mouseY(), "Physical pointer coordinates changed");
            require(Core.input.getKeyboard() == input.originalInput().getKeyboard()
                && Core.input.getInputMultiplexer() == input.originalInput().getInputMultiplexer(),
                "Backend keyboard or event multiplexer was replaced");

            Method tileX = method("tileX", float.class);
            Method tileY = method("tileY", float.class);
            Method tileAt = method("tileAt", float.class, float.class);
            checkNativeAnchor(input, tileX, tileY, tileAt, x, y);

            input.block = Blocks.copperWallLarge;
            require(input.block.size == 2 && input.block.offset != 0f, "Expected native even-size block fixture");
            input.setAimWorld(worldX + input.block.offset, worldY + input.block.offset, true);
            require(input.pickedTile() == Vars.world.tile(x, y), "Even-sized offset was not removed exactly once");
            checkNativeAnchor(input, tileX, tileY, tileAt, x, y);
            input.mode = PlaceMode.breaking;
            require(input.pickedTile() == Vars.world.tile(World.toTile(worldX + input.block.offset),
                World.toTile(worldY + input.block.offset)), "Demolition must use the unshifted native tile");

            // Invoke the actual host planner through the adapter, without flushing/constructing blocks.
            input.mode = PlaceMode.placing;
            input.block = Blocks.conveyor;
            input.overrideLineRotation = false;
            input.previewLineForSmoke(x, y, x + 2, y);
            require(input.linePlans.size >= 2, "Native conveyor planner produced no line");
            require(input.linePlans.first().x == x && input.linePlans.first().y == y,
                "Native line start differs from requested tile");
            require(input.linePlans.peek().x == x + 2 && input.linePlans.peek().y == y,
                "Native line end differs from requested tile");
            for (BuildPlan plan : input.linePlans) {
                require(plan.block != null && !plan.breaking && Vars.world.tile(plan.x, plan.y) != null,
                    "Native preview contains an invalid plan");
            }

            BuildPlan sentinel = new BuildPlan(x, y, 0, Blocks.conveyor);
            saved.unit.plans.addLast(sentinel);
            input.cancelPointerGesture();
            require(saved.unit.plans.contains(sentinel), "Cancelling a gesture cleared committed plans");
            require(input.linePlans.isEmpty() && input.mode == PlaceMode.none,
                "Cancelling a gesture retained an uncommitted line");

            input.setAimWorld(Float.NaN, Float.NaN, false);
            require(!input.hasValidPick() && input.pickedTile() == null && input.pointerBlocked(),
                "Invalid ray permits a world pick");
            require(input.suppressBinding(Binding.select) && input.suppressBinding(Binding.breakBlock),
                "Invalid ray permits placement/demolition polling");
            require(!input.touchDown(0f, 0f, 0, KeyCode.mouseRight)
                && !input.tap(0f, 0f, 1, KeyCode.mouseLeft), "Invalid ray reached native world gesture handling");
            require(saved.unit.plans.contains(sentinel), "Invalid ray modified the committed queue");

            input.setFlightAim(worldX + Vars.tilesize, worldY);
            require(!input.hasValidPick() && Core.input.mouseWorldX() == worldX + Vars.tilesize,
                "Sky weapon aim incorrectly enables building or retains a stale aim");

            input.setAimWorld(worldX, worldY, true);
            Core.scene.add(temporaryFocus);
            Core.scene.setKeyboardFocus(temporaryFocus);
            require(input.pointerBlocked() && input.suppressBinding(Binding.select),
                "Native UI keyboard focus permits a world action");
            require(!input.tap(0f, 0f, 1, KeyCode.mouseLeft), "Native UI focus reaches world tap handling");
            Core.scene.setKeyboardFocus(null);
            temporaryFocus.remove();
            input.setSuspended(true);
            require(input.pointerBlocked() && input.suppressBinding(Binding.breakBlock),
                "Focus suspension permits world actions");

            input.commandMode = true;
            input.setBuildMode(false);
            require(!input.commandMode, "Native Command Mode blocks firing after returning to Flight");
            require(input.suppressBinding(Binding.moveX) && input.suppressBinding(Binding.select)
                && input.suppressBinding(Binding.playerList), "Flight input conflicts remain active");
            input.setFpvEnabled(false);
            Vec2 expected = input.originalInput().mouseWorld().cpy();
            Vec2 actual = Core.input.mouseWorld().cpy();
            require(actual.equals(expected), "2D fallback changed native mouse projection");
            require(!input.suppressBinding(Binding.moveX), "2D fallback still suppresses native movement");
            checkMovementOwnership(input);

            boolean rejected = false;
            try {
                new FpvDesktopInput.Policy(new String[]{"thisBindingMustNeverExist"},
                    new String[0], new String[0], 20, 200);
            } catch (IllegalArgumentException expectedFailure) {
                rejected = true;
            }
            require(rejected, "An unsupported native binding silently passed policy validation");
            return "input-api: passed native tile projection, even-size/demolition offsets, native conveyor preview, "
                + "queue preservation, invalid/UI/focus gates, sky aim, backend delegation, single online 2D movement authority, offline 2D fallback and policy validation";
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Pinned native input hook differs from supported Mindustry", exception);
        } finally {
            temporaryFocus.remove();
            saved.restore(input);
        }
    }

    /** Real native movePref spy catches the former second acceleration with FPV presentation off. */
    private static void checkMovementOwnership(FpvDesktopInput input){
        final class MovementSpy extends mindustry.gen.MechUnit {
            int calls;
            @Override public void movePref(Vec2 vector){calls++;super.movePref(vector);}
        }
        var unit=new MovementSpy();unit.setType(mindustry.content.UnitTypes.dagger);unit.team(Vars.player.team());
        unit.set(Vars.player.x,Vars.player.y);unit.rotation=90;
        float mouseX=Vars.player.mouseX,mouseY=Vars.player.mouseY;boolean boosting=Vars.player.boosting,shooting=Vars.player.shooting;
        var previous=input.setMovementOwnedSupplier(()->true);
        try{
            var controller=new fpv.control.GroundedPilot();
            var controls=new fpv.net.NetworkControls(true,3,0,0,0,0,1,0,0,0,0,0,1,0,false);
            controller.step(Vars.player,unit,controls,new fpv.units.UnitAltitudeSystem((x,z)->0),1d/60);
            require(unit.calls==1&&unit.vel.len2()>0,"authoritative native grounded command did not apply movement");
            Vec2 velocity=unit.vel.cpy();unit.aim(Core.input.mouseWorldX(),Core.input.mouseWorldY(),true);
            float expectedX=unit.aimX(),expectedY=unit.aimY();input.updateMovement(unit);
            require(unit.calls==1&&unit.vel.equals(velocity),"online 2D native input applied duplicate acceleration");
            require(Math.abs(unit.aimX()-expectedX)<.01&&Math.abs(unit.aimY()-expectedY)<.01,"online 2D aim uses stale FPV camera target");
            input.setMovementOwnedSupplier(()->false);input.updateMovement(unit);
            require(unit.calls==2,"offline 2D input no longer delegates native movement");
        }finally{input.setMovementOwnedSupplier(previous);Vars.player.mouseX=mouseX;Vars.player.mouseY=mouseY;Vars.player.boosting=boosting;Vars.player.shooting=shooting;}
    }

    private static Method method(String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = InputHandler.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void checkNativeAnchor(FpvDesktopInput input, Method tileX, Method tileY, Method tileAt,
                                          int x, int y) throws ReflectiveOperationException {
        float mouseX = input.originalInput().mouseX(), mouseY = input.originalInput().mouseY();
        require(((Integer)tileX.invoke(input, mouseX)) == x, "Host tileX did not receive the perspective X");
        require(((Integer)tileY.invoke(input, mouseY)) == y, "Host tileY did not receive the perspective Y");
        require(((Tile)tileAt.invoke(input, mouseX, mouseY)) == Vars.world.tile(x, y),
            "Host tileAt differs from the perspective anchor");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Input smoke: " + message);
    }

    private static final class Snapshot {
        final Unit unit = Vars.player.unit();
        final Seq<BuildPlan> queue = new Seq<>();
        final Seq<BuildPlan> linePlans = new Seq<>();
        final Vec2 aim = new Vec2();
        final Block block;
        final PlaceMode mode;
        final BuildPlan splan;
        final Element keyboardFocus = Core.scene.getKeyboardFocus();
        final int rotation, selectX, selectY, schemX, schemY;
        final boolean enabled, build, valid, suspended, shooting, shouldShoot, deleting, movedPlan,
            commandRect, commandMode, droppingItem, overrideLineRotation;

        Snapshot(FpvDesktopInput input) {
            for (BuildPlan plan : unit.plans) queue.add(plan);
            linePlans.addAll(input.linePlans);
            input.getAimWorld(aim);
            block = input.block;
            mode = input.mode;
            splan = input.splan;
            rotation = input.rotation;
            selectX = input.selectX; selectY = input.selectY;
            schemX = input.schemX; schemY = input.schemY;
            enabled = input.isFpvEnabled(); build = input.isBuildMode(); valid = input.hasValidPick();
            suspended = input.isSuspended(); shooting = Vars.player.shooting;
            shouldShoot = input.shouldShoot; deleting = input.deleting; movedPlan = input.movedPlan;
            commandRect = input.commandRect; droppingItem = input.droppingItem;
            commandMode = input.commandMode;
            overrideLineRotation = input.overrideLineRotation;
        }

        void restore(FpvDesktopInput input) {
            unit.plans.clear();
            for (BuildPlan plan : queue) unit.plans.addLast(plan);
            input.setFpvEnabled(enabled);
            input.setBuildMode(build);
            input.setSuspended(suspended);
            input.setAimWorld(aim.x, aim.y, true);
            if (!valid) input.setAimWorld(aim.x, aim.y, false);
            input.block = block; input.mode = mode; input.splan = splan; input.rotation = rotation;
            input.selectX = selectX; input.selectY = selectY;
            input.schemX = schemX; input.schemY = schemY;
            input.linePlans.clear(); input.linePlans.addAll(linePlans);
            input.shouldShoot = shouldShoot; input.deleting = deleting; input.movedPlan = movedPlan;
            input.commandRect = commandRect; input.droppingItem = droppingItem;
            input.commandMode = commandMode;
            input.overrideLineRotation = overrideLineRotation;
            Vars.player.shooting = shooting;
            Core.scene.setKeyboardFocus(keyboardFocus);
        }
    }
}
