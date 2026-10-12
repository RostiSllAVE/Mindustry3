package fpv.units;

import arc.math.Mathf;
import arc.math.Rand;
import arc.math.geom.QuadTree;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.struct.Seq;
import fpv.world.Unit3DState;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import mindustry.Vars;
import mindustry.async.AsyncProcess;
import mindustry.async.PhysicsProcess;
import mindustry.async.PhysicsProcess.PhysicRef;
import mindustry.async.PhysicsProcess.PhysicsWorld;
import mindustry.async.PhysicsProcess.PhysicsWorld.PhysicsBody;
import mindustry.gen.Unit;

/**
 * Verified native passive separation adapter. The original process still owns body registration,
 * collision layers, worker submission and the eventual native unit.move. Only overlapping
 * vertical intervals separate, using the shared flying-body hull and native ground radius.
 * All lifecycle methods must run on the game thread; layer workers read immutable snapshots.
 */
public final class AltitudeSeparation implements AsyncProcess, AutoCloseable {
    private static final Field worldsField = field("physics"), refsField = field("refs"), futuresField = field("futures");
    private final PhysicsProcess nativeProcess;
    private final Function<Unit, Unit3DState> stateProvider;
    private final BooleanSupplier enabled;
    private PhysicsWorld[] nativeWorlds;
    private Layer[] layers;
    private boolean installed, closed;

    public AltitudeSeparation(PhysicsProcess nativeProcess, Function<Unit, Unit3DState> stateProvider,
                              BooleanSupplier enabled) {
        if (nativeProcess == null || stateProvider == null || enabled == null) {
            throw new IllegalArgumentException("Native physics, physical unit states and enabled predicate required");
        }
        this.nativeProcess = nativeProcess;
        this.stateProvider = stateProvider;
        this.enabled = enabled;
    }

    /** Register immediately before the retained native process, never replace Vars.unitPhysics. */
    public void install() {
        if (closed) throw new IllegalStateException("Altitude separation is closed");
        if (installed) return;
        int index = Vars.asyncCore.processes.indexOf(nativeProcess, true);
        if (index < 0) throw new IllegalStateException("Pinned native physics process is not registered");
        Vars.asyncCore.processes.insert(index, this);
        installed = true;
    }

    /** Only the authority resolves passive contacts; clients receive its unit positions. */
    public void syncActive() {
        if (closed) return;
        boolean wanted = enabled.getAsBoolean() && (Vars.net == null || !Vars.net.client());
        PhysicsWorld[] current = worlds();
        if (!wanted) {
            if (layers != null) detach(true);
            return;
        }
        if (current == null) {
            if (layers != null) detach(false);
            return;
        }
        if (current != nativeWorlds) {
            if (layers != null) detach(true);
            awaitPending();
            discardPendingCorrection();
            nativeWorlds = current;
            layers = new Layer[current.length];
            Rect bounds = Vars.world.getQuadBounds(new Rect());
            for (int i = 0; i < current.length; i++) {
                layers[i] = new Layer(bounds, current[i], this::awaitPending);
                current[i] = layers[i];
            }
            for (PhysicRef ref : refs()) {
                if (ref.lastLayer >= 0 && ref.lastLayer < layers.length) layers[ref.lastLayer].seed(ref.body);
            }
            publish();
        }
    }

    @Override public void begin() {
        syncActive();
        if (layers == null) return;
        awaitPending();
        publish();
    }

    @Override public boolean shouldProcess() { return false; }
    @Override public void init() { detach(false); }
    @Override public void reset() { detach(false); }
    public boolean active() { return layers != null; }

    /** Call after an explicit controlled-pose reset/possession change, before native correction commits. */
    public void rebase() {
        if (closed) return;
        awaitPending();
        discardPendingCorrection();
        if (layers != null) publish();
    }

    @Override public void close() {
        if (closed) return;
        detach(true);
        if (installed && Vars.asyncCore != null) Vars.asyncCore.processes.remove(this, true);
        installed = false;
        closed = true;
    }

    private void publish() {
        IdentityHashMap<PhysicsBody, Unit3DState> next = new IdentityHashMap<>();
        for (PhysicRef ref : refs()) {
            Unit unit = ref.entity;
            if (unit == null || !unit.isAdded() || unit.dead) continue;
            Unit3DState state = stateProvider.apply(unit);
            if (state != null && state.unitId() == unit.id && state.typeId() == unit.type.id) next.put(ref.body, state);
        }
        Map<PhysicsBody, Unit3DState> snapshot = Collections.unmodifiableMap(next);
        for (Layer layer : layers) layer.snapshot = snapshot;
    }

    private void detach(boolean discardCorrection) {
        if (layers == null) {
            // World-reset callbacks precede the native reset, including an inactive adapter.
            awaitPending();
            return;
        }
        awaitPending();
        if (discardCorrection) discardPendingCorrection();
        for (int i = 0; i < layers.length; i++) {
            if (nativeWorlds[i] == layers[i]) nativeWorlds[i] = layers[i].original;
        }
        nativeWorlds = null;
        layers = null;
    }

    /** A correction computed under the previous collision policy must not leak across a mode switch. */
    private void discardPendingCorrection() {
        for (PhysicRef ref : refs()) {
            ref.body.x = ref.startX;
            ref.body.y = ref.startY;
        }
    }

    // Package-visible only for native smoke fixtures, which use an isolated real PhysicsProcess.
    void awaitPending() {
        Seq<Future<?>> futures = read(futuresField);
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for native unit separation", error);
            } catch (ExecutionException error) {
                throw new IllegalStateException("Native unit separation worker failed", error.getCause());
            }
        }
    }

    private PhysicsWorld[] worlds() { return read(worldsField); }
    private Seq<PhysicRef> refs() { return read(refsField); }
    @SuppressWarnings("unchecked") private <T> T read(Field field) {
        try { return (T)field.get(nativeProcess); }
        catch (IllegalAccessException error) { throw new IllegalStateException("Pinned native physics access failed", error); }
    }
    private static Field field(String name) {
        try {
            Field result = PhysicsProcess.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException("Altitude separation requires the verified native PhysicsProcess: " + name, error);
        }
    }

    /** Native layers remain independent; this wrapper adds only the physical vertical overlap test. */
    private static final class Layer extends PhysicsWorld {
        final PhysicsWorld original;
        final Seq<PhysicsBody> bodies = new Seq<>(false, 16, PhysicsBody.class);
        final Seq<PhysicsBody> found = new Seq<>(PhysicsBody.class);
        final QuadTree<PhysicsBody> tree;
        final Vec2 vector = new Vec2();
        final Rand random = new Rand();
        final Runnable beforeMutation;
        volatile Map<PhysicsBody, Unit3DState> snapshot = Map.of();

        Layer(Rect bounds, PhysicsWorld original, Runnable beforeMutation) {
            super(bounds);
            this.original = original;
            this.beforeMutation = beforeMutation;
            tree = new QuadTree<>(new Rect(bounds));
        }
        void seed(PhysicsBody body) {
            // In 160.7 refs already includes pending additions before native begin commits them.
            // Seeding the wrapper must not register that same body twice when begin calls add.
            if(!bodies.contains(body,true)) bodies.add(body);
        }
        @Override public void add(PhysicsBody body) {
            // Units may spawn during Logic.update while the previous begin's layer tasks run.
            // Always forward native registration: a seeded pending body is not in original yet.
            beforeMutation.run();original.add(body);seed(body);
        }
        @Override public void remove(PhysicsBody body) {
            beforeMutation.run();original.remove(body);bodies.remove(body,true);
        }

        @Override public void update() {
            // The pinned native implementation uses one iteration on desktop and mobile.
            Map<PhysicsBody, Unit3DState> heights = snapshot;
            tree.fill(bodies);
            float maximumExtraRadius=0;
            for (PhysicsBody body : bodies) {
                body.collided = false;
                maximumExtraRadius=Math.max(maximumExtraRadius,radius(body,heights.get(body))-body.radius);
            }
            for (PhysicsBody body : bodies) {
                if (!body.local) continue;
                found.clear();
                Unit3DState a = heights.get(body);
                float ownRadius=radius(body,a),queryRadius=ownRadius+maximumExtraRadius;
                // The native quadtree still indexes unchanged native circle bounds. This
                // padding also admits sprites whose shared physical hull is larger.
                tree.intersect(body.x-queryRadius, body.y-queryRadius, queryRadius*2, queryRadius*2, found);
                for (PhysicsBody other : found) {
                    if (other == body || other.collided) continue;
                    Unit3DState b = heights.get(other);
                    if (a != null && b != null && Math.abs(a.z()-b.z()) >= a.halfHeight()+b.halfHeight()) continue;
                    float radius = ownRadius+radius(other,b);
                    float dx = body.x-other.x, dy = body.y-other.y, distanceSquared = dx*dx+dy*dy;
                    if (distanceSquared >= radius*radius) continue;
                    float distance = Mathf.sqrt(distanceSquared);
                    vector.set(dx,dy);
                    if (vector.isZero()) vector.trns(random.random(360f),radius-distance);
                    else vector.setLength(radius-distance);
                    float mass = body.mass+other.mass;
                    float share = other.mass/mass, otherShare = body.mass/mass;
                    body.x += vector.x*share/1.25f;
                    body.y += vector.y*share/1.25f;
                    if (other.local) {
                        other.x -= vector.x*otherShare/1.25f;
                        other.y -= vector.y*otherShare/1.25f;
                    }
                }
                body.collided = true;
            }
        }
        private static float radius(PhysicsBody body,Unit3DState state) {
            return state!=null&&state.flying()?(float)(state.radius()*Vars.tilesize):body.radius;
        }
    }
}
