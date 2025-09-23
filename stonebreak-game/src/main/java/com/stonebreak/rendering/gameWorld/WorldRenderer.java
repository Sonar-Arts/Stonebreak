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
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.WaterEffects;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.models.entities.EntityRenderer;
import com.stonebreak.rendering.models.entities.DropRenderer;
import com.stonebreak.rendering.player.PlayerArmRenderer;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.gameWorld.sky.SkyRenderer;
import com.stonebreak.world.chunk.Chunk;
import com.stonebreak.world.World;

/**
 * Specialized renderer for 3D world elements including chunks, entities, and world-specific effects.
 * This renderer handles all world rendering without UI elements.
 */
public class WorldRenderer {
    
    // Dependencies
    private final ShaderProgram shaderProgram;
    private final TextureAtlas textureAtlas;
    private final Matrix4f projectionMatrix;
    private final BlockRenderer blockRenderer;
    private final PlayerArmRenderer playerArmRenderer;
    private final EntityRenderer entityRenderer;
    private final DropRenderer dropRenderer;
    private final SkyRenderer skyRenderer;
    
    // Reusable lists to avoid allocations during rendering
    private final List<Chunk> reusableSortedChunks = new ArrayList<>();
    
    /**
     * Creates a WorldRenderer with the required dependencies.
     */
    public WorldRenderer(ShaderProgram shaderProgram, TextureAtlas textureAtlas, Matrix4f projectionMatrix,
                        BlockRenderer blockRenderer, PlayerArmRenderer playerArmRenderer, EntityRenderer entityRenderer,
                        DropRenderer dropRenderer) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
        this.projectionMatrix = projectionMatrix;
        this.blockRenderer = blockRenderer;
        this.playerArmRenderer = playerArmRenderer;
        this.entityRenderer = entityRenderer;
        this.dropRenderer = dropRenderer;
        this.skyRenderer = new SkyRenderer();
    }
    
    /**
     * Renders the 3D world including chunks, entities, and world effects.
     */
    public void renderWorld(World world, Player player, float totalTime) {
        // Clear any pending OpenGL errors from previous operations
        clearPendingGLErrors();
        checkGLError("After clearing pending errors");
        
        // Render sky first (before world geometry for proper depth testing)
        skyRenderer.renderSky(projectionMatrix, player.getViewMatrix(), player.getPosition(), totalTime);
        checkGLError("After sky rendering");
        
        // Ensure proper depth function for world geometry
        glDepthFunc(GL_LESS);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        
        // Use shader program
        shaderProgram.bind();
        checkGLError("After shader bind");
        
        // Set common uniforms for world rendering
        setupWorldUniforms(player);
        // Animate water waves in the vertex shader without remeshing
        shaderProgram.setUniform("u_time", totalTime);
        checkGLError("After setting uniforms");
        
        // Bind texture atlas once before passes
        bindTextureAtlas();
        checkGLError("After texture binding");
        
        // Update animated textures now that atlas is properly bound
        updateAnimatedTextures(totalTime, player);
        checkGLError("After updateAnimatedWater");
        
        // Get visible chunks
        Map<World.ChunkPosition, Chunk> visibleChunks = getVisibleChunks(world, player);

        // Render opaque pass
        renderOpaquePass(visibleChunks);

        // Render transparent pass  
        renderTransparentPass(visibleChunks, player);
        
        // Restore OpenGL state after passes
        restoreGLStateAfterPasses();

        // Render world-specific overlays and effects
        renderWorldOverlays(player);

        // Render entities first (solid objects should render before transparent ones)
        renderEntities(player);
        
        // Render drops after entities (transparent objects render last)
        renderDrops(player);

        // Render water particles
        renderWaterParticles();

        // Render player arm last (if not paused) to appear in front of entities
        renderPlayerArm(player);
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
        shaderProgram.setUniform("u_useSolidColor", false); // World objects are textured
        shaderProgram.setUniform("u_isText", false);        // World objects are not text
        shaderProgram.setUniform("u_isUIElement", false);   // World objects are not UI elements
    }
    
    /**
     * Bind the texture atlas for world rendering.
     */
    private void bindTextureAtlas() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureAtlas.getTextureId());
        // Ensure texture filtering is set (NEAREST for blocky style)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }
    
    /**
     * Update animated textures.
     */
    private void updateAnimatedTextures(float totalTime, Player player) {
        WaterEffects waterEffects = Game.getWaterEffects();
        textureAtlas.updateAnimatedWater(totalTime, waterEffects, player.getPosition().x, player.getPosition().z);
    }
    
    /**
     * Get visible chunks around the player.
     */
    private Map<World.ChunkPosition, Chunk> getVisibleChunks(World world, Player player) {
        int playerChunkX = (int) Math.floor(player.getPosition().x / WorldConfiguration.CHUNK_SIZE);
        int playerChunkZ = (int) Math.floor(player.getPosition().z / WorldConfiguration.CHUNK_SIZE);
        return world.getChunksAroundPlayer(playerChunkX, playerChunkZ);
    }
    
    /**
     * Render opaque pass (non-water parts of chunks).
     */
    private void renderOpaquePass(Map<World.ChunkPosition, Chunk> visibleChunks) {
        shaderProgram.setUniform("u_renderPass", 0); // 0 for opaque/non-water pass
        glDepthMask(true);  // Enable depth writing for opaque objects
        glDisable(GL_BLEND); // Opaque objects typically don't need blending

        for (Chunk chunk : visibleChunks.values()) {
            chunk.render(); // Shader will discard water fragments
        }
    }
    
    /**
     * Render transparent pass (water parts of chunks).
     */
    private void renderTransparentPass(Map<World.ChunkPosition, Chunk> visibleChunks, Player player) {
        shaderProgram.setUniform("u_renderPass", 1); // 1 for transparent/water pass
        glDepthMask(false); // Disable depth writing for transparent objects
        glEnable(GL_BLEND); // Enable blending
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); // Standard alpha blending

        // Enable polygon offset to prevent Z-fighting with adjacent solid blocks
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(-2.0f, -2.0f); // Stronger offset to eliminate Z-fighting completely

        // Sort chunks from back to front for transparent pass
        sortChunksBackToFront(visibleChunks, player);

        for (Chunk chunk : reusableSortedChunks) {
            chunk.render(); // Shader will discard non-water fragments
        }

        // Disable polygon offset after water rendering
        glDisable(GL_POLYGON_OFFSET_FILL);
    }
    
    /**
     * Sort chunks from back to front for proper transparent rendering.
     */
    private void sortChunksBackToFront(Map<World.ChunkPosition, Chunk> visibleChunks, Player player) {
        reusableSortedChunks.clear();
        reusableSortedChunks.addAll(visibleChunks.values());
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
    }
    
    /**
     * Render world-specific overlays like block crack effects.
     */
    private void renderWorldOverlays(Player player) {
        // Render block crack overlay if breaking a block
        blockRenderer.renderBlockCrackOverlay(player, shaderProgram, projectionMatrix);
    }
    
    /**
     * Render player arm in appropriate game states.
     */
    private void renderPlayerArm(Player player) {
        GameState currentState = Game.getInstance().getState();
        if (currentState == GameState.PLAYING || currentState == GameState.INVENTORY_UI || currentState == GameState.RECIPE_BOOK_UI || currentState == GameState.WORKBENCH_UI) {
            playerArmRenderer.renderPlayerArm(player); // This method binds its own shader and texture
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
     * Render all drops using the drop sub-renderer.
     * Drops are rendered before entities to appear underneath everything but above world geometry.
     */
    private void renderDrops(Player player) {
        com.stonebreak.mobs.entities.EntityManager entityManager = Game.getEntityManager();
        
        if (entityManager != null && dropRenderer != null) {
            // Get all entities and filter for drops
            List<com.stonebreak.mobs.entities.Entity> allEntities = entityManager.getAllEntities();
            java.util.List<com.stonebreak.mobs.entities.Entity> drops = new java.util.ArrayList<>();
            
            for (com.stonebreak.mobs.entities.Entity entity : allEntities) {
                if (entity.isAlive() && isDropEntity(entity)) {
                    drops.add(entity);
                }
            }
            
            // Render all drops at once
            if (!drops.isEmpty()) {
                dropRenderer.renderDrops(drops, shaderProgram, projectionMatrix, player.getViewMatrix());
            }
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
            // Get all entities and render them using the sub-renderer
            for (com.stonebreak.mobs.entities.Entity entity : entityManager.getAllEntities()) {
                if (entity.isAlive() && !isDropEntity(entity)) { // Exclude drops as they're rendered separately
                    entityRenderer.renderEntity(entity, player.getViewMatrix(), projectionMatrix);
                }
            }
        }
    }
    
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
    }
}
