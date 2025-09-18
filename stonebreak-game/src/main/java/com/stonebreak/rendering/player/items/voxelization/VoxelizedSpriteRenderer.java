package com.stonebreak.rendering.player.items.voxelization;

import com.stonebreak.items.ItemType;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import org.joml.Vector4f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles rendering of voxelized 3D sprite representations.
 * Manages mesh caching and OpenGL rendering state for 3D sprite projections.
 */
public class VoxelizedSpriteRenderer {

    private final ShaderProgram shaderProgram;
    private final TextureAtlas textureAtlas;

    // Cache for voxel meshes to avoid regenerating them every frame
    private final Map<ItemType, VoxelMesh> meshCache = new HashMap<>();

    // Cache for voxelization results (palette + voxel data)
    private final Map<ItemType, SpriteVoxelizer.VoxelizationResult> voxelizationCache = new HashMap<>();

    // Hardcoded base translation (updated from debug output final values)
    private static final Vector3f BASE_TRANSLATION = new Vector3f(0.3f, -1.1f, -0.1f);

    // Adjustable translation offset (reset to 0,0,0)
    private static Vector3f translationAdjustment = new Vector3f(0.0f, 0.0f, 0.0f);

    // Hardcoded base rotation (updated from debug output final values)
    private static final Vector3f BASE_ROTATION = new Vector3f(-23.1f, -38.1f, 21.9f); // degrees (X, Y, Z)

    // Adjustable rotation offset (reset to 0,0,0)
    private static Vector3f rotationAdjustment = new Vector3f(0.0f, 0.0f, 0.0f); // degrees (X, Y, Z)

    // Hardcoded base scale (updated from debug output final values)
    private static final float BASE_SCALE = 4.0f;

    // Adjustable scale multiplier (reset to 1.0 = no change)
    private static float scaleAdjustment = 1.0f;

    /**
     * Creates a new voxelized sprite renderer.
     *
     * @param shaderProgram The shader program to use for rendering
     * @param textureAtlas The texture atlas (for consistent texture binding)
     */
    public VoxelizedSpriteRenderer(ShaderProgram shaderProgram, TextureAtlas textureAtlas) {
        this.shaderProgram = shaderProgram;
        this.textureAtlas = textureAtlas;
    }

    /**
     * Renders a voxelized sprite for the given item type.
     *
     * @param itemType The item type to render
     */
    public void renderVoxelizedSprite(ItemType itemType) {
        if (!SpriteVoxelizer.isVoxelizable(itemType)) {
            System.err.println("Item type " + itemType.getName() + " is not voxelizable");
            return;
        }

        // Get or create voxel mesh
        VoxelMesh mesh = getOrCreateMesh(itemType);
        if (mesh == null || !mesh.isCreated()) {
            System.err.println("Failed to create mesh for " + itemType.getName());
            return;
        }

        // Set up shader uniforms
        setupShaderUniforms();

        // Apply sprite transformation (2x scale, -40° diagonal rotation)
        Matrix4f originalTransform = getCurrentModelMatrix();
        Matrix4f spriteTransform = createSpriteTransform();
        applyTransformMatrix(spriteTransform);

        // Set up OpenGL state for voxel rendering
        setupRenderState();

        // Bind texture atlas (though we won't use it in solid color mode)
        bindColorPaletteTexture(itemType);

        // Render each voxel with its individual color
        renderVoxelsWithColors(itemType, mesh);

        // Restore transformation matrix
        applyTransformMatrix(originalTransform);

        // Restore OpenGL state
        restoreRenderState();
    }

    /**
     * Gets or creates a voxel mesh for the given item type.
     */
    private VoxelMesh getOrCreateMesh(ItemType itemType) {
        if (meshCache.containsKey(itemType)) {
            return meshCache.get(itemType);
        }

        // Generate voxelization result if not cached
        SpriteVoxelizer.VoxelizationResult result;
        if (voxelizationCache.containsKey(itemType)) {
            result = voxelizationCache.get(itemType);
            // System.out.println("Using cached voxelization for " + itemType.getName()); // Debug only
        } else {
            result = SpriteVoxelizer.voxelizeSpriteWithPalette(itemType);
            voxelizationCache.put(itemType, result);
            // System.out.println("Generated voxelization for " + itemType.getName() + ": " + result.getVoxels().size() + " voxels, " + result.getColorPalette().getColorCount() + " colors"); // Debug only
        }

        if (!result.isValid()) {
            System.err.println("No valid voxelization data available for " + itemType.getName() + " - cannot create mesh");
            return null;
        }

        // Create mesh from voxel data
        VoxelMesh mesh = new VoxelMesh();
        mesh.createMesh(result.getVoxels(), SpriteVoxelizer.getDefaultVoxelSize());
        meshCache.put(itemType, mesh);

        // System.out.println("Created voxel mesh for " + itemType.getName() + " with " + mesh.getIndexCount() + " indices"); // Debug only
        return mesh;
    }

    /**
     * Sets up shader uniforms for voxel rendering.
     */
    private void setupShaderUniforms() {
        // Enable solid color mode since we'll be rendering each voxel with its own color
        shaderProgram.setUniform("u_useSolidColor", true);
        shaderProgram.setUniform("u_isText", false);
        shaderProgram.setUniform("u_transformUVsForItem", false);

        // Enable UI element mode to disable water waves for held items
        shaderProgram.setUniform("u_isUIElement", true);

        // Color will be set per voxel during rendering
    }

    /**
     * Sets up OpenGL rendering state for voxel sprites.
     */
    private void setupRenderState() {
        // Enable blending for transparency
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Enable depth testing for proper 3D rendering
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_LESS);

        // Enable back face culling for performance (we generated proper normals)
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glFrontFace(GL11.GL_CCW);
    }

    /**
     * Renders each voxel with its individual color using solid color mode.
     */
    private void renderVoxelsWithColors(ItemType itemType, VoxelMesh mesh) {
        SpriteVoxelizer.VoxelizationResult result = voxelizationCache.get(itemType);
        if (result == null || result.getVoxels().isEmpty()) {
            return;
        }

        mesh.bind();

        List<VoxelData> voxels = result.getVoxels();
        int facesPerVoxel = 6;
        int indicesPerFace = 6;

        // Render each voxel with its own color
        for (int i = 0; i < voxels.size(); i++) {
            VoxelData voxel = voxels.get(i);

            // Convert RGBA to normalized color values
            int rgba = voxel.getOriginalRGBA();
            float red = ((rgba >> 16) & 0xFF) / 255.0f;
            float green = ((rgba >> 8) & 0xFF) / 255.0f;
            float blue = (rgba & 0xFF) / 255.0f;
            float alpha = ((rgba >> 24) & 0xFF) / 255.0f;

            // Set the color uniform for this voxel
            shaderProgram.setUniform("u_color", new Vector4f(red, green, blue, alpha));

            // Render all faces of this voxel
            int startIndex = i * facesPerVoxel * indicesPerFace;
            GL11.glDrawElements(GL11.GL_TRIANGLES, facesPerVoxel * indicesPerFace, GL11.GL_UNSIGNED_INT, startIndex * Integer.BYTES);
        }

        mesh.unbind();
    }

    /**
     * Binds the main texture atlas instead of a separate color palette texture.
     */
    private void bindColorPaletteTexture(ItemType itemType) {
        // Use the main texture atlas instead of a separate 1D palette texture
        // This makes the voxelizer compatible with the existing shader system
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureAtlas.getTextureId());
        shaderProgram.setUniform("texture_sampler", 0);
    }

    /**
     * Creates the transformation matrix for sprite rendering (adjustable scale, adjustable XYZ rotation, adjustable positioning).
     */
    private Matrix4f createSpriteTransform() {
        Matrix4f transform = new Matrix4f();

        // Apply translation to position in hand (base + adjustment)
        Vector3f finalTranslation = new Vector3f(BASE_TRANSLATION).add(translationAdjustment);
        transform.translate(finalTranslation);

        // Apply scale (base * adjustment)
        float finalScale = BASE_SCALE * scaleAdjustment;
        transform.scale(finalScale);

        // Apply rotations around X, Y, Z axes (base + adjustment)
        Vector3f finalRotation = new Vector3f(BASE_ROTATION).add(rotationAdjustment);

        // Apply rotations in XYZ order (Tait-Bryan angles)
        transform.rotateX((float) Math.toRadians(finalRotation.x));
        transform.rotateY((float) Math.toRadians(finalRotation.y));
        transform.rotateZ((float) Math.toRadians(finalRotation.z));

        return transform;
    }

    /**
     * Gets the current model matrix from the shader.
     */
    private Matrix4f getCurrentModelMatrix() {
        // Create identity matrix as placeholder - in real implementation,
        // you'd get this from the shader's current model matrix uniform
        return new Matrix4f();
    }

    /**
     * Applies a transformation matrix to the shader.
     */
    private void applyTransformMatrix(Matrix4f transform) {
        // Apply the transformation matrix to the model matrix uniform
        // Use the standard modelMatrix uniform name
        shaderProgram.setUniform("modelMatrix", transform);
    }

    /**
     * Restores OpenGL state after rendering.
     */
    private void restoreRenderState() {
        // Restore world rendering state
        shaderProgram.setUniform("u_isUIElement", false);

        // Keep blending enabled for UI elements
        // Keep depth testing enabled
        // Keep face culling enabled - this is standard for the game
    }

    /**
     * Preloads voxel data and meshes for all supported items.
     * Useful for reducing hitches during gameplay.
     */
    public void preloadAllVoxelMeshes() {
        System.out.println("Preloading voxel meshes...");

        for (ItemType itemType : ItemType.values()) {
            if (SpriteVoxelizer.isVoxelizable(itemType)) {
                getOrCreateMesh(itemType);
            }
        }

        System.out.println("Preloaded " + meshCache.size() + " voxel meshes");
    }

    /**
     * Gets statistics about the voxel renderer for debugging.
     */
    public String getStatistics() {
        int totalVoxels = 0;
        int totalIndices = 0;
        int totalColors = 0;

        for (Map.Entry<ItemType, SpriteVoxelizer.VoxelizationResult> entry : voxelizationCache.entrySet()) {
            SpriteVoxelizer.VoxelizationResult result = entry.getValue();
            totalVoxels += result.getVoxels().size();
            if (result.getColorPalette() != null) {
                totalColors += result.getColorPalette().getColorCount();
            }
        }

        for (Map.Entry<ItemType, VoxelMesh> entry : meshCache.entrySet()) {
            if (entry.getValue().isCreated()) {
                totalIndices += entry.getValue().getIndexCount();
            }
        }

        return String.format("VoxelizedSpriteRenderer: %d items cached, %d total voxels, %d total colors, %d total indices",
            meshCache.size(), totalVoxels, totalColors, totalIndices);
    }

    /**
     * Checks if an item type has a cached mesh.
     */
    public boolean hasCachedMesh(ItemType itemType) {
        return meshCache.containsKey(itemType) && meshCache.get(itemType).isCreated();
    }

    /**
     * Clears all cached meshes, palettes, and voxel data.
     * Useful for resource management or when switching render modes.
     */
    public void clearCache() {
        // Clean up OpenGL resources
        for (VoxelMesh mesh : meshCache.values()) {
            mesh.cleanup();
        }

        // Clean up color palette textures
        for (SpriteVoxelizer.VoxelizationResult result : voxelizationCache.values()) {
            if (result.getColorPalette() != null) {
                result.getColorPalette().cleanup();
            }
        }

        meshCache.clear();
        voxelizationCache.clear();
        SpriteVoxelizer.clearCache();

        System.out.println("Cleared voxel renderer cache");
    }

    /**
     * Tests the voxelized rendering system by preloading and reporting on all items.
     * This can be called during game initialization to verify everything works.
     */
    public void testVoxelizedRendering() {
        System.out.println("=== Voxelized Sprite Renderer Test ===");

        // Test sprite voxelization first
        SpriteVoxelizer.testVoxelization();

        // Preload all meshes and report results
        preloadAllVoxelMeshes();

        // Print final statistics
        System.out.println(getStatistics());

        System.out.println("=== Voxelized Renderer Test Complete ===");
    }

    /**
     * Adjusts the translation offset for voxel positioning.
     *
     * @param x X-axis adjustment
     * @param y Y-axis adjustment
     * @param z Z-axis adjustment
     */
    public static void adjustTranslation(float x, float y, float z) {
        translationAdjustment.set(x, y, z);
    }

    /**
     * Adjusts translation, rotation, and scale for voxel positioning.
     *
     * @param x X-axis translation adjustment
     * @param y Y-axis translation adjustment
     * @param z Z-axis translation adjustment
     * @param rotX X-axis rotation adjustment in degrees
     * @param rotY Y-axis rotation adjustment in degrees
     * @param rotZ Z-axis rotation adjustment in degrees
     * @param scale Scale multiplier (1.0 = base scale, 0.5 = half size, 2.0 = double size)
     */
    public static void adjustTransform(float x, float y, float z, float rotX, float rotY, float rotZ, float scale) {
        translationAdjustment.set(x, y, z);
        rotationAdjustment.set(rotX, rotY, rotZ);
        scaleAdjustment = scale;
    }

    /**
     * Adjusts both translation and rotation for voxel positioning (no scale change).
     *
     * @param x X-axis translation adjustment
     * @param y Y-axis translation adjustment
     * @param z Z-axis translation adjustment
     * @param rotX X-axis rotation adjustment in degrees
     * @param rotY Y-axis rotation adjustment in degrees
     * @param rotZ Z-axis rotation adjustment in degrees
     */
    public static void adjustTransformNoScale(float x, float y, float z, float rotX, float rotY, float rotZ) {
        translationAdjustment.set(x, y, z);
        rotationAdjustment.set(rotX, rotY, rotZ);
    }

    /**
     * Adjusts both translation and single-axis rotation for voxel positioning (backward compatibility).
     *
     * @param x X-axis translation adjustment
     * @param y Y-axis translation adjustment
     * @param z Z-axis translation adjustment
     * @param rotation Single rotation adjustment applied to Y-axis in degrees
     */
    public static void adjustTransformSingleRotation(float x, float y, float z, float rotation) {
        translationAdjustment.set(x, y, z);
        rotationAdjustment.set(0.0f, rotation, 0.0f);
    }

    /**
     * Gets the current translation adjustment values.
     *
     * @return Copy of the current translation adjustment
     */
    public static Vector3f getTranslationAdjustment() {
        return new Vector3f(translationAdjustment);
    }

    /**
     * Gets the base translation values.
     *
     * @return Copy of the base translation
     */
    public static Vector3f getBaseTranslation() {
        return new Vector3f(BASE_TRANSLATION);
    }

    /**
     * Gets the final translation (base + adjustment).
     *
     * @return Copy of the final translation
     */
    public static Vector3f getFinalTranslation() {
        return new Vector3f(BASE_TRANSLATION).add(translationAdjustment);
    }

    /**
     * Gets the current rotation adjustment values.
     *
     * @return Copy of current rotation adjustment (X, Y, Z degrees)
     */
    public static Vector3f getRotationAdjustment() {
        return new Vector3f(rotationAdjustment);
    }

    /**
     * Gets the base rotation angles.
     *
     * @return Copy of base rotation (X, Y, Z degrees)
     */
    public static Vector3f getBaseRotation() {
        return new Vector3f(BASE_ROTATION);
    }

    /**
     * Gets the final rotation angles (base + adjustment).
     *
     * @return Copy of final rotation (X, Y, Z degrees)
     */
    public static Vector3f getFinalRotation() {
        return new Vector3f(BASE_ROTATION).add(rotationAdjustment);
    }

    /**
     * Gets the current scale adjustment value.
     *
     * @return Current scale multiplier
     */
    public static float getScaleAdjustment() {
        return scaleAdjustment;
    }

    /**
     * Gets the base scale value.
     *
     * @return Base scale multiplier
     */
    public static float getBaseScale() {
        return BASE_SCALE;
    }

    /**
     * Gets the final scale (base * adjustment).
     *
     * @return Final scale multiplier
     */
    public static float getFinalScale() {
        return BASE_SCALE * scaleAdjustment;
    }

    /**
     * Cleans up all OpenGL resources.
     */
    public void cleanup() {
        clearCache();
    }
}