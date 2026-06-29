package com.stonebreak.rendering.models.entities;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.openmason.engine.rendering.cbr.meshing.MeshManager;
import com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.BlockTextureArray;
import com.stonebreak.rendering.sbo.SBOHandMeshRegistry;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Specialized renderer for block and item drops in the world.
 * Uses the CBR API and BlockRenderer for 3D block drops, and the voxelization system for 3D item drops.
 */
public class DropRenderer {
    
    private final BlockRenderer blockRenderer;
    private final BlockTextureArray blockTextureArray;
    private final SBOHandMeshRegistry sboHandMeshRegistry;
    private CBRResourceManager cbrManager;
    private final VoxelizedSpriteRenderer voxelizedSpriteRenderer;

    /** Per-block-type cube meshes for drops, textured from the block texture array. */
    private final java.util.Map<BlockType, MeshManager.MeshResource> dropCubeMeshes = new java.util.HashMap<>();

    private boolean initialized = false;
    
    // Reusable matrices to avoid allocations
    private final Matrix4f dropModelMatrix = new Matrix4f();
    private final Matrix4f rotationMatrix = new Matrix4f();
    
    /**
     * Creates a DropRenderer with the required dependencies.
     */
    public DropRenderer(BlockRenderer blockRenderer, BlockTextureArray blockTextureArray,
                        SBOHandMeshRegistry sboHandMeshRegistry, ShaderProgram shaderProgram) {
        this.blockRenderer = blockRenderer;
        this.blockTextureArray = blockTextureArray;
        this.sboHandMeshRegistry = sboHandMeshRegistry;
        this.cbrManager = blockRenderer.getCBRResourceManager();
        this.voxelizedSpriteRenderer = new VoxelizedSpriteRenderer(shaderProgram);
        initialize();
    }
    
    /**
     * Initialize the drop renderer by creating necessary resources.
     */
    private void initialize() {
        if (initialized) return;

        // Preload voxel meshes for all supported items to reduce hitches during gameplay
        voxelizedSpriteRenderer.preloadAllVoxelMeshes();
        initialized = true;
    }
    
    /**
     * Renders opaque block/item drops. Call this BEFORE the transparent water pass
     * so that drops write depth values and can be occluded by water.
     */
    public void renderOpaqueDrops(List<Entity> drops, ShaderProgram shaderProgram, Matrix4f projectionMatrix, Matrix4f viewMatrix,
                                  World world, Vector3f cameraPos) {
        if (drops == null || drops.isEmpty()) return;

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("u_renderPass", 0);
        shaderProgram.setUniform("u_translucentLayer", -1);
        shaderProgram.setUniform("u_waterDepthOffset", 0.0f);
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("u_isText", false);

        // Underwater fog
        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(0.1f, 0.3f, 0.5f);
        if (world != null && cameraPos != null) {
            int camX = (int) Math.floor(cameraPos.x);
            int camY = (int) Math.floor(cameraPos.y);
            int camZ = (int) Math.floor(cameraPos.z);
            if (world.isPositionUnderwater(camX, camY, camZ)) fogDensity = 0.15f;
        }
        shaderProgram.setUniform("u_cameraPos", cameraPos != null ? cameraPos : new Vector3f(0, 0, 0));
        shaderProgram.setUniform("u_underwaterFogDensity", fogDensity);
        shaderProgram.setUniform("u_underwaterFogColor", fogColor);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        blockTextureArray.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);  // Write depth so water can occlude drops

        for (Entity drop : drops) {
            if (!drop.isAlive()) continue;
            boolean shouldRender = true;
            if (drop instanceof com.stonebreak.mobs.entities.BlockDrop bd) shouldRender = bd.shouldRender();
            else if (drop instanceof com.stonebreak.mobs.entities.ItemDrop id) shouldRender = id.shouldRender();
            if (!shouldRender) continue;

            // Opaque block drops + all item drops go in this pass
            if (drop instanceof com.stonebreak.mobs.entities.BlockDrop bd) {
                BlockType bt = bd.getBlockType();
                if (bt != null && isTransparentBlock(bt)) continue; // skip transparent, handled by renderTransparentDrops
                glDisable(GL_BLEND);
                renderDrop(drop, shaderProgram, viewMatrix, world);
            } else if (drop instanceof com.stonebreak.mobs.entities.ItemDrop) {
                // Item drops: voxelized uses u_useSolidColor (ignores render pass), fallback uses u_isUIElement
                glDisable(GL_BLEND);
                renderDrop(drop, shaderProgram, viewMatrix, world);
            }
        }

        // Restore view matrix (renderDrop overwrites it with view*model per drop)
        shaderProgram.setUniform("viewMatrix", viewMatrix);

        // Restore state
        glDepthMask(true);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_isUIElement", false);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
    }

    /**
     * Renders transparent block drops. Call this AFTER the transparent water pass.
     */
    public void renderTransparentDrops(List<Entity> drops, ShaderProgram shaderProgram, Matrix4f projectionMatrix, Matrix4f viewMatrix,
                                       World world, Vector3f cameraPos) {
        if (drops == null || drops.isEmpty()) return;

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("u_renderPass", 0);
        shaderProgram.setUniform("u_translucentLayer", -1);
        shaderProgram.setUniform("u_waterDepthOffset", 0.0f);
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("u_isText", false);

        // Underwater fog
        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(0.1f, 0.3f, 0.5f);
        if (world != null && cameraPos != null) {
            int camX = (int) Math.floor(cameraPos.x);
            int camY = (int) Math.floor(cameraPos.y);
            int camZ = (int) Math.floor(cameraPos.z);
            if (world.isPositionUnderwater(camX, camY, camZ)) fogDensity = 0.15f;
        }
        shaderProgram.setUniform("u_cameraPos", cameraPos != null ? cameraPos : new Vector3f(0, 0, 0));
        shaderProgram.setUniform("u_underwaterFogDensity", fogDensity);
        shaderProgram.setUniform("u_underwaterFogColor", fogColor);

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        blockTextureArray.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        for (Entity drop : drops) {
            if (!(drop instanceof com.stonebreak.mobs.entities.BlockDrop bd)) continue;
            if (!drop.isAlive() || !bd.shouldRender()) continue;
            BlockType bt = bd.getBlockType();
            if (bt == null || !isTransparentBlock(bt)) continue; // only transparent blocks

            renderDrop(drop, shaderProgram, viewMatrix, world);
        }

        // Restore view matrix (renderDrop overwrites it with view*model per drop)
        shaderProgram.setUniform("viewMatrix", viewMatrix);

        // Restore state
        glDepthMask(true);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_isUIElement", false);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
    }

    /**
     * Renders all drops in the world. This method should be called before UI rendering
     * to ensure drops render underneath the UI.
     *
     * @deprecated Use {@link #renderOpaqueDrops} and {@link #renderTransparentDrops} instead
     * to ensure proper depth-buffer interaction with water rendering.
     */
    @Deprecated
    public void renderDrops(List<Entity> drops, ShaderProgram shaderProgram, Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        renderDrops(drops, shaderProgram, projectionMatrix, viewMatrix, null, null);
    }

    /**
     * Renders all drops in the world with underwater fog support.
     * @param drops List of drop entities to render
     * @param shaderProgram Shader program to use
     * @param projectionMatrix Projection matrix
     * @param viewMatrix View matrix
     * @param world World instance for underwater detection (can be null)
     * @param cameraPos Camera position for fog distance calculation (can be null)
     */
    public void renderDrops(List<Entity> drops, ShaderProgram shaderProgram, Matrix4f projectionMatrix, Matrix4f viewMatrix,
                           World world, Vector3f cameraPos) {
        if (drops == null || drops.isEmpty()) {
            return;
        }

        // Ensure shader is bound
        shaderProgram.bind();

        // Set common uniforms
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("texture_sampler", 0);
        // Block-drop CBR meshes carry legacy atlas UVs (no texture-array layer),
        // so they sample the 2D atlas, not the block texture array.
        shaderProgram.setUniform("u_useTextureArray", false);
        shaderProgram.setUniform("u_isText", false);

        // Calculate underwater fog parameters once for all drops
        float fogDensity = 0.0f;
        Vector3f fogColor = new Vector3f(0.1f, 0.3f, 0.5f); // Blue-cyan water color

        if (world != null && cameraPos != null) {
            int camX = (int) Math.floor(cameraPos.x);
            int camY = (int) Math.floor(cameraPos.y);
            int camZ = (int) Math.floor(cameraPos.z);
            boolean cameraUnderwater = world.isPositionUnderwater(camX, camY, camZ);

            // Apply fog if camera is underwater (affects all drops)
            if (cameraUnderwater) {
                fogDensity = 0.15f; // Moderate fog density for nice underwater effect
            }
        }

        // Set underwater fog uniforms (applied to all drops)
        shaderProgram.setUniform("u_cameraPos", cameraPos != null ? cameraPos : new Vector3f(0, 0, 0));
        shaderProgram.setUniform("u_underwaterFogDensity", fogDensity);
        shaderProgram.setUniform("u_underwaterFogColor", fogColor);

        // Bind the block texture array on unit 1 for block-drop cubes.
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        blockTextureArray.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);

        // Enable depth testing for proper rendering
        glEnable(GL_DEPTH_TEST);

        // Note: Blending will be handled per-drop based on transparency requirements

        // Render each drop (skip compressed ones)
        for (Entity drop : drops) {
            if (drop.isAlive() && isDropEntity(drop)) {
                // Only render if it's not compressed into another drop
                boolean shouldRender = true;
                if (drop instanceof com.stonebreak.mobs.entities.BlockDrop blockDrop) {
                    shouldRender = blockDrop.shouldRender();
                } else if (drop instanceof com.stonebreak.mobs.entities.ItemDrop itemDrop) {
                    shouldRender = itemDrop.shouldRender();
                }

                if (shouldRender) {
                    renderDrop(drop, shaderProgram, viewMatrix, world);
                }
            }
        }

        // Clean up state - restore blending for UI elements
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);

        // Reset UI element mode back to false for world rendering
        shaderProgram.setUniform("u_isUIElement", false);
    }
    
    /**
     * Render the held item of every visible player-shaped entity at its hand.
     * Dispatches on the entity's resolved {@link com.stonebreak.items.Item}:
     * blocks render as a textured cube, flowers as their SBO cross mesh, and
     * tools/items as 3D voxelized sprites — the same three forms the first-person
     * hand uses, so a decoy mirrors whatever the caster is holding.
     *
     * <p>This is intentionally separate from {@link #renderDrops} so callers
     * can sequence the two passes (drops first, held items second) and so the
     * remote-player list isn't dragged through the drop iteration.
     */
    public void renderHeldItems(java.util.List<com.stonebreak.mobs.entities.RemotePlayer> players,
                                 ShaderProgram shaderProgram,
                                 Matrix4f projectionMatrix, Matrix4f viewMatrix,
                                 World world, Vector3f cameraPos) {
        if (players == null || players.isEmpty() || cbrManager == null) return;

        shaderProgram.bind();
        shaderProgram.setUniform("projectionMatrix", projectionMatrix);
        shaderProgram.setUniform("u_renderPass", 0);
        shaderProgram.setUniform("u_translucentLayer", -1);
        shaderProgram.setUniform("texture_sampler", 0);
        shaderProgram.setUniform("u_useTextureArray", false); // CBR meshes use the 2D atlas
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_cameraPos", cameraPos != null ? cameraPos : new Vector3f(0, 0, 0));
        shaderProgram.setUniform("u_underwaterFogDensity", 0.0f);
        shaderProgram.setUniform("u_underwaterFogColor", new Vector3f(0.1f, 0.3f, 0.5f));

        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        blockTextureArray.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        glEnable(GL_DEPTH_TEST);

        // Hand offset, in player local space (right, up, forward).
        final float HAND_RIGHT   = 0.35f;
        final float HAND_UP      = 1.2f;
        final float HAND_FORWARD = 0.15f;
        final float HAND_SCALE   = 0.30f;
        // Voxelized tools reuse the world-drop pipeline, which renders at scale 0.25.
        final float TOOL_SCALE   = 0.25f;

        for (com.stonebreak.mobs.entities.RemotePlayer rp : players) {
            if (!rp.isAlive()) continue;
            com.stonebreak.items.Item held = rp.getHeldItem();
            if (held == null) continue;

            Vector3f pos = rp.getPosition();
            float yawRad = (float) Math.toRadians(rp.getRotation().y);
            float cos = (float) Math.cos(yawRad);
            float sin = (float) Math.sin(yawRad);
            // World vectors: forward = (-sin, 0, -cos), right = (cos, 0, -sin).
            float worldDx = HAND_RIGHT * cos + HAND_FORWARD * (-sin);
            float worldDz = HAND_RIGHT * (-sin) + HAND_FORWARD * (-cos);
            float handX = pos.x + worldDx;
            float handY = pos.y + HAND_UP;
            float handZ = pos.z + worldDz;

            if (held instanceof BlockType blockType && blockType != BlockType.AIR) {
                boolean isFlower = blockType.isFlower() && sboHandMeshRegistry != null
                        && sboHandMeshRegistry.getMesh(blockType) != null;
                dropModelMatrix.identity()
                        .translate(handX, handY, handZ)
                        .rotateY(yawRad)
                        .scale(HAND_SCALE);
                shaderProgram.setUniform("viewMatrix", new Matrix4f(viewMatrix).mul(dropModelMatrix));

                MeshManager.MeshResource mesh = isFlower
                        ? sboHandMeshRegistry.getMesh(blockType)
                        : getDropCubeMesh(blockType);
                boolean isTransparent = isTransparentBlock(blockType);
                if (isFlower || isTransparent) {
                    glEnable(GL_BLEND);
                    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                } else {
                    glDisable(GL_BLEND);
                }
                glDepthMask(!isTransparent);
                shaderProgram.setUniform("u_useSolidColor", false);
                shaderProgram.setUniform("u_isUIElement", true);
                shaderProgram.setUniform("u_transformUVsForItem", false);
                shaderProgram.setUniform("u_useTextureArray", true);
                // Flower cross meshes have no per-vertex alpha flag — force alpha test.
                shaderProgram.setUniform("u_forceAlphaTest", isFlower);
                float alpha = isTransparent ? 0.95f : 1.0f;
                shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, alpha));

                mesh.bind();
                glDrawElements(GL_TRIANGLES, mesh.getIndexCount(), GL_UNSIGNED_INT, 0);
                mesh.unbind();
                shaderProgram.setUniform("u_forceAlphaTest", false);
                glDepthMask(true);
            } else if (held instanceof ItemType itemType && SpriteVoxelizer.isVoxelizable(itemType)) {
                // Voxelized tool/item: position the same world-space sprite pipeline drops use
                // at the hand (no spin), then let the drop helper drive the voxel render.
                dropModelMatrix.identity()
                        .translate(handX, handY, handZ)
                        .rotateY(yawRad)
                        .scale(TOOL_SCALE);
                shaderProgram.setUniform("viewMatrix", new Matrix4f(viewMatrix).mul(dropModelMatrix));
                glDisable(GL_BLEND);
                glDepthMask(true);
                renderVoxelizedItemDrop(itemType, null);
            }
        }

        // Restore shared shader state — the loop overwrites viewMatrix per player and tweaks
        // u_color/u_useTextureArray/u_useSolidColor/u_isUIElement/depthMask. The subsequent
        // chunk transparent pass relies on viewMatrix being the camera view AND
        // u_useTextureArray=true (it does not re-bind either). If we leave the per-hand
        // model-view, water/ice/glass renders transformed by it. If we leave u_useTextureArray
        // false (the function sets it false at entry and only re-sets to true INSIDE the loop
        // body, so a run with no held items leaks the entry-time false), the chunk shader
        // samples the wrong texture and water renders black — visible as a flicker that
        // correlates with what remote players are holding.
        shaderProgram.setUniform("viewMatrix", viewMatrix);
        glDepthMask(true);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        GL30.glBindVertexArray(0);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_isUIElement", false);
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_useTextureArray", true);
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
    }

    /**
     * Renders a single drop entity.
     */
    private void renderDrop(Entity drop, ShaderProgram shaderProgram, Matrix4f viewMatrix, World world) {
        // Create model matrix for the drop
        Vector3f dropPos = drop.getPosition();
        float dropAge = drop.getAge();

        // Apply bobbing animation
        float bobOffset = (float) Math.sin(dropAge * 2.0f) * 0.1f;

        if (isItemDrop(drop)) {
            // For 3D voxelized items, create standard transformation with spinning rotation
            float rotationY = dropAge * 30.0f; // Slower rotation for voxelized items

            dropModelMatrix.identity()
                .translate(dropPos.x, dropPos.y + bobOffset, dropPos.z)
                .scale(0.25f); // Same size as blocks for consistency

            // Apply rotation for spinning effect
            rotationMatrix.identity().rotateY((float) Math.toRadians(rotationY));
            dropModelMatrix.mul(rotationMatrix);
        } else {
            // For block drops, keep spinning rotation
            float rotationY = dropAge * 50.0f; // Rotate 50 degrees per second

            dropModelMatrix.identity()
                .translate(dropPos.x, dropPos.y + bobOffset, dropPos.z)
                .scale(0.25f); // Make drops smaller than full blocks

            // Apply rotation for spinning effect
            rotationMatrix.identity().rotateY((float) Math.toRadians(rotationY));
            dropModelMatrix.mul(rotationMatrix);
        }

        // Combine with view matrix
        Matrix4f modelViewMatrix = new Matrix4f(viewMatrix).mul(dropModelMatrix);
        shaderProgram.setUniform("viewMatrix", modelViewMatrix);

        // Render based on drop type
        if (isBlockDrop(drop)) {
            renderBlockDrop(drop, shaderProgram);
        } else if (isItemDrop(drop)) {
            renderItemDrop(drop, shaderProgram);
        }
    }
    
    /**
     * Renders a block drop using the CBR API and BlockRenderer.
     * Depth mask and blending are controlled by the caller (renderOpaqueDrops/renderTransparentDrops).
     */
    private void renderBlockDrop(Entity drop, ShaderProgram shaderProgram) {
        BlockType blockType = getBlockTypeFromDrop(drop);
        if (blockType == null || blockType == BlockType.AIR) {
            return;
        }
        if (cbrManager == null) {
            System.err.println("[DropRenderer] CBR not available for block drop " + blockType);
            return;
        }

        // Flowers render as their SBO cross geometry; other blocks as cubes.
        boolean isFlowerMesh = blockType.isFlower() && sboHandMeshRegistry != null
                && sboHandMeshRegistry.getMesh(blockType) != null;
        MeshManager.MeshResource mesh = isFlowerMesh
                ? sboHandMeshRegistry.getMesh(blockType)
                : getDropCubeMesh(blockType);

        // Note: blending and depth mask are now controlled by the caller
        // (renderOpaqueDrops sets glDepthMask(true)/blend OFF, renderTransparentDrops sets glDepthMask(false)/blend ON)

        // Set shader uniforms for block rendering
        shaderProgram.setUniform("u_useSolidColor", false);

        // Enable UI element mode for consistent lighting with hotbar icons
        shaderProgram.setUniform("u_isUIElement", true);

        // Mesh carries tile-local UVs; layers select the array texture.
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_useTextureArray", true);
        // Flower cross meshes have no per-vertex alpha flag — force alpha test.
        shaderProgram.setUniform("u_forceAlphaTest", isFlowerMesh);

        // Set color - full opacity for opaque blocks, slight transparency for transparent blocks
        float alpha = isTransparentBlock(blockType) ? 0.95f : 1.0f;
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, alpha));

        // Render the block mesh from the block texture array.
        mesh.bind();
        glDrawElements(GL_TRIANGLES, mesh.getIndexCount(), GL_UNSIGNED_INT, 0);
        mesh.unbind();

        shaderProgram.setUniform("u_forceAlphaTest", false);
        // Restore depth writes for subsequent rendering
        glDepthMask(true);
    }

    /**
     * Returns a cached cube mesh for the block type, textured from the block
     * texture array. Built lazily on first use.
     */
    private MeshManager.MeshResource getDropCubeMesh(BlockType blockType) {
        return dropCubeMeshes.computeIfAbsent(blockType, bt -> {
            // CBR cube face order: front(+Z), back(-Z), left(-X), right(+X), top(+Y), bottom(-Y)
            // mapped to MMS face ids: south(3), north(2), west(5), east(4), top(0), bottom(1).
            float[] faceLayers = {
                blockTextureArray.getBlockFaceLayer(bt, 3),
                blockTextureArray.getBlockFaceLayer(bt, 2),
                blockTextureArray.getBlockFaceLayer(bt, 5),
                blockTextureArray.getBlockFaceLayer(bt, 4),
                blockTextureArray.getBlockFaceLayer(bt, 0),
                blockTextureArray.getBlockFaceLayer(bt, 1)
            };
            return cbrManager.getMeshManager()
                    .createCubeMeshWithLayers("drop_cube_" + bt.name(), faceLayers);
        });
    }
    
    /**
     * Renders an item drop as a 3D voxelized representation.
     */
    private void renderItemDrop(Entity drop, ShaderProgram shaderProgram) {
        ItemType itemType = getItemTypeFromDrop(drop);
        if (itemType == null) {
            return;
        }

        // Pull SBO state from the drop's stack so multi-state items (e.g. filled
        // wooden bucket) render the correct variant instead of the default.
        String state = null;
        if (drop instanceof com.stonebreak.mobs.entities.ItemDrop itemDrop) {
            com.stonebreak.items.ItemStack stack = itemDrop.getItemStack();
            if (stack != null) state = stack.getState();
        }

        // All items are SBO-backed and render via the voxelization system.
        if (SpriteVoxelizer.isVoxelizable(itemType)) {
            renderVoxelizedItemDrop(itemType, state);
        }
    }

    /**
     * Renders a voxelized item drop with proper positioning for world drops.
     * Uses instance-specific transform adjustments to avoid interfering with hand-held item rendering.
     */
    private void renderVoxelizedItemDrop(ItemType itemType, String state) {
        // Save current instance transform settings
        Vector3f originalTranslation = voxelizedSpriteRenderer.getInstanceTranslationAdjustment();
        Vector3f originalRotation = voxelizedSpriteRenderer.getInstanceRotationAdjustment();
        float originalScale = voxelizedSpriteRenderer.getInstanceScaleAdjustment();

        // Apply drop-specific adjustments to counteract hand-held positioning
        // VoxelizedSpriteRenderer has BASE_TRANSLATION.y = -1.1f for hand positioning
        // We need to lift item drops higher so they don't sink into blocks
        float dropYOffset = 1.3f; // Compensate for base translation + extra lift for floating
        voxelizedSpriteRenderer.adjustInstanceTransform(
            0.0f, dropYOffset, 0.0f,     // Translation: lift Y position
            0.0f, 0.0f, 0.0f,            // Rotation: no additional rotation needed
            1.0f                         // Scale: keep same scale
        );

        try {
            // Render with drop-specific settings
            voxelizedSpriteRenderer.renderVoxelizedSprite(itemType, state);
        } finally {
            // Always restore original settings - this now only affects this instance
            voxelizedSpriteRenderer.adjustInstanceTransform(
                originalTranslation.x, originalTranslation.y, originalTranslation.z,
                originalRotation.x, originalRotation.y, originalRotation.z,
                originalScale
            );
        }
    }

    /**
     * Helper methods to determine drop entity types and extract data.
     * These would need to be implemented based on the actual drop entity structure.
     */
    
    private boolean isDropEntity(Entity entity) {
        return entity instanceof com.stonebreak.mobs.entities.BlockDrop || 
               entity instanceof com.stonebreak.mobs.entities.ItemDrop;
    }
    
    private boolean isBlockDrop(Entity drop) {
        return drop instanceof com.stonebreak.mobs.entities.BlockDrop;
    }
    
    private boolean isItemDrop(Entity drop) {
        return drop instanceof com.stonebreak.mobs.entities.ItemDrop;
    }
    
    private BlockType getBlockTypeFromDrop(Entity drop) {
        if (drop instanceof com.stonebreak.mobs.entities.BlockDrop blockDrop) {
            return blockDrop.getBlockType();
        }
        return BlockType.AIR; // Default fallback
    }
    
    private ItemType getItemTypeFromDrop(Entity drop) {
        if (drop instanceof com.stonebreak.mobs.entities.ItemDrop itemDrop) {
            return itemDrop.getItemType();
        }
        return null; // No item type
    }
    
    /**
     * Determines if a block type should be considered transparent for rendering purposes.
     * Transparent blocks need special depth handling to render properly.
     * Uses the same logic as BlockType.isTransparent() to respect leaf transparency setting.
     */
    private boolean isTransparentBlock(BlockType blockType) {
        // Use the BlockType's isTransparent() method which handles leaf transparency setting
        return blockType.isTransparent();
    }
    
    /**
     * Gets statistics about the voxelized item drop rendering.
     */
    public String getVoxelizationStatistics() {
        return voxelizedSpriteRenderer.getStatistics();
    }

    /**
     * Checks if an item type uses voxelized rendering for drops.
     */
    public boolean usesVoxelizedRendering(ItemType itemType) {
        return SpriteVoxelizer.isVoxelizable(itemType);
    }

    /**
     * Tests the drop rendering system including voxelization.
     */
    public void testDropRendering() {
        System.out.println("=== Drop Renderer Test ===");

        // Test voxelization system
        voxelizedSpriteRenderer.testVoxelizedRendering();

        // Test positioning adjustments for voxelized items
        System.out.println("\nTesting voxelized item drop positioning:");
        System.out.println("  Base VoxelizedSpriteRenderer Y offset: " + VoxelizedSpriteRenderer.getBaseTranslation().y + "f");
        System.out.println("  Drop Y compensation offset: 1.3f");
        System.out.println("  Final effective Y offset for drops: " + (VoxelizedSpriteRenderer.getBaseTranslation().y + 1.3f) + "f");
        System.out.println("  Result: Item drops should float ~0.2f units above their base position");
        System.out.println("  Using instance-specific transforms to avoid interference with hand-held items");

        // Report which items use which rendering method
        System.out.println("\nItem Drop Rendering Methods:");
        for (ItemType itemType : ItemType.values()) {
            if (usesVoxelizedRendering(itemType)) {
                System.out.println("  " + itemType.getName() + ": 3D Voxelized (with positioning adjustment)");
            } else {
                System.out.println("  " + itemType.getName() + ": 2D Billboard Fallback");
            }
        }

        System.out.println("=== Drop Renderer Test Complete ===");
    }

    /**
     * Cleanup OpenGL resources.
     */
    public void cleanup() {
        if (voxelizedSpriteRenderer != null) {
            voxelizedSpriteRenderer.cleanup();
        }
    }
}