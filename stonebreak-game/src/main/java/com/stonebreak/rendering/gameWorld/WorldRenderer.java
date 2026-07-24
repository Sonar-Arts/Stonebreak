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
import com.openmason.engine.rendering.sky.SkyRenderer;
import com.openmason.engine.rendering.sky.clouds.CloudRenderer;
import com.stonebreak.rendering.gameWorld.fastlod.FastLodRenderPass;
import com.stonebreak.world.chunk.Chunk;
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
    private final com.stonebreak.rendering.gameWorld.water.WaterRenderer waterRenderer;
    private final com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer shadowMapRenderer;
    private final FastLodRenderPass lodRenderPass;
    private final ChunkFrustumCuller frustumCuller = new ChunkFrustumCuller();
    private final com.stonebreak.rendering.models.entities.FishingLineRenderer fishingLineRenderer;

    // Reusable lists to avoid allocations during rendering
    private final List<Chunk> reusableSortedChunks = new ArrayList<>();
    private final List<Chunk> reusableVisibleChunks = new ArrayList<>();
    private final List<Chunk> reusableLoadedChunks = new ArrayList<>();
    private final List<Chunk> reusableTranslucentChunks = new ArrayList<>();
    private long[] sortKeys = new long[512];
    // Region-level frustum pre-cull grid: one classifying AABB test per
    // 8x8-chunk-column region decides whole regions; only INTERSECT regions
    // fall through to per-chunk tests. Reused across frames.
    private int[] regionVisibility = new int[0];
    private int regionGridMinRx, regionGridMinRz, regionGridWidth, regionGridHeight;
    private final List<com.stonebreak.rendering.gameWorld.water.WaterRenderer.LodWaterNode> reusableLodWater = new ArrayList<>();
    // Cached so the per-frame chunk visit doesn't allocate a capturing lambda each call.
    private final java.util.function.Consumer<Chunk> loadedCollector = reusableLoadedChunks::add;
    
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
        this.shadowMapRenderer =
                new com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer(projectionMatrix, blockTextureArray);
        if (entityRenderer != null) {
            entityRenderer.setShadowMapRenderer(shadowMapRenderer);
        }
        if (animatedBlockRenderer != null) {
            animatedBlockRenderer.setShadowMapRenderer(shadowMapRenderer);
        }
        this.lodRenderPass = new FastLodRenderPass();
        this.fishingLineRenderer = new com.stonebreak.rendering.models.entities.FishingLineRenderer(shaderProgram, projectionMatrix);
        this.waterRenderer = new com.stonebreak.rendering.gameWorld.water.WaterRenderer();
    }
    
    /**
     * Renders the 3D world including chunks, entities, and world effects.
     */
    public void renderWorld(World world, Player player, float totalTime) {
        // Debug: Log first call
        if (debugRenderWorldCount < 1) {
            System.out.println("[WorldRenderer.renderWorld] Called! world=" + (world != null) + " player=" + (player != null));
            debugRenderWorldCount++;
        }

        // Clear any pending OpenGL errors from previous operations
        clearPendingGLErrors();
        checkGLError("After clearing pending errors");

        // Get time of day system for lighting. The engine sky/cloud renderers are decoupled
        // from TimeOfDay, so resolve the values they need here (static fallback when absent).
        com.stonebreak.world.TimeOfDay timeOfDay = Game.getTimeOfDay();
        Vector3f sunDirection;
        Vector3f skyColor;
        float ambientLightLevel;
        if (timeOfDay != null) {
            sunDirection = timeOfDay.getSunDirection();
            skyColor = timeOfDay.getSkyColor();
            ambientLightLevel = timeOfDay.getAmbientLightLevel();
        } else {
            sunDirection = new Vector3f(0.7f, 0.1f, 0.5f).normalize();
            skyColor = new Vector3f(0.53f, 0.81f, 0.92f); // Day sky
            ambientLightLevel = 1.0f;
        }

        // Region-batched chunk rendering: publish last frame's stats and prune
        // emptied regions before this frame's first draws.
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance().beginFrame();
        }

        // ONE grid walk over the render-distance ring collects every loaded
        // chunk for the frame; the shadow caster cull and the camera frustum
        // cull below both iterate this list (previously each did its own
        // ~2401-cell ConcurrentHashMap walk per frame at 24-chunk distance).
        collectLoadedChunks(world, player);

        // Cascaded sun-shadow depth pre-pass. Runs before anything samples the
        // shadow map this frame; restores the caller's framebuffer and viewport,
        // so it is safe even when the post-fx scene FBO is already bound.
        shadowMapRenderer.renderShadowPass(world, player, sunDirection, entityRenderer, reusableLoadedChunks);
        checkGLError("After shadow depth pre-pass");

        // Render sky first (before world geometry for proper depth testing)
        skyRenderer.renderSky(projectionMatrix, player.getViewMatrix(), player.getPosition(), sunDirection, skyColor);
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
        // Disabled (fogEnd=0) when LOD is off or the camera is underwater —
        // the underwater fog owns the look there.
        com.stonebreak.config.Settings settings = com.stonebreak.config.Settings.getInstance();
        float fogStart = 0f, fogEnd = 0f;
        Vector3f cameraPos = player.getCamera().getPosition();
        boolean cameraUnderwater = world.isPositionUnderwater(
                (int) Math.floor(cameraPos.x), (int) Math.floor(cameraPos.y), (int) Math.floor(cameraPos.z));
        if (settings.getLodEnabled() && settings.getLodDistance() > 0 && !cameraUnderwater) {
            fogStart = settings.getRenderDistance() * (float) WorldConfiguration.CHUNK_SIZE;
            fogEnd = (settings.getRenderDistance() + settings.getLodDistance())
                    * (float) WorldConfiguration.CHUNK_SIZE;
        }

        // Set common uniforms for world rendering
        setupWorldUniforms(player, skyColor, fogStart, fogEnd);
        // Water animation setting — consumed by the dedicated water renderer
        // (waves + flow scroll) and the LOD sea-sheet drift (u_time).
        boolean waterAnimationEnabled = settings.getWaterShaderEnabled();
        checkGLError("After setting uniforms");
        
        // Bind texture atlas once before passes
        bindTextureAtlas();
        checkGLError("After texture binding");
        
        // Visit chunks around the player, culling those outside the view frustum
        List<Chunk> visibleChunks = cullChunksToFrustum(world, player);

        // Debug: Log chunk count
        if (debugVisibleChunksCount < 1) {
            System.out.println("[WorldRenderer] Got " + visibleChunks.size() + " visible chunks (of "
                + world.getLoadedChunkCount() + " loaded) after frustum culling");
            debugVisibleChunksCount++;
        }

        // Render opaque pass
        renderOpaquePass(visibleChunks);

        // Render distant-terrain LOD between detail opaque and SBO; shares shader/atlas state.
        // frustumCuller was updated for this frame's camera by cullChunksToFrustum above —
        // the LOD pass reuses those planes, so keep this call after it. Surviving nodes'
        // water sheets are collected for the dedicated water pass below.
        world.ensureFastLodManager(blockTextureArray);
        int lodPlayerCx = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int lodPlayerCz = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        reusableLodWater.clear();
        lodRenderPass.render(shaderProgram, world.getFastLodManager(), lodPlayerCx, lodPlayerCz,
                frustumCuller, world::isChunkRenderableAt, reusableLodWater);

        // Render SBO blocks (blocks with SBO textures, rendered separately from atlas)
        renderSBOPass(visibleChunks);

        // Render entities after opaque blocks but before transparent water
        // This allows water to blend over entities when viewing through water
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

        // Render transparent pass (translucent solids like ice)
        renderTransparentPass(visibleChunks, player);

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
        // shader, in the compositing slot the old water sub-pass held (after
        // ice). Reuses the back-to-front order renderTransparentPass computed.
        // State-neutral (saves/restores all GL state it touches), but leaves
        // the nearest water surface's depth in the depth buffer, so later
        // passes (fire bolts, particles) are occluded by water in front of
        // them — physically correct compositing.
        waterRenderer.render(reusableSortedChunks, projectionMatrix, player.getViewMatrix(),
                player.getCamera().getPosition(), totalTime, sunDirection,
                ambientLightLevel, waterAnimationEnabled, skyColor, fogStart, fogEnd,
                reusableLodWater);
        checkGLError("After water pass");

        // Voxel cloud layer — drawn AFTER world geometry and water so it depth
        // tests against them: clouds behind mountains are rejected, and when
        // the camera is above the cloud layer the clouds blend over the
        // terrain/water below instead of being overwritten by it (clouds do
        // not write depth, so anything drawn later at the same pixels would
        // win regardless of distance if this ran before the world passes).
        if (com.stonebreak.config.Settings.getInstance().getCloudsEnabled()) {
            cloudRenderer.renderClouds(projectionMatrix, player.getViewMatrix(), player.getPosition(), totalTime, ambientLightLevel);
            checkGLError("After cloud rendering");
        }

        // Render fire bolt cores after the water pass: bolts in front of water
        // draw over it; bolts behind a water surface are depth-occluded by the
        // water prepass (physically correct). Opaque blocks in front still
        // occlude them via depth testing.
        renderFireBoltCores(player);

        // Render fire bolt trail particles
        renderFireBoltParticles();

        // Render Illusionist decoy smoke puffs
        renderIllusionSmoke();

        // Render through-terrain outlines for REVEALED enemies (Illusionist)
        renderRevealedOutlines(player);

        // Render player arm last (if not paused) to appear in front of entities
        renderPlayerArm(player);
    }
    
    /**
     * Stamps cloud depth into the bound framebuffer's depth attachment (color writes masked)
     * so screen-space god rays treat clouds as occluders. Must be called after
     * {@link #renderWorld} so clouds behind terrain are depth-rejected, and while the
     * post-processing scene framebuffer (whose depth the god rays read) is still bound.
     */
    public void renderCloudOcclusion(Player player, float totalTime) {
        if (!com.stonebreak.config.Settings.getInstance().getCloudsEnabled()) {
            return;
        }
        cloudRenderer.renderCloudOcclusion(projectionMatrix, player.getViewMatrix(),
                player.getPosition(), totalTime);
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
     * Set up common uniforms for world rendering.
     */
    private void setupWorldUniforms(Player player, Vector3f skyColor,
                                    float fogStart, float fogEnd) {
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
            shaderProgram.setUniform("u_ambientLight", timeOfDay.getAmbientLightLevel());
            shaderProgram.setUniform("u_sunDirection", timeOfDay.getSunDirection());
        } else {
            // Fallback to default lighting if TimeOfDay not initialized
            shaderProgram.setUniform("u_ambientLight", 1.0f);
            shaderProgram.setUniform("u_sunDirection", new Vector3f(0.5f, 1.0f, 0.3f).normalize());
        }

        // Set view position for specular lighting calculations
        shaderProgram.setUniform("u_viewPos", player.getCamera().getPosition());

        // Cascaded sun-shadow sampling state (binds the map on its own unit;
        // disables sampling when the pass was skipped — night, setting off).
        shadowMapRenderer.applyToShader(shaderProgram);

        // Reset underwater fog uniforms for world rendering (blocks/chunks don't use fog)
        shaderProgram.setUniform("u_cameraPos", new Vector3f(0, 0, 0));
        shaderProgram.setUniform("u_underwaterFogDensity", 0.0f);
        shaderProgram.setUniform("u_underwaterFogColor", new Vector3f(0, 0, 0));

        // Atmospheric distance fog toward the sky color (fogEnd <= fogStart disables).
        shaderProgram.setUniform("u_fogColor", skyColor);
        shaderProgram.setUniform("u_fogStart", fogStart);
        shaderProgram.setUniform("u_fogEnd", fogEnd);
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
     * The frame's single render-distance grid walk: collects every loaded
     * chunk into a reused list shared by the shadow caster cull and the
     * camera frustum cull. Allocation-free.
     */
    private void collectLoadedChunks(World world, Player player) {
        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        reusableLoadedChunks.clear();
        world.forEachChunkAroundPlayer(playerChunkX, playerChunkZ, loadedCollector);
    }

    /**
     * Filters this frame's loaded-chunk list down to those intersecting the
     * camera view frustum. A region-level pre-cull (one classifying AABB test
     * per 8x8-chunk-column region) skips or wholesale-accepts entire regions,
     * so per-chunk tests only run where a region straddles the frustum edge.
     * Returns a reused list; allocation-free.
     */
    private List<Chunk> cullChunksToFrustum(World world, Player player) {
        frustumCuller.update(projectionMatrix, player.getViewMatrix());
        reusableVisibleChunks.clear();
        int count = reusableLoadedChunks.size();
        if (count == 0) {
            return reusableVisibleChunks;
        }
        computeRegionVisibility();
        int shift = com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion.REGION_SHIFT;
        for (int i = 0; i < count; i++) {
            Chunk chunk = reusableLoadedChunks.get(i);
            int ix = (chunk.getChunkX() >> shift) - regionGridMinRx;
            int iz = (chunk.getChunkZ() >> shift) - regionGridMinRz;
            int vis = regionVisibility[ix * regionGridHeight + iz];
            if (vis >= 0) {
                continue; // Whole region outside (vis = index of the culling plane).
            }
            if (vis == org.joml.FrustumIntersection.INSIDE || frustumCuller.isChunkVisible(chunk)) {
                reusableVisibleChunks.add(chunk);
            }
        }
        return reusableVisibleChunks;
    }

    /**
     * Classifies every region covering this frame's loaded chunks against the
     * camera frustum (INSIDE / INTERSECT / plane index when fully outside).
     * Must run after {@code frustumCuller.update}.
     */
    private void computeRegionVisibility() {
        int shift = com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion.REGION_SHIFT;
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
        int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
        for (int i = 0; i < reusableLoadedChunks.size(); i++) {
            Chunk chunk = reusableLoadedChunks.get(i);
            int cx = chunk.getChunkX();
            int cz = chunk.getChunkZ();
            if (cx < minCx) minCx = cx;
            if (cx > maxCx) maxCx = cx;
            if (cz < minCz) minCz = cz;
            if (cz > maxCz) maxCz = cz;
        }
        regionGridMinRx = minCx >> shift;
        regionGridMinRz = minCz >> shift;
        regionGridWidth = (maxCx >> shift) - regionGridMinRx + 1;
        regionGridHeight = (maxCz >> shift) - regionGridMinRz + 1;
        int cells = regionGridWidth * regionGridHeight;
        if (regionVisibility.length < cells) {
            regionVisibility = new int[cells];
        }
        float regionBlocks = com.openmason.engine.voxel.mms.mmsRegion.MmsChunkRegion.REGION_SPAN
                * (float) WorldConfiguration.CHUNK_SIZE;
        for (int ix = 0; ix < regionGridWidth; ix++) {
            float minX = (regionGridMinRx + ix) * regionBlocks;
            for (int iz = 0; iz < regionGridHeight; iz++) {
                float minZ = (regionGridMinRz + iz) * regionBlocks;
                regionVisibility[ix * regionGridHeight + iz] = frustumCuller.intersectAab(
                        minX, 0f, minZ,
                        minX + regionBlocks, WorldConfiguration.WORLD_HEIGHT, minZ + regionBlocks);
            }
        }
    }

    /**
     * Render opaque pass (non-water parts of chunks).
     */
    private void renderOpaquePass(List<Chunk> visibleChunks) {
        shaderProgram.setUniform("u_renderPass", 0); // 0 for opaque/non-water pass
        glDepthMask(true);  // Enable depth writing for opaque objects
        glDisable(GL_BLEND); // Opaque objects typically don't need blending

        // Debug: Log first time
        if (debugOpaquePassCount < 1) {
            System.out.println("[WorldRenderer.renderOpaquePass] Rendering " + visibleChunks.size() + " chunks");
            debugOpaquePassCount++;
        }

        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            var regionRenderer =
                com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance();
            // GL 4.3+ path: compute-shader per-mesh cull + one indirect
            // multidraw per region — no per-chunk CPU visibility work for the
            // opaque pass at all. Falls back to the CPU multidraw when the
            // cull program is unavailable.
            if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isGpuCullEnabled()
                    && regionRenderer.drawLayerGpuCulled(
                        com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.LAYER_ATLAS,
                        frustumCuller.projectionView())) {
                regionRenderer.drawLegacyOnly(visibleChunks,
                    com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.LAYER_ATLAS);
                return;
            }
            // One multidraw per visible region instead of a VAO bind + draw
            // call per chunk (legacy-handle stragglers draw individually).
            regionRenderer.drawChunks(visibleChunks,
                com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.LAYER_ATLAS);
            return;
        }
        for (Chunk chunk : visibleChunks) {
            chunk.render(); // Shader will discard water fragments
        }
    }

    private static int debugOpaquePassCount = 0;

    /**
     * Render SBO blocks from all visible chunks.
     * Each face binds its own SBO texture before drawing.
     */
    private void renderSBOPass(List<Chunk> visibleChunks) {
        int sboCount = 0;
        for (Chunk chunk : visibleChunks) {
            java.util.List<com.openmason.engine.voxel.sbo.SBORenderData> sboList = chunk.getSBORenderDataList();
            if (sboList != null && !sboList.isEmpty()) {
                sboCount++;
                for (com.openmason.engine.voxel.sbo.SBORenderData sboData : sboList) {
                    sboData.render();
                }
            }
        }
        if (debugSBOPassCount < 3 && sboCount > 0) {
            System.out.println("[WorldRenderer] SBO pass: " + sboCount + " chunks with SBO meshes");
            debugSBOPassCount++;
        }
    }

    private static int debugSBOPassCount = 0;

    /**
     * Render transparent pass (water parts of chunks).
     */
    private void renderTransparentPass(List<Chunk> visibleChunks, Player player) {
        shaderProgram.bind(); // re-bind in case entity rendering left a different shader active
        shaderProgram.setUniform("u_renderPass", 1); // 1 for transparent pass
        glEnable(GL_BLEND); // Enable blending
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Standard alpha blending

        // Sort chunks from back to front for transparent pass
        sortChunksBackToFront(visibleChunks, player);

        // Translucent solids (e.g. ice) only. Depth-write ON so they occlude
        // any farther translucent surface. Water no longer lives in the atlas
        // mesh — it has its own per-chunk mesh drawn by the dedicated
        // WaterRenderer right after this pass, which also made the old
        // two-sub-layer scheme (u_translucentLayer 0/1) unnecessary.
        //
        // Only chunks whose atlas mesh actually CONTAINS translucent geometry
        // draw here — previously every visible chunk's whole mesh re-drew for
        // its shader-discarded fragments, doubling world geometry per frame.
        glDepthMask(true);
        reusableTranslucentChunks.clear();
        for (int i = 0; i < reusableSortedChunks.size(); i++) {
            Chunk chunk = reusableSortedChunks.get(i);
            if (chunk.atlasHasTranslucent()) {
                reusableTranslucentChunks.add(chunk);
            }
        }
        if (com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.isEnabled()) {
            com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.getInstance()
                .drawChunks(reusableTranslucentChunks,
                    com.stonebreak.rendering.gameWorld.regions.ChunkRegionRenderer.LAYER_ATLAS);
            return;
        }
        for (Chunk chunk : reusableTranslucentChunks) {
            chunk.render();
        }
    }

    /**
     * Sort chunks from back to front for proper transparent rendering.
     * Distance keys are computed once per chunk and sorted as packed longs
     * (IEEE bits of a non-negative float are order-preserving), replacing the
     * comparator that re-derived both chunk centers on every comparison.
     */
    private void sortChunksBackToFront(List<Chunk> visibleChunks, Player player) {
        reusableSortedChunks.clear();
        int count = visibleChunks.size();
        if (count == 0) {
            return;
        }
        if (sortKeys.length < count) {
            sortKeys = new long[Math.max(count, sortKeys.length * 2)];
        }
        Vector3f playerPos = player.getPosition();
        for (int i = 0; i < count; i++) {
            Chunk chunk = visibleChunks.get(i);
            float dx = playerPos.x - chunk.getWorldX(WorldConfiguration.CHUNK_SIZE / 2);
            float dz = playerPos.z - chunk.getWorldZ(WorldConfiguration.CHUNK_SIZE / 2);
            float distSq = dx * dx + dz * dz;
            sortKeys[i] = ((long) Float.floatToIntBits(distSq) << 32) | (i & 0xFFFFFFFFL);
        }
        java.util.Arrays.sort(sortKeys, 0, count);
        // Descending distance = farthest first.
        for (int i = count - 1; i >= 0; i--) {
            reusableSortedChunks.add(visibleChunks.get((int) sortKeys[i]));
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
        boolean activeState = currentState == GameState.PLAYING
                || currentState == GameState.INVENTORY_UI
                || currentState == GameState.CHARACTER_SHEET_UI
                || currentState == GameState.RECIPE_BOOK_UI
                || currentState == GameState.WORKBENCH_UI
                || currentState == GameState.FURNACE_UI;
        if (!activeState) return;

        if (player.isThirdPerson()) {
            // Render the full body model instead of the first-person arm.
            if (entityRenderer != null) {
                World world = Game.getWorld();
                Vector3f cameraPos = player.getCamera().getPosition();
                entityRenderer.renderLocalPlayer(player, player.getViewMatrix(), projectionMatrix, world, cameraPos);
            }
        } else {
            playerArmRenderer.renderPlayerArm(player);
        }
    }

    /**
     * Render fire bolt core cubes after the transparent water pass so they are
     * not blended under water. Drawn via the entity renderer, which skips bolts
     * that have already impacted (only their particles remain).
     */
    private void renderFireBoltCores(Player player) {
        com.stonebreak.mobs.entities.EntityManager em = Game.getEntityManager();
        if (em == null || entityRenderer == null) return;

        World world = Game.getWorld();
        Vector3f cameraPos = player.getCamera().getPosition();
        for (com.stonebreak.mobs.entities.Entity entity : em.getAllEntities()) {
            if (entity.isAlive()
                    && entity.getType() == com.stonebreak.mobs.entities.EntityType.FIRE_BOLT) {
                entityRenderer.renderEntity(entity, player.getViewMatrix(), projectionMatrix, world, cameraPos);
            }
        }
    }

    /**
     * Render fire trail particles from all active fire bolt entities.
     */
    private void renderFireBoltParticles() {
        com.stonebreak.mobs.entities.EntityManager em = Game.getEntityManager();
        if (em == null) return;

        boolean anyParticles = false;
        for (com.stonebreak.mobs.entities.Entity entity : em.getAllEntities()) {
            if (entity instanceof com.stonebreak.mobs.entities.FireBolt bolt && bolt.isAlive()
                    && !bolt.particles.isEmpty()) {
                anyParticles = true;
                break;
            }
        }
        if (!anyParticles) return;

        Matrix4f viewMatrix = Game.getPlayer().getViewMatrix();
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", viewMatrix);
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE); // additive for fire glow
        glDepthMask(false);

        for (com.stonebreak.mobs.entities.Entity entity : em.getAllEntities()) {
            if (!(entity instanceof com.stonebreak.mobs.entities.FireBolt bolt) || !bolt.isAlive()) continue;

            for (com.stonebreak.rendering.effects.FireTrailParticles.FireParticle p : bolt.particles.snapshot()) {
                float opacity = p.getOpacity();
                // Lerp orange → red as particle fades
                float r = 1.0f;
                float g = 0.35f * opacity;
                shaderProgram.setUniform("u_color", new org.joml.Vector4f(r, g, 0.0f, opacity * 0.85f));

                glPointSize(p.getSize());
                glBegin(GL_POINTS);
                glVertex3f(p.getPosition().x, p.getPosition().y, p.getPosition().z);
                glEnd();
            }
        }

        glPointSize(1.0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.unbind();
    }

    /**
     * Render the smoke puff emitted when Illusionist decoys appear or shatter. The particle list
     * lives on the active Mirrored Deceit ability so it persists past the cast until it fades.
     */
    private void renderIllusionSmoke() {
        Player player = Game.getPlayer();
        if (player == null) return;
        com.stonebreak.rendering.effects.IllusionSmokeParticles smoke =
                player.getIllusionistAbilities().getMirroredDeceit().getSmoke();
        if (smoke.isEmpty()) return;

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // straight alpha for soft grey smoke
        glDepthMask(false);

        for (com.stonebreak.rendering.effects.IllusionSmokeParticles.SmokeParticle p : smoke.snapshot()) {
            float opacity = p.getOpacity();
            // Pale violet smoke, fading out.
            shaderProgram.setUniform("u_color", new org.joml.Vector4f(0.72f, 0.66f, 0.85f, opacity * 0.55f));
            glPointSize(p.getSize());
            glBegin(GL_POINTS);
            glVertex3f(p.getPosition().x, p.getPosition().y, p.getPosition().z);
            glEnd();
        }

        glPointSize(1.0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.unbind();
    }

    /**
     * Render a through-terrain wireframe box around every living entity carrying the REVEALED
     * status (Illusionist decoy hit). Depth testing is disabled so the outline is visible even
     * when the enemy is behind walls.
     */
    private void renderRevealedOutlines(Player player) {
        com.stonebreak.mobs.entities.EntityManager em = Game.getEntityManager();
        if (em == null) return;

        List<com.stonebreak.mobs.entities.LivingEntity> revealed = new ArrayList<>();
        for (com.stonebreak.mobs.entities.LivingEntity entity : em.getLivingEntities()) {
            if (entity.isAlive()
                    && entity.hasStatusEffect(com.stonebreak.mobs.entities.status.StatusEffectType.REVEALED)) {
                revealed.add(entity);
            }
        }
        if (revealed.isEmpty()) return;

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", player.getViewMatrix());
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_color", new org.joml.Vector4f(0.85f, 0.30f, 0.95f, 0.9f));

        glDisable(GL_DEPTH_TEST);
        glDepthMask(false);

        for (com.stonebreak.mobs.entities.LivingEntity entity : revealed) {
            Vector3f pos = entity.getPosition();
            com.stonebreak.mobs.entities.EntityType type = entity.getType();
            float hw = type.getWidth() * 0.5f;
            float hl = type.getLength() * 0.5f;
            float minX = pos.x - hw, maxX = pos.x + hw;
            float minZ = pos.z - hl, maxZ = pos.z + hl;
            float minY = pos.y - type.getLegHeight(), maxY = pos.y + type.getHeight();
            drawWireBox(minX, minY, minZ, maxX, maxY, maxZ);
        }

        glDepthMask(true);
        glEnable(GL_DEPTH_TEST);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.unbind();
    }

    /** Draws the 12 edges of an axis-aligned box in immediate mode (caller sets shader/color). */
    private void drawWireBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        glBegin(GL_LINES);
        // Bottom rectangle
        glVertex3f(minX, minY, minZ); glVertex3f(maxX, minY, minZ);
        glVertex3f(maxX, minY, minZ); glVertex3f(maxX, minY, maxZ);
        glVertex3f(maxX, minY, maxZ); glVertex3f(minX, minY, maxZ);
        glVertex3f(minX, minY, maxZ); glVertex3f(minX, minY, minZ);
        // Top rectangle
        glVertex3f(minX, maxY, minZ); glVertex3f(maxX, maxY, minZ);
        glVertex3f(maxX, maxY, minZ); glVertex3f(maxX, maxY, maxZ);
        glVertex3f(maxX, maxY, maxZ); glVertex3f(minX, maxY, maxZ);
        glVertex3f(minX, maxY, maxZ); glVertex3f(minX, maxY, minZ);
        // Vertical edges
        glVertex3f(minX, minY, minZ); glVertex3f(minX, maxY, minZ);
        glVertex3f(maxX, minY, minZ); glVertex3f(maxX, maxY, minZ);
        glVertex3f(maxX, minY, maxZ); glVertex3f(maxX, maxY, maxZ);
        glVertex3f(minX, minY, maxZ); glVertex3f(minX, maxY, maxZ);
        glEnd();
    }

    /**
     * Render all drops using the drop sub-renderer.
     * Drops are rendered before entities to appear underneath everything but above world geometry.
     *
     * @deprecated Replaced by {@link #renderOpaqueDrops} and {@link #renderTransparentDrops}
     */
    @Deprecated
    private void renderDrops(Player player) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();

        if (entityManager != null && dropRenderer != null) {
            // Get all entities and filter for drops + collect remote players for held-item rendering.
            List<com.stonebreak.mobs.entities.Entity> allEntities = entityManager.getAllEntities();
            java.util.List<com.stonebreak.mobs.entities.Entity> drops = new java.util.ArrayList<>();
            java.util.List<com.stonebreak.mobs.entities.RemotePlayer> remotePlayers = new java.util.ArrayList<>();

            for (com.stonebreak.mobs.entities.Entity entity : allEntities) {
                if (!entity.isAlive()) continue;
                if (isDropEntity(entity)) {
                    drops.add(entity);
                } else if (entity instanceof com.stonebreak.mobs.entities.RemotePlayer rp) {
                    remotePlayers.add(rp);
                }
            }

            World world = Game.getWorld();
            Vector3f cameraPos = player.getCamera().getPosition();

            if (!drops.isEmpty()) {
                dropRenderer.renderDrops(drops, shaderProgram, projectionMatrix, player.getViewMatrix(), world, cameraPos);
            }
            if (!remotePlayers.isEmpty()) {
                dropRenderer.renderHeldItems(remotePlayers, shaderProgram, projectionMatrix, player.getViewMatrix(), world, cameraPos);
            }
        }
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
        Vector3f cameraPos = player.getCamera().getPosition();

        if (!drops.isEmpty()) {
            dropRenderer.renderOpaqueDrops(drops, shaderProgram, projectionMatrix, player.getViewMatrix(), world, cameraPos);
        }
        if (!remotePlayers.isEmpty()) {
            dropRenderer.renderHeldItems(remotePlayers, shaderProgram, projectionMatrix, player.getViewMatrix(), world, cameraPos);
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
        Vector3f cameraPos = player.getCamera().getPosition();

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

        if (entityManager != null && entityRenderer != null) {
            World world = Game.getWorld();
            // Get camera position (at eye level, not feet)
            Vector3f cameraPos = player.getCamera().getPosition();

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
    private static int debugVisibleChunksCount = 0;

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
        if (skyRenderer != null) {
            skyRenderer.cleanup();
        }
        if (cloudRenderer != null) {
            cloudRenderer.cleanup();
        }
        if (shadowMapRenderer != null) {
            shadowMapRenderer.cleanup();
        }
        if (waterRenderer != null) {
            waterRenderer.cleanup();
        }
    }
}
