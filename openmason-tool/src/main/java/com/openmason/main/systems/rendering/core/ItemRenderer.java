package com.openmason.main.systems.rendering.core;

import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.player.items.voxelization.VoxelMesh;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.rendering.player.items.voxelization.VoxelData;
import com.openmason.main.systems.rendering.model.item.ItemManager;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Simple item renderer for Open Mason following the same pattern as BlockRenderer.
 * Leverages the voxelization system to render 3D item representations.
 */
public class ItemRenderer implements AutoCloseable {

    private boolean initialized = false;
    private final String debugPrefix;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    /**
     * Creates a new ItemRenderer.
     *
     * @param debugPrefix Prefix for debug logging
     */
    public ItemRenderer(String debugPrefix) {
        this.debugPrefix = debugPrefix != null ? debugPrefix : "ItemRenderer";
    }

    /**
     * Initialize the renderer. Must be called before rendering.
     */
    public void initialize() {
        if (initialized) {
            return;
        }

        if (!ItemManager.isInitialized()) {
            ItemManager.initialize();
        }

        initialized = true;
        System.out.println("[" + debugPrefix + "] ItemRenderer initialized");
    }

    /**
     * Renders an item using its ItemType.
     * This is the main rendering method, mirroring BlockRenderer.renderBlock().
     * Uses per-voxel color rendering to match stonebreak's approach.
     *
     * @param itemType The item type to render
     * @param shaderProgram The shader program to use
     * @param mvpLocation Location of MVP matrix uniform
     * @param modelLocation Location of model matrix uniform
     * @param vpMatrix View-projection matrix from camera
     * @param modelMatrix Model transformation matrix
     * @param useTextureLocation Location of useTexture flag uniform (unused for items)
     */
    public void renderItem(ItemType itemType, int shaderProgram,
                          int mvpLocation, int modelLocation,
                          float[] vpMatrix, Matrix4f modelMatrix,
                           int useTextureLocation) {
        if (!initialized) {
            throw new IllegalStateException("ItemRenderer not initialized");
        }

        if (itemType == null) {
            System.err.println("[" + debugPrefix + "] Cannot render null item type");
            return;
        }

        try {
            // Get voxelized mesh from ItemManager
            ItemManager manager = ItemManager.getInstance();
            VoxelMesh mesh = manager.getItemMesh(itemType);

            if (mesh == null || !mesh.isCreated()) {
                System.err.println("[" + debugPrefix + "] No mesh found for item: " + itemType);
                return;
            }

            // Get color uniform location from shader
            int colorLocation = glGetUniformLocation(shaderProgram, "uColor");

            // Disable texture mode for solid color rendering
            if (useTextureLocation != -1) {
                glUseProgram(shaderProgram);
                glUniform1i(useTextureLocation, 0); // Disable texture, use solid colors
            }

            // Render using per-voxel colors (matching stonebreak's approach)
            renderVoxelMeshWithColors(itemType, mesh, shaderProgram, mvpLocation, modelLocation,
                                     vpMatrix, modelMatrix, colorLocation);

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering item " + itemType + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Renders a voxelized mesh directly using per-voxel color rendering.
     * This matches stonebreak's approach of rendering each voxel individually with solid colors.
     *
     * @param itemType The item type being rendered
     * @param mesh The voxel mesh to render
     * @param shaderProgram The shader program to use
     * @param mvpLocation Location of MVP matrix uniform
     * @param modelLocation Location of model matrix uniform
     * @param vpMatrix View-projection matrix from camera
     * @param modelMatrix Model transformation matrix
     * @param colorLocation Location of color uniform for per-voxel colors
     */
    private void renderVoxelMeshWithColors(ItemType itemType, VoxelMesh mesh,
                                          int shaderProgram,
                                          int mvpLocation, int modelLocation,
                                          float[] vpMatrix, Matrix4f modelMatrix,
                                          int colorLocation) {
        if (!initialized) {
            throw new IllegalStateException("ItemRenderer not initialized");
        }

        if (mesh == null || !mesh.isCreated()) {
            return;
        }

        try {
            // Use the shader program
            glUseProgram(shaderProgram);

            // Set view-projection matrix uniform
            if (mvpLocation != -1 && vpMatrix != null) {
                glUniformMatrix4fv(mvpLocation, false, vpMatrix);
            }

            // Set model transformation matrix uniform
            if (modelLocation != -1 && modelMatrix != null) {
                modelMatrix.get(matrixBuffer);
                glUniformMatrix4fv(modelLocation, false, matrixBuffer);
            }

            // Setup OpenGL state for voxel rendering
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            glEnable(GL_CULL_FACE);
            glCullFace(GL_BACK);

            // Get voxel data from ItemManager
            SpriteVoxelizer.VoxelizationResult result = SpriteVoxelizer.voxelizeSpriteWithPalette(itemType);

            if (!result.isValid() || result.getVoxels().isEmpty()) {
                System.err.println("[" + debugPrefix + "] No voxel data available for " + itemType);
                return;
            }

            // Bind the mesh once
            mesh.bind();

            // Render each voxel individually with its own color (matching stonebreak's approach)
            java.util.List<VoxelData> voxels = result.getVoxels();
            int facesPerVoxel = 6;
            int indicesPerFace = 6;

            for (int i = 0; i < voxels.size(); i++) {
                VoxelData voxel = voxels.get(i);

                // Extract RGBA color from voxel
                int rgba = voxel.getOriginalRGBA();
                float red = ((rgba >> 16) & 0xFF) / 255.0f;
                float green = ((rgba >> 8) & 0xFF) / 255.0f;
                float blue = (rgba & 0xFF) / 255.0f;

                // Set color uniform for this voxel (using vec3 to match shader)
                if (colorLocation != -1) {
                    glUniform3f(colorLocation, red, green, blue);
                }

                // Render this voxel's faces
                int startIndex = i * facesPerVoxel * indicesPerFace;
                glDrawElements(GL_TRIANGLES, facesPerVoxel * indicesPerFace, GL_UNSIGNED_INT, (long) startIndex * Integer.BYTES);
            }

            mesh.unbind();

            // Restore OpenGL state
            glDisable(GL_BLEND);
            glDepthMask(true);

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering voxel mesh: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Checks if the renderer is initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Cleanup resources.
     */
    @Override
    public void close() {
        if (initialized) {
            System.out.println("[" + debugPrefix + "] Shutting down ItemRenderer");
            initialized = false;
        }
    }

}
