package com.stonebreak.rendering.UI;

import java.nio.FloatBuffer;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.ui.chat.ChatSystem;
import com.stonebreak.items.Item;
import com.stonebreak.rendering.models.blocks.BlockRenderer;
import com.stonebreak.rendering.shaders.ShaderProgram;
import com.stonebreak.rendering.textures.TextureAtlas;
import com.stonebreak.rendering.UI.components.ChatRenderer;
import com.stonebreak.rendering.UI.components.CrosshairRenderer;
import com.stonebreak.rendering.UI.components.OpenGLQuadRenderer;
import com.stonebreak.rendering.UI.components.HotbarRenderer;
import com.stonebreak.rendering.UI.menus.BlockIconRenderer;
import com.stonebreak.rendering.UI.menus.ItemIconRenderer;
import com.stonebreak.rendering.UI.menus.MenuRenderer;
import com.stonebreak.rendering.UI.menus.VolumeSliderRenderer;

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
    private MenuRenderer menuRenderer;
    private ChatRenderer chatRenderer;
    private VolumeSliderRenderer volumeSliderRenderer;
    private ItemIconRenderer itemIconRenderer;
    private BlockIconRenderer blockIconRenderer;
    private CrosshairRenderer crosshairRenderer;
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
        
        // Initialize specialized renderers
        menuRenderer = new MenuRenderer(vg);
        chatRenderer = new ChatRenderer(vg);
        volumeSliderRenderer = new VolumeSliderRenderer(vg);
        itemIconRenderer = new ItemIconRenderer(vg);
        crosshairRenderer = new CrosshairRenderer(vg);
        hotbarRenderer = new HotbarRenderer(vg);
        openGLQuadRenderer.initialize();
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
    
    // ===== Menu Rendering Delegation =====
    
    public void renderMainMenu(int windowWidth, int windowHeight) {
        menuRenderer.renderMainMenu(windowWidth, windowHeight);
    }
    
    public void renderPauseMenu(int windowWidth, int windowHeight, boolean isQuitButtonHovered, boolean isSettingsButtonHovered) {
        menuRenderer.renderPauseMenu(windowWidth, windowHeight, isQuitButtonHovered, isSettingsButtonHovered);
    }
    
    public void renderSettingsMenu(int windowWidth, int windowHeight) {
        menuRenderer.renderSettingsMenu(windowWidth, windowHeight);
    }
    
    public void drawButton(String text, float x, float y, float w, float h, boolean highlighted) {
        menuRenderer.drawButton(text, x, y, w, h, highlighted);
    }
    
    public void drawDropdownButton(String text, float x, float y, float w, float h, boolean highlighted, boolean isOpen) {
        menuRenderer.drawDropdownButton(text, x, y, w, h, highlighted, isOpen);
    }
    
    public void drawDropdownMenu(String[] options, int selectedIndex, float x, float y, float w, float itemHeight) {
        menuRenderer.drawDropdownMenu(options, selectedIndex, x, y, w, itemHeight);
    }
    
    public boolean isButtonClicked(float mouseX, float mouseY, float buttonX, float buttonY, float buttonW, float buttonH) {
        return menuRenderer.isButtonClicked(mouseX, mouseY, buttonX, buttonY, buttonW, buttonH);
    }
    
    public boolean isPauseResumeClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return menuRenderer.isPauseResumeClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    public boolean isPauseSettingsClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return menuRenderer.isPauseSettingsClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    public boolean isPauseQuitClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return menuRenderer.isPauseQuitClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void renderDeathMenu(int windowWidth, int windowHeight, boolean isRespawnButtonHovered) {
        menuRenderer.renderDeathMenu(windowWidth, windowHeight, isRespawnButtonHovered);
    }

    public boolean isDeathRespawnClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return menuRenderer.isDeathRespawnClicked(mouseX, mouseY, windowWidth, windowHeight);
    }
    
    // ===== Chat Rendering Delegation =====
    
    public void renderChat(ChatSystem chatSystem, int windowWidth, int windowHeight) {
        chatRenderer.renderChat(chatSystem, windowWidth, windowHeight);
    }
    
    // ===== Volume Slider Rendering Delegation =====
    
    public void drawVolumeSlider(String label, float centerX, float centerY, float sliderWidth, float sliderHeight, float value, boolean highlighted) {
        volumeSliderRenderer.drawVolumeSlider(label, centerX, centerY, sliderWidth, sliderHeight, value, highlighted);
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
    
    // ===== Text Rendering Delegation =====
    
    public float getTextWidth(String text, float fontSize, String fontFaceName) {
        return menuRenderer.getTextWidth(text, fontSize, fontFaceName);
    }
    
    public void drawText(String text, float x, float y, String fontFaceName, float fontSize, float r, float g, float b, float a) {
        menuRenderer.drawText(text, x, y, fontFaceName, fontSize, r, g, b, a);
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
        if (crosshairRenderer != null) {
            crosshairRenderer.renderCrosshair(windowWidth, windowHeight);
        }
    }
    
    /**
     * Gets the crosshair renderer for configuration.
     * @return CrosshairRenderer instance for customization
     */
    public CrosshairRenderer getCrosshairRenderer() {
        return crosshairRenderer;
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
     * Draws a horizontal separator line for UI organization using NanoVG.
     * @param centerX Center X position
     * @param y Y position for the line
     * @param width Width of the separator line
     */
    public void drawSeparator(float centerX, float y, float width) {
        if (menuRenderer != null) {
            menuRenderer.drawSeparatorLine(centerX, y, width);
        }
    }
    
    // Helper method for backward compatibility - delegates to MenuRenderer
    public org.lwjgl.nanovg.NVGColor nvgRGBA(int r, int g, int b, int a, org.lwjgl.nanovg.NVGColor color) {
        return menuRenderer.nvgRGBA(r, g, b, a, color);
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
     * Get the chat renderer for chat-specific interactions.
     */
    public ChatRenderer getChatRenderer() {
        return chatRenderer;
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