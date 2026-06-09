package com.stonebreak.rendering.UI;

import java.nio.FloatBuffer;
import com.stonebreak.rendering.textures.BlockTextureArray;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.items.Item;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.components.MCrosshairRenderer;
import com.stonebreak.ui.chat.SkijaChatRenderer;
import com.stonebreak.rendering.UI.components.OpenGLQuadRenderer;
import com.stonebreak.rendering.UI.menus.BlockIconRenderer;
import com.stonebreak.rendering.UI.menus.ItemIconRenderer;

/**
 * UIRenderer acts as a controller that delegates rendering tasks to specialized renderers.
 * This follows the controller pattern where the main renderer orchestrates calls to
 * smaller, focused renderers for different UI components.
 */
public class UIRenderer {
    // Specialized renderers
    private SkijaChatRenderer skijaChatRenderer;
    private ItemIconRenderer itemIconRenderer;
    private BlockIconRenderer blockIconRenderer;
    private MCrosshairRenderer mCrosshairRenderer;
    private OpenGLQuadRenderer openGLQuadRenderer;

    public UIRenderer() {
        openGLQuadRenderer = new OpenGLQuadRenderer();
    }

    public void init() {
        openGLQuadRenderer.initialize();
        // ItemIconRenderer is now Skija-backed — initialized in initializeSkijaRenderers()
    }

    /**
     * Initializes the depth curtain renderer with the necessary parameters from the main renderer.
     * This should be called after the main renderer has been set up.
     * @param shaderProgram The main shader program
     * @param windowWidth Current window width
     * @param windowHeight Current window height
     * @param projectionMatrix The 3D projection matrix
     */
    public void initializeDepthCurtainRenderer(ShaderProgram shaderProgram,
                                               int windowWidth, int windowHeight,
                                               org.joml.Matrix4f projectionMatrix) {
        openGLQuadRenderer.initializeDepthCurtainRenderer(shaderProgram, windowWidth, windowHeight, projectionMatrix);
    }

    /**
     * Initializes the block icon renderer with the necessary dependencies.
     * This should be called after the main renderer and block renderer have been set up.
     * @param blockRenderer The block renderer for creating block geometry
     * @param windowHeight Current window height for viewport calculations
     */
    public void initializeBlockIconRenderer(BlockRenderer blockRenderer,
                                            com.stonebreak.rendering.textures.BlockTextureArray blockTextureArray,
                                            com.stonebreak.rendering.sbo.SBOHandMeshRegistry sboHandMeshRegistry,
                                            int windowHeight) {
        this.blockIconRenderer = new BlockIconRenderer(blockRenderer, blockTextureArray, sboHandMeshRegistry, this, windowHeight);
    }

    /**
     * Wires the Skija backend into UIRenderer so renderers that draw via Skija
     * (chat panel, crosshair, item icons) can be constructed. Called by Renderer after
     * the backend is initialized — UIRenderer.init() runs before the backend
     * exists, so chat construction happens here instead.
     */
    public void initializeSkijaRenderers(SkijaUIBackend skijaBackend) {
        this.skijaChatRenderer = new SkijaChatRenderer(skijaBackend);
        this.mCrosshairRenderer = new MCrosshairRenderer(skijaBackend);
        this.itemIconRenderer = new ItemIconRenderer(skijaBackend);
    }

    // ===== Chat Rendering Delegation =====

    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
        if (skijaChatRenderer != null) {
            skijaChatRenderer.render(chatSystem, windowWidth, windowHeight);
        }
    }

    // ===== Item Icon Rendering Delegation =====

    public void renderItemIcon(float x, float y, float w, float h, Item item, BlockTextureArray textureAtlas) {
        itemIconRenderer.renderItemIcon(x, y, w, h, item, textureAtlas);
    }

    public void renderItemIcon(float x, float y, float w, float h, int blockTypeId, BlockTextureArray textureAtlas) {
        itemIconRenderer.renderItemIcon(x, y, w, h, blockTypeId, textureAtlas);
    }

    public void renderQuad(float x, float y, float w, float h, float r, float g, float b, float a) {
        itemIconRenderer.renderQuad(x, y, w, h, r, g, b, a);
    }

    public void renderOutline(float x, float y, float w, float h, float strokeWidth, float[] color) {
        itemIconRenderer.renderOutline(x, y, w, h, strokeWidth, color);
    }

    // ===== OpenGL Quad Rendering Delegation =====

    public void drawQuad(ShaderProgram shaderProgram, int x, int y, int width, int height, int r, int g, int b, int a) {
        openGLQuadRenderer.drawQuad(shaderProgram, x, y, width, height, r, g, b, a);
    }

    public void drawTexturedQuadUI(ShaderProgram shaderProgram, int x, int y, int width, int height, int textureId, float u1, float v1, float u2, float v2) {
        openGLQuadRenderer.drawTexturedQuadUI(shaderProgram, x, y, width, height, textureId, u1, v1, u2, v2);
    }

    public void drawFlat2DItemInSlot(ShaderProgram shaderProgram,
                                     com.stonebreak.blocks.BlockType type,
                                     int screenSlotX, int screenSlotY,
                                     int screenSlotWidth, int screenSlotHeight,
                                     com.stonebreak.rendering.textures.BlockTextureArray blockTextureArray,
                                     FloatBuffer projectionMatrixBuffer,
                                     FloatBuffer viewMatrixBuffer) {
        openGLQuadRenderer.drawFlat2DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, blockTextureArray, projectionMatrixBuffer, viewMatrixBuffer);
    }

    /**
     * Renders a 3D block icon in the specified slot area.
     * Handles both 3D cube blocks and flat 2D flower blocks.
     *
     * @param shaderProgram The shader program to use for rendering
     * @param type The block type to render
     * @param screenSlotX X coordinate of the slot
     * @param screenSlotY Y coordinate of the slot
     * @param screenSlotWidth Width of the slot
     * @param screenSlotHeight Height of the slot
     * @param textureAtlas The texture atlas containing block textures
     */
    public void draw3DItemInSlot(ShaderProgram shaderProgram, BlockType type, int screenSlotX, int screenSlotY,
                                int screenSlotWidth, int screenSlotHeight, BlockTextureArray textureAtlas) {
        if (blockIconRenderer == null) {
            throw new IllegalStateException("BlockIconRenderer not initialized. Call initializeBlockIconRenderer() first.");
        }
        blockIconRenderer.draw3DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas);
    }

    /**
     * Renders a 3D block icon in the specified slot area with dragged item support.
     * Delegates to BlockIconRenderer.
     *
     * @param shaderProgram The shader program to use for rendering
     * @param type The block type to render
     * @param screenSlotX X coordinate of the slot
     * @param screenSlotY Y coordinate of the slot
     * @param screenSlotWidth Width of the slot
     * @param screenSlotHeight Height of the slot
     * @param textureAtlas The texture atlas containing block textures
     * @param isDraggedItem If true, renders closer to camera to avoid z-fighting
     */
    public void draw3DItemInSlot(ShaderProgram shaderProgram, BlockType type, int screenSlotX, int screenSlotY,
                                int screenSlotWidth, int screenSlotHeight, BlockTextureArray textureAtlas, boolean isDraggedItem) {
        if (blockIconRenderer == null) {
            throw new IllegalStateException("BlockIconRenderer not initialized. Call initializeBlockIconRenderer() first.");
        }
        blockIconRenderer.draw3DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas, isDraggedItem);
    }

    // ===== Depth Curtain Rendering Delegation =====

    public void renderInventoryDepthCurtain() {
        openGLQuadRenderer.renderInventoryDepthCurtain();
    }

    public void renderHotbarDepthCurtain() {
        openGLQuadRenderer.renderHotbarDepthCurtain();
    }

    public void renderPauseMenuDepthCurtain() {
        openGLQuadRenderer.renderPauseMenuDepthCurtain();
    }

    public void renderRecipeBookDepthCurtain() {
        openGLQuadRenderer.renderRecipeBookDepthCurtain();
    }

    public void renderWorkbenchDepthCurtain() {
        openGLQuadRenderer.renderWorkbenchDepthCurtain();
    }

    // ===== Crosshair Rendering Delegation =====

    /**
     * Renders the crosshair at the center of the screen.
     * @param windowWidth Current window width
     * @param windowHeight Current window height
     */
    public void renderCrosshair(int windowWidth, int windowHeight) {
        if (mCrosshairRenderer != null) {
            mCrosshairRenderer.renderCrosshair(windowWidth, windowHeight);
        }
    }

    /**
     * Gets the Skija/MasonryUI crosshair renderer for configuration.
     * @return MCrosshairRenderer instance for customization
     */
    public MCrosshairRenderer getMCrosshairRenderer() {
        return mCrosshairRenderer;
    }

    // ===== Getter Methods =====

    /**
     * Get the block icon renderer for rendering block icons in tooltip layer.
     */
    public BlockIconRenderer getBlockIconRenderer() {
        return blockIconRenderer;
    }

    /**
     * Get the item icon renderer for rendering item icons in tooltip layer.
     */
    public ItemIconRenderer getItemIconRenderer() {
        return itemIconRenderer;
    }

    /**
     * Get the Skija/MasonryUI chat renderer for chat-specific interactions
     * (scrollbar drag, command click hit-testing, hover updates).
     */
    public SkijaChatRenderer getSkijaChatRenderer() {
        return skijaChatRenderer;
    }

    public void cleanup() {
        // Cleanup OpenGL quad renderer
        if (openGLQuadRenderer != null) {
            openGLQuadRenderer.cleanup();
        }

        if (skijaChatRenderer != null) {
            skijaChatRenderer.dispose();
            skijaChatRenderer = null;
        }
    }
}
