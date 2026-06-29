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
import com.stonebreak.rendering.WaterEffects;
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
    private final SkyRenderer skyRenderer;
    private final CloudRenderer cloudRenderer;
    private final FastLodRenderPass lodRenderPass;
    private final ChunkFrustumCuller frustumCuller = new ChunkFrustumCuller();
    private final com.stonebreak.rendering.models.entities.FishingLineRenderer fishingLineRenderer;

    // Reusable lists to avoid allocations during rendering
    private final List<Chunk> reusableSortedChunks = new ArrayList<>();
    private final List<Chunk> reusableVisibleChunks = new ArrayList<>();
    // Cached so the per-frame chunk visit doesn't allocate a capturing lambda each call.
    private final java.util.function.Consumer<Chunk> frustumCollector = chunk -> {
        if (frustumCuller.isChunkVisible(chunk)) {
            reusableVisibleChunks.add(chunk);
        }
    };
    
    /**
     * Creates a WorldRenderer with the required dependencies.
     */
    public WorldRenderer(ShaderProgram shaderProgram, BlockTextureArray blockTextureArray,
                        Matrix4f projectionMatrix,
                        BlockRenderer blockRenderer, PlayerArmRenderer playerArmRenderer, EntityRenderer entityRenderer,
                        DropRenderer dropRenderer) {
        this.shaderProgram = shaderProgram;
        this.blockTextureArray = blockTextureArray;
        this.projectionMatrix = projectionMatrix;
        this.blockRenderer = blockRenderer;
        this.playerArmRenderer = playerArmRenderer;
        this.entityRenderer = entityRenderer;
        this.dropRenderer = dropRenderer;
        this.skyRenderer = new SkyRenderer();
        this.cloudRenderer = new CloudRenderer();
        this.lodRenderPass = new FastLodRenderPass();
        this.fishingLineRenderer = new com.stonebreak.rendering.models.entities.FishingLineRenderer(shaderProgram, projectionMatrix);
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

        // Render sky first (before world geometry for proper depth testing)
        skyRenderer.renderSky(projectionMatrix, player.getViewMatrix(), player.getPosition(), sunDirection, skyColor);
        checkGLError("After sky rendering");

        // Render the voxel cloud layer just after the sky dome, before world geometry.
        if (com.stonebreak.config.Settings.getInstance().getCloudsEnabled()) {
            cloudRenderer.renderClouds(projectionMatrix, player.getViewMatrix(), player.getPosition(), totalTime, ambientLightLevel);
            checkGLError("After cloud rendering");
        }
        
        // Ensure proper depth function for world geometry
        glDepthFunc(GL_LESS);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        
        // Use shader program
        shaderProgram.bind();
        checkGLError("After shader bind");
        
        // Set common uniforms for world rendering
        setupWorldUniforms(player);
        // Animate water waves in the vertex shader without remeshing (only if water shader is enabled)
        boolean waterAnimationEnabled = com.stonebreak.config.Settings.getInstance().getWaterShaderEnabled();
        shaderProgram.setUniform("u_time", totalTime);
        shaderProgram.setUniform("u_waterAnimationEnabled", waterAnimationEnabled);
        checkGLError("After setting uniforms");
        
        // Bind texture atlas once before passes
        bindTextureAtlas();
        checkGLError("After texture binding");
        
        // Update animated textures now that atlas is properly bound
        updateAnimatedTextures(totalTime, player);
        checkGLError("After updateAnimatedWater");
        
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
        world.ensureFastLodManager(blockTextureArray);
        int lodPlayerCx = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int lodPlayerCz = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        lodRenderPass.render(shaderProgram, world.getFastLodManager(), lodPlayerCx, lodPlayerCz);

        // Render SBO blocks (blocks with SBO textures, rendered separately from atlas)
        renderSBOPass(visibleChunks);

        // Render entities after opaque blocks but before transparent water
        // This allows water to blend over entities when viewing through water
        renderEntities(player);

        // Render opaque drops BEFORE the transparent pass so they write depth
        // Water can then occlude them properly (water renders with glDepthMask(false))
        renderOpaqueDrops(player);

        // Render transparent pass (water blends over entities correctly)
        renderTransparentPass(visibleChunks, player);

        // Render transparent drops AFTER the transparent pass
        renderTransparentDrops(player);

        // Restore OpenGL state after passes
        restoreGLStateAfterPasses();

        // Render world-specific overlays and effects
        renderWorldOverlays(player);

        // Render water particles
        renderWaterParticles();

        // Render fire bolt cores after the transparent water pass so they draw
        // over water instead of being blended under it. Depth testing still
        // lets opaque blocks in front occlude them correctly.
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
    private void setupWorldUniforms(Player player) {
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

        // Reset underwater fog uniforms for world rendering (blocks/chunks don't use fog)
        shaderProgram.setUniform("u_cameraPos", new Vector3f(0, 0, 0));
        shaderProgram.setUniform("u_underwaterFogDensity", 0.0f);
        shaderProgram.setUniform("u_underwaterFogColor", new Vector3f(0, 0, 0));
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
     * Update animated textures.
     */
    private void updateAnimatedTextures(float totalTime, Player player) {
        blockTextureArray.updateAnimatedWater(totalTime);
    }
    
    /**
     * Visits the chunks around the player and collects those intersecting the camera view
     * frustum. Returns a reused list; the visit itself is allocation-free (no intermediate
     * map of nearby chunks — at 24-chunk render distance that map was ~2,401 entries of
     * per-frame garbage).
     */
    private List<Chunk> cullChunksToFrustum(World world, Player player) {
        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        frustumCuller.update(projectionMatrix, player.getViewMatrix());
        reusableVisibleChunks.clear();
        world.forEachChunkAroundPlayer(playerChunkX, playerChunkZ, frustumCollector);
        return reusableVisibleChunks;
    }

    /**
     * Render opaque pass (non-water parts of chunks).
     */
    private void renderOpaquePass(List<Chunk> visibleChunks) {
        shaderProgram.setUniform("u_renderPass", 0); // 0 for opaque/non-water pass
        shaderProgram.setUniform("u_waterDepthOffset", 0.0f); // No depth offset for opaque pass
        glDepthMask(true);  // Enable depth writing for opaque objects
        glDisable(GL_BLEND); // Opaque objects typically don't need blending

        // Debug: Log first time
        if (debugOpaquePassCount < 1) {
            System.out.println("[WorldRenderer.renderOpaquePass] Rendering " + visibleChunks.size() + " chunks");
            debugOpaquePassCount++;
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
        shaderProgram.setUniform("u_renderPass", 1); // 1 for transparent/water pass
        shaderProgram.setUniform("u_waterDepthOffset", -0.0001f); // Negative offset to pull water slightly closer
        glEnable(GL_BLEND); // Enable blending
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Standard alpha blending

        // Sort chunks from back to front for transparent pass
        sortChunksBackToFront(visibleChunks, player);

        // Sub-pass A: translucent solids (e.g. ice). Depth-write ON so they
        // occlude any farther translucent surface (most importantly distant
        // water seen through ice — that's the artifact this fixes). The
        // shader's water/translucent paths key off u_translucentLayer to
        // pick which sub-layer they emit in.
        shaderProgram.setUniform("u_translucentLayer", 0);
        glDepthMask(true);
        for (Chunk chunk : reusableSortedChunks) {
            chunk.render();
        }

        // Sub-pass B: water and remaining translucents. Depth-write OFF so
        // multiple water layers blend correctly without occluding each other.
        shaderProgram.setUniform("u_translucentLayer", 1);
        glDepthMask(false);
        for (Chunk chunk : reusableSortedChunks) {
            chunk.render();
        }

        // Reset for downstream passes (drops, particles, etc.) that expect
        // the legacy "no filter" behavior.
        shaderProgram.setUniform("u_translucentLayer", -1);
    }
    
    /**
     * Sort chunks from back to front for proper transparent rendering.
     */
    private void sortChunksBackToFront(List<Chunk> visibleChunks, Player player) {
        reusableSortedChunks.clear();
        reusableSortedChunks.addAll(visibleChunks);
        Vector3f playerPos = player.getPosition();
        
        Collections.sort(reusableSortedChunks, (c1, c2) -> {
            // Calculate distance squared from player to center of each chunk
            float c1CenterX = c1.getWorldX(WorldConfiguration.CHUNK_SIZE / 2);
            float c1CenterZ = c1.getWorldZ(WorldConfiguration.CHUNK_SIZE / 2);
            float c2CenterX = c2.getWorldX(WorldConfiguration.CHUNK_SIZE / 2);
            float c2CenterZ = c2.getWorldZ(WorldConfiguration.CHUNK_SIZE / 2);

            float distSq1 = (playerPos.x - c1CenterX) * (playerPos.x - c1CenterX) +
                            (playerPos.z - c1CenterZ) * (playerPos.z - c1CenterZ);
            float distSq2 = (playerPos.x - c2CenterX) * (playerPos.x - c2CenterX) +
                            (playerPos.z - c2CenterZ) * (playerPos.z - c2CenterZ);
            
            // Sort in descending order of distance (farthest first)
            return Float.compare(distSq2, distSq1);
        });
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
     * Render water particles in the 3D world.
     */
    private void renderWaterParticles() {
        WaterEffects waterEffects = Game.getWaterEffects();
        if (waterEffects == null || waterEffects.getParticles().isEmpty()) {
            return;
        }
        
        // Get player's camera view matrix
        Matrix4f viewMatrix = Game.getPlayer().getViewMatrix();
        
        // Use the shader program
        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("viewMatrix", viewMatrix);
        
        // Set up for particle rendering
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        
        // Enable blending
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        
        // Disable depth writing but keep depth testing
        glDepthMask(false);
        
        // Use point rendering for particles
        glPointSize(5.0f);
        
        // Start drawing points
        glBegin(GL_POINTS);
        
        // Draw each particle
        for (WaterEffects.WaterParticle particle : waterEffects.getParticles()) {
            float opacity = particle.getOpacity();
            
            // Set particle color (light blue with variable opacity)
            shaderProgram.setUniform("u_color", new org.joml.Vector4f(0.7f, 0.85f, 1.0f, opacity * 0.7f));
            
            // Draw particle at its position
            glVertex3f(
                particle.getPosition().x,
                particle.getPosition().y,
                particle.getPosition().z
            );
        }
        
        glEnd();
        
        // Reset OpenGL state
        glPointSize(1.0f);
        glDepthMask(true);
        glDisable(GL_BLEND);
        
        // Reset shader state
        shaderProgram.setUniform("u_useSolidColor", false);
        
        // Unbind shader
        shaderProgram.unbind();
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

        for (com.stonebreak.mobs.entities.Entity entity : entityManager.getAllEntities()) {
            if (!entity.isAlive()) continue;
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
    }
}
