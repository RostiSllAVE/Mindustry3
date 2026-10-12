package fpv.input;

import arc.Core;
import arc.Input;
import arc.input.KeyBind;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.math.geom.Vec3;

/**
 * Delegates physical input to the existing backend and changes only world projection and
 * named gameplay binds. Native Scene UI continues receiving original screen positions/events.
 */
final class ProjectedInput extends Input {
    private final Input original;
    private final FpvDesktopInput owner;

    ProjectedInput(Input original, FpvDesktopInput owner) {
        this.original = original;
        this.owner = owner;
        keyboard = original.getKeyboard();
        inputMultiplexer = original.getInputMultiplexer();
    }

    Input original() { return original; }

    @Override
    public Vec2 mouseWorld(float x, float y) {
        // InputHandler's tileX(x,0)/tileY(0,y) need both components of the *current* ray hit.
        return owner.isFpvEnabled() ? owner.getAimWorld(mouseReturn) : original.mouseWorld(x, y);
    }

    @Override public Vec2 mouseWorld() { return owner.isFpvEnabled() ? owner.getAimWorld(mouseReturn) : original.mouseWorld(); }
    @Override public float mouseWorldX() { return mouseWorld().x; }
    @Override public float mouseWorldY() { return mouseWorld().y; }

    @Override
    public Vec2 mouseScreen(float x, float y) {
        if (owner.isFpvEnabled() && owner.hasWorldProjector()) {
            // A panel behind the camera is kept offscreen until native validation closes it.
            if (!owner.projectWorld(x, y, mouseReturn)) {
                mouseReturn.set(-Core.graphics.getWidth(), -Core.graphics.getHeight());
            }
            return mouseReturn;
        }
        return original.mouseScreen(x, y);
    }

    @Override public boolean keyDown(KeyBind key) { return !owner.suppressBinding(key) && original.keyDown(key); }
    @Override public boolean keyTap(KeyBind key) { return !owner.suppressBinding(key) && original.keyTap(key); }
    @Override public boolean keyRelease(KeyBind key) { return !owner.suppressBinding(key) && original.keyRelease(key); }
    @Override public float axis(KeyBind key) { return owner.suppressBinding(key) ? 0f : original.axis(key); }
    @Override public float axisTap(KeyBind key) { return owner.suppressBinding(key) ? 0f : original.axisTap(key); }

    @Override public int mouseX() { return original.mouseX(); }
    @Override public int mouseX(int pointer) { return original.mouseX(pointer); }
    @Override public int mouseY() { return original.mouseY(); }
    @Override public int mouseY(int pointer) { return original.mouseY(pointer); }
    @Override public int deltaX() { return original.deltaX(); }
    @Override public int deltaX(int pointer) { return original.deltaX(pointer); }
    @Override public int deltaY() { return original.deltaY(); }
    @Override public int deltaY(int pointer) { return original.deltaY(pointer); }
    @Override public boolean isTouched() { return original.isTouched(); }
    @Override public boolean isTouched(int pointer) { return original.isTouched(pointer); }
    @Override public boolean justTouched() { return original.justTouched(); }
    @Override public int getTouches() { return original.getTouches(); }
    @Override public float getPressure() { return original.getPressure(); }
    @Override public float getPressure(int pointer) { return original.getPressure(pointer); }
    @Override public long getCurrentEventTime() { return original.getCurrentEventTime(); }
    @Override public boolean keyDown(KeyCode key) { return original.keyDown(key); }
    @Override public boolean keyTap(KeyCode key) { return original.keyTap(key); }
    @Override public boolean keyRelease(KeyCode key) { return original.keyRelease(key); }
    @Override public float axis(KeyCode key) { return original.axis(key); }
    @Override public boolean shift() { return original.shift(); }
    @Override public boolean ctrl() { return original.ctrl(); }
    @Override public boolean alt() { return original.alt(); }
    @Override public void setUseKeyboard(boolean useKeyboard) { original.setUseKeyboard(useKeyboard); }
    @Override public boolean useKeyboard() { return original.useKeyboard(); }
    @Override public void getTextInput(TextInput input) { original.getTextInput(input); }
    @Override public boolean isShowingTextInput() { return original.isShowingTextInput(); }
    @Override public void setOnscreenKeyboardVisible(boolean visible) { original.setOnscreenKeyboardVisible(visible); }
    @Override public void vibrate(int milliseconds) { original.vibrate(milliseconds); }
    @Override public void vibrate(long[] pattern, int repeat) { original.vibrate(pattern, repeat); }
    @Override public void cancelVibrate() { original.cancelVibrate(); }
    @Override public Vec3 getAccelerometer() { return original.getAccelerometer(); }
    @Override public Vec3 getGyroscope() { return original.getGyroscope(); }
    @Override public Vec3 getOrientation() { return original.getOrientation(); }
    @Override public void getRotationMatrix(float[] matrix) { original.getRotationMatrix(matrix); }
    @Override public void setCatch(KeyCode code, boolean value) { original.setCatch(code, value); }
    @Override public boolean isCatch(KeyCode code) { return original.isCatch(code); }
    @Override public boolean isPeripheralAvailable(Peripheral peripheral) { return original.isPeripheralAvailable(peripheral); }
    @Override public int getRotation() { return original.getRotation(); }
    @Override public Orientation getNativeOrientation() { return original.getNativeOrientation(); }
    @Override public String getKeyName(KeyCode code) { return original.getKeyName(code); }
}
