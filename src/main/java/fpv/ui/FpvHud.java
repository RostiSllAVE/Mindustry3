package fpv.ui;

import arc.Core;
import arc.func.Boolp;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g3d.Camera3D;
import arc.scene.Element;
import arc.scene.Group;
import arc.scene.event.Touchable;
import arc.util.Align;
import arc.util.Strings;
import fpv.flight.FlightController;
import fpv.generated.HudConfig;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.gen.Unit;
import mindustry.type.Item;
import mindustry.ui.Fonts;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Read-only flight instruments around the original Mindustry simulation and builder. */
public final class FpvHud implements AutoCloseable {
    public interface Source {
        boolean active();
        boolean buildMode();
        default boolean commandMode(){return false;}
        boolean suspended();
        double throttle();
        int buildTimePercent();
        FlightController.State state();
        Camera3D camera();
        String status();
        default String skyClock() { return ""; }
        default String skyPhase() { return ""; }
        default String flightMode() { return "ACRO"; }
        default boolean thirdPerson() { return false; }
        default String cameraView() { return thirdPerson()?"CHASE":"FPV"; }
        default boolean rearView() { return false; }
        default String constructionLayer() { return "Z0"; }
    }

    private final Source source;
    private final double metersPerTile;
    private final Color text = color(HudConfig.COLOR_TEXT), accent = color(HudConfig.COLOR_ACCENT),
        warning = color(HudConfig.COLOR_WARNING), dim = color(HudConfig.COLOR_DIM),
        track = color(HudConfig.COLOR_TRACK), shadow = color(HudConfig.COLOR_SHADOW),
        horizonColor = new Color();
    private final Map<Element, NativePanel> panels = new IdentityHashMap<>();
    private final List<Element> targets = new ArrayList<>();
    private final Element canvas = new Element() {
        {
            name = "fpv-instruments";
            touchable = Touchable.disabled;
            setFillParent(true);
            visible(() -> active() && !interfaceFocused());
        }
        @Override public void draw() {
            super.draw();
            drawHud(getWidth(), getHeight());
        }
    };
    private boolean installed, suppressNative;
    private float scale = 1f;

    public FpvHud(Source source, double metersPerTile) {
        if (source == null || !Double.isFinite(metersPerTile) || metersPerTile <= 0) {
            throw new IllegalArgumentException("HUD requires a source and the flight profile's positive map scale");
        }
        this.source = source;
        this.metersPerTile = metersPerTile;
    }

    /** Call after ClientLoadEvent; the separate scene element never owns input focus. */
    public void install() {
        if (installed) return;
        if (Core.scene == null || Vars.ui == null || Vars.ui.hudGroup == null) {
            throw new IllegalStateException("Install the FPV HUD after native UI creation");
        }
        Core.scene.add(canvas);
        installed = true;
        update();
    }

    /** Refresh after mode changes and each frame; also detects PlacementFragment.rebuild. */
    public void update() {
        long profiled=fpv.profile.CameraPerformanceProfile.start();
        try{updateProfiled();}finally{fpv.profile.CameraPerformanceProfile.end(fpv.profile.CameraPerformanceProfile.Part.HUD,profiled);}
    }
    private void updateProfiled() {
        if (!installed) return;
        suppressNative = active() && !source.buildMode() && !source.commandMode() && !source.suspended()
            && !Vars.state.isPaused() && !interfaceFocused();
        discoverPanels();
        for (NativePanel panel : panels.values()) panel.element.updateVisibility();
        canvas.updateVisibility();
    }

    /** Runtime observation: all five identified bulky panels are actually hidden. */
    public boolean nativePanelsSuppressed() {
        if (!suppressNative || targets.size() != 5) return false;
        for (Element target : targets) if (target.visible) return false;
        return true;
    }
    public int nativePanelCount() { return targets.size(); }
    public boolean instrumentsVisible() { return installed && canvas.visible; }

    private boolean active() {
        return source.active() && Vars.state != null && Vars.state.isGame()
            && Vars.player != null && !Vars.player.dead();
    }
    private boolean interfaceFocused() {
        return Core.scene != null && (Core.scene.hasDialog() || Core.scene.hasKeyboard());
    }

    private void discoverPanels() {
        Group root = Vars.ui.hudGroup;
        targets.clear();
        target(root.find("waves/editor"));
        target(root.find("fps/ping"));
        target(root.find("minimap/position"));
        target(Vars.ui.hudfrag.coreItems);
        Element builder = root.find("inputTable");
        if (builder != null) {
            while (builder.parent != null && builder.parent != root) builder = builder.parent;
            if (builder.parent == root) target(builder);
        }
        Iterator<Map.Entry<Element, NativePanel>> it = panels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Element, NativePanel> entry = it.next();
            if (!targets.contains(entry.getKey())) {
                entry.getValue().restore();
                it.remove();
            }
        }
    }
    private void target(Element element) {
        if (element == null || targets.contains(element)) return;
        targets.add(element);
        if (!panels.containsKey(element)) panels.put(element, new NativePanel(element));
    }
    private final class NativePanel {
        final Element element;
        final Boolp original, wrapper;
        final boolean originalVisible;
        NativePanel(Element element) {
            this.element = element;
            original = element.visibility;
            originalVisible = element.visible;
            wrapper = () -> !suppressNative && (original == null ? originalVisible : original.get());
            element.visibility = wrapper;
        }
        void restore() {
            // Respect any replacement installed later by another mod.
            if (element.visibility != wrapper) return;
            element.visibility = original;
            if (original == null) element.visible = originalVisible;
            else element.updateVisibility();
        }
    }
    @Override public void close() {
        suppressNative = false;
        for (NativePanel panel : panels.values()) panel.restore();
        panels.clear();
        targets.clear();
        canvas.remove();
        installed = false;
    }

    private void drawHud(float width, float height) {
        scale = (float)Math.min(width / HudConfig.REFERENCE_WIDTH, height / HudConfig.REFERENCE_HEIGHT);
        if (scale <= 0 || source.state() == null || source.camera() == null) return;
        float stroke = Lines.getStroke();
        try {
            Lines.stroke(px(HudConfig.STROKE_WIDTH));
            if(source.commandMode())return; // Native command panel and the screen-space selection overlay own this view.
            if (source.buildMode() || source.suspended() || Vars.state.isPaused()) {
                String mode = source.buildMode()
                    ? "BUILD  " + source.buildTimePercent() + "%  /  "+source.constructionLayer()+"  /  TAB returns to flight"
                    : "FLIGHT HELD  /  " + (Vars.state.isPaused() ? "PAUSED" : "INPUT RELEASED");
                label(mode, width / 2, px(HudConfig.BUILD_BADGE_BOTTOM), HudConfig.MODE_FONT_SCALE, accent, Align.center);
                return;
            }
            drawFlight(width, height);
        } finally {
            Lines.stroke(stroke);
            Draw.reset();
        }
    }

    private void drawFlight(float width, float height) {
        FlightController.State state = source.state();
        float margin = px(HudConfig.EDGE_MARGIN), readoutY = px(HudConfig.READOUT_BOTTOM),
            telemetryY = px(HudConfig.TELEMETRY_BOTTOM);
        double speed = Math.sqrt(state.vx * state.vx + state.vy * state.vy + state.vz * state.vz) * metersPerTile;
        label(source.cameraView()+" / "+source.flightMode()+" / "+(Vars.net.client()?"SERVER":Core.settings.getInt("fpv-flight-speed",100)+"%"), margin, height - px(HudConfig.HEADER_Y), HudConfig.MODE_FONT_SCALE, accent, Align.left);
        label(source.rearView()?"OBSERVER / PILOT AIM / ALT + WHEEL ZOOM":"TAB BUILD / F5 2D / F7 VIEW / F9 MODE", margin, height - px(HudConfig.HEADER_SECOND_Y), HudConfig.SMALL_FONT_SCALE, text, Align.left);
        label(source.skyClock(), width - margin, height - px(HudConfig.HEADER_Y), HudConfig.MODE_FONT_SCALE, text, Align.right);
        label(source.skyPhase(), width - margin, height - px(HudConfig.HEADER_SECOND_Y), HudConfig.SMALL_FONT_SCALE, dim, Align.right);
        drawCompass(source.camera(), width, height);
        drawHorizon(source.camera(), width, height);
        label(fixed(speed, 1), margin, readoutY, HudConfig.VALUE_FONT_SCALE, text, Align.left);
        label("SPEED  m/s", margin, readoutY - px(HudConfig.READOUT_LABEL_GAP), HudConfig.LABEL_FONT_SCALE, dim, Align.left);
        label(fixed(state.y * metersPerTile, 1), width - margin, readoutY, HudConfig.VALUE_FONT_SCALE, text, Align.right);
        label("ALT DATUM  m", width - margin, readoutY - px(HudConfig.READOUT_LABEL_GAP), HudConfig.LABEL_FONT_SCALE, dim, Align.right);
        label("V/S  " + signed(state.vy * metersPerTile) + " m/s", width - margin,
            readoutY + px(HudConfig.CLIMB_DETAIL_GAP), HudConfig.SMALL_FONT_SCALE, dim, Align.right);
        drawThrottle(width);
        Unit unit = Vars.player.unit();
        CoreBuild core = Vars.player.core();
        drawHealth("HULL", unit.health, unit.maxHealth, margin, telemetryY, false);
        if (core == null) label("NO CORE", width - margin, telemetryY, HudConfig.LABEL_FONT_SCALE, warning, Align.right);
        else drawHealth("CORE", core.health, core.maxHealth, width - margin, telemetryY, true);
        if (Vars.state.rules.waves) {
            String wave = "WAVE " + Vars.state.wave;
            if (Vars.state.rules.waveTimer) wave += "  /  " + Math.max(0, (int)Math.ceil(Vars.state.wavetime / 60.0)) + "s";
            label(wave, width / 2, telemetryY, HudConfig.LABEL_FONT_SCALE, text, Align.center);
        }
        drawResources(core, margin, px(HudConfig.RESOURCE_BOTTOM));
        if (unit.stack != null && unit.stack.item != null && unit.stack.amount > 0) {
            label("CARGO  " + unit.stack.item.localizedName + " " + UI.formatAmount(unit.stack.amount),
                width - margin, px(HudConfig.RESOURCE_BOTTOM), HudConfig.SMALL_FONT_SCALE, text, Align.right);
        }
        String status = source.status();
        if (status != null && !status.isBlank() && !status.equals("Ready")) {
            label(status, width / 2, px(HudConfig.RESOURCE_BOTTOM), HudConfig.SMALL_FONT_SCALE, warning, Align.center);
        }
        label(source.flightMode().contains("STABILIZED")||source.flightMode().startsWith("GROUND")||source.flightMode().contains("LANDING")
            ? "WASD pilot heading   E/Q altitude   Mouse look   LMB fire   TAB build   F6 recover"
            : "W/S thrust   A/D yaw   Mouse pitch / roll   LMB fire   TAB build   F6 recover",
            width / 2, px(HudConfig.HELP_BOTTOM), HudConfig.SMALL_FONT_SCALE, dim, Align.center);
    }

    private void drawThrottle(float width) {
        double throttle = Math.max(0, Math.min(1, source.throttle()));
        float rw = px(HudConfig.THROTTLE_WIDTH), rh = px(HudConfig.THROTTLE_HEIGHT),
            x = (width - rw) / 2, y = px(HudConfig.THROTTLE_BOTTOM);
        label("THR  " + fixed(throttle * 100, 0) + "%", width / 2,
            y + px(HudConfig.THROTTLE_LABEL_GAP), HudConfig.LABEL_FONT_SCALE, text, Align.center);
        Draw.color(track); Fill.crect(x, y, rw, rh);
        Draw.color(accent); Fill.crect(x, y, (float)(rw * throttle), rh);
        Draw.reset();
    }

    private void drawHealth(String title, float value, float maximum, float x, float y, boolean right) {
        double fraction = maximum <= 0 ? 0 : Math.max(0, Math.min(1, value / maximum));
        Color color = fraction <= HudConfig.HEALTH_WARNING_FRACTION ? warning : text;
        label(title + "  " + fixed(Math.max(0, value), 0) + " / " + fixed(maximum, 0),
            x, y, HudConfig.LABEL_FONT_SCALE, color, right ? Align.right : Align.left);
        float width = px(HudConfig.HEALTH_BAR_WIDTH), height = px(HudConfig.HEALTH_BAR_HEIGHT),
            left = right ? x - width : x, bottom = y - px(HudConfig.HEALTH_BAR_OFFSET);
        Draw.color(track); Fill.crect(left, bottom, width, height);
        Draw.color(color); Fill.crect(left, bottom, (float)(width * fraction), height);
        Draw.reset();
    }

    private void drawResources(CoreBuild core, float x, float y) {
        if (core == null || core.items == null) return;
        int count = 0;
        for (Item item : Vars.content.items()) {
            int amount = core.items.get(item);
            if (amount <= 0) continue;
            float size = px(HudConfig.RESOURCE_ICON_SIZE), cellX = x + count * px(HudConfig.RESOURCE_CELL_WIDTH);
            Draw.color(Color.white);
            Draw.rect(item.uiIcon, cellX + size / 2, y - size / 2, size, size);
            label(UI.formatAmount(amount), cellX + px(HudConfig.RESOURCE_AMOUNT_OFFSET) + size / 2,
                y, HudConfig.SMALL_FONT_SCALE, text, Align.left);
            if (++count >= (int)HudConfig.RESOURCE_COUNT) break;
        }
        if (count == 0) label("CORE STOCK  0", x, y, HudConfig.SMALL_FONT_SCALE, dim, Align.left);
        Draw.reset();
    }

    private void drawCompass(Camera3D camera, float width, float height) {
        if (Math.hypot(camera.direction.x, camera.direction.z) < 1e-8) return;
        double heading = wrap(Math.toDegrees(Math.atan2(camera.direction.x, -camera.direction.z)));
        double range = HudConfig.COMPASS_RANGE_DEGREES, tick = HudConfig.COMPASS_TICK_DEGREES;
        float tapeWidth = px(HudConfig.COMPASS_WIDTH), tapeY = height - px(HudConfig.COMPASS_Y);
        long start = (long)Math.ceil((heading - range / 2) / tick), end = (long)Math.floor((heading + range / 2) / tick);
        for (long index = start; index <= end; index++) {
            double degrees = index * tick;
            float x = width / 2 + (float)((degrees - heading) / range * tapeWidth);
            Draw.color(dim);
            Lines.line(x, tapeY, x, tapeY + px(HudConfig.COMPASS_TICK_HEIGHT));
            double labelStep = degrees / HudConfig.COMPASS_LABEL_DEGREES;
            if (Math.abs(labelStep - Math.rint(labelStep)) < 1e-8) {
                int angle = (int)Math.round(wrap(degrees)) % 360;
                String mark = angle == 0 ? "N" : angle == 90 ? "E" : angle == 180 ? "S" : angle == 270 ? "W" : Integer.toString(angle);
                label(mark, x, tapeY + px(HudConfig.COMPASS_LABEL_Y), HudConfig.SMALL_FONT_SCALE, text, Align.center);
            }
        }
        Draw.color(accent);
        Lines.line(width / 2, tapeY - px(HudConfig.COMPASS_TICK_HEIGHT), width / 2, tapeY);
        label(String.format(java.util.Locale.ROOT, "%03d", (int)Math.round(heading) % 360),
            width / 2, tapeY - px(HudConfig.COMPASS_LABEL_Y), HudConfig.LABEL_FONT_SCALE, accent, Align.center);
        Draw.reset();
    }

    private void drawHorizon(Camera3D camera, float width, float height) {
        // World-up dot viewing ray = 0 is the horizon. Using the actual camera basis
        // includes FPV camera tilt and handles vertical banks without dividing by up.y.
        double tan = Math.tan(Math.toRadians(camera.fov) / 2);
        double rightY = camera.direction.z * camera.up.x - camera.direction.x * camera.up.z;
        double a = rightY * tan * (camera.width / camera.height) / (width / 2);
        double b = camera.up.y * tan / (height / 2), c = camera.direction.y;
        double square = a * a + b * b;
        if (Double.isFinite(square) && square > 1e-16) {
            double length = Math.sqrt(square), hx = -c * a / square, hy = -c * b / square;
            double dx = -b / length, dy = a / length;
            double hw = px(HudConfig.HORIZON_WIDTH) / 2, hh = px(HudConfig.HORIZON_HEIGHT) / 2;
            double low = Double.NEGATIVE_INFINITY, high = Double.POSITIVE_INFINITY;
            if (Math.abs(dx) < 1e-8) {
                if (Math.abs(hx) > hw) high = Double.NEGATIVE_INFINITY;
            } else {
                double t1 = (-hw - hx) / dx, t2 = (hw - hx) / dx;
                low = Math.max(low, Math.min(t1, t2)); high = Math.min(high, Math.max(t1, t2));
            }
            if (Math.abs(dy) < 1e-8) {
                if (Math.abs(hy) > hh) high = Double.NEGATIVE_INFINITY;
            } else {
                double t1 = (-hh - hy) / dy, t2 = (hh - hy) / dy;
                low = Math.max(low, Math.min(t1, t2)); high = Math.min(high, Math.max(t1, t2));
            }
            if (Double.isFinite(low) && Double.isFinite(high) && low <= high) {
                float x1 = width / 2 + (float)(hx + low * dx), y1 = height / 2 + (float)(hy + low * dy);
                float x2 = width / 2 + (float)(hx + high * dx), y2 = height / 2 + (float)(hy + high * dy);
                Draw.color(horizonColor.set(accent).a((float)HudConfig.HORIZON_ALPHA));
                Lines.line(x1, y1, x2, y2);
                float cap = px(HudConfig.HORIZON_END_CAP);
                Lines.line(x1, y1, x1 + (float)(a / length) * cap, y1 + (float)(b / length) * cap);
                Lines.line(x2, y2, x2 + (float)(a / length) * cap, y2 + (float)(b / length) * cap);
            }
        }
        float cx = width / 2, cy = height / 2, gap = px(HudConfig.RETICLE_GAP), arm = px(HudConfig.RETICLE_ARM);
        if(source.rearView())return;
        Draw.color(text);
        Lines.line(cx - gap - arm, cy, cx - gap, cy);
        Lines.line(cx + gap, cy, cx + gap + arm, cy);
        Lines.line(cx, cy - gap - arm, cx, cy - gap);
        Draw.reset();
    }

    private void label(String value, float x, float y, double fontScale, Color color, int align) {
        if (value == null || value.isEmpty()) return;
        float size = px(fontScale), offset = px(HudConfig.TEXT_SHADOW_OFFSET);
        Fonts.outline.draw(value, x + offset, y - offset, shadow, size, false, align);
        Fonts.outline.draw(value, x, y, color, size, false, align);
    }
    private float px(double value) { return (float)value * scale; }
    private static Color color(double rgba) { return new Color((int)(long)rgba); }
    private static String fixed(double value, int places) { return Double.isFinite(value) ? Strings.fixed((float)value, places) : "--"; }
    private static String signed(double value) { return (value > 0 ? "+" : "") + fixed(value, 1); }
    private static double wrap(double angle) { return (angle % 360 + 360) % 360; }
}
