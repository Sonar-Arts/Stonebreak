package com.stonebreak.rendering.gameWorld.weather;

import com.openmason.engine.rendering.particles.FxEmitter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.ParticleEffect;
import com.openmason.engine.rendering.particles.ParticleGpuBackend;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.fx.sbfx.SbfxDocument;
import com.stonebreak.rendering.gameWorld.particles.ParticlePass;
import com.stonebreak.world.World;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.world.weather.CloudColumn;
import com.stonebreak.world.weather.WeatherSurface;
import org.joml.Vector3f;

/**
 * Rain and snow around the camera as FX particles: the shipped {@code stonebreak:precipitation} system's
 * {@code rain}/{@code snow} effects, spawned drop by drop under the raining clouds and simulated (on the GPU where
 * available) by the world's FX runtime. Drops land on the ground and die there; rain splashes. Takes over the
 * {@link PrecipitationRenderer#NEAR near lane ring}: the same reach, the same fade-out toward its edge (so the far
 * ring's fade-in completes it), the same rule for where it rains (the cloud field's precipitation, falling from the
 * cloud's base) and where it snows (snowy ground). The far ring stays lanes: rain hanging under clouds 200 blocks
 * away is not particle work.
 *
 * <p>Where it rains comes from the CPU cloud field ({@link ClientWeather#columnAt}, the mirror of the field the
 * clouds and the lanes are drawn from), sampled on a coarse grid a few times a second. Ground heights come from
 * loaded chunks ({@link WeatherSurface}), as for the lanes, and also feed the GPU backend's height field, so GPU drops
 * land where CPU ones would.
 *
 * <p>Off (the near ring stays lanes) with {@code -Dstonebreak.rain=lanes}, when the player switched particles off, or
 * when the asset is missing. Main thread only; allocation-free per frame.
 */
public final class PrecipitationFx {

    /** {@code -Dstonebreak.rain=lanes|particles}: who draws the near rain (A/B switch). */
    public static final String MODE_PROPERTY = "stonebreak.rain";

    public static final String SYSTEM = "precipitation";
    public static final String RAIN_EMITTER = "rain";
    public static final String SNOW_EMITTER = "snow";
    public static final String RAIN_EFFECT = "sb-rain";
    public static final String SNOW_EFFECT = "sb-snow";
    /** Seconds a drop lives at most (it usually lands first): the rain fades out over its last stretch upward. */
    public static final float RAIN_LIFE = 1.25f;
    public static final float SNOW_LIFE = 9f;

    /** Blocks/s the effects fall at (the asset's launch speed): converts a column's height into a spawn rate. */
    static final float RAIN_SPEED = 16f;
    static final float SNOW_SPEED = 1.5f;
    /** Drops per cubic block in full rain/snow (at the density setting's maximum). */
    static final float RAIN_DENSITY = 0.7f;
    static final float SNOW_DENSITY = 0.5f;

    /** Share of the drops spawned when they simulate on the CPU. */
    static final float CPU_SHARE = 0.3f;

    /** Reach: the near lane ring's. Spawning thins from {@code FADE_FROM × RADIUS} out to it, as its lanes fade. */
    static final float RADIUS = PrecipitationRenderer.NEAR.radius();
    static final float FADE_FROM = 0.6f;
    /** Drops spawn at most this far above the camera (rain fades out above it)... */
    static final float ABOVE = 40f;
    /** ...and fall no further than this below it (the near lanes' limit). */
    static final float BELOW = PrecipitationRenderer.NEAR.below();

    /** Spawn cells, blocks: every cell rains or not on its own. */
    static final int CELL = 2;
    private static final int CELL_HALF_SPAN = (int) Math.ceil(RADIUS / CELL);
    private static final int CELLS = 2 * CELL_HALF_SPAN + 1;
    /** Cloud samples, blocks apart (the cloud map's own cells are 12 blocks: the field is smooth at this scale). */
    static final int CLOUD_STEP = 4;
    private static final int CLOUD_HALF_SPAN = (int) Math.ceil((RADIUS + CELL) / CLOUD_STEP) + 1;
    private static final int CLOUD_SIZE = 2 * CLOUD_HALF_SPAN + 2;
    /** Seconds between cloud refreshes (the clouds drift ~0.6 blocks/s). */
    private static final float CLOUD_REFRESH = 0.25f;
    /** Ground columns: the reach plus a cell each side. */
    private static final int GROUND_HALF_SPAN = (int) RADIUS + CELL + 1;
    private static final int GROUND_SIZE = 2 * GROUND_HALF_SPAN + 1;
    private static final float GROUND_REFRESH = 0.5f;
    private static final float GROUND_UNKNOWN = -1.0e6f;
    private static final float NO_CLOUD = Float.POSITIVE_INFINITY;

    private final ParticlePass pass;
    private final boolean particlesMode;
    private ParticleEffect rain;
    private ParticleEffect snow;
    private boolean resolved;

    private final float[] cloudRain = new float[CLOUD_SIZE * CLOUD_SIZE];
    private final float[] cloudBase = new float[CLOUD_SIZE * CLOUD_SIZE];
    private int cloudX0 = Integer.MIN_VALUE, cloudZ0;
    private float cloudAge = Float.POSITIVE_INFINITY;

    private final float[] ground = new float[GROUND_SIZE * GROUND_SIZE];
    private final boolean[] snowy = new boolean[GROUND_SIZE * GROUND_SIZE];
    private int groundX0 = Integer.MIN_VALUE, groundZ0;
    private float groundAge = Float.POSITIVE_INFINITY;
    private boolean heightFieldDirty = true;
    private ParticleGpuBackend heightFieldBackend;
    private World lastWorld;

    private long seed = 0x9E3779B97F4A7C15L;
    private float lastTime = Float.NaN;
    private boolean active;
    private int spawnedLastFrame;

    public PrecipitationFx(ParticlePass pass) {
        this.pass = pass;
        this.particlesMode = !"lanes".equalsIgnoreCase(System.getProperty(MODE_PROPERTY, "particles").trim());
    }

    /** True while particles draw the near rain this frame (the lane renderer then draws only its far ring). */
    public boolean active() {
        return active;
    }

    /** Drops spawned in the last {@link #update} (F3 / bench). */
    public int spawnedLastFrame() {
        return spawnedLastFrame;
    }

    /** "particles" or "lanes": what the A/B switch asked for. */
    public String mode() {
        return particlesMode ? "particles" : "lanes";
    }

    /**
     * Spawns this frame's drops around the camera (world position {@code cam}). Call once per frame, before the
     * particle pass draws. Inactive in lanes mode, with particles off or without the asset; spawns nothing while
     * the camera is under water (the lanes are not drawn there either).
     */
    public void update(World world, ClientWeather weather, Vector3f cam, float totalTime, boolean underwater) {
        float dt = Float.isNaN(lastTime) ? 0f : Math.clamp(totalTime - lastTime, 0f, 0.1f);
        lastTime = totalTime;
        spawnedLastFrame = 0;
        active = particlesMode && Fx.enabled() && world != null && weather != null && resolve();
        if (!active) {
            return;
        }
        if (world != lastWorld) {
            lastWorld = world;
            cloudX0 = Integer.MIN_VALUE;
            groundX0 = Integer.MIN_VALUE;
        }
        FxRuntime runtime = pass.runtime();
        refreshClouds(weather, cam, dt);
        refreshGround(world, cam, dt);
        uploadHeightField();
        if (underwater || dt <= 0f) {
            return;
        }
        // Without GPU simulation (no GL 4.3) drops share the CPU budget with every other FX: full rain alone would
        // fill the AMBIENT share and starve torch/furnace FX, and cost ~1 ms of simulation per frame.
        ParticleSystem particles = runtime.particles();
        spawn(particles, cam, dt, runtime.density() * (particles.gpuEnabled() ? 1f : CPU_SHARE));
        if (spawnedLastFrame > 0) {
            pass.markGpuSpawn(totalTime);
        }
    }

    /** Finds the asset's effects once (the very instances the runtime compiled). */
    private boolean resolve() {
        if (!resolved) {
            resolved = true;
            FxSystem system = pass.runtime().library().find(SbfxDocument.objectIdFor(SYSTEM));
            FxEmitter r = system == null ? null : system.emitter(RAIN_EMITTER);
            FxEmitter s = system == null ? null : system.emitter(SNOW_EMITTER);
            rain = r == null ? null : r.effect();
            snow = s == null ? null : s.effect();
            if (rain == null || snow == null) {
                org.slf4j.LoggerFactory.getLogger(PrecipitationFx.class).warn(
                        "FX {} (emitters rain/snow) is not shipped; near rain stays lanes",
                        SbfxDocument.objectIdFor(SYSTEM));
            }
        }
        return rain != null && snow != null;
    }

    private void refreshClouds(ClientWeather weather, Vector3f cam, float dt) {
        int x0 = Math.floorDiv((int) Math.floor(cam.x), CLOUD_STEP) * CLOUD_STEP - CLOUD_HALF_SPAN * CLOUD_STEP;
        int z0 = Math.floorDiv((int) Math.floor(cam.z), CLOUD_STEP) * CLOUD_STEP - CLOUD_HALF_SPAN * CLOUD_STEP;
        cloudAge += dt;
        if (x0 == cloudX0 && z0 == cloudZ0 && cloudAge < CLOUD_REFRESH) {
            return;
        }
        cloudX0 = x0;
        cloudZ0 = z0;
        cloudAge = 0f;
        for (int j = 0; j < CLOUD_SIZE; j++) {
            for (int i = 0; i < CLOUD_SIZE; i++) {
                CloudColumn col = weather.columnAt(x0 + i * CLOUD_STEP, z0 + j * CLOUD_STEP);
                float p = col.precipitation();
                cloudRain[j * CLOUD_SIZE + i] = p;
                cloudBase[j * CLOUD_SIZE + i] = p > 0f ? col.baseY() : NO_CLOUD;
            }
        }
    }

    private void refreshGround(World world, Vector3f cam, float dt) {
        int x0 = (int) Math.floor(cam.x) - GROUND_HALF_SPAN;
        int z0 = (int) Math.floor(cam.z) - GROUND_HALF_SPAN;
        groundAge += dt;
        if (x0 == groundX0 && z0 == groundZ0 && groundAge < GROUND_REFRESH) {
            return;
        }
        boolean moved = x0 != groundX0 || z0 != groundZ0;
        groundX0 = x0;
        groundZ0 = z0;
        groundAge = 0f;
        boolean changed = moved;
        for (int j = 0; j < GROUND_SIZE; j++) {
            for (int i = 0; i < GROUND_SIZE; i++) {
                int k = j * GROUND_SIZE + i;
                int y = WeatherSurface.groundY(world, x0 + i, z0 + j);
                float g = y == WeatherSurface.UNKNOWN ? GROUND_UNKNOWN : y;
                if (g != ground[k]) {
                    ground[k] = g;
                    changed = true;
                }
                snowy[k] = y != WeatherSurface.UNKNOWN && WeatherSurface.isSnow(world, x0 + i, z0 + j);
            }
        }
        heightFieldDirty |= changed;
    }

    /** The GPU drops' ground: the same columns, solid below their surface. */
    private void uploadHeightField() {
        ParticleGpuBackend gpu = pass.renderer().gpu();
        if (gpu == null) {
            return;
        }
        if (gpu != heightFieldBackend) {
            heightFieldBackend = gpu;
            heightFieldDirty = true;
        }
        if (heightFieldDirty) {
            heightFieldDirty = false;
            gpu.setHeightField(groundX0, groundZ0, 1f, GROUND_SIZE, GROUND_SIZE, ground);
        }
    }

    private void spawn(ParticleSystem particles, Vector3f cam, float dt, float density) {
        int cx0 = Math.floorDiv((int) Math.floor(cam.x), CELL) - CELL_HALF_SPAN;
        int cz0 = Math.floorDiv((int) Math.floor(cam.z), CELL) - CELL_HALF_SPAN;
        float fadeFrom = FADE_FROM * RADIUS;
        float top = cam.y + ABOVE;
        float floor = cam.y - BELOW;
        for (int cj = 0; cj < CELLS; cj++) {
            for (int ci = 0; ci < CELLS; ci++) {
                float x = (cx0 + ci) * CELL + 0.5f * CELL;
                float z = (cz0 + cj) * CELL + 0.5f * CELL;
                float dist = (float) Math.hypot(x - cam.x, z - cam.z);
                if (dist > RADIUS) continue;
                float intensity = sampleCloud(cloudRain, x, z);
                if (intensity <= 0f) continue;
                float base = sampleCloudBase(x, z);
                // The camera above the cloud: no rain (the lanes' rule — they search upward from the eye).
                if (base <= cam.y) continue;
                float cellTop = Math.min(base, top);
                int gk = groundIndex(x, z);
                if (gk < 0) continue;
                float bottom = Math.max(ground[gk], floor);
                float height = cellTop - bottom;
                if (height <= 0f) continue;
                boolean isSnow = snowy[gk];
                float edge = 1f - smoothstep(fadeFrom, RADIUS, dist);
                // Light rain falls in fewer lanes; each drop is fainter too (its tint's alpha).
                float share = Math.min(1f, intensity + 0.25f) * edge * density;
                float expected = isSnow
                        ? SNOW_DENSITY * CELL * CELL * height / SNOW_LIFE * share * dt
                        : RAIN_DENSITY * CELL * CELL * height / RAIN_LIFE * share * dt;
                int n = (int) expected;
                if (nextFloat() < expected - n) n++;
                if (n == 0) continue;
                int alpha = Math.round(255f * (0.35f + 0.65f * Math.min(1f, intensity)));
                int tint = 0xFFFFFF00 | alpha;
                ParticleEffect effect = isSnow ? snow : rain;
                for (int d = 0; d < n; d++) {
                    float px = x + (nextFloat() - 0.5f) * CELL;
                    float pz = z + (nextFloat() - 0.5f) * CELL;
                    int dk = groundIndex(px, pz);
                    float lo = dk < 0 ? bottom : Math.max(ground[dk], floor);
                    if (lo >= cellTop) continue;
                    float py = lo + nextFloat() * (cellTop - lo);
                    spawnedLastFrame += particles.burst(effect, px, py, pz, 0f, -1f, 0f, 1, tint);
                }
            }
        }
    }

    /** Index into the ground grid of the column holding world {@code (x, z)}, or -1 outside it. */
    private int groundIndex(float x, float z) {
        int i = (int) Math.floor(x) - groundX0;
        int j = (int) Math.floor(z) - groundZ0;
        if (i < 0 || j < 0 || i >= GROUND_SIZE || j >= GROUND_SIZE) return -1;
        return j * GROUND_SIZE + i;
    }

    /** Bilinear sample of a cloud grid at world {@code (x, z)}. */
    private float sampleCloud(float[] grid, float x, float z) {
        float fx = (x - cloudX0) / CLOUD_STEP;
        float fz = (z - cloudZ0) / CLOUD_STEP;
        int i = Math.clamp((int) Math.floor(fx), 0, CLOUD_SIZE - 2);
        int j = Math.clamp((int) Math.floor(fz), 0, CLOUD_SIZE - 2);
        float tx = Math.clamp(fx - i, 0f, 1f), tz = Math.clamp(fz - j, 0f, 1f);
        int k = j * CLOUD_SIZE + i;
        float a = grid[k] + (grid[k + 1] - grid[k]) * tx;
        float b = grid[k + CLOUD_SIZE] + (grid[k + CLOUD_SIZE + 1] - grid[k + CLOUD_SIZE]) * tx;
        return a + (b - a) * tz;
    }

    /** The lowest raining cloud base among the four samples around {@code (x, z)} (rain falls from the lowest). */
    private float sampleCloudBase(float x, float z) {
        int i = Math.clamp((int) Math.floor((x - cloudX0) / CLOUD_STEP), 0, CLOUD_SIZE - 2);
        int j = Math.clamp((int) Math.floor((z - cloudZ0) / CLOUD_STEP), 0, CLOUD_SIZE - 2);
        int k = j * CLOUD_SIZE + i;
        return Math.min(Math.min(cloudBase[k], cloudBase[k + 1]),
                Math.min(cloudBase[k + CLOUD_SIZE], cloudBase[k + CLOUD_SIZE + 1]));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    /** xorshift64*, [0, 1). */
    private float nextFloat() {
        long x = seed;
        x ^= x >>> 12;
        x ^= x << 25;
        x ^= x >>> 27;
        seed = x;
        return ((x * 0x2545F4914F6CDD1DL) >>> 40) / (float) (1 << 24);
    }
}
