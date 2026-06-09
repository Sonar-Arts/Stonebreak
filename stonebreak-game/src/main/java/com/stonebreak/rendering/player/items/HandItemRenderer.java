package com.stonebreak.rendering.player.items;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.core.API.commonBlockResources.resources.CBRResourceManager;
import com.openmason.engine.rendering.cbr.meshing.MeshManager;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.rendering.player.items.voxelization.VoxelizedSpriteRenderer;
import com.stonebreak.rendering.sbo.SBOHandMeshRegistry;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.BlockTextureArray;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL13;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;

/**
 * Handles rendering of items (blocks, tools, materials) in the player's hand.
 *
 * <p>Blocks render as cube meshes textured from the {@link BlockTextureArray};
 * flowers use the SBO's own hand mesh for in-hand/in-world parity; tools render
 * as 3D voxelized sprites.
 */
public class HandItemRenderer {

    private final ShaderProgram shaderProgram;
    private final BlockTextureArray blockTextureArray;
    private final VoxelizedSpriteRenderer voxelizedSpriteRenderer;
    // Optional — held flowers render using the SBO's own geometry for
    // in-hand/in-world parity.
    private final SBOHandMeshRegistry sboHandMeshRegistry;

    /** Per-block-type cube meshes for held blocks, textured from the array. */
    private final Map<BlockType, MeshManager.MeshResource> handCubeMeshes = new HashMap<>();

    public HandItemRenderer(ShaderProgram shaderProgram, BlockTextureArray blockTextureArray,
                            SBOHandMeshRegistry sboHandMeshRegistry) {
        this.shaderProgram = shaderProgram;
        this.blockTextureArray = blockTextureArray;
        this.voxelizedSpriteRenderer = new VoxelizedSpriteRenderer(shaderProgram);
        this.sboHandMeshRegistry = sboHandMeshRegistry;
    }

    /**
     * Renders a block in the player's hand using appropriate rendering method.
     */
    public void renderBlockInHand(BlockType blockType) {
        if (blockType != null && blockType.isFlower()) {
            renderFlowerInHand(blockType);
        } else {
            renderCubeBlockInHand(blockType);
        }
    }

    /**
     * Returns a cached cube mesh for the block, textured from the block texture
     * array (CBR cube face order mapped to MMS face ids).
     */
    private MeshManager.MeshResource getHandCubeMesh(BlockType blockType) {
        return handCubeMeshes.computeIfAbsent(blockType, bt -> {
            float[] faceLayers = {
                blockTextureArray.getBlockFaceLayer(bt, 3), // front +Z
                blockTextureArray.getBlockFaceLayer(bt, 2), // back -Z
                blockTextureArray.getBlockFaceLayer(bt, 5), // left -X
                blockTextureArray.getBlockFaceLayer(bt, 4), // right +X
                blockTextureArray.getBlockFaceLayer(bt, 0), // top +Y
                blockTextureArray.getBlockFaceLayer(bt, 1)  // bottom -Y
            };
            return CBRResourceManager.getInstance().getMeshManager()
                    .createCubeMeshWithLayers("hand_cube_" + bt.name(), faceLayers);
        });
    }

    /** Binds the block texture array on unit 1 for array-textured hand geometry. */
    private void bindArray() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        blockTextureArray.bind();
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        shaderProgram.setUniform("block_sampler", 1);
        shaderProgram.setUniform("u_useTextureArray", true);
    }

    /**
     * Renders a regular block as a 3D cube in the hand.
     */
    private void renderCubeBlockInHand(BlockType blockType) {
        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_isUIElement", true);
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        bindArray();

        boolean isLeafBlock = (blockType == BlockType.LEAVES || blockType == BlockType.PINE_LEAVES || blockType == BlockType.ELM_LEAVES);
        if (isLeafBlock && blockType.isTransparent()) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }

        MeshManager.MeshResource mesh = getHandCubeMesh(blockType);
        mesh.bind();
        glDrawElements(GL_TRIANGLES, mesh.getIndexCount(), GL_UNSIGNED_INT, 0);
        mesh.unbind();

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        shaderProgram.setUniform("u_isUIElement", false);
        shaderProgram.setUniform("u_useTextureArray", false);
    }

    /**
     * Renders flowers as a cross pattern in the player's hand, using the SBO's
     * own geometry so in-hand matches in-world.
     */
    private void renderFlowerInHand(BlockType flowerType) {
        MeshManager.MeshResource sboMesh = sboHandMeshRegistry != null
                ? sboHandMeshRegistry.getMesh(flowerType)
                : null;
        if (sboMesh == null) {
            return; // No SBO hand mesh — nothing to draw.
        }

        shaderProgram.setUniform("u_useSolidColor", false);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);
        shaderProgram.setUniform("u_isUIElement", true);
        shaderProgram.setUniform("u_color", new Vector4f(1.0f, 1.0f, 1.0f, 1.0f));
        bindArray();
        // Flower cross meshes have no per-vertex alpha flag — force alpha test.
        shaderProgram.setUniform("u_forceAlphaTest", true);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        sboMesh.bind();
        glDrawElements(GL_TRIANGLES, sboMesh.getIndexCount(), GL_UNSIGNED_INT, 0);
        sboMesh.unbind();

        shaderProgram.setUniform("u_forceAlphaTest", false);
        shaderProgram.setUniform("u_isUIElement", false);
        shaderProgram.setUniform("u_useTextureArray", false);
    }

    /**
     * Renders tools in the player's hand as 3D voxelized sprites.
     */
    public void renderToolInHand(ItemType itemType) {
        renderToolInHand(itemType, null);
    }

    /**
     * State-aware variant — picks the OMT for the given SBO state name.
     */
    public void renderToolInHand(ItemType itemType, String state) {
        voxelizedSpriteRenderer.renderVoxelizedSprite(itemType, state);
    }

    /**
     * Preloads voxel meshes for all supported items.
     */
    public void preloadVoxelMeshes() {
        voxelizedSpriteRenderer.preloadAllVoxelMeshes();
    }

    /**
     * Gets statistics about the voxelized sprite renderer.
     */
    public String getVoxelizationStatistics() {
        return voxelizedSpriteRenderer.getStatistics();
    }

    /**
     * Checks if an item uses voxelized rendering.
     */
    public boolean usesVoxelizedRendering(ItemType itemType) {
        return SpriteVoxelizer.isVoxelizable(itemType);
    }

    /**
     * Cleanup resources when the renderer is destroyed.
     */
    public void cleanup() {
        voxelizedSpriteRenderer.cleanup();
    }
}
