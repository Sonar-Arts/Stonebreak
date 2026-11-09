package com.stonebreak.fx;

import com.openmason.engine.rendering.particles.FxHost;
import com.openmason.engine.rendering.particles.FxInstance;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.FxWorld;
import com.openmason.engine.rendering.particles.ParticleCollider;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.stonebreak.fx.sbfx.SbfxDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The game's FX simulation (Stonebreak#436): one engine {@link ParticleSystem} under one world budget, the
 * {@link FxWorld} running every playing FX system on it, and the game hooks the engine asks for — block
 * collision, sounds, the precision anchor. GL-free: the {@code ParticlePass} renders it, tests drive it alone.
 *
 * <p><b>Client-side only.</b> FX are cosmetic: the integrated server never simulates particles (two-world rule).
 * Server-side happenings reach FX through the events the client already receives ({@code SoundEventS2C}).
 *
 * <p><b>Threading:</b> the render (main) thread only, like the engine runtime. {@link Fx} hands calls from other
 * threads over.
 *
 * <p><b>Allocation:</b> {@link #update} and {@link #play} allocate nothing once warm (pinned by
 * {@code FxRuntimeAllocationTest}); an unknown FX id allocates its one-time warning.
 *
 * <h2>Parameter conventions</h2>
 * Shipped FX systems may expose these parameters; the game sets them when present and ignores them otherwise:
 * <ul>
 *   <li>{@value #PARAM_DENSITY} (float) — the player's particle density setting (0.25..1); bind it to RATE and
 *       BURST so the setting thins every effect.</li>
 *   <li>{@value #PARAM_TINT} (color) — an asset binding's {@code tintFromTexture} colour (debris like its block).</li>
 *   <li>{@value #PARAM_INTENSITY} (float) — scaled by how strong the event was (a splash's impact speed).</li>
 * </ul>
 */
public final class FxRuntime implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FxRuntime.class);

    /** One world budget for every FX particle (the engine grows its arrays lazily up to it). */
    public static final int DEFAULT_BUDGET = 24_000;
    /** Most FX instances alive at once. */
    public static final int MAX_INSTANCES = 2048;

    public static final String PARAM_DENSITY = "density";
    public static final String PARAM_TINT = "tint";
    public static final String PARAM_INTENSITY = "intensity";

    private final FxLibrary library;
    private final ParticleSystem particles;
    private final FxWorld world;
    private final int budget;
    private float density = 1f;
    private double anchorX = Double.NaN, anchorY, anchorZ;
    private long lastUpdateNanos;
    private final Set<String> warnedIds = new HashSet<>();
    private boolean closed;

    public FxRuntime(FxLibrary library, int budget, FxHost host) {
        this.library = Objects.requireNonNull(library, "library");
        this.budget = budget;
        this.particles = new ParticleSystem(budget);
        this.world = new FxWorld(particles, MAX_INSTANCES);
        world.setHost(host);
        // Compile every shipped system up front: the first play of each then allocates nothing.
        for (SbfxDocument doc : library.documents()) {
            try {
                world.register(doc.system());
            } catch (RuntimeException e) {
                logger.error("FX {} does not compile and will not play: {}", doc.objectId(), e.getMessage());
            }
        }
    }

    public FxLibrary library() {
        return library;
    }

    public ParticleSystem particles() {
        return particles;
    }

    public FxWorld world() {
        return world;
    }

    public int budget() {
        return budget;
    }

    /** Wall time of the last {@link #update}, nanoseconds (F3). */
    public long lastUpdateNanos() {
        return lastUpdateNanos;
    }

    public void setCollider(ParticleCollider collider) {
        particles.setCollider(collider == null ? ParticleCollider.NONE : collider);
    }

    /** The density setting (0.25..1) every new instance's {@value #PARAM_DENSITY} parameter starts at. */
    public void setDensity(float density) {
        this.density = Math.clamp(density, 0.25f, 1f);
    }

    public float density() {
        return density;
    }

    /**
     * Plays the shipped FX named {@code id} ({@code stonebreak:name} or the bare system id) at world
     * {@code (x, y, z)}: a retained handle the caller must {@link FxInstance#release()} (stop it first to end it).
     * Null when the game ships no such FX (warned once) or the instance budget is full.
     */
    public FxInstance play(String id, double x, double y, double z) {
        FxSystem system = library.find(id);
        if (system == null) {
            warnUnknown(id);
            return null;
        }
        return play(system, x, y, z);
    }

    /** {@link #play(String, double, double, double)} for a resolved system. */
    public FxInstance play(FxSystem system, double x, double y, double z) {
        if (closed) {
            return null;
        }
        FxInstance inst;
        try {
            inst = world.play(system, x, y, z);
        } catch (RuntimeException e) {
            warnUnknown(system.name() + " (" + e.getMessage() + ")");
            return null;
        }
        if (inst != null) {
            FxParams.setFloat(inst, system, PARAM_DENSITY, density);
        }
        return inst;
    }

    /** Fire and forget; false when it did not play. */
    public boolean fire(String id, double x, double y, double z) {
        FxInstance inst = play(id, x, y, z);
        if (inst == null) {
            return false;
        }
        inst.release();
        return true;
    }

    /**
     * Advances every FX by {@code dt} seconds. {@code viewer*} is the camera (distance thinning), {@code anchor*}
     * the render origin in world space: particles are stored relative to it, so moving it with the origin keeps
     * them precise anywhere in the world (O(live), only when it moves).
     */
    public void update(float dt, double viewerX, double viewerY, double viewerZ,
                       double anchorX, double anchorY, double anchorZ) {
        if (closed) {
            return;
        }
        long start = System.nanoTime();
        if (anchorX != this.anchorX || anchorY != this.anchorY || anchorZ != this.anchorZ) {
            particles.setAnchor(anchorX, anchorY, anchorZ);
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.anchorZ = anchorZ;
        }
        particles.setViewer(viewerX, viewerY, viewerZ);
        world.update(Math.max(0f, dt));
        lastUpdateNanos = System.nanoTime() - start;
    }

    /** Ends every FX at once and removes every particle (a world change). Handles stay valid until released. */
    public void clear() {
        if (closed) {
            return;
        }
        world.stopAll(0f);
        particles.clearParticles();
    }

    public int liveParticles() {
        return particles.liveCount();
    }

    public int instanceCount() {
        return world.instanceCount();
    }

    private void warnUnknown(String id) {
        if (warnedIds.size() < 256 && warnedIds.add(String.valueOf(id))) {
            logger.warn("FX '{}' is not shipped (no sbfx/ asset has that id); binding ignored", id);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            world.close();
        }
    }
}
