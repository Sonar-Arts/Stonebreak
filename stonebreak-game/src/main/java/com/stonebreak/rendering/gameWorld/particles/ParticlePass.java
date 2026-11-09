package com.stonebreak.rendering.gameWorld.particles;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.particles.ParticleFrame;
import com.openmason.engine.rendering.particles.ParticleRenderer;
import com.openmason.engine.rendering.particles.ParticleSpriteLayout;
import com.openmason.engine.rendering.particles.ParticleTextureArray;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.blocks.waterSystem.WaterHeightUtil;
import com.stonebreak.config.Settings;
import com.stonebreak.fx.BlockParticleCollider;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.FxLibrary;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.fx.GameFxHost;
import com.stonebreak.fx.bindings.AmbientFx;
import com.stonebreak.fx.bindings.BlockFx;
import com.stonebreak.fx.bindings.LightAmbientSources;
import com.stonebreak.fx.sbfx.ShippedFx;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.gameWorld.SceneDepthCopy;
import com.stonebreak.rendering.gameWorld.UnderwaterFog;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationFog;
import com.stonebreak.rendering.lighting.DynamicLights;
import com.stonebreak.world.World;
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

import static org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;

/**
 * The world's FX pass (Stonebreak#436): owns the game's {@link FxRuntime} (installed for {@link Fx}), the engine
 * {@link ParticleRenderer} with the game's shader hooks ({@link ParticleShaderGlsl}), the shared sprite texture
 * array and a scene-depth copy for soft particles. {@code WorldRenderer} drives it each frame:
 * <ol>
 *   <li>{@link #update} — after {@code DynamicLights.update}: ambient FX follow the light selection, then the
 *       simulation steps (precision anchor = the render origin, viewer = the camera).</li>
 *   <li>{@link #drawBehind} — before the water pass: culls, sorts, splits at sea level and uploads, then draws the
 *       particles on the far side of the water surface (under it, from above) so the water blends over them.</li>
 *   <li>{@link #drawFront} — after the water pass: the camera's side, over the water.</li>
 * </ol>
 * Particles light like the world (ambient dimmed in caves, torch lights) and fog like it (rain/snow fog, distance
 * fog, the underwater murk). The pass is free while nothing is alive (no depth copy, no draw).
 */
public final class ParticlePass implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ParticlePass.class);

    /** Texture unit the depth copy is made on (clear of world/water/shadow units 0-9). */
    private static final int DEPTH_COPY_UNIT = 11;
    /** Particles beyond this are culled (blocks). */
    private static final float MAX_DISTANCE = 160f;
    /** A frame longer than this is a hitch (load, alt-tab): step as if it were this long. */
    private static final float MAX_DT = 0.1f;
    /** Ocean surface: the top source cell under sea level, at its source height. */
    static final float SEA_SURFACE = WorldConfiguration.SEA_LEVEL - 1 + WaterHeightUtil.MAX_WATER_HEIGHT;

    private final PrecipitationFog precipitationFog;
    private final FxRuntime runtime;
    private final BlockParticleCollider collider = new BlockParticleCollider();
    private final AmbientFx ambient = new AmbientFx();
    private final ParticleRenderer renderer;
    private final ParticleFrame frame = new ParticleFrame();
    private final SceneDepthCopy depth = new SceneDepthCopy();
    private final Consumer<ShaderProgram> uniforms = this::applyUniforms;
    private final Vector3f camRender = new Vector3f();
    private final Vector3f fogColor = new Vector3f();
    private ParticleTextureArray sprites;
    private boolean spritesBuilt;

    private World lastWorld;
    private float lastTime = Float.NaN;
    private boolean wasEnabled = true;
    private int prepared;
    private float skyExposure = 1f;
    private float fogStart, fogEnd;

    public ParticlePass(PrecipitationFog precipitationFog) {
        this.precipitationFog = precipitationFog;
        FxLibrary library = ShippedFx.library();
        if (library.size() == 0) {
            library = ShippedFx.scanAndLoad();
        }
        this.runtime = new FxRuntime(library, FxRuntime.DEFAULT_BUDGET, new GameFxHost(ShippedFx::library));
        runtime.setCollider(collider);
        this.renderer = new ParticleRenderer(ParticleShaderGlsl.extension());
        frame.maxDistance = MAX_DISTANCE;
        frame.uniforms = uniforms;
        Fx.install(runtime);
    }

    public FxRuntime runtime() {
        return runtime;
    }

    public AmbientFx ambient() {
        return ambient;
    }

    /** Particles prepared for this frame (F3). */
    public int prepared() {
        return prepared;
    }

    public ParticleRenderer renderer() {
        return renderer;
    }

    /**
     * Advances FX by the time since the last frame. Call after {@code DynamicLights.update} (ambient FX follow its
     * selection). A new world (or FX switched off) ends every FX at once.
     */
    public void update(World world, Player player, float totalTime) {
        float dt = Float.isNaN(lastTime) ? 0f : Math.clamp(totalTime - lastTime, 0f, MAX_DT);
        lastTime = totalTime;
        boolean enabled = Fx.enabled();
        if (world != lastWorld || (wasEnabled && !enabled)) {
            ambient.clear();
            runtime.clear();
            lastWorld = world;
        }
        wasEnabled = enabled;
        prepared = 0;
        if (!enabled || world == null || player == null) {
            return;
        }
        Settings settings = Settings.getInstance();
        runtime.setDensity(settings.getParticleDensity());
        collider.setWorld(world);
        Vector3f cam = player.getCamera().getPosition();
        ambient.sync(runtime, LightAmbientSources.INSTANCE, BlockFx::data, BlockFx::tint, cam.x, cam.y, cam.z);
        runtime.update(dt, cam.x, cam.y, cam.z, RenderOrigin.x(), 0.0, RenderOrigin.z());
        skyExposure = sampleSky(world, cam);
    }

    /**
     * Culls, sorts, splits and uploads this frame's particles, then draws those beyond the water surface. Call right
     * before the water pass with the frame's lighting and fog.
     */
    public void drawBehind(Player player, Matrix4f projection, float ambientLight, boolean underwater,
                           Vector3f distanceFogColor, float distanceFogStart, float distanceFogEnd) {
        prepared = 0;
        if (!Fx.enabled() || player == null || runtime.liveParticles() == 0) {
            return;
        }
        ensureSprites();
        player.getRenderPosition(camRender);
        frame.camera(player.getViewMatrix(), projection, camRender.x, camRender.y, camRender.z)
                .origin(RenderOrigin.x(), 0.0, RenderOrigin.z())
                .sprites(sprites)
                .splitAtHeight(SEA_SURFACE);
        // Caves: the camera's sky exposure dims the ambient part, as the sky light dims the walls around it.
        frame.ambient = ambientLight * (0.2f + 0.8f * skyExposure);
        if (underwater) {
            frame.fogDensity = 3f / Math.max(1f, distanceFogEnd);
            frame.fogColor.set(UnderwaterFog.COLOR);
            fogStart = 0f;
            fogEnd = 0f;
        } else {
            frame.fogDensity = 0f;
            fogStart = distanceFogStart;
            fogEnd = distanceFogEnd;
            fogColor.set(distanceFogColor);
        }
        prepared = renderer.prepare(runtime.particles(), frame);
        if (prepared == 0) {
            return;
        }
        copyDepth();
        renderer.drawBehind();
    }

    /** Draws the particles on the camera's side of the water surface. Call after the water pass. */
    public void drawFront() {
        if (prepared > 0) {
            renderer.drawFront();
        }
    }

    private void copyDepth() {
        int previousUnit = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + DEPTH_COPY_UNIT);
        int previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        depth.copy();
        glBindTexture(GL_TEXTURE_2D, previousTexture);
        glActiveTexture(previousUnit);
        frame.depthTexture = depth.texture();
    }

    private void ensureSprites() {
        if (spritesBuilt) {
            return;
        }
        spritesBuilt = true;
        var effects = runtime.library().effects();
        if (ParticleSpriteLayout.referencedTextures(effects).isEmpty()) {
            return;
        }
        try {
            sprites = ParticleTextureArray.build(effects, runtime.library().spriteLoader(),
                    ParticleSpriteLayout.DEFAULT_CELL_SIZE);
            if (!sprites.layout().missing().isEmpty()) {
                logger.warn("FX sprites with no embedded image (drawn as the missing checker): {}",
                        sprites.layout().missing());
            }
        } catch (RuntimeException e) {
            logger.error("FX sprite array could not be built; textured FX show the missing checker", e);
        }
    }

    private void applyUniforms(ShaderProgram shader) {
        DynamicLights.applyTo(shader);
        precipitationFog.applyTo(shader);
        shader.setFloat("u_sbSkyExposure", skyExposure);
        shader.setFloat("u_sbFogStart", fogStart);
        shader.setFloat("u_sbFogEnd", fogEnd);
        shader.setVec3("u_sbFogColor", fogColor);
    }

    private static float sampleSky(World world, Vector3f cam) {
        try {
            return com.openmason.engine.voxel.lighting.VertexLightSampler.samplePointSky(
                    new com.stonebreak.world.lighting.WorldLightingContext(world), cam.x, cam.y, cam.z);
        } catch (RuntimeException e) {
            return 1f;
        }
    }

    @Override
    public void close() {
        Fx.uninstall(runtime);
        ambient.clear();
        runtime.close();
        renderer.close();
        if (sprites != null) {
            sprites.close();
        }
        depth.cleanup();
    }
}
