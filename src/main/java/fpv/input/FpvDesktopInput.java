package fpv.input;

import arc.Core;
import arc.Input;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.util.Time;
import mindustry.Vars;
import mindustry.core.World;
import mindustry.gen.Mechc;
import mindustry.gen.Payloadc;
import mindustry.gen.Unit;
import mindustry.input.Binding;
import mindustry.input.DesktopInput;
import mindustry.input.PlaceMode;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Adapts the native desktop builder without replacing its queues or simulation. */
public final class FpvDesktopInput extends DesktopInput {
    /** Values generated from design/input.sheet.json. Names are Binding field names. */
    public static final class Policy {
        final Set<KeyBind> fpvBlocked;
        final Set<KeyBind> flightBlocked;
        final Set<KeyBind> pointerBlocked;
        final int payloadRepeatMilliseconds;
        final int payloadHoldDelayMilliseconds;

        public Policy(String[] fpvBlockedBindings, String[] flightBlockedBindings,
                      String[] pointerBlockedBindings, int payloadRepeatMilliseconds,
                      int payloadHoldDelayMilliseconds) {
            if (payloadRepeatMilliseconds <= 0 || payloadHoldDelayMilliseconds < 0) {
                throw new IllegalArgumentException("Invalid payload key timing");
            }
            fpvBlocked = resolve(fpvBlockedBindings);
            flightBlocked = resolve(flightBlockedBindings);
            pointerBlocked = resolve(pointerBlockedBindings);
            this.payloadRepeatMilliseconds = payloadRepeatMilliseconds;
            this.payloadHoldDelayMilliseconds = payloadHoldDelayMilliseconds;
        }

        private static Set<KeyBind> resolve(String[] names) {
            if (names == null) throw new IllegalArgumentException("Missing binding policy");
            Set<KeyBind> result = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String name : names) {
                try {
                    Object value = Binding.class.getField(name).get(null);
                    if (!(value instanceof KeyBind)) throw new IllegalArgumentException("Not a key binding: " + name);
                    if (!result.add((KeyBind)value)) throw new IllegalArgumentException("Duplicate binding: " + name);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalArgumentException("Unsupported Mindustry binding: " + name, exception);
                }
            }
            return result;
        }
    }

    @FunctionalInterface
    public interface WorldProjector {
        /** Write native bottom-left screen coordinates. Return false for a point behind the camera. */
        boolean project(float worldX, float worldY, Vec2 out);
    }

    private final Policy policy;
    private final ProjectedInput projection;
    private final Vec2 aimWorld = new Vec2();
    private final Vec2 uiPointer = new Vec2();
    private boolean fpvEnabled;
    private BooleanSupplier boostOwned=()->false;
    public void setBoostOwnedSupplier(BooleanSupplier value){boostOwned=value==null?()->false:value;}
    private boolean buildMode;
    private boolean aimValid;
    private boolean aimInitialized;
    private Tile miningPick,ownedMiningTile;
    private Unit ownedMiner;
    public void setMiningPick(Tile tile){miningPick=tile;}
    public Tile miningPick(){return miningPick;}
    private void stopFlightMining(){
        if(ownedMiner!=null&&ownedMiner.mineTile==ownedMiningTile)ownedMiner.mineTile=null;
        ownedMiner=null;ownedMiningTile=null;
    }
    /** Original native mining state/rules, independent of the far-away weapon aim projection. */
    public boolean applyFlightMining(boolean held){
        boolean modal=Core.scene!=null&&(Core.scene.hasDialog()||Core.scene.hasKeyboard());
        boolean allowed=fpvEnabled&&!buildMode&&!suspended&&!modal&&!commandActive()&&!commandPointerFence()
            &&Vars.player!=null&&!Vars.player.dead()&&!Vars.state.isPaused();
        Unit unit=allowed?Vars.player.unit():null;
        if(ownedMiner!=null&&ownedMiner!=unit)stopFlightMining();
        if(!allowed||!held||Core.scene!=null&&Core.scene.hasMouse()||!FpvMiningRules.allowed(unit,miningPick)){
            stopFlightMining();return false;
        }
        unit.mineTile=miningPick;
        ownedMiner=unit;ownedMiningTile=miningPick;return true;
    }
    private boolean suspended;
    private Unit controlPick;
    private boolean selectionConsumed;
    private boolean upperLayerOwned;
    private boolean interactionOwned,cancelConsumed;
    private boolean schematicOwned;
    private FpvUnitCommands commands;
    public void setCommands(FpvUnitCommands value){commands=value;}
    public FpvUnitCommands commands(){return commands;}
    public boolean commandActive(){return commands!=null&&commands.active();}
    public boolean commandPointerFence(){return commands!=null&&commands.pointerFence();}
    public void syncCommands(){if(fpvEnabled&&commands!=null)commands.syncMode();}
    public void stopCommandFire(){stopFlightMining();shouldShoot=false;if(Vars.player!=null&&!Vars.player.dead()){Vars.player.shooting=false;Vars.player.boosting=false;Vars.player.unit().controlWeapons(false,false);}}
    private java.util.function.Consumer<Unit> controlAction=u->mindustry.gen.Call.unitControl(Vars.player,u);
    private java.util.function.BooleanSupplier releaseControl=()->false;
    private boolean releaseConsumed;
    public void setControlAction(java.util.function.Consumer<Unit> action,java.util.function.BooleanSupplier release){controlAction=action;releaseControl=release;}
    public void setSchematicOwned(boolean value){schematicOwned=value;}
    public boolean schematicOwned(){return schematicOwned;}
    public void setInteractionOwned(boolean value){interactionOwned=value;}
    public void setCancelConsumed(boolean value){cancelConsumed=value;}
    private boolean ownsInteraction(){return interactionOwned||fpvEnabled&&buildMode&&block==null&&!projection.original().keyDown(Binding.control)&&!projection.original().keyDown(Binding.breakBlock);}
    public void setUpperLayerOwned(boolean value){if(value!=upperLayerOwned){cancelPointerGesture();config.hideConfig();inv.hide();}upperLayerOwned=value;}
    private Runnable beforeUpdate;
    private WorldProjector worldProjector;
    private BooleanSupplier movementOwned=()->false;
    /** Presentation may be 2D while the matching server still owns movement. */
    public BooleanSupplier setMovementOwnedSupplier(BooleanSupplier value){BooleanSupplier previous=movementOwned;movementOwned=value==null?()->false:value;return previous;}

    public FpvDesktopInput(Input originalInput, Policy policy) {
        if (originalInput == null || policy == null) throw new IllegalArgumentException("Input and policy are required");
        this.policy = policy;
        projection = new ProjectedInput(originalInput, this);
    }

    /** Install once on the main game thread. SDL keeps updating its original backend instance. */
    public void installProjection() {
        if (Core.input == projection) return;
        if (Core.input != projection.original()) {
            throw new IllegalStateException("Another mod replaced Core.input before FPV installation");
        }
        Core.input = projection;
    }

    /** Does not overwrite a later replacement made by another mod. */
    public void uninstallProjection() {
        setFpvEnabled(false);
        if (Core.input == projection) Core.input = projection.original();
    }

    public Input originalInput() {
        return projection.original();
    }

    public boolean isFpvEnabled() {
        return fpvEnabled;
    }

    public boolean isBuildMode() {
        return buildMode;
    }

    public void setFpvEnabled(boolean enabled) {
        if (fpvEnabled == enabled) return;
        stopFlightMining();miningPick=null;
        cancelPointerGesture();
        fpvEnabled = enabled;
        if(commands!=null)commands.modeTransition();
        if (enabled) commandMode = false;
        aimValid = false;
        if (!enabled) {buildMode = false;selectPlans.removeAll(fpv.construction.LayerSchematic::platform);schematicOwned=false;}
        if (enabled && Vars.player != null) {
            aimWorld.set(Vars.player.x, Vars.player.y);
            aimInitialized = true;
        }
    }

    public void setBuildMode(boolean enabled) {
        if (buildMode == enabled) return;
        stopFlightMining();miningPick=null;
        cancelPointerGesture();
        buildMode = enabled;
        if(commands!=null)commands.modeTransition();
        if (!enabled) {
            commandMode = false;
            config.hideConfig();
            inv.hide();
            planConfig.hide();
        }
    }

    /** Parent must report native window focus loss as well as modal UI suspension. */
    public void setSuspended(boolean value) {
        if(value)stopFlightMining();
        if (value && !suspended) {cancelPointerGesture();if(commands!=null)commands.modeTransition();}
        suspended = value;
    }

    public boolean isSuspended() {
        return suspended;
    }
    public void setControlPick(Unit unit){controlPick=unit;}
    /** Ray-selected bodies may be above a sky ray with no valid building tile. */
    public boolean applyControlSelection(boolean held,boolean tapped){
        if(!fpvEnabled||commandActive()||commandPointerFence()||!buildMode||block!=null||schematicOwned||!selectPlans.isEmpty()||suspended||interfaceOwnsInput()||!held||!tapped||controlPick==null||!Vars.state.rules.possessionAllowed)return false;
        Unit unit=controlPick;
        if(unit.dead()||!unit.isAdded()||unit.team!=Vars.player.team()||!unit.isAI()||!unit.playerControllable())return false;
        controlAction.accept(unit);cancelPointerGesture();controlPick=null;return true;
    }

    /** Parent updates this with the current perspective pick, in Mindustry world units. */
    public void setAimWorld(float x, float y, boolean valid) {
        boolean finite = Float.isFinite(x) && Float.isFinite(y);
        if (finite && (valid || !aimInitialized)) {
            aimWorld.set(x, y);
            aimInitialized = true;
        }
        aimValid = valid && finite && Vars.world != null && Vars.world.tileWorld(x, y) != null;
    }

    public boolean hasValidPick() {
        return aimValid;
    }

    /** Horizontal native weapon target for a sky ray; this never makes a build pick valid. */
    public void setFlightAim(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        aimWorld.set(x, y);
        aimInitialized = true;
        aimValid = false;
    }

    public Vec2 getAimWorld(Vec2 out) {
        return out.set(aimWorld);
    }

    /** The native preview anchor includes the even-sized block offset, just like InputHandler. */
    public Tile pickedTile() {
        if (!aimValid) return null;
        float offset = selectedBlock() && block != null ? block.offset : 0f;
        return Vars.world.tile(World.toTile(aimWorld.x - offset), World.toTile(aimWorld.y - offset));
    }

    /** Refresh flight, camera and picking immediately before native input processing, if required. */
    public void setBeforeUpdate(Runnable callback) {
        beforeUpdate = callback;
    }

    public void setWorldProjector(WorldProjector projector) {
        worldProjector = projector;
    }

    boolean projectWorld(float x, float y, Vec2 out) {
        return worldProjector != null && worldProjector.project(x, y, out);
    }

    boolean hasWorldProjector() {
        return worldProjector != null;
    }

    boolean pointerBlocked() {
        return suspended || commandPointerFence() || !buildMode || !aimValid || interfaceOwnsInput();
    }

    boolean interfaceOwnsInput() {
        if (Core.scene == null) return false;
        // Scene.hasMouse() reports the previous hover update. Test the current pointer as well,
        // converting with the Scene's own viewport so UI scaling never becomes a world pick.
        Core.scene.screenToStageCoordinates(uiPointer.set(projection.mouseX(), projection.mouseY()));
        return Core.scene.hit(uiPointer.x, uiPointer.y, true) != null
            || Core.scene.hasKeyboard() || Core.scene.hasDialog();
    }

    boolean suppressBinding(KeyBind binding) {
        if(releaseConsumed&&binding==Binding.respawn)return true;
        if (!fpvEnabled) return false;
        if(commands!=null&&binding==Binding.commandMode)return true; // The FPV mode adapter reads the unfiltered native binding once.
        if(commandActive()&&binding==Binding.boost)return true;
        if(commands!=null&&commands.consumeEscape()&&binding==Binding.menu)return true;
        if(commandActive()&&(policy.pointerBlocked.contains(binding)||policy.flightBlocked.contains(binding)))return true;
        if(cancelConsumed&&(binding==Binding.deselect||binding==Binding.breakBlock||binding==Binding.menu))return true;
        if(selectionConsumed&&(binding==Binding.control||binding==Binding.select))return true;
        return policy.fpvBlocked.contains(binding)
            || (!buildMode && policy.flightBlocked.contains(binding))
            || (pointerBlocked() && policy.pointerBlocked.contains(binding));
    }

    /** Cancels only the uncommitted gesture. Never clears player.unit().plans. */
    public void cancelPointerGesture() {
        mode = PlaceMode.none;
        linePlans.clear();
        selectX = selectY = schemX = schemY = -1;
        splan = null;
        deleting = false;
        movedPlan = false;
        commandRect = false;
        droppingItem = false;
        shouldShoot = false;
        if (Vars.player != null) Vars.player.shooting = false;
    }

    @Override public void useSchematic(mindustry.game.Schematic schematic,boolean checkHidden){
        super.useSchematic(schematic,checkHidden);
        if(fpvEnabled)for(var p:fpv.construction.LayerSchematic.slabs(schematic))
            selectPlans.add(new fpv.construction.LayerSchematic.PlatformPlan(p.x+schematicX-schematic.width/2,p.y+schematicY-schematic.height/2));
    }
    @Override protected int schemOriginX(){return fpvEnabled&&buildMode?schematicX:super.schemOriginX();}
    @Override protected int schemOriginY(){return fpvEnabled&&buildMode?schematicY:super.schemOriginY();}
    /** Invoke the native queue/cost path; slab metadata is meaningful only on elevated floors. */
    public void flushGroundSchematic(){var plans=selectPlans.select(p->!fpv.construction.LayerSchematic.platform(p));super.flushPlans(plans);}

    @Override
    public void update() {
        if (beforeUpdate != null) beforeUpdate.run();
        syncCommands();
        if(fpvEnabled&&commandActive()){
            commands.update();stopCommandFire();
            if(Vars.player!=null&&!Vars.player.dead())Vars.player.unit().updateBuilding(isBuilding);
            return;
        }
        releaseConsumed=projection.original().keyTap(Binding.respawn)&&!interfaceOwnsInput()&&releaseControl.getAsBoolean();
        if (!fpvEnabled) {
            super.update();
            return;
        }
        if((upperLayerOwned||ownsInteraction())&&buildMode){
            if(!interfaceOwnsInput()&&projection.original().keyTap(Binding.pauseBuilding)){
                isBuilding=!isBuilding;buildWasAutoPaused=false;
            }
            // Native possession remains available when automatic picking resolves an elevated floor.
            applyControlSelection(projection.original().keyDown(Binding.control),projection.original().keyTap(Binding.select));
            cancelPointerGesture();
            if(Vars.player!=null&&!Vars.player.dead()){Vars.player.unit().updateBuilding(isBuilding);updateMovement(Vars.player.unit());}
            return;
        }
        if (pointerBlocked()) cancelPointerGesture();
        selectionConsumed=applyControlSelection(projection.original().keyDown(Binding.control),projection.original().keyTap(Binding.select));
        // Keep the selected build block for the next Build Mode, but do not let it stop flight firing.
        Block savedBlock = block;
        if (!buildMode) block = null;
        try {
            applyFlightTrigger(projection.original().keyDown(Binding.select));
            super.update();
            // The desktop builder rechecks the tile under its 2D cursor and queued plans later
            // in update(). Those are not flight triggers; reapply after that native UI pass.
            applyFlightTrigger(projection.original().keyDown(Binding.select));
        } finally {
            selectionConsumed=false;
            if (!buildMode) block = savedBlock;
        }
        applyFlightMining(projection.original().keyDown(MiningBinding.mine));
    }

    /** Shared production/test boundary; status, ammo, reload and mount cones stay in native Weapon.update. */
    public void applyFlightTrigger(boolean held){
        if(!fpvEnabled||Vars.player==null||Vars.player.dead())return;
        Unit unit=Vars.player.unit();
        boolean modal=Core.scene!=null&&(Core.scene.hasDialog()||Core.scene.hasKeyboard());
        if(commandActive()||commandPointerFence()){stopCommandFire();return;}
        boolean fire=held&&!suspended&&!buildMode&&!modal&&unit.canShoot()
            &&!(unit instanceof Mechc&&unit.isFlying());
        Vars.player.shooting=fire;
        if(aimInitialized)unit.aim(aimWorld.x,aimWorld.y,true);
        unit.controlWeapons(true,fire);
        Vars.player.mouseX=unit.aimX();Vars.player.mouseY=unit.aimY();
    }

    @Override
    protected void updateMovement(Unit unit) {
        if (!fpvEnabled && !movementOwned.getAsBoolean()) {
            super.updateMovement(unit);
            return;
        }
        if (!fpvEnabled) {
            // Online native presentation keeps its cursor, weapon and payload behavior, but the
            // server's GroundedPilot/flight adapter has already applied movement for this tick.
            movement.setZero();
            unit.aim(Core.input.mouseWorldX(),Core.input.mouseWorldY(),true);
            boolean boosted=unit instanceof Mechc&&unit.isFlying();
            unit.controlWeapons(true,Vars.player.shooting&&!boosted);
            Vars.player.boosting=Core.input.keyDown(Binding.boost);
            Vars.player.mouseX=unit.aimX();Vars.player.mouseY=unit.aimY();
            updatePayloadInput(unit);
            return;
        }
        // The FPV integration owns translation and facing; native movePref must not run as well.
        movement.setZero();
        if (aimInitialized) unit.aim(aimWorld.x, aimWorld.y, true);
        boolean boosted = unit instanceof Mechc && unit.isFlying();
        unit.controlWeapons(true, !suspended && !buildMode && !commandActive()&&!commandPointerFence()&&Vars.player.shooting && !boosted);
        Vars.player.boosting = boostOwned.getAsBoolean(); // FPV uses the separate boost toggle; Shift remains group commands.
        Vars.player.mouseX = unit.aimX();
        Vars.player.mouseY = unit.aimY();
        updatePayloadInput(unit);
    }

    private void updatePayloadInput(Unit unit) {
        if (!(unit instanceof Payloadc) || (fpvEnabled&&suspended) || interfaceOwnsInput()) return;
        if (Core.input.keyTap(Binding.pickupCargo)) {
            tryPickupPayload();
            lastPayloadKeyTapMillis = Time.millis();
        }
        if (Core.input.keyDown(Binding.pickupCargo)
            && Time.timeSinceMillis(lastPayloadKeyHoldMillis) > policy.payloadRepeatMilliseconds
            && Time.timeSinceMillis(lastPayloadKeyTapMillis) > policy.payloadHoldDelayMilliseconds) {
            tryPickupPayload();
            lastPayloadKeyHoldMillis = Time.millis();
        }
        if (Core.input.keyTap(Binding.dropCargo)) {
            tryDropPayload();
            lastPayloadKeyTapMillis = Time.millis();
        }
        if (Core.input.keyDown(Binding.dropCargo)
            && Time.timeSinceMillis(lastPayloadKeyHoldMillis) > policy.payloadRepeatMilliseconds
            && Time.timeSinceMillis(lastPayloadKeyTapMillis) > policy.payloadHoldDelayMilliseconds) {
            tryDropPayload();
            lastPayloadKeyHoldMillis = Time.millis();
        }
    }

    @Override
    public boolean tap(float x, float y, int count, KeyCode button) {
        if(fpvEnabled&&(commandActive()||commandPointerFence()))return false;
        if((upperLayerOwned||ownsInteraction())&&fpvEnabled&&buildMode)return false;
        if (fpvEnabled && pointerBlocked()) return false;
        return super.tap(x, y, count, button);
    }

    @Override
    public boolean touchDown(float x, float y, int pointer, KeyCode button) {
        if(fpvEnabled&&(commandActive()||commandPointerFence()))return false;
        if((upperLayerOwned||ownsInteraction())&&fpvEnabled&&buildMode)return false;
        if (fpvEnabled && pointerBlocked()) return false;
        return super.touchDown(x, y, pointer, button);
    }

    @Override
    public void drawTop() {
        if (!fpvEnabled) super.drawTop();
    }

    @Override
    public void drawBottom() {
        if (!fpvEnabled) super.drawBottom();
    }

    @Override public boolean isPlacing(){return !commandActive()&&super.isPlacing();}

    /** Package-local smoke hook invokes the actual protected host planner; never commits plans. */
    void previewLineForSmoke(int startX, int startY, int endX, int endY) {
        super.updateLine(startX, startY, endX, endY);
    }
}
