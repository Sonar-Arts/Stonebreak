package com.openmason.rendering;

import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.player.items.voxelization.ColorPalette;
import com.stonebreak.rendering.player.items.voxelization.VoxelMesh;
import com.stonebreak.rendering.player.items.voxelization.SpriteVoxelizer;
import com.stonebreak.rendering.player.items.voxelization.VoxelData;
import com.openmason.item.ItemManager;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * Simple item renderer for Open Mason following the same pattern as BlockRenderer.
 * Leverages the voxelization system to render 3D item representations.
 *
 * Design Principles:
 * - KISS: Simple delegation to ItemManager and VoxelMesh
 * - YAGNI: Only implements basic voxelized item rendering
 * - DRY: Reuses voxelization API, no duplicate code
 * - API Consistency: Mirrors BlockRenderer's interface
 */
public class ItemRenderer implements AutoCloseable {

    private boolean initialized = false;
    private final String debugPrefix;
    private final FloatBuffer matrixBuffer = BufferUtils.createFloatBuffer(16);

    // Statistics
    private long totalRenderCalls = 0;
    private ItemType currentItem = null;

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
     * @param textureLocation Location of texture sampler uniform (unused for items)
     * @param useTextureLocation Location of useTexture flag uniform (unused for items)
     */
    public void renderItem(ItemType itemType, int shaderProgram,
                          int mvpLocation, int modelLocation,
                          float[] vpMatrix, Matrix4f modelMatrix,
                          int textureLocation, int useTextureLocation) {
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

            currentItem = itemType;

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
            ItemManager manager = ItemManager.getInstance();
            SpriteVoxelizer.VoxelizationResult result = SpriteVoxelizer.voxelizeSpriteWithPalette(itemType);

            if (result == null || !result.isValid() || result.getVoxels().isEmpty()) {
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
                float alpha = ((rgba >> 24) & 0xFF) / 255.0f;

                // Set color uniform for this voxel (using vec3 to match shader)
                if (colorLocation != -1) {
                    glUniform3f(colorLocation, red, green, blue);
                }

                // Render this voxel's faces
                int startIndex = i * facesPerVoxel * indicesPerFace;
                glDrawElements(GL_TRIANGLES, facesPerVoxel * indicesPerFace, GL_UNSIGNED_INT, startIndex * Integer.BYTES);
            }

            mesh.unbind();

            // Restore OpenGL state
            glDisable(GL_BLEND);
            glDepthMask(true);

            totalRenderCalls++;

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering voxel mesh: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Renders an item with transparency support.
     * Uses special rendering settings for items with alpha channels.
     *
     * @param itemType The item type to render
     * @param shaderProgram The shader program to use
     * @param mvpLocation Location of MVP matrix uniform
     * @param modelLocation Location of model matrix uniform
     * @param vpMatrix View-projection matrix from camera
     * @param modelMatrix Model transformation matrix
     * @param textureLocation Location of texture sampler uniform
     * @param useTextureLocation Location of useTexture flag uniform
     * @param enableTransparency Whether to enable transparency rendering
     */
    public void renderItemWithTransparency(ItemType itemType, int shaderProgram,
                                          int mvpLocation, int modelLocation,
                                          float[] vpMatrix, Matrix4f modelMatrix,
                                          int textureLocation, int useTextureLocation,
                                          boolean enableTransparency) {
        if (!initialized) {
            throw new IllegalStateException("ItemRenderer not initialized");
        }

        if (itemType == null) {
            System.err.println("[" + debugPrefix + "] Cannot render null item type");
            return;
        }

        try {
            ItemManager manager = ItemManager.getInstance();
            VoxelMesh mesh = manager.getItemMesh(itemType);
            ColorPalette palette = manager.getItemPalette(itemType);

            if (mesh == null || !mesh.isCreated()) {
                return;
            }

            // Use the shader program
            glUseProgram(shaderProgram);

            // Set uniforms
            if (mvpLocation != -1 && vpMatrix != null) {
                glUniformMatrix4fv(mvpLocation, false, vpMatrix);
            }
            if (modelLocation != -1 && modelMatrix != null) {
                modelMatrix.get(matrixBuffer);
                glUniformMatrix4fv(modelLocation, false, matrixBuffer);
            }

            // Bind palette
            if (palette != null) {
                palette.bind();
                if (textureLocation != -1) {
                    glUniform1i(textureLocation, 0);
                }
                if (useTextureLocation != -1) {
                    glUniform1i(useTextureLocation, 1);
                }
            }

            // Special transparency handling
            if (enableTransparency) {
                glEnable(GL_BLEND);
                glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
                glDepthMask(false); // Don't write to depth buffer for transparent objects
                glDisable(GL_CULL_FACE); // Render both sides for transparent objects
            } else {
                glDisable(GL_BLEND);
                glDepthMask(true);
                glEnable(GL_CULL_FACE);
                glCullFace(GL_BACK);
            }

            // Render
            mesh.bind();
            glDrawElements(GL_TRIANGLES, mesh.getIndexCount(), GL_UNSIGNED_INT, 0);
            mesh.unbind();

            // Restore state
            if (enableTransparency) {
                glDisable(GL_BLEND);
                glDepthMask(true);
                glEnable(GL_CULL_FACE);
            }

            currentItem = itemType;
            totalRenderCalls++;

        } catch (Exception e) {
            System.err.println("[" + debugPrefix + "] Error rendering item with transparency: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Gets the currently rendered item type.
     *
     * @return The current item type or null
     */
    public ItemType getCurrentItem() {
        return currentItem;
    }

    /**
     * Gets the total number of render calls.
     *
     * @return Number of render calls
     */
    public long getTotalRenderCalls() {
        return totalRenderCalls;
    }

    /**
     * Resets statistics.
     */
    public void resetStatistics() {
        totalRenderCalls = 0;
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
     * Gets rendering statistics as a string.
     *
     * @return Statistics string
     */
    public String getStatistics() {
        return String.format("[%s] Stats: %d render calls, current item: %s",
            debugPrefix, totalRenderCalls,
            currentItem != null ? currentItem.name() : "none");
    }

    /**
     * Validates that an item can be rendered.
     *
     * @param itemType The item type to validate
     * @return true if the item can be rendered
     */
    public boolean canRender(ItemType itemType) {
        if (!initialized || itemType == null) {
            return false;
        }

        try {
            ItemManager manager = ItemManager.getInstance();
            return manager.validateItem(itemType);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cleanup resources.
     */
    @Override
    public void close() {
        if (initialized) {
            System.out.println("[" + debugPrefix + "] Shutting down ItemRenderer");
            initialized = false;
            currentItem = null;
        }
    }

    /**
     * Static utility to create and initialize an ItemRenderer.
     *
     * @param debugPrefix Debug prefix
     * @return Initialized ItemRenderer
     */
    public static ItemRenderer createAndInitialize(String debugPrefix) {
        ItemRenderer renderer = new ItemRenderer(debugPrefix);
        renderer.initialize();
        return renderer;
    }
}
