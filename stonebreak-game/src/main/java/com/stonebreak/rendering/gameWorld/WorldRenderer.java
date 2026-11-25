package com.stonebreak.rendering.gameWorld;

// Standard Library Imports
import java.util.*;

// JOML Math Library
import com.stonebreak.world.operations.WorldConfiguration;
import org.joml.Matrix4f;
import org.joml.Vector3f;

// LWJGL OpenGL Classes
import org.lwjgl.opengl.GL13;

// LWJGL OpenGL Static Imports
import static org.lwjgl.opengl.GL11.*;

// Project Imports
import com.stonebreak.core.Game;
import com.stonebreak.core.GameState;
import com.stonebreak.player.Player;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.models.blocks.AnimatedBlockRenderer;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.models.entities.DropRenderer;
import com.stonebreak.rendering.player.PlayerArmRenderer;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.openmason.engine.rendering.sky.SkyParams;
import com.openmason.engine.rendering.sky.SkyRenderer;
import com.stonebreak.rendering.lighting.SkyLight;
import com.stonebreak.rendering.gameWorld.clouds.CloudRenderer;
import com.stonebreak.rendering.gameWorld.clouds.CloudsInFront;
import com.stonebreak.rendering.gameWorld.weather.LightningRenderer;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationFog;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationBench;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationFx;
import com.stonebreak.rendering.gameWorld.weather.PrecipitationRenderer;
import com.stonebreak.rendering.gameWorld.weather.WeatherEffects;
import com.stonebreak.rendering.gameWorld.weather.WeatherFog;
import com.stonebreak.world.weather.ClientWeather;
import com.stonebreak.rendering.gameWorld.fastlod.FastLodRenderPass;
import com.stonebreak.world.World;

/**
 * Specialized renderer for 3D world elements including chunks, entities, and world-specific effects.
 * This renderer handles all world rendering without UI elements.
 */
public class WorldRenderer {


    // Dependencies
    private final ShaderProgram shaderProgram;
    private final BlockTextureArray blockTextureArray;
    private final Matrix4f projectionMatrix;
    private final BlockRenderer blockRenderer;
    private final PlayerArmRenderer playerArmRenderer;
    private final EntityRenderer entityRenderer;
    private final DropRenderer dropRenderer;
    private final AnimatedBlockRenderer animatedBlockRenderer;
    private final SkyRenderer skyRenderer;
    private final CloudRenderer cloudRenderer;
    private final com.stonebreak.rendering.gameWorld.clouds.CloudShadowCache cloudShadowCache =
            new com.stonebreak.rendering.gameWorld.clouds.CloudShadowCache();
    private final Vector3f cloudCacheEye = new Vector3f();
    private final PrecipitationFog precipitationFog;
    private final com.stonebreak.rendering.gameWorld.fog.GroundFog groundFog;
    /** The point the sky turns about ({@link com.stonebreak.world.TimeOfDay#celestialPole}). */
    private static final Vector3f CELESTIAL_POLE = com.stonebreak.world.TimeOfDay.celestialPole();
    /** How much a full lightning flash adds to the scene's ambient light. */
    private static final float LIGHTNING_AMBIENT = 0.6f;
    private final WeatherEffects weatherEffects =
            new WeatherEffects();
    private final PrecipitationRenderer precipitationRenderer;
    private final LightningRenderer lightningRenderer;
    private final com.stonebreak.rendering.gameWorld.water.WaterRenderer waterRenderer;
    private final com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer shadowMapRenderer;
    private final com.stonebreak.rendering.gameWorld.shadow.TorchShadowRenderer torchShadowRenderer;
    private final com.stonebreak.rendering.lighting.TorchIndirectLighting torchIndirectLighting =
            new com.stonebreak.rendering.lighting.TorchIndirectLighting();
    private final FastLodRenderPass lodRenderPass;
    private static final boolean OCTREE_ONLY = Boolean.getBoolean("stonebreak.octreeLod.noFallback");
    /**
     * Dev cost probe of the world shader's per-pixel terms ({@code -Dstonebreak.debugShade=<mask>}): 1 = no cloud
     * shadow, 2 = no sun cascades, 4 = no torch lights, 8 = texture only. Wrong pictures on purpose; 0 = normal.
     */
    private static final int DEBUG_SHADE = Integer.getInteger("stonebreak.debugShade", 0);
    private final com.stonebreak.rendering.gameWorld.fastlod.OctreeLodRenderer octreeLodRenderer =
            new com.stonebreak.rendering.gameWorld.fastlod.OctreeLodRenderer();
    // Shared region arenas for FastLOD nodes (multidraw batching); created
    // lazily on the GL thread once region rendering is known to be enabled.
    private com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher lodRegionBatcher;
    private final ChunkFrustumCuller frustumCuller = new ChunkFrustumCuller();
    private final com.stonebreak.rendering.models.entities.FishingLineRenderer fishingLineRenderer;
    private final com.stonebreak.rendering.gameWorld.effects.WorldEffectsRenderer effectsRenderer;
    /** Open Mason FX (Stonebreak#436): simulation, ambient FX and the particle draw around the water pass. */
    private final com.stonebreak.rendering.gameWorld.particles.ParticlePass particlePass;
    /** Near rain and snow as FX particles (the lanes keep the far ring); {@code -Dstonebreak.rain=lanes} = A/B. */
    private final PrecipitationFx precipitationFx;

    /** The camera pass's cube set: visibility walk + back-to-front lists (Phase 5). */
    private final CubeVisibility cubeVisibility = new CubeVisibility();
    /** Fog-coloured boxes for visible cubes that are not loaded yet. */
    private final CubeProxyRenderer proxyRenderer = new CubeProxyRenderer();
    /** Render-space camera clip matrix of this frame. */
    private final Matrix4f cameraViewProj = new Matrix4f();
    private final List<com.stonebreak.rendering.gameWorld.water.WaterRenderer.LodWaterNode> reusableLodWater = new ArrayList<>();
    
    /**
     * Creates a WorldRenderer with the required dependencies.
     */
    public WorldRenderer(ShaderProgram shaderProgram, BlockTextureArray blockTextureArray,
                        Matrix4f projectionMatrix,
                        BlockRenderer blockRenderer, PlayerArmRenderer playerArmRenderer, EntityRenderer entityRenderer,
                        DropRenderer dropRenderer, AnimatedBlockRenderer animatedBlockRenderer) {
        this.shaderProgram = shaderProgram;
        this.blockTextureArray = blockTextureArray;
        this.projectionMatrix = projectionMatrix;
        this.blockRenderer = blockRenderer;
        this.playerArmRenderer = playerArmRenderer;
        this.entityRenderer = entityRenderer;
        this.dropRenderer = dropRenderer;
        this.animatedBlockRenderer = animatedBlockRenderer;
        this.skyRenderer = new SkyRenderer();
        this.cloudRenderer = new CloudRenderer();
        this.precipitationFog = new PrecipitationFog();
        this.groundFog = new com.stonebreak.rendering.gameWorld.fog.GroundFog();
        this.precipitationRenderer = new PrecipitationRenderer();
        this.lightningRenderer = new LightningRenderer();
        this.shadowMapRenderer =
                new com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer(projectionMatrix, blockTextureArray);
        if (entityRenderer != null) {
            entityRenderer.setShadowMapRenderer(shadowMapRenderer);
            entityRenderer.setGroundFog(groundFog);
        }
        if (animatedBlockRenderer != null) {
            animatedBlockRenderer.setShadowMapRenderer(shadowMapRenderer);
        }
        this.torchShadowRenderer = new com.stonebreak.rendering.gameWorld.shadow.TorchShadowRenderer(blockTextureArray);
        this.lodRenderPass = new FastLodRenderPass();
        this.fishingLineRenderer = new com.stonebreak.rendering.models.entities.FishingLineRenderer(shaderProgram, projectionMatrix);
        this.waterRenderer = new com.stonebreak.rendering.gameWorld.water.WaterRenderer();
        this.waterRenderer.setOctreeLod(octreeLodRenderer);
        this.effectsRenderer = new com.stonebreak.rendering.gameWorld.effects.WorldEffectsRenderer(
                shaderProgram, projectionMatrix, entityRenderer);
        this.particlePass = new com.stonebreak.rendering.gameWorld.particles.ParticlePass(precipitationFog, cloudRenderer);
        this.precipitationFx = new PrecipitationFx(particlePass);
    }

    /** The FX pass (F3 statistics). */
    public com.stonebreak.rendering.gameWorld.particles.ParticlePass getParticlePass() {
        return particlePass;
    }
    
    /**
     * Renders the 3D world including chunks, entities, and world effects.
     */
    private final com.stonebreak.battletest.BattleTestRenderer battleRenderer =
            new com.stonebreak.battletest.BattleTestRenderer();
    private boolean battleWasRendered;

    /** Reused per frame by {@link #collectRemotePlayers}. */
    private final java.util.List<com.stonebreak.mobs.entities.RemotePlayer> frameRemotePlayers =
            new java.util.ArrayList<>();

    /** This frame's remote-player figures (their held torches light the world). */
    private java.util.List<com.stonebreak.mobs.entities.RemotePlayer> collectRemotePlayers() {
        frameRemotePlayers.clear();
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager != null) {
            for (com.stonebreak.mobs.entities.Entity entity
                    : entityManager.getEntitiesByType(com.stonebreak.mobs.entities.EntityType.REMOTE_PLAYER)) {
                if (entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
                    frameRemotePlayers.add(rp);
                }
            }
        }
        return frameRemotePlayers;
    }

    public void renderWorld(World world, Player player, float totalTime) {
        var battle = com.stonebreak.battletest.BattleTestSession.current();
        if (battle != null) {
            shadowMapRenderer.suspend();
            torchShadowRenderer.suspend();
            torchIndirectLighting.suspend();
            com.stonebreak.rendering.lighting.DynamicLights.update(
                    world, player, player.getCamera().getPosition(), totalTime);
            // The arena scene mesh is baked in ABSOLUTE world coordinates (arena.json and the
            // authored OMO), so it must not be drawn through the RenderOrigin-rebased view matrix
            // the rest of the frame uses: as soon as the camera leaves the origin's 64-block cell —
            // which the battle camera does the moment a shot sits at negative x or z — the whole
            // arena is drawn a grid step away from the actors, which the SBE path rebases correctly.
            // The arena lives within a few tens of blocks of world zero, so the absolute matrix
            // costs no precision here.
            battleRenderer.render(battle.mesh(), projectionMatrix,
                    player.getCamera().getAbsoluteViewMatrix(),
                    player.getCamera().getPosition(), totalTime);
            battleWasRendered = true;
            renderEntities(player);
            com.stonebreak.battle.stage.BattleActors.renderIfActive(entityRenderer, projectionMatrix, player.getViewMatrix());
            effectsRenderer.renderAll(player);
            renderPlayerArm(player);
            return;
        }
        if (battleWasRendered) {
            battleRenderer.close();
            battleWasRendered = false;
        }
        // Debug: Log first call
        if (debugRenderWorldCount < 1) {
            System.out.println("[WorldRenderer.renderWorld] Called! world=" + (world != null) + " player=" + (player != null));
            debugRenderWorldCount++;
        }

        // Clear any pending OpenGL errors from previous operations
        clearPendingGLErrors();
        checkGLError("After clearing pending errors");

        // Gather this frame's dynamic point lights (placed + held torches, other players'
        // included) once; the world, water and entity shaders all read the same set.
        com.stonebreak.rendering.lighting.DynamicLights.update(
                world, player, player.getCamera().getPosition(), totalTime, collectRemotePlayers());
        PrecipitationBench bench = PrecipitationBench.get();
        if (bench != null) bench.beginFrame();
        // FX step once per frame; ambient FX (torch flames, furnace smoke) follow that light selection.
        if (bench != null) bench.begin(PrecipitationBench.Section.FX_UPDATE);
        particlePass.update(world, player, totalTime);
        if (bench != null) bench.end(PrecipitationBench.Section.FX_UPDATE);

        // Get time of day system for lighting. The engine sky/cloud renderers are decoupled
        // from TimeOfDay, so resolve the values they need here (static fallback when absent).
        com.stonebreak.world.TimeOfDay timeOfDay = Game.getTimeOfDay();
        // Weather first: it dims the ambient light, greys the sky read below and
        // veils it with rain/snow fog, and lightning flashes brighten it. None of
        // it reaches past the terrain's horizon.
        float weatherReach = horizonDistance(com.stonebreak.config.Settings.getInstance());
        float lightningFlash = lightningRenderer.update(Game.getWeather(), world, player.getCamera().getPosition(),
                weatherReach);
        weatherEffects.update(Game.getWeather(), world, player.getCamera().getPosition(), totalTime,
                LIGHTNING_AMBIENT * lightningFlash);
        Vector3f sunDirection;
        Vector3f skyColor;
        float ambientLightLevel;
        // The light that lights faces and casts shadows: the sun, or the moon at night.
        Vector3f keyDirection;
        SkyParams skyParams;
        SkyLight sky = timeOfDay != null ? SkyLight.of(timeOfDay) : null;
        // The distance fog away from any sunset gold, and the gold at the horizon.
        Vector3f clearFogColor;
        Vector3f horizonGold = new Vector3f();
        float sunsetCoverage = -1f;
        // The Earth's shadow on the land opposite the sun, and the sun's aureole toward it.
        Vector3f earthShadow = new Vector3f();
        float earthShadowHeight = 0f, twilightBand = 0f;
        Vector3f sunAureole = new Vector3f();
        float seaHorizon = SunsetFog.seaHorizon((float) player.getCamera().getPosition().y, weatherReach);
        if (sky != null) {
            sunDirection = sky.sunDir();
            keyDirection = sky.keyDir();
            skyColor = weatherEffects.tintSky(sky.skyColor(), new Vector3f());
            clearFogColor = weatherEffects.tintSky(
                    new Vector3f(sky.clearZenith()).lerp(sky.clearHorizon(), 0.5f), new Vector3f());
            weatherEffects.tintSky(sky.sunsetColor(), horizonGold);
            sunsetCoverage = sky.sunsetCoverage();
            // Overcast hides the sun and the twilight bands it casts on the sky.
            float clear = 1f - weatherEffects.storminess();
            weatherEffects.tintSky(sky.earthShadowColor(), earthShadow);
            earthShadowHeight = sky.earthShadowHeight();
            twilightBand = sky.twilightBand() * clear;
            SunsetFog.sunAureole(sky, clear, seaHorizon, sunAureole);
            weatherEffects.updateFogColor(sky);
            ambientLightLevel = com.stonebreak.rendering.lighting.SceneLight.ambient();
            skyParams = new SkyParams(sunDirection, sky.moonDir(), sky.moonLight(),
                    weatherEffects.tintSky(sky.clearZenith(), new Vector3f()),
                    weatherEffects.tintSky(sky.clearHorizon(), new Vector3f()),
                    weatherEffects.tintSky(sky.sunsetColor(), new Vector3f()), sky.sunsetCoverage(),
                    new Vector3f(sky.sunGlow()).mul(clear), sky.sunColor(),
                    earthShadow, earthShadowHeight, twilightBand, sky.daylight(), seaHorizon, CELESTIAL_POLE);
        } else {
            sunDirection = new Vector3f(0.7f, 0.1f, 0.5f).normalize();
            keyDirection = sunDirection;
            skyColor = new Vector3f(0.53f, 0.81f, 0.92f); // Day sky
            clearFogColor = skyColor;
            ambientLightLevel = 1.0f;
            skyParams = new SkyParams(sunDirection, new Vector3f(sunDirection).negate(), 0f,
                    skyColor, new Vector3f(skyColor).mul(1.15f), new Vector3f(), -1f, new Vector3f(),
                    new Vector3f(1f), new Vector3f(), 0f, 0f, 1f, seaHorizon, CELESTIAL_POLE);
        }

        // Cube terrain: this world's stream events feed the region renderer (idempotent attach); publish
        // last frame's stats and prune emptied regions before this frame's first draws. The FastLOD handover
        // hides detail cubes of non-READY columns whenever the LOD is there to cover them.
        com.stonebreak.rendering.gameWorld.regions.CubeTerrain.attach(world);
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            var regions = com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance();
            regions.beginFrame();
            regions.setHandover(lodHandoverActive(world));
        }

        // Cascaded sun-shadow depth pre-pass. Runs before anything samples the
        // shadow map this frame; restores the caller's framebuffer and viewport,
        // so it is safe even when the post-fx scene FBO is already bound.
        if (bench != null) bench.begin(PrecipitationBench.Section.SHADOWS);
        if (entityRenderer != null) entityRenderer.prepareShadowCasters(world);
        torchIndirectLighting.update(world);
        shadowMapRenderer.renderShadowPass(world, player, keyDirection, entityRenderer);
        torchShadowRenderer.render(world, player, entityRenderer, animatedBlockRenderer, totalTime);
        if (bench != null) bench.end(PrecipitationBench.Section.SHADOWS);
        checkGLError("After shadow depth pre-pass");

        // Underwater changes what the BACKGROUND is, so it has to be decided
        // before the sky pass rather than with the fog parameters further down.
        org.joml.Vector3d cameraPos = player.getCamera().getPosition();

        // The local weather and terrain relief follow the camera; rain and
        // lightning read them even with clouds switched off.
        if (bench != null) bench.begin(PrecipitationBench.Section.CLOUD_PREP);
        ClientWeather weather = Game.getWeather();
        if (weather != null) {
            weather.focus(cameraPos.x, cameraPos.z, world.terrain());
        }
        // Clouds resolve before any receiver of cloud shadows is set up.
        cloudRenderer.prepareFrame(weather, cameraPos, keyDirection,
                com.stonebreak.config.Settings.getInstance().getCloudsEnabled());
        // The shadow column cache, from the uniforms just prepared, before any receiver is set up.
        cloudShadowCache.build(cloudRenderer.uniforms(), player.getRenderPosition(cloudCacheEye),
                world.getConfig().getHorizontalRenderRadiusBlocks());
        if (bench != null) bench.end(PrecipitationBench.Section.CLOUD_PREP);
        boolean cameraUnderwater = world.isPositionUnderwater(
                (int) Math.floor(cameraPos.x), (int) Math.floor(cameraPos.y), (int) Math.floor(cameraPos.z));
        // Rain and snow fog for every pass this frame; underwater the murk replaces it.
        WeatherFog.set(
                cameraUnderwater ? 0f : weatherEffects.extinction(), weatherEffects.localFogColor(new Vector3f()));
        precipitationFog.prepare(!cameraUnderwater, weatherEffects);
        // The Shade's night ground fog; underwater the murk replaces it too.
        groundFog.prepare(!cameraUnderwater, sky, world.terrain(), cameraPos,
                projectionMatrix, player.getViewMatrix());
        // Near rain and snow drops for the FX pass (drawn with the other particles, before the water).
        if (bench != null) bench.begin(PrecipitationBench.Section.SPAWN);
        precipitationFx.update(world, weather, cameraPos, totalTime, cameraUnderwater);
        if (bench != null) bench.end(PrecipitationBench.Section.SPAWN);

        if (cameraUnderwater) {
            // Fog alone cannot hide the horizon, because it only tints pixels
            // something was drawn to. The sky gradient and the sun disc are
            // drawn at full brightness and never fogged, and wherever no chunk
            // has loaded there is no fragment at all — so in a deep ocean the
            // far water ends in a bright band of sky exactly where visibility
            // is supposed to have run out. Skip the sky and fill the frame with
            // the colour the fog saturates to, so "further than 20 blocks" and
            // "nothing rendered there" are the same colour.
            float[] previousClear = new float[4];
            glGetFloatv(GL_COLOR_CLEAR_VALUE, previousClear);
            glClearColor(UnderwaterFog.COLOR.x, UnderwaterFog.COLOR.y, UnderwaterFog.COLOR.z, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            // Put back whatever the clear colour was — FrameRenderer clears
            // with it before this method is reached, and the next frame may be
            // above water.
            glClearColor(previousClear[0], previousClear[1], previousClear[2], previousClear[3]);
        } else {
            // Render sky first (before world geometry for proper depth testing)
            // The engine sky takes the WORLD camera position and rebases it itself (RenderOrigin.toRender):
            // handing it the render position subtracted the origin twice, so once the origin was ~1000 blocks
            // from zero the sky cube (camera + 1000) no longer surrounded the camera and the sky beyond it
            // stayed the black clear colour - a black sector toward +X/+Z (or -X/-Z) far from spawn.
            // Float world X/Z keep the cube around the camera out to ~2^33 blocks (float ulp < its 1000-block
            // half size); beyond that the engine needs a render-space overload (doc: engine note).
            if (bench != null) bench.begin(PrecipitationBench.Section.SKY);
            skyRenderer.renderSky(projectionMatrix, player.getViewMatrix(),
                    new Vector3f((float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z), skyParams);
            precipitationFog.renderSky(projectionMatrix, player.getViewMatrix(), player.getRenderPosition(new Vector3f()));
            if (bench != null) bench.end(PrecipitationBench.Section.SKY);
        }
        checkGLError("After sky rendering");

        // Ensure proper depth function for world geometry
        glDepthFunc(GL_LESS);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        
        // Use shader program
        shaderProgram.bind();
        checkGLError("After shader bind");

        // Atmospheric distance fog: fades world geometry into the sky color
        // from the native ring edge out to the LOD outer ring, so the distant
        // terrain dissolves at the horizon instead of ending in a hard edge.
        // Underwater swaps this for a much shorter, murky-water-colored fog
        // instead of disabling it — visibility is short underwater, and the
        // full-screen blue tint overlay alone doesn't shrink view distance.
        com.stonebreak.config.Settings settings = com.stonebreak.config.Settings.getInstance();
        float fogStart = 0f, fogEnd = 0f;
        Vector3f fogColor = clearFogColor;
        // The evening gold fogs land on the sun's side; underwater the murk is all there is.
        SunsetFog.set(cameraUnderwater ? new Vector3f() : horizonGold, sunsetCoverage, sunDirection);
        SunsetFog.setTwilight(earthShadow, earthShadowHeight, cameraUnderwater ? 0f : twilightBand,
                cameraUnderwater ? new Vector3f() : sunAureole);
        // Underwater the fog is a VOLUME around the eye, so it is measured as a
        // sphere; the atmospheric fog keeps its horizontal measure, which is
        // what makes a distant peak fade with its base instead of pulling clear
        // of the haze as the camera climbs.
        boolean fogSpherical = cameraUnderwater;
        if (cameraUnderwater) {
            fogStart = UnderwaterFog.start((float) cameraPos.y);
            fogEnd = UnderwaterFog.end((float) cameraPos.y);
            fogColor = UnderwaterFog.COLOR;
        } else if (settings.getLodEnabled() && settings.getLodDistance() > 0) {
            fogStart = settings.getRenderDistance() * (float) WorldConfiguration.CHUNK_SIZE;
            fogEnd = horizonDistance(settings);
        }

        // Set common uniforms for world rendering
        setupWorldUniforms(player, fogColor, fogStart, fogEnd, fogSpherical);
        // Water animation setting — consumed by the dedicated water renderer
        // (waves + flow scroll) and the LOD sea-sheet drift (u_time).
        boolean waterAnimationEnabled = settings.getWaterShaderEnabled();
        checkGLError("After setting uniforms");
        
        // Bind texture atlas once before passes
        bindTextureAtlas();
        checkGLError("After texture binding");
        
        // The camera's cube set: visibility-graph walk inside the frustum (+ the missing cubes it reached).
        cullCubes(world, player);

        // Render opaque pass (atlas + stamps of the visible cubes), then the proxies for what is missing.
        if (bench != null) bench.begin(PrecipitationBench.Section.OPAQUE);
        renderOpaquePass();
        if (bench != null) bench.end(PrecipitationBench.Section.OPAQUE);
        proxyRenderer.render(cubeVisibility,
                com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance(),
                projectionMatrix, player.getViewMatrix(), fogColor);
        shaderProgram.bind();
        publishTerrainStats();

        // Render distant-terrain LOD between detail opaque and SBO; shares shader/atlas state.
        // frustumCuller was updated for this frame's camera by cullChunksToFrustum above —
        // the LOD pass reuses those planes, so keep this call after it. Surviving nodes'
        // water sheets are collected for the dedicated water pass below.
        world.ensureFastLodManager(blockTextureArray);
        int lodPlayerCx = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int lodPlayerCz = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        reusableLodWater.clear();
        if (lodRegionBatcher == null
                && com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            lodRegionBatcher = new com.stonebreak.rendering.gameWorld.fastlod.FastLodRegionBatcher();
        }
        // Octree LOD (cv_lod): ticked here on the owner thread after the stream tick; FastLOD then draws only
        // the FALLBACK footprints it hands back, the octree nodes draw right after.
        org.joml.Vector3d lodEye = player.getCamera().getPosition();
        if (bench != null) bench.begin(PrecipitationBench.Section.LOD_TICK);
        octreeLodRenderer.update(world, world.getFastLodManager(), lodEye.x, lodEye.y, lodEye.z);
        if (bench != null) bench.end(PrecipitationBench.Section.LOD_TICK);
        // Dev: -Dstonebreak.octreeLod.noFallback=true hides FastLOD (octree nodes + detail only, smoke checks).
        if (bench != null) bench.begin(PrecipitationBench.Section.FASTLOD);
        int lodStamp = OCTREE_ONLY ? 0 : lodRenderPass.render(shaderProgram, world.getFastLodManager(),
                lodPlayerCx, lodPlayerCz,
                frustumCuller, this::lodColumnCovered, lodRegionBatcher, reusableLodWater,
                octreeLodRenderer.octree());
        if (bench != null) bench.end(PrecipitationBench.Section.FASTLOD);
        if (bench != null) bench.begin(PrecipitationBench.Section.OCTREE);
        octreeLodRenderer.drawTerrain(shaderProgram, cameraViewProj);
        if (bench != null) bench.end(PrecipitationBench.Section.OCTREE);

        // The Shade's ground fog over the sky and the opaque terrain, once per pixel from
        // the depth buffer; everything drawn from here on fogs itself.
        groundFog.renderScreen(projectionMatrix, player.getViewMatrix(), player.getRenderPosition(new Vector3f()));
        shaderProgram.bind();
        groundFog.applyTo(shaderProgram);

        // Render entities after opaque blocks but before transparent water
        // This allows water to blend over entities when viewing through water
        if (bench != null) bench.begin(PrecipitationBench.Section.ENTITIES);
        renderEntities(player);

        // Animated blocks (doors etc.) draw entity-style right after the mobs:
        // they depth-sort against opaque terrain and water still blends over them.
        if (animatedBlockRenderer != null) {
            animatedBlockRenderer.render(world, player.getViewMatrix(), projectionMatrix,
                    player.getCamera().getPosition(), totalTime);
        }

        // Render opaque drops BEFORE the transparent pass so they write depth
        // Water can then occlude them properly (water renders with glDepthMask(false))
        renderOpaqueDrops(player);
        if (bench != null) bench.end(PrecipitationBench.Section.ENTITIES);

        // Volumetric clouds — drawn after the opaque world but BEFORE anything
        // translucent: the raymarch stops at the scene depth, and glass/ice
        // write depth, so marching after them hid every cloud behind a window.
        // Here they march to opaque depth (clouds behind mountains stay hidden,
        // clouds below a high camera cover the terrain under them) and glass
        // blends over them. Water, drawn later, fades itself out under the
        // cloud in front of it (WaterRenderer). Not underwater, for the same
        // reason the sky is skipped there: clouds are never fogged, and they
        // would draw into exactly the gaps that have no water in front of
        // them — the unloaded horizon.
        if (!cameraUnderwater) {
            // Bolts write depth before the clouds march, so cloud in front of a
            // bolt hides it and cloud behind it doesn't paint over it.
            if (bench != null) bench.begin(PrecipitationBench.Section.CLOUDS);
            lightningRenderer.render(projectionMatrix, player.getViewMatrix(), cameraPos);
            cloudRenderer.render(projectionMatrix, player.getViewMatrix(), player.getRenderPosition(new Vector3f()),
                    sky, skyColor, clearFogColor,
                    timeOfDay != null ? com.stonebreak.rendering.lighting.SceneLight.steadyAmbient() : ambientLightLevel,
                    lightningRenderer.flashes(), precipitationFog, groundFog, weatherReach);
            if (bench != null) bench.end(PrecipitationBench.Section.CLOUDS);
            checkGLError("After cloud rendering");
        }

        // Translucent solids (ice, glass) write depth, so whatever draws after
        // them is hidden behind them. Above water they go AFTER the water pass:
        // water behind a window or under lake ice is drawn first and the glass
        // or ice blends over it (drawn before, the water was depth-rejected —
        // invisible through glass, and ice showed the bare lakebed). Underwater
        // the ice lies beyond the water surface, so the old order still holds:
        // ice first, then the water blends over it.
        boolean translucentAfterWater = !cameraUnderwater;
        if (!translucentAfterWater) {
            renderTranslucentSolids(cameraUnderwater);
        }
        // The drops and crack overlay below set world-shader uniforms without
        // binding it; the clouds may have left their own program bound.
        shaderProgram.bind();

        // Transparent drops and the crack overlay draw BEFORE water: the water
        // pass depth-prepasses its nearest surface into the depth buffer (to
        // self-occlude — no water visible through water), so anything drawn
        // after it is hidden behind that surface. Drawing these first keeps
        // underwater drops and cracks visible, correctly tinted by the water
        // that blends over them. ORDER MATTERS within this trio: the crack
        // overlay samples a 2D texture through the world shader and needs
        // restoreGLStateAfterPasses to flip u_useTextureArray off first
        // (drops manage their own texture state and ran pre-restore before).
        renderTransparentDrops(player);
        restoreGLStateAfterPasses();
        renderWorldOverlays(player);

        // Dedicated water pass — draws every chunk's water mesh with the water
        // shader — before ice and glass above water, after them underwater
        // (see translucentAfterWater). Reuses the back-to-front order sorted above.
        // State-neutral (saves/restores all GL state it touches), but leaves
        // the nearest water surface's depth in the depth buffer, so later
        // passes (fire bolts, particles) are occluded by water in front of
        // them — physically correct compositing.
        // FX beyond the water surface (under it, seen from above) draw before the water so it blends over
        // them; the camera's side draws after it (drawFront below). Soft particles read a copy of the depth
        // made here, which holds the opaque scene.
        if (bench != null) bench.begin(PrecipitationBench.Section.FX_BEHIND);
        particlePass.drawBehind(player, projectionMatrix, ambientLightLevel, cameraUnderwater,
                fogColor, fogStart, fogEnd);
        if (bench != null) bench.end(PrecipitationBench.Section.FX_BEHIND);
        checkGLError("After particles behind water");

        if (bench != null) bench.begin(PrecipitationBench.Section.WATER);
        waterRenderer.render(cubeVisibility.waterBackToFront(), projectionMatrix, player.getViewMatrix(),
                player.getCamera().getPosition(), totalTime, keyDirection,
                ambientLightLevel, waterAnimationEnabled, fogColor, fogStart, fogEnd, fogSpherical,
                reusableLodWater, lodRegionBatcher, lodStamp, cloudRenderer.uniforms(),
                cloudRenderer, precipitationFog, groundFog);
        if (bench != null) bench.end(PrecipitationBench.Section.WATER);
        checkGLError("After water pass");

        if (bench != null) bench.begin(PrecipitationBench.Section.FX_FRONT);
        particlePass.drawFront();
        if (bench != null) bench.end(PrecipitationBench.Section.FX_FRONT);
        checkGLError("After particles in front of water");

        if (!cameraUnderwater) {
            // Rain and snow fall below the clouds and in front of them. Before
            // glass and ice: those write depth, so rain drawn after them vanished
            // behind every window; drawn first, it shows through them, tinted by
            // them. (Rain in front of a pane takes the pane's faint tint too.)
            if (cloudRenderer.mapsReady()) {
                if (bench != null) bench.begin(PrecipitationBench.Section.LANES);
                precipitationRenderer.render(world, cloudRenderer, precipitationFog, projectionMatrix,
                        player.getViewMatrix(), cameraPos, totalTime, ambientLightLevel, weatherEffects.snow(),
                        !precipitationFx.active());
                if (bench != null) bench.end(PrecipitationBench.Section.LANES);
            }
            checkGLError("After precipitation rendering");
        }

        if (translucentAfterWater) {
            shaderProgram.bind();
            shaderProgram.setUniform("u_useTextureArray", true);
            renderTranslucentSolids(cameraUnderwater);
            restoreGLStateAfterPasses();
        }

        // Ability and player VFX go after the water pass: effects in front of water draw over it,
        // effects behind a water surface are depth-occluded by the water prepass (physically
        // correct), and opaque blocks in front still occlude them via depth testing.
        effectsRenderer.renderAll(player);

        // Render player arm last to appear in front of entities
        renderPlayerArm(player);
        if (bench != null) {
            var gpu = particlePass.renderer().gpu();
            bench.endFrame(precipitationFx.active() ? "particles" + (gpu != null ? "-gpu" : "-cpu") : "lanes",
                    precipitationFx.spawnedLastFrame(),
                    particlePass.runtime().liveParticles() + (gpu != null ? gpu.lastAlive() : 0));
        }
    }
    
    /**
     * How much of the sky's light reaches the eye at each pixel through this
     * frame's cloud and rain (alpha), for screen-space effects such as god rays;
     * 0 when nothing veils the sky. Call after {@link #renderWorld}.
     */
    public int skyTransmittanceTexture(Player player) {
        int clouds = cloudRenderer.transmittanceTexture();
        return precipitationFog.sunVisibility(clouds, projectionMatrix, player.getViewMatrix(),
                player.getRenderPosition(new Vector3f()));
    }

    /**
     * Clear any pending OpenGL errors from previous operations.
     */
    private void clearPendingGLErrors() {
        int pendingError;
        while ((pendingError = glGetError()) != GL_NO_ERROR) {
            String errorString = switch (pendingError) {
                case 0x0500 -> "GL_INVALID_ENUM";
                case 0x0501 -> "GL_INVALID_VALUE";
                case 0x0502 -> "GL_INVALID_OPERATION";
                case 0x0503 -> "GL_STACK_OVERFLOW";
                case 0x0504 -> "GL_STACK_UNDERFLOW";
                case 0x0505 -> "GL_OUT_OF_MEMORY";
                case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
                default -> "UNKNOWN_ERROR_" + Integer.toHexString(pendingError);
            };
            System.err.println("PENDING OPENGL ERROR: " + errorString + " (0x" + Integer.toHexString(pendingError) + ") from previous operation");
        }
    }
    
    /**
     * Blocks to the edge of the drawn terrain: the native ring plus the LOD
     * ring when LOD is on. The atmospheric fog is opaque there, and the
     * weather ends there too.
     */
    private static float horizonDistance(com.stonebreak.config.Settings settings) {
        int chunks = settings.getRenderDistance() + (settings.getLodEnabled() ? settings.getLodDistance() : 0);
        return chunks * (float) WorldConfiguration.CHUNK_SIZE;
    }

    /**
     * Set up common uniforms for world rendering.
     */
    private void setupWorldUniforms(Player player, Vector3f skyColor,
                                    float fogStart, float fogEnd, boolean fogSpherical) {
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("modelMatrix", new Matrix4f()); // Identity for world chunks
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("block_sampler", 1); // Keep the array sampler off unit 0
        shaderProgram.setUniform("u_useSolidColor", false); // World objects are textured
        shaderProgram.setUniform("u_isText", false);        // World objects are not text
        shaderProgram.setUniform("u_isUIElement", false);   // World objects are not UI elements
        shaderProgram.setUniform("u_transformUVsForItem", false); // Chunks use tile-local UVs directly
        // World chunks/SBO geometry sample the block texture array (unit 1).
        shaderProgram.setUniform("u_useTextureArray", true);

        // Set lighting uniforms from TimeOfDay system
        com.stonebreak.world.TimeOfDay timeOfDay = Game.getTimeOfDay();
        if (timeOfDay != null) {
            shaderProgram.setUniform("u_ambientLight", com.stonebreak.rendering.lighting.SceneLight.ambient());
            shaderProgram.setUniform("u_sunDirection", com.stonebreak.rendering.lighting.SceneLight.keyDirection());
            shaderProgram.setUniform("u_keyLight", com.stonebreak.rendering.lighting.SceneLight.keyLight());
            shaderProgram.setUniform("u_debugShade", DEBUG_SHADE);
        } else {
            // Fallback to default lighting if TimeOfDay not initialized
            shaderProgram.setUniform("u_ambientLight", 1.0f);
            shaderProgram.setUniform("u_sunDirection", new Vector3f(0.5f, 1.0f, 0.3f).normalize());
            shaderProgram.setUniform("u_keyLight", new Vector3f(1f));
        }

        // Set view position for specular lighting calculations. Render space —
        // it is differenced against fragPos, which the mesh origin buffers put
        // in render space too.
        shaderProgram.setUniform("u_viewPos", player.getRenderPosition(new Vector3f()));

        // Dynamic point lights (torches) for this frame.
        com.stonebreak.rendering.lighting.DynamicLights.applyTo(shaderProgram);

        // Cascaded sun-shadow sampling state (binds the map on its own unit;
        // disables sampling when the pass was skipped — night, setting off).
        shadowMapRenderer.applyToShader(shaderProgram);
        // Cloud shadows (sunlight dimmed by the cloud above, Beer-Lambert).
        cloudRenderer.uniforms().applyTo(shaderProgram);
        cloudShadowCache.applyTo(shaderProgram);
        CloudsInFront.bindSampler(shaderProgram);
        shaderProgram.setBool("u_cloudsInFront", false);

        // Reset underwater fog uniforms for world rendering (blocks/chunks don't use fog)
        shaderProgram.setUniform("u_cameraPos", new Vector3f(0, 0, 0));
        shaderProgram.setUniform("u_underwaterFogDensity", 0.0f);
        shaderProgram.setUniform("u_underwaterFogColor", new Vector3f(0, 0, 0));

        // Atmospheric distance fog toward the sky color (fogEnd <= fogStart disables).
        shaderProgram.setUniform("u_fogColor", skyColor);
        SunsetFog.applyTo(shaderProgram);
        shaderProgram.setUniform("u_fogStart", fogStart);
        shaderProgram.setUniform("u_fogEnd", fogEnd);
        shaderProgram.setUniform("u_fogSpherical", fogSpherical);
        // Rain and snow fog (precip_fog.glsl), marched in front of every fragment.
        precipitationFog.applyTo(shaderProgram);
        // The Shade's ground fog: the opaque passes leave it to its screen pass.
        com.stonebreak.rendering.gameWorld.fog.GroundFog.applyDisabled(shaderProgram);
    }
    
    /**
     * Bind the texture atlas for world rendering.
     */
    private void bindTextureAtlas() {
        // Block texture array on unit 1 — world/voxel geometry samples this.
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        blockTextureArray.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    /**
     * Whether detail cubes of non-READY columns stay hidden this frame: only while the FastLOD ring is on
     * to draw those columns instead ({@code -Dstonebreak.lodHandover=off} always draws every resident cube).
     */
    private static boolean lodHandoverActive(World world) {
        if ("off".equalsIgnoreCase(System.getProperty("stonebreak.lodHandover", "on"))) {
            return false;
        }
        com.stonebreak.config.Settings settings = com.stonebreak.config.Settings.getInstance();
        return world.isRenderOnly() && settings.getLodEnabled() && settings.getLodDistance() > 0
                && world.getFastLodManager() != null;
    }

    /** FastLOD node test: the native detail of this 16-block LOD column is drawn (its cube column READY). */
    private boolean lodColumnCovered(int chunkX16, int chunkZ16) {
        if (!com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            return false;
        }
        return com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance()
                .isColumnReady(chunkX16 >> 1, chunkZ16 >> 1);
    }

    /**
     * This frame's camera cube set: the frustum (for the LOD pass and debug) plus the visibility-graph walk
     * from the camera cube ({@link CubeVisibility}), which also lists the visible missing cubes.
     */
    private void cullCubes(World world, Player player) {
        frustumCuller.update(projectionMatrix, player.getViewMatrix());
        projectionMatrix.mul(player.getViewMatrix(), cameraViewProj);
        if (!com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            return;
        }
        org.joml.Vector3d cam = player.getCamera().getPosition();
        cubeVisibility.update(world, com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance(),
                cameraViewProj, cam.x, cam.y, cam.z, world.getConfig().getHorizontalRenderRadiusBlocks());
    }

    private void publishTerrainStats() {
        if (!com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            return;
        }
        var regions = com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance();
        TerrainRenderStats.publish(new TerrainRenderStats.Snapshot(regions.residentCubes(),
                cubeVisibility.visible().size(), cubeVisibility.walkFrame(), cubeVisibility.missingCount(),
                cubeVisibility.missingTotal(), proxyRenderer.lastDrawn(), cubeVisibility.lastWalkNanos(),
                regions.hiddenByHandover(), regions.handover(), cubeVisibility.walkActive()));
    }

    /** The camera pass's visibility state (F3 / tests). */
    public CubeVisibility cubeVisibility() {
        return cubeVisibility;
    }

    /** Proxies drawn last frame (F3). */
    public int proxiesDrawn() {
        return proxyRenderer.lastDrawn();
    }

    /**
     * Back-face culling for opaque cube quads in the opaque pass (see
     * {@code u_cullBackQuads} in world.vert). {@code -Dstonebreak.backfaceCull=false}
     * turns it off for A/B comparisons.
     */
    private static final boolean BACKFACE_CULL =
        !"false".equalsIgnoreCase(System.getProperty("stonebreak.backfaceCull"));

    /**
     * Opaque pass: atlas (opaque + cutout QUAD16) and stamp (COMPACT20) geometry of every enabled cube - the
     * enable bits carry the column handover and this frame's visibility walk; GPU cull on GL 4.3, CPU
     * multidraw otherwise. Opaque cube faces that point away from the camera are dropped. Scoped to this
     * pass only: the same shader draws items, entities and LOD, which must not be culled.
     */
    private void renderOpaquePass() {
        shaderProgram.setUniform("u_cullBackQuads", BACKFACE_CULL);
        try {
            drawOpaqueAtlas();
        } finally {
            shaderProgram.setUniform("u_cullBackQuads", false);
        }
    }

    private void drawOpaqueAtlas() {
        shaderProgram.setUniform("u_renderPass", 0); // 0 for opaque/non-water pass
        glDepthMask(true);  // Enable depth writing for opaque objects
        glDisable(GL_BLEND); // Opaque objects typically don't need blending
        if (!com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            return;
        }
        var regionRenderer = com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance();
        regionRenderer.prepareCameraPass(cubeVisibility.walkFrame());
        // Hi-Z two-phase on the GPU path: the pyramid is built from this terrain depth inside the call, before
        // proxies, LOD or the first-person arm (depth-range tricks) write depth. -Dopenmason.hiz=off disables.
        regionRenderer.drawCameraSolid(cameraViewProj);
    }

    private static int debugOpaquePassCount = 0;


    /**
     * Translucent solids (ice, glass), composited behind the cloud in front of
     * them: blended over the clouds they would paint over that cloud.
     */
    private void renderTranslucentSolids(boolean cameraUnderwater) {
        shaderProgram.bind();
        CloudsInFront.Bindings previousCloudTexture = CloudsInFront.enable(
                shaderProgram, cameraUnderwater ? null : cloudRenderer);
        renderTransparentPass();
        shaderProgram.bind();
        CloudsInFront.disable(shaderProgram, previousCloudTexture);
    }

    /**
     * Render transparent pass (translucent solids) over the chunks
     * {@link #sortChunksBackToFront} ordered this frame.
     */
    private void renderTransparentPass() {
        shaderProgram.bind(); // re-bind in case entity rendering left a different shader active
        shaderProgram.setUniform("u_renderPass", 1); // 1 for transparent pass
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        // Translucent solids (ice, glass) only, depth-write ON so they occlude any farther translucent
        // surface. Only cubes whose mesh holds translucent quads, farthest first (region granularity).
        glDepthMask(true);
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance()
                .drawCubes(cubeVisibility.translucentBackToFront(),
                    com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.LAYER_TRANSLUCENT);
        }
    }

    /**
     * Restore OpenGL state after rendering passes.
     */
    private void restoreGLStateAfterPasses() {
        glDepthMask(true);  // Restore depth writing
        glDisable(GL_BLEND); // Restore blending state
        // Downstream passes (overlays, drops, particles) sample 2D textures.
        shaderProgram.setUniform("u_useTextureArray", false);
    }
    
    /**
     * Render world-specific overlays like block crack effects.
     */
    private void renderWorldOverlays(Player player) {
        // Render block crack overlay if breaking a block
        blockRenderer.renderBlockCrackOverlay(player, shaderProgram, projectionMatrix);
    }
    
    /**
     * Render player arm (first-person) or full body (third-person) in appropriate game states.
     */
    private void renderPlayerArm(Player player) {
        GameState currentState = Game.getInstance().getState();
        // PAUSED included: the pause menu draws over a live view of the world (the HUD stays
        // too, see FrameRenderer.HUD_STATES), so the arm/body must not vanish behind it.
        boolean activeState = currentState == GameState.PLAYING
                || currentState == GameState.PAUSED
                || currentState == GameState.INVENTORY_UI
                || currentState == GameState.CHARACTER_SHEET_UI
                || currentState == GameState.RECIPE_BOOK_UI
                || currentState == GameState.WORKBENCH_UI
                || currentState == GameState.FURNACE_UI
                || currentState == GameState.CHEST_UI;
        if (currentState == GameState.MAIN_MENU && com.stonebreak.network.MultiplayerSession.isPreview()) {
            renderPreviewBody(player);
            return;
        }
        if (!activeState) return;

        if (player.isThirdPerson()) {
            // Render the full body model instead of the first-person arm.
            if (entityRenderer != null) {
                World world = Game.getWorld();
                org.joml.Vector3d cameraPos = player.getCamera().getPosition();
                entityRenderer.renderLocalPlayer(player, player.getViewMatrix(), projectionMatrix, world, cameraPos);
            }
        } else if (!armHiddenWithHud(currentState)) {
            playerArmRenderer.renderPlayerArm(player);
        }
    }

    /** Closer than this to the eye, the main menu preview's camera would see inside the head. */
    private static final float PREVIEW_BODY_MIN_EYE_DISTANCE = 0.6f;

    /**
     * The main menu's preview of the last-played world: its camera orbits the player, so the whole
     * body is drawn, until Resume brings the camera into the eye.
     */
    private void renderPreviewBody(Player player) {
        if (entityRenderer == null) {
            return;
        }
        org.joml.Vector3d cameraPos = player.getCamera().getPosition();
        org.joml.Vector3d feet = player.getPosition();
        float dx = (float) (cameraPos.x - feet.x);
        float dy = (float) (cameraPos.y - (feet.y + com.stonebreak.player.PlayerConstants.CAMERA_EYE_OFFSET));
        float dz = (float) (cameraPos.z - feet.z);
        if (dx * dx + dy * dy + dz * dz < PREVIEW_BODY_MIN_EYE_DISTANCE * PREVIEW_BODY_MIN_EYE_DISTANCE) {
            return;
        }
        entityRenderer.renderLocalPlayer(player, player.getViewMatrix(), projectionMatrix, Game.getWorld(), cameraPos);
    }

    /**
     * F1 hid the HUD and the "Hide Arm With HUD" setting is on. Mirrors FrameRenderer's rule: the
     * HUD only hides while PLAYING, so the arm stays behind the pause menu and in-game screens.
     */
    private static boolean armHiddenWithHud(GameState state) {
        return state == GameState.PLAYING && Game.isHudHidden()
                && com.stonebreak.config.Settings.getInstance().getHideArmWithHud();
    }

    /**
     * Collect drop entities and remote players from the entity manager.
     */
    private void collectDrops(java.util.List<com.stonebreak.mobs.entities.Entity> drops,
                              java.util.List<com.stonebreak.mobs.entities.RemotePlayer> remotePlayers) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null) return;

        World world = Game.getWorld();
        for (com.stonebreak.mobs.entities.Entity entity : entityManager.getAllEntities()) {
            if (!entity.isAlive()) continue;
            // Same not-yet-streamed-chunk cull as the entity pass — a drop or a remote
            // player's held item must not float in the void either.
            if (!com.stonebreak.rendering.models.entities.EntityRenderer.isInRenderableChunk(entity, world)) continue;
            if (isDropEntity(entity)) {
                drops.add(entity);
            } else if (entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
                remotePlayers.add(rp);
            }
        }
    }

    /**
     * Render opaque block/item drops before the transparent water pass.
     * Writing depth here allows water to occlude drops behind it.
     */
    private void renderOpaqueDrops(Player player) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null || dropRenderer == null) return;

        java.util.List<com.stonebreak.mobs.entities.Entity> drops = new java.util.ArrayList<>();
        java.util.List<com.stonebreak.mobs.entities.RemotePlayer> remotePlayers = new java.util.ArrayList<>();
        collectDrops(drops, remotePlayers);

        World world = Game.getWorld();
        org.joml.Vector3d cameraPos = player.getCamera().getPosition();

        if (!drops.isEmpty()) {
            dropRenderer.renderOpaqueDrops(drops, shaderProgram, projectionMatrix, player.getViewMatrix(), world, cameraPos);
        }
        if (!remotePlayers.isEmpty()) {
            dropRenderer.renderHeldItems(remotePlayers, shaderProgram, projectionMatrix, player.getViewMatrix(), world, cameraPos);
        }
        if (entityRenderer != null) {
            entityRenderer.queueMeatRackItems(world, cameraPos);
            entityRenderer.queueCampfireFood(world, cameraPos);
            dropRenderer.renderSocketItems(entityRenderer.socketItems(), shaderProgram, projectionMatrix,
                    player.getViewMatrix(), cameraPos);
        }
    }

    /**
     * Render transparent block drops after the transparent water pass.
     */
    private void renderTransparentDrops(Player player) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        if (entityManager == null || dropRenderer == null) return;

        java.util.List<com.stonebreak.mobs.entities.Entity> drops = new java.util.ArrayList<>();
        collectDrops(drops, new java.util.ArrayList<>());

        World world = Game.getWorld();
        org.joml.Vector3d cameraPos = player.getCamera().getPosition();

        if (!drops.isEmpty()) {
            dropRenderer.renderTransparentDrops(drops, shaderProgram, projectionMatrix, player.getViewMatrix(), world, cameraPos);
        }
    }

    /**
     * Helper method to check if an entity is a drop entity.
     * This would need to be updated when actual drop entity classes are implemented.
     */
    private boolean isDropEntity(com.stonebreak.mobs.entities.Entity entity) {
        return entity instanceof com.stonebreak.mobs.entities.BlockDrop ||
               entity instanceof com.stonebreak.mobs.entities.ItemDrop;
    }

    /**
     * Render all entities using the entity sub-renderer.
     */
    private void renderEntities(Player player) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        // Socket items are drained by the drop pass; a frame that skips it must not let them pile up.
        if (entityRenderer != null) entityRenderer.socketItems().clear();

        if (entityManager != null && entityRenderer != null) {
            World world = Game.getWorld();
            // Get camera position (at eye level, not feet)
            org.joml.Vector3d cameraPos = player.getCamera().getPosition();

            // Get all entities and render them using the sub-renderer with underwater fog support
            for (com.stonebreak.mobs.entities.Entity entity : entityManager.getAllEntities()) {
                // Exclude drops (rendered separately) and fire bolts (rendered
                // after the transparent water pass so they draw over water).
                if (entity.isAlive() && !isDropEntity(entity)
                        && entity.getType() != com.stonebreak.mobs.entities.EntityType.FIRE_BOLT) {
                    entityRenderer.renderEntity(entity, player.getViewMatrix(), projectionMatrix, world, cameraPos);
                }
            }
        }

        // Draw fishing line from the held rod's tip to the active bobber. The
        // rod tip is computed from the rod's actual render transform so the line
        // stays physically attached to the rod.
        com.stonebreak.mobs.entities.FishingBobber bobber = player.getActiveBobber();
        if (bobber != null && bobber.isAlive()) {
            org.joml.Vector3f rodTip = playerArmRenderer.getHeldRodTipWorld(player);
            if (rodTip != null) {
                org.joml.Vector3f bobberTop = new org.joml.Vector3f(bobber.getPosition())
                        .add(0, 0.1f + bobber.getBobOffset(), 0);
                fishingLineRenderer.render(rodTip, bobberTop, player.getViewMatrix());
            }
        }
    }
    
    private static int debugRenderWorldCount = 0;

    /**
     * Checks for OpenGL errors and logs them with context information.
     */
    private void checkGLError(String context) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            String errorString = switch (error) {
                case 0x0500 -> "GL_INVALID_ENUM";
                case 0x0501 -> "GL_INVALID_VALUE";
                case 0x0502 -> "GL_INVALID_OPERATION";
                case 0x0503 -> "GL_STACK_OVERFLOW";
                case 0x0504 -> "GL_STACK_UNDERFLOW";
                case 0x0505 -> "GL_OUT_OF_MEMORY";
                case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION";
                default -> "UNKNOWN_ERROR_" + Integer.toHexString(error);
            };
            
            System.err.println("WORLD RENDERER OPENGL ERROR: " + errorString + " (0x" + Integer.toHexString(error) + ") at: " + context);
            
            // For critical errors, throw exception
            if (error == 0x0505) { // GL_OUT_OF_MEMORY
                throw new RuntimeException("OpenGL OUT OF MEMORY error in WorldRenderer at: " + context);
            }
        }
    }
    
    /**
     * Clean up OpenGL resources used by the world renderer.
     */
    public void cleanup() {
        battleRenderer.close();
        if (skyRenderer != null) {
            skyRenderer.cleanup();
        }
        precipitationFog.cleanup();
        groundFog.cleanup();
        if (cloudRenderer != null) {
            cloudRenderer.cleanup();
        }
        if (precipitationRenderer != null) {
            precipitationRenderer.cleanup();
        }
        if (lightningRenderer != null) {
            lightningRenderer.cleanup();
        }
        torchShadowRenderer.close();
        torchIndirectLighting.close();
        if (shadowMapRenderer != null) {
            shadowMapRenderer.cleanup();
        }
        if (waterRenderer != null) {
            waterRenderer.cleanup();
        }
        particlePass.close();
        proxyRenderer.cleanup();
        if (lodRegionBatcher != null) {
            lodRegionBatcher.cleanup();
        }
        octreeLodRenderer.cleanup();
        cloudShadowCache.cleanup();
    }
}
