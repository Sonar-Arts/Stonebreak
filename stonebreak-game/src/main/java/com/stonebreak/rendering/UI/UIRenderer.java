package com.stonebreak.rendering.UI;

import java.nio.FloatBuffer;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.items.Item;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.rendering.UI.components.MCrosshairRenderer;
import com.stonebreak.ui.chat.SkijaChatRenderer;
import com.stonebreak.rendering.UI.components.OpenGLQuadRenderer;
import com.stonebreak.rendering.UI.components.HotbarRenderer;
import com.stonebreak.rendering.UI.menus.BlockIconRenderer;
import com.stonebreak.rendering.UI.menus.ItemIconRenderer;

import static org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES;
import static org.lwjgl.nanovg.NanoVGGL3.nvgCreate;
import static org.lwjgl.nanovg.NanoVGGL3.nvgDelete;
import static org.lwjgl.nanovg.NanoVG.nvgBeginFrame;
import static org.lwjgl.nanovg.NanoVG.nvgEndFrame;

/**
 * UIRenderer acts as a controller that delegates rendering tasks to specialized renderers.
 * This follows the controller pattern where the main renderer orchestrates calls to
 * smaller, focused renderers for different UI components.
 */
public class UIRenderer {
    private long vg;
    
    // Specialized renderers
    private SkijaChatRenderer skijaChatRenderer;
    private ItemIconRenderer itemIconRenderer;
    private BlockIconRenderer blockIconRenderer;
    private MCrosshairRenderer mCrosshairRenderer;
    private OpenGLQuadRenderer openGLQuadRenderer;
    private HotbarRenderer hotbarRenderer;
    
    public UIRenderer() {
        openGLQuadRenderer = new OpenGLQuadRenderer();
    }
    
    public void init() {
        vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
        if (vg == 0) {
            throw new RuntimeException("Could not init NanoVG.");
        }

        // Initialize specialized renderers that still need NanoVG
        hotbarRenderer = new HotbarRenderer(vg);
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
    public void initializeBlockIconRenderer(BlockRenderer blockRenderer, int windowHeight) {
        this.blockIconRenderer = new BlockIconRenderer(blockRenderer, this, windowHeight);
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
    
    public void beginFrame(int width, int height, float pixelRatio) {
        // Store window dimensions for OpenGL quad rendering
        openGLQuadRenderer.setWindowDimensions(width, height);
        
        if (vg != 0) {
            nvgBeginFrame(vg, width, height, pixelRatio);
        }
    }
    
    public void endFrame() {
        if (vg != 0) {
            nvgEndFrame(vg);
        }
    }
    
    // ===== Chat Rendering Delegation =====
    
    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
        if (skijaChatRenderer != null) {
            skijaChatRenderer.render(chatSystem, windowWidth, windowHeight);
        }
    }
    
    // ===== Item Icon Rendering Delegation =====
    
    public void renderItemIcon(float x, float y, float w, float h, Item item, TextureAtlas textureAtlas) {
        itemIconRenderer.renderItemIcon(x, y, w, h, item, textureAtlas);
    }
    
    public void renderItemIcon(float x, float y, float w, float h, int blockTypeId, TextureAtlas textureAtlas) {
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
                                     TextureAtlas textureAtlas,
                                     FloatBuffer projectionMatrixBuffer,
                                     FloatBuffer viewMatrixBuffer) {
        openGLQuadRenderer.drawFlat2DItemInSlot(shaderProgram, type, screenSlotX, screenSlotY, screenSlotWidth, screenSlotHeight, textureAtlas, projectionMatrixBuffer, viewMatrixBuffer);
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
                                int screenSlotWidth, int screenSlotHeight, TextureAtlas textureAtlas) {
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
                                int screenSlotWidth, int screenSlotHeight, TextureAtlas textureAtlas, boolean isDraggedItem) {
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
    
    // ===== Utility Methods =====
    
    public long getVG() {
        return vg;
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
    
    // ===== Hotbar Rendering Delegation =====
    
    /**
     * Renders the complete hotbar (background, slots, items, tooltips).
     */
    public void renderHotbar(com.stonebreak.ui.HotbarScreen hotbarScreen, int screenWidth, int screenHeight, 
                           TextureAtlas textureAtlas, ShaderProgram shaderProgram) {
        if (hotbarRenderer != null) {
            hotbarRenderer.renderHotbar(hotbarScreen, screenWidth, screenHeight, 
                                      textureAtlas, this, shaderProgram);
        }
    }
    
    /**
     * Renders only the hotbar tooltip (for layered rendering).
     */
    public void renderHotbarTooltip(com.stonebreak.ui.HotbarScreen hotbarScreen, int screenWidth, int screenHeight) {
        if (hotbarRenderer != null) {
            hotbarRenderer.renderHotbarTooltip(hotbarScreen, screenWidth, screenHeight);
        }
    }
    
    /**
     * Draws a translucent rounded panel for debug HUD groups.
     * Uses NanoVG so it composites correctly with the existing text overlay.
     */
    public void drawDebugPanel(float x, float y, float width, float height) {
        if (vg == 0) return;
        org.lwjgl.nanovg.NVGColor fill = org.lwjgl.nanovg.NVGColor.create();
        org.lwjgl.nanovg.NVGColor border = org.lwjgl.nanovg.NVGColor.create();
        org.lwjgl.nanovg.NanoVG.nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 160, fill);
        org.lwjgl.nanovg.NanoVG.nvgRGBA((byte) 255, (byte) 255, (byte) 255, (byte) 60, border);

        org.lwjgl.nanovg.NanoVG.nvgBeginPath(vg);
        org.lwjgl.nanovg.NanoVG.nvgRoundedRect(vg, x, y, width, height, 6f);
        org.lwjgl.nanovg.NanoVG.nvgFillColor(vg, fill);
        org.lwjgl.nanovg.NanoVG.nvgFill(vg);

        org.lwjgl.nanovg.NanoVG.nvgBeginPath(vg);
        org.lwjgl.nanovg.NanoVG.nvgRoundedRect(vg, x + 0.5f, y + 0.5f, width - 1f, height - 1f, 6f);
        org.lwjgl.nanovg.NanoVG.nvgStrokeColor(vg, border);
        org.lwjgl.nanovg.NanoVG.nvgStrokeWidth(vg, 1f);
        org.lwjgl.nanovg.NanoVG.nvgStroke(vg);
    }

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

        // Cleanup hotbar renderer
        if (hotbarRenderer != null) {
            hotbarRenderer.cleanup();
        }

        if (skijaChatRenderer != null) {
            skijaChatRenderer.dispose();
            skijaChatRenderer = null;
        }

        if (vg != 0) {
            try {
                nvgDelete(vg);
            } catch (Exception e) {
                System.err.println("Warning: Error during UIRenderer cleanup: " + e.getMessage());
            } finally {
                vg = 0;
            }
        }
    }
}